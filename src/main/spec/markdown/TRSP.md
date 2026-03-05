# TRSP — TransitionRegionStateProcedure

**Source:** [`TRSP.tla`](../TRSP.tla)

TransitionRegionStateProcedure actions for the HBase AssignmentManager. Contains all TRSP actions: ASSIGN path (create, get-candidate, dispatch-open, confirm-opened), failure path (dispatch-fail, dispatch-fail-close), UNASSIGN path (create-unassign), MOVE path (create-move), REOPEN path (create-reopen), CLOSE path (dispatch-close, confirm-closed), and crash recovery (server-crashed).

## TRSP State Mapping

The `TRSPState` set maps 1:1 to the `RegionStateTransitionState` protobuf enum (`MasterProcedure.proto`):

| TRSPState | Protobuf Enum Value |
|-----------|---------------------|
| `"GET_ASSIGN_CANDIDATE"` | `REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE` (=1) |
| `"OPEN"` | `REGION_STATE_TRANSITION_OPEN` (=2) |
| `"CONFIRM_OPENED"` | `REGION_STATE_TRANSITION_CONFIRM_OPENED` (=3) |
| `"CLOSE"` | `REGION_STATE_TRANSITION_CLOSE` (=4) |
| `"CONFIRM_CLOSED"` | `REGION_STATE_TRANSITION_CONFIRM_CLOSED` (=5) |

## RegionRemoteProcedureBase Absorption

The implementation uses child procedures (`OpenRegionProcedure`, `CloseRegionProcedure`) that extend `RegionRemoteProcedureBase` and have their own 4-state machine (`RegionRemoteProcedureBaseState` in `MasterProcedure.proto`). The model merges these into TRSP actions:

| RRPB State | Absorbed by TLA+ Action(s) |
|------------|----------------------------|
| `DISPATCH` (=1) | `TRSPDispatchOpen`, `TRSPDispatchClose` — RPC dispatch is atomic within the TRSP step |
| `REPORT_SUCCEED` (=2) | `TRSPReportSucceedOpen`, `TRSPReportSucceedClose` — decomposed to model crash window between in-memory update and meta persist |
| `DISPATCH_FAIL` (=3) | `DispatchFail`, `DispatchFailClose` — RPC failure resets TRSP to retry |
| `SERVER_CRASH` (=4) | `TRSPServerCrashed` — server crash converts TRSP to ASSIGN |

---

## Module Declaration

```tla
------------------------------ MODULE TRSP ------------------------------------
EXTENDS Types
```

## Variables

```tla
VARIABLE regionState, metaTable, dispatchedOps, pendingReports,
         rsOnlineRegions, serverState, scpState, scpRegions,
         walFenced, locked, carryingMeta, serverRegions,
         procStore, masterAlive, zkNode
```

### Variable Group Shorthands

```tla
rpcVars    == << dispatchedOps, pendingReports >>
rsVars     == << rsOnlineRegions >>
scpVars    == << scpState, scpRegions, walFenced, carryingMeta, zkNode >>
masterVars == << masterAlive >>
procVars   == << procStore, locked >>
serverVars == << serverState, serverRegions >>
```

---

## ASSIGN Path

### TRSPCreate(r)

Create a TRSP ASSIGN procedure for a region eligible for assignment.

**Pre:** Region is in a state eligible for assignment (`OFFLINE`, `CLOSED`, `ABNORMALLY_CLOSED`, `FAILED_OPEN`) AND no procedure is currently attached.

**Post:** Procedure fields set to `ASSIGN`/`GET_ASSIGN_CANDIDATE`. Region lifecycle state is NOT changed yet — the TRSP will drive transitions in subsequent steps.

Don't auto-create ASSIGN for `ABNORMALLY_CLOSED` regions while any SCP is actively processing a crash. In the implementation, SCP owns crash recovery; no background daemon races to create procedures for crashed regions during the SCP assign loop.

*Source: `TRSP.assign()` creates the procedure; `TRSP.queueAssign()` applies the `GET_ASSIGN_CANDIDATE` initial state via `TRSP.setInitialAndLastState()`.*

