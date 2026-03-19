# Disable

**Source:** [`Disable.tla`](../Disable.tla)

DisableTable procedure actions — disables a table by closing all its regions.

---

```tla
------------------------------ MODULE Disable ----------------------------------
```

Models `DisableTableProcedure` actions: Disable a table by transitioning it from `ENABLED` → `DISABLING` → `DISABLED`, closing all its regions via child UNASSIGN TRSPs.

### Implementation State Simplification

The implementation's [`DisableTableProcedure`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/procedure/DisableTableProcedure.java) uses the `DisableTableState` enum with 7 values: `PREPARE`, `PRE_OPERATION`, `SET_DISABLING_TABLE_STATE`, `MARK_REGIONS_OFFLINE`, `ADD_REPLICATION_BARRIER`, `SET_DISABLED_TABLE_STATE`, `POST_OPERATION`. The model collapses these into three actions:

- **`DisablePrepare`** — `PREPARE` + `PRE_OPERATION` + `SET_DISABLING_TABLE_STATE` (set table `DISABLING`)
- **`DisableUnassign`** — `MARK_REGIONS_OFFLINE` (spawn child UNASSIGN TRSPs)
- **`DisableDone`** — `SET_DISABLED_TABLE_STATE` + `POST_OPERATION` (set table `DISABLED`)

Omitted: `PRE_OPERATION` fires coprocessor `preDisableTable` hook. `ADD_REPLICATION_BARRIER` adds a replication barrier in meta for each region. `POST_OPERATION` fires coprocessor `postDisableTable` hook. All are orthogonal to the assignment protocol.

### Unmodeled Behaviors

The implementation's `DISABLE_TABLE_ADD_REPLICATION_BARRIER` state inserts replication barriers into `hbase:meta` for each region — this is not modeled because replication barriers do not affect the assignment protocol. The table state transitions (`ENABLED` → `DISABLING` → `DISABLED`) are faithfully modeled through `tableEnabled[t]`: `DisableTablePrepare` sets `"DISABLING"` and `DisableTableDone` sets `"DISABLED"`, matching the implementation's `TableStateManager.setTableState()` persistence.

### Table State Machine

[`TableStateManager.setTableState()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/TableStateManager.java) stores the table state as a protobuf `HBaseProtos.TableState` record in an `hbase:meta` table row keyed by the table name. The transition path is `ENABLED → DISABLING → DISABLED`, with `DISABLING` persisted in meta before any regions are closed. The model's `tableEnabled[t]` variable mirrors this persistence: `DisablePrepare` sets it to `"DISABLING"`, and `DisableDone` sets it to `"DISABLED"`.

### MARK_REGIONS_OFFLINE vs UNASSIGN

In `MARK_REGIONS_OFFLINE`, the implementation calls `assignmentManager.unassign(regionInfos)`, which creates one `TransitRegionStateProcedure` (type `UNASSIGN`) per region and adds them as child procedures via `addChildProcedure()`. The model's `DisableUnassign` action captures this exactly: it spawns child UNASSIGN TRSPs using the normal `TRSPCreate` machinery. The "offline" naming in the implementation is historical — the actual operation is an unassignment.

### Concurrent Request Rejection

The `DISABLING` intermediate state serves as a gate for concurrent client requests. `Admin.disableTable()` in the client checks `!table.isEnabled()` before proceeding, and `MasterRpcServices.disableTable()` checks `tableState.isEnabled()`. If the table is already `DISABLING` or `DISABLED`, the request is rejected. The model captures this via the `tableEnabled[t] = "ENABLED"` guard on `DisablePrepare` — since TLC explores all interleavings, this guard prevents any concurrent disable from starting.

Models `DisableTableProcedure`: disables a table by closing all its regions. All regions must be `OPEN` with `procType = "NONE"` (enabled-table precondition). Spawns `UNASSIGN` TRSPs to close each region.

**Forward-path actions:**
- **`DisableTablePrepare`** — acquire table lock, set `tableEnabled[t] = "DISABLING"`, spawn child UNASSIGN TRSPs for all regions
- **`DisableTableDone`** — all regions `CLOSED`/`OFFLINE`, clear `parentProc`

The `parentProc[r]` variable tracks the `DISABLE` procedure's state. Child TRSPs use the normal TRSP machinery (`procType`/`procStep`).

> *Source:* `DisableTableProcedure.java` — `PREPARE → PRE_OP → SET_DISABLING → MARK_OFFLINE → ADD_REPLICATION_BARRIER → SET_DISABLED → POST_OP`. Coprocessor hooks and replication barriers omitted (orthogonal). Collapsed to `DisableTablePrepare` + `DisableTableDone`.

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

### Helper Predicates

Is meta available? (no server in `ASSIGN_META` state)

```tla
MetaIsAvailable == \A s \in Servers: scpState[s] # "ASSIGN_META"
```

No `parentProc` of any type active on any region of table `t`.

```tla
TableLockFree(t) == ~\E r2 \in Regions: /\ metaTable[r2].table = t
                                         /\ parentProc[r2].type # "NONE"
```

```tla
---------------------------------------------------------------------------
```

## DisableTable Actions

### `DisableTablePrepare(t)`

Disable a table by closing all its regions.

All regions of table `t` must be `OPEN` with no active procedure — modeling the enabled-table precondition from the implementation. Atomically spawns `UNASSIGN` TRSPs for each region and sets `tableEnabled[t] = "DISABLING"`.

**Pre:** master alive, PEWorker available, meta available, `tableEnabled[t] = "ENABLED"`, `TableLockFree(t)`, at least one region belongs to `t`, all regions of `t` `OPEN` with `procType = "NONE"`.
**Post:** `parentProc` set to `[DISABLE, SPAWNED_CLOSE]` on all regions, UNASSIGN TRSPs spawned, `tableEnabled[t] = "DISABLING"`.

> *Source:* `DisableTableProcedure.executeFromState()` — `PREPARE → PRE_OP → SET_DISABLING` steps (collapsed).

```tla
DisableTablePrepare(t) ==
```

Master must be alive to execute the procedure.

```tla
  /\ masterAlive = TRUE
```

PEWorker thread available to execute this step.

```tla
  /\ availableWorkers > 0
```

Meta region must be accessible (not on a crashed server).

```tla
  /\ MetaIsAvailable
```

Table must be currently enabled.

```tla
  /\ tableEnabled[t] = "ENABLED"
```

`TableLockFree`: no `parentProc` active on table `t`.

```tla
  /\ TableLockFree(t)
```

At least one region belongs to table `t`.

```tla
  /\ \E r \in Regions: metaTable[r].table = t
```

All regions of table `t` must be `OPEN` with no active procedure.

```tla
  /\ \A r \in Regions:
       metaTable[r].table = t =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
```

Set `parentProc` for table-level tracking on all regions of `t` and spawn child UNASSIGN TRSPs.

```tla
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t
         THEN [ type |-> "DISABLE", step |-> "SPAWNED_CLOSE",
                ref1 |-> NoRegion, ref2 |-> NoRegion ]
         ELSE parentProc[r]]
