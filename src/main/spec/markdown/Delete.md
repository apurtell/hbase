# Delete

**Source:** [`Delete.tla`](../Delete.tla)

DeleteTable procedure actions — table deletion with identifier freeing.

---

```tla
------------------------------ MODULE Delete ----------------------------------
```

Models `DeleteTableProcedure` actions: Delete a disabled table by removing its region from meta and clearing state.

### Implementation State Simplification

The implementation's [`DeleteTableProcedure`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/procedure/DeleteTableProcedure.java) uses the `DeleteTableState` enum with 7 values: `PRE_OPERATION`, `REMOVE_TABLE_FROM_CACHE`, `MARK_REGIONS_CLOSED`, `CLEAR_FS_LAYOUT`, `REMOVE_FROM_META`, `DELETE_TABLE_FROM_FS`, `POST_OPERATION`. The model collapses these into two actions:

- **`DeleteTablePrepare`** — `PRE_OPERATION` + `MARK_REGIONS_CLOSED` + `REMOVE_FROM_META` (removes region from meta)
- **`DeleteTableDone`** — `CLEAR_FS_LAYOUT` + `DELETE_TABLE_FROM_FS` + `REMOVE_TABLE_FROM_CACHE` + `POST_OPERATION`

Omitted operations: `CLEAR_FS_LAYOUT` removes HFiles and HRegion directories from HDFS. `DELETE_TABLE_FROM_FS` removes the table-level directory. `REMOVE_TABLE_FROM_CACHE` clears `TableDescriptor` cache. All are filesystem/cache operations orthogonal to assignment.

### Disabled-Table Precondition

`DeleteTableProcedure.prepareDelete()` checks `tableState.isDisabled()` and throws `TableNotDisabledException` if the table is still enabled. This maps to the model's guard that regions must be in `CLOSED` or `OFFLINE` state (which they will be after `DisableTableProcedure` completes) and `tableEnabled[t] = "DISABLED"`. The spec enforces this via the `regionState[r].state \in {"CLOSED", "OFFLINE"}` guard on `DeleteTablePrepare`.

### Identifier Recycling

The model's immediate reset of `keyRange` to `NoRange` and `metaTable` entries models the implementation's eventual GC of meta entries. In production, the `REMOVE_FROM_META` step deletes the region's meta rows, and `DELETE_TABLE_FROM_FS` removes the HDFS directory. The model's atomic reset is a safe abstraction because the deleted region identifier becomes immediately reusable for `CreateTableProcedure`, which is the same end state.

### Staged Deletion and Crash Vulnerability

The implementation's `DeleteTableProcedure` has 6 states and performs a multi-step staged deletion: first FS layout (`CLEAR_FS_LAYOUT`), then meta removal (`REMOVE_FROM_META`), then assignment state cleanup. The spec atomically clears meta and resets region state in a single `DeleteTableDone` action, which is a significant simplification. The implementation's staged deletion creates crash-vulnerability windows not captured by the spec. If a crash occurs after FS deletion but before meta cleanup, the implementation must handle this inconsistency on recovery, a path the spec does not exercise. The implementation optionally takes a snapshot of the table data before deletion. Snapshot operations are orthogonal to the assignment protocol and are not modeled.

Models `DeleteTableProcedure`: deletes all regions of a table, freeing region identifiers back to the unused pool. Requires all regions of the target table to be in `{"CLOSED","OFFLINE"}` with `procType = "NONE"` (modeling the disabled-table precondition).

**Forward-path actions:**
- **`DeleteTablePrepare`** — acquire table lock, mark all regions for deletion
- **`DeleteTableDone`** — atomically clear meta, free identifiers, reset state

No child TRSPs are spawned — regions are already closed. The `parentProc[r]` variable tracks the `DELETE` procedure's state.

> *Source:* `DeleteTableProcedure.java` — `PRE_OPERATION → CLEAR_FS_LAYOUT → REMOVE_FROM_META → UNASSIGN_REGIONS → POST_OPERATION`. Filesystem, descriptor cache, and coprocessor operations abstracted. `REMOVE_FROM_META` + `POST_OPERATION` collapsed into `DeleteTableDone`.

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

