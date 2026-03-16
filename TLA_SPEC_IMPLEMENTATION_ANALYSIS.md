# Spec–Implementation Fidelity & Developer Utility Analysis

## 1. Executive Summary

The specification (15 modules, 19 variables, 35 invariants, 4 liveness properties, 57 distinct actions) has achieved remarkably high fidelity to the HBase AssignmentManager implementation. It exhaustively verifies 368M distinct states at 3r/2s and has passed 8-hour simulation at 9r/3s. This analysis evaluates spec–implementation correspondence along three axes:

1. **Fidelity gaps** — where the spec diverges from the implementation
2. **Modeling abstractions** — where deliberate simplifications reduce state space
3. **Developer utility** — how well the spec serves its goal of validating design/architecture changes *before* code

The negotiation between fidelity and tractability is excellent. Below I identify specific opportunities to enhance developer utility while respecting state space constraints.

---

## 2. Fidelity Assessment: What the Spec Models Concretely

The following implementation concepts have **1:1 TLA+ action correspondence** (high fidelity):

| Implementation Concept | Spec Representation | Fidelity |
|------------------------|---------------------|----------|
| Region lifecycle (13 states) | `State` set in `Types.tla` | ★★★★★ |
| TRSP state machine (5 states + REPORT_SUCCEED) | `TRSPState` + 16 TRSP actions | ★★★★★ |
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
| Disable/Enable table | `Disable.tla` / `Enable.tla` (4 actions) | ★★★★★ |
| Table enabled/disabled state | `tableEnabled` variable, `TableEnabledStateConsistency` invariant | ★★★★★ |
| `serverRegions` tracking vs `regionState.location` | Independent `serverRegions` variable | ★★★★★ |
| Dispatch-failure server expiration | `DispatchFail` disjunct 2 | ★★★★★ |
| Type-preserving crash recovery | `TRSPConfirmClosedCrash` / `TRSPServerCrashed` | ★★★★★ |
| RS duplicate-open / close-not-found | `RSOpenDuplicate` / `RSCloseNotFound` (quirk flags) | ★★★★★ |

---

## 3. Deliberate Abstractions (Tractability)

These are intentional simplifications. Each trades fidelity for finite state space:

| Abstraction | What's Simplified | Impact on Developer Utility | Risk |
|-------------|-------------------|----------------------------|------|
| **Meta writes always succeed** | No `IOException` → revert logic | Cannot validate meta-write-failure → revert correctness | **Low**: lock held across write+revert; no concurrent observation possible |
| **Atomic RS open/close** | `RSReceiveOpen` + `RSCompleteOpen` → `RSOpen` | Cannot model crash *between* receive and complete on RS side | **Low**: intermediate RS state is "not observable by master" and crash-recovery outcome is identical |
| **RPC = set of records** | No wire format, no batching, no epoch fencing | Cannot model epoch-based stale master rejection | **Low**: `serverState` CRASHED flag + report guards provide equivalent fencing |
| **Single-region CreateTable** | Creates 1 region; real HBase creates N | Cannot verify multi-region table creation | **Medium**: single-region suffices for procedure-framework testing; multi-region is a straightforward extension |
| **No DISABLING/ENABLING intermediate states** | `DisableTableProcedure` collapses 7 states → 2 actions; no DISABLING/ENABLING `TableState` modeled | Cannot verify concurrent client enable/disable rejection based on intermediate states | **Low**: `TableLockExclusivity` prevents concurrent table procedures; the intermediate states are serialization artifacts |
| **No region replica handling in Enable** | Spec enables single-replica regions only | Cannot verify replica count changes during enable | **Low**: region replication is orthogonal to assignment safety |
| **N-way merge → 2-way** | Only 2 regions can be merged | Cannot verify N>2 merge | **Low**: all state transitions, PONR, and crash recovery paths are exercised with 2-way |
| **No coprocessor hooks** | Omitted | Cannot verify coprocessor-induced failures during split/merge/disable/enable | **Low**: orthogonal to assignment protocol correctness |
| **No replication queues** | Omitted | N/A — orthogonal concern | **None** |
| **`SplitMergeConstraint` (≤1)** | At most 1 concurrent split/merge | Cannot verify concurrent split/merge interactions | **Medium**: faithful to implementation which allows concurrency; tractability limit |

