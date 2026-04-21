# RaftRegionReplica TLC Model Checking

## Overview

`RaftRegionReplica.tla` is a TLA+ specification modeling RAFT-based region
replicas for Apache HBase.  It captures leader election, lease management,
clock drift, the write pipeline (WAL sync, RAFT commit, memstore apply),
the flush protocol (HFile commit, RAFT-proposed flush markers), RAFT log
garbage collection, shared-storage catch-up (InstallSnapshot), new member
bootstrap, crash recovery, and network partitions.

The specification defines 18 safety invariants verified by TLC:

| # | Invariant | Category |
|---|-----------|----------|
| 1 | `TypeOK` | Type correctness |
| 2 | `LeaderUniqueness` | RAFT consensus |
| 3 | `LeaseImpliesLeadership` | RAFT consensus |
| 4 | `LeaseExpiresBeforeElection` | Timing / lease |
| 5 | `RaftLogConsistency` | RAFT consensus |
| 6 | `ClockDriftBounded` | Timing |
| 7 | `PartitionSymmetric` | Network |
| 8 | `WriteBarrierSafety` | Write path |
| 9 | `WALSyncFailureSafety` | Write path |
| 10 | `FollowerSeqIdConsistency` | Write path |
| 11 | `MarkerBreaksBatch` | Follower apply |
| 12 | `FlushAtomicity` | Flush protocol |
| 13 | `NoOrphanMemstoreDrop` | Flush protocol |
| 14 | `FlushWriteExclusion` | Flush protocol |
| 15 | `FollowerFlushMemstoreDrop` | Flush protocol |
| 16 | `HFilesBeforeFlushMarker` | Flush protocol |
| 17 | `NoFlushDuplication` | Flush protocol |
| 18 | `PromotionReadWriteGuard` | Promotion |
| 19 | `CatchUpDataIntegrity` | Log GC / catch-up |

See `RAFT_REGION_REPLICAS.md` Appendix B for the design rationale behind
each property.

## Verification Strategy

The model checking suite uses three verification layers that compose to
provide complete coverage:

### Layer 1: Simulation (`MCRaftRegionReplica_sim`)

Exercises **both timing and data-path domains simultaneously** with the
full unmodified spec, full timing (MaxClockDrift=1), deep data path
(MaxSeqId=5), and all 31 actions.  Provides high-confidence statistical
coverage of the cross-product of timing and data-path states.

Used in two modes:

- **Dev inner loop (15–30 min):** fast feedback during spec development.
  Counterexamples surface within minutes.  This is the recommended first
  step after any spec change.

- **Daily deep run (8 hours):** supplements exhaustive checking by
  exercising longer traces and richer interleavings at MaxSeqId=5, which
  is deeper than any exhaustive config.  Run as overnight CI or
  buildbox.aws cron.

**Simulation is critical** because it is the ONLY configuration that
exercises both domains simultaneously at full parameter ranges.  The
domain-decomposed exhaustive configs provide mathematical proofs within
their respective parameter slices, but they do not cover the full
cross-product.  Simulation compensates by statistically exploring the
cross-product at even deeper parameter ranges.

### Layer 2: Domain-Decomposed Exhaustive (datapath + election)

Splits the exhaustive state space into two domains that can run
concurrently, completing in hours instead of days.  Each domain provides
a **mathematical proof** (not statistical) that no invariant violation
exists within its parameter range.  Together they cover all 18 invariants
non-trivially.

### Layer 3: Full Exhaustive (`MCRaftRegionReplica`)

Proves absence of violations across the complete cross-product at
MaxSeqId=3 and full timing.  Takes days on a large machine.  Run for
pre-release gates or after major spec changes when a complete proof over
the cross-product is required.

## Model Checking Configurations

