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
20 state variables capturing:

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
- **Keyspace infrastructure** -- per-region key range (`regionKeyRange`) mapping
  each region to a `[startKey, endKey)` interval or `NoRange` for unused
  identifiers.  `DeployedRegions` tile `[0, MaxKey)` at Init; unused identifiers
  are available for future split/merge.
- **Parent-child procedure framework** -- per-region `parentProc` record tracking
  parent procedure type and step (split or merge), persists across child TRSP
  lifecycles and survives master crash.
- **Table identity infrastructure** -- per-region `regionTable` tracking which table
  a region belongs to, with guard predicates (`NoTableExclusiveLock`, `TableLockFree`)
  and an invariant (`TableLockExclusivity`) to ensure exclusive table-level locks
  prevent concurrent region-level operations on the same table.
- **Split procedure** -- `SplitTableRegionProcedure`
  modeled through the parent-child framework: `SplitPrepare` (set SPLITTING,
  spawn child UNASSIGN TRSP to close parent), `SplitResumeAfterClose` (detect
  child completion, advance to point-of-no-return), `SplitUpdateMeta` (PONR:
  write meta, materialize two daughters with computed `[startKey, mid)` and
  `[mid, endKey)` keyspaces, spawn child ASSIGN TRSPs), `SplitDone`
  (daughters OPEN, clear parent keyspace and procedure state), and `SplitFail`
  (pre-PONR rollback: abort the split, create a fresh ASSIGN TRSP to reopen
  the parent).  Crash-during-split is deferred.
- **Merge procedure** -- `MergeTableRegionsProcedure`
  modeled through the parent-child framework: `MergePrepare` (set MERGING on
  two adjacent targets, spawn child UNASSIGN TRSPs to close both),
  `MergeCheckClosed` (detect child completion, advance to PONR),
  `MergeUpdateMeta` (PONR: write meta, materialize merged region with union
  keyspace `[r1.startKey, r2.endKey)`, spawn child ASSIGN TRSP), `MergeDone`
  (merged region OPEN, clear target keyspaces), and `MergeFail` (pre-PONR
  rollback: create fresh ASSIGN TRSPs to reopen both targets).  Gated by
  `UseMerge` constant; disabled in exhaustive mode (unbounded state space
  with split+merge cycle), enabled in simulation mode.
- **CreateTable procedure** -- `CreateTableProcedure`
  modeled through the parent-child framework: `CreateTablePrepare(t, r)` picks
  an unused region identifier, assigns keyspace `[0, MaxKey)`, writes meta as
  `CLOSED`/`NoServer`, spawns a child ASSIGN TRSP, and sets `parentProc =
  [CREATE, SPAWNED_OPEN]`.  `CreateTableDone(t)` clears `parentProc` when all
  CREATE-bearing regions of table `t` are OPEN.  Guards: master alive, PEWorker
  available, meta available, table not in use, `TableLockFree(t)`.  Exercised
  via simulation (`Tables = {T1, T2}`); exhaustive config uses `Tables = {T1}`
  (adding T2 makes exhaustive intractable at any region count).

The specification defines 31 safety invariants verified at every reachable
state, including the critical `NoDoubleAssignment` (no region writable on two
servers), `MetaConsistency` (persistent and in-memory state agree),
`FencingOrder` (WALs fenced before reassignment), `NoLostRegions` (no region
stuck without a procedure after crash recovery), `NoPEWorkerDeadlock`
(thread pool exhaustion detection), `KeyspaceCoverage` (all keys covered by
exactly one live region), `SplitMergeMutualExclusion` (split daughters and
merged regions cannot have active parent procedures), `SplitAtomicity`
(pre-PONR, no daughters materialized), `AtMostOneCarryingMeta` (at most one
server carrying meta), `MergeCompleteness` (completed merge has cleaned-up
targets), `MergeAtomicity` (pre-PONR, merged region not materialized), and
`TableLockExclusivity` (exclusive table locks prevent concurrent region ops
on the same table).

