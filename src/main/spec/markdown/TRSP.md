# TRSP

**Source:** [`TRSP.tla`](../TRSP.tla)

TransitionRegionStateProcedure actions: assign, unassign, move, reopen, dispatch, confirm, failure, crash recovery, meta-blocking.

---

```tla
------------------------------ MODULE TRSP ------------------------------------
```

TransitionRegionStateProcedure actions for the HBase AssignmentManager. Contains all TRSP actions: ASSIGN path (create, get-candidate, dispatch-open, confirm-opened), failure path (dispatch-fail, dispatch-fail-close, handle-failed-open, give-up-open), UNASSIGN path (create-unassign), MOVE path (create-move), REOPEN path (create-reopen), CLOSE path (dispatch-close, confirm-closed), and crash recovery (server-crashed).

### TRSP State Mapping

The `TRSPState` set maps 1:1 to the `RegionStateTransitionState` protobuf enum (`MasterProcedure.proto`):

| TRSPState                | Protobuf enum value                                      |
|--------------------------|----------------------------------------------------------|
| `"GET_ASSIGN_CANDIDATE"` | `REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE` (=1)      |
| `"OPEN"`                 | `REGION_STATE_TRANSITION_OPEN` (=2)                      |
| `"CONFIRM_OPENED"`       | `REGION_STATE_TRANSITION_CONFIRM_OPENED` (=3)            |
| `"CLOSE"`                | `REGION_STATE_TRANSITION_CLOSE` (=4)                     |
| `"CONFIRM_CLOSED"`       | `REGION_STATE_TRANSITION_CONFIRM_CLOSED` (=5)            |

### RegionRemoteProcedureBase Child Procedure Absorption

The implementation uses child procedures (`OpenRegionProcedure`, `CloseRegionProcedure`) that extend `RegionRemoteProcedureBase` and have their own 4-state machine (`RegionRemoteProcedureBaseState` in `MasterProcedure.proto`). The model merges these into TRSP actions:

| RRPB state            | Absorbed by TLA+ action(s)                                                          |
|-----------------------|-------------------------------------------------------------------------------------|
| `DISPATCH` (=1)       | `TRSPDispatchOpen`, `TRSPDispatchClose` — RPC dispatch is atomic within TRSP step   |
| `REPORT_SUCCEED` (=2) | `TRSPConfirmOpened`, `TRSPConfirmClosed` — decomposed to model crash window         |
| `DISPATCH_FAIL` (=3)  | `DispatchFail`, `DispatchFailClose` — RPC failure resets TRSP to retry               |
| `SERVER_CRASH` (=4)   | `TRSPServerCrashed` — server crash recovery (type-preserving)                       |

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

RPC channel variables (used in `UNCHANGED` clauses).

```tla
rpcVars == << dispatchedOps, pendingReports >>
```

RS-side variable (used in `UNCHANGED` clauses).

```tla
rsVars == << rsOnlineRegions >>
```

Variables unchanged by TRSP actions (includes SCP state and ZK ephemeral nodes — neither is modified by any TRSP action).

```tla
scpVars ==
  << scpState, scpRegions, walFenced, carryingMeta, zkNode, regionKeyRange, parentProc >>
```

Master lifecycle variables (used in `UNCHANGED` clauses).

```tla
masterVars == << masterAlive >>
```

Server tracking variables (used in `UNCHANGED` clauses).

```tla
serverVars == << serverState, serverRegions >>
```

PEWorker pool variables (used in `UNCHANGED` clauses).

```tla
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>
```

`MetaIsAvailable` is `TRUE` when no server is in `ASSIGN_META` `scpState`, meaning `hbase:meta` is online and accessible for read/write.

```tla
MetaIsAvailable == \A s \in Servers: scpState[s] # "ASSIGN_META"
```

```tla
---------------------------------------------------------------------------
```

## TRSP ASSIGN Path

### `TRSPCreate(r)`

Create a TRSP `ASSIGN` procedure for a region eligible for assignment.

**Pre:** region is in a state eligible for assignment AND no procedure is currently attached.
**Post:** procedure fields set to `ASSIGN`/`GET_ASSIGN_CANDIDATE`. Region lifecycle state is *not* changed yet — the TRSP will drive transitions in subsequent steps.

> *Source:* `TRSP.assign()` creates the procedure; `TRSP.queueAssign()` applies the `GET_ASSIGN_CANDIDATE` initial state via `TRSP.setInitialAndLastState()`.

```tla
TRSPCreate(r) ==
```

Region is in an assignable state and has no active procedure. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Region must be in a state eligible for assignment.

```tla
  /\ regionState[r].state \in
       { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED", "FAILED_OPEN" }
```

No procedure is currently attached to this region.

```tla
  /\ regionState[r].procType = "NONE"
```

No parent procedure in progress (models `ProcedureExecutor` region-level locking).

```tla
  /\ parentProc[r].type = "NONE"
```

Don't auto-create `ASSIGN` for `ABNORMALLY_CLOSED` regions while any SCP is actively processing a crash. In the implementation, SCP owns crash recovery; no background daemon races to create procedures for crashed regions during the SCP assign loop.

```tla
  /\ \/ regionState[r].state # "ABNORMALLY_CLOSED"
     \/ \A s \in Servers: scpState[s] \in { "NONE", "DONE" }
```

Initialize embedded `ASSIGN` procedure at `GET_ASSIGN_CANDIDATE` step.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "ASSIGN",
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       NoServer,
       ![r].retries =
       0]
```

Persist the new procedure to the procedure store.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

META, RPC channels, RS-side state, and server liveness unchanged.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>
```

### `TRSPGetCandidate(r, s)`

Choose a target server for the `ASSIGN`, `MOVE`, `REOPEN`, or `UNASSIGN` procedure. `UNASSIGN` reaches `GET_ASSIGN_CANDIDATE` during two-phase crash recovery (reopen then re-close).

