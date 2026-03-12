# Types

**Source:** [`Types.tla`](../Types.tla)

Pure-definition module: constants, type sets, and state definitions for the HBase AssignmentManager specification.

---

```tla
---------------------- MODULE Types ---------------------------
```

```tla
EXTENDS Naturals, FiniteSets, TLC
```

## Constants

The specification is parameterized over a finite set of **region identifiers** and a finite set of **RegionServer identifiers**. Both must be non-empty.

```tla
CONSTANTS Regions,    \* The finite set of region identifiers
         Servers
```

`Servers` is the finite set of RegionServer identifiers.

```tla
ASSUME Regions # {}
ASSUME Servers # {}
```

### Deployed Regions

`DeployedRegions` are the table regions that exist at system start. They tile the full keyspace `[0, MaxKey)` in `Init`. Regions outside `DeployedRegions` are unused identifiers available for split/merge to materialize as new regions.

```tla
CONSTANTS DeployedRegions
ASSUME DeployedRegions \subseteq Regions
ASSUME DeployedRegions # {}
```

### Keyspace Bound

`MaxKey` defines the keyspace as `0..(MaxKey-1)`.

```tla
CONSTANTS MaxKey
ASSUME MaxKey \in Nat /\ MaxKey > 0
```

### Sentinel Values

`NoRange` — sentinel model value for unused region identifiers whose keyspace has not been assigned (region does not exist).

```tla
CONSTANTS NoRange
```

`NoServer` — sentinel model value for *"no server assigned."*

```tla
CONSTANTS NoServer
ASSUME NoServer \notin Servers
```

`NoTransition` — sentinel model value for *"no transition code recorded."* Used in `ProcStoreRecord.transitionCode` for all procedure steps except `REPORT_SUCCEED`.

```tla
CONSTANTS NoTransition
```

`NoProcedure` — sentinel model value for *"no persisted procedure."*

```tla
CONSTANTS NoProcedure
```

### Behavioral Constants

`MaxRetries` — maximum open-retry attempts before giving up (`FAILED_OPEN`).

```tla
CONSTANTS MaxRetries
ASSUME MaxRetries \in Nat /\ MaxRetries >= 0
```

`UseReopen` — when `TRUE`, `TRSPCreateReopen` is enabled, modeling the branch-2.6 `REOPEN` transition type (close then reopen on the same server). Master (branch-3+) does not have `REOPEN`, only `MOVE`. Setting `FALSE` disables `REOPEN`, reducing the state space.

```tla
CONSTANTS UseReopen
ASSUME UseReopen \in BOOLEAN
```

`NoRegion` — sentinel model value for "no region reference" in `parentProc` records. Used when a region-reference field is not applicable (e.g., split pre-PONR before daughters are chosen).

```tla
CONSTANTS NoRegion
ASSUME NoRegion \notin Regions
```

`UseMerge` — when `TRUE`, merge actions are enabled in `Next` and `Fairness`. With both split and merge active, the state space becomes unbounded (split → daughters → merge → parent → split → …). Setting `FALSE` keeps exhaustive model checking tractable (split-only). Setting `TRUE` enables merge in simulation mode.

```tla
CONSTANTS UseMerge
ASSUME UseMerge \in BOOLEAN
```

`UseRSOpenDuplicateQuirk` — when `TRUE`, the `RSOpenDuplicate` action is enabled, modeling `AssignRegionHandler.process()` L107–115 where the RS silently drops `OPEN` requests for already-online regions without reporting back. This can cause TRSP deadlock (stuck at `CONFIRM_OPENED`). Default `FALSE` to avoid deadlock in model checking; set `TRUE` to surface the implementation quirk and generate traces.

```tla
CONSTANTS UseRSOpenDuplicateQuirk
ASSUME UseRSOpenDuplicateQuirk \in BOOLEAN
```

`UseRSCloseNotFoundQuirk` — when `TRUE`, the `RSCloseNotFound` action is enabled, modeling `UnassignRegionHandler.process()` L111–117 where the RS silently drops `CLOSE` requests for regions that are not online without reporting back. This can cause TRSP deadlock (stuck at `CONFIRM_CLOSED`). Default `FALSE` to avoid deadlock in model checking; set `TRUE` to surface the implementation quirk and generate traces.

```tla
CONSTANTS UseRSCloseNotFoundQuirk
ASSUME UseRSCloseNotFoundQuirk \in BOOLEAN
```

`UseRestoreSucceedQuirk` — when `TRUE`, `RestoreSucceedState` faithfully reproduces the `OpenRegionProcedure.restoreSucceedState()` L128–136 bug where `OPEN`-type procedures always replay as `OPENED` regardless of the persisted `transitionCode` (even `FAILED_OPEN`). Default `FALSE` so that recovery correctly checks `transitionCode` and branches; set `TRUE` to demonstrate the violation and generate counterexample traces.

```tla
CONSTANTS UseRestoreSucceedQuirk
ASSUME UseRestoreSucceedQuirk \in BOOLEAN
```

### ProcedureExecutor Worker Pool

