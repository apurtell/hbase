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
 * Per-group actions are dispatched via G1!GroupNext / G2!GroupNext
 * (the per-group subset of Next defined in RaftRegionReplica.tla).
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
    promotionPhase_1,
    hibernateState_1

\* ---- Group 2 per-group state (22 variables) ----
VARIABLES
    role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
    leaseRemaining_2, timerRemaining_2,
    nextSeqId_2, committedEntries_2, markerEntries_2,
    flushMarkerEntries_2, hdfsHFiles_2,
    memstore_2, fApplyBatch_2,
    writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
    flushPhase_2, flushSeqId_2,
    promotionPhase_2,
    hibernateState_2

\* ---- Variable tuples ----

g1_vars == <<role_1, currentTerm_1, votedFor_1, votesGranted_1, raftLog_1,
             leaseRemaining_1, timerRemaining_1,
             nextSeqId_1, committedEntries_1, markerEntries_1,
             flushMarkerEntries_1, hdfsHFiles_1,
             memstore_1, fApplyBatch_1,
             writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
             flushPhase_1, flushSeqId_1,
             promotionPhase_1, hibernateState_1>>

g2_vars == <<role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
             leaseRemaining_2, timerRemaining_2,
             nextSeqId_2, committedEntries_2, markerEntries_2,
             flushMarkerEntries_2, hdfsHFiles_2,
             memstore_2, fApplyBatch_2,
             writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
             flushPhase_2, flushSeqId_2,
             promotionPhase_2, hibernateState_2>>

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
    hibernateState  <- hibernateState_2

Majority == (Cardinality(Members) \div 2) + 1

----
(* ---- Initial state ---- *)

Init == G1!Init /\ G2!Init

----
(* ---- Shared-impact actions ---- *)

\* Clock tick decrements both groups' timers on the ticking member.
\* Guards check both groups' candidate and timer state.
MultiGroupClockTick(m) ==
    /\ clock[m] < MaxClock
    /\ \A other \in Members :
        clock[m] + 1 - clock[other] <= MaxClockDrift
    /\ ~\E c \in Members :
          \/ (role_1[c] = "Candidate"
              /\ Cardinality(votesGranted_1[c]) >= Majority)
          \/ (role_2[c] = "Candidate"
              /\ Cardinality(votesGranted_2[c]) >= Majority)
    /\ \E m2 \in Members :
          \/ timerRemaining_1[m2] > 0 \/ leaseRemaining_1[m2] > 0
          \/ timerRemaining_2[m2] > 0 \/ leaseRemaining_2[m2] > 0
    /\ clock' = [clock EXCEPT ![m] = @ + 1]
    /\ timerRemaining_1' = [timerRemaining_1 EXCEPT
                            ![m] = IF @ > 0 THEN @ - 1 ELSE 0]
    /\ leaseRemaining_1' = [leaseRemaining_1 EXCEPT
                            ![m] = IF @ > 0 THEN @ - 1 ELSE 0]
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
                   flushPhase_1, flushSeqId_1, promotionPhase_1, hibernateState_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, promotionPhase_2, hibernateState_2>>

