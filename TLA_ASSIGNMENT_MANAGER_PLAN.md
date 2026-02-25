# TLA+ Model of the HBase AssignmentManager

## 1. Executive Summary

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
MERGED           — Server completed merge (terminal for parents)
SPLITTING_NEW    — Daughter region being created by split
MERGING_NEW      — Merged region being created by merge
FAILED_OPEN      — Open failed, no more retries
FAILED_CLOSE     — Close failed, no more retries
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
            FAILED_OPEN               │      FAILED_CLOSE             │
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
SERVER_CRASH_SPLIT_LOGS                 — Split server WALs
SERVER_CRASH_ASSIGN                     — Create child TRSPs to reassign regions
SERVER_CRASH_CLAIM_REPLICATION_QUEUES   — Claim replication queues
SERVER_CRASH_FINISH                     — Cleanup
```

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

4. **No Lost Regions**: Every region that was OPEN before a crash is eventually reassigned.
   - `□(serverCrashed(s) ∧ regionOn(r, s) ⇒ ◇ regionState[r].state = OPEN)`

5. **Procedure Atomicity**: Each procedure either completes fully or is fully rolled back.
   - For pre-PONR states, rollback is possible. After PONR (e.g., meta update in split/merge), the procedure must complete.

6. **Lock Exclusivity**: At most one `TransitRegionStateProcedure` is attached to a `RegionStateNode` at any time.

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
AssignmentManager.tla   (monolithic spec, iteratively built)
AssignmentManager.cfg   (TLC model configuration)
```

### 5.2 Abstraction Decisions

The following table documents what is modeled concretely vs. abstracted:

| Aspect | Modeling Decision | Rationale |
|--------|-------------------|-----------|
| Region state machine | **Concrete** | Core of the model; exact states and transitions |
| TRSP state machine | **Concrete** | The heart of assignment logic |
| RegionRemoteProcedure (Open/Close) | **Merged into TRSP** | Simplify by treating open/close dispatch as atomic TRSP actions |
| RS open/close execution | **Concrete** | Models the RS-side lifecycle and failure modes |
| ProcedureExecutor | **Abstract** | Model execute/suspend/resume/crash-recover, not thread pool details |
| ProcedureStore (WAL) | **Abstract** | Model as a persistent set of procedure states; no WAL rolling details |
| hbase:meta | **Abstract** | Model as a function `Region → (State, Server)` |
| ZooKeeper crash detection | **Abstract** | Model as non-deterministic crash detection with delay |
| Network/RPC | **Abstract** | Model as unreliable async message channels (can lose, reorder, duplicate) |
| RegionStateNode locking | **Concrete** | Critical for mutual exclusion; model as per-region mutex |
| ServerStateNode locking | **Concrete** | Read/write locks for server state |
| Load balancer | **Abstract** | Non-deterministic choice of move targets |
| Split/Merge procedures | **Deferred** (Phase 3) | Complex; build after core model is validated |
| ServerCrashProcedure | **Concrete** | Critical failure recovery path |
| Coprocessor hooks | **Omitted** | Not relevant to correctness of assignment protocol |
| Replication queues | **Omitted** | Orthogonal concern |
| Table enable/disable | **Deferred** | Can be added as a constraint on assignment |

### 5.3 Model Constants and Variables

```tla
CONSTANTS
    Regions,          \* Set of region identifiers
    Servers,          \* Set of regionserver identifiers
    MaxRetries        \* Maximum open/close retries before FAILED_OPEN/FAILED_CLOSE

VARIABLES
    \* --- Master-side state ---
    regionState,      \* [Regions → RegionStateRecord]
                      \*   where RegionStateRecord = [state: State, location: Server ∪ {None},
                      \*                              procedure: ProcId ∪ {None}]
    serverState,      \* [Servers → {ONLINE, CRASHED, OFFLINE}]
    procedures,       \* [ProcId → ProcedureRecord]
                      \*   where ProcedureRecord = [type: ProcType, state: ProcState,
                      \*                            region: Region, targetServer: Server, ...]
    procStore,        \* Set of ProcedureRecord (persisted to WAL)
    metaTable,        \* [Regions → MetaRecord]  (persistent state in hbase:meta)
                      \*   where MetaRecord = [state: State, server: Server ∪ {None}]
    nextProcId,       \* Nat (monotonically increasing procedure ID)

    \* --- RegionServer-side state ---
    rsOnlineRegions,  \* [Servers → SUBSET Regions]
    rsTransitions,    \* [Servers → [Regions → {Opening, Closing, None}]]

    \* --- Communication ---
    masterToRS,       \* Set of Message  (master → RS commands)
    rsToMaster,       \* Set of Message  (RS → master reports)

    \* --- Failure model ---
    masterAlive,      \* BOOLEAN
    serverAlive       \* [Servers → BOOLEAN]
```

---

## 6. Getting Started

### Prerequisites

- Cursor/VS Code with the TLA+ extension (`tlaplus.vscode-ide`)
- Java 11+ (**important**: the TLA+ tools jar requires class file version
  55.0; the default `java` on this system is temurin-8, which will fail
  with `UnsupportedClassVersionError`)
- Familiarity with PlusCal (optional, for algorithmic notation before
  translating to TLA+)

### Running TLC via the TLA+ MCP Server (Preferred)

The TLA+ extension exposes an MCP server
(`user-tlaplus.vscode-ide-extension-TLA_MCP_Server`) with tools that
handle Java selection, classpath, and worker configuration automatically.
**This is the recommended method for AI agents and interactive use.**

**Required setting** (already configured in Cursor user `settings.json`):

```json
"tlaplus.java.home": "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
```

Without this, the extension uses the default `java` on PATH (temurin-8),
which fails with `UnsupportedClassVersionError` (class file version 55.0
requires Java 11+).

| MCP Tool | Purpose |
|----------|---------|
| `tlaplus_mcp_sany_parse` | Syntax/level check only (no model checking). Fast. |
| `tlaplus_mcp_tlc_check` | **Exhaustive model check** — verifies all invariants and properties. Use for iteration verification. |
| `tlaplus_mcp_tlc_smoke` | Simulation-mode smoke test (random behaviors, time-limited). Good for quick sanity checks. |
| `tlaplus_mcp_tlc_explore` | Generate and print a random behavior of a given length. Useful for understanding the spec. |
| `tlaplus_mcp_tlc_trace` | Replay a previously generated TLC counterexample trace file. |

**Exhaustive check** (standard iteration verification):

```
CallMcpTool:
  server: user-tlaplus.vscode-ide-extension-TLA_MCP_Server
  toolName: tlaplus_mcp_tlc_check
  arguments:
    fileName: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager.tla
    cfgFile: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager.cfg
    extraOpts: ["-workers", "auto", "-cleanup"]
```

**Parse check only** (verify syntax before running TLC):

```
CallMcpTool:
  server: user-tlaplus.vscode-ide-extension-TLA_MCP_Server
  toolName: tlaplus_mcp_sany_parse
  arguments:
    fileName: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager.tla
```

---

## 7. Iterative Development Plan

Each iteration introduces exactly one new concept, produces a spec that
TLC can verify, and is small enough to review and debug in isolation.
Iterations are grouped into phases for readability, but the unit of work
is the individual iteration.

### Phase 1: Master-Side Foundation

#### Iteration 1 — Region states and valid transitions ✅ COMPLETE

**File**: `AssignmentManager.tla` (originally `RegionStates.tla`, renamed at Iteration 2)
**What was added**:
- `State` type: 7 core states (OFFLINE through ABNORMALLY_CLOSED)
- `ValidTransition` relation (10 valid transitions)
- Per-region record: `[state, location]`
- 7 actions: `BeginOpen`, `ConfirmOpened`, `FailOpen`, `BeginClose`,
  `ConfirmClosed`, `GoOffline`, `ServerCrash`
- Invariants: `TypeOK`, `OpenImpliesLocation`, `OfflineImpliesNoLocation`,
  `SingleAssignment`, `TransitionValid` (action constraint)
**TLC result**: 3 regions, 3 servers → 2,197 states, all pass.

#### Iteration 2 — Meta table as persistent state ✅ COMPLETE

**File**: `AssignmentManager.tla` (updated), `AssignmentManager.cfg` (updated)
**What was added**:
- `metaTable` variable: `[Regions → [state: State, location: Servers ∪ {None}]]`
- Every action atomically updates both `regionState` and `metaTable`
  (per Appendix A, Section A.8, item 2: the RegionStateNode lock is held
  across both in-memory and meta writes, so they are a single atomic step)
- `TypeOK` extended with `metaTable` type constraint
- New invariant: `MetaConsistency` — `metaTable[r] = regionState[r]` for
  all regions at all times (trivially true by construction in this iteration;
  becomes non-trivial when master crash breaks the symmetry in Iteration 19)
- `vars` tuple updated from `<<regionState>>` to `<<regionState, metaTable>>`
**Why separate**: Introduces the concept of persistent vs. in-memory state
before any procedures exist. When master crash is added later, meta
survives but in-memory state is lost — this distinction will matter.
**TLC result**: 3 regions, 3 servers → 2,197 distinct states (14,197 total),
depth 13, all 5 invariants + TransitionValid action constraint pass.
State count unchanged from Iteration 1 (metaTable is a dependent variable).

