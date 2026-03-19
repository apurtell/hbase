# Master

**Source:** [`Master.tla`](../Master.tla)

Master-side actions: `GoOffline`, `MasterDetectCrash`, `MasterCrash`, `MasterRecover`, `DetectUnknownServer`.

---

```tla
------------------------------- MODULE Master ---------------------------------
```

Master-side actions for the HBase AssignmentManager:
- **`GoOffline`** — table disable (`CLOSED` → `OFFLINE`)
- **`MasterDetectCrash`** — ZK expiry → server marked `CRASHED`, SCP started
- **`MasterCrash`** — master JVM crash, in-memory state lost
- **`MasterRecover`** — master restart, rebuild from `metaTable` + `procStore`
- **`DetectUnknownServer`** — orphan region on Unknown Server

```tla
EXTENDS Types
```

All shared variables are declared as `VARIABLE` parameters so that the root module can substitute its own variables via `INSTANCE`.

```tla
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
         parentProc,
         tableEnabled
```

### Variable Shorthands

Shorthand tuples for `UNCHANGED` clauses:

```tla
rpcVars == << dispatchedOps, pendingReports >>
```

```tla
rsVars == << rsOnlineRegions >>
```

```tla
scpVars == << scpState, scpRegions, walFenced, carryingMeta >>
```

```tla
masterVars == << masterAlive >>
```

```tla
serverVars == << serverState, serverRegions >>
```

```tla
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>
```

```tla
---------------------------------------------------------------------------
```

## Master-Side Events

### `GoOffline(r)`

Transition from `CLOSED` back to `OFFLINE`. Used by `RegionStateNode.offline()` when a region is being taken fully offline (e.g., table disable).

**Pre:** region is `CLOSED` with no procedure attached, master is alive, no SCP is actively processing on any server.

**Post:** `regionState` set to `OFFLINE` with cleared location (in-memory only — `metaTable` is *not* updated). Meta retains `CLOSED`; divergence is resolved on master restart.

