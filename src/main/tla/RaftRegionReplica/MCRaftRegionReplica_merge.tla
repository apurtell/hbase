---- MODULE MCRaftRegionReplica_merge ----
(*
 * Merge lifecycle domain model-checking configuration.
 *
 * PURPOSE
 * -------
 * Verify that the region merge protocol — each parent group's leader
 * proposes a merge marker through RAFT, members apply both committed
 * markers (closing both parents locally), master opens the merged
 * group on members that have applied both markers — correctly prevents
 * any period where both a parent group and the merged group are active
 * for the same key range.
 *
 * Uses the data-path-merged MergeDataPathNext (GroupDataPathNext for
 * per-group actions) for reduced state space.  All 14 per-group
 * safety invariants are checked per parent group alongside the
 * merge-specific NoKeyRangeOverlapMerge invariant.
 *
 * TIMING SIMPLIFICATION
 * ---------------------
 * Identical to MCRaftRegionReplica_multigroup: MaxClockDrift = 0,
 * ElectionTimeoutMin = 2, MaxClock = 2.
 *
 * STATE SPACE
 * -----------
 * MaxSeqId = 2 (same as multi-group): merge marker(1) + one data
 * entry(2) per group exercises the critical paths.  3 members with
 * symmetry.  Partitions limited to at most 1 link.
 *)
EXTENDS MergeRaftRegionReplica, TLC

CONSTANTS m1, m2, m3, NoVote

MC_Members == {m1, m2, m3}
MC_None == NoVote
MC_MaxTerm == 2
MC_LeaderLeaseDuration == 1
MC_ElectionTimeoutMin == 2
MC_MaxClockDrift == 0
MC_MaxClock == 2
MC_MaxSeqId == 2

Symmetry == Permutations(MC_Members)

PartitionConstraint == Cardinality(partition) <= 2

====