#### Iteration 3 — Procedure attachment (per-region mutex) ✅ COMPLETE

**File**: `AssignmentManager.tla` (updated), `AssignmentManager.cfg` (updated)
**What was added**:
- `procedure` field on each region's `regionState` record: `None` when
  no procedure is attached, `TRUE` when some procedure holds the lock.
  (Actual procedure identity deferred to Iteration 4.)
- `BeginOpen` and `BeginClose` now guard on `procedure = None` and set
  it to `TRUE` (lock acquire).
- `ConfirmOpened`, `ConfirmClosed`, and `FailOpen` now clear `procedure`
  back to `None` (lock release).
- `GoOffline` and `ServerCrash` are external actions that preserve the
  `procedure` field as-is (they do not acquire or release a lock).
- `MetaConsistency` updated to compare `state` and `location` fields
  individually (metaTable records do not carry the `procedure` field,
  since procedures are master in-memory state, not persisted to meta).
- New invariant: `LockExclusivity` — a procedure is attached only during
  transitional states (OPENING or CLOSING). The "at most one procedure
  per region" aspect is trivially true by construction (scalar field),
  but the state correlation is a non-trivial check that acquire/release
  is correctly paired with state transitions.
- `TypeOK` extended with `procedure : {None, TRUE}` on regionState.
**Why separate**: Establishes the mutual exclusion discipline before the
procedure itself has any internal state. The `procedure` field is the
TLA+ analog of `RegionStateNodeLock` + `holdLock() == true`.
**Source**: `RegionStateNode.java` `setProcedure()` L213-218,
`unsetProcedure()` L220-224.
**TLC result**: 3 regions, 3 servers → 2,197 distinct states (14,197 total),
depth 13, all 6 invariants + TransitionValid action constraint pass.
State count unchanged from Iteration 2 (procedure field is a dependent
variable — deterministically derived from state transitions).

#### Iteration 4 — TRSP state machine for ASSIGN (master-side only) ✅ COMPLETE

**File**: `AssignmentManager.tla` (updated), `AssignmentManager.cfg` (updated)
**What was added**:
- `procedures` variable: function from procedure IDs (Nat) to records
  `[type, trspState, region, targetServer]`. The ASSIGN procedure has
  states `GET_ASSIGN_CANDIDATE → OPEN → CONFIRM_OPENED → (removed)`.
- `nextProcId` variable: monotonically increasing counter for procedure
  ID allocation, starting at 1.
- `regionState[r].procedure` field changed from `{None, TRUE}` to
  `{None} ∪ Nat` — now holds the actual procedure ID instead of a
  boolean sentinel.
- Replaced the monolithic `BeginOpen`/`ConfirmOpened` actions with four
  TRSP-step actions:
  - `TRSPCreate(r)`: Create ASSIGN procedure, attach to region, initial
    state `GET_ASSIGN_CANDIDATE`. Region state is NOT changed yet.
  - `TRSPGetCandidate(pid, s)`: Non-deterministically choose server, set
    `targetServer`, advance to `OPEN`.
  - `TRSPOpen(pid)`: Set `regionState = OPENING`, `location = targetServer`,
    update meta, advance to `CONFIRM_OPENED`.
  - `TRSPConfirmOpened(pid)`: Set `regionState = OPEN`, update meta,
    remove procedure and detach from region.
- `BeginClose` and `ConfirmClosed` adapted to create/remove procedure
  records (type `"UNASSIGN"`, trspState `"CLOSE"` placeholder) so the
  procedure representation is uniform across assign and unassign paths.
- `FailOpen` adapted to remove the procedure on failure.
- `GoOffline` now guards on `procedure = None` to respect the RSN lock.
- `LockExclusivity` updated: procedure may be attached during
  pre-transitional states (OFFLINE, CLOSED, ABNORMALLY_CLOSED,
  FAILED_OPEN) in addition to transitional states (OPENING, CLOSING),
  reflecting that the TRSP attaches before driving state transitions.
- New invariant: `ProcedureConsistency` — bidirectional consistency
  between `regionState[r].procedure` and `procedures[pid].region`.
- `TypeOK` extended with `procedures`, `nextProcId`, and updated
  procedure field type.
- `vars` tuple updated to `<<regionState, metaTable, procedures, nextProcId>>`.
- `StateConstraint` added: `nextProcId <= 7` to bound TLC state space.
- Helper operators `AddProc`/`RemoveProc` for function domain manipulation.
- `TRSPState` defined: `{"GET_ASSIGN_CANDIDATE", "OPEN", "CONFIRM_OPENED", "CLOSE"}`.
**No RS side yet** — the TRSP drives the state machine directly. This is
the master's view in isolation.
**Source**: `TransitRegionStateProcedure.java` `executeFromState()` L483-531,
`queueAssign()` L246-278, `openRegion()` L293-311, `confirmOpened()` L320-374.
**TLC result**: 3 regions, 3 servers, nextProcId ≤ 7 → 829,329 distinct
states (3,845,782 total), depth 26, all 7 invariants (TypeOK,
OpenImpliesLocation, OfflineImpliesNoLocation, SingleAssignment,
MetaConsistency, LockExclusivity, ProcedureConsistency) +
TransitionValid action constraint pass. ~3 seconds on 16 workers.
State count increased from 2,197 (Iteration 3) due to TRSP intermediate
states and procedure ID allocation.

#### Iteration 5 — TRSP state machine for UNASSIGN ✅ COMPLETE

**File**: `AssignmentManager.tla` (updated), `AssignmentManager.cfg` (unchanged)
**What was added**:
- Replaced the placeholder `BeginClose`/`ConfirmClosed` actions with
  three TRSP-step actions for the UNASSIGN path:
  - `TRSPCreateUnassign(r)`: Pre: region is OPEN, no procedure. Create
    UNASSIGN procedure in CLOSE state, attach to region. Region stays
    OPEN (state change deferred to TRSPClose).
  - `TRSPClose(pid)`: Pre: UNASSIGN procedure in CLOSE state, region
    OPEN. Transition to CLOSING, update meta, advance to CONFIRM_CLOSED.
  - `TRSPConfirmClosed(pid)`: Pre: UNASSIGN in CONFIRM_CLOSED, region
    CLOSING. Transition to CLOSED, clear location, update meta, remove
    procedure.
- Added `"CONFIRM_CLOSED"` to `TRSPState`.
- `LockExclusivity` strengthened: now correlates procedure type with
  valid region states (ASSIGN may be attached during {OFFLINE, CLOSED,
  ABNORMALLY_CLOSED, FAILED_OPEN, OPENING}; UNASSIGN during {OPEN,
  CLOSING, ABNORMALLY_CLOSED}). Previously a flat set that would have
  become vacuous with OPEN added.
- Deadlock initially detected when ServerCrash strands UNASSIGN
  procedures on ABNORMALLY_CLOSED regions (TRSPClose requires OPEN).
  Resolved by adding `TRSPServerCrashed(pid)` action: when a procedure's
  region is ABNORMALLY_CLOSED, the procedure converts to ASSIGN at
  GET_ASSIGN_CANDIDATE (models the `serverCrashed()` callback plus
  the `closeRegion()` recovery branch where forceNewPlan is set).
  This is the TRSP's self-recovery logic — the full SCP orchestration
  of WHEN this fires remains in Iterations 14-16.
**Source**: `TransitRegionStateProcedure.java` `closeRegion()` L389-407,
`confirmClosed()` L409-446, `serverCrashed()` L566-586.
**TLC result**: 3 regions, 3 servers, nextProcId ≤ 7 →
1,441,599 distinct states (7,142,467 total), depth 27, all 7 invariants
(TypeOK, OpenImpliesLocation, OfflineImpliesNoLocation, SingleAssignment,
MetaConsistency, LockExclusivity, ProcedureConsistency) + TransitionValid
action constraint pass. No deadlock. ~6 seconds on 16 workers. State
count increased from 829,329 (Iteration 4) due to UNASSIGN TRSP
intermediate states and crash recovery paths.

---

### Phase 2: RPC Channels and RegionServer Side

#### Iteration 6 — RPC channels (data structures only) ✅ COMPLETE

**File**: `AssignmentManager.tla` (updated), `AssignmentManager.cfg` (updated)
**What was added**:
- `CommandType` set: `{"OPEN", "CLOSE"}` — RPC command types from master
  to RS (RSProcedureDispatcher dispatches OpenRegionProcedure /
  CloseRegionProcedure).
- `ReportCode` set: `{"OPENED", "FAILED_OPEN", "CLOSED"}` — transition
  codes reported from RS back to master.
- `dispatchedOps` variable: `[Servers → SUBSET [type : CommandType,
  region : Regions, procId : Nat]]` — master→RS command channel, per
  server. Commands remain until consumed by RS-side actions or discarded
  on dispatch failure / server crash.
- `pendingReports` variable: subset of `[server : Servers, region :
  Regions, code : ReportCode, procId : Nat]` — RS→master report channel.
  Reports remain until consumed by master-side actions.
