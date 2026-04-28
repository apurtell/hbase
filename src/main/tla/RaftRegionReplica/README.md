# RaftRegionReplica TLC Model Checking

## What is TLA+?

TLA+ is a formal specification language created by Leslie Lamport for designing and verifying concurrent and distributed systems. The name stands for the **Temporal Logic of Actions**, a mathematical framework that combines first-order logic with temporal operators to reason about how system state evolves over time. TLA+ does not produce executable code. Instead it produces a precise, machine-checkable mathematical model of a system's behavior that can be exhaustively verified against safety and liveness properties. When the model is a high-fidelity representation of the real system, proposed design and architectural changes can be checked against the full space of possible executions, surfacing critical logic bugs at design time, before any code is written. This can save weeks or months of development effort that would otherwise be spent discovering and debugging subtle concurrency issues in a running system.

A TLA+ specification describes a system as a state machine, an initial state predicate (`Init`), a next-state relation (`Next`) that defines every legal transition, and a collection of invariants, properties that must hold in every reachable state.  The TLC model checker then systematically explores every possible execution of this state machine, checking each property at every state. If a property is violated, TLC produces a minimal counterexample trace showing the exact sequence of steps that led to the failure.

### Temporal Logic Foundations

Classical logic can express facts about a single moment in time, but it cannot express how ground truth changes across a sequence of states. Temporal logic extends classical logic with operators that talk about behaviors, infinite sequences of states:

| Operator | Meaning |
|----------|---------|
| `[]P` ("always P") | Property `P` holds in every state of every behavior |
| `<>P` ("eventually P") | Property `P` holds in some future state |
| `[]<>P` ("infinitely often") | `P` holds in infinitely many states |
| `<>[]P` ("eventually always") | `P` holds from some point onward forever |
| `P ~> Q` ("P leads to Q") | Whenever `P` becomes true, `Q` eventually becomes true |

Safety properties (expressed with `[]`) assert that bad things never happen. Those assertions can be checked and formally validated.

Liveness properties (expressed with `<>` and `~>`) assert that good things eventually happen. Liveness requires fairness conditions, the assumption that the system does not indefinitely starve enabled actions.

In TLA+, a single step of the system is an action, a predicate over the current  state and the next state. The `Next` relation is the disjunction of all actions, meaning that at each step, any enabled action may fire. This naturally models the non-determinism inherent in distributed systems: message arrival order, scheduling decisions, and failure timing are all left unspecified, so the model checker explores every possible interleaving.

## Overview

`RaftRegionReplica.tla` is a TLA+ specification modeling RAFT-based region replicas for Apache HBase.  It captures leader election, lease management, clock drift, the write pipeline (WAL sync, RAFT commit, memstore apply), the snapshot-boundary flush protocol (concurrent write+flush via `snapshotMaxSeqId` / `flushDropBound`, HFile commit, RAFT-proposed flush markers with HFile coverage boundary), the promotion protocol with master confirmation (MasterConfirmPromotion action with term-fencing guard), RAFT log garbage collection, shared-storage catch-up, new member bootstrap, crash recovery, and network partitions (including individual link healing and full network recovery).

The base spec exports parameterized building-block operators that collect all single-member normal-operation actions into a single disjunction. Composition modules invoke these with per-member gating predicates to control when a RAFT group's write path and RAFT operations are active on each member, enabling lifecycle transitions (split, merge) to write-close a group per-member without duplicating action dispatch logic. In the real system, the parent's read path remains active after write-closure (frozen-parent read continuation), serving Timeline reads from the frozen, immutable memstore + HFiles until daughter or merged groups are ready. The read path is not modeled because reads do not flow through the consensus layer. Guard + effect factoring of `CrashRestart`, `ClockTick`, and `RaftLogGC` allows multi-group and lifecycle modules to reuse crash/tick/GC effects across groups without copy-paste. Named variable tuples and shared helper operators reduce UNCHANGED clause verbosity and eliminate duplicated guard logic across actions.

Dual-group compositions (`MultiGroupRaftRegionReplica.tla` and `MergeRaftRegionReplica.tla`) share common infrastructure via `DualGroupBase.tla`, which provides per-group variable declarations, INSTANCE blocks, variable tuples, and the `PerGroupSafety` invariant. Single-group MC configurations share common constants via `MCRaftRegionReplica_base.tla`.

