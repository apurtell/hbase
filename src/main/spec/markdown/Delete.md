# Delete

**Source:** [`Delete.tla`](../Delete.tla)

DeleteTable procedure actions — table deletion with identifier freeing.

---

```tla
------------------------------ MODULE Delete ----------------------------------
```

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
         regionKeyRange,
         parentProc,
         regionTable,
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
TableLockFree(t) == ~\E r2 \in Regions: /\ regionTable[r2] = t
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
  /\ \E r \in Regions: regionTable[r] = t
```

All regions of table `t` must be disabled (`CLOSED` or `OFFLINE`) with no active procedure.

```tla
  /\ \A r \in Regions:
       regionTable[r] = t =>
         /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
         /\ regionState[r].procType = "NONE"
```

Set `parentProc` for table-level tracking on all regions of `t`.

```tla
  /\ parentProc' =
       [r \in Regions |->
         IF regionTable[r] = t
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
        regionKeyRange,
        regionTable,
        tableEnabled,
        zkNode
     >>
```

### `DeleteTableDone(t)`

Complete the `DeleteTable` procedure: clear meta, free identifiers, reset state.

Atomically resets all DELETE-bearing regions of table `t` to the initial unused state: meta cleared, keyspace freed, table identity removed, in-memory state reset, `parentProc` cleared. Region identifiers return to the unused pool (`regionKeyRange = NoRange`, `regionTable = NoTable`).

**Pre:** master alive, PEWorker available, at least one region of table `t` has `parentProc = [DELETE, COMPLETING]`.
**Post:** `metaTable`, `regionKeyRange`, `regionTable`, `regionState`, `parentProc` all reset for DELETE-bearing regions of `t`.

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
       /\ regionTable[r] = t
       /\ parentProc[r].type = "DELETE"
       /\ parentProc[r].step = "COMPLETING"
```

Reset `metaTable` for all DELETE-bearing regions of `t`.

```tla
  /\ metaTable' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
         THEN [state |-> "OFFLINE", location |-> NoServer]
         ELSE metaTable[r]]
```

Free keyspace: clear `regionKeyRange` to `NoRange`.

```tla
  /\ regionKeyRange' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
         THEN NoRange
         ELSE regionKeyRange[r]]
```

Clear table identity: set `regionTable` to `NoTable`.

```tla
  /\ regionTable' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
         THEN NoTable
         ELSE regionTable[r]]
```

Reset in-memory state to initial unused state.

```tla
  /\ regionState' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
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
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
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
