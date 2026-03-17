# SCP

**Source:** [`SCP.tla`](../SCP.tla)

ServerCrashProcedure state machine: detect crash, assign meta, get regions, fence WALs, assign regions, done.

---

```tla
------------------------------- MODULE SCP ------------------------------------
```

ServerCrashProcedure (SCP) actions for the HBase AssignmentManager:
- **`SCPAssignMeta`** — meta reassignment when crashed server was hosting `hbase:meta`
- **`SCPGetRegions`** — snapshot regions on the crashed server
- **`SCPFenceWALs`** — revoke WAL leases (prevents zombie writes)
- **`SCPAssignRegion`** — process regions one at a time
- **`SCPDone`** — all regions processed

### Implementation Mapping

The SCP is implemented in [`ServerCrashProcedure.java`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/procedure/ServerCrashProcedure.java), a `StateMachineProcedure` that extends `Procedure<MasterProcedureEnv>`. It uses the `ServerCrashState` enum from `MasterProcedure.proto` to track its progress.

The implementation's 13 enum values are collapsed to 6 model states. The collapse is safe because the omitted states perform operations **orthogonal** to region assignment:

| Omitted state              | Implementation action                            | Omission rationale                                                               |
|---------------------------|--------------------------------------------------|-----------------------------------------------------------------------------------|
| `PROCESS_META` (=2)        | *Deprecated* (no-op in current code)             | Dead code                                                                         |
| `NO_SPLIT_LOGS` (=4)      | *Deprecated*                                      | Dead code                                                                         |
| `HANDLE_RIT2` (=20)       | *Deprecated*                                      | Dead code                                                                         |
| `CLAIM_REPLICATION_QUEUES` (=14) | Claims peer-replication queues from dead server | Replication is orthogonal to region assignment                                   |
| `DELETE_SPLIT_WALS_DIR` (=13) | Cleans up WAL split output directory            | Filesystem cleanup; no state transition visible to assignment                     |
| `SPLIT_META_LOGS` (=10)   | Splits meta WALs before meta reassignment         | Collapsed into `ASSIGN_META` — the model captures the *outcome* (meta reassigned) |
| `DELETE_SPLIT_META_WALS_DIR` (=12) | Cleans up meta WAL split directory        | Filesystem cleanup                                                                |

The specification focuses on the region-state-affecting steps: which regions are marked crashed, when WALs are fenced (preventing zombie writes), and when reassignment procedures are created. WAL splitting itself (the I/O operation of reading WAL files and writing per-region recovered-edits) is abstracted into the `walFenced` flag — the model cares about fencing semantics (zombie prevention), not the I/O details of WAL replay.

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
rpcVars == << dispatchedOps, pendingReports >>
```

```tla
rsVars == << rsOnlineRegions >>
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

`MetaIsAvailable` is `TRUE` when no server is in `ASSIGN_META` `scpState`.

```tla
MetaIsAvailable == \A t \in Servers: scpState[t] # "ASSIGN_META"
```

```tla
---------------------------------------------------------------------------
```

## SCP State Machine

### State Mapping

The implementation's `ServerCrashState` enum (`MasterProcedure.proto`) has 13 values (3 deprecated). The model abstracts these into 6 states that capture the assignment-relevant SCP lifecycle. States omitted from the model are pass-through cleanup/replication steps that do not interact with region state, assignment, or fencing.

| Model state       | Implementation enum(s) abstracted                                                    |
|--------------------|---------------------------------------------------------------------------------------|
| *(MasterDetectCrash)* | `SERVER_CRASH_START` (=1) — determines if server carries meta; collapsed into `MasterDetectCrash` |
| `"ASSIGN_META"`    | `SERVER_CRASH_SPLIT_META_LOGS` (=10), `SERVER_CRASH_ASSIGN_META` (=11), `SERVER_CRASH_DELETE_SPLIT_META_WALS_DIR` (=12) — meta WAL split + meta reassign + cleanup; modeled as single atomic meta reassignment |
| `"GET_REGIONS"`    | `SERVER_CRASH_GET_REGIONS` (=3) — 1:1 match                                          |
| `"FENCE_WALS"`     | `SERVER_CRASH_SPLIT_LOGS` (=5) — WAL splitting → fencing semantics only               |
| `"ASSIGN"`         | `SERVER_CRASH_ASSIGN` (=8), `SERVER_CRASH_WAIT_ON_ASSIGN` (=9) — assign + wait collapsed into per-region `SCPAssignRegion` + `SCPDone` |
| `"DONE"`           | `SERVER_CRASH_CLAIM_REPLICATION_QUEUES` (=14), `SERVER_CRASH_DELETE_SPLIT_WALS_DIR` (=13), `SERVER_CRASH_FINISH` (=100) — replication queue claiming and WAL dir cleanup are orthogonal to assignment; collapsed with `FINISH` into terminal `"DONE"` |
| *(not modeled)*    | `SERVER_CRASH_PROCESS_META` (=2, deprecated), `SERVER_CRASH_NO_SPLIT_LOGS` (=4, deprecated), `SERVER_CRASH_HANDLE_RIT2` (=20, deprecated) |

