# Developer Guide: Modeling Changes with the TLA+ Specification

This guide shows how to use the AssignmentManager TLA+ specification to validate
proposed design and architecture changes **before** writing implementation code.
It covers the five most common change patterns, the end-to-end verification
workflow, and annotated guidance on which invariants to check for each kind of
change.

---

## 1. Verification Workflow

For any proposed change to the AssignmentManager or its supporting subsystems,
follow this workflow:

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. Identify the feature area                                    │
│    (TRSP, SCP, Split/Merge, Table procedure, PE worker pool)    │
├─────────────────────────────────────────────────────────────────┤
│ 2. Find the corresponding spec module                           │
│    (TRSP.tla, SCP.tla, Split.tla, Merge.tla, Create.tla, etc.)  │
├─────────────────────────────────────────────────────────────────┤
│ 3. Model the proposed change in TLA+                            │
│    (add/modify actions, adjust guards, add/modify types)        │
├─────────────────────────────────────────────────────────────────┤
│ 4. Add or modify invariants if the change introduces            │
│    new safety requirements                                      │
├─────────────────────────────────────────────────────────────────┤
│ 5. Run EXHAUSTIVE verification (3r/2s) — ~70 min                │
│    → fast feedback on core safety properties                    │
├─────────────────────────────────────────────────────────────────┤
│ 6. Run SIMULATION (9r/3s) — configurable duration               │
│    → deeper coverage including merge, create, delete, truncate  │
├─────────────────────────────────────────────────────────────────┤
│ 7. If a violation is found: the counterexample trace shows the  │
│    exact failure sequence — refine the design and repeat        │
└─────────────────────────────────────────────────────────────────┘
```

### Running Verification

**Exhaustive (3r/2s, ~70 min):**

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager.cfg -workers auto -cleanup
```

**Simulation (9r/3s, configurable duration):**

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  -Dtlc2.TLC.stopAfter=3600 \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg -simulate \
  -workers auto -cleanup
```

**Liveness (3r/2s, no symmetry):**

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-liveness.cfg -workers auto -cleanup
```

### Recommended Durations for Simulation

| Tier | Duration | Use Case |
|------|----------|----------|
| Per-iteration | 900s (15 min) | Feedback during development |
| Post-iteration | 3600s (1 hr) | Validation after completing an iteration |
| Post-phase | 14400s (4 hr) | Milestone verification |

---

## 2. Common Change Patterns

### Pattern A: Adding a TRSP State

**When:** You are adding a new step to `TransitionRegionStateProcedure` — for
example, a new region state, a new procedure step, or a new guard condition.

**Where to edit:**

1. **`Types.tla`** — Add the new state to `TRSPState` or the new region state to
   `State`. Update `ValidTransition` if introducing a new state transition.
2. **`TRSP.tla`** — Add the new action(s) with appropriate preconditions and
   effects. Wire the new action into the existing flow by adjusting `procStep`
   guards.
3. **`AssignmentManager.tla`** — Add the new action to the `Next` disjunction
   and, if appropriate, to the `Fairness` condition.

**What to verify (primary invariants):**

| Invariant | Why |
|-----------|-----|
| `ProcStepConsistency` | Ensures the new step correlates with legitimate region lifecycle states |
| `LockExclusivity` | Ensures the new procedure type/step does not break exclusivity of region locks |
| `TransitionValid` (action constraint) | Ensures every region state change through the new step is in `ValidTransition` |
| `ProcStoreConsistency` | Ensures the persisted procedure record remains consistent with in-memory state |
| `ProcStoreBijection` | Ensures 1:1 mapping between in-memory and persisted procedures is maintained |
| `NoDoubleAssignment` | Core safety: new step must not create dual-writable state |

---

### Pattern B: Modifying SCP Ordering

**When:** You are reordering steps in `ServerCrashProcedure` — for example,
changing when WALs are fenced relative to region reassignment, or changing the
meta-reassignment timing.

**Where to edit:**

1. **`SCP.tla`** — Reorder the step actions or modify the guards that control
   progression through `scpState`.
2. **`AssignmentManager.tla`** — Update the `SCPMonotonicity` action constraint
   definition if the step order changes.

**What to verify (primary invariants):**