| Config | Module | MaxSeqId | Timing | Actions | Mode | Expected Runtime |
|--------|--------|----------|--------|---------|------|------------------|
| Simulation (dev) | `MCRaftRegionReplica_sim` | 5 | Full (drift=1) | All 30 | `-simulate` 15–30 min | 15–30 min |
| Simulation (daily) | `MCRaftRegionReplica_sim` | 5 | Full (drift=1) | All 30 | `-simulate` 8 hours | 8 hours |
| Datapath exhaustive | `MCRaftRegionReplica_datapath` | 3 | Simplified (drift=0) | 26 (4 removed, 2 merged) | BFS | 1–8 hours |
| Election exhaustive | `MCRaftRegionReplica_election` | 1 | Full (drift=1) | All 30 | BFS | Minutes |
| Full exhaustive | `MCRaftRegionReplica` | 3 | Full (drift=1) | All 30 | BFS | Days |

## Two-Domain Decomposition

### Rationale

The full exhaustive config explores the cross-product of two largely
orthogonal state dimensions:

- **Timing/election:** clock positions, timer countdowns, lease
  remaining, clock drift, term transitions, vote grants
- **Data path:** seqId values, memstore contents, raftLog contents,
  HFiles, flush phases, write phases, WAL sync state

Timing invariants (`ClockDriftBounded`, `LeaseExpiresBeforeElection`)
depend on clock/timer/lease state but not on memstore/seqId contents.
Data-path invariants (`FlushAtomicity`, `RaftLogConsistency`,
`CatchUpDataIntegrity`) depend on seqId/memstore/raftLog but not on
clock drift.  By splitting the exhaustive check into two domain-focused
configs, each pays the state space cost of only its domain, avoiding the
multiplicative cross-product.

### Why Not a True Spec Split?

A coupling analysis of the 30 actions reveals 8 bidirectionally-coupled
actions that read and write variables from both domains (e.g.,
`BecomeLeader` resets write pipeline state AND sets lease; `CrashRestart`
resets both timing and data state).  Splitting the spec into two
independent TLA+ modules would break the atomicity of these coupled
actions and lose the ability to verify invariants that span both domains
(e.g., `LeaseImpliesLeadership` relates lease state to role, which is
modified by data-path actions like `StepDown`).

The MC-module approach preserves atomicity: both domain configs extend
the same `RaftRegionReplica.tla` and use its original operators.  The
datapath config defines merged action variants and a restricted
`DataPathNext`; the election config uses the original `Next` unchanged.

### Datapath Domain (`MCRaftRegionReplica_datapath`)

**Constants:**
- MaxSeqId = 3 (unchanged — full data-path depth)
- MaxClockDrift = 0 (clocks advance in lockstep)
- ElectionTimeoutMin = 2 (lease inequality 1 < 2 - 0 holds)
- MaxClock = 2 (matches reduced timer range)

**Action modifications:**
- `FollowerBatchApply` — merges `FollowerBeginBatchApply` +
  `FollowerCompleteBatchApply`.  The intermediate state (fApplyBatch
  non-empty) represents a within-callback state on MicroRaft's
  single-threaded actor; crash mid-callback is indistinguishable from
  crash before callback in all post-crash states.
- `CompleteWriteAndAck` — merges `CompleteWrite` + `AckWrite`.  The
  "Applied" writePhase is a transient state with only one enabled
  successor (AckWrite, unconditional).
- `ProposeMarker` removed — compaction markers are a trivial subset of
  flush markers.
- `WALSyncFail` + `WALFailureAbort` removed — WAL failure path produces
  the same post-crash state as `CrashRestart` during pending write.

**Partition constraint:** `Cardinality(partition) <= 2` (at most 1 link
failure).

**Estimated state reduction:** ~100–500x vs. full exhaustive.

### Election Domain (`MCRaftRegionReplica_election`)

**Constants:**
- MaxSeqId = 1 (one write or flush per trace)
- MaxClockDrift = 1, ElectionTimeoutMin = 4, MaxClock = 4 (full timing)

