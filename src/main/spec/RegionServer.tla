--------------------------- MODULE RegionServer -------------------------------
(*
 * RegionServer-side actions for the HBase AssignmentManager.
 *
 * Contains RS-side open/close handlers (RSOpen, RSFailOpen, RSClose),
 * the zombie RS abort action (RSAbort), RS restart (RSRestart),
 * stale report cleanup (DropStaleReport), and the conditional
 * RSOpenDuplicate action.
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

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == << rsOnlineRegions >>

\* Shorthand for the SCP-related variables (used in UNCHANGED clauses).
scpVars == << scpState, scpRegions, walFenced, carryingMeta >>

\* Shorthand for master lifecycle variables (used in UNCHANGED clauses).
masterVars == << masterAlive >>

\* Shorthand for procedure/lock variables (used in UNCHANGED clauses).
procVars == << procStore, locked >>

\* Shorthand for server tracking variables (used in UNCHANGED clauses).
serverVars == << serverState, serverRegions >>

\* Shorthand for PEWorker pool variables (used in UNCHANGED clauses).
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>

---------------------------------------------------------------------------

(* Actions -- RS abort (zombie shutdown) *)
\* The zombie RS discovers it is dead (via YouAreDeadException, ZK
\* session expiry, or WAL fencing) and shuts down.  Clears RS-side
\* state (online regions and pending commands).
\*
\* This action is non-deterministic in timing: it may fire at any time
\* after ZKSessionExpire, including before or after MasterDetectCrash
\* and before or after SCPFenceWALs.  The RS discovers its own death
\* by detecting the ZK session expiry (zkNode[s] = FALSE is the
\* ground truth).
\*
\* Pre: ZK ephemeral node is gone (RS is dead) AND still has
\*      residual RS-side state.
\* Post: rsOnlineRegions[s] cleared to {}, dispatchedOps[s] cleared
\*       to {}.  Master-side state unchanged.
\*
\* Source: HRegionServer.abort() triggers the RS shutdown
\*         sequence, clearing online regions and stopping RPC
\*         handlers.
RSAbort(s) ==
  \* Guard: ZK says this RS is dead, and it still has residual state.
  /\ zkNode[s] = FALSE
  /\ rsOnlineRegions[s] # {} \/ dispatchedOps[s] # {}
  \* Purge all regions the zombie RS considers online.
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
  \* Discard all unprocessed commands queued for this RS.
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
  \* Master-side state, meta, reports, and SCP state are unaffected.
  /\ UNCHANGED << scpVars,
        serverVars,
        procVars,
        masterVars,
        regionState,
        metaTable,
        pendingReports,
        peVars,
        zkNode
     >>

---------------------------------------------------------------------------

(* Actions -- stale report cleanup *)
\* Drop a report from a crashed server.  Models the "You are dead"
\* rejection path in AM.reportRegionStateTransition().
\* Reports from crashed servers cannot be consumed by TRSPConfirmOpened,
\* TRSPConfirmClosed, or TRSPHandleFailedOpen (server ONLINE guard),
\* so this action cleans them up.
\*
\* Pre: master is alive, a pending report exists from a CRASHED server.
\* Post: the stale report is removed from pendingReports.  All other
\*       state variables unchanged.
\*
\* Source: AM.reportRegionStateTransition() rejects
\*         reports when serverNode is not in ONLINE state
\*         ("You are dead" error path).
DropStaleReport ==
  \* Master must be alive for report processing.
  /\ masterAlive = TRUE
  \* A pending report exists from a CRASHED server.
  /\ \E rpt \in pendingReports:
     /\ serverState[rpt.server] = "CRASHED"
    \* Discard the stale report.
    /\ pendingReports' = pendingReports \ { rpt }
    \* All other state variables unchanged.
    /\ UNCHANGED << scpVars,
          peVars,
          serverVars,
          procVars,
          rsVars,
          masterVars,
          regionState,
          metaTable,
          dispatchedOps,
          zkNode
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
  \* regionState[r].state = "OPENING" /\ regionState[r].location = s
  \* guard is faithful to the implementation and enables detection
  \* of ghost-region scenarios from stale OPEN commands.
  /\ serverState[s] = "ONLINE"
  \* ZK confirms server is still alive.
  /\ zkNode[s] = TRUE
  \* Region is not already online on this server.
  /\ r \notin rsOnlineRegions[s]
  \* An OPEN command for region r exists in the server's queue.
  /\ \E cmd \in dispatchedOps[s]:
       \* Command must be an OPEN command.
       /\ cmd.type = "OPEN"
       \* Command must target region r.
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
       /\ UNCHANGED << scpVars,
             peVars,
             serverVars,
             procVars,
             masterVars,
             regionState,
             metaTable,
             zkNode
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
  \* Server is ONLINE and an OPEN command for region r exists.
  /\ serverState[s] = "ONLINE"
  \* ZK confirms server is still alive.
  /\ zkNode[s] = TRUE
  \* An OPEN command for region r exists in the server's queue.
  /\ \E cmd \in dispatchedOps[s]:
       \* Command must be an OPEN command.
       /\ cmd.type = "OPEN"
       \* Command must target region r.
       /\ cmd.region = r
       \* Consume the command from the server's dispatched ops queue.
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       \* Send a FAILED_OPEN report to the master for error handling.
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "FAILED_OPEN" ] }
       \* Master-side state, online regions, and server liveness unchanged.
       /\ UNCHANGED << scpVars,
             peVars,
             serverVars,
             procVars,
             rsVars,
             masterVars,
             regionState,
             metaTable,
             zkNode
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
  \* Server is ONLINE and a CLOSE command for region r exists.
  /\ serverState[s] = "ONLINE"
  \* ZK confirms server is still alive.
  /\ zkNode[s] = TRUE
  \* A CLOSE command for region r exists in the server's queue.
  /\ \E cmd \in dispatchedOps[s]:
       \* Command must be a CLOSE command.
       /\ cmd.type = "CLOSE"
       \* Command must target region r.
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
       /\ UNCHANGED << scpVars,
             peVars,
             serverVars,
             procVars,
             masterVars,
             regionState,
             metaTable,
             zkNode
          >>

