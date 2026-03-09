# Split

**Source:** [`Split.tla`](../Split.tla)

Split procedure actions using the parent-child framework.

---

```tla
------------------------------- MODULE Split ---------------------------------
```

Models the forward path of `SplitTableRegionProcedure`, which splits a region into two daughters. The procedure uses the **parent-child framework**: it spawns child TRSPs via `addChildProcedure()` and yields while children execute.

**Actions:**
- **`SplitPrepare`** — set `SPLITTING`, create `parentProc`
- **`SplitSpawnClose`** — spawn child `UNASSIGN` TRSP (`addChildProcedure`)
- **`SplitResumeAfterClose`** — resume after child TRSP completes
- **`SplitUpdateMeta`** — PONR: meta write, materialize daughters, spawn child `ASSIGN` TRSPs
- **`SplitDone`** — daughters `OPEN`, cleanup

The `parentProc[r]` variable tracks the parent procedure's state. It persists across child TRSP lifecycles and survives master crash. Child TRSPs use the normal TRSP machinery (`procType`/`procStep`).

*Rollback and failure paths are deferred to a future iteration.*

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
         parentProc
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
RegionExists(r) == regionKeyRange[r] # NoRange
```

Does region `r` have an active parent procedure?

```tla
HasActiveParent(r) == parentProc[r].type # "NONE"
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

Master must be alive.

```tla
  /\ masterAlive = TRUE
```

PEWorker thread available.

```tla
  /\ availableWorkers > 0
```

Region must exist.

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
  /\ regionKeyRange[r].endKey - regionKeyRange[r].startKey >= 2
```

At least 2 unused identifiers available for daughters.

```tla
  /\ Cardinality({d \in Regions: regionKeyRange[d] = NoRange}) >= 2
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
       ![r] =
       [ state |-> "SPLITTING", location |-> metaTable[r].location ]]
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
       [parentProc EXCEPT ![r] = [ type |-> "SPLIT", step |-> "SPAWNED_CLOSE" ]]
```

Everything else unchanged.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        regionKeyRange,
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
  /\ parentProc[r] = [ type |-> "SPLIT", step |-> "SPAWNED_CLOSE" ]
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
        regionKeyRange,
        zkNode
     >>
```

### `SplitUpdateMeta(r, dA, dB)`

**Point of No Return:** update meta, materialize daughters, spawn child `ASSIGN` TRSPs on daughters.

Non-deterministically picks two unused region identifiers for daughters. Computes `mid = (startKey + endKey) / 2` from the parent keyspace. Atomically:
- **Parent:** `CLOSED` → `SPLIT` (in-memory and meta), location cleared.
- **Daughter A:** keyspace = `[startKey, mid)`, state = `SPLITTING_NEW` (in-memory), `CLOSED` (meta). `procType = ASSIGN` (child spawned).
- **Daughter B:** keyspace = `[mid, endKey)`, state = `SPLITTING_NEW` (in-memory), `CLOSED` (meta). `procType = ASSIGN` (child spawned).

**Pre:** master alive, PEWorker available, meta available, parent has `procType = SPLIT`, `parentProc = [SPLIT, PONR]`, state = `CLOSED`. `dA` and `dB` are distinct unused identifiers.
**Post:** parent in `SPLIT` state, daughters materialized with child `ASSIGN` TRSPs. `parentProc` step = `SPAWNED_OPEN`.

> *Source:* `AssignmentManager.markRegionAsSplit()` L2364–2390; `RegionStateStore.splitRegion()` L392–395; `OPEN_CHILD_REGIONS`: `addChildProcedure(createAssignProcedures)`.

```tla
SplitUpdateMeta(r, dA, dB) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ MetaIsAvailable
  /\ RegionExists(r)
  /\ regionState[r].procType = "SPLIT"
  /\ parentProc[r] = [ type |-> "SPLIT", step |-> "PONR" ]
  /\ regionState[r].state = "CLOSED"
```

`dA` and `dB` are distinct unused identifiers.

```tla
  /\ dA # dB
  /\ dA # r
  /\ dB # r
  /\ regionKeyRange[dA] = NoRange
  /\ regionKeyRange[dB] = NoRange
```

Compute the midpoint of the parent's keyspace.

```tla
  /\ LET startK == regionKeyRange[r].startKey
         endK == regionKeyRange[r].endKey
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

Update meta: parent → `SPLIT`, daughters → `CLOSED` (Appendix C.8).

```tla
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "SPLIT", location |-> NoServer ],
             ![dA] =
             [ state |-> "CLOSED", location |-> NoServer ],
             ![dB] =
             [ state |-> "CLOSED", location |-> NoServer ]]
```

Materialize daughter keyspaces.

```tla
        /\ regionKeyRange' =
             [regionKeyRange EXCEPT
             ![dA] =
             [ startKey |-> startK, endKey |-> mid ],
             ![dB] =
             [ startKey |-> mid, endKey |-> endK ]]
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

```tla
  /\ parentProc' = [parentProc EXCEPT ![r].step = "SPAWNED_OPEN"]
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        zkNode
     >>
```

### `SplitDone(r)`

Complete the split after both daughters are `OPEN`.

Clears the parent's keyspace to `NoRange` (region *"deleted"* — models the post-compaction cleanup) and clears the parent procedure state.

**Pre:** master alive, PEWorker available, `parentProc = [SPLIT, SPAWNED_OPEN]`, both daughters are `OPEN` with `procType = NONE` (ASSIGN completed).
**Post:** parent keyspace cleared (`NoRange`), `procType = NONE`, `parentProc = NoParentProc`. Parent stays in `SPLIT` state (terminal).

> *Source:* `POST_OPERATION` state (procedure completes).

```tla
SplitDone(r) ==
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
  /\ RegionExists(r)
  /\ regionState[r].procType = "SPLIT"
  /\ parentProc[r] = [ type |-> "SPLIT", step |-> "SPAWNED_OPEN" ]
```

Identify daughters: `OPEN` regions whose keyspaces were carved from this parent. Under `SplitMergeConstraint ≤ 1`, these are the only regions with keyspaces that partition the parent's range.

```tla
  /\ LET startK == regionKeyRange[r].startKey
         endK == regionKeyRange[r].endKey
         mid == ( startK + endK ) \div 2
         daughters ==
           {d \in Regions:
             /\ regionState[d].state = "OPEN"
             /\ regionState[d].procType = "NONE"
             /\ regionKeyRange[d] # NoRange
             /\ \/ regionKeyRange[d] = [ startKey |-> startK, endKey |-> mid ]
                \/ regionKeyRange[d] = [ startKey |-> mid, endKey |-> endK ]
           }
     IN \* Both daughters must be OPEN and unattached.
        /\ Cardinality(daughters) = 2
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

Clear parent keyspace — region *"deleted."*

```tla
        /\ regionKeyRange' = [regionKeyRange EXCEPT ![r] = NoRange]
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
        metaTable,
        zkNode
     >>
```

```tla
============================================================================
```