**Pre:** TRSP is in `GET_ASSIGN_CANDIDATE` state, chosen server is `ONLINE`, and no `CRASHED` server still has `r` in its `rsOnlineRegions` (the zombie window must be closed).
**Post:** `targetServer` set, TRSP advances to `OPEN` state.

> *Source:* `TRSP.queueAssign()` → `AM.queueAssign()` → `LoadBalancer`; `createDestinationServersList()` excludes `CRASHED` servers; `HRegionServer.abort()` (`RSAbort`) clears `rsOnlineRegions`.

```tla
TRSPGetCandidate(r, s) ==
```

Procedure is `ASSIGN`, `MOVE`, `REOPEN`, or `UNASSIGN` at `GET_ASSIGN_CANDIDATE`. Chosen server is `ONLINE`; no `CRASHED` server still holds `r` in `rsOnlineRegions`. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `ASSIGN`, `MOVE`, `REOPEN`, or `UNASSIGN` (`UNASSIGN` reaches `GET_ASSIGN_CANDIDATE` during two-phase crash recovery: reopen then re-close).

```tla
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
```

Procedure must be at the candidate selection step.

```tla
  /\ regionState[r].procStep = "GET_ASSIGN_CANDIDATE"
```

Chosen target server must be `ONLINE`.

```tla
  /\ serverState[s] = "ONLINE"
```

Zombie window must be closed: no `CRASHED` server still holds `r`.

```tla
  /\ \A sZombie \in Servers:
       serverState[sZombie] = "CRASHED" => r \notin rsOnlineRegions[sZombie]
```

Record the chosen server and advance the procedure to the `OPEN` step.

```tla
  /\ regionState' =
       [regionState EXCEPT ![r].targetServer = s, ![r].procStep = "OPEN"]
```

Update persisted procedure with target server and step.

```tla
  /\ procStore' = [procStore EXCEPT ![r].step = "OPEN", ![r].targetServer = s]
```

META, RPC channels, RS-side state, and server liveness unchanged.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>
```

### `TRSPDispatchOpen(r)`

Dispatch the open command to the target server via RPC.

**Pre:** TRSP is in `OPEN` state with a target server.
**Post:** region transitions to `OPENING` with the target server as location, meta updated, open command added to `dispatchedOps[targetServer]`, TRSP advances to `CONFIRM_OPENED`.

> *Source:* `TRSP.openRegion()` calls `AM.regionOpening()` to set `OPENING` state and meta, then creates an `OpenRegionProcedure` child; `RSProcedureDispatcher` dispatches the RPC.

```tla
TRSPDispatchOpen(r) ==
```

Procedure is `ASSIGN` or `MOVE`, in `OPEN` step, with a target server. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
  /\ regionState[r].procStep = "OPEN"
  /\ regionState[r].targetServer # NoServer
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
```

Region is not already suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not already blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Branch on meta-write blocking mode.

```tla
        /\ IF UseBlockOnMetaWrite = FALSE
```

Async: suspend procedure, release PEWorker thread.

```tla
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
```

Sync: block PEWorker thread on meta write.

```tla
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              regionState,
              metaTable,
              procStore
           >>
     \/ \* --- Meta available: dispatch open ---
        /\ MetaIsAvailable
```

Region is not already suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not already blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Transition region to `OPENING`, record server, advance to `CONFIRM_OPENED`.

```tla
        /\ LET s == regionState[r].targetServer
           IN /\ regionState' =
                   [regionState EXCEPT
                   ![r].state =
                   "OPENING",
                   ![r].location =
                   s,
                   ![r].procStep =
                   "CONFIRM_OPENED"]
```

Persist the `OPENING` state and server assignment in META.

```tla
              /\ metaTable' =
                   [metaTable EXCEPT
                   ![r] =
                   [ state |-> "OPENING", location |-> s ]]
```

Enqueue an `OPEN` command to the target server's dispatched ops.

```tla
              /\ dispatchedOps' =
                   [dispatchedOps EXCEPT
                   ![s] =
                   @ \cup { [ type |-> "OPEN", region |-> r ] }]
```

`ServerStateNode` tracking: `AM.regionOpening()` adds `r` to the new target server's set.

```tla
              /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \cup { r }]
```

Update persisted procedure step.

```tla
              /\ procStore' = [procStore EXCEPT ![r].step = "CONFIRM_OPENED"]
```

Pending reports, RS-side state, and server liveness unchanged.

```tla
              /\ UNCHANGED << scpVars,
                    rsVars,
                    masterVars,
                    peVars,
                    pendingReports,
                    serverState
                 >>

```
### `TRSPReportSucceedOpen(r)`

RS report consumed: in-memory `regionState` updated to reflect the RS report (`OPENED` or `FAILED_OPEN`), procedure persisted to `procStore` at `REPORT_SUCCEED` with the transition code recorded. `metaTable` is *not* updated yet — that happens in `TRSPPersistToMetaOpen`.

This action absorbs the former `TRSPConfirmOpened` (OPENED path), `TRSPHandleFailedOpen`, and `TRSPGiveUpOpen` by uniformly processing all report codes through the `REPORT_SUCCEED` intermediate step.

**Pre:** `ASSIGN`/`MOVE`/`REOPEN` procedure in `CONFIRM_OPENED` step, region is `OPENING`, and a matching report exists (`OPENED` or `FAILED_OPEN`).
**Post:** in-memory state reflects report, `procStore` updated to `REPORT_SUCCEED` with `transitionCode`. `metaTable` unchanged.

> *Source:* `RegionRemoteProcedureBase.reportTransition()` → RRPB `REPORT_SUCCEED` state.

```tla
TRSPReportSucceedOpen(r) ==
```

Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `ASSIGN`, `MOVE`, `REOPEN`, or `UNASSIGN`.

```tla
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
```

Procedure must be waiting for the open confirmation.

```tla
  /\ regionState[r].procStep = "CONFIRM_OPENED"
```

Region must be in `OPENING` state (dispatch already happened).

```tla
  /\ regionState[r].state = "OPENING"
```

A matching report exists for this region.

```tla
  /\ \E rpt \in pendingReports:
```

