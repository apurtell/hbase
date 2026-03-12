# AssignmentManager-liveness Configuration

**Source:** [`AssignmentManager-liveness.cfg`](../AssignmentManager-liveness.cfg)

## Overview

TLC model configuration for liveness checking.

Liveness properties (temporal `PROPERTY` clauses) are incompatible with
TLC's `SYMMETRY` reduction. Symmetry can create false lasso cycles that don't
exist in the real state graph. This config omits `SYMMETRY` so that
liveness checking is sound.

**Trade-off:** the state space is much larger without symmetry. This config is
intended for overnight / batch runs.

## Running

```sh
/usr/bin/java -XX:+UseParallelGC \
  -cp "$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/tla2tools.jar:$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-liveness.cfg -workers auto -cleanup
```

---

## Specification

```tla
SPECIFICATION Spec
```

## Constants

Same model values and universe sizing as the primary exhaustive config:

| Constant | Value | Description |
|----------|-------|-------------|
| `NoProcedure` | model value | Sentinel: no procedure attached |
| `NoTransition` | model value | Sentinel: no transition code recorded |
| `NoRange` | model value | Sentinel: unused region identifier (no keyspace) |
| `NoRegion` | model value | Sentinel: no region reference |
| `NoServer` | model value | Sentinel: no server assigned |
| `Servers` | `{s1, s2}` | Finite set of RegionServer identifiers |
| `Regions` | `{r1, r2, r3}` | All region identifiers (deployed + spare for split/merge) |
| `DeployedRegions` | `{r1}` | Regions that exist at system start |
| `MaxKey` | `2` | Upper bound of keyspace: keys range over `0..(MaxKey-1)` |
| `MaxRetries` | `1` | Maximum open-retry count per procedure |
| `MaxWorkers` | `2` | ProcedureExecutor worker-thread pool size |
| `UseReopen` | `FALSE` | REOPEN procedure (branch-2) disabled |
| `UseMerge` | `FALSE` | Merge procedure disabled in liveness mode |
| `UseRSOpenDuplicateQuirk` | `FALSE` | RS duplicate-open silent-drop disabled (deadlock avoidance) |
| `UseRSCloseNotFoundQuirk` | `FALSE` | RS close-not-found silent-drop disabled (deadlock avoidance) |
| `UseRestoreSucceedQuirk` | `FALSE` | Correct recovery; `TRUE` reproduces `restoreSucceedState()` bug |
| `UseBlockOnMetaWrite` | `FALSE` | Async suspension releases PEWorker (master/branch-3+ behavior) |

## Symmetry

**No `SYMMETRY`** — required for sound liveness checking.

## Safety Invariants

All 30 safety invariants are checked alongside liveness:

```tla
INVARIANT
    TypeOK
    OpenImpliesLocation
    OfflineImpliesNoLocation
    MetaConsistency
    LockExclusivity
    RSMasterAgreement
    RSMasterAgreementConverse
    NoDoubleAssignment
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
```

## Action Constraints

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

## Liveness Properties

The reason this config exists — temporal properties that require `SYMMETRY` to
be disabled:

- **`MetaEventuallyAssigned`**: Meta eventually reassigned after crash.
- **`OfflineEventuallyOpen`**: ASSIGN-bearing OFFLINE region eventually opens.
- **`SCPEventuallyDone`**: Started SCP eventually completes.

```tla
\* Liveness properties (the reason this config exists)
PROPERTY
    MetaEventuallyAssigned
    OfflineEventuallyOpen
    SCPEventuallyDone
```