\* Server crash resets volatile state for BOTH groups on the crashed
\* member.  Durable state (currentTerm, votedFor, raftLog) survives.
\* Guard: at least one group has volatile state worth resetting.
MultiGroupCrashRestart(m) ==
    /\ \/ role_1[m] # "Follower"
       \/ memstore_1[m] # {}
       \/ fApplyBatch_1[m] # {}
       \/ votesGranted_1[m] # {}
       \/ leaseRemaining_1[m] > 0
       \/ timerRemaining_1[m] # ElectionTimeoutMin
       \/ writePhase_1[m] # "Idle"
       \/ flushPhase_1[m] # "Idle"
       \/ promotionPhase_1[m] # "None"
       \/ hibernateState_1[m] # "Active"
       \/ role_2[m] # "Follower"
       \/ memstore_2[m] # {}
       \/ fApplyBatch_2[m] # {}
       \/ votesGranted_2[m] # {}
       \/ leaseRemaining_2[m] > 0
       \/ timerRemaining_2[m] # ElectionTimeoutMin
       \/ writePhase_2[m] # "Idle"
       \/ flushPhase_2[m] # "Idle"
       \/ promotionPhase_2[m] # "None"
       \/ hibernateState_2[m] # "Active"
    \* Reset group 1 volatile state
    /\ role_1'           = [role_1           EXCEPT ![m] = "Follower"]
    /\ votesGranted_1'   = [votesGranted_1   EXCEPT ![m] = {}]
    /\ leaseRemaining_1' = [leaseRemaining_1 EXCEPT ![m] = 0]
    /\ timerRemaining_1' = [timerRemaining_1 EXCEPT ![m] = ElectionTimeoutMin]
    /\ memstore_1'       = [memstore_1       EXCEPT ![m] = {}]
    /\ fApplyBatch_1'    = [fApplyBatch_1    EXCEPT ![m] = {}]
    /\ writePhase_1'     = [writePhase_1     EXCEPT ![m] = "Idle"]
    /\ walSync_1'        = [walSync_1        EXCEPT ![m] = "Pending"]
    /\ raftCommitted_1'  = [raftCommitted_1  EXCEPT ![m] = FALSE]
    /\ writeSeqId_1'     = [writeSeqId_1     EXCEPT ![m] = 0]
    /\ flushPhase_1'     = [flushPhase_1     EXCEPT ![m] = "Idle"]
    /\ flushSeqId_1'     = [flushSeqId_1     EXCEPT ![m] = 0]
    /\ promotionPhase_1' = [promotionPhase_1 EXCEPT ![m] = "None"]
    /\ hibernateState_1' = [hibernateState_1 EXCEPT ![m] = "Active"]
    \* Reset group 2 volatile state
    /\ role_2'           = [role_2           EXCEPT ![m] = "Follower"]
    /\ votesGranted_2'   = [votesGranted_2   EXCEPT ![m] = {}]
    /\ leaseRemaining_2' = [leaseRemaining_2 EXCEPT ![m] = 0]
    /\ timerRemaining_2' = [timerRemaining_2 EXCEPT ![m] = ElectionTimeoutMin]
    /\ memstore_2'       = [memstore_2       EXCEPT ![m] = {}]
    /\ fApplyBatch_2'    = [fApplyBatch_2    EXCEPT ![m] = {}]
    /\ writePhase_2'     = [writePhase_2     EXCEPT ![m] = "Idle"]
    /\ walSync_2'        = [walSync_2        EXCEPT ![m] = "Pending"]
    /\ raftCommitted_2'  = [raftCommitted_2  EXCEPT ![m] = FALSE]
    /\ writeSeqId_2'     = [writeSeqId_2     EXCEPT ![m] = 0]
    /\ flushPhase_2'     = [flushPhase_2     EXCEPT ![m] = "Idle"]
    /\ flushSeqId_2'     = [flushSeqId_2     EXCEPT ![m] = 0]
    /\ promotionPhase_2' = [promotionPhase_2 EXCEPT ![m] = "None"]
    /\ hibernateState_2' = [hibernateState_2 EXCEPT ![m] = "Active"]
    \* Preserve durable state for both groups
    /\ UNCHANGED <<clock, partition,
                   currentTerm_1, votedFor_1, raftLog_1,
                   nextSeqId_1, committedEntries_1, markerEntries_1,
                   flushMarkerEntries_1, hdfsHFiles_1,
                   currentTerm_2, votedFor_2, raftLog_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2>>

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
\* append-only consensus log.  A segment is eligible for deletion
\* only when ALL groups referenced in it have flushed past their
\* entries.  Both groups must have an applied flush marker on member
\* m, and entries below each group's chosen flush watermark are
\* removed from both groups' raftLogs simultaneously.
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
                   flushPhase_1, flushSeqId_1, promotionPhase_1, hibernateState_1,
                   role_2, currentTerm_2, votedFor_2, votesGranted_2,
                   leaseRemaining_2, timerRemaining_2,
                   nextSeqId_2, committedEntries_2, markerEntries_2,
                   flushMarkerEntries_2, hdfsHFiles_2,
                   memstore_2, fApplyBatch_2,
                   writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
                   flushPhase_2, flushSeqId_2, promotionPhase_2, hibernateState_2>>

----
(* ---- Next-state relation and specification ---- *)

Next ==
    \* Per-group steps via INSTANCE
    \/ (G1!GroupNext /\ UNCHANGED g2_vars)
    \/ (G2!GroupNext /\ UNCHANGED g1_vars)
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
    /\ G1!FlushWriteExclusion       /\ G2!FlushWriteExclusion
    /\ G1!FollowerFlushMemstoreDrop /\ G2!FollowerFlushMemstoreDrop
    /\ G1!HFilesBeforeFlushMarker   /\ G2!HFilesBeforeFlushMarker
    /\ G1!PromotionReadWriteGuard   /\ G2!PromotionReadWriteGuard
    /\ G1!PromotionMVCCContinuity   /\ G2!PromotionMVCCContinuity
    /\ G1!CatchUpCompleteness       /\ G2!CatchUpCompleteness

====