| Invariant | Why |
|-----------|-----|
| `FencingOrder` | **Critical**: WALs must be fenced before any reassignment to prevent split-brain writes |
| `NoLostRegions` | After SCP completes, every region from the crashed server must have a recovery procedure |
| `SCPMonotonicity` (action constraint) | Ensures the SCP state machine remains strictly monotonic under the new ordering |
| `MetaAvailableForRecovery` | If the crashed server was hosting meta, meta must be reassigned before region recovery proceeds |
| `AtMostOneCarryingMeta` | At most one server can be carrying `hbase:meta` at any time |
| `NoDoubleAssignment` | SCP reordering must not introduce a window where a region is writable on two servers |

---

### Pattern C: Adding a New Quirk Flag

**When:** You have identified an implementation bug and want to model it as a
toggleable behavior in the spec, either to reproduce the failure or to validate
a proposed fix.

**Where to edit:**

1. **`Types.tla`** — Add the new constant (e.g., `UseMyNewQuirk`) and an
   `ASSUME` declaration constraining it to `BOOLEAN`.
2. **Relevant module** (e.g., `TRSP.tla`, `SCP.tla`, `RegionServer.tla`) — Add
   the guarded behavior: `IF UseMyNewQuirk THEN <buggy behavior> ELSE <correct behavior>`.
3. **All `.cfg` files** — Add the new constant with `FALSE` (default) in
   `AssignmentManager.cfg`; decide on `TRUE`/`FALSE` for `AssignmentManager-sim.cfg`
   and `AssignmentManager-liveness.cfg`.

**What to verify:**

- With the quirk flag **`TRUE`**: existing invariants should **detect** the bug,
  producing a counterexample trace that demonstrates the failure.
- With the quirk flag **`FALSE`** (fix applied): all invariants should **pass**,
  confirming the fix is correct.

**Existing quirk flags for reference:**

| Quirk Flag | Bug Description |
|------------|-----------------|
| `UseRSOpenDuplicateQuirk` | RS silently drops OPEN for already-online region → TRSP deadlock |
| `UseRSCloseNotFoundQuirk` | RS silently drops CLOSE for not-found region → TRSP deadlock |
| `UseRestoreSucceedQuirk` | `restoreSucceedState()` replays FAILED_OPEN as OPENED |
| `UseUnknownServerQuirk` | `checkOnlineRegionsReport()` closes orphans without TRSP |
| `UseMasterAbortOnMetaWriteQuirk` | `updateRegionLocation()` calls `master.abort()` on IOException |
| `UseStaleStateQuirk` | `visitMeta()` creates ONLINE ServerStateNode for dead servers |

---

### Pattern D: Changing a Split or Merge Step

**When:** You are modifying the split or merge procedure — for example, changing
daughter materialization, adjusting the point-of-no-return (PONR), or modifying
rollback behavior.

**Where to edit:**

1. **`Split.tla`** or **`Merge.tla`** — Modify the relevant action
   (`SplitPrepare`, `SplitResumeAfterClose`, `SplitUpdateMeta`, `SplitDone`,
   `SplitFail`, or the merge equivalents).
2. **`Types.tla`** — If adding new parent procedure steps, update
   `ParentProcStep`.
3. **`AssignmentManager.tla`** — Wire new actions into `Next`/`Fairness` if added.

**What to verify (primary invariants):**

| Invariant | Why |
|-----------|-----|
| `KeyspaceCoverage` | **Critical**: all keys in `[0, MaxKey)` must be covered by exactly one live region — no gaps, no overlaps |
| `SplitAtomicity` / `MergeAtomicity` | Pre-PONR, daughters/merged region must not be materialized |
| `NoOrphanedDaughters` / `NoOrphanedMergedRegion` | Newly materialized regions must always have an ASSIGN procedure |
| `SplitCompleteness` / `MergeCompleteness` | After procedure completes, parent procedure state must be cleared |
| `SplitMergeMutualExclusion` | Daughter/merged regions cannot have active parent procedures |

> **Tip:** Split changes are tested in **exhaustive** mode (`UseMerge = FALSE`).
> Merge changes require **simulation** mode (`UseMerge = TRUE`) since merge is
> disabled in exhaustive mode for tractability.

---

### Pattern E: Adding a New Invariant

**When:** Your proposed change introduces new safety requirements that are not
covered by existing invariants.

**Where to edit:**

1. **`AssignmentManager.tla`** — Define the invariant predicate and wire it into
   the `THEOREM` declaration. Place it near semantically related invariants.
