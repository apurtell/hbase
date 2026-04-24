---- MODULE MCRaftRegionReplica_liveness_election ----
(*
 * Model-checking configuration tuned for ElectionProgress liveness.
 *
 * Tuned for ElectionProgress: the property needs enough term and
 * clock budget for elections to succeed despite nondeterministic
 * crashes and partitions (which have no fairness and can waste
 * terms and clock ticks via timer resets).
 *
 * MaxTerm = 4: enough headroom for multiple failed elections.
 * ElectionTimeoutMin = 2: each election countdown costs 2 clock
 *   ticks instead of 4, conserving clock budget (safety constraint
 *   LeaderLeaseDuration=1 < ElectionTimeoutMin=2 still holds).
 * MaxClock = 12: allows 12/2 = 6 timer countdowns per member.
 * MaxSeqId = 1: ElectionProgress doesn't exercise writes/flushes.
 *
 * No Symmetry (incompatible with liveness checking in TLC).
 *)
EXTENDS RaftRegionReplica, MCRaftRegionReplica_base

MC_MaxTerm == 4
MC_ElectionTimeoutMin == 2
MC_MaxClockDrift == 1
MC_MaxClock == 12
MC_MaxSeqId == 1

====
