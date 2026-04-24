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
EXTENDS DualGroupBase

vars == <<clock, partition, g1_vars, g2_vars>>

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
                   flushPhase_1, flushSeqId_1, snapshotMaxSeqId_1, flushDropBound_1,
                   promotionPhase_1, masterConfirmedTerm_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, snapshotMaxSeqId_2, flushDropBound_2,
                   promotionPhase_2, masterConfirmedTerm_2>>

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
                   flushDropBound_1, masterConfirmedTerm_1,
                   currentTerm_2, votedFor_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   flushDropBound_2, masterConfirmedTerm_2>>

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
                   flushPhase_1, flushSeqId_1, snapshotMaxSeqId_1, flushDropBound_1,
                   promotionPhase_1, masterConfirmedTerm_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2,
                   leaseRemaining_2, timerRemaining_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, snapshotMaxSeqId_2, flushDropBound_2,
                   promotionPhase_2, masterConfirmedTerm_2>>

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
    \/ G2!GatedMemberActionsNoMasterConfirm(m)
    \/ G2MasterConfirmPromotion(m)

\* G2's GroupNext with MasterConfirmPromotion replaced by the
\* MetaReady-gated version.
G2GroupNextMetaGated ==
    \/ \E m \in Members : G2GatedMemberActions(m)
    \/ G2!UngatedGroupActions

\* G2's data-path single-member actions with MasterConfirmPromotion
\* replaced by the MetaReady-gated version.
G2GatedMemberDataPathActions(m) ==
    \/ G2!ElectionAndLeadershipActions(m)
    \/ G2!WritePathCommonActions(m)
    \/ G2!AtomicCompleteWriteAndAck(m)
    \/ G2!FlushActions(m)
    \/ G2!AtomicFollowerBatchApply(m)
    \/ G2MasterConfirmPromotion(m)
    \/ G2!PromotionComplete(m)
    \/ G2!NewMemberBootstrap(m)

\* G2's GroupDataPathNext with MasterConfirmPromotion replaced by
\* the MetaReady-gated version.
G2GroupDataPathNextMetaGated ==
    \/ \E m \in Members : G2GatedMemberDataPathActions(m)
    \/ G2!UngatedGroupActions

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

====