Report must be for region `r`.

```tla
       /\ rpt.region = r
       /\ rpt.code \in { "OPENED", "FAILED_OPEN" }
```

Report must be from the target server. In Java, `RegionRemoteProcedureBase.reportTransition()` (L208–211) validates the server name for both open and close paths via `reportRegionStateTransition()`. Without this check the model could accept stale `OPENED` reports from a previous server after a crash+reassign.

```tla
       /\ rpt.server = regionState[r].targetServer
```

Prefer `OPENED` over `FAILED_OPEN`: if both exist, only consume `OPENED`.

```tla
       /\ rpt.code = "FAILED_OPEN" =>
            ~\E rpt2 \in pendingReports: rpt2.region = r /\ rpt2.code = "OPENED"
```

Update in-memory state to reflect the report.
- **`OPENED`:** transition to `OPEN` (`AM.regionOpenedWithoutPersistingToMeta()`).
- **`FAILED_OPEN`:** no state/location change — faithful to the implementation's `regionFailedOpen(giveUp=false)` which only calls `removeRegionFromServer()` without calling `setState()` or `setRegionLocation(null)`. Region stays `OPENING` with location intact.

```tla
       /\ regionState' =
            [regionState EXCEPT
            ![r].state =
            IF rpt.code = "OPENED"
            THEN "OPEN"
```

`AM.regionOpenedWithoutPersistingToMeta()`

```tla
            ELSE regionState[r].state,
```

`FAILED_OPEN`: stays `OPENING`

```tla
            ![r].procStep =
            "REPORT_SUCCEED"]
```

Consume the report.

```tla
       /\ pendingReports' = pendingReports \ { rpt }
```

Persist procedure at `REPORT_SUCCEED` with the transition code.

```tla
       /\ procStore' =
            [procStore EXCEPT
            ![r].step =
            "REPORT_SUCCEED",
            ![r].transitionCode =
            IF rpt.code = "OPENED" THEN "OPENED" ELSE "FAILED_OPEN"]
```

`ServerStateNode` tracking: only for `FAILED_OPEN`. `AM.regionFailedOpen()` calls `removeRegionFromServer()`.

```tla
       /\ LET loc == regionState[r].location
          IN serverRegions' =
                IF rpt.code = "FAILED_OPEN" /\ loc # NoServer
                THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                ELSE serverRegions
```

`metaTable` **not** updated: meta persist happens in `TRSPPersistToMetaOpen`.

```tla
       /\ UNCHANGED << scpVars,
             rsVars,
             masterVars,
             peVars,
             metaTable,
             dispatchedOps,
             serverState
          >>
```

### `TRSPPersistToMetaOpen(r)`

**Persist-to-meta for OPEN path** — final state persisted to `metaTable`.
- **`OPENED`:** procedure completed, record deleted from `procStore`.
- **`FAILED_OPEN` (retry):** retries remaining, advance back to `GET_ASSIGN_CANDIDATE`.
- **`FAILED_OPEN` (give-up):** retries exhausted, move to `FAILED_OPEN` terminal state.

**Pre:** procedure at `REPORT_SUCCEED` with `transitionCode` ∈ {`OPENED`, `FAILED_OPEN`}.
**Post:** `metaTable` updated, procedure completed or advanced.

> *Source:* RRPB persist-to-meta phase for open path.

```tla
TRSPPersistToMetaOpen(r) ==
```

Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `ASSIGN`, `MOVE`, `REOPEN`, or `UNASSIGN`.

```tla
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
```

Procedure must be at the report-succeed (persist-to-meta) step.

```tla
  /\ regionState[r].procStep = "REPORT_SUCCEED"
```

Transition code must be for the open path.

```tla
  /\ procStore[r].transitionCode \in { "OPENED", "FAILED_OPEN" }
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
```

Region is not already suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not already blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Branch on meta-write blocking mode.

```tla
        /\ IF UseBlockOnMetaWrite = FALSE
```

Async: suspend procedure, release PEWorker thread.

```tla
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
```

Sync: block PEWorker thread on meta write.

```tla
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              regionState,
              metaTable,
              procStore
           >>
     \/ \* --- OPENED branch ---
```

Meta must be available to persist the state.

```tla
        /\ MetaIsAvailable
```

Region is not suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Transition code must be `OPENED`.

```tla
        /\ tc = "OPENED"
```

In-memory state must already reflect `OPEN` (from `TRSPReportSucceedOpen`).

```tla
        /\ regionState[r].state = "OPEN"
```

Persist `OPEN` to `metaTable`. Write final `OPEN` state and server location to meta.

```tla
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "OPEN", location |-> metaTable[r].location ]]
```

Branch on procedure type. **UNASSIGN two-phase recovery:** reopen succeeded, now continue to `CLOSE` phase to complete the unassignment. Models `confirmOpened()` L289-301 where `lastState == CONFIRM_CLOSED` triggers `nextState = CLOSE`, returns `HAS_MORE_STATE`. **ASSIGN/MOVE/REOPEN:** procedure complete.

```tla
        /\ IF regionState[r].procType = "UNASSIGN"
              THEN /\ regionState' =
                        [regionState EXCEPT
                        ![r].procStep =
                        "CLOSE",
                        ![r].targetServer =
                        regionState[r].location]
                   /\ procStore' =
                        [procStore EXCEPT
                        ![r] =
                        NewProcRecord("UNASSIGN", "CLOSE",
                                       regionState[r].location,
                                       NoTransition)]
              ELSE /\ regionState' =
                        [regionState EXCEPT
                        ![r] =
                        [ state |-> "OPEN",
                          location |-> regionState[r].location,
                          procType |-> "NONE",
                          procStep |-> "IDLE",
                          targetServer |-> NoServer,
                          retries |-> 0
                        ]]
                   /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              peVars
           >>
     \/ \* --- FAILED_OPEN, retry branch ---
```

Meta must be available to persist the state.

```tla
        /\ MetaIsAvailable
```

Region is not suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Transition code must be `FAILED_OPEN`.