The specification intentionally abstracts several features of `hbase-consensus` that do not change the safety / liveness story at this level. These are documented in detail in the spec header (`RaftRegionReplica.tla`, "Implementation features intentionally abstracted") and listed here for orientation:
- The wire-level distinction between `LeaderHeartbeat` (lightweight, steady-state liveness) and `AppendEntriesRequest` (log replication, snapshot trigger, `matchIndex` discovery, membership-op preparation): the spec has a single atomic `LeaderHeartbeat` action that simultaneously resets responder election timers and refreshes the leader's lease. The implementation splits the round across two wire messages (`LeaderHeartbeat` broadcast + per-follower `LeaderHeartbeatAck`) for performance, but at this abstraction level the round must be modeled atomically because the `LeaseExpiresBeforeElection` argument requires the lease refresh and follower timer-resets to be causally bound by the same round-trip. Log replication is folded into the atomic `RAFTCommitWrite` / `FlushRAFTPropose` / `ProposeMarker` actions.
- The `lastVerifiedLogIndex` clamp on commit-index advancement from heartbeats — unnecessary at this abstraction level because `RAFTCommitWrite` is atomic.
- Linearizable queries (`QueryState`, `querySequenceNumber`, the fail-pending-and-bump-QSN-on-leader-self-removal handling). Reads do not flow through the consensus layer in the spec.
- `REMOVE_MEMBER` / `ADD_LEARNER` / `ADD_OR_PROMOTE_TO_FOLLOWER` `UpdateRaftGroupMembersOp` entries as replicated log entries, including leader self-removal that drives the node into `RaftNodeStatus.TERMINATED`. Membership in the spec is the static `CONSTANT Members` set.
- `LEARNER` role / non-voting members. The spec's `role` ranges over `{Follower, Candidate, Leader}`.
- `PreVote` as a distinct round; subsumed in the leader-stickiness guard on `RequestVote`.
- Chunked `InstallSnapshot` transfer (`SnapshotChunkCollector`). The consensus layer carries one snapshot wire path. The application payload it transports is opaque. The spec collapses chunk-transfer dynamics into a single atomic `FollowerLoadFlushedState` action whose effect is the design-target shared-storage catch-up. The follower log is truncated to the snapshot index and the application data is recoverable by loading HFiles on HDFS reached through the SPI-encoded metadata bytes.

The specification defines 19 safety invariants and 6 liveness properties verified by TLC:

| # | Invariant | Category |
|---|-----------|----------|
| 1 | `TypeOK` | Type correctness |
| 2 | `LeaderUniqueness` | RAFT consensus |
| 3 | `LeaseImpliesLeadership` | RAFT consensus |
| 4 | `LeaseExpiresBeforeElection` | Timing / lease |
| 5 | `CatchUpDataIntegrity` | Data recoverability |
| 6 | `WriteBarrierSafety` | Write path |
| 7 | `FollowerSeqIdConsistency` | Write path |
| 8 | `NoOrphanMemstoreDrop` | Flush protocol |
| 9 | `FlushDropBoundary` | Flush protocol |
| 10 | `FollowerFlushMemstoreDrop` | Flush protocol |
| 11 | `HFilesBeforeFlushMarker` | Flush protocol |
| 12 | `PromotionReadWriteGuard` | Promotion |
| 13 | `PromotionMVCCContinuity` | Promotion |
| 14 | `CatchUpCompleteness` | Catch-up |
| 15 | `QuiesceImpliesAllAcked` | Quiescence |
| 16 | `QuiesceImpliesNoPendingWrite` | Quiescence |
| 17 | `QuiesceImpliesIdleFlush` | Quiescence |
| 18 | `QuiesceImpliesTermConsistency` | Quiescence |
| 19 | `WakeBeforePropose` | Quiescence |

The specification also defines 6 liveness properties checked under fairness constraints:

| # | Property | Fairness | Description |
|---|----------|----------|-------------|
| 1 | `ElectionProgress` | `LiveSpecElection` | If no leader exists, one is eventually elected |
| 2 | `WriteCompletion` | `LiveSpecWrite` | A pending write eventually returns to idle |
| 3 | `FlushCompletion` | `LiveSpecFlush` | A started flush eventually completes |
| 4 | `PromotionCompletion` | `LiveSpecLocal` | A promoting member eventually leaves Promoting (via master confirmation) and AwaitingMaster (via local completion) |
| 5 | `CatchUpCompletion` | `LiveSpecLocal` | A catching-up follower eventually finishes |
| 6 | `EventualWake` | `LiveSpecLocal` | A quiescent leader eventually wakes (Wake fires) or transitions out of leadership |