---

## 4. Specification Completeness Map

### 4.1 Module Coverage Summary

| Module | Actions | Implementation Class | Coverage |
|--------|---------|---------------------|----------|
| `TRSP.tla` | 16 | `TransitRegionStateProcedure`, `OpenRegionProcedure`, `CloseRegionProcedure` | ✅ Complete |
| `SCP.tla` | 5 | `ServerCrashProcedure` | ✅ Complete |
| `RegionServer.tla` | 8 | `HRegionServer`, `AssignRegionHandler`, `UnassignRegionHandler` | ✅ Complete |
| `Master.tla` | 5 | `HMaster`, `ServerManager` | ✅ Complete |
| `Split.tla` | 5 | `SplitTableRegionProcedure` | ✅ Complete |
| `Merge.tla` | 5 | `MergeTableRegionsProcedure` | ✅ Complete |
| `Create.tla` | 2 | `CreateTableProcedure` | ✅ Complete |
| `Delete.tla` | 2 | `DeleteTableProcedure` | ✅ Complete |
| `Truncate.tla` | 4 | `TruncateTableProcedure` | ✅ Complete |
| `Disable.tla` | 2 | `DisableTableProcedure` | ✅ Complete |
| `Enable.tla` | 2 | `EnableTableProcedure` | ✅ Complete |
| `ZK.tla` | 1 | ZooKeeper ephemeral nodes | ✅ Complete |
| `Types.tla` | — | Region/table types, state enumerations | ✅ Complete |
| `ProcStore.tla` | — | `WALProcedureStore` / `RegionProcedureStore` | ✅ Complete |
| `AssignmentManager.tla` | — | Root orchestrator: Init, Next, Fairness, invariants | ✅ Complete |

**Total distinct actions**: 57 across 15 modules.

### 4.2 Known Implementation Bugs Modeled as Quirk Flags

| Quirk Flag | Bug Description | JIRA |
|------------|-----------------|------|
| `UseRSOpenDuplicateQuirk` | RS silently drops OPEN for already-online region → TRSP deadlock | — |
| `UseRSCloseNotFoundQuirk` | RS silently drops CLOSE for not-found region → TRSP deadlock | — |
| `UseRestoreSucceedQuirk` | `OpenRegionProcedure.restoreSucceedState()` replays FAILED_OPEN as OPENED | — |
| `UseUnknownServerQuirk` | `checkOnlineRegionsReport()` closes orphans without TRSP → stuck forever | HBASE-24293 / HBASE-21623 |
| `UseMasterAbortOnMetaWriteQuirk` | `updateRegionLocation()` calls `master.abort()` on IOException | HBASE-23595 |
| `UseStaleStateQuirk` | `visitMeta()` creates ONLINE ServerStateNode for dead servers | — |

---

## 5. Developer Utility Assessment

### 5.1 Strengths — What the Spec Excels At

1. **Design validation for TRSP changes**: Any modification to the TRSP state machine (new states, guard changes, transition reordering) can be modeled and verified in minutes against all 35 invariants. The spec's TRSP fidelity is extremely high.

2. **Crash recovery correctness**: The decomposed crash model (ZK expiry → master detect → SCP → fence WALs → assign → RS abort) with independent `serverRegions` tracking precisely captures the implementation's race conditions. Proposed changes to SCP ordering can be immediately validated.

3. **Split/merge safety**: `KeyspaceCoverage`, `SplitAtomicity`, `MergeAtomicity`, and the PONR model provide rigorous verification. A developer adding a new split step or modifying daughter materialization can verify no keyspace gaps or overlaps are introduced.

