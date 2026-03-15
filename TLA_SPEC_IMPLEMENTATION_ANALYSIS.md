# Spec–Implementation Fidelity & Developer Utility Analysis

## 1. Executive Summary

The specification (35 iterations, 12 modules, 20 variables, 34 invariants, 3 liveness properties) has achieved remarkably high fidelity to the HBase AssignmentManager implementation. It exhaustively verifies 368M distinct states at 3r/2s and has passed 8-hour simulation at 9r/3s. This analysis evaluates spec–implementation correspondence along three axes:

1. **Fidelity gaps** — where the spec diverges from the implementation
2. **Modeling abstractions** — where deliberate simplifications reduce state space
3. **Developer utility** — how well the spec serves its goal of validating design/architecture changes *before* code

The negotiation between fidelity and tractability is already excellent. Below I identify specific opportunities to enhance developer utility while respecting state space constraints.

---

## 2. Fidelity Assessment: What the Spec Models Concretely

The following implementation concepts have **1:1 TLA+ action correspondence** (high fidelity):

| Implementation Concept | Spec Representation | Fidelity |
|------------------------|---------------------|----------|
| Region lifecycle (13 states) | `State` set in `Types.tla` | ★★★★★ |
| TRSP state machine (5 states + REPORT_SUCCEED) | `TRSPState` + 17 TRSP actions | ★★★★★ |
| SCP state machine (6 states) | `scpState` + 5 SCP actions | ★★★★★ |
| Two-phase report processing (RRPB) | `TRSPReportSucceed*` / `TRSPPersistToMeta*` | ★★★★★ |
| `isMatchingRegionLocation()` skip path | `SCPAssignRegion` 3-way disjunction | ★★★★★ |
| WAL lease fencing | `walFenced` + `FencingOrder` invariant | ★★★★★ |
| Zombie RS window | `MasterDetectCrash` / `RSAbort` decomposition | ★★★★★ |
| ProcedureStore persistence | `procStore` variable + `ProcStoreConsistency` | ★★★★★ |
| Master crash/recovery | `MasterCrash` / `MasterRecover` + `RestoreSucceedState` | ★★★★★ |
| ZK session expiry | `ZK.tla` (`zkNode`, `ZKSessionExpire`) | ★★★★★ |
| PE worker pool + meta-blocking | `availableWorkers` / `suspendedOnMeta` / `blockedOnMeta` | ★★★★★ |
| Split (forward + rollback) | `Split.tla` (5 actions, keyspace halving) | ★★★★★ |
| Merge (forward + rollback) | `Merge.tla` (5 actions, keyspace union) | ★★★★★ |
| Create/Delete/Truncate table | `Create.tla` / `Delete.tla` / `Truncate.tla` | ★★★★☆ |
| `serverRegions` tracking vs `regionState.location` | Independent `serverRegions` variable | ★★★★★ |
| Dispatch-failure server expiration | `DispatchFail` disjunct 2 | ★★★★★ |
| Type-preserving crash recovery (Iter 25) | `TRSPConfirmClosedCrash` / `TRSPServerCrashed` | ★★★★★ |
| RS duplicate-open / close-not-found | `RSOpenDuplicate` / `RSCloseNotFound` (quirk flags) | ★★★★★ |

---

## 3. Deliberate Abstractions (Tractability)

These are **intentional** simplifications documented in the plan. Each trades fidelity for finite state space:

