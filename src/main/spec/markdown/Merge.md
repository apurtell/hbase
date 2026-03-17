# Merge

**Source:** [`Merge.tla`](../Merge.tla)

Merge procedure actions using the parent-child framework.

---

```tla
------------------------------- MODULE Merge ---------------------------------
```

Models `MergeTableRegionsProcedure`: forward path (merge two adjacent regions into one) and pre-PONR rollback (abort and reopen targets). The procedure uses the **parent-child framework**: it spawns child TRSPs via `addChildProcedure()` and yields while children execute.

**Forward-path actions:**
- **`MergePrepare`** — set `MERGING`, create `parentProc` on both targets, spawn child `UNASSIGN` TRSPs
- **`MergeCheckClosed`** — resume after child TRSPs complete
- **`MergeUpdateMeta`** — PONR: meta write, materialize merged region, spawn child `ASSIGN` TRSP
- **`MergeDone`** — merged region `OPEN`, cleanup

**Rollback action:**
- **`MergeFail`** — pre-PONR failure: create fresh `ASSIGN`s to reopen targets, clear `parentProc`

The `parentProc[r]` variable tracks the parent procedure's state on BOTH target regions. It persists across child TRSP lifecycles and survives master crash. The `ref1` field cross-references the other target (peer); `ref2` references the merged region identifier.

### Dual-Region Coordination

Unlike split (which operates on a single parent), merge coordinates TWO input regions atomically. The implementation's [`MergeTableRegionsProcedure`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/MergeTableRegionsProcedure.java) acquires locks on both regions in `acquireLock()` and performs the merge under those locks. The model captures this by setting `parentProc` on BOTH targets at `MergePrepare`, with cross-references: `parentProc[r1].ref1 = r2` and `parentProc[r2].ref1 = r1`. This ensures the `NoTableExclusiveLock` guard on other procedures (split, create, delete) blocks on either target.

The `ref2` field on both targets stores the merged region identifier `m`, which is selected non-deterministically from unused identifiers. This models the implementation's `RegionInfoBuilder.newRegionInfo()` call that creates a new `RegionInfo` for the merged output.

### Implementation State Simplification

The implementation's `MergeTableRegionsState` enum has 10 values, collapsed to 5 model steps. Omitted states are filesystem (move HFile store files) and coprocessor operations, identical to the Split collapse rationale.

### GC Procedure Omission 

The implementation creates [`GCMergedRegionsProcedure`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/GCMergedRegionsProcedure.java) / [`GCMultipleMergedRegionsProcedure`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/GCMultipleMergedRegionsProcedure.java) after merge completion to clean up compaction references and delete the merged-from regions' HFile directories. These GC procedures are post-merge cleanup and do not modify `RegionState`, `ServerStateNode`, or any assignment-related variable. The model's `MergeDone` action clears the target keyspaces to `NoRange` (modeling the GC outcome) without the intermediate GC procedure.

`MergeFail` fires non-deterministically at the same precondition as `MergeCheckClosed` (both targets `CLOSED`, children complete). TLC explores both the success path (`MergeCheckClosed`) and the failure path (`MergeFail`) for every reachable merge state.

> *Source:* `MergeTableRegionsProcedure.executeFromState()` L189–255; `MergeTableRegionsProcedure.rollbackState()` L266–310.

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

Are two regions adjacent (`r1`'s `endKey` = `r2`'s `startKey`)?

```tla
Adjacent(r1, r2) == /\ RegionExists(r1)
                    /\ RegionExists(r2)
                    /\ metaTable[r1].keyRange.endKey = metaTable[r2].keyRange.startKey
```

Is meta available? (no server carrying meta is crashed)

```tla
MetaIsAvailable ==
  \A s \in Servers: carryingMeta[s] = FALSE \/ serverState[s] = "ONLINE"
```

```tla
---------------------------------------------------------------------------
```

## Merge Actions

### `MergePrepare(r1, r2, m)`

Initiate a merge on two adjacent `OPEN` regions. Atomically sets both targets to `MERGING` in-memory and meta, creates `parentProc` on both (with cross-references), and spawns child `UNASSIGN` TRSPs on both targets.

In the implementation, `prepareMergeRegion()` and `addChildProcedure(createUnassignProcedures())` execute within the same `executeFromState()` call under region locks — effectively atomic.

**Pre:** master alive, PEWorker available, `UseMerge = TRUE`, r1 and r2 are `OPEN` with locations, no procedures attached, no parent procedures in progress, `Adjacent(r1, r2)`, m is an unused identifier (`metaTable[m].keyRange = NoRange`).
**Post:** both targets state = `MERGING`, `parentProc = [MERGE, SPAWNED_CLOSE, ref1=peer, ref2=m]`. Child `UNASSIGN` TRSPs spawned on both.

