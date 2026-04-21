---- MODULE MCRaftRegionReplica_election ----
(*
 * Election/timing domain exhaustive model-checking configuration for
 * RaftRegionReplica.
 *
 * PURPOSE
 * -------
 * This module targets exhaustive verification of election safety, lease
 * exclusivity under worst-case clock drift, term fencing, and timer/
 * heartbeat protocols.  It uses the ORIGINAL UNMODIFIED Next relation
 * with all 34 actions — no action merges, no action removals.  The only
 * change from the full exhaustive config (MCRaftRegionReplica) is
 * reducing MaxSeqId from 3 to 1.
 *
 * DATA-PATH SIMPLIFICATION
 * ------------------------
 * With MaxSeqId = 1, each set-typed data variable (committedEntries,
 * raftLog[m], memstore[m], fApplyBatch[m], hdfsHFiles) has only 2
 * possible values: {} or {1}.  This collapses the data-path state
 * space by orders of magnitude while preserving the full timing model
 * (clock drift, timer countdown, lease expiry, heartbeat intervals).
 *
 * MaxSeqId = 1 still allows one write or one flush per trace, which is
 * sufficient to exercise every data-path action at least once and
 * verify their interaction with the timing/election machinery.
 *
 * TIMING PARAMETERS (unchanged from full exhaustive)
 * --------------------------------------------------
 *   MaxClockDrift = 1        -- worst-case drift scenarios
 *   ElectionTimeoutMin = 4   -- full timer range
 *   MaxClock = 4             -- full clock range
 *   LeaderLeaseDuration = 1  -- lease safety: 1 < 4 - 2*1 = 2
 *   MaxTerm = 2              -- two term transitions
 *
 * INVARIANT COVERAGE IN THIS DOMAIN
 * ----------------------------------
 * All 14 invariants are checked with the full unmodified spec.
 * Coverage status:
 *
 *   Primary verification (this is the only domain that exercises these):
 *     LeaseExpiresBeforeElection -- drift-induced lease overlap tested
 *     WriteBarrierSafety       -- full Applied writePhase (no action merge)
 *
 *   Non-trivial (verified here and in datapath domain):
 *     LeaderUniqueness, LeaseImpliesLeadership,
 *     CatchUpDataIntegrity, FollowerSeqIdConsistency,
 *     NoOrphanMemstoreDrop, FollowerFlushMemstoreDrop,
 *     HFilesBeforeFlushMarker, PromotionReadWriteGuard,
 *     CatchUpCompleteness
 *
 *   Shallow coverage (MaxSeqId = 1 limits scenario depth):
 *     FlushWriteExclusion    -- mutual exclusion guards tested but only
 *                               one operation possible per trace
 *     PromotionMVCCContinuity -- requires in-flight write + crash +
 *                               re-election (needs MaxSeqId >= 2 for
 *                               full scenario); primary verification
 *                               in the datapath domain
 *
 * COMPLEMENTARY DOMAIN
 * --------------------
 * The datapath domain (MCRaftRegionReplica_datapath) uses MaxSeqId = 3
 * with simplified timing (MaxClockDrift = 0) and merged/removed actions
 * for deep verification of multi-operation data-path interleavings.
 * It provides primary verification for the invariants that receive only
 * shallow coverage here (FlushWriteExclusion).
 *
 * Simulation mode (MCRaftRegionReplica_sim) exercises both domains
 * simultaneously with MaxSeqId = 5 and full timing, providing statistical
 * coverage of the cross-product that neither exhaustive domain covers
 * alone.
 *
 * EXPECTED RUNTIME
 * ----------------
 * Minutes to low single-digit hours.  MaxSeqId = 1 shrinks the data-path
 * state space by ~1000x+ compared to the full exhaustive config.
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
MC_MaxSeqId == 1

Symmetry == Permutations(MC_Members)

====
