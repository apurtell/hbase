---- MODULE MergeRaftRegionReplica ----
(*
 * Region merge lifecycle composition over two RaftRegionReplica groups.
 *
 * Models two parent RAFT groups (G1, G2) undergoing a region merge:
 * each parent's leader proposes a "region-close / merge" marker through
 * RAFT, members apply both committed markers (write-closing each parent
 * group locally), and the master opens the merged group on members that
 * have applied both markers.
 *
 * In the real system, each parent's read path remains active after
 * write-closure (frozen-parent read continuation): Timeline reads
 * continue from the frozen, immutable memstore + HFiles until the
 * merged group is ready, at which point both parents' read paths are
 * atomically torn down.  The read path is not modeled in TLA+ because
 * reads do not flow through the consensus layer.  The
 * NoKeyRangeOverlapMerge invariant constrains write-active groups only.
 *
 * Follows the MultiGroupRaftRegionReplica pattern: two full INSTANCE
 * groups sharing clock, partition, and unified log, with shared-impact
 * actions (ClockTick, CrashRestart, CreatePartition, HealPartition,
 * UnifiedLogGC) replaced by multi-group versions.
 *
 * Gating is implemented by constructing per-group gated Next relations
 * using the base spec's building-block operators (GatedMemberActions,
 * GatedMemberDataPathActions).  Single-member actions are guarded by
 * G1ParentActive(m) / G2ParentActive(m); multi-member actions
 * (RequestVote, FollowerLoadFlushedState) are guarded on the initiating member.
 * FollowerApplyMarker and NewLeaderCommitOrphanEntry remain ungated.
 *
 * The merged group is lightweight (per-member boolean) since its
 * internal RAFT safety is already verified by the base spec's 14
 * invariants.  The focus here is the lifecycle handoff: verifying
 * that no member has both a parent group and the merged group active
 * for the same key range simultaneously.
 *
 * MergeCrashRestart wraps CrashRestartEffect for both groups to
 * additionally reset mergedGroupActive on the crashed member.
 *)
EXTENDS DualGroupBase

\* ---- Merge lifecycle state ----
VARIABLES
    mergeMarkerSeqId_1,  \* 0 = not proposed, >0 = G1's merge marker seqId
    mergeMarkerSeqId_2,  \* 0 = not proposed, >0 = G2's merge marker seqId
    mergedGroupActive    \* [Members -> BOOLEAN]: per-member merged group lifecycle

mergeVars == <<mergeMarkerSeqId_1, mergeMarkerSeqId_2, mergedGroupActive>>

vars == <<clock, partition, g1_vars, g2_vars, mergeVars>>

----
(* ---- Per-member parent gating predicates ---- *)

\* TRUE until member m has applied the merge marker (write-closure).
\* Once the marker is in memstore, the parent's write path and RAFT
\* operations are gated on m.  The parent's read path (not modeled)
\* remains active until the merged group is ready.
G1ParentActive(m) ==
    mergeMarkerSeqId_1 = 0 \/ mergeMarkerSeqId_1 \notin memstore_1[m]

G2ParentActive(m) ==
    mergeMarkerSeqId_2 = 0 \/ mergeMarkerSeqId_2 \notin memstore_2[m]

----
(* ---- Initial state ---- *)

Init ==
    /\ G1!Init
    /\ G2!Init
    /\ mergeMarkerSeqId_1 = 0
    /\ mergeMarkerSeqId_2 = 0
    /\ mergedGroupActive = [m \in Members |-> FALSE]

----
(* ---- Shared-impact actions ---- *)

\* Clock tick decrements both groups' timers on the ticking member.
MergeClockTick(m) ==
    /\ G1!ClockTickGuard(m)
    /\ ~\E c \in Members :
          role_2[c] = "Candidate"
              /\ Cardinality(votesGranted_2[c]) >= Majority
    /\ \E m2 \in Members :
          \/ timerRemaining_1[m2] > 0 \/ leaseRemaining_1[m2] > 0
          \/ timerRemaining_2[m2] > 0 \/ leaseRemaining_2[m2] > 0
    /\ G1!ClockTickEffect(m)
    /\ timerRemaining_2' = [timerRemaining_2 EXCEPT
                            ![m] = IF @ > 0 THEN @ - 1 ELSE 0]
    /\ leaseRemaining_2' = [leaseRemaining_2 EXCEPT
                            ![m] = IF @ > 0 THEN @ - 1 ELSE 0]
    /\ UNCHANGED <<partition,
                   role_1, currentTerm_1, votedFor_1, votesGranted_1, raftLog_1,
                   nextSeqId_1, committedEntries_1, markerEntries_1,
                   flushMarkerEntries_1, hdfsHFiles_1,
                   memstore_1, fApplyBatch_1,
                   writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
                   flushPhase_1, flushSeqId_1, snapshotMaxSeqId_1, flushDropBound_1,
                   promotionPhase_1, masterConfirmedTerm_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, snapshotMaxSeqId_2, flushDropBound_2,
                   promotionPhase_2, masterConfirmedTerm_2,
                   mergeVars>>