---

### `SCPAssignMeta(s)`

SCP meta-reassignment step: when the crashed server was hosting `hbase:meta`, the SCP must reassign meta before proceeding to the normal crash-recovery path. Meta reassignment is abstracted as a single atomic step (the actual implementation creates a TRSP for the meta region and waits for it to complete).

**Pre:** `scpState[s] = "ASSIGN_META"`, `carryingMeta[s] = TRUE`.
**Post:** `scpState[s] = "GET_REGIONS"` (meta is now online, SCP proceeds to the normal path).

> *Source:* `SCP.executeFromState()` `SERVER_CRASH_SPLIT_META_LOGS` and `ASSIGN_META` cases.

```tla
SCPAssignMeta(s) ==
```

Master must be alive for SCP to execute.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this SCP step.

```tla
  /\ availableWorkers > 0
```

SCP is in the meta-recovery sub-path.

```tla
  /\ scpState[s] = "ASSIGN_META"
```

Only servers that were hosting `hbase:meta` enter this path.

```tla
  /\ carryingMeta[s] = TRUE
```

Meta reassigned (abstracted); advance to normal crash-recovery.

```tla
  /\ scpState' = [scpState EXCEPT ![s] = "GET_REGIONS"]
```

Meta is now online; clear the `carryingMeta` flag.

```tla
  /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
  /\ UNCHANGED << rpcVars,
        serverVars,
        procStore,
        rsVars,
        masterVars,
        peVars,
        regionState,
        metaTable,
        scpRegions,
        walFenced,
        zkNode,
        parentProc,
        tableEnabled
     >>
```

### `SCPGetRegions(s)`

**SCP step 1:** Snapshot the set of regions assigned to the crashed server. This snapshot can go stale: between `GET_REGIONS` and `ASSIGN`, concurrent TRSPs may move regions, causing `isMatchingRegionLocation()` to skip them (Iteration 15).

**`isMatchingRegionLocation()` race condition:** The SCP snapshots region assignments at `GET_REGIONS`, then iterates through them at `ASSIGN`. Between these two steps, a concurrent TRSP (e.g., a `MOVE` initiated before the crash was detected) may relocate a region from server `s` to server `t`. When SCP reaches this region in `SCPAssignRegion`, `regionState[r].location ≠ s` and SCP skips it. If the concurrent TRSP subsequently fails (e.g., the target server also crashes), the region is left in a limbo state — not processed by SCP, not successfully moved by TRSP. The model faithfully captures this race via the skip branch in `SCPAssignRegion`.

**Implementation note (branch-2.6):** at this step, the implementation also calls `AM.markRegionsAsCrashed()`, which updates internal bookkeeping (RIT tracking, crash timestamps) to mark the regions as unavailable. This does *not* change the `RegionState.State` enum — the actual transition to `ABNORMALLY_CLOSED` happens later in `assignRegions()` (`SERVER_CRASH_ASSIGN`). The model's abstraction (no region state change at `GET_REGIONS`, state change only at `SCPAssignRegion`) remains valid.

**Pre:** `scpState[s] = "GET_REGIONS"`.
**Post:** `scpRegions[s]` = snapshot of regions with location = `s`, `scpState` advances to `"FENCE_WALS"`.

> *Source:* `ServerCrashProcedure.executeFromState()` `SERVER_CRASH_GET_REGIONS` case calls `ServerCrashProcedure.getRegionsOnCrashedServer()` which delegates to `AM.getRegionsOnServer()`; then `AM.markRegionsAsCrashed()` updates RIT tracking for each region.

```tla
SCPGetRegions(s) ==
```

Master must be alive for SCP to execute.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this SCP step.

```tla
  /\ availableWorkers > 0
```

SCP is in `GET_REGIONS` state for this crashed server.

