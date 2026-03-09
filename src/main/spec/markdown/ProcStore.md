# ProcStore — Procedure Store Invariants

**Source:** [`ProcStore.tla`](../ProcStore.tla)

Procedure store invariants and the RestoreSucceedState recovery operator. Defines ProcStoreConsistency (intra-record correlation) and ProcStoreBijection (in-memory ↔ persisted record bijection).

---

```tla
------------------------------ MODULE ProcStore --------------------------------
```

Procedure store helpers, invariants, and recovery operators for the
HBase AssignmentManager specification.

The procedure store (WALProcedureStore/RegionProcedureStore) persists
active procedures to durable storage.  It survives master crash;
MasterRecover reads it back to reconstruct in-memory procedure state.

This module provides:
ProcStoreConsistency — intra-record correlation invariant
ProcStoreBijection — procType ↔ procStore presence (masterAlive-gated)
RestoreSucceedState — recovery operator for REPORT_SUCCEED procedures

```tla
EXTENDS Types
```

All shared variables are declared as VARIABLE parameters so that
the root module can substitute its own variables via INSTANCE.

```tla
VARIABLE regionState, masterAlive, procStore
```

---

Invariants

Intra-record correlation invariant for persisted procedure records.
Validates structural properties of procStore entries regardless of
masterAlive — procStore survives master crash.

Checks:
1. transitionCode is recorded iff step is REPORT_SUCCEED
2. targetServer presence correlates with step
3. UNASSIGN type never reaches open-path steps
4. transitionCode must match procedure type

```tla
ProcStoreConsistency ==
  \A r \in Regions:
    procStore[r] # NoProcedure =>
```

transitionCode is recorded iff step is REPORT_SUCCEED.
REPORT_SUCCEED is the intermediate step after the RS report
has been consumed and in-memory state updated, but before the
final state has been persisted to metaTable.

```tla
      /\ ( procStore[r].step = "REPORT_SUCCEED" =>
             procStore[r].transitionCode \in
               { "OPENED", "FAILED_OPEN", "CLOSED" }
         )
      /\ ( procStore[r].step # "REPORT_SUCCEED" =>
             procStore[r].transitionCode = NoTransition
         )
```

targetServer presence correlates with step.
GET_ASSIGN_CANDIDATE has not yet selected a server.

```tla
      /\ ( procStore[r].step = "GET_ASSIGN_CANDIDATE" =>
             procStore[r].targetServer = NoServer
         )
```

OPEN, CONFIRM_OPENED, CONFIRM_CLOSED, and REPORT_SUCCEED
all have a target server selected.

```tla
      /\ ( procStore[r].step \in
               { "OPEN", "CONFIRM_OPENED", "CONFIRM_CLOSED", "REPORT_SUCCEED" } =>
             procStore[r].targetServer # NoServer
         )
```

UNASSIGN starts at CLOSE and never reaches open-path steps.

```tla
      /\ ( procStore[r].type = "UNASSIGN" =>
             procStore[r].step \in
               { "CLOSE", "CONFIRM_CLOSED", "REPORT_SUCCEED" }
         )
```

transitionCode must match procedure type:
UNASSIGN can only report CLOSED.

```tla
      /\ ( procStore[r].type = "UNASSIGN" /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode = "CLOSED"
         )
```

Pure ASSIGN procedures only reach the open path:
transitionCode can be OPENED or FAILED_OPEN.

```tla
      /\ ( procStore[r].type = "ASSIGN" /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode \in { "OPENED", "FAILED_OPEN" }
         )
```

MOVE and REOPEN have both close and open phases:
transitionCode can be CLOSED (close phase), OPENED, or
FAILED_OPEN (open phase).

```tla
      /\ ( procStore[r].type \in { "MOVE", "REOPEN" } /\
               procStore[r].transitionCode # NoTransition =>
             procStore[r].transitionCode \in
               { "OPENED", "FAILED_OPEN", "CLOSED" }
         )
```

Bijection between in-memory procedures and persisted records.
Only meaningful when masterAlive = TRUE; when master is down,
in-memory state (regionState) does not exist — the active master
does not exist — so both directions are vacuously true.

```tla
ProcStoreBijection ==
  masterAlive = TRUE =>
    \A r \in Regions:
      /\ ( regionState[r].procType # "NONE" ) => ( procStore[r] # NoProcedure )
      /\ ( procStore[r] # NoProcedure ) => ( regionState[r].procType # "NONE" )
```

---

Recovery operators

Compute the recovered in-memory state for a procedure that was at
REPORT_SUCCEED when the master crashed.  This models the
implementation's restoreSucceedState() callback invoked during
ProcedureExecutor recovery.

Branches on UseRestoreSucceedQuirk:
TRUE  — faithfully reproduces OpenRegionProcedure.restoreSucceedState()
L128-136 bug: procedures with OPENED or FAILED_OPEN transition
codes unconditionally replay as OPENED (ignoring FAILED_OPEN).
FALSE — correct behavior: checks transitionCode and branches.
OPENED      → region marked OPEN at targetServer.
FAILED_OPEN → region stays in FAILED_OPEN state.
CLOSED      → region marked CLOSED with no location.

Returns a record [state, location] for updating regionState[r].

The CLOSED branch fires for UNASSIGN and for the close phase of
MOVE/REOPEN.  No quirk applies to the CLOSED path.

```tla
RestoreSucceedState(r) ==
  LET rec == procStore[r]
  IN IF rec.transitionCode = "CLOSED"
      THEN \* Close-path result — always CLOSED, no location.
```

Applies to UNASSIGN directly and to MOVE/REOPEN close phase.

```tla
        [ state |-> "CLOSED", location |-> NoServer ]
      ELSE \* Open-path result (OPENED or FAILED_OPEN).
        IF UseRestoreSucceedQuirk
        THEN \* Bug-faithful: unconditionally replay as OPENED,
```

ignoring transitionCode.  Even a FAILED_OPEN
report gets replayed as OPEN.
*Source:* OpenRegionProcedure.restoreSucceedState()
L128-136 always calls regionOpenedWith...()

```tla
          [ state |-> "OPEN", location |-> rec.targetServer ]
        ELSE \* Correct behavior: check transitionCode.
          IF rec.transitionCode = "OPENED"
          THEN [ state |-> "OPEN", location |-> rec.targetServer ]
          ELSE \* FAILED_OPEN — region failed to open on the RS.
```

Keep it in FAILED_OPEN state so retry can occur.
No location — the region is not online anywhere.

```tla
            [ state |-> "FAILED_OPEN", location |-> NoServer ]
```
