# AssignmentManager.cfg — Primary (2r/2s) Exhaustive Config

**Source:** [`AssignmentManager.cfg`](../AssignmentManager.cfg)

Primary (fast) TLC model configuration: **2 regions, 2 servers**. The state space is naturally finite with region-keyed procedures — no `StateConstraint` is needed.

---

## Specification

```cfg
SPECIFICATION Spec
```

---

## Constants

| Constant | Value | Notes |
|----------|-------|-------|
| `Regions` | `{r1, r2}` | 2 model-value regions |
| `Servers` | `{s1, s2}` | 2 model-value servers |
| `NoServer` | `NoServer` | Sentinel: no server assigned |
| `NoProcedure` | `NoProcedure` | Sentinel: no persisted procedure |
| `NoTransition` | `NoTransition` | Sentinel: no transition code |
| `MaxRetries` | `1` | Max FAILED_OPEN retries before give-up |
| `UseReopen` | `FALSE` | Models branch-2's REOPEN procedure (disabled in primary config for state-space reduction) |
| `UseRSOpenDuplicateQuirk` | `FALSE` | Disable RS duplicate-open silent-drop (avoids deadlock) |
| `UseRestoreSucceedQuirk` | `FALSE` | Correct recovery behavior (disable bug reproduction) |
| `MaxWorkers` | `2` | PEWorker thread pool size |
| `UseBlockOnMetaWrite` | `FALSE` | Async meta writes (master/branch-3+ behavior) |

```cfg
CONSTANTS
    Regions = {r1, r2}
    Servers = {s1, s2}
    NoServer = NoServer
    NoProcedure = NoProcedure
    NoTransition = NoTransition
    MaxRetries = 1
    UseReopen = FALSE
    UseRSOpenDuplicateQuirk = FALSE
    UseRestoreSucceedQuirk = FALSE
    MaxWorkers = 2
    UseBlockOnMetaWrite = FALSE
```

---

## Symmetry Reduction

Regions and servers are interchangeable — TLC explores one representative per equivalence class.

```cfg
SYMMETRY Symmetry
```

---

## Invariants

All 21 safety invariants are checked:

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

## Liveness

Liveness properties (`PROPERTY MetaEventuallyAssigned`) require symmetry to be disabled. Use [`AssignmentManager-liveness.cfg`](AssignmentManager-liveness.cfg) for overnight liveness checking.

---

## Running

```bash
# Preferred: via MCP tool tlaplus_mcp_tlc_check
# Fallback:
/usr/bin/java -XX:+UseParallelGC \
  -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager.cfg -workers auto -cleanup
```
