---- MODULE MCRaftRegionReplica ----
(*
 * Model-checking configuration for RaftRegionReplica.
 * Defines concrete constant values for TLC verification.
 *
 * Timing parameters satisfy the safety condition for lease/election:
 *   LeaderLeaseDuration (1) < ElectionTimeoutMin (4) - 2 * MaxClockDrift (1) = 2
 *
 * Network partitions are modeled as nondeterministic symmetric link
 * failures between member pairs, with nondeterministic heal.  With 3
 * members there are 3 possible link-level partitions, giving 8
 * reachable partition configurations (2^3 symmetric subsets).
 *
 * MaxTerm = 2 provides two term transitions (initial -> first election
 * -> re-election after partition/crash), sufficient for term-fencing,
 * lease-expiry, and step-down verification.
 *
 * MaxClock = 4 provides enough ticks for a full election cycle (timer=4)
 * plus lease duration (1) with room for clock drift scenarios.  The
 * countdown representation of timers/leases already collapses states
 * that differ only in absolute clock position.
 *
 * MaxSeqId = 3 bounds the total number of writes + markers across the
 * trace.
 *
 * For deeper coverage (MaxSeqId=5), use the simulation configuration
 * MCRaftRegionReplica_sim, which runs TLC in -simulate mode with no
 * symmetry reduction.
 *)
EXTENDS RaftRegionReplica, MCRaftRegionReplica_base

MC_MaxTerm == 2
MC_ElectionTimeoutMin == 4
MC_MaxClockDrift == 1
MC_MaxClock == 4
MC_MaxSeqId == 3

====