2. **All `.cfg` files** — Add the new invariant to the `INVARIANTS` section in
   `AssignmentManager.cfg`, `AssignmentManager-sim.cfg`, and
   `AssignmentManager-liveness.cfg`.

**What to verify:**

- The new invariant **passes** under the current specification (no false positives).
- All **existing** invariants still pass (no regressions from any spec changes
  needed to support the new invariant).
- If modeling a bug fix, toggle the relevant quirk flag to `TRUE` and verify that
  the new invariant **catches** the bug.

---

## 3. Invariant Reference: When to Check What

The following table maps each invariant to the change areas where it is most
relevant. When modifying a given area, **prioritize** the invariants listed for
that area.

### Core Safety Invariants

| Invariant | Check When Changing |
|-----------|---------------------|
| `NoDoubleAssignment` | SCP ordering, RSAbort timing, WAL fencing, TRSP assignment guards, master crash/recovery |
| `FencingOrder` | SCP step ordering, WAL fencing logic, crash detection timing |
| `NoLostRegions` | SCP region snapshot, SCP completion logic, crash recovery flow |
| `MetaConsistency` | Any action that writes to `metaTable`, TRSP meta-persist steps, master recovery |
| `KeyspaceCoverage` | Split/merge PONR, daughter materialization, keyspace computation, delete/truncate |
| `NoPEWorkerDeadlock` | PEWorker pool size, meta-blocking/suspension logic, procedure spawn counts |

### State Machine Consistency Invariants

| Invariant | Check When Changing |
|-----------|---------------------|
| `TypeOK` | Any change to `Types.tla` type sets, new variables, constant modifications |
| `LockExclusivity` | Adding procedure types, changing procedure lifecycle, lock acquisition/release |
| `ProcStepConsistency` | TRSP step progression, adding new procedure steps |
| `TargetServerConsistency` | Target server selection, server assignment in TRSP |
| `NoOrphanedProcedures` | Procedure creation/completion, OFFLINE state handling |
| `ProcStoreConsistency` | Procedure store writes, master crash/recovery, `RestoreSucceedState` |
| `ProcStoreBijection` | Procedure lifecycle (create → persist → complete → delete) |
| `SCPMonotonicity` | SCP step ordering, SCP state transitions |

### Location Tracking Invariants

| Invariant | Check When Changing |
|-----------|---------------------|
| `OpenImpliesLocation` | TRSP assignment completion, RS open handlers |
| `OfflineImpliesNoLocation` | TRSP unassignment, SCP region recovery, GoOffline |
| `OpeningImpliesLocation` | TRSP dispatch, target server selection |
| `ClosingImpliesLocation` | TRSP close dispatch, unassignment flow |
| `ServerRegionsTrackLocation` | `serverRegions` bookkeeping, SCP region snapshot |

### RS-Master Agreement Invariants

| Invariant | Check When Changing |
|-----------|---------------------|
| `RSMasterAgreement` | RS open/close handlers, report processing, transition guards |
| `RSMasterAgreementConverse` | RS abort, crash handling, `rsOnlineRegions` updates |

### SCP/Meta Integrity Invariants

| Invariant | Check When Changing |
|-----------|---------------------|
| `MetaAvailableForRecovery` | SCP meta-reassignment step ordering |
| `AtMostOneCarryingMeta` | Meta assignment, SCP meta-detection |
| `DispatchCorrespondance` | Command dispatch logic, `dispatchedOps` management |

### Split/Merge Atomicity Invariants

| Invariant | Check When Changing |
|-----------|---------------------|
| `SplitAtomicity` | Split PONR logic, split preparation, daughter creation timing |
| `SplitCompleteness` | Split completion, parent procedure cleanup |
| `NoOrphanedDaughters` | Daughter TRSP spawning, split meta-update |
| `SplitMergeMutualExclusion` | Concurrent split/merge guards, parent procedure assignment |
| `MergeAtomicity` | Merge PONR logic, merged-region materialization timing |
| `MergeCompleteness` | Merge completion, target procedure cleanup |
| `NoOrphanedMergedRegion` | Merged-region TRSP spawning, merge meta-update |

### Table-Level Procedure Invariants