\* Server crash resets volatile state for BOTH groups and the merged
\* group on the crashed member.
MergeCrashRestart(m) ==
    /\ G1!CrashRestartGuard(m)
       \/ G2!CrashRestartGuard(m)
       \/ mergedGroupActive[m]
    /\ G1!CrashRestartEffect(m)
    /\ G2!CrashRestartEffect(m)
    /\ mergedGroupActive' = [mergedGroupActive EXCEPT ![m] = FALSE]
    /\ UNCHANGED <<clock, partition,
                   currentTerm_1, votedFor_1, raftLog_1,
                   nextSeqId_1, committedEntries_1, markerEntries_1,
                   flushMarkerEntries_1, hdfsHFiles_1,
                   flushDropBound_1, masterConfirmedTerm_1,
                   currentTerm_2, votedFor_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   flushDropBound_2, masterConfirmedTerm_2,
                   mergeMarkerSeqId_1, mergeMarkerSeqId_2>>

\* Server crash with consensus-log suffix loss on the crashed member.
\* Models page-cache or torn-tail loss in the shared multiplexed
\* consensus log (UnifiedRaftStore).  Volatile state for BOTH groups
\* and the merged group is reset; at least one group's raftLog is
\* truncated to a prefix satisfying the per-group catchup precondition.
\* See G1!CrashRestartWithLogLoss for details.
MergeCrashRestartWithLogLoss(m) ==
    /\ G1!CrashRestartEffect(m)
    /\ G2!CrashRestartEffect(m)
    /\ mergedGroupActive' = [mergedGroupActive EXCEPT ![m] = FALSE]
    /\ \/ /\ G1!CrashRestartWithLogLossEffect(m)
          /\ \/ G2!CrashRestartWithLogLossEffect(m)
             \/ raftLog_2' = raftLog_2
       \/ /\ raftLog_1' = raftLog_1
          /\ G2!CrashRestartWithLogLossEffect(m)
    /\ UNCHANGED <<clock, partition,
                   currentTerm_1, votedFor_1,
                   nextSeqId_1, committedEntries_1, markerEntries_1,
                   flushMarkerEntries_1, hdfsHFiles_1,
                   flushDropBound_1, masterConfirmedTerm_1,
                   currentTerm_2, votedFor_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   flushDropBound_2, masterConfirmedTerm_2,
                   mergeMarkerSeqId_1, mergeMarkerSeqId_2>>

\* Network partition — shared across both groups.
MergeCreatePartition ==
    \E m1, m2 \in Members :
        /\ m1 # m2
        /\ <<m1, m2>> \notin partition
        /\ partition' = partition \union {<<m1, m2>>, <<m2, m1>>}
        /\ UNCHANGED <<clock, g1_vars, g2_vars, mergeVars>>

MergeHealPartition ==
    \E m1, m2 \in Members :
        /\ <<m1, m2>> \in partition
        /\ partition' = partition \ {<<m1, m2>>, <<m2, m1>>}
        /\ UNCHANGED <<clock, g1_vars, g2_vars, mergeVars>>

\* Unified log GC: both groups must have an applied flush marker
\* before entries below the watermark are removed.
MergeUnifiedLogGC(m) ==
    /\ \E s1 \in flushMarkerEntries_1 \cap memstore_1[m] :
       \E s2 \in flushMarkerEntries_2 \cap memstore_2[m] :
            /\ (\E e \in raftLog_1[m] : e < s1)
               \/ (\E e \in raftLog_2[m] : e < s2)
            /\ raftLog_1' = [raftLog_1 EXCEPT
                             ![m] = {e \in @ : e >= s1}]
            /\ raftLog_2' = [raftLog_2 EXCEPT
                             ![m] = {e \in @ : e >= s2}]
    /\ UNCHANGED <<clock, partition,
                   role_1, currentTerm_1, votedFor_1, votesGranted_1,
                   leaseRemaining_1, timerRemaining_1,
                   nextSeqId_1, committedEntries_1, markerEntries_1,
                   flushMarkerEntries_1, hdfsHFiles_1,
                   memstore_1, fApplyBatch_1,
                   writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
                   flushPhase_1, flushSeqId_1, snapshotMaxSeqId_1, flushDropBound_1,
                   promotionPhase_1, masterConfirmedTerm_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2,
                   leaseRemaining_2, timerRemaining_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, snapshotMaxSeqId_2, flushDropBound_2,
                   promotionPhase_2, masterConfirmedTerm_2,
                   mergeVars>>