4. **Procedure store + master recovery**: The `procStore` + `MasterRecover` + `RestoreSucceedState` pipeline faithfully models WAL procedure store persistence. Changes to procedure serialization or recovery logic can be validated against `ProcStoreConsistency` and `ProcStoreBijection`.

5. **Bug reproduction via quirk flags**: The 6 quirk flags let developers reproduce known bugs, verify fixes, and ensure no regressions. This is a novel and powerful feature.

6. **Two-tier verification**: Exhaustive (3r/2s, ~71 min) for fast iteration + simulation (9r/3s, configurable duration) for deep coverage provides an efficient developer workflow.

7. **Table lifecycle completeness**: With the addition of Disable/Enable, the spec now covers the complete table lifecycle: Create → Enable/Disable → Delete/Truncate. The `TableEnabledStateConsistency` invariant and `RegionEventuallyAssigned` liveness property provide safety and liveness guarantees across the full lifecycle. A developer modifying the enable/disable path can validate changes against the spec.

8. **Comprehensive developer guide**: The `DEVELOPING.md` provides five annotated change patterns (add TRSP state, modify SCP ordering, add quirk flag, change split/merge step, add invariant), an invariant reference table mapping each invariant to change areas, and a module-implementation mapping. This dramatically lowers the barrier to entry for HBase developers.

### 5.2 Current Limitations for Developer Utility

1. **Multi-region table operations**: CreateTable creates a single region. Developers working on multi-region table creation (the common case) must reason about the extension themselves.

2. **No heartbeat / load-balancer model**: The load balancer is modeled as non-deterministic choice. Developers working on balancer-driven move policies cannot validate their changes.

3. **No configuration parameter validation**: Parameters like `hbase.procedure.threads`, `hbase.assignment.max.attempts`, and retry intervals are abstracted to `MaxWorkers`, `MaxRetries`, etc. The spec cannot validate edge cases around specific parameter values.

4. **No explicit region epoch / ServerName generation counter**: The plan notes this is intentional (ONLINE/CRASHED flag suffices), but developers working on `ServerName` matching logic in `reportTransition()` cannot validate those guards.

5. **No region replica model in Enable**: The implementation's `EnableTableProcedure` handles region replica count changes (add/remove replicas). The spec enables single-replica regions only.

---

## 6. Recommendations for Optimizing Developer Utility

### 6.1 Low-Cost, High-Value Improvements

These can be done without increasing state space significantly:

| # | Recommendation | Rationale | State Space Impact |
|---|---------------|-----------|-------------------|
| 1 | **Add `NoStuckRegions` temporal property** | `□(∀ r: regionState[r].state ∈ {"OPENING", "CLOSING"} ⇒ ◇ regionState[r].state ∉ {"OPENING", "CLOSING"})` — regions don't remain in transitional states forever. Check in liveness config. | Zero at safety level; liveness check cost |
| 2 | **Add region epoch / sequence number** | A monotonic counter per region incremented on each assignment. Enables modeling of stale-report rejection based on sequence mismatch rather than server name. | Small: adds one Nat per region |
| 3 | **Add table-level invariants to DEVELOPING.md** | The DEVELOPING.md invariant reference table (§3) should be extended with a "Table Enable/Disable Invariants" section mapping `TableEnabledStateConsistency`, `RegionEventuallyAssigned`, and enable/disable-specific change patterns. | Zero: documentation only |

### 6.2 Medium-Cost Improvements

These require some spec changes but don't fundamentally alter state space:

| # | Recommendation | Rationale | State Space Impact |
|---|---------------|-----------|-------------------|
| 4 | **Multi-region CreateTable** | Extend `CreateTablePrepare` to create N regions (parameter). The existing framework supports it — just loop over unused identifiers. | Moderate: increases symmetry-broken states proportional to N |
| 5 | **Add DISABLING/ENABLING intermediate table states** | Model the `TableState.State.DISABLING` / `ENABLING` intermediate states for concurrent client request rejection. Currently collapsed into atomics. | Small: adds 2 new states to `tableEnabled`; guards on concurrent requests |

