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

**Simulation (9r/3s, configurable duration):**

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  -Dtlc2.TLC.stopAfter=3600 \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg -simulate \
  -workers auto -cleanup
```

**Exhaustive (3r/2s):**

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager.cfg -workers auto -cleanup
```

**Liveness (3r/2s, no symmetry):**

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-liveness.cfg -workers auto -cleanup
```

### Recommended Durations for Simulation

| Tier | Duration | Use Case |
|------|----------|----------|
| Per-iteration | 300s (5 min) | Feedback during development |
| Post-iteration | 900s (15 min) | Validation after completing an iteration |
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

### Table Enable/Disable Invariants

| Invariant | Check When Changing |
|-----------|---------------------|
| `TableEnabledStateConsistency` | Disable/Enable procedure flow, `tableEnabled` state transitions, `TRSPCreate` guards |
| `RegionEventuallyAssigned` | Disable/Enable procedure liveness, `TRSPCreate` disabled-table guard, ASSIGN pipeline |
| `NoStuckRegions` | TRSP dispatch/confirm, RS handlers, SCP interaction with transitional states |

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
| `DisableTableProcedure` | `Disable.tla` | `DisableTablePrepare`, `DisableTableDone` |
| `EnableTableProcedure` | `Enable.tla` | `EnableTablePrepare`, `EnableTableDone` |
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
   `OfflineEventuallyOpen`, `SCPEventuallyDone`, `RegionEventuallyAssigned`,
   `NoStuckRegions`) are incompatible with TLC's
   `SYMMETRY` reduction. Use `AssignmentManager-liveness.cfg` for sound liveness
   checking.

7. **Check both tiers.** A change that passes exhaustive (3r/2s) may fail in
   simulation (9r/3s) due to deeper state space exploration (more retries,
   concurrent operations, merge interactions). Always run both.

---

## 6. How To: Understanding a TLA+ Counterexample Trace

When TLC finds an invariant violation, it produces a **counterexample trace**: a
step-by-step sequence of states from `Init` to the violating state. Each state
is labeled with the action that produced it and shows the values of every state
variable. This trace is the spec's single most valuable output — it is the
**exact** interleaving of events that breaks your invariant, and it often
reveals race conditions or ordering bugs that are extremely difficult to find
by code review alone.

This section teaches you how to read a TLC counterexample trace and how to use
an LLM (Claude, Cursor, or Antigravity) to analyze it.

### 6.1 Anatomy of a Trace Step

TLC output for a counterexample follows this structure:

```
Error: Invariant <InvariantName> is violated.
Error: The behavior up to this point is:
State 1: <Initial predicate>
/\ var1 = <value>
/\ var2 = <value>
...
State 2: <Action line L, col C to line L2, col C2 of module Module>
/\ var1 = <value>       (only changed variables differ from State 1)
/\ var2 = <value>
...
State N: <Action line L, col C to line L2, col C2 of module Module>
/\ var1 = <value>       ← the state that violates the invariant
...
```

Key elements of each state:

| Element | Meaning |
|---------|---------|
| `State N:` | Sequential state number (1 = initial state) |
| `<Action line L, col C ... of module M>` | The TLA+ action that produced this state, with its source location in the spec module. Map to implementation code via the § 4 Module–Implementation Mapping table. |
| `/\ var = value` | The full value of every state variable. TLC prints **all** variables in every state, not just the ones that changed. You must diff consecutive states manually to identify what changed. |

**Reading tip:** Start from the **last state** (the violating state) and work
backwards. Identify which variable(s) violate the invariant predicate, then
trace backwards through the states to find the action that introduced the
problematic value.

### 6.2 Contrived Counterexample Trace

The following trace is a **contrived but realistic** example of what TLC
produces when it discovers a `NoDoubleAssignment` violation. The scenario uses
the default exhaustive model configuration (`AssignmentManager.cfg`) with
`UseRSOpenDuplicateQuirk = TRUE` added as a modification. Model constants:
`Regions = {r1, r2, r3}`, `Servers = {s1, s2}`, `DeployedRegions = {r1}`,
`MaxRetries = 1`, `MaxWorkers = 2`.

> **Scenario summary:** Region `r1` is assigned to server `s1` and becomes
> OPEN. A MOVE procedure starts to relocate `r1` from `s1` to `s2`. The MOVE
> dispatches a CLOSE to `s1`, which completes. Then the MOVE dispatches an OPEN
> to `s2`, and `s2` opens the region. But a duplicate OPEN command from a prior
> dispatch-fail retry is still queued on `s1`. Server `s1` — which still has
> `r1` in `rsOnlineRegions` from the initial assignment — processes the stale
> duplicate OPEN via `RSOpenDuplicate`, silently consuming it. Meanwhile `s2`
> has also opened `r1`. The result: `r1 ∈ rsOnlineRegions[s1]` and
> `r1 ∈ rsOnlineRegions[s2]` with neither server WAL-fenced, violating
> `NoDoubleAssignment`.

The full TLC output is below. For brevity, only the variables that are
**relevant to the violation path** are shown in each state. In a real TLC run,
all 19 variables would be printed in every state.

```
Error: Invariant NoDoubleAssignment is violated.
Error: The behavior up to this point is:
State 1: <Initial predicate>
/\ regionState = (r1 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0])
/\ metaTable = (r1 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> [startKey |-> 0, endKey |-> 2],
                        table |-> T1]
             @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> NoRange, table |-> NoTable]
             @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> NoRange, table |-> NoTable])
