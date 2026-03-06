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
         locked,
         carryingMeta,
         serverRegions,
         procStore,
         masterAlive,
         zkNode,
         availableWorkers,
         suspendedOnMeta,
         blockedOnMeta

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == << dispatchedOps, pendingReports >>

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == << rsOnlineRegions >>

\* Shorthand for variables unchanged by TRSP actions (used in UNCHANGED
\* clauses).  Includes SCP state and ZK ephemeral nodes — neither is
\* modified by any TRSP action.
scpVars == << scpState, scpRegions, walFenced, carryingMeta, zkNode >>

\* Shorthand for master lifecycle variables (used in UNCHANGED clauses).
masterVars == << masterAlive >>

\* Shorthand for procedure/lock variables (used in UNCHANGED clauses).
procVars == << procStore, locked >>

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
  /\ availableWorkers > 0
  /\ regionState[r].state \in
       { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED", "FAILED_OPEN" }
  /\ regionState[r].procType = "NONE"
  \* Don't auto-create ASSIGN for ABNORMALLY_CLOSED regions while any SCP
  \* is actively processing a crash.  In the implementation, SCP owns
  \* crash recovery; no background daemon races to create procedures for
  \* crashed regions during the SCP assign loop.
  /\ \/ regionState[r].state # "ABNORMALLY_CLOSED"
     \/ \A s \in Servers: scpState[s] \in { "NONE", "DONE" }
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
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
        metaTable,
        locked
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
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "GET_ASSIGN_CANDIDATE"
  /\ serverState[s] = "ONLINE"
  /\ \A sZombie \in Servers:
       serverState[sZombie] = "CRASHED" => r \notin rsOnlineRegions[sZombie]
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
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
        metaTable,
        locked
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
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "OPEN"
  /\ regionState[r].targetServer # NoServer
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        /\ IF UseBlockOnMetaWrite = FALSE
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
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
              procStore,
              locked
           >>
     \/ \* --- Meta available: dispatch open ---
        /\ MetaIsAvailable
        /\ r \notin suspendedOnMeta
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
                    serverState,
                    locked
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
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ regionState[r].state = "OPENING"
  /\ locked[r] = FALSE
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code \in { "OPENED", "FAILED_OPEN" }
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
             serverState,
             locked
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
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "REPORT_SUCCEED"
  /\ locked[r] = FALSE
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
           /\ r \notin suspendedOnMeta
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
                 procStore,
                 locked
              >>
        \/ \* --- OPENED branch ---
           /\ MetaIsAvailable
           /\ r \notin suspendedOnMeta
           /\ r \notin blockedOnMeta
           /\ tc = "OPENED"
           /\ regionState[r].state = "OPEN"
           \* Clear procedure (ASSIGN), or advance to GET_ASSIGN_CANDIDATE (MOVE/REOPEN — this shouldn't normally happen for OPENED on MOVE, but handle uniformly).
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
           \* Persist OPEN state to metaTable.
           /\ metaTable' =
                [metaTable EXCEPT
                ![r] =
                [ state |-> "OPEN", location |-> metaTable[r].location ]]
           \* Delete completed procedure from store.
           /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
           /\ UNCHANGED << scpVars,
                 rpcVars,
                 serverVars,
                 rsVars,
                 masterVars,
                 peVars,
                 locked
              >>
        \/ \* --- FAILED_OPEN, retry branch ---
           /\ MetaIsAvailable
           /\ r \notin suspendedOnMeta
           /\ r \notin blockedOnMeta
           /\ tc = "FAILED_OPEN"
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
                 pendingReports,
                 locked
              >>
        \/ \* --- FAILED_OPEN, give-up branch ---
           /\ MetaIsAvailable
           /\ r \notin suspendedOnMeta
           /\ r \notin blockedOnMeta
           /\ tc = "FAILED_OPEN"
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
                 peVars,
                 locked
              >>

---------------------------------------------------------------------------


(* Actions -- failure path *)
\* Open command dispatch failed (non-deterministic RPC failure).
\* The command is removed from dispatchedOps without delivery and
\* the TRSP returns to GET_ASSIGN_CANDIDATE with forceNewPlan (i.e.,
\* targetServer is cleared so a fresh candidate will be chosen).
\* The region remains OPENING with its current location; the next
\* TRSPDispatchOpen will update the location to the new server.
\*
\* Pre: ASSIGN/MOVE procedure in CONFIRM_OPENED state and the matching
\*      open command still exists in dispatchedOps (not yet consumed
\*      by an RS).
\* Post: command removed, TRSP reset to GET_ASSIGN_CANDIDATE.
\*
\* Source: RegionRemoteProcedureBase.remoteCallFailed() sets
\*         DISPATCH_FAIL state and wakes the parent TRSP;
\*         RSProcedureDispatcher.scheduleForRetry() decides
\*         whether to retry or fail the remote call.
DispatchFail(r) ==
  \* Procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, has a
  \* target server, and the OPEN command is still in dispatchedOps.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
  \* Bind the target server; it must have been set by TRSPGetCandidate.
  /\ LET s == regionState[r].targetServer
     IN /\ s # NoServer
        \* Reconstruct the dispatched command and verify it is still queued.
        /\ LET cmd == [ type |-> "OPEN", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              \* Remove the undelivered command from the server's queue.
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
        \* Reset the procedure to GET_ASSIGN_CANDIDATE with no target server.
        /\ regionState' =
             [regionState EXCEPT
             ![r].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r].targetServer =
             NoServer]
        \* META, pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << scpVars,
              serverVars,
              procVars,
              rsVars,
              masterVars,
              peVars,
              metaTable,
              pendingReports
           >>

\* Close command dispatch failed (non-deterministic RPC failure).
\* The command is removed from dispatchedOps without delivery and
\* the TRSP returns to CLOSE to retry the dispatch.  Unlike the open
\* path (DispatchFail), the targetServer is NOT cleared because the
\* close must still target the server hosting the region.
\*
\* Pre: UNASSIGN or MOVE procedure in CONFIRM_CLOSED state and the
\*      matching close command still exists in dispatchedOps.
\* Post: command removed, TRSP reset to CLOSE.
\*
\* Source: RegionRemoteProcedureBase.remoteCallFailed() sets
\*         DISPATCH_FAIL state and wakes the parent TRSP;
\*         RSProcedureDispatcher.scheduleForRetry() decides
\*         whether to retry or fail the remote call.
DispatchFailClose(r) ==
  \* Procedure is UNASSIGN or MOVE, in CONFIRM_CLOSED step, has a
  \* target server, and the CLOSE command is still in dispatchedOps.
  \* Master must be alive for procedure execution.
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
  \* Bind the target server; it must have been set by TRSPGetCandidate.
  /\ LET s == regionState[r].targetServer
     IN /\ s # NoServer
        \* Reconstruct the dispatched command and verify it is still queued.
        /\ LET cmd == [ type |-> "CLOSE", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              \* Remove the undelivered command from the server's queue.
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
        \* Reset the procedure to CLOSE to retry (target server kept).
        /\ regionState' = [regionState EXCEPT ![r].procStep = "CLOSE"]
        \* META, pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << scpVars,
              serverVars,
              procVars,
              rsVars,
              masterVars,
              peVars,
              metaTable,
              pendingReports
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
  /\ availableWorkers > 0
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # NoServer
  /\ regionState[r].procType = "NONE"
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
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
        metaTable,
        locked
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
  /\ availableWorkers > 0
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # NoServer
  /\ regionState[r].procType = "NONE"
  /\ \E s \in Servers: s # regionState[r].location /\ serverState[s] = "ONLINE"
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
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
        metaTable,
        locked
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
  /\ availableWorkers > 0
  /\ UseReopen = TRUE
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # NoServer
  /\ regionState[r].procType = "NONE"
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
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
        metaTable,
        locked
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
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CLOSE"
  /\ regionState[r].targetServer # NoServer
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        /\ IF UseBlockOnMetaWrite = FALSE
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
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
              procStore,
              locked
           >>
     \/ \* --- Meta available: dispatch close ---
        /\ MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        \* Bind the target server for readability.
        /\ LET s == regionState[r].targetServer
           IN /\ regionState[r].state \in { "OPEN", "CLOSING" }
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
                    pendingReports,
                    locked
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
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ regionState[r].state = "CLOSING"
  /\ locked[r] = FALSE
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code = "CLOSED"
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
             serverState,
             locked
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
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "REPORT_SUCCEED"
  /\ procStore[r].transitionCode = "CLOSED"
  /\ locked[r] = FALSE
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        /\ IF UseBlockOnMetaWrite = FALSE
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
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
              procStore,
              locked
           >>
     \/ \* --- Meta available: persist close to meta ---
        /\ MetaIsAvailable
        /\ r \notin suspendedOnMeta
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
              peVars,
              locked
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
  /\ availableWorkers > 0
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ locked[r] = FALSE
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
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
  \* Clear stale dispatched commands for this region.
  /\ dispatchedOps' =
       [s \in Servers |-> {cmd \in dispatchedOps[s]: cmd.region # r}
       ]
  \* Update persisted procedure: convert to ASSIGN.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
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
        serverState,
        locked
     >>

---------------------------------------------------------------------------

(* Actions -- crash recovery *)
\* A procedure's region has been crashed (ABNORMALLY_CLOSED).  The
\* procedure converts itself to an ASSIGN at GET_ASSIGN_CANDIDATE,
\* modeling the TRSP serverCrashed() callback plus the closeRegion()
\* recovery branch: when closeRegion() finds the region is not in a
\* closeable state, it sets forceNewPlan=true, clears the location,
\* and jumps to GET_ASSIGN_CANDIDATE.
\*
\* This resolves the deadlock where an UNASSIGN procedure is stranded
\* on an ABNORMALLY_CLOSED region with no way to progress.  The full
\* SCP machinery (ServerCrashProcedure) orchestrates WHEN this callback
\* fires; this action models WHAT happens to the TRSP.
\*
\* Pre: region has an active procedure (any type) AND region state is
\*      ABNORMALLY_CLOSED.
\* Post: procedure converted to ASSIGN/GET_ASSIGN_CANDIDATE with
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
  /\ availableWorkers > 0
  /\ regionState[r].procType # "NONE"
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
  /\ regionState[r].procStep # "GET_ASSIGN_CANDIDATE"
  \* Region is not locked by an in-progress procedure step.
  /\ locked[r] = FALSE
  \* Convert the procedure to ASSIGN at GET_ASSIGN_CANDIDATE, clear target.
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
  \* Clear any CLOSE for r from dispatchedOps.  The prior procedure (UNASSIGN
  \* or MOVE) dispatched it; we are abandoning that and doing ASSIGN instead.
  \* Without this, RSClose can consume the stale CLOSE after we reassign,
  \* violating RSMasterAgreement (OPEN in regionState but r not in rsOnlineRegions).
  /\ dispatchedOps' =
       [t \in Servers |->
         {cmd \in dispatchedOps[t]: cmd.region # r \/ cmd.type # "CLOSE"}
       ]
  \* Drop CLOSED reports for r.  They are from the abandoned UNASSIGN/MOVE;
  \* consuming them after we reassign would set CLOSED while r is in
  \* rsOnlineRegions (from RSOpen), violating RSMasterAgreementConverse.
  /\ pendingReports' =
       {pr \in pendingReports: pr.region # r \/ pr.code # "CLOSED"}
  \* META, RS-side state, and server liveness unchanged.
  \* Update persisted procedure: convert to ASSIGN.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  /\ UNCHANGED << scpVars, serverVars, rsVars, masterVars, metaTable, locked >>
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
        /\ r \in suspendedOnMeta
        /\ suspendedOnMeta' = suspendedOnMeta \ { r }
        /\ UNCHANGED << availableWorkers, blockedOnMeta >>
     \/ \* --- Sync resume: PEWorker was blocked ---
        /\ r \in blockedOnMeta
        /\ blockedOnMeta' = blockedOnMeta \ { r }
        /\ availableWorkers' = availableWorkers + 1
        /\ UNCHANGED suspendedOnMeta
  \* No state changes -- just remove from suspended/blocked set.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        regionState,
        metaTable,
        procStore,
        locked
     >>

============================================================================
