# Modify

**Source:** [`Modify.tla`](../Modify.tla)

ModifyTable procedure actions — modifies a table's descriptor.

---

```tla
------------------------------ MODULE Modify ----------------------------------
```

Models `ModifyTableProcedure` (`ALTER TABLE`): modifies a table's descriptor. Two administrative workflows are modeled:

### Non-Structural Modification (ENABLED Table)

The table stays enabled. Regions are reopened via a rolling restart (`ReopenTableRegionsProcedure`) to pick up the new descriptor. Idle OPEN regions receive TRSP REOPEN; busy regions (mid-split, mid-merge, mid-SCP) are skipped — matching `ReopenTableRegionsProcedure`'s `regionNode.getProcedure() != null` skip (L221-223).

### Structural Modification (DISABLED Table)

The administrator disables the table first (`DisableTable`), then issues ALTER TABLE for structural changes (e.g., column family add/remove, coprocessor changes, region replica count), then re-enables the table (`EnableTable`). Since all regions are already CLOSED/OFFLINE, no reopens are needed — the modify procedure just acquires/releases the exclusive table lock.

> *Source:* `ModifyTableProcedure.preflightChecks()` L119-152 rejects structural changes when `reopenRegions=true` (enabled table); the admin must disable first.

### Implementation State Simplification

The implementation's [`ModifyTableProcedure`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/procedure/ModifyTableProcedure.java) uses the `ModifyTableState` enum with states: `PREPARE`, `PRE_OPERATION`, `SNAPSHOT`, `CLOSE_EXCESS_REPLICAS`, `UPDATE_TABLE_DESCRIPTOR`, `REMOVE_REPLICA_COLUMN`, `POST_OPERATION`, `REOPEN_ALL_REGIONS`, `ASSIGN_NEW_REPLICAS`, `DELETE_FS_LAYOUT`, `SYNC_ERASURE_CODING_POLICY`. The model collapses these into two actions:

- **`ModifyTablePrepare`** — `PREPARE` + `PRE_OP` + `UPDATE_DESCRIPTOR` + `REOPEN_ALL_REGIONS` (acquire lock, spawn REOPENs or skip)
- **`ModifyTableDone`** — `POST_OPERATION` (all reopens complete, release lock)

Omitted: `SNAPSHOT` (recovery snapshot for column deletion), `CLOSE_EXCESS_REPLICAS` / `REMOVE_REPLICA_COLUMN` / `ASSIGN_NEW_REPLICAS` (region replica adjustment), `DELETE_FS_LAYOUT` (HFile cleanup), `SYNC_ERASURE_CODING_POLICY` (erasure coding sync). All are orthogonal to assignment state.

### Structural vs Non-Structural Non-Determinism

Structural modifications on ENABLED tables are rejected by the implementation (`preflightChecks` L119-152). This is modeled by the non-deterministic choice of whether `ModifyTablePrepare` fires on an enabled table — TLC explores both "admin issues non-structural ALTER" (fires) and "admin chooses to disable first" (does not fire, `DisableTable` fires instead).

**Forward-path actions:**
- **`ModifyTablePrepare`** — acquire exclusive table lock, spawn child TRSP REOPEN for all idle OPEN regions (ENABLED) or mark all regions COMPLETING (DISABLED)
- **`ModifyTableDone`** — all REOPEN TRSPs completed (or skipped), clear `parentProc`

The `parentProc[r]` variable tracks the `MODIFY` procedure's state:
- `[MODIFY, SPAWNED_OPEN]`: region targeted for REOPEN
- `[MODIFY, COMPLETING]`: region skipped (busy or non-OPEN) or structural modify (DISABLED table)

Child TRSPs use the normal TRSP machinery (`procType`/`procStep`).

`ModifyTable` does NOT change `tableEnabled`. The table lifecycle state is managed exclusively by `DisableTable`/`EnableTable`.