Properties 1-3 are network-dependent and require strong fairness (SF) on network recovery and RAFT communication actions.  Properties 4-6 are local and require only weak fairness (WF) on local actions — network instability cannot block them.  `EventualWake` is in this category because `Wake` and `LeaderKeepalive` are local actions; `BaseFairness` includes `WF_vars(Wake(m))` and `WF_vars(LeaderKeepalive(m))`.

Fairness is factored into `BaseFairness` (WF for the 19 local actions, including `MasterConfirmPromotion`, `Wake`, and `LeaderKeepalive`) plus per-property SF additions (`ElectionSF`, `WriteSF`, `FlushSF`).  Network recovery uses `SF_vars(HealAllPartitions)` — a deterministic action that forces full network recovery — rather than `SF_vars(HealPartition)`, because `HealPartition`'s internal `\E` nondeterminism allows TLC to always heal the same unhelpful link while leaving other members isolated.

### Idle-group quiescence

The specification models idle-group quiescence (see the `Quiesce`, `Wake`, and `LeaderKeepalive` actions in the spec header).  When a leader's group is fully caught up and the lease is valid, `Quiesce` atomically marks the leader and every reachable responder quiescent, the leader's lease is refreshed one final time, and per-group `LeaderHeartbeat` actions stop firing.  Per-tick failure detection switches to the per-server `LeaderKeepalive` action that refreshes the leader's lease and resets responders' election timers without touching per-group state.  Any leader-side propose action (`BeginWrite`, `ProposeMarker`, `FlushStart`, `ProposeSplitMarker`, `ProposeMergeMarker_*`) is gated on `~groupQuiescent[leader]` and the leader must explicitly `Wake` first; `Wake` is also the only path that clears `groupQuiescent` and `laggingOnQuiesce` in steady state.  Crash-restart, role transitions (`Timeout`, `RequestVote`, `BecomeLeader`, `StepDown`, `LeaderLeaseExpiry`), and partition healing all reset `groupQuiescent` to `FALSE` on the affected member.

The five new safety invariants capture the implementation contract:
- `QuiesceImpliesAllAcked` — for a quiescent leader, every quiescent responder agrees with the leader on the committed prefix of the RAFT log.
- `QuiesceImpliesNoPendingWrite` — no in-flight write on a quiescent leader.
- `QuiesceImpliesIdleFlush` — no in-flight flush on a quiescent leader.
- `QuiesceImpliesTermConsistency` — every quiescent member agrees with the quiescent leader on `currentTerm`.
- `WakeBeforePropose` — propose-pipeline state (write Pending/Applied, flush FlushStarted/HFilesCommitted/RAFTProposed/RAFTCommitted) is unreachable while the leader is quiescent; every propose path goes through `Wake` first.

`EventualWake` (liveness) ensures a quiescent leader eventually unquiesces (via `Wake`, role transition, or lease expiry); without it, weak fairness on `Wake` would not exclude infinite-quiescence executions.

## Latest Results

The table below reflects the most recent run on the merged spec (with idle-group quiescence integrated into the base spec and all five quiescence invariants wired into every safety configuration).  Numbers without an explicit timestamp note are pre-quiescence baselines retained for reference; quiescence runs are scheduled after the implementation lands.