Three liveness properties verify temporal guarantees:
`MetaEventuallyAssigned` (meta eventually reassigned after crash),
`OfflineEventuallyOpen` (ASSIGN-bearing OFFLINE region eventually opens),
and `SCPEventuallyDone` (started SCP eventually completes).  Two action
constraints enforce transition validity and SCP monotonicity.  One state
constraint (`SplitMergeConstraint`) bounds concurrent split/merge procedures
for TLC tractability.

The model checker runs in two tiers: fast exhaustive verification at 3
regions / 2 servers (1 deployed + 2 unused for split daughters), and deep
random simulation at 9r/3s with extended retries and merge enabled.
Configurable "quirk" flags allow toggling known implementation bugs to
correctly adhere to implementation semantics, reproduce failures and
validate fixes.

### TLA+ vs Actor-Based Programming

Both TLA+ and actor-based programming model systems as collections of
state machines communicating through messages, with no shared mutable
state. In TLA+, each process in PlusCal is essentially a labeled state
machine. Each label is a state, and transitions happen atomically between
labels. In actor systems each actor processes one message at a time,
transitioning between internal states. TLA+ models distributed communication
through message channels (sets, sequences, or bags of messages). Actor
systems use mailboxes. In both, messages can be reordered, delayed, or lost
(depending on model assumptions), and the system's behavior emerges from the
interleaving of message processing.

TLA+ is particularly effective for modeling systems like HBase's region
assignment. The real system already has an actor-alike architecture, so the
specification maps naturally onto the implementation architecture. Each
RegionServer and the Master are effectively actors. They have local state,
process events one at a time, and communicate via messages, and through
ZooKeeper as a coordination channel.

Despite the similarities, there are important distinctions. Key is TLA+ lets
you state what should happen, establishing safety and liveness properties,
and exhaustively checks all possible behaviors, while the actor model
provides a runtime framework for building systems that behave that way but
does not perform formal verification. TLA+ operates at a higher level of
abstraction and does not produce executable code. In TLA+, you explicitly
model nondeterminism. In actor systems, nondeterminism happens naturally from
network timing, scheduling, etc. TLA+ makes the nondeterminism exhaustively
explorable. TLA+ has formal fairness conditions. Actor frameworks have
analogous but informal concepts: dispatcher fairness, mailbox prioritization,
and back-pressure mechanisms.

---

This is a formal TLA+ specification of the HBase AssignmentManager, covering the
region assignment lifecycle: state transitions, persistent metadata, procedure-
driven operations, RPC dispatch, RegionServer-side behavior, server crash recovery,
and master crash/recovery. The spec models the core assign/unassign/move/reopen
lifecycle, the split forward path, the merge forward path, and pre-PONR
split/merge rollback for regions across the OFFLINE, OPENING, OPEN, CLOSING,
CLOSED, FAILED_OPEN, ABNORMALLY_CLOSED, SPLITTING, SPLIT, SPLITTING_NEW,
MERGING, MERGED, and MERGING_NEW states.

## Module Structure

