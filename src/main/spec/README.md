# HBase AssignmentManager TLA+ Specification

## What is TLA+?

TLA+ is a formal specification language created by Leslie Lamport for designing
and verifying concurrent and distributed systems. The name stands for the
**Temporal Logic of Actions**, a mathematical framework that combines
first-order logic with temporal operators to reason about how system state
evolves over time. TLA+ does not produce executable code. Instead it produces a
precise, machine-checkable mathematical model of a system's behavior that can be
exhaustively verified against safety and liveness properties. When the model is
a high-fidelity representation of the real system, proposed design and
architectural changes can be checked against the full space of possible
executions, surfacing critical logic bugs at design time, before any code is
written. This can save weeks or months of development effort that would otherwise
be spent discovering and debugging subtle concurrency issues in a running system.

A TLA+ specification describes a system as a state machine, an initial state
predicate (`Init`), a next-state relation (`Next`) that defines every legal
transition, and a collection of invariants, properties that must hold in
every reachable state.  The TLC model checker then systematically explores every
possible execution of this state machine, checking each property at every state.
If a property is violated, TLC produces a minimal counterexample trace showing
the exact sequence of steps that led to the failure.

### Temporal Logic Foundations

Classical logic can express facts about a single moment in time ("region r is
OPEN"), but it cannot express how ground truth changes across a sequence of states.
Temporal logic extends classical logic with operators that talk about
behaviors, infinite sequences of states:

| Operator | Meaning |
|----------|---------|
| `[]P` ("always P") | Property `P` holds in every state of every behavior |
| `<>P` ("eventually P") | Property `P` holds in some future state |
| `[]<>P` ("infinitely often") | `P` holds in infinitely many states |
| `<>[]P` ("eventually always") | `P` holds from some point onward forever |
| `P ~> Q` ("P leads to Q") | Whenever `P` becomes true, `Q` eventually becomes true |

Safety properties (expressed with `[]`) assert that bad things never
happen, such as region is never writable on two servers simultaneously, a
ServerCrashProcedure never reassigns regions before WAL leases are revoked, and
procedure metadata stays consistent with in-memory state. Those assertions can
be checked and formally validated.

Liveness properties (expressed with `<>` and `~>`) assert that good
things eventually happen, such as every assigned region eventually reaches OPEN,
every crash is eventually detected, and every procedure eventually completes.
Liveness requires fairness conditions, the assumption that the system does not
indefinitely starve enabled actions.

In TLA+, a single step of the system is an action, a predicate over the current
state and the next state (written with primed variables, e.g.,
`regionState'`). The `Next` relation is the disjunction of all actions,
meaning that at each step, any enabled action may fire. This naturally models
the non-determinism inherent in distributed systems: message arrival order,
scheduling decisions, and failure timing are all left unspecified, so the model
checker explores every possible interleaving.

### Modeling Distributed Systems with TLA+

Distributed systems are difficult to reason about because the number of
possible interleavings grows combinatorially with the number of concurrent
actors. Testing and code review can cover common cases but are fundamentally
incomplete. Subtle bugs often hide in rare event orderings that occur only
under specific failure timing. TLA+ addresses this by exploring the entire
state space (or a probabilistically thorough sample via simulation).

Key modeling idioms used in distributed systems specifications:

- **Message channels** are modeled as sets of records. Sending appends to the
  set; receiving removes from it. Non-deterministic delivery order emerges
  naturally from set semantics.
- **Process crashes** are modeled as actions that reset volatile state while
  preserving durable state, allowing the model checker to explore crash timing
  at every interleaving point.
- **Non-determinism** is modeled through existential quantification (`\E`):
  e.g., "there exists some server s that the master could choose as the
  assignment target." TLC explores every possible choice.
- **Symmetry reduction** exploits the interchangeability of identifiers (e.g.,
  servers and regions) to reduce the state space by up to `|Regions|! ×
  |Servers|!` without sacrificing coverage.

### How This Spec Models the HBase AssignmentManager

The HBase AssignmentManager is a core component of the HBase master that manages
the lifecycle of regions across a cluster of RegionServers. It coordinates
region assignment, unassignment, moves, and reopens; handles RegionServer
crashes through the ServerCrashProcedure (SCP); and recovers its own state
after a master crash through a durable procedure store. The correctness of
these interactions is critical. Bugs can cause data loss (double assignment /
split-brain writes), data unavailability (lost or stuck regions), or cluster
hangs (deadlocked procedures).

This TLA+ specification models the AssignmentManager as a state machine with
18 state variables capturing:

- **Region lifecycle** — in-memory master state (`regionState`) and persistent
  `hbase:meta` state (`metaTable`), tracking regions through OFFLINE → OPENING
  → OPEN → CLOSING → CLOSED (and the failure states FAILED_OPEN and
  ABNORMALLY_CLOSED).
- **Asynchronous RPC channels** — master-to-RS commands (`dispatchedOps`) and
  RS-to-master transition reports (`pendingReports`), modeled as sets of records
  with non-deterministic delivery.
- **Procedure state** — inlined into region state records (type, step, target
  server, retry count), with a durable procedure store (`procStore`) that
  survives master crashes.
- **Server liveness** — per-server online/crashed state, ZooKeeper ephemeral
  nodes, and WAL fencing state.
- **Crash recovery** — multi-step ServerCrashProcedure (detect → assign meta →
  snapshot regions → fence WALs → reassign) and master crash/recovery (volatile
  state lost, durable state replayed).
- **PEWorker thread pool** — available worker count (`availableWorkers`), async
  suspension (`suspendedOnMeta`), and sync blocking (`blockedOnMeta`) when
  `hbase:meta` is unavailable during SCP meta-reassignment.

The specification defines 21 safety invariants verified at every reachable
state, including the critical `NoDoubleAssignment` (no region writable on two
servers), `MetaConsistency` (persistent and in-memory state agree),
`FencingOrder` (WALs fenced before reassignment), `NoLostRegions` (no region
stuck without a procedure after crash recovery), and `NoPEWorkerDeadlock`
(thread pool exhaustion detection). One liveness property
(`MetaEventuallyAssigned`) verifies that `hbase:meta` is eventually
reassigned after a crash. Two action constraints enforce transition validity
and SCP monotonicity.

The model checker runs in three tiers: fast exhaustive verification at 2
regions / 2 servers, full exhaustive at 3r/3s, and deep random simulation at
3r/3s with extended retries. Configurable "quirk" flags allow toggling known
implementation bugs to correctly adhere to implementation semantics, reproduce
failures and validate fixes.

---

This is a formal TLA+ specification of the HBase AssignmentManager, covering the
region assignment lifecycle: state transitions, persistent metadata, procedure-
driven operations, RPC dispatch, RegionServer-side behavior, server crash recovery,
and master crash/recovery. The spec models the core assign/unassign/move/reopen
lifecycle for regions across the OFFLINE, OPENING, OPEN, CLOSING, CLOSED,
FAILED_OPEN, and ABNORMALLY_CLOSED states.

## Module Structure

| Module | Lines | Description |
|--------|------:|-------------|
| [AssignmentManager.tla](markdown/AssignmentManager.md) | 1125 | Root orchestrator — variables, Init, Next, Fairness, Spec, invariants, liveness |
| [Types.tla](markdown/Types.md) | 238 | Constants, type sets, state definitions, `ValidTransition` |
| [TRSP.tla](markdown/TRSP.md) | 1182 | TransitionRegionStateProcedure actions (assign, unassign, move, reopen, dispatch, confirm, failure, crash recovery, meta-blocking, ResumeFromMeta) |
| [SCP.tla](markdown/SCP.md) | 470 | ServerCrashProcedure state machine (detect crash → assign meta → get regions → fence WALs → assign regions → done, with meta-blocking) |
| [RegionServer.tla](markdown/RegionServer.md) | 370 | RS-side handlers (open, fail-open, close, abort, restart, duplicate-open, stale report drop) |
| [Master.tla](markdown/Master.md) | 338 | Master-side actions (GoOffline, MasterDetectCrash, MasterCrash, MasterRecover with PEWorker reset) |
| [ProcStore.tla](markdown/ProcStore.md) | 137 | Procedure store invariants, bijection, and `RestoreSucceedState` recovery operator |
| [ZK.tla](markdown/ZK.md) | 91 | Minimal ZooKeeper model — ephemeral node lifecycle (`ZKSessionExpire`) |

## State Variables (18 total)

- **`regionState`** — volatile in-memory master state per region (state, location, procedure fields)
- **`metaTable`** — persistent `hbase:meta` state per region (survives master crash)
- **`dispatchedOps`** — master→RS command channel per server
- **`pendingReports`** — RS→master report channel
- **`rsOnlineRegions`** — RS-side view of locally online regions
- **`serverState`** — per-server liveness as seen by master (ONLINE / CRASHED)
- **`scpState`** — ServerCrashProcedure progress per server
- **`scpRegions`** — SCP region snapshot per server
- **`walFenced`** — WAL lease revocation state per server
- **`locked`** — per-region write lock
- **`carryingMeta`** — whether a crashed server was hosting `hbase:meta`
- **`serverRegions`** — per-server region tracking (ServerStateNode)
- **`procStore`** — persisted procedure records (survives master crash)
- **`masterAlive`** — master JVM liveness
- **`zkNode`** — ZK ephemeral node liveness per server
- **`availableWorkers`** — number of idle PEWorker threads
- **`suspendedOnMeta`** — regions whose procedures are async-suspended on meta unavailability
- **`blockedOnMeta`** — regions whose procedures are sync-blocked on meta unavailability

## Configurable Behaviors

| Constant | Description |
|----------|-------------|
| `UseReopen` | `TRUE` enables the REOPEN procedure, needed to model branch-2 |
| `UseRSOpenDuplicateQuirk` | `TRUE` models `AssignRegionHandler.process()` silent-drop bug (causes deadlock) |
| `UseRestoreSucceedQuirk` | `TRUE` reproduces `OpenRegionProcedure.restoreSucceedState()` bug where FAILED_OPEN reports replay as OPENED (causes constraint violations) |
| `MaxRetries` | Maximum open-retry count per procedure |
| `MaxWorkers` | PEWorker thread pool size; all procedure-step actions require `availableWorkers > 0` |
| `UseBlockOnMetaWrite` | `FALSE` (default): async suspension releases PEWorker. `TRUE` (branch-2.6): sync blocking holds PEWorker |

## Verification Configurations

### 1. Primary Exhaustive — 2 Regions / 2 Servers ([AssignmentManager.cfg](markdown/AssignmentManager-cfg.md))

Fast exhaustive model check with symmetry reduction. Used as the routine
verification pass.

| Parameter | Value |
|-----------|-------|
| Regions | `{r1, r2}` |
| Servers | `{s1, s2}` |
| MaxRetries | 1 |
| MaxWorkers | 2 |
| UseReopen | TRUE |
| Symmetry | Yes |
| Mode | Exhaustive |

### 2. Simulation — 3 Regions / 3 Servers ([AssignmentManager-sim.cfg](markdown/AssignmentManager-sim-cfg.md))

Deep random-trace simulation at 3r/3s with `MaxRetries = 2`.  Probabilistic
coverage of the full state space including cascading crashes, master
crash/recovery, and multi-cycle assign/unassign/move sequences.  Simulation is
the only tier that verifies deeper retry behavior (`MaxRetries = 2`).

| Parameter | Value |
|-----------|-------|
| Regions | `{r1, r2, r3}` |
| Servers | `{s1, s2, s3}` |
| MaxRetries | 2 |
| UseReopen | TRUE |
| Symmetry | N/A (simulation mode) |
| Mode | Random Simulation |

**Recommended simulation durations:**

| Tier | Duration | Use Case |
|------|----------|----------|
| Per-iteration | 300s (5 min) | Quick feedback during development |
| Post-iteration | 900s (15 min) | Validation after completing an iteration |
| Post-phase | 3600s (1 hr) | Milestone verification |

## Invariants

All configurations check the same 21 safety invariants:

| Invariant | Description |
|-----------|-------------|
| `TypeOK` | Type correctness for all state variables |
| `OpenImpliesLocation` | OPEN regions always have a location |
| `OfflineImpliesNoLocation` | Offline-like regions have no location |
| `NoDoubleAssignment` | Region writable on at most one server (WAL-fencing–aware) |
| `MetaConsistency` | `hbase:meta` matches in-memory state (with allowed divergences) |
| `LockExclusivity` | Procedure type correlates with valid region states |
| `RSMasterAgreement` | Stably OPEN region is online on its RS |
| `RSMasterAgreementConverse` | RS-online region is acknowledged by master |
| `FencingOrder` | SCP does not reassign until WAL leases are revoked |
| `MetaAvailableForRecovery` | Meta-carrying SCP reassigns meta before proceeding |
| `NoLostRegions` | After SCP completes, no region is stuck without a procedure |
| `ProcStoreConsistency` | Intra-record correlation for persisted procedure records |
| `ProcStoreBijection` | In-memory procedures ↔ persisted records (master-alive–gated) |
| `ProcStepConsistency` | Procedure step correlates with region lifecycle state |
| `TargetServerConsistency` | Target server presence correlates with procedure step |
| `OpeningImpliesLocation` | OPENING regions always have a location |
| `ClosingImpliesLocation` | CLOSING regions always have a location |
| `ServerRegionsTrackLocation` | `serverRegions` tracks location for stable regions |
| `DispatchCorrespondance` | Dispatched commands have corresponding procedures |
| `NoOrphanedProcedures` | OFFLINE procedure-bearing regions are ASSIGN only |
| `NoPEWorkerDeadlock` | When all PEWorkers are consumed and meta is unavailable, at least one active procedure is not blocked/suspended |

## Liveness Properties

| Property | Description |
|----------|-------------|
| `MetaEventuallyAssigned` | When meta becomes unavailable (`ASSIGN_META`), the SCP eventually reassigns it |

> Liveness properties are incompatible with TLC's `SYMMETRY` reduction.
> Use [`AssignmentManager-liveness.cfg`](markdown/AssignmentManager-liveness-cfg.md)
> (no symmetry) for sound liveness checking.

## Action Constraints

| Constraint | Description |
|------------|-------------|
| `TransitionValid` | Every region state change is in `ValidTransition` |
| `SCPMonotonicity` | SCP state machine transitions are strictly monotonic |

## Latest Verification Results

### 2r/2s Exhaustive (Primary)

| Detail | Value |
|--------|-------|
| **Date** | 2026-03-06 |
| **TLC version** | 2026.03.02.213938 |
| **Config** | `AssignmentManager.cfg` |
| **Mode** | Exhaustive with symmetry reduction |
| **Result** | ✅ All 21 invariants and action constraints passed |
| **States checked** | 36,896,874 distinct |

## Running the Spec

### Exhaustive (2r/2s)

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager.cfg -workers auto -cleanup
```

### Simulation (3r/3s, configurable duration)

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  -Dtlc2.TLC.stopAfter=600 \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg -simulate \
  -workers auto -cleanup
```

Adjust `-Dtlc2.TLC.stopAfter=<seconds>` for the desired duration (300, 900, 3600).

## Scope and Limitations

**Modeled:**
- Full assign/unassign/move/reopen lifecycle
- Server crash → SCP (detect, fence WALs, reassign regions)
- Master crash and recovery with procedure store replay
- ZooKeeper ephemeral node lifecycle
- WAL fencing to prevent write-side split-brain
- `hbase:meta` persistence and divergence resolution
- Procedure store persistence and recovery
- PEWorker thread pool (worker availability, meta-blocking, async suspension vs sync blocking)
- Configurable implementation quirks (duplicate open, restore succeed)
- Configurable meta-write behavior

**Deferred:**
- Split/merge states (SPLITTING, SPLIT, MERGING, MERGED, SPLITTING_NEW, MERGING_NEW)
- FAILED_CLOSE (RS abort triggers crash detection instead)