## DeleteTable Actions

### `DeleteTablePrepare(t)`

Acquire exclusive table lock and mark all regions for deletion.

All regions of table `t` must be in `{"CLOSED","OFFLINE"}` with no active procedure — modeling the disabled-table precondition from the implementation (`DeleteTableProcedure` requires the table to be disabled).

**Pre:** master alive, PEWorker available, meta available, `TableLockFree(t)`, at least one region belongs to `t`, all regions of `t` in `{"CLOSED","OFFLINE"}` with `procType = "NONE"`.
**Post:** `parentProc` set to `[DELETE, COMPLETING]` on all regions of `t`.

> *Source:* `DeleteTableProcedure.executeFromState()` — `PRE_OPERATION` step.

```tla
DeleteTablePrepare(t) ==
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

`TableLockFree`: no `parentProc` active on table `t`.

```tla
  /\ TableLockFree(t)
```

At least one region belongs to table `t`.

```tla
  /\ \E r \in Regions: metaTable[r].table = t
```

All regions of table `t` must be disabled (`CLOSED` or `OFFLINE`) with no active procedure.

```tla
  /\ \A r \in Regions:
       metaTable[r].table = t =>
         /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
         /\ regionState[r].procType = "NONE"
```

Set `parentProc` for table-level tracking on all regions of `t`.

```tla
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t
         THEN [ type |-> "DELETE", step |-> "COMPLETING",
                ref1 |-> NoRegion, ref2 |-> NoRegion ]
         ELSE parentProc[r]]
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
        tableEnabled,
        zkNode
     >>
```

### `DeleteTableDone(t)`

Complete the `DeleteTable` procedure: clear meta, free identifiers, reset state.

Atomically resets all DELETE-bearing regions of table `t` to the initial unused state: meta cleared (state, location, keyRange, table), in-memory state reset, `parentProc` cleared. Region identifiers return to the unused pool (`metaTable[r].keyRange = NoRange`, `metaTable[r].table = NoTable`).

**Pre:** master alive, PEWorker available, at least one region of table `t` has `parentProc = [DELETE, COMPLETING]`.
**Post:** `metaTable`, `regionState`, `parentProc` all reset for DELETE-bearing regions of `t`.

> *Source:* `DeleteTableProcedure.executeFromState()` — `REMOVE_FROM_META` + `POST_OPERATION` steps (collapsed).

```tla
DeleteTableDone(t) ==
```

Master must be alive to execute the procedure.

```tla
  /\ masterAlive = TRUE
```

PEWorker thread available to execute this step.

```tla
  /\ availableWorkers > 0
```

At least one region of table `t` has a `DELETE` parent procedure in `COMPLETING` step.

```tla
  /\ \E r \in Regions:
       /\ metaTable[r].table = t
       /\ parentProc[r].type = "DELETE"
       /\ parentProc[r].step = "COMPLETING"
```

Reset `metaTable` for all DELETE-bearing regions of `t`: clear state, location, keyRange, and table.

```tla
  /\ metaTable' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "DELETE"
         THEN [state |-> "OFFLINE", location |-> NoServer,
               keyRange |-> NoRange, table |-> NoTable]
         ELSE metaTable[r]]
```

Reset in-memory state to initial unused state.

```tla
  /\ regionState' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "DELETE"
         THEN [ state |-> "OFFLINE",
                location |-> NoServer,
                procType |-> "NONE",
                procStep |-> "IDLE",
                targetServer |-> NoServer,
                retries |-> 0
              ]
         ELSE regionState[r]]
```

Clear `parentProc` on all DELETE-bearing regions.

```tla
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "DELETE"
         THEN NoParentProc
         ELSE parentProc[r]]
```

Everything else unchanged.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        procStore,
        tableEnabled,
        zkNode
     >>
```

```tla
============================================================================
```