```tla
        /\ procStore[r].transitionCode = "FAILED_OPEN"
```

Retries remain; reset to `GET_ASSIGN_CANDIDATE` for another attempt.

```tla
        /\ regionState[r].retries < MaxRetries
```

Increment retry counter, clear target server, advance to `GET_ASSIGN_CANDIDATE`.

```tla
        /\ regionState' =
             [regionState EXCEPT
             ![r].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r].targetServer =
             NoServer,
             ![r].retries =
             @ + 1]
```

Clear stale open commands for this region.

```tla
        /\ dispatchedOps' =
             [t \in Servers |->
               {cmd \in dispatchedOps[t]:
                 cmd.region # r \/ cmd.type # "OPEN"
               }
             ]
        /\ UNCHANGED << scpVars,
              serverVars,
              rsVars,
              masterVars,
              peVars,
              metaTable,
              pendingReports
           >>
        \/ \* --- FAILED_OPEN, give-up branch ---
```

Meta must be available to persist the state.

```tla
           /\ MetaIsAvailable
```

Region is not suspended waiting for meta.

```tla
           /\ r \notin suspendedOnMeta
```

Region is not blocking a PEWorker on meta.

```tla
           /\ r \notin blockedOnMeta
```

Transition code must be `FAILED_OPEN`.

```tla
           /\ tc = "FAILED_OPEN"
```

Retries exhausted; give up on opening this region.

```tla
           /\ regionState[r].retries >= MaxRetries
```

Move region to `FAILED_OPEN`, clear procedure.

```tla
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
```

Persist `FAILED_OPEN` to `metaTable`.

```tla
           /\ metaTable' =
                [metaTable EXCEPT
                ![r] =
                [ state |-> "FAILED_OPEN", location |-> NoServer ]]
```

Delete completed procedure from store.

```tla
           /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
           /\ UNCHANGED << scpVars,
                 rpcVars,
                 serverVars,
                 rsVars,
                 masterVars,
                 peVars
              >>

```

```tla
---------------------------------------------------------------------------
```

## Failure Path

### `DispatchFail(r)`

Open command dispatch failed (non-deterministic RPC failure). The command is removed from `dispatchedOps` without delivery and the TRSP returns to `GET_ASSIGN_CANDIDATE` with `forceNewPlan` (i.e., `targetServer` is cleared so a fresh candidate will be chosen). The region remains `OPENING` with its current location; the next `TRSPDispatchOpen` will update the location to the new server.

**Pre:** `ASSIGN`/`MOVE`/`REOPEN` procedure in `CONFIRM_OPENED` state, the matching open command still exists in `dispatchedOps` (not yet consumed by an RS), AND the target server is `ONLINE`.
**Post:** command removed, TRSP reset to `GET_ASSIGN_CANDIDATE`.

Guard: `serverState[s] = "ONLINE"` matches the implementation's early-return in `RRPB.remoteCallFailed()` (RRPB.java L122-127): if the server is dead, `remoteCallFailed()` returns without setting `DISPATCH_FAIL`, relying on `SCP.serverCrashed()` instead.

> *Source:* `RegionRemoteProcedureBase.remoteCallFailed()` sets `DISPATCH_FAIL` state and wakes the parent TRSP; `RSProcedureDispatcher.scheduleForRetry()` decides whether to retry or fail the remote call.

```tla
DispatchFail(r) ==
```

Procedure is `ASSIGN` or `MOVE`, in `CONFIRM_OPENED` step, has a target server, and the OPEN command is still in `dispatchedOps`. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `ASSIGN`, `MOVE`, `REOPEN`, or `UNASSIGN`.

```tla
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN", "UNASSIGN" }
```

Procedure must be waiting for the open confirmation.

```tla
  /\ regionState[r].procStep = "CONFIRM_OPENED"
```

Bind the target server; it must have been set by `TRSPGetCandidate`.

```tla
  /\ LET s == regionState[r].targetServer
     IN /\ s # NoServer
```

Target server must be `ONLINE`: `RRPB.remoteCallFailed()` early-returns when `isServerOnline()` is false (RRPB.java L122-127).

```tla
        /\ serverState[s] = "ONLINE"
```

Reconstruct the dispatched command and verify it is still queued.

```tla
        /\ LET cmd == [ type |-> "OPEN", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
```

Remove the undelivered command from the server's queue.

```tla
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
```

Reset the procedure to `GET_ASSIGN_CANDIDATE` with no target server.

```tla
        /\ regionState' =
             [regionState EXCEPT
             ![r].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r].targetServer =
             NoServer]
```

META, pending reports, RS-side state, and server liveness unchanged.

```tla
        /\ UNCHANGED << scpVars,
              serverVars,
              procStore,
              rsVars,
              masterVars,
              peVars,
              metaTable,
              pendingReports
           >>

```

### `DispatchFailClose(r)`

Close command dispatch failed (non-deterministic RPC failure). The command is removed from `dispatchedOps` without delivery and the TRSP returns to `CLOSE` to retry the dispatch. Unlike the open path (`DispatchFail`), the `targetServer` is *not* cleared because the close must still target the server hosting the region.

**Pre:** `UNASSIGN` or `MOVE` procedure in `CONFIRM_CLOSED` state, the matching close command still exists in `dispatchedOps`, AND the target server is `ONLINE`.
**Post:** command removed, TRSP reset to `CLOSE`.

Guard: `serverState[s] = "ONLINE"` — same rationale as `DispatchFail`.

> *Source:* `RegionRemoteProcedureBase.remoteCallFailed()` sets `DISPATCH_FAIL` state and wakes the parent TRSP; `RSProcedureDispatcher.scheduleForRetry()` decides whether to retry or fail the remote call.

```tla
DispatchFailClose(r) ==
```

Procedure is `UNASSIGN` or `MOVE`, in `CONFIRM_CLOSED` step, has a target server, and the `CLOSE` command is still in `dispatchedOps`. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `UNASSIGN`, `MOVE`, or `REOPEN`.

