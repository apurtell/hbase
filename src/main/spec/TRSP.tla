------------------------------ MODULE TRSP ------------------------------------
(*
 * TransitionalRegionStateProcedure actions for the HBase AssignmentManager.
 *
 * Contains all TRSP actions: ASSIGN path (create, get-candidate,
 * dispatch-open, confirm-opened), failure path (dispatch-fail,
 * dispatch-fail-close, handle-failed-open, give-up-open),
 * UNASSIGN path (create-unassign), MOVE path (create-move),
 * REOPEN path (create-reopen), CLOSE path (dispatch-close,
 * confirm-closed), and crash recovery (server-crashed).
 *
 * This module declares all shared variables as CONSTANT parameters.
 * The root AssignmentManager module instantiates it with
 * INSTANCE TRSP WITH ... substituting the actual variables.
 *)
EXTENDS AssignmentManagerTypes

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
         procStore

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == << dispatchedOps, pendingReports >>

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == << rsOnlineRegions >>

\* Shorthand for the SCP-related variables (used in UNCHANGED clauses).
scpVars == << scpState, scpRegions, walFenced, carryingMeta >>

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
  \* Guards: region is in an assignable state and has no active procedure.
  /\ regionState[r].state \in
       { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED", "FAILED_OPEN" }
  /\ regionState[r].procType = "NONE"
  \* Don't auto-create ASSIGN for ABNORMALLY_CLOSED regions while any SCP
  \* is actively processing a crash.  In the implementation, SCP owns
  \* crash recovery; no background daemon races to create procedures for
  \* crashed regions during the SCP assign loop.
  /\ \/ regionState[r].state # "ABNORMALLY_CLOSED"
     \/ \A s \in Servers: scpState[s] \in { "NONE", "DONE" }
  \* Initialize embedded ASSIGN procedure at GET_ASSIGN_CANDIDATE step.
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "ASSIGN",
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       None,
       ![r].retries =
       0]
  \* Persist the new procedure to the procedure store.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       [ type |-> "ASSIGN",
         step |-> "GET_ASSIGN_CANDIDATE",
         targetServer |-> None
       ]]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpVars,
        locked,
        serverRegions
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
\* Note: ZombieFencingOrder (scpState[s] = "ASSIGN" => walFenced[s])
\* and the walFenced guard on SCPAssignRegion remain unchanged --
\* those faithfully model SCP's own ordering constraint.
\*
\* Source: TRSP.queueAssign() -> AM.queueAssign() -> LoadBalancer;
\*         createDestinationServersList() excludes CRASHED servers;
\*         HRegionServer.abort() (RSAbort) clears rsOnlineRegions.
TRSPGetCandidate(r, s) ==
  \* Guards: procedure is ASSIGN, MOVE, or REOPEN at GET_ASSIGN_CANDIDATE;
  \* chosen server is ONLINE; no CRASHED server still holds r in
  \* rsOnlineRegions (zombie window must be closed by RSAbort first).
  \* For REOPEN, s may equal regionState[r].location (same server OK).
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "GET_ASSIGN_CANDIDATE"
  /\ serverState[s] = "ONLINE"
  /\ \A sZombie \in Servers:
       serverState[sZombie] = "CRASHED" => r \notin rsOnlineRegions[sZombie]
  \* Record the chosen server and advance the procedure to the OPEN step.
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT ![r].targetServer = s, ![r].procStep = "OPEN"]
  \* Update persisted procedure with target server and step.
  /\ procStore' = [procStore EXCEPT ![r].step = "OPEN", ![r].targetServer = s]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpVars,
        locked,
        serverRegions
     >>

