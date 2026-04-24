---- MODULE MCRaftRegionReplica_datapath ----
(*
 * Data-path domain exhaustive model-checking configuration for
 * RaftRegionReplica.
 *
 * PURPOSE
 * -------
 * This module targets exhaustive verification of the data-path protocols:
 * write pipeline, flush protocol, RAFT log GC, shared-storage catch-up
 * (InstallSnapshot), and new member bootstrap.  It uses MaxSeqId = 3
 * (same as the full exhaustive config) to exercise multi-operation
 * interleavings — write(1) + flush(2) + write(3), orphan flush + re-flush,
 * log GC + catch-up, and bootstrap + post-bootstrap writes.
 *
 * TIMING SIMPLIFICATION
 * ---------------------
 * Clock drift is orthogonal to data-path protocol correctness.  The
 * data-path invariants (CatchUpDataIntegrity, NoOrphanMemstoreDrop,
 * FollowerFlushMemstoreDrop, etc.) depend on
 * action guards and seqId/memstore/raftLog state, not on relative
 * clock positions.  By setting MaxClockDrift = 0, all member clocks
 * advance in lockstep, collapsing ~125 independent per-member clock
 * positions to ~3 effective positions.  This enables:
 *
 *   MaxClockDrift = 0
 *   ElectionTimeoutMin = 2   (the lease inequality 1 < 2 - 0 holds)
 *   MaxClock = 2             (matches reduced timer range)
 *
 * Elections, heartbeats, lease expiry, and partitions still function
 * normally — only the drift dimension is removed.  The election domain
 * (MCRaftRegionReplica_election) provides full drift coverage.
 *
 * ACTION MERGES
 * -------------
 * Two pairs of actions are merged into atomic equivalents to eliminate
 * transient intermediate states that do not affect safety:
 *
 * 1. FollowerBatchApply (merges FollowerBeginBatchApply +
 *    FollowerCompleteBatchApply):  The intermediate state (fApplyBatch
 *    non-empty between begin and complete) represents a within-callback
 *    state on MicroRaft's single-threaded actor.  A JVM crash
 *    mid-callback produces the same post-crash state as crash-before-
 *    callback (CrashRestart resets both memstore and fApplyBatch to {}).
 *    The merged action computes the batch and applies it to memstore
 *    atomically; fApplyBatch remains permanently {}.
 *
 * 2. CompleteWriteAndAck (merges CompleteWrite + AckWrite):  The
 *    "Applied" writePhase is a transient state between "cells added
 *    to memstore" and "pipeline reset for next write."  The only action
 *    enabled in the Applied state is AckWrite (unconditional reset).
 *    No safety-relevant interleaving can occur.  The merged action
 *    atomically adds to memstore and resets the write pipeline.
 *
 * ACTION REMOVALS
 * ---------------
 * 1. ProposeMarker (compaction markers): compaction markers are a
 *    trivial subset of flush markers — atomic commit, no multi-step
 *    protocol, no HFile handling.  Every invariant exercised by
 *    compaction markers is also exercised by flush markers + mutations.
 *    The election domain retains ProposeMarker via the unmodified Next.
 *
 * 2. WALSyncFail + WALFailureAbort: the WAL failure path (sync fails ->
 *    leader crashes) produces the same post-crash state as CrashRestart
 *    during a pending write.  The election domain retains these actions
 *    via the unmodified Next.
 *
 * STATE CONSTRAINT
 * ----------------
 * Partitions are limited to at most 1 link (Cardinality(partition) <= 2).
 * With 3 members, this allows any single link to fail (leader loses one
 * follower but retains majority).  The dual-partition scenario (leader
 * isolated from both) is functionally equivalent to leader crash +
 * re-election, already covered by CrashRestart + Timeout.
 *
 * INVARIANT COVERAGE IN THIS DOMAIN
 * ----------------------------------
 * All 14 invariants are checked.  Coverage status:
 *
 *   Non-trivial (primary verification in this domain):
 *     CatchUpDataIntegrity, FollowerSeqIdConsistency,
 *     NoOrphanMemstoreDrop, FlushDropBoundary,
 *     FollowerFlushMemstoreDrop, HFilesBeforeFlushMarker,
 *     PromotionReadWriteGuard, PromotionMVCCContinuity,
 *     CatchUpCompleteness
 *
 *   Non-trivial (verified here and in election domain):
 *     LeaderUniqueness, LeaseImpliesLeadership, LeaseExpiresBeforeElection
 *     (zero-drift case only)
 *
 *   Vacuous in this domain (primary verification in election domain):
 *     WriteBarrierSafety     -- trivially true because Applied phase is
 *                               merged away (CompleteWriteAndAck skips it);
 *                               the barrier is still enforced by the merged
 *                               action's guard (walSync = Done /\
 *                               raftCommitted = TRUE)
 *
 * COMPLEMENTARY DOMAIN
 * --------------------
 * The election domain (MCRaftRegionReplica_election) uses the original
 * unmodified Next with all 35 actions, full timing (MaxClockDrift = 1,
 * ElectionTimeoutMin = 4), and MaxSeqId = 1.  It provides primary
 * verification for WriteBarrierSafety (which is vacuous here).
 * Simulation mode (MCRaftRegionReplica_sim) exercises both domains
 * simultaneously with MaxSeqId = 5 and full timing, providing
 * statistical coverage of the cross-product that neither exhaustive
 * domain covers alone.
 *)
EXTENDS RaftRegionReplica, TLC

CONSTANTS m1, m2, m3, NoVote

MC_Members == {m1, m2, m3}
MC_None == NoVote
MC_MaxTerm == 2
MC_LeaderLeaseDuration == 1
MC_ElectionTimeoutMin == 2
MC_MaxClockDrift == 0
MC_MaxClock == 2
MC_MaxSeqId == 3

Symmetry == Permutations(MC_Members)

\* State constraint: limit partitions to at most 1 link
PartitionConstraint == Cardinality(partition) <= 2

----
(* ---- Data-path next-state relation ---- *)

\* Uses GroupDataPathNext from the base spec (merged follower batch
\* apply, merged write completion, ProposeMarker/WALSyncFail/
\* WALFailureAbort removed) plus shared-impact actions.
DataPathNext ==
    \/ GroupDataPathNext
    \/ \E m \in Members : ClockTick(m)
    \/ \E m \in Members : CrashRestart(m)
    \/ CreatePartition
    \/ HealPartition
    \/ \E m \in Members : RaftLogGC(m)

DataPathSpec == Init /\ [][DataPathNext]_vars

====