| # | Configuration | Module (+ config file) | States generated | Wall time | Result |
|---|---|---|---|---|---|
| 1 | Base simulation | `MCRaftRegionReplica_sim` | 1,167,284,500 | 15m | Pass (pre-quiescence; smoke run on merged spec covers 119k states without invariant violation) |
| 2 | Datapath domain | `MCRaftRegionReplica_datapath` | 1,226,965,995 | 15m | Pass (pre-quiescence) |
| 3 | Election domain | `MCRaftRegionReplica_election` | 1,278,598,301 | 15m | Pass (pre-quiescence) |
| 4 | Multi-group | `MCRaftRegionReplica_multigroup` | 254,440,915 | 15m | Pass (pre-quiescence) |
| 5 | Split lifecycle | `MCRaftRegionReplica_split` | 521,235,119 | 15m | Pass (pre-quiescence) |
| 6 | Merge lifecycle | `MCRaftRegionReplica_merge` | 236,765,899 | 15m | Pass (pre-quiescence) |
| 7 | Full cross-product | `MCRaftRegionReplica` | 1,176,078,499 | 15m | Pass (pre-quiescence) |
| 8 | Liveness: election | `MCRaftRegionReplica_liveness_election` (`_liveness_election.cfg`) | 3,397,323 | 18m 14s | Pass (pre-quiescence) |
| 9 | Liveness: write | `MCRaftRegionReplica_liveness` (`_liveness_write.cfg`) | 71,391,615 | 15m | Pass (pre-quiescence) |
| 10 | Liveness: flush | `MCRaftRegionReplica_liveness` (`_liveness_flush.cfg`) | 75,178,436 | 15m | Pass (pre-quiescence) |
| 11 | Liveness: promotion | `MCRaftRegionReplica_liveness` (`_liveness_promotion.cfg`) | 51,867,035 | 15m | Pass (pre-quiescence) |
| 12 | Liveness: catchup | `MCRaftRegionReplica_liveness` (`_liveness_catchup.cfg`) | 106,405,908 | 15m | Pass (pre-quiescence) |
| 13 | Liveness: quiescence | `MCRaftRegionReplica_liveness` (`_liveness_quiescence.cfg`) | TBD | TBD | TBD (`EventualWake`) |

Across the twelve pre-quiescence configurations, approximately 5.17 billion distinct states were explored with no invariant violations and no liveness counterexamples.  After merging the quiescence model the merged spec passes SANY parsing on every module and a 3-second simulation smoke run on `MCRaftRegionReplica.cfg` (≈119k states) without any invariant violation.  Full re-runs of all 12+1 configurations are scheduled as part of the build-verify task; results will be back-filled into this table when complete.

## Verification Strategy

The model checking suite uses three verification layers that compose to provide complete coverage.

### Simulation

Exercises both timing and data-path domains simultaneously with the full unmodified spec, full timing (MaxClockDrift=1), deep data path (MaxSeqId=5), and all 31 actions.  Provides high-confidence statistical coverage of the cross-product of timing and data-path states.

Simulation is critical because it is the ONLY configuration that exercises both domains simultaneously at full parameter ranges.  The domain-decomposed exhaustive configs provide mathematical proofs within their respective parameter slices, but they do not cover the full cross-product.  Simulation compensates by statistically exploring the cross-product at even deeper parameter ranges.

### Domain-Decomposed Exhaustive (datapath + election)

Splits the exhaustive state space into two domains that can run concurrently, completing in hours instead of days.  Each domain provides a mathematical proof hat no invariant violation exists within its parameter range.  Together they cover all 14 invariants non-trivially.

#### Datapath Domain (`MCRaftRegionReplica_datapath`)

This domain retains full data-path depth (MaxSeqId = 3) while collapsing the timing dimension: MaxClockDrift = 0 forces all member clocks to advance in lockstep, and ElectionTimeoutMin and MaxClock are reduced to 2 accordingly (the lease inequality 1 < 2 - 0 still holds). Partitions are constrained to at most one link failure.

#### Election Domain (`MCRaftRegionReplica_election`)

This domain preserves full timing parameters (MaxClockDrift = 1, ElectionTimeoutMin = 4, MaxClock = 4) while reducing the data-path dimension to MaxSeqId = 1, allowing at most one write or flush per trace.

### Multi-Group (`MCRaftRegionReplica_multigroup`)

Verifies that two RAFT groups sharing the same ConsensusServer (clock, network, unified log) do not violate each other's safety invariants.  Uses `MultiGroupRaftRegionReplica.tla`, which composes two INSTANCE copies of the base spec with shared-impact actions (ClockTick, CrashRestart, Create/HealPartition) using factored guard + effect operators and a `UnifiedLogGC` action modeling cross-group log segment deletion.  Group 1 is designated as the META group and Group 2 as a non-META group.  Group 2's `MasterConfirmPromotion` is gated on `MetaReady` (a derived operator: true when some G1 member has `promotionPhase = "Complete"`), modeling the META availability ordering constraint from the design document's "META Region Self-Promotion Bootstrap" section.  Group 1 uses the base spec's `MasterConfirmPromotion` unmodified (the master confirms META via an in-memory-only path with no META write dependency).  META promotion ordering is structural (enforced by the gate), not a state-based invariant.  All 14 invariants are checked per-group; `CatchUpDataIntegrity` is the key cross-group invariant verifying that unified log GC does not delete entries needed for catch-up.

