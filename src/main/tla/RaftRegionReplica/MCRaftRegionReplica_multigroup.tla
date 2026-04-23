---- MODULE MCRaftRegionReplica_multigroup ----
(*
 * Multi-group domain model-checking configuration.
 *
 * PURPOSE
 * -------
 * Verify that two RAFT groups sharing the same ConsensusServer
 * resources (clock, network, unified log) do not interfere with
 * each other's safety properties.  All 14 single-group invariants
 * are checked per-group via INSTANCE.  CatchUpDataIntegrity is the
 * key cross-group invariant: it verifies that unified log GC
 * (which requires all groups to have flushed) does not delete
 * entries needed by either group for catch-up.
 *
 * TIMING SIMPLIFICATION
 * ---------------------
 * Identical to MCRaftRegionReplica_datapath: MaxClockDrift = 0,
 * ElectionTimeoutMin = 2, MaxClock = 2.  Clock drift is orthogonal
 * to cross-group interaction correctness.
 *
 * DATA-PATH ACTION MERGES
 * -----------------------
 * Per-group steps use GroupDataPathNext (defined in the base spec),
 * which applies the same action merges as the datapath domain:
 *   - FollowerBeginBatchApply + FollowerCompleteBatchApply merged
 *   - CompleteWrite + AckWrite merged
 *   - ProposeMarker, WALSyncFail, WALFailureAbort removed
 *
 * STATE SPACE
 * -----------
 * MaxSeqId = 2 (reduced from 3) compensates for the two-group
 * product.  write(1) + flush(2) per group exercises the flush-GC
 * path that is critical for UnifiedLogGC verification.
 *
 * Partitions limited to at most 1 link (same as datapath domain).
 *)
EXTENDS MultiGroupRaftRegionReplica, TLC

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

----
(* ---- Multi-group data-path next-state relation ---- *)

\* Per-group steps use the data-path-merged GroupDataPathNext.
\* Shared-impact actions are the multi-group versions from
\* MultiGroupRaftRegionReplica.
MultiGroupDataPathNext ==
    \/ (G1!GroupDataPathNext /\ UNCHANGED g2_vars)
    \/ (G2GroupDataPathNextMetaGated /\ UNCHANGED g1_vars)
    \/ \E m \in Members : MultiGroupClockTick(m)
    \/ \E m \in Members : MultiGroupCrashRestart(m)
    \/ MultiGroupCreatePartition
    \/ MultiGroupHealPartition
    \/ \E m \in Members : UnifiedLogGC(m)

MultiGroupDataPathSpec == Init /\ [][MultiGroupDataPathNext]_vars

====