| Abstraction | What's Simplified | Impact on Developer Utility | Risk |
|-------------|-------------------|----------------------------|------|
| **Meta writes always succeed** | No `IOException` → revert logic | Cannot validate meta-write-failure → revert correctness | **Low**: lock held across write+revert; no concurrent observation possible (Appendix D) |
| **Atomic RS open/close** | `RSReceiveOpen` + `RSCompleteOpen` → `RSOpen` | Cannot model crash *between* receive and complete on RS side | **Low**: intermediate RS state is "not observable by master" and crash-recovery outcome is identical |
| **RPC = set of records** | No wire format, no batching, no epoch fencing | Cannot model epoch-based stale master rejection | **Low**: `serverState` CRASHED flag + report guards provide equivalent fencing |
| **Single-region CreateTable** | Creates 1 region; real HBase creates N | Cannot verify multi-region table creation | **Medium**: single-region suffices for procedure-framework testing; multi-region is a straightforward extension |
| **No table enable/disable** | Preconditions enforced by region-state guards | Cannot verify enable/disable → split/merge interaction | **Medium**: deferred as documented in plan §5.3 |
| **N-way merge → 2-way** | Only 2 regions can be merged | Cannot verify N>2 merge | **Low**: all state transitions, PONR, and crash recovery paths are exercised with 2-way |
| **No coprocessor hooks** | Omitted | Cannot verify coprocessor-induced failures during split/merge | **Low**: orthogonal to assignment protocol correctness |
| **No replication queues** | Omitted | N/A — orthogonal concern | **None** |
| **`SplitMergeConstraint` (≤1)** | At most 1 concurrent split/merge | Cannot verify concurrent split/merge interactions | **Medium**: faithful to implementation which allows concurrency; tractability limit |

> [!IMPORTANT]
> None of these abstractions compromise the spec's ability to validate the **core safety properties** (NoDoubleAssignment, NoLostRegions, FencingOrder, KeyspaceCoverage). They primarily affect secondary fidelity around failure modes and scale.

---

## 4. Specification Completeness Map

### 4.1 Code Paths Fully Modeled

All 67 code path entries in §8 of the plan are accounted for. 63 are marked ✅ implemented; the 4 marked ⏳ (iterations 32-35 table-level procedures) are now also complete per the plan's iteration summaries.

### 4.2 Known Implementation Bugs Modeled as Quirk Flags

| Quirk Flag | Bug Description | JIRA |
|------------|-----------------|------|
| `UseRSOpenDuplicateQuirk` | RS silently drops OPEN for already-online region → TRSP deadlock | — |
| `UseRSCloseNotFoundQuirk` | RS silently drops CLOSE for not-found region → TRSP deadlock | — |
| `UseRestoreSucceedQuirk` | `OpenRegionProcedure.restoreSucceedState()` replays FAILED_OPEN as OPENED | — |
| `UseUnknownServerQuirk` | `checkOnlineRegionsReport()` closes orphans without TRSP → stuck forever | HBASE-24293 / HBASE-21623 |
| `UseMasterAbortOnMetaWriteQuirk` | `updateRegionLocation()` calls `master.abort()` on IOException | HBASE-23595 |
| `UseStaleStateQuirk` | `visitMeta()` creates ONLINE ServerStateNode for dead servers | — |

These are **extremely valuable** for developer utility: a developer proposing a fix for any of these bugs can toggle the quirk flag and verify the fix eliminates the violation.

---

## 5. Developer Utility Assessment

### 5.1 Strengths — What the Spec Excels At

1. **Design validation for TRSP changes**: Any modification to the TRSP state machine (new states, guard changes, transition reordering) can be modeled and verified in minutes against all 34 invariants. The spec's TRSP fidelity is extremely high.

2. **Crash recovery correctness**: The decomposed crash model (ZK expiry → master detect → SCP → fence WALs → assign → RS abort) with independent `serverRegions` tracking precisely captures the implementation's race conditions. Proposed changes to SCP ordering can be immediately validated.

3. **Split/merge safety**: `KeyspaceCoverage`, `SplitAtomicity`, `MergeAtomicity`, and the PONR model provide rigorous verification. A developer adding a new split step or modifying daughter materialization can verify no keyspace gaps or overlaps are introduced.

4. **Procedure store + master recovery**: The `procStore` + `MasterRecover` + `RestoreSucceedState` pipeline faithfully models WAL procedure store persistence. Changes to procedure serialization or recovery logic can be validated against `ProcStoreConsistency` and `ProcStoreBijection`.

5. **Bug reproduction via quirk flags**: The 6 quirk flags let developers reproduce known bugs, verify fixes, and ensure no regressions. This is a novel and powerful feature.

6. **Two-tier verification**: Exhaustive (3r/2s, ~70min) for fast iteration + simulation (9r/3s, configurable duration) for deep coverage provides an efficient developer workflow.