| Module | Description |
|--------|-------------|
| [AssignmentManager.tla](markdown/AssignmentManager.md) | Root orchestrator -- variables, Init, Next, Fairness, Spec, invariants, liveness, keyspace predicates |
| [Types.tla](markdown/Types.md) | Constants, type sets, state definitions, `ValidTransition`, parent-child procedure types |
| [TRSP.tla](markdown/TRSP.md) | TransitionRegionStateProcedure actions (assign, unassign, move, reopen, dispatch, confirm, failure, crash recovery, meta-blocking, ResumeFromMeta) |
| [SCP.tla](markdown/SCP.md) | ServerCrashProcedure state machine (detect crash -> assign meta -> get regions -> fence WALs -> assign regions -> done, with meta-blocking) |
| [Split.tla](markdown/Split.md) | Split procedure forward path and pre-PONR rollback using parent-child framework (SplitPrepare, SplitResumeAfterClose, SplitUpdateMeta, SplitDone, SplitFail) |
| [Merge.tla](markdown/Merge.md) | Merge procedure forward path and pre-PONR rollback using parent-child framework (MergePrepare, MergeCheckClosed, MergeUpdateMeta, MergeDone, MergeFail) |
| [Create.tla](markdown/Create.md) | CreateTableProcedure: single-region table creation (CreateTablePrepare, CreateTableDone) |
| [RegionServer.tla](markdown/RegionServer.md) | RS-side handlers (open, fail-open, close, abort, restart, duplicate-open, close-not-found, stale report drop) |
| [Master.tla](markdown/Master.md) | Master-side actions (GoOffline, MasterDetectCrash, MasterCrash, MasterRecover, DetectUnknownServer) |
| [ProcStore.tla](markdown/ProcStore.md) | Procedure store invariants, bijection, and `RestoreSucceedState` recovery operator |
| [ZK.tla](markdown/ZK.md) | Minimal ZooKeeper model -- ephemeral node lifecycle (`ZKSessionExpire`) |

## State Variables (20 total)

- **`regionState`** — volatile in-memory master state per region (state, location, procedure fields)
- **`metaTable`** — persistent `hbase:meta` state per region (survives master crash)
- **`dispatchedOps`** — master→RS command channel per server
- **`pendingReports`** — RS→master report channel
- **`rsOnlineRegions`** — RS-side view of locally online regions
- **`serverState`** — per-server liveness as seen by master (ONLINE / CRASHED)
- **`scpState`** — ServerCrashProcedure progress per server
- **`scpRegions`** — SCP region snapshot per server
- **`walFenced`** — WAL lease revocation state per server
- **`carryingMeta`** — whether a crashed server was hosting `hbase:meta`
- **`serverRegions`** — per-server region tracking (ServerStateNode)
- **`procStore`** — persisted procedure records (survives master crash)
- **`masterAlive`** — master JVM liveness
- **`zkNode`** — ZK ephemeral node liveness per server
- **`availableWorkers`** — number of idle PEWorker threads
- **`suspendedOnMeta`** — regions whose procedures are async-suspended on meta unavailability
- **`blockedOnMeta`** — regions whose procedures are sync-blocked on meta unavailability
- **`regionKeyRange`** -- per-region keyspace assignment (`[startKey, endKey)` or `NoRange` for unused identifiers)
- **`parentProc`** -- per-region parent procedure record (`[type, step, ref1, ref2]`) tracking split/merge progress across child TRSP lifecycles; `ref1`/`ref2` hold region references (daughters for split, peer/merged for merge)
- **`regionTable`** -- per-region table identity tracking which table each region belongs to (`Tables` or `NoTable` for unused identifiers); used by guard predicates to enforce exclusive table-level locks

## Configurable Behaviors