> *Source:* `prepareMergeRegion()` sets `MERGING`; `CLOSE_REGIONS`: `addChildProcedure(createUnassignProcedures)`.

```tla
MergePrepare(r1, r2, m) ==
```

Merge feature must be enabled.

```tla
  /\ UseMerge = TRUE
```

Master must be alive to execute the procedure.

```tla
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
```

Both regions must exist (have assigned keyspaces).

```tla
  /\ RegionExists(r1)
  /\ RegionExists(r2)
```

Both must be `OPEN` with locations.

```tla
  /\ regionState[r1].state = "OPEN"
  /\ regionState[r1].location # NoServer
  /\ regionState[r2].state = "OPEN"
  /\ regionState[r2].location # NoServer
```

No procedures attached to either target.

```tla
  /\ regionState[r1].procType = "NONE"
  /\ regionState[r2].procType = "NONE"
```

No parent procedures in progress on either target.

```tla
  /\ ~HasActiveParent(r1)
  /\ ~HasActiveParent(r2)
```

Regions must be adjacent. All three identifiers must be distinct.

```tla
  /\ Adjacent(r1, r2)
  /\ r1 # r2
  /\ r1 # m
  /\ r2 # m
```

`m` must be an unused identifier with no parent procedure.

```tla
  /\ metaTable[m].keyRange = NoRange
  /\ ~HasActiveParent(m)
```

No table-level exclusive lock on this pair's table.

```tla
  /\ NoTableExclusiveLock(r1)
```

Transition both targets to `MERGING` and spawn child `UNASSIGN` TRSPs.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r1].state = "MERGING",
       ![r1].procType = "UNASSIGN",
       ![r1].procStep = "CLOSE",
       ![r1].targetServer = regionState[r1].location,
       ![r1].retries = 0,
       ![r2].state = "MERGING",
       ![r2].procType = "UNASSIGN",
       ![r2].procStep = "CLOSE",
       ![r2].targetServer = regionState[r2].location,
       ![r2].retries = 0]
```

Update meta to `MERGING` (preserving locations).

```tla
  /\ metaTable' =
       [metaTable EXCEPT
       ![r1].state = "MERGING",
       ![r2].state = "MERGING"]
```

Persist child `UNASSIGN` procedures to `procStore`.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r1] = NewProcRecord("UNASSIGN", "CLOSE", regionState[r1].location, NoTransition),
       ![r2] = NewProcRecord("UNASSIGN", "CLOSE", regionState[r2].location, NoTransition)]
```

Create parent procedure records on both targets with cross-references. `ref1` = peer (other target), `ref2` = merged region identifier.

```tla
  /\ parentProc' =
       [parentProc EXCEPT
       ![r1] = [ type |-> "MERGE", step |-> "SPAWNED_CLOSE",
                 ref1 |-> r2, ref2 |-> m ],
       ![r2] = [ type |-> "MERGE", step |-> "SPAWNED_CLOSE",
                 ref1 |-> r1, ref2 |-> m ]]
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

### `MergeCheckClosed(r1)`

Resume the merge after both child `UNASSIGN` TRSPs complete.

When both TRSP close paths finish, r1 and r2 are `CLOSED` with `procType = NONE`. This action detects `parentProc.step = SPAWNED_CLOSE` on r1 (the primary target) and re-attaches the `MERGE` procedure lock, then advances to `PONR`.

**Pre:** master alive, PEWorker available, r1 is `CLOSED` with no procedure, r2 (`= parentProc[r1].ref1`) is `CLOSED` with no procedure, both `parentProc = [MERGE, SPAWNED_CLOSE]`.
**Post:** r1 `procType = MERGE` (re-attached), `parentProc[r1].step = PONR`.

> *Source:* `checkClosedRegions()` L279–283.

```tla
MergeCheckClosed(r1) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ RegionExists(r1)
```

r1 must be `CLOSED` — the `UNASSIGN` child completed.

```tla
  /\ regionState[r1].state = "CLOSED"
```

No procedure attached — child `UNASSIGN` cleared it.

```tla
  /\ regionState[r1].procType = "NONE"
```

Merge is pending at the close phase on r1.

```tla
  /\ parentProc[r1].type = "MERGE"
  /\ parentProc[r1].step = "SPAWNED_CLOSE"
```

Read peer from `parentProc`. r2 must also be `CLOSED` with no procedure.

```tla
  /\ LET r2 == parentProc[r1].ref1
     IN /\ regionState[r2].state = "CLOSED"
        /\ regionState[r2].procType = "NONE"
        /\ parentProc[r2].type = "MERGE"
        /\ parentProc[r2].step = "SPAWNED_CLOSE"
