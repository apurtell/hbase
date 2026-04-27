---- MODULE DualGroupBase ----
(*
 * Shared infrastructure for dual-group RaftRegionReplica compositions.
 *
 * Contains variable declarations, INSTANCE blocks, variable tuples,
 * and per-group safety invariants common to MultiGroupRaftRegionReplica
 * and MergeRaftRegionReplica.  Both modules EXTENDS this module and
 * add their composition-specific logic.
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

\* ---- Group 1 per-group state (24 variables) ----
VARIABLES
    role_1, currentTerm_1, votedFor_1, votesGranted_1, raftLog_1,
    leaseRemaining_1, timerRemaining_1,
    nextSeqId_1, committedEntries_1, markerEntries_1,
    flushMarkerEntries_1, hdfsHFiles_1,
    memstore_1, fApplyBatch_1,
    writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
    flushPhase_1, flushSeqId_1, snapshotMaxSeqId_1, flushDropBound_1,
    promotionPhase_1, masterConfirmedTerm_1

\* ---- Group 2 per-group state (24 variables) ----
VARIABLES
    role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
    leaseRemaining_2, timerRemaining_2,
    nextSeqId_2, committedEntries_2, markerEntries_2,
    flushMarkerEntries_2, hdfsHFiles_2,
    memstore_2, fApplyBatch_2,
    writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
    flushPhase_2, flushSeqId_2, snapshotMaxSeqId_2, flushDropBound_2,
    promotionPhase_2, masterConfirmedTerm_2

\* ---- Variable tuples ----

g1_vars == <<role_1, currentTerm_1, votedFor_1, votesGranted_1, raftLog_1,
             leaseRemaining_1, timerRemaining_1,
             nextSeqId_1, committedEntries_1, markerEntries_1,
             flushMarkerEntries_1, hdfsHFiles_1,
             memstore_1, fApplyBatch_1,
             writePhase_1, walSync_1, raftCommitted_1, writeSeqId_1,
             flushPhase_1, flushSeqId_1, snapshotMaxSeqId_1, flushDropBound_1,
             promotionPhase_1, masterConfirmedTerm_1>>

g2_vars == <<role_2, currentTerm_2, votedFor_2, votesGranted_2, raftLog_2,
             leaseRemaining_2, timerRemaining_2,
             nextSeqId_2, committedEntries_2, markerEntries_2,
             flushMarkerEntries_2, hdfsHFiles_2,
             memstore_2, fApplyBatch_2,
             writePhase_2, walSync_2, raftCommitted_2, writeSeqId_2,
             flushPhase_2, flushSeqId_2, snapshotMaxSeqId_2, flushDropBound_2,
             promotionPhase_2, masterConfirmedTerm_2>>

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
    snapshotMaxSeqId <- snapshotMaxSeqId_1,
    flushDropBound  <- flushDropBound_1,
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
    snapshotMaxSeqId <- snapshotMaxSeqId_2,
    flushDropBound  <- flushDropBound_2,
    promotionPhase  <- promotionPhase_2,
    masterConfirmedTerm <- masterConfirmedTerm_2

Majority == (Cardinality(Members) \div 2) + 1

----
(* ---- Per-group safety invariants ---- *)

PerGroupSafety ==
    /\ G1!LeaderUniqueness           /\ G2!LeaderUniqueness
    /\ G1!LeaseImpliesLeadership     /\ G2!LeaseImpliesLeadership
    /\ G1!LeaseExpiresBeforeElection /\ G2!LeaseExpiresBeforeElection
    /\ G1!CatchUpDataIntegrity       /\ G2!CatchUpDataIntegrity
    /\ G1!NoFollowerExposureRollback /\ G2!NoFollowerExposureRollback
    /\ G1!WriteBarrierSafety         /\ G2!WriteBarrierSafety
    /\ G1!FollowerSeqIdConsistency   /\ G2!FollowerSeqIdConsistency
    /\ G1!NoOrphanMemstoreDrop       /\ G2!NoOrphanMemstoreDrop
    /\ G1!FlushDropBoundary          /\ G2!FlushDropBoundary
    /\ G1!FollowerFlushMemstoreDrop  /\ G2!FollowerFlushMemstoreDrop
    /\ G1!HFilesBeforeFlushMarker    /\ G2!HFilesBeforeFlushMarker
    /\ G1!PromotionReadWriteGuard    /\ G2!PromotionReadWriteGuard
    /\ G1!PromotionMVCCContinuity    /\ G2!PromotionMVCCContinuity
    /\ G1!CatchUpCompleteness        /\ G2!CatchUpCompleteness

====
