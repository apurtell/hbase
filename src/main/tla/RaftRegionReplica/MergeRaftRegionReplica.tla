---- MODULE MergeRaftRegionReplica ----
(*
 * Region merge lifecycle composition over two RaftRegionReplica groups.
 *
 * Models two parent RAFT groups (G1, G2) undergoing a region merge:
 * each parent's leader proposes a "region-close / merge" marker through
 * RAFT, members apply both committed markers (closing each parent group
 * locally), and the master opens the merged group on members that have
 * applied both markers.
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
 * (RequestVote, InstallSnapshot) are guarded on the initiating member.
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
EXTENDS Naturals, FiniteSets

CONSTANTS
    Members,
    None,
    MaxTerm,
    LeaderLeaseDuration,
    ElectionTimeoutMin,
    MaxClockDrift,
    MaxClock,
    MaxSeqId

\* ---- Shared state ----
VARIABLES clock, partition

\* ---- Group 1 per-group state (23 variables) ----
VARIABLES
    role_1, currentTerm_1, votedFor_1, votesGranted_1, raftLog_1,
    leaseRemaining_1, timerRemaining_1,
    nextSeqId_1, committedEntries_1, markerEntries_1,
    flushMarkerEntries_1, hdfsHFiles_1,
    memstore_1, fApplyBatch_1,
    writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
    flushPhase_1, flushSeqId_1,
    promotionPhase_1, masterConfirmedTerm_1,
    hibernateState_1

\* ---- Group 2 per-group state (23 variables) ----
VARIABLES
    role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
    leaseRemaining_2, timerRemaining_2,
    nextSeqId_2, committedEntries_2, markerEntries_2,
    flushMarkerEntries_2, hdfsHFiles_2,
    memstore_2, fApplyBatch_2,
    writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
    flushPhase_2, flushSeqId_2,
    promotionPhase_2, masterConfirmedTerm_2,
    hibernateState_2

\* ---- Merge lifecycle state ----
VARIABLES
    mergeMarkerSeqId_1,  \* 0 = not proposed, >0 = G1's merge marker seqId
    mergeMarkerSeqId_2,  \* 0 = not proposed, >0 = G2's merge marker seqId
    mergedGroupActive    \* [Members -> BOOLEAN]: per-member merged group lifecycle

\* ---- Variable tuples ----

g1_vars == <<role_1, currentTerm_1, votedFor_1, votesGranted_1, raftLog_1,
             leaseRemaining_1, timerRemaining_1,
             nextSeqId_1, committedEntries_1, markerEntries_1,
             flushMarkerEntries_1, hdfsHFiles_1,
             memstore_1, fApplyBatch_1,
             writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
             flushPhase_1, flushSeqId_1,
             promotionPhase_1, masterConfirmedTerm_1,
             hibernateState_1>>

g2_vars == <<role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
             leaseRemaining_2, timerRemaining_2,
             nextSeqId_2, committedEntries_2, markerEntries_2,
             flushMarkerEntries_2, hdfsHFiles_2,
             memstore_2, fApplyBatch_2,
             writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
             flushPhase_2, flushSeqId_2,
             promotionPhase_2, masterConfirmedTerm_2,
             hibernateState_2>>

mergeVars == <<mergeMarkerSeqId_1, mergeMarkerSeqId_2, mergedGroupActive>>

vars == <<clock, partition, g1_vars, g2_vars, mergeVars>>

----
(* ---- Per-member parent gating predicates ---- *)

G1ParentActive(m) ==
    mergeMarkerSeqId_1 = 0 \/ mergeMarkerSeqId_1 \notin memstore_1[m]

G2ParentActive(m) ==
    mergeMarkerSeqId_2 = 0 \/ mergeMarkerSeqId_2 \notin memstore_2[m]

----
(* ---- INSTANCE declarations ---- *)

G1 == INSTANCE RaftRegionReplica WITH
    role            <- role_1,
    currentTerm     <- currentTerm_1,
    votedFor        <- votedFor_1,
    votesGranted    <- votesGranted_1,
    raftLog         <- raftLog_1,
    clock           <- clock,
    leaseRemaining  <- leaseRemaining_1,
    timerRemaining  <- timerRemaining_1,
    partition       <- partition,
    nextSeqId       <- nextSeqId_1,
    committedEntries <- committedEntries_1,
    markerEntries   <- markerEntries_1,
    flushMarkerEntries <- flushMarkerEntries_1,
    hdfsHFiles      <- hdfsHFiles_1,
    memstore        <- memstore_1,
    fApplyBatch     <- fApplyBatch_1,
    writePhase      <- writePhase_1,
    walSync         <- walSync_1,
    raftCommitted   <- raftCommitted_1,
    writeSeqId      <- writeSeqId_1,
    flushPhase      <- flushPhase_1,
    flushSeqId      <- flushSeqId_1,
    promotionPhase  <- promotionPhase_1,
    masterConfirmedTerm <- masterConfirmedTerm_1,
    hibernateState  <- hibernateState_1

G2 == INSTANCE RaftRegionReplica WITH
    role            <- role_2,
    currentTerm     <- currentTerm_2,
    votedFor        <- votedFor_2,
    votesGranted    <- votesGranted_2,
    raftLog         <- raftLog_2,
    clock           <- clock,
    leaseRemaining  <- leaseRemaining_2,
    timerRemaining  <- timerRemaining_2,
    partition       <- partition,
    nextSeqId       <- nextSeqId_2,
    committedEntries <- committedEntries_2,
    markerEntries   <- markerEntries_2,
    flushMarkerEntries <- flushMarkerEntries_2,
    hdfsHFiles      <- hdfsHFiles_2,
    memstore        <- memstore_2,
    fApplyBatch     <- fApplyBatch_2,
    writePhase      <- writePhase_2,
    walSync         <- walSync_2,
    raftCommitted   <- raftCommitted_2,
    writeSeqId      <- writeSeqId_2,
    flushPhase      <- flushPhase_2,
    flushSeqId      <- flushSeqId_2,
    promotionPhase  <- promotionPhase_2,
    masterConfirmedTerm <- masterConfirmedTerm_2,
    hibernateState  <- hibernateState_2

Majority == (Cardinality(Members) \div 2) + 1

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
                   flushPhase_1, flushSeqId_1, promotionPhase_1, masterConfirmedTerm_1, hibernateState_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, promotionPhase_2, masterConfirmedTerm_2, hibernateState_2,
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
                   masterConfirmedTerm_1,
                   currentTerm_2, votedFor_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   masterConfirmedTerm_2,
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
                   flushPhase_1, flushSeqId_1, promotionPhase_1, masterConfirmedTerm_1, hibernateState_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2,
                   leaseRemaining_2, timerRemaining_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, promotionPhase_2, masterConfirmedTerm_2, hibernateState_2,
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
    /\ LET seqId == nextSeqId_1
           followers  == Members \ {m}
           responders == {f \in followers :
                            /\ currentTerm_1[m] >= currentTerm_1[f]
                            /\ G1!CanCommunicate(m, f)}
       IN
        /\ ~\E f \in followers :
              /\ G1!CanCommunicate(m, f)
              /\ currentTerm_1[f] > currentTerm_1[m]
        /\ Cardinality(responders) + 1 >= Majority
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
                   flushPhase_1, flushSeqId_1, promotionPhase_1, masterConfirmedTerm_1, hibernateState_1,
                   g2_vars, mergeMarkerSeqId_2, mergedGroupActive>>

\* G2's leader proposes a "region-close / merge" marker through RAFT.
ProposeMergeMarker_2(m) ==
    /\ mergeMarkerSeqId_2 = 0
    /\ G2!IsLeader(m)
    /\ promotionPhase_2[m] = "Complete"
    /\ writePhase_2[m] = "Idle"
    /\ flushPhase_2[m] = "Idle"
    /\ nextSeqId_2 <= MaxSeqId
    /\ LET seqId == nextSeqId_2
           followers  == Members \ {m}
           responders == {f \in followers :
                            /\ currentTerm_2[m] >= currentTerm_2[f]
                            /\ G2!CanCommunicate(m, f)}
       IN
        /\ ~\E f \in followers :
              /\ G2!CanCommunicate(m, f)
              /\ currentTerm_2[f] > currentTerm_2[m]
        /\ Cardinality(responders) + 1 >= Majority
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
                   flushPhase_2, flushSeqId_2, promotionPhase_2, masterConfirmedTerm_2, hibernateState_2,
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
    \/ \E l, f \in Members  : G1ParentActive(l) /\ G1!InstallSnapshot(l, f)
    \/ \E m \in Members     : G1!FollowerApplyMarker(m)
    \/ G1!NewLeaderCommitOrphanEntry

MergeG2GroupNext ==
    \/ \E m \in Members     : G2ParentActive(m) /\ G2!GatedMemberActions(m)
    \/ \E c, v \in Members  : G2ParentActive(c) /\ G2!RequestVote(c, v)
    \/ \E l, f \in Members  : G2ParentActive(l) /\ G2!InstallSnapshot(l, f)
    \/ \E m \in Members     : G2!FollowerApplyMarker(m)
    \/ G2!NewLeaderCommitOrphanEntry

MergeG1GroupDataPathNext ==
    \/ \E m \in Members     : G1ParentActive(m) /\ G1!GatedMemberDataPathActions(m)
    \/ \E c, v \in Members  : G1ParentActive(c) /\ G1!RequestVote(c, v)
    \/ \E l, f \in Members  : G1ParentActive(l) /\ G1!InstallSnapshot(l, f)
    \/ \E m \in Members     : G1!FollowerApplyMarker(m)
    \/ G1!NewLeaderCommitOrphanEntry

MergeG2GroupDataPathNext ==
    \/ \E m \in Members     : G2ParentActive(m) /\ G2!GatedMemberDataPathActions(m)
    \/ \E c, v \in Members  : G2ParentActive(c) /\ G2!RequestVote(c, v)
    \/ \E l, f \in Members  : G2ParentActive(l) /\ G2!InstallSnapshot(l, f)
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

\* All 14 per-group safety invariants for both parent groups.
PerGroupSafety ==
    /\ G1!LeaderUniqueness          /\ G2!LeaderUniqueness
    /\ G1!LeaseImpliesLeadership    /\ G2!LeaseImpliesLeadership
    /\ G1!LeaseExpiresBeforeElection /\ G2!LeaseExpiresBeforeElection
    /\ G1!CatchUpDataIntegrity      /\ G2!CatchUpDataIntegrity
    /\ G1!WriteBarrierSafety        /\ G2!WriteBarrierSafety
    /\ G1!FollowerSeqIdConsistency  /\ G2!FollowerSeqIdConsistency
    /\ G1!NoOrphanMemstoreDrop      /\ G2!NoOrphanMemstoreDrop
    /\ G1!FlushWriteExclusion       /\ G2!FlushWriteExclusion
    /\ G1!FollowerFlushMemstoreDrop /\ G2!FollowerFlushMemstoreDrop
    /\ G1!HFilesBeforeFlushMarker   /\ G2!HFilesBeforeFlushMarker
    /\ G1!PromotionReadWriteGuard   /\ G2!PromotionReadWriteGuard
    /\ G1!PromotionMVCCContinuity   /\ G2!PromotionMVCCContinuity
    /\ G1!CatchUpCompleteness       /\ G2!CatchUpCompleteness

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