```

Re-attach `MERGE` procedure to r1 for protection.

```tla
  /\ regionState' =
       [regionState EXCEPT ![r1].procType = "MERGE", ![r1].procStep = "IDLE"]
```

Persist `MERGE` procedure to `procStore`.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r1] = NewProcRecord("MERGE", "IDLE", NoServer, NoTransition)]
```

Advance r1 to `PONR`.

```tla
  /\ parentProc' = [parentProc EXCEPT ![r1].step = "PONR"]
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

### `MergeUpdateMeta(r1)`

**Point of No Return:** update meta, materialize merged region, spawn child `ASSIGN` TRSP on merged region.

Atomically:
- r1, r2: `CLOSED` → `MERGED` (in-memory and meta). Terminal state.
- m: keyspace = `[r1.startKey, r2.endKey)`, state = `MERGING_NEW` (in-memory), `CLOSED` (meta). `procType = ASSIGN` (child spawned).

**Pre:** master alive, PEWorker available, meta available, r1 has `procType = MERGE`, `parentProc[r1].type = "MERGE"` and `.step = "PONR"`, r1 is `CLOSED`.
**Post:** targets in `MERGED` state, merged region materialized with child `ASSIGN` TRSP. `parentProc[r1].step = SPAWNED_OPEN`.

> *Source:* `AssignmentManager.markRegionAsMerged()`; `RegionStateStore.mergeRegions()`; `OPEN_MERGED`: `addChildProcedure(createAssignProcedures)`.

```tla
MergeUpdateMeta(r1) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ MetaIsAvailable
  /\ RegionExists(r1)
  /\ regionState[r1].procType = "MERGE"
  /\ parentProc[r1].type = "MERGE"
  /\ parentProc[r1].step = "PONR"
  /\ regionState[r1].state = "CLOSED"
```

Read peer and merged region from `parentProc`.

```tla
  /\ LET r2 == parentProc[r1].ref1
         m == parentProc[r1].ref2
         startK == metaTable[r1].keyRange.startKey
         endK == metaTable[r2].keyRange.endKey
     IN /\ regionState[r2].state = "CLOSED"
        /\ metaTable[m].keyRange = NoRange
```

Update `regionState`: targets → `MERGED`, merged → `MERGING_NEW` with child `ASSIGN` TRSP spawned.

```tla
        /\ regionState' =
             [regionState EXCEPT
             ![r1].state = "MERGED",
             ![r1].location = NoServer,
             ![r1].procType = "NONE",
             ![r1].procStep = "IDLE",
             ![r1].targetServer = NoServer,
             ![r1].retries = 0,
             ![r2].state = "MERGED",
             ![r2].location = NoServer,
             ![m] =
             [ state |-> "MERGING_NEW",
               location |-> NoServer,
               procType |-> "ASSIGN",
               procStep |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> NoServer,
               retries |-> 0
             ]]
```

Update meta: targets → `MERGED`, merged → `CLOSED` with keyRange and table identity. All `metaTable` fields are set via field-level EXCEPT.

```tla
        /\ metaTable' =
             [metaTable EXCEPT
             ![r1].state = "MERGED",
             ![r1].location = NoServer,
             ![r2].state = "MERGED",
             ![r2].location = NoServer,
             ![m].state = "CLOSED",
             ![m].location = NoServer,
             ![m].keyRange = [ startKey |-> startK, endKey |-> endK ],
             ![m].table = metaTable[r1].table ]