**Action modifications:** None.  Uses the original unmodified `Next` with
all 31 actions.

**Estimated state reduction:** ~1000x+ vs. full exhaustive (from MaxSeqId
reduction alone).

### How Simulation Bridges the Gap

Neither exhaustive domain covers the full cross-product of timing and
data-path states.  Simulation mode bridges this gap:

- Uses the full unmodified `Next` with all 31 actions
- MaxSeqId = 5 (deeper than any exhaustive config)
- Full timing (MaxClockDrift = 1)
- Random trace exploration covers the cross-product statistically
- 15–30 min dev runs catch regressions quickly
- 8-hour daily runs provide deep statistical confidence

For the dev inner loop, simulation is the primary verification tool:
make a spec change, run simulation for 15–30 minutes, iterate.  The
domain-decomposed exhaustive configs are run on buildbox.aws for
proof-level confidence, and daily simulation provides ongoing regression
coverage of the full cross-product.

## Invariant Coverage Matrix

Every invariant is non-trivially exercised in at least one exhaustive
domain AND in simulation (which tests both domains together).

| Invariant | Simulation | Datapath Exhaustive | Election Exhaustive |
|-----------|------------|---------------------|---------------------|
| `TypeOK` | Non-trivial | Non-trivial | Non-trivial |
| `LeaderUniqueness` | Non-trivial | Non-trivial | Non-trivial |
| `LeaseImpliesLeadership` | Non-trivial | Non-trivial | Non-trivial |
| `LeaseExpiresBeforeElection` | Non-trivial | Non-trivial (zero-drift) | **Non-trivial (drift)** |
| `RaftLogConsistency` | Non-trivial | **Non-trivial (deep)** | Non-trivial |
| `ClockDriftBounded` | Non-trivial | Vacuous (drift=0) | **Non-trivial** |
| `PartitionSymmetric` | Non-trivial | Non-trivial | Non-trivial |
| `WriteBarrierSafety` | Non-trivial | Vacuous (Applied merged) | **Non-trivial** |
| `WALSyncFailureSafety` | Non-trivial | Vacuous (WALSyncFail removed) | **Non-trivial** |
| `FollowerSeqIdConsistency` | Non-trivial | **Non-trivial (deep)** | Non-trivial |
| `MarkerBreaksBatch` | Non-trivial | Vacuous (batch merged) | **Non-trivial** |
| `FlushAtomicity` | Non-trivial | **Non-trivial (deep)** | Non-trivial |
| `NoOrphanMemstoreDrop` | Non-trivial | **Non-trivial (deep)** | Non-trivial |
| `FlushWriteExclusion` | Non-trivial | **Non-trivial (deep)** | Shallow (MaxSeqId=1) |
| `FollowerFlushMemstoreDrop` | Non-trivial | **Non-trivial (deep)** | Non-trivial |
| `HFilesBeforeFlushMarker` | Non-trivial | **Non-trivial (deep)** | Non-trivial |
| `NoFlushDuplication` | Non-trivial | **Non-trivial (deep)** | Trivial (MaxSeqId=1) |
| `PromotionReadWriteGuard` | Non-trivial | Non-trivial | Non-trivial |
| `CatchUpDataIntegrity` | Non-trivial | **Non-trivial (deep)** | Non-trivial |

**Bold** indicates the domain providing primary (deepest) verification.

**Completeness argument:** No invariant is vacuous in both exhaustive
domains.  Every invariant that is vacuous or trivially satisfied in the
datapath domain is non-trivially exercised in the election domain (which
uses the full unmodified `Next`).  Every invariant that has limited
coverage in the election domain (due to MaxSeqId=1) receives deep
coverage in the datapath domain (MaxSeqId=3).  Simulation exercises all
invariants non-trivially with both domains active simultaneously.

## Combined Validation Protocol

Follow these steps to fully validate the design:

### Step 1: Simulation Quick-Check (15–30 min)

