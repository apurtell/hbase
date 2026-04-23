---- MODULE SplitRaftRegionReplica ----
(*
 * Region split lifecycle composition over RaftRegionReplica.
 *
 * Models one parent RAFT group (full INSTANCE of RaftRegionReplica)
 * undergoing a region split: the leader proposes a "region-close /
 * split" marker through RAFT, members apply the committed marker
 * (closing the parent group locally), and the master opens daughter
 * groups on members that have applied the marker.
 *
 * Daughter groups are lightweight (per-member boolean) since their
 * internal RAFT safety is already verified by the base spec's 14
 * invariants.  The focus here is the lifecycle handoff: verifying
 * that no member has both parent and daughter groups active for the
 * same key range simultaneously.
 *
 * Per-member gating: the parent group is gated on each member after
 * that member applies the split marker.  ParentGroupActive(m) is
 * TRUE until splitMarkerSeqId is in memstore[m].  ProposeSplitMarker
 * atomically commits the marker and places it in the leader's
 * memstore, so the leader is immediately gated.  Followers remain
 * active until FollowerApplyMarker applies the split marker on each.
 * This matches the real system's regionGroupManager.removeGroup()
 * semantics.
 *
 * Gating is implemented by constructing a gated Next relation using
 * the base spec's building-block operators (GatedMemberActions,
 * GatedMemberDataPathActions).  Single-member actions are guarded by
 * ParentGroupActive(m); multi-member actions (RequestVote,
 * InstallSnapshot) are guarded on the initiating member.
 * FollowerApplyMarker and NewLeaderCommitOrphanEntry remain ungated
 * so the split marker itself can be applied and committed.
 *
 * SplitCrashRestart wraps CrashRestart to additionally reset
 * daughterGroupsActive on the crashed member.
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

(* ---- Parent group state (full INSTANCE) ---- *)
VARIABLES
    role, currentTerm, votedFor, votesGranted, raftLog,
    clock, leaseRemaining, timerRemaining, partition,
    nextSeqId, committedEntries, markerEntries,
    flushMarkerEntries, hdfsHFiles,
    memstore, fApplyBatch,
    writePhase, walSync, raftCommitted, writeSeqId,
    flushPhase, flushSeqId, promotionPhase, masterConfirmedTerm

(* ---- Split lifecycle state ---- *)
VARIABLES
    splitMarkerSeqId,    \* 0 = not proposed, >0 = the split marker's seqId
    daughterGroupsActive \* [Members -> BOOLEAN]: per-member daughter lifecycle

splitVars == <<splitMarkerSeqId, daughterGroupsActive>>

parentVars == <<role, currentTerm, votedFor, votesGranted, raftLog,
               clock, leaseRemaining, timerRemaining, partition,
               nextSeqId, committedEntries, markerEntries, flushMarkerEntries,
               hdfsHFiles, memstore, fApplyBatch,
               writePhase, walSync, raftCommitted, writeSeqId,
               flushPhase, flushSeqId, promotionPhase, masterConfirmedTerm>>

vars == <<parentVars, splitVars>>

----
(* ---- Per-member parent gating predicate ---- *)

\* TRUE until member m has applied the split marker.  Once the split
\* marker is in memstore[m], the parent group is "removed" on m and
\* no further normal-operation actions fire for that member.
ParentGroupActive(m) ==
    splitMarkerSeqId = 0 \/ splitMarkerSeqId \notin memstore[m]

----
(* ---- INSTANCE declaration ---- *)

Parent == INSTANCE RaftRegionReplica

Majority == (Cardinality(Members) \div 2) + 1

----
(* ---- Initial state ---- *)

Init ==
    /\ Parent!Init
    /\ splitMarkerSeqId = 0
    /\ daughterGroupsActive = [m \in Members |-> FALSE]

----
(* ---- Split lifecycle actions ---- *)

\* Leader proposes a "region-close / split" marker through RAFT.
ProposeSplitMarker(m) ==
    /\ splitMarkerSeqId = 0
    /\ Parent!IsLeader(m)
    /\ promotionPhase[m] = "Complete"
    /\ writePhase[m] = "Idle"
    /\ flushPhase[m] = "Idle"
    /\ nextSeqId <= MaxSeqId
    /\ LET seqId == nextSeqId
           followers  == Members \ {m}
           responders == {f \in followers :
                            /\ currentTerm[m] >= currentTerm[f]
                            /\ Parent!CanCommunicate(m, f)}
       IN
        /\ ~\E f \in followers :
              /\ Parent!CanCommunicate(m, f)
              /\ currentTerm[f] > currentTerm[m]
        /\ Cardinality(responders) + 1 >= Majority
        /\ nextSeqId' = nextSeqId + 1
        /\ committedEntries' = committedEntries \union {seqId}
        /\ markerEntries' = markerEntries \union {seqId}
        /\ memstore' = [memstore EXCEPT ![m] = @ \union {seqId}]
        /\ raftLog' = [r \in Members |->
              IF r = m \/ r \in responders
              THEN raftLog[r] \union {seqId}
              ELSE raftLog[r]]
        /\ splitMarkerSeqId' = seqId
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted,
                   clock, leaseRemaining, timerRemaining, partition,
                   flushMarkerEntries, hdfsHFiles, fApplyBatch,
                   writePhase, walSync, raftCommitted, writeSeqId,
                   flushPhase, flushSeqId, promotionPhase,
                   masterConfirmedTerm,
                   daughterGroupsActive>>