> *Source:* `ModifyTableProcedure.java` — `executeFromState()` L199-311: `PREPARE → PRE_OP → UPDATE_DESCRIPTOR → REOPEN_ALL_REGIONS (if enabled) → POST_OP → DONE`. L263-268: `if (isTableEnabled(env))` spawns `ReopenTableRegionsProcedure`. Collapsed to `ModifyTablePrepare` + `ModifyTableDone`.

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

## ModifyTable Actions

### `ModifyTablePrepare(t)`

Modify a table's descriptor. Two disjuncts model the two administrative workflows.

**Pre:** master alive, PEWorker available, meta available, `UseModify`, `TableLockFree(t)`, at least one region belongs to `t`, `tableEnabled[t] ∈ {"ENABLED", "DISABLED"}`.
**Post:** `parentProc` set on all regions of `t`. `tableEnabled` unchanged.

> *Source:* `ModifyTableProcedure.executeFromState()` L199-311, `ReopenTableRegionsProcedure.executeFromState()` L204-233.

```tla
ModifyTablePrepare(t) ==
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

ModifyTable feature must be enabled.

```tla
  /\ UseModify = TRUE
```

`TableLockFree`: no `parentProc` active on table `t`.

```tla
  /\ TableLockFree(t)
```

At least one region belongs to table `t`.

```tla
  /\ \E r \in Regions: metaTable[r].table = t
```

#### Disjunct 1: Non-structural modification (ENABLED table)

Table must be enabled. Structural modifications on enabled tables are rejected by `preflightChecks()` (L119-152); the non-deterministic choice of whether this disjunct fires models the administrator issuing a non-structural ALTER TABLE.

> *Source:* `ModifyTableProcedure.executeFromState()` L264: `if (isTableEnabled(env))` → spawns `ReopenTableRegionsProcedure`.

```tla
  /\ \/ /\ tableEnabled[t] = "ENABLED"
```

Set `parentProc` for table-level tracking on all regions of `t`. Idle OPEN regions: `SPAWNED_OPEN` (will be reopened). Others (busy or non-OPEN): `COMPLETING` (skipped).

> *Source:* `ReopenTableRegionsProcedure` L221-223: `regionNode.getProcedure() != null` → skip.

```tla
        /\ parentProc' =
             [r \in Regions |->
               IF metaTable[r].table = t
               THEN IF /\ regionState[r].state = "OPEN"
                       /\ regionState[r].procType = "NONE"
                    THEN [ type |-> "MODIFY", step |-> "SPAWNED_OPEN",
                           ref1 |-> NoRegion, ref2 |-> NoRegion ]
                    ELSE [ type |-> "MODIFY", step |-> "COMPLETING",
                           ref1 |-> NoRegion, ref2 |-> NoRegion ]
               ELSE parentProc[r]]
```

Spawn TRSP REOPEN on each idle OPEN region of table `t`: set `procType=REOPEN`, `procStep=CLOSE`, `targetServer=current location`. Busy or non-OPEN regions are left unchanged.

```tla
        /\ regionState' =
             [r \in Regions |->
               IF /\ metaTable[r].table = t
                  /\ regionState[r].state = "OPEN"
                  /\ regionState[r].procType = "NONE"
               THEN [ state |-> regionState[r].state,
                      location |-> regionState[r].location,
                      procType |-> "REOPEN",
                      procStep |-> "CLOSE",
                      targetServer |-> regionState[r].location,
                      retries |-> 0
                    ]
               ELSE regionState[r]]
```

Persist child REOPEN procedures to `procStore` for idle OPEN regions.

```tla
        /\ procStore' =
             [r \in Regions |->
               IF /\ metaTable[r].table = t
                  /\ regionState[r].state = "OPEN"
                  /\ regionState[r].procType = "NONE"
               THEN NewProcRecord("REOPEN", "CLOSE", regionState[r].location, NoTransition)
               ELSE procStore[r]]
