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
            FAILED_OPEN               │      FAILED_CLOSE (dead)       │
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

4. **No Lost Regions** (Iteration 14): After SCP completes for a
   crashed server, every region that was in the SCP's region snapshot
   must either have a procedure attached (recovery is in progress) or
   be OPEN on some server.  A region left in ABNORMALLY_CLOSED (or
   any non-OPEN state) with no procedure after SCP is done is "lost"
   — it will never be reassigned without manual intervention.
   - Liveness form: `□(serverCrashed(s) ∧ regionOn(r, s) ⇒ ◇ regionState[r].state = OPEN)`
   - Safety form (invariant, checked at each state):
     ```tla
     NoLostRegions ==
         \A s \in Servers : \A r \in Regions :
             \* After SCP has finished processing all its regions...
             (scpState[s] = "DONE"
             \* ...if r was on server s at crash detection time
             \* (implicitly: r was in the original scpRegions snapshot)
              \* ...then r must either have a procedure handling it
             \* or already be OPEN.
             /\ regionState[r].location = None
             /\ regionState[r].state # "OPEN")
             => regionState[r].procType # "NONE"
     ```
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
| `AssignmentManager.cfg` | 2r/2s, MaxRetries=1 | Exhaustive | Every iteration | <10s |
| `AssignmentManager-sim.cfg` | 3r/3s, MaxRetries=2 | Simulation | Every iteration | 5 min |
| `AssignmentManager-sim.cfg` | (same) | Simulation | Post-iteration | 15 min |
| `AssignmentManager-sim.cfg` | (same) | Simulation | Post-phase | 1 hr |
| `AssignmentManager-full.cfg` | 3r/3s, MaxRetries=1 | Exhaustive | Ad hoc | **Expensive** |

### 5.3 Abstraction Decisions

The following table documents what is modeled concretely vs. abstracted:

| Aspect | Modeling Decision | Rationale |
|--------|-------------------|-----------|
| Region state machine | **Concrete** | Core of the model; exact states and transitions |
| TRSP state machine | **Concrete** | The heart of assignment logic |
| RegionRemoteProcedure (Open/Close) | **Merged into TRSP** | Simplify by treating open/close dispatch as atomic TRSP actions |
| RS open/close execution | **Concrete** | Models the RS-side lifecycle and failure modes |
| ProcedureExecutor | **Abstract** (Iter 19 adds thread pool) | Model execute/suspend/resume/crash-recover; Iter 19 adds counting-semaphore model of PEWorker thread pool for exhaustion analysis |
| ProcedureStore (WAL) | **Abstract** | Model as a persistent set of procedure states; no WAL rolling details |
| hbase:meta | **Abstract** | Model as a function `Region → (State, Server)` |
| ZooKeeper crash detection | **Abstract** | Model as non-deterministic crash detection with delay |
| Network/RPC | **Abstract** | Model as unreliable async message channels (can lose, reorder, duplicate) |
| RegionStateNode locking | **Concrete** (Iter 15) ✅ | Per-region `locked[r]` boolean serializes all TRSP and SCP per-region actions under `regionNode.lock()`. |
| ServerStateNode locking | **Abstract** | Modeled as `serverState` ONLINE/CRASHED flag; read/write lock semantics are implicit in action guards |
| Load balancer | **Abstract** | Non-deterministic choice of move targets |
| REOPEN vs MOVE | **Concrete** (Iter 15) ✅ | `TRSPCreateReopen` pins `assignCandidate` to the region's current server; `TRSPCreateMove` forces a new plan. Separate `REOPEN` ProcType added. |
| SCP carryingMeta path | **Concrete** (Iter 15) ✅ | `carryingMeta` variable, `SCPAssignMeta` action (`ASSIGN_META` → `GET_REGIONS`), all non-meta SCP steps gated on `∀ t: scpState[t] ≠ "ASSIGN_META"` (`waitMetaLoaded`). `MetaAvailableForRecovery` invariant. |
| Split/Merge procedures | **Deferred** (Phase 7) | Complex; build after core model is validated |
| ServerCrashProcedure | **Concrete** | Critical failure recovery path |
| WAL lease revocation (fencing) | **Abstract** | Modeled as per-server Boolean (`walFenced`); fencing property only, no HDFS lease or log-splitting details |
| RS crash / zombie window | **Concrete** (Iter 14) | Decomposed into non-atomic `MasterDetectCrash` + `RSAbort` to expose the zombie RS window |
| RS epoch / ServerName | **Omitted** (resolved) | Not needed: `serverState` ONLINE/CRASHED flag (Iter 10) plus atomic crash/restart provides equivalent fencing without an explicit epoch counter. |
| TRSPGetCandidate guard (walFenced) | **Corrected** (Iter 15) ✅ | Iter 15 removed the model-specific `walFenced` guard; `serverState[s]="ONLINE"` suffices, matching the implementation's `createDestinationServersList()`. |
| `isMatchingRegionLocation()` in SCP | **Configurable** (Iter 16) | Controlled by `UseLocationCheck` BOOLEAN constant. When TRUE, `SCPAssignRegion` skips regions whose location changed since `SCPGetRegions` — matching the implementation (SCP.java L529-538). When FALSE, every region is processed unconditionally (correct protocol). The implementation's check is a known source of bugs (HBASE-24293, HBASE-21623); setting TRUE may expose `NoLostRegions` violations confirming those bugs. |
| Coprocessor hooks | **Omitted** | Not relevant to correctness of assignment protocol |
| Replication queues | **Omitted** | Orthogonal concern |
| Table enable/disable | **Deferred** | Can be added as a constraint on assignment |