\* Master opens daughter groups on member m after the split marker
\* is RAFT-committed and applied on that member.
MasterOpenDaughter(m) ==
    /\ splitMarkerSeqId > 0
    /\ splitMarkerSeqId \in committedEntries
    /\ splitMarkerSeqId \in memstore[m]
    /\ ~daughterGroupsActive[m]
    /\ daughterGroupsActive' = [daughterGroupsActive EXCEPT ![m] = TRUE]
    /\ UNCHANGED <<parentVars, splitMarkerSeqId>>

\* Server crash resets daughter state on the crashed member.
SplitCrashRestart(m) ==
    /\ Parent!CrashRestart(m)
    /\ daughterGroupsActive' = [daughterGroupsActive EXCEPT ![m] = FALSE]
    /\ UNCHANGED splitMarkerSeqId

----
(* ---- Gated per-group action sets ---- *)

\* Parent group actions with per-member lifecycle gating.
\* GatedMemberActions are guarded by ParentGroupActive on the acting
\* member.  RequestVote and InstallSnapshot are guarded on the
\* initiating member.  FollowerApplyMarker and NewLeaderCommitOrphanEntry
\* remain ungated.
SplitGroupNext ==
    \/ \E m \in Members     : ParentGroupActive(m) /\ Parent!GatedMemberActions(m)
    \/ \E c, v \in Members  : ParentGroupActive(c) /\ Parent!RequestVote(c, v)
    \/ \E l, f \in Members  : ParentGroupActive(l) /\ Parent!InstallSnapshot(l, f)
    \/ \E m \in Members     : Parent!FollowerApplyMarker(m)
    \/ Parent!NewLeaderCommitOrphanEntry

\* Data-path variant with merged actions.
SplitGroupDataPathNext ==
    \/ \E m \in Members     : ParentGroupActive(m) /\ Parent!GatedMemberDataPathActions(m)
    \/ \E c, v \in Members  : ParentGroupActive(c) /\ Parent!RequestVote(c, v)
    \/ \E l, f \in Members  : ParentGroupActive(l) /\ Parent!InstallSnapshot(l, f)
    \/ \E m \in Members     : Parent!FollowerApplyMarker(m)
    \/ Parent!NewLeaderCommitOrphanEntry

----
(* ---- Next-state relation and specification ---- *)

Next ==
    \* Per-group actions with lifecycle gating
    \/ (SplitGroupNext /\ UNCHANGED splitVars)
    \* Shared-impact actions
    \/ (\E m \in Members : Parent!ClockTick(m) /\ UNCHANGED splitVars)
    \/ \E m \in Members : SplitCrashRestart(m)
    \/ (Parent!CreatePartition /\ UNCHANGED splitVars)
    \/ (Parent!HealPartition /\ UNCHANGED splitVars)
    \/ (Parent!HealAllPartitions /\ UNCHANGED splitVars)
    \/ (\E m \in Members : Parent!RaftLogGC(m) /\ UNCHANGED splitVars)
    \* Split lifecycle
    \/ \E m \in Members : ProposeSplitMarker(m)
    \/ \E m \in Members : MasterOpenDaughter(m)

Spec == Init /\ [][Next]_vars

----
(* ---- Data-path next-state relation ---- *)

SplitDataPathNext ==
    \* Per-group actions with lifecycle gating (data-path merged)
    \/ (SplitGroupDataPathNext /\ UNCHANGED splitVars)
    \* Shared-impact actions
    \/ (\E m \in Members : Parent!ClockTick(m) /\ UNCHANGED splitVars)
    \/ \E m \in Members : SplitCrashRestart(m)
    \/ (Parent!CreatePartition /\ UNCHANGED splitVars)
    \/ (Parent!HealPartition /\ UNCHANGED splitVars)
    \/ (Parent!HealAllPartitions /\ UNCHANGED splitVars)
    \/ (\E m \in Members : Parent!RaftLogGC(m) /\ UNCHANGED splitVars)
    \* Split lifecycle
    \/ \E m \in Members : ProposeSplitMarker(m)
    \/ \E m \in Members : MasterOpenDaughter(m)

SplitDataPathSpec == Init /\ [][SplitDataPathNext]_vars

----
(* ---- Safety properties ---- *)

SplitTypeOK ==
    /\ Parent!TypeOK
    /\ splitMarkerSeqId \in 0..MaxSeqId
    /\ daughterGroupsActive \in [Members -> BOOLEAN]

ParentGroupSafety ==
    /\ Parent!LeaderUniqueness
    /\ Parent!LeaseImpliesLeadership
    /\ Parent!LeaseExpiresBeforeElection
    /\ Parent!CatchUpDataIntegrity
    /\ Parent!WriteBarrierSafety
    /\ Parent!FollowerSeqIdConsistency
    /\ Parent!NoOrphanMemstoreDrop
    /\ Parent!FlushWriteExclusion
    /\ Parent!FollowerFlushMemstoreDrop
    /\ Parent!HFilesBeforeFlushMarker
    /\ Parent!PromotionReadWriteGuard
    /\ Parent!PromotionMVCCContinuity
    /\ Parent!CatchUpCompleteness

NoKeyRangeOverlap ==
    \A m \in Members :
        daughterGroupsActive[m] =>
            /\ splitMarkerSeqId > 0
            /\ splitMarkerSeqId \in memstore[m]

====