| Constant | Description |
|----------|-------------|
| `UseReopen` | `TRUE` enables the REOPEN procedure, needed to model branch-2 |
| `UseRSOpenDuplicateQuirk` | `TRUE` models `AssignRegionHandler.process()` silent-drop bug (causes deadlock) |
| `UseRSCloseNotFoundQuirk` | `TRUE` models `UnassignRegionHandler.process()` silent-drop bug (causes deadlock) |
| `UseRestoreSucceedQuirk` | `TRUE` reproduces `OpenRegionProcedure.restoreSucceedState()` bug where FAILED_OPEN reports replay as OPENED (causes constraint violations) |
| `UseBlockOnMetaWrite` | `FALSE` (default): async suspension releases PEWorker. `TRUE` (branch-2.6): sync blocking holds PEWorker |
| `UseMerge` | `TRUE` enables merge actions in `Next`/`Fairness`. `FALSE` (default) keeps exhaustive mode tractable (split-only) |
| `MaxRetries` | Maximum open-retry count per procedure |
| `MaxWorkers` | PEWorker thread pool size; all procedure-step actions require `availableWorkers > 0` |
| `MaxKey` | Upper bound of the keyspace `[0, MaxKey)` |
| `UseUnknownServerQuirk` | `TRUE` models `checkOnlineRegionsReport()` gap: orphans on Unknown Servers closed silently without TRSP. `FALSE` (default): master creates TRSP(ASSIGN) |
| `UseMasterAbortOnMetaWriteQuirk` | `TRUE` models when `RegionStateStore.updateRegionLocation()` calls `master.abort()` on `IOException` during meta write, crashing the master. `FALSE` (default): suspend/block per `UseBlockOnMetaWrite` |
| `UseStaleStateQuirk` | `TRUE` models when `visitMeta()` creates `ServerStateNode` for dead servers referenced in meta, making them appear `ONLINE` (no SCP started, regions never recovered). `FALSE` (default): correct `zkNode`-based liveness |

## Verification Configurations

### 1. Primary Exhaustive -- 3 Regions / 2 Servers ([AssignmentManager.cfg](markdown/AssignmentManager-cfg.md))

Fast exhaustive model check with symmetry reduction. 1 deployed region tiles
`[0, 2)` with 2 unused identifiers for split daughters.

| Parameter | Value |
|-----------|-------|
| Regions | `{r1, r2, r3}` |
| Servers | `{s1, s2}` |
| DeployedRegions | `{r1}` |
| MaxKey | 2 |
| MaxRetries | 1 |
| MaxWorkers | 2 |
| UseReopen | FALSE |
| Symmetry | `Permutations(Regions \ DeployedRegions) \cup Permutations(Servers)` |
| Mode | Exhaustive |

### 2. Simulation -- 9 Regions / 3 Servers ([AssignmentManager-sim.cfg](markdown/AssignmentManager-sim-cfg.md))

Deep random-trace simulation at 9r/3s (3 deployed + 6 unused) with
`MaxRetries = 2`.  3 deployed regions tile `[0, 12)` with width 4 each,
allowing up to 3 independent splits.  Probabilistic coverage of the full
state space including cascading crashes, master crash/recovery, concurrent
split operations, and multi-cycle assign/unassign/move sequences.
Simulation is the only tier that verifies deeper retry behavior and REOPEN.

| Parameter | Value |
|-----------|-------|
| Regions | `{r1, r2, r3, r4, r5, r6, r7, r8, r9}` |
| Servers | `{s1, s2, s3}` |
| DeployedRegions | `{r1, r2, r3}` |
| MaxKey | 12 |
| MaxRetries | 2 |
| MaxWorkers | 3 |
| UseReopen | TRUE |
| Mode | Random Simulation |

**Recommended simulation durations:**

| Tier | Duration | Use Case |
|------|----------|----------|
| Per-iteration | 900s (15 min) | Feedback during development |
| Post-iteration | 3600s (1 hr) | Validation after completing an iteration |
| Post-phase | 14400s (4 hr) | Milestone verification |

## Invariants

All configurations check the same 31 safety invariants:

