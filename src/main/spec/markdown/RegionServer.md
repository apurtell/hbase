# RegionServer

**Source:** [`RegionServer.tla`](../RegionServer.tla)

RS-side handlers: open, fail-open, close, abort, restart, duplicate-open, close-not-found, stale report drop.

---

```tla
--------------------------- MODULE RegionServer -------------------------------
```

RegionServer-side actions for the HBase AssignmentManager:
- **`RSOpen`** / **`RSFailOpen`** — RS-side open handlers
- **`RSClose`** — RS-side close handler
- **`RSAbort`** — zombie RS shutdown
- **`RSRestart`** — process supervisor restart
- **`DropStaleReport`** — stale report cleanup
- **`RSOpenDuplicate`** — conditional duplicate-open handler
- **`RSCloseNotFound`** — conditional close-not-found handler

### Atomicity Merging

In the implementation, open and close operations are multi-step: the RS receives an RPC, spawns a handler thread, performs I/O (HRegion open/close), updates internal tracking, and reports back via a separate RPC. The model merges these into single atomic actions (`RSOpen`, `RSClose`, `RSFailOpen`). This is sound because:

1. **Intermediate state not observable by master.** Between command consumption and report transmission, the RS is performing local I/O. The master does not poll for progress — it waits for the `reportRegionStateTransition()` RPC. No master-side action can distinguish "RS is still opening" from "RS has finished but report is in flight."

2. **Same crash-recovery outcome.** If the RS crashes during the intermediate state, the master observes no report (same as if the command had never been consumed). The SCP will handle the crash identically in both cases: mark the region `ABNORMALLY_CLOSED` and create a fresh `ASSIGN`. The atomic model captures every reachable post-crash state.

3. **State space reduction.** Separating command consumption from completion would add O(|Servers| × |Regions|) intermediate states per action — a multiplicative explosion that provides no additional safety coverage.

> *Source:* [`AssignRegionHandler.java`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/handler/AssignRegionHandler.java) (open handler), [`UnassignRegionHandler.java`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/handler/UnassignRegionHandler.java) (close handler). Both extend `EventHandler` and execute asynchronously on the RS's handler thread pool.

### RS Epoch Modeling

> **Design note — RS epochs are *not* modeled explicitly.** Real HBase uses `ServerName` (host + port + startcode) to distinguish incarnations. Each RS restart gets a new `startcode` (system timestamp). Stale reports carry the old `ServerName` and are rejected by `AM.reportRegionStateTransition()` (`serverNode.getServerName().equals(serverName)` check). The model achieves the same effect through atomic crash (`RSAbort` purges zombie state) + atomic restart (`RSRestart` purges stale reports and dispatched ops). An explicit epoch variable would only add value if crash or restart were decomposed into non-atomic multi-step sequences — which would increase state space without covering additional safety properties.

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

```tla
rsVars == << rsOnlineRegions >>
```

```tla
scpVars == << scpState, scpRegions, walFenced, carryingMeta, parentProc, tableEnabled >>
```

```tla
masterVars == << masterAlive >>
```

Shorthand for server tracking variables:

```tla
serverVars == << serverState, serverRegions >>
```

```tla
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>
```

```tla
---------------------------------------------------------------------------
```

## RS Abort (Zombie Shutdown)

### `RSAbort(s)`

The zombie RS discovers it is dead (via `YouAreDeadException`, ZK session expiry, or WAL fencing) and shuts down. Clears RS-side state (online regions and pending commands).

This action is non-deterministic in timing: it may fire at any time after `ZKSessionExpire`, including before or after `MasterDetectCrash` and before or after `SCPFenceWALs`. The RS discovers its own death by detecting the ZK session expiry (`zkNode[s] = FALSE` is the ground truth).

