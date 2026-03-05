# AssignmentManager-sim.cfg — Simulation (3r/3s) Config

**Source:** [`AssignmentManager-sim.cfg`](../AssignmentManager-sim.cfg)

Simulation model configuration: **3 regions, 3 servers**, no crash limit. Run alongside the primary exhaustive config to form the two-tier verification strategy:

1. **Primary** (`AssignmentManager.cfg`): fast exhaustive 2r/2s
2. **Simulation** (this file): deep random traces at 3r/3s

Simulation mode randomly samples long behaviors, providing probabilistic coverage of the full 3r/3s state space including cascading crash scenarios, master crash/recovery cycles, and multi-cycle assign/unassign/move sequences.

> [!NOTE]
> This config uses `MaxRetries = 2` (vs 1 in the exhaustive configs), so simulation is the **only** verification of the deeper retry behavior.

---

## Specification

```cfg
SPECIFICATION Spec
```

---

## Constants

| Constant | Value | Notes |
|----------|-------|-------|
| `Regions` | `{r1, r2, r3}` | 3 model-value regions |
| `Servers` | `{s1, s2, s3}` | 3 model-value servers |
| `NoServer` | `NoServer` | Sentinel |
| `NoProcedure` | `NoProcedure` | Sentinel |
| `NoTransition` | `NoTransition` | Sentinel |
| `MaxRetries` | `2` | Higher than exhaustive configs for deeper retry coverage |
| `UseReopen` | `TRUE` | Models branch-2's REOPEN procedure |
| `UseRSOpenDuplicateQuirk` | `FALSE` | Disable silent-drop |
| `UseRestoreSucceedQuirk` | `FALSE` | Correct recovery |

```cfg
CONSTANTS
    Regions = {r1, r2, r3}
    Servers = {s1, s2, s3}
    NoServer = NoServer
    NoProcedure = NoProcedure
    NoTransition = NoTransition
    MaxRetries = 2
    UseReopen = TRUE
    UseRSOpenDuplicateQuirk = FALSE
    UseRestoreSucceedQuirk = FALSE
```

---

## Invariants

All 20 safety invariants (same as primary config):

```cfg
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
```

---

## Action Constraints

```cfg
ACTION_CONSTRAINT
    TransitionValid
    SCPMonotonicity
```

---

## Running

Three-tier simulation durations:

| Tier | Duration | Use Case |
|------|----------|----------|
| Per-iteration (routine) | 300s (5 min) | Quick feedback each iteration |
| Post-iteration (validation) | 900s (15 min) | After completing an iteration |
| Post-phase (milestone) | 3600s (1 hr) | After completing a phase |

```bash
# Via command line (adjust -Dtlc2.TLC.stopAfter for duration in seconds):
/usr/bin/java -XX:+UseParallelGC \
  -Dtlc2.TLC.stopAfter=900 \
  -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg -simulate \
  -workers auto -cleanup
```