- `rpcVars` shorthand: `<<dispatchedOps, pendingReports>>` used in
  UNCHANGED clauses throughout.
- `vars` tuple extended to 6 elements:
  `<<regionState, metaTable, procedures, nextProcId, dispatchedOps,
  pendingReports>>`.
- `TypeOK` extended with type constraints for both new variables.
- `Init` extended: `dispatchedOps = [s ∈ Servers ↦ {}]`,
  `pendingReports = {}`.
- All 11 existing actions updated with `UNCHANGED rpcVars` (or
  `UNCHANGED <<..., rpcVars>>`).
- No actions produce or consume messages yet — channels remain empty
  throughout. This is expected and resolved in Iterations 7-9.
**Why separate**: Establishes the channel data structures before any
actions produce or consume messages. Ensures the type invariant is
correct before building on it.
**TLC result**: 3 regions, 3 servers, nextProcId ≤ 7 →
1,441,599 distinct states (7,142,467 total), depth 27, all 7 invariants
(TypeOK, OpenImpliesLocation, OfflineImpliesNoLocation, SingleAssignment,
MetaConsistency, LockExclusivity, ProcedureConsistency) + TransitionValid
action constraint pass. ~56 seconds on 1 worker (16 cores).
State count unchanged from Iteration 5 (dispatchedOps and pendingReports
are constant empty — dependent variables with no new state).

#### Iteration 7 — Master dispatches open command via RPC ✅ COMPLETE

**File**: `AssignmentManager.tla` (updated), `AssignmentManager.cfg` (updated)
**What was changed**:
- Renamed `TRSPOpen(pid)` to `TRSPDispatchOpen(pid)`: same logic (set
  `regionState = OPENING`, update meta, advance TRSP to `CONFIRM_OPENED`)
  **plus** adds an `[type |-> "OPEN", region |-> r, procId |-> pid]`
  command record to `dispatchedOps[targetServer]`.
- `TRSPConfirmOpened(pid)` now requires consuming a matching `OPENED`
  report from `pendingReports` (existential quantification over reports
  with matching region, code, and procId). The consumed report is removed
  from `pendingReports`. Until RS-side actions are added (Iteration 8),
  no reports are produced, so this action is never enabled — regions may
  reach OPENING but cannot advance to OPEN (expected, resolved next
  iteration).
**What was added**:
- `DispatchFail(pid)`: Non-deterministic RPC failure. Pre: ASSIGN
  procedure in `CONFIRM_OPENED` state, matching open command exists in
  `dispatchedOps[targetServer]`. Post: command removed, TRSP reset to
  `GET_ASSIGN_CANDIDATE`, `targetServer` cleared (`forceNewPlan`).
  Region remains OPENING with current location; the next
  `TRSPDispatchOpen` will update location to the new server.
  Source: `RSProcedureDispatcher.java` `remoteCallFailed()` L325-340.
- `Next` relation updated: `TRSPOpen` replaced with `TRSPDispatchOpen`,
  `DispatchFail` added.
**State space management**: The `DispatchFail` retry loop (dispatch →
fail → get_candidate(3 choices) → dispatch → ...) and orphaned commands
from `FailOpen` caused state space explosion (~1.1B states at 3r/3s).
Two mitigations applied:
1. **Symmetry reduction**: Added `TLC` to `EXTENDS`, defined
   `Symmetry == Permutations(Regions) \union Permutations(Servers)`,
   added `SYMMETRY Symmetry` to cfg. Up to 36× reduction for 3r/3s,
   semantically lossless.
2. **FailOpen cleanup**: `FailOpen` now removes the dispatched command
   from `dispatchedOps` (guarded with `IF s \in Servers` for the case
   where `DispatchFail` already cleared `targetServer` to `None`).
   Previously it used `UNCHANGED rpcVars`, leaving orphaned commands.
**TLC result**: 3 regions, 3 servers, nextProcId ≤ 7 →
39,250 distinct states (247,466 total), depth 28, all 7 invariants
(TypeOK, OpenImpliesLocation, OfflineImpliesNoLocation, SingleAssignment,
MetaConsistency, LockExclusivity, ProcedureConsistency) + TransitionValid
action constraint pass. No errors, no warnings. ~1 second on 16 workers.
State count decreased from 1,441,599 (Iteration 6) due to symmetry
reduction, despite the new dispatch/fail branching.

#### Iteration 8 — RS-side open handler and report

**What to add**: RS-side variables:
- `rsOnlineRegions`: `[Servers → SUBSET Regions]`
- `rsTransitions`: `[Servers → [Regions → {"Opening", "Closing", None}]]`

RS-side actions:
- `RSReceiveOpen(s, r)`: Dequeue open command from `dispatchedOps[s]`,
  set `rsTransitions[s][r] = "Opening"`.
- `RSCompleteOpen(s, r)`: Pre: `rsTransitions[s][r] = "Opening"`. Add
  region to `rsOnlineRegions[s]`, clear transition, add `OPENED` report
  to `pendingReports`.
- `RSFailOpen(s, r)`: Pre: `rsTransitions[s][r] = "Opening"`. Clear
  transition, add `FAILED_OPEN` report to `pendingReports`.
**What to change**: The non-deterministic `FailOpen(r)` action from
earlier iterations should be removed or disabled once `RSFailOpen`
provides the proper RS-side failure path. `FailOpen` currently handles
dispatched command cleanup (added in Iteration 7); `RSFailOpen` produces
a `FAILED_OPEN` report instead, which the master will process in a
later iteration.
**Verify**: The ASSIGN round-trip now completes:
dispatch → RS receive → RS complete → report → master confirm.
All invariants should hold. Symmetry reduction (added in Iteration 7)
keeps state space tractable with the additional RS-side branching.
**Source**: `AssignRegionHandler.java` `process()` L98-164.

#### Iteration 9 — Master dispatches close command and RS close handler

**What to change**: Split `TRSPClose(p)` into dispatch + confirm, same
pattern established in Iteration 7 for the open path:
- `TRSPDispatchClose(p)`: Sets `regionState = CLOSING`, updates meta,
  adds close command to `dispatchedOps[targetServer]`.
- Add `DispatchFailClose(p)` following the same pattern as `DispatchFail`
  for the open path — remove command, reset TRSP to retry. (Note:
  `DispatchFail` from Iteration 7 only handles ASSIGN/open commands;
  close dispatch failure needs its own action or a generalization of
  the existing one.)
RS-side actions:
- `RSReceiveClose(s, r)`: Dequeue close command, set
  `rsTransitions[s][r] = "Closing"`.
- `RSCompleteClose(s, r)`: Close region, remove from `rsOnlineRegions[s]`,
  clear transition, add `CLOSED` report to `pendingReports`.
**What to change**: `TRSPConfirmClosed(p)` now requires consuming a
matching `CLOSED` report from `pendingReports` (same pattern as
`TRSPConfirmOpened` from Iteration 7).
**Verify**: UNASSIGN round-trip now completes. All invariants hold.
**New invariant**: `RSMasterAgreement` — if a region is OPEN in
`regionState` and the procedure is `None` (i.e., stable), then the RS
also has the region in `rsOnlineRegions`.
**Source**: `UnassignRegionHandler.java` `process()` L92-158.

#### Iteration 10 — Master report processing with validation

**What to add**: Explicit `MasterReceiveReport(rpt)` action that:
- Dequeues a report from `pendingReports`.
- Validates the reporting server is ONLINE (pre: `serverState[s] # "CRASHED"`).
- Matches `procId` against the region's attached procedure.
- Updates the procedure state based on `TransitionCode`.
Previously `TRSPConfirmOpened`/`TRSPConfirmClosed` consumed reports
directly; now they are triggered by `MasterReceiveReport`.
**What to add**: `serverState` variable: `[Servers → {"ONLINE", "CRASHED"}]`.
Initially all ONLINE. Reject reports from CRASHED servers.
**Verify**: All invariants hold. Report from unknown/crashed server is
silently dropped.
**Source**: `AssignmentManager.reportRegionStateTransition()` L1256-1299.

---

### Phase 3: MOVE and Failures

#### Iteration 11 — MOVE transition type

**What to add**: MOVE TRSP with state sequence:
`CLOSE → CONFIRM_CLOSED → GET_ASSIGN_CANDIDATE → OPEN → CONFIRM_OPENED → DONE`.
- `TRSPCreateMove(r, targetServer)`: Pre: region is OPEN. Create MOVE
  procedure with initial state `CLOSE`. `targetServer` may be specified
  or `None` (chosen later in GET_ASSIGN_CANDIDATE).
Reuses existing `TRSPDispatchClose`, `TRSPConfirmClosed`,
`TRSPGetCandidate`, `TRSPDispatchOpen`, `TRSPConfirmOpened` actions
— they are parameterized by procedure, not transition type.
**Verify**: Region is OPEN on new server after MOVE completes.
All invariants hold.
**New invariant**: `NoSplitBrain` — a region is never in `rsOnlineRegions`
of two different servers simultaneously.
**Source**: `TransitRegionStateProcedure.java` `TransitionType.MOVE` L160-162.

