---- MODULE MCRaftRegionReplica_split ----
(*
 * Split lifecycle domain model-checking configuration.
 *
 * PURPOSE
 * -------
 * Verify that the region split protocol — leader proposes a split
 * marker through RAFT, members apply the committed marker (closing
 * the parent group locally), master opens daughters on members that
 * have applied the marker — correctly prevents any period where both
 * parent and daughter groups are active for the same key range.
 *
 * Uses the data-path-merged SplitDataPathNext (GroupDataPathNext for
 * parent group actions) for reduced state space.  All 14 parent-group
 * safety invariants are checked alongside the split-specific
 * NoKeyRangeOverlap invariant.
 *
 * TIMING SIMPLIFICATION
 * ---------------------
 * MaxClockDrift = 0, ElectionTimeoutMin = 2, MaxClock = 2.
 * Clock drift is orthogonal to split lifecycle correctness.
 *
 * STATE SPACE
 * -----------
 * MaxSeqId = 2: write(1) + split marker(2), or flush(1) + split(2),
 * exercises the critical paths.  3 members with symmetry.
 * Partitions limited to at most 1 link.
 *)
EXTENDS SplitRaftRegionReplica, MCRaftRegionReplica_base

MC_MaxTerm == 2
MC_ElectionTimeoutMin == 2
MC_MaxClockDrift == 0
MC_MaxClock == 2
MC_MaxSeqId == 2

PartitionConstraint == Cardinality(partition) <= 2

====
