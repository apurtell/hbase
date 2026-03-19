# TLA+ Model of the HBase AssignmentManager

## 1. Summary

This document presents a detailed analysis of the HBase AssignmentManager system and a
step-by-step plan for modeling it in TLA+. The AssignmentManager is the component of the
HBase Master responsible for assigning regions to RegionServers, handling transitions
(open, close, move, split, merge), and recovering from failures (RegionServer crashes,
Master failover). The system involves multiple concurrent actors, distributed state,
crash recovery via a write-ahead procedure store, and complex state machines — making it
an excellent candidate for formal specification in TLA+.

The plan is organized as an iterative series of increasingly detailed TLA+ modules,
starting with the core region state machine and building outward to encompass
multi-region operations, crash recovery, and liveness properties.

---

## 2. Architecture Overview

### 2.1 Actors

| Actor | Role |
|-------|------|
| **Master (HMaster)** | Coordinates all region assignments; runs ProcedureExecutor |
| **AssignmentManager** | Master subsystem that tracks region states, creates/manages procedures |
| **ProcedureExecutor** | Executes procedures with crash recovery via WAL-based store |
| **RegionServer (RS)** | Hosts regions; opens/closes regions on master's request |
| **hbase:meta** | System table storing authoritative region→server mapping |
| **ProcedureStore (WAL)** | Write-ahead log for procedure state; enables master crash recovery |
| **ZooKeeper** | Detects RS crashes (ephemeral nodes); mirrors meta location |

### 2.2 Key Data Structures

| Structure | Location | Description |
|-----------|----------|-------------|
| `RegionStateNode` | Master (in-memory) | Per-region: current `State`, `regionLocation`, attached `TransitRegionStateProcedure`, lock |
| `ServerStateNode` | Master (in-memory) | Per-server: state (ONLINE/CRASHED), set of hosted regions |
| `regionsInTransitionInRS` | RegionServer (in-memory) | `Map<byte[], Boolean>` — `TRUE`=opening, `FALSE`=closing |
| `onlineRegions` | RegionServer (in-memory) | `Map<String, HRegion>` — currently serving regions |
| `hbase:meta` | Distributed (table) | Persistent region state and location |
| `ProcedureStore` | Master (WAL on HDFS) | Serialized procedure state for crash recovery |

### 2.3 Communication Channels

| Channel | Direction | Mechanism |
|---------|-----------|-----------|
| Assignment commands | Master → RS | RPC: `executeProcedures()` dispatched by `RSProcedureDispatcher` |
| State reports | RS → Master | RPC: `reportRegionStateTransition(TransitionCode)` |
| Heartbeats | RS → Master | RPC: `regionServerReport()` with load metrics and online regions |
| Crash detection | ZK → Master | ZK watcher on `/hbase/rs` ephemeral nodes |

---

## 3. State Machines in the Implementation

### 3.1 Region State (RegionState.State)

The fundamental per-region state, defined in `RegionState.java`:

```
OFFLINE          — Region not assigned to any server
OPENING          — Server has begun opening but not yet done
OPEN             — Server opened region and updated meta
CLOSING          — Server has begun closing but not yet done
CLOSED           — Server closed region and updated meta
SPLITTING        — Server started split
SPLIT            — Server completed split (terminal for parent)
MERGING          — Server started merge
MERGED           — Server completed merge (terminal for targets)
SPLITTING_NEW    — Daughter region being created by split
MERGING_NEW      — Merged region being created by merge
FAILED_OPEN      — Open failed, no more retries
FAILED_CLOSE     — Close failed, no more retries (dead state: no code path enters it; RS aborts on close failure)
ABNORMALLY_CLOSED — Closed due to RS crash
```

**Core transition graph (assign/unassign/move):**

```
                    ┌─────────────────────────────────────────────────┐
                    │                                                 │
                    v                                                 │
  OFFLINE ──► OPENING ──► OPEN ──► CLOSING ──► CLOSED ──► OFFLINE     │
                 │                    │           │                   │
                 v                    │           v                   │
            FAILED_OPEN               │      FAILED_CLOSE (dead)      │
                 │                    │                               │
                 v                    v                               │
           (retry/abort)      ABNORMALLY_CLOSED ──────────────────────┘
```

### 3.2 TransitRegionStateProcedure (TRSP)

The master-side state machine for assign/unassign/move/reopen operations. This is the
central procedure that drives region transitions.

**States** (from `MasterProcedure.proto`):

```
REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE   — Select target server
REGION_STATE_TRANSITION_OPEN                   — Dispatch open to RS
REGION_STATE_TRANSITION_CONFIRM_OPENED         — Wait for RS OPENED report
REGION_STATE_TRANSITION_CLOSE                  — Dispatch close to RS
REGION_STATE_TRANSITION_CONFIRM_CLOSED         — Wait for RS CLOSED report
```

**Flows by TransitionType:**

| Type | Flow |
|------|------|
| ASSIGN   | `GET_ASSIGN_CANDIDATE → OPEN → CONFIRM_OPENED` |
| UNASSIGN | `CLOSE → CONFIRM_CLOSED` |
| MOVE     | `CLOSE → CONFIRM_CLOSED → GET_ASSIGN_CANDIDATE → OPEN → CONFIRM_OPENED` |
| REOPEN   | `CLOSE → CONFIRM_CLOSED → GET_ASSIGN_CANDIDATE → OPEN → CONFIRM_OPENED` |

### 3.3 RegionRemoteProcedureBase (Open/Close child procedures)

The child procedures of TRSP that handle the actual RPC dispatch:

```
REGION_REMOTE_PROCEDURE_DISPATCH        — Send RPC to RS
REGION_REMOTE_PROCEDURE_REPORT_SUCCEED  — RS reported success
REGION_REMOTE_PROCEDURE_DISPATCH_FAIL   — RPC dispatch failed
REGION_REMOTE_PROCEDURE_SERVER_CRASH    — Target RS crashed
```

### 3.4 ServerCrashProcedure (SCP)

Handles RS crash recovery:

```
SERVER_CRASH_START                      — Determine if carrying meta, get regions
SERVER_CRASH_SPLIT_META_LOGS            — Split meta WALs if needed
SERVER_CRASH_ASSIGN_META                — Reassign meta region if needed
SERVER_CRASH_GET_REGIONS                — Get list of regions on crashed server
SERVER_CRASH_SPLIT_LOGS                 — Split server WALs  ← FENCING POINT
SERVER_CRASH_ASSIGN                     — Create child TRSPs to reassign regions
SERVER_CRASH_CLAIM_REPLICATION_QUEUES   — Claim replication queues
SERVER_CRASH_FINISH                     — Cleanup
```

`SERVER_CRASH_SPLIT_LOGS` is the **fencing point**: the master initiates
HDFS lease recovery on the crashed RS's WALs, revoking its write leases.
After this step, the zombie RS (if still alive after a GC pause or
network partition) cannot write to its WALs — any write attempt fails
with an HDFS lease exception, triggering RS self-abort.  This ordering
guarantee — WALs are fenced before regions are reassigned to new servers
(`SERVER_CRASH_ASSIGN`) — is what prevents write-side split-brain.
Modeled as `SCPFenceWALs` in Iteration 14.

### 3.5 SplitTableRegionProcedure

```
PREPARE → PRE_OPERATION → CLOSE_PARENT → CHECK_CLOSED →
CREATE_DAUGHTERS → WRITE_MAX_SEQ_ID → PRE_BEFORE_META →
UPDATE_META (PONR) → PRE_AFTER_META → OPEN_CHILDREN → POST_OPERATION
```

### 3.6 MergeTableRegionsProcedure

```
PREPARE → PRE_OPERATION → PRE_MERGE → CLOSE_REGIONS → CHECK_CLOSED →
CREATE_MERGED → WRITE_MAX_SEQ_ID → PRE_MERGE_COMMIT →
UPDATE_META (PONR) → POST_MERGE_COMMIT → OPEN_MERGED → POST_OPERATION
```

### 3.7 RegionServer-Side State Model

The RS maintains a simpler model via `regionsInTransitionInRS`:

```
∅ (not tracked)  — Region not in transition
TRUE             — Region is being opened
FALSE            — Region is being closed
```

Combined with `onlineRegions` membership, the RS-side region lifecycle is:

```
(not present) ──[receive open]──► inTransition(TRUE) ──[open done]──► online
    online    ──[receive close]──► inTransition(FALSE) ──[close done]──► (not present)
```

**Conflict handling:**
- Open received while closing (`FALSE`): retry with backoff
- Close received while opening (`TRUE`): cancel open, transition to close
- Duplicate open/close: ignored

---

## 4. Key Invariants and Properties to Verify

### 4.1 Safety Properties

1. **Single Assignment**: A region is OPEN on at most one RegionServer at any time.
   - `∀ r ∈ Regions: |{s ∈ Servers : regionState[r].location = s ∧ regionState[r].state = OPEN}| ≤ 1`

2. **State Consistency**: Region state transitions follow the valid transition graph.
   - No transition from OPEN directly to OPENING without going through CLOSING → CLOSED first.

3. **Meta Consistency**: The persistent state in `hbase:meta` eventually matches in-memory state.
   - After a procedure completes, `meta[r].state = inMemory[r].state`.

4. **No Lost Regions**: After SCP completes for a crashed server, every
   region that was in the SCP's region snapshot must either have a
   procedure attached (recovery is in progress) or be OPEN on some server.
   A region left in ABNORMALLY_CLOSED (or any non-OPEN state) with no
   procedure after SCP is done is "lost"
   — it will never be reassigned without manual intervention.
   - Liveness form: if a server crashes and a region was on that
     server, eventually that region reaches OPEN again.
   - Safety form (invariant, checked at each state): for every server
     and every region, once SCP reaches DONE, if the region has no
     location and is not OPEN, then it must have a procedure attached
     (i.e. recovery is still in progress).
   - This invariant PASSES because the model processes every region
     in the SCP snapshot unconditionally.  Code analysis at each
     iteration compares this correct protocol behavior against the
     actual implementation to identify gaps (e.g., the
     `isMatchingRegionLocation()` check in SCP.java L540-542 is a
     known source of HBASE-24293 and HBASE-21623, where the
     `scpRegions` snapshot goes stale between `GET_REGIONS` and
     `ASSIGN`).

5. **Procedure Atomicity**: Each procedure either completes fully or is fully rolled back.
   - For pre-PONR states, rollback is possible. After PONR (e.g., meta update in split/merge), the procedure must complete.

6. **Lock Exclusivity**: At most one `TransitRegionStateProcedure` is attached to a `RegionStateNode` at any time.

7. **No Double Write** (Iteration 13): A region is never *writable* on
   two servers simultaneously. A region is writable on server `s` when
   `r ∈ rsOnlineRegions[s] ∧ walFenced[s] = FALSE`. This is the
   fundamental safety property for the zombie RS scenario: after the
   master revokes a crashed RS's WAL leases (`walFenced[s] = TRUE`),
   the zombie RS can no longer write, so even though it may still be
   in `rsOnlineRegions` (serving stale reads), write-side split-brain
   is prevented.
   - `∀ r ∈ Regions: |{s ∈ Servers : r ∈ rsOnlineRegions[s] ∧ walFenced[s] = FALSE}| ≤ 1`

8. **No Double Assignment (refined)** (Iteration 13): The original
   `NoDoubleAssignment` (`Cardinality({s : r ∈ rsOnlineRegions[s]}) ≤ 1`)
   is temporarily violated during the zombie window (between
   `MasterDetectCrash` and `RSAbort`).  After Iteration 13 this
   invariant is relaxed or demoted to a liveness-adjacent check:
   eventually restored after all zombie RSs have aborted.
   `NoDoubleWrite` replaces it as the primary safety property.

9. **RS-Master Agreement Converse (refined)** (Iteration 13): The
   current `RSMasterAgreementConverse` (`∀ s, r ∈ rsOnlineRegions[s]:
   regionState[r].location = s`) is violated during the zombie window
   because the master has cleared the crashed RS's location.  Refined
   to exempt servers with `serverState[s] = "CRASHED"` — the master no
   longer considers them authoritative.

10. **Zombie Fencing Order** (Iteration 14): SCP does not reassign
    regions (`SCPAssign`) until WAL leases have been revoked
    (`SCPFenceWALs` completed).  Verified by construction (SCP state
    machine ordering: `FENCE_WALS` precedes `ASSIGN`) but stated
    explicitly for documentation and as a safety net if SCP states are
    later decomposed further.

11. **Table Lock Exclusivity** (Iteration 32): Table-level exclusive
     operations (CREATE, DELETE, TRUNCATE) and shared operations (SPLIT,
     MERGE) respect the `MasterProcedureScheduler`'s table-queue locking:
     an exclusive-type `parentProc` on any region of table `t` blocks all
     other table-level procedures and all split/merge on that table;
     concurrent splits/merges on different regions of the same table are
     permitted (shared compatibility).  Modeled via guard predicates
     `TableLockFree(t)` and `NoTableExclusiveLock(r)` over
     `parentProc[r].type`.

### 4.2 Liveness Properties

1. **Assignment Progress**: A region in OFFLINE state is eventually assigned (assuming servers are available).
   - `□(regionState[r].state = OFFLINE ⇒ ◇ regionState[r].state = OPEN)` (under fairness)

2. **Crash Recovery Completion**: A `ServerCrashProcedure` eventually completes.
   - `□(scp_started(s) ⇒ ◇ scp_finished(s))`

3. **Move Completion**: A move operation eventually completes (region ends up OPEN somewhere).

4. **No Stuck Transitions**: A region does not remain in OPENING or CLOSING indefinitely.

### 4.3 Properties Specific to Interesting Scenarios

1. **Double-crash**: RS1 crashes while SCP for RS0 is reassigning regions to RS1.
2. **Master failover during TRSP**: Master crashes between procedure store write and meta update.
3. **Split during move**: A split is requested for a region that is currently being moved.
4. **Concurrent SCP and balance**: The balancer attempts to move a region from a server that is being processed by SCP.

---

## 5. TLA+ Model Design

### 5.1 Module Structure

The specification is built as a single monolithic file, `AssignmentManager.tla`,
iteratively extended per the iteration plan below. All TRSP, crash recovery,
and (future) RPC/RS-side logic lives in this one module. Decomposition into
separate modules (e.g., TRSP.tla, ServerCrash.tla, Network.tla) may be
considered if the file grows unwieldy (1500+ lines) or a component has
genuinely independent state that composes cleanly, but is not planned at
this time.

```
AssignmentManager.tla        (monolithic spec, iteratively built)
AssignmentManager.cfg        (primary TLC config — fast exhaustive, every iteration)
AssignmentManager-sim.cfg    (simulation TLC config — deep random traces, every iteration)
AssignmentManager-full.cfg   (full TLC config — exhaustive 3r/3s, ad hoc on-demand)
```

### 5.2 Model Verification

Two TLC configurations form the standard verification pair, run at
every iteration.  A third is reserved for ad hoc on-demand checks.

| Config | Model | Mode | Role | Time |
|--------|-------|------|------|------|
| `AssignmentManager.cfg` | 3r/2s, MaxRetries=1 | Exhaustive | Every iteration | 1 hr|
| `AssignmentManager-sim.cfg` | 9r/3s, MaxRetries=2 | Simulation | Every iteration | 15 min |
| `AssignmentManager-sim.cfg` | (same) | Simulation | Post-iteration | 1 hr |
| `AssignmentManager-sim.cfg` | (same) | Simulation | Post-phase | 4 hr |
| `AssignmentManager-liveness.cfg` | 3r/2s, MaxRetries=1 | Exhaustive | Ad hoc | 1 hr |

### 5.3 Abstraction Decisions

The following table documents what is modeled concretely vs. abstracted:

