---- MODULE MCRaftRegionReplica_liveness ----
(*
 * Model-checking configuration for RaftRegionReplica liveness properties.
 * Defines concrete constant values for TLC liveness verification.
 *
 * Key differences from the safety exhaustive configuration
 * (MCRaftRegionReplica):
 *
 *   - MaxSeqId = 2 (reduced from 3).  Without SYMMETRY the state
 *     space is ~6x larger per state variable, so a smaller seqId
 *     bound is needed to keep exhaustive checking tractable.  Two
 *     seqIds are sufficient for write + flush + follower-apply.
 *
 *   - No Symmetry definition.  TLC's liveness checking is
 *     incompatible with symmetry reduction (the behavior graph
 *     cross-product with the tableau requires distinguishing
 *     states that symmetry would collapse).
 *
 * All other parameters match the safety exhaustive configuration.
 * See MCRaftRegionReplica.tla for parameter rationale.
 *
 * Invocation (exhaustive, single property):
 *   java -XX:+UseParallelGC -Xmx32g \
 *     -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet \
 *     -cp tla2tools.jar tlc2.TLC \
 *     MCRaftRegionReplica_liveness -workers auto \
 *     -config MCRaftRegionReplica_liveness_election.cfg
 *
 * Invocation (simulation, all properties):
 *   java -XX:+UseParallelGC -Xmx16g -cp tla2tools.jar \
 *     -Dtlc2.TLC.stopAfter=900 \
 *     tlc2.TLC MCRaftRegionReplica_liveness \
 *     -simulate -depth 200 -workers auto \
 *     -config MCRaftRegionReplica_liveness_all.cfg
 *)
EXTENDS RaftRegionReplica, MCRaftRegionReplica_base

MC_MaxTerm == 2
MC_ElectionTimeoutMin == 4
MC_MaxClockDrift == 1
MC_MaxClock == 4
MC_MaxSeqId == 2

====
