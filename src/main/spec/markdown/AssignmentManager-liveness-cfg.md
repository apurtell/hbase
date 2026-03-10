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

Model values and universe sizing (same as primary exhaustive config):

```tla
CONSTANTS
    NoProcedure = NoProcedure
    NoTransition = NoTransition
    NoRange = NoRange
    NoServer = NoServer
    Servers = {s1, s2}
    Regions = {r1, r2, r3}
    DeployedRegions = {r1}
    MaxKey = 2
    MaxRetries = 1
    MaxWorkers = 2
```

**`UseReopen = FALSE`** — the REOPEN procedure (branch-2) is disabled.

**`UseRSOpenDuplicateQuirk = FALSE`** disables the RS duplicate-open
silent-drop behavior to avoid deadlock. Set `TRUE` to model the implementation
quirk (`AssignRegionHandler.process()`).

**`UseRestoreSucceedQuirk = FALSE`** for correct recovery behavior. Set `TRUE`
to reproduce the `OpenRegionProcedure.restoreSucceedState()` bug where
`FAILED_OPEN` reports are replayed as `OPENED`.

**`UseBlockOnMetaWrite = FALSE`** models master/branch-3+ behavior where
procedures suspend and release the PEWorker on async meta writes.

```tla
    UseReopen = FALSE
    UseRSOpenDuplicateQuirk = FALSE
    UseRestoreSucceedQuirk = FALSE
    UseBlockOnMetaWrite = FALSE
```

## Symmetry

**No `SYMMETRY`** — required for sound liveness checking.

## Safety Invariants

All 27 safety invariants are checked alongside liveness:

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

```tla
PROPERTY
    MetaEventuallyAssigned
```