| Aspect | Modeling Decision | Rationale |
|--------|-------------------|-----------|
| Region state machine | **Concrete** | Core of the model; exact states and transitions |
| TRSP state machine | **Concrete** | The heart of assignment logic |
| RegionRemoteProcedure (Open/Close) | **Merged into TRSP** | Simplify by treating open/close dispatch as atomic TRSP actions |
| RS open/close execution | **Concrete** | Models the RS-side lifecycle and failure modes |
| ProcedureExecutor | **Abstract** (counting semaphore, Iter 19) | Model execute/suspend/resume/crash-recover; counting-semaphore model of PEWorker thread pool for exhaustion analysis |
| ProcedureStore (WAL) | **Abstract** | Model as a persistent set of procedure states; no WAL rolling details |
| hbase:meta | **Abstract** | Model as a function `Region → (State, Server)` |
| ZooKeeper crash detection | **Abstract** | Model as non-deterministic crash detection with delay |
| Network/RPC | **Abstract** | Model as unreliable async message channels (can lose, reorder, duplicate) |
| RegionStateNode locking | **Removed** | Per-region `locked[r]` was always FALSE at rest (acquired+released within each atomic step). Mutual exclusion enforced by `procType ≠ NONE` guards instead. |
| ServerStateNode locking | **Abstract** | Modeled as `serverState` ONLINE/CRASHED flag; read/write lock semantics are implicit in action guards |
| Load balancer | **Abstract** | Non-deterministic choice of move targets |
| REOPEN vs MOVE | **Concrete** | `TRSPCreateReopen` pins `assignCandidate` to the region's current server; `TRSPCreateMove` forces a new plan. Separate `REOPEN` ProcType added. |
| SCP carryingMeta path | **Concrete** | `carryingMeta` variable, `SCPAssignMeta` action (`ASSIGN_META` → `GET_REGIONS`), all non-meta SCP steps gated on `∀ t: scpState[t] ≠ "ASSIGN_META"` (`waitMetaLoaded`). `MetaAvailableForRecovery` invariant. |
| Split/Merge procedures | **Concrete** | Keyspace-aware model: `regionKeyRange`, `MaxKey`, `DeployedRegions`, `Adjacent` predicate, `KeyspaceCoverage`/`SplitMergeMutualExclusion` invariants, `RequestSplit`/`RequestMerge` initiation. `DeployedRegions ⊆ Regions` are the initially deployed table regions; `Regions \ DeployedRegions` are unused identifiers available for split/merge to materialize new regions. See C.6, C.11. |
| ServerCrashProcedure | **Concrete** | Critical failure recovery path |
| WAL lease revocation (fencing) | **Abstract** | Modeled as per-server Boolean (`walFenced`); fencing property only, no HDFS lease or log-splitting details |
| RS crash / zombie window | **Concrete** | Decomposed into non-atomic `MasterDetectCrash` + `RSAbort` to expose the zombie RS window |
| RS epoch / ServerName | **Omitted** | Not needed: `serverState` ONLINE/CRASHED flag (Iter 10) plus atomic crash/restart provides equivalent fencing without an explicit epoch counter. |
| `isMatchingRegionLocation()` in SCP | **Concrete** | `SCPAssignRegion` models the implementation's `isMatchingRegionLocation()` check (SCP.java L498-500, L529-538): regions whose location changed since `SCPGetRegions` are skipped — matching the implementation behavior that is a known source of bugs (HBASE-24293, HBASE-21623) and may expose `NoLostRegions` violations confirming those bugs. |
| Table identity | **Concrete** (Iter 32) | Per-region `regionTable[r]` tracks which table each region belongs to. `Tables` constant, `NoTable` sentinel. Daughters inherit parent's table on split; merged region inherits on merge. Freed identifiers get `NoTable`. |
| Exclusive table locks | **Abstract** (Iter 32) | Modeled via guard predicates over `parentProc[r].type` — `TableLockFree(t)` for exclusive ops, `NoTableExclusiveLock(r)` for shared ops. No separate lock variable; the scheduler's `TableQueue` shared/exclusive semantics are captured by type checks. |
| CreateTableProcedure | **Concrete** (Iter 33) | `CreateTablePrepare` → child ASSIGNs → `CreateTableDone`. Consumes unused region identifiers, tiles new table keyspace `[0, MaxKey)`. |
| DeleteTableProcedure | **Concrete** (Iter 34) | `DeleteTablePrepare` → `DeleteTableRemoveMeta` → `DeleteTableDone`. Frees region identifiers. Precondition: table disabled (all regions CLOSED/OFFLINE). |
| TruncateTableProcedure | **Concrete** (Iter 35) | Multi-step: delete old → create new → assign. Crash between delete-meta and create-meta is the key bug. `UseTruncateCrashQuirk` toggle. |
| Coprocessor hooks | **Omitted** | Not relevant to correctness of assignment protocol |
| Replication queues | **Omitted** | Orthogonal concern |
| Table enable/disable | **Deferred** | Not explicitly modeled; create/delete/truncate preconditions enforce the disabled-table requirement via region-state guards |

---

## 6. Getting Started

### Prerequisites

- Cursor/VS Code with the TLA+ extension (`tlaplus.vscode-ide`)
- Java 11+ (**important**: the TLA+ tools jar requires class file version
  55.0; the default `java` on this system is temurin-8, which will fail
  with `UnsupportedClassVersionError`)
- Familiarity with PlusCal (optional, for algorithmic notation before
  translating to TLA+)

### Running TLC via Command Line

The MCP tools handle per-iteration and post-iteration simulation checks.
Use the command line for the full exhaustive config (ad hoc,
user-requested) or extended post-phase simulation runs.

**Exhaustive check** (3r/2s, per-iteration):

```bash
/usr/bin/java -XX:+UseParallelGC \
-cp "$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.22149-universal/tools/tla2tools.jar:$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.22149-universal/tools/CommunityModules-deps.jar" \
tlc2.TLC AssignmentManager.tla -config AssignmentManager.cfg \
-workers auto -cleanup
```

**Post-phase simulation** (4 hours, high-confidence sweep after completing a phase):

```bash
/usr/bin/java -XX:+UseParallelGC \
-cp "$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.22149-universal/tools/tla2tools.jar:$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.22149-universal/tools/CommunityModules-deps.jar" -Dtlc2.TLC.stopAfter=14400 \
tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg \
-simulate -workers auto
```

Run in background (Shell `block_until_ms: 0`) and monitor the terminal
file for progress. Use `-Dtlc2.TLC.stopAfter=N` (seconds) to adjust
the time limit. Standard durations: 900 (per-iteration), 3600
(post-iteration), 14400 (post-phase).

---

## 7. Iterative Development Plan

Each iteration introduces exactly one new concept, produces a spec that
TLC can verify, and is small enough to review and debug in isolation.
Iterations are grouped into phases for readability, but the unit of work
is the individual iteration.

### Phase 1: Master-Side Foundation ✅ COMPLETE

#### Iteration 1 — Region states and valid transitions ✅ COMPLETE

`State` (7 states), `ValidTransition` (10 transitions), per-region
`[state, location]` records, 7 actions, 4 invariants + `TransitionValid`.
TLC: 2,197 states.

#### Iteration 2 — Meta table as persistent state ✅ COMPLETE

Added `metaTable` (persistent `[state, location]` per region), atomic
dual update with `regionState`, `MetaConsistency` invariant. TLC: 2,197
distinct (metaTable is dependent).

#### Iteration 3 — Procedure attachment (per-region mutex) ✅ COMPLETE

Added `procedure` field (`None`/`TRUE`) to `regionState`, lock
acquire/release guards, `LockExclusivity` invariant. TLC: 2,197
distinct.

#### Iteration 4 — TRSP state machine for ASSIGN (master-side only) ✅ COMPLETE

`procedures` variable (function from Nat to records), `nextProcId`,
procedure field changed to Nat. Actions: `TRSPCreate`, `TRSPGetCandidate`,
`TRSPOpen`, `TRSPConfirmOpened`. `ProcedureConsistency` invariant.
`StateConstraint`, `AddProc`/`RemoveProc` helpers. TLC: 829,329 distinct,
~3s.
*Subsequently refactored*: procedure state inlined into `regionState`
records (region-keyed), eliminating the `procedures` map, `nextProcId`
counter, `StateConstraint`, `ProcedureConsistency`, and
`AddProc`/`RemoveProc` helpers. See Iter 12.

#### Iteration 5 — TRSP state machine for UNASSIGN ✅ COMPLETE

Actions: `TRSPCreateUnassign`, `TRSPClose`, `TRSPConfirmClosed`.
`LockExclusivity` strengthened (type-correlated). Deadlock from
`ServerCrash` stranding UNASSIGN resolved by `TRSPServerCrashed`.
TLC: 1,441,599 distinct, ~6s.

---

### Phase 2: RPC Channels and RegionServer Side ✅ COMPLETE

#### Iteration 6 — RPC channels (data structures only) ✅ COMPLETE

`dispatchedOps` (per-server command set), `pendingReports` (report set),
`CommandType`, `ReportCode`, `rpcVars` shorthand. Channels empty
throughout — no actions produce/consume yet. TLC: 1,441,599 distinct
(channels are dependent).

#### Iteration 7 — Master dispatches open command via RPC ✅ COMPLETE

`TRSPDispatchOpen` (renamed), `TRSPConfirmOpened` (requires OPENED
report), `DispatchFail` (RPC failure → retry). Symmetry reduction
(`Permutations`) resolved state explosion. TLC: 39,250 distinct, ~1s.

#### Iteration 8 — RS-side open handler and report ✅ COMPLETE

RS-side variables `rsOnlineRegions`, `rsTransitions`, `rsVars`.
Actions: `RSReceiveOpen`, `RSCompleteOpen`, `RSFailOpen`. `FailOpen`
removed from `Next` (superseded). ASSIGN round-trip complete.
TLC: 5,622,240 distinct, ~67s.
*ubsequently simplified*: `rsTransitions` dropped; `rsOnlineRegions`
alone captures the RS view. `RSReceiveOpen`+`RSCompleteOpen` merged
into atomic `RSOpen` (intermediate RS state is not observable by the
master and produces the same crash-recovery outcome).

#### Iteration 9 — Master dispatches close command and RS close handler ✅ COMPLETE

`TRSPDispatchClose` (renamed), `TRSPConfirmClosed` (requires CLOSED
report), `DispatchFailClose`, `RSReceiveClose`, `RSCompleteClose`.
`RSMasterAgreement` invariant. Both round-trips end-to-end through RS.
TLC: 6,322,817 distinct, ~79s.
*Subsequently simplified*: `RSReceiveClose`+`RSCompleteClose` merged
into atomic `RSClose` (same rationale as `RSOpen` — see Iter 8 note).

#### Iteration 10 — Server liveness, per-server crash, FAILED_OPEN handling ✅ COMPLETE

`serverState` (`ONLINE`/`CRASHED`), `ServerCrashAll(s)` (atomic per-server
crash + RS cleanup), `TRSPHandleFailedOpen`, `DropStaleReport`, `ONLINE`
guards on report-consuming and RS actions. `NoDoubleAssignment`,
`RSMasterAgreementConverse` invariants. Two-tier TLC verification
(2r/2s exhaustive + 3r/3s simulation).
TLC primary: 35,856 distinct, <1s.

---

### Phase 3: MOVE and Failures ✅ COMPLETE

#### Iteration 11 — MOVE transition type ✅ COMPLETE

`TRSPCreateMove(r)`: close-then-open in one procedure. Existing actions
reused via relaxed type guards; `TRSPConfirmClosed` branches (UNASSIGN
removes, MOVE advances). Renamed `NoSplitBrain` → `NoDoubleAssignment`.
TLC primary: 61,151 distinct, ~4s. Full: 85M distinct, ~28min.

#### Iteration 12 — Open failures give-up path and server restart ✅ COMPLETE

`retries`/`MaxRetries` give-up path: `TRSPGiveUpOpen` → FAILED_OPEN at
limit. `ServerRestart(s)` restarts CRASHED servers (WF liveness);
epoch-based stale-report rejection; `CrashConstraint` removed. Procedure
state inlined into `regionState`, dropping `procedures`, `nextProcId`,
`StateConstraint`. RS actions merged; `rsTransitions` dropped.
TLC primary: 307,449 distinct, ~16s.

#### Iteration 13 — Phase 3 close-out: fidelity fixes and fairness ✅ COMPLETE

`TRSPConfirmClosed` retries unconditionally reset to 0 (matching
`retryCounter = null` at TRSP.java L412).  `GoOffline` no longer
writes `metaTable` (RSN.java L132-134); `MetaConsistency` relaxed for
OFFLINE/CLOSED divergence (Appendix D.5).  Comprehensive WF on all
deterministic procedure steps, crash recovery, and RS-side actions; no
fairness on non-deterministic environmental events.  Post-audit fix:
`TRSPServerCrashed` now resets `retries` to 0 (matching
`retryCounter = null` on crash recovery paths, TRSP.java L329/L443).
TLC primary: 35,726 distinct, <1s.

---

### Phase 4: RegionServer Crash and Recovery ✅ COMPLETE

#### Iteration 14 — ServerCrashProcedure with WAL lease fencing ✅ COMPLETE

Replace `ServerCrashAll` with `MasterDetectCrash` + `RSAbort` + SCP
state machine (GET_REGIONS → FENCE_WALS → ASSIGN → DONE).  New vars:
`scpState`, `scpRegions`, `walFenced`.  No `isMatchingRegionLocation`
(Iter 16); SCP processes every region.  Path A (proc attached):
ABNORMALLY_CLOSED, `TRSPServerCrashed` converts.  Path B (no proc):
ABNORMALLY_CLOSED + fresh ASSIGN.  `SCPAssignRegion` clears `r` from
`rsOnlineRegions` on all servers, drops stale OPENED.  `ServerRestart`:
pre `scpState ∈ {DONE,NONE}`; resets SCP vars, `dispatchedOps[s]`,
`rsOnlineRegions[s]`.  `TRSPGetCandidate` guards zombie/walFenced.
`TRSPHandleFailedOpen` prefers OPENED, clears dispatched OPEN.
`TRSPConfirmClosed` skips if `r` reopened.  `TRSPServerCrashed` clears
dispatched CLOSE, drops CLOSED.  `RSOpen` guards `r ∉ rsOnlineRegions[s]`,
`state=OPENING`, `location=s`.  `NoDoubleAssignment` refined (writable
only); `RSMasterAgreement`/`RSMasterAgreementConverse` exempt CRASHED;
new `ZombieFencingOrder`, `NoLostRegions`.
TLC primary: 5,525,325 distinct, ~20s.

#### Iteration 15 — Fidelity improvements: REOPEN type, per-region lock, TRSPGetCandidate guard, carryingMeta SCP ✅ COMPLETE

Four fidelity fixes.  Removed model-specific `walFenced` guard from
`TRSPGetCandidate`; `serverState[s]="ONLINE"` suffices. `"REOPEN"` added
to `ProcType`; `TRSPCreateReopen(r)` pins `assignCandidate` to current
location; 9 action guards updated. Per-region `locked` variable
(`RegionStateNode.lock()`); `locked[r]=FALSE` guard on all 15 region-
mutating actions. `carryingMeta` variable; `MasterDetectCrash` non-
deterministic; `SCPAssignMeta(s)` action (`ASSIGN_META` → `GET_REGIONS`);
SCP actions\ gated on `∀ t: scpState[t] ≠ "ASSIGN_META"`
(`waitMetaLoaded`); new `MetaAvailableForRecovery` invariant.
TLC primary: 74,500,838 distinct, ~19min.

---

#### Iteration 16 — `isMatchingRegionLocation` in SCP (code-analysis grounded) ✅ COMPLETE

`SCPAssignRegion(s, r)` restructured from 2-way IF/THEN/ELSE to 3-way
disjunction modelling `isMatchingRegionLocation()` (SCP.java L498-500,
L529-538): Skip (location changed), Path A (proc attached →
ABNORMALLY_CLOSED, `TRSPServerCrashed` converts), Path B (no proc →
ABNORMALLY_CLOSED + fresh ASSIGN).  Skip path removes `r` from
`scpRegions` with no state change; Path A/B guard requires
`location=s`.  The skip path needs ≥3 servers to fire (with 2r/2s the
only assignment target during SCP is the surviving server).
TLC primary: 1,527,546 distinct, 14s.

#### Iteration 16.5 — Simulation fidelity: race-window and guard audit ✅ COMPLETE

Guard audit to make the `SCPAssignRegion` skip path reachable.
`TRSPCreate`: SCP-active guard added,
`WF` removed (lost regions need manual intervention).
`TRSPConfirmClosed` Path 1 / `TRSPConfirmOpened`: removed
`serverState ONLINE` guard on reports (models race with crash
detection).  `RSOpen`: removed spec-only `regionState` guard.
`UseReopen` BOOLEAN constant added (branch-2.6 REOPEN; default FALSE).
`serverRegions[s]` variable (`[Servers → SUBSET Regions]`): models
`ServerStateNode` tracking independent from `regionState[r].location`.
`TRSPDispatchOpen` only adds to new server (matching `regionOpening()`
which calls `addRegion()` but NOT `removeRegionFromServer()`); r may
appear on two servers' tracking simultaneously during OPENING.
Removal by `TRSPHandleFailedOpen`, `TRSPGiveUpOpen`,
`TRSPConfirmClosed` Paths 1–2, `SCPAssignRegion` Paths A–B,
`ServerRestart` (clear).  `SCPGetRegions` snapshot changed to
`serverRegions[s]` (was location-based filter), matching
`AM.getRegionsOnServer()` reading from `ServerStateNode`.
`NoLostRegions` strengthened: (1) ABNORMALLY_CLOSED with no procedure,
(2) OPENING/CLOSING with `location=None`, no procedure, not in any
SCP snapshot.  TLC 2r/2s: 3,157,489 distinct, 28s, clean.

---

### Phase 5: Procedure Persistence and Master Recovery ✅ COMPLETE

#### Iteration 17 — Procedure store ✅ COMPLETE

`procStore` variable (`[Regions → ProcStoreRecord ∪ {NoneRecord}]`):
models the WALProcedureStore / RegionProcedureStore persistence layer.
Record: `[type, step, targetServer]`; `NoneRecord` when no procedure
persisted.  14 actions actively update procStore: 4 inserts (TRSPCreate,
TRSPCreateUnassign, TRSPCreateMove, TRSPCreateReopen), procedure step
updates (TRSPGetCandidate, TRSPDispatchOpen, TRSPDispatchClose,
TRSPHandleFailedOpen, TRSPConfirmClosed Path 2, TRSPServerCrashed,
SCPAssignRegion Path B), 3 deletes (TRSPConfirmOpened, TRSPGiveUpOpen,
TRSPConfirmClosed Path 1 UNASSIGN).  DispatchFail actions use UNCHANGED
(not persisted, matching `remoteCallFailed()` L139).
`ProcStoreConsistency` invariant: bijection between `procType ≠ NONE`
and `procStore[r] ≠ NoneRecord`.  TLC 2r/2s: 3,465,621 distinct, 31s,
clean.

#### Iteration 17.5 — Cross-variable consistency invariants ✅ COMPLETE

