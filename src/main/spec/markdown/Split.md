# Split

**Source:** [`Split.tla`](../Split.tla)

Split procedure actions using the parent-child framework.

---

```tla
------------------------------- MODULE Split ---------------------------------
```

Models `SplitTableRegionProcedure`: forward path (split a region into two daughters) and pre-PONR rollback (abort and reopen parent). The procedure uses the **parent-child framework**: it spawns child TRSPs via `addChildProcedure()` and yields while children execute.

**Forward-path actions:**
- **`SplitPrepare`** — set `SPLITTING`, create `parentProc`, spawn child `UNASSIGN` TRSP
- **`SplitResumeAfterClose`** — resume after child TRSP completes
- **`SplitUpdateMeta`** — PONR: meta write, materialize daughters, spawn child `ASSIGN` TRSPs
- **`SplitDone`** — daughters `OPEN`, cleanup

**Rollback action:**
- **`SplitFail`** — pre-PONR failure: create fresh `ASSIGN` to reopen parent, clear `parentProc`

The `parentProc[r]` variable tracks the parent procedure's state. It persists across child TRSP lifecycles and survives master crash. Child TRSPs use the normal TRSP machinery (`procType`/`procStep`).

### Parent-Child Framework

The implementation's [parent-child procedure mechanism](file:///Users/andrewpurtell/src/hbase/hbase-procedure/src/main/java/org/apache/hadoop/hbase/procedure2/Procedure.java) uses `addChildProcedure()` to spawn child procedures. The parent enters `ProcedureState.WAITING` and yields its PEWorker thread. When all children complete, the `ProcedureExecutor` wakes the parent (via `countDown()` on the child latch) and re-enqueues it for execution.

In the model, this yield/resume pattern is captured by the `parentProc[r].step` state machine:
- **`SPAWNED_CLOSE`** — parent yielded after spawning child UNASSIGN TRSPs
- **`PONR`** — parent resumed, crossing point-of-no-return (child completes detected via `regionState[r].state = "CLOSED"` + `procType = "NONE"` guard)
- **`SPAWNED_OPEN`** — parent yielded after spawning child ASSIGN TRSPs for daughters

The non-deterministic choice between `SplitResumeAfterClose` and `SplitFail` at the `SPAWNED_CLOSE` → `PONR` boundary models the success/failure decision that occurs inside the implementation's `checkClosedRegions()`. TLC exhaustively explores both branches for every reachable split state.

### Implementation State Collapse

The implementation's [`SplitTableRegionProcedure`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/SplitTableRegionProcedure.java) uses the `SplitTableRegionState` enum with 11 values. The spec collapses these into 5 steps (`SPAWNED_CLOSE`, `PONR`, `SPAWNED_OPEN`, `COMPLETING`, plus rollback). The collapsed states are:

- **`PREPARE` + `PRE_OPERATION`** → `SplitPrepare`: Coprocessor pre-split hooks are abstracted away entirely. This is safe for assignment correctness but means the spec cannot detect bugs in coprocessor interaction (e.g., a coprocessor vetoing a split after partial state has been set).

- **`CHECK_CLOSED_REGIONS` + `CREATE_DAUGHTER_REGIONS` + `WRITE_MAX_SEQUENCE_ID_FILE` + `PRE_OPERATION_BEFORE_META` + `UPDATE_META`** → PONR (`SplitUpdateMeta`): Five implementation states collapsed into one atomic action. The critical question is whether the intermediate filesystem states (`createDaughterRegions()`, `writeMaxSequenceIdFile()`) can fail in ways that leave orphaned state. In the implementation, these states are all pre-PONR; failure triggers rollback. The spec captures the PONR boundary correctly — `SplitFail` can fire at `SPAWNED_CLOSE` but not after `SplitUpdateMeta`. The filesystem operations themselves (`HRegionFileSystem.createRegionOnFileSystem()`) are not modeled, which is appropriate since HDFS-level failures would manifest as HBase-level exceptions triggering rollback.

