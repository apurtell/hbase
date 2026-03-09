# AssignmentManager-liveness.cfg — Liveness Config

**Source:** [`AssignmentManager-liveness.cfg`](../AssignmentManager-liveness.cfg)

---

TLC model configuration for liveness checking.

Liveness properties (temporal PROPERTY clauses) are INCOMPATIBLE with
TLC's SYMMETRY reduction.  Symmetry can create false lasso cycles that
don't exist in the real state graph.  This config OMITS SYMMETRY so
that liveness checking is sound.

Trade-off: the state space is much larger without symmetry.
This config is intended for overnight / batch runs.

Run:
/usr/bin/java -XX:+UseParallelGC \
-cp "$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/tla2tools.jar:$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/CommunityModules-deps.jar" \
tlc2.TLC AssignmentManager.tla -config AssignmentManager-liveness.cfg -workers auto -cleanup

```cfg
SPECIFICATION Spec
```

Model values

```cfg
CONSTANTS
    NoServer = NoServer
    NoProcedure = NoProcedure
    NoTransition = NoTransition
    NoRange = NoRange
    Servers = {s1, s2}
    Regions = {r1, r2, r3}
    DeployedRegions = {r1, r2}
    MaxKey = 8
    MaxRetries = 1
    MaxWorkers = 2
```

UseReopen = TRUE models branch-2's additional REOPEN procedure

```cfg
    UseReopen = TRUE
```

UseRSOpenDuplicateQuirk = FALSE to disable the RS duplicate-open
silent-drop behavior to avoid deadlock.  Set TRUE to model the
implementation quirk (AssignRegionHandler.process()).

```cfg
    UseRSOpenDuplicateQuirk = FALSE
```

UseRestoreSucceedQuirk = FALSE for correct recovery behavior.
Set TRUE to reproduce the OpenRegionProcedure.restoreSucceedState()
bug where FAILED_OPEN reports are replayed as OPENED.

```cfg
    UseRestoreSucceedQuirk = FALSE
```

UseBlockOnMetaWrite = FALSE models master/branch-3+ behavior where
procedures suspend and release the PEWorker on async meta writes.

```cfg
    UseBlockOnMetaWrite = FALSE
```

NO SYMMETRY -- required for sound liveness checking.

Safety invariants (checked alongside liveness)

```cfg
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
```

Action properties

```cfg
ACTION_CONSTRAINT
    TransitionValid
    SCPMonotonicity
```

State constraint: bound concurrent split/merge procedures

```cfg
CONSTRAINT
    SplitMergeConstraint
```

Liveness properties (the reason this config exists)

```cfg
PROPERTY
    MetaEventuallyAssigned
```