The two-group cross-product produces a large state space.  Exhaustive BFS requires a large machine.  Simulation mode provides high-confidence coverage locally.

### Liveness Simulation

Verifies the 5 liveness properties under fairness constraints using TLC's simulation mode.  Each property has a dedicated `.cfg` file selecting the appropriate `LiveSpec` (which includes only the SF terms that property's progress chain requires, avoiding DNF blowup).

The election property uses a dedicated MC module (`MCRaftRegionReplica_liveness_election`) with tuned constants: `MaxTerm=4`, `ElectionTimeoutMin=2`, `MaxClock=12`, `MaxSeqId=1`.  These provide sufficient clock/term headroom for election liveness while keeping the state space tractable.  The other 4 properties use the standard liveness MC module (`MCRaftRegionReplica_liveness`) with `MaxSeqId=2` and no symmetry reduction (symmetry is incompatible with TLC liveness checking).

Exhaustive liveness checking is intractable due to the large state space × tableau product.  Simulation provides statistical confidence.

### Full Exhaustive

Proves absence of violations across the complete cross-product at MaxSeqId=3 and full timing.  Takes days on a large machine.  Run for pre-release gates or after major spec changes when a complete proof over the cross-product is required.

## Validation

Follow these steps to fully validate the design:

### Step 1: Simulation Quick-Check (15–30 min)

Run simulation to confirm no obvious violations.  This exercises both domains together and catches regressions quickly.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=900 \
  tlc2.TLC MCRaftRegionReplica_sim -simulate -depth 120 -workers auto
```

### Step 2: Datapath Exhaustive (1–8 hours)

Verify data-path protocol correctness with BFS proof.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  tlc2.TLC MCRaftRegionReplica_datapath -workers auto
```

### Step 3: Election Exhaustive (minutes)

Verify timing/drift safety with BFS proof.  Can run concurrently with Step 2.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  tlc2.TLC MCRaftRegionReplica_election -workers auto
```

### Step 4: Multi-Group Simulation (30 min, local)

Verify that two groups sharing the same ConsensusServer do not interfere with each other's safety.  Runs locally on any machine.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=1800 \
  tlc2.TLC MCRaftRegionReplica_multigroup -simulate -depth 150 -workers auto
```

### Step 5: Multi-Group Exhaustive

BFS proof of multi-group safety.  The two-group cross-product produces billions of distinct states; requires a large machine.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  tlc2.TLC MCRaftRegionReplica_multigroup -workers auto
```

### Step 6: Split Lifecycle Simulation (30 min, local)

Verify that the region split protocol preserves `NoKeyRangeOverlap` and all 14 parent-group safety invariants.  Uses `SplitRaftRegionReplica.tla`, which gates the parent group's write path and RAFT operations per-member after the split marker is applied (write-closure).  The parent's read path (frozen-parent read continuation) is not modeled because Timeline reads do not flow through the consensus layer; `NoKeyRangeOverlap` constrains write-active groups only.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=1800 \
  tlc2.TLC MCRaftRegionReplica_split -simulate -depth 150 -workers auto
```

### Step 7: Merge Lifecycle Simulation (30 min, local)

Verify that the region merge protocol preserves `NoKeyRangeOverlapMerge` and all 14 per-group safety invariants for both parent groups.  Uses `MergeRaftRegionReplica.tla`, which gates each parent group's write path and RAFT operations independently after their respective merge markers are applied (write-closure).  As with split, the frozen-parent read continuation is not modeled; `NoKeyRangeOverlapMerge` constrains write-active groups only.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=1800 \
  tlc2.TLC MCRaftRegionReplica_merge -simulate -depth 150 -workers auto
```

### Step 8: Interpret Results

All checks (simulation, both single-group exhaustive domains, multi-group simulation, multi-group exhaustive, split simulation, merge simulation, and liveness simulation) must complete with no violations for the design to be considered validated.  Cross-reference the invariant coverage matrix to confirm every safety invariant received non-trivial verification in at least one exhaustive domain, and confirm all 5 liveness properties pass at least 3 simulation passes each.

### Step 9: Liveness Simulation

Run each of the 5 liveness properties in simulation mode.  Each property uses a dedicated `.cfg` file. The election property uses a dedicated MC module (`MCRaftRegionReplica_liveness_election`) with tuned constants (MaxTerm=4, ElectionTimeoutMin=2, MaxClock=12) to provide sufficient clock/term headroom.

```bash
JAVA_HOME=/path/to/temurin-17
for prop in election write flush promotion catchup; do
  if [ "$prop" = "election" ]; then
    MC=MCRaftRegionReplica_liveness_election
  else
    MC=MCRaftRegionReplica_liveness
  fi
  CFG=MCRaftRegionReplica_liveness_${prop}.cfg
  echo "=== $prop ==="
  "$JAVA_HOME/bin/java" -XX:+UseParallelGC -Xmx8g \
    -cp tla2tools.jar -Dtlc2.TLC.stopAfter=1800 \
    tlc2.TLC $MC -simulate -depth 500 -workers auto \
    -config $CFG
