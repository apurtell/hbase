# SCP — ServerCrashProcedure

**Source:** [`SCP.tla`](../SCP.tla)

ServerCrashProcedure (SCP) actions for the HBase AssignmentManager. Contains the SCP state machine: `SCPAssignMeta`, `SCPGetRegions`, `SCPFenceWALs`, `SCPAssignRegion`, and `SCPDone`.

---

## Module Declaration

```tla
------------------------------- MODULE SCP ------------------------------------
EXTENDS Types
```

## Variables

```tla
VARIABLE regionState, metaTable, dispatchedOps, pendingReports,
         rsOnlineRegions, serverState, scpState, scpRegions,
         walFenced, locked, carryingMeta, serverRegions,
         procStore, masterAlive, zkNode,
         availableWorkers, suspendedOnMeta, blockedOnMeta
```

### Variable Group Shorthands

```tla
rpcVars    == << dispatchedOps, pendingReports >>
rsVars     == << rsOnlineRegions >>
masterVars == << masterAlive >>
procVars   == << procStore, locked >>
serverVars == << serverState, serverRegions >>
peVars     == << availableWorkers, suspendedOnMeta, blockedOnMeta >>
```

### MetaIsAvailable

TRUE when no server is in the `ASSIGN_META` scpState (meta is online and available for writes).

```tla
MetaIsAvailable == \A t \in Servers: scpState[t] # "ASSIGN_META"
```

---

## SCP State Machine Overview

The implementation's `ServerCrashState` enum (`MasterProcedure.proto`) has 13 values (3 deprecated). The model abstracts these into 6 states that capture the assignment-relevant SCP lifecycle. States omitted from the model are pass-through cleanup/replication steps that do not interact with region state, assignment, or fencing.

| Model State | Implementation Enum(s) Abstracted |
|-------------|-----------------------------------|
| *(MasterDetectCrash)* | `SERVER_CRASH_START` (=1) — determines if server carries meta, sets initial SCP state |
| `"ASSIGN_META"` | `SERVER_CRASH_SPLIT_META_LOGS` (=10), `SERVER_CRASH_ASSIGN_META` (=11), `SERVER_CRASH_DELETE_SPLIT_META_WALS_DIR` (=12) — meta WAL split + reassign + cleanup |
| `"GET_REGIONS"` | `SERVER_CRASH_GET_REGIONS` (=3) — 1:1 match |
| `"FENCE_WALS"` | `SERVER_CRASH_SPLIT_LOGS` (=5) — WAL splitting → fencing semantics |
| `"ASSIGN"` | `SERVER_CRASH_ASSIGN` (=8), `SERVER_CRASH_WAIT_ON_ASSIGN` (=9) — collapsed into per-region `SCPAssignRegion` + `SCPDone` |
| `"DONE"` | `SERVER_CRASH_CLAIM_REPLICATION_QUEUES` (=14), `SERVER_CRASH_DELETE_SPLIT_WALS_DIR` (=13), `SERVER_CRASH_FINISH` (=100) — orthogonal cleanup |
| *(not modeled)* | `SERVER_CRASH_PROCESS_META` (=2), `SERVER_CRASH_NO_SPLIT_LOGS` (=4), `SERVER_CRASH_HANDLE_RIT2` (=20) — all deprecated |

---

## Actions

### SCPAssignMeta(s)

SCP meta-reassignment step: when the crashed server was hosting `hbase:meta`, the SCP must reassign meta before proceeding to the normal crash-recovery path. Meta reassignment is abstracted as a single atomic step (the actual implementation creates a TRSP for the meta region and waits for it to complete).

**Pre:** `scpState[s] = "ASSIGN_META"`, `carryingMeta[s] = TRUE`, `availableWorkers > 0`.

**Post:** `scpState[s] = "GET_REGIONS"` (meta is now online, SCP proceeds to the normal path).

*Source: `SCP.executeFromState()` `SERVER_CRASH_SPLIT_META_LOGS` and `ASSIGN_META` cases.*

```tla
SCPAssignMeta(s) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ scpState[s] = "ASSIGN_META"
  /\ carryingMeta[s] = TRUE
  /\ scpState' = [scpState EXCEPT ![s] = "GET_REGIONS"]
  /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
  /\ UNCHANGED << rpcVars, serverVars, procVars, rsVars,
                   masterVars, peVars, regionState, metaTable,
                   scpRegions, walFenced, zkNode >>
```

---

### SCPGetRegions(s)

**SCP step 1:** Snapshot the set of regions assigned to the crashed server. This snapshot can go stale between `GET_REGIONS` and `ASSIGN`, concurrent TRSPs may move regions, causing `isMatchingRegionLocation()` to skip them (HBASE-24293).