The RS process detects its own death through three paths:
1. **`YouAreDeadException`** — [`RSRpcServices.killRegionServer()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/RSRpcServices.java) is called by the master via `RegionServerTracker` → `ServerManager.expireServer()`. The RS receives an RPC telling it to die.
2. **ZK session expiry** — the RS's own ZooKeeper client fires a `Disconnected`/`Expired` event. The RS calls [`HRegionServer.abort()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/HRegionServer.java).
3. **WAL fencing** — the RS tries to write to a WAL whose HDFS lease has been revoked by `SplitWALManager`. The `IOException` triggers `HRegionServer.abort()`.

In all three paths, `HRegionServer.abort()` sets a flag, the RS's main run loop detects it, and the RS shuts down: it calls `closeAllRegions()` (which closes all online regions without reporting to the master — the master considers them dead) and stops RPC handlers. The model's atomic `RSAbort` captures the essential outcome: `rsOnlineRegions[s] = {}`, `dispatchedOps[s] = {}`.

**Pre:** ZK ephemeral node is gone (RS is dead) AND still has residual RS-side state.
**Post:** `rsOnlineRegions[s]` cleared to `{}`, `dispatchedOps[s]` cleared to `{}`. Master-side state unchanged.

> *Source:* `HRegionServer.abort()` triggers the RS shutdown sequence, clearing online regions and stopping RPC handlers.

```tla
RSAbort(s) ==
```

Guard: ZK says this RS is dead, and it still has residual state.

```tla
  /\ zkNode[s] = FALSE
  /\ rsOnlineRegions[s] # {} \/ dispatchedOps[s] # {}
```

Purge all regions the zombie RS considers online.

```tla
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
```

Discard all unprocessed commands queued for this RS.

```tla
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
```

Master-side state, meta, reports, and SCP state are unaffected.

```tla
  /\ UNCHANGED << scpVars,
        serverVars,
        procStore,
        masterVars,
        regionState,
        metaTable,
        pendingReports,
        peVars,
        zkNode
     >>
```

```tla
---------------------------------------------------------------------------
```

## Stale Report Cleanup

### `DropStaleReport`

Drop a report from a crashed server. Models the *"You are dead"* rejection path in `AM.reportRegionStateTransition()`. Reports from crashed servers cannot be consumed by `TRSPConfirmOpened`, `TRSPConfirmClosed`, or `TRSPHandleFailedOpen` (server `ONLINE` guard), so this action cleans them up.

**Pre:** master is alive, a pending report exists from a `CRASHED` server.
**Post:** the stale report is removed from `pendingReports`. All other state variables unchanged.

> *Source:* `AM.reportRegionStateTransition()` rejects reports when `serverNode` is not in `ONLINE` state (*"You are dead"* error path).

```tla
DropStaleReport ==
```

Master must be alive for report processing.

```tla
  /\ masterAlive = TRUE
```

A pending report exists from a `CRASHED` server.

```tla
  /\ \E rpt \in pendingReports:
     /\ serverState[rpt.server] = "CRASHED"
```

Discard the stale report.

```tla
    /\ pendingReports' = pendingReports \ { rpt }
```

All other state variables unchanged.

```tla
    /\ UNCHANGED << scpVars,
          peVars,
          serverVars,
          procStore,
          rsVars,
          masterVars,
          regionState,
          metaTable,
          dispatchedOps,
          zkNode
       >>
```

```tla
---------------------------------------------------------------------------
```

## RS-Side Open Handler

### `RSOpen(s, r)`

RS atomically receives an `OPEN` command, opens the region, adds it to `rsOnlineRegions`, and reports `OPENED` to the master. Merges the former `RSReceiveOpen` + `RSCompleteOpen` into a single action because the intermediate state (command consumed, RS working on open) is not observable by the master and produces the same crash-recovery outcome as the pre-receive state.

**Pre:** Server is `ONLINE`, an `OPEN` command for region `r` exists in `dispatchedOps[s]`.
**Post:** Command consumed, `r` added to `rsOnlineRegions[s]`, `OPENED` report produced in `pendingReports`.

> *Source:* `AssignRegionHandler.process()` success path: opens the region via `HRegion.openHRegion()`, adds it to the RS's online regions, and reports `OPENED` via `reportRegionStateTransition()` RPC.