----
(* ---- Merge lifecycle actions ---- *)

\* G1's leader proposes a "region-close / merge" marker through RAFT.
ProposeMergeMarker_1(m) ==
    /\ mergeMarkerSeqId_1 = 0
    /\ G1!IsLeader(m)
    /\ promotionPhase_1[m] = "Complete"
    /\ writePhase_1[m] = "Idle"
    /\ flushPhase_1[m] = "Idle"
    /\ nextSeqId_1 <= MaxSeqId
    /\ G1!QuorumReachable(m)
    /\ LET seqId == nextSeqId_1
           responders == G1!Responders(m)
       IN
        /\ nextSeqId_1' = nextSeqId_1 + 1
        /\ committedEntries_1' = committedEntries_1 \union {seqId}
        /\ markerEntries_1' = markerEntries_1 \union {seqId}
        /\ memstore_1' = [memstore_1 EXCEPT ![m] = @ \union {seqId}]
        /\ raftLog_1' = [r \in Members |->
              IF r = m \/ r \in responders
              THEN raftLog_1[r] \union {seqId}
              ELSE raftLog_1[r]]
        /\ mergeMarkerSeqId_1' = seqId
    /\ UNCHANGED <<role_1, currentTerm_1, votedFor_1, votesGranted_1,
                   clock, leaseRemaining_1, timerRemaining_1, partition,
                   flushMarkerEntries_1, hdfsHFiles_1, fApplyBatch_1,
                   writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
                   flushPhase_1, flushSeqId_1, snapshotMaxSeqId_1, flushDropBound_1,
                   promotionPhase_1, masterConfirmedTerm_1,
                   g2_vars, mergeMarkerSeqId_2, mergedGroupActive>>

\* G2's leader proposes a "region-close / merge" marker through RAFT.
ProposeMergeMarker_2(m) ==
    /\ mergeMarkerSeqId_2 = 0
    /\ G2!IsLeader(m)
    /\ promotionPhase_2[m] = "Complete"
    /\ writePhase_2[m] = "Idle"
    /\ flushPhase_2[m] = "Idle"
    /\ nextSeqId_2 <= MaxSeqId
    /\ G2!QuorumReachable(m)
    /\ LET seqId == nextSeqId_2
           responders == G2!Responders(m)
       IN
        /\ nextSeqId_2' = nextSeqId_2 + 1
        /\ committedEntries_2' = committedEntries_2 \union {seqId}
        /\ markerEntries_2' = markerEntries_2 \union {seqId}
        /\ memstore_2' = [memstore_2 EXCEPT ![m] = @ \union {seqId}]
        /\ raftLog_2' = [r \in Members |->
              IF r = m \/ r \in responders
              THEN raftLog_2[r] \union {seqId}
              ELSE raftLog_2[r]]
        /\ mergeMarkerSeqId_2' = seqId
    /\ UNCHANGED <<role_2, currentTerm_2, votedFor_2, votesGranted_2,
                   clock, leaseRemaining_2, timerRemaining_2, partition,
                   flushMarkerEntries_2, hdfsHFiles_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, snapshotMaxSeqId_2, flushDropBound_2,
                   promotionPhase_2, masterConfirmedTerm_2,
                   g1_vars, mergeMarkerSeqId_1, mergedGroupActive>>

\* Master opens the merged group on member m after BOTH parent groups'
\* merge markers are RAFT-committed and applied on that member.
MasterOpenMerged(m) ==
    /\ mergeMarkerSeqId_1 > 0
    /\ mergeMarkerSeqId_1 \in committedEntries_1
    /\ mergeMarkerSeqId_1 \in memstore_1[m]
    /\ mergeMarkerSeqId_2 > 0
    /\ mergeMarkerSeqId_2 \in committedEntries_2
    /\ mergeMarkerSeqId_2 \in memstore_2[m]
    /\ ~mergedGroupActive[m]
    /\ mergedGroupActive' = [mergedGroupActive EXCEPT ![m] = TRUE]
    /\ UNCHANGED <<clock, partition, g1_vars, g2_vars,
                   mergeMarkerSeqId_1, mergeMarkerSeqId_2>>

----
(* ---- Gated per-group action sets ---- *)