```tla
  /\ scpState[s] = "GET_REGIONS"
```

Meta must be online (no server in `ASSIGN_META`) before SCP proceeds.

```tla
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
```

Snapshot regions from the `ServerStateNode` tracking for the crashed server. In the implementation, `getRegionsOnServer()` reads from the `ServerStateNode`'s region set, *not* from `regionNode.getRegionLocation()`. These two can be out of sync.

> *Source:* `AM.getRegionsOnServer()`.

```tla
  /\ scpRegions' = [scpRegions EXCEPT ![s] = serverRegions[s]]
```

Advance SCP to the WAL fencing step.

```tla
  /\ scpState' = [scpState EXCEPT ![s] = "FENCE_WALS"]
```

Region state, meta, RPCs, RS-side state, and WAL fencing unchanged.

```tla
  /\ UNCHANGED << rpcVars,
        serverVars,
        procStore,
        rsVars,
        masterVars,
        peVars,
        regionState,
        metaTable,
        walFenced,
        carryingMeta,
        zkNode,
        parentProc,
        tableEnabled
     >>
```

### `SCPFenceWALs(s)`

**SCP step 2:** Revoke WAL leases for the crashed server. After this step, the zombie RS cannot write to its WALs. Any write attempt will fail with an HDFS lease exception, triggering RS self-abort. This is the fencing mechanism that prevents write-side split-brain.