---------------------------------------------------------------------------

(* Actions -- RS-side duplicate open handler *)
\* RS receives an OPEN command for a region that is already online on
\* this server.  The command is consumed WITHOUT producing an OPENED
\* report, modeling AssignRegionHandler.process() L107-115 where the
\* handler returns early ("region already online") without calling
\* reportRegionStateTransition().
\*
\* This means the TRSP on the master side will never receive the
\* expected OPENED report and will get stuck at CONFIRM_OPENED,
\* eventually causing deadlock.
\*
\* Guarded by UseRSOpenDuplicateQuirk: disabled by default to
\* avoid deadlock in model checking.  Enable to faithfully model
\* this implementation quirk and generate counterexample traces.
\*
\* Pre: UseRSOpenDuplicateQuirk = TRUE, server is ONLINE, region r
\*      is already in rsOnlineRegions[s], an OPEN command for r exists.
\* Post: Command consumed, NO report produced, rsOnlineRegions unchanged.
\*
\* Source: AssignRegionHandler.process() L107-115.
RSOpenDuplicate(s, r) ==
  \* Quirk modeling must be enabled.
  /\ UseRSOpenDuplicateQuirk = TRUE
  \* Server is ONLINE, region is ALREADY online on this server.
  /\ serverState[s] = "ONLINE"
  \* ZK confirms server is still alive.
  /\ zkNode[s] = TRUE
  \* Region is already online on this server (duplicate).
  /\ r \in rsOnlineRegions[s]
  \* An OPEN command for region r exists in the server's queue.
  /\ \E cmd \in dispatchedOps[s]:
       \* Command must be an OPEN command.
       /\ cmd.type = "OPEN"
       \* Command must target region r.
       /\ cmd.region = r
       \* Consume the command — but produce NO report.
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       \* All other state unchanged: no report, no rsOnlineRegions change.
       /\ UNCHANGED << scpVars,
             serverVars,
             peVars,
             procVars,
             rsVars,
             masterVars,
             regionState,
             metaTable,
             pendingReports,
             zkNode
          >>