```tla
RSOpen(s, r) ==
```

Guards: server is `ONLINE`, region is *not* already online on this server, and an `OPEN` command for region `r` exists. The `r \notin rsOnlineRegions[s]` guard matches the implementation: `AssignRegionHandler.process()` returns without reporting `OPENED` if the region is already online. No `regionState` guard: the RS does not consult the master state before processing an `OPEN` command. Removing the former `regionState[r].state = "OPENING" /\ regionState[r].location = s` guard is faithful to the implementation and enables detection of ghost-region scenarios from stale `OPEN` commands.

[`AssignRegionHandler.process()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/handler/AssignRegionHandler.java) calls `HRegion.openHRegion()` which performs full region initialization (HFile reader setup, WAL recovery, MemStore initialization, coprocessor loading). On success, the region is added to `HRegionServer.onlineRegions` and `reportRegionStateTransition()` sends an `OPENED` report. The model collapses this entire sequence into the atomic `RSOpen` action.

```tla
  /\ serverState[s] = "ONLINE"
```

ZK confirms server is still alive.

```tla
  /\ zkNode[s] = TRUE
```

Region is not already online on this server.

```tla
  /\ r \notin rsOnlineRegions[s]
```

An `OPEN` command for region `r` exists in the server's queue.

```tla
  /\ \E cmd \in dispatchedOps[s]:
```

Command must be an `OPEN` command.

```tla
       /\ cmd.type = "OPEN"
```

Command must target region `r`.

```tla
       /\ cmd.region = r
```

Consume the command from the server's dispatched ops queue.

```tla
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
```

Add the region to the server's set of online regions.

```tla
       /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = @ \cup { r }]
```

Send an `OPENED` report to the master for procedure confirmation.

```tla
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "OPENED" ] }
```

Master-side state and server liveness unchanged.

```tla
       /\ UNCHANGED << scpVars,
             peVars,
             serverVars,
             procStore,
             masterVars,
             regionState,
             metaTable,
             zkNode
          >>
```

### `RSFailOpen(s, r)`

RS atomically receives an `OPEN` command but **fails** to open the region. The command is consumed and a `FAILED_OPEN` report is produced. The region is *not* added to `rsOnlineRegions`.

**Pre:** Server is `ONLINE`, an `OPEN` command for region `r` exists in `dispatchedOps[s]`.
**Post:** Command consumed, `FAILED_OPEN` report produced.

> *Source:* `AssignRegionHandler.process()` failure path: `AssignRegionHandler.cleanUpAndReportFailure()` reports `FAILED_OPEN` via `reportRegionStateTransition()` RPC; the region is *not* added to online regions.

```tla
RSFailOpen(s, r) ==
```

Server is `ONLINE` and an `OPEN` command for region `r` exists.

```tla
  /\ serverState[s] = "ONLINE"
```

ZK confirms server is still alive.

```tla
  /\ zkNode[s] = TRUE
```

An `OPEN` command for region `r` exists in the server's queue.

```tla
  /\ \E cmd \in dispatchedOps[s]:
```

Command must be an `OPEN` command.

```tla
       /\ cmd.type = "OPEN"
```

Command must target region `r`.

```tla
       /\ cmd.region = r
```

Consume the command from the server's dispatched ops queue.

```tla
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
```

Send a `FAILED_OPEN` report to the master for error handling.

```tla
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "FAILED_OPEN" ] }
```

Master-side state, online regions, and server liveness unchanged.

```tla
       /\ UNCHANGED << scpVars,
             peVars,
             serverVars,
             procStore,
             rsVars,
             masterVars,
             regionState,
             metaTable,
             zkNode
          >>
