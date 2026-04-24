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
 * trace.  This is sufficient for the iteration 12 old-primary-rejoin
 * scenario: write(1), flush(2) with log GC, crash, rejoin via
 * InstallSnapshot, write(3).  It also covers the iteration 10 orphan
 * flush + re-flush scenario: write(1), flush(2) with crash at
 * RAFTProposed, new leader commits orphan marker(2), new leader
 * flush(3).  It also covers the iteration 13 new-member-bootstrap
 * scenario: write(1), flush(2), bootstrap new member via leader
 * AppendEntries + HFile load, write(3).  It also covers the
 * iteration 14 promotion + in-flight write scenario: write(1)
 * with crash after RAFTCommitWrite but before CompleteWrite,
 * new leader promotion with orphan apply, write(2), and the
 * orphan flush marker scenario: write(1), flush(2) with crash
 * at RAFTProposed, new leader promotion, orphan flush marker
 * commit with leader apply, write(3).  The per-member raftLog[m]
 * variable adds (2^3)^3 = 512 theoretical state combinations;
 * RaftLogGC reduces raftLog cardinality in later steps, partially
 * offsetting the state space growth from InstallSnapshot and
 * NewMemberBootstrap.  If exhaustive TLC time becomes prohibitive
 * (> 24 hours), consider falling back to a global
 * proposedFlushMarkers set.
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