```tla
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
```

Procedure must be waiting for the close confirmation.

```tla
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
```

Bind the target server; it must have been set by `TRSPGetCandidate`.

```tla
  /\ LET s == regionState[r].targetServer
     IN /\ s # NoServer
```

Target server must be `ONLINE` (same guard as `DispatchFail`).

```tla
        /\ serverState[s] = "ONLINE"
```

Reconstruct the dispatched command and verify it is still queued.

```tla
        /\ LET cmd == [ type |-> "CLOSE", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
```

Remove the undelivered command from the server's queue.

```tla
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
```

Reset the procedure to `CLOSE` to retry (target server kept).

```tla
        /\ regionState' = [regionState EXCEPT ![r].procStep = "CLOSE"]
```

META, pending reports, RS-side state, and server liveness unchanged.

```tla
        /\ UNCHANGED << scpVars,
              serverVars,
              procStore,
              rsVars,
              masterVars,
              peVars,
              metaTable,
              pendingReports
           >>

```

```tla
---------------------------------------------------------------------------
```

## TRSP UNASSIGN Path

### `TRSPCreateUnassign(r)`

Create a TRSP `UNASSIGN` procedure for an `OPEN` region.

**Pre:** region is `OPEN` with a location AND no procedure is attached.
**Post:** procedure fields set to `UNASSIGN`/`CLOSE` with `targetServer` pointing at the region's current server. Region lifecycle state is *not* changed yet — the TRSP will drive the `OPEN` → `CLOSING` transition in the next step.

> *Source:* `TRSP.unassign()` creates the procedure with `TransitionType.UNASSIGN`; `TRSP.setInitialAndLastState()` sets initial state to `CLOSE` and last state to `CONFIRM_CLOSED`; `RegionStateNode.setProcedure()` attaches it to the region.

```tla
TRSPCreateUnassign(r) ==
```

Region is `OPEN`, has a location, and has no active procedure. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Region must be in `OPEN` state.

```tla
  /\ regionState[r].state = "OPEN"
```

Region must have a server location assigned.

```tla
  /\ regionState[r].location # NoServer
```

No parent procedure in progress (models `ProcedureExecutor` region-level locking).

```tla
  /\ parentProc[r].type = "NONE"
```

No procedure is currently attached to this region.

```tla
  /\ regionState[r].procType = "NONE"
```

Initialize embedded `UNASSIGN` procedure at `CLOSE` step, targeting current server.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "UNASSIGN",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
```

Persist the new procedure to the procedure store.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("UNASSIGN", "CLOSE", regionState[r].location, NoTransition)]
```

META, RPC channels, RS-side state, and server liveness unchanged.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>

```

### `TRSPCreateMove(r)`

Create a TRSP `MOVE` procedure for an `OPEN` region. A `MOVE` is an `UNASSIGN` (close on the current server) followed by an `ASSIGN` (open on a new server), all within a single procedure. The procedure starts in the `CLOSE` phase with `targetServer` set to the region's current location. After the close completes, `targetServer` is cleared and `TRSPGetCandidate` picks a new destination.

**Pre:** region is `OPEN` with a location AND no procedure is attached AND at least one other `ONLINE` server exists as a potential destination.
**Post:** `MOVE` procedure created in `CLOSE` state, attached to region.

> *Source:* `TRSP.move()` / `TRSP.reopen()` creates the procedure with `TransitionType.MOVE`; `TRSP.setInitialAndLastState()` sets initial state to `CLOSE` and last state to `CONFIRM_OPENED`; `RegionStateNode.setProcedure()` attaches it to the region.

```tla
TRSPCreateMove(r) ==
```

Region is `OPEN`, has a location, has no active procedure, and at least one other `ONLINE` server exists as a destination. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Region must be in `OPEN` state.

```tla
  /\ regionState[r].state = "OPEN"
```

No parent procedure in progress (models `ProcedureExecutor` region-level locking).

```tla
  /\ parentProc[r].type = "NONE"
```

Region must have a server location assigned.

```tla
  /\ regionState[r].location # NoServer
```

No procedure is currently attached to this region.

```tla
  /\ regionState[r].procType = "NONE"
```

At least one other `ONLINE` server must exist as a move destination.

```tla
  /\ \E s \in Servers: s # regionState[r].location /\ serverState[s] = "ONLINE"
```

Initialize embedded `MOVE` procedure at `CLOSE` step, targeting current server.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "MOVE",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
```

Persist the new procedure to the procedure store.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("MOVE", "CLOSE", regionState[r].location, NoTransition)]
```

META, RPC channels, RS-side state, and server liveness unchanged.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>

```

### `TRSPCreateReopen(r)`

Create a TRSP `REOPEN` procedure for an `OPEN` region.
`REOPEN` follows the same flow as `MOVE` (`CLOSE` → `CONFIRM_CLOSED` → `GET_ASSIGN_CANDIDATE` → `OPEN` → `CONFIRM_OPENED`) but differs in that `assignCandidate` is pre-set to the region's current server, so `TRSPGetCandidate` may choose the same server. There is no requirement that another `ONLINE` server exists (contrast with `TRSPCreateMove`).

**Pre:** region is `OPEN`, has a location, and has no active procedure.
**Post:** `REOPEN` procedure created in `CLOSE` state, `targetServer` set to the region's current location (assignCandidate pinning).

> *Source:* `TRSP.reopen()`; `TRSP.setInitialAndLastState()`; `TRSP.queueAssign()` (`retain=true` when `assignCandidate` set).

```tla
TRSPCreateReopen(r) ==
```

`UseReopen` enabled, region is `OPEN`, has a server location, no active procedure. No requirement that another `ONLINE` server exists. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

`REOPEN` feature must be enabled.

```tla
  /\ UseReopen = TRUE
```

No parent procedure in progress (models `ProcedureExecutor` region-level locking).

```tla
  /\ parentProc[r].type = "NONE"
```

Region must be in `OPEN` state.

```tla
  /\ regionState[r].state = "OPEN"
```

