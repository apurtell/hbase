------------------------------ MODULE TRSP ------------------------------------
(*
 * TransitionRegionStateProcedure actions for the HBase AssignmentManager.
 *
 * Contains all TRSP actions: ASSIGN path (create, get-candidate,
 * dispatch-open, confirm-opened), failure path (dispatch-fail,
 * dispatch-fail-close, handle-failed-open, give-up-open),
 * UNASSIGN path (create-unassign), MOVE path (create-move),
 * REOPEN path (create-reopen), CLOSE path (dispatch-close,
 * confirm-closed), and crash recovery (server-crashed).
 *
 * The TRSPState set maps 1:1 to the RegionStateTransitionState protobuf
 * enum (MasterProcedure.proto):
 *
 *   TRSPState              | Protobuf enum value
 *   -----------------------+---------------------------------------------
 *   "GET_ASSIGN_CANDIDATE" | REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE (=1)
 *   "OPEN"                 | REGION_STATE_TRANSITION_OPEN (=2)
 *   "CONFIRM_OPENED"       | REGION_STATE_TRANSITION_CONFIRM_OPENED (=3)
 *   "CLOSE"                | REGION_STATE_TRANSITION_CLOSE (=4)
 *   "CONFIRM_CLOSED"       | REGION_STATE_TRANSITION_CONFIRM_CLOSED (=5)
 *
 * RegionRemoteProcedureBase child procedure absorption
 * ----------------------------------------------------
 * The implementation uses child procedures (OpenRegionProcedure,
 * CloseRegionProcedure) that extend RegionRemoteProcedureBase and
 * have their own 4-state machine (RegionRemoteProcedureBaseState in
 * MasterProcedure.proto).  The model merges these into TRSP actions:
 *
 *   RRPB state             | Absorbed by TLA+ action(s)
 *   -----------------------+---------------------------------------------
 *   DISPATCH (=1)          | TRSPDispatchOpen, TRSPDispatchClose
 *                          |   RPC dispatch is atomic within the TRSP step.
 *   REPORT_SUCCEED (=2)    | TRSPConfirmOpened, TRSPConfirmClosed
 *                          |   Will be decomposed to model the
 *                          |   crash window between in-memory update and
 *                          |   meta persist (Pattern C inconsistency).
 *   DISPATCH_FAIL (=3)     | DispatchFail, DispatchFailClose
 *                          |   RPC failure resets TRSP to retry.
 *   SERVER_CRASH (=4)      | TRSPServerCrashed
 *                          |   Server crash converts TRSP to ASSIGN.
 *)
EXTENDS Types

\* All shared variables are declared as VARIABLE parameters so that
\* the root module can substitute its own variables via INSTANCE.
VARIABLE regionState,
         metaTable,
         dispatchedOps,
         pendingReports,
         rsOnlineRegions,
         serverState,
         scpState,
         scpRegions,
         walFenced,
         carryingMeta,
         serverRegions,
         procStore,
         masterAlive,
         zkNode,
         availableWorkers,
         suspendedOnMeta,
         blockedOnMeta,
         regionKeyRange,
         parentProc

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == << dispatchedOps, pendingReports >>

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == << rsOnlineRegions >>

\* Shorthand for variables unchanged by TRSP actions (used in UNCHANGED
\* clauses).  Includes SCP state and ZK ephemeral nodes -- neither is
\* modified by any TRSP action.
scpVars ==
  << scpState,
     scpRegions,
     walFenced,
     carryingMeta,
     zkNode,
     regionKeyRange,
     parentProc
  >>

\* Shorthand for master lifecycle variables (used in UNCHANGED clauses).
masterVars == << masterAlive >>

\* Shorthand for server tracking variables (used in UNCHANGED clauses).
serverVars == << serverState, serverRegions >>

\* Shorthand for PEWorker pool variables (used in UNCHANGED clauses).
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>

\* MetaIsAvailable is TRUE when no server is in ASSIGN_META scpState,
\* meaning hbase:meta is online and accessible for read/write.
\* Reuses the existing waitMetaLoaded guard from SCP actions.
MetaIsAvailable == \A s \in Servers: scpState[s] # "ASSIGN_META"

---------------------------------------------------------------------------

(* Actions -- TRSP ASSIGN path *)
\* Create a TRSP ASSIGN procedure for a region eligible for assignment.
\* Pre: region is in a state eligible for assignment AND no procedure
\*      is currently attached.
\* Post: procedure fields set to ASSIGN/GET_ASSIGN_CANDIDATE.  Region
\*       lifecycle state is NOT changed yet -- the TRSP will drive
\*       transitions in subsequent steps.
\*
\* Source: TRSP.assign() creates the procedure;
\*         TRSP.queueAssign() applies the GET_ASSIGN_CANDIDATE
\*         initial state via TRSP.setInitialAndLastState().
TRSPCreate(r) ==
  \* Region is in an assignable state and has no active procedure.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Region must be in a state eligible for assignment.
  /\ regionState[r].state \in
       { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED", "FAILED_OPEN" }
  \* No procedure is currently attached to this region.
  /\ regionState[r].procType = "NONE"
  \* No parent procedure in progress (models ProcedureExecutor region-level locking).
  /\ parentProc[r].type = "NONE"
  \* Don't auto-create ASSIGN for ABNORMALLY_CLOSED regions while any SCP
  \* is actively processing a crash.  In the implementation, SCP owns
  \* crash recovery; no background daemon races to create procedures for
  \* crashed regions during the SCP assign loop.
  /\ \/ regionState[r].state # "ABNORMALLY_CLOSED"
     \/ \A s \in Servers: scpState[s] \in { "NONE", "DONE" }
  \* Initialize embedded ASSIGN procedure at GET_ASSIGN_CANDIDATE step.
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "ASSIGN",
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       NoServer,
       ![r].retries =
       0]
  \* Persist the new procedure to the procedure store.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>