### 5.4 Model Constants and Variables

The following shows the full planned variable set. Variables marked with
✅ are implemented in the current spec; those marked with ⏳ are planned
for future iterations. Names reflect the actual spec where implemented.

```tla
CONSTANTS
    Regions,          \* Set of region identifiers                     ✅ (Iter 1)
    Servers,          \* Set of regionserver identifiers               ✅ (Iter 1)
    None              \* Sentinel for "no server assigned"             ✅ (Iter 1)
    MaxRetries        \* Maximum open retries before giving up         ✅ (Iter 12)

VARIABLES
    \* --- Master-side state ---
    regionState,      \* [Regions → [state: State,                    ✅ (Iter 1)
                      \*              location: Servers ∪ {None},      ✅ (Iter 1)
                      \*              procType: ProcType,              ✅ (Iter 12)
                      \*              procStep: TRSPState ∪ {"IDLE"},  ✅ (Iter 12)
                      \*              targetServer: Servers ∪ {None},  ✅ (Iter 12)
                      \*              retries: 0..MaxRetries]]         ✅ (Iter 12)
                      \* Procedure state is inlined (region-keyed)
                      \* rather than indexed by a global procedure ID.
                      \* At most one procedure per region; region
                      \* identity is sufficient for matching.
                      \* Supersedes the former `procedures` map and
                      \* `nextProcId` counter (Iter 4 design, refactored
                      \* during Iter 12).
    metaTable,        \* [Regions → [state: State,                    ✅ (Iter 2)
                      \*              location: Servers ∪ {None}]]
    serverState,      \* [Servers → {"ONLINE", "CRASHED"}]            ✅ (Iter 10)
    procStore,        \* Set of ProcedureRecord (persisted to WAL)    ⏳ (Iter 17)

    \* --- Communication ---
    dispatchedOps,    \* [Servers → SUBSET [type: CommandType,        ✅ (Iter 6)
                      \*   region: Regions]]
                      \* Master→RS command channel (per server).
                      \* Commands dispatched by TRSP actions (Iter 7+),
                      \* consumed by RS-side actions (Iter 8+).
                      \* Matched by region (not by procedure ID);
                      \* at most one procedure per region ensures
                      \* region identity provides sufficient
                      \* discrimination.
    pendingReports,   \* SUBSET [server: Servers, region: Regions,    ✅ (Iter 6)
                      \*   code: ReportCode]
                      \* RS→master report channel.
                      \* Matched by region (same rationale as
                      \* dispatchedOps above).

    \* --- RegionServer-side state ---
    rsOnlineRegions,  \* [Servers → SUBSET Regions]                   ✅ (Iter 8)
                      \* RS-side model simplified: rsTransitions
                      \* (originally planned in Iter 8) was dropped;
                      \* rsOnlineRegions alone captures the RS view.
                      \* RSReceiveOpen+RSCompleteOpen merged into
                      \* atomic RSOpen; same for RSClose.

    \* --- Fencing and epoch ---
    serverEpoch,      \* [Servers → Nat] (incarnation counter)        ⏳ (Iter 14)
                      \* Starts at 1, incremented on ServerRestart.
                      \* Reports carry epoch; master rejects mismatches.
                      \* Models ServerName startcode-based rejection.
                      \* Note: the current spec achieves epoch-like
                      \* fencing via atomic crash (ServerCrashAll) +
                      \* atomic restart (ServerRestart purges stale
                      \* reports).  Explicit epochs are only needed if
                      \* crash is decomposed into non-atomic steps
                      \* (Iter 14).  See spec design note at
                      \* ServerRestart.
    walFenced,        \* [Servers → BOOLEAN]                          ✅ (Iter 14)
                      \* TRUE after SCP revokes WAL leases for server.
                      \* Reset to FALSE on ServerRestart. Guards
                      \* SCPAssign and the NoDoubleWrite invariant.

    \* --- Failure model ---
    masterAlive       \* BOOLEAN                                      ⏳ (Iter 18)
    \* Note: serverAlive was originally planned for Iter 14 but was
    \* superseded by serverState (above), implemented in Iter 10.
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
| `tlaplus_mcp_tlc_check` | **Exhaustive model check** — verifies all invariants and properties. Use with primary config at every iteration. |
| `tlaplus_mcp_tlc_smoke` | **Simulation model check** — random deep traces, time-limited. Use with sim config (300s per-iteration, 900s post-iteration). |
| `tlaplus_mcp_tlc_explore` | Generate and print a random behavior of a given length. Useful for understanding the spec. |
| `tlaplus_mcp_tlc_trace` | Replay a previously generated TLC counterexample trace file. |

**Primary — fast, for development**:

```
CallMcpTool:
  server: user-tlaplus.vscode-ide-extension-TLA_MCP_Server
  toolName: tlaplus_mcp_tlc_check
  arguments:
    fileName: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager.tla
    cfgFile: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager.cfg
    extraOpts: ["-workers", "auto", "-cleanup"]
```

**Simulation — per-iteration (300s / 5 min)**:

```
CallMcpTool:
  server: user-tlaplus.vscode-ide-extension-TLA_MCP_Server
  toolName: tlaplus_mcp_tlc_smoke
  arguments:
    fileName: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager.tla
    cfgFile: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager-sim.cfg
    extraJavaOpts: ["-Dtlc2.TLC.stopAfter=300"]
