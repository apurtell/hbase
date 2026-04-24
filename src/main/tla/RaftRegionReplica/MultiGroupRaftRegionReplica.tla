---- MODULE MultiGroupRaftRegionReplica ----
(*
 * Multi-group composition of RaftRegionReplica.
 *
 * Models two independent RAFT groups (G1, G2) sharing the same
 * ConsensusServer resources on a set of RegionServers:
 *
 *   - Shared physical clock (clock): ClockTick decrements both
 *     groups' timers simultaneously.
 *   - Shared network (partition): partitions affect both groups.
 *   - Unified multiplexed consensus log: log segment GC requires
 *     ALL groups to have flushed past their entries before deletion.
 *   - Shared thread pool (MultiGroupExecutor): modeled implicitly
 *     by TLA+'s nondeterministic interleaving — any enabled action
 *     from either group may fire at each step.
 *
 * G1 is designated as the META group and G2 as a non-META group.
 * G2's MasterConfirmPromotion is gated on MetaReady (derived from
 * G1's promotion state), modeling the META availability ordering
 * constraint: the master cannot persist non-META promotions to META
 * until META itself is writable.  G1 uses the base spec's
 * MasterConfirmPromotion unmodified (the master confirms META via
 * an in-memory-only path with no META write dependency).
 *
 * Per-group actions are dispatched via G1!GroupNext / G2!GroupNext,
 * with G2!MasterConfirmPromotion replaced by a gated version.
 *
 * Five "shared-impact" actions are replaced with multi-group
 * versions: ClockTick, CrashRestart, CreatePartition, HealPartition,
 * and RaftLogGC (replaced by UnifiedLogGC).
 *
 * Server crash (MultiGroupCrashRestart) resets volatile state for
 * BOTH groups on the crashed member, modeling the physical reality
 * that a process crash kills all RAFT groups on that server.
 *
 * All 14 single-group safety invariants are checked per-group via
 * INSTANCE (G1!LeaderUniqueness, G2!LeaderUniqueness, etc.).
 * CatchUpDataIntegrity is the key cross-group invariant: it verifies
 * that unified log GC does not delete entries still needed by
 * either group for catch-up.
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

\* ---- Group 1 per-group state (22 variables) ----
VARIABLES
    role_1, currentTerm_1, votedFor_1, votesGranted_1, raftLog_1,
    leaseRemaining_1, timerRemaining_1,
    nextSeqId_1, committedEntries_1, markerEntries_1,
    flushMarkerEntries_1, hdfsHFiles_1,
    memstore_1, fApplyBatch_1,
    writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
    flushPhase_1, flushSeqId_1,
    promotionPhase_1, masterConfirmedTerm_1

\* ---- Group 2 per-group state (22 variables) ----
VARIABLES
    role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
    leaseRemaining_2, timerRemaining_2,
    nextSeqId_2, committedEntries_2, markerEntries_2,
    flushMarkerEntries_2, hdfsHFiles_2,
    memstore_2, fApplyBatch_2,
    writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
    flushPhase_2, flushSeqId_2,
    promotionPhase_2, masterConfirmedTerm_2

\* ---- Variable tuples ----

g1_vars == <<role_1, currentTerm_1, votedFor_1, votesGranted_1, raftLog_1,
             leaseRemaining_1, timerRemaining_1,
             nextSeqId_1, committedEntries_1, markerEntries_1,
             flushMarkerEntries_1, hdfsHFiles_1,
             memstore_1, fApplyBatch_1,
             writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
             flushPhase_1, flushSeqId_1,
             promotionPhase_1, masterConfirmedTerm_1>>

g2_vars == <<role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
             leaseRemaining_2, timerRemaining_2,
             nextSeqId_2, committedEntries_2, markerEntries_2,
             flushMarkerEntries_2, hdfsHFiles_2,
             memstore_2, fApplyBatch_2,
             writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
             flushPhase_2, flushSeqId_2,
             promotionPhase_2, masterConfirmedTerm_2>>

vars == <<clock, partition, g1_vars, g2_vars>>

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
    masterConfirmedTerm <- masterConfirmedTerm_1

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
    masterConfirmedTerm <- masterConfirmedTerm_2

Majority == (Cardinality(Members) \div 2) + 1

----
(* ---- Initial state ---- *)

Init == G1!Init /\ G2!Init

----
(* ---- Shared-impact actions ---- *)

\* Clock tick decrements both groups' timers on the ticking member.
\* Guards check both groups' candidate and timer state.
MultiGroupClockTick(m) ==
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
                   flushPhase_1, flushSeqId_1, promotionPhase_1,
                   masterConfirmedTerm_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, promotionPhase_2,
                   masterConfirmedTerm_2>>

\* Server crash resets volatile state for BOTH groups on the crashed
\* member.  Durable state (currentTerm, votedFor, raftLog) survives.
\* Guard: at least one group has volatile state worth resetting.
MultiGroupCrashRestart(m) ==
    /\ G1!CrashRestartGuard(m) \/ G2!CrashRestartGuard(m)
    /\ G1!CrashRestartEffect(m)
    /\ G2!CrashRestartEffect(m)
    /\ UNCHANGED <<clock, partition,
                   currentTerm_1, votedFor_1, raftLog_1,
                   nextSeqId_1, committedEntries_1, markerEntries_1,
                   flushMarkerEntries_1, hdfsHFiles_1,
                   masterConfirmedTerm_1,
                   currentTerm_2, votedFor_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   masterConfirmedTerm_2>>

\* Network partition — shared across both groups.
MultiGroupCreatePartition ==
    \E m1, m2 \in Members :
        /\ m1 # m2
        /\ <<m1, m2>> \notin partition
        /\ partition' = partition \union {<<m1, m2>>, <<m2, m1>>}
        /\ UNCHANGED <<clock, g1_vars, g2_vars>>

MultiGroupHealPartition ==
    \E m1, m2 \in Members :
        /\ <<m1, m2>> \in partition
        /\ partition' = partition \ {<<m1, m2>>, <<m2, m1>>}
        /\ UNCHANGED <<clock, g1_vars, g2_vars>>

\* Unified log GC: models physical segment deletion in the shared
\* append-only consensus log.  Both groups must have an applied flush
\* marker on member m, and entries below each group's chosen flush
\* watermark are removed from both groups' raftLogs simultaneously.
UnifiedLogGC(m) ==
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
                   flushPhase_1, flushSeqId_1, promotionPhase_1,
                   masterConfirmedTerm_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2,
                   leaseRemaining_2, timerRemaining_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, promotionPhase_2,
                   masterConfirmedTerm_2>>

----
(* ---- META availability ordering ---- *)

\* G1 is the META group.  MetaReady is TRUE when META's promoted
\* leader has completed all three promotion phases (including master
\* confirmation), meaning META is writable.  Derived operator — no
\* explicit maintenance needed.
MetaReady == \E m \in Members : promotionPhase_1[m] = "Complete"

\* G2's MasterConfirmPromotion is gated on MetaReady.  The master
\* cannot persist a non-META promotion to META if META is not writable.
\* G1 (META) uses the base spec's MasterConfirmPromotion unmodified —
\* the master confirms META via an in-memory-only path.
G2MasterConfirmPromotion(m) ==
    /\ MetaReady
    /\ G2!MasterConfirmPromotion(m)

\* G2's single-member actions with MasterConfirmPromotion replaced
\* by the MetaReady-gated version.
G2GatedMemberActions(m) ==
    \/ G2!Timeout(m)
    \/ G2!BecomeLeader(m)
    \/ G2!Heartbeat(m)
    \/ G2!StepDown(m)
    \/ G2!LeaderLeaseExpiry(m)
    \/ G2!BeginWrite(m)
    \/ G2!WALSyncComplete(m)
    \/ G2!RAFTCommitWrite(m)
    \/ G2!WALSyncFail(m)
    \/ G2!CompleteWrite(m)
    \/ G2!AckWrite(m)
    \/ G2!WALFailureAbort(m)
    \/ G2!FlushStart(m)
    \/ G2!FlushCommitHFiles(m)
    \/ G2!FlushRAFTPropose(m)
    \/ G2!FlushRAFTCommit(m)
    \/ G2!FlushComplete(m)
    \/ G2!ProposeMarker(m)
    \/ G2!FollowerBeginBatchApply(m)
    \/ G2!FollowerCompleteBatchApply(m)
    \/ G2MasterConfirmPromotion(m)
    \/ G2!PromotionComplete(m)
    \/ G2!NewMemberBootstrap(m)

\* G2's GroupNext with MasterConfirmPromotion replaced by the
\* MetaReady-gated version.
G2GroupNextMetaGated ==
    \/ \E m \in Members     : G2GatedMemberActions(m)
    \/ \E c, v \in Members  : G2!RequestVote(c, v)
    \/ \E l, f \in Members  : G2!InstallSnapshot(l, f)
    \/ \E m \in Members     : G2!FollowerApplyMarker(m)
    \/ G2!NewLeaderCommitOrphanEntry

\* G2's data-path single-member actions with MasterConfirmPromotion
\* replaced by the MetaReady-gated version.
G2GatedMemberDataPathActions(m) ==
    \/ G2!Timeout(m)
    \/ G2!BecomeLeader(m)
    \/ G2!Heartbeat(m)
    \/ G2!StepDown(m)
    \/ G2!LeaderLeaseExpiry(m)
    \/ G2!BeginWrite(m)
    \/ G2!WALSyncComplete(m)
    \/ G2!RAFTCommitWrite(m)
    \/ G2!AtomicCompleteWriteAndAck(m)
    \/ G2!FlushStart(m)
    \/ G2!FlushCommitHFiles(m)
    \/ G2!FlushRAFTPropose(m)
    \/ G2!FlushRAFTCommit(m)
    \/ G2!FlushComplete(m)
    \/ G2!AtomicFollowerBatchApply(m)
    \/ G2MasterConfirmPromotion(m)
    \/ G2!PromotionComplete(m)
    \/ G2!NewMemberBootstrap(m)

\* G2's GroupDataPathNext with MasterConfirmPromotion replaced by
\* the MetaReady-gated version.
G2GroupDataPathNextMetaGated ==
    \/ \E m \in Members     : G2GatedMemberDataPathActions(m)
    \/ \E c, v \in Members  : G2!RequestVote(c, v)
    \/ \E l, f \in Members  : G2!InstallSnapshot(l, f)
    \/ \E m \in Members     : G2!FollowerApplyMarker(m)
    \/ G2!NewLeaderCommitOrphanEntry

----
(* ---- Next-state relation and specification ---- *)

Next ==
    \* G1 (META) uses base spec actions unmodified.
    \* G2 (non-META) uses MetaReady-gated MasterConfirmPromotion.
    \/ (G1!GroupNext /\ UNCHANGED g2_vars)
    \/ (G2GroupNextMetaGated /\ UNCHANGED g1_vars)
    \* Shared-impact actions
    \/ \E m \in Members : MultiGroupClockTick(m)
    \/ \E m \in Members : MultiGroupCrashRestart(m)
    \/ MultiGroupCreatePartition
    \/ MultiGroupHealPartition
    \* Unified log GC (replaces per-group RaftLogGC)
    \/ \E m \in Members : UnifiedLogGC(m)

Spec == Init /\ [][Next]_vars

----
(* ---- Safety properties ---- *)

MultiGroupTypeOK == G1!TypeOK /\ G2!TypeOK

PerGroupSafety ==
    /\ G1!LeaderUniqueness          /\ G2!LeaderUniqueness
    /\ G1!LeaseImpliesLeadership    /\ G2!LeaseImpliesLeadership
    /\ G1!LeaseExpiresBeforeElection /\ G2!LeaseExpiresBeforeElection
    /\ G1!CatchUpDataIntegrity      /\ G2!CatchUpDataIntegrity
    /\ G1!WriteBarrierSafety        /\ G2!WriteBarrierSafety
    /\ G1!FollowerSeqIdConsistency  /\ G2!FollowerSeqIdConsistency
    /\ G1!NoOrphanMemstoreDrop      /\ G2!NoOrphanMemstoreDrop
    /\ G1!FlushDropBoundary          /\ G2!FlushDropBoundary
    /\ G1!FollowerFlushMemstoreDrop /\ G2!FollowerFlushMemstoreDrop
    /\ G1!HFilesBeforeFlushMarker   /\ G2!HFilesBeforeFlushMarker
    /\ G1!PromotionReadWriteGuard   /\ G2!PromotionReadWriteGuard
    /\ G1!PromotionMVCCContinuity   /\ G2!PromotionMVCCContinuity
    /\ G1!CatchUpCompleteness       /\ G2!CatchUpCompleteness

====