\* Choose a target server for the ASSIGN, MOVE, or REOPEN procedure.
\* Pre: TRSP is in GET_ASSIGN_CANDIDATE state, chosen server is ONLINE,
\*      and no CRASHED server still has r in its rsOnlineRegions (the
\*      zombie window must be closed: RSAbort clears the zombie RS's
\*      in-memory region set, after which a new assignment is safe).
\* Post: targetServer set, TRSP advances to OPEN state.
\*
\* The zombie-window guard (\A zombie: CRASHED => r \notin rsOnlineRegions)
\* is implementation-faithful: the implementation relies on
\* createDestinationServersList() to exclude CRASHED servers from
\* candidates and on RSAbort to close the zombie window.  Once RSAbort
\* fires, rsOnlineRegions[zombie] no longer contains r and
\* TRSPGetCandidate may safely pick a new server.
\*
\* Note: FencingOrder (scpState[s] = "ASSIGN" => walFenced[s])
\* and the walFenced guard on SCPAssignRegion remain unchanged --
\* those faithfully model SCP's own ordering constraint.
\*
\* Source: TRSP.queueAssign() -> AM.queueAssign() -> LoadBalancer;
\*         createDestinationServersList() excludes CRASHED servers;
\*         HRegionServer.abort() (RSAbort) clears rsOnlineRegions.
TRSPGetCandidate(r, s) ==
  \* Procedure is ASSIGN, MOVE, or REOPEN at GET_ASSIGN_CANDIDATE.
  \* Chosen server is ONLINE; no CRASHED server still holds r in
  \* rsOnlineRegions (zombie window must be closed by RSAbort first).
  \* For REOPEN, s may equal regionState[r].location (same server OK).
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be ASSIGN, MOVE, REOPEN, or UNASSIGN
  \* (UNASSIGN reaches GET_ASSIGN_CANDIDATE during two-phase crash
  \* recovery: reopen then re-close).
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
  \* Procedure must be at the candidate selection step.
  /\ regionState[r].procStep = "GET_ASSIGN_CANDIDATE"
  \* Chosen target server must be ONLINE.
  /\ serverState[s] = "ONLINE"
  \* Zombie window must be closed: no CRASHED server still holds r.
  /\ \A sZombie \in Servers:
       serverState[sZombie] = "CRASHED" => r \notin rsOnlineRegions[sZombie]
  \* Record the chosen server and advance the procedure to the OPEN step.
  /\ regionState' =
       [regionState EXCEPT ![r].targetServer = s, ![r].procStep = "OPEN"]
  \* Update persisted procedure with target server and step.
  /\ procStore' = [procStore EXCEPT ![r].step = "OPEN", ![r].targetServer = s]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>

\* Dispatch the open command to the target server via RPC.
\* Pre: TRSP is in OPEN state with a target server.
\* Post: region transitions to OPENING with the target server as
\*       location, meta updated, open command added to
\*       dispatchedOps[targetServer], TRSP advances to CONFIRM_OPENED.
\*
\* Impl states absorbed: REGION_STATE_TRANSITION_OPEN (=2) from the
\*   parent TRSP, plus REGION_REMOTE_PROCEDURE_DISPATCH (=1) from the
\*   child OpenRegionProcedure.  The model treats open-region-state-
\*   transition + child-dispatch as a single atomic step.
\*
\* Source: TRSP.openRegion() calls AM.regionOpening()
\*         to set OPENING state and meta, then creates an
\*         OpenRegionProcedure child; RSProcedureDispatcher
\*         dispatches the RPC via
\*         RSProcedureDispatcher.ExecuteProceduresRemoteCall.run().
TRSPDispatchOpen(r) ==
  \* Procedure is ASSIGN or MOVE, in OPEN step, with a target server.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
  /\ regionState[r].procStep = "OPEN"
  /\ regionState[r].targetServer # NoServer
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
        \* Region is not already suspended waiting for meta.
        /\ r \notin suspendedOnMeta
        \* Region is not already blocking a PEWorker on meta.
        /\ r \notin blockedOnMeta
        \* Branch on meta-write blocking mode.
        /\ IF UseBlockOnMetaWrite = FALSE
           \* Async: suspend procedure, release PEWorker thread.
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
           \* Sync: block PEWorker thread on meta write.
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
        \* No state changes: procedure paused before the write.
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              regionState,
              metaTable,
              procStore
           >>
     \/ \* --- Meta available: dispatch open ---
        /\ MetaIsAvailable
        \* Region is not already suspended waiting for meta.
        /\ r \notin suspendedOnMeta
        \* Region is not already blocking a PEWorker on meta.
        /\ r \notin blockedOnMeta
        \* Transition region to OPENING, record server, advance to CONFIRM_OPENED.
        /\ LET s == regionState[r].targetServer
           IN /\ regionState' =
                   [regionState EXCEPT
                   ![r].state =
                   "OPENING",
                   ![r].location =
                   s,
                   ![r].procStep =
                   "CONFIRM_OPENED"]
              \* Persist the OPENING state and server assignment in META.
              /\ metaTable' =
                   [metaTable EXCEPT
                   ![r] =
                   [ state |-> "OPENING", location |-> s ]]
              \* Enqueue an OPEN command to the target server's dispatched ops.
              /\ dispatchedOps' =
                   [dispatchedOps EXCEPT
                   ![s] =
                   @ \cup { [ type |-> "OPEN", region |-> r ] }]
              \* ServerStateNode tracking: AM.regionOpening() adds r to the
              \* new target server's set.
              \* Source: AM.regionOpening().
              /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \cup { r }]
              \* Update persisted procedure step.
              /\ procStore' = [procStore EXCEPT ![r].step = "CONFIRM_OPENED"]
              \* Pending reports, RS-side state, and server liveness unchanged.
              /\ UNCHANGED << scpVars,
                    rsVars,
                    masterVars,
                    peVars,
                    pendingReports,
                    serverState
                 >>