```

**Simulation — post-iteration validation (900s / 15 min)**:

```
CallMcpTool:
  server: user-tlaplus.vscode-ide-extension-TLA_MCP_Server
  toolName: tlaplus_mcp_tlc_smoke
  arguments:
    fileName: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager.tla
    cfgFile: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager-sim.cfg
    extraJavaOpts: ["-Dtlc2.TLC.stopAfter=900"]
```

**Parse check only** (verify syntax before running TLC):

```
CallMcpTool:
  server: user-tlaplus.vscode-ide-extension-TLA_MCP_Server
  toolName: tlaplus_mcp_sany_parse
  arguments:
    fileName: /Users/apurtell/src/hbase/src/main/spec/AssignmentManager.tla
```

### Running TLC via Command Line

The MCP tools handle per-iteration and post-iteration simulation checks.
Use the command line for the full exhaustive config (ad hoc,
user-requested) or extended post-phase simulation runs.

**Full exhaustive check** (3r/3s, ad hoc on-demand only):

```bash
cd /Users/apurtell/src/hbase/src/main/spec
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
$JAVA_HOME/bin/java -XX:+UseParallelGC -Xmx8g \
  -cp "$HOME/.cursor/extensions/tlaplus.vscode-ide-2026.2.250046-universal/tools/tla2tools.jar:$HOME/.cursor/extensions/tlaplus.vscode-ide-2026.2.250046-universal/tools/CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-full.cfg -workers auto -cleanup
```

**Post-phase simulation** (1 hour, high-confidence sweep after completing a phase):

```bash
cd /Users/apurtell/src/hbase/src/main/spec
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
$JAVA_HOME/bin/java -XX:+UseParallelGC -Dtlc2.TLC.stopAfter=3600 \
  -cp "$HOME/.cursor/extensions/tlaplus.vscode-ide-2026.2.250046-universal/tools/tla2tools.jar:$HOME/.cursor/extensions/tlaplus.vscode-ide-2026.2.250046-universal/tools/CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg -simulate -workers auto