| Invariant | Description |
|-----------|-------------|
| `TypeOK` | Type correctness for all state variables |
| `OpenImpliesLocation` | OPEN regions always have a location |
| `OfflineImpliesNoLocation` | Offline-like regions have no location |
| `NoDoubleAssignment` | Region writable on at most one server (WAL-fencing–aware) |
| `MetaConsistency` | `hbase:meta` matches in-memory state (with allowed divergences) |
| `LockExclusivity` | Procedure type correlates with valid region lifecycle states |
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
| `KeyspaceCoverage` | All keys in `[0, MaxKey)` covered by exactly one live region with no gaps or overlaps |
| `SplitMergeMutualExclusion` | Daughter/merged regions (SPLITTING_NEW, MERGING_NEW) cannot have active parent procedures |
| `SplitAtomicity` | Pre-PONR (SPAWNED_CLOSE phase), no SPLITTING_NEW daughters of this parent exist |
| `NoOrphanedDaughters` | SPLITTING_NEW regions always have an ASSIGN procedure |
| `SplitCompleteness` | After split completes (parent SPLIT + NoRange), parentProc is cleared |
| `AtMostOneCarryingMeta` | At most one server can be carrying `hbase:meta` at any time |
| `NoOrphanedMergedRegion` | MERGING_NEW regions always have an ASSIGN procedure |
| `MergeCompleteness` | After merge completes (targets MERGED + NoRange), parentProc is cleared |
| `MergeAtomicity` | Pre-PONR (SPAWNED_CLOSE phase), merged region not materialized |
| `TableLockExclusivity` | No two regions of the same table can simultaneously hold exclusive-type parent procedures (CREATE, DELETE, TRUNCATE) |

## Liveness Properties

| Property | Description |
|----------|-------------|
| `MetaEventuallyAssigned` | When meta becomes unavailable (`ASSIGN_META`), the SCP eventually reassigns it |
| `OfflineEventuallyOpen` | Once an ASSIGN procedure is attached to an OFFLINE region, the region eventually reaches OPEN |
| `SCPEventuallyDone` | Once an SCP starts for a crashed server (`scpState ∉ {NONE, DONE}`), it eventually completes (`scpState = DONE`) |

> Liveness properties are incompatible with TLC's `SYMMETRY` reduction.
> Use [`AssignmentManager-liveness.cfg`](markdown/AssignmentManager-liveness-cfg.md)
> (no symmetry) for sound liveness checking.

## Action Constraints

| Constraint | Description |
|------------|-------------|
| `TransitionValid` | Every region state change is in `ValidTransition` |
| `SCPMonotonicity` | SCP state machine transitions are strictly monotonic |

## State Constraints

| Constraint | Description |
|------------|-------------|
| `SplitMergeConstraint` | Bounds concurrent split/merge procedures to at most 1 for TLC tractability |

## Latest Verification Results

### 3r/2s Exhaustive (Primary)

| Detail | Value |
|--------|-------|
| **Date** | 2026-03-15 |
| **TLC version** | 2026.03.02.213938 |
| **Config** | `AssignmentManager.cfg` (3r/2s: 1 deployed + 2 unused, split only) |
| **Mode** | Exhaustive with symmetry reduction |
| **Workers** | 128 on 128 cores |
| **Result** | All 31 invariants, 2 action constraints, and state constraint passed |
| **States generated** | 1,328,348,760 |
| **States checked** | 368,662,744 distinct |
| **Depth** | 92 |
| **Duration** | ~71 min |

### 9r/3s Simulation

| Detail | Value |
|--------|-------|
| **Date** | 2026-03-13 |
| **TLC version** | 2026.03.12.002851 |
| **Config** | `AssignmentManager-sim.cfg` (9r/3s: 3 deployed + 6 unused, split and merge) |
| **Mode** | Random Simulation (seed -2625496395084300664) |
| **Workers** | 128 on 128 cores |
| **Result** | All 31 invariants, 2 action constraints, and state constraint passed |
| **States generated** | 1,297,736,623 |
| **Duration** | 8 hours |

## Running the Spec

### Exhaustive (3r/2s)

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager.cfg -workers auto -cleanup
```

### Simulation (9r/3s, configurable duration)

```sh
java -XX:+UseParallelGC -cp "tla2tools.jar:CommunityModules-deps.jar" \
  -Dtlc2.TLC.stopAfter=3600 \
  tlc2.TLC AssignmentManager.tla -config AssignmentManager-sim.cfg -simulate \
  -workers auto -cleanup
```

Adjust `-Dtlc2.TLC.stopAfter=<seconds>` for the desired duration (900, 3600, 14400).