\* --- Report-succeed for OPEN path ---
\* RS report consumed: in-memory regionState updated to reflect the RS
\* report (OPENED or FAILED_OPEN), procedure persisted to procStore at
\* REPORT_SUCCEED with the transition code recorded.  MetaTable is NOT
\* updated yet -- that happens in TRSPPersistToMetaOpen.
\*
\* This action absorbs the former TRSPConfirmOpened (OPENED path),
\* TRSPHandleFailedOpen, and TRSPGiveUpOpen by uniformly processing
\* all report codes through the REPORT_SUCCEED intermediate step.
\*
\* Pre: ASSIGN/MOVE/REOPEN procedure in CONFIRM_OPENED step, region is
\*      OPENING, and a matching report exists (OPENED or FAILED_OPEN).
\* Post: In-memory state reflects report, procStore updated to
\*       REPORT_SUCCEED with transitionCode.  MetaTable unchanged.
\*
\* Source: RegionRemoteProcedureBase.reportTransition() ->
\*         RRPB REPORT_SUCCEED state: the report has been consumed
\*         and in-memory state updated, but metaTable not yet written.
TRSPReportSucceedOpen(r) ==
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be ASSIGN, MOVE, REOPEN, or UNASSIGN.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
  \* Procedure must be waiting for the open confirmation.
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  \* Region must be in OPENING state (dispatch already happened).
  /\ regionState[r].state = "OPENING"
  \* A matching report exists for this region.
  /\ \E rpt \in pendingReports:
       \* Report must be for region r.
       /\ rpt.region = r
       /\ rpt.code \in { "OPENED", "FAILED_OPEN" }
       \* Report must be from the target server.
       /\ rpt.server = regionState[r].targetServer
       \* Prefer OPENED over FAILED_OPEN: if both exist, only consume OPENED.
       /\ rpt.code = "FAILED_OPEN" =>
            ~\E rpt2 \in pendingReports: rpt2.region = r /\ rpt2.code = "OPENED"
       \* Update in-memory state to reflect the report.
       \* OPENED: transition to OPEN (AM.regionOpenedWithoutPersistingToMeta()).
       \* FAILED_OPEN: no state/location change â faithful to the
       \*   implementation's regionFailedOpen(giveUp=false) which only
       \*   calls removeRegionFromServer() without calling setState()
       \*   or setRegionLocation(null).  Region stays OPENING with
       \*   location intact.  State transitions to FAILED_OPEN only in
       \*   TRSPPersistToMetaOpen give-up branch (regionFailedOpen(giveUp=true)).
       /\ regionState' =
            [regionState EXCEPT
            ![r].state =
            IF rpt.code = "OPENED"
            THEN "OPEN"
            \* AM.regionOpenedWithoutPersistingToMeta()
            ELSE regionState[r].state,
            \* FAILED_OPEN: stays OPENING
            ![r].procStep =
            "REPORT_SUCCEED"]
       \* Consume the report.
       /\ pendingReports' = pendingReports \ { rpt }
       \* Persist procedure at REPORT_SUCCEED with the transition code.
       /\ procStore' =
            [procStore EXCEPT
            ![r].step =
            "REPORT_SUCCEED",
            ![r].transitionCode =
            IF rpt.code = "OPENED" THEN "OPENED" ELSE "FAILED_OPEN"]
       \* ServerStateNode tracking: only for FAILED_OPEN.
       \* AM.regionFailedOpen() calls removeRegionFromServer().
       /\ LET loc == regionState[r].location
          IN serverRegions' =
                IF rpt.code = "FAILED_OPEN" /\ loc # NoServer
                THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                ELSE serverRegions
       \* MetaTable NOT updated: meta persist happens in TRSPPersistToMetaOpen.
       /\ UNCHANGED << scpVars,
             rsVars,
             masterVars,
             peVars,
             metaTable,
             dispatchedOps,
             serverState
          >>