```

Run in background (Shell `block_until_ms: 0`) and monitor the terminal
file for progress. Use `-Dtlc2.TLC.stopAfter=N` (seconds) to adjust
the time limit. Standard durations: 300 (per-iteration), 900
(post-iteration), 3600 (post-phase).

---

## 7. Iterative Development Plan

Each iteration introduces exactly one new concept, produces a spec that
TLC can verify, and is small enough to review and debug in isolation.
Iterations are grouped into phases for readability, but the unit of work
is the individual iteration.

### Phase 1: Master-Side Foundation

#### Iteration 1 — Region states and valid transitions ✅ COMPLETE

`State` (7 states), `ValidTransition` (10 transitions), per-region
`[state, location]` records, 7 actions, 4 invariants + `TransitionValid`.
TLC: 2,197 states. Git: `1e09615768`

#### Iteration 2 — Meta table as persistent state ✅ COMPLETE

Added `metaTable` (persistent `[state, location]` per region), atomic
dual update with `regionState`, `MetaConsistency` invariant. TLC: 2,197
distinct (metaTable is dependent). Git: `1e09615768`.

#### Iteration 3 — Procedure attachment (per-region mutex) ✅ COMPLETE

Added `procedure` field (`None`/`TRUE`) to `regionState`, lock
acquire/release guards, `LockExclusivity` invariant. TLC: 2,197
distinct. Git: `5f21f83d37`.

#### Iteration 4 — TRSP state machine for ASSIGN (master-side only) ✅ COMPLETE

`procedures` variable (function from Nat to records), `nextProcId`,
procedure field changed to Nat. Actions: `TRSPCreate`, `TRSPGetCandidate`,
`TRSPOpen`, `TRSPConfirmOpened`. `ProcedureConsistency` invariant.
`StateConstraint`, `AddProc`/`RemoveProc` helpers. TLC: 829,329 distinct,
~3s. Git: `ad76a4d4db`.
*Subsequently refactored*: procedure state inlined into `regionState`
records (region-keyed), eliminating the `procedures` map, `nextProcId`
counter, `StateConstraint`, `ProcedureConsistency`, and
`AddProc`/`RemoveProc` helpers. See Iter 12.

#### Iteration 5 — TRSP state machine for UNASSIGN ✅ COMPLETE

Actions: `TRSPCreateUnassign`, `TRSPClose`, `TRSPConfirmClosed`.
`LockExclusivity` strengthened (type-correlated). Deadlock from
`ServerCrash` stranding UNASSIGN resolved by `TRSPServerCrashed`.
TLC: 1,441,599 distinct, ~6s. Git: `ec0b870b5c`.

---

### Phase 2: RPC Channels and RegionServer Side

#### Iteration 6 — RPC channels (data structures only) ✅ COMPLETE

`dispatchedOps` (per-server command set), `pendingReports` (report set),
`CommandType`, `ReportCode`, `rpcVars` shorthand. Channels empty
throughout — no actions produce/consume yet. TLC: 1,441,599 distinct
(channels are dependent). Git: `dd4c127fff`.

#### Iteration 7 — Master dispatches open command via RPC ✅ COMPLETE

`TRSPDispatchOpen` (renamed), `TRSPConfirmOpened` (requires OPENED
report), `DispatchFail` (RPC failure → retry). Symmetry reduction
(`Permutations`) resolved state explosion. TLC: 39,250 distinct, ~1s.
Git: `806a9002c4`.

#### Iteration 8 — RS-side open handler and report ✅ COMPLETE

RS-side variables `rsOnlineRegions`, `rsTransitions`, `rsVars`.
Actions: `RSReceiveOpen`, `RSCompleteOpen`, `RSFailOpen`. `FailOpen`
removed from `Next` (superseded). ASSIGN round-trip complete.
TLC: 5,622,240 distinct, ~67s. Git: `f01818db30`.
*Subsequently simplified*: `rsTransitions` dropped; `rsOnlineRegions`
alone captures the RS view. `RSReceiveOpen`+`RSCompleteOpen` merged
into atomic `RSOpen` (intermediate RS state is not observable by the
master and produces the same crash-recovery outcome).

#### Iteration 9 — Master dispatches close command and RS close handler ✅ COMPLETE

`TRSPDispatchClose` (renamed), `TRSPConfirmClosed` (requires CLOSED
report), `DispatchFailClose`, `RSReceiveClose`, `RSCompleteClose`.
`RSMasterAgreement` invariant. Both round-trips end-to-end through RS.
TLC: 6,322,817 distinct, ~79s. Git: `3e92a15830`.
*Subsequently simplified*: `RSReceiveClose`+`RSCompleteClose` merged
into atomic `RSClose` (same rationale as `RSOpen` — see Iter 8 note).

#### Iteration 10 — Server liveness, per-server crash, FAILED_OPEN handling ✅ COMPLETE

`serverState` (`ONLINE`/`CRASHED`), `ServerCrashAll(s)` (atomic per-server
crash + RS cleanup), `TRSPHandleFailedOpen`, `DropStaleReport`, `ONLINE`
guards on report-consuming and RS actions. `NoDoubleAssignment`,
`RSMasterAgreementConverse` invariants. Two-tier TLC verification
(2r/2s exhaustive + 3r/3s simulation).
TLC primary: 35,856 distinct, <1s. Git: `cc9a28a29f`.

---

### Phase 3: MOVE and Failures

#### Iteration 11 — MOVE transition type ✅ COMPLETE

`TRSPCreateMove(r)`: close-then-open in one procedure. Existing actions
reused via relaxed type guards; `TRSPConfirmClosed` branches (UNASSIGN
removes, MOVE advances). Renamed `NoSplitBrain` → `NoDoubleAssignment`.
TLC primary: 61,151 distinct, ~4s. Full: 85M distinct, ~28min.
Git: `732d3aa9b9`.

#### Iteration 12 — Open failures give-up path and server restart ✅ COMPLETE

`retries`/`MaxRetries` give-up path: `TRSPGiveUpOpen` → FAILED_OPEN at
limit. `ServerRestart(s)` restarts CRASHED servers (WF liveness);
epoch-based stale-report rejection; `CrashConstraint` removed. Procedure
state inlined into `regionState`, dropping `procedures`, `nextProcId`,
`StateConstraint`. RS actions merged; `rsTransitions` dropped.
TLC primary: 307,449 distinct, ~16s. Git: `765bee6bf4`.

#### Iteration 13 — Phase 3 close-out: fidelity fixes and fairness ✅ COMPLETE

`TRSPConfirmClosed` retries unconditionally reset to 0 (matching
`retryCounter = null` at TRSP.java L412).  `GoOffline` no longer
writes `metaTable` (RSN.java L132-134); `MetaConsistency` relaxed for
OFFLINE/CLOSED divergence (Appendix D.5).  Comprehensive WF on all
deterministic procedure steps, crash recovery, and RS-side actions; no
fairness on non-deterministic environmental events.  Post-audit fix:
`TRSPServerCrashed` now resets `retries` to 0 (matching
`retryCounter = null` on crash recovery paths, TRSP.java L329/L443).
TLC primary: 35,726 distinct, <1s. Git: `b5fbed2f22`.

---

### Phase 4: RegionServer Crash and Recovery

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
TLC primary: 5,525,325 distinct, ~20s. Git: `a466c53e1e`.

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
TLC primary: 74,500,838 distinct, ~19min. Git: `90bfdf8f07`.

---

#### Iteration 16 — `isMatchingRegionLocation` in SCP (code-analysis grounded)

`UseLocationCheck` BOOLEAN constant added, controlling
`isMatchingRegionLocation()` (SCP.java L498-500, L529-538).
`SCPAssignRegion(s, r)` restructured from 2-way IF/THEN/ELSE to 3-way
disjunction: Skip (location changed, `UseLocationCheck=TRUE`), Path A
(proc attached → ABNORMALLY_CLOSED, `TRSPServerCrashed` converts),
Path B (no proc → ABNORMALLY_CLOSED + fresh ASSIGN).  Skip path
removes `r` from `scpRegions` with no state change.  Path A/B guard
`¬UseLocationCheck ∨ location=s`.  All configs default
`UseLocationCheck=TRUE` (matching implementation); `FALSE` models
violations (needs ≥3 servers; 2r/2s skip path never fires because the
only assignment target during SCP is the surviving server).
TLC primary (TRUE): 1,527,546 distinct, 14s. TLC primary (FALSE):
74,500,838 distinct, ~12min. Git: `2097e19d14`.

#### Iteration 16.5 — Simulation fidelity: race-window and guard audit

Guard audit to make the `SCPAssignRegion` skip path reachable with
`UseLocationCheck=TRUE`.  `TRSPCreate`: SCP-active guard added,
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
Git: `f3e4303dd9`.

---

### Phase 5: Procedure Persistence and Master Recovery


#### Iteration 17 — Procedure store

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

#### Iteration 18 — Master crash and recovery

**What to add**: Master crash/recovery actions, `RSOpenDuplicate`
for HBASE-26283, and `restoreSucceedState` modeling for HBASE-29364.

**Master crash/recovery actions**:
- `MasterCrash`: Clears ALL in-memory master state: `regionState`,
  `serverState`, `procedures`, `dispatchedOps`, `pendingReports`.
  `metaTable` and `procStore` survive.
- `MasterRecover`:
  1. Rebuild `regionState` from `metaTable` (scan meta).
  2. Reload `procedures` from `procStore`.
  3. Re-attach procedures to regions.
  4. Set `serverState` for all servers to ONLINE (RS will re-register).
  5. Resumed procedures pick up from their last persisted state.
**Two-phase report processing (Pattern C inconsistency window)**:
This iteration must model the two-phase report processing flow
implemented by `RegionRemoteProcedureBase` (branch-2.6).  This is
the mechanism identified in analysis recommendation 8
("Branch-2.6 vs TLA+ Model Analysis").

In branch-2.6, when a RegionServer reports a transition (OPENED,
CLOSED, FAILED_OPEN), processing happens in two distinct phases:

*Phase 1* — `RegionRemoteProcedureBase.reportTransition()` (L201-238):
  1. Persist the `transitionCode` and `seqId` to the ProcedureStore
     (via `persistAndWake`).
  2. Update in-memory state WITHOUT persisting to meta (via
     `updateTransitionWithoutPersistingToMeta`).  For OPENED, this
     calls `regionOpenedWithoutPersistingToMeta()` (AM.java L2245-2250)
     which transitions to OPEN in memory.  For CLOSED, this calls
     `regionClosedWithoutPersistingToMeta()` (AM.java L2253-2261)
     which transitions to CLOSED in memory.
  3. Wake the parent TRSP procedure.

*Phase 2* — TRSP's `confirmOpened()` / `confirmClosed()`:
  4. The woken TRSP calls `persistToMeta()` (AM.java L2289-2300)
     to update hbase:meta with the final state and location.

If the master crashes between Phase 1 step 2 and Phase 2 step 4,
meta retains the old value (OPENING or CLOSING) while the
procedure state in `procStore` is REPORT_SUCCEED. On recovery, the
procedure replays `persistToMeta` to resolve the inconsistency.

**Model decomposition**: The current model's `TRSPConfirmOpened` and
`TRSPConfirmClosed` actions perform an atomic regionState + metaTable
update.  For this iteration, these should be decomposed:
  - `TRSPReportSucceed(r)`: Consumes the report, updates `regionState`
    in memory, persists procedure state to `procStore` with
    `step = "REPORT_SUCCEED"`.  Meta is NOT updated yet.
  - `TRSPPersistToMeta(r)`: Persists the final state to `metaTable`.
    Procedure completes (or advances, for MOVE).
  - `MasterCrash` can fire between these two steps.

`MetaConsistency` must be relaxed to allow in-memory / meta
divergence when a procedure's persisted state is REPORT_SUCCEED
(indicating the in-memory update completed but meta has not caught
up).

**Source**: `RegionRemoteProcedureBase.java` reportTransition() L201-238,
  serverCrashed() L240-261, remoteCallFailed() L117-145;
  `AssignmentManager.java` regionOpenedWithoutPersistingToMeta() L2245-2250,
  regionClosedWithoutPersistingToMeta() L2253-2261,
  persistToMeta() L2289-2300.

**`RSOpenDuplicate` action (faithful modeling of RS behavior)**: After
master recovery, the master may re-dispatch OPEN commands for regions
that the RS already has online (because the master rebuilt its state
from meta, which may lag behind the RS's in-memory state).  The RS's
`AssignRegionHandler.process()` (L105-112) silently drops the
duplicate open — it returns without reporting OPENED back to the
master.  This is the **actual implementation behavior**, not a bug
being injected into the model.  The master's TRSP stays stuck at
CONFIRM_OPENED, which is a real design gap (HBASE-26283).

```tla
RSOpenDuplicate(s, r) ==
    \* Pre: server is ONLINE, region is already online on this server,
    \* and there is a pending OPEN command for this region.
    /\ serverState[s] = "ONLINE"
    /\ r \in rsOnlineRegions[s]
    /\ \E cmd \in dispatchedOps[s] :
        /\ cmd.type = "OPEN" /\ cmd.region = r
        \* Post: consume the command WITHOUT producing an OPENED report.
        \* The master's TRSP remains stuck at CONFIRM_OPENED.
        /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
        /\ UNCHANGED <<regionState, metaTable, pendingReports,
                        rsOnlineRegions, serverState, walFenced,
                        scpState, scpRegions>>