Run simulation to confirm no obvious violations.  This exercises both
domains together and catches regressions quickly.

```bash
java -XX:+UseParallelGC -Xmx16g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -simulate -depth 120 -workers auto MCRaftRegionReplica_sim
```

### Step 2: Datapath Exhaustive (1–8 hours)

Verify data-path protocol correctness with BFS proof.

```bash
java -XX:+UseParallelGC -Xmx64g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto -checkpoint 60 MCRaftRegionReplica_datapath
```

### Step 3: Election Exhaustive (minutes)

Verify timing/drift safety with BFS proof.  Can run concurrently with
Step 2.

```bash
java -XX:+UseParallelGC -Xmx16g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto MCRaftRegionReplica_election
```

### Step 4: Interpret Results

All three checks (simulation + both exhaustive domains) must complete
with **0 invariant violations** for the design to be considered
validated.  Cross-reference the invariant coverage matrix to confirm
every invariant received non-trivial verification in at least one
exhaustive domain.

### Step 5: Daily Deep Simulation (8 hours, ongoing)

Run as overnight CI or buildbox.aws cron for ongoing regression coverage.

```bash
java -XX:+UseParallelGC -Xmx16g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -Dtlc2.TLC.stopAfter=28800 \
  -cp tla2tools.jar tlc2.TLC \
  -simulate -depth 120 -workers auto MCRaftRegionReplica_sim
```

### Step 6: Full Exhaustive (optional, days)

Run for pre-release gates or after major spec changes when a complete
proof over the full cross-product is required.

```bash
java -XX:+UseParallelGC -Xmx256g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto -checkpoint 60 MCRaftRegionReplica
```

## Running TLC

### Local (laptop)

For simulation (dev inner loop):
```bash
java -XX:+UseParallelGC -Xmx8g \
  -cp tla2tools.jar tlc2.TLC \
  -simulate -depth 120 -workers auto MCRaftRegionReplica_sim
```

For election exhaustive (fast enough for local):
```bash
java -XX:+UseParallelGC -Xmx8g \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto MCRaftRegionReplica_election
```

### Remote (buildbox.aws)

For datapath exhaustive:
```bash
java -XX:+UseParallelGC -Xmx64g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers auto -checkpoint 60 MCRaftRegionReplica_datapath
```

For full exhaustive:
```bash
java -XX:+UseParallelGC -Xmx256g \
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
  -cp tla2tools.jar tlc2.TLC \
  -workers 128 -checkpoint 60 MCRaftRegionReplica
```

### JVM Flags

| Flag | Purpose |
|------|---------|
| `-XX:+UseParallelGC` | Best GC for TLC's allocation pattern |
| `-Xmx<size>` | Heap for the fingerprint set; size depends on state space |
| `-Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet` | Off-heap fingerprint storage for large state spaces |
| `-Dtlc2.TLC.stopAfter=<seconds>` | Time-bounded simulation runs |
| `-checkpoint <minutes>` | Checkpoint interval for resumable exhaustive runs |

## Maintaining the Configs

When modifying `RaftRegionReplica.tla`, keep the model checking suite
consistent:

1. **New action added:** Add it to `DataPathNext` in
   `MCRaftRegionReplica_datapath.tla` unless it falls into one of the
   removed categories (WAL failure path or compaction markers).

2. **New invariant added:** Add it to ALL `.cfg` files (`_datapath.cfg`,
   `_election.cfg`, `_sim.cfg`, and the base `.cfg`).  Update the
   invariant coverage matrix in this README.

3. **Constants changed:** Review both domain configs for consistency.
   The datapath config must satisfy `LeaderLeaseDuration <
   ElectionTimeoutMin - 2 * MaxClockDrift`.

4. **Simulation config:** Always uses the unmodified `Next` and `Spec`.
   No maintenance needed beyond updating the invariant list in the
   `.cfg` file.
