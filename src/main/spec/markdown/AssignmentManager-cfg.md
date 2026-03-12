# AssignmentManager Configuration

**Source:** [`AssignmentManager.cfg`](../AssignmentManager.cfg)

## Overview

TLC model configuration for `AssignmentManager.tla`.

**Primary (fast) model: 3 regions (1 deployed + 2 unused), 2 servers.**
`DeployedRegions` tile `[0, MaxKey)` at `Init`. `Regions \ DeployedRegions` are
unused identifiers for future splits/merges.

## Running

```sh
/usr/bin/java -XX:+UseParallelGC \
  -cp "$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/tla2tools.jar:$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager.cfg -workers auto -cleanup
```

---

## Specification

```tla
SPECIFICATION Spec
```

## Constants

Model values and universe sizing:

```tla
CONSTANTS
    NoProcedure = NoProcedure
    NoTransition = NoTransition
    NoRange = NoRange
    NoServer = NoServer
    NoRegion = NoRegion
    Servers = {s1, s2}
    Regions = {r1, r2, r3}
    DeployedRegions = {r1}
    MaxKey = 2
    MaxRetries = 1
    MaxWorkers = 2
```

**`UseReopen = FALSE`** — the REOPEN procedure (branch-2) is disabled in the
primary exhaustive config for state-space reasons.

**`UseRSOpenDuplicateQuirk = FALSE`** disables the RS duplicate-open
silent-drop behavior to avoid deadlock. Set `TRUE` to model the implementation
quirk (`AssignRegionHandler.process()`).

**`UseRSCloseNotFoundQuirk = FALSE`** disables the RS close-not-found
silent-drop behavior to avoid deadlock. Set `TRUE` to model the implementation
quirk (`UnassignRegionHandler.process()`).

**`UseRestoreSucceedQuirk = FALSE`** for correct recovery behavior. Set `TRUE`
to reproduce the `OpenRegionProcedure.restoreSucceedState()` bug where
`FAILED_OPEN` reports are replayed as `OPENED`.

**`UseBlockOnMetaWrite = FALSE`** models master/branch-3+ behavior where
procedures suspend and release the PEWorker on async meta writes.

```tla
    UseReopen = FALSE
    UseRSOpenDuplicateQuirk = FALSE
    UseRSCloseNotFoundQuirk = FALSE
    UseRestoreSucceedQuirk = FALSE
    UseBlockOnMetaWrite = FALSE
    UseMerge = FALSE
```

## Symmetry Reduction

Unused region identifiers and servers are interchangeable.
`DeployedRegions` have distinct keyspaces and cannot be permuted.

```tla
SYMMETRY Symmetry
```

## Invariants

All 30 safety invariants are checked:

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
