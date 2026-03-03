--------------------------- MODULE RegionServer -------------------------------
(*
 * RegionServer-side actions for the HBase AssignmentManager.
 *
 * Contains RS-side open/close handlers (RSOpen, RSFailOpen, RSClose),
 * the zombie RS abort action (RSAbort), and stale report cleanup
 * (DropStaleReport).
 *
 * This module declares all shared variables as CONSTANT parameters.
 * The root AssignmentManager module instantiates it with
 * INSTANCE RegionServer WITH ... substituting the actual variables.
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

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == << rsOnlineRegions >>

\* Shorthand for the SCP-related variables (used in UNCHANGED clauses).
scpVars == << scpState, scpRegions, walFenced, carryingMeta >>

---------------------------------------------------------------------------

(* Actions -- RS abort (zombie shutdown) *)
\* The zombie RS discovers it is dead (via YouAreDeadException, ZK
\* session expiry, or WAL fencing) and shuts down.  Clears RS-side
\* state (online regions and pending commands).
\*
\* This action is non-deterministic in timing: it may fire at any time
\* after MasterDetectCrash, including before or after SCPFenceWALs.
\* walFenced[s] = TRUE is a sufficient but not necessary enabling
\* condition (the RS might self-detect death before WAL fencing).
\* WF on RSAbort ensures the zombie eventually shuts down.
\*
\* Pre: server is CRASHED AND still has residual RS-side state
\*      (rsOnlineRegions[s] non-empty OR dispatchedOps[s] non-empty).
\* Post: rsOnlineRegions[s] cleared to {}, dispatchedOps[s] cleared
\*       to {}.  Master-side state (regionState, metaTable,
\*       pendingReports, serverState, scpVars) unchanged.
\*
\* Source: HRegionServer.abort() triggers the RS shutdown
\*         sequence, clearing online regions and stopping RPC
\*         handlers.
RSAbort(s) ==
  \* Guards: server is CRASHED and still has residual RS-side state.
  /\ serverState[s] = "CRASHED"
  /\ rsOnlineRegions[s] # {} \/ dispatchedOps[s] # {}
  \* Purge all regions the zombie RS considers online.
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
  \* Discard all unprocessed commands queued for this RS.
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
  \* Master-side state, meta, reports, and SCP state are unaffected.
  /\ UNCHANGED << regionState,
        metaTable,
        pendingReports,
        serverState,
        scpVars,
        locked,
        serverRegions,
        procStore
     >>

---------------------------------------------------------------------------

(* Actions -- stale report cleanup *)
\* Drop a report from a crashed server.  Models the "You are dead"
\* rejection path in AM.reportRegionStateTransition().
\* Reports from crashed servers cannot be consumed by TRSPConfirmOpened,
\* TRSPConfirmClosed, or TRSPHandleFailedOpen (server ONLINE guard),
\* so this action cleans them up.
\*
\* Pre: a pending report exists from a CRASHED server.
\* Post: the stale report is removed from pendingReports.  All other
\*       state variables unchanged.
\*
\* Source: AM.reportRegionStateTransition() rejects
\*         reports when serverNode is not in ONLINE state
\*         ("You are dead" error path).
DropStaleReport ==
  \* Guard: a pending report exists from a CRASHED server.
    \E rpt \in pendingReports:
    /\ serverState[rpt.server] = "CRASHED"
    \* Discard the stale report.
    /\ pendingReports' = pendingReports \ { rpt }
    \* All other state variables unchanged.
    /\ UNCHANGED << regionState,
          metaTable,
          dispatchedOps,
          rsVars,
          serverState,
          scpVars,
          locked,
          serverRegions,
          procStore
       >>

---------------------------------------------------------------------------

(* Actions -- RS-side open handler *)
\* RS atomically receives an OPEN command, opens the region, adds it
\* to rsOnlineRegions, and reports OPENED to the master.  Merges the
\* former RSReceiveOpen + RSCompleteOpen into a single action because
\* the intermediate state (command consumed, RS working on open) is
\* not observable by the master and produces the same crash-recovery
\* outcome as the pre-receive state.
\*
\* Pre: Server is ONLINE, an OPEN command for region r exists in
\*      dispatchedOps[s].
\* Post: Command consumed, r added to rsOnlineRegions[s], OPENED
\*       report produced in pendingReports.
\*
\* Source: AssignRegionHandler.process() success path:
\*         opens the region via HRegion.openHRegion(), adds it
\*         to the RS's online regions, and reports OPENED via
\*         reportRegionStateTransition() RPC.
RSOpen(s, r) ==
  \* Guards: server is ONLINE, region is NOT already online on this
  \* server, and an OPEN command for region r exists.
  \* The r \notin rsOnlineRegions[s] guard matches the implementation:
  \* AssignRegionHandler.process() returns without reporting OPENED
  \* if the region is already online.
  \* No regionState guard: the RS does not consult the master state
  \* before processing an OPEN command.  Removing the former
  \* regionState[r].state = "OPENING" /\\ regionState[r].location = s
  \* guard is faithful to the implementation and enables detection
  \* of ghost-region scenarios from stale OPEN commands.
  /\ serverState[s] = "ONLINE"
  /\ r \notin rsOnlineRegions[s]
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "OPEN"
       /\ cmd.region = r
       \* Consume the command from the server's dispatched ops queue.
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       \* Add the region to the server's set of online regions.
       /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = @ \cup { r }]
       \* Send an OPENED report to the master for procedure confirmation.
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "OPENED" ] }
       \* Master-side state and server liveness unchanged.
       /\ UNCHANGED << regionState,
             metaTable,
             serverState,
             scpVars,
             locked,
             serverRegions,
             procStore
          >>