[`RegionStateNode.offline()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/RegionStateNode.java) sets state to `OFFLINE` and clears the region location without writing to `hbase:meta`. This creates a **deliberate divergence** between in-memory state (`OFFLINE`) and persisted state (`CLOSED` in meta). The divergence is safe because:
1. Both `OFFLINE` and `CLOSED` are *unassigned* states — no server is serving the region in either state.
2. On master restart, `MasterRecover` rebuilds in-memory state from `metaTable`, restoring the region to `CLOSED`. The `TRSPCreate` action's guard (`regionState[r].state \in {"OFFLINE", "CLOSED", ...}`) accepts both states, so recovery proceeds correctly.
3. The `GoOffline` action serves `DisableTableProcedure`: after the UNASSIGN TRSP closes the region and leaves it `CLOSED`, the procedure calls `offline()` to move it to `OFFLINE` for the "table is disabled" quiescent state.

The spec explicitly models this divergence (no meta write in `GoOffline`, meta retains `CLOSED`) to catch any invariant violations that might arise from the inconsistency.

> *Source:* `RegionStateNode.offline()` sets state to `OFFLINE` and clears the region location without writing to meta.

```tla
GoOffline(r) ==
```

Master must be alive for in-memory state operations.

```tla
  /\ masterAlive = TRUE
```

Region must exist (have an assigned keyspace).

```tla
  /\ metaTable[r].keyRange # NoRange
```

Guards: region is `CLOSED` and has no active procedure.

```tla
  /\ regionState[r].state = "CLOSED"
  /\ regionState[r].procType = "NONE"
```

No split in progress on this region (models table-level locking between `DisableTableProcedure` and `SplitTableRegionProcedure`).

```tla
  /\ parentProc[r].type = "NONE"
```

Guard: no SCP is actively processing on any server. In the implementation, `GoOffline` runs as part of `DisableTableProcedure`, which is not created during crash recovery. This prevents `GoOffline` from firing on regions that are about to be processed by SCP.

```tla
  /\ \A s \in Servers: scpState[s] \in { "NONE", "DONE" }
```

Move region to `OFFLINE` with cleared location (in-memory only).

```tla
  /\ regionState' =
       [regionState EXCEPT ![r].state = "OFFLINE", ![r].location = NoServer]
```

Meta is not updated: `RegionStateNode.offline()` does not write to meta. `metaTable` retains `CLOSED`; divergence is resolved on master restart. See Appendix D.5.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        procStore,
        rsVars,
        masterVars,
        metaTable,
        peVars,
        zkNode,
        parentProc,
        tableEnabled
     >>
```

### `MasterDetectCrash(s)`

The master detects that a RegionServer has crashed. The master's `RegionServerTracker` watcher observes that the RS's ZK ephemeral node has been deleted (`zkNode[s] = FALSE`) and calls `expireServer()`.

Regions remain in their pre-crash state (`OPEN`, `OPENING`, `CLOSING`) with location pointing at the crashed server. The RS may still be a zombie (`rsOnlineRegions` not yet cleared). This creates the window where `NoDoubleAssignment` can be violated if WALs are not fenced before regions are reassigned.

**Pre:** master is alive, server is `ONLINE` in master's view, ZK ephemeral node is gone (`zkNode[s] = FALSE`).

**Post:** `serverState` set to `CRASHED`, SCP started.

[`RegionServerTracker.processAsActiveMaster()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/RegionServerTracker.java) detects ZK child-node removal (ephemeral node deleted) and calls:
1. `ServerManager.expireServer(serverName)` → `moveFromOnlineToDeadServers(serverName)` — marks the server as dead in the master's server tracking.
2. → `AssignmentManager.submitServerCrash(serverName, shouldSplitWAL)` — creates a `ServerCrashProcedure` and submits it to the `ProcedureExecutor`.

The master's crash detection is event-driven, not polling-based: `RegionServerTracker` registers a ZK watcher on `/hbase/rs` children. When ZK notifies the watcher of a child removal, `processAsActiveMaster()` fires synchronously on the ZK event thread.

> *Source:* `RegionServerTracker.processAsActiveMaster()` detects child removal and calls `ServerManager.expireServer()`, which calls `moveFromOnlineToDeadServers()` and then `AM.submitServerCrash()`.

```tla
MasterDetectCrash(s) ==
```

Master must be alive to detect crashes.

```tla
  /\ masterAlive = TRUE
```

Master still considers this server `ONLINE`.

```tla
  /\ serverState[s] = "ONLINE"
```

ZK ephemeral node is gone — ZK has detected the RS death.

```tla
  /\ zkNode[s] = FALSE
  /\ serverState' = [serverState EXCEPT ![s] = "CRASHED"]
```

Non-deterministic: crashed server may or may not have been hosting `hbase:meta`. If `carryingMeta`, SCP must reassign meta first (`ASSIGN_META` state); otherwise proceed to `GET_REGIONS`.

Guard: only one server can be carrying meta at a time. `hbase:meta` is hosted on exactly one server, so at most one crashed server's SCP can enter the `ASSIGN_META` sub-path.

```tla
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
        parentProc,
        tableEnabled
     >>
```

```tla
---------------------------------------------------------------------------
```

## Master Crash and Recovery

### `MasterCrash`

The active master JVM crashes. All in-memory state is lost. Durable state (`metaTable`, `procStore`) and RS-side state (`rsOnlineRegions`) survive. WAL fencing (`walFenced`) survives because fencing is an HDFS-level operation, not master-level.

In-memory variables (`regionState`, `serverState`, `dispatchedOps`, `pendingReports`, `scpState`, `scpRegions`, `serverRegions`, `carryingMeta`) become stale but are left `UNCHANGED` in this action. This design choice avoids 4 problems:
1. **Invariant gating.** All invariants referencing these variables are gated on `masterAlive=TRUE`, so stale values cannot cause false violations.
2. **Action gating.** All actions using them require `masterAlive=TRUE` as a guard, so stale values cannot enable spurious actions.
3. **Recovery rebuild.** `MasterRecover` rebuilds all in-memory state from durable storage (`metaTable`, `procStore`, `zkNode`), so stale values are overwritten before any master-side action fires.
4. **Action constraint preservation.** Resetting variables to initial values here would create spurious `TransitionValid` and `SCPMonotonicity` action constraint violations, since transitions like `OPEN → OFFLINE` and `ASSIGN → NONE` would appear in the action trace even though they represent crash recovery, not state machine violations.

The regions are still open on their RegionServers and `hbase:meta` is still valid — only the master's in-memory view vanishes.

**Pre:** `masterAlive = TRUE`.
**Post:** `masterAlive = FALSE`.

> *Source:* Master JVM crash — all in-memory state is lost. In production, the standby master (if HA is enabled) takes over; the model abstracts this to a single master that crashes and recovers.

```tla
MasterCrash ==
```

Master must be alive to crash.

```tla
  /\ masterAlive = TRUE
  /\ masterAlive' = FALSE
```

In-memory master state becomes stale (gated on `masterAlive`).

```tla
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
        parentProc
     >>
```

Durable state survives.

```tla
  /\ UNCHANGED << metaTable, procStore, parentProc, tableEnabled >>
```

RS-side state survives.

```tla
  /\ UNCHANGED rsOnlineRegions
```

WAL fencing survives (HDFS-level).

```tla
  /\ UNCHANGED walFenced
```

ZK ephemeral nodes survive (external to master).

```tla
  /\ UNCHANGED zkNode
```

### `MasterRecover`

The master recovers after a crash. Rebuilds in-memory state from durable storage (`metaTable` and `procStore`).

This is modeled as a single atomic action because no external interactions happen between the recovery sub-steps (meta scan, procedure reload, `restoreSucceedState`).

The recovery path in the implementation is:
1. [`HMaster.finishActiveMasterInitialization()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/HMaster.java) — main entry point for master initialization.
2. → `AssignmentManager.start()` — scans `hbase:meta` to rebuild `RegionStateNode` objects (in-memory region state), populates `RegionStates` (master-side tracking), reads `TableState` from meta for each table.
3. → `ServerManager.findDeadServersAndProcess()` — reads ZK `/hbase/rs` children to discover live servers; any server with a `ServerStateNode` but no ZK ephemeral node is marked `CRASHED` and gets an SCP submitted.
4. → `ProcedureExecutor.start()` — calls `WALProcedureStore.load()` to deserialize all persisted procedures. For each procedure in `REPORT_SUCCEED` state, invokes `restoreSucceedState()` callback.
5. → `AssignmentManager.joinCluster()` — creates TRSPs for any regions that are not in a stable state (e.g., `OFFLINE` regions that should be `OPEN`).

Steps 2–4 are serialized (no concurrent external interactions). The model's single atomic `MasterRecover` action captures steps 2–4; step 5 is modeled by the separate `TRSPCreate` action (which fires after `masterAlive = TRUE`).

### Atomicity Limitation

`MasterRecover` is the most complex single action in the spec. It atomically rebuilds `regionState` from `metaTable`, reattaches procedures from `procStore`, applies `restoreSucceedState` for procedures at `REPORT_SUCCEED`, rebuilds `serverState` from `zkNode`, starts fresh SCPs for crashed servers, and rebuilds `serverRegions`. This models `HMaster.finishActiveMasterInitialization()` faithfully in terms of the data flow.

However, `MasterRecover` is atomic, but the implementation's recovery has multiple sub-phases (meta scan → procedure reload → `restoreSucceedState` → start assignment manager → start balancer). Between these phases, partial state exists. For example, after the meta scan but before procedure reload, the master has region state but no procedures. If an RS reports during this window, the report handler may behave differently than after full recovery. The spec's atomic recovery cannot detect such sub-phase window bugs.

The `UseStaleStateQuirk` in `MasterRecover` faithfully models the `visitMeta()`/`createServer()` bug where dead servers with stale `hbase:meta` references appear `ONLINE`, preventing SCP from starting and leaving regions on those servers unrecovered.

**Recovery steps:**
1. Rebuild `regionState` from `metaTable` (state and location only; procedure fields initially `NONE`/`IDLE`).
2. Reload procedures from `procStore` — for each region with a persisted procedure, set `procType`/`procStep`/`targetServer` from the record.
3. For procedures at `REPORT_SUCCEED`, apply `restoreSucceedState` to compute the recovered `regionState`. This replays the in-memory state that was updated (RS report consumed) but not yet persisted to `metaTable` before the crash.
4. Set `masterAlive = TRUE`.

**Pre:** `masterAlive = FALSE`.
**Post:** `masterAlive = TRUE`, `regionState` rebuilt, procedures reattached.

> *Source:* `HMaster.finishActiveMasterInitialization()` → `ProcedureExecutor.start()` → recovery of `WALProcedureStore` → `restoreSucceedState()` callbacks.

```tla
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
```

apply `restoreSucceedState` to recover the in-memory state that reflects the RS report (consumed before the crash) but was not yet persisted to `metaTable`.

```tla
                   IF procRec.step = "REPORT_SUCCEED"
                   THEN LET restored ==
                              IF procRec.transitionCode = "CLOSED"
                              THEN \* Close-path: always CLOSED, no location.
                                [ state |-> "CLOSED", location |-> NoServer ]
                              ELSE \* Open-path (OPENED or FAILED_OPEN).
                                IF UseRestoreSucceedQuirk
                                THEN \* Bug-faithful: unconditionally replay as OPENED,
```

ignoring `transitionCode`. Even a `FAILED_OPEN` gets replayed as `OPEN`.

> *Source:* `OpenRegionProcedure.restoreSucceedState()` L128–136.

```tla
                                  [ state |-> "OPEN",
                                    location |-> procRec.targetServer
                                  ]
                                ELSE \* Correct: check transitionCode.
                                  IF procRec.transitionCode = "OPENED"
                                  THEN [ state |-> "OPEN",
                                      location |-> procRec.targetServer
                                    ]
                                  ELSE \* FAILED_OPEN -- keep in failed state.
```

Location cleared: `regionFailedOpen()` calls `removeRegionFromServer()`; the give-up path (`TRSPPersistToMetaOpen`) sets `location=NoServer`. During `REPORT_SUCCEED`, `TRSPReportSucceedOpen` leaves location intact (`OPENING`), but recovery must model the eventual `FAILED_OPEN` terminal state, not the transient `REPORT_SUCCEED` window.

```tla
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
```

with state from `metaTable`.

```tla
                     [ state |-> baseState,
                       location |-> baseLoc,
                       procType |-> procRec.type,
                       procStep |-> procRec.step,
                       targetServer |-> procRec.targetServer,
                       retries |-> 0
                     ]
       ]
```

**ServerState:** read ZK ephemeral nodes to determine server liveness. On startup the master connects to ZK and gets the list of live RegionServers (via ephemeral nodes). Any server whose ephemeral node is missing is marked `CRASHED` and gets a fresh SCP. When `UseStaleStateQuirk = TRUE`, a dead server (`zkNode=FALSE`) is marked `ONLINE` if any region in `metaTable` still references it as its location — faithfully reproducing the `AM.start()` L341-348 bug where `regionStates.createServer(regionLocation)` creates a `ServerStateNode` for dead servers.

> *Source:* `HMaster.finishActiveMasterInitialization()` → `RegionServerTracker.upgrade()` → `ServerManager.findDeadServersAndProcess()`.

```tla
  /\ serverState' =
       [s \in Servers |->
         IF UseStaleStateQuirk
         THEN IF zkNode[s] = TRUE
              THEN "ONLINE"
              ELSE IF \E r \in Regions: metaTable[r].location = s
                   THEN "ONLINE"    \* BUG: stale entry
                   ELSE "CRASHED"
         ELSE IF zkNode[s] = FALSE THEN "CRASHED" ELSE "ONLINE"
       ]
```

Crashed servers get a fresh SCP at `GET_REGIONS`. Non-crashed servers get no SCP. When `UseStaleStateQuirk` is `TRUE` and a dead server appears `ONLINE` due to stale meta references, no SCP is started for it — the core of the bug (regions on the dead server are never recovered).

```tla
  /\ scpState' =
       [s \in Servers |->
         IF UseStaleStateQuirk
         THEN IF zkNode[s] = TRUE
              THEN "NONE"
              ELSE IF \E r \in Regions: metaTable[r].location = s
                   THEN "NONE"     \* BUG: no SCP for stale-online
                   ELSE "GET_REGIONS"
         ELSE IF zkNode[s] = FALSE THEN "GET_REGIONS" ELSE "NONE"
       ]
```

Reset SCP region sets (fresh SCPs will re-scan meta).

```tla
  /\ scpRegions' = [s \in Servers |-> {}]
```

Reset `carryingMeta` (meta assignment handled by fresh SCP if needed).

```tla
  /\ carryingMeta' = [s \in Servers |-> FALSE]
```

Clear in-flight RPCs (stale from pre-crash master).

```tla
  /\ dispatchedOps' = [s \in Servers |-> {}]
```

Clear pending reports (stale from pre-crash master).

```tla
  /\ pendingReports' = {}
```

Rebuild `serverRegions` from recovered `regionState`.

```tla
  /\ serverRegions' =
       [s \in Servers |->
         {r \in Regions:
           LET metaRec == metaTable[r]
               procRec == procStore[r]
           IN IF procRec # NoProcedure /\ procRec.step = "REPORT_SUCCEED"
               THEN \* Use restored location
                 IF procRec.transitionCode = "CLOSED"
                 THEN FALSE
```

`CLOSED` — no server.

```tla
                 ELSE IF UseRestoreSucceedQuirk
                   THEN procRec.targetServer = s
                   ELSE IF procRec.transitionCode = "OPENED"
                     THEN procRec.targetServer = s
                     ELSE FALSE
```

`FAILED_OPEN`: no server tracking.

```tla
               ELSE metaRec.location = s
         }
       ]
```

Master is now alive.

```tla
  /\ masterAlive' = TRUE
```

Reset PEWorker pool state on recovery.

```tla
  /\ availableWorkers' = MaxWorkers
```

Clear suspended procedures.

```tla
  /\ suspendedOnMeta' = {}
```

Clear blocked procedures.

```tla
  /\ blockedOnMeta' = {}
```

Durable state unchanged.

```tla
  /\ UNCHANGED << metaTable,
        procStore,
        rsOnlineRegions,
        walFenced,
        zkNode,
        parentProc,
        tableEnabled
     >>
```

### `DetectUnknownServer(r)`

Master discovers a region whose location references a server that has completed crash recovery (`CRASHED` + SCP `DONE`), modeling the "Unknown Server" condition.

**Most common production path:**
1. RS crashes → `serverState` becomes `CRASHED`, SCP scheduled.
2. SCP runs, processes most regions. Some are skipped when `isMatchingRegionLocation()` finds that a concurrent TRSP already moved the region's location away from the crashed server.
3. SCP completes (`DONE`). Skipped regions still reference the crashed server in meta.
4. A new RS starts on the same host:port. During registration, `DeadServer.cleanPreviousInstance()` removes the old dead entry. The server is now neither ONLINE (old startcode) nor in the dead servers list → `ServerLiveState.UNKNOWN`.
5. `checkOnlineRegionsReport()`, `DeadServerMetricRegionChore`, or `CatalogJanitor` detects regions pointing at the unknown server.
6. `closeRegionSilently()` closes the region without creating a TRSP, leaving it `CLOSED`/`OFFLINE` forever (the bug).

> *Source:* `AM.checkOnlineRegionsReport()` L1496–1546, `AM.closeRegionSilently()` L1482–1490, `DeadServer.cleanPreviousInstance()` L98–106, `CatalogJanitorReport` L50–54 (TODO: auto-fix), `HBCKServerCrashProcedure` L40–185 (manual fix).

```tla
DetectUnknownServer(r) ==
```

Master must be alive.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ metaTable[r].keyRange # NoRange
```

Region must be OPEN with no active procedure.

```tla
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].procType = "NONE"
```

No parent procedure in progress.

```tla
  /\ parentProc[r].type = "NONE"