\* Dispatch the open command to the target server via RPC.
\* Pre: TRSP is in OPEN state with a target server.
\* Post: region transitions to OPENING with the target server as
\*       location, meta updated, open command added to
\*       dispatchedOps[targetServer], TRSP advances to CONFIRM_OPENED.
\*
\* Source: TRSP.openRegion() calls AM.regionOpening()
\*         to set OPENING state and meta, then creates an
\*         OpenRegionProcedure child; RSProcedureDispatcher
\*         dispatches the RPC via
\*         RSProcedureDispatcher.ExecuteProceduresRemoteCall.run().
TRSPDispatchOpen(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in OPEN step, with a target server.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "OPEN"
  /\ regionState[r].targetServer # None
  \* Bind the target server for readability.
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN \* Transition region to OPENING, record server, advance to CONFIRM_OPENED.
        /\ regionState' =
             [regionState EXCEPT
             ![r].state =
             "OPENING",
             ![r].location =
             s,
             ![r].procStep =
             "CONFIRM_OPENED"]
        \* Persist the OPENING state and server assignment in META.
        /\ metaTable' =
             [metaTable EXCEPT ![r] = [ state |-> "OPENING", location |-> s ]]
        \* Enqueue an OPEN command to the target server's dispatched ops.
        /\ dispatchedOps' =
             [dispatchedOps EXCEPT
             ![s] =
             @ \cup { [ type |-> "OPEN", region |-> r ] }]
        \* ServerStateNode tracking: AM.regionOpening() adds r to the
        \* new target server's set.  It does NOT remove r from the old
        \* server's set — that removal happens only in regionFailedOpen,
        \* regionClosedWithoutPersistingToMeta, or regionClosedAbnormally.
        \* This means r may appear on two servers' ServerStateNode
        \* simultaneously (old and new) during the OPENING window.
        \* Source: AM.regionOpening().
        /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \cup { r }]
        \* Update persisted procedure step.
        /\ procStore' = [procStore EXCEPT ![r].step = "CONFIRM_OPENED"]
        \* Pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << pendingReports, rsVars, serverState, scpVars, locked >>