### 5.2 Current Limitations for Developer Utility

1. **Table enable/disable not modeled**: A developer modifying the enable/disable path cannot validate changes against the spec. This is the most significant gap for real-world developer use.

2. **Multi-region table operations**: CreateTable creates a single region. Developers working on multi-region table creation (the common case) must reason about the extension themselves.

3. **No heartbeat / load-balancer model**: The load balancer is modeled as non-deterministic choice. Developers working on balancer-driven move policies cannot validate their changes.

4. **No configuration parameter validation**: Parameters like `hbase.procedure.threads`, `hbase.assignment.max.attempts`, and retry intervals are abstracted to `MaxWorkers`, `MaxRetries`, etc. The spec cannot validate edge cases around specific parameter values.

5. **No explicit region epoch / ServerName generation counter**: The plan notes this is intentional (ONLINE/CRASHED flag suffices), but developers working on `ServerName` matching logic in `reportTransition()` cannot validate those guards.

---

## 6. Recommendations for Optimizing Developer Utility

### 6.1 Low-Cost, High-Value Improvements

These can be done without increasing state space significantly:

| # | Recommendation | Rationale | State Space Impact |
|---|---------------|-----------|-------------------|
| 1 | **Add `UseEnableDisableQuirk` toggle** | Model disabled tables where all regions are CLOSED with `parentProc = NONE`. A developer adding an operation on a disabled table can verify CLOSED+no-proc preconditions. | Minimal: adds a boolean guard, no new actions needed |
| 2 | **Add a "dev guide" section to README** | Document: "If you're changing [X], here's how to model it in the spec." Cover the 5 most common change patterns: (a) adding a TRSP state, (b) modifying SCP ordering, (c) adding a new quirk flag, (d) changing a split/merge step, (e) adding a new invariant. | Zero: documentation only |
| 3 | **Create an `AssignmentManager-quirks.cfg`** | A config that enables ALL quirk flags simultaneously. Developers can use this to see all known bugs fire at once, or progressively disable quirks to validate individual fixes. | Zero: config file only |
| 4 | **Add `RegionEventuallyAssigned` liveness** | `∀ r ∈ Regions: RegionExists(r) ∧ regionState[r].state = "OFFLINE" ∧ procType = "NONE" ~> regionState[r].state = "OPEN"` — but this *should* fail (TRSPCreate has no fairness), documenting the implementation's "manual intervention required" semantic | Zero: property definition only |
| 5 | **Annotate each invariant with "when to check"** | In the README invariants table, add a "Check when changing" column: e.g., `NoDoubleAssignment` → "SCP ordering, RSAbort timing, fencing"; `KeyspaceCoverage` → "Split/merge PONR, daughter materialization" | Zero: documentation only |

### 6.2 Medium-Cost Improvements

These require some spec changes but don't fundamentally alter state space:

| # | Recommendation | Rationale | State Space Impact |
|---|---------------|-----------|-------------------|
| 6 | **Add `DisableTableProcedure` / `EnableTableProcedure`** | Completes the table lifecycle. Without these, developers working on table state transitions cannot validate against the spec. | Moderate: 2 new actions per procedure, adds table-state variable |
| 7 | **Multi-region CreateTable** | Extend `CreateTablePrepare` to create N regions (parameter). The existing framework supports it — just loop over unused identifiers. | Moderate: increases symmetry-broken states proportional to N |
| 8 | **Add `NoStuckRegions` temporal property** | `□(∀ r: regionState[r].state ∈ {"OPENING", "CLOSING"} ⇒ ◇ regionState[r].state ∉ {"OPENING", "CLOSING"})` — regions don't remain in transitional states forever. Check in liveness config. | Zero at safety level; liveness check cost |
| 9 | **Add region epoch / sequence number** | A monotonic counter per region incremented on each assignment. Enables modeling of stale-report rejection based on sequence mismatch rather than server name. | Small: adds one Nat per region |

### 6.3 Higher-Cost Improvements (Future Consideration)