#### Iteration 12 — Open failures and retry

**What to add**: When `MasterReceiveReport` processes a `FAILED_OPEN`
report:
- Increment retry counter on the procedure.
- If retries < `MaxRetries`: set `forceNewPlan = true`, go back to
  `GET_ASSIGN_CANDIDATE`.
- If retries >= `MaxRetries`: set `regionState = FAILED_OPEN`, update
  meta, detach procedure (give up).
**New constant**: `MaxRetries` (recommend 1-2 for TLC).
**Verify**: Region can reach `FAILED_OPEN` after enough failures.
`TypeOK` updated. All safety invariants hold.
**Source**: `TransitRegionStateProcedure.java` `confirmOpened()` L345-374.

#### Iteration 13 — Dispatch failure (close path and ambiguous delivery)

**Already done** (from Iteration 7): `DispatchFail(pid)` handles open
dispatch failure — removes command from `dispatchedOps`, resets TRSP to
`GET_ASSIGN_CANDIDATE` with `forceNewPlan`.
**Already done** (from Iteration 9): Close dispatch failure should be
handled by `DispatchFailClose` or a generalized `DispatchFail` action.
**What to add**: `DispatchMaybeDelivered(p)` — models the ambiguous case
where the command might or might not have been delivered (the connection
error on retry case from Appendix B.2). In this case the procedure must
NOT try another server — it waits or the server is expired.
**Verify**: No double-assign (region never in `rsOnlineRegions` of two
servers).
**Source**: `RSProcedureDispatcher.scheduleForRetry()` L290-367.

---

### Phase 4: RegionServer Crash and Recovery

#### Iteration 14 — RS crash event

**What to add**: `ServerCrash(s)` action:
- Set `serverState[s] = "CRASHED"`.
- Clear `rsOnlineRegions[s]` and `rsTransitions[s]` (RS state is lost).
- Clear all entries in `dispatchedOps[s]` (in-flight commands lost).
- Pending reports from `s` in `pendingReports` may be retained (RS might
  have sent them before crashing) or cleared (non-deterministic).
**What to add**: For each region with `location = s` and state `OPEN`,
the master sets state to `ABNORMALLY_CLOSED` and clears location.
**Verify**: `TypeOK`. Regions on crashed server become ABNORMALLY_CLOSED.
No reports from crashed server are accepted (server fencing).
**Source**: `ServerManager.expireServer()` L662-720.

#### Iteration 15 — ServerCrashProcedure (basic)

**What to add**: `ServerCrashProcedure` with simplified states:
`START → GET_REGIONS → ASSIGN → DONE`.
- `SCPCreate(s)`: Pre: `serverState[s] = "CRASHED"`. Create SCP.
- `SCPGetRegions(scp)`: Collect set of regions that were on server `s`.
- `SCPAssign(scp)`: For each region in the set, if no procedure
  attached, create a child TRSP(ASSIGN) and attach it.
- `SCPDone(scp)`: All child TRSPs have completed. SCP finishes.
**Verify**: Every region on the crashed server eventually gets a new
TRSP(ASSIGN) created. `NoLostRegions` safety property: after SCP
completes, no region is stuck in ABNORMALLY_CLOSED with no procedure.
**Source**: `ServerCrashProcedure.java` L142-305, `assignRegions()` L562-645.

#### Iteration 16 — SCP interaction with in-flight TRSPs

**Note**: The TRSP's `serverCrashed()` self-recovery logic (procedure
converts to ASSIGN/GET_ASSIGN_CANDIDATE when region is ABNORMALLY_CLOSED)
is already modeled by `TRSPServerCrashed` from Iteration 5.  This
iteration adds the SCP's orchestration of WHEN that callback is invoked.
**What to add**: When SCP encounters a region that already has a TRSP
attached (e.g., an ASSIGN or MOVE was in progress when the server
crashed):
- Call `serverCrashed()` on the TRSP.
- The TRSP sets `forceNewPlan = true` and either:
  - Rewinds to `GET_ASSIGN_CANDIDATE` (if was in OPEN/CONFIRM_OPENED), or
  - Proceeds to `GET_ASSIGN_CANDIDATE` (if was in CONFIRM_CLOSED and the
    region is now ABNORMALLY_CLOSED instead of CLOSED).
- SCP does NOT create a new TRSP for this region — the existing one
  handles recovery.
**Verify**: `NoLostRegions` still holds. No duplicate procedures for
the same region. `LockExclusivity` holds.
**Source**: `ServerCrashProcedure.java` L612-618,
`TransitRegionStateProcedure.serverCrashed()` L566-586.

#### Iteration 17 — Double crash

**What to add**: No new code — this is a **verification-only** iteration.
Allow two servers to crash in the model. Verify that:
- SCP for server A creates TRSPs that target server B.
- If server B also crashes, a second SCP is created.
- The first SCP's TRSPs detect the crash (via `serverCrashed()` or
  dispatch failure) and reassign to server C.
- No regions are lost.
**Config change**: Ensure model has ≥ 3 servers.
**Verify**: `NoLostRegions`, `NoSplitBrain`, `LockExclusivity`.

---

### Phase 5: Procedure Persistence and Master Recovery

#### Iteration 18 — Procedure store

**What to add**: `procStore` variable — a persistent set of procedure
records (survives master crash). Actions that modify procedure state
also persist to `procStore`:
- Procedure creation → insert into `procStore`.
- State transitions → update in `procStore`.
- Procedure completion → delete from `procStore`.
The `procStore` variable is not modified by `ServerCrash` or
`MasterCrash` — it survives both.
**Verify**: `TypeOK` with `procStore`. All existing invariants hold.
`ProcStoreConsistency`: every active procedure in `procedures` has a
matching entry in `procStore`.
**Source**: `WALProcedureStore.java` insert/update/delete L527-694.

#### Iteration 19 — Master crash and recovery

**What to add**: Two new actions:
- `MasterCrash`: Clears ALL in-memory master state: `regionState`,
  `serverState`, `procedures`, `dispatchedOps`, `pendingReports`.
  `metaTable` and `procStore` survive.
- `MasterRecover`:
  1. Rebuild `regionState` from `metaTable` (scan meta).
  2. Reload `procedures` from `procStore`.
  3. Re-attach procedures to regions.
  4. Set `serverState` for all servers to ONLINE (RS will re-register).
  5. Resumed procedures pick up from their last persisted state.
**Pattern C inconsistency window**: This iteration must model the
two-step persistence pattern for OPENING→OPEN and CLOSING→CLOSED
transitions (see Appendix D.4). In-memory state is updated first
(`regionOpenedWithoutPersistingToMeta`), then meta is updated
separately (`persistToMeta`). If the master crashes between these
steps, meta retains the old value (OPENING or CLOSING) while the
procedure state in `procStore` is REPORT_SUCCEED. On recovery, the
procedure replays `persistToMeta` to resolve the inconsistency.
The OPENING→OPEN and CLOSING→CLOSED actions should be split into
separate in-memory and meta-persist steps, with `MasterCrash`
possible between them. `MetaConsistency` must be relaxed to allow
divergence when a procedure's persisted state is REPORT_SUCCEED
(indicating the in-memory update completed but meta has not caught up).
**Verify**: After `MasterRecover`, the system eventually reaches a
consistent state. `MetaConsistency` holds after recovery. No lost
regions. No stuck procedures.
**Source**: `ProcedureExecutor.java` `load()` L328-609,
`AssignmentManager.start()` L313-362.

---

### Phase 6: Split and Merge (Deferred)

#### Iteration 20 — Split/merge region states and region pool

**What to add**: Extend `State` with `SPLITTING`, `SPLIT`,
`SPLITTING_NEW`, `MERGING`, `MERGED`, `MERGING_NEW`. Extend
`ValidTransition` with the split/merge transitions from Appendix C.4.
Add `DaughterPool` constant and `regionExists` variable to model
dynamic region creation/deletion (see Appendix C.6). Regions in the
`DaughterPool` start with `regionExists = FALSE` and `state = OFFLINE`.
All actions guard on `regionExists[r] = TRUE`.
**Verify**: `TypeOK` and `TransitionValid` with the extended state space.
TLC with 2 primary regions, 4 daughter pool slots, 2 servers.

#### Iteration 21 — Split procedure: PREPARE through CLOSE_PARENT

**What to add**: Split procedure state machine, initially covering only
the pre-PONR states: `SPLIT_PREPARE → SPLIT_CLOSE_PARENT →
SPLIT_CHECK_CLOSED`.
- `SplitPrepare(parent, dA, dB)`: Pre: parent is OPEN, no procedure on
  parent/dA/dB, dA and dB are in DaughterPool with `regionExists = FALSE`.
  Set parent to SPLITTING, attach split procedure to parent, dA, dB.
- `SplitCloseParent(p)`: Create child TRSP(UNASSIGN) for parent.
  Parent transitions: SPLITTING → CLOSING → CLOSED (via TRSP).
- `SplitCheckClosed(p)`: Verify parent is CLOSED. Advance to
  SPLIT_UPDATE_META (added in next iteration).