In the implementation, this step calls [`SplitWALManager.splitWALs()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/SplitWALManager.java) (or `WALSplitUtil.splitLogDistributed()` depending on configuration), which:
1. **Revokes HDFS leases** on the crashed server's WAL files via `recoverLease()` / `recoverFileLease()`. This is the actual fencing operation — after this, the zombie RS gets `AlreadyBeingCreatedException` or `RecoveryInProgressException` on any write attempt.
2. **Splits WAL entries** per-region into recovered-edits files. Each region's recovered edits will be replayed when the region is opened on its new server.

The model abstracts both operations into `walFenced[s] = TRUE`. The splitting I/O is orthogonal to assignment correctness; the fencing semantics (preventing zombie writes) are the safety-critical property. The `walFenced` guard on `SCPAssignRegion` ensures regions are never reassigned before fencing completes — this prevents the scenario where a zombie RS writes to a region that has been reopened on a new server (split-brain data corruption).

**Pre:** `scpState[s] = "FENCE_WALS"`.
**Post:** `walFenced[s] = TRUE`, `scpState` advances to `"ASSIGN"`.

> *Source:* `ServerCrashProcedure.executeFromState()` `SERVER_CRASH_SPLIT_LOGS` case.

```tla
SCPFenceWALs(s) ==
```

Master must be alive for SCP to execute.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this SCP step.

```tla
  /\ availableWorkers > 0
```

SCP is in `FENCE_WALS` state for this crashed server.

```tla
  /\ scpState[s] = "FENCE_WALS"
```

Meta must be online (no server in `ASSIGN_META`) before SCP proceeds.

```tla
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
```

Revoke WAL leases — zombie RS can no longer write.

```tla
  /\ walFenced' = [walFenced EXCEPT ![s] = TRUE]
```

Advance SCP to the region assignment step.

```tla
  /\ scpState' = [scpState EXCEPT ![s] = "ASSIGN"]
```

Region state, meta, RPCs, RS-side state, and region snapshot unchanged.

```tla
  /\ UNCHANGED << rpcVars,
        serverVars,
        procStore,
        rsVars,
        masterVars,
        peVars,
        regionState,
        metaTable,
        scpRegions,
        carryingMeta,
        zkNode,
        parentProc,
        tableEnabled
     >>
```

### `SCPAssignRegion(s, r)`

**SCP step 3:** Process *one* region from the SCP's region snapshot. Each invocation handles a single region and removes it from `scpRegions[s]`. Three sub-paths:

- **Skip** (location check): if the region's master-side location no longer matches the crashed server, SCP skips the region entirely. This models the `isMatchingRegionLocation()` guard.
- **Path A** (procedure attached): transition region to `ABNORMALLY_CLOSED`, clear location; existing procedure preserved for `TRSPServerCrashed`.
- **Path B** (no procedure): transition to `ABNORMALLY_CLOSED`, clear location, create fresh `ASSIGN`/`GET_ASSIGN_CANDIDATE` procedure.

**Pre:** `scpState[s] = "ASSIGN"`, `r ∈ scpRegions[s]`, `walFenced[s] = TRUE`.
**Post:** `r` removed from `scpRegions[s]`, region transitioned (or skipped).

> *Source:* `ServerCrashProcedure.assignRegions()`; `ServerCrashProcedure.isMatchingRegionLocation()`.

```tla
SCPAssignRegion(s, r) ==
```

Master must be alive for SCP to execute.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this SCP step.

```tla
  /\ availableWorkers > 0
```

SCP is in `ASSIGN` state for this crashed server.

```tla
  /\ scpState[s] = "ASSIGN"
```

Region is in this SCP's snapshot (collected at `GET_REGIONS`).

```tla
  /\ r \in scpRegions[s]
```

WAL leases for the crashed server must already be revoked before reassigning any of its regions.

```tla
  /\ walFenced[s] = TRUE
  /\ \/ \* --- Skip: isMatchingRegionLocation fails ---
```

Between `SCPGetRegions` and now, a concurrent TRSP may have moved this region to another server. The implementation skips such regions. If the concurrent TRSP subsequently fails, the region may be lost without manual intervention (HBASE-24293).

```tla
        /\ regionState[r].location # s
```

Skip: only shrink the SCP snapshot; no state changes.

```tla
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
        /\ UNCHANGED << rpcVars,
              serverVars,
              procStore,
              rsVars,
              masterVars,
              regionState,
              metaTable,
              scpState,
              walFenced,
              carryingMeta,
              peVars,
              zkNode,
              parentProc,
              tableEnabled
           >>
     \/ \* --- Meta unavailable: suspend or block ---
```

Paths A/B write to meta; if meta is unavailable, suspend (async) or block (sync) the procedure.

```tla
        /\ regionState[r].location = s
        /\ ~MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        /\ IF UseBlockOnMetaWrite = FALSE
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta, parentProc, tableEnabled >>
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
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
              masterVars,
              zkNode,
              parentProc,
              tableEnabled
           >>
     \/ \* --- Path A: TRSP already attached ---
```

Meta must be available for Path A (writes to meta).

```tla
        /\ MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
```

Location matches; procedure exists. Transition region to `ABNORMALLY_CLOSED` and atomically convert the existing TRSP to `ASSIGN`/`GET_ASSIGN_CANDIDATE`. This models the implementation's `serverCrashed()` callback firing under the same `RegionStateNode` lock as the state transition — they are a single atomic step.

> *Source:* `ServerCrashProcedure.assignRegions()` acquires `RegionStateNode.lock()`, then calls `regionNode.getProcedure().serverCrashed(env, ...)`; `TRSP.serverCrashed()` → `AM.regionClosedAbnormally()` all execute under that same lock.

```tla
        /\ regionState[r].location = s
        /\ regionState[r].procType # "NONE"
```

Clear `r` from `rsOnlineRegions` on all servers. The region may have moved between `SCPGetRegions` and now; clearing everywhere prevents ghost regions.

```tla
        /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
```

Clear *all* stale commands for this region from every server. The procedure is being reset; any in-flight `OPEN`/`CLOSE` RPCs are obsolete.

```tla
        /\ dispatchedOps' =
             [t \in Servers |-> {cmd \in dispatchedOps[t]: cmd.region # r}
             ]
```

Drop *all* reports for `r`: any `OPENED`/`CLOSED`/`FAILED_OPEN` reports are from the abandoned procedure and must not be consumed after reassignment.

```tla
        /\ pendingReports' = {pr \in pendingReports: pr.region # r}
```

Atomically: mark `ABNORMALLY_CLOSED`, clear location, *and* convert procedure to `ASSIGN`/`GET_ASSIGN_CANDIDATE`.

```tla
        /\ regionState' =
             [regionState EXCEPT
             ![r].state =
             "ABNORMALLY_CLOSED",
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
```

Persist `ABNORMALLY_CLOSED` state to `metaTable` with cleared location.

```tla
        /\ metaTable' =
             [metaTable EXCEPT
             ![r].state = "ABNORMALLY_CLOSED",
             ![r].location = NoServer ]
```

Remove `r` from the SCP snapshot (processed).

```tla
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
```

Update persisted procedure: convert to `ASSIGN`.

```tla
        /\ procStore' =
             [procStore EXCEPT
             ![r] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

`ServerStateNode` tracking: remove `r` from crashed server `s`.

```tla
        /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \ { r }]
```

Server state, WAL fencing, SCP state, and meta-carrying flag unchanged.

```tla
        /\ UNCHANGED << masterVars,
              serverState,
              scpState,
              walFenced,
              carryingMeta,
              zkNode,
              parentProc,
              tableEnabled
           >>
```

Clear `r` from suspended/blocked sets if it was waiting on meta.

```tla
        /\ suspendedOnMeta' = suspendedOnMeta \ { r }
```

Clear `r` from blocked set if it was blocked on meta.

```tla
        /\ blockedOnMeta' = blockedOnMeta \ { r }
```

Recover the PEWorker thread if `r` was blocking one.

```tla
        /\ availableWorkers' =
             IF r \in blockedOnMeta
             THEN availableWorkers + 1
             ELSE availableWorkers
     \/ \* --- Path B: No TRSP attached ---
```

Meta must be available for Path B (writes to meta).

```tla
        /\ MetaIsAvailable
```

Region is not suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Location matches; no procedure. Transition to `ABNORMALLY_CLOSED` and attach a fresh `ASSIGN` procedure at `GET_ASSIGN_CANDIDATE`.

```tla
        /\ regionState[r].location = s
        /\ regionState[r].procType = "NONE"
```

Clear `r` from `rsOnlineRegions` on all servers.

```tla
        /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
```

Drop stale `OPENED` reports for `r`.

```tla
        /\ pendingReports' =
             {pr \in pendingReports: pr.code # "OPENED" \/ pr.region # r}
```

Mark `ABNORMALLY_CLOSED`, clear location, attach fresh `ASSIGN` procedure.

```tla
        /\ regionState' =
             [regionState EXCEPT
             ![r].state =
             "ABNORMALLY_CLOSED",
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
```

Persist `ABNORMALLY_CLOSED` state to `metaTable` with cleared location.

```tla
        /\ metaTable' =
             [metaTable EXCEPT
             ![r].state = "ABNORMALLY_CLOSED",
             ![r].location = NoServer ]
```

Remove `r` from the SCP snapshot (processed).

```tla
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
```

Insert a fresh `ASSIGN` procedure into the store.

```tla
        /\ procStore' =
             [procStore EXCEPT
             ![r] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

`ServerStateNode` tracking: remove `r` from crashed server `s`.

```tla
        /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \ { r }]
```

Dispatched ops, server state, WAL fencing, SCP state, and meta-carrying flag unchanged.

```tla
        /\ UNCHANGED << masterVars,
              dispatchedOps,
              serverState,
              scpState,
              walFenced,
              carryingMeta,
              zkNode,
              parentProc,
              tableEnabled
           >>
```

Clear `r` from suspended/blocked sets if it was waiting on meta.

```tla
        /\ suspendedOnMeta' = suspendedOnMeta \ { r }
        /\ blockedOnMeta' = blockedOnMeta \ { r }
        /\ availableWorkers' =
             IF r \in blockedOnMeta
             THEN availableWorkers + 1
             ELSE availableWorkers
```

### `SCPDone(s)`

**SCP step 4:** All regions processed. Mark SCP as complete.

**Pre:** `scpState[s] = "ASSIGN"`, `scpRegions[s] = {}` (all processed).
**Post:** `scpState[s] = "DONE"`.

> *Source:* `ServerCrashProcedure.executeFromState()` `SERVER_CRASH_FINISH` case — the procedure completes and is cleaned up by the `ProcedureExecutor`.

```tla
SCPDone(s) ==
```

Master must be alive for SCP to execute.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this SCP step.

```tla
  /\ availableWorkers > 0
```

SCP is in `ASSIGN` state and all regions have been processed.

```tla
  /\ scpState[s] = "ASSIGN"
  /\ scpRegions[s] = {}
```

Mark SCP as complete for this crashed server.

```tla
  /\ scpState' = [scpState EXCEPT ![s] = "DONE"]
```

Clean up `ServerStateNode` tracking for the crashed server.

> *Source:* `ServerManager.expireServer()` → `RegionStates.removeServer()` (L679–681) removes the `ServerStateNode` entirely.

```tla
  /\ serverRegions' = [serverRegions EXCEPT ![s] = {}]
```

All other state unchanged — region reassignments already applied.

```tla
  /\ UNCHANGED << rpcVars,
        serverState,
        procStore,
        rsVars,
        masterVars,
        peVars,
        regionState,
        metaTable,
        scpRegions,
        walFenced,
        carryingMeta,
        zkNode,
        parentProc,
        tableEnabled
     >>
```

```tla
============================================================================
```
