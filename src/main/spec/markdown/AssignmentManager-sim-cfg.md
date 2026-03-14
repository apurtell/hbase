# AssignmentManager-sim Configuration

**Source:** [`AssignmentManager-sim.cfg`](../AssignmentManager-sim.cfg)

## Overview

TLC simulation configuration for `AssignmentManager.tla`.

**Simulation model: 9 regions (3 deployed + 6 unused), 3 servers.** Models a
realistic small test cluster where 3 active regions can each be split once,
using 6 unused identifiers for daughters.

3 deployed regions tile `[0, 12)`: `r1=[0,4)`, `r2=[4,8)`, `r3=[8,12)`. Each
deployed region has keyspace width 4, so a split produces daughters with
width 2 (which cannot be re-split since `2/2=1 < 2`). This allows up to 3
concurrent or sequential splits, exercising the parent-child procedure
framework across multiple regions and servers. The spec currently models the
split forward path (`SplitPrepare` → `SplitResumeAfterClose` →
`SplitUpdateMeta` → `SplitDone`) and pre-PONR rollback (`SplitFail`),
covering 8 modules, 19 state variables, and 27 safety invariants.

## Verification Strategy

Run at every iteration alongside the primary exhaustive config. Together
they form the two-tier safety verification strategy:

1. **Primary** (`AssignmentManager.cfg`): fast exhaustive 3r/2s
2. **Simulation** (this file): deep random traces at 9r/3s

A third config (`AssignmentManager-liveness.cfg`) checks temporal liveness
properties without `SYMMETRY`; it is not run routinely.

## How TLC Simulation Works

TLC simulation mode generates random behaviors by performing long random walks
through the state graph rather than the breadth-first exhaustive enumeration
used in model-checking mode. At each step TLC randomly selects one enabled
action from the `Next` disjunction, producing trace depths far exceeding the
exhaustive diameter (often thousands of steps vs. the ~83-step exhaustive
depth). This trades completeness for depth. Simulation never visits every
reachable state, but it efficiently reaches deeply-nested corners of the state
space — such as multi-crash cascades, extended retry loops, and interleaved
split/assign/move sequences — that are unreachable by exhaustive search at
this model size. Invariants are checked at every state along each trace. A
violation produces a full counterexample trace just as in exhaustive mode.

## Configuration Parameters

This config uses `MaxRetries = 2` (vs 1 in the exhaustive configs), so
simulation is the only tier that verifies deeper retry behavior (the give-up
path at retry count 2). `UseReopen = TRUE` enables the branch-2 REOPEN
procedure (disabled in the primary exhaustive config for state-space reasons).
`MaxWorkers = 3` models a realistic PEWorker pool for 3 servers, providing
coverage of thread-pool exhaustion and meta-blocking scenarios at realistic
concurrency.

## Simulation Durations

Three-tier simulation durations:

| Tier | Duration | Use Case |
|------|----------|----------|
| Per-iteration (routine) | 900s (15 min) | Feedback each iteration |
| Post-iteration (validation) | 3600s (1 hr) | After completing an iteration |
| Post-phase (milestone) | 14400s (4 hr) | After completing a phase |

## Running

```sh
/usr/bin/java -XX:+UseParallelGC \
  -Dtlc2.TLC.stopAfter=3600 \
  -cp "$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/tla2tools.jar:$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg -simulate -workers auto
```

For post-phase (4 hours): `-Dtlc2.TLC.stopAfter=14400`

---

## Specification

```tla
SPECIFICATION Spec
```

## Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `NoServer` | model value | Sentinel: no server assigned |
| `NoProcedure` | model value | Sentinel: no procedure attached |
| `NoTransition` | model value | Sentinel: no transition code recorded |
| `NoRange` | model value | Sentinel: unused region identifier (no keyspace) |
| `NoRegion` | model value | Sentinel: no region reference |
| `NoTable` | model value | Sentinel: no table assigned |
| `Servers` | `{s1, s2, s3}` | Finite set of RegionServer identifiers |
| `Regions` | `{r1, r2, r3, r4, r5, r6, r7, r8, r9}` | All region identifiers (3 deployed + 6 spare for split/merge) |
| `DeployedRegions` | `{r1, r2, r3}` | Regions that exist at system start; tile `[0, 12)` with width 4 |
| `Tables` | `{T1}` | Finite set of table identifiers |
| `MaxKey` | `12` | Upper bound of keyspace: keys range over `0..(MaxKey-1)` |
| `MaxRetries` | `2` | Maximum open-retry count (deeper than exhaustive's 1) |
| `MaxWorkers` | `3` | ProcedureExecutor worker-thread pool size (realistic for 3 servers) |
| `UseReopen` | `TRUE` | REOPEN procedure (branch-2) enabled in simulation |
| `UseMerge` | `TRUE` | Merge procedure enabled; exercises interleaved split/merge |
| `UseRSOpenDuplicateQuirk` | `FALSE` | RS duplicate-open silent-drop disabled (deadlock avoidance) |
| `UseRSCloseNotFoundQuirk` | `FALSE` | RS close-not-found silent-drop disabled (deadlock avoidance) |
| `UseRestoreSucceedQuirk` | `FALSE` | Correct recovery; `TRUE` reproduces `restoreSucceedState()` bug |
| `UseBlockOnMetaWrite` | `FALSE` | Async suspension releases PEWorker (master/branch-3+ behavior) |
| `UseUnknownServerQuirk` | `FALSE` | Master creates TRSP(ASSIGN) for Unknown Server orphans; `TRUE` models silent close gap |
| `UseMasterAbortOnMetaWriteQuirk` | `FALSE` | No master abort on meta write failure; `TRUE` models `master.abort()` on `IOException` |
| `UseStaleStateQuirk` | `FALSE` | No stale server state on recovery; `TRUE` models `AM.start()` stale `ServerStateNode` creation |

## Invariants

All 28 safety invariants are checked:

```tla
INVARIANT
    TypeOK
    OpenImpliesLocation
    OfflineImpliesNoLocation
    NoDoubleAssignment
    MetaConsistency
    LockExclusivity
    RSMasterAgreement
    RSMasterAgreementConverse
    FencingOrder
    MetaAvailableForRecovery
    NoLostRegions
    ProcStoreConsistency
    ProcStoreBijection
    ProcStepConsistency
    TargetServerConsistency
    OpeningImpliesLocation
    ClosingImpliesLocation
    ServerRegionsTrackLocation
    DispatchCorrespondance
    NoOrphanedProcedures
    NoPEWorkerDeadlock
    KeyspaceCoverage
    SplitMergeMutualExclusion
    SplitAtomicity
    NoOrphanedDaughters
    SplitCompleteness
    AtMostOneCarryingMeta
    NoOrphanedMergedRegion
    MergeCompleteness
    MergeAtomicity
    TableLockExclusivity
```

## Action Constraints

Every state change follows `ValidTransition` and SCP progress is monotonic:

```tla
ACTION_CONSTRAINT
    TransitionValid
    SCPMonotonicity
```

## State Constraints

Bound concurrent split/merge procedures for TLC tractability:

```tla
CONSTRAINT
    SplitMergeConstraint
```

## Liveness

Liveness properties require symmetry to be disabled. Use
`AssignmentManager-liveness.cfg` for liveness checking.