| # | Recommendation | Rationale | State Space Impact |
|---|---------------|-----------|-------------------|
| 10 | **Meta write failure modeling** | Split meta-writing actions into attempt+succeed/fail. Add `metaWritePending` variable. Validate revert correctness (Appendix D patterns A/B/C). | **Large**: roughly doubles action count, significantly increases state space |
| 11 | **Concurrent split/merge (≥2)** | Relax `SplitMergeConstraint` to ≤2. Verifies concurrent splits on different regions don't interfere. | **Large**: quadratic state space increase |
| 12 | **Heartbeat-based region tracking** | Model `regionServerReport()` with online-region lists. Validates the `checkOnlineRegionsReport()` → unknown server detection pipeline end-to-end. | **Large**: adds periodic action with set comparison |

---

## 7. Spec-Implementation Correspondence Deep Dive

### 7.1 Variables: Spec ↔ Implementation

| Spec Variable | Implementation Structure | Correspondence |
|---------------|-------------------------|----------------|
| `regionState[r]` | `RegionStateNode` (in-memory) | **Exact**: state, location, procType, procStep, targetServer, retries all map 1:1 |
| `metaTable[r]` | `hbase:meta` table rows | **Exact**: [state, location] per region |
| `dispatchedOps[s]` | `RSProcedureDispatcher` batched commands | **Abstracted**: no batching, no epoch; set semantics capture reorder/loss |
| `pendingReports` | `reportRegionStateTransition()` RPC | **Abstracted**: no RPC retry; set models async delivery |
| `rsOnlineRegions[s]` | `HRegionServer.onlineRegions` | **Exact**: set of regions online per RS |
| `serverState[s]` | `ServerStateNode.state` | **Exact**: ONLINE/CRASHED enum |
| `scpState[s]` | `ServerCrashProcedure` state machine | **Exact**: 6-state enum (NONE→ASSIGN_META→GET_REGIONS→FENCE_WALS→ASSIGN→DONE) |
| `scpRegions[s]` | `SCP.getRegionsOnServer()` snapshot | **Exact**: reads from `serverRegions[s]` matching `AM.getRegionsOnServer()` |
| `walFenced[s]` | HDFS lease recovery on WALs | **Abstracted**: boolean flag; no HDFS lease details |
| `carryingMeta[s]` | `SCP.isCarryingMeta()` | **Exact**: boolean indicating meta-hosting |
| `serverRegions[s]` | `ServerStateNode` region tracking | **Exact**: independent from `regionState[r].location` |
| `procStore[r]` | `WALProcedureStore` / `RegionProcedureStore` | **Abstracted**: persisted record, no WAL rolling |
| `masterAlive` | HMaster JVM process state | **Exact**: boolean liveness |
| `zkNode[s]` | ZK ephemeral node existence | **Exact**: boolean per server |
| `availableWorkers` | `ProcedureExecutor.workerThreadCount` | **Exact**: counting semaphore |
| `suspendedOnMeta` / `blockedOnMeta` | `ProcedureFutureUtil` / sync `Table.put()` | **Exact**: two modes per `UseBlockOnMetaWrite` |
| `regionKeyRange[r]` | `RegionInfo.getStartKey()` / `getEndKey()` | **Exact**: [startKey, endKey) range |
| `parentProc[r]` | Split/Merge procedure state machines | **Exact**: [type, step, ref1, ref2] record |
| `regionTable[r]` | `RegionInfo.getTable()` | **Exact**: table identity per region |

### 7.2 Actions: Spec ↔ Implementation (Completeness Matrix)

**Total actions in spec**: ~55 distinct actions across 12 modules.
**Total code paths in §8 mapping**: 67 (some code paths map to the same action).
**Coverage**: 100% — every mapped code path has a corresponding spec action.

### 7.3 Invariants: What Each Protects

The 34 invariants form a comprehensive safety net. Grouped by what they protect:

**Core Safety** (6):
- `NoDoubleAssignment`, `FencingOrder`, `NoLostRegions`, `MetaConsistency`, `KeyspaceCoverage`, `NoPEWorkerDeadlock`