Multi-region locking: split procedure is attached to all three regions.
**Verify**: Parent reaches CLOSED. `LockExclusivity` holds.
No daughters exist yet (`regionExists` still FALSE).
**Source**: `SplitTableRegionProcedure.java` `prepareSplitRegion()` L509-593,
`createUnassignProcedures()` L950-954.

#### Iteration 22 — Split PONR: atomic meta update creates daughters

**What to add**: The PONR step:
- `SplitUpdateMeta(p)`: Atomically:
  - Set parent to SPLIT in both memory and meta.
  - Set `regionExists[dA] = TRUE`, `regionExists[dB] = TRUE`.
  - Set daughters to SPLITTING_NEW in memory.
  - Set daughters to CLOSED in meta (intentional discrepancy, see C.8).
  After this action, rollback is forbidden.
**Relax invariant**: `MetaConsistency` must allow
`regionState = SPLITTING_NEW` while `metaTable = CLOSED`.
**Verify**: `TypeOK`. Daughters now exist. Parent is SPLIT.
`SplitAtomicity`: pre-PONR states have no daughter entries in meta.
**Source**: `AssignmentManager.markRegionAsSplit()` L2364-2390,
`RegionStateStore.splitRegion()` L367-410.

#### Iteration 23 — Split post-PONR: open daughters

**What to add**:
- `SplitOpenChildren(p)`: Create child TRSP(ASSIGN) for each daughter.
  Daughters transition: SPLITTING_NEW → OPENING → OPEN (via TRSP).
- `SplitDone(p)`: All child TRSPs complete. Detach split procedure from
  parent, dA, dB.
**Verify**: `SplitCompleteness` — after `SplitDone`, both daughters are
OPEN and parent is SPLIT. `NoOrphanedDaughters` — every SPLITTING_NEW
region has a parent split procedure.
**Source**: `SplitTableRegionProcedure.java` `createAssignProcedures()` L956-963.

#### Iteration 24 — Split pre-PONR rollback

**What to add**: Non-deterministic failure action for pre-PONR states:
- `SplitFail(p)`: Pre: split procedure is in a pre-PONR state
  (PREPARE, CLOSE_PARENT, CHECK_CLOSED). Triggers rollback.
- `SplitRollback(p)`: Revert parent from SPLITTING to OPEN (if in
  PREPARE) or create TRSP(ASSIGN) to reopen parent (if parent was
  already CLOSED). Set `regionExists[dA] = FALSE`,
  `regionExists[dB] = FALSE`. Detach procedure from all regions.
**Verify**: After rollback, parent is OPEN (or being reassigned), no
daughters exist, no procedures attached. All safety invariants hold.
**Source**: `SplitTableRegionProcedure.java` `rollbackState()` L368-411.

#### Iteration 25 — Merge procedure (full lifecycle)

**What to add**: Complete merge procedure following the same pattern as
split:
- `MergePrepare(r1, r2, m)`: Pre: r1, r2 OPEN, no procedures attached,
  m in DaughterPool with `regionExists = FALSE`. Set r1, r2 to MERGING.
- `MergeCloseRegions(p)`: Create child TRSP(UNASSIGN) for each parent.
- `MergeCheckClosed(p)`: Verify all parents CLOSED.
- `MergeCreateMerged(p)`: Set `regionExists[m] = TRUE`, set m to
  MERGING_NEW in memory.
- `MergeUpdateMeta(p)`: **PONR**: Set parents to MERGED (terminal),
  create merged region as CLOSED in meta, MERGING_NEW in memory.
- `MergeOpenMerged(p)`: Create child TRSP(ASSIGN) for merged region.
- `MergeDone(p)`: Detach procedure from all regions.
- `MergeRollback(p)`: Pre-PONR only: revert parents to OPEN, delete
  merged, detach procedure.
**Verify**: `MergeCompleteness` — after done, merged region is OPEN,
parents are MERGED. All safety invariants hold.
**Source**: `MergeTableRegionsProcedure.java` `executeFromState()` L189-255.

#### Iteration 26 — Crash during split/merge

**What to add**: No new actions — this is a **verification-only** iteration.
Configure TLC to allow RS crash and master crash during active
split/merge procedures. Verify:
- Pre-PONR crash → rollback succeeds, parent reopens.
- Post-PONR crash → procedure resumes and completes.
- SCP interaction: SCP calls `serverCrashed()` on child TRSPs of the
  split/merge procedure; child TRSPs reassign to new server.
- `NoLostRegions`, `NoSplitBrain`, `SplitCompleteness`,
  `MergeCompleteness` all hold under crash scenarios.
**Source**: See Appendix C.9 for crash interaction analysis.

---

### Phase 7: Liveness and Refinement (Deferred)

#### Iteration 27 — Fairness and liveness

**What to add**: Weak fairness on procedure execution, strong fairness
on message delivery. Check temporal properties:
- `□(regionState[r].state = "OFFLINE" ⇒ ◇ regionState[r].state = "OPEN")`
- `□(scp_started(s) ⇒ ◇ scp_done(s))`

#### Iteration 28 — TLC optimization

**What to add**: Symmetry sets for Regions and Servers. State
constraints to bound message queue sizes. Action constraints to limit
crash frequency. Measure state space reduction.

#### Iteration 29 — Advanced scenarios and findings

**What to verify**: Cascade crashes, master failover during SCP,
concurrent split and move on the same region, split during merge of
adjacent regions. Document all counterexamples or confirmed invariants.
**Optional: meta write failure modeling**: Model non-deterministic meta
write failure for all meta-writing actions (see Appendix D). This would
split each meta-writing action into attempt + succeed/fail sub-actions,
add a `metaWritePending` variable, and verify that the three revert
patterns (state-only revert, full revert, no revert) correctly restore
invariants under all crash and concurrency scenarios. Key properties to
check: revert correctness after Pattern A failure (asymmetric revert —
state reverted but location not), interaction between meta write retry
and server crash (SCP blocked by procedure lock during retry), and
GoOffline meta divergence (in-memory OFFLINE while meta shows CLOSED).

---

## 8. Mapping from Code to TLA+ Actions

This table maps each significant code path to its corresponding TLA+ action.

| Code Path | TLA+ Action | Phase |
|-----------|-------------|-------|
| `AssignmentManager.assign()` | `MasterInitiateAssign(r)` | 1 |
| `AssignmentManager.unassign()` | `MasterInitiateUnassign(r)` | 1 |
| `TRSP.queueAssign()` (GET_ASSIGN_CANDIDATE) | `TRSPGetCandidate(p, r)` | 1 |
| `TRSP.openRegion()` (OPEN) | `TRSPDispatchOpen(p, r, s)` | 1 |
| `TRSP.confirmOpened()` (CONFIRM_OPENED) | `TRSPConfirmOpened(p, r)` | 1 |
| `TRSP.closeRegion()` (CLOSE) | `TRSPDispatchClose(p, r, s)` | 1 |
| `TRSP.confirmClosed()` (CONFIRM_CLOSED) | `TRSPConfirmClosed(p, r)` | 1 |
| `AssignRegionHandler.process()` | `RSExecuteOpen(s, r)` | 1 |
| `UnassignRegionHandler.process()` | `RSExecuteClose(s, r)` | 1 |
| `RS.reportRegionStateTransition(OPENED)` | `RSSendOpened(s, r)` | 1 |
| `RS.reportRegionStateTransition(CLOSED)` | `RSSendClosed(s, r)` | 1 |
| `AM.reportRegionStateTransition()` | `MasterReceiveReport(msg)` | 1 |
| `AM.balance()` / `createMoveRegionProcedure()` | `MasterInitiateMove(r, s)` | 2 |
| `RS.reportRegionStateTransition(FAILED_OPEN)` | `RSSendFailedOpen(s, r)` | 2 |
| RS abort on close failure | `RSCrashOnCloseFail(s)` | 2 |
| Open-while-closing conflict | `RSOpenCloseConflict(s, r)` | 2 |
| RS crash (non-deterministic) | `ServerCrash(s)` | 3 |
| ZK crash detection | `DetectCrash(s)` | 3 |
| `SCP.assignRegions()` | `SCPAssignRegions(scp)` | 3 |
| `SCP.serverCrashed()` on TRSP | `SCPInterruptTRSP(scp, p)` | 3 |
| Master crash | `MasterCrash` | 3 |
| Master recovery (load from store) | `MasterRecover` | 3 |
| `SplitTableRegionProcedure.prepareSplitRegion()` | `SplitPrepare(parent, dA, dB)` | 6 |
| `SplitTableRegionProcedure` CLOSE_PARENT | `SplitCloseParent(p)` | 6 |
| `SplitTableRegionProcedure` CHECK_CLOSED | `SplitCheckClosed(p)` | 6 |
| `AssignmentManager.markRegionAsSplit()` | `SplitUpdateMeta(p)` | 6 |
| `SplitTableRegionProcedure` OPEN_CHILDREN | `SplitOpenChildren(p)` | 6 |
| `SplitTableRegionProcedure` completion | `SplitDone(p)` | 6 |
| `SplitTableRegionProcedure.rollbackState()` | `SplitRollback(p)` | 6 |
| `MergeTableRegionsProcedure.prepareMergeRegion()` | `MergePrepare(r1, r2, m)` | 6 |
| `MergeTableRegionsProcedure` CLOSE_REGIONS | `MergeCloseRegions(p)` | 6 |
| `MergeTableRegionsProcedure` CHECK_CLOSED | `MergeCheckClosed(p)` | 6 |
| `MergeTableRegionsProcedure` CREATE_MERGED | `MergeCreateMerged(p)` | 6 |
| `AssignmentManager.markRegionAsMerged()` | `MergeUpdateMeta(p)` | 6 |
| `MergeTableRegionsProcedure` OPEN_MERGED | `MergeOpenMerged(p)` | 6 |
| `MergeTableRegionsProcedure` completion | `MergeDone(p)` | 6 |
| `MergeTableRegionsProcedure.rollbackState()` | `MergeRollback(p)` | 6 |

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

