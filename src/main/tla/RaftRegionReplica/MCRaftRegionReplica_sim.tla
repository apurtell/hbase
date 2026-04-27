---- MODULE MCRaftRegionReplica_sim ----
(*
 * Simulation configuration for RaftRegionReplica.
 * Designed for daily TLC -simulate runs with a larger state space
 * than the exhaustive configuration (MCRaftRegionReplica).
 *
 * MaxSeqId = 5 enables richer scenarios.
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
EXTENDS RaftRegionReplica, MCRaftRegionReplica_base

MC_MaxTerm == 2
MC_ElectionTimeoutMin == 4
MC_MaxClockDrift == 1
MC_MaxClock == 4
MC_MaxSeqId == 5

====