\* Confirm that the region opened successfully by consuming an OPENED
\* report from pendingReports.
\* Pre: TRSP is in CONFIRM_OPENED state, region is OPENING, and a
\*      matching OPENED report exists in pendingReports from an ONLINE
\*      server.
\* Post: region transitions to OPEN, procedure cleared, report consumed.
\*
\* At CONFIRM_OPENED the procedure suspends until a report
\* arrives; reportTransition() wakes it. The procedure does not advance
\* until the report is processed, so OPENED/FAILED_OPEN ordering is
\* serialized per region in the implementation.
\*
\* Source: TRSP.confirmOpened() on the OPEN branch;
\*         TRSP.reportTransition() wakes the procedure;
\*         AM.regionOpenedWithoutProcedure() / AM.regionOpened()
\*         performs the OPENING -> OPEN state transition and meta
\*         update.
TRSPConfirmOpened(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, region is
  \* OPENING, and a matching OPENED report exists from an ONLINE server.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ regionState[r].state = "OPENING"
  /\ locked[r] = FALSE
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code = "OPENED"
       \* No serverState ONLINE guard: models the race where
       \* an OPENED report arrives before crash detection (see
       \* TRSPConfirmClosed comment for rationale).
       \* Finalize region as OPEN, keep location, clear procedure fields.
       /\ regionState' =
            [regionState EXCEPT
            ![r] =
            [ state |-> "OPEN",
              location |-> regionState[r].location,
              procType |-> "NONE",
              procStep |-> "IDLE",
              targetServer |-> None,
              retries |-> 0
            ]]
       \* Persist the OPEN state in META, preserving the location.
       /\ metaTable' =
            [metaTable EXCEPT
            ![r] =
            [ state |-> "OPEN", location |-> metaTable[r].location ]]
       \* Consume the matched report from the pending set.
       /\ pendingReports' = pendingReports \ { rpt }
       \* Delete the completed procedure from the store.
       /\ procStore' = [procStore EXCEPT ![r] = NoneRecord]
       \* Dispatched ops, RS-side state, and server liveness unchanged.
       /\ UNCHANGED << dispatchedOps,
             rsVars,
             serverState,
             scpVars,
             locked,
             serverRegions
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
  \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, has a
  \* target server, and the OPEN command is still in dispatchedOps.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ s # None
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
             None]
        \* META, pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << metaTable,
              pendingReports,
              rsVars,
              serverState,
              scpVars,
              locked,
              serverRegions,
              procStore
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
  \* Guards: procedure is UNASSIGN or MOVE, in CONFIRM_CLOSED step, has a
  \* target server, and the CLOSE command is still in dispatchedOps.
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ s # None
        \* Reconstruct the dispatched command and verify it is still queued.
        /\ LET cmd == [ type |-> "CLOSE", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              \* Remove the undelivered command from the server's queue.
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
        \* Reset the procedure to CLOSE to retry (target server kept).
        /\ regionState' = [regionState EXCEPT ![r].procStep = "CLOSE"]
        \* META, pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << metaTable,
              pendingReports,
              rsVars,
              serverState,
              scpVars,
              locked,
              serverRegions,
              procStore
           >>

\* Handle a FAILED_OPEN report from the RS by retrying the assignment.
\* Models the non-give-up path of TRSP.confirmOpened():
\* the procedure resets to GET_ASSIGN_CANDIDATE with forceNewPlan,
\* allowing a fresh server to be chosen.  Region state and meta are
\* NOT changed (stays OPENING); the next TRSPDispatchOpen will
\* overwrite location and state.
\*
\* Pre: ASSIGN/MOVE procedure in CONFIRM_OPENED state, retry budget not
\*      exhausted, region is OPENING, and a matching FAILED_OPEN report
\*      exists from an ONLINE server.
\* Post: report consumed, retry counter incremented, TRSP reset to
\*       GET_ASSIGN_CANDIDATE.
\*
\* Source: TRSP.confirmOpened() retry branch (retries <
\*         maxAttempts); AM.regionFailedOpen() updates
\*         bookkeeping; forceNewPlan is set to choose a new server.
TRSPHandleFailedOpen(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, retries
  \* not exhausted, region is OPENING, and a matching FAILED_OPEN report
  \* exists from an ONLINE server.
  \* Do not handle FAILED_OPEN when OPENED exists for r: the RS may have
  \* succeeded on a retry (RSOpen ran after a prior RSFailOpen); prefer OPENED.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ regionState[r].retries < MaxRetries
  /\ locked[r] = FALSE
  /\ regionState[r].state = "OPENING"
  /\ ~\E rpt2 \in pendingReports: rpt2.region = r /\ rpt2.code = "OPENED"
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code = "FAILED_OPEN"
       /\ serverState[rpt.server] = "ONLINE"
       \* Reset procedure to retry with a new server, increment retry count.
       /\ regionState' =
            [regionState EXCEPT
            ![r].procStep =
            "GET_ASSIGN_CANDIDATE",
            ![r].targetServer =
            None,
            ![r].retries =
            regionState[r].retries + 1]
       \* Consume the matched report from the pending set.
       /\ pendingReports' = pendingReports \ { rpt }
       \* Clear any OPEN for r from dispatchedOps.  In the spec's interleaving,
       \* TRSPDispatchOpen may have run before this (retry to same server); that
       \* OPEN is now stale since we are picking a new server.  Without this,
       \* RSOpen could consume the stale OPEN and violate RSMasterAgreementConverse.
       \* The implementation's procedure ordering (CONFIRM_OPENED suspends until
       \* report) may prevent this race; the clear is a conservative mitigation.
       /\ dispatchedOps' =
            [t \in Servers |->
              {cmd \in dispatchedOps[t]: cmd.region # r \/ cmd.type # "OPEN"}
            ]
       \* META, RS-side state, and server liveness unchanged.
       \* Update persisted procedure step.
       /\ procStore' =
            [procStore EXCEPT
            ![r].step =
            "GET_ASSIGN_CANDIDATE",
            ![r].targetServer =
            None]
       \* ServerStateNode tracking: AM.regionFailedOpen() calls
       \* removeRegionFromServer() for the region's current location.
       \* Source: AM.regionFailedOpen().
       /\ LET loc == regionState[r].location
          IN serverRegions' =
                IF loc # None
                THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                ELSE serverRegions
       /\ UNCHANGED << metaTable, rsVars, serverState, scpVars, locked >>

\* Give up on opening a region after exhausting the retry budget.
\* Consumes the FAILED_OPEN report, transitions the region to
\* FAILED_OPEN (persistent), and clears the procedure.
\*
\* Pre: ASSIGN/MOVE procedure in CONFIRM_OPENED state, retry budget
\*      exhausted, region is OPENING, and a matching FAILED_OPEN
\*      report exists from an ONLINE server.
\* Post: region becomes FAILED_OPEN with no location or procedure,
\*       meta updated, report consumed.
\*
\* Source: TRSP.confirmOpened() give-up branch (retries >=
\*         maxAttempts); AM.regionFailedOpen(regionNode, true)
\*         persists FAILED_OPEN to meta and clears location.
TRSPGiveUpOpen(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, retries
  \* exhausted, region is OPENING, and a matching FAILED_OPEN report
  \* exists from an ONLINE server.
  \* Do not give up when OPENED exists for r: prefer OPENED (RS succeeded).
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ regionState[r].retries >= MaxRetries
  /\ locked[r] = FALSE
  /\ regionState[r].state = "OPENING"
  /\ ~\E rpt2 \in pendingReports: rpt2.region = r /\ rpt2.code = "OPENED"
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code = "FAILED_OPEN"
       /\ serverState[rpt.server] = "ONLINE"
       \* Move region to FAILED_OPEN, clear location, clear procedure fields.
       /\ regionState' =
            [regionState EXCEPT
            ![r] =
            [ state |-> "FAILED_OPEN",
              location |-> None,
              procType |-> "NONE",
              procStep |-> "IDLE",
              targetServer |-> None,
              retries |-> 0
            ]]
       \* Persist FAILED_OPEN state and cleared location in META.
       /\ metaTable' =
            [metaTable EXCEPT
            ![r] =
            [ state |-> "FAILED_OPEN", location |-> None ]]
       \* Consume the matched report from the pending set.
       /\ pendingReports' = pendingReports \ { rpt }
       \* Delete the completed procedure from the store.
       /\ procStore' = [procStore EXCEPT ![r] = NoneRecord]
       \* ServerStateNode tracking: AM.regionFailedOpen() calls
       \* removeRegionFromServer() for the region's current location.
       \* Source: AM.regionFailedOpen().
       /\ LET loc == regionState[r].location
          IN serverRegions' =
                IF loc # None
                THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                ELSE serverRegions
       \* Dispatched ops, RS-side state, and server liveness unchanged.
       /\ UNCHANGED << dispatchedOps, rsVars, serverState, scpVars, locked >>

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
  \* Guards: region is OPEN, has a location, and has no active procedure.
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # None
  /\ regionState[r].procType = "NONE"
  \* Initialize embedded UNASSIGN procedure at CLOSE step, targeting current server.
  /\ locked[r] = FALSE
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
       [ type |-> "UNASSIGN",
         step |-> "CLOSE",
         targetServer |-> regionState[r].location
       ]]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpVars,
        locked,
        serverRegions
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
  \* Guards: region is OPEN, has a location, has no active procedure,
  \* and at least one other ONLINE server exists as a destination.
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # None
  /\ regionState[r].procType = "NONE"
  /\ \E s \in Servers: s # regionState[r].location /\ serverState[s] = "ONLINE"
  \* Initialize embedded MOVE procedure at CLOSE step, targeting current server.
  /\ locked[r] = FALSE
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
       [ type |-> "MOVE",
         step |-> "CLOSE",
         targetServer |-> regionState[r].location
       ]]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpVars,
        locked,
        serverRegions
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
  \* Guards: UseReopen enabled, region is OPEN, has a server location,
  \* no active procedure.  No requirement that another ONLINE server exists.
  /\ UseReopen = TRUE
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # None
  /\ regionState[r].procType = "NONE"
  \* Initialize embedded REOPEN procedure at CLOSE step, targeting the
  \* region's current server (assignCandidate pinning).
  /\ locked[r] = FALSE
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
       [ type |-> "REOPEN",
         step |-> "CLOSE",
         targetServer |-> regionState[r].location
       ]]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpVars,
        locked,
        serverRegions
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
  \* Guards: procedure is UNASSIGN or MOVE, in CLOSE step, has a target
  \* server, and region is OPEN or CLOSING (CLOSING on retry).
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CLOSE"
  /\ regionState[r].targetServer # None
  \* Bind the target server for readability.
  /\ locked[r] = FALSE
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
        \* Pending reports, RS-side state, and server liveness unchanged.
        \* Update persisted procedure step.
        /\ procStore' = [procStore EXCEPT ![r].step = "CONFIRM_CLOSED"]
        /\ UNCHANGED << pendingReports,
              rsVars,
              serverState,
              scpVars,
              locked,
              serverRegions
           >>