```

Spawn UNASSIGN TRSP on each region: set `procType=UNASSIGN`, `procStep=CLOSE`, `targetServer=current location`.

```tla
  /\ regionState' =
       [r \in Regions |->
         IF metaTable[r].table = t
         THEN [ state |-> regionState[r].state,
                location |-> regionState[r].location,
                procType |-> "UNASSIGN",
                procStep |-> "CLOSE",
                targetServer |-> regionState[r].location,
                retries |-> 0
              ]
         ELSE regionState[r]]
```

Persist child UNASSIGN procedures to `procStore`.

```tla
  /\ procStore' =
       [r \in Regions |->
         IF metaTable[r].table = t
         THEN NewProcRecord("UNASSIGN", "CLOSE", regionState[r].location, NoTransition)
         ELSE procStore[r]]
```

Set `tableEnabled[t] = "DISABLING"` (intermediate state).

> *Source:* `DisableTableProcedure.SET_DISABLING_TABLE_STATE`.

```tla
  /\ tableEnabled' = [tableEnabled EXCEPT ![t] = "DISABLING"]
```

Everything else unchanged.

```tla
  /\ UNCHANGED << metaTable,
        scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        zkNode
     >>
```

### `DisableTableDone(t)`

Complete the `DisableTable` procedure after all regions are closed.

All regions of table `t` with `parentProc.type = "DISABLE"` must be in `{CLOSED, OFFLINE}` with no active procedure (the UNASSIGN TRSPs have completed). Clears `parentProc` on all such regions.

**Pre:** master alive, PEWorker available, at least one region of table `t` has `parentProc.type = "DISABLE"`, and all such regions are `CLOSED`/`OFFLINE` with `procType = "NONE"`.
**Post:** `parentProc` cleared on all regions of table `t` with `DISABLE`.

> *Source:* `DisableTableProcedure` `POST_OP`.

```tla
DisableTableDone(t) ==
```

Master must be alive to execute the procedure.

```tla
  /\ masterAlive = TRUE
```

PEWorker thread available to execute this step.

```tla
  /\ availableWorkers > 0
```

At least one region of table `t` has a `DISABLE` parent procedure.

```tla
  /\ \E r \in Regions:
       /\ metaTable[r].table = t
       /\ parentProc[r].type = "DISABLE"
```

All regions of table `t` with `DISABLE` `parentProc` are closed and unattached.

```tla
  /\ \A r \in Regions:
       (metaTable[r].table = t /\ parentProc[r].type = "DISABLE") =>
         /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
         /\ regionState[r].procType = "NONE"
```

Clear `parentProc` on all regions of table `t` with `DISABLE`.

```tla
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "DISABLE"
         THEN NoParentProc
         ELSE parentProc[r]]
```

Set `tableEnabled[t] = "DISABLED"` (final state).

> *Source:* `DisableTableProcedure.SET_DISABLED_TABLE_STATE`.

```tla
  /\ tableEnabled' = [tableEnabled EXCEPT ![t] = "DISABLED"]
```

Everything else unchanged.

```tla
  /\ UNCHANGED << regionState,
        metaTable,
        scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        procStore,
        zkNode
     >>
```

```tla
============================================================================
```