Region must have a server location assigned.

```tla
  /\ regionState[r].location # NoServer
```

No procedure is currently attached to this region.

```tla
  /\ regionState[r].procType = "NONE"
```

Initialize embedded `REOPEN` procedure at `CLOSE` step, targeting the region's current server (assignCandidate pinning).

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "REOPEN",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
```

Persist the new procedure to the procedure store.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("REOPEN", "CLOSE", regionState[r].location, NoTransition)]
```

META, RPC channels, RS-side state, and server liveness unchanged.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable
     >>

```

### `TRSPDispatchClose(r)`

Dispatch the close command to the target server via RPC.

**Pre:** `UNASSIGN` or `MOVE` procedure in `CLOSE` state, region is `OPEN` or `CLOSING` (`CLOSING` on retry after `DispatchFailClose`).
**Post:** region transitions to `CLOSING` (no-op if already `CLOSING`), meta updated, close command added to `dispatchedOps[targetServer]`, TRSP advances to `CONFIRM_CLOSED`.

> *Source:* `TRSP.closeRegion()` calls `AM.regionClosing()` to set `CLOSING` state and meta, then creates a `CloseRegionProcedure` child; `RSProcedureDispatcher` dispatches the RPC via `RSProcedureDispatcher.ExecuteProceduresRemoteCall.run()`.

```tla
TRSPDispatchClose(r) ==
```

Procedure is `UNASSIGN` or `MOVE`, in `CLOSE` step, has a target server, and region is `OPEN` or `CLOSING` (`CLOSING` on retry). Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `UNASSIGN`, `MOVE`, or `REOPEN`.

```tla
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
```

Procedure must be at the `CLOSE` step.

```tla
  /\ regionState[r].procStep = "CLOSE"
```

A target server must be set for the close dispatch.

```tla
  /\ regionState[r].targetServer # NoServer
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
```

Region is not already suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not already blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Branch on meta-write blocking mode.

```tla
        /\ IF UseBlockOnMetaWrite = FALSE
```

Async: suspend procedure, release PEWorker thread.

```tla
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
```

Sync: block PEWorker thread on meta write.

```tla
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              regionState,
              metaTable,
              procStore
           >>
     \/ \* --- Meta available: dispatch close ---
        /\ MetaIsAvailable
        /\ r \notin suspendedOnMeta
        /\ r \notin blockedOnMeta
```

Bind the target server for readability.

```tla
        /\ LET s == regionState[r].targetServer
           IN /\ regionState[r].state \in { "OPEN", "CLOSING", "SPLITTING", "MERGING" }
```

Transition region to `CLOSING` and advance to `CONFIRM_CLOSED`.

```tla
              /\ regionState' =
                   [regionState EXCEPT
                   ![r].state =
                   "CLOSING",
                   ![r].procStep =
                   "CONFIRM_CLOSED"]
```

Persist `CLOSING` state in META, preserving the location.

```tla
              /\ metaTable' =
                   [metaTable EXCEPT
                   ![r] =
                   [ state |-> "CLOSING", location |-> metaTable[r].location ]]
```

Enqueue a `CLOSE` command to the target server's dispatched ops.

```tla
              /\ dispatchedOps' =
                   [dispatchedOps EXCEPT
                   ![s] =
                   @ \cup { [ type |-> "CLOSE", region |-> r ] }]
```

Update persisted procedure step.

```tla
              /\ procStore' = [procStore EXCEPT ![r].step = "CONFIRM_CLOSED"]
              /\ UNCHANGED << scpVars,
                    serverVars,
                    rsVars,
                    masterVars,
                    peVars,
                    pendingReports
                 >>


```
### `TRSPReportSucceedClose(r)`

**Report-succeed for CLOSE path** — RS `CLOSED` report consumed: in-memory `regionState` updated to `CLOSED`, procedure persisted to `procStore` at `REPORT_SUCCEED` with `transitionCode = "CLOSED"`. `metaTable` is *not* updated yet.

**Pre:** `UNASSIGN`/`MOVE`/`REOPEN` procedure in `CONFIRM_CLOSED` step, region is `CLOSING`, and a matching `CLOSED` report exists from the target server.
**Post:** in-memory state = `CLOSED`, `procStore` at `REPORT_SUCCEED`.

> *Source:* `RegionRemoteProcedureBase.reportTransition()` → RRPB `REPORT_SUCCEED` state for the close path.

```tla
TRSPReportSucceedClose(r) ==
```

Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `UNASSIGN`, `MOVE`, or `REOPEN`.

```tla
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
```

Procedure must be waiting for the close confirmation.

```tla
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
```

Region must be in `CLOSING` state (dispatch already happened).

```tla
  /\ regionState[r].state = "CLOSING"
```

A matching `CLOSED` report exists for this region.

```tla
  /\ \E rpt \in pendingReports:
```

Report must be for region `r`.

```tla
       /\ rpt.region = r
```

Report must indicate successful close.

```tla
       /\ rpt.code = "CLOSED"
```

Report must be from the target server.

```tla
       /\ rpt.server = regionState[r].targetServer
```

Do not consume if `r` was reopened on that server.

```tla
       /\ r \notin rsOnlineRegions[rpt.server]
```

Update in-memory state: `CLOSING` → `CLOSED`. `AM.regionClosedWithoutPersistingToMeta()`.

```tla
       /\ regionState' =
            [regionState EXCEPT
            ![r].state =
            "CLOSED",
            ![r].location =
            NoServer,
            ![r].procStep =
            "REPORT_SUCCEED"]
```

Consume the report.

```tla
       /\ pendingReports' = pendingReports \ { rpt }
```

Persist at `REPORT_SUCCEED` with `transitionCode = CLOSED`.

```tla
       /\ procStore' =
            [procStore EXCEPT
            ![r].step =
            "REPORT_SUCCEED",
            ![r].transitionCode =
            "CLOSED"]