- **`PRE_OPERATION_AFTER_META` + `OPEN_CHILD_REGIONS`** → `SPAWNED_OPEN`

- **`POST_OPERATION`** → `SplitDone` (`COMPLETING`)

### Safety-Critical Properties

The split's safety-critical properties are: (1) no daughters materialized pre-PONR (`SplitAtomicity`), (2) daughters always have `ASSIGN` procedures (`NoOrphanedDaughters`), (3) keyspace coverage maintained (`KeyspaceCoverage`), and (4) parent keyspace cleared after daughters are open (`SplitCompleteness`). All four are verifiable at the 5-state abstraction level. The abstracted filesystem operations are either idempotent or roll back cleanly on failure.

> *Source:* `SplitTableRegionProcedure.rollbackState()` L368–411; `openParentRegion()` L647–651.

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

Does region `r` have an assigned keyspace?

```tla
RegionExists(r) == metaTable[r].keyRange # NoRange
```

Does region `r` have an active parent procedure?

```tla
HasActiveParent(r) == parentProc[r].type # "NONE"
```

No exclusive-type parent procedure (`CREATE`, `DELETE`, `TRUNCATE`) active on any region of the same table as `r`.

```tla
NoTableExclusiveLock(r) ==
  LET t == metaTable[r].table
  IN t # NoTable =>
       ~ \E r2 \in Regions:
            /\ metaTable[r2].table = t
            /\ parentProc[r2].type \in TableExclusiveType
```

Is meta available? (no server carrying meta is crashed)

```tla
MetaIsAvailable ==
  \A s \in Servers: carryingMeta[s] = FALSE \/ serverState[s] = "ONLINE"
```

```tla
---------------------------------------------------------------------------
```

## Split Actions

### `SplitPrepare(r)`

Initiate a split on an `OPEN` region. Atomically sets `SPLITTING` in-memory and meta, creates `parentProc`, and spawns the child `UNASSIGN` TRSP (`addChildProcedure`).

In the implementation, `prepareSplitRegion()` and `addChildProcedure(createUnassignProcedures())` execute within the same `executeFromState()` call under the region lock held by `acquireLock()` — they are effectively atomic.

**Pre:** master alive, PEWorker available, region is `OPEN` with a location, no procedure attached, no parent procedure in progress, keyspace width ≥ 2 (can be halved), and at least 2 unused region identifiers exist for daughters.
**Post:** parent state = `SPLITTING`, `parentProc = [SPLIT, SPAWNED_CLOSE]`. Meta updated to `SPLITTING`. Child `UNASSIGN` TRSP spawned.

> *Source:* `prepareSplitRegion()` L509–593 sets `SPLITTING`; `CLOSE_PARENT_REGION`: `addChildProcedure(createUnassignProcedures)`.

```tla
SplitPrepare(r) ==
```

Master must be alive to execute the procedure.

```tla
  /\ masterAlive = TRUE
```

PEWorker thread available to execute this step.

```tla
  /\ availableWorkers > 0
```

Region must still exist (has an assigned keyspace).

```tla
  /\ RegionExists(r)
```

Region must be `OPEN` with a location.

```tla
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # NoServer
```

No procedure attached.

```tla
  /\ regionState[r].procType = "NONE"
```

No parent procedure in progress on this region.

```tla
  /\ ~HasActiveParent(r)
```

Keyspace wide enough to halve.

```tla
  /\ metaTable[r].keyRange.endKey - metaTable[r].keyRange.startKey >= 2
```

At least 2 unused identifiers available for daughters.

```tla
  /\ Cardinality({d \in Regions: metaTable[d].keyRange = NoRange}) >= 2
```

No table-level exclusive lock on this region's table.

```tla
  /\ NoTableExclusiveLock(r)
```