```

This action fires non-deterministically — it is NOT a fairness
obligation.  The existing `RSOpen` action is guarded by
`r \notin rsOnlineRegions[s]` (see Part 5a, already applied in the
current spec), so when a duplicate open arrives for an already-online
region, the ONLY enabled action is `RSOpenDuplicate`, which silently
drops it.  The liveness property "No Stuck Transitions" (Section
4.2.4) would flag the resulting deadlock: the TRSP waits for an
OPENED report that will never arrive.

**`restoreSucceedState` modeling (correct behavior by default)**:
When the master recovers procedures from `procStore`, the recovery
action `restoreSucceedState()` (TRSP.java) replays the persisted
`transitionCode`.  The default model specs to correct behavior:
the `transitionCode` is honored faithfully.

The model should distinguish the `transitionCode` in the procedure
store record.  During `MasterRecover`, when replaying a procedure
at `REPORT_SUCCEED`:

```tla
MasterRecoverProcedure(r) ==
    /\ procStore[r].step = "REPORT_SUCCEED"
    /\ IF procStore[r].transitionCode = "OPENED"
       THEN \* Normal case: persist OPEN to meta
            /\ metaTable' = [metaTable EXCEPT ![r] =
                 [state |-> "OPEN", location |-> procStore[r].server]]
       ELSE \* transitionCode is FAILED_OPEN:
            \* Correct behavior: persist FAILED_OPEN and retry.
            /\ metaTable' = [metaTable EXCEPT ![r] =
                 [state |-> "FAILED_OPEN", location |-> None]]
