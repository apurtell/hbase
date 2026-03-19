# Types

**Source:** [`Types.tla`](../Types.tla)

Pure-definition module: constants, type sets, and state definitions for the HBase AssignmentManager specification.

### Implementation Mapping

Every type set and constant in this module maps to a specific implementation artifact. The design philosophy is **1:1 enum correspondence** where possible: each string literal in a TLA+ type set corresponds to exactly one enum value in the Java source or protobuf definition. This enables direct traceability between model-checked properties and implementation code paths.

Sentinel constants (`NoServer`, `NoRange`, `NoTable`, `NoProcedure`, `NoTransition`, `NoRegion`) are declared as TLC **model values** — distinct atoms that are guaranteed unequal to any domain element. Model values are critical for TLC's symmetry reduction: they cannot participate in arithmetic or string comparison, preventing TLC from confusing sentinel absence with a valid domain element.

> *Key implementation classes:* [`RegionState.java`](file:///Users/andrewpurtell/src/hbase/hbase-client/src/main/java/org/apache/hadoop/hbase/master/RegionState.java) (State enum), [`TableState.java`](file:///Users/andrewpurtell/src/hbase/hbase-client/src/main/java/org/apache/hadoop/hbase/client/TableState.java) (table lifecycle), [`MasterProcedure.proto`](file:///Users/andrewpurtell/src/hbase/hbase-protocol-shaded/src/main/protobuf/server/master/MasterProcedure.proto) (TRSP state, procedure type, split/merge state enums), [`RegionStateNode.java`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/RegionStateNode.java) (in-memory procedure attachment).

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

> *Source:* `hbase.assignment.maximum.attempts` (default 10). In the specification, this is typically set to a small value (0 or 1) for exhaustive model checking to keep the state space tractable. In simulation mode, it can be set higher to exercise more retry paths.

```tla
CONSTANTS MaxRetries
ASSUME MaxRetries \in Nat /\ MaxRetries >= 0
```

`UseReopen` — when `TRUE`, `TRSPCreateReopen` is enabled, modeling the branch-2.6 `REOPEN` transition type (close then reopen on the same server). Master (branch-3+) does not have `REOPEN`, only `MOVE`. Setting `FALSE` disables `REOPEN`, reducing the state space.

```tla
CONSTANTS UseReopen
ASSUME UseReopen \in BOOLEAN
```

```tla
CONSTANTS NoRegion
ASSUME NoRegion \notin Regions
```

`Tables` — the finite set of table identifiers. Each deployed region belongs to exactly one table. At system start, all `DeployedRegions` map to the single element of `Tables`.

```tla
CONSTANTS Tables
ASSUME Tables # {}
```

`NoTable` — sentinel model value for "no table assigned." Used for unused region identifiers whose table has not been set.

```tla
CONSTANTS NoTable
ASSUME NoTable \notin Tables
```

`UseMerge` — when `TRUE`, merge actions are enabled in `Next` and `Fairness`. With both split and merge active, the state space becomes unbounded (split → daughters → merge → parent → split → …). Setting `FALSE` keeps exhaustive model checking tractable (split-only). Setting `TRUE` enables merge in simulation mode.

```tla
CONSTANTS UseMerge
ASSUME UseMerge \in BOOLEAN
```

`UseCreate` — when `TRUE`, CreateTable actions are enabled in `Next` and `Fairness`. Setting `FALSE` disables CreateTable in exhaustive mode (where `Tables = {T1}` already makes it vacuous). Setting `TRUE` enables CreateTable in simulation mode (with `Tables = {T1, T2}`).

```tla
CONSTANTS UseCreate
ASSUME UseCreate \in BOOLEAN
```

`UseDelete` — when `TRUE`, DeleteTable actions are enabled in `Next` and `Fairness`. Setting `FALSE` disables DeleteTable in exhaustive mode. Setting `TRUE` enables DeleteTable in simulation mode.

```tla
CONSTANTS UseDelete
ASSUME UseDelete \in BOOLEAN
```

`UseTruncate` — when `TRUE`, TruncateTable actions are enabled in `Next` and `Fairness`. Setting `FALSE` disables TruncateTable in exhaustive mode. Setting `TRUE` enables TruncateTable in simulation mode.

```tla
CONSTANTS UseTruncate
ASSUME UseTruncate \in BOOLEAN
```

`UseDisable` — when `TRUE`, `DisableTable` and `EnableTable` actions are enabled in `Next` and `Fairness`. A single toggle controls both procedures — enabling one without the other is an obvious deadlock (once disabled, regions could never be re-enabled). Setting `FALSE` disables both in exhaustive mode. Setting `TRUE` enables both in simulation mode.

```tla
CONSTANTS UseDisable
ASSUME UseDisable \in BOOLEAN
```

`UseModify` — when `TRUE`, `ModifyTable` actions are enabled in `Next` and `Fairness`. Setting `FALSE` disables ModifyTable in exhaustive mode. Setting `TRUE` enables it in simulation mode (with `UseDisable = TRUE` to exercise the disable → modify → enable structural change path).

```tla
CONSTANTS UseModify
ASSUME UseModify \in BOOLEAN
```

`UseRSOpenDuplicateQuirk` — when `TRUE`, the `RSOpenDuplicate` action is enabled, modeling [`AssignRegionHandler.process()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/handler/AssignRegionHandler.java) L107–115 where the RS silently drops `OPEN` requests for already-online regions without reporting back. This can cause TRSP deadlock (stuck at `CONFIRM_OPENED`). Default `FALSE` to avoid deadlock in model checking; set `TRUE` to surface the implementation quirk and generate traces.

The RS checks `getRegion(encodedName) != null` and returns early without calling `reportRegionStateTransition()`. The master-side `OpenRegionProcedure` suspends indefinitely at `REPORT_SUCCEED` waiting for a report that will never arrive. This is a genuine implementation bug that can cause region unavailability; the `UseRSOpenDuplicateQuirk` toggle lets TLC generate counterexample traces demonstrating the deadlock.

```tla
CONSTANTS UseRSOpenDuplicateQuirk
ASSUME UseRSOpenDuplicateQuirk \in BOOLEAN
```

`UseRSCloseNotFoundQuirk` — when `TRUE`, the `RSCloseNotFound` action is enabled, modeling [`UnassignRegionHandler.process()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/regionserver/handler/UnassignRegionHandler.java) L111–117 where the RS silently drops `CLOSE` requests for regions that are not online without reporting back. This can cause TRSP deadlock (stuck at `CONFIRM_CLOSED`). Default `FALSE` to avoid deadlock in model checking; set `TRUE` to surface the implementation quirk and generate traces.

The RS checks `rs.getRegion(encodedName) == null` and returns early. Also covers L94–109 where `regionsInTransitionInRS` has a conflicting entry (already closing/opening). In both cases, no `CLOSED` report is sent, causing the master-side `CloseRegionProcedure` to suspend indefinitely.

```tla
CONSTANTS UseRSCloseNotFoundQuirk
ASSUME UseRSCloseNotFoundQuirk \in BOOLEAN
```

`UseRestoreSucceedQuirk` — when `TRUE`, `RestoreSucceedState` faithfully reproduces the [`OpenRegionProcedure.restoreSucceedState()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/OpenRegionProcedure.java) L128–136 bug where `OPEN`-type procedures always replay as `OPENED` regardless of the persisted `transitionCode` (even `FAILED_OPEN`). Default `FALSE` so that recovery correctly checks `transitionCode` and branches; set `TRUE` to demonstrate the violation and generate counterexample traces.

`OpenRegionProcedure.restoreSucceedState()` unconditionally calls `env.getAssignmentManager().regionOpenedWithoutPersistingToMeta(regionNode)` — it never inspects `transitionCode` to distinguish `OPENED` from `FAILED_OPEN`. After a master crash at `REPORT_SUCCEED` with `transitionCode=FAILED_OPEN`, recovery marks the failed region as `OPEN`, potentially causing data-serving from a region with incomplete initialization.

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

`UseUnknownServerQuirk` — when `TRUE`, `DetectUnknownServer` silently closes the orphaned region without creating a TRSP(ASSIGN), modeling the `checkOnlineRegionsReport()` gap (AM.java L1496–1546) where regions on Unknown Servers are closed but never reassigned. Default `FALSE`: master creates a TRSP(ASSIGN) for the orphan.

Most common production path to Unknown Server:
1. RS crashes → goes to DEAD → SCP scheduled.
2. SCP runs, processes most regions. Some skipped when `isMatchingRegionLocation()` finds location moved by concurrent TRSP.
3. SCP completes (DONE). Skipped regions still reference crashed server.
4. New RS starts on same host:port → `DeadServer.cleanPreviousInstance()` removes old dead entry → server becomes UNKNOWN (neither ONLINE nor DEAD).
5. `checkOnlineRegionsReport()` / CatalogJanitor detects orphans.
6. `closeRegionSilently()` closes without TRSP — region stuck CLOSED/OFFLINE forever.

> *Source:* `AM.checkOnlineRegionsReport()` L1496–1546, `AM.closeRegionSilently()` L1482–1490, `DeadServer.cleanPreviousInstance()` L98–106, `CatalogJanitorReport` L50–54 (TODO: auto-fix), `HBCKServerCrashProcedure` L40–185 (manual fix).

```tla
CONSTANTS UseUnknownServerQuirk
ASSUME UseUnknownServerQuirk \in BOOLEAN
```

`UseMasterAbortOnMetaWriteQuirk` — when `TRUE`, models HBASE-23595 where `RegionStateStore.updateRegionLocation()` catches `IOException` and calls `master.abort(msg, e)`, crashing the entire master when meta is temporarily unavailable (e.g., during SCP for the meta RS). When `FALSE` (default), the procedure suspends or blocks per `UseBlockOnMetaWrite`.

> *Source:* `RegionStateStore.updateRegionLocation()` L231–250, private `updateRegionLocation(RegionInfo, State, Put)` catch block calls `master.abort()` on `IOException`.

```tla
CONSTANTS UseMasterAbortOnMetaWriteQuirk
ASSUME UseMasterAbortOnMetaWriteQuirk \in BOOLEAN
```

`UseStaleStateQuirk` — when `TRUE`, `MasterRecover` marks a dead server (`zkNode=FALSE`) as `ONLINE` if any region in `metaTable` still references it as its location. Models `RegionStateStore.visitMeta()` / `AM.start()` L341-348 where `regionStates.createServer(regionLocation)` creates a `ServerStateNode` for the region's location regardless of ZK liveness, making the dead server appear `ONLINE` to subsequent crash-detection. Default `FALSE` for correct behavior.

> *Source:* `AM.start()` L341-348, `regionStates.createServer(regionLocation)`.

```tla
CONSTANTS UseStaleStateQuirk
ASSUME UseStaleStateQuirk \in BOOLEAN
```

```tla
---------------------------------------------------------------------------
```

## State Definitions

### Region Lifecycle States

The `State` set covers the full `RegionState.State` enum (declared in [`RegionState.java`](file:///Users/andrewpurtell/src/hbase/hbase-client/src/main/java/org/apache/hadoop/hbase/master/RegionState.java) L38–59):

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

**Omitted:** `FAILED_CLOSE` (=9 in implementation) is declared in the Java enum but has **no reachable code path** that transitions any region into this state. When a close RPC fails, the RS either retries internally or crashes — in both cases, the master observes `ABNORMALLY_CLOSED` (via `ServerCrashProcedure`), never `FAILED_CLOSE`. Including it would add an unreachable state to the model and expand the state space without covering any real behavior. The implementation's `RegionState.isFailedClose()` method exists but is never called in the assignment path.

The specification enumerates only *reachable* states — not the full declared set. The Java `RegionState.State` enum contains `FAILED_CLOSE` for historical/forward-compatibility reasons, but no TRSP, SCP, or RS handler ever calls `setState(FAILED_CLOSE)`. Omitting it from the TLA+ model is both correct (no behavior is lost) and beneficial (smaller state space for exhaustive model checking).

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

The set of valid *(from, to)* state transitions. Unlike the implementation (which does not declare a formal transition table — transitions are enforced implicitly by action guards in each procedure), the specification makes the valid transition set *explicit*. This set was derived by analyzing all code paths through `TransitRegionStateProcedure`, `SplitTableRegionProcedure`, `MergeTableRegionsProcedure`, `ServerCrashProcedure`, and the RS handlers.

The `TransitionValid` action constraint (in `AssignmentManager.md`) checks every state change against this set. If any action produces a transition not in `ValidTransition`, TLC reports a violation. This is a **specification-level** enforcement of state-machine discipline that the implementation achieves implicitly through the procedural flow of `executeFromState()` case statements.

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

Parent procedure step states track the progress of a parent procedure (`SplitTableRegionProcedure`, `MergeTableRegionsProcedure`) that owns one or more child TRSPs via `addChildProcedure()`.

The parent *yields* by spawning child TRSPs (`SPAWNED_CLOSE`, `SPAWNED_OPEN`) and *resumes* when all children complete. `parentProc[r]` persists across the child TRSP lifecycle.

These map to the `SplitTableRegionState` enum (`MasterProcedure.proto`), collapsed from 11 states to 5 (filesystem + coprocessor ops abstracted). They generalize to `MergeTableRegionsState` identically.

| Step            | Implementation mapping                                                |
|-----------------|-----------------------------------------------------------------------|
| `PREPARE`       | `PREPARE` → `PRE_OP` (set `SPLITTING`/`MERGING`)                     |
| `SPAWNED_CLOSE` | `CLOSE_PARENT`: `addChildProcedure(unassign)`                        |
| `PONR`          | `CHECK_CLOSED` → `CREATE_DAUGHTERS` → `WRITE_SEQ` → `PRE_BEFORE_META` → `UPDATE_META` (PONR) |
| `SPAWNED_OPEN`  | `OPEN_CHILD_REGIONS`: `addChildProcedure(assign)`                    |
| `COMPLETING`    | `POST_OPERATION`                                                      |

The implementation's `SplitTableRegionState` enum includes `SPLIT_TABLE_REGION_PRE_OPERATION`, `SPLIT_TABLE_REGION_CREATE_DAUGHTER_REGIONS` (HFile reference creation), `SPLIT_TABLE_REGION_WRITE_MAX_SEQUENCE_ID_FILE` (WAL sequence ID persistence), `SPLIT_TABLE_REGION_PRE_OPERATION_BEFORE_META` (coprocessor hook), and `SPLIT_TABLE_REGION_UPDATE_META` (meta write for daughters). These filesystem and coprocessor operations are **orthogonal** to the assignment/state-tracking protocol: they do not modify `RegionState`, `ServerStateNode`, `dispatchedOps`, or any variable in the model. Collapsing them into `PONR` captures the essential property — the procedure crosses a point-of-no-return where daughter regions become visible — without modeling HFile manipulation or coprocessor hooks.

Similarly, `MergeTableRegionsState` has `MERGE_TABLE_REGIONS_PRE_MERGE_OPERATION`, `MERGE_TABLE_REGIONS_MOVE_REGION_STORE_FILES`, `MERGE_TABLE_REGIONS_PRE_MERGE_COMMIT`, `MERGE_TABLE_REGIONS_UPDATE_META` — all collapsed into `PONR` for the same reason.

> *Source:* `SplitTableRegionProcedure.executeFromState()` (11 case branches), `MergeTableRegionsProcedure.executeFromState()` (10 case branches).

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

Parent procedure types. `"NONE"` means no parent procedure is attached. `SPLIT`/`MERGE` are region-level parent procedures. `CREATE`/`DELETE`/`TRUNCATE`/`MODIFY` are table-level parent procedures.

```tla
ParentProcType == { "SPLIT", "MERGE", "CREATE", "DELETE", "TRUNCATE", "DISABLE", "ENABLE", "MODIFY" }
```

`TableExclusiveType` — the subset of parent procedure types that require exclusive access to all regions of a table. No `SPLIT` or `MERGE` can coexist with these on the same table.

> *Source:* `CreateTableProcedure`, `DeleteTableProcedure`, `TruncateTableProcedure`, `DisableTableProcedure`, `EnableTableProcedure`, `ModifyTableProcedure` each acquire a table-level exclusive lock via `TableLockManager`.

```tla
TableExclusiveType == { "CREATE", "DELETE", "TRUNCATE", "DISABLE", "ENABLE", "MODIFY" }
```

### Table State Set

`TableStateSet` — the set of valid table-level states, matching the Java [`TableState.State`](file:///Users/andrewpurtell/src/hbase/hbase-client/src/main/java/org/apache/hadoop/hbase/client/TableState.java) enum (L35–45). `DISABLING` and `ENABLING` are intermediate states set early in the disable/enable procedure flow and serve as concurrent client request rejection gates.

The implementation stores table state in `hbase:meta` via `TableStateManager.setTableState()`, persisting a protobuf `HBaseProtos.TableState` record. The TLA+ model abstracts this to the `tableEnabled` variable, which is declared as durable (survives master crash via `UNCHANGED` in `MasterCrash`). This faithfully models the implementation's storage of table state in meta.

> *Source:* `org.apache.hadoop.hbase.client.TableState.State`, [`TableStateManager.java`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/TableStateManager.java)

```tla
TableStateSet == { "ENABLED", "DISABLED", "DISABLING", "ENABLING" }
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

Each `ProcStoreRecord` abstracts the state of a procedure as persisted by `WALProcedureStore` (master) or `RegionProcedureStore` (branch-3+). The implementation serializes the full `Procedure` object (including subclass-specific state like `TransitRegionStateProcedure`'s `lastState`, `assignCandidate`, and `RegionRemoteProcedureBase`'s `state` + `transitionCode`) to a WAL entry. The model captures only the 4 assignment-critical fields: `type`, `step`, `targetServer`, and `transitionCode`. Serialization format, procedure IDs, parent-child linkage in the store, WAL slot reuse, and store compaction are all abstracted.

The `transitionCode` field is the key to **crash recovery fidelity**: when the master crashes between consuming an RS report (`TRSPReportSucceedOpen`/`TRSPReportSucceedClose` sets in-memory state) and persisting to meta, the `transitionCode` in `procStore` preserves the report outcome across the crash. `MasterRecover` reads `transitionCode` to reconstruct the in-memory state via `restoreSucceedState()`.

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