```

Persist merged region `ASSIGN` procedure to `procStore`. Clear r1's `procStore` (`MERGE` lock was removed above).

```tla
        /\ procStore' =
             [procStore EXCEPT
             ![r1] = NoProcedure,
             ![m] = NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

Parent advances to `SPAWNED_OPEN` (yielding to merged `ASSIGN`).

```tla
  /\ parentProc' = [parentProc EXCEPT ![r1].step = "SPAWNED_OPEN"]
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

### `MergeDone(r1)`

Complete the merge after the merged region is `OPEN`.

Clears the targets' keyspaces and table identity to free them (regions *"deleted"* — models the post-compaction cleanup) and clears all parent procedure state.

**Pre:** master alive, PEWorker available, `parentProc[r1].type = "MERGE"` and `.step = "SPAWNED_OPEN"`, merged region m is `OPEN` with `procType = NONE` (ASSIGN completed).
**Post:** target keyspaces and table identity cleared (`NoRange`, `NoTable`), `parentProc` cleared on both. Targets stay in `MERGED` state (terminal).

> *Source:* `POST_OPERATION` state (procedure completes).

```tla
MergeDone(r1) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ RegionExists(r1)
  /\ parentProc[r1].type = "MERGE"
  /\ parentProc[r1].step = "SPAWNED_OPEN"
```

Read peer and merged region from `parentProc`.

```tla
  /\ LET r2 == parentProc[r1].ref1
         m == parentProc[r1].ref2
     IN /\ regionState[m].state = "OPEN"
        /\ regionState[m].procType = "NONE"
```

Clear target keyspaces and table identity via `metaTable` — regions *"deleted."*

```tla
         /\ metaTable' =
              [metaTable EXCEPT
              ![r1].keyRange = NoRange,
              ![r1].table = NoTable,
              ![r2].keyRange = NoRange,
              ![r2].table = NoTable ]
```

No `procStore` change needed (r1 already cleared at `MergeUpdateMeta`).

```tla
  /\ UNCHANGED << procStore, tableEnabled >>
```

Clear parent procedure on both targets.

```tla
  /\ parentProc' =
       [parentProc EXCEPT
       ![r1] = NoParentProc,
       ![parentProc[r1].ref1] = NoParentProc]
```

`regionState` unchanged (targets already `MERGED`, m already `OPEN`).

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        regionState,
        tableEnabled,
        zkNode
     >>
```

### `MergeFail(r1)`

Pre-PONR rollback: abort the merge and create fresh `ASSIGN` TRSPs to reopen both target regions.

Fires non-deterministically at the same precondition as `MergeCheckClosed` (both targets `CLOSED`, child `UNASSIGN`s complete, `parentProc = [MERGE, SPAWNED_CLOSE]`). The non-deterministic choice between `MergeCheckClosed` and `MergeFail` models the success/failure decision: TLC explores both branches.

**The rollback:**
1. Creates fresh `ASSIGN` TRSPs on both targets (`GET_ASSIGN_CANDIDATE`) to reopen them.
2. Clears `parentProc` on both targets to `NoParentProc` (merge is terminated).
3. Reverts meta from `MERGING` to `CLOSED` (targets' actual state; the `ASSIGN` TRSPs will update meta to `OPENING` → `OPEN` later).
4. Persists the new `ASSIGN` procedures to `procStore`.

No merged region was created pre-PONR (`MergeAtomicity` invariant), so no merged region cleanup is needed. `metaTable` keyRange/table unchanged (targets keep their keyspaces, m stays `NoRange`).

**Pre:** master alive, PEWorker available, r1 and r2 exist, both `CLOSED`, no procedure attached, `parentProc = SPAWNED_CLOSE`.
**Post:** `ASSIGN` TRSPs spawned on both, `parentProc` cleared, meta reverted.

> *Source:* `rollbackState()` L266–310; `openParentRegion()` analog for merge targets.

```tla
MergeFail(r1) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ RegionExists(r1)
```

r1 must be `CLOSED` — the child `UNASSIGN` completed.

```tla
  /\ regionState[r1].state = "CLOSED"
```

No procedure attached — child `UNASSIGN` cleared it.

```tla
  /\ regionState[r1].procType = "NONE"
```

Merge is pending at the close phase (pre-PONR).

```tla
  /\ parentProc[r1].type = "MERGE"
  /\ parentProc[r1].step = "SPAWNED_CLOSE"
```

Read peer from `parentProc`. r2 must also be `CLOSED` with no procedure.

```tla
  /\ LET r2 == parentProc[r1].ref1
     IN /\ regionState[r2].state = "CLOSED"
        /\ regionState[r2].procType = "NONE"
        /\ parentProc[r2].type = "MERGE"
        /\ parentProc[r2].step = "SPAWNED_CLOSE"
```

Create fresh `ASSIGN` TRSPs to reopen both targets.

```tla
        /\ regionState' =
             [regionState EXCEPT
             ![r1].procType = "ASSIGN",
             ![r1].procStep = "GET_ASSIGN_CANDIDATE",
             ![r1].targetServer = NoServer,
             ![r1].retries = 0,
             ![r2].procType = "ASSIGN",
             ![r2].procStep = "GET_ASSIGN_CANDIDATE",
             ![r2].targetServer = NoServer,
             ![r2].retries = 0]
```

Persist the new `ASSIGN` procedures to `procStore`.

```tla
        /\ procStore' =
             [procStore EXCEPT
             ![r1] = NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition),
             ![r2] = NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

Revert meta from `MERGING` to `CLOSED` (targets' actual state).

```tla
        /\ metaTable' =
             [metaTable EXCEPT
             ![r1].state = "CLOSED", ![r1].location = NoServer,
             ![r2].state = "CLOSED", ![r2].location = NoServer ]
```

Clear parent procedure on both targets — merge is terminated.

```tla
  /\ parentProc' =
       [parentProc EXCEPT
       ![r1] = NoParentProc,
       ![parentProc[r1].ref1] = NoParentProc]
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