\* --- Persist-to-meta for OPEN path ---
\* Final state persisted to metaTable, procedure completed (or retried).
\*
\* Branches on transitionCode recorded in procStore:
\*   OPENED     -> region marked OPEN in meta, procedure cleared
\*                 (UNASSIGN: cleared; MOVE/REOPEN: advance to GET_ASSIGN_CANDIDATE)
\*   FAILED_OPEN -> either retry (retries < MaxRetries: reset to
\*                  GET_ASSIGN_CANDIDATE) or give up (persist FAILED_OPEN
\*                  to meta, clear procedure)
\*
\* Pre: Procedure at REPORT_SUCCEED with transitionCode OPENED or FAILED_OPEN.
\* Post: MetaTable updated, procedure completed or advanced.
\*
\* Source: RRPB persist-to-meta phase: metaTable write completes the
\*         transition that was started in TRSPReportSucceedOpen.
TRSPPersistToMetaOpen(r) ==
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be ASSIGN, MOVE, REOPEN, or UNASSIGN.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
  \* Procedure must be at the report-succeed (persist-to-meta) step.
  /\ regionState[r].procStep = "REPORT_SUCCEED"
  \* Bind the transition code from the persisted procedure record.
  /\ LET tc == procStore[r].transitionCode
     IN \/ \* --- Meta unavailable: suspend or block ---
           \* When meta is unavailable and the procedure attempts a meta
           \* write, two behaviors are modeled:
           \*   Default (UseBlockOnMetaWrite=FALSE, master/branch-3+):
           \*     Procedure suspends via ProcedureFutureUtil.
           \*     suspendIfNecessary(), releasing the PEWorker thread.
           \*   Branch-2.6 (UseBlockOnMetaWrite=TRUE):
           \*     Synchronous Table.put() blocks the PEWorker thread.
           \*
           \* Source: RegionRemoteProcedureBase.execute() REPORT_SUCCEED
           \*         branch; RegionStateStore.updateRegionLocation().
           /\ ~MetaIsAvailable
           \* Region is not already suspended waiting for meta.
           /\ r \notin suspendedOnMeta
           \* Region is not already blocking a PEWorker on meta.
           /\ r \notin blockedOnMeta
           /\ IF UseBlockOnMetaWrite = FALSE
              THEN \* Async: suspend procedure, release PEWorker.
                   /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                   /\ UNCHANGED << availableWorkers, blockedOnMeta >>
              ELSE \* Sync: block PEWorker thread on meta write.
                   /\ blockedOnMeta' = blockedOnMeta \cup { r }
                   /\ availableWorkers' = availableWorkers - 1
                   /\ UNCHANGED suspendedOnMeta
           \* No state changes: procedure paused before the write.
           /\ UNCHANGED << scpVars,
                 rpcVars,
                 serverVars,
                 rsVars,
                 masterVars,
                 regionState,
                 metaTable,
                 procStore
              >>
        \/ \* --- OPENED branch ---
           \* Meta must be available to persist the state.
           /\ MetaIsAvailable
           \* Region is not suspended waiting for meta.
           /\ r \notin suspendedOnMeta
           \* Region is not blocking a PEWorker on meta.
           /\ r \notin blockedOnMeta
           \* Transition code must be OPENED.
           /\ tc = "OPENED"
           \* In-memory state must already reflect OPEN (from TRSPReportSucceedOpen).
           /\ regionState[r].state = "OPEN"
           \* Persist OPEN state to metaTable.
           /\ metaTable' =
                [metaTable EXCEPT
                ![r] =
                [ state |-> "OPEN", location |-> metaTable[r].location ]]
           \* Branch on procedure type.
           /\ IF regionState[r].procType = "UNASSIGN"
              THEN \* UNASSIGN two-phase recovery: reopen succeeded,
                   \* now continue to CLOSE phase to complete the
                   \* unassignment.  Models confirmOpened() L289-301
                   \* where lastState == CONFIRM_CLOSED triggers
                   \* nextState = CLOSE, returns HAS_MORE_STATE.
                   /\ regionState' =
                        [regionState EXCEPT
                        ![r].procStep =
                        "CLOSE",
                        ![r].targetServer =
                        regionState[r].location]
                   /\ procStore' =
                        [procStore EXCEPT
                        ![r] =
                        NewProcRecord("UNASSIGN", "CLOSE", regionState[r].location, NoTransition)]
              ELSE \* ASSIGN/MOVE/REOPEN: procedure complete.
                   /\ regionState' =
                        [regionState EXCEPT
                        ![r] =
                        [ state |-> "OPEN",
                          location |-> regionState[r].location,
                          procType |-> "NONE",
                          procStep |-> "IDLE",
                          targetServer |-> NoServer,
                          retries |-> 0
                        ]]
                   \* Delete completed procedure from store.
                   /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
           /\ UNCHANGED << scpVars,
                 rpcVars,
                 serverVars,
                 rsVars,
                 masterVars,
                 peVars
              >>
        \/ \* --- FAILED_OPEN, retry branch ---
           \* Meta must be available to persist the state.
           /\ MetaIsAvailable
           \* Region is not suspended waiting for meta.
           /\ r \notin suspendedOnMeta
           \* Region is not blocking a PEWorker on meta.
           /\ r \notin blockedOnMeta
           \* Transition code must be FAILED_OPEN.
           /\ tc = "FAILED_OPEN"
           \* Retries not yet exhausted; eligible for retry.
           /\ regionState[r].retries < MaxRetries
           \* Reset to GET_ASSIGN_CANDIDATE for retry with a new server.
           /\ regionState' =
                [regionState EXCEPT
                ![r].procStep =
                "GET_ASSIGN_CANDIDATE",
                ![r].targetServer =
                NoServer,
                ![r].retries =
                regionState[r].retries + 1]
           \* Update procStore: back to GET_ASSIGN_CANDIDATE.
           /\ procStore' =
                [procStore EXCEPT
                ![r] =
                NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
           \* Clear any stale OPEN commands for r from dispatchedOps.
           /\ dispatchedOps' =
                [t \in Servers |->
                  {cmd \in dispatchedOps[t]:
                    cmd.region # r \/ cmd.type # "OPEN"
                  }
                ]
           /\ UNCHANGED << scpVars,
                 serverVars,
                 rsVars,
                 masterVars,
                 peVars,
                 metaTable,
                 pendingReports
              >>
        \/ \* --- FAILED_OPEN, give-up branch ---
           \* Meta must be available to persist the state.
           /\ MetaIsAvailable
           \* Region is not suspended waiting for meta.
           /\ r \notin suspendedOnMeta
           \* Region is not blocking a PEWorker on meta.
           /\ r \notin blockedOnMeta
           \* Transition code must be FAILED_OPEN.
           /\ tc = "FAILED_OPEN"
           \* Retries exhausted; give up on opening this region.
           /\ regionState[r].retries >= MaxRetries
           \* Move region to FAILED_OPEN, clear procedure.
           /\ regionState' =
                [regionState EXCEPT
                ![r] =
                [ state |-> "FAILED_OPEN",
                  location |-> NoServer,
                  procType |-> "NONE",
                  procStep |-> "IDLE",
                  targetServer |-> NoServer,
                  retries |-> 0
                ]]
           \* Persist FAILED_OPEN to metaTable.
           /\ metaTable' =
                [metaTable EXCEPT
                ![r] =
                [ state |-> "FAILED_OPEN", location |-> NoServer ]]
           \* Delete completed procedure from store.
           /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
           /\ UNCHANGED << scpVars,
                 rpcVars,
                 serverVars,
                 rsVars,
                 masterVars,
                 peVars
              >>

---------------------------------------------------------------------------

(* Actions -- failure path *)
\* Open command dispatch failed (non-deterministic RPC failure).
\*
\* Two disjuncts model two outcomes of dispatch failure:
\*
\*   Disjunct 1 (retry): The command is removed from dispatchedOps
\*     without delivery and the TRSP returns to GET_ASSIGN_CANDIDATE
\*     with forceNewPlan (targetServer cleared).  Server stays ONLINE.
\*
\*   Disjunct 2 (dispatch-expiry server crash): Repeated dispatch
\*     failures cause RSProcedureDispatcher.scheduleForRetry() to
\*     call ServerManager.expireServer(), atomically crashing the
\*     target server and starting SCP.  The TRSP is left at
\*     CONFIRM_OPENED (matching remoteCallFailed() early-return when
\*     isServerOnline()=false, RRPB.java L122-127).  SCP's
\*     SCPAssignRegion -> TRSPServerCrashed path drives the region
\*     forward.  The RS may still be alive (ZK node present),
\*     modeling the hung-RS scenario where the server is unreachable
\*     by RPC but has not yet lost its ZK session.
\*     The 10-retry threshold is abstracted as non-deterministic
\*     choice -- sound because TLC explores all branches regardless
\*     of counter value.
\*
\* Pre: ASSIGN/MOVE/REOPEN/UNASSIGN procedure in CONFIRM_OPENED
\*      state, the matching open command still exists in
\*      dispatchedOps (not yet consumed by an RS), AND the target
\*      server is ONLINE.
\* Post: Disjunct 1: command removed, TRSP reset to
\*       GET_ASSIGN_CANDIDATE.
\*       Disjunct 2: command removed, server CRASHED, SCP started,
\*       TRSP unchanged (left at CONFIRM_OPENED).
\*
\* Guard: serverState[s] = "ONLINE" matches the implementation's
\*        early-return in RRPB.remoteCallFailed() (RRPB.java L122-127):
\*        if the server is dead, remoteCallFailed() returns without
\*        setting DISPATCH_FAIL, relying on SCP.serverCrashed() instead.
\*
\* Source: RegionRemoteProcedureBase.remoteCallFailed() sets
\*         DISPATCH_FAIL state and wakes the parent TRSP;
\*         RSProcedureDispatcher.scheduleForRetry() decides
\*         whether to retry or fail the remote call;
\*         RSProcedureDispatcher.scheduleForRetry() L326-336 ->
\*         ServerManager.expireServer() L662-720.
DispatchFail(r) ==
  \* Procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, has a
  \* target server, and the OPEN command is still in dispatchedOps.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be ASSIGN, MOVE, REOPEN, or UNASSIGN.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
  \* Procedure must be waiting for the open confirmation.
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  \* Bind the target server; it must have been set by TRSPGetCandidate.
  /\ LET s == regionState[r].targetServer
     IN /\ s # NoServer
        \* Target server must be ONLINE: RRPB.remoteCallFailed()
        \* early-returns when isServerOnline() is false (RRPB.java L122-127).
        /\ serverState[s] = "ONLINE"
        \* Reconstruct the dispatched command and verify it is still queued.
        /\ LET cmd == [ type |-> "OPEN", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              /\ \/ \* --- Disjunct 1: retry (server stays ONLINE) ---
                    \* Remove the undelivered command from the server's queue.
                    /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
                    \* Reset the procedure to GET_ASSIGN_CANDIDATE with no target.
                    /\ regionState' =
                         [regionState EXCEPT
                         ![r].procStep =
                         "GET_ASSIGN_CANDIDATE",
                         ![r].targetServer =
                         NoServer]
                    \* META, pending reports, RS-side state, server liveness unchanged.
                    /\ UNCHANGED << scpVars,
                          serverVars,
                          procStore,
                          rsVars,
                          masterVars,
                          peVars,
                          metaTable,
                          pendingReports
                       >>
                 \/ \* --- Disjunct 2: dispatch-expiry server crash ---
                    \* Repeated dispatch failures -> expireServer().
                    \* Atomically crash the target server and start SCP.
                    /\ serverState' = [serverState EXCEPT ![s] = "CRASHED"]
                    \* Non-deterministic carryingMeta (same pattern as
                    \* MasterDetectCrash): the crashed server may or may
                    \* not have been hosting hbase:meta.
                    /\ \/ /\ \A t \in Servers \ { s }: carryingMeta[t] = FALSE
                          /\ carryingMeta' = [carryingMeta EXCEPT ![s] = TRUE]
                          /\ scpState' = [scpState EXCEPT ![s] = "ASSIGN_META"]
                       \/ /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
                          /\ scpState' = [scpState EXCEPT ![s] = "GET_REGIONS"]
                    \* Remove the dispatched command.
                    /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
                    \* TRSP stays at CONFIRM_OPENED -- not reset.
                    \* Matches remoteCallFailed() early-return when
                    \* isServerOnline()=false.
                    /\ UNCHANGED << regionState,
                          procStore,
                          metaTable,
                          pendingReports,
                          rsVars,
                          masterVars,
                          peVars,
                          scpRegions,
                          walFenced,
                          serverRegions,
                          zkNode,
                          regionKeyRange,
                          parentProc
                       >>

\* Close command dispatch failed (non-deterministic RPC failure).
\*
\* Two disjuncts (same structure as DispatchFail):
\*
\*   Disjunct 1 (retry): Command removed, TRSP returns to CLOSE.
\*     Unlike the open path (DispatchFail), targetServer is NOT
\*     cleared because the close must still target the server
\*     hosting the region.  Server stays ONLINE.
\*
\*   Disjunct 2 (dispatch-expiry server crash): Repeated dispatch
\*     failures cause expireServer().  Server atomically CRASHED,
\*     SCP started.  TRSP left at CONFIRM_CLOSED (matching
\*     remoteCallFailed() early-return when isServerOnline()=false).
\*
\* Pre: UNASSIGN/MOVE/REOPEN procedure in CONFIRM_CLOSED state,
\*      the matching close command still exists in dispatchedOps,
\*      AND the target server is ONLINE.
\* Post: Disjunct 1: command removed, TRSP reset to CLOSE.
\*       Disjunct 2: command removed, server CRASHED, SCP started,
\*       TRSP unchanged (left at CONFIRM_CLOSED).
\*
\* Guard: serverState[s] = "ONLINE" -- same rationale as DispatchFail.
\*
\* Source: RegionRemoteProcedureBase.remoteCallFailed() sets
\*         DISPATCH_FAIL state and wakes the parent TRSP;
\*         RSProcedureDispatcher.scheduleForRetry() decides
\*         whether to retry or fail the remote call;
\*         RSProcedureDispatcher.scheduleForRetry() L326-336 ->
\*         ServerManager.expireServer() L662-720.
DispatchFailClose(r) ==
  \* Procedure is UNASSIGN or MOVE, in CONFIRM_CLOSED step, has a
  \* target server, and the CLOSE command is still in dispatchedOps.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be UNASSIGN, MOVE, or REOPEN.
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  \* Procedure must be waiting for the close confirmation.
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  \* Bind the target server; it must have been set by TRSPGetCandidate.
  /\ LET s == regionState[r].targetServer
     IN /\ s # NoServer
        \* Target server must be ONLINE (same guard as DispatchFail).
        /\ serverState[s] = "ONLINE"
        \* Reconstruct the dispatched command and verify it is still queued.
        /\ LET cmd == [ type |-> "CLOSE", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              /\ \/ \* --- Disjunct 1: retry (server stays ONLINE) ---
                    \* Remove the undelivered command from the server's queue.
                    /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
                    \* Reset the procedure to CLOSE to retry (target server kept).
                    /\ regionState' = [regionState EXCEPT ![r].procStep = "CLOSE"]
                    \* META, pending reports, RS-side state, server liveness unchanged.
                    /\ UNCHANGED << scpVars,
                          serverVars,
                          procStore,
                          rsVars,
                          masterVars,
                          peVars,
                          metaTable,
                          pendingReports
                       >>
                 \/ \* --- Disjunct 2: dispatch-expiry server crash ---
                    \* Repeated dispatch failures -> expireServer().
                    \* Atomically crash the target server and start SCP.
                    /\ serverState' = [serverState EXCEPT ![s] = "CRASHED"]
                    \* Non-deterministic carryingMeta (same pattern as
                    \* MasterDetectCrash).
                    /\ \/ /\ \A t \in Servers \ { s }: carryingMeta[t] = FALSE
                          /\ carryingMeta' = [carryingMeta EXCEPT ![s] = TRUE]
                          /\ scpState' = [scpState EXCEPT ![s] = "ASSIGN_META"]
                       \/ /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
                          /\ scpState' = [scpState EXCEPT ![s] = "GET_REGIONS"]
                    \* Remove the dispatched command.
                    /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
                    \* TRSP stays at CONFIRM_CLOSED -- not reset.
                    \* Matches remoteCallFailed() early-return when
                    \* isServerOnline()=false.
                    /\ UNCHANGED << regionState,
                          procStore,
                          metaTable,
                          pendingReports,
                          rsVars,
                          masterVars,
                          peVars,
                          scpRegions,
                          walFenced,
                          serverRegions,
                          zkNode,
                          regionKeyRange,
                          parentProc
                       >>

---------------------------------------------------------------------------

(* Actions -- TRSP UNASSIGN path *)
\* Create a TRSP UNASSIGN procedure for an OPEN region.
\* Pre: region is OPEN with a location AND no procedure is attached.
\* Post: procedure fields set to UNASSIGN/CLOSE with targetServer
\*       pointing at the region's current server.  Region lifecycle
\*       state is NOT changed yet -- the TRSP will drive the
\*       OPEN -> CLOSING transition in the next step.
\*
\* Source: TRSP.unassign() creates the procedure with
\*         TransitionType.UNASSIGN; TRSP.setInitialAndLastState()
\*         sets initial state to CLOSE and last state to
\*         CONFIRM_CLOSED; RegionStateNode.setProcedure()
\*         attaches it to the region.
TRSPCreateUnassign(r) ==
  \* Region is OPEN, has a location, and has no active procedure.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Region must be in OPEN state.
  /\ regionState[r].state = "OPEN"
  \* Region must have a server location assigned.
  /\ regionState[r].location # NoServer
  \* No parent procedure in progress (models ProcedureExecutor region-level locking).
  /\ parentProc[r].type = "NONE"
  \* No procedure is currently attached to this region.
  /\ regionState[r].procType = "NONE"
  \* Initialize embedded UNASSIGN procedure at CLOSE step, targeting current server.
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "UNASSIGN",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
  \* Persist the new procedure to the procedure store.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("UNASSIGN", "CLOSE", regionState[r].location, NoTransition)]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>

\* Create a TRSP MOVE procedure for an OPEN region.
\* A MOVE is an UNASSIGN (close on the current server) followed by an
\* ASSIGN (open on a new server), all within a single procedure.  The
\* procedure starts in the CLOSE phase with targetServer set to the
\* region's current location.  After the close completes, targetServer
\* is cleared and TRSPGetCandidate picks a new destination.
\*
\* Pre: region is OPEN with a location AND no procedure is attached
\*      AND at least one other ONLINE server exists as a potential
\*      destination.
\* Post: MOVE procedure created in CLOSE state, attached to region.
\*
\* Source: TRSP.move() / TRSP.reopen() creates the procedure with
\*         TransitionType.MOVE; TRSP.setInitialAndLastState()
\*         sets initial state to CLOSE and last state to
\*         CONFIRM_OPENED; RegionStateNode.setProcedure()
\*         attaches it to the region.
TRSPCreateMove(r) ==
  \* Region is OPEN, has a location, has no active procedure,
  \* and at least one other ONLINE server exists as a destination.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Region must be in OPEN state.
  /\ regionState[r].state = "OPEN"
  \* No parent procedure in progress (models ProcedureExecutor region-level locking).
  /\ parentProc[r].type = "NONE"
  \* Region must have a server location assigned.
  /\ regionState[r].location # NoServer
  \* No procedure is currently attached to this region.
  /\ regionState[r].procType = "NONE"
  \* At least one other ONLINE server must exist as a move destination.
  /\ \E s \in Servers: s # regionState[r].location /\ serverState[s] = "ONLINE"
  \* Initialize embedded MOVE procedure at CLOSE step, targeting current server.
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "MOVE",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
  \* Persist the new procedure to the procedure store.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("MOVE", "CLOSE", regionState[r].location, NoTransition)]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>

\* Create a TRSP REOPEN procedure for an OPEN region.
\* REOPEN follows the same flow as MOVE (CLOSE -> CONFIRM_CLOSED ->
\* GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED) but differs in that
\* assignCandidate is pre-set to the region's current server, so
\* TRSPGetCandidate may choose the same server.  There is no requirement
\* that another ONLINE server exists (contrast with TRSPCreateMove).
\*
\* Pre: region is OPEN, has a location, and has no active procedure.
\* Post: REOPEN procedure created in CLOSE state, targetServer set
\*       to the region's current location (assignCandidate pinning).
\*
\* Source: TRSP.reopen();
\*         TRSP.setInitialAndLastState();
\*         TRSP.queueAssign() (retain=true when assignCandidate set).
TRSPCreateReopen(r) ==
  \* UseReopen enabled, region is OPEN, has a server location,
  \* no active procedure.  No requirement that another ONLINE server exists.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* REOPEN feature must be enabled.
  /\ UseReopen = TRUE
  \* No parent procedure in progress (models ProcedureExecutor region-level locking).
  /\ parentProc[r].type = "NONE"
  \* Region must be in OPEN state.
  /\ regionState[r].state = "OPEN"
  \* Region must have a server location assigned.
  /\ regionState[r].location # NoServer
  \* No procedure is currently attached to this region.
  /\ regionState[r].procType = "NONE"
  \* Initialize embedded REOPEN procedure at CLOSE step, targeting the
  \* region's current server (assignCandidate pinning).
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "REOPEN",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
  \* Persist the new procedure to the procedure store.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("REOPEN", "CLOSE", regionState[r].location, NoTransition)]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>