**Implementation note (branch-2.6):** At this step, the implementation also calls `AM.markRegionsAsCrashed()`, which updates internal bookkeeping (RIT tracking, crash timestamps) to mark the regions as unavailable. This does NOT change the `RegionState.State` enum — the actual transition to `ABNORMALLY_CLOSED` happens later in `assignRegions()` (`SERVER_CRASH_ASSIGN`). The model's abstraction (no region state change at `GET_REGIONS`, state change only at `SCPAssignRegion`) remains valid.

**Pre:** `scpState[s] = "GET_REGIONS"`, `availableWorkers > 0`.

**Post:** `scpRegions[s]` = snapshot of regions from `serverRegions[s]`, `scpState` advances to `"FENCE_WALS"`.

*Source: `ServerCrashProcedure.executeFromState()` `SERVER_CRASH_GET_REGIONS` case calls `getRegionsOnCrashedServer()` which delegates to `AM.getRegionsOnServer()`; then `AM.markRegionsAsCrashed()` updates RIT tracking.*

```tla
SCPGetRegions(s) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ scpState[s] = "GET_REGIONS"
  \* Meta must be online (no server in ASSIGN_META) before SCP proceeds.
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
  \* Snapshot from ServerStateNode tracking (NOT regionState.location).
  /\ scpRegions' = [scpRegions EXCEPT ![s] = serverRegions[s]]
  /\ scpState' = [scpState EXCEPT ![s] = "FENCE_WALS"]
  /\ UNCHANGED << rpcVars, serverVars, procVars, rsVars,
                   masterVars, peVars, regionState, metaTable,
                   walFenced, carryingMeta, zkNode >>
```

---

### SCPFenceWALs(s)

**SCP step 2:** Revoke WAL leases for the crashed server. After this step, the zombie RS cannot write to its WALs. Any write attempt will fail with an HDFS lease exception, triggering RS self-abort. This is the fencing mechanism that prevents write-side split-brain.

**Pre:** `scpState[s] = "FENCE_WALS"`, `availableWorkers > 0`.

**Post:** `walFenced[s] = TRUE`, `scpState` advances to `"ASSIGN"`.

*Source: `ServerCrashProcedure.executeFromState()` `SERVER_CRASH_SPLIT_LOGS` case.*

```tla
SCPFenceWALs(s) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ scpState[s] = "FENCE_WALS"
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
  /\ walFenced' = [walFenced EXCEPT ![s] = TRUE]
  /\ scpState' = [scpState EXCEPT ![s] = "ASSIGN"]
  /\ UNCHANGED << rpcVars, serverVars, procVars, rsVars,
                   masterVars, peVars, regionState, metaTable,
                   scpRegions, carryingMeta, zkNode >>
```

---

### SCPAssignRegion(s, r)

**SCP step 3:** Process ONE region from the SCP's region snapshot. Each invocation handles a single region and removes it from `scpRegions[s]`. Four sub-paths:

#### Skip — `isMatchingRegionLocation` fails

Between `SCPGetRegions` and now, a concurrent TRSP may have moved this region to another server. The implementation skips such regions. If the concurrent TRSP subsequently fails, the region may be lost without manual intervention (HBASE-24293). Skip does not require meta availability.

#### Meta Unavailable — suspend or block

Paths A and B write to meta. If meta is unavailable (`¬MetaIsAvailable`), the procedure suspends (`UseBlockOnMetaWrite=FALSE`, adds `r` to `suspendedOnMeta`) or blocks (`UseBlockOnMetaWrite=TRUE`, adds `r` to `blockedOnMeta`, decrements `availableWorkers`).

#### Path A — TRSP already attached

Location matches; procedure exists. Transition region to `ABNORMALLY_CLOSED` and atomically convert the existing TRSP to `ASSIGN`/`GET_ASSIGN_CANDIDATE`. Requires `MetaIsAvailable`. Clears `r` from `suspendedOnMeta`/`blockedOnMeta` when resetting procedures.

*Source: `ServerCrashProcedure.assignRegions()` acquires `RegionStateNode.lock()`, then calls `regionNode.getProcedure().serverCrashed(env, ...)`; `TRSP.serverCrashed()` → `AM.regionClosedAbnormally()` all execute under that same lock.*

#### Path B — No TRSP attached

Location matches; no procedure. Transition to `ABNORMALLY_CLOSED` and attach a fresh `ASSIGN`/`GET_ASSIGN_CANDIDATE` procedure. Requires `MetaIsAvailable`. Clears `r` from `suspendedOnMeta`/`blockedOnMeta`.

**Pre:** `scpState[s] = "ASSIGN"`, `r ∈ scpRegions[s]`, `walFenced[s] = TRUE`, `availableWorkers > 0`.

