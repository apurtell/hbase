# ProcStore — Procedure Store Invariants

**Source:** [`ProcStore.tla`](../ProcStore.tla)

Procedure store invariants and the `RestoreSucceedState` recovery operator. Defines `ProcStoreConsistency` (intra-record correlation) and `ProcStoreBijection` (in-memory ↔ persisted record bijection).

---

```tla
------------------------------ MODULE ProcStore --------------------------------
```

The procedure store (`WALProcedureStore`/`RegionProcedureStore`) persists active procedures to durable storage. It survives master crash; `MasterRecover` reads it back to reconstruct in-memory procedure state.

This module provides:
- **`ProcStoreConsistency`** — intra-record correlation invariant
- **`ProcStoreBijection`** — `procType` ↔ `procStore` presence (masterAlive-gated)
- **`RestoreSucceedState`** — recovery operator for `REPORT_SUCCEED` procedures

### Implementation Architecture

The implementation uses two procedure store variants:

- **`WALProcedureStore`** ([WALProcedureStore.java](file:///Users/andrewpurtell/src/hbase/hbase-procedure/src/main/java/org/apache/hadoop/hbase/procedure2/store/wal/WALProcedureStore.java)) — the default store. Writes procedure state as WAL entries (protobuf-serialized `Procedure` records) to HDFS. Uses a slot-based file layout with periodic compaction to garbage-collect completed procedures. Each slot holds a full serialized `Procedure` object, including subclass-specific state.

- **`RegionProcedureStore`** ([RegionProcedureStore.java](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/procedure2/store/region/RegionProcedureStore.java)) — an alternative (branch-3+) that stores procedure state in a special region backed by a local HRegion. Provides faster recovery and better compaction semantics.

**Design note:** The specification's `procStore` is `[Regions → ProcStoreRecord ∪ {NoProcedure}]` — a simple function from region identifiers to 4-field records. This deliberately abstracts away:
- **Procedure IDs.** The implementation assigns a global monotonically increasing `procId` to each procedure. The model uses the region identifier as the key, relying on the invariant that at most one assignment-type procedure is active per region at a time. The `ProcStoreBijection` invariant validates this mapping.
- **Parent-child linkage.** The implementation's `Procedure.parentProcId` links child procedures (e.g., `OpenRegionProcedure`) to parent TRSPs. The model collapses parent-child into a single `regionState[r].procType`/`procStep` pair, with `parentProc[r]` handling the split/merge parent level.
- **Slot management / compaction.** WAL slot reuse, file rolling, and cleanup compaction are operational concerns that do not affect correctness of the assignment protocol.
- **Serialization format.** The model operates on semantic records, not byte-level serialization.
> This abstraction is valid because the model's safety invariants (`ProcStoreConsistency`, `ProcStoreBijection`) are exactly the properties that the real store must maintain for correct recovery. If the model satisfies them, any implementation that faithfully persists/restores these 4 fields will also be correct.

```tla
EXTENDS Types
```

All shared variables are declared as `VARIABLE` parameters so that the root module can substitute its own variables via `INSTANCE`.

```tla
VARIABLE regionState, masterAlive, procStore
```

---

## Invariants

### `ProcStoreConsistency`

Intra-record correlation invariant for persisted procedure records. Validates structural properties of `procStore` entries regardless of `masterAlive` — `procStore` survives master crash.

**Checks:**
1. `transitionCode` is recorded iff step is `REPORT_SUCCEED`
2. `targetServer` presence correlates with step
3. `UNASSIGN` type step and transition-code constraints (both close-path and open-path for two-phase recovery)
4. `transitionCode` must match procedure type

```tla
ProcStoreConsistency ==
  \A r \in Regions:
    procStore[r] # NoProcedure =>
```

`transitionCode` is recorded iff step is `REPORT_SUCCEED`. This is the intermediate step after the RS report has been consumed and in-memory state updated, but before the final state has been persisted to `metaTable`.

```tla
      /\ ( procStore[r].step = "REPORT_SUCCEED" =>
             procStore[r].transitionCode \in
               { "OPENED", "FAILED_OPEN", "CLOSED" }
         )
      /\ ( procStore[r].step # "REPORT_SUCCEED" =>
             procStore[r].transitionCode = NoTransition
         )
```

`targetServer` presence correlates with step. `GET_ASSIGN_CANDIDATE` has not yet selected a server.

```tla
      /\ ( procStore[r].step = "GET_ASSIGN_CANDIDATE" =>
             procStore[r].targetServer = NoServer
         )
```

`OPEN`, `CONFIRM_OPENED`, `CONFIRM_CLOSED`, and `REPORT_SUCCEED` all have a target server selected.

```tla
      /\ ( procStore[r].step \in
               { "OPEN", "CONFIRM_OPENED", "CONFIRM_CLOSED", "REPORT_SUCCEED" } =>
             procStore[r].targetServer # NoServer
         )
```

`UNASSIGN` normally starts at `CLOSE`, but during two-phase crash recovery (`confirmClosed` `ABNORMALLY_CLOSED`), `UNASSIGN` traverses the open-path steps (`GET_ASSIGN_CANDIDATE` → `OPEN` → `CONFIRM_OPENED` → `REPORT_SUCCEED`) before returning to `CLOSE`.

```tla
      /\ ( procStore[r].type = "UNASSIGN" =>
             procStore[r].step \in
               { "CLOSE", "CONFIRM_CLOSED", "REPORT_SUCCEED",
                 "GET_ASSIGN_CANDIDATE", "OPEN", "CONFIRM_OPENED" }
         )
```

`transitionCode` must match procedure type — `UNASSIGN` can report `CLOSED` (normal close path or re-close after recovery), `OPENED` (reopen succeeded during two-phase recovery), or `FAILED_OPEN` (reopen failed).

```tla
      /\ ( procStore[r].type = "UNASSIGN" /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode \in
               { "CLOSED", "OPENED", "FAILED_OPEN" }
         )
```

Pure `ASSIGN` procedures only reach the open path: `transitionCode` can be `OPENED` or `FAILED_OPEN`.

```tla
      /\ ( procStore[r].type = "ASSIGN" /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode \in { "OPENED", "FAILED_OPEN" }
         )
```

`MOVE` and `REOPEN` have both close and open phases: `transitionCode` can be `CLOSED` (close phase), `OPENED`, or `FAILED_OPEN` (open phase).

```tla
      /\ ( procStore[r].type \in { "MOVE", "REOPEN" } /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode \in
               { "OPENED", "FAILED_OPEN", "CLOSED" }
         )
```

### `ProcStoreBijection`

Bijection between in-memory procedures and persisted records. Only meaningful when `masterAlive = TRUE`; when master is down, in-memory state (`regionState`) does not exist — the active master does not exist — so both directions are vacuously true.

```tla
ProcStoreBijection ==
  masterAlive = TRUE =>
    \A r \in Regions:
      /\ ( regionState[r].procType # "NONE" ) => ( procStore[r] # NoProcedure )
      /\ ( procStore[r] # NoProcedure ) => ( regionState[r].procType # "NONE" )
```

---

## Recovery Operators

### `RestoreSucceedState(r)`

Compute the recovered in-memory state for a procedure that was at `REPORT_SUCCEED` when the master crashed. This models the implementation's `restoreSucceedState()` callback invoked during `ProcedureExecutor` recovery.

**Recovery walk-through:** When the master restarts, `ProcedureExecutor.start()` calls `WALProcedureStore.load()` to deserialize all persisted procedures. For each procedure in `REPORT_SUCCEED` state, the executor invokes the procedure's `restoreSucceedState()` method (a virtual callback) to let the procedure reconstruct the in-memory `RegionStateNode` state from the persisted `transitionCode`.

The implementation path for open-type procedures is:
1. `ProcedureExecutor.start()` → `restoreProcedures()` → for each loaded procedure in `REPORT_SUCCEED` state
2. → `procedure.restoreSucceedState(env)` (virtual call)
3. → [`OpenRegionProcedure.restoreSucceedState()`](file:///Users/andrewpurtell/src/hbase/hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/OpenRegionProcedure.java) L128–136
4. → `AM.regionOpenedWithoutPersistingToMeta(regionNode)` (sets `OPEN` state + location)

For close-type procedures: `CloseRegionProcedure.restoreSucceedState()` → `AM.regionClosedWithoutPersistingToMeta(regionNode)` → sets `CLOSED` state, clears location.

**Branches on `UseRestoreSucceedQuirk`:**
- **`TRUE`** — faithfully reproduces `OpenRegionProcedure.restoreSucceedState()` L128–136 bug: procedures with `OPENED` or `FAILED_OPEN` transition codes unconditionally replay as `OPENED` (ignoring `FAILED_OPEN`).
- **`FALSE`** — correct behavior: checks `transitionCode` and branches:
  - `OPENED` → region marked `OPEN` at `targetServer`
  - `FAILED_OPEN` → region stays in `FAILED_OPEN` state
  - `CLOSED` → region marked `CLOSED` with no location

Returns a record `[state, location]` for updating `regionState[r]`.

The `CLOSED` branch fires for `UNASSIGN` and for the close phase of `MOVE`/`REOPEN`. No quirk applies to the `CLOSED` path.

```tla
RestoreSucceedState(r) ==
  LET rec == procStore[r]
  IN IF rec.transitionCode = "CLOSED"
      THEN \* Close-path result — always CLOSED, no location.
```

Applies to `UNASSIGN` directly and to `MOVE`/`REOPEN` close phase.

```tla
        [ state |-> "CLOSED", location |-> NoServer ]
      ELSE \* Open-path result (OPENED or FAILED_OPEN).
        IF UseRestoreSucceedQuirk
        THEN \* Bug-faithful: unconditionally replay as OPENED,
```

ignoring `transitionCode`. Even a `FAILED_OPEN` report gets replayed as `OPEN`.

> *Source:* `OpenRegionProcedure.restoreSucceedState()` L128–136 always calls `regionOpenedWith...()`.

```tla
          [ state |-> "OPEN", location |-> rec.targetServer ]
        ELSE \* Correct behavior: check transitionCode.
          IF rec.transitionCode = "OPENED"
          THEN [ state |-> "OPEN", location |-> rec.targetServer ]
          ELSE \* FAILED_OPEN — region failed to open on the RS.
```

Keep it in `FAILED_OPEN` state so retry can occur. No location — the region is not online anywhere.

```tla
            [ state |-> "FAILED_OPEN", location |-> NoServer ]
```
