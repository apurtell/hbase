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
  -cp "$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.122210-universal/tools/tla2tools.jar:$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.122210-universal/tools/CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager.cfg -workers auto -cleanup
```

---

## Specification

```tla
SPECIFICATION Spec
```

## Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `NoProcedure` | model value | Sentinel: no procedure attached |
| `NoTransition` | model value | Sentinel: no transition code recorded |
| `NoRange` | model value | Sentinel: unused region identifier (no keyspace) |
| `NoServer` | model value | Sentinel: no server assigned |
| `NoRegion` | model value | Sentinel: no region reference |
| `NoTable` | model value | Sentinel: no table assigned |
| `Servers` | `{s1, s2}` | Finite set of RegionServer identifiers |
| `Regions` | `{r1, r2, r3}` | All region identifiers (1 deployed + 2 spare for split or create) |
| `DeployedRegions` | `{r1}` | Regions that exist at system start |
| `Tables` | `{T1}` | Finite set of table identifiers; T2 exercised in simulation |
| `MaxKey` | `2` | Upper bound of keyspace: keys range over `0..(MaxKey-1)` |
| `MaxRetries` | `1` | Maximum open-retry count per procedure |
| `MaxWorkers` | `2` | ProcedureExecutor worker-thread pool size |
| `UseReopen` | `FALSE` | REOPEN procedure (branch-2) disabled for state-space reasons |
| `UseMerge` | `FALSE` | Merge procedure disabled in exhaustive mode |
| `UseCreate` | `FALSE` | CreateTable procedure disabled in exhaustive mode |
| `UseDelete` | `FALSE` | DeleteTable procedure disabled in exhaustive mode |
| `UseTruncate` | `FALSE` | TruncateTable procedure disabled in exhaustive mode |
| `UseRSOpenDuplicateQuirk` | `FALSE` | RS duplicate-open silent-drop disabled (deadlock avoidance) |
| `UseRSCloseNotFoundQuirk` | `FALSE` | RS close-not-found silent-drop disabled (deadlock avoidance) |
| `UseRestoreSucceedQuirk` | `FALSE` | Correct recovery; `TRUE` reproduces `restoreSucceedState()` bug |
| `UseBlockOnMetaWrite` | `FALSE` | Async suspension releases PEWorker (master/branch-3+ behavior) |
| `UseUnknownServerQuirk` | `FALSE` | Master creates TRSP(ASSIGN) for Unknown Server orphans; `TRUE` models silent close gap |
| `UseMasterAbortOnMetaWriteQuirk` | `FALSE` | No master abort on meta write failure; `TRUE` models `master.abort()` on `IOException` |
| `UseStaleStateQuirk` | `FALSE` | No stale server state on recovery; `TRUE` models `AM.start()` stale `ServerStateNode` creation |
| `UseDisable` | `FALSE` | Disable/Enable procedures disabled in exhaustive mode |
| `UseModify` | `FALSE` | ModifyTable procedure disabled in exhaustive mode |

## Symmetry Reduction

Unused region identifiers and servers are interchangeable.
`DeployedRegions` have distinct keyspaces and cannot be permuted.

```tla
SYMMETRY Symmetry
```

## Invariants

All 36 safety invariants are checked:

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
    AtMostOneCarryingMeta
    NoOrphanedMergedRegion
    MergeAtomicity
    TableLockExclusivity
    DeleteTableAtomicity
    TruncateAtomicity
    TruncateNoOrphans
    CreateNoOrphans
    TableEnabledStateConsistency
    ModifyTableSafety
    FencedServerNoOpen
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