| Invariant | Check When Changing |
|-----------|---------------------|
| `TableLockExclusivity` | Table lock acquisition in create/delete/truncate, concurrent table ops |
| `DeleteTableAtomicity` | Delete procedure table-wide lock acquisition, region-by-region cleanup |
| `TruncateAtomicity` | Truncate procedure table-wide lock acquisition, meta-delete/re-create window |
| `TruncateNoOrphans` | Truncate SPAWNED_OPEN step, child TRSP spawning after meta-create |

---

## 4. Module–Implementation Mapping

Use this to quickly locate which spec module corresponds to the implementation
code you are changing:

| Implementation Class / Package | Spec Module | Key Actions |
|-------------------------------|-------------|-------------|
| `AssignmentManager` | `AssignmentManager.tla` | `Init`, `Next`, `Fairness`, invariants |
| `TransitRegionStateProcedure` / `OpenRegionProcedure` / `CloseRegionProcedure` | `TRSP.tla` | 17 TRSP actions (assign, unassign, move, reopen, dispatch, confirm, failure, crash recovery) |
| `ServerCrashProcedure` | `SCP.tla` | 5 SCP steps (detect → assign meta → get regions → fence WALs → assign → done) |
| `SplitTableRegionProcedure` | `Split.tla` | `SplitPrepare`, `SplitResumeAfterClose`, `SplitUpdateMeta`, `SplitDone`, `SplitFail` |
| `MergeTableRegionsProcedure` | `Merge.tla` | `MergePrepare`, `MergeCheckClosed`, `MergeUpdateMeta`, `MergeDone`, `MergeFail` |
| `CreateTableProcedure` | `Create.tla` | `CreateTablePrepare`, `CreateTableDone` |
| `DeleteTableProcedure` | `Delete.tla` | `DeleteTablePrepare`, `DeleteTableDone` |
| `TruncateTableProcedure` | `Truncate.tla` | `TruncatePrepare`, `TruncateDeleteMeta`, `TruncateCreateMeta`, `TruncateDone` |
| `HRegionServer` / `AssignRegionHandler` / `UnassignRegionHandler` | `RegionServer.tla` | RS-side open, close, fail-open, abort, restart, duplicate-open, close-not-found |
| `HMaster` / `ServerManager` | `Master.tla` | `GoOffline`, `MasterDetectCrash`, `MasterCrash`, `MasterRecover`, `DetectUnknownServer` |
| `WALProcedureStore` / `RegionProcedureStore` | `ProcStore.tla` | Store invariants, `RestoreSucceedState` |
| ZooKeeper ephemeral nodes | `ZK.tla` | `ZKSessionExpire` |
| Region/table types, state enumerations | `Types.tla` | Type definitions, `ValidTransition`, parent-child procedure types |

---

## 5. Tips for Effective Spec-Driven Development

1. **Start small.** Make the minimal change to the spec that captures your
   proposed design. Run exhaustive verification first. Add complexity
   incrementally.

2. **Read counterexample traces carefully.** When TLC reports a violation, it
   produces a step-by-step trace. Each step is an action name + the values of
   every state variable. The trace is the **exact** sequence of events that
   breaks your invariant — this is the spec's most valuable output.

3. **Use simulation for merge and table operations.** Merge, create, delete, and
   truncate are disabled in exhaustive mode for tractability. Enable them via
   `UseMerge`, `UseCreate`, `UseDelete`, `UseTruncate` in simulation configs.

4. **Test fixes against quirk flags.** If your change fixes a known bug, enable
   the corresponding quirk flag and verify the invariant violation still fires.
   Then disable the quirk flag (apply your fix) and verify all invariants pass.

5. **Preserve symmetry reduction.** When adding identifiers (regions, servers),
   keep them in the symmetry sets when possible. Symmetry reduction can reduce
   state space by orders of magnitude. Deployed regions (`DeployedRegions`)
   cannot be in symmetry sets because they have fixed initial keyspace
   assignments.

6. **Liveness requires no symmetry.** Liveness properties (`MetaEventuallyAssigned`,
   `OfflineEventuallyOpen`, `SCPEventuallyDone`) are incompatible with TLC's
   `SYMMETRY` reduction. Use `AssignmentManager-liveness.cfg` for sound liveness
   checking.

7. **Check both tiers.** A change that passes exhaustive (3r/2s) may fail in
   simulation (9r/3s) due to deeper state space exploration (more retries,
   concurrent operations, merge interactions). Always run both.