### 6.3 Higher-Cost Improvements (Future Consideration)

| # | Recommendation | Rationale | State Space Impact |
|---|---------------|-----------|-------------------|
| 6 | **Meta write failure modeling** | Split meta-writing actions into attempt+succeed/fail. Add `metaWritePending` variable. Validate revert correctness. | **Large**: roughly doubles action count, significantly increases state space |
| 7 | **Concurrent split/merge (≥2)** | Relax `SplitMergeConstraint` to ≤2. Verifies concurrent splits on different regions don't interfere. | **Large**: quadratic state space increase |
| 8 | **Heartbeat-based region tracking** | Model `regionServerReport()` with online-region lists. Validates the `checkOnlineRegionsReport()` → unknown server detection pipeline end-to-end. | **Large**: adds periodic action with set comparison |

---

## 7. Spec-Implementation Correspondence Deep Dive

### 7.1 Variables: Spec ↔ Implementation

| Spec Variable | Implementation Structure | Correspondence |
|---------------|-------------------------|----------------|
| `regionState[r]` | `RegionStateNode` (in-memory) | **Exact**: state, location, procType, procStep, targetServer, retries all map 1:1 |
| `metaTable[r]` | `hbase:meta` table rows | **Exact**: [state, location, keyRange, table] per region |
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
| `parentProc[r]` | Split/Merge/Table procedure state machines | **Exact**: [type, step, ref1, ref2] record; now includes DISABLE/ENABLE types |
| `tableEnabled[t]` | `TableStateManager.setTableState()` | **Abstracted**: boolean (ENABLED/DISABLED only); implementation has ENABLING/DISABLING intermediate states collapsed |

### 7.2 Actions: Spec ↔ Implementation (Completeness Matrix)

**Total actions in spec**: 57 distinct actions across 15 modules.

| Module | Action Count | Key Actions |
|--------|-------------|-------------|
| `TRSP.tla` | 16 | TRSPCreate, TRSPGetCandidate, TRSPDispatchOpen, TRSPReportSucceedOpen, TRSPPersistToMetaOpen, DispatchFail, TRSPCreateUnassign, TRSPCreateMove, TRSPCreateReopen, TRSPDispatchClose, TRSPReportSucceedClose, TRSPPersistToMetaClose, TRSPConfirmClosedCrash, DispatchFailClose, TRSPServerCrashed, ResumeFromMeta |
| `SCP.tla` | 5 | SCPAssignMeta, SCPGetRegions, SCPFenceWALs, SCPAssignRegion, SCPDone |
| `RegionServer.tla` | 8 | RSOpen, RSFailOpen, RSClose, RSAbort, RSRestart, RSOpenDuplicate, RSCloseNotFound, DropStaleReport |
| `Master.tla` | 5 | GoOffline, MasterDetectCrash, MasterCrash, MasterRecover, DetectUnknownServer |
| `Split.tla` | 5 | SplitPrepare, SplitResumeAfterClose, SplitUpdateMeta, SplitDone, SplitFail |
| `Merge.tla` | 5 | MergePrepare, MergeCheckClosed, MergeUpdateMeta, MergeDone, MergeFail |
| `Create.tla` | 2 | CreateTablePrepare, CreateTableDone |
| `Delete.tla` | 2 | DeleteTablePrepare, DeleteTableDone |
| `Truncate.tla` | 4 | TruncatePrepare, TruncateDeleteMeta, TruncateCreateMeta, TruncateDone |
| `Disable.tla` | 2 | DisableTablePrepare, DisableTableDone |
| `Enable.tla` | 2 | EnableTablePrepare, EnableTableDone |
| `ZK.tla` | 1 | ZKSessionExpire |

**Coverage**: 100% — every mapped implementation code path has a corresponding spec action.

### 7.3 Invariants: What Each Protects

The 35 invariants form a comprehensive safety net. Grouped by what they protect:

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

