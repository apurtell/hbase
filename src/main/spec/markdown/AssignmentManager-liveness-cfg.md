# AssignmentManager-liveness.cfg — Liveness Checking Config

**Source:** [`AssignmentManager-liveness.cfg`](../AssignmentManager-liveness.cfg)

Liveness-only TLC model configuration: **2 regions, 2 servers**, no symmetry reduction. Liveness properties (temporal `PROPERTY` clauses) are **incompatible** with TLC's `SYMMETRY` reduction — symmetry can create false lasso cycles that don't exist in the real state graph. This config omits `SYMMETRY` so that liveness checking is sound.

**Trade-off:** the state space is ~4× larger without symmetry (for 2r/2s). This config is intended for overnight / batch runs.

---

## Specification

```cfg
SPECIFICATION Spec
```

---

## Constants

Same as primary config (2r/2s):

| Constant | Value | Notes |
|----------|-------|-------|
| `Regions` | `{r1, r2}` | 2 model-value regions |
| `Servers` | `{s1, s2}` | 2 model-value servers |
| `NoServer` | `NoServer` | Sentinel |
| `NoProcedure` | `NoProcedure` | Sentinel |
| `NoTransition` | `NoTransition` | Sentinel |
| `MaxRetries` | `1` | Max FAILED_OPEN retries |
| `UseReopen` | `TRUE` | branch-2 REOPEN |
| `UseRSOpenDuplicateQuirk` | `FALSE` | Disable silent-drop |
| `UseRestoreSucceedQuirk` | `FALSE` | Correct recovery |
| `MaxWorkers` | `2` | PEWorker thread pool size |
| `UseBlockOnMetaWrite` | `FALSE` | Async meta writes |

```cfg
CONSTANTS
    Regions = {r1, r2}
    Servers = {s1, s2}
    NoServer = NoServer
    NoProcedure = NoProcedure
    NoTransition = NoTransition
    MaxRetries = 1
    UseReopen = TRUE
    UseRSOpenDuplicateQuirk = FALSE
    UseRestoreSucceedQuirk = FALSE
    MaxWorkers = 2
    UseBlockOnMetaWrite = FALSE
```

---

## No Symmetry

**NO `SYMMETRY` declaration** — required for sound liveness checking.

---

## Invariants

All 21 safety invariants (checked alongside liveness):

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
```

---

## Action Constraints

```cfg
ACTION_CONSTRAINT
    TransitionValid
    SCPMonotonicity
```

---

## Liveness Properties

The reason this config exists:

```cfg
PROPERTY
    MetaEventuallyAssigned
```

---

## Running

```bash
# Intended for overnight / batch runs (no symmetry, ~4x larger state space):
/usr/bin/java -XX:+UseParallelGC \
  -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-liveness.cfg -workers auto -cleanup
```
