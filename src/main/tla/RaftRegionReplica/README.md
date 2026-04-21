# RaftRegionReplica TLC Model Checking

## Overview

`RaftRegionReplica.tla` is a TLA+ specification modeling RAFT-based region replicas for Apache HBase.  It captures leader election, lease management, clock drift, the write pipeline (WAL sync, RAFT commit, memstore apply), the flush protocol (HFile commit, RAFT-proposed flush markers), RAFT log garbage collection, shared-storage catch-up, new member bootstrap, crash recovery, network partitions, and the hibernate/wake lifecycle with wake-race safety.

The specification defines 14 safety invariants verified by TLC:

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

## Verification Strategy

The model checking suite uses three verification layers that compose to provide complete coverage:

## Model Checking Configurations

| Config | Module | MaxSeqId | Timing | Actions | Mode | Expected Runtime |
|--------|--------|----------|--------|---------|------|------------------|
| Simulation (dev) | `MCRaftRegionReplica_sim` | 5 | Full (drift=1) | All 34 | `-simulate` 15 min | 15 min |
| Simulation (daily) | `MCRaftRegionReplica_sim` | 5 | Full (drift=1) | All 34 | `-simulate` 8 hours | 8 hours |
| Datapath exhaustive | `MCRaftRegionReplica_datapath` | 3 | Simplified (drift=0) | 29 | BFS | ~10 min |
| Election exhaustive | `MCRaftRegionReplica_election` | 1 | Full (drift=1) | All 34 | BFS | ~45 min |
| Full exhaustive | `MCRaftRegionReplica` | 3 | Full (drift=1) | All 34 | BFS | Days |

### Simulation

Exercises both timing and data-path domains simultaneously with the full unmodified spec, full timing (MaxClockDrift=1), deep data path (MaxSeqId=5), and all 34 actions.  Provides high-confidence statistical coverage of the cross-product of timing and data-path states.

Simulation is critical because it is the ONLY configuration that exercises both domains simultaneously at full parameter ranges.  The domain-decomposed exhaustive configs provide mathematical proofs within their respective parameter slices, but they do not cover the full cross-product.  Simulation compensates by statistically exploring the cross-product at even deeper parameter ranges.

### Domain-Decomposed Exhaustive (datapath + election)

Splits the exhaustive state space into two domains that can run concurrently, completing in hours instead of days.  Each domain provides a mathematical proof hat no invariant violation exists within its parameter range.  Together they cover all 14 invariants non-trivially.

#### Datapath Domain (`MCRaftRegionReplica_datapath`)

This domain retains full data-path depth (MaxSeqId = 3) while collapsing the timing dimension: MaxClockDrift = 0 forces all member clocks to advance in lockstep, and ElectionTimeoutMin and MaxClock are reduced to 2 accordingly (the lease inequality 1 < 2 - 0 still holds). Partitions are constrained to at most one link failure.

#### Election Domain (`MCRaftRegionReplica_election`)

This domain preserves full timing parameters (MaxClockDrift = 1, ElectionTimeoutMin = 4, MaxClock = 4) while reducing the data-path dimension to MaxSeqId = 1, allowing at most one write or flush per trace.  No actions are merged or removed; the original unmodified `Next` relation with all 34 actions is used.

### Full Exhaustive

Proves absence of violations across the complete cross-product at MaxSeqId=3 and full timing.  Takes days on a large machine.  Run for pre-release gates or after major spec changes when a complete proof over the cross-product is required.

## Latest Verification Results

All configurations pass with no invariant violations (128 cores):

| Config | States Generated | Distinct States | Depth | Runtime |
|--------|-----------------|-----------------|-------|---------|
| Simulation (15 min) | 1,245,366,092 | — | 120 (8.6M traces) | 15 min |
| Datapath exhaustive | 628,489,645 | 35,054,293 | 48 | 9 min |
| Election exhaustive | 4,494,574,695 | 206,623,962 | 47 | 43 min |

## Validation

Follow these steps to fully validate the design:

### Step 1: Simulation Quick-Check (15–30 min)

Run simulation to confirm no obvious violations.  This exercises both domains together and catches regressions quickly.

```bash
java -XX:+UseParallelGC -Dtlc2.TLC.stopAfter=900 \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -simulate -depth 120 -workers auto MCRaftRegionReplica_sim
```

### Step 2: Datapath Exhaustive (1–8 hours)

Verify data-path protocol correctness with BFS proof.

```bash
java -XX:+UseParallelGC \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto -checkpoint 60 MCRaftRegionReplica_datapath
```

### Step 3: Election Exhaustive (minutes)

Verify timing/drift safety with BFS proof.  Can run concurrently with Step 2.

```bash
java -XX:+UseParallelGC \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto MCRaftRegionReplica_election
```

### Step 4: Interpret Results

All three checks (simulation + both exhaustive domains) must complete with no invariant violations for the design to be considered validated.  Cross-reference the invariant coverage matrix to confirm every invariant received non-trivial verification in at least one exhaustive domain.

### Step 5: Daily Deep Simulation (8 hours, ongoing)

Run overnight for ongoing regression coverage.

```bash
java -XX:+UseParallelGC \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -Dtlc2.TLC.stopAfter=28800 \
  -cp tla2tools.jar tlc2.TLC \
  -simulate -depth 120 -workers auto MCRaftRegionReplica_sim
```

## Running TLC

For simulation (dev inner loop):
```bash
java -XX:+UseParallelGC \
  -cp tla2tools.jar tlc2.TLC \
  -simulate -depth 120 -workers auto MCRaftRegionReplica_sim
```

For election exhaustive (fast enough for local):
```bash
java -XX:+UseParallelGC \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto MCRaftRegionReplica_election
```

For datapath exhaustive:
```bash
java -XX:+UseParallelGC \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto -checkpoint 60 MCRaftRegionReplica_datapath
```

For full exhaustive:
```bash
java -XX:+UseParallelGC \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers 128 -checkpoint 60 MCRaftRegionReplica
```