`MaxWorkers` — `ProcedureExecutor` worker thread pool size. All procedure-step actions require an available worker to execute. Non-blocking actions acquire and release within the same atomic step (net-zero effect). Meta-writing actions when meta is unavailable may hold a worker (`UseBlockOnMetaWrite=TRUE`) or suspend and release (`UseBlockOnMetaWrite=FALSE`).

> *Source:* `ProcedureExecutor.workerThreadCount`; `hbase.procedure.threads` (conf, default = `# CPUs / 4`).

```tla
CONSTANTS MaxWorkers
ASSUME MaxWorkers \in Nat /\ MaxWorkers > 0
```

`UseBlockOnMetaWrite` — when `FALSE` (default, master/branch-3+), `RegionStateStore.updateRegionLocation()` returns `CompletableFuture<Void>` via `AsyncTable.put()` and the calling procedure suspends via `ProcedureFutureUtil.suspendIfNecessary()`, releasing the PEWorker thread. When `TRUE` (branch-2.6), `RegionStateStore` uses synchronous `Table.put()`, blocking the PEWorker thread until the RPC completes.

> *Source (master/branch-3+):* `RegionStateStore.updateRegionLocation()` via `AsyncTable.put()`; `ProcedureFutureUtil.suspendIfNecessary()`.
> *Source (branch-2.6):* `RegionStateStore.updateRegionLocation()` L158–240 uses synchronous `Table.put()` (L237–239).

```tla
CONSTANTS UseBlockOnMetaWrite
ASSUME UseBlockOnMetaWrite \in BOOLEAN
```

```tla
---------------------------------------------------------------------------
```

## State Definitions

### Region Lifecycle States

The `State` set covers the full `RegionState.State` enum:

| Modeled               | Impl enum value           |
|-----------------------|---------------------------|
| `"OFFLINE"`           | `OFFLINE` (=0)            |
| `"OPENING"`           | `OPENING` (=1)            |
| `"OPEN"`              | `OPEN` (=2)               |
| `"CLOSING"`           | `CLOSING` (=3)            |
| `"CLOSED"`            | `CLOSED` (=4)             |
| `"SPLITTING"`         | `SPLITTING` (=5)          |
| `"SPLIT"`             | `SPLIT` (=6)              |
| `"SPLITTING_NEW"`     | `SPLITTING_NEW` (=7)      |
| `"FAILED_OPEN"`       | `FAILED_OPEN` (=8)        |
| `"MERGING"`           | `MERGING` (=9)            |
| `"ABNORMALLY_CLOSED"` | `ABNORMALLY_CLOSED` (=10) |
| `"MERGED"`            | `MERGED` (=11)            |
| `"MERGING_NEW"`       | `MERGING_NEW` (=12)       |

```tla
State ==
  { "OFFLINE",
    "OPENING",
    "OPEN",
    "CLOSING",
    "CLOSED",
    "SPLITTING",
    "SPLIT",
    "SPLITTING_NEW",
    "FAILED_OPEN",
    "MERGING",
    "ABNORMALLY_CLOSED",
    "MERGED",
    "MERGING_NEW"
  }
```

### Valid Transitions

The set of valid *(from, to)* state transitions:

```tla
ValidTransition ==
  { \* --- Core assign/unassign/move ---
    << "OFFLINE", "OPENING" >>,
    << "OPENING", "OPEN" >>,
    << "OPENING", "FAILED_OPEN" >>,
    << "OPEN", "CLOSING" >>,
    << "CLOSING", "CLOSED" >>,
    << "CLOSED", "OPENING" >>,
    << "CLOSED", "OFFLINE" >>,
    << "OPEN", "ABNORMALLY_CLOSED" >>,
    << "OPENING", "ABNORMALLY_CLOSED" >>,
    << "CLOSING", "ABNORMALLY_CLOSED" >>,
    << "ABNORMALLY_CLOSED", "OPENING" >>,
    << "FAILED_OPEN", "OPENING" >>,
```

Split-specific transitions:

```tla
    << "OPEN", "SPLITTING" >>,
    << "SPLITTING", "CLOSING" >>,
    << "SPLITTING", "OPEN" >>,
    << "CLOSED", "SPLIT" >>,
    << "SPLITTING_NEW", "OPENING" >>,
```

Merge-specific transitions:

```tla
    << "OPEN", "MERGING" >>,
    << "MERGING", "CLOSING" >>,
    << "MERGING", "OPEN" >>,
    << "CLOSED", "MERGED" >>,
    << "MERGING_NEW", "OPENING" >>
  }
```

### TRSP Internal States

TRSP internal states used in the `procStep` field of `regionState`. These match the `RegionStateTransitionState` enum (`MasterProcedure.proto`). See `TRSP.tla` header for the full traceability table.

| Path     | Sequence                                                                    |
|----------|-----------------------------------------------------------------------------|
| ASSIGN   | `GET_ASSIGN_CANDIDATE` → `OPEN` → `CONFIRM_OPENED` → *(cleared)*          |
| UNASSIGN | `CLOSE` → `CONFIRM_CLOSED` → *(cleared)*                                   |
| MOVE     | `CLOSE` → `CONFIRM_CLOSED` → `GET_ASSIGN_CANDIDATE` → `OPEN` → `CONFIRM_OPENED` → *(cleared)* |
| REPORT_SUCCEED | intermediate state                                                    |