**State Machine Consistency** (8):
- `TypeOK`, `LockExclusivity`, `ProcStepConsistency`, `TargetServerConsistency`, `NoOrphanedProcedures`, `ProcStoreConsistency`, `ProcStoreBijection`, `SCPMonotonicity` (action constraint)

**Location Tracking** (5):
- `OpenImpliesLocation`, `OfflineImpliesNoLocation`, `OpeningImpliesLocation`, `ClosingImpliesLocation`, `ServerRegionsTrackLocation`

**RS-Master Agreement** (2):
- `RSMasterAgreement`, `RSMasterAgreementConverse`

**SCP/Meta Integrity** (3):
- `MetaAvailableForRecovery`, `AtMostOneCarryingMeta`, `DispatchCorrespondance`

**Split/Merge Atomicity** (6):
- `SplitAtomicity`, `SplitCompleteness`, `NoOrphanedDaughters`, `SplitMergeMutualExclusion`, `MergeAtomicity`, `MergeCompleteness`, `NoOrphanedMergedRegion`

**Table-Level Procedures** (4):
- `TableLockExclusivity`, `DeleteTableAtomicity`, `TruncateAtomicity`, `TruncateNoOrphans`

---

## 8. How to Use the Spec for Design Changes

### 8.1 Workflow for a Developer

1. **Identify the feature area** (TRSP, SCP, split/merge, table procedure, PE worker pool)
2. **Find the corresponding module** (TRSP.tla, SCP.tla, Split.tla, etc.)
3. **Model the proposed change** in TLA+ (add/modify actions, adjust guards)
4. **Add or modify invariants** if the change introduces new safety requirements
5. **Run exhaustive** (`AssignmentManager.cfg`) — fast feedback in ~70 min
6. **Run simulation** (`AssignmentManager-sim.cfg`) — deeper coverage
7. **If a violation is found**: the counterexample trace shows the exact failure sequence — use this to refine the design before writing code

### 8.2 Common Change Patterns

| Change Type | Where to Edit | What to Verify |
|-------------|---------------|----------------|
| Add a TRSP state | `Types.tla` (`TRSPState`), `TRSP.tla` (new action + guards), `AssignmentManager.tla` (wire into `Next`/`Fairness`) | `ProcStepConsistency`, `LockExclusivity`, `TransitionValid` |
| Modify SCP ordering | `SCP.tla` (reorder steps), `AssignmentManager.tla` (`SCPMonotonicity`) | `FencingOrder`, `NoLostRegions`, `SCPMonotonicity` |
| Add a new quirk flag | `Types.tla` (constant+assume), relevant module (guarded action), all `.cfg` files | Existing invariants should detect the bug |
| Change split/merge step | `Split.tla` or `Merge.tla` (modify action) | `KeyspaceCoverage`, `SplitAtomicity`/`MergeAtomicity`, `NoOrphanedDaughters`/`NoOrphanedMergedRegion` |
| Add a new invariant | `AssignmentManager.tla` (define + wire into THEOREM), all `.cfg` files | The new invariant itself + no regression on existing |

---

## 9. Conclusion

The specification has reached a mature and highly useful state. The 35 iterations have systematically built a model that is faithful to the implementation at every critical decision point, while making principled abstractions where modeling cost would exceed verification value.

**Key strengths** for developer utility:
- **Exhaustive safety verification** of the core assignment protocol
- **Quirk flags** enabling bug reproduction and fix validation
- **Rich invariant suite** catching violations at precise semantic boundaries
- **Two-tier verification** (exhaustive + simulation) for fast/deep tradeoff

**Primary improvement opportunity**: A developer-facing guide (recommendation #2 and #5 above) that maps implementation change patterns to spec verification steps would dramatically lower the barrier to entry for HBase developers wanting to use the spec.

**Secondary**: Enable/Disable table procedures (recommendation #6) would close the most significant functional gap for real-world use.

The negotiation between fidelity and tractability is well-calibrated. The current abstractions (atomic RS steps, no meta-write failures, no heartbeat model) are justified by the lock-discipline analysis (Appendix A) and the observation that these intermediate states are not observable by concurrent actors. The spec successfully serves its primary purpose: **validating design and architecture changes to the AssignmentManager before implementation**.