/\ dispatchedOps = (s1 :> {} @@ s2 :> {})
/\ pendingReports = {}
/\ rsOnlineRegions = (s1 :> {} @@ s2 :> {})
/\ serverState = (s1 :> "ONLINE" @@ s2 :> "ONLINE")
/\ scpState = (s1 :> "NONE" @@ s2 :> "NONE")
/\ walFenced = (s1 :> FALSE @@ s2 :> FALSE)
/\ serverRegions = (s1 :> {} @@ s2 :> {})
/\ procStore = (r1 :> NoProcedure @@ r2 :> NoProcedure @@ r3 :> NoProcedure)
/\ masterAlive = TRUE
/\ zkNode = (s1 :> TRUE @@ s2 :> TRUE)
/\ availableWorkers = 2

State 2: <TRSPCreate line 113, col 3 to line 163, col 9 of module TRSP>
/\ regionState = (r1 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "ASSIGN", procStep |-> "GET_ASSIGN_CANDIDATE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0])
/\ procStore = (r1 :> [type |-> "ASSIGN", step |-> "GET_ASSIGN_CANDIDATE",
                        targetServer |-> NoServer, transitionCode |-> NoTransition]
             @@ r2 :> NoProcedure @@ r3 :> NoProcedure)

State 3: <TRSPGetCandidate line 186, col 3 to line 221, col 9 of module TRSP>
/\ regionState = (r1 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "ASSIGN", procStep |-> "OPEN",
                          targetServer |-> s1, retries |-> 0]
               @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0])
/\ procStore = (r1 :> [type |-> "ASSIGN", step |-> "OPEN",
                        targetServer |-> s1, transitionCode |-> NoTransition]
             @@ r2 :> NoProcedure @@ r3 :> NoProcedure)

State 4: <TRSPDispatchOpen line 239, col 3 to line 316, col 9 of module TRSP>
/\ regionState = (r1 :> [state |-> "OPENING", location |-> s1,
                          procType |-> "ASSIGN", procStep |-> "CONFIRM_OPENED",
                          targetServer |-> s1, retries |-> 0]
               @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0])
/\ metaTable = (r1 :> [state |-> "OPENING", location |-> s1,
                        keyRange |-> [startKey |-> 0, endKey |-> 2],
                        table |-> T1]
             @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> NoRange, table |-> NoTable]
             @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> NoRange, table |-> NoTable])