**Table-Level Procedures** (5):
- `TableLockExclusivity`, `DeleteTableAtomicity`, `TruncateAtomicity`, `TruncateNoOrphans`, `TableEnabledStateConsistency`

### 7.4 Liveness Properties

| Property | Description | Fairness Required |
|----------|-------------|-------------------|
| `MetaEventuallyAssigned` | When meta becomes unavailable (ASSIGN_META), SCP reassigns it | WF on SCPAssignMeta |
| `OfflineEventuallyOpen` | ASSIGN-bearing OFFLINE region eventually reaches OPEN | WF on TRSP steps, SF on RS handlers |
| `SCPEventuallyDone` | Started SCP eventually completes | WF on all SCP steps |
| `RegionEventuallyAssigned` | ASSIGN on **enabled** table eventually reaches OPEN | WF on TRSP + enable guard |

### 7.5 Configurable Behaviors (16 toggles)

| Constant | Default | Description |
|----------|---------|-------------|
| `UseReopen` | FALSE | Branch-2.6 REOPEN procedure |
| `UseMerge` | FALSE | Merge actions in Next/Fairness |
| `UseCreate` | FALSE | CreateTable actions |
| `UseDelete` | FALSE | DeleteTable actions |
| `UseTruncate` | FALSE | TruncateTable actions |
| `UseDisable` | FALSE | Disable/Enable actions (single toggle) |
| `UseRSOpenDuplicateQuirk` | FALSE | RS duplicate-open silent drop |
| `UseRSCloseNotFoundQuirk` | FALSE | RS close-not-found silent drop |
| `UseRestoreSucceedQuirk` | FALSE | FAILED_OPEN replayed as OPENED |
| `UseBlockOnMetaWrite` | FALSE | Sync blocking on meta writes (branch-2.6) |
| `UseUnknownServerQuirk` | FALSE | Unknown server orphan handling |
| `UseMasterAbortOnMetaWriteQuirk` | FALSE | Master abort on meta write IOException |
| `UseStaleStateQuirk` | FALSE | Stale ServerStateNode on recovery |
| `MaxRetries` | 1 (exhaustive), 2 (sim) | Open retry count |
| `MaxWorkers` | 2 (exhaustive), 3 (sim) | PE worker pool size |
| `MaxKey` | 2 (exhaustive), 12 (sim) | Keyspace upper bound |

---

## 8. How to Use the Spec for Design Changes

### 8.1 Workflow for a Developer

1. **Identify the feature area** (TRSP, SCP, split/merge, table procedure, PE worker pool, enable/disable)
2. **Find the corresponding module** (TRSP.tla, SCP.tla, Split.tla, Disable.tla, Enable.tla, etc.)
3. **Model the proposed change** in TLA+ (add/modify actions, adjust guards)
4. **Add or modify invariants** if the change introduces new safety requirements
5. **Run exhaustive** (`AssignmentManager.cfg`) — fast feedback in ~71 min
6. **Run simulation** (`AssignmentManager-sim.cfg`) — deeper coverage
7. **If a violation is found**: the counterexample trace shows the exact failure sequence — use this to refine the design before writing code

See **[DEVELOPING.md](src/main/spec/DEVELOPING.md)** for detailed guidance on the five most common change patterns with annotated invariant references.

### 8.2 Common Change Patterns

