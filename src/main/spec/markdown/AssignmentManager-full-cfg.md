# AssignmentManager-full.cfg — Extended (3r/3s) Exhaustive Config

**Source:** [`AssignmentManager-full.cfg`](../AssignmentManager-full.cfg)

Extended (full) TLC model configuration: **3 regions, 3 servers**, exhaustive state-space exploration. NOT run at every iteration — reserved for ad hoc on-demand checks at user-requested checkpoints.

`ServerRestart + WF` ensures crashed servers eventually come back online, so no `CrashConstraint` is needed. Multi-crash and all-crashed scenarios are explored exhaustively.

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
| `MaxRetries` | `1` | Same as primary config |
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
    MaxRetries = 1
    UseReopen = TRUE
    UseRSOpenDuplicateQuirk = FALSE
    UseRestoreSucceedQuirk = FALSE
```

---

## Symmetry Reduction

```cfg
SYMMETRY Symmetry
```

---

## Invariants

All 20 safety invariants:

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

```bash
/usr/bin/java -XX:+UseParallelGC \
  -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-full.cfg -workers auto -cleanup
```