/\ dispatchedOps = (s1 :> {[type |-> "OPEN", region |-> r1]} @@ s2 :> {})
/\ serverRegions = (s1 :> {r1} @@ s2 :> {})
/\ procStore = (r1 :> [type |-> "ASSIGN", step |-> "CONFIRM_OPENED",
                        targetServer |-> s1, transitionCode |-> NoTransition]
             @@ r2 :> NoProcedure @@ r3 :> NoProcedure)

State 5: <RSOpen line 148, col 3 to line 187, col 9 of module RegionServer>
/\ dispatchedOps = (s1 :> {} @@ s2 :> {})
/\ pendingReports = {[server |-> s1, region |-> r1, code |-> "OPENED"]}
/\ rsOnlineRegions = (s1 :> {r1} @@ s2 :> {})

State 6: <TRSPReportSucceedOpen line 337, col 3 to line 402, col 9 of module TRSP>
/\ regionState = (r1 :> [state |-> "OPEN", location |-> s1,
                          procType |-> "ASSIGN", procStep |-> "REPORT_SUCCEED",
                          targetServer |-> s1, retries |-> 0]
               @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0])
/\ pendingReports = {}
/\ procStore = (r1 :> [type |-> "ASSIGN", step |-> "REPORT_SUCCEED",
                        targetServer |-> s1, transitionCode |-> "OPENED"]
             @@ r2 :> NoProcedure @@ r3 :> NoProcedure)

State 7: <TRSPPersistToMetaOpen line 419, col 3 to line 598, col 9 of module TRSP>
/\ regionState = (r1 :> [state |-> "OPEN", location |-> s1,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0])
/\ metaTable = (r1 :> [state |-> "OPEN", location |-> s1,
                        keyRange |-> [startKey |-> 0, endKey |-> 2],
                        table |-> T1]
             @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> NoRange, table |-> NoTable]
             @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> NoRange, table |-> NoTable])
/\ procStore = (r1 :> NoProcedure @@ r2 :> NoProcedure @@ r3 :> NoProcedure)

State 8: <TRSPCreateMove line 945, col 3 to line 1012, col 9 of module TRSP>
/\ regionState = (r1 :> [state |-> "OPEN", location |-> s1,
                          procType |-> "MOVE", procStep |-> "CLOSE",
                          targetServer |-> s1, retries |-> 0]
               @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0])
/\ procStore = (r1 :> [type |-> "MOVE", step |-> "CLOSE",
                        targetServer |-> s1, transitionCode |-> NoTransition]
             @@ r2 :> NoProcedure @@ r3 :> NoProcedure)

State 9: <TRSPDispatchClose line 835, col 3 to line 942, col 9 of module TRSP>
/\ regionState = (r1 :> [state |-> "CLOSING", location |-> s1,
                          procType |-> "MOVE", procStep |-> "CONFIRM_CLOSED",
                          targetServer |-> s1, retries |-> 0]
               @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0]
               @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                          procType |-> "NONE", procStep |-> "IDLE",
                          targetServer |-> NoServer, retries |-> 0])
/\ metaTable = (r1 :> [state |-> "CLOSING", location |-> s1,
                        keyRange |-> [startKey |-> 0, endKey |-> 2],
                        table |-> T1]
             @@ r2 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> NoRange, table |-> NoTable]
             @@ r3 :> [state |-> "OFFLINE", location |-> NoServer,
                        keyRange |-> NoRange, table |-> NoTable])
/\ dispatchedOps = (s1 :> {[type |-> "CLOSE", region |-> r1]} @@ s2 :> {})

State 10: <DispatchFail line 645, col 3 to line 717, col 9 of module TRSP>
/\ dispatchedOps = (s1 :> {[type |-> "OPEN", region |-> r1]} @@ s2 :> {})
/\ serverState = (s1 :> "CRASHED" @@ s2 :> "ONLINE")
/\ scpState = (s1 :> "GET_REGIONS" @@ s2 :> "NONE")