MergeG1GroupNext ==
    \/ \E m \in Members     : G1ParentActive(m) /\ G1!GatedMemberActions(m)
    \/ \E c, v \in Members  : G1ParentActive(c) /\ G1!RequestVote(c, v)
    \/ \E l, f \in Members  : G1ParentActive(l) /\ G1!FollowerLoadFlushedState(l, f)
    \/ \E m \in Members     : G1!FollowerApplyMarker(m)
    \/ G1!NewLeaderCommitOrphanEntry

MergeG2GroupNext ==
    \/ \E m \in Members     : G2ParentActive(m) /\ G2!GatedMemberActions(m)
    \/ \E c, v \in Members  : G2ParentActive(c) /\ G2!RequestVote(c, v)
    \/ \E l, f \in Members  : G2ParentActive(l) /\ G2!FollowerLoadFlushedState(l, f)
    \/ \E m \in Members     : G2!FollowerApplyMarker(m)
    \/ G2!NewLeaderCommitOrphanEntry

MergeG1GroupDataPathNext ==
    \/ \E m \in Members     : G1ParentActive(m) /\ G1!GatedMemberDataPathActions(m)
    \/ \E c, v \in Members  : G1ParentActive(c) /\ G1!RequestVote(c, v)
    \/ \E l, f \in Members  : G1ParentActive(l) /\ G1!FollowerLoadFlushedState(l, f)
    \/ \E m \in Members     : G1!FollowerApplyMarker(m)
    \/ G1!NewLeaderCommitOrphanEntry

MergeG2GroupDataPathNext ==
    \/ \E m \in Members     : G2ParentActive(m) /\ G2!GatedMemberDataPathActions(m)
    \/ \E c, v \in Members  : G2ParentActive(c) /\ G2!RequestVote(c, v)
    \/ \E l, f \in Members  : G2ParentActive(l) /\ G2!FollowerLoadFlushedState(l, f)
    \/ \E m \in Members     : G2!FollowerApplyMarker(m)
    \/ G2!NewLeaderCommitOrphanEntry

----
(* ---- Next-state relation and specification ---- *)

Next ==
    \* Per-group actions with lifecycle gating
    \/ (MergeG1GroupNext /\ UNCHANGED <<g2_vars, mergeVars>>)
    \/ (MergeG2GroupNext /\ UNCHANGED <<g1_vars, mergeVars>>)
    \* Shared-impact actions
    \/ \E m \in Members : MergeClockTick(m)
    \/ \E m \in Members : MergeCrashRestart(m)
    \/ \E m \in Members : MergeCrashRestartWithLogLoss(m)
    \/ MergeCreatePartition
    \/ MergeHealPartition
    \/ \E m \in Members : MergeUnifiedLogGC(m)
    \* Merge lifecycle
    \/ \E m \in Members : ProposeMergeMarker_1(m)
    \/ \E m \in Members : ProposeMergeMarker_2(m)
    \/ \E m \in Members : MasterOpenMerged(m)

Spec == Init /\ [][Next]_vars

----
(* ---- Data-path next-state relation ---- *)

MergeDataPathNext ==
    \* Per-group actions with lifecycle gating (data-path merged)
    \/ (MergeG1GroupDataPathNext /\ UNCHANGED <<g2_vars, mergeVars>>)
    \/ (MergeG2GroupDataPathNext /\ UNCHANGED <<g1_vars, mergeVars>>)
    \* Shared-impact actions
    \/ \E m \in Members : MergeClockTick(m)
    \/ \E m \in Members : MergeCrashRestart(m)
    \/ \E m \in Members : MergeCrashRestartWithLogLoss(m)
    \/ MergeCreatePartition
    \/ MergeHealPartition
    \/ \E m \in Members : MergeUnifiedLogGC(m)
    \* Merge lifecycle
    \/ \E m \in Members : ProposeMergeMarker_1(m)
    \/ \E m \in Members : ProposeMergeMarker_2(m)
    \/ \E m \in Members : MasterOpenMerged(m)

MergeDataPathSpec == Init /\ [][MergeDataPathNext]_vars

----
(* ---- Safety properties ---- *)

MergeTypeOK ==
    /\ G1!TypeOK
    /\ G2!TypeOK
    /\ mergeMarkerSeqId_1 \in 0..MaxSeqId
    /\ mergeMarkerSeqId_2 \in 0..MaxSeqId
    /\ mergedGroupActive \in [Members -> BOOLEAN]

\* Core merge safety invariant: no member has both a parent group and
\* the merged group active for the same key range.
NoKeyRangeOverlapMerge ==
    \A m \in Members :
        mergedGroupActive[m] =>
            /\ mergeMarkerSeqId_1 > 0
            /\ mergeMarkerSeqId_1 \in memstore_1[m]
            /\ mergeMarkerSeqId_2 > 0
            /\ mergeMarkerSeqId_2 \in memstore_2[m]

====
