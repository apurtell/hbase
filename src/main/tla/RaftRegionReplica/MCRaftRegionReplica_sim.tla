---- MODULE MCRaftRegionReplica_sim ----
(*
 * Simulation configuration for RaftRegionReplica.
 * Designed for daily TLC -simulate runs with a larger state space
 * than the exhaustive configuration (MCRaftRegionReplica).
 *
 * MaxSeqId = 5 enables richer scenarios than exhaustive (MaxSeqId=3):
 *   - write(1), write(2), flush(3), orphan commit, write(4), new-flush(5)
 *   - write(1), flush(2), crash, orphan commit, write(3), write(4), flush(5)
 *   - write(1), flush(2), log GC, crash, rejoin via InstallSnapshot,
 *     write(3), write(4), flush(5) (iteration 12 catch-up path)
 *   - write(1), flush(2), bootstrap new member via leader AppendEntries
 *     + HFile load, write(3), write(4), flush(5) (iteration 13 bootstrap)
 *   - write(1) with crash after RAFTCommitWrite, new leader promotion
 *     with orphan apply, write(2), write(3), flush(4), write(5)
 *     (iteration 14 promotion + in-flight write)
 *   - write(1), flush(2) crash at RAFTProposed, new leader promotion,
 *     orphan flush commit with leader apply, write(3), write(4),
 *     flush(5) (iteration 14 orphan flush marker + promotion)
 * These exercise multiple writes interleaved with orphan flush commitment,
 * new-leader flush, RAFT log GC + shared-storage catch-up, new member
 * bootstrap, and promotion MVCC continuity with in-flight writes,
 * providing deeper coverage of NoFlushDuplication, RaftLogConsistency,
 * CatchUpDataIntegrity, FollowerFlushMemstoreDrop, and
 * PromotionMVCCContinuity interactions.
 *
 * No symmetry reduction: TLC simulation mode does not benefit
 * from symmetry and can produce spurious counterexamples with it.
 *
 * All other parameters match the exhaustive configuration.
 * See MCRaftRegionReplica.tla for parameter rationale.
 *
 * Depth 120 is chosen to balance trace length against coverage breadth
 * in time-bounded runs.  The deepest interesting scenario (5-seqId
 * orphan-flush + re-flush with two election cycles) completes in ~80
 * steps; 120 provides ~40 steps of headroom for partition and crash
 * interleavings without spending half the trace on post-data election
 * cycling (as depth 200 did).  In a 15-minute run this yields ~60%
 * more traces than depth 200.
 *
 * Invocation:
 *   java -XX:+UseParallelGC -Xmx16g \
 *     -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
 *     -Dtlc2.TLC.stopAfter=28800 \
 *     -cp tla2tools.jar tlc2.TLC \
 *     -simulate -depth 120 -workers auto MCRaftRegionReplica_sim
 *)
EXTENDS RaftRegionReplica, TLC

CONSTANTS m1, m2, m3, NoVote

MC_Members == {m1, m2, m3}
MC_None == NoVote
MC_MaxTerm == 2
MC_LeaderLeaseDuration == 1
MC_ElectionTimeoutMin == 4
MC_MaxClockDrift == 1
MC_MaxClock == 4
MC_MaxSeqId == 5

====
