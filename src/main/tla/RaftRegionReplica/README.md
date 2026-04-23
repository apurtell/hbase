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

`RaftRegionReplica.tla` is a TLA+ specification modeling RAFT-based region replicas for Apache HBase.  It captures leader election, lease management, clock drift, the write pipeline (WAL sync, RAFT commit, memstore apply), the flush protocol (HFile commit, RAFT-proposed flush markers), the promotion protocol with master confirmation (MasterConfirmPromotion action with term-fencing guard), RAFT log garbage collection, shared-storage catch-up, new member bootstrap, crash recovery, and network partitions (including individual link healing and full network recovery).

The base spec exports parameterized building-block operators (`GatedMemberActions(m)`, `GatedMemberDataPathActions(m)`) that collect all single-member normal-operation actions into a single disjunction. Composition modules (`SplitRaftRegionReplica`, `MergeRaftRegionReplica`, `MultiGroupRaftRegionReplica`) invoke these with per-member gating predicates to control when a RAFT group's operations are active on each member, enabling lifecycle transitions (split, merge) to deactivate a group per-member without duplicating action dispatch logic. Guard + effect factoring of `CrashRestart` and `ClockTick` allows multi-group and lifecycle modules to reuse crash/tick effects across groups without copy-paste.

The specification defines 14 safety invariants and 5 liveness properties verified by TLC:

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
| 9 | `FlushWriteExclusion` | Flush protocol |
| 10 | `FollowerFlushMemstoreDrop` | Flush protocol |
| 11 | `HFilesBeforeFlushMarker` | Flush protocol |
| 12 | `PromotionReadWriteGuard` | Promotion |
| 13 | `PromotionMVCCContinuity` | Promotion |
| 14 | `CatchUpCompleteness` | Catch-up |

The specification also defines 5 liveness properties checked under fairness constraints:

| # | Property | Fairness | Description |
|---|----------|----------|-------------|
| 1 | `ElectionProgress` | `LiveSpecElection` | If no leader exists, one is eventually elected |
| 2 | `WriteCompletion` | `LiveSpecWrite` | A pending write eventually returns to idle |
| 3 | `FlushCompletion` | `LiveSpecFlush` | A started flush eventually completes |
| 4 | `PromotionCompletion` | `LiveSpecLocal` | A promoting member eventually leaves Promoting (via master confirmation) and AwaitingMaster (via local completion) |
| 5 | `CatchUpCompletion` | `LiveSpecLocal` | A catching-up follower eventually finishes |

Properties 1-3 are network-dependent and require strong fairness (SF) on network recovery and RAFT communication actions.  Properties 4-5 are local and require only weak fairness (WF) on local actions — network instability cannot block them.

Fairness is factored into `BaseFairness` (WF for all 17 local actions, including `MasterConfirmPromotion`) plus per-property SF additions (`ElectionSF`, `WriteSF`, `FlushSF`).  Network recovery uses `SF_vars(HealAllPartitions)` — a deterministic action that forces full network recovery — rather than `SF_vars(HealPartition)`, because `HealPartition`'s internal `\E` nondeterminism allows TLC to always heal the same unhelpful link while leaving other members isolated.

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

This domain preserves full timing parameters (MaxClockDrift = 1, ElectionTimeoutMin = 4, MaxClock = 4) while reducing the data-path dimension to MaxSeqId = 1, allowing at most one write or flush per trace.  No actions are merged or removed; the original unmodified `Next` relation with all 31 actions is used.

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

Verify that the region split protocol preserves `NoKeyRangeOverlap` and all 14 parent-group safety invariants.  Uses `SplitRaftRegionReplica.tla`, which gates parent-group actions per-member after the split marker is applied.

```bash
java -XX:+UseParallelGC -cp tla2tools.jar \
  -Dtlc2.TLC.stopAfter=1800 \
  tlc2.TLC MCRaftRegionReplica_split -simulate -depth 150 -workers auto
```

### Step 7: Merge Lifecycle Simulation (30 min, local)

Verify that the region merge protocol preserves `NoKeyRangeOverlapMerge` and all 14 per-group safety invariants for both parent groups.  Uses `MergeRaftRegionReplica.tla`, which gates each parent group independently.

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