done
```

### Step 10: Daily Deep Simulation

Run overnight for ongoing regression coverage.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=28800 \
  tlc2.TLC MCRaftRegionReplica_sim -simulate -depth 120 -workers auto
```

## Running TLC

For simulation (dev inner loop):
```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  tlc2.TLC MCRaftRegionReplica_sim -simulate -depth 120 -workers auto
```

For election exhaustive (fast enough for local):
```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  tlc2.TLC MCRaftRegionReplica_election -workers auto
```

For datapath exhaustive:
```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  tlc2.TLC MCRaftRegionReplica_datapath -workers auto -checkpoint 60
```

For multi-group simulation (local):
```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=1800 \
  tlc2.TLC MCRaftRegionReplica_multigroup -simulate -depth 150 -workers auto
```

For multi-group exhaustive (large machine):
```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  tlc2.TLC MCRaftRegionReplica_multigroup -workers auto
```

For full exhaustive:
```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  tlc2.TLC MCRaftRegionReplica -workers auto
```

For liveness simulation (single property, e.g., election):
```bash
java -XX:+UseParallelGC -Xmx8g -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=900 \
  tlc2.TLC MCRaftRegionReplica_liveness_election \
  -simulate -depth 500 -workers auto \
  -config MCRaftRegionReplica_liveness_election.cfg
```

For liveness simulation (non-election properties, e.g., write):
```bash
java -XX:+UseParallelGC -Xmx8g -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=900 \
  tlc2.TLC MCRaftRegionReplica_liveness \
  -simulate -depth 500 -workers auto \
  -config MCRaftRegionReplica_liveness_write.cfg
```

## Side specs

### `OutboundChannelFlush.tla` — outbound transport flush latency

`OutboundChannelFlush.tla` is a standalone, single-channel model of the per-peer outbound mailbox in `OutboundChannel.java` that the deadline-based flush wakeup mechanism (introduced to bound head-of-line latency in `CoalescingTransport`) operates on. It models four producers of "schedule a drain" wake-ups: immediate enqueues, coalescible enqueues with the new head-age deadline check, the periodic `BATCH_MS` tick (folded into `ClockTick`), and the post-drain tail re-arm. Both the CAS-fail behavior of `scheduleDrainOn` and the unbounded-poll loop in `drainOnce` are modeled so the spec captures the lost-wakeup window the deadline mechanism is designed to close.

Composition with `RaftRegionReplica.tla` is by *assumption*, not `INSTANCE`: the consensus spec's atomic `LeaderHeartbeat` action assumes wire delivery is "fast enough" relative to `leaderHeartbeatTimeoutMillis`. `OutboundChannelFlush.tla` proves a tight value for that "fast enough" — `BoundedDeliveryLatency(L)` with `L = TickPeriod + DrainCost` in the worst case — that the implementation must respect. As long as the configured `hbase.consensus.transport.flush.deadline.ms` plus the worst-case observed `OutboundChannelStats.drainWallNanos` stays well below `2 * leaderHeartbeatTimeoutMillis`, the leader-election liveness story in the main spec carries over.