\* Confirm that the region closed successfully, OR handle the case
\* where the region's server crashed (ABNORMALLY_CLOSED) during close.
\*
\* Pre: UNASSIGN or MOVE procedure in CONFIRM_CLOSED state.  Either
\*      the region is CLOSING with a matching CLOSED report from the
\*      target server (Path 1), or the region is ABNORMALLY_CLOSED
\*      (Path 2, server crash during close).
\* Post (Path 1 - normal close): region transitions to CLOSED with no
\*      location.  UNASSIGN: procedure cleared to idle.  MOVE: procedure
\*      advances to GET_ASSIGN_CANDIDATE for re-open.  Report consumed,
\*      meta updated.
\* Post (Path 2 - crash during close): procedure converts to
\*      ASSIGN/GET_ASSIGN_CANDIDATE to reopen the region (needed to
\*      process recovered edits).  Stale dispatched commands for the
\*      region are cleared.
\*
\* Two paths (disjunction):
\* Path 1 (normal close): Region is CLOSING, a CLOSED report exists.
\*   For UNASSIGN: procedure cleared.
\*   For MOVE: procedure advanced to GET_ASSIGN_CANDIDATE.
\* Path 2 (crash during close): Region is ABNORMALLY_CLOSED.
\*   The procedure self-recovers by converting to ASSIGN at
\*   GET_ASSIGN_CANDIDATE.  This models the branch in confirmClosed()
\*   where the procedure detects that the region was abnormally closed
\*   and advances to re-open it, rather than waiting for
\*   TRSPServerCrashed.  The region needs to be reopened to process
\*   recovered edits before any further operations.
\*
\* Source: TRSP.confirmClosed(); Path 1 checks
\*         regionNode.isInState(CLOSED) and completes or advances
\*         to GET_ASSIGN_CANDIDATE; Path 2 detects
\*         ABNORMALLY_CLOSED and sets forceNewPlan to reopen.
\*         AM.regionClosedAbnormally() persists the
\*         ABNORMALLY_CLOSED state to meta.
TRSPConfirmClosed(r) ==
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ locked[r] = FALSE
  /\ \/ \* --- Path 1: Normal close (CLOSING -> CLOSED) ---
        /\ regionState[r].state = "CLOSING"
        /\ \E rpt \in pendingReports:
             /\ rpt.region = r
             /\ rpt.code = "CLOSED"
             \* No serverState ONLINE guard. Models the race where a CLOSED
             \* report is processed by the master RPC handler before crash
             \* detection marks the server dead.
             \* The report must come from the server where the CLOSE was
             \* dispatched.
             /\ rpt.server = regionState[r].targetServer
             \* Do not consume if r was reopened on that server (RSOpen ran
             \* after a prior RSFailOpen); prefer OPENED.
             /\ r \notin rsOnlineRegions[rpt.server]
             \* Finalize region as CLOSED, clear location and target server.
             \* UNASSIGN: clear procedure to idle.
             \* MOVE: keep procedure, advance to GET_ASSIGN_CANDIDATE for re-open.
             /\ regionState' =
                  [regionState EXCEPT
                  ![r] =
                  [ state |-> "CLOSED",
                    location |-> None,
                    procType |->
                      IF regionState[r].procType = "UNASSIGN"
                      THEN "NONE"
                      ELSE regionState[r].procType,
                    procStep |->
                      IF regionState[r].procType = "UNASSIGN"
                      THEN "IDLE"
                      ELSE "GET_ASSIGN_CANDIDATE",
                    targetServer |-> None,
                    retries |-> 0
                  ]]
             \* Persist CLOSED state and cleared location in META.
             /\ metaTable' =
                  [metaTable EXCEPT
                  ![r] =
                  [ state |-> "CLOSED", location |-> None ]]
             \* Consume the matched report from the pending set.
             /\ pendingReports' = pendingReports \ { rpt }
             \* Procedure store: delete if UNASSIGN (complete), update if MOVE/REOPEN.
             /\ procStore' =
                  IF regionState[r].procType = "UNASSIGN"
                  THEN [procStore EXCEPT ![r] = NoneRecord]
                  ELSE [procStore EXCEPT
                    ![r].step =
                    "GET_ASSIGN_CANDIDATE",
                    ![r].targetServer =
                    None]
             \* ServerStateNode tracking: AM.regionClosedWithoutPersisting()
             \* calls removeRegionFromServer() for the region's location.
             \* Source: AM.regionClosedWithoutPersistingToMeta().
             /\ LET loc == regionState[r].location
                IN serverRegions' =
                      IF loc # None
                      THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                      ELSE serverRegions
             \* Dispatched ops, RS-side state, and server liveness unchanged.
             /\ UNCHANGED << dispatchedOps,
                   rsVars,
                   serverState,
                   scpVars,
                   locked
                >>
     \/ \* --- Path 2: Crash during close (ABNORMALLY_CLOSED) ---
        \* The target server crashed while the close was in flight.
        \* The procedure self-recovers: convert to ASSIGN at
        \* GET_ASSIGN_CANDIDATE to reopen the region (needed to
        \* process recovered edits before completing the original
        \* unassign/move).
        \*
        \* We also clear any stale dispatched commands (e.g., CLOSE)
        \* for this region from all servers.  In the implementation,
        \* RegionRemoteProcedureBase.serverCrashed() transitions the
        \* stale remote procedure to SERVER_CRASH state, which
        \* effectively abandons it.  The model must do the equivalent
        \* to prevent stale commands from racing with the new ASSIGN.
        \*
        \* Source: TRSP.confirmClosed(),
        \*         RegionRemoteProcedureBase.serverCrashed().
        /\ regionState[r].state = "ABNORMALLY_CLOSED"
        /\ regionState' =
             [regionState EXCEPT
             ![r].procType =
             "ASSIGN",
             ![r].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r].targetServer =
             None,
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
             [ type |-> "ASSIGN",
               step |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> None
             ]]
        \* ServerStateNode tracking: AM.regionClosedAbnormally() calls
        \* removeRegionFromServer() for the region's location.
        \* Source: AM.regionClosedAbnormally().
        /\ LET loc == regionState[r].location
           IN serverRegions' =
                 IF loc # None
                 THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                 ELSE serverRegions
        /\ UNCHANGED << metaTable,
              pendingReports,
              rsVars,
              serverState,
              scpVars,
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
  \* Guards: region has an active procedure and is ABNORMALLY_CLOSED.
  /\ regionState[r].procType # "NONE"
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
  /\ locked[r] = FALSE
  \* Convert the procedure to ASSIGN at GET_ASSIGN_CANDIDATE, clear target.
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "ASSIGN",
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       None,
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
       [ type |-> "ASSIGN",
         step |-> "GET_ASSIGN_CANDIDATE",
         targetServer |-> None
       ]]
  /\ UNCHANGED << metaTable,
        rsVars,
        serverState,
        scpVars,
        locked,
        serverRegions
     >>

============================================================================