State 11: <SCPGetRegions line 202, col 3 to line 270, col 9 of module SCP>
/\ scpState = (s1 :> "FENCE_WALS" @@ s2 :> "NONE")
/\ scpRegions = (s1 :> {r1} @@ s2 :> {})

State 12: <RSOpen line 148, col 3 to line 187, col 9 of module RegionServer>
/\ dispatchedOps = (s1 :> {} @@ s2 :> {})
/\ pendingReports = {[server |-> s1, region |-> r1, code |-> "OPENED"]}
/\ rsOnlineRegions = (s1 :> {r1} @@ s2 :> {})
```

At State 12, the invariant is checked:

```
NoDoubleAssignment ==
  \A r \in Regions:
    Cardinality({s \in Servers:
          r \in rsOnlineRegions[s] /\ walFenced[s] = FALSE
        }) <= 1
```

At this point: `r1 ∈ rsOnlineRegions[s1]` (from the original open in State 5,
never cleared because `RSAbort` has not yet fired) and `walFenced[s1] = FALSE`
(SCP is at `FENCE_WALS` but has not yet executed `SCPFenceWALs`). The stale
OPEN command on `s1` from the `DispatchFail` crash disjunct (State 10) was
consumed by `RSOpen` in State 12, which re-confirmed `r1` in
`rsOnlineRegions[s1]`. Since `walFenced[s1] = FALSE` and
`walFenced[s2] = FALSE`, the cardinality of writable servers for `r1` would be
2 if `r1` were also on `s2` — in this trace the violation is that `s1`, a
crashed-but-not-yet-fenced zombie, still has `r1` writable.

> **Note:** This trace is contrived for illustration. In the actual spec with
> default configuration (`UseRSOpenDuplicateQuirk = FALSE`), TLC exhaustively
> verifies that `NoDoubleAssignment` holds. The quirk flags exist precisely to
> surface implementation bugs as counterexample traces.

### 6.3 Reading the Trace: Step-by-Step Annotation

| State | Action | What happened | Why it matters |
|-------|--------|---------------|----------------|
| 1 | `Init` | Region `r1` is OFFLINE, all servers ONLINE, no procedures | Starting state. |
| 2 | `TRSPCreate` | ASSIGN procedure created for `r1` | Normal assignment flow begins. |
| 3 | `TRSPGetCandidate` | Target server `s1` chosen | `r1` will be opened on `s1`. |
| 4 | `TRSPDispatchOpen` | OPEN dispatched to `s1`; `r1` → OPENING | Meta persisted with OPENING + `s1`. |
| 5 | `RSOpen` | `s1` opens `r1`, adds to `rsOnlineRegions[s1]`, reports OPENED | Region is now writable on `s1`. |
| 6 | `TRSPReportSucceedOpen` | Master consumes OPENED, in-memory → OPEN | Procedure at `REPORT_SUCCEED`. |
| 7 | `TRSPPersistToMetaOpen` | Meta updated to OPEN, procedure cleared | `r1` is stably OPEN on `s1`. |
| 8 | `TRSPCreateMove` | MOVE procedure starts for `r1` | Region will be relocated to another server. |
| 9 | `TRSPDispatchClose` | CLOSE dispatched to `s1`; `r1` → CLOSING | Close phase of MOVE begins. |
| 10 | `DispatchFail` (crash disjunct) | Close dispatch fails; `s1` CRASHED, SCP starts at `GET_REGIONS`. Stale OPEN command injected into `dispatchedOps[s1]`. | **Key event.** `s1` crashes but `walFenced[s1]` is still `FALSE`. Stale OPEN for `r1` appears on `s1`. |
| 11 | `SCPGetRegions` | SCP snapshots `r1`; advances to `FENCE_WALS` | `walFenced[s1]` is still `FALSE` – SCP hasn't fenced yet. |
| 12 | `RSOpen` | Zombie `s1` processes stale OPEN, `r1` back in `rsOnlineRegions[s1]` | **Violation.** `r1` writable on crashed `s1` (not fenced) while SCP hasn't completed. |

### 6.4 Using an LLM to Analyze a Counterexample Trace

When TLC reports a violation, you can paste the trace into an LLM (Claude in
Cursor, Antigravity, or a standalone chat session) along with relevant context.
The LLM can help you:

1. **Identify root cause** — Which action introduced the invariant-violating
   state? What interleaving made it possible?
2. **Determine spec vs. implementation bug** — Is the violation a genuine
   implementation bug, or is the spec model too permissive / incorrect?
3. **Suggest fixes** — Propose spec changes (new guards, reordered steps) or
   implementation patches.

#### Prompt Template

Copy-paste the following template, filling in the bracketed sections:

````
I have a TLA+ counterexample trace from the HBase AssignmentManager
specification. Please analyze the trace and help me understand the
root cause of the invariant violation.

## Violated Invariant

[Paste the invariant definition from AssignmentManager.tla]

## Counterexample Trace

[Paste the full TLC output, starting from "Error: Invariant ..."]

## Relevant Spec Module(s)

[Paste the action definitions from the relevant .tla file(s) that
 appear in the trace — typically the actions named in State N: headers]

## Implementation Code (optional)

[Paste the corresponding Java class or method, using the § 4
 Module–Implementation Mapping table to locate it]

## Questions

1. What is the root cause of this invariant violation?
2. At which state in the trace does the critical event occur?
3. Is this a bug in the implementation or in the spec model?
4. What change would fix this? Provide both a spec-level fix
   (new guard or action change) and an implementation-level fix
   (Java code change) if applicable.
````

#### What to Expect from the LLM

A good LLM analysis will:

- Walk through the trace state by state, noting which variables changed
- Identify the **critical state** (often 2–3 steps before the violation)
- Explain the interleaving that allowed the bug
- Distinguish between the spec model's abstraction and the implementation's
  actual behavior
- Suggest concrete guards or ordering changes

#### Iterating After the Fix

1. Apply the suggested fix to the spec (add a guard, reorder steps, add an
   invariant).
2. Re-run TLC in exhaustive mode. If the violation disappears, proceed to
   simulation.
3. If TLC finds a new violation from the fix, paste the new trace and iterate.
4. Once TLC passes, apply the corresponding implementation change and run
   unit/integration tests.

### 6.5 Common Patterns in Counterexample Traces

When reading traces, watch for these recurring patterns:

| Pattern | What to look for | Typical invariants violated |
|---------|------------------|-----------------------------|
| **Zombie window** | A gap between `MasterDetectCrash(s)` and `RSAbort(s)` where `rsOnlineRegions[s]` still contains regions from the crashed server | `NoDoubleAssignment`, `RSMasterAgreementConverse` |
| **Interleaving race** | An RS-side action (e.g., `RSOpen`) fires between two TRSP steps (e.g., after `TRSPDispatchOpen` but before `TRSPReportSucceedOpen`) | `LockExclusivity`, `ProcStepConsistency` |
| **Meta unavailability** | Actions stall on `MetaIsAvailable = FALSE` (a server is in `ASSIGN_META` scpState), causing procedures to suspend or block | `NoPEWorkerDeadlock` |
| **SCP ordering** | SCP steps fire out of the expected order relative to TRSP actions for the same region | `FencingOrder`, `NoLostRegions` |
| **Retry exhaustion** | `retries` reaches `MaxRetries` and the procedure gives up, leaving the region in `FAILED_OPEN` | `NoOrphanedProcedures`, `NoStuckRegions` |
| **Split/merge atomicity** | Daughter or merged regions materialize (appear in `metaTable`) before the point-of-no-return (PONR) | `SplitAtomicity`, `MergeAtomicity`, `KeyspaceCoverage` |
| **Stale dispatch** | A command in `dispatchedOps` persists across a crash/restart cycle and is consumed by the wrong server incarnation | `NoDoubleAssignment`, `RSMasterAgreement` |