```

`ServerStateNode` tracking: `AM.regionClosedWithoutPersisting()` calls `removeRegionFromServer()`.

```tla
       /\ LET loc == regionState[r].location
          IN serverRegions' =
                IF loc # NoServer
                THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
                ELSE serverRegions
```

`metaTable` **not** updated yet.

```tla
       /\ UNCHANGED << scpVars,
             rsVars,
             peVars,
             masterVars,
             metaTable,
             dispatchedOps,
             serverState
          >>

```

### `TRSPPersistToMetaClose(r)`

**Persist-to-meta for CLOSE path** — final `CLOSED` state persisted to `metaTable`.
- **`UNASSIGN`:** procedure completed, record deleted from `procStore`.
- **`MOVE`/`REOPEN`:** procedure advances to `GET_ASSIGN_CANDIDATE` for re-open.

**Pre:** procedure at `REPORT_SUCCEED` with `transitionCode = CLOSED`.
**Post:** `metaTable` updated, procedure completed or advanced.

> *Source:* RRPB persist-to-meta phase for close path.

```tla
TRSPPersistToMetaClose(r) ==
```

Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `UNASSIGN`, `MOVE`, or `REOPEN`.

```tla
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
```

Procedure must be at the report-succeed (persist-to-meta) step.

```tla
  /\ regionState[r].procStep = "REPORT_SUCCEED"
```

Transition code must be `CLOSED`.

```tla
  /\ procStore[r].transitionCode = "CLOSED"
  /\ \/ \* --- Meta unavailable: suspend or block ---
        /\ ~MetaIsAvailable
```

Region is not already suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not already blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Branch on meta-write blocking mode.

```tla
        /\ IF UseBlockOnMetaWrite = FALSE
```

Async: suspend procedure, release PEWorker thread.

```tla
           THEN /\ suspendedOnMeta' = suspendedOnMeta \cup { r }
                /\ UNCHANGED << availableWorkers, blockedOnMeta >>
```

Sync: block PEWorker thread on meta write.

```tla
           ELSE /\ blockedOnMeta' = blockedOnMeta \cup { r }
                /\ availableWorkers' = availableWorkers - 1
                /\ UNCHANGED suspendedOnMeta
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              regionState,
              metaTable,
              procStore
           >>
     \/ \* --- Meta available: persist close to meta ---
        /\ MetaIsAvailable
```

Region is not suspended waiting for meta.

```tla
        /\ r \notin suspendedOnMeta
```

Region is not blocking a PEWorker on meta.

```tla
        /\ r \notin blockedOnMeta