\* Dispatch the close command to the target server via RPC.
\* Pre: UNASSIGN or MOVE procedure in CLOSE state, region is OPEN or
\*      CLOSING (CLOSING on retry after DispatchFailClose).
\* Post: region transitions to CLOSING (no-op if already CLOSING),
\*       meta updated, close command added to dispatchedOps[targetServer],
\*       TRSP advances to CONFIRM_CLOSED.
\*
\* Source: TRSP.closeRegion() calls AM.regionClosing()
\*         to set CLOSING state and meta, then creates a
\*         CloseRegionProcedure child; RSProcedureDispatcher
\*         dispatches the RPC via
\*         RSProcedureDispatcher.ExecuteProceduresRemoteCall.run().
TRSPDispatchClose(r) ==
  \* Procedure is UNASSIGN or MOVE, in CLOSE step, has a target
  \* server, and region is OPEN or CLOSING (CLOSING on retry).
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be UNASSIGN, MOVE, or REOPEN.
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  \* Procedure must be at the CLOSE step.
  /\ regionState[r].procStep = "CLOSE"
  \* A target server must be set for the close dispatch.
  /\ regionState[r].targetServer # NoServer
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
        \* Region is not already suspended waiting for meta.
        /\ r \notin suspendedOnMeta
        \* Region is not already blocking a PEWorker on meta.
        /\ r \notin blockedOnMeta
        \* Branch on meta-write blocking mode.
        /\ IF UseBlockOnMetaWrite = FALSE
           \* Async: suspend procedure, release PEWorker thread.
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
           \* Sync: block PEWorker thread on meta write.
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              regionState,
              metaTable,
              procStore
           >>
     \/ \* --- Meta available: dispatch close ---
        /\ MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        \* Bind the target server for readability.
        /\ LET s == regionState[r].targetServer
           IN /\ regionState[r].state \in
                   { "OPEN", "CLOSING", "SPLITTING", "MERGING" }
              \* Transition region to CLOSING and advance to CONFIRM_CLOSED.
              /\ regionState' =
                   [regionState EXCEPT
                   ![r].state =
                   "CLOSING",
                   ![r].procStep =
                   "CONFIRM_CLOSED"]
              \* Persist CLOSING state in META, preserving the location.
              /\ metaTable' =
                   [metaTable EXCEPT
                   ![r] =
                   [ state |-> "CLOSING", location |-> metaTable[r].location ]]
              \* Enqueue a CLOSE command to the target server's dispatched ops.
              /\ dispatchedOps' =
                   [dispatchedOps EXCEPT
                   ![s] =
                   @ \cup { [ type |-> "CLOSE", region |-> r ] }]
              \* Update persisted procedure step.
              /\ procStore' = [procStore EXCEPT ![r].step = "CONFIRM_CLOSED"]
              /\ UNCHANGED << scpVars,
                    serverVars,
                    rsVars,
                    masterVars,
                    peVars,
                    pendingReports
                 >>