```

```tla
---------------------------------------------------------------------------
```

## RS-Side Close Handler

### `RSClose(s, r)`

RS atomically receives a `CLOSE` command, closes the region, removes it from `rsOnlineRegions`, and reports `CLOSED` to the master. Merges the former `RSReceiveClose` + `RSCompleteClose` into a single action (same rationale as `RSOpen` — see RS-side open handler comment).

**Pre:** Server is `ONLINE`, a `CLOSE` command for region `r` exists in `dispatchedOps[s]`.
**Post:** Command consumed, `r` removed from `rsOnlineRegions[s]`, `CLOSED` report produced in `pendingReports`.

> *Source:* `UnassignRegionHandler.process()` success path: closes the region via `HRegion.close()`, removes it from the RS's online regions, and reports `CLOSED` via `reportRegionStateTransition()` RPC.

```tla
RSClose(s, r) ==
```

Server is `ONLINE` and a `CLOSE` command for region `r` exists.

```tla
  /\ serverState[s] = "ONLINE"
```

ZK confirms server is still alive.

```tla
  /\ zkNode[s] = TRUE
```

A `CLOSE` command for region `r` exists in the server's queue.

```tla
  /\ \E cmd \in dispatchedOps[s]:
```

Command must be a `CLOSE` command.

```tla
       /\ cmd.type = "CLOSE"
```

Command must target region `r`.

```tla
       /\ cmd.region = r
```

Consume the command from the server's dispatched ops queue.

```tla
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
```

Remove the region from the server's set of online regions.

```tla
       /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
```

Send a `CLOSED` report to the master for procedure confirmation.

```tla
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "CLOSED" ] }
```

Master-side state and server liveness unchanged.

```tla
       /\ UNCHANGED << scpVars,
             peVars,
             serverVars,
             procStore,
             masterVars,
             regionState,
             metaTable,
             zkNode
          >>
```

```tla
---------------------------------------------------------------------------
```

## RS-Side Duplicate Open Handler

### `RSOpenDuplicate(s, r)`

RS receives an `OPEN` command for a region that is **already online** on this server. The command is consumed *without* producing an `OPENED` report, modeling [`AssignRegionHandler.process()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/handler/AssignRegionHandler.java) L107–115 where the handler returns early (*"region already online"*) without calling `reportRegionStateTransition()`.

This means the TRSP on the master side will never receive the expected `OPENED` report and will get stuck at `CONFIRM_OPENED`, eventually causing **deadlock**.

The implementation checks `region.getRegion(regionInfo.getEncodedName()) != null` and if true, logs a warning and returns without reporting. This is a defensive check for duplicate `OPEN` RPCs (e.g., from retries when the first RPC timed out but actually succeeded). However, it fails to account for the case where a *different* TRSP sends a new `OPEN` for the same region: since the RS already has the region online (from a previous TRSP), it silently drops the `OPEN`, and the new TRSP hangs indefinitely at `CONFIRM_OPENED` waiting for a report that never arrives.

The deadlock is particularly dangerous because the stuck TRSP holds the `RegionStateNode` lock, preventing any other operation (move, close, split) on that region. In production, this manifests as a permanently stuck region-in-transition (RIT).

Guarded by `UseRSOpenDuplicateQuirk`: disabled by default to avoid deadlock in model checking. Enable to faithfully model this implementation quirk and generate counterexample traces.

**Pre:** `UseRSOpenDuplicateQuirk = TRUE`, server is `ONLINE`, region `r` is already in `rsOnlineRegions[s]`, an `OPEN` command for `r` exists.
**Post:** Command consumed, *no* report produced, `rsOnlineRegions` unchanged.

> *Source:* `AssignRegionHandler.process()` L107–115.

```tla
RSOpenDuplicate(s, r) ==
```

Quirk modeling must be enabled.

```tla
  /\ UseRSOpenDuplicateQuirk = TRUE
```

Server is `ONLINE`, region is *already* online on this server.

```tla
  /\ serverState[s] = "ONLINE"
```

ZK confirms server is still alive.

```tla
  /\ zkNode[s] = TRUE
```

Region is already online on this server (duplicate).

```tla
  /\ r \in rsOnlineRegions[s]
```

