# AssignmentManager-sim Configuration

**Source:** [`AssignmentManager-sim.cfg`](../AssignmentManager-sim.cfg)

```tla
\* TLC simulation configuration for AssignmentManager.tla
\*
\* Simulation model: 9 regions (3 deployed + 6 unused), 3 servers.
\* Models a realistic small test cluster where 3 active regions can
\* each be split once, using 6 unused identifiers for daughters.
\*
\* 3 deployed regions tile [0, 12): r1=[0,4), r2=[4,8), r3=[8,12).
\* Each deployed region has keyspace width 4, so a split produces
\* daughters with width 2 (which cannot be re-split since 2/2=1 < 2).
\* This allows up to 3 concurrent or sequential splits, exercising
\* the parent-child framework across multiple regions and servers.
\*
\* Run at EVERY iteration alongside the primary exhaustive config.
\* Together they form the two-tier verification strategy:
\*   1. Primary (AssignmentManager.cfg): fast exhaustive 3r/2s
\*   2. Simulation (this file): deep random traces at 9r/3s
\*
\* Simulation mode randomly samples long behaviors, providing
\* probabilistic coverage of the full state space including
\* cascading crash scenarios, master crash/recovery cycles,
\* multi-cycle assign/unassign/move sequences, and concurrent
\* split operations across different regions.
\*
\* This config uses MaxRetries = 2 (vs 1 in the exhaustive configs),
\* so simulation is the only verification of the deeper retry behavior.
\* MaxWorkers = 3 models a realistic PEWorker pool for 3 servers.
\*
\* Three-tier simulation durations:
\*   Per-iteration (routine):      300s  (5 min)  -- quick feedback each iteration
\*   Post-iteration (validation):  900s  (15 min) -- after completing an iteration
\*   Post-phase (milestone):       3600s (1 hr)   -- after completing a phase
\*
\* Run:
\*   /usr/bin/java -XX:+UseParallelGC \
\*     -Dtlc2.TLC.stopAfter=900 \
\*     -cp "$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/tla2tools.jar:$HOME/.antigravity/extensions/tlaplus.vscode-ide-2026.3.52117-universal/tools/CommunityModules-deps.jar" \
\*     tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg -simulate -workers auto
\*
\*   For post-phase (1 hour):  -Dtlc2.TLC.stopAfter=3600

SPECIFICATION Spec

\* Model values
CONSTANTS
    NoServer = NoServer
    NoProcedure = NoProcedure
    NoTransition = NoTransition
    NoRange = NoRange
    Servers = {s1, s2, s3}
    Regions = {r1, r2, r3, r4, r5, r6, r7, r8, r9}
    DeployedRegions = {r1, r2, r3}
    MaxKey = 12
    MaxRetries = 2
    MaxWorkers = 3
    \* UseReopen = TRUE models branch-2's additional REOPEN procedure
    UseReopen = TRUE
    \* UseRSOpenDuplicateQuirk = FALSE to disable the RS duplicate-open
    \* silent-drop behavior to avoid deadlock.  Set TRUE to model the
    \* implementation quirk (AssignRegionHandler.process()).
    UseRSOpenDuplicateQuirk = FALSE
    \* UseRestoreSucceedQuirk = FALSE for correct recovery behavior.
    \* Set TRUE to reproduce the OpenRegionProcedure.restoreSucceedState()
    \* bug where FAILED_OPEN reports are replayed as OPENED.
    UseRestoreSucceedQuirk = FALSE
    \* UseBlockOnMetaWrite = FALSE models master/branch-3+ behavior where
    \* procedures suspend and release the PEWorker on async meta writes.
    UseBlockOnMetaWrite = FALSE

\* Invariants to check
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

\* Action property: every state change follows ValidTransition
\* and SCP progress is monotonic
ACTION_CONSTRAINT
    TransitionValid
    SCPMonotonicity

\* State constraint: bound concurrent split/merge procedures
CONSTRAINT
    SplitMergeConstraint

\* Liveness properties require symmetry to be disabled.
\* Use AssignmentManager-liveness.cfg for liveness checking.
```