**Post:** `r` removed from `scpRegions[s]`, region transitioned (or skipped/suspended/blocked).

*Source: `ServerCrashProcedure.assignRegions()`; `ServerCrashProcedure.isMatchingRegionLocation()`.*

```tla
SCPAssignRegion(s, r) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ scpState[s] = "ASSIGN"
  /\ r \in scpRegions[s]
  /\ walFenced[s] = TRUE
  /\ locked[r] = FALSE
  /\ \/ \* --- Skip: isMatchingRegionLocation fails ---
        /\ regionState[r].location # s
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
        /\ UNCHANGED << rpcVars, serverVars, procVars, rsVars,
                         masterVars, regionState, metaTable,
                         scpState, walFenced, carryingMeta,
                         peVars, zkNode >>
     \/ \* --- Meta unavailable: suspend or block ---
        /\ regionState[r].location = s
        /\ ~MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        /\ IF UseBlockOnMetaWrite = FALSE
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
        /\ UNCHANGED << regionState, metaTable, dispatchedOps,
              pendingReports, rsOnlineRegions, serverState,
              scpState, scpRegions, walFenced, locked,
              carryingMeta, serverRegions, procStore,
              masterVars, zkNode >>
     \/ \* --- Path A: TRSP already attached ---
        /\ MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        /\ regionState[r].location = s
        /\ regionState[r].procType # "NONE"
        /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
        /\ dispatchedOps' =
             [t \in Servers |-> {cmd \in dispatchedOps[t]: cmd.region # r}]
        /\ pendingReports' = {pr \in pendingReports: pr.region # r}
        /\ regionState' =
             [regionState EXCEPT
             ![r].state = "ABNORMALLY_CLOSED",
             ![r].location = NoServer,
             ![r].procType = "ASSIGN",
             ![r].procStep = "GET_ASSIGN_CANDIDATE",
             ![r].targetServer = NoServer,
             ![r].retries = 0]
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] = [ state |-> "ABNORMALLY_CLOSED", location |-> NoServer ]]
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
        /\ procStore' =
             [procStore EXCEPT
             ![r] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
        /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \ { r }]
        /\ UNCHANGED << masterVars, serverState, scpState,
              walFenced, locked, carryingMeta, zkNode >>
        \* Clear r from suspended/blocked sets if it was waiting on meta.
        /\ suspendedOnMeta' = suspendedOnMeta \ { r }
        /\ blockedOnMeta' = blockedOnMeta \ { r }
        /\ availableWorkers' =
             IF r \in blockedOnMeta
             THEN availableWorkers + 1
             ELSE availableWorkers
     \/ \* --- Path B: No TRSP attached ---
        /\ MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
        /\ regionState[r].location = s
        /\ regionState[r].procType = "NONE"
        /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
        /\ pendingReports' =
             {pr \in pendingReports: pr.code # "OPENED" \/ pr.region # r}
        /\ regionState' =
             [regionState EXCEPT
             ![r].state = "ABNORMALLY_CLOSED",
             ![r].location = NoServer,
             ![r].procType = "ASSIGN",
             ![r].procStep = "GET_ASSIGN_CANDIDATE",
             ![r].targetServer = NoServer,
             ![r].retries = 0]
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] = [ state |-> "ABNORMALLY_CLOSED", location |-> NoServer ]]
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
        /\ procStore' =
             [procStore EXCEPT
             ![r] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
        /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \ { r }]
        /\ UNCHANGED << masterVars, dispatchedOps, serverState,
              scpState, walFenced, locked, carryingMeta, zkNode >>
        \* Clear r from suspended/blocked sets if it was waiting on meta.
        /\ suspendedOnMeta' = suspendedOnMeta \ { r }
        /\ blockedOnMeta' = blockedOnMeta \ { r }
        /\ availableWorkers' =
             IF r \in blockedOnMeta
             THEN availableWorkers + 1
             ELSE availableWorkers
```

---

### SCPDone(s)

**SCP step 4:** All regions processed. Mark SCP as complete.

**Pre:** `scpState[s] = "ASSIGN"`, `scpRegions[s] = {}` (all processed), `availableWorkers > 0`.

**Post:** `scpState[s] = "DONE"`.

*Source: `ServerCrashProcedure.executeFromState()` `SERVER_CRASH_FINISH` case — the procedure completes and is cleaned up by the `ProcedureExecutor`.*

```tla
SCPDone(s) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ scpState[s] = "ASSIGN"
  /\ scpRegions[s] = {}
  /\ scpState' = [scpState EXCEPT ![s] = "DONE"]
  /\ UNCHANGED << rpcVars, serverVars, procVars, rsVars,
                   masterVars, peVars, regionState, metaTable,
                   scpRegions, walFenced, carryingMeta, zkNode >>
```