```

Persist `CLOSED` to `metaTable`.

```tla
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "CLOSED", location |-> NoServer ]]
```

Branch on procedure type.

```tla
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
                     ![r].procStep =
                     "GET_ASSIGN_CANDIDATE",
                     ![r].targetServer =
                     NoServer,
                     ![r].retries =
                     0]
                /\ procStore' =
                     [procStore EXCEPT
                     ![r] =
                     NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
        /\ UNCHANGED << scpVars,
              rpcVars,
              serverVars,
              rsVars,
              masterVars,
              peVars
           >>

```

### `TRSPConfirmClosedCrash(r)`

**Crash during close** (`ABNORMALLY_CLOSED`) — the target server crashed while the close was in flight. The procedure self-recovers with type-preserving crash recovery (matching Java `confirmClosed()` L379-389): the original `procType` is preserved instead of unconditionally converting to `ASSIGN`. For `UNASSIGN`, this triggers two-phase recovery (reopen then re-close) in `TRSPPersistToMetaOpen`.

**Pre:** `UNASSIGN`/`MOVE`/`REOPEN` at `CONFIRM_CLOSED`, region `ABNORMALLY_CLOSED`.
**Post:** procedure at `GET_ASSIGN_CANDIDATE` with preserved type, stale dispatched commands cleared.

> *Source:* `TRSP.confirmClosed()` detects `ABNORMALLY_CLOSED`, `RegionRemoteProcedureBase.serverCrashed()`.

```tla
TRSPConfirmClosedCrash(r) ==
```

Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

Procedure type must be `UNASSIGN`, `MOVE`, or `REOPEN`.

```tla
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
```

Procedure must be waiting for the close confirmation.

```tla
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
```

Region was marked `ABNORMALLY_CLOSED` by SCP (server crashed).

```tla
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
```

Type-preserving crash recovery: preserve the original `procType` instead of unconditionally converting to `ASSIGN`. For `UNASSIGN`, this triggers two-phase recovery (reopen then re-close) in `TRSPPersistToMetaOpen`.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       NoServer,
       ![r].retries =
       0]
```

Clear stale dispatched commands for this region.

```tla
  /\ dispatchedOps' =
       [s \in Servers |-> {cmd \in dispatchedOps[s]: cmd.region # r}
       ]
```

Update persisted procedure: preserve type.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
```

`ServerStateNode` tracking.

```tla
  /\ LET loc == regionState[r].location
     IN serverRegions' =
           IF loc # NoServer
           THEN [serverRegions EXCEPT ![loc] = @ \ { r }]
           ELSE serverRegions
  /\ UNCHANGED << scpVars,
        rsVars,
        masterVars,
        peVars,
        metaTable,
        pendingReports,
        serverState
     >>

```

```tla
---------------------------------------------------------------------------
```

## Crash Recovery

### `TRSPServerCrashed(r)`

A procedure's region has been crashed (`ABNORMALLY_CLOSED`). The procedure preserves its type and advances to `GET_ASSIGN_CANDIDATE`, modeling the TRSP `serverCrashed()` callback plus the `closeRegion()` recovery branch: when `closeRegion()` finds the region is not in a closeable state, it sets `forceNewPlan=true`, clears the location, and jumps to `GET_ASSIGN_CANDIDATE`.

**Type-preserving** (matching Java `serverCrashed()`):
- **UNASSIGN:** preserves type for two-phase recovery (reopen then re-close). `TRSPPersistToMetaOpen` detects `UNASSIGN` and continues to `CLOSE` instead of completing.
- **MOVE/REOPEN/ASSIGN:** preserves existing type; behaviorally equivalent to the old `ASSIGN` conversion since the remaining steps are identical.

This resolves the deadlock where an `UNASSIGN` procedure is stranded on an `ABNORMALLY_CLOSED` region with no way to progress. The full SCP machinery (`ServerCrashProcedure`) orchestrates *when* this callback fires; this action models *what* happens to the TRSP.

**Pre:** region has an active procedure (any type) AND region state is `ABNORMALLY_CLOSED`.
**Post:** procedure at `GET_ASSIGN_CANDIDATE` with preserved type, cleared `targetServer` and reset retries. Stale `CLOSE` commands for this region cleared from all servers' `dispatchedOps`. Stale `CLOSED` reports for this region dropped from `pendingReports`.

> *Source:* `TRSP.serverCrashed()` delegates to `RegionRemoteProcedureBase.serverCrashed()` if a sub-procedure is in flight, or directly calls `AM.regionClosedAbnormally()`; `TRSP.closeRegion()` else-branch sets `forceNewPlan` and advances to `GET_ASSIGN_CANDIDATE` when the region is not in a closeable state.

```tla
TRSPServerCrashed(r) ==
```

Region has an active procedure, is `ABNORMALLY_CLOSED`, and the procedure has not already been converted to `GET_ASSIGN_CANDIDATE` by `SCPAssignRegion` Path A. Master must be alive for procedure execution.

```tla
  /\ masterAlive = TRUE
```

A PEWorker thread must be available to execute this procedure step.

```tla
  /\ availableWorkers > 0
```

Region must exist (have an assigned keyspace).

```tla
  /\ regionKeyRange[r] # NoRange
```

A procedure must be attached to this region.

```tla
  /\ regionState[r].procType # "NONE"
```

Region was marked `ABNORMALLY_CLOSED` by SCP (server crashed).

```tla
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
```

Procedure must not already be at `GET_ASSIGN_CANDIDATE` (already converted).

```tla
  /\ regionState[r].procStep # "GET_ASSIGN_CANDIDATE"
```

`ABNORMALLY_CLOSED` is only set by SCP (`AM.regionClosedAbnormally()`), so at least one SCP must have reached the `ASSIGN` phase.

```tla
  /\ \E s \in Servers: scpState[s] \in { "ASSIGN", "DONE" }
```

Type-preserving crash recovery: preserve the procedure type, advance to `GET_ASSIGN_CANDIDATE`. Matches Java `serverCrashed()` which does not change `TransitionType`.

```tla
  /\ regionState' =
       [regionState EXCEPT
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       NoServer,
       ![r].retries =
       0]
```

Clear any `CLOSE` for `r` from `dispatchedOps`. The prior procedure (`UNASSIGN` or `MOVE`) dispatched it; we are abandoning that and reopening instead. Without this, `RSClose` can consume the stale `CLOSE` after we reassign, violating `RSMasterAgreement` (`OPEN` in `regionState` but `r` not in `rsOnlineRegions`).

```tla
  /\ dispatchedOps' =
       [t \in Servers |->
         {cmd \in dispatchedOps[t]: cmd.region # r \/ cmd.type # "CLOSE"}
       ]
```

Drop `CLOSED` reports for `r`. They are from the abandoned close; consuming them after we reopen would set `CLOSED` while `r` is in `rsOnlineRegions` (from `RSOpen`), violating `RSMasterAgreementConverse`.

```tla
  /\ pendingReports' =
       {pr \in pendingReports: pr.region # r \/ pr.code # "CLOSED"}
```

META, RS-side state, and server liveness unchanged. Update persisted procedure: preserve type.

```tla
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord(regionState[r].procType, "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  /\ UNCHANGED << scpVars, serverVars, rsVars, masterVars, metaTable >>
```

Clear `r` from suspended/blocked sets if it was waiting on meta.

```tla
  /\ suspendedOnMeta' = suspendedOnMeta \ { r }
  /\ blockedOnMeta' = blockedOnMeta \ { r }
  /\ availableWorkers' =
       IF r \in blockedOnMeta THEN availableWorkers + 1 ELSE availableWorkers

```

```tla
---------------------------------------------------------------------------
```


## PEWorker Meta-Resume

### `ResumeFromMeta(r)`

Resume a procedure that was suspended or blocked on a meta write. When `MetaIsAvailable` becomes `TRUE` (SCP completes meta assignment), suspended procedures are woken and blocked workers are unblocked.

**Pre:** `masterAlive = TRUE`, `r` in `suspendedOnMeta` or `blockedOnMeta`, `MetaIsAvailable`.
**Post:** `r` removed from `suspendedOnMeta` or `blockedOnMeta`; `availableWorkers` incremented if blocked.

> *Source:* `ProcedureFutureUtil.wakeIfSuspended()` for async case; `Table.put()` returning for sync case.

```tla
ResumeFromMeta(r) ==
  /\ masterAlive = TRUE
  /\ MetaIsAvailable
  /\ \/ \* --- Async resume: procedure was suspended ---
```

Region must be in the suspended set.

```tla
        /\ r \in suspendedOnMeta
```

Remove `r` from the suspended set (waking it up).

```tla
        /\ suspendedOnMeta' = suspendedOnMeta \ { r }
```

No PEWorker was consumed; no changes to workers or blocked set.

```tla
        /\ UNCHANGED << availableWorkers, blockedOnMeta >>
     \/ \* --- Sync resume: PEWorker was blocked ---
```

Region must be in the blocked set.

```tla
        /\ r \in blockedOnMeta
```

Remove `r` from the blocked set (waking it up).

```tla
        /\ blockedOnMeta' = blockedOnMeta \ { r }
```

Recover the PEWorker thread that was blocked.

```tla
        /\ availableWorkers' = availableWorkers + 1
```

No changes to the suspended set.

```tla
        /\ UNCHANGED suspendedOnMeta
```

No state changes — just remove from suspended/blocked set.

```tla
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        regionState,
        metaTable,
        procStore
     >>

```

```tla
============================================================================
```