```tla
TRSPState ==
  { "GET_ASSIGN_CANDIDATE",
    "OPEN",
    "CONFIRM_OPENED",
    "CLOSE",
    "CONFIRM_CLOSED",
    "REPORT_SUCCEED"
  }
```

### Parent Procedure Step States

Parent procedure step states track the progress of a parent procedure (`SplitTableRegionProcedure`, future merge) that owns one or more child TRSPs via `addChildProcedure()`.

The parent *yields* by spawning child TRSPs (`SPAWNED_CLOSE`, `SPAWNED_OPEN`) and *resumes* when all children complete. `parentProc[r]` persists across the child TRSP lifecycle.

These map to the `SplitTableRegionState` enum (`MasterProcedure.proto`), collapsed from 11 states to 5 (filesystem + coprocessor ops abstracted). They generalize to `MergeTableRegionsState` later.

| Step            | Implementation mapping                                                |
|-----------------|-----------------------------------------------------------------------|
| `PREPARE`       | `PREPARE` → `PRE_OP` (set `SPLITTING`/`MERGING`)                     |
| `SPAWNED_CLOSE` | `CLOSE_PARENT`: `addChildProcedure(unassign)`                        |
| `PONR`          | `CHECK_CLOSED` → `CREATE_DAUGHTERS` → `WRITE_SEQ` → `PRE_BEFORE_META` → `UPDATE_META` (PONR) |
| `SPAWNED_OPEN`  | `OPEN_CHILD_REGIONS`: `addChildProcedure(assign)`                    |
| `COMPLETING`    | `POST_OPERATION`                                                      |

```tla
ParentProcStep ==
  { "SPAWNED_CLOSE", \* CLOSE_PARENT: addChildProcedure(unassign)
    "PONR", \* CHECK_CLOSED -> CREATE_DAUGHTERS -> WRITE_SEQ
```

→ `PRE_BEFORE_META` → `UPDATE_META` (PONR)

```tla
    "SPAWNED_OPEN", \* OPEN_CHILD_REGIONS: addChildProcedure(assign)
    "COMPLETING" \* POST_OPERATION
  }
```

### Parent Procedure Types

Parent procedure types. `"NONE"` means no parent procedure is attached.

```tla
ParentProcType == { "SPLIT", "MERGE" }
```

`NoParentProc` — sentinel: no parent procedure attached.

```tla
NoParentProc == [ type |-> "NONE", step |-> "NONE",
                  ref1 |-> NoRegion, ref2 |-> NoRegion ]
```

Type definition for `parentProc` records (used in `TypeOK`). The `ref1` and `ref2` fields hold region-reference values: for split, `ref1`/`ref2` are the daughter identifiers (set at PONR); for merge, `ref1` is the peer target and `ref2` is the merged region identifier.

```tla
ParentProcRecord ==
  [type:ParentProcType \cup { "NONE" },
    step:ParentProcStep \cup { "NONE" },
    ref1:Regions \cup { NoRegion },
    ref2:Regions \cup { NoRegion }
  ]
```

### Procedure and Command Types

`ProcType` — procedure types. `"NONE"` means no procedure is attached. `REOPEN` closes on the current server then reopens preferring the same server (`assignCandidate` pinning); no other `ONLINE` server required.

> *Maps to:* `RegionTransitionType` enum (`MasterProcedure.proto`).

```tla
ProcType == { "ASSIGN", "UNASSIGN", "MOVE", "REOPEN", "SPLIT", "MERGE", "NONE" }
```

`CommandType` — RPC command types dispatched from master to RegionServer.

> *Maps to:* `RegionRemoteProcedureBase` subclasses.

```tla
CommandType == { "OPEN", "CLOSE" }
```

`ReportCode` — transition codes reported from RegionServer back to master.

> *Maps to:* `RegionServerStatusService.RegionStateTransition.TransitionCode`.

```tla
ReportCode == { "OPENED", "FAILED_OPEN", "CLOSED" }
```

### Procedure Store Record

Type definition for persisted procedure records. `transitionCode` records the RS report outcome when `step = REPORT_SUCCEED`; set to `NoTransition` for all other steps.

```tla
ProcStoreRecord ==
  [type:ProcType \ { "NONE" },
    step:TRSPState \cup { "IDLE" },
    targetServer:Servers \cup { NoServer },
    transitionCode:ReportCode \cup { NoTransition }
  ]
```

```tla
---------------------------------------------------------------------------
```

## Helpers

`NewProcRecord` — constructor for `procStore` records. All sites that write to `procStore` must call this instead of constructing an inline record literal, ensuring the 4-field shape is maintained in one place.

```tla
NewProcRecord(type, step, server, tc) ==
  [ type |-> type,
    step |-> step,
    targetServer |-> server,
    transitionCode |-> tc
  ]
```

```tla
============================================================================
```