```

Region's location must reference a crashed server whose SCP has completed — the "Unknown Server" condition.

```tla
  /\ LET s == regionState[r].location
     IN /\ s # NoServer
        /\ serverState[s] = "CRASHED"
        /\ scpState[s] = "DONE"
```

**`UseUnknownServerQuirk = TRUE`** (buggy path): close silently, no reassignment. Models `closeRegionSilently()` which sends an RPC to close but does NOT create a TRSP. Region ends up `CLOSED` with no procedure to drive reassignment.

```tla
        /\ IF UseUnknownServerQuirk
           THEN /\ regionState' =
                     [regionState EXCEPT
                     ![r].state =
                     "CLOSED",
                     ![r].location =
                     NoServer]
                /\ metaTable' =
                     [metaTable EXCEPT
                     ![r].state = "CLOSED", ![r].location = NoServer ]
                /\ serverRegions' =
                     [serverRegions EXCEPT ![s] = @ \ { r }]
                /\ UNCHANGED << procStore, dispatchedOps, pendingReports >>
```

**`UseUnknownServerQuirk = FALSE`** (correct path): close and schedule ASSIGN.

```tla
           ELSE /\ regionState' =
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
                     ![r].state = "CLOSED", ![r].location = NoServer ]
                /\ procStore' =
                     [procStore EXCEPT
                     ![r] =
                     NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE",
                                   NoServer, NoTransition)]
                /\ serverRegions' =
                     [serverRegions EXCEPT ![s] = @ \ { r }]
                /\ UNCHANGED << dispatchedOps, pendingReports >>
```

Remaining variables unchanged.

```tla
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
        parentProc,
        tableEnabled
     >>
```

```tla
============================================================================
```