Transition parent to `SPLITTING` and spawn child `UNASSIGN` TRSP.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r].state =
       "SPLITTING",
       ![r].procType =
       "UNASSIGN",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
```

Update meta to `SPLITTING` (preserving location).

```tla
  /\ metaTable' =
       [metaTable EXCEPT
       ![r].state = "SPLITTING"]
```

Persist the child `UNASSIGN` procedure to `procStore`.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("UNASSIGN", "CLOSE", regionState[r].location, NoTransition)]
```

Create parent procedure record (yielding to child).

```tla
  /\ parentProc' =
       [parentProc EXCEPT ![r] = [ type |-> "SPLIT", step |-> "SPAWNED_CLOSE",
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

### `SplitResumeAfterClose(r)`

Resume the split after the child `UNASSIGN` TRSP completes.

When the TRSP close path finishes, the parent is `CLOSED` with `procType = NONE`. This action detects `parentProc.step = SPAWNED_CLOSE` and re-attaches the `SPLIT` procedure to the parent for protection, then advances to `PONR`.

**Pre:** master alive, PEWorker available, parent is `CLOSED` with no procedure attached, and `parentProc = [SPLIT, SPAWNED_CLOSE]`.
**Post:** `procType = SPLIT` (re-attached for mutual exclusion), `procStep = IDLE`, `parentProc` step = `PONR`.

> *Source:* `checkClosedRegions()` L279–283.

```tla
SplitResumeAfterClose(r) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ RegionExists(r)
```

Parent must be `CLOSED` — the UNASSIGN child completed.

```tla
  /\ regionState[r].state = "CLOSED"
```

No procedure attached — child `UNASSIGN` cleared it.

```tla
  /\ regionState[r].procType = "NONE"
```

Split is pending at the close phase.

```tla
  /\ parentProc[r].type = "SPLIT"
  /\ parentProc[r].step = "SPAWNED_CLOSE"
```

Re-attach `SPLIT` procedure to parent for protection.

```tla
  /\ regionState' =
       [regionState EXCEPT ![r].procType = "SPLIT", ![r].procStep = "IDLE"]
```

Persist `SPLIT` procedure to `procStore`.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("SPLIT", "IDLE", NoServer, NoTransition)]
```

Advance parent to `PONR`.

```tla
  /\ parentProc' = [parentProc EXCEPT ![r].step = "PONR"]
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable,
        tableEnabled,
        zkNode
     >>
```

### `SplitUpdateMeta(r, dA, dB)`

**Point of No Return:** update meta, materialize daughters, spawn child `ASSIGN` TRSPs on daughters.

Non-deterministically picks two unused region identifiers for daughters. Computes `mid = (startKey + endKey) / 2` from the parent keyspace. Atomically:
- **Parent:** `CLOSED` → `SPLIT` (in-memory and meta), location cleared.
- **Daughter A:** keyspace = `[startKey, mid)`, state = `SPLITTING_NEW` (in-memory), `CLOSED` (meta). `procType = ASSIGN` (child spawned).
- **Daughter B:** keyspace = `[mid, endKey)`, state = `SPLITTING_NEW` (in-memory), `CLOSED` (meta). `procType = ASSIGN` (child spawned).

**Pre:** master alive, PEWorker available, meta available, parent has `procType = SPLIT`, `parentProc[r].type = "SPLIT"` and `.step = "PONR"`, state = `CLOSED`. `dA` and `dB` are distinct unused identifiers.
**Post:** parent in `SPLIT` state, daughters materialized with child `ASSIGN` TRSPs. `parentProc` step = `SPAWNED_OPEN`, `ref1 = dA`, `ref2 = dB`.

> *Source:* `AssignmentManager.markRegionAsSplit()` L2364–2390; `RegionStateStore.splitRegion()` L392–395; `OPEN_CHILD_REGIONS`: `addChildProcedure(createAssignProcedures)`.