---------------------------------------------------------------------------


(* Actions -- RS restart *)
\* A process supervisor (Kubernetes, systemd, etc.) restarts a crashed
\* RegionServer.  The restarted server is empty: no regions, no pending
\* commands, no RS-side state.  Any pending reports from the previous
\* incarnation are discarded.  SCP state is reset to "NONE" and
\* walFenced is cleared.
\*
\* Design note -- RS epochs are NOT modeled explicitly.  Real HBase uses
\* ServerName (host + startcode) to distinguish incarnations. Stale
\* reports carry the old ServerName and are rejected.  Here the same
\* effect is achieved by atomic crash plus atomic restart (this action
\* purges stale reports).  An explicit epoch variable would only add
\* value if crash or restart were decomposed into non-atomic multi-step
\* sequences.
\*
\* Pre: server is CRASHED AND SCP for this server is complete or was
\*      never started.  The guard prevents premature restart while SCP
\*      is still processing regions from the crashed server.
\* Post: serverState set to ONLINE, pending reports from s discarded,
\*       SCP state reset, walFenced cleared.
\*
\* Source: Environmental assumption -- a process supervisor
\*         (Kubernetes, systemd, etc.) guarantees that crashed
\*         RegionServer processes are eventually restarted.
RSRestart(s) ==
  \* Server is CRASHED and SCP is complete (or never started).
  /\ serverState[s] = "CRASHED"
  /\ scpState[s] \in { "DONE", "NONE" }
  \* Bring the server back ONLINE.
  /\ serverState' = [serverState EXCEPT ![s] = "ONLINE"]
  \* Purge all stale pending reports from this server's prior incarnation.
  /\ pendingReports' = {rpt \in pendingReports: rpt.server # s}
  \* Clear stale commands for the restarting server.  The prior incarnation
  \* never received them (or crashed before consuming); the new process
  \* starts with an empty queue.  Without this, RSOpen could fire on
  \* stale OPEN commands, creating ghost regions (r in rsOnlineRegions
  \* but master has regionState ABNORMALLY_CLOSED / no location).
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
  \* Clear RS-side state for the restarting server.  The new process has
  \* no regions; the zombie's rsOnlineRegions was never cleared by RSAbort.
  \* Without this, RSMasterAgreementConverse fails: restarted server ONLINE
  \* but rsOnlineRegions[s] still has regions the master has ABNORMALLY_CLOSED.
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
  \* Reset SCP state for the restarting server.
  /\ scpState' = [scpState EXCEPT ![s] = "NONE"]
  \* Clear SCP region set for the restarting server.
  /\ scpRegions' = [scpRegions EXCEPT ![s] = {}]
  \* Clear WAL fencing state for the restarting server.
  /\ walFenced' = [walFenced EXCEPT ![s] = FALSE]
  \* Clear carryingMeta flag for the restarting server.
  /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
  \* Clear ServerStateNode tracking for the restarting server.
  \* In the implementation, SCP.removeServer() calls
  \* RegionStates.removeServer() which removes the ServerStateNode.
  /\ serverRegions' = [serverRegions EXCEPT ![s] = {}]
  \* Register a fresh ZK ephemeral node for the restarted server.
  \* Source: HRegionServer.run() -> createMyEphemeralNode().
  /\ zkNode' = [zkNode EXCEPT ![s] = TRUE]
  \* Region state and META unchanged.
  /\ UNCHANGED << procVars, masterVars, peVars, regionState, metaTable >>

============================================================================