\* --- Report-succeed for CLOSE path ---
\* RS CLOSED report consumed: in-memory regionState updated to CLOSED,
\* procedure persisted to procStore at REPORT_SUCCEED with transitionCode
\* = "CLOSED".  MetaTable is NOT updated yet.
\*
\* Pre: UNASSIGN/MOVE/REOPEN procedure in CONFIRM_CLOSED step, region is
\*      CLOSING, and a matching CLOSED report exists from the target server.
\* Post: In-memory state = CLOSED, procStore at REPORT_SUCCEED.
\*
\* Source: RegionRemoteProcedureBase.reportTransition() ->
\*         RRPB REPORT_SUCCEED state for the close path.
TRSPReportSucceedClose(r) ==
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be UNASSIGN, MOVE, or REOPEN.
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  \* Procedure must be waiting for the close confirmation.
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  \* Region must be in CLOSING state (dispatch already happened).
  /\ regionState[r].state = "CLOSING"
  \* A matching CLOSED report exists for this region.
  /\ \E rpt \in pendingReports:
       \* Report must be for region r.
       /\ rpt.region = r
       \* Report must indicate successful close.
       /\ rpt.code = "CLOSED"
       \* Report must be from the target server.
       /\ rpt.server = regionState[r].targetServer
       \* Do not consume if r was reopened on that server.
       /\ r \notin rsOnlineRegions[rpt.server]
       \* Update in-memory state: CLOSING -> CLOSED.
       \* AM.regionClosedWithoutPersistingToMeta().
       /\ regionState' =
            [regionState EXCEPT
            ![r].state =
            "CLOSED",
            ![r].location =
            NoServer,
            ![r].procStep =
            "REPORT_SUCCEED"]
       \* Consume the report.
       /\ pendingReports' = pendingReports \ { rpt }
       \* Persist at REPORT_SUCCEED with transitionCode = CLOSED.
       /\ procStore' =
            [procStore EXCEPT
            ![r].step =
            "REPORT_SUCCEED",
            ![r].transitionCode =
            "CLOSED"]
       \* ServerStateNode tracking: AM.regionClosedWithoutPersisting()
       \* calls removeRegionFromServer().
       /\ LET loc == regionState[r].location
          IN serverRegions' =
                IF loc # NoServer
                THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                ELSE serverRegions
       \* MetaTable NOT updated yet.
       /\ UNCHANGED << scpVars,
             rsVars,
             peVars,
             masterVars,
             metaTable,
             dispatchedOps,
             serverState
          >>