Eight new invariants (no new variables or actions) tightening
cross-variable correlations.  `ProcStepConsistency`: procStep
correlates with region lifecycle state (e.g. CONFIRM_OPENED ⇒
OPENING).  `TargetServerConsistency`: targetServer presence ↔
procStep.  `OpeningImpliesLocation`, `ClosingImpliesLocation`:
mid-transition regions always have a location.
`ServerRegionsTrackLocation`: serverRegions tracks location for
stable regions.  `DispatchCorrespondance`: dispatched commands have
corresponding procedures.  `NoOrphanedProcedures`: OFFLINE +
procedure ⇒ ASSIGN only.  `SCPMonotonicity` (ACTION_CONSTRAINT):
SCP state never moves backward.  Model fix: `SCPAssignRegion`
Path A tightened to atomically convert the existing TRSP (matching
`serverCrashed()` under `RegionStateNode.lock()`);
`TRSPServerCrashed` guard updated to skip already-converted regions.
`ProcStoreConsistency` evolution note: bijection will require
relaxation in Iteration 18 when `MasterCrash` clears in-memory
state but preserves `procStore`; weaken to one-direction implication
with converse holding only when master is alive.
TLC 2r/2s: 3,339,614 distinct, 41s, clean.  Simulation 3r/3s
(300s): 48,540,636 states, 427,311 traces, clean.

#### Iteration 18 — Master crash and recovery ✅ COMPLETE

New variables/constants: `masterAlive` (BOOLEAN), `procStore` (durable
store), `NewProcRecord` constructor, `NoServer`/`NoProcedure`/
`NoTransition` sentinels (renamed from `None`/`NoneRecord`),
`UseRestoreSucceedQuirk`/`UseRSOpenDuplicateQuirk` toggles.  Module
restructure: new `ProcStore.tla` (invariants + `RestoreSucceedState`),
`Master.tla` (extracted `GoOffline`/`MasterDetectCrash`/`MasterCrash`/
`MasterRecover` from `ExternalEvents.tla`, deleted); `RSRestart` →
`RegionServer.tla`.
Two-phase TRSP report processing (models `RegionRemoteProcedureBase`):
Phase 1 (`TRSPReportSucceedOpen`/`Close`) consumes report, updates
in-memory state, persists `transitionCode`; Phase 2
(`TRSPPersistToMetaOpen`/`Close`) writes meta.  FAILED_OPEN faithfully
keeps OPENING during Phase 1.
`MasterCrash` clears all in-memory state; `MasterRecover` rebuilds from
`metaTable`+`procStore` via `RestoreSucceedState` (branches on
`transitionCode`, not type).  Invariant adjustments: `LockExclusivity`
widened for REPORT_SUCCEED window; `MetaConsistency` relaxed for active
procedures; `ProcStepConsistency` allows OPENING at REPORT_SUCCEED;
`ProcStoreConsistency` allows CLOSED for MOVE/REOPEN close-phase.
ZK liveness model: new `ZK.tla` with `zkNode[s] ∈ BOOLEAN` and
`ZKSessionExpire(s)`.  Causal chain: `ZKSessionExpire` → 
`MasterDetectCrash` (guards `zkNode[s]=FALSE`) → SCP.  `RSAbort`
guards on `zkNode[s]=FALSE`; RS actions guard `zkNode[s]=TRUE`.
`MasterRecover` reads `zkNode` for liveness (replaces `isDead`).
`RSRestart` creates fresh ZK node.  `RSMasterAgreement`/Converse
exempt ZK-session-expiry→crash-detect window.
`RestoreSucceedState` FAILED_OPEN location fixed to `NoServer`.
TLC 2r/2s: 17,430,108 distinct, 63,165,534 generated, 20m26s, clean.

---

### Phase 6: PEWorker Pool and Meta-Blocking Semantics

#### Iteration 19 — PEWorker pool and meta-blocking semantics ✅ COMPLETE

New constants (`MaxWorkers`, `UseBlockOnMetaWrite`) in `Types.tla`;
new variables `availableWorkers` (counting semaphore),
`suspendedOnMeta`/`blockedOnMeta` (region sets) with `MetaIsAvailable`
predicate and `peVars` shorthand in `AssignmentManager.tla`; variable
declarations and `UNCHANGED peVars` in all 7 modules.
`availableWorkers > 0` guard on all 22 procedure-step actions (17
TRSP + 5 SCP).  Meta-blocking disjuncts on all 5 meta-writing actions
(`TRSPPersistToMetaOpen`, `TRSPDispatchOpen`, `TRSPDispatchClose`,
`TRSPPersistToMetaClose`, `SCPAssignRegion` Paths A/B); `SCPAssignRegion`
Skip path exempted (no meta write).  `ResumeFromMeta(r)` action wired
into `Next`/`Fairness` clears `suspendedOnMeta` (async) or
`blockedOnMeta` (sync).  Bugfix: `SCPAssignRegion` Paths A/B and
`TRSPServerCrashed` must clear pe-state when resetting procedures.
`NoPEWorkerDeadlock` invariant passes with `UseBlockOnMetaWrite=FALSE`.
`MetaEventuallyAssigned` liveness property added; liveness checking
incompatible with TLC `SYMMETRY`; separate `AssignmentManager-liveness.cfg`
(no symmetry) provided for overnight runs. New precondition
`serverState[regionState[r].targetServer] = "ONLINE"` for `DispatchFail`
and `DispatchFailClose` in `TRSP.tla` matching `RRPB.remoteCallFailed()`.
Added guard `∀ s ∈ Servers: scpState[s] ∈ {"NONE", "DONE"}` to
`GoOffline` in `Master.tla`. `DropStaleReport` in `RegionServer.tla`
guards on `masterAlive = TRUE`.
TLC 2r/2s: 25,959,400 distinct, 90,478,387 generated, 6m08s, clean.

#### Iteration 19.5 — State space reduction ✅ COMPLETE

Removed `locked` variable (per-region write lock always `FALSE` at rest;
`locked[r] = FALSE` guard never prunes TLC behavior since lock is
acquired+released within each atomic step; mutual exclusion already
enforced by `procType ≠ NONE` guards matching `RegionStateNode.setProcedure()`).
Removed from `vars`, `TypeOK`, `Init`, `UNCHANGED` clauses across all 7
modules; removed `locked[r] = FALSE` guards from 16 actions (15 TRSP +
1 SCP); eliminated `procVars` shorthand (replaced with direct `procStore`
references). Variable tuple width 18 → 17. Defaulted `UseReopen = FALSE`
in primary 2r/2s config (`AssignmentManager.cfg`); simulation and liveness
configs retain `UseReopen = TRUE`.
TLC 2r/2s: 12,412,690 distinct, 43,093,199 generated, 2m57s, clean.

---

### Phase 7: Split and Merge ✅ COMPLETE

#### Iteration 20 — Keyspace infrastructure (no split/merge actions) ✅ COMPLETE

New constants `DeployedRegions`, `MaxKey`, `NoRange` in `Types.tla`;
extended `State` with 6 split/merge states, `ValidTransition` with 10
transitions (Appendix C.4), `ProcType` with `SPLIT`/`MERGE`.  New
variable `regionKeyRange` (`[startKey, endKey)` or `NoRange`) with
`RegionExists(r)` and `Adjacent(r1, r2)` predicates.  `Init` tiles
`[0, MaxKey)` across `DeployedRegions`; unused identifiers get
`NoRange`.  Gated 12 existing invariants on `RegionExists(r)`; new
invariants `KeyspaceCoverage` and `SplitMergeMutualExclusion`;
`SplitMergeConstraint` state constraint (all vacuously true).
`Symmetry` now `Permutations(Regions \ DeployedRegions) ∪
Permutations(Servers)`.  `regionKeyRange` declared and `UNCHANGED`
in all 7 modules; `regionKeyRange[r] # NoRange` guard on 17
per-region actions (16 TRSP + `GoOffline`).  Configs: primary 3r/2s,
sim 7r/3s, liveness 4r/2s with new constants/invariants/constraint.
TLC 3r/2s: 24,781,202 distinct, 86,037,209 generated, depth 66, 8m08s, clean.

#### Iteration 21 — Parent-child procedure framework and split forward path ✅ COMPLETE

New `parentProc[r]` variable (`[type: ParentProcType ∪ {"NONE"}, step:
ParentProcStep ∪ {"NONE"}]`) replacing flat `splitStep` model;
`ParentProcType == {"SPLIT"}` (extensible: `"MERGE"` in iteration 23),
`ParentProcStep == {"SPAWNED_CLOSE", "PONR", "SPAWNED_OPEN", "COMPLETING"}`,
`NoParentProc` sentinel, `HasActiveParent(r)` predicate.  `parentProc`
persists across child TRSP lifecycles and survives master crash.  New
`Split.tla` module with 4 actions: `SplitPrepare(r)` (atomically sets
SPLITTING, creates parentProc, spawns child UNASSIGN TRSP — prepare +
addChildProcedure collapsed for state space efficiency),
`SplitResumeAfterClose(r)` (detects child completion, re-attaches SPLIT
lock, advances to PONR), `SplitUpdateMeta(r, dA, dB)` (PONR: picks 2
unused identifiers, materializes daughters with `[startKey, mid)` and
`[mid, endKey)` keyspaces, spawns child ASSIGN TRSPs),
`SplitDone(r)` (daughters OPEN, clears parent keyspace + parentProc).
New/updated invariants: `SplitAtomicity` (pre-PONR, no daughters),
`NoOrphanedDaughters` (SPLITTING_NEW ⇒ ASSIGN), `SplitCompleteness`
(SPLIT + NoRange ⇒ NoParentProc), `SplitMergeConstraint` (≤1 concurrent).
`parentProc[r].type = "NONE"` guard on `TRSPCreate`, `TRSPCreateUnassign`,
`TRSPCreateMove`, `TRSPCreateReopen`, `GoOffline`.  All configs updated
with new constants/invariants/constraint; sim config expanded to 9r/3s
(3 deployed + 6 unused, `MaxKey = 12`); `AtMostOneCarryingMeta` fix;
TLC 3r/2s: 147,814,458 distinct, 527,398,193 generated, depth 83, ~68min,
clean. TLC 9r/3s: 9,015,843 traces, 928,632,272 generated, ~4hrs, clean.

#### Iteration 22 — Split pre-PONR rollback ✅ COMPLETE

New `SplitFail(r)` action in `Split.tla`: non-deterministic pre-PONR
rollback fires at the same precondition as `SplitResumeAfterClose`
(parent `CLOSED`, child `UNASSIGN` complete, `parentProc = [SPLIT,
SPAWNED_CLOSE]`).  Creates a fresh `ASSIGN` TRSP to reopen the parent,
clears `parentProc`, reverts meta from `SPLITTING` to `CLOSED`.  No
daughter cleanup needed pre-PONR (`SplitAtomicity` invariant).  TLC
explores both the success path (`SplitResumeAfterClose`) and the
failure path (`SplitFail`) for every reachable split state. Wired into
`Next` (no WF — failure is non-deterministic).
TLC 3r/2s: 147,814,458 distinct, 527,398,347 generated, depth 83,
~68min, clean.

#### Iteration 23 — Complete merge forward path with rollback  ✅ COMPLETE

`UseMerge` conditional guard (follows `UseReopen` pattern):
`UseMerge ∈ BOOLEAN` constant in `Types.tla`.  All merge actions
gated on `UseMerge = TRUE` in `Next`/`Fairness`.  New `Merge.tla` module —
5 actions (`MergePrepare`, `MergeCheckClosed`, `MergeUpdateMeta`,
`MergeDone`, `MergeFail`) using the parent-child framework with
`parentProc` cross-references (`ref1` = peer target, `ref2` = 
merged region).  `parentProc` extended to 4-field record 
`[type, step,ref1, ref2]` with `NoRegion` sentinel.  Split actions updated:
`SplitUpdateMeta` stores daughters in `ref1`/`ref2`, `SplitDone` reads them
back.  All `parentProc` equality checks converted to field-by-field.
`TRSP.tla`: added `"MERGING"` to `TRSPDispatchClose` state guard.
`AssignmentManager.tla`: +`INSTANCE Merge`, TypeOK extended for MERGE
procType, 11 invariants extended for MERGING/MERGED/MERGING_NEW, 3 new
invariants (`NoOrphanedMergedRegion`, `MergeCompleteness`,
`MergeAtomicity`), 5 merge disjuncts in `Next`, 3 WF entries in
`Fairness`, 3 THEOREMs.  All configs updated (+`NoRegion`, +`UseMerge`,
+3 merge invariants). TLC 3r/2s (UseMerge=FALSE): 147,814,458 distinct,
527,398,347 generated, depth 83, ~71min, clean.

---

### Phase 8: Liveness and Refinement

#### Iteration 24 — Fairness and liveness ✅ COMPLETE

Strong fairness (SF) on RS-side message delivery: upgraded `RSOpen`,
`RSClose` from WF to SF (report delivery is intermittently enabled
across retry cycles); added SF on `RSFailOpen` (previously no fairness).
Two new temporal liveness properties: `OfflineEventuallyOpen` (ASSIGN-
bearing OFFLINE region eventually reaches OPEN; leads-to with
`procType="ASSIGN"` precondition since `TRSPCreate` has no fairness),
`SCPEventuallyDone` (`scpState ∉ {NONE, DONE} ~> scpState = DONE`).
`AssignmentManager.tla`: Fairness (WF→SF, +SF RSFailOpen), 2 property
definitions, 2 THEOREMs.  `AssignmentManager-liveness.cfg`: +2 PROPERTY
entries. TLC 3r/2s (UseMerge=FALSE): 147,814,458 distinct,
527,398,347 generated, depth 83, ~70min, clean.

#### Iteration 25 — TRSPConfirmClosedCrash type-preserving crash recovery

Type-preserving crash recovery (matching Java `confirmClosed()` L379-389
and `serverCrashed()`): `TRSPConfirmClosedCrash` and `TRSPServerCrashed`
now preserve the original `procType` instead of unconditionally converting
to `ASSIGN`.  For `UNASSIGN`, this enables two-phase recovery (reopen →
re-close): `TRSPPersistToMetaOpen` OPENED branch detects
`procType = "UNASSIGN"` and advances to `CLOSE` instead of completing,
modeling `confirmOpened()` L289-301 where `lastState == CONFIRM_CLOSED`
triggers `nextState = CLOSE`.  `TRSP.tla`: 8 actions modified —
type-preserving `TRSPConfirmClosedCrash` and `TRSPServerCrashed`
(removed unconditional `procType = "ASSIGN"` override, preserve
`regionState[r].procType`); widened procType guards on `TRSPGetCandidate`,
`TRSPDispatchOpen`, `TRSPReportSucceedOpen`, `TRSPPersistToMetaOpen`,
`DispatchFail` to include `"UNASSIGN"`; added UNASSIGN continuation
branch in `TRSPPersistToMetaOpen` OPENED (IF/ELSE on procType).
`AssignmentManager.tla`: `LockExclusivity` — added `OPENING`, `OPEN`,
`FAILED_OPEN` to UNASSIGN state set (reachable during reopen phase).
`ProcStore.tla`: `ProcStoreConsistency` — widened UNASSIGN allowed
steps to include `GET_ASSIGN_CANDIDATE`, `OPEN`, `CONFIRM_OPENED`;
widened UNASSIGN allowed transitionCodes to include `OPENED`,
`FAILED_OPEN`.  No changes needed to `ProcStepConsistency`,
`TargetServerConsistency`, `NoOrphanedProcedures`, or `Master.tla`
(existing constraints already accommodate the UNASSIGN reopen path).
TLC 3r/2s: 147,814,458 distinct, 527,675,023 generated, depth 83,
~74min, clean.

#### Iteration 26 — RSCloseNotFound quirk ✅ COMPLETE

Modeled two silent-return paths in `UnassignRegionHandler.process()`
(L94–109 already-transitioning, L111–117 region-not-found) where the RS
consumes a CLOSE command without producing a CLOSED report.  Analogous
to `RSOpenDuplicate` (Iter 18); can cause TRSP deadlock at
`CONFIRM_CLOSED`.  `Types.tla`: +`UseRSCloseNotFoundQuirk ∈ BOOLEAN`
(follows `UseRSOpenDuplicateQuirk` pattern).  `RegionServer.tla`: new
`RSCloseNotFound(s, r)` — guard `UseRSCloseNotFoundQuirk = TRUE`,
server ONLINE, `zkNode[s] = TRUE`, `r ∉ rsOnlineRegions[s]`, CLOSE
command exists; consumes command, produces no report.
`AssignmentManager.tla`: wired into `Next` (after `RSOpenDuplicate`),
`PrintConfig`, `Fairness` comment (no WF — non-deterministic quirk).
All 3 configs: +`UseRSCloseNotFoundQuirk = FALSE`.  With quirk disabled
(default), state space unchanged from Iter 25. TLC 3r/2s:
147,814,458 distinct, 527,675,023 generated, depth 83, ~69min, clean.

#### Iteration 27 — Miscellaneous fidelity improvements ✅ COMPLETE

Two fidelity fixes, no new variables or actions. `SCP.tla`: `SCPDone`
now clears `serverRegions[s]` to `{}`, modeling
`ServerManager.expireServer()` → `RegionStates.removeServer()`
(L679–681) which removes the `ServerStateNode` entirely on SCP
completion, preventing ghost tracking entries.  `UNCHANGED`
clause updated (`serverVars` → `serverState`).  `TRSP.tla`:
`TRSPReportSucceedOpen` added server-name check
`rpt.server = regionState[r].targetServer`, matching the guard
already present in `TRSPReportSucceedClose` (L996) and the Java
`RegionRemoteProcedureBase.reportTransition()` (L208–211) which
validates the server name for both open and close paths; prevents
accepting stale `OPENED` reports from a previous server after
crash+reassign. TLC 3r/2s: 137,680,580 distinct, 488,668,819 generated,
depth 82, ~74min, clean.

