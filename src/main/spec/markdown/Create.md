# Create

**Source:** [`Create.tla`](../Create.tla)

CreateTable procedure actions — single-region table creation.

---

```tla
------------------------------- MODULE Create ---------------------------------
```

Models `CreateTableProcedure`: creates a new table with a single region covering the full keyspace `[0, MaxKey)`, writes meta, and spawns a child `ASSIGN` TRSP to open the region.

**Forward-path actions:**
- **`CreateTablePrepare`** — pick unused identifier, assign keyspace, write meta, spawn child `ASSIGN` TRSP
- **`CreateTableDone`** — region `OPEN`, clear `parentProc`

The `parentProc[r]` variable tracks the `CREATE` procedure's state. It persists across the child TRSP lifecycle and survives master crash. The child TRSP uses the normal TRSP machinery (`procType`/`procStep`).

> *Source:* `CreateTableProcedure.java` — `PRE_OPERATION → WRITE_FS_LAYOUT → ADD_TO_META → ASSIGN_REGIONS → UPDATE_DESC_CACHE → POST_OPERATION`. Filesystem, descriptor cache, and coprocessor operations abstracted. `ADD_TO_META` + `ASSIGN_REGIONS` collapsed into `CreateTablePrepare`; `POST_OPERATION` collapsed into `CreateTableDone`.

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

## CreateTable Actions

### `CreateTablePrepare(t, r)`

Create a new table with a single region covering `[0, MaxKey)`.

Picks an unused region identifier `r`, assigns the full keyspace, writes meta as `CLOSED`/`NoServer`, spawns a child `ASSIGN` TRSP, and sets `parentProc` for table-level tracking.

**Pre:** master alive, PEWorker available, meta available, table `t` not in use (no region belongs to it), `r` is an unused identifier (`NoRange`, `NoTable`), `TableLockFree(t)`.
**Post:** region `r` belongs to table `t`, has keyspace `[0, MaxKey)`, state = `CLOSED` with `ASSIGN` TRSP spawned, `parentProc = [CREATE, SPAWNED_OPEN]`.

> *Source:* `CreateTableProcedure.executeFromState()` — `ADD_TO_META` + `ASSIGN_REGIONS` steps.

```tla
CreateTablePrepare(t, r) ==
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

Table `t` must not be in use (no region belongs to it).

```tla
  /\ ~ \E r2 \in Regions: metaTable[r2].table = t
```

`TableLockFree`: no `parentProc` active on table `t`. (Vacuously true when no region belongs to `t`, but kept for completeness and consistency with Split/Merge guards.)

```tla
  /\ TableLockFree(t)
```

`r` must be an unused identifier.

```tla
  /\ metaTable[r].keyRange = NoRange
  /\ metaTable[r].table = NoTable
```

Assign keyspace `[0, MaxKey)`, set table identity, and write meta as `CLOSED`/`NoServer`. All four `metaTable` fields are set in a single field-level EXCEPT.

```tla
  /\ metaTable' =
       [metaTable EXCEPT
       ![r].state = "CLOSED",
       ![r].location = NoServer,
       ![r].keyRange = [ startKey |-> 0, endKey |-> MaxKey ],
       ![r].table = t ]
```

Set in-memory state: `CLOSED` with child `ASSIGN` TRSP spawned.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r] =
       [ state |-> "CLOSED",
         location |-> NoServer,
         procType |-> "ASSIGN",
         procStep |-> "GET_ASSIGN_CANDIDATE",
         targetServer |-> NoServer,
         retries |-> 0
       ]]
```

Persist the child `ASSIGN` procedure to `procStore`.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

Set `parentProc` for table-level tracking. `ref1`/`ref2` are `NoRegion` (single-region creation needs no cross-references).

```tla
  /\ parentProc' =
       [parentProc EXCEPT ![r] = [ type |-> "CREATE", step |-> "SPAWNED_OPEN",
                                    ref1 |-> NoRegion, ref2 |-> NoRegion ]]
```

Everything else unchanged.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        tableEnabled,
        zkNode
     >>
```

### `CreateTableDone(t)`

Complete the `CreateTable` procedure after the region is `OPEN`.

All regions of table `t` with `parentProc.type = "CREATE"` must be `OPEN` with no active procedure (the `ASSIGN` TRSP has completed). Clears `parentProc` on all such regions.

**Pre:** master alive, PEWorker available, at least one region of table `t` has `parentProc.type = "CREATE"`, and all such regions are `OPEN` with `procType = "NONE"`.
**Post:** `parentProc` cleared on all regions of table `t` with `CREATE`.

> *Source:* `CreateTableProcedure` `POST_OPERATION`.

```tla
CreateTableDone(t) ==
```

Master must be alive to execute the procedure.

```tla
  /\ masterAlive = TRUE
```

PEWorker thread available to execute this step.

```tla
  /\ availableWorkers > 0
```

At least one region of table `t` has a `CREATE` parent procedure.

```tla
  /\ \E r \in Regions:
       /\ metaTable[r].table = t
       /\ parentProc[r].type = "CREATE"
```

All regions of table `t` with `CREATE` `parentProc` are `OPEN` and unattached.

```tla
  /\ \A r \in Regions:
       (metaTable[r].table = t /\ parentProc[r].type = "CREATE") =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
```

Clear `parentProc` on all regions of table `t` with `CREATE`.

```tla
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "CREATE"
         THEN NoParentProc
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

```tla
============================================================================
```
