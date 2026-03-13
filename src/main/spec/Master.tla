------------------------------- MODULE Master ---------------------------------
(*
 * Master-side actions for the HBase AssignmentManager.
 *
 * Contains actions for master-driven events:
 *   GoOffline -- table disable (CLOSED -> OFFLINE)
 *   MasterDetectCrash -- ZK expiry -> server marked CRASHED, SCP started
 *   MasterCrash -- master JVM crash, in-memory state lost
 *   MasterRecover -- master restart, rebuild from metaTable + procStore
 *   DetectUnknownServer -- orphan region on Unknown Server
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

\* Shorthand for the SCP-related variables (used in UNCHANGED clauses).
scpVars == << scpState, scpRegions, walFenced, carryingMeta >>

\* Shorthand for master lifecycle variables (used in UNCHANGED clauses).
masterVars == << masterAlive >>

\* Shorthand for server tracking variables (used in UNCHANGED clauses).
serverVars == << serverState, serverRegions >>

\* Shorthand for PEWorker pool variables (used in UNCHANGED clauses).
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>

---------------------------------------------------------------------------

(* Actions -- master-side events *)
\* Transition from CLOSED back to OFFLINE.
\* Used by RegionStateNode.offline() when a region is being taken fully
\* offline (e.g., table disable).
\* Pre: region is CLOSED with no procedure attached, master is alive,
\*      no SCP is actively processing on any server.
\* Post: regionState set to OFFLINE with cleared location (in-memory
\*       only -- metaTable is NOT updated).  META retains CLOSED;
\*       divergence is resolved on master restart.
\*
\* Source: RegionStateNode.offline() sets state to OFFLINE
\*         and clears the region location without writing to meta.
GoOffline(r) ==
  \* Master must be alive for in-memory state operations.
  /\ masterAlive = TRUE
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Guards: region is CLOSED and has no active procedure.
  /\ regionState[r].state = "CLOSED"
  /\ regionState[r].procType = "NONE"
  \* No split in progress on this region (models table-level locking
  \* between DisableTableProcedure and SplitTableRegionProcedure).
  /\ parentProc[r].type = "NONE"
  \* Guard: no SCP is actively processing on any server.
  \* In the implementation, GoOffline runs as part of DisableTableProcedure,
  \* which is not created during crash recovery.  This prevents GoOffline
  \* from firing on regions that are about to be processed by SCP.
  /\ \A s \in Servers: scpState[s] \in { "NONE", "DONE" }
  \* Move region to OFFLINE with cleared location (in-memory only).
  /\ regionState' =
       [regionState EXCEPT ![r].state = "OFFLINE", ![r].location = NoServer]
  \* Meta is NOT updated: RegionStateNode.offline()
  \* does not write to meta.  metaTable retains CLOSED; divergence is
  \* resolved on master restart.  See Appendix D.5.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        procStore,
        rsVars,
        masterVars,
        metaTable,
        peVars,
        zkNode,
        regionKeyRange,
        parentProc
     >>

\* The master detects that a RegionServer has crashed.  The master's
\* RegionServerTracker watcher observes that the RS's ZK ephemeral
\* node has been deleted (zkNode[s] = FALSE) and calls expireServer().
\*
\* Regions remain in their pre-crash state (OPEN, OPENING, CLOSING)
\* with location pointing at the crashed server.  The RS may still
\* be a zombie (rsOnlineRegions not yet cleared).  This creates the
\* window where NoDoubleAssignment can be violated if WALs are not
\* fenced before regions are reassigned.
\*
\* Pre: master is alive, server is ONLINE in master's view,
\*      ZK ephemeral node is gone (zkNode[s] = FALSE).
\* Post: serverState set to CRASHED, SCP started.
\*
\* Impl state: SERVER_CRASH_START (=1), absorbed into this action.
\*   See SCP.tla header for the full enum traceability table.
\*
\* Source: RegionServerTracker.processAsActiveMaster() detects
\*         child removal and calls ServerManager.expireServer(),
\*         which calls moveFromOnlineToDeadServers() and then
\*         AM.submitServerCrash().
MasterDetectCrash(s) ==
  \* Master must be alive to detect crashes.
  /\ masterAlive = TRUE
  \* Master still considers this server ONLINE.
  /\ serverState[s] = "ONLINE"
  \* ZK ephemeral node is gone -- ZK has detected the RS death.
  /\ zkNode[s] = FALSE
  /\ serverState' = [serverState EXCEPT ![s] = "CRASHED"]
  \* Non-deterministic: crashed server may or may not have been
  \* hosting hbase:meta.  If carryingMeta, SCP must reassign meta
  \* first (ASSIGN_META state); otherwise proceed to GET_REGIONS.
  \* Guard: only one server can be carrying meta at a time.
  \* hbase:meta is hosted on exactly one server, so at most one
  \* crashed server's SCP can enter the ASSIGN_META sub-path.
  /\ \/ /\ \A t \in Servers \ { s }: carryingMeta[t] = FALSE
        /\ carryingMeta' = [carryingMeta EXCEPT ![s] = TRUE]
        /\ scpState' = [scpState EXCEPT ![s] = "ASSIGN_META"]
     \/ /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
        /\ scpState' = [scpState EXCEPT ![s] = "GET_REGIONS"]
  /\ UNCHANGED << rpcVars,
        peVars,
        procStore,
        rsVars,
        masterVars,
        regionState,
        metaTable,
        scpRegions,
        walFenced,
        serverRegions,
        zkNode,
        regionKeyRange,
        parentProc
     >>

---------------------------------------------------------------------------

(* Actions -- master crash and recovery *)
\* The active master JVM crashes.  All in-memory state is lost.
\* Durable state (metaTable, procStore) and RS-side state
\* (rsOnlineRegions) survive.  WAL fencing (walFenced) survives
\* because fencing is an HDFS-level operation, not master-level.
\*
\* In-memory variables (regionState, serverState, dispatchedOps,
\* pendingReports, scpState, scpRegions, serverRegions,
\* carryingMeta) become stale but are left UNCHANGED because:
\*   1. All invariants referencing them are gated on masterAlive=TRUE.
\*   2. All actions using them require masterAlive=TRUE as a guard.
\*   3. MasterRecover rebuilds them from durable state.
\*   4. Mutating them here would create spurious action-constraint
\*      violations (TransitionValid, SCPMonotonicity).
\* The regions are still open on their RegionServers and hbase:meta
\* is still valid -- only the master's in-memory view vanishes.
\*
\* Pre: masterAlive = TRUE.
\* Post: masterAlive = FALSE.
\*
\* Source: Master JVM crash -- all in-memory state is lost.
MasterCrash ==
  \* Master must be alive to crash
  /\ masterAlive = TRUE
  /\ masterAlive' = FALSE
  \* In-memory master state becomes stale (gated on masterAlive).
  /\ UNCHANGED << regionState,
        serverState,
        dispatchedOps,
        pendingReports,
        scpState,
        scpRegions,
        serverRegions,
        carryingMeta,
        availableWorkers,
        suspendedOnMeta,
        blockedOnMeta,
        regionKeyRange,
        parentProc
     >>
  \* Durable state survives.
  /\ UNCHANGED << metaTable, procStore, parentProc >>
  \* RS-side state survives.
  /\ UNCHANGED rsOnlineRegions
  \* WAL fencing survives (HDFS-level).
  /\ UNCHANGED walFenced
  \* ZK ephemeral nodes survive (external to master).
  /\ UNCHANGED zkNode

\* The master recovers after a crash.  Rebuilds in-memory state from
\* durable storage (metaTable and procStore).
\*
\* This is modeled as a single atomic action because no external
\* interactions happen between the recovery sub-steps (meta scan,
\* procedure reload, restoreSucceedState).
\*
\* Recovery steps:
\*   1. Rebuild regionState from metaTable (state and location only;
\*      procedure fields initially NONE/IDLE).
\*   2. Reload procedures from procStore -- for each region with a
\*      persisted procedure, set procType/procStep/targetServer from
\*      the record.
\*   3. For procedures at REPORT_SUCCEED, apply restoreSucceedState
\*      to compute the recovered regionState.  This replays the
\*      in-memory state that was updated (RS report consumed) but
\*      not yet persisted to metaTable before the crash.
\*   4. Set masterAlive = TRUE.
\*
\* Pre: masterAlive = FALSE.
\* Post: masterAlive = TRUE, regionState rebuilt, procedures reattached.
\*
\* Source: HMaster.finishActiveMasterInitialization() ->
\*         ProcedureExecutor.start() -> recovery of WALProcedureStore ->
\*         restoreSucceedState() callbacks.
MasterRecover ==
  /\ masterAlive = FALSE
  /\ regionState' =
       [r \in Regions |->
         LET metaRec == metaTable[r]
             procRec == procStore[r]
         IN \* Step 1: Base state from metaTable.
             IF procRec = NoProcedure
             THEN \* No procedure -- just restore from meta.
               [ state |-> metaRec.state,
                 location |-> metaRec.location,
                 procType |-> "NONE",
                 procStep |-> "IDLE",
                 targetServer |-> NoServer,
                 retries |-> 0
               ]
             ELSE \* Step 2: Procedure exists -- attach it.
               LET baseState == metaRec.state
                   baseLoc == metaRec.location
               IN \* Step 3: If procedure was at REPORT_SUCCEED,
                   \* apply restoreSucceedState to recover the in-memory
                   \* state that reflects the RS report (consumed before
                   \* the crash) but was not yet persisted to metaTable.
                   IF procRec.step = "REPORT_SUCCEED"
                   THEN LET restored ==
                              IF procRec.transitionCode = "CLOSED"
                              THEN \* Close-path: always CLOSED, no location.
                                [ state |-> "CLOSED", location |-> NoServer ]
                              ELSE \* Open-path (OPENED or FAILED_OPEN).
                                IF UseRestoreSucceedQuirk
                                THEN \* Bug-faithful: unconditionally replay as OPENED,
                                  \* ignoring transitionCode.  Even a FAILED_OPEN
                                  \* gets replayed as OPEN.
                                  \* Source: OpenRegionProcedure.restoreSucceedState()
                                  \*         L128-136.
                                  [ state |-> "OPEN",
                                    location |-> procRec.targetServer
                                  ]
                                ELSE \* Correct: check transitionCode.
                                  IF procRec.transitionCode = "OPENED"
                                  THEN [ state |-> "OPEN",
                                      location |-> procRec.targetServer
                                    ]
                                  ELSE \* FAILED_OPEN -- keep in failed state.
                                    \* Location cleared: regionFailedOpen() calls
                                    \* removeRegionFromServer(); the give-up path
                                    \* (TRSPPersistToMetaOpen) sets location=NoServer.
                                    \* During REPORT_SUCCEED, TRSPReportSucceedOpen
                                    \* leaves location intact (OPENING), but recovery
                                    \* must model the eventual FAILED_OPEN terminal
                                    \* state, not the transient REPORT_SUCCEED window.
                                    [ state |-> "FAILED_OPEN",
                                      location |-> NoServer
                                    ]
                     IN [ state |-> restored.state,
                           location |-> restored.location,
                           procType |-> procRec.type,
                           procStep |-> procRec.step,
                           targetServer |-> procRec.targetServer,
                           retries |-> 0
                         ]
                   ELSE \* Procedure not at REPORT_SUCCEED -- just re-attach
                     \* with state from metaTable.
                     [ state |-> baseState,
                       location |-> baseLoc,
                       procType |-> procRec.type,
                       procStep |-> procRec.step,
                       targetServer |-> procRec.targetServer,
                       retries |-> 0
                     ]
       ]
  \* ServerState: read ZK ephemeral nodes to determine server liveness.
  \* On startup the master connects to ZK and gets the list of live
  \* RegionServers (via ephemeral nodes). Any server whose ephemeral
  \* node is missing is marked CRASHED and gets a fresh SCP.
  \*
  \* Source: HMaster.finishActiveMasterInitialization() ->
  \*         RegionServerTracker.upgrade() ->
  \*         ServerManager.findDeadServersAndProcess()
  /\ serverState' =
       [s \in Servers |-> IF zkNode[s] = FALSE THEN "CRASHED" ELSE "ONLINE"
       ]
  \* Crashed servers get a fresh SCP at GET_REGIONS.
  \* Non-crashed servers get no SCP.
  /\ scpState' =
       [s \in Servers |-> IF zkNode[s] = FALSE THEN "GET_REGIONS" ELSE "NONE"
       ]
  \* Reset SCP region sets (fresh SCPs will re-scan meta).
  /\ scpRegions' = [s \in Servers |-> {}]
  \* Reset carryingMeta (meta assignment handled by fresh SCP if needed).
  /\ carryingMeta' = [s \in Servers |-> FALSE]
  \* Clear in-flight RPCs (stale from pre-crash master).
  /\ dispatchedOps' = [s \in Servers |-> {}]
  \* Clear pending reports (stale from pre-crash master).
  /\ pendingReports' = {}
  \* Rebuild serverRegions from recovered regionState.
  /\ serverRegions' =
       [s \in Servers |->
         {r \in Regions:
           LET metaRec == metaTable[r]
               procRec == procStore[r]
           IN IF procRec # NoProcedure /\ procRec.step = "REPORT_SUCCEED"
               THEN \* Use restored location
                 IF procRec.transitionCode = "CLOSED"
                 THEN FALSE
                 \* CLOSED -- no server
                 ELSE IF UseRestoreSucceedQuirk
                   THEN procRec.targetServer = s
                   ELSE IF procRec.transitionCode = "OPENED"
                     THEN procRec.targetServer = s
                     ELSE FALSE
               \* FAILED_OPEN: no server tracking
               ELSE metaRec.location = s
         }
       ]
  \* Master is now alive.
  /\ masterAlive' = TRUE
  \* Reset PEWorker pool state on recovery.
  /\ availableWorkers' = MaxWorkers
  \* Clear suspended procedures.
  /\ suspendedOnMeta' = {}
  \* Clear blocked procedures.
  /\ blockedOnMeta' = {}
  \* Durable state unchanged.
  /\ UNCHANGED << metaTable,
        procStore,
        rsOnlineRegions,
        walFenced,
        zkNode,
        regionKeyRange,
        parentProc
     >>

\* DetectUnknownServer: Master discovers a region whose location
\* references a server that has completed crash recovery (CRASHED +
\* SCP DONE), modeling the "Unknown Server" condition.
\*
\* Most common production path:
\*   1. RS crashes -> serverState becomes CRASHED, SCP scheduled.
\*   2. SCP runs, processes most regions.  Some are skipped when
\*      isMatchingRegionLocation() finds that a concurrent TRSP
\*      already moved the region's location away from the crashed
\*      server.
\*   3. SCP completes (DONE).  Skipped regions still reference the
\*      crashed server in meta.
\*   4. A new RS starts on the same host:port.  During registration,
\*      DeadServer.cleanPreviousInstance() removes the old dead entry.
\*      The server is now neither ONLINE (old startcode) nor in the
\*      dead servers list -> ServerLiveState.UNKNOWN.
\*   5. checkOnlineRegionsReport(), DeadServerMetricRegionChore, or
\*      CatalogJanitor detects regions pointing at the unknown server.
\*   6. closeRegionSilently() closes the region without creating a
\*      TRSP, leaving it CLOSED/OFFLINE forever (the bug).
\*
\* Source: AM.checkOnlineRegionsReport() L1496-1546,
\*         AM.closeRegionSilently() L1482-1490,
\*         DeadServer.cleanPreviousInstance() L98-106,
\*         CatalogJanitorReport L50-54 (TODO: auto-fix),
\*         HBCKServerCrashProcedure L40-185 (manual fix).
DetectUnknownServer(r) ==
  \* Master must be alive.
  /\ masterAlive = TRUE
  \* A PEWorker thread must be available.
  /\ availableWorkers > 0
  \* Region must exist (have an assigned keyspace).
  /\ regionKeyRange[r] # NoRange
  \* Region must be OPEN with no active procedure.
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].procType = "NONE"
  \* No parent procedure in progress.
  /\ parentProc[r].type = "NONE"
  \* Region's location must reference a crashed server whose SCP
  \* has completed -- the "Unknown Server" condition.
  /\ LET s == regionState[r].location
     IN /\ s # NoServer
        /\ serverState[s] = "CRASHED"
        /\ scpState[s] = "DONE"
        /\ IF UseUnknownServerQuirk
           THEN \* Buggy path: close silently, no reassignment.
                \* Models closeRegionSilently() which sends an RPC
                \* to close but does NOT create a TRSP.  Region ends
                \* up CLOSED with no procedure to drive reassignment.
                /\ regionState' =
                     [regionState EXCEPT
                     ![r].state =
                     "CLOSED",
                     ![r].location =
                     NoServer]
                /\ metaTable' =
                     [metaTable EXCEPT
                     ![r] =
                     [ state |-> "CLOSED", location |-> NoServer ]]
                /\ serverRegions' =
                     [serverRegions EXCEPT ![s] = @ \ { r }]
                /\ UNCHANGED << procStore, dispatchedOps, pendingReports >>
           ELSE \* Correct path: close and schedule ASSIGN.
                /\ regionState' =
                     [regionState EXCEPT
                     ![r].state =
                     "CLOSED",
                     ![r].location =
                     NoServer,
                     ![r].procType =
                     "ASSIGN",
                     ![r].procStep =
                     "GET_ASSIGN_CANDIDATE",
                     ![r].targetServer =
                     NoServer,
                     ![r].retries =
                     0]
                /\ metaTable' =
                     [metaTable EXCEPT
                     ![r] =
                     [ state |-> "CLOSED", location |-> NoServer ]]
                /\ procStore' =
                     [procStore EXCEPT
                     ![r] =
                     NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE",
                                   NoServer, NoTransition)]
                /\ serverRegions' =
                     [serverRegions EXCEPT ![s] = @ \ { r }]
                /\ UNCHANGED << dispatchedOps, pendingReports >>
  /\ UNCHANGED << scpState,
        scpRegions,
        walFenced,
        carryingMeta,
        rsOnlineRegions,
        masterAlive,
        serverState,
        availableWorkers,
        suspendedOnMeta,
        blockedOnMeta,
        zkNode,
        regionKeyRange,
        parentProc
     >>

============================================================================
