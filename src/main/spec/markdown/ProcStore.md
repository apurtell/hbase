# ProcStore — Procedure Store

**Source:** [`ProcStore.tla`](../ProcStore.tla)

Procedure store helpers, invariants, and recovery operators for the HBase AssignmentManager specification.

The procedure store (`WALProcedureStore` / `RegionProcedureStore`) persists active procedures to durable storage. It survives master crash; `MasterRecover` reads it back to reconstruct in-memory procedure state.

This module provides:

- **`ProcStoreConsistency`** — intra-record correlation invariant
- **`ProcStoreBijection`** — `procType` ↔ `procStore` presence (masterAlive-gated)
- **`RestoreSucceedState`** — recovery operator for REPORT_SUCCEED procedures

---

## Module Declaration

```tla
------------------------------ MODULE ProcStore --------------------------------
EXTENDS Types
```

## Variables

```tla
VARIABLE regionState, masterAlive, procStore
```

---

## Invariants

### ProcStoreConsistency

Intra-record correlation invariant for persisted procedure records. Validates structural properties of `procStore` entries regardless of `masterAlive` — `procStore` survives master crash.

**Checks:**

1. `transitionCode` is recorded iff step is `REPORT_SUCCEED`
2. `targetServer` presence correlates with step
3. `UNASSIGN` type never reaches open-path steps
4. `transitionCode` must match procedure type

*Source: ProcedureExecutor lifecycle — insert on creation, update on each step, delete on completion.*

```tla
ProcStoreConsistency ==
  \A r \in Regions:
    procStore[r] # NoProcedure =>
      \* transitionCode is recorded iff step is REPORT_SUCCEED.
      /\ ( procStore[r].step = "REPORT_SUCCEED" =>
             procStore[r].transitionCode \in
               { "OPENED", "FAILED_OPEN", "CLOSED" }
         )
      /\ ( procStore[r].step # "REPORT_SUCCEED" =>
             procStore[r].transitionCode = NoTransition
         )
      \* targetServer presence correlates with step.
      /\ ( procStore[r].step = "GET_ASSIGN_CANDIDATE" =>
             procStore[r].targetServer = NoServer
         )
      /\ ( procStore[r].step \in
               { "OPEN", "CONFIRM_OPENED", "CONFIRM_CLOSED", "REPORT_SUCCEED" } =>
             procStore[r].targetServer # NoServer
         )
      \* UNASSIGN starts at CLOSE and never reaches open-path steps.
      /\ ( procStore[r].type = "UNASSIGN" =>
             procStore[r].step \in
               { "CLOSE", "CONFIRM_CLOSED", "REPORT_SUCCEED" }
         )
      \* transitionCode must match procedure type:
      \*   UNASSIGN can only report CLOSED.
      /\ ( procStore[r].type = "UNASSIGN" /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode = "CLOSED"
         )
      \*   Pure ASSIGN procedures only reach the open path.
      /\ ( procStore[r].type = "ASSIGN" /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode \in { "OPENED", "FAILED_OPEN" }
         )
      \*   MOVE and REOPEN have both close and open phases.
      /\ ( procStore[r].type \in { "MOVE", "REOPEN" } /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode \in
               { "OPENED", "FAILED_OPEN", "CLOSED" }
         )
```

---

### ProcStoreBijection

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

### RestoreSucceedState(r)

Compute the recovered in-memory state for a procedure that was at `REPORT_SUCCEED` when the master crashed. This models the implementation's `restoreSucceedState()` callback invoked during `ProcedureExecutor` recovery.

Returns a record `[state, location]` for updating `regionState[r]`.

**Branches on `UseRestoreSucceedQuirk`:**

| Value | Behavior |
|-------|----------|
| `TRUE` | Bug-faithful: reproduces `OpenRegionProcedure.restoreSucceedState()` bug — procedures with `OPENED` or `FAILED_OPEN` transition codes unconditionally replay as `OPENED` (ignoring `FAILED_OPEN`). |
| `FALSE` | Correct behavior: checks `transitionCode` and branches: `OPENED` → region marked OPEN at `targetServer`; `FAILED_OPEN` → region stays in FAILED_OPEN state; `CLOSED` → region marked CLOSED with no location. |

The `CLOSED` branch fires for `UNASSIGN` and for the close phase of `MOVE`/`REOPEN`. No quirk applies to the `CLOSED` path.

```tla
RestoreSucceedState(r) ==
  LET rec == procStore[r]
  IN IF rec.transitionCode = "CLOSED"
      THEN \* Close-path result — always CLOSED, no location.
        [ state |-> "CLOSED", location |-> NoServer ]
      ELSE \* Open-path result (OPENED or FAILED_OPEN).
        IF UseRestoreSucceedQuirk
        THEN \* Bug-faithful: unconditionally replay as OPENED,
          \* ignoring transitionCode.
          [ state |-> "OPEN", location |-> rec.targetServer ]
        ELSE \* Correct behavior: check transitionCode.
          IF rec.transitionCode = "OPENED"
          THEN [ state |-> "OPEN", location |-> rec.targetServer ]
          ELSE \* FAILED_OPEN — region failed to open on the RS.
            [ state |-> "FAILED_OPEN", location |-> NoServer ]
```