## 10. Estimated Scope and Complexity

| Phase | Iterations | Estimated TLA+ Lines | Key Challenge |
|-------|-----------|---------------------|---------------|
| Phase 1: Master-Side Foundation | 1-5 | ~250 | State machine + procedures in isolation |
| Phase 2: RPC and RegionServer | 6-10 | +200 | Two-channel RPC, RS-side state, report validation |
| Phase 3: MOVE and Failures | 11-13 | +100 | Move lifecycle, retry logic, dispatch ambiguity |
| Phase 4: RS Crash and Recovery | 14-17 | +200 | SCP, TRSP interaction, double crash |
| Phase 5: Procedure Store + Master Recovery | 18-19 | +150 | Persistence, crash+rebuild |
| Phase 6: Split and Merge | 20-26 | +350 | Region pool, multi-region locking, PONR, rollback |
| Phase 7: Liveness and Refinement | 27-29 | +100 | Fairness, optimization, scenarios |
| **Total** | **29** | **~1350** | |

### Model Checking Feasibility

For TLC (explicit state model checker), the state space must be kept manageable:

| Parameter | Recommended TLC Value | Notes |
|-----------|-----------------------|-------|
| `|Regions|` | 2-3 | More than 3 causes state explosion |
| `|Servers|` | 2-3 | Minimum 2 needed for MOVE |
| `MaxRetries` | 1-2 | Keep small for state space |
| Message channels | Bounded (size 2-3) | Prevent unbounded message queues |
| Concurrent procedures | Bounded (2-3) | Limit active procedures |

For larger parameter values, TLAPS (TLA+ Proof System) can be used for
proof-based verification of inductive invariants.

---

## 11. Open Questions and Risks

1. ~~**Meta table modeling granularity**~~: **RESOLVED** — Meta (and all region
   writes) are modeled as immediately consistent and atomic. When the RPC
   returns, the write is persisted. This is the guarantee that HBase's single-
   region-mastering provides. No async replication delay needs to be modeled.

2. ~~**Procedure executor threading**~~: **RESOLVED** — Analysis of the locking
   discipline confirms that TLA+ interleaving semantics are a faithful model.
   See Appendix A for the full analysis.

3. ~~**Network model**~~: **RESOLVED** — Model at the RPC level, faithfully
   representing the two distinct RPCs: `AdminService.ExecuteProcedures`
   (master→RS, dispatch with retry) and
   `RegionServerStatusService.ReportRegionStateTransition` (RS→master, report
   with retry). See Appendix B for the full analysis.

4. ~~**WAL splitting**~~: **RESOLVED** — Modeled as an abstract step in SCP.
   WAL splitting failures are a data correctness problem, not an assignment
   correctness one. No refinement needed.

5. ~~**Master election**~~: **RESOLVED** — Assume a single master. Master
   failover is modeled as crash + recovery (master state lost, procedures
   reloaded from ProcedureStore, region states rebuilt from meta). Split-brain
   is out of scope.

6. ~~**Region replicas**~~: **RESOLVED** — Deferred. The model covers primary
   regions (replicaId=0) only. Read replicas have relaxed constraints (no
   exclusive assignment, staleness tolerated, different lifecycle) and can
   be layered on as an extension without altering the core model.

---

## 12. Iteration Process and Success Criteria

This section defines the methodology for iterating on the TLA+ specification,
the classification scheme for TLC findings, and the criteria for declaring an
iteration complete.

### 12.1 Terminal Outcomes

Every iteration ends in one of two states:

1. **Clean TLC run**: The model checker exhaustively explores the state space
   for the configured parameters and reports zero invariant violations and
   zero property violations. The spec faithfully models the implementation
   and no issues are found.

2. **Legitimate finding**: TLC produces a counterexample trace that, after
   triage (see 12.3), is confirmed to represent a genuine issue in the HBase
   implementation — a bug, a race condition, or a design gap that requires a
   code or architectural change. The finding is documented with full
   traceability (see 12.5) and handed off for remediation.

There is no third "acceptable" terminal state. Spurious violations caused by
modeling errors are intermediate conditions that must be resolved before the
iteration is considered complete.

### 12.2 Per-Iteration Workflow

Each iteration follows a fixed loop:

1. **WRITE / EDIT** — Add or modify spec per the iteration's scope
   (see Section 7 for iteration descriptions).
2. **SYNTAX CHECK** — Parse with SANY. Fix all parse errors before proceeding.
3. **RUN TLC** — Model-check with the documented configuration
   (constants, constraints, symmetry sets — see 12.4).
4. **TRIAGE** — If TLC reports violations, classify each one (see 12.3).
   Repeat from step 1 or 3 as needed.
5. **REGRESSION CHECK** — Re-verify all invariants and properties from
   prior iterations. A fix in iteration N must not break any invariant
   proven in iterations 1 through N-1.
6. **RECORD** — Document the TLC result, configuration, state count,
   and any findings (see 12.4 and 12.5).
7. **UPDATE PLAN** — Mark the iteration complete in this plan document
   (Section 7). Append `✅ COMPLETE` to the iteration heading, convert
   the "What to add" description to past tense ("What was added"), and
   add a `**TLC result**` line summarizing the final model-checking
   outcome (constants, state count, invariants checked, pass/fail).
   If the iteration produced a legitimate finding, note it here with
   its Finding ID (see 12.5). This keeps the plan document as the
   single source of truth for iteration status.
8. **GIT COMMIT** — Commit the successful spec files, configuration,
   updated plan document, and iteration record to version control. The
   commit message must identify the iteration number and summarize the
   outcome (clean pass or legitimate finding). This ensures every
   completed iteration has a recoverable checkpoint and provides an
   auditable history of the specification's evolution.

Steps 1–4 repeat until TLC either passes cleanly or produces a confirmed
legitimate finding. Step 5 is mandatory — no iteration is complete without
a regression check against all prior invariants. Steps 7–8 are the
terminal actions — an iteration is not considered done until the plan
document is updated and the results are committed.

### 12.3 Finding Classification (Triage)

When TLC reports a violation, the counterexample trace must be classified
into exactly one of three categories:

| Category | Description | Resolution |
|----------|-------------|------------|
| **Spec error** | The TLA+ spec does not faithfully model the implementation. The violation is an artifact of incorrect or incomplete modeling — not a real issue. | Fix the spec. The counterexample represents a behavior that cannot occur in the real system due to constraints not yet captured in the model. Common causes: missing preconditions, over-abstracted actions, incorrect transition guards. |
| **Modeling abstraction gap** | The spec's abstraction level is too coarse or too fine for the property being checked. The violation is technically possible in the model but prevented by mechanisms not yet modeled (e.g., a locking protocol from a later iteration, or a retry mechanism not yet introduced). | Refine the abstraction or defer to a later iteration that introduces the missing mechanism. Document the gap and the iteration where it will be resolved. |
| **Legitimate implementation issue** | The counterexample represents a behavior that CAN occur in the real system. The invariant violation maps to a real bug, race condition, or design gap. | Document the finding (see 12.5). Do NOT fix the spec to mask it. The spec is correct — the implementation needs to change. |

**Triage procedure for each counterexample:**

1. Read the full TLC error trace, noting every state transition.
2. For each transition in the trace, identify the corresponding code path
   using the mapping in Section 8.
3. Ask: "Can this exact sequence of events occur in the real system?"
   - If NO → Spec error or abstraction gap. Identify the constraint or
     mechanism that prevents it.
   - If YES → Ask: "Does the violated invariant represent a real safety or
     liveness requirement?"
     - If YES → Legitimate finding.
     - If NO → The invariant is too strong. Weaken it with justification.
4. When uncertain, default to investigating further rather than dismissing.
   Err on the side of treating a finding as legitimate until proven otherwise.

### 12.4 TLC Configuration Documentation

Each iteration must record its TLC configuration and results. This ensures
reproducibility and provides a baseline for regression checks.

**Required fields per iteration:**