#### Iteration 28 — Dispatch-failure server expiration ✅ COMPLETE

Modeled `RSProcedureDispatcher.scheduleForRetry()` →
`ServerManager.expireServer()` code path where repeated dispatch
failures cause the master to expire the target server, triggering
SCP while the RS may still be alive (ZK node present).  `TRSP.tla`:
extended `DispatchFail(r)` and `DispatchFailClose(r)` with a second
disjunct (unconditional).  First disjunct retains existing retry
behavior (server stays ONLINE, TRSP resets).  Second disjunct
atomically: (1) sets `serverState[s] = "CRASHED"` and starts SCP
(non-deterministic `carryingMeta`, same `MasterDetectCrash` pattern),
(2) removes dispatched command from `dispatchedOps[s]`,
(3) leaves the TRSP at `CONFIRM_OPENED`/`CONFIRM_CLOSED` (matching
`remoteCallFailed()` early-return when `isServerOnline()=false`).
TRSP-vs-SCP race explored naturally: SCP's `SCPAssignRegion →
TRSPServerCrashed` drives the region forward while TRSP is in its
dispatch-waiting step. TLC 3r/2s: 368,662,744 distinct,
1,328,348,760 generated, depth 92, ~71min, clean.

#### Iteration 29 — UseUnknownServerQuirk ✅ COMPLETE

`AssignmentManager.checkOnlineRegionsReport()` closes regions on stale
servers without scheduling reassignment, leaving orphans on "Unknown Servers"
indefinitely. Most common production path: RS crashes → DEAD → SCP runs and
processes most regions → new RS starts on same host:port →
`DeadServer.cleanPreviousInstance()` removes old dead entry → any region that
SCP's `isMatchingRegionLocation()` skipped remains pointing at the
now-unknown server → `closeRegionSilently()` closes without TRSP.
Added `UseUnknownServerQuirk ∈ BOOLEAN` (Types.tla) and
`DetectUnknownServer(r)` action (Master.tla). When TRUE: region closed
silently, no TRSP — stuck CLOSED/OFFLINE forever. When FALSE (default):
master creates TRSP(ASSIGN). Wired into Next (no WF — non-deterministic
event). All 3 configs updated. TLC 3r/2s: 368,662,744 distinct,
1,328,348,760 generated, depth 92, clean.

#### Iteration 30 — UseMasterAbortOnMetaWriteQuirk ✅ COMPLETE

`RegionStateStore.updateRegionLocation()` catches `IOException` and calls
`master.abort(msg, e)` — crashing the entire master when meta is temporarily
unavailable (e.g., during SCP for the meta RS).  Added
`UseMasterAbortOnMetaWriteQuirk ∈ BOOLEAN` (Types.tla) with three-way
branch in all four meta-blocking disjuncts (`TRSPDispatchOpen`,
`TRSPPersistToMetaOpen`, `TRSPDispatchClose`, `TRSPPersistToMetaClose`):
when TRUE and `~MetaIsAvailable`, `masterAlive' = FALSE` (master aborts);
when FALSE (default), existing suspend/block per `UseBlockOnMetaWrite`.
All 3 configs updated with `UseMasterAbortOnMetaWriteQuirk = FALSE`.
TLC 3r/2s: 368,662,744 distinct, 1,328,348,760 generated, depth 92, clean.

#### Iteration 31 - UseStaleStateQuirk ✅ COMPLETE

`RegionStateStore.visitMeta()` / `AM.start()` L341-348 calls
`regionStates.createServer(regionLocation)` for every region in meta,
creating `ServerStateNode` entries for dead servers and making them appear
`ONLINE` to subsequent crash-detection — no SCP started, regions never
recovered.  Added `UseStaleStateQuirk ∈ BOOLEAN` (Types.tla).  Modified
`MasterRecover` `serverState'` and `scpState'` reconstruction (Master.tla):
when TRUE and `zkNode[s]=FALSE`, server marked `ONLINE` (no SCP) if
`∃ r ∈ Regions: metaTable[r].location = s`; when FALSE (default), correct
`zkNode`-based liveness.  All 3 configs updated. TLC 3r/2s: 368,662,744
distinct, 1,328,348,760 generated, depth 92, ~64min, clean.

---

### Phase 9: Table-Level Procedures

#### Iteration 32 — Table identity infrastructure and exclusive table lock guards ✅ COMPLETE

Per-region table identity tracking and exclusive table-level lock guards —
infrastructure for table-level procedures.  Added `Tables`, `NoTable`,
`TableExclusiveType = {"CREATE","DELETE","TRUNCATE"}` (Types.tla).  New
20th variable `regionTable ∈ [Regions → Tables ∪ {NoTable}]`; guard
predicates `NoTableExclusiveLock(r)`, `TableLockFree(t)`; invariant
`TableLockExclusivity` (31st).  Split/Merge: `NoTableExclusiveLock` guard
on Prepare, daughters/merged inherit table, Done clears to `NoTable`.
All modules: `regionTable` in VARIABLE/UNCHANGED.  TLC 3r/2s: 368,662,744
distinct, 1,328,348,760 generated, depth 92, ~71min, clean.

#### Iteration 33 — CreateTableProcedure ✅ COMPLETE

First table-level procedure.  Source: `CreateTableProcedure.java`. New
module `Create.tla`: `CreateTablePrepare(t, r)` picks unused `r`, assigns
`[0, MaxKey)`, sets `regionTable[r] = t`, writes meta, spawns ASSIGN TRSP,
`parentProc = [CREATE, SPAWNED_OPEN]`; `CreateTableDone(t)` clears when
OPEN.  `KeyspaceCoverage` rewritten per-table.  `UseCreate` constant.
TLC 3r/2s: same counts, clean.

#### Iteration 34 — DeleteTableProcedure ✅ COMPLETE

Second table-level procedure.  Source: `DeleteTableProcedure.java`. New
module `Delete.tla`: `DeleteTablePrepare(t)` locks all regions of disabled
table `t`, `parentProc = [DELETE, COMPLETING]`; `DeleteTableDone(t)`
atomically clears meta, frees identifiers, resets state.  No child TRSPs.
Invariant `DeleteTableAtomicity` (32nd).  `UseDelete` constant.  TLC
3r/2s: same counts, clean.

#### Iteration 35 — TruncateTableProcedure ✅ COMPLETE

Third table-level procedure.  Source: `TruncateTableProcedure.java`. New
module `Truncate.tla` with 4 actions: `TruncatePrepare(t)` locks all
regions of disabled table `t`, `parentProc = [TRUNCATE, COMPLETING]`;
`TruncateDeleteMeta(t)` frees identifiers, advances to PONR (availability-
vulnerable point); `TruncateCreateMeta(t, r)` picks unused `r`, assigns
`[0, MaxKey)`, spawns ASSIGN TRSP, clears floating PONR parentProcs;
`TruncateDone(t)` clears when OPEN.  Invariants `TruncateAtomicity`,
`TruncateNoOrphans`; `TruncateRecovery` covered by `KeyspaceCoverage`.
`UseTruncate` constant.  Proc store durable. Recovery via `parentProc` +
`MasterRecover`.  TLC 3r/2s: 368,662,744 distinct, 1,328,348,760 generated,
depth 92, ~71min, clean.

#### Iteration 36 — Disable/EnableTableProcedure ✅ COMPLETE

Fourth and fifth table-level procedures.  Sources: `DisableTableProcedure.java`,
`EnableTableProcedure.java` (each collapsed to Prepare + Done).  New 21st
variable `tableEnabled ∈ [Tables → BOOLEAN]`; persists across master crash.
New modules `Disable.tla`, `Enable.tla`.  `DisableTablePrepare(t)`: guards
enabled table, `TableLockFree`, all regions OPEN/no proc; spawns UNASSIGN
TRSPs, sets `tableEnabled[t] = FALSE`.  `EnableTablePrepare(t)`: symmetric
for disabled table, spawns ASSIGN TRSPs.  Done actions clear `parentProc`
when all regions reach target state.  `TRSPCreate` gains disabled-table
guard (`NoTable` exempt).  `"DISABLE"`/`"ENABLE"` added to `ParentProcType`
and `TableExclusiveType` (mutual exclusion via existing `TableLockExclusivity`).
Invariant `TableEnabledStateConsistency`: disabled table with no exclusive
lock ⇒ all regions CLOSED/OFFLINE.  Liveness `RegionEventuallyAssigned`:
ASSIGN on enabled table leads-to OPEN.  Single `UseDisable` constant gates
both; `FALSE` exhaustive/liveness, `TRUE` simulation.  `tableEnabled` plumbed
through all 12 modules.  TLC 3r/2s: 368,662,744 distinct, 1,328,348,760
generated, depth 92, ~71min, clean.

#### Iteration 37 — Fold regionKeyRange/regionTable into metaTable ✅ COMPLETE

Structural refactoring: eliminated `regionKeyRange` and `regionTable` as
top-level variables; all per-region data unified into `metaTable[r]` record
with 4 fields `[state, location, keyRange, table]`.  All 14 modules updated
to use field-level EXCEPT (`![r].field = value`) preventing accidental field
erasure.  `SCP.tla`: 2 whole-record `SCPAssignRegion` patterns found during
simulation to be overwriting `keyRange`/`table`, converted to field-level.
Net variable count reduced 21 → 19.  `ProcStore.tla` unchanged — returns
`[state, location]` records for `regionState`, not `metaTable`.  TLC 9r/3s
simulation 120s: 9,244,308 states, 90,397 traces, depth 67 (σ=33), clean.

#### Iteration 38 - ENABLING/DISABLING Table States ✅ COMPLETE

Expanded `tableEnabled` from `BOOLEAN` to 4-state `TableStateSet`
(`ENABLED`, `DISABLING`, `DISABLED`, `ENABLING`) matching Java's
`TableState.State` enum.  `DisableTablePrepare` guard `"ENABLED"`, sets
`"DISABLING"`; `DisableTableDone` sets `"DISABLED"`.  `EnableTablePrepare`
guard `"DISABLED"`, sets `"ENABLING"`; `EnableTableDone` sets `"ENABLED"`.
`TRSPCreate` disabled-table guard updated `TRUE` → `"ENABLED"`.
`AssignmentManager.tla`: `TypeOK` now `TableStateSet`, `Init` starts
`"ENABLED"`, `TableEnabledStateConsistency` checks `"DISABLED"`,
`RegionEventuallyAssigned` checks `"ENABLED"`.  New liveness property
`NoStuckRegions`: regions in `OPENING`/`CLOSING` eventually leave those
states; wired into `AssignmentManager-liveness.cfg`. TLC 9r/3s simulation
300s: 3,302,114 states, 29,879 traces, depth 67 (σ=33), clean.

#### Iteration 39 - Multi-region CreateTable ✅ COMPLETE

Extended `CreateTablePrepare(t, r)` → `CreateTablePrepare(t, S)` where `S`
is a non-deterministically chosen non-empty set of unused region identifiers
with `MaxKey % |S| = 0`.  Atomically tiles `[0, MaxKey)` into `|S|` equal-width
sub-ranges via CHOOSE bijection, writes `metaTable`, `regionState`, `procStore`,
and `parentProc` for all `r ∈ S`.  No new constants — region count bounded by
available unused identifiers and `MaxKey` divisibility.  `CreateTableDone`
unchanged (already uses `∀` over all CREATE regions).  `AssignmentManager.tla`:
Next disjunct updated (`\E S \in SUBSET Regions`); new `CreateNoOrphans`
invariant; `TruncateNoOrphans` strengthened to also allow OPEN/NONE completion
window. All 3 configs updated (+`CreateNoOrphans`).  TLC 9r/3s simulation 300s:
2,286,210 states, 14,111 traces, depth 67 (σ=33), clean.

#### Iteration 40 — SCP disabled-table guard ✅ COMPLETE

Modeled `isTableState(DISABLED)` check in `ServerCrashProcedure.assignRegions()`
(L546-553).  `SCP.tla`: new Path C in `SCPAssignRegion(s, r)` — disabled-table
skip path, guarded on `UseDisable`, `metaTable[r].table # NoTable`,
`tableEnabled[metaTable[r].table] = "DISABLED"`; transitions to
ABNORMALLY_CLOSED, clears location, does NOT create ASSIGN TRSP (no `procType`,
no `procStore` insert).  Path B gained exclusion guard for disabled tables.
`AssignmentManager.tla`: `NoLostRegions` modified to exclude disabled-table
regions; `TableEnabledStateConsistency` relaxed to allow `ABNORMALLY_CLOSED`.
No new invariants, variables, or constants; uses existing `tableEnabled` and
`UseDisable` gate.  TLC 9r/3s simulation 300s: 2,399,960 states, 14,777
traces, depth 67 (σ=33), clean.

#### Iteration 41 - Concurrent Split/Merge ✅ COMPLETE

Removed `SplitMergeConstraint` from simulation configuration
(`AssignmentManager-sim.cfg`), allowing concurrent split/merge procedures
on disjoint regions during simulation.  Constraint retained in exhaustive
(`AssignmentManager.cfg`) and liveness (`AssignmentManager-liveness.cfg`)
configs for tractability at 3r/2s.  `SplitMergeConstraint` definition in
`AssignmentManager.tla` unchanged. TLC 9r/3s simulation 300s:
3,503,967 states, 13,593 traces, depth 67, (σ=33), clean.

#### Iteration 42 — ModifyTableProcedure ✅ COMPLETE

Sixth table-level procedure.  Sources: `ModifyTableProcedure.java`
(`executeFromState()` L199-311), `ReopenTableRegionsProcedure.java`
(L191-254 TRSP REOPEN per region), `TableQueue.java` L50-80 (`EDIT` →
exclusive table lock).  Two administrative workflows modeled via two
disjunct `ModifyTablePrepare`: (1) non-structural modification on ENABLED
table — spawns TRSP REOPEN for idle OPEN regions, skips busy regions
(`parentProc = [MODIFY, COMPLETING]`), matching
`ReopenTableRegionsProcedure` L221-223 `getProcedure() != null` skip; (2)
structural modification on DISABLED table (admin sequence
`DisableTable → ModifyTable → EnableTable`) — no reopens, all regions
marked COMPLETING, matching `preflightChecks()` rejection of structural
changes on enabled tables.  `ModifyTableDone(t)` clears `parentProc` when
all SPAWNED_OPEN regions are OPEN/idle.  New module `Modify.tla` (collapsed
Prepare + Done).  `Types.tla`: +`UseModify ∈ BOOLEAN`, +`"MODIFY"` to
`ParentProcType` and `TableExclusiveType`.  `AssignmentManager.tla`:
+`INSTANCE Modify`, +2 Next disjuncts gated on `UseModify`, +WF
`ModifyTableDone`, +`UseModify` in PrintConfig. Invariant
`ModifyTableSafety`: MODIFY-tagged regions are in valid REOPEN, skipped,
or SCP-handled state.  Liveness `ModifyEventuallyDone`: modify eventually
completes. Config: exhaustive/liveness `UseModify = FALSE`, simulation
`UseModify = TRUE`.  TLC 9r/3s simulation 300s: 1,268,409 states, 4,799
traces, depth 67 (σ=33), clean.

#### Iteration 43 - Improve Invariants and Liveness

Invariants:
- After a CREATE/DELETE/TRUNCATE completes, no regions of that table should have dangling parentProc entries. SplitCompleteness and MergeCompleteness check this for split/merge, but there is no analogous "CreateCompleteness" or "DeleteCompleteness."
- Strengthening NoDoubleAssignment to explicitly check if walFenced[s] = TRUE, then no region on s should be in a state allowing new writes from the master's perspective (no region assigned to s with state OPEN in regionState). This would complement the rsOnlineRegions-based check.
- The spec tracks serverRegions separately from regionState[r].location. Do we need serverRegions?