```

**Code analysis note**: The implementation's `restoreSucceedState()`
(HBASE-29364) treats any `REPORT_SUCCEED` as a successful transition
regardless of `transitionCode`, persisting OPEN even for FAILED_OPEN.
The model captures correct behavior; the gap is identified through code
analysis at each iteration.

**Verify**: After `MasterRecover`, the system eventually reaches a
consistent state. `MetaConsistency` holds after recovery. No lost
regions. No stuck procedures. The `RSOpenDuplicate` action (faithful
modeling) triggers a "No Stuck Transitions" liveness violation,
exposing the HBASE-26283 design gap.
**Source**: `ProcedureExecutor.java` `load()` L328-609,
`AssignmentManager.start()` L313-362,
`AssignRegionHandler.process()` L98-164 (HBASE-26283),
`TransitRegionStateProcedure.restoreSucceedState()` (HBASE-29364).

---

### Phase 6: PEWorker Pool and Meta-Blocking Semantics

#### Iteration 19 — PEWorker pool and meta-blocking semantics

This iteration faithfully models the ProcedureExecutor's finite worker
thread pool as it exists in the branch-2.6 implementation.  The
ProcedureExecutor uses a fixed pool of `PEWorker` threads (default 16,
configurable via `hbase.procedure.worker.count`) to execute all
procedures.  In branch-2.6, when a procedure performs a synchronous
write to `hbase:meta` via `RegionStateStore.updateRegionLocation()`, the
calling PEWorker thread blocks until the RPC completes.  If meta is
unavailable (e.g., the meta RS has crashed and meta is being
reassigned), the thread blocks indefinitely.

The model captures this synchronous blocking behavior using a
counting-semaphore abstraction for the worker pool.  A
`UseSuspendOnMetaBlock` constant enables comparative analysis against
the alternative async implementation in branch-3, where procedures
suspend and release the PEWorker thread when meta is unavailable.

**What to add**:

1. **New constants**:
   - `MaxWorkers ∈ Nat \ {0}` — PEWorker thread pool size.
   - `UseSuspendOnMetaBlock ∈ BOOLEAN` — `FALSE` = branch-2.6
     behavior (synchronous meta writes hold the PEWorker thread),
     `TRUE` = branch-3 behavior (procedure suspends and releases the
     PEWorker; re-enqueued when meta becomes available).

2. **New variables**:
   - `availableWorkers ∈ 0..MaxWorkers` — counting semaphore for
     idle PEWorker threads.
   - `blockedOnMeta ⊆ Regions` — regions whose procedures are
     blocked on a synchronous meta write (holds PEWorker thread).
   - `suspendedOnMeta ⊆ Regions` — regions whose procedures have
     yielded and released the PEWorker thread while waiting for meta.

3. **Modified actions** (guard with `availableWorkers > 0`):
   All TRSP and SCP procedure-step actions acquire a PEWorker at the
   start and release it at the end.  Since TLA+ steps are atomic,
   non-blocking actions have a net-zero change on `availableWorkers`
   (the guard enforces availability, but the step finishes immediately).
   The interesting case is meta-writing actions when meta is unavailable.

4. **Meta-blocking semantics** (faithful to `RegionStateStore`):
   - `MetaIsAvailable` predicate: `TRUE` when no server is in
     `ASSIGN_META` scpState (reuses existing `carryingMeta` / `scpState`).
   - Modified `TRSPDispatchOpen(r)` and `SCPAssignRegion(s, r)`: when
     the action attempts a meta write and `¬MetaIsAvailable`:
     - **Branch-2.6** (`UseSuspendOnMetaBlock = FALSE`): add `r` to
       `blockedOnMeta`, decrement `availableWorkers`.  The PEWorker is
       held — faithfully modeling the synchronous
       `RegionStateStore.updateRegionLocation()` call blocking on an
       unavailable meta table.
     - **Branch-3 comparison** (`UseSuspendOnMetaBlock = TRUE`): add
       `r` to `suspendedOnMeta`.  `availableWorkers` is NOT
       decremented.  Models the `CompletableFuture`-based async path.
   - `TRSPResumeFromMeta(r)` / `SCPResumeFromMeta(r)`: when
     `MetaIsAvailable` becomes `TRUE`, remove `r` from
     `blockedOnMeta` / `suspendedOnMeta` and allow the procedure to
     continue.  Branch-2.6: increment `availableWorkers` (worker
     released).  Branch-3: procedure re-enters the scheduler normally.

5. **New invariant** — `NoPEWorkerDeadlock`:
   ```tla
   NoPEWorkerDeadlock ==
     (availableWorkers = 0 /\ ~MetaIsAvailable)
       => \E r \in Regions:
            /\ regionState[r].procType = "ASSIGN"
            /\ r \notin blockedOnMeta
            /\ r \notin suspendedOnMeta
   ```
   This invariant checks that when all PEWorker threads are consumed
   and meta is unavailable, at least one free worker remains available
   to execute the meta assignment procedure.  Any violation represents
   a worker-pool deadlock in the implementation.

6. **New liveness property** — `MetaEventuallyAssigned`:
   ```tla
   MetaEventuallyAssigned ==
     \A s \in Servers: scpState[s] = "ASSIGN_META" ~> MetaIsAvailable
   ```
   Checks that the meta assignment procedure eventually completes
   despite concurrent procedure load on the PEWorker pool.

**Verify**:
- `TypeOK` with new variables.
- All existing invariants hold with `UseSuspendOnMetaBlock = FALSE`
  (branch-2.6 faithful model).
- Check `NoPEWorkerDeadlock` and `MetaEventuallyAssigned` with the
  branch-2.6 configuration.  If violations are found, analyze the
  counterexample traces — they represent genuine thread-pool exhaustion
  scenarios in the implementation.
- Compare results with `UseSuspendOnMetaBlock = TRUE` (branch-3
  comparison) to understand whether the async suspension path
  eliminates the deadlock class.
- TLC primary config: 2r/2s (or 3r/2s), MaxWorkers=2, MaxRetries=1.

**Source**: `ProcedureExecutor.WorkerThread.run()` L1986-2030;
`TransitRegionStateProcedure.executeFromState()`;
`RegionStateStore.updateRegionLocation()` L158-240 (synchronous
`Table.put()` in branch-2.6; async `CompletableFuture` in branch-3).
For context on known thread-pool exhaustion scenarios: HBASE-24526,
HBASE-24673.  Branch-3 async path: HBASE-28196, HBASE-28199,
HBASE-28240.

---

### Phase 7: Split and Merge

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
- `NoLostRegions`, `NoDoubleAssignment`, `SplitCompleteness`,
  `MergeCompleteness` all hold under crash scenarios.
**Source**: See Appendix C.9 for crash interaction analysis.

---

### Phase 8: Liveness and Refinement

#### Iteration 27 — Fairness and liveness

**What to add**: Weak fairness on procedure execution, strong fairness
on message delivery. Check temporal properties:
- `□(regionState[r].state = "OFFLINE" ⇒ ◇ regionState[r].state = "OPEN")`
- `□(scp_started(s) ⇒ ◇ scp_done(s))`

#### Iteration 28 — TLC optimization

**Already done** (from Iteration 7): Symmetry sets for Regions and
Servers (`Permutations(Regions) \union Permutations(Servers)`). This
provided up to 36× reduction for 3r/3s.
**Remaining work**: State constraints to bound message queue sizes.
Action constraints to limit crash frequency. Measure cumulative state
space reduction across all phases.

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
| `ServerManager.expireServer()` (atomic per-server) | `ServerCrashAll(s)` | 10 | ✅ |
| `ServerManager.expireServer()` (master-side only) | `MasterDetectCrash(s)` | 14 | ⏳ |
| `HRegionServer.abort()` (RS discovers death) | `RSAbort(s)` | 14 | ⏳ |
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
| `TRSP.reopen()` | `TRSPCreateReopen(r)` | 15 | ⏳ |
| Per-region write lock | `locked[r]` variable | 15 | ⏳ |
| SCP carryingMeta path | `SCPSplitMetaLogs`, `SCPAssignMeta` | 15 | ⏳ |
| `SCP.assignRegions()` with `isMatchingRegionLocation` | `SCPAssignRegion(s,r)` refined | 16 | ⏳ |
| `SCP` + `TRSP.serverCrashed()` interaction | `SCPInterruptTRSP(scp, p)` | 17 | ⏳ |
| Master crash | `MasterCrash` | 20 | ⏳ |
| Master recovery (load from store) | `MasterRecover` | 20 | ⏳ |
| `SplitTableRegionProcedure.prepareSplitRegion()` | `SplitPrepare(parent, dA, dB)` | 22 | ⏳ |
| `SplitTableRegionProcedure` CLOSE_PARENT | `SplitCloseParent(p)` | 22 | ⏳ |
| `SplitTableRegionProcedure` CHECK_CLOSED | `SplitCheckClosed(p)` | 22 | ⏳ |
| `AssignmentManager.markRegionAsSplit()` | `SplitUpdateMeta(p)` | 23 | ⏳ |
| `SplitTableRegionProcedure` OPEN_CHILDREN | `SplitOpenChildren(p)` | 24 | ⏳ |
| `SplitTableRegionProcedure` completion | `SplitDone(p)` | 24 | ⏳ |
| `SplitTableRegionProcedure.rollbackState()` | `SplitRollback(p)` | 25 | ⏳ |
| `MergeTableRegionsProcedure.prepareMergeRegion()` | `MergePrepare(r1, r2, m)` | 26 | ⏳ |
| `MergeTableRegionsProcedure` CLOSE_REGIONS | `MergeCloseRegions(p)` | 26 | ⏳ |
| `MergeTableRegionsProcedure` CHECK_CLOSED | `MergeCheckClosed(p)` | 26 | ⏳ |
| `MergeTableRegionsProcedure` CREATE_MERGED | `MergeCreateMerged(p)` | 26 | ⏳ |
| `AssignmentManager.markRegionAsMerged()` | `MergeUpdateMeta(p)` | 26 | ⏳ |
| `MergeTableRegionsProcedure` OPEN_MERGED | `MergeOpenMerged(p)` | 26 | ⏳ |
| `MergeTableRegionsProcedure` completion | `MergeDone(p)` | 26 | ⏳ |
| `MergeTableRegionsProcedure.rollbackState()` | `MergeRollback(p)` | 26 | ⏳ |

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
| Phase 1: Master-Side Foundation | 1-5 | ~500 (actual) | State machine + procedures in isolation |
| Phase 2: RPC and RegionServer | 6-10 | ~800 (actual at Iter 10) | Two-channel RPC, RS-side state, report validation |
| Phase 3: MOVE and Failures | 11-13 | ~1200 (actual at Iter 13) | Move lifecycle, retry logic, server restart, procedure inlining refactor, fairness, liveness |
| Phase 4: RS Crash and Recovery | 14-16 | +200 | SCP, TRSP interaction, double crash |
| Phase 5: Procedure Store + Master Recovery | 17-18 | +150 | Persistence, crash+rebuild |
| Phase 6: PEWorker Pool + Meta-Blocking | 19 | +100 | Finite worker pool, synchronous meta-blocking semantics |
| Phase 7: Split and Merge | 20-26 | +350 | Region pool, multi-region locking, PONR, rollback |
| Phase 8: Liveness and Refinement | 27-29 | +50 | Fairness, scenarios (symmetry already done) |
| **Total** | **29** | **~1860** (est.) | |

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

4. ~~**WAL splitting / lease revocation**~~: **RESOLVED** — WAL lease
   revocation is the mechanism by which the master fences a zombie RS's
   writes, preventing write-side double-assignment.  It is an assignment
   safety mechanism, not merely a data recovery step.  Modeled as an
   abstract fencing step (`SCPFenceWALs`) in SCP (Iteration 14) with a
   per-server Boolean (`walFenced`).  The `NoDoubleWrite` invariant
   (Iteration 13) captures the safety property: a region is never
   writable on two servers simultaneously, where writable means
   `r ∈ rsOnlineRegions[s] ∧ walFenced[s] = FALSE`.  WAL splitting
   mechanics (log file replay, HDFS lease details) remain out of
   scope; only the fencing property is modeled.

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
4. **RUN TLC** — Run both mandatory configurations:
   - `AssignmentManager.cfg` (primary, exhaustive 2r/2s) — must pass.
   - `AssignmentManager-sim.cfg` (simulation, 3r/3s, 300s) — must pass.
   After completing an iteration, run a 15-minute post-iteration
   simulation (900s).  After completing a phase, run a 1-hour
   post-phase simulation (3600s).  The full exhaustive config
   (`AssignmentManager-full.cfg`) is not run at every iteration.
   It is reserved for ad hoc on-demand checks at user-requested
   checkpoints.
5. **TRIAGE** — If TLC reports violations, classify each one (see 12.3).
   Repeat from step 1 or 4 as needed.
6. **REGRESSION CHECK** — Re-verify all invariants and properties from
   prior iterations. A fix in iteration N must not break any invariant
   proven in iterations 1 through N-1. The primary and simulation
   configs provide this coverage automatically at every iteration.
7. **RECORD** — Document the TLC result, configuration, state count,
   and any findings (see 12.4 and 12.5).
8. **UPDATE PLAN** — Mark the iteration complete in this plan document
   (Section 7). Append `✅ COMPLETE` to the iteration heading, convert
   the "What to add" description to past tense ("What was added"), and
   add a `**TLC result**` line summarizing the final model-checking
   outcome (constants, state count, invariants checked, pass/fail).
   If the iteration produced a legitimate finding, note it here with
   its Finding ID (see 12.5). This keeps the plan document as the
   single source of truth for iteration status.
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
   `MetaConsistency` is relaxed in Iteration 23 to account for the
   SPLITTING_NEW/MERGING_NEW discrepancy. The weakening must be justified
   by reference to the implementation behavior that necessitates it.

3. **Clean run required**: An iteration is not complete until TLC passes
   with ALL invariants from all prior iterations included. If a change in
   iteration N breaks an invariant from iteration M (M < N), the breakage
   must be triaged and resolved before proceeding.

4. **Configuration consistency**: When increasing model size (e.g., adding
   a third server for a multi-crash scenario), all prior invariants
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

> **Implementation note**: The variable and action design below reflects
> the original pre-implementation analysis. The actual spec simplified
> the RPC model: (1) `remoteProcState` was absorbed into TRSP inline
> procedure fields (`procType`, `procStep` in `regionState`); (2) RS
> receive+complete were merged into atomic actions (`RSOpen`, `RSClose`);
> (3) procedure IDs were replaced by region-keyed matching. The analysis
> in B.1-B.6 remains accurate as implementation reference.

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
   variable, weakening 4 invariants) is a candidate for Iteration 30 (advanced
   scenarios). It would roughly double the action count and significantly
   increase the state space, but could validate the revert correctness and
   the interaction between meta write failure and crash recovery.
