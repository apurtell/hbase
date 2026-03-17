# ZK

**Source:** [`ZK.tla`](../ZK.tla)

Minimal ZooKeeper model for the HBase AssignmentManager. Models ZK ephemeral nodes for RegionServer liveness.

### Implementation Fidelity

The real ZooKeeper is a replicated state machine with quorum reads, version checks, multi-op transactions, session heartbeats (`tickTime`), and configurable session timeouts (`zookeeper.session.timeout`, default 90s). The model abstracts ALL of this into a single boolean per server (`zkNode[s]`). This is sound for assignment safety because:

1. **Ephemeral node = liveness signal.** The *only* ZK interaction relevant to the AssignmentManager is the ephemeral node under `/hbase/rs/<serverName>`. The master subscribes to child-change events via `RegionServerTracker` ([RegionServerTracker.java](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/RegionServerTracker.java)). When a node vanishes, the master calls `ServerManager.expireServer()` → `AssignmentManager.submitServerCrash()`. The boolean model captures this precisely.

2. **Session timeout window abstraction.** In production, there is a delay between RS process death and ZK session expiry (up to `sessionTimeout` ms). The model's instantaneous `ZKSessionExpire` action is a *conservative* abstraction: it allows ZK detection at any time, including immediately. Since safety properties are universally quantified over all behaviors, checking them against a model where ZK detects death instantly is strictly stronger than checking with a delay — any safety violation with delayed detection is also reachable with immediate detection.

3. **Quorum reads, version checks, multi-op.** These ZK features are used by other HBase subsystems (region assignment via ZK in pre-2.0, replication) but NOT by the assignment manager's ephemeral-node liveness protocol. The assignment manager does not perform conditional ZK writes or read ZK data nodes for region state — it only watches for node creation/deletion events.

A more detailed ZK model (with session timeout delays, heartbeat failures, or network partitions between RS and ZK quorum) would increase the state space dramatically without surfacing additional assignment-relevant bugs. The single-boolean model preserves all safety properties while remaining tractable for exhaustive model checking.

---

```tla
------------------------------- MODULE ZK -------------------------------------
```

Each live RS holds an ephemeral node; when the RS process dies, ZK detects the session expiry and deletes the node. The master reads these nodes to determine which servers are alive.

ZK is the ground truth about RS liveness, independent of master state. A RS can die at any time and ZK will detect it regardless of whether the master is alive or not.

This module contains one action:
- **`ZKSessionExpire(s)`** — RS dies, ZK deletes its ephemeral node

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

Shorthand for PEWorker pool variables (used in `UNCHANGED` clauses).

```tla
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>
```

```tla
---------------------------------------------------------------------------
```

## ZK Ephemeral Node Lifecycle

### `ZKSessionExpire(s)`

ZK detects that a RegionServer's session has expired (the RS process is dead) and deletes its ephemeral node.

This is a ZK-side action, independent of both master and RS. ZK is the ground truth about RS liveness. Any live RS can die at any time — this action is non-deterministic, like `MasterCrash`.

After `ZKSessionExpire` fires:
- `MasterDetectCrash` (if master is alive) will observe `zkNode[s] = FALSE` and mark the server as `CRASHED`.
- `MasterRecover` (if master restarts) will read `zkNode[s] = FALSE` and mark the server as `CRASHED` during recovery.
- `RSAbort` will eventually clean up the zombie RS state.

The RS process may still be a zombie briefly after ZK session expiry — `rsOnlineRegions` is *not* cleared here (that's `RSAbort`'s job). This preserves the zombie window that makes WAL fencing necessary for correctness.

In production, the zombie window duration depends on when the RS process detects its own death. It discovers death through one of three paths: (1) **`YouAreDeadException`** — the master's `RegionServerTracker` calls `RSRpcServices.killRegionServer()` via RPC; (2) **ZK session expiry detection** — the RS's own ZK client fires a `Disconnected`/`Expired` event; (3) **WAL fencing** — the RS tries to write to a WAL whose HDFS lease has been revoked by `SplitWALManager`, gets an `IOException`, and self-aborts. The model's non-deterministic `RSAbort` timing (any time after `zkNode[s] = FALSE`) subsumes all three detection paths.

> *Source:* ZooKeeper session timeout; ephemeral node under `/hbase/rs` is deleted when the RS's ZK session expires. [`ZKUtil.java`](file:///Users/andrewpurtell/src/hbase/hbase-zookeeper/src/main/java/org/apache/hadoop/hbase/zookeeper/ZKUtil.java) provides the low-level ephemeral node management; [`RegionServerTracker.java`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/RegionServerTracker.java) subscribes to change events on the master side.

```tla
ZKSessionExpire(s) ==
```

The RS has a live ZK ephemeral node.

```tla
  /\ zkNode[s] = TRUE
```

Delete the ephemeral node. ZK now considers this RS dead.

```tla
  /\ zkNode' = [zkNode EXCEPT ![s] = FALSE]
```

Everything else is unchanged. The zombie RS may still hold regions in `rsOnlineRegions` until `RSAbort` fires.

```tla
  /\ UNCHANGED << regionState,
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
        peVars,
        parentProc,
        tableEnabled
     >>
```

```tla
============================================================================
```