Liveness:
- Does every CreateTable/DeleteTable/TruncateTable/DisableTable/EnableTable eventually complete? The spec has no liveness properties for these. A stuck DisableTableProcedure (e.g., one region's UNASSIGN keeps failing and retrying) would not be detected.
- Does every SplitPrepare eventually reach either SplitDone or SplitFail? Does every MergePrepare eventually reach either MergeDone or MergeFail? The current liveness properties do not cover this.
- After DisableTableDone, do all regions eventually reach a quiescent state (CLOSED or OFFLINE)? 

#### Iteration 44 — CatalogJanitor

Model the `CatalogJanitor` periodic meta-consistency scanner and its
associated `MetaFixer` repair actions.  The CatalogJanitor is the
implementation's runtime consistency checker — analogous to some of the
spec's invariants (`KeyspaceCoverage`, `NoLostRegions`) — but its actual
behavior (async scanning, repair procedure creation) introduces its own
concurrency concerns not currently modeled.
Source: `CatalogJanitor.java` (`ScheduledChore`, default 300s period),
`ReportMakingVisitor.java` (meta scan + consistency checks),
`CatalogJanitorReport.java` (report: holes, overlaps, unknown servers,
split parents, merged regions, empty region info),
`MetaFixer.java` (automated repair: hole fill + ASSIGN, overlap merge),
`GCRegionProcedure.java` (split parent GC),
`GCMultipleMergedRegionsProcedure.java` (merged region GC).
**New module:** `CatalogJanitor.tla` — periodic meta scan action with
concurrency guards.
**New constant:** `UseCatalogJanitor ∈ BOOLEAN` (Types.tla).  When
`FALSE` (default for exhaustive), CatalogJanitor actions disabled.
When `TRUE` (simulation), CatalogJanitor scan and repair enabled.
**New actions:**
1. `CJScanDetectHole(t)` — Scans `metaTable` for keyspace holes
   within table `t` (gap between consecutive regions' `endKey` and
   `startKey`).  When a hole is found: creates a new region covering
   the gap, writes `metaTable`, spawns ASSIGN TRSP.  Models
   `MetaFixer.fixHoles()` which creates `RegionInfo` for holes,
   adds meta entries, creates region directories, and submits
   `TransitRegionStateProcedure` for assignment.
   Guard: `masterAlive`, `availableWorkers > 0`, `UseCatalogJanitor`,
   no active table-exclusive lock on `t`, `tableEnabled[t] ∈
   {"ENABLED"}`.  Source: `MetaFixer.fixHoles()` L100-118,
   `ReportMakingVisitor.metaTableConsistencyCheck()` L117-188.
2. `CJScanDetectOverlap(t)` — Scans `metaTable` for overlapping
   regions within table `t`.  When overlap is found between adjacent
   regions: initiates a merge procedure on the overlapping set.
   Models `MetaFixer.fixOverlaps()` which calls
   `masterServices.mergeRegions()`.  Guard: same as `CJScanDetectHole`
   plus `UseMerge = TRUE`.
   Source: `MetaFixer.fixOverlaps()` L258-270,
   `ReportMakingVisitor.metaTableConsistencyCheck()` overlap branch
   L163-181.
3. `CJGCSplitParent(r)` — Garbage-collects a completed split parent.
   Guard: region in state `SPLIT`, `metaTable[r].keyRange = NoRange`,
   daughters exist and are `OPEN` with no references to parent.
   Atomically removes the parent's meta entry and frees the region
   identifier.  Models `CatalogJanitor.cleanParent()` L325-373 →
   `GCRegionProcedure`.  Source: `CatalogJanitor.scan()` L198-224,
   `GCRegionProcedure`.
4. `CJGCMergedRegion(r)` — Garbage-collects completed merge targets.
   Guard: region in state `MERGED`, `metaTable[r].keyRange = NoRange`,
   merged region exists and is `OPEN` with no references to targets.
   Atomically removes target meta entries and frees identifiers.
   Models `CatalogJanitor.cleanMergeRegion()` L254-290 →
   `GCMultipleMergedRegionsProcedure`.
   Source: `CatalogJanitor.scan()` L185-197,
   `GCMultipleMergedRegionsProcedure`.
5. `CJDetectUnknownServer(r)` — Refactored from the existing
   `DetectUnknownServer(r)` action (Iteration 29).  The unknown-server
   detection is moved into the CatalogJanitor scan cycle.  The action
   retains the same semantics and the `UseUnknownServerQuirk` toggle
   but fires as part of the janitor's periodic scan rather than as an
   independent non-deterministic event.
   Guard: same as current `DetectUnknownServer` plus `UseCatalogJanitor`.
   Source: `ReportMakingVisitor.checkServer()` L221-273,
   `CatalogJanitorReport.unknownServers` L54.
**Concurrency guards (from implementation):**
- `CatalogJanitor.chore()` only runs when `!isInMaintenanceMode()`,
  `!isClusterShutdown()`, `isMetaLoaded(am)`, and `getEnabled()`.
  Modeled as: `masterAlive = TRUE` (subsumes maintenance/shutdown).
- `scan()` uses `alreadyRunning` CAS — at most one concurrent scan.
  Modeled as a new boolean variable `cjRunning` or simply by making
  the scan actions mutually exclusive via unchanged guards.
- `ReportMakingVisitor.isTableDisabled()` — skips disabled tables'
  integrity checks. Modeled as: hole/overlap actions guard on
  `tableEnabled[t] ∈ {"ENABLED"}`.
- No RIT check for unknown-server detection (only GC operations
  check `!hasRegionsInTransition()` in older versions; current code
  does not gate the scan itself on RIT absence).
**Modifications to existing actions:**
- `DetectUnknownServer(r)` in `Master.tla`: When `UseCatalogJanitor =
  TRUE`, this action is superseded by `CJDetectUnknownServer(r)` in
  `CatalogJanitor.tla`.  The original `DetectUnknownServer` remains
  available when `UseCatalogJanitor = FALSE` (backward compatibility
  for existing exhaustive configs that don't enable CatalogJanitor).
  The `Next` disjunct is updated: `DetectUnknownServer` gains an
  additional guard `UseCatalogJanitor = FALSE`; `CJDetectUnknownServer`
  is added with guard `UseCatalogJanitor = TRUE`.
**New invariants:**
- `CJHoleRepairSafety`: A region created by CatalogJanitor hole repair
  always has a matching ASSIGN TRSP — no orphaned hole-fill regions.
- `CJGCSafety`: A split parent or merge target is only GC'd when all
  daughter/merged regions are OPEN and no references remain.
**New liveness property (liveness config only):**
- `FailedOpenEventuallyRecovered`: FAILED_OPEN regions on enabled
  tables are eventually re-assigned.  With CatalogJanitor modeled,
  the janitor's hole-detection scan would detect the missing coverage
  and create a repair ASSIGN, providing the fairness mechanism for
  this property.  Source: SPEC_CRITIQUE §4 item 4.
**Config updates:** All 3 configs: `+UseCatalogJanitor`.  Exhaustive
and liveness: `UseCatalogJanitor = FALSE`.  Simulation:
`UseCatalogJanitor = TRUE`.

---

## 8. Mapping from Code to TLA+ Actions

This table maps each significant code path to its corresponding TLA+
action. Actions marked ✅ are implemented; ⏳ are planned. Where the
actual action name differs from the original plan, the implemented name
is shown.

| Code Path | TLA+ Action | Iter | Status |
|-----------|-------------|------|--------|
| `TRSP.queueAssign()` | `TRSPCreate(r)` | 4 | ✅ |
| `TRSP.executeFromState()` GET_ASSIGN_CANDIDATE | `TRSPGetCandidate(r, s)` | 4 | ✅ |
| `TRSP.openRegion()` + `RSProcedureDispatcher` | `TRSPDispatchOpen(r)` | 7 | ✅ |
| `TRSP.confirmOpened()` | `TRSPConfirmOpened(r)` | 7 | ✅ |
| `RSProcedureDispatcher.remoteCallFailed()` (open) | `DispatchFail(r)` | 7 | ✅ |
| `RSProcedureDispatcher.remoteCallFailed()` (close) | `DispatchFailClose(r)` | 9 | ✅ |
| `TRSP.queueAssign()` UNASSIGN | `TRSPCreateUnassign(r)` | 5 | ✅ |
| `TRSP.closeRegion()` + dispatch | `TRSPDispatchClose(r)` | 9 | ✅ |
| `TRSP.confirmClosed()` | `TRSPConfirmClosed(r)` | 5 | ✅ |
| `TRSP.confirmOpened()` FAILED_OPEN retry | `TRSPHandleFailedOpen(r)` | 10 | ✅ |
| `RegionStateNode.offline()` | `GoOffline(r)` | 1 | ✅ |
| `ServerManager.expireServer()` (atomic per-server) | `ServerCrashAll(s)` (removed Iter 14) | 10 | ✅→❌ |
| `ServerManager.expireServer()` (master-side only) | `MasterDetectCrash(s)` | 14 | ✅ |
| `HRegionServer.abort()` (RS discovers death) | `RSAbort(s)` | 14 | ✅ |
| `TRSP.serverCrashed()` | `TRSPServerCrashed(r)` | 5 | ✅ |
| Drop report from crashed server | `DropStaleReport` | 10 | ✅ |
| `AssignRegionHandler.process()` (success path) | `RSOpen(s, r)` | 8 | ✅ |
| `AssignRegionHandler.process()` (failure path) | `RSFailOpen(s, r)` | 8 | ✅ |
| `UnassignRegionHandler.process()` (success path) | `RSClose(s, r)` | 9 | ✅ |
| `AM.balance()` / `createMoveRegionProcedure()` | `TRSPCreateMove(r)` | 11 | ✅ |
| `TRSP.confirmOpened()` maxAttempts give-up | `TRSPGiveUpOpen(r)` | 12 | ✅ |
| Kubernetes / process supervisor restart | `ServerRestart(s)` | 12 | ✅ |
| `SCP.splitLogs()` (WAL lease revocation) | `SCPFenceWALs(scp)` | 14 | ✅ |
| `SCP.assignRegions()` (simplified) | `SCPAssignRegion(s, r)` | 14 | ✅ |
| `TRSP.reopen()` | `TRSPCreateReopen(r)` | 15 | ✅ |
| SCP carryingMeta path | `SCPAssignMeta(s)` | 15 | ✅ |
| `SCP.assignRegions()` with `isMatchingRegionLocation` | `SCPAssignRegion(s,r)` refined | 16 | ✅ |
| `serverRegions` tracking (`ServerStateNode`) | `serverRegions[s]` variable | 16.5 | ✅ |
| `SCP` + `TRSP.serverCrashed()` interaction | `SCPAssignRegion` Path A + `TRSPServerCrashed` | 17 | ✅ |
| `WALProcedureStore` persistence | `procStore[r]` variable | 17 | ✅ |
| Cross-variable consistency | 8 new invariants (e.g. `ProcStepConsistency`) | 17.5 | ✅ |
| Master crash | `MasterCrash` | 18 | ✅ |
| Master recovery (load from store) | `MasterRecover` + `RestoreSucceedState` | 18 | ✅ |
| `RegionRemoteProcedureBase` two-phase reports | `TRSPReportSucceedOpen/Close`, `TRSPPersistToMetaOpen/Close` | 18 | ✅ |
| ZK session expiry (crash detection) | `ZKSessionExpire(s)` (`ZK.tla`) | 18 | ✅ |
| `ProcedureExecutor` worker pool | `availableWorkers` (counting semaphore) | 19 | ✅ |
| Meta-blocking semantics | `suspendedOnMeta`/`blockedOnMeta`, `ResumeFromMeta(r)` | 19 | ✅ |
| Per-region write lock | `locked[r]` (removed Iter 19.5) | 15 | ✅→❌ |
| Keyspace infrastructure (`regionKeyRange`) | `RegionExists(r)`, `Adjacent(r1, r2)` | 20 | ✅ |
| `SplitTableRegionProcedure.prepareSplitRegion()` | `SplitPrepare(r)` | 21 | ✅ |
| `SplitTableRegionProcedure` CHECK_CLOSED | `SplitResumeAfterClose(r)` | 21 | ✅ |
| `AssignmentManager.markRegionAsSplit()` | `SplitUpdateMeta(r, dA, dB)` | 21 | ✅ |
| `SplitTableRegionProcedure` completion | `SplitDone(r)` | 21 | ✅ |
| `SplitTableRegionProcedure.rollbackState()` | `SplitFail(r)` | 22 | ✅ |
| `MergeTableRegionsProcedure.prepareMergeRegion()` | `MergePrepare(r1, r2, m)` | 23 | ✅ |
| `MergeTableRegionsProcedure` CHECK_CLOSED | `MergeCheckClosed(p)` | 23 | ✅ |
| `AssignmentManager.markRegionAsMerged()` | `MergeUpdateMeta(p)` | 23 | ✅ |
| `MergeTableRegionsProcedure` completion | `MergeDone(p)` | 23 | ✅ |
| `MergeTableRegionsProcedure.rollbackState()` | `MergeFail(p)` | 23 | ✅ |
| SF on `RSOpen`, `RSClose`, `RSFailOpen` | `OfflineEventuallyOpen`, `SCPEventuallyDone` | 24 | ✅ |
| `TRSP.confirmClosed()` ABNORMALLY_CLOSED type-preserving | `TRSPConfirmClosedCrash` type-preserving branches | 25 | ✅ |
| `TRSP.serverCrashed()` type-preserving | `TRSPServerCrashed` type-preserving branches | 25 | ✅ |
| `TRSP.confirmOpened()` UNASSIGN continuation (L289-301) | `TRSPConfirmOpened` UNASSIGN→CLOSE branch | 25 | ✅ |
| `UnassignRegionHandler.process()` silent return (L111-117, L132-138) | `RSCloseNotFound(s, r)` gated by `UseRSCloseNotFoundQuirk` | 26 | ✅ |
| `RSProcedureDispatcher.scheduleForRetry()` → `expireServer()` (L326-336) | `DispatchFail`/`DispatchFailClose` second disjunct (unconditional) | 27 | ✅ |
| Table identity tracking (`regionTable`) | `regionTable[r]`, `NoTableExclusiveLock(r)`, `TableLockFree(t)` | 32 | ✅ |
| `CreateTableProcedure.executeFromState()` ADD_TO_META+ASSIGN | `CreateTablePrepare(t)` | 33 | ✅ |
| `CreateTableProcedure` POST_OPERATION | `CreateTableDone(t)` | 33 | ✅ |
| `DeleteTableProcedure.executeFromState()` PRE_OPERATION | `DeleteTablePrepare(t)` | 34 | ✅ |
| `DeleteTableProcedure` REMOVE_FROM_META | `DeleteTableRemoveMeta(t)` | 34 | ✅ |
| `DeleteTableProcedure` POST_OPERATION | `DeleteTableDone(t)` | 34 | ✅ |
| `TruncateTableProcedure.executeFromState()` PRE_OPERATION | `TruncatePrepare(t)` | 35 | ✅ |
| `TruncateTableProcedure` REMOVE_FROM_META | `TruncateDeleteMeta(t)` | 35 | ✅ |
| `TruncateTableProcedure` ADD_TO_META+ASSIGN | `TruncateCreateMeta(t)` | 35 | ✅ |
| `TruncateTableProcedure` POST_OPERATION | `TruncateDone(t)` | 35 | ✅ |
| `DisableTableProcedure.executeFromState()` PREPARE→SET_DISABLED | `DisableTablePrepare(t)` | 36 | ✅ |
| `DisableTableProcedure` POST_OP | `DisableTableDone(t)` | 36 | ✅ |
| `EnableTableProcedure.executeFromState()` PREPARE→SET_ENABLED | `EnableTablePrepare(t)` | 36 | ✅ |
| `EnableTableProcedure` POST_OP | `EnableTableDone(t)` | 36 | ✅ |
| `tableEnabled` guard on `TRSPCreate` | `TRSPCreate` disabled-table guard | 36 | ✅ |
| `SCP.assignRegions()` disabled-table skip (L546-553) | `SCPAssignRegion` Path C (disabled-table skip) | 40 | ✅ |
| `ModifyTableProcedure.executeFromState()` PREPARE→REOPEN | `ModifyTablePrepare(t)` | 42 | ✅ |
| `ReopenTableRegionsProcedure` → TRSP REOPEN per region | `ModifyTablePrepare(t)` (atomic reopen spawn) | 42 | ✅ |
| `ModifyTableProcedure` POST_OPERATION / completion | `ModifyTableDone(t)` | 42 | ✅ |
| `CatalogJanitor.scan()` periodic meta consistency scan | `CJScanDetectHole(t)`, `CJScanDetectOverlap(t)`, `CJDetectUnknownServer(r)` | 43 | ⏳ |
| `MetaFixer.fixHoles()` — create region for keyspace hole + ASSIGN | `CJScanDetectHole(t)` | 43 | ⏳ |
| `MetaFixer.fixOverlaps()` — merge overlapping regions | `CJScanDetectOverlap(t)` | 43 | ⏳ |
| `CatalogJanitor.cleanParent()` → `GCRegionProcedure` (split parent GC) | `CJGCSplitParent(r)` | 43 | ⏳ |
| `CatalogJanitor.cleanMergeRegion()` → `GCMultipleMergedRegionsProcedure` | `CJGCMergedRegion(r)` | 43 | ⏳ |
| `ReportMakingVisitor.checkServer()` unknown-server detection | `CJDetectUnknownServer(r)` (refactored from `DetectUnknownServer`) | 43 | ⏳ |

---

## 9. Source Code Reference Map

For each module, the primary source files and their key line ranges:

### Master Side

| File | Key Sections |
|------|-------------|
| `master/assignment/AssignmentManager.java` | `assign()` L823-849, `unassign()` L851-867, `reportRegionStateTransition()` L1256-1299, `submitServerCrash()` L1988-2049, `regionOpening()` L2211-2231, `regionClosing()` L2264-2275 |
| `master/assignment/TransitRegionStateProcedure.java` | `queueAssign()` L246-278, `openRegion()` L293-311, `confirmOpened()` L320-374, `closeRegion()` L389-407, `confirmClosed()` L409-446, `reportTransition()` L544-563, `serverCrashed()` L566-586 |
| `master/assignment/RegionStateNode.java` | `setState()` L119-126, `transitionState()` L141-147, `setProcedure()` L213-218, lock L343-388 |
| `master/assignment/RegionStates.java` | `updateRegionState()` L416-424, server tracking L672-716 |
| `master/assignment/RegionStateStore.java` | `updateRegionLocation()` L227-252, `visitMeta()` L107-130 |
| `master/assignment/RegionRemoteProcedureBase.java` | `reportTransition()` L209-245, `persistAndWake()` L190-206, `execute()` L329-388 |
| `master/assignment/OpenRegionProcedure.java` | Open dispatch logic |
| `master/assignment/CloseRegionProcedure.java` | Close dispatch logic |
| `master/assignment/SplitTableRegionProcedure.java` | Split state machine, PONR L417-429 |
| `master/assignment/MergeTableRegionsProcedure.java` | Merge state machine |
| `master/ServerCrashProcedure.java` | State machine L142-305, `assignRegions()` L562-645 |
| `master/ServerManager.java` | `expireServer()` L662-720, `regionServerReport()` L325-356 |
| `master/HMaster.java` | Initialization L929-1228, `balance()` L2098-2190 |
| `master/janitor/CatalogJanitor.java` | `scan()` L164-229, `cleanParent()` L325-383, `cleanMergeRegion()` L254-290 |
| `master/janitor/CatalogJanitorReport.java` | `holes` L46, `overlaps` L47, `unknownServers` L54, `splitParents` L42, `mergedRegions` L43 |
| `master/janitor/ReportMakingVisitor.java` | `metaTableConsistencyCheck()` L117-188, `checkServer()` L221-273 |
| `master/janitor/MetaFixer.java` | `fix()` L82-94, `fixHoles()` L100-118, `fixOverlaps()` L258-270 |
| `master/assignment/GCRegionProcedure.java` | Split parent GC procedure |
| `master/assignment/GCMultipleMergedRegionsProcedure.java` | Merged region GC procedure |
| `master/procedure/ModifyTableProcedure.java` | `executeFromState()` L199-311, `prepareModify()` L432-472, `getTableOperationType()` L424-426 |
| `master/procedure/ReopenTableRegionsProcedure.java` | `executeFromState()` L191-254, `canSchedule()` L178-188, `filterReopened()` L256-260 |
| `master/procedure/TableQueue.java` | `requireTableExclusiveLock()` L50-80 (EDIT → exclusive table lock) |

### RegionServer Side

| File | Key Sections |
|------|-------------|
| `regionserver/HRegionServer.java` | `regionsInTransitionInRS` L261, `onlineRegions` L298, `closeRegion()` L3067-3128, `reportRegionStateTransition()` L2338-2396, `postOpenDeployTasks()` L2243-2280 |
| `regionserver/RSRpcServices.java` | `openRegion()` L1911-2047, `closeRegion()` L1526-1549, `executeProcedures()` L4004 |
| `regionserver/handler/AssignRegionHandler.java` | `process()` L98-164 |
| `regionserver/handler/UnassignRegionHandler.java` | `process()` L92-158 |

### Procedure Framework

| File | Key Sections |
|------|-------------|
| `procedure2/Procedure.java` | State management L132-145, parent/child L124-127, execution L223-246 |
| `procedure2/StateMachineProcedure.java` | State tracking L51-69, execution L161-202, child management L138-158 |
| `procedure2/ProcedureExecutor.java` | Loading L328-609, execution L1437-1531, rollback L1612-1717, child management L1961-2006 |
| `procedure2/store/wal/WALProcedureStore.java` | Insert/update/delete L527-694, recovery L443-503 |

### Protocol Definitions

| File | Key Sections |
|------|-------------|
| `MasterProcedure.proto` | `RegionStateTransitionState` L643-649, `RegionTransitionType` L651-656, `ServerCrashState` L395-410, `SplitTableRegionState` L337-349, `MergeTableRegionsState` L357-370, `RegionRemoteProcedureBaseState` L666-671 |
| `RegionServerStatus.proto` | `TransitionCode` L103-118 |

### State Definitions

| File | Key Sections |
|------|-------------|
| `RegionState.java` | `State` enum L38-58 |

---

## 10. Iteration Process and Success Criteria

This section defines the methodology for iterating on the TLA+ specification,
the classification scheme for TLC findings, and the criteria for declaring an
iteration complete.

### 10.1 Terminal Outcomes

Every iteration ends in one of two states:

1. **Clean TLC run**: The model checker exhaustively explores the state space
   for the configured parameters and reports zero invariant violations and
   zero property violations. The spec faithfully models the implementation
   and no issues are found.

2. **Legitimate finding**: TLC produces a counterexample trace that, after
   triage, is confirmed to represent a genuine issue in the HBase
   implementation — a bug, a race condition, or a design gap that requires a
   code or architectural change. The finding is documented with full
   traceability and handed off for remediation.

There is no third "acceptable" terminal state. Spurious violations caused by
modeling errors are intermediate conditions that must be resolved before the
iteration is considered complete.

### 10.2 Per-Iteration Workflow

Each iteration follows a fixed loop:

1. **CODE ANALYSIS** — Before writing TLA+, analyze the relevant
   implementation code paths for this iteration's scope.  Ground the
   model to the actual implementation behavior.  The spec captures
   the correct protocol behavior — do NOT model known bugs.  At the
   end of each iteration, compare the model against the
   implementation to identify gaps where the code diverges from the
   correct protocol.  These gaps are the findings.
2. **WRITE / EDIT** — Add or modify spec per the iteration's scope
   (see Section 7 for iteration descriptions).
3. **SYNTAX CHECK** — Parse with SANY. Fix all parse errors before proceeding.
4. **RUN TLC** — Run simulation as the primary per-step verification:
   - `AssignmentManager-sim.cfg` (simulation, 9r/3s, 300s) — must pass.
     A 300-second simulation run is sufficient to uncover regressions
     during iterative development. 
   - `AssignmentManager.cfg` (primary, exhaustive 3r/2s) — run manually.
   - After completing a phase, run a 4-hour post-phase simulation (14400s).
5. **TRIAGE** — If TLC reports violations, classify each one.
   Repeat from step 1 or 4 as needed.
6. **REGRESSION CHECK** — Re-verify all invariants and properties from
   prior iterations. A fix in iteration N must not break any invariant
   proven in iterations 1 through N-1. The primary and simulation
   configs provide this coverage automatically at every iteration.
7. **RECORD** — Document the TLC result, configuration, state count,
   and any findings.
8. **UPDATE PLAN** — Mark the iteration complete in this plan document
   (Section 7). Append `✅ COMPLETE` to the iteration heading, convert
   the "What to add" description to past tense ("What was added"), and
   add a `**TLC result**` line summarizing the final model-checking
   outcome (constants, state count, invariants checked, pass/fail).
9. **GIT COMMIT** — Commit the successful spec files, configuration,
   updated plan document, and iteration record to version control. The
   commit message must identify the iteration number and summarize the
   outcome (clean pass or legitimate finding). This ensures every
   completed iteration has a recoverable checkpoint and provides an
   auditable history of the specification's evolution.

Steps 1–5 repeat until TLC either passes cleanly or produces a confirmed
legitimate finding. Step 6 is mandatory — no iteration is complete without
a regression check against all prior invariants. Steps 8–9 are the
terminal actions — an iteration is not considered done until the plan
document is updated and the results are committed.

---

## Appendix A: Locking Discipline Analysis

This appendix documents the analysis of procedure-level and row-level locking in
the implementation, confirming that TLA+ interleaving semantics faithfully model
the concurrency control.

### A.1 Lock Hierarchy

The implementation uses a three-level lock hierarchy:

```
Level 1: ProcedureScheduler region locks   (coarse, procedure-level)
Level 2: RegionStateNode locks              (fine, per-region mutual exclusion)
Level 3: HRegion row locks                  (storage-level, per-row in meta)
```

Locks are always acquired top-down. A procedure at Level 1 may acquire Level 2,
and Level 2 holders may trigger Level 3 writes. This prevents deadlocks.

### A.2 RegionStateNode Lock (Level 2)

The `RegionStateNodeLock` (`RegionStateNodeLock.java`) is the primary coordination
mechanism. Key properties:

- **Owner type**: Can be owned by a `Thread` or a `Procedure<?>`.
- **Reentrancy**: Supported — if the same owner re-acquires, a counter increments.
  Unlock decrements; the lock is freed when the counter reaches zero.
- **Cross-thread unlock**: Procedure-based ownership allows unlock from a different
  thread than the one that acquired it. This is critical because the
  `ProcedureExecutor` thread pool may schedule a procedure's steps on different
  threads.
- **Suspension**: When a procedure cannot acquire the lock, it throws
  `ProcedureSuspendedException`, yielding execution. When the lock becomes
  available, the procedure is woken via a callback (`wakeUp.run()`).
- **Wait queue**: Contending procedures are queued in FIFO order.

### A.3 TRSP and RegionRemoteProcedureBase Lock Protocol

Both `TransitRegionStateProcedure` and `RegionRemoteProcedureBase` follow the same
protocol:

1. **`beforeExec()`**: Acquire `RegionStateNode` lock. If contended, suspend.
2. **`holdLock()` returns `true`**: Lock is retained across execution steps and
   suspension — the procedure never releases the lock between steps.
3. **`afterExec()`**: Release the lock ONLY if there is no pending async meta
   update (`future == null`). If a meta update is in flight, the lock is held
   until the meta write completes.

This means: **while a TRSP is running on a region, no other procedure can
modify that region's state.** This is exactly the mutual exclusion that the
TLA+ model captures by requiring the `procedure` field of `RegionStateNode`
to be `None` before a new procedure can be attached.

### A.4 Split/Merge Procedure Locks (Level 1)

`SplitTableRegionProcedure` and `MergeTableRegionsProcedure` acquire
`ProcedureScheduler` region locks (via `waitRegions()`) for ALL involved
regions:

- **Split**: Locks parent + both daughters.
- **Merge**: Locks all targets + the merged region.

These Level 1 locks prevent any other procedure from being scheduled for the
same regions while split/merge is in progress. The actual state mutations
happen through child TRSPs which additionally acquire Level 2 locks.

### A.5 ServerStateNode Lock

`ServerStateNode` uses a `ReentrantReadWriteLock`:

- **Read lock**: Acquired by `AssignmentManager.reportRegionStateTransition()` when
  processing RS reports. Multiple reports can be processed concurrently.
- **Write lock**: Acquired by `AssignmentManager.submitServerCrash()` when
  transitioning a server to CRASHED state. This blocks all concurrent RS reports
  for that server.

This ensures that once an SCP is submitted, no more transition reports from the
crashed server can interfere.

### A.6 Meta Row-Level Locking (Level 3)

Meta table writes use two mechanisms:

- **Single-row updates** (`updateRegionLocation`): Standard `HRegion.put()`, which
  acquires a per-row `ReentrantReadWriteLock` internally. Atomic per row. No
  explicit application-level lock needed beyond the Level 2 RegionStateNode lock.

- **Multi-row updates** (split/merge via `multiMutate`): Uses the
  `MultiRowMutationService` coprocessor endpoint, which:
  1. Collects all rows to mutate.
  2. Sorts them by row key (prevents deadlocks).
  3. Acquires write row locks for all rows.
  4. Applies all mutations atomically within `mutateRowsWithLocks()`.
  5. Releases all row locks in `finally`.

### A.7 Failure Recovery and Lock Revert

- **Meta write failure**: `transitStateAndUpdate()` reverts the in-memory state
  to the pre-transition value if the meta `Put` fails. The RegionStateNode lock
  is still held during the revert.

- **Two-phase persistence** (in `RegionRemoteProcedureBase`):
  1. Persist procedure state to ProcedureStore (WAL).
  2. Then update meta.
  If step 1 fails, the transition code is reset. If step 2 fails after step 1
  succeeds, the procedure can be replayed from the WAL on master restart.

- **Lock restoration on master restart**: `ProcedureExecutor.restoreLocks()`
  walks the parent chain of each loaded procedure from root to leaf, restoring
  locks bottom-up. This ensures parent locks are acquired before child locks,
  maintaining the hierarchy.

### A.8 Implications for TLA+ Modeling

The analysis confirms the following modeling decisions:

1. **Per-region mutual exclusion**: Modeled as a `procedure` field on each
   region. At most one procedure is attached at a time. This faithfully
   represents the `RegionStateNodeLock` + `holdLock() == true` discipline.

2. **Atomic state + meta update**: The RegionStateNode lock is held across the
   in-memory transition AND the meta write. Since meta writes are atomic and
   immediately consistent (see resolved item 1), the TLA+ model can represent
   `transitStateAndUpdate()` as a single atomic action that updates both
   `regionState` and `metaTable` simultaneously.

3. **Server-level fencing**: The ServerStateNode write lock during SCP
   submission should be modeled as an atomic action that sets server state to
   CRASHED and blocks further transition reports. The TLA+ model can use a
   `serverState[s] = "CRASHED"` guard to reject reports from crashed servers.

4. **Split/merge multi-region locking**: When modeling split/merge, the TLA+
   spec must acquire "locks" (procedure attachment) on ALL involved regions
   before any state changes. This can be modeled as a conjunctive precondition
   checking that all regions have `procedure = None`.

5. **Lock restoration**: The TLA+ model of master crash recovery should restore
   procedure attachments from the procedure store, matching the
   `restoreLocks()` bottom-up algorithm.

---

## Appendix B: RPC Model Analysis

This appendix documents the RPC round-trip between Master and RegionServer
for region assignment operations, supporting the decision to model at the
RPC level rather than as abstract messages.

### B.1 Two Distinct RPCs

The assignment protocol uses exactly two RPCs, on two different services,
in opposite directions:

| RPC | Direction | Service | Proto File |
|-----|-----------|---------|------------|
| `ExecuteProcedures` | Master → RS | `AdminService` | `Admin.proto` |
| `ReportRegionStateTransition` | RS → Master | `RegionServerStatusService` | `RegionServerStatus.proto` |

These are **not** request-response pairs for a single logical operation.
The master dispatches a command via `ExecuteProcedures` (the response is
an empty message — it confirms delivery, not outcome), and the RS
independently reports the outcome back via
`ReportRegionStateTransition` on a completely separate RPC channel.

### B.2 Master → RS: ExecuteProcedures

**Wire format** (`Admin.proto:283-287`):

```protobuf
message ExecuteProceduresRequest {
  repeated OpenRegionRequest open_region = 1;
  repeated CloseRegionRequest close_region = 2;
  repeated RemoteProcedureRequest proc = 3;
}

message ExecuteProceduresResponse {
  // intentionally empty
}
```

**Dispatch path**:

```
TRSP.openRegion() / closeRegion()
  → creates OpenRegionProcedure / CloseRegionProcedure (child)
  → RegionRemoteProcedureBase.execute() [DISPATCH state]
    → RSProcedureDispatcher.addOperationToNode(targetServer, this)
      → buffers operations per server
      → RSProcedureDispatcher.remoteDispatch()
        → batches into ExecuteProceduresRequest
        → AdminService.executeProcedures(request)
  → procedure suspends (ProcedureSuspendedException)
```

Key properties:
- **Delivery confirmation only**: The response is empty — a successful RPC
  return confirms the command was delivered to the RS, but says nothing
  about whether the open/close will succeed. The outcome arrives later
  via the separate `ReportRegionStateTransition` RPC.
- **Batching**: Multiple open/close operations for the same server are batched
  into a single `ExecuteProceduresRequest`.
- **Epoch fencing**: Each request carries `initiatingMasterActiveTime`, which
  the RS can use to reject commands from a stale master.
- **Dispatch retry with backoff** (`RSProcedureDispatcher.scheduleForRetry`):
  The master retries the dispatch RPC itself on transient failures:
  - `ServerNotRunningYetException`: retry with interval up to max wait.
  - Connection error on first attempt: give up immediately (safe — command
    was never delivered, can try another server).
  - Connection error on subsequent attempts: **must** keep retrying because
    the RS may have already received the command — giving up and picking a
    new server could cause double-assign.
  - Retry limit exceeded for certain error types: expire the server (triggers
    SCP), then give up.
  - Server goes offline during retries: give up.
  - Exponential backoff: `rsRpcRetryInterval * attempt²`, capped at 10s.
- **Terminal failure**: When all retries are exhausted, `remoteCallFailed()`
  is called, setting the remote procedure state to `DISPATCH_FAIL`. The
  TRSP then retries with a new server via `forceNewPlan=true`.

### B.3 RS → Master: ReportRegionStateTransition

**Wire format** (`RegionServerStatus.proto:90-135`):

```protobuf
message RegionStateTransition {
  required TransitionCode transition_code = 1;
  repeated RegionInfo region_info = 2;
  optional uint64 open_seq_num = 3;
  repeated int64 proc_id = 4;
  optional int64 initiating_master_active_time = 5;

  enum TransitionCode {
    OPENED = 0;
    FAILED_OPEN = 1;
    CLOSED = 2;
    READY_TO_SPLIT = 3;
    READY_TO_MERGE = 4;
    SPLIT = 7;
    MERGED = 8;
    SPLIT_REVERTED = 9;
    MERGE_REVERTED = 10;
  }
}

message ReportRegionStateTransitionRequest {
  required ServerName server = 1;
  repeated RegionStateTransition transition = 2;
}

message ReportRegionStateTransitionResponse {
  optional string error_message = 1;
}
```

**Report path**:

```
AssignRegionHandler.process() completes open
  → HRegionServer.postOpenDeployTasks()
    → reportRegionStateTransition(OPENED, openSeqNum, procId)
      → builds ReportRegionStateTransitionRequest
      → retry loop:
          RegionServerStatusService.reportRegionStateTransition(request)
          on error: backoff, retry (ServerNotRunning, PleaseHold, QueueTooBig)

Master receives:
  MasterRpcServices.reportRegionStateTransition()
    → validates initiatingMasterActiveTime (epoch check)
    → AssignmentManager.reportRegionStateTransition()
      → acquires ServerStateNode read lock
      → validates server is ONLINE
      → acquires RegionStateNode lock
      → TRSP.reportTransition()
        → RegionRemoteProcedureBase.reportTransition()
          → state = REPORT_SUCCEED
          → persist to procedure store
          → update in-memory region state
          → wake procedure
```

Key properties:
- **Retry with backoff**: The RS retries indefinitely until the report is
  accepted or the RS shuts down. Handles `ServerNotRunningYetException`,
  `PleaseHoldException`, `CallQueueTooBigException`.
- **Epoch validation**: The master rejects reports with a
  `initiatingMasterActiveTime` from a future master (stale report from an
  RS that was talking to a different master instance).
- **Server fencing**: The master acquires the `ServerStateNode` read lock
  and checks the server is ONLINE before processing. If the server is
  already CRASHED (write-locked by SCP), reports are rejected.
- **Procedure matching**: The `procId` in the report is matched against the
  active `RegionRemoteProcedureBase` on the region's TRSP.

### B.4 Complete Open Round-Trip

```
 Master                                        RegionServer
 ──────                                        ────────────
 TRSP state: OPEN
 │
 ├─ regionOpening():
 │    regionState = OPENING
 │    meta ← OPENING
 │
 ├─ create OpenRegionProcedure (child)
 │
 ├─ RemoteProcBase.execute() [DISPATCH]
 │    │
 │    ├── addOperationToNode(server, this)
 │    │
 │    ├── RSProcedureDispatcher batches ──────► ExecuteProcedures RPC
 │    │                                         │
 │    └── suspend                               ├─ executeProcedures()
 │                                              │   └─ executeOpenRegionProcedures()
 │                                              │       └─ submit AssignRegionHandler
 │                                              │
 │                                              ├─ AssignRegionHandler.process()
 │                                              │   ├─ regionsInTransition[r] = TRUE
 │                                              │   ├─ HRegion.openHRegion()
 │                                              │   ├─ postOpenDeployTasks()
 │                                              │   │   └─ openSeqNum = getOpenSeqNum()
 │                                              │   ├─ addRegion(region)
 │                                              │   └─ remove from regionsInTransition
 │                                              │
 │    reportRegionStateTransition() ◄────────── reportRegionStateTransition(OPENED)
 │    │                                          retry loop until accepted
 │    ├─ validate epoch
 │    ├─ validate server ONLINE
 │    ├─ lock RegionStateNode
 │    ├─ TRSP.reportTransition()
 │    │   └─ RemoteProcBase.reportTransition()
 │    │       ├─ state = REPORT_SUCCEED
 │    │       ├─ persist procedure store
 │    │       └─ regionOpenedWithoutPersistingToMeta()
 │    │            regionState = OPEN (in memory)
 │    └─ wake procedure
 │
 ├─ RemoteProcBase.execute() [REPORT_SUCCEED]
 │    └─ persistToMeta()
 │         meta ← OPEN
 │
 TRSP state: CONFIRM_OPENED
 │
 └─ confirmOpened(): region is OPEN → done
```

### B.5 Complete Close Round-Trip

```
 Master                                        RegionServer
 ──────                                        ────────────
 TRSP state: CLOSE
 │
 ├─ regionClosing():
 │    regionState = CLOSING
 │    meta ← CLOSING
 │
 ├─ create CloseRegionProcedure (child)
 │
 ├─ RemoteProcBase.execute() [DISPATCH]
 │    │
 │    ├── addOperationToNode(server, this)
 │    │
 │    ├── RSProcedureDispatcher batches ──────► ExecuteProcedures RPC
 │    │                                         │
 │    └── suspend                               ├─ executeProcedures()
 │                                              │   └─ executeCloseRegionProcedures()
 │                                              │       └─ submit UnassignRegionHandler
 │                                              │
 │                                              ├─ UnassignRegionHandler.process()
 │                                              │   ├─ regionsInTransition[r] = FALSE
 │                                              │   ├─ region.close()
 │                                              │   ├─ removeRegion(region)
 │                                              │   └─ remove from regionsInTransition
 │                                              │
 │    reportRegionStateTransition() ◄────────── reportRegionStateTransition(CLOSED)
 │    │                                          retry loop until accepted
 │    ├─ validate epoch
 │    ├─ validate server ONLINE
 │    ├─ lock RegionStateNode
 │    ├─ TRSP.reportTransition()
 │    │   └─ RemoteProcBase.reportTransition()
 │    │       ├─ state = REPORT_SUCCEED
 │    │       ├─ persist procedure store
 │    │       └─ regionClosedWithoutPersistingToMeta()
 │    │            regionState = CLOSED (in memory)
 │    │            location = null
 │    └─ wake procedure
 │
 ├─ RemoteProcBase.execute() [REPORT_SUCCEED]
 │    └─ persistToMeta()
 │         meta ← CLOSED
 │
 TRSP state: CONFIRM_CLOSED
 │
 └─ confirmClosed(): region is CLOSED → done
```

### B.6 Failure Scenarios

#### RPC dispatch failure (Master → RS fails)

```
RemoteProcBase.execute() [DISPATCH]
  → addOperationToNode()
  → RSProcedureDispatcher.sendRequest() fails (IOException)
    → remoteCallFailed()
      → state = DISPATCH_FAIL
      → wake procedure
  → RemoteProcBase.execute() [DISPATCH_FAIL]
    → unattach from RegionStateNode
    → parent TRSP retries with forceNewPlan=true
```

#### RS crashes after receiving command

```
RemoteProcBase [DISPATCH, suspended]
  ← ServerCrashProcedure detects crash
  → TRSP.serverCrashed()
    → RemoteProcBase.serverCrashed()
      → state = SERVER_CRASH
      → persist procedure store
      → wake procedure
  → RemoteProcBase.execute() [SERVER_CRASH]
    → marks region ABNORMALLY_CLOSED
    → unattach
    → parent TRSP: confirmClosed() sees ABNORMALLY_CLOSED
      → transitions to GET_ASSIGN_CANDIDATE (reassign)
```

#### RS reports FAILED_OPEN

```
AssignRegionHandler.process()
  → HRegion.openHRegion() fails (IOException)
  → cleanUpAndReportFailure()
    → reportRegionStateTransition(FAILED_OPEN)

Master receives FAILED_OPEN:
  → RemoteProcBase.reportTransition()
    → state = REPORT_SUCCEED
    → regionFailedOpen() (in memory)
  → TRSP.confirmOpened()
    → region is not OPEN
    → retryCounter++
    → if retries < max: retry with forceNewPlan
    → if retries >= max: give up, set FAILED_OPEN in meta
```

### B.7 Implications for TLA+ Modeling

> **Implementation note**: The variable and action design below reflects
> the original pre-implementation analysis. The actual spec simplified
> the RPC model: (1) `remoteProcState` was absorbed into TRSP inline
> procedure fields (`procType`, `procStep` in `regionState`); (2) RS
> receive+complete were merged into atomic actions (`RSOpen`, `RSClose`);
> (3) procedure IDs were replaced by region-keyed matching. The analysis
> in B.1-B.6 remains accurate as implementation reference.

See the implemented spec for the actual variable and action design,
which differs from the original pre-implementation analysis above.

---

## Appendix C: Split and Merge Operations Analysis

This appendix documents the split and merge procedures, their unique
assignment states, state transition graphs, PONR semantics, and
implications for TLA+ modeling.

### C.1 SplitTableRegionProcedure State Machine

The split procedure has 11 states. For assignment modeling, we focus on
the subset that affects region states and child procedures, abstracting
away filesystem and coprocessor operations:

```
 PREPARE                            Validate region is OPEN and splittable;
                                    set parent to SPLITTING (in-memory only)
    │
    v
 PRE_OPERATION                      Coprocessor hooks (abstract: skip)
    │
    v
 CLOSE_PARENT_REGION                Create child TRSP(UNASSIGN) for parent
    │                               Parent: SPLITTING → CLOSING → CLOSED
    v
 CHECK_CLOSED_REGIONS               Verify parent is CLOSED
    │
    v
 CREATE_DAUGHTER_REGIONS            Create daughter region files on HDFS
    │                               (abstract: no-op in TLA+ model)
    v
 WRITE_MAX_SEQUENCE_ID_FILE        Write seq ID files (abstract: skip)
    │
    v
 PRE_OPERATION_BEFORE_META         Coprocessor hooks (abstract: skip)
    │
    v
 UPDATE_META  ═══ PONR ═══         Atomic multi-row meta update:
    │                                 Parent: CLOSED → SPLIT (meta + memory)
    │                                 DaughterA: created as SPLITTING_NEW (mem)
    │                                            / CLOSED (meta)
    │                                 DaughterB: created as SPLITTING_NEW (mem)
    │                                            / CLOSED (meta)
    v
 PRE_OPERATION_AFTER_META          Coprocessor hooks (abstract: skip)
    │
    v
 OPEN_CHILD_REGIONS                Create child TRSP(ASSIGN) for each daughter
    │                               DaughterA: SPLITTING_NEW → OPENING → OPEN
    │                               DaughterB: SPLITTING_NEW → OPENING → OPEN
    v
 POST_OPERATION                    Coprocessor hooks; procedure completes
```

**Source**: `SplitTableRegionProcedure.java` `executeFromState()` L286-359.

**Simplified for TLA+ modeling** (abstracting filesystem/coprocessors):

```
 PREPARE → CLOSE_PARENT → CHECK_CLOSED → UPDATE_META (PONR)
    → OPEN_CHILDREN → DONE
```

### C.2 MergeTableRegionsProcedure State Machine

The merge procedure also has 11 states. Simplified for assignment:

```
 PREPARE                            Validate all parents are OPEN and
                                    mergeable; set each to MERGING (in-memory)
    │
    v
 PRE_MERGE_OPERATION               Coprocessor hooks (abstract: skip)
    │
    v
 CLOSE_REGIONS                     Create child TRSP(UNASSIGN) for each parent
    │                               Each parent: MERGING → CLOSING → CLOSED
    v
 CHECK_CLOSED_REGIONS              Verify all parents are CLOSED
    │
    v
 CREATE_MERGED_REGION              Create merged region on HDFS;
    │                               set to MERGING_NEW (in-memory)
    │                               (abstract: create region record)
    v
 WRITE_MAX_SEQUENCE_ID_FILE       (abstract: skip)
    │
    v
 PRE_MERGE_COMMIT_OPERATION       Coprocessor hooks (abstract: skip)
    │
    v
 UPDATE_META  ═══ PONR ═══        Atomic multi-row meta update:
    │                                Parents: deleted from meta and regionStates
    │                                Merged: created as MERGING_NEW (mem)
    │                                        / CLOSED (meta)
    v
 POST_MERGE_COMMIT_OPERATION      Coprocessor hooks (abstract: skip)
    │
    v
 OPEN_MERGED_REGION               Create child TRSP(ASSIGN) for merged region
    │                               Merged: MERGING_NEW → OPENING → OPEN
    v
 POST_OPERATION                   Procedure completes
```

**Source**: `MergeTableRegionsProcedure.java` `executeFromState()` L189-255.

**Simplified for TLA+ modeling**:

```
 PREPARE → CLOSE_REGIONS → CHECK_CLOSED → CREATE_MERGED
    → UPDATE_META (PONR) → OPEN_MERGED → DONE
```

### C.3 ValidTransition Set

The full set of valid transitions:

```tla
ValidTransition ==
    \* --- Core assign/unassign/move ---
    { <<"OFFLINE",            "OPENING">>,
      <<"OPENING",            "OPEN">>,
      <<"OPENING",            "FAILED_OPEN">>,
      <<"OPEN",               "CLOSING">>,
      <<"CLOSING",            "CLOSED">>,
      <<"CLOSED",             "OPENING">>,
      <<"CLOSED",             "OFFLINE">>,
      <<"OPEN",               "ABNORMALLY_CLOSED">>,
      <<"OPENING",            "ABNORMALLY_CLOSED">>,
      <<"CLOSING",            "ABNORMALLY_CLOSED">>,
      <<"ABNORMALLY_CLOSED",  "OPENING">>,
      <<"FAILED_OPEN",        "OPENING">>,

      \* --- Split-specific ---
      <<"OPEN",               "SPLITTING">>,       \* prepareSplitRegion()
      <<"SPLITTING",          "CLOSING">>,          \* TRSP unassign of parent
      <<"SPLITTING",          "OPEN">>,             \* rollback: revert to OPEN
      <<"CLOSED",             "SPLIT">>,            \* markRegionAsSplit() at PONR
      <<"SPLITTING_NEW",      "OPENING">>,          \* child TRSP assigns daughter

      \* --- Merge-specific ---
      <<"OPEN",               "MERGING">>,          \* prepareMergeRegion()
      <<"MERGING",            "CLOSING">>,          \* TRSP unassign of target
      <<"MERGING",            "OPEN">>,             \* rollback: revert to OPEN
      <<"CLOSED",             "MERGED">>,           \* markRegionAsMerged() at PONR
      <<"MERGING_NEW",        "OPENING">> }         \* child TRSP assigns merged
```

**Key observations**:

- `SPLITTING → CLOSING` and `MERGING → CLOSING`: These transitions are
  driven by child TRSP(UNASSIGN) procedures. The TRSP `closeRegion()`
  method explicitly accepts regions in SPLITTING and MERGING states
  (`TransitRegionStateProcedure.java:397`).

- `SPLITTING → OPEN` and `MERGING → OPEN`: These are rollback transitions
  used when the split/merge procedure fails before PONR
  (`SplitTableRegionProcedure.java:603`,
  `MergeTableRegionsProcedure.java:541`).

- `CLOSED → SPLIT`: Only happens at PONR, not during normal unassignment.
  The split procedure drives this transition after the parent is closed.

- **MERGED**: The code never directly transitions a region to `MERGED`
  state — merge targets are deleted from `regionStates` and meta via
  `markRegionAsMerged()`. For TLA+ modeling, we use `MERGED` as an
  abstract terminal state representing deletion, and add a
  `CLOSED → MERGED` transition at PONR. The `MERGED` state is
  simpler for TLC than dynamic set membership. The corresponding
  `CLOSED → MERGED` transition is included above in the merge-specific
  section of `ValidTransition`.

- `SPLITTING_NEW → OPENING` and `MERGING_NEW → OPENING`: These are the
  initial transitions for newly created regions. The daughter/merged
  regions are created in `SPLITTING_NEW`/`MERGING_NEW` state and then
  assigned via child TRSP(ASSIGN).

### C.4 PONR Semantics and Rollback Model

Both split and merge have a Point of No Return at `UPDATE_META`:

| Phase | Split | Merge |
|-------|-------|-------|
| Pre-PONR | PREPARE through PRE_BEFORE_META | PREPARE through PRE_MERGE_COMMIT |
| PONR | UPDATE_META | UPDATE_META |
| Post-PONR | PRE_AFTER_META through POST_OP | POST_MERGE_COMMIT through POST_OP |

**Pre-PONR rollback actions** (in reverse order):

| Split Rollback State | Action |
|---------------------|--------|
| CREATE_DAUGHTERS / WRITE_SEQ_ID | Delete daughter regions from filesystem |
| CHECK_CLOSED | Reopen parent region (create TRSP ASSIGN) |
| CLOSE_PARENT | Child TRSP handles its own rollback |
| PRE_OPERATION | Coprocessor rollback hook |
| PREPARE | Revert parent from SPLITTING to OPEN |

| Merge Rollback State | Action |
|---------------------|--------|
| CREATE_MERGED / WRITE_SEQ_ID | Delete merged region from filesystem |
| CHECK_CLOSED | No-op |
| CLOSE_REGIONS | Reopen target regions |
| PRE_MERGE | Coprocessor rollback hook |
| PREPARE | Revert targets from MERGING to OPEN |

**Post-PONR**: Rollback is forbidden — `isRollbackSupported()` returns
`false`. The procedure must retry until it completes. On master crash,
the procedure is replayed from the ProcedureStore and continues forward.

**Source**: `SplitTableRegionProcedure.java` `isRollbackSupported()` L417-429,
`rollbackState()` L368-411.
`MergeTableRegionsProcedure.java` `isRollbackSupported()` L313-325,
`rollbackState()` L264-307.

**TLA+ modeling**: Pre-PONR states should have a non-deterministic failure
action that triggers rollback. Post-PONR states should have no rollback
path — only forward progress. The PONR itself is an atomic action that
commits the meta update.

### C.5 Dynamic Region Creation and Deletion (Keyspace Model)

Split creates 2 new regions; merge deletes 2 parent/target regions. This
poses a modeling challenge since TLC works with finite, pre-defined
constants.

**Approach: Unified region set with keyspace-range identity.**

`Regions` is the finite universe of all region identifiers in the
model, pre-allocated for TLC. It encompasses both the deployed
table regions and additional unused identifiers that split/merge
can materialize as new regions.

- **`DeployedRegions ⊆ Regions`** — the table regions that exist
  at system start. They tile the full keyspace `[0, MaxKey)` in
  Init. E.g. with `MaxKey = 8` and `DeployedRegions = {r1, r2}`:
  r1 gets `[0, 4)`, r2 gets `[4, 8)`.
- **`Regions \ DeployedRegions`** — unused identifiers. They start
  with `regionKeyRange = NoRange` (non-existent) and
  `state = OFFLINE`. When a split or merge needs a daughter or
  merged region, it picks an identifier from this set and
  materializes it by assigning a keyspace at the PONR.
- A region **"exists"** iff `regionKeyRange[r] ≠ NoRange`.
  All existing actions guard on this predicate.

```tla
CONSTANTS
    Regions,             \* {r1, r2, r3, r4, r5, r6} — all identifiers
    DeployedRegions,     \* {r1, r2} ⊆ Regions — deployed at start
    MaxKey               \* Integer; keyspace is 0..(MaxKey-1)

VARIABLE regionKeyRange  \* [Regions → [startKey: 0..MaxKey,
                         \*              endKey: 0..MaxKey]
                         \*            ∪ {NoRange}]
```

**Minimum keyspace width constraint**: Every region in the initial
tiling must have `endKey - startKey ≥ 2` so that it satisfies the
split precondition (keyspace wide enough to halve). This also applies
transitively: after a split, each daughter gets half the parent's
width, so a parent must have width ≥ 4 to produce daughters that are
themselves splittable, width ≥ 8 for granddaughters, etc. After a
merge, the merged region's width is the sum of its two targets' widths.
`MaxKey` and the number of initial regions must be chosen so that
`MaxKey / |DeployedRegions| ≥ 2^d` where `d` is the maximum split
depth to be explored.  For example, `MaxKey = 8` with 2 initial
regions gives width 4 each, allowing one level of splits (daughters
have width 2, still splittable) or two levels if `MaxKey = 16`.

**Split keyspace halving**: `SplitPrepare` computes
`mid = (startKey + endKey) ÷ 2`. At PONR (`SplitUpdateMeta`),
daughters are materialized: `dA = [startKey, mid)`, `dB = [mid, endKey)`.
At `SplitDone`, the parent's keyspace is cleared to `NoRange` (deletion
after compaction — modeled as atomic).

**Merge keyspace union**: `MergePrepare` requires `Adjacent(r1, r2)` —
i.e. `r1.endKey = r2.startKey`. At PONR (`MergeUpdateMeta`), merged
region is materialized: `m = [r1.startKey, r2.endKey)`. At `MergeDone`,
targets' keyspaces are cleared to `NoRange`.

**Adjacency predicate**:

```tla
Adjacent(r1, r2) ==
    regionKeyRange[r1].endKey = regionKeyRange[r2].startKey
```

**Coverage invariant**:

```tla
KeyspaceCoverage ==
    \* Every key in [0, MaxKey) is covered by exactly one live region.
    \A k \in 0..(MaxKey-1) :
        \E! r \in Regions :
            /\ regionKeyRange[r] # NoRange
            /\ regionKeyRange[r].startKey <= k
            /\ k < regionKeyRange[r].endKey
            /\ regionState[r].state \notin {"SPLIT", "MERGED"}
```

`KeyspaceCoverage` is relaxed during in-flight split/merge (between PONR
and daughters/merged reaching OPEN) — the SPLITTING_NEW/MERGING_NEW
regions are included as covering their keyspaces even though they are
not yet OPEN.

**State space implications**: With `MaxKey = 8`, 2 deployed regions, and
4 unused identifiers (6 total in `Regions`), the model supports 2 splits
(4 daughters consume all unused identifiers). TLC feasibility depends on
bounding the number of
concurrent split/merge operations via `SplitMergeMutualExclusion`.

**Modeling abstractions** (see C.11 for full analysis):

- *Split point*: The model uses `mid = (start + end) ÷ 2`. In the
  implementation, the split row comes from the RegionServer
  (`GetRegionInfoResponse.bestSplitRow`) or a user-specified row. The
  exact byte chosen is data-dependent and irrelevant to assignment safety;
  what matters is that daughters partition the parent's keyspace.
- *N-way merge*: The implementation supports merging ≥2 regions
  (`RegionInfo[]`). The model restricts to 2-way merge, which is
  sufficient for exercising all state transitions, locking, PONR, and
  crash recovery paths.
- *Force merge*: When `force=true`, the implementation bypasses the
  adjacency/overlap check and computes a `min(startKey)..max(endKey)`
  envelope that may overlap existing regions. Force-merge is a
  repair tool for broken region state; the model intentionally excludes
  it. The `KeyspaceCoverage` invariant is exactly what force-merge is
  designed to fix.

### C.6 Multi-Region Locking

Split and merge procedures acquire `ProcedureScheduler` region locks
for ALL involved regions before any state changes:

| Procedure | Regions Locked |
|-----------|---------------|
| Split | Parent + 2 daughter identifiers |
| Merge | All targets + merged region |

Locks are held for the entire procedure lifetime (`holdLock() == true`).

**TLA+ modeling**: The implemented spec uses `parentProc` and
`procType ≠ "NONE"` guards on all involved regions as a conjunctive
precondition — all locks must be free before split/merge can proceed.
See `SplitPrepare` and `MergePrepare` in the spec (Iterations 21, 23).

**Source**: `SplitTableRegionProcedure.java` `acquireLock()` L158-171.
`MergeTableRegionsProcedure.java` `acquireLock()` L398-411.

### C.7 In-Memory vs. Meta State Discrepancy at PONR

At the PONR (`UPDATE_META`), there is a deliberate discrepancy between
in-memory and meta state for newly created regions:

| Region | In-Memory State | Meta State | Reason |
|--------|----------------|------------|--------|
| Split daughter | `SPLITTING_NEW` | `CLOSED` | Prevents auto-assign on master restart |
| Merged child | `MERGING_NEW` | `CLOSED` | Same reason |

**Why CLOSED in meta**: If daughters/merged were stored as OFFLINE in meta,
master startup would scan meta and attempt to assign all OFFLINE regions,
conflicting with the resumed `SplitTableRegionProcedure`/
`MergeTableRegionsProcedure` that would also try to assign them. Using
CLOSED avoids this — CLOSED regions are not auto-assigned.

**TLA+ modeling**: The model should maintain separate `regionState` and
`metaTable` variables with potentially different states for daughter/
merged regions during the window between PONR and OPEN_CHILDREN/
OPEN_MERGED. The `MetaConsistency` invariant must be relaxed for regions
in `SPLITTING_NEW` or `MERGING_NEW` state:

```tla
MetaConsistency ==
    \A r \in Regions :
        regionKeyRange[r] # NoRange =>
            \/ regionState[r].state \in {"SPLITTING_NEW", "MERGING_NEW"}
               \* meta says CLOSED while memory says SPLITTING_NEW/MERGING_NEW
            \/ regionState[r].state = metaTable[r].state
```

**Source**: `RegionStateStore.java` `splitRegion()` L392-395 (daughters
stored as CLOSED in meta).

### C.8 Interaction with ServerCrashProcedure

When a RegionServer crashes during a split or merge operation:

**Scenario 1: Crash before PONR (parent still in SPLITTING/MERGING)**

- SCP detects the crash.
- SCP iterates regions on the crashed server.
- If the region has an attached split/merge procedure:
  - SCP calls `serverCrashed()` on the child TRSP (unassign procedure).
  - The child TRSP handles the crash (region becomes ABNORMALLY_CLOSED).
  - The parent split/merge procedure sees the child failed.
  - Since pre-PONR, rollback is triggered: parent reverts to OPEN,
    daughter/merged region artifacts are cleaned up.

**Scenario 2: Crash after PONR (parent is SPLIT, daughters are SPLITTING_NEW)**

- SCP detects the crash.
- The split/merge procedure is post-PONR — no rollback.
- If child TRSP(ASSIGN) for daughters was in progress and targeted the
  crashed server, SCP calls `serverCrashed()` on it.
- The TRSP retries assignment to a different server.
- Split/merge procedure eventually completes.

**Scenario 3: Master crash during split/merge**

- Procedure state is in ProcedureStore.
- On master restart, procedure is reloaded and resumed from last
  persisted state.
- If pre-PONR: may need to re-validate state, potentially rollback.
- If post-PONR: continues forward from persisted state.
- Daughter/merged region state in meta is CLOSED (see C.8), so no
  conflict with auto-assignment.

**TLA+ modeling**: The `ServerCrash(s)` action should check for split/merge
procedures attached to regions on the crashed server and trigger the
appropriate crash handling (rollback if pre-PONR, retry if post-PONR).

---

## Appendix D: Meta Write Failure Patterns

This appendix documents the three distinct meta-write persistence patterns in the
implementation and their failure/revert behavior, explaining why the TLA+ model
treats meta writes as atomic (always-succeeding) and identifying the iteration
where this abstraction is refined.

### D.1 Overview

The model treats every action as an atomic update of both `regionState` (in-memory)
and `metaTable` (persistent). In the implementation, meta writes can fail,
triggering revert logic that restores the in-memory state to its pre-transition
value while leaving meta unchanged. The procedure lock is held throughout, so the
transient inconsistency is never observable by other procedures.

Three distinct patterns are used:

| Pattern | Used by | Revert on failure |
|---------|---------|-------------------|
| **A: `transitStateAndUpdate()`** | `regionOpening()`, `regionClosing()` | State only (location NOT reverted) |
| **B: Direct setState + meta write** | `regionFailedOpen()`, `regionClosedAbnormally()` | State AND location |
| **C: Two-step persistence** | `regionOpened...()` + `persistToMeta()`, `regionClosed...()` + `persistToMeta()` | No revert; retry until success |

### D.2 Pattern A: `transitStateAndUpdate()` — State-Only Revert

Used by `regionOpening()` (→OPENING) and `regionClosing()` (→CLOSING).

```
1.  transitionState(newState)       — in-memory state changes
2.  regionStateStore.update(...)    — meta write attempted
3.  IF meta write fails:
      regionNode.setState(oldState) — revert STATE only, NOT location
```

**Source**: `AssignmentManager.java` `transitStateAndUpdate()` L2190-2208.

**Asymmetric revert observation**: For `regionOpening()`, the TRSP sets
`regionNode.setRegionLocation(targetServer)` in `queueAssign()` (TRSP.java
L249-260) BEFORE calling `regionOpening()`. If the meta write fails, the state
reverts (e.g., OPENING back to OFFLINE) but the location remains set to the
target server. This creates a transient state `(state=OFFLINE, location=server)`
that would violate the model's `OfflineImpliesNoLocation` invariant.

By contrast, `regionClosing()` does not change the location (it retains the
existing server), so the revert is clean — the state goes back to OPEN with
the original location, which is a valid consistent state.

The asymmetry between Pattern A (state-only revert) and Pattern B (full revert)
is not a bug because the RegionStateNode lock prevents any concurrent procedure
from observing the transient state. The TRSP retries the same step on the next
execution cycle.

### D.3 Pattern B: Full Revert (State + Location)

Used by `regionFailedOpen()` (→FAILED_OPEN, when `giveUp=true`) and
`regionClosedAbnormally()` (→ABNORMALLY_CLOSED).

```
1.  setState(newState)                      — in-memory state changes
2.  setRegionLocation(null)                 — in-memory location cleared
3.  regionStateStore.update(...)            — meta write attempted
4.  IF meta write fails:
      regionNode.setState(oldState)         — revert state
      regionNode.setRegionLocation(oldLoc)  — revert location
```

**Source**: `AssignmentManager.java` `regionFailedOpen()` L2236-2261,
`regionClosedAbnormally()` L2320-2340.

Both state and location are reverted, restoring the region to its pre-transition
state. The procedure retries on the next execution cycle.

### D.4 Pattern C: Two-Step Persistence (No Revert)

Used for the OPENING→OPEN and CLOSING→CLOSED transitions, which are reported
by the RegionServer via `ReportRegionStateTransition`.

```
Step 1 (in RegionRemoteProcedureBase.reportTransition):
  regionOpenedWithoutPersistingToMeta()   — in-memory: OPENING → OPEN
  OR regionClosedWithoutPersistingToMeta() — in-memory: CLOSING → CLOSED
  persist procedure state to ProcedureStore (WAL)
  wake procedure

Step 2 (in RegionRemoteProcedureBase.execute, REPORT_SUCCEED state):
  persistToMeta(regionNode)               — meta write attempted
  IF meta write fails:
    procedure suspended, retried          — NO revert of in-memory state
  IF meta write succeeds:
    unattach procedure from region
```

**Source**: `AssignmentManager.java` `regionOpenedWithoutPersistingToMeta()` L2283-2289,
`regionClosedWithoutPersistingToMeta()` L2292-2301, `persistToMeta()` L2304-2316;
`RegionRemoteProcedureBase.java` `execute()` L352-361.

There is an intentional inconsistency window between steps 1 and 2: in-memory
state says OPEN (or CLOSED) while meta still says OPENING (or CLOSING). No revert
is performed because the region IS genuinely open/closed on the RegionServer — the
in-memory state reflects reality, and meta will catch up on retry.

**Master crash during this window**: If the master crashes after step 1 but before
step 2 succeeds, meta retains the old value (OPENING or CLOSING). The procedure
state REPORT_SUCCEED is persisted to the ProcedureStore (WAL). On master recovery,
the procedure is reloaded and replays step 2, retrying `persistToMeta()`. This is
the core scenario for Iteration 18 (master crash and recovery).

### D.5 GoOffline: No Meta Write

`RegionStateNode.offline()` (RSN.java L132-134) calls `setState(State.OFFLINE)`
and `setRegionLocation(null)` but does NOT write to meta. After `offline()`, the
in-memory state is OFFLINE while meta retains the last persisted value (typically
CLOSED). This divergence is resolved on master restart when in-memory state is
rebuilt from meta.

**Resolved in Iteration 13**: The TLA+ model's `GoOffline` action now matches
the implementation — it updates only `regionState` (in-memory) and does NOT
write to `metaTable`.  `MetaConsistency` is relaxed to permit `regionState =
OFFLINE` while `metaTable = CLOSED`, since both are "unassigned" states.

### D.6 Implications for TLA+ Modeling

The meta write failure patterns are deliberately not modeled in the TLA+
specification. The justification:

1. **Lock discipline masks intermediate states**: The RegionStateNode lock
   (`holdLock() == true`) is held across all steps, including retries. No
   concurrent procedure can observe the transient inconsistency between
   in-memory and meta state. The procedure retries on the next execution cycle.

2. **Retry is captured by TLA+ non-determinism**: An action in TLA+ either fires
   or doesn't. If it doesn't fire in a given step, it may fire in a later step.
   This naturally models the retry semantics without explicit failure/revert logic.

3. **Pattern C is refined in Iteration 18**: The two-step persistence window
   (in-memory updated, meta not yet) becomes non-trivial when master crash is
   introduced. At that point, the model must split the OPENING→OPEN and
   CLOSING→CLOSED transitions into separate in-memory and meta steps, with
   master crash possible between them. The recovery action rebuilds in-memory
   state from meta (which still says OPENING/CLOSING) and replays the procedure.

4. **Meta write failure as an advanced scenario**: Full meta write failure
   modeling (splitting every meta-writing action, adding a `metaWritePending`
   variable, weakening 4 invariants) remains a potential future extension.
   It would roughly double the action count and significantly increase the
   state space, but could validate the revert correctness and the interaction
   between meta write failure and crash recovery.

## Appendix E: Table-Level Procedure Locking Analysis

This appendix documents the table-level locking analysis that informs the
Phase 9 iterations (Iterations 32–35).

### E.1 Implementation Table Lock Mechanism

The `MasterProcedureScheduler` manages a `TableQueue` per table.  Each
table-modifying procedure declares its `TableOperationType` via
`getTableOperationType()`.  The scheduler serializes conflicting operations:

- **Exclusive** (`CREATE`, `DELETE`, `EDIT`): block all other table-level
  procedures including split/merge.  Used by `CreateTableProcedure` (CREATE),
  `DeleteTableProcedure` (DELETE), `TruncateTableProcedure` (EDIT).
  All three have `holdLock() == true`.
- **Shared** (`REGION_SPLIT`, `REGION_MERGE`): block only exclusive ops.
  Multiple splits/merges on different regions of the same table can
  co-exist.  Used by `SplitTableRegionProcedure` (REGION_SPLIT) and
  `MergeTableRegionsProcedure` (REGION_MERGE).  These acquire per-region
  locks via `waitRegions()` for intra-table mutual exclusion on specific
  regions.

Source: `AbstractStateMachineTableProcedure.holdLock()` returns `true`;
`getTableOperationType()` returns CREATE/DELETE/EDIT respectively.
`SplitTableRegionProcedure.getTableOperationType()` returns REGION_SPLIT
(L490); `MergeTableRegionsProcedure.getTableOperationType()` returns
REGION_MERGE (L433).

### E.2 Table Lock Compatibility Matrix

| | CREATE | DELETE | TRUNCATE | SPLIT | MERGE |
|---|---|---|---|---|---|
| **CREATE** | ✗ | ✗ | ✗ | ✗ | ✗ |
| **DELETE** | ✗ | ✗ | ✗ | ✗ | ✗ |
| **TRUNCATE** | ✗ | ✗ | ✗ | ✗ | ✗ |
| **SPLIT** | ✗ | ✗ | ✗ | ✓ | ✓ |
| **MERGE** | ✗ | ✗ | ✗ | ✓ | ✓ |

*Exclusive ops* are mutually incompatible with everything.
*Shared ops* (SPLIT/MERGE) are compatible only with each other.

### E.3 TLA+ Guard Predicate Design

The model emulates the scheduler's shared/exclusive semantics using
predicates over the existing `parentProc[r].type` variable.  No separate
lock variable is introduced.

**Exclusive lock guard** — used by CreateTable, DeleteTable, TruncateTable:

```tla
TableLockFree(t) ==
  \A r \in Regions:
    regionTable[r] = t => parentProc[r].type = "NONE"
```

Requires that no region of table `t` has any active parent procedure
(SPLIT, MERGE, CREATE, DELETE, or TRUNCATE).  This is the exclusive-lock
acquisition check.

**Shared lock guard** — used by SplitPrepare, MergePrepare:

```tla
NoTableExclusiveLock(r) ==
  \A r2 \in Regions:
    (regionTable[r2] = regionTable[r] /\ r2 # r) =>
      parentProc[r2].type \notin {"CREATE", "DELETE", "TRUNCATE"}
```

Permits the split/merge to proceed as long as no exclusive-type procedure
is active on any other region of the same table.  Other SPLIT/MERGE
parent procs on different regions are allowed (shared compatibility).

### E.4 Table Identity Tracking

Table identity is tracked per-region via `regionTable[r] ∈ Tables ∪ {NoTable}`.
Inheritance rules:

- **Split**: daughters inherit the parent's table
  (`regionTable'[dA] = regionTable[r]`).
- **Merge**: merged region inherits the targets' table
  (`regionTable'[m] = regionTable[r1]`).
- **SplitDone/MergeDone**: freed parent/target identifiers cleared
  to `NoTable`.
- **CreateTable**: new regions assigned a fresh table identity.
- **DeleteTable**: all table regions cleared to `NoTable`.