```tla
SplitUpdateMeta(r, dA, dB) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ MetaIsAvailable
  /\ RegionExists(r)
  /\ regionState[r].procType = "SPLIT"
  /\ parentProc[r].type = "SPLIT"
  /\ parentProc[r].step = "PONR"
  /\ regionState[r].state = "CLOSED"
```

`dA` and `dB` are distinct unused identifiers.

```tla
  /\ dA # dB
  /\ dA # r
  /\ dB # r
  /\ metaTable[dA].keyRange = NoRange
  /\ metaTable[dB].keyRange = NoRange
```

Compute the midpoint of the parent's keyspace.

```tla
  /\ LET startK == metaTable[r].keyRange.startKey
         endK == metaTable[r].keyRange.endKey
         mid == ( startK + endK ) \div 2
     IN \* Update regionState: parent -> SPLIT, daughters -> SPLITTING_NEW
```

with child `ASSIGN` TRSPs spawned.

```tla
        /\ regionState' =
             [regionState EXCEPT
```

Parent: `CLOSED` → `SPLIT`, clear location.

```tla
             ![r].state =
             "SPLIT",
             ![r].location =
             NoServer,
```

Daughter A: `SPLITTING_NEW` with child `ASSIGN` TRSP.

```tla
             ![dA] =
             [ state |-> "SPLITTING_NEW",
               location |-> NoServer,
               procType |-> "ASSIGN",
               procStep |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> NoServer,
               retries |-> 0
             ],
```

Daughter B: `SPLITTING_NEW` with child `ASSIGN` TRSP.

```tla
             ![dB] =
             [ state |-> "SPLITTING_NEW",
               location |-> NoServer,
               procType |-> "ASSIGN",
               procStep |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> NoServer,
               retries |-> 0
             ]]
```

Update meta: parent → `SPLIT`, daughters → `CLOSED` with keyRanges and table identity. All `metaTable` fields are set via field-level EXCEPT.

```tla
        /\ metaTable' =
             [metaTable EXCEPT
             ![r].state = "SPLIT",
             ![r].location = NoServer,
             ![dA].state = "CLOSED",
             ![dA].location = NoServer,
             ![dA].keyRange = [ startKey |-> startK, endKey |-> mid ],
             ![dA].table = metaTable[r].table,
             ![dB].state = "CLOSED",
             ![dB].location = NoServer,
             ![dB].keyRange = [ startKey |-> mid, endKey |-> endK ],
             ![dB].table = metaTable[r].table ]
```

Persist daughter `ASSIGN` procedures to `procStore`.

```tla
        /\ procStore' =
             [procStore EXCEPT
             ![dA] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition),
             ![dB] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

Parent yields to daughter ASSIGNs: step → `SPAWNED_OPEN`.
Store daughter references for `SplitDone` to read back.

```tla
  /\ parentProc' = [parentProc EXCEPT ![r].step = "SPAWNED_OPEN",
                                      ![r].ref1 = dA,
                                      ![r].ref2 = dB]
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

### `SplitDone(r)`

Complete the split after both daughters are `OPEN`.

Clears the parent's keyspace to `NoRange` (region *"deleted"* — models the post-compaction cleanup) and clears the parent procedure state.

**Pre:** master alive, PEWorker available, `parentProc[r].type = "SPLIT"` and `.step = "SPAWNED_OPEN"`, daughters referenced by `parentProc[r].ref1` and `.ref2` are `OPEN` with `procType = NONE` (ASSIGN completed).
**Post:** parent keyspace cleared (`NoRange`), `procType = NONE`, `parentProc = NoParentProc`. Parent stays in `SPLIT` state (terminal).

> *Source:* `POST_OPERATION` state (procedure completes).

```tla
SplitDone(r) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ RegionExists(r)
  /\ parentProc[r].type = "SPLIT"
  /\ parentProc[r].step = "SPAWNED_OPEN"
```