| Change Type | Where to Edit | What to Verify |
|-------------|---------------|----------------|
| Add a TRSP state | `Types.tla` (`TRSPState`), `TRSP.tla` (new action + guards), `AssignmentManager.tla` (wire into `Next`/`Fairness`) | `ProcStepConsistency`, `LockExclusivity`, `TransitionValid` |
| Modify SCP ordering | `SCP.tla` (reorder steps), `AssignmentManager.tla` (`SCPMonotonicity`) | `FencingOrder`, `NoLostRegions`, `SCPMonotonicity` |
| Add a new quirk flag | `Types.tla` (constant+assume), relevant module (guarded action), all `.cfg` files | Existing invariants should detect the bug |
| Change split/merge step | `Split.tla` or `Merge.tla` (modify action) | `KeyspaceCoverage`, `SplitAtomicity`/`MergeAtomicity`, `NoOrphanedDaughters`/`NoOrphanedMergedRegion` |
| Add a new invariant | `AssignmentManager.tla` (define + wire into THEOREM), all `.cfg` files | The new invariant itself + no regression on existing |
| Modify enable/disable | `Disable.tla` or `Enable.tla` (modify action), `AssignmentManager.tla` | `TableEnabledStateConsistency`, `TableLockExclusivity`, `RegionEventuallyAssigned` |
| Add a table-level procedure | `Types.tla` (`TableExclusiveType`), new `.tla` module, `AssignmentManager.tla` (wire into `Next`/`Fairness`) | `TableLockExclusivity`, `KeyspaceCoverage`, relevant atomicity invariants |

---

## 9. Verification Configuration Summary

### 9.1 Three-Tier Verification Strategy

| Tier | Config | Features Enabled | State Space | Use Case |
|------|--------|-------------------|-------------|----------|
| **Exhaustive** | `AssignmentManager.cfg` | Split only, 3r/2s | 368M distinct states | Fast iteration (~71 min) |
| **Simulation** | `AssignmentManager-sim.cfg` | All (split, merge, create, delete, truncate, disable/enable, reopen), 9r/3s, 2 tables | Random traces | Deep coverage (15 min – 4 hr) |
| **Liveness** | `AssignmentManager-liveness.cfg` | Split only, 3r/2s, no symmetry, 2 tables | Larger than exhaustive | Temporal property verification |

### 9.2 Latest Verification Results

#### 3r/2s Exhaustive (Primary)

| Detail | Value |
|--------|-------|
| **Date** | 2026-03-15 |
| **TLC version** | 2026.03.02.213938 |
| **Config** | `AssignmentManager.cfg` (3r/2s: 1 deployed + 2 unused, split only) |
| **Mode** | Exhaustive with symmetry reduction |
| **Workers** | 128 on 128 cores |
| **Result** | All 35 invariants, 2 action constraints, and state constraint passed |
| **States generated** | 1,328,348,760 |
| **States checked** | 368,662,744 distinct |
| **Depth** | 92 |
| **Duration** | ~71 min |

#### 9r/3s Simulation

| Detail | Value |
|--------|-------|
| **Date** | 2026-03-15 |
| **TLC version** | 2026.03.02.213938 |
| **Config** | `AssignmentManager-sim.cfg` (9r/3s: 3 deployed + 6 unused, all features) |
| **Mode** | Random Simulation (seed -8405536033383709680) |
| **Workers** | 128 on 128 cores |
| **Result** | All 35 invariants, 2 action constraints, and state constraint passed |
| **States generated** | 1,247,314,282 |
| **Duration** | 8 hours |

---

## 10. Conclusion

The specification has reached a mature and highly useful state. The iterative development has systematically built a model that is faithful to the implementation at every critical decision point, while making principled abstractions where modeling cost would exceed verification value.

**Key strengths** for developer utility:
- **Exhaustive safety verification** of the core assignment protocol across 35 invariants
- **Complete table lifecycle** — Create, Enable, Disable, Delete, Truncate all modeled
- **Quirk flags** enabling bug reproduction and fix validation (6 known bugs)
- **Rich liveness suite** — 4 temporal properties including enabled-table-aware region assignment
- **Two-tier verification** (exhaustive + simulation) for fast/deep tradeoff
- **Developer guide** (`DEVELOPING.md`) with annotated change patterns and invariant references

The negotiation between fidelity and tractability is well-calibrated. The current abstractions (atomic RS steps, no meta-write failures, no heartbeat model, collapsed enable/disable states) are justified by the lock-discipline analysis and the observation that these intermediate states are not observable by concurrent actors or are orthogonal to the core assignment safety properties. The spec successfully serves its primary purpose of validating design and architecture changes to the AssignmentManager before implementation.
