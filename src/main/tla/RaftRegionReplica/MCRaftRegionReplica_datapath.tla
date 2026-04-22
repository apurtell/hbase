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
 *     NoOrphanMemstoreDrop, FlushWriteExclusion,
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
(* ---- Merged actions ---- *)

\* Atomic follower batch apply: computes the mutation batch and applies
\* it to memstore in a single step.  Merges FollowerBeginBatchApply +
\* FollowerCompleteBatchApply.  The fApplyBatch variable is never modified
\* (remains {}).
FollowerBatchApply(m) ==
    /\ \/ role[m] = "Follower"
       \/ promotionPhase[m] = "Promoting"
    /\ fApplyBatch[m] = {}
    /\ LET applicable == ApplicableEntries(m)
       IN /\ applicable # {}
          /\ LET nextEntry == SetMin(applicable)
             IN /\ nextEntry \notin markerEntries
                /\ LET applicableMarkers == applicable \cap markerEntries
                       boundary == IF applicableMarkers # {}
                                   THEN SetMin(applicableMarkers)
                                   ELSE MaxSeqId + 1
                       batch == {s \in applicable \ markerEntries : s < boundary}
                   IN memstore' = [memstore EXCEPT ![m] = @ \union batch]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   clock, leaseRemaining, timerRemaining, partition,
                   nextSeqId, committedEntries, markerEntries,
                   flushMarkerEntries, hdfsHFiles, fApplyBatch,
                   writePhase, walSync, raftCommitted, writeSeqId,
                   flushPhase, flushSeqId, promotionPhase, hibernateState>>

\* Atomic write completion and acknowledgement: applies the write to
\* memstore and resets the write pipeline in a single step.  Merges
\* CompleteWrite + AckWrite, skipping the transient "Applied" phase.
CompleteWriteAndAck(m) ==
    /\ writePhase[m] = "Pending"
    /\ walSync[m] = "Done"
    /\ raftCommitted[m]
    /\ role[m] = "Leader"
    /\ writePhase'    = [writePhase    EXCEPT ![m] = "Idle"]
    /\ walSync'       = [walSync       EXCEPT ![m] = "Pending"]
    /\ raftCommitted' = [raftCommitted EXCEPT ![m] = FALSE]
    /\ writeSeqId'    = [writeSeqId    EXCEPT ![m] = 0]
    /\ memstore'      = [memstore EXCEPT ![m] = @ \union {writeSeqId[m]}]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   clock, leaseRemaining, timerRemaining, partition,
                   nextSeqId, committedEntries, markerEntries,
                   flushMarkerEntries, hdfsHFiles, fApplyBatch,
                   flushPhase, flushSeqId, promotionPhase, hibernateState>>

----
(* ---- Data-path next-state relation ---- *)

DataPathNext ==
    \* ---- Election (unchanged from base spec) ----
    \/ \E m \in Members     : Timeout(m)
    \/ \E c, v \in Members  : RequestVote(c, v)
    \/ \E m \in Members     : BecomeLeader(m)
    \* ---- Leadership (unchanged) ----
    \/ \E m \in Members     : Heartbeat(m)
    \/ \E m \in Members     : StepDown(m)
    \/ \E m \in Members     : LeaderLeaseExpiry(m)
    \* ---- Timing (unchanged) ----
    \/ \E m \in Members     : ClockTick(m)
    \* ---- Crash recovery (unchanged) ----
    \/ \E m \in Members     : CrashRestart(m)
    \* ---- Network (unchanged) ----
    \/ CreatePartition
    \/ HealPartition
    \* ---- Write path (WALSyncFail/WALFailureAbort removed,
    \*       CompleteWrite+AckWrite merged into CompleteWriteAndAck) ----
    \/ \E m \in Members     : BeginWrite(m)
    \/ \E m \in Members     : WALSyncComplete(m)
    \/ \E m \in Members     : RAFTCommitWrite(m)
    \/ \E m \in Members     : CompleteWriteAndAck(m)
    \* ---- Markers (ProposeMarker removed — compaction markers are a
    \*       trivial subset of flush markers; election domain retains it) ----
    \* ---- Flush protocol (unchanged) ----
    \/ \E m \in Members     : FlushStart(m)
    \/ \E m \in Members     : FlushCommitHFiles(m)
    \/ \E m \in Members     : FlushRAFTPropose(m)
    \/ \E m \in Members     : FlushRAFTCommit(m)
    \/ \E m \in Members     : FlushComplete(m)
    \* ---- Follower apply (FollowerBeginBatchApply+FollowerCompleteBatchApply
    \*       merged into atomic FollowerBatchApply) ----
    \/ \E m \in Members     : FollowerBatchApply(m)
    \/ \E m \in Members     : FollowerApplyMarker(m)
    \* ---- Promotion (unchanged) ----
    \/ \E m \in Members     : PromotionComplete(m)
    \* ---- Orphan commitment (unchanged) ----
    \/ NewLeaderCommitOrphanEntry
    \* ---- RAFT log GC and catch-up (unchanged) ----
    \/ \E m \in Members     : RaftLogGC(m)
    \/ \E l, f \in Members  : InstallSnapshot(l, f)
    \* ---- New member bootstrap (unchanged) ----
    \/ \E m \in Members     : NewMemberBootstrap(m)
    \* ---- Hibernate lifecycle (unchanged) ----
    \/ \E m \in Members     : HibernateRequest(m)
    \/ \E m \in Members     : WakeGroup(m)
    \/ \E m \in Members     : WakeComplete(m)

DataPathSpec == Init /\ [][DataPathNext]_vars

====