```
Iteration: N
Date: YYYY-MM-DD
Spec file(s): [list of .tla files]
Config file: [.cfg file]

Constants:
  Regions = {r1, r2, ...}
  Servers = {s1, s2, ...}
  MaxRetries = N
  [other constants]

State constraint: [if any]
Action constraint: [if any]
Symmetry sets: [if any]

Invariants checked: [list]
Properties checked: [list, including temporal]

Result: PASS | FAIL
  States found: N distinct / N total
  Duration: N seconds
  Diameter: N

If FAIL:
  Violation: [invariant or property name]
  Trace length: N states
  Classification: Spec error | Abstraction gap | Legitimate finding
  Resolution: [brief description]
```

Configurations should be checked into version control alongside the spec
files, in standard TLC `.cfg` format.

### 12.5 Finding Documentation

Each legitimate finding must be documented with full traceability:

| Field | Description |
|-------|-------------|
| **Finding ID** | Sequential identifier (e.g., F-001) |
| **Iteration** | The iteration in which it was discovered |
| **Violated invariant/property** | The name and definition of the violated property |
| **TLC trace summary** | The sequence of actions leading to the violation, in plain language |
| **Code path** | The corresponding Java code path(s) from Section 8/9 |
| **Root cause** | Why the implementation permits this behavior |
| **Severity** | Critical (data loss / split-brain) / High (stuck region / lost region) / Medium (transient inconsistency, self-healing) / Low (cosmetic or unlikely) |
| **Recommended fix** | Suggested code or design change |
| **JIRA** | Link to the tracking issue, once filed |

Findings that are later resolved (either by code change or by re-analysis
showing they are not real) should be marked as such, not deleted.

### 12.6 Regression Policy

The following rules govern backward compatibility across iterations:

1. **Invariant monotonicity**: The set of checked invariants grows
   monotonically. An invariant introduced in iteration N is checked in
   all subsequent iterations. Removing an invariant requires explicit
   justification documented in the iteration record.

2. **Invariant weakening**: An invariant may be weakened (relaxed) in a
   later iteration if the original formulation was too strong — e.g.,
   `MetaConsistency` is relaxed in Iteration 22 to account for the
   SPLITTING_NEW/MERGING_NEW discrepancy. The weakening must be justified
   by reference to the implementation behavior that necessitates it.

3. **Clean run required**: An iteration is not complete until TLC passes
   with ALL invariants from all prior iterations included. If a change in
   iteration N breaks an invariant from iteration M (M < N), the breakage
   must be triaged and resolved before proceeding.

4. **Configuration consistency**: When increasing model size (e.g., adding
   a third server for double-crash in Iteration 17), all prior invariants
   must still pass at the new size. If a prior invariant only passed at a
   smaller size due to state space limitations, this must be documented.

### 12.7 Completion Criteria for the Full Specification

The overall TLA+ specification effort is complete when ALL of the following
hold:

1. **All planned iterations are done**: Every iteration in Section 7
   (Phases 1–7, Iterations 1–29) has been completed per the workflow in
   12.2, or explicitly deferred with justification.

2. **All invariants pass**: TLC reports zero violations for all defined
   invariants and temporal properties at the documented configuration.

3. **All findings are dispositioned**: Every legitimate finding (12.5) has
   been either:
   - Filed as a JIRA issue with a recommended fix, or
   - Documented as an accepted risk with justification from the project
     maintainers.

4. **No open abstraction gaps**: Every modeling abstraction gap identified
   during triage (12.3) has been either resolved by a later iteration or
   explicitly accepted as out of scope with justification.

5. **Results are reproducible**: Another engineer can check out the spec
   files and TLC configuration from version control, run TLC, and obtain
   the same results.

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
- **Merge**: Locks all parents + the merged child.

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
 │    └── suspend                                ├─ executeProcedures()
 │                                               │   └─ executeOpenRegionProcedures()
 │                                               │       └─ submit AssignRegionHandler
 │                                               │
 │                                               ├─ AssignRegionHandler.process()
 │                                               │   ├─ regionsInTransition[r] = TRUE
 │                                               │   ├─ HRegion.openHRegion()
 │                                               │   ├─ postOpenDeployTasks()
 │                                               │   │   └─ openSeqNum = getOpenSeqNum()
 │                                               │   ├─ addRegion(region)
 │                                               │   └─ remove from regionsInTransition
 │                                               │
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
 │    └── suspend                                ├─ executeProcedures()
 │                                               │   └─ executeCloseRegionProcedures()
 │                                               │       └─ submit UnassignRegionHandler
 │                                               │
 │                                               ├─ UnassignRegionHandler.process()
 │                                               │   ├─ regionsInTransition[r] = FALSE
 │                                               │   ├─ region.close()
 │                                               │   ├─ removeRegion(region)
 │                                               │   └─ remove from regionsInTransition
 │                                               │
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

The RPC-level model should include these variables:

```tla
VARIABLES
    \* Master → RS command channel (per server)
    dispatchedOps,    \* [Servers → Set of {type, region, procId}]

    \* RS → Master report channel
    pendingReports,   \* Set of {server, region, code, procId, seqNum}

    \* Per-procedure remote state
    remoteProcState   \* [ProcId → {"DISPATCH", "REPORT_SUCCEED",
                      \*            "DISPATCH_FAIL", "SERVER_CRASH"}]
```

The following TLA+ actions model the RPC lifecycle:

| Action | What it models |
|--------|---------------|
| `MasterDispatch(p, r, s)` | Master sends open/close via ExecuteProcedures; adds to `dispatchedOps[s]`; may non-deterministically succeed or fail |
| `DispatchDelivered(p, r, s)` | ExecuteProcedures RPC returned successfully; command delivered to RS |
| `DispatchFail(p, r, s)` | ExecuteProcedures RPC ultimately fails (after retries exhausted); sets `remoteProcState = DISPATCH_FAIL` |
| `RSReceiveCommand(s, r, op)` | RS dequeues from `dispatchedOps[s]`; begins processing |
| `RSCompleteOpen(s, r)` | RS finishes opening; adds OPENED to `pendingReports` |
| `RSCompleteFail(s, r)` | RS fails to open; adds FAILED_OPEN to `pendingReports` |
| `RSCompleteClose(s, r)` | RS finishes closing; adds CLOSED to `pendingReports` |
| `MasterReceiveReport(rpt)` | Master dequeues from `pendingReports`; validates; updates state |
| `ReportLost(rpt)` | Report lost (network failure); RS will retry |
| `ServerCrashDuringOp(s)` | RS crashes; `dispatchedOps[s]` cleared; procedure notified |

This model captures the asynchronous dispatch-and-report nature of the
protocol: the dispatch RPC confirms delivery but not outcome, the
outcome arrives via an independent report channel, and both channels
have their own failure and retry semantics. The key correctness
constraint — that a command that *might* have been delivered must not
be abandoned in favor of a new server (double-assign risk) — is
captured by the non-deterministic choice between `DispatchDelivered`
and `DispatchFail`.

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

### C.3 Complete Region State Transition Graph (with Split/Merge)

Extended from the core assign/unassign graph to include all split/merge
states:

```
                        ┌──────────────────────────────────────────────┐
                        │                                              │
                        v                                              │
  OFFLINE ──► OPENING ──► OPEN ──► CLOSING ──► CLOSED ──► OFFLINE      │
                 │          │ │       ▲           │                    │
                 v          │ │       │           v                    │
            FAILED_OPEN     │ │       │      (to OPENING               │
                 │          │ │       │       via TRSP)                │
                 v          │ │       │                                │
           (to OPENING      │ │       │                                │
            via TRSP)       │ │       │                                │
                            │ │       │                                │
                            │ │  ┌────┘                                │
                            │ │  │                                     │
                            │ ├──┤ SPLITTING ──► CLOSING               │
                            │ │  │                                     │
                            │ │  └────────────────┐                    │
                            │ │                    │                   │
                            │ └──► MERGING ──► CLOSING                 │
                            │                                          │
                            v                                          │
                     ABNORMALLY_CLOSED ────────────────────────────────┘


  (Created by split)                    (Created by merge)
  SPLITTING_NEW ──► OPENING ──► OPEN    MERGING_NEW ──► OPENING ──► OPEN

  (Terminal states for parents)
  CLOSED ──► SPLIT (split parent; stays in meta until GC)
  CLOSED ──► (deleted)  (merge parents; removed from meta at PONR)
```

### C.4 Extended ValidTransition Set

The full set of valid transitions, extending the core 10 with
split/merge transitions:

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
      <<"MERGING",            "CLOSING">>,          \* TRSP unassign of parent
      <<"MERGING",            "OPEN">>,             \* rollback: revert to OPEN
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

- **MERGED is absent**: The code never transitions a region to `MERGED`.
  Merge parents are deleted from `regionStates` and meta. For TLA+
  modeling, we can either model deletion (remove from the `Regions` set)
  or introduce `MERGED` as an abstract terminal state representing
  deletion. The latter is simpler for TLC.

- `SPLITTING_NEW → OPENING` and `MERGING_NEW → OPENING`: These are the
  initial transitions for newly created regions. The daughter/merged
  regions are created in `SPLITTING_NEW`/`MERGING_NEW` state and then
  assigned via child TRSP(ASSIGN).