```tla
TRSPCreate(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].state \in
       { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED", "FAILED_OPEN" }
  /\ regionState[r].procType = "NONE"
  /\ \/ regionState[r].state # "ABNORMALLY_CLOSED"
     \/ \A s \in Servers: scpState[s] \in { "NONE", "DONE" }
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType = "ASSIGN",
       ![r].procStep = "GET_ASSIGN_CANDIDATE",
       ![r].targetServer = NoServer,
       ![r].retries = 0]
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  /\ UNCHANGED << scpVars, rpcVars, serverVars, rsVars,
                   masterVars, metaTable, locked >>
```

---

### TRSPGetCandidate(r, s)

Choose a target server for the ASSIGN, MOVE, or REOPEN procedure.

**Pre:** TRSP is in `GET_ASSIGN_CANDIDATE` state, chosen server is ONLINE, and no CRASHED server still has `r` in its `rsOnlineRegions` (the zombie window must be closed: `RSAbort` clears the zombie RS's in-memory region set, after which a new assignment is safe).

**Post:** `targetServer` set, TRSP advances to `OPEN` state.

The zombie-window guard (`∀ zombie: CRASHED ⇒ r ∉ rsOnlineRegions`) is implementation-faithful: the implementation relies on `createDestinationServersList()` to exclude CRASHED servers from candidates and on `RSAbort` to close the zombie window.

*Source: `TRSP.queueAssign()` → `AM.queueAssign()` → LoadBalancer; `createDestinationServersList()` excludes CRASHED servers; `HRegionServer.abort()` (RSAbort) clears `rsOnlineRegions`.*

```tla
TRSPGetCandidate(r, s) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "GET_ASSIGN_CANDIDATE"
  /\ serverState[s] = "ONLINE"
  /\ \A sZombie \in Servers:
       serverState[sZombie] = "CRASHED" => r \notin rsOnlineRegions[sZombie]
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT ![r].targetServer = s, ![r].procStep = "OPEN"]
  /\ procStore' = [procStore EXCEPT ![r].step = "OPEN", ![r].targetServer = s]
  /\ UNCHANGED << scpVars, rpcVars, serverVars, rsVars,
                   masterVars, metaTable, locked >>
```

---

### TRSPDispatchOpen(r)

Dispatch the open command to the target server via RPC.

**Pre:** TRSP is in `OPEN` state with a target server.

**Post:** Region transitions to `OPENING` with the target server as location, meta updated, open command added to `dispatchedOps[targetServer]`, TRSP advances to `CONFIRM_OPENED`.

Impl states absorbed: `REGION_STATE_TRANSITION_OPEN` (=2) from the parent TRSP, plus `REGION_REMOTE_PROCEDURE_DISPATCH` (=1) from the child `OpenRegionProcedure`. The model treats open-region-state-transition + child-dispatch as a single atomic step.

`serverRegions` tracking: `AM.regionOpening()` adds `r` to the new target server's set but does NOT remove `r` from the old server's set — that removal happens only in `regionFailedOpen`, `regionClosedWithoutPersistingToMeta`, or `regionClosedAbnormally`. This means `r` may appear on two servers' `ServerStateNode` simultaneously (old and new) during the OPENING window.

*Source: `TRSP.openRegion()` calls `AM.regionOpening()` to set OPENING state and meta, then creates an `OpenRegionProcedure` child; `RSProcedureDispatcher` dispatches the RPC.*

```tla
TRSPDispatchOpen(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "OPEN"
  /\ regionState[r].targetServer # NoServer
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ regionState' =
             [regionState EXCEPT
             ![r].state = "OPENING",
             ![r].location = s,
             ![r].procStep = "CONFIRM_OPENED"]
        /\ metaTable' =
             [metaTable EXCEPT ![r] = [ state |-> "OPENING", location |-> s ]]
        /\ dispatchedOps' =
             [dispatchedOps EXCEPT
             ![s] = @ \cup { [ type |-> "OPEN", region |-> r ] }]
        /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \cup { r }]
        /\ procStore' = [procStore EXCEPT ![r].step = "CONFIRM_OPENED"]
        /\ UNCHANGED << scpVars, rsVars, masterVars,
                         pendingReports, serverState, locked >>
```

---

### TRSPReportSucceedOpen(r)

RS report consumed: in-memory `regionState` updated to reflect the RS report (`OPENED` or `FAILED_OPEN`), procedure persisted to `procStore` at `REPORT_SUCCEED` with the transition code recorded. `metaTable` is NOT updated yet — that happens in `TRSPPersistToMetaOpen`.

This action absorbs the former `TRSPConfirmOpened` (OPENED path), `TRSPHandleFailedOpen`, and `TRSPGiveUpOpen` by uniformly processing all report codes through the `REPORT_SUCCEED` intermediate step.

**Pre:** ASSIGN/MOVE/REOPEN procedure in `CONFIRM_OPENED` step, region is `OPENING`, and a matching report exists (`OPENED` or `FAILED_OPEN`). Prefers `OPENED` over `FAILED_OPEN` if both exist.

**Post:** In-memory state reflects report, `procStore` updated to `REPORT_SUCCEED` with `transitionCode`. `metaTable` unchanged.

For **OPENED**: `AM.regionOpenedWithoutPersistingToMeta()` transitions to OPEN.

For **FAILED_OPEN**: no state/location change — faithful to `regionFailedOpen(giveUp=false)` which only calls `removeRegionFromServer()` without calling `setState()` or `setRegionLocation(null)`. Region stays OPENING with location intact. Transition to FAILED_OPEN happens only in `TRSPPersistToMetaOpen` give-up branch.

*Source: `RegionRemoteProcedureBase.reportTransition()` → RRPB `REPORT_SUCCEED` state.*

```tla
TRSPReportSucceedOpen(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ regionState[r].state = "OPENING"
  /\ locked[r] = FALSE
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code \in { "OPENED", "FAILED_OPEN" }
       /\ rpt.code = "FAILED_OPEN" =>
            ~\E rpt2 \in pendingReports: rpt2.region = r /\ rpt2.code = "OPENED"
       /\ regionState' =
            [regionState EXCEPT
            ![r].state =
            IF rpt.code = "OPENED" THEN "OPEN" ELSE regionState[r].state,
            ![r].procStep = "REPORT_SUCCEED"]
       /\ pendingReports' = pendingReports \ { rpt }
       /\ procStore' =
            [procStore EXCEPT
            ![r].step = "REPORT_SUCCEED",
            ![r].transitionCode =
            IF rpt.code = "OPENED" THEN "OPENED" ELSE "FAILED_OPEN"]
       /\ LET loc == regionState[r].location
          IN serverRegions' =
                IF rpt.code = "FAILED_OPEN" /\ loc # NoServer
                THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                ELSE serverRegions
       /\ UNCHANGED << scpVars, rsVars, masterVars, metaTable,
                        dispatchedOps, serverState, locked >>
```

---

### TRSPPersistToMetaOpen(r)

Final state persisted to `metaTable`, procedure completed (or retried).

**Branches on `transitionCode` recorded in `procStore`:**

| Code | Behavior |
|------|----------|
| `OPENED` | Region marked OPEN in meta, procedure cleared |
| `FAILED_OPEN` (retry) | `retries < MaxRetries`: reset to `GET_ASSIGN_CANDIDATE`, stale OPEN commands cleared |
| `FAILED_OPEN` (give up) | `retries ≥ MaxRetries`: persist `FAILED_OPEN` to meta, clear procedure |

**Pre:** Procedure at `REPORT_SUCCEED` with `transitionCode` `OPENED` or `FAILED_OPEN`.

**Post:** `metaTable` updated, procedure completed or advanced.

*Source: RRPB persist-to-meta phase.*

```tla
TRSPPersistToMetaOpen(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "REPORT_SUCCEED"
  /\ locked[r] = FALSE
  /\ LET tc == procStore[r].transitionCode
     IN \/ \* --- OPENED branch ---
           /\ tc = "OPENED"
           /\ regionState[r].state = "OPEN"
           /\ regionState' =
                [regionState EXCEPT
                ![r] =
                [ state |-> "OPEN",
                  location |-> regionState[r].location,
                  procType |-> "NONE",
                  procStep |-> "IDLE",
                  targetServer |-> NoServer,
                  retries |-> 0
                ]]
           /\ metaTable' =
                [metaTable EXCEPT
                ![r] =
                [ state |-> "OPEN", location |-> metaTable[r].location ]]
           /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
           /\ UNCHANGED << scpVars, rpcVars, serverVars, rsVars,
                            masterVars, locked >>
        \/ \* --- FAILED_OPEN, retry branch ---
           /\ tc = "FAILED_OPEN"
           /\ regionState[r].retries < MaxRetries
           /\ regionState' =
                [regionState EXCEPT
                ![r].procStep = "GET_ASSIGN_CANDIDATE",
                ![r].targetServer = NoServer,
                ![r].retries = regionState[r].retries + 1]
           /\ procStore' =
                [procStore EXCEPT
                ![r] =
                NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
           /\ dispatchedOps' =
                [t \in Servers |->
                  {cmd \in dispatchedOps[t]:
                    cmd.region # r \/ cmd.type # "OPEN"
                  }
                ]
           /\ UNCHANGED << scpVars, serverVars, rsVars,
                            masterVars, metaTable, pendingReports, locked >>
        \/ \* --- FAILED_OPEN, give-up branch ---
           /\ tc = "FAILED_OPEN"
           /\ regionState[r].retries >= MaxRetries
           /\ regionState' =
                [regionState EXCEPT
                ![r] =
                [ state |-> "FAILED_OPEN",
                  location |-> NoServer,
                  procType |-> "NONE",
                  procStep |-> "IDLE",
                  targetServer |-> NoServer,
                  retries |-> 0
                ]]
           /\ metaTable' =
                [metaTable EXCEPT
                ![r] =
                [ state |-> "FAILED_OPEN", location |-> NoServer ]]
           /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
           /\ UNCHANGED << scpVars, rpcVars, serverVars, rsVars,
                            masterVars, locked >>
```

---

## Failure Path

### DispatchFail(r)

Open command dispatch failed (non-deterministic RPC failure). The command is removed from `dispatchedOps` without delivery and the TRSP returns to `GET_ASSIGN_CANDIDATE` with `forceNewPlan` (targetServer cleared). The region remains `OPENING` with its current location; the next `TRSPDispatchOpen` will update the location.

**Pre:** ASSIGN/MOVE/REOPEN procedure in `CONFIRM_OPENED` state and the matching OPEN command still exists in `dispatchedOps` (not yet consumed by an RS).

**Post:** Command removed, TRSP reset to `GET_ASSIGN_CANDIDATE`.

*Source: `RegionRemoteProcedureBase.remoteCallFailed()` sets `DISPATCH_FAIL` state and wakes the parent TRSP; `RSProcedureDispatcher.scheduleForRetry()` decides whether to retry or fail.*

```tla
DispatchFail(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ s # NoServer
        /\ LET cmd == [ type |-> "OPEN", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
        /\ regionState' =
             [regionState EXCEPT
             ![r].procStep = "GET_ASSIGN_CANDIDATE",
             ![r].targetServer = NoServer]
        /\ UNCHANGED << scpVars, serverVars, procVars, rsVars,
                         masterVars, metaTable, pendingReports >>
```

---

### DispatchFailClose(r)

Close command dispatch failed (non-deterministic RPC failure). The command is removed from `dispatchedOps` without delivery and the TRSP returns to `CLOSE` to retry the dispatch. Unlike the open path (`DispatchFail`), the `targetServer` is NOT cleared because the close must still target the server hosting the region.

**Pre:** UNASSIGN/MOVE/REOPEN procedure in `CONFIRM_CLOSED` state and the matching CLOSE command still exists in `dispatchedOps`.

**Post:** Command removed, TRSP reset to `CLOSE`.

*Source: `RegionRemoteProcedureBase.remoteCallFailed()` sets `DISPATCH_FAIL` state; `RSProcedureDispatcher.scheduleForRetry()`.*

```tla
DispatchFailClose(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ s # NoServer
        /\ LET cmd == [ type |-> "CLOSE", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
        /\ regionState' = [regionState EXCEPT ![r].procStep = "CLOSE"]
        /\ UNCHANGED << scpVars, serverVars, procVars, rsVars,
                         masterVars, metaTable, pendingReports >>
```

---

## UNASSIGN Path

### TRSPCreateUnassign(r)

Create a TRSP UNASSIGN procedure for an OPEN region.

**Pre:** Region is OPEN with a location AND no procedure is attached.

**Post:** Procedure fields set to `UNASSIGN`/`CLOSE` with `targetServer` pointing at the region's current server. Region lifecycle state is NOT changed yet.

*Source: `TRSP.unassign()` creates the procedure with `TransitionType.UNASSIGN`; `TRSP.setInitialAndLastState()` sets initial state to `CLOSE` and last state to `CONFIRM_CLOSED`; `RegionStateNode.setProcedure()` attaches it.*

```tla
TRSPCreateUnassign(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # NoServer
  /\ regionState[r].procType = "NONE"
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType = "UNASSIGN",
       ![r].procStep = "CLOSE",
       ![r].targetServer = regionState[r].location,
       ![r].retries = 0]
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("UNASSIGN", "CLOSE", regionState[r].location, NoTransition)]
  /\ UNCHANGED << scpVars, rpcVars, serverVars, rsVars,
                   masterVars, metaTable, locked >>
```

---

## MOVE Path

### TRSPCreateMove(r)

Create a TRSP MOVE procedure for an OPEN region. A MOVE is an UNASSIGN (close on the current server) followed by an ASSIGN (open on a new server), all within a single procedure. Starts in the CLOSE phase with `targetServer` set to the region's current location. After the close completes, `targetServer` is cleared and `TRSPGetCandidate` picks a new destination.

**Pre:** Region is OPEN with a location, no procedure attached, AND at least one other ONLINE server exists as a potential destination.

**Post:** MOVE procedure created in `CLOSE` state, attached to region.

*Source: `TRSP.move()` / `TRSP.reopen()` creates the procedure with `TransitionType.MOVE`; `TRSP.setInitialAndLastState()` sets initial state to `CLOSE`.*

```tla
TRSPCreateMove(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # NoServer
  /\ regionState[r].procType = "NONE"
  /\ \E s \in Servers: s # regionState[r].location /\ serverState[s] = "ONLINE"
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType = "MOVE",
       ![r].procStep = "CLOSE",
       ![r].targetServer = regionState[r].location,
       ![r].retries = 0]
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("MOVE", "CLOSE", regionState[r].location, NoTransition)]
  /\ UNCHANGED << scpVars, rpcVars, serverVars, rsVars,
                   masterVars, metaTable, locked >>
```

---

## REOPEN Path

### TRSPCreateReopen(r)

Create a TRSP REOPEN procedure for an OPEN region. REOPEN follows the same flow as MOVE (`CLOSE` → `CONFIRM_CLOSED` → `GET_ASSIGN_CANDIDATE` → `OPEN` → `CONFIRM_OPENED`) but differs in that `assignCandidate` is pre-set to the region's current server, so `TRSPGetCandidate` may choose the same server. There is no requirement that another ONLINE server exists (contrast with `TRSPCreateMove`).

**Pre:** Region is OPEN, has a location, and has no active procedure. `UseReopen = TRUE`.

**Post:** REOPEN procedure created in `CLOSE` state, `targetServer` set to the region's current location (assignCandidate pinning).

*Source: `TRSP.reopen()`; `TRSP.setInitialAndLastState()`; `TRSP.queueAssign()` (retain=true when assignCandidate set).*

```tla
TRSPCreateReopen(r) ==
  /\ masterAlive = TRUE
  /\ UseReopen = TRUE
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # NoServer
  /\ regionState[r].procType = "NONE"
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType = "REOPEN",
       ![r].procStep = "CLOSE",
       ![r].targetServer = regionState[r].location,
       ![r].retries = 0]
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("REOPEN", "CLOSE", regionState[r].location, NoTransition)]
  /\ UNCHANGED << scpVars, rpcVars, serverVars, rsVars,
                   masterVars, metaTable, locked >>
```

---

## CLOSE Path

### TRSPDispatchClose(r)

Dispatch the close command to the target server via RPC.

**Pre:** UNASSIGN/MOVE/REOPEN procedure in `CLOSE` state, region is `OPEN` or `CLOSING` (CLOSING on retry after `DispatchFailClose`).

**Post:** Region transitions to `CLOSING` (no-op if already CLOSING), meta updated, close command added to `dispatchedOps[targetServer]`, TRSP advances to `CONFIRM_CLOSED`.

*Source: `TRSP.closeRegion()` calls `AM.regionClosing()` to set CLOSING state and meta, then creates a `CloseRegionProcedure` child; `RSProcedureDispatcher` dispatches the RPC.*

```tla
TRSPDispatchClose(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CLOSE"
  /\ regionState[r].targetServer # NoServer
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ regionState[r].state \in { "OPEN", "CLOSING" }
        /\ regionState' =
             [regionState EXCEPT
             ![r].state = "CLOSING",
             ![r].procStep = "CONFIRM_CLOSED"]
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "CLOSING", location |-> metaTable[r].location ]]
        /\ dispatchedOps' =
             [dispatchedOps EXCEPT
             ![s] =
             @ \cup { [ type |-> "CLOSE", region |-> r ] }]
        /\ procStore' = [procStore EXCEPT ![r].step = "CONFIRM_CLOSED"]
        /\ UNCHANGED << scpVars, serverVars, rsVars,
                         masterVars, pendingReports, locked >>
```

---

### TRSPReportSucceedClose(r)

RS CLOSED report consumed: in-memory `regionState` updated to CLOSED, procedure persisted to `procStore` at `REPORT_SUCCEED` with `transitionCode = "CLOSED"`. `metaTable` is NOT updated yet.

**Pre:** UNASSIGN/MOVE/REOPEN procedure in `CONFIRM_CLOSED` step, region is `CLOSING`, and a matching `CLOSED` report exists from the target server. The `r ∉ rsOnlineRegions[rpt.server]` guard prevents consuming a CLOSED report if the region was reopened on that server.

**Post:** In-memory state = CLOSED, `procStore` at `REPORT_SUCCEED`.

*Source: `RegionRemoteProcedureBase.reportTransition()` → RRPB `REPORT_SUCCEED` state for the close path.*

```tla
TRSPReportSucceedClose(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ regionState[r].state = "CLOSING"
  /\ locked[r] = FALSE
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code = "CLOSED"
       /\ rpt.server = regionState[r].targetServer
       /\ r \notin rsOnlineRegions[rpt.server]
       /\ regionState' =
            [regionState EXCEPT
            ![r].state = "CLOSED",
            ![r].location = NoServer,
            ![r].procStep = "REPORT_SUCCEED"]
       /\ pendingReports' = pendingReports \ { rpt }
       /\ procStore' =
            [procStore EXCEPT
            ![r].step = "REPORT_SUCCEED",
            ![r].transitionCode = "CLOSED"]
       /\ LET loc == regionState[r].location
          IN serverRegions' =
                IF loc # NoServer
                THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                ELSE serverRegions
       /\ UNCHANGED << scpVars, rsVars, masterVars, metaTable,
                        dispatchedOps, serverState, locked >>
```

---

### TRSPPersistToMetaClose(r)

Final CLOSED state persisted to `metaTable`.

- **UNASSIGN:** procedure completed, record deleted from `procStore`.
- **MOVE/REOPEN:** procedure advances to `GET_ASSIGN_CANDIDATE` for re-open.

**Pre:** Procedure at `REPORT_SUCCEED` with `transitionCode = CLOSED`.

**Post:** `metaTable` updated, procedure completed or advanced.

*Source: RRPB persist-to-meta phase for close path.*

```tla
TRSPPersistToMetaClose(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "REPORT_SUCCEED"
  /\ procStore[r].transitionCode = "CLOSED"
  /\ locked[r] = FALSE
  /\ metaTable' =
       [metaTable EXCEPT ![r] = [ state |-> "CLOSED", location |-> NoServer ]]
  /\ IF regionState[r].procType = "UNASSIGN"
     THEN \* UNASSIGN complete: clear procedure.
          /\ regionState' =
               [regionState EXCEPT
               ![r] =
               [ state |-> "CLOSED",
                 location |-> NoServer,
                 procType |-> "NONE",
                 procStep |-> "IDLE",
                 targetServer |-> NoServer,
                 retries |-> 0
               ]]
          /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
     ELSE \* MOVE/REOPEN: advance to GET_ASSIGN_CANDIDATE.
          /\ regionState' =
               [regionState EXCEPT
               ![r].procStep = "GET_ASSIGN_CANDIDATE",
               ![r].targetServer = NoServer,
               ![r].retries = 0]
          /\ procStore' =
               [procStore EXCEPT
               ![r] =
               NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  /\ UNCHANGED << scpVars, rpcVars, serverVars, rsVars, masterVars, locked >>
```

---

### TRSPConfirmClosedCrash(r)

Crash during close: the target server crashed while the close was in flight. The procedure self-recovers: convert to ASSIGN at `GET_ASSIGN_CANDIDATE` to reopen the region (needed to process recovered edits).

**Pre:** UNASSIGN/MOVE/REOPEN at `CONFIRM_CLOSED`, region `ABNORMALLY_CLOSED`.

**Post:** Procedure converted to `ASSIGN`/`GET_ASSIGN_CANDIDATE`, stale dispatched commands cleared.

*Source: `TRSP.confirmClosed()` detects `ABNORMALLY_CLOSED`; `RegionRemoteProcedureBase.serverCrashed()`.*

```tla
TRSPConfirmClosedCrash(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ locked[r] = FALSE
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType = "ASSIGN",
       ![r].procStep = "GET_ASSIGN_CANDIDATE",
       ![r].targetServer = NoServer,
       ![r].retries = 0]
  /\ dispatchedOps' =
       [s \in Servers |-> {cmd \in dispatchedOps[s]: cmd.region # r}]
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  /\ LET loc == regionState[r].location
     IN serverRegions' =
           IF loc # NoServer
           THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
           ELSE serverRegions
  /\ UNCHANGED << scpVars, rsVars, masterVars, metaTable,
                   pendingReports, serverState, locked >>
```

---

## Crash Recovery

### TRSPServerCrashed(r)

A procedure's region has been crashed (`ABNORMALLY_CLOSED`). The procedure converts itself to an ASSIGN at `GET_ASSIGN_CANDIDATE`, modeling the TRSP `serverCrashed()` callback plus the `closeRegion()` recovery branch: when `closeRegion()` finds the region is not in a closeable state, it sets `forceNewPlan=true`, clears the location, and jumps to `GET_ASSIGN_CANDIDATE`.

This resolves the deadlock where an UNASSIGN procedure is stranded on an `ABNORMALLY_CLOSED` region with no way to progress. The full SCP machinery orchestrates when this callback fires; this action models what happens to the TRSP.

**Pre:** Region has an active procedure (any type) AND region state is `ABNORMALLY_CLOSED`. Procedure has not already been converted to `GET_ASSIGN_CANDIDATE` by `SCPAssignRegion` Path A.

**Post:** Procedure converted to `ASSIGN`/`GET_ASSIGN_CANDIDATE` with cleared `targetServer` and reset retries. Stale CLOSE commands cleared. Stale CLOSED reports dropped.

*Source: `TRSP.serverCrashed()` delegates to `RegionRemoteProcedureBase.serverCrashed()` if a sub-procedure is in flight; `TRSP.closeRegion()` else-branch sets `forceNewPlan` and advances to `GET_ASSIGN_CANDIDATE`.*

```tla
TRSPServerCrashed(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].procType # "NONE"
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
  /\ regionState[r].procStep # "GET_ASSIGN_CANDIDATE"
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType = "ASSIGN",
       ![r].procStep = "GET_ASSIGN_CANDIDATE",
       ![r].targetServer = NoServer,
       ![r].retries = 0]
  /\ dispatchedOps' =
       [t \in Servers |->
         {cmd \in dispatchedOps[t]: cmd.region # r \/ cmd.type # "CLOSE"}
       ]
  /\ pendingReports' =
       {pr \in pendingReports: pr.region # r \/ pr.code # "CLOSED"}
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  /\ UNCHANGED << scpVars, serverVars, rsVars, masterVars, metaTable, locked >>
```