An `OPEN` command for region `r` exists in the server's queue.

```tla
  /\ \E cmd \in dispatchedOps[s]:
```

Command must be an `OPEN` command.

```tla
       /\ cmd.type = "OPEN"
```

Command must target region `r`.

```tla
       /\ cmd.region = r
```

Consume the command — but produce *no* report.

```tla
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
```

All other state unchanged: no report, no `rsOnlineRegions` change.

```tla
       /\ UNCHANGED << scpVars,
             serverVars,
             peVars,
             procStore,
             rsVars,
             masterVars,
             regionState,
             metaTable,
             pendingReports,
             zkNode
          >>
```

```tla
---------------------------------------------------------------------------
```

## RS-Side Close-Not-Found Handler

### `RSCloseNotFound(s, r)`

RS receives a `CLOSE` command for a region that is **not online** on this server. The command is consumed *without* producing a `CLOSED` report, modeling [`UnassignRegionHandler.process()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/handler/UnassignRegionHandler.java) L111–117 where the handler discovers `rs.getRegion(encodedName) == null` and returns early without calling `reportRegionStateTransition()`. Also covers the L94–109 path where `regionsInTransitionInRS` already has the region (already closing/opening) — the TLA+ model collapses both paths to the same predicate: `r \notin rsOnlineRegions[s]`.

This means the TRSP on the master side will never receive the expected `CLOSED` report and will get stuck at `CONFIRM_CLOSED`, eventually causing **deadlock**.

In the first code path (L111–117), the RS receives a `CLOSE` for a region it doesn't know about. This can happen when: (1) the region was already closed and removed by a previous handler; (2) the region was never successfully opened on this server but the master believes it is here (stale meta). In the second code path (L94–109), the RS has a conflicting entry in `regionsInTransitionInRS` — the region is already being opened or closed by another handler. Both paths silently return without reporting.

Like `RSOpenDuplicate`, the stuck TRSP holds the `RegionStateNode` lock, causing a permanent RIT. The model collapses both paths into `r \notin rsOnlineRegions[s]` because the TLA+ model does not track `regionsInTransitionInRS` (an RS-internal queue).

Guarded by `UseRSCloseNotFoundQuirk`: disabled by default to avoid deadlock in model checking. Enable to faithfully model this implementation quirk and generate counterexample traces.

**Pre:** `UseRSCloseNotFoundQuirk = TRUE`, server is `ONLINE`, region `r` is *not* in `rsOnlineRegions[s]`, a `CLOSE` command for `r` exists.
**Post:** Command consumed, *no* report produced, `rsOnlineRegions` unchanged.

> *Source:* `UnassignRegionHandler.process()` L111–117.

```tla
RSCloseNotFound(s, r) ==
```

Quirk modeling must be enabled.

```tla
  /\ UseRSCloseNotFoundQuirk = TRUE
```

Server is `ONLINE`.

```tla
  /\ serverState[s] = "ONLINE"
```

ZK confirms server is still alive.

```tla
  /\ zkNode[s] = TRUE
```

Region is NOT online on this server (not found).

```tla
  /\ r \notin rsOnlineRegions[s]
```

A `CLOSE` command for region `r` exists in the server's queue.

```tla
  /\ \E cmd \in dispatchedOps[s]:
```

Command must be a `CLOSE` command.

```tla
       /\ cmd.type = "CLOSE"
```

Command must target region `r`.

```tla
       /\ cmd.region = r
```

Consume the command — but produce *no* report.

```tla
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
```

All other state unchanged: no report, no `rsOnlineRegions` change.

```tla
       /\ UNCHANGED << scpVars,
             serverVars,
             peVars,
             procStore,
             rsVars,
             masterVars,
             regionState,
             metaTable,
             pendingReports,
             zkNode
          >>
```

```tla
---------------------------------------------------------------------------
```

## RS Restart

### `RSRestart(s)`

A process supervisor (Kubernetes, systemd, etc.) restarts a crashed RegionServer. The restarted server is empty: no regions, no pending commands, no RS-side state. Any pending reports from the previous incarnation are discarded. SCP state is reset to `"NONE"` and `walFenced` is cleared.

**Design note:** RS epochs are not modeled explicitly. Real HBase uses `ServerName` (host + startcode) to distinguish incarnations. Stale reports carry the old `ServerName` and are rejected. Here the same effect is achieved by atomic crash plus atomic restart (this action purges stale reports). An explicit epoch variable would only add value if crash or restart were decomposed into non-atomic multi-step sequences.

In production, the new RS incarnation registers with ZK (`createMyEphemeralNode()`), opens a new RPC port (potentially the same host:port), and calls `RegionServerStartup.regionServerStartup()`. The master's `RegionServerTracker` picks up the new ZK node and calls `ServerManager.regionServerReport()` to register the server. The model's atomic `RSRestart` action captures the net effect: the server transitions from `CRASHED` to `ONLINE`, all stale state is purged, and a fresh ZK node is created. The intermediate steps (ZK registration, RPC port binding, master registration) are not observable to the assignment protocol — no region operations can be dispatched to a server until the master registers it, which happens atomically in the model.

**Pre:** server is `CRASHED` AND SCP for this server is complete or was never started. The guard prevents premature restart while SCP is still processing regions from the crashed server.
**Post:** `serverState` set to `ONLINE`, pending reports from `s` discarded, SCP state reset, `walFenced` cleared.

> *Source:* Environmental assumption — a process supervisor (Kubernetes, systemd, etc.) guarantees that crashed RegionServer processes are eventually restarted.

```tla
RSRestart(s) ==
```

Server is `CRASHED` and SCP is complete (or never started).

```tla
  /\ serverState[s] = "CRASHED"
  /\ scpState[s] \in { "DONE", "NONE" }
```

Bring the server back `ONLINE`.

```tla
  /\ serverState' = [serverState EXCEPT ![s] = "ONLINE"]
```

Purge all stale pending reports from this server's prior incarnation.

```tla
  /\ pendingReports' = {rpt \in pendingReports: rpt.server # s}
```

Clear stale commands for the restarting server. The prior incarnation never received them (or crashed before consuming); the new process starts with an empty queue. Without this, `RSOpen` could fire on stale `OPEN` commands, creating ghost regions (`r` in `rsOnlineRegions` but master has `regionState` `ABNORMALLY_CLOSED` / no location).

```tla
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
```

Clear RS-side state for the restarting server. The new process has no regions; the zombie's `rsOnlineRegions` was never cleared by `RSAbort`. Without this, `RSMasterAgreementConverse` fails: restarted server `ONLINE` but `rsOnlineRegions[s]` still has regions the master has `ABNORMALLY_CLOSED`.

```tla
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
```

Reset SCP state for the restarting server.

```tla
  /\ scpState' = [scpState EXCEPT ![s] = "NONE"]
```

Clear SCP region set for the restarting server.

```tla
  /\ scpRegions' = [scpRegions EXCEPT ![s] = {}]
```

Clear WAL fencing state for the restarting server.

```tla
  /\ walFenced' = [walFenced EXCEPT ![s] = FALSE]
```

Clear `carryingMeta` flag for the restarting server.

```tla
  /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
```

Clear `ServerStateNode` tracking for the restarting server. In the implementation, `SCP.removeServer()` calls `RegionStates.removeServer()` which removes the `ServerStateNode`.

```tla
  /\ serverRegions' = [serverRegions EXCEPT ![s] = {}]
```

Register a fresh ZK ephemeral node for the restarted server.

> *Source:* `HRegionServer.run()` → `createMyEphemeralNode()`.

```tla
  /\ zkNode' = [zkNode EXCEPT ![s] = TRUE]
```

Region state and META unchanged.

```tla
  /\ UNCHANGED << procStore, masterVars, peVars, regionState, metaTable, parentProc, tableEnabled >>
```

```tla
============================================================================
```