\* RS atomically receives an OPEN command but fails to open the
\* region.  The command is consumed and a FAILED_OPEN report is
\* produced.  The region is NOT added to rsOnlineRegions.
\*
\* Pre: Server is ONLINE, an OPEN command for region r exists in
\*      dispatchedOps[s].
\* Post: Command consumed, FAILED_OPEN report produced.
\*
\* Source: AssignRegionHandler.process() failure path:
\*         AssignRegionHandler.cleanUpAndReportFailure()
\*         reports FAILED_OPEN via reportRegionStateTransition()
\*         RPC; the region is NOT added to online regions.
RSFailOpen(s, r) ==
  \* Guards: server is ONLINE and an OPEN command for region r exists.
  /\ serverState[s] = "ONLINE"
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "OPEN"
       /\ cmd.region = r
       \* Consume the command from the server's dispatched ops queue.
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       \* Send a FAILED_OPEN report to the master for error handling.
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "FAILED_OPEN" ] }
       \* Master-side state, online regions, and server liveness unchanged.
       /\ UNCHANGED << regionState,
             metaTable,
             rsOnlineRegions,
             serverState,
             scpVars,
             locked,
             serverRegions,
             procStore
          >>

---------------------------------------------------------------------------

(* Actions -- RS-side close handler *)
\* RS atomically receives a CLOSE command, closes the region, removes
\* it from rsOnlineRegions, and reports CLOSED to the master.  Merges
\* the former RSReceiveClose + RSCompleteClose into a single action
\* (same rationale as RSOpen -- see RS-side open handler comment).
\*
\* Pre: Server is ONLINE, a CLOSE command for region r exists in
\*      dispatchedOps[s].
\* Post: Command consumed, r removed from rsOnlineRegions[s], CLOSED
\*       report produced in pendingReports.
\*
\* Source: UnassignRegionHandler.process() success path:
\*         closes the region via HRegion.close(), removes it from
\*         the RS's online regions, and reports CLOSED via
\*         reportRegionStateTransition() RPC.
RSClose(s, r) ==
  \* Guards: server is ONLINE and a CLOSE command for region r exists.
  /\ serverState[s] = "ONLINE"
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "CLOSE"
       /\ cmd.region = r
       \* Consume the command from the server's dispatched ops queue.
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       \* Remove the region from the server's set of online regions.
       /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
       \* Send a CLOSED report to the master for procedure confirmation.
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "CLOSED" ] }
       \* Master-side state and server liveness unchanged.
       /\ UNCHANGED << regionState,
             metaTable,
             serverState,
             scpVars,
             locked,
             serverRegions,
             procStore
          >>

============================================================================