```

#### Disjunct 2: Structural modification (DISABLED table)

Table must be disabled. All regions are CLOSED/OFFLINE. The administrator has already run `DisableTable`; after this modify completes, the administrator will run `EnableTable`.

> *Source:* `ModifyTableProcedure.preflightChecks()` L119-152 rejects structural changes when `reopenRegions=true`; `ModifyTableProcedure.executeFromState()` L264: `if (!isTableEnabled(env))` → skips reopen entirely.

```tla
     \/ /\ tableEnabled[t] = "DISABLED"
```

All regions of table `t` must be disabled (`CLOSED` or `OFFLINE`) with no active procedure.

```tla
        /\ \A r \in Regions:
             metaTable[r].table = t =>
               /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
               /\ regionState[r].procType = "NONE"
```

All regions marked `COMPLETING` (no reopens needed).

```tla
        /\ parentProc' =
             [r \in Regions |->
               IF metaTable[r].table = t
               THEN [ type |-> "MODIFY", step |-> "COMPLETING",
                      ref1 |-> NoRegion, ref2 |-> NoRegion ]
               ELSE parentProc[r]]
```

No TRSP spawned — regions stay as-is.

```tla
        /\ UNCHANGED << regionState, procStore >>
```

`tableEnabled` unchanged — `ModifyTable` does not alter table lifecycle state. Table lifecycle is managed exclusively by `DisableTable` (`ENABLED → DISABLED`) and `EnableTable` (`DISABLED → ENABLED`).

> *Source:* `ModifyTableProcedure` does not call `setTableState()`.

Everything else unchanged.

```tla
  /\ UNCHANGED << metaTable,
        scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        zkNode,
        tableEnabled
     >>
```

### `ModifyTableDone(t)`

Complete the `ModifyTable` procedure after all reopens complete.

All regions of table `t` with `parentProc.type = "MODIFY"` and `step = "SPAWNED_OPEN"` must be `OPEN` with no active procedure (the REOPEN TRSPs have completed). Regions with `step = "COMPLETING"` were skipped (or structural modify on disabled table) and don't block completion. Clears `parentProc` on all MODIFY-tagged regions of table `t`.

**Pre:** master alive, PEWorker available, at least one region of table `t` has `parentProc.type = "MODIFY"`, and all `SPAWNED_OPEN` regions are `OPEN` with `procType = "NONE"`.
**Post:** `parentProc` cleared on all regions of table `t` with `MODIFY`. `tableEnabled` unchanged.

> *Source:* `ReopenTableRegionsProcedure.executeFromState()` L245-246 (empty regions → `NO_MORE_STATE`); `ModifyTableProcedure.executeFromState()` L254-261 (`!reopenRegions` → `NO_MORE_STATE` after `POST_OPERATION`).

```tla
ModifyTableDone(t) ==
```

Master must be alive to execute the procedure.

```tla
  /\ masterAlive = TRUE
```

PEWorker thread available to execute this step.

```tla
  /\ availableWorkers > 0
```

At least one region of table `t` has a `MODIFY` parent procedure.

```tla
  /\ \E r \in Regions:
       /\ metaTable[r].table = t
       /\ parentProc[r].type = "MODIFY"
```

All regions of table `t` with `MODIFY`/`SPAWNED_OPEN` are `OPEN` and idle. Regions with `MODIFY`/`COMPLETING` are skipped — no check needed.

```tla
  /\ \A r \in Regions:
       (metaTable[r].table = t /\ parentProc[r].type = "MODIFY"
            /\ parentProc[r].step = "SPAWNED_OPEN") =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
```

Clear `parentProc` on all regions of table `t` with `MODIFY`.

```tla
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "MODIFY"
         THEN NoParentProc
         ELSE parentProc[r]]
```

`tableEnabled` unchanged — `ModifyTable` does not alter table lifecycle state. For the structural modification path (DISABLED table), the administrator will subsequently issue `EnableTable` to bring regions back online with the new descriptor.

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
        zkNode,
        tableEnabled
     >>
```

```tla
============================================================================
```