\* --- Persist-to-meta for CLOSE path ---
\* Final CLOSED state persisted to metaTable.
\*   UNASSIGN: procedure completed, record deleted from procStore.
\*   MOVE/REOPEN: procedure advances to GET_ASSIGN_CANDIDATE for re-open.
\*
\* Pre: Procedure at REPORT_SUCCEED with transitionCode = CLOSED.
\* Post: MetaTable updated, procedure completed or advanced.
\*
\* Source: RRPB persist-to-meta phase for close path.
TRSPPersistToMetaClose(r) ==
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be UNASSIGN, MOVE, or REOPEN.
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  \* Procedure must be at the report-succeed (persist-to-meta) step.
  /\ regionState[r].procStep = "REPORT_SUCCEED"
  \* Transition code must be CLOSED.
  /\ procStore[r].transitionCode = "CLOSED"
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
        \* Region is not already suspended waiting for meta.
        /\ r \notin suspendedOnMeta
        \* Region is not already blocking a PEWorker on meta.
        /\ r \notin blockedOnMeta
        \* Branch on meta-write blocking mode.
        /\ IF UseBlockOnMetaWrite = FALSE
           \* Async: suspend procedure, release PEWorker thread.
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
           \* Sync: block PEWorker thread on meta write.
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              regionState,
              metaTable,
              procStore
           >>
     \/ \* --- Meta available: persist close to meta ---
        /\ MetaIsAvailable
        \* Region is not suspended waiting for meta.
        /\ r \notin suspendedOnMeta
        \* Region is not blocking a PEWorker on meta.
        /\ r \notin blockedOnMeta
        \* Persist CLOSED to metaTable.
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "CLOSED", location |-> NoServer ]]
        \* Branch on procedure type.
        /\ IF regionState[r].procType = "UNASSIGN"
           THEN \* UNASSIGN complete: clear procedure.
                /\ regionState' =
                     [regionState EXCEPT
                     ![r] =
                     [ state |-> "CLOSED",
                       location |-> NoServer,
                       procType |-> "NONE",
                       procStep |-> "IDLE",
                       targetServer |-> NoServer,
                       retries |-> 0
                     ]]
                /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
           ELSE \* MOVE/REOPEN: advance to GET_ASSIGN_CANDIDATE.
                /\ regionState' =
                     [regionState EXCEPT
                     ![r].procStep =
                     "GET_ASSIGN_CANDIDATE",
                     ![r].targetServer =
                     NoServer,
                     ![r].retries =
                     0]
                /\ procStore' =
                     [procStore EXCEPT
                     ![r] =
                     NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              peVars
           >>

\* --- Path 2: Crash during close (ABNORMALLY_CLOSED) ---
\* The target server crashed while the close was in flight.
\* The procedure self-recovers: convert to ASSIGN at
\* GET_ASSIGN_CANDIDATE to reopen the region (needed to
\* process recovered edits).
\*
\* Pre: UNASSIGN/MOVE/REOPEN at CONFIRM_CLOSED, region ABNORMALLY_CLOSED.
\* Post: Procedure converted to ASSIGN/GET_ASSIGN_CANDIDATE, stale
\*       dispatched commands cleared.
\*
\* Source: TRSP.confirmClosed() detects ABNORMALLY_CLOSED,
\*         RegionRemoteProcedureBase.serverCrashed().
TRSPConfirmClosedCrash(r) ==
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Procedure type must be UNASSIGN, MOVE, or REOPEN.
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  \* Procedure must be waiting for the close confirmation.
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  \* Region was marked ABNORMALLY_CLOSED by SCP (server crashed).
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
  \* Type-preserving crash recovery (matching Java confirmClosed() L379-389):
  \* preserve the original procType instead of unconditionally converting
  \* to ASSIGN.  For UNASSIGN, this triggers two-phase recovery (reopen
  \* then re-close) in TRSPPersistToMetaOpen.
  /\ regionState' =
       [regionState EXCEPT
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       NoServer,
       ![r].retries =
       0]
  \* Clear stale dispatched commands for this region.
  /\ dispatchedOps' =
       [s \in Servers |-> {cmd \in dispatchedOps[s]: cmd.region # r}
       ]
  \* Update persisted procedure: preserve type.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  \* ServerStateNode tracking.
  /\ LET loc == regionState[r].location
     IN serverRegions' =
           IF loc # NoServer
           THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
           ELSE serverRegions
  /\ UNCHANGED << scpVars,
        rsVars,
        masterVars,
        peVars,
        metaTable,
        pendingReports,
        serverState
     >>

---------------------------------------------------------------------------

(* Actions -- crash recovery *)
\* A procedure's region has been crashed (ABNORMALLY_CLOSED).  The
\* procedure preserves its type and advances to GET_ASSIGN_CANDIDATE,
\* modeling the TRSP serverCrashed() callback plus the closeRegion()
\* recovery branch: when closeRegion() finds the region is not in a
\* closeable state, it sets forceNewPlan=true, clears the location,
\* and jumps to GET_ASSIGN_CANDIDATE.
\*
\* Type-preserving (matching Java serverCrashed()):
\*   UNASSIGN: preserves type for two-phase recovery (reopen then
\*     re-close).  TRSPPersistToMetaOpen detects UNASSIGN and
\*     continues to CLOSE instead of completing.
\*   MOVE/REOPEN/ASSIGN: preserves existing type; behaviorally
\*     equivalent to the old ASSIGN conversion since the remaining
\*     steps (GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED) are
\*     identical regardless of type.
\*
\* This resolves the deadlock where an UNASSIGN procedure is stranded
\* on an ABNORMALLY_CLOSED region with no way to progress.  The full
\* SCP machinery (ServerCrashProcedure) orchestrates WHEN this callback
\* fires; this action models WHAT happens to the TRSP.
\*
\* Pre: region has an active procedure (any type) AND region state is
\*      ABNORMALLY_CLOSED.
\* Post: procedure at GET_ASSIGN_CANDIDATE with preserved type,
\*       cleared targetServer and reset retries.  Stale CLOSE commands
\*       for this region cleared from all servers' dispatchedOps.
\*       Stale CLOSED reports for this region dropped from
\*       pendingReports.
\*
\* Source: TRSP.serverCrashed() delegates to
\*         RegionRemoteProcedureBase.serverCrashed()
\*         if a sub-procedure is in flight, or directly calls
\*         AM.regionClosedAbnormally();
\*         TRSP.closeRegion() else-branch sets forceNewPlan
\*         and advances to GET_ASSIGN_CANDIDATE when the region
\*         is not in a closeable state.
TRSPServerCrashed(r) ==
  \* Region has an active procedure, is ABNORMALLY_CLOSED,
  \* and the procedure has not already been converted to
  \* GET_ASSIGN_CANDIDATE by SCPAssignRegion Path A.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available to execute this procedure step.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* A procedure must be attached to this region.
  /\ regionState[r].procType # "NONE"
  \* Region was marked ABNORMALLY_CLOSED by SCP (server crashed).
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
  \* Procedure must not already be at GET_ASSIGN_CANDIDATE (already converted).
  /\ regionState[r].procStep # "GET_ASSIGN_CANDIDATE"
  \* ABNORMALLY_CLOSED is only set by SCP (AM.regionClosedAbnormally()),
  \* so at least one SCP must have reached the ASSIGN phase.
  /\ \E s \in Servers: scpState[s] \in { "ASSIGN", "DONE" }
  \* Type-preserving crash recovery: preserve the procedure type,
  \* advance to GET_ASSIGN_CANDIDATE.  Matches Java serverCrashed()
  \* which does not change TransitionType.
  /\ regionState' =
       [regionState EXCEPT
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       NoServer,
       ![r].retries =
       0]
  \* Clear any CLOSE for r from dispatchedOps.  The prior procedure (UNASSIGN
  \* or MOVE) dispatched it; we are abandoning that and reopening instead.
  \* Without this, RSClose can consume the stale CLOSE after we reassign,
  \* violating RSMasterAgreement (OPEN in regionState but r not in rsOnlineRegions).
  /\ dispatchedOps' =
       [t \in Servers |->
         {cmd \in dispatchedOps[t]: cmd.region # r \/ cmd.type # "CLOSE"}
       ]
  \* Drop CLOSED reports for r.  They are from the abandoned close;
  \* consuming them after we reopen would set CLOSED while r is in
  \* rsOnlineRegions (from RSOpen), violating RSMasterAgreementConverse.
  /\ pendingReports' =
       {pr \in pendingReports: pr.region # r \/ pr.code # "CLOSED"}
  \* META, RS-side state, and server liveness unchanged.
  \* Update persisted procedure: preserve type.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  /\ UNCHANGED << scpVars, serverVars, rsVars, masterVars, metaTable >>
  \* Clear r from suspended/blocked sets if it was waiting on meta.
  /\ suspendedOnMeta' = suspendedOnMeta \ { r }
  /\ blockedOnMeta' = blockedOnMeta \ { r }
  /\ availableWorkers' =
       IF r \in blockedOnMeta THEN availableWorkers + 1 ELSE availableWorkers

---------------------------------------------------------------------------

(* Actions -- PEWorker meta-resume *)
\* Resume a procedure that was suspended or blocked on a meta write.
\* When MetaIsAvailable becomes TRUE (SCP completes meta assignment),
\* suspended procedures are woken and blocked workers are unblocked.
\*
\* Pre: masterAlive = TRUE, r in suspendedOnMeta or blockedOnMeta,
\*      MetaIsAvailable.
\* Post: r removed from suspendedOnMeta or blockedOnMeta;
\*       availableWorkers incremented if blocked.
\*
\* Source: ProcedureFutureUtil.wakeIfSuspended() for async case;
\*         Table.put() returning for sync case.
ResumeFromMeta(r) ==
  /\ masterAlive = TRUE
  /\ MetaIsAvailable
  /\ \/ \* --- Async resume: procedure was suspended ---
        \* Region must be in the suspended set.
        /\ r \in suspendedOnMeta
        \* Remove r from the suspended set (waking it up).
        /\ suspendedOnMeta' = suspendedOnMeta \ { r }
        \* No PEWorker was consumed; no changes to workers or blocked set.
        /\ UNCHANGED << availableWorkers, blockedOnMeta >>
     \/ \* --- Sync resume: PEWorker was blocked ---
        \* Region must be in the blocked set.
        /\ r \in blockedOnMeta
        \* Remove r from the blocked set (waking it up).
        /\ blockedOnMeta' = blockedOnMeta \ { r }
        \* Recover the PEWorker thread that was blocked.
        /\ availableWorkers' = availableWorkers + 1
        \* No changes to the suspended set.
        /\ UNCHANGED suspendedOnMeta
  \* No state changes -- just remove from suspended/blocked set.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        regionState,
        metaTable,
        procStore
     >>

============================================================================