### C.5 PONR Semantics and Rollback Model

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
| CLOSE_REGIONS | Reopen parent regions |
| PRE_MERGE | Coprocessor rollback hook |
| PREPARE | Revert parents from MERGING to OPEN |

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

### C.6 Dynamic Region Creation and Deletion

Split creates 2 new regions; merge deletes N parent regions. This poses
a modeling challenge since TLC works with finite, pre-defined constants.

**Recommended approach: Pre-allocated region pool with existence flag.**

```tla
CONSTANTS
    PrimaryRegions,      \* {r1, r2, r3} — initially existing regions
    DaughterPool         \* {d1, d2, d3, d4, d5, d6} — pre-allocated slots

Regions == PrimaryRegions \cup DaughterPool

VARIABLE regionExists   \* [Regions → BOOLEAN]
```

- `PrimaryRegions` start with `regionExists = TRUE`.
- `DaughterPool` regions start with `regionExists = FALSE`.
- Split's `UPDATE_META` action sets `regionExists[daughter] = TRUE` for
  two unused slots from `DaughterPool`.
- Merge's `UPDATE_META` action sets `regionExists[parent] = FALSE` for
  each parent (modeling deletion). Alternatively, set state to `MERGED`
  as a terminal marker.
- All actions guard on `regionExists[r] = TRUE` before operating on `r`.

**State space implications**: With 3 primary regions and 6 daughter slots,
the pool is large enough for 3 splits. TLC feasibility depends on
bounding the number of concurrent split/merge operations.

### C.7 Multi-Region Locking

Split and merge procedures acquire `ProcedureScheduler` region locks
for ALL involved regions before any state changes:

| Procedure | Regions Locked |
|-----------|---------------|
| Split | Parent + DaughterA + DaughterB |
| Merge | All parents + merged child |

Locks are held for the entire procedure lifetime (`holdLock() == true`).

**TLA+ modeling**: Before the split/merge procedure begins, verify that
none of the involved regions have an attached procedure:

```tla
SplitPrepare(parent, dA, dB) ==
    /\ regionState[parent].procedure = None
    /\ regionState[dA].procedure = None   \* (if exists)
    /\ regionState[dB].procedure = None   \* (if exists)
    /\ regionState[parent].state = "OPEN"
    \* ... attach split procedure to all three ...
```

This is a conjunctive precondition — all locks must be free. If any
region has a procedure attached, the split/merge cannot proceed.

**Source**: `SplitTableRegionProcedure.java` `acquireLock()` L158-171.
`MergeTableRegionsProcedure.java` `acquireLock()` L398-411.

### C.8 In-Memory vs. Meta State Discrepancy at PONR

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
        regionExists[r] =>
            \/ regionState[r].state \in {"SPLITTING_NEW", "MERGING_NEW"}
               \* meta says CLOSED while memory says SPLITTING_NEW/MERGING_NEW
            \/ regionState[r].state = metaTable[r].state
```

**Source**: `RegionStateStore.java` `splitRegion()` L392-395 (daughters
stored as CLOSED in meta).

### C.9 Interaction with ServerCrashProcedure

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

### C.10 Implications for TLA+ Modeling

**New state constants**:

```tla
State == { "OFFLINE", "OPENING", "OPEN", "CLOSING", "CLOSED",
           "FAILED_OPEN", "ABNORMALLY_CLOSED",
           "SPLITTING", "SPLIT", "SPLITTING_NEW",
           "MERGING", "MERGED", "MERGING_NEW" }
```

(`MERGED` is modeled as a terminal state even though the code uses
deletion — this is simpler for TLC than dynamic set membership.)

**New procedure types**:

```tla
ProcType == { "ASSIGN", "UNASSIGN", "MOVE",
              "SPLIT", "MERGE", "SCP" }
```

**New split procedure states**:

```tla
SplitProcState == { "SPLIT_PREPARE", "SPLIT_CLOSE_PARENT",
                    "SPLIT_CHECK_CLOSED", "SPLIT_UPDATE_META",
                    "SPLIT_OPEN_CHILDREN", "SPLIT_DONE" }
```

**New merge procedure states**:

```tla
MergeProcState == { "MERGE_PREPARE", "MERGE_CLOSE_REGIONS",
                    "MERGE_CHECK_CLOSED", "MERGE_CREATE_MERGED",
                    "MERGE_UPDATE_META", "MERGE_OPEN_MERGED",
                    "MERGE_DONE" }
```

**New variables**:

```tla
VARIABLES
    regionExists,      \* [Regions → BOOLEAN]
    splitProcState,     \* [ProcId → SplitProcState]
    mergeProcState,     \* [ProcId → MergeProcState]
    daughters,          \* [ProcId → {dA: Region, dB: Region}]
    mergedRegion        \* [ProcId → Region]
```

**New actions (split)**:

| Action | What it does |
|--------|-------------|
| `SplitPrepare(r, dA, dB)` | Pre: r is OPEN, no procedure attached to r/dA/dB. Set r to SPLITTING, attach split proc to r, dA, dB. |
| `SplitCloseParent(p)` | Create child TRSP(UNASSIGN) for parent. |
| `SplitCheckClosed(p)` | Verify parent is CLOSED. |
| `SplitUpdateMeta(p)` | **PONR**: Atomically set parent to SPLIT, create daughters as SPLITTING_NEW (mem) / CLOSED (meta), set regionExists for daughters. |
| `SplitOpenChildren(p)` | Create child TRSP(ASSIGN) for each daughter. |
| `SplitDone(p)` | Detach procedure from all regions. |
| `SplitRollback(p)` | Pre-PONR only: revert parent to OPEN, delete daughters, detach procedure. |

**New actions (merge)**:

| Action | What it does |
|--------|-------------|
| `MergePrepare(r1, r2, m)` | Pre: r1, r2 OPEN, no procedures attached. Set r1, r2 to MERGING, attach merge proc. |
| `MergeCloseRegions(p)` | Create child TRSP(UNASSIGN) for each parent. |
| `MergeCheckClosed(p)` | Verify all parents are CLOSED. |
| `MergeCreateMerged(p)` | Set merged region to MERGING_NEW, set regionExists. |
| `MergeUpdateMeta(p)` | **PONR**: Delete parents from meta, create merged as CLOSED in meta. Set parents to MERGED (or mark non-existent). |
| `MergeOpenMerged(p)` | Create child TRSP(ASSIGN) for merged region. |
| `MergeDone(p)` | Detach procedure from all regions. |
| `MergeRollback(p)` | Pre-PONR only: revert parents to OPEN, delete merged, detach procedure. |

**New invariants**:

```tla
SplitCompleteness ==
    \A p \in SplitProcs :
        splitProcState[p] = "SPLIT_DONE" =>
            /\ regionState[daughters[p].dA].state = "OPEN"
            /\ regionState[daughters[p].dB].state = "OPEN"
            /\ regionState[parent[p]].state = "SPLIT"

MergeCompleteness ==
    \A p \in MergeProcs :
        mergeProcState[p] = "MERGE_DONE" =>
            /\ regionState[mergedRegion[p]].state = "OPEN"
            /\ \A r \in parents[p] : regionState[r].state = "MERGED"

SplitAtomicity ==
    \A p \in SplitProcs :
        splitProcState[p] \in {"SPLIT_PREPARE", "SPLIT_CLOSE_PARENT",
                               "SPLIT_CHECK_CLOSED"}
            => \* rollback is possible; daughters don't exist in meta

NoOrphanedDaughters ==
    \A r \in Regions :
        regionState[r].state = "SPLITTING_NEW"
            => \E p \in SplitProcs : r \in {daughters[p].dA, daughters[p].dB}
```

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
the core scenario for Iteration 19 (master crash and recovery).

### D.5 GoOffline: No Meta Write

`RegionStateNode.offline()` (RSN.java L132-134) calls `setState(State.OFFLINE)`
and `setRegionLocation(null)` but does NOT write to meta. After `offline()`, the
in-memory state is OFFLINE while meta retains the last persisted value (typically
CLOSED). This divergence is resolved on master restart when in-memory state is
rebuilt from meta.

The TLA+ model currently updates both `regionState` and `metaTable` atomically in
the `GoOffline` action. This is an over-simplification: in the implementation, meta
would retain CLOSED. The discrepancy is harmless for the assignment model because
OFFLINE and CLOSED are both "unassigned" states from the assignment perspective.

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

3. **Pattern C is refined in Iteration 19**: The two-step persistence window
   (in-memory updated, meta not yet) becomes non-trivial when master crash is
   introduced. At that point, the model must split the OPENING→OPEN and
   CLOSING→CLOSED transitions into separate in-memory and meta steps, with
   master crash possible between them. The recovery action rebuilds in-memory
   state from meta (which still says OPENING/CLOSING) and replays the procedure.

4. **Meta write failure as an advanced scenario**: Full meta write failure
   modeling (splitting every meta-writing action, adding a `metaWritePending`
   variable, weakening 4 invariants) is a candidate for Iteration 29 (advanced
   scenarios). It would roughly double the action count and significantly
   increase the state space, but could validate the revert correctness and
   the interaction between meta write failure and crash recovery.