Read daughter references from `parentProc`.

```tla
  /\ LET dA == parentProc[r].ref1
         dB == parentProc[r].ref2
     IN /\ dA # NoRegion
        /\ dB # NoRegion
        \* Both daughters must be OPEN and unattached.
        /\ regionState[dA].state = "OPEN"
        /\ regionState[dA].procType = "NONE"
        /\ regionState[dB].state = "OPEN"
        /\ regionState[dB].procType = "NONE"
```

Clear parent procedure state.

```tla
        /\ regionState' =
             [regionState EXCEPT
             ![r].procType =
             "NONE",
             ![r].procStep =
             "IDLE",
             ![r].targetServer =
             NoServer,
             ![r].retries =
             0]
```

Clear parent keyspace and table via `metaTable` — region *"deleted."*

```tla
        /\ metaTable' =
             [metaTable EXCEPT ![r].keyRange = NoRange,
                               ![r].table = NoTable ]
```

Clear parent from `procStore`.

```tla
  /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
```

Clear parent procedure.

```tla
  /\ parentProc' = [parentProc EXCEPT ![r] = NoParentProc]
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

### `SplitFail(r)`

Pre-PONR rollback: abort the split and create a fresh `ASSIGN` TRSP to reopen the parent region.

Fires non-deterministically at the same precondition as `SplitResumeAfterClose` (parent `CLOSED`, child `UNASSIGN` complete, `parentProc = [SPLIT, SPAWNED_CLOSE]`). The non-deterministic choice between `SplitResumeAfterClose` and `SplitFail` models the success/failure decision: TLC explores both branches.

**The rollback:**
1. Creates a fresh `ASSIGN` TRSP on the parent (`GET_ASSIGN_CANDIDATE`) to reopen it. Matches `openParentRegion()` → `assignRegionForRollback()`.
2. Clears `parentProc` to `NoParentProc` (split is terminated).
3. Reverts meta from `SPLITTING` to `CLOSED` (parent's actual state; the `ASSIGN` TRSP will update meta to `OPENING` → `OPEN` later).
4. Persists the new `ASSIGN` procedure to `procStore`.

No daughters are created pre-PONR (`SplitAtomicity` invariant), so no daughter cleanup is needed. `metaTable` keyRange is unchanged (parent keeps its keyspace).

**Pre:** master alive, PEWorker available, region exists, parent `CLOSED`, no procedure attached, `parentProc = SPAWNED_CLOSE`.
**Post:** `ASSIGN` TRSP spawned, `parentProc` cleared, meta reverted.

> *Source:* `rollbackState()` `CHECK_CLOSED_REGIONS` case L386–388; `openParentRegion()` L647–651.

```tla
SplitFail(r) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ RegionExists(r)
```

Parent must be `CLOSED` — the child `UNASSIGN` completed.

```tla
  /\ regionState[r].state = "CLOSED"
```

No procedure attached — child `UNASSIGN` cleared it.

```tla
  /\ regionState[r].procType = "NONE"
```

Split is pending at the close phase (pre-PONR).

```tla
  /\ parentProc[r].type = "SPLIT"
  /\ parentProc[r].step = "SPAWNED_CLOSE"
```

Create fresh `ASSIGN` TRSP to reopen the parent.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType = "ASSIGN",
       ![r].procStep = "GET_ASSIGN_CANDIDATE",
       ![r].targetServer = NoServer,
       ![r].retries = 0]
```

Persist the new `ASSIGN` procedure to `procStore`.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

Clear parent procedure — split is terminated.

```tla
  /\ parentProc' = [parentProc EXCEPT ![r] = NoParentProc]
```

Revert meta from `SPLITTING` to `CLOSED` (parent's actual state).

```tla
  /\ metaTable' =
       [metaTable EXCEPT ![r].state = "CLOSED", ![r].location = NoServer ]
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

```tla
============================================================================
```