| Configuration | Module | Constants | Purpose | States | Result |
|---|---|---|---|---|---|
| Base safety | `MCOutboundChannelFlush` (`MCOutboundChannelFlush.cfg`) | TickPeriod=1, FlushDeadline=3, DrainCost=1, MaxClock=8, MaxEnqueues=3, **L=2** | Tight latency bound at TickPeriod=DrainCost=1; L=1 is provably violated, L=2 holds. | 35,205 | Pass |
| Stress safety | `MCOutboundChannelFlush` (`MCOutboundChannelFlush_stress.cfg`) | TickPeriod=4, FlushDeadline=2, DrainCost=5, MaxClock=16, MaxEnqueues=4, **L=9** | DrainCost > FlushDeadline regime; mirrors the 1000-group stress where one drain absorbs ~1s of encode work. L=9 = TickPeriod + DrainCost is tight. | 3,212,421 | Pass |
| Liveness | `MCOutboundChannelFlush` (`MCOutboundChannelFlush_liveness.cfg`) | TickPeriod=1, FlushDeadline=3, DrainCost=1, MaxClock=10, MaxEnqueues=3 | `EventualDelivery` under WF on ClockTick / BeginDrain / EndDrain. | 109,837 | Pass |
| Simulation | `MCOutboundChannelFlush` (`MCOutboundChannelFlush_sim.cfg`) | TickPeriod=5, FlushDeadline=3, DrainCost=7, MaxClock=60, MaxEnqueues=12, L=12 | Deeper random exploration, ~3.4 minutes. | 192,000,001 | Pass |

The verification fed three concrete decisions back into the implementation plan:

1. **Default `hbase.consensus.transport.flush.deadline.ms = 250 ms`** — the deadline only changes the worst case under tick starvation (where the mechanism is the only safety net). With sustained producer activity it caps head age at FlushDeadline + DrainCost, so 250 ms keeps the head-of-line latency under one second in the realistic worst case (DrainCost ≈ 700 ms for an 8000-batch encode on an oversubscribed event loop).
2. **`TestConsensusServerScale.leaderHeartbeatLag.p99 < 2 * leaderHeartbeatTimeoutMillis`** — the model's tight bound is well below `2 * 5 s = 10 s`, so the hardened invariant is achievable.
3. **No additional wake-up sites needed** — counterexample triage showed all known lost-wakeup scenarios are closed by the combination of `scheduleDrainOn`'s CAS, the unbounded poll in `drainOnce`, and the deadline check in `enqueue()`. The tail re-arm extension to also fire on stale heads is the only Java-side protocol change required.

#### Counterexample log

The TLC iteration that produced the model also produced a small, useful list of "would-have-been-bug" traces. All were closed by tightening the model rather than the implementation; none required a Java code change.

| # | Counterexample | Triage | Fix |
|---|---|---|---|
| 1 | TLC interleaved arbitrary `ClockTick`s between `drainScheduled = TRUE` and `BeginDrain`, inflating latency past L. | Modeling artifact — the implementation queues the drain runnable synchronously with the CAS. | Added `~drainScheduled` precondition to `ClockTick`. |
| 2 | TickFlush as a may-fire action with no fairness let TLC defer ticks arbitrarily, again inflating latency past L. | Modeling artifact — `scheduleWithFixedDelay` is deterministic on its period. | Folded the periodic tick into `ClockTick` so it fires at every clock multiple of `TickPeriod`. |
| 3 | TLC interleaved `ClockTick`s past the EndDrain enabling boundary (`clock >= drainStart + DrainCost`). | Modeling artifact — once a drain has consumed its work it proceeds straight to releasing the latch. | Added `~(draining /\ clock >= drainStart + DrainCost)` precondition to `ClockTick`. |
| 4 | Liveness CE: producer enqueue at `clock = MaxClock` left the mailbox non-empty with no clock budget for a drain to complete. | Model-horizon artifact. | Added `EnqueueHonoursHorizon` CONSTRAINT used by the liveness config. |

#### Running

```bash
JAR=/path/to/tla2tools.jar
cd src/main/tla/RaftRegionReplica

# Base safety (~1s, exhaustive)
java -XX:+UseParallelGC -cp $JAR tlc2.TLC -workers auto \
  -config MCOutboundChannelFlush.cfg MCOutboundChannelFlush

# Stress safety (~10s, exhaustive)
java -XX:+UseParallelGC -cp $JAR tlc2.TLC -workers auto \
  -config MCOutboundChannelFlush_stress.cfg MCOutboundChannelFlush

# Liveness (~3s, exhaustive)
java -XX:+UseParallelGC -cp $JAR tlc2.TLC -workers auto \
  -config MCOutboundChannelFlush_liveness.cfg MCOutboundChannelFlush

# Simulation (~3 minutes)
java -XX:+UseParallelGC -cp $JAR tlc2.TLC -workers auto \
  -simulate num=200000 -depth 60 \
  -config MCOutboundChannelFlush_sim.cfg MCOutboundChannelFlush
```
