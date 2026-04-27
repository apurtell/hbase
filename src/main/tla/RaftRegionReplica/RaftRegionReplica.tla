---- MODULE RaftRegionReplica ----
(*
 * Formal model of hbase-consensus leader election, leases,
 * crash-restart, network partitions, leader write path,
 * follower batch apply, flush and compaction marker handling,
 * flush protocol, flush crash recovery, follower flush-complete
 * handling, per-member RAFT log, orphan entry commitment by new
 * leader, promotion protocol with master confirmation and
 * leader-primary gap, RAFT log GC, old primary rejoin via
 * shared-storage catch-up, new member bootstrap via leader-based
 * network catch-up, promotion MVCC continuity with in-flight
 * writes, catch-up completeness with concurrent flush, and
 * and crash recovery.
 *
 * Models RAFT member roles (Leader, Follower, Candidate), term fencing,
 * leader lease acquisition via majority heartbeat acknowledgement, bounded
 * clock drift, crash-restart with durable votes (hard requirement), and
 * nondeterministic network partitions with heal.
 *
 * The leader write path models the parallel WAL sync + RAFT propose
 * pipeline.  The write proceeds as: mvcc.begin (atomic with WAL ring
 * buffer publish) -> fork(WAL sync to HDFS, RAFT propose to followers)
 * -> barrier join -> memstore.add -> mvcc.completeAndWait.  WAL sync
 * failure is modeled as a nondeterministic event; the mandated response
 * is leader crash (WALFailureAbort), consistent with HBase's existing
 * behavior on WAL sync failure.
 *
 * The follower apply callback models how committed RAFT entries are
 * applied to follower memstores using batch semantics.  The apply
 * pipeline collects consecutive mutation entries into a batch with a
 * single MVCC bracket: mvcc.beginAt(maxBatchSeqId) (advance
 * writePoint) -> stamp cells from all entries -> memstore.add
 * (combined cell set) -> mvcc.completeAndWait (advance readPoint).
 * This reduces MVCC overhead from N begin/complete cycles to 1 per
 * batch.  Marker entries (flush, compaction) break the batch boundary:
 * preceding mutations are applied as a batch, then the marker is
 * processed via mvcc.advanceTo(markerSeqId).
 *
 * Compaction markers are modeled via ProposeMarker, which atomically
 * commits a compaction-complete marker entry.  Flush markers use the
 * multi-step flush protocol described below.  The two marker types
 * are tracked separately: markerEntries contains all markers, while
 * flushMarkerEntries contains only flush markers.  This distinction
 * drives differential follower handling: flush markers trigger a
 * memstore drop (entries below the marker's seqId are now in HFiles),
 * while compaction markers only advance the MVCC point.
 *
 * The flush protocol models the 14-step primary flush sequence from the
 * design document, collapsed into safety-critical phases:
 *   FlushStart       (steps 1-7):  consume flushOpSeqId, record
 *                     snapshotMaxSeqId, take snapshot, write HFiles
 *   FlushCommitHFiles (step 8):    sfc.commit() — HFiles durable on HDFS
 *   FlushRAFTPropose  (step 9):    propose FLUSH_COMPLETE through RAFT
 *   FlushRAFTCommit   (step 10):   majority acknowledge — marker committed
 *   FlushComplete     (steps 11-14): drop memstore, COMMIT_FLUSH, GC log
 *
 * Snapshot-boundary flush: the write and flush pipelines run concurrently.
 * At FlushStart, snapshotMaxSeqId captures the highest seqId currently in
 * the leader's memstore — the true HFile coverage boundary.  Concurrent
 * in-flight writes receive seqIds above snapshotMaxSeqId (from the
 * monotonic nextSeqId counter) and are NOT included in the HFile
 * snapshot.  The memstore drop (FlushComplete) removes entries at or
 * below snapshotMaxSeqId, preserving concurrent writes.  This
 * eliminates the flush-induced write pause that mutual exclusion would
 * cause.  The FlushDropBoundary invariant verifies that the HFile
 * coverage boundary is always strictly below the flush marker seqId,
 * ensuring in-flight writes survive the drop.
 *
 * Flush crash recovery is modeled by the existing CrashRestart action
 * firing at each of the 4 non-terminal flush phases (FlushStarted,
 * HFilesCommitted, RAFTProposed, RAFTCommitted).  CrashRestart resets
 * volatile state (flushPhase, flushSeqId, memstore) while preserving
 * RAFT-committed state (committedEntries, markerEntries).  The
 * NoOrphanMemstoreDrop invariant verifies that no member reaches the
 * memstore-drop gate (RAFTCommitted) without the flush marker being
 * classified as a committed marker.  Phase ordering (FlushStarted ->
 * HFilesCommitted -> RAFTProposed -> RAFTCommitted -> Idle) is
 * enforced by action guards and verified by TLC's exhaustive
 * exploration of all reachable states.
 *
 * Follower flush-complete handling models the 6-step protocol from
 * the design document.  When a follower encounters a committed flush
 * marker, it: (1) completes any preceding mutation batch, (2) calls
 * mvcc.advanceTo(flushOpSeqId), (3) refreshes store file lists from
 * HDFS, (4) confirms HFiles are accessible (modeled as a guard on
 * hdfsHFiles), (5) drops memstore entries below flushOpSeqId, and
 * (6) GCs RAFT log entries.  HDFS visibility delay is modeled via
 * hdfsHFiles (a global set tracking flush seqIds whose HFiles are
 * durable on HDFS, populated by FlushCommitHFiles).  The
 * HFilesBeforeFlushMarker invariant verifies that the phase ordering
 * guarantees HFiles are on HDFS before the flush marker is committed.
 *
 * Phase-aware step-down: when a leader or responder steps down while
 * in RAFTCommitted flush phase, the memstore drop is completed
 * atomically (rather than aborted).  The flush marker is irrevocably
 * committed through RAFT; aborting would leave the member's memstore
 * inconsistent.  This is modeled in StepDown, BecomeLeader,
 * LeaderHeartbeat, and RequestVote.  The FollowerFlushMemstoreDrop
 * invariant verifies that after any follower has applied a flush
 * marker, no non-marker entry below it remains in the memstore.
 *
 * Per-member RAFT log: raftLog[m] is a per-member set of seqIds
 * representing the durable RAFT log on each member.  Following the
 * canonical TLA+ RAFT modeling practice (Ongaro's raft.tla, Microsoft
 * CCF's ccfraft.tla), the log is a first-class variable that survives
 * crashes (CrashRestart preserves raftLog).  We use a set rather than
 * a sequence because our spec needs only membership and majority
 * counting, not log ordering.  Entries are added to raftLog when
 * proposed through RAFT (RAFTCommitWrite, FlushRAFTPropose,
 * ProposeMarker) for the leader and all reachable responders.
 *
 * Orphan entry commitment by new leader: when a leader crashes after
 * proposing an entry (e.g., mid-flush at RAFTProposed) but before the
 * entry is committed, the entry may exist in a majority of members'
 * durable logs.  The NewLeaderCommitOrphanEntry action models the new
 * leader's AdvanceCommitIndex: any entry present in a majority of
 * raftLogs but not yet in committedEntries is nondeterministically
 * committed.  Entry type (flush marker vs. mutation) is classified by
 * checking s \in hdfsHFiles.  The CatchUpDataIntegrity invariant
 * verifies that every committed entry is recoverable (majority of
 * logs or covered by a flush marker with HFiles on HDFS).  The
 * HFilesBeforeFlushMarker invariant verifies that overlapping
 * committed flush markers (orphan + new leader's) both have HFiles.
 *
 * Promotion protocol and leader-primary gap: when a candidate wins the
 * RAFT election and becomes Leader (BecomeLeader), it enters the
 * "Promoting" phase.  During this phase, isLeader() returns true (the
 * member holds the Leader role and a valid lease), but the region has
 * not yet acquired a WAL reference.  Writes must be rejected during
 * this gap.  The promotion protocol proceeds in three phases modeled
 * by three promotionPhase transitions:
 *
 *   Promoting -> AwaitingMaster:  MasterConfirmPromotion fires when
 *     the leader has consumed all applicable RAFT log entries
 *     (ApplicableEntries(m) = {}), holds a valid lease, and the
 *     master has not already confirmed a higher term for this group
 *     (currentTerm[m] > masterConfirmedTerm).  This models the
 *     master's ReportLeaderElection validation and META update.
 *
 *   AwaitingMaster -> Complete:  PromotionComplete fires when master
 *     confirmation has been received (promotionPhase = AwaitingMaster),
 *     the leader still holds a valid lease, and no new committed entries
 *     need applying (ApplicableEntries(m) = {}).  This models the
 *     local promotion steps: setReadOnly(false) and WAL reference
 *     acquisition.
 *
 * The master is modeled as a nondeterministic oracle with a term-fencing
 * guard (masterConfirmedTerm), consistent with how the master is modeled
 * in the split and merge lifecycle modules (MasterOpenDaughter,
 * MasterOpenMerged).  BeginWrite, FlushStart, and ProposeMarker all
 * guard on promotionPhase[m] = "Complete".  The PromotionReadWriteGuard
 * invariant verifies that no write pipeline is active without
 * promotion completion, now with master confirmation included in the
 * definition of completion.
 *
 * RAFT log GC and old primary rejoin: after a flush completes, log
 * entries below the flush seqId are GC-eligible because the data they
 * contain is now in HFiles on HDFS.  RaftLogGC models this by removing
 * entries below an applied flush marker from raftLog[m].  When a
 * crashed primary recovers, it has two catch-up paths: (1) if the
 * leader still has the needed entries in its log, normal AppendEntries
 * delivers them and the follower applies via FollowerBeginBatchApply /
 * FollowerApplyMarker (already modeled); (2) if the needed entries
 * have been GC'd from the leader's log, the leader sends a
 * CatchUpReference containing HFile paths and the flush seqId.  The
 * follower loads HFiles from HDFS and starts with an empty memstore
 * for post-flush entries.  InstallSnapshot models this second path:
 * the follower's memstore is set to the flush boundary (entries below
 * the flush seqId are dropped, the flush marker is added as an
 * mvcc.advanceTo point), and the follower's raftLog is truncated to
 * the snapshot point.  This replaces standard RAFT's snapshot chunk
 * transfer with the shared-storage catch-up described in the design
 * document.  The CatchUpDataIntegrity invariant allows entries
 * subsumed by a committed flush marker to be absent from a majority
 * of logs (they are in HFiles), and verifies that every committed
 * entry is recoverable through at least one path (RAFT logs or
 * HFiles on HDFS).
 *
 * New member bootstrap: when a member's instance is replaced (e.g.,
 * a new Kubernetes pod with no persistent local state), all local
 * state is lost — raftLog, currentTerm, votedFor, memstore.  Unlike
 * CrashRestart (which preserves durable local state), bootstrap
 * models total state loss.  NewMemberBootstrap atomically resets all
 * state and recovers the raftLog via RAFT log entries from the leader
 * (modeled by setting raftLog to the leader's raftLog union all
 * committed entries not covered by a flush marker; the union
 * compensates for this model's omission of the RAFT log up-to-date
 * election check, which in the real system guarantees the leader
 * has all committed entries).  The member starts with an empty
 * memstore; existing HFiles are discovered by processing committed
 * flush markers through the normal follower apply path
 * (FollowerApplyMarker), which refreshes store files and drops
 * memstore entries below the flush watermark.  This enables TLC to
 * verify safety under all interleavings of catch-up entry
 * application with concurrent leader flush.  After bootstrap, normal
 * follower apply actions rebuild the memstore from all committed
 * entries, including flush markers.
 *
 * Promotion MVCC continuity with in-flight writes: when the old
 * leader crashes with a write in the WAL pipeline (after
 * mvcc.begin, before barrier join), the write's seqId may already
 * be RAFT-committed (RAFTCommitWrite fired) but not yet applied
 * to the old leader's memstore (CompleteWrite did not fire).
 * The new leader picks up this entry during promotion step 1
 * (finish consuming RAFT log entries, modeled by follower apply
 * actions during the Promoting phase).  PromotionComplete
 * requires ApplicableEntries(m) = {}, ensuring all committed
 * entries are in memstore before the new leader accepts writes.
 * Entries committed after promotion (e.g., orphan flush markers
 * committed by NewLeaderCommitOrphanEntry when a partition heals)
 * are atomically applied to the leader's memstore within the same
 * action, mirroring the consensus core's single-threaded
 * AdvanceCommitIndexTask + runOperation() callback (one
 * RaftNodeExecutor per group).  The PromotionMVCCContinuity
 * invariant verifies that no committed entry is unapplied outside
 * the leader's active write pipeline, ensuring no MVCC sequence
 * gaps after promotion.
 *
 * Sequence IDs and MVCC state: nextSeqId is a global monotonic counter,
 * writeSeqId tracks the leader's current write seqId, flushSeqId tracks
 * the leader's current flush seqId (flushOpSeqId), snapshotMaxSeqId
 * tracks the leader's current flush HFile coverage boundary (the max
 * seqId in the memstore at FlushStart time), committedEntries is the
 * set of RAFT-committed seqIds, markerEntries is the subset of
 * committedEntries that are markers (vs mutations), memstore is a
 * per-member set of applied/processed seqIds (including marker seqIds,
 * which represent mvcc.advanceTo points), fApplyBatch is per-member
 * follower batch apply state (set of mutation seqIds being applied),
 * and flushDropBound is a global function mapping flush marker seqIds
 * to their HFile coverage boundaries (snapshotMaxSeqId recorded at
 * FlushStart time, persisted for follower marker application).
 * The MVCC writePoint is derived (not tracked as state) via
 * MVCCWritePoint(m), which computes the max of all active seqIds
 * (memstore + in-flight write + in-flight batch apply).  This reduces
 * the state space by eliminating a per-member variable without
 * compromising the invariant: in all reachable states, the derivation
 * matches the value that explicit tracking would produce.
 *
 * Implementation grounding:  This spec models hbase-consensus's
 * election protocol with clock-drift-compensated lease extension.
 * Each spec action maps onto a code path in the consensus core
 * (the consensus core is the single-threaded actor in
 * hbase-consensus/src/main/java/org/apache/hadoop/hbase/consensus
 * derived from the MicroRaft baseline; one RaftNodeExecutor per
 * group serializes all state mutations).  The lease itself is
 * represented in the implementation by the explicit
 * LeaderState.leaseExpiryMillis field, refreshed monotonically on
 * each ack from a voting follower (LeaderHeartbeatAckHandler and
 * AppendEntriesSuccessResponseHandler) and re-evaluated on every
 * heartbeat tick by RaftNodeImpl.demoteToFollowerIfLeaseExpired,
 * which steps the leader down to Follower if leaseExpiryMillis <= now.
 * The spec collapses this absolute-deadline representation to a
 * relative countdown (leaseRemaining as ticks remaining), which
 * collapses functionally equivalent states that differ only in
 * absolute clock position; the LeaderHeartbeat action atomically
 * refreshes the leader's lease as part of the same heartbeat round
 * that resets responder election timers.
 *
 * Timers and leases in the spec use relative countdown
 * representation (ticks remaining) rather than absolute deadlines,
 * collapsing functionally equivalent states that differ only in
 * absolute clock position.
 *
 * Implementation features intentionally abstracted (not modeled):
 *
 *   - The wire-level distinction between LeaderHeartbeat (lightweight,
 *     steady-state liveness) and AppendEntriesRequest (log replication,
 *     snapshot trigger, matchIndex discovery, membership-op preparation).
 *     The spec has a single atomic LeaderHeartbeat action that captures
 *     both the liveness effect on followers (election-timer reset) and
 *     the leader's lease refresh; the implementation splits this round
 *     on the wire into a LeaderHeartbeat broadcast plus per-follower
 *     LeaderHeartbeatAck responses (see SweepingHeartbeatScheduler),
 *     but the round must be modeled atomically because the lease-safety
 *     argument requires the lease refresh and the quorum of follower
 *     election-timer resets to be causally bound by the same round-trip.
 *     Log replication is abstracted into atomic raftLog updates inside
 *     RAFTCommitWrite / FlushRAFTPropose / ProposeMarker.
 *
 *   - The lastVerifiedLogIndex clamp on commit-index advancement from
 *     heartbeats (LeaderHeartbeatHandler / AppendEntriesRequestHandler
 *     and LeaderState.lastVerifiedLogIndex).  The spec's atomic
 *     RAFTCommitWrite makes a per-follower verified watermark
 *     unnecessary at this abstraction level.
 *
 *   - Linearizable queries (QueryState, querySequenceNumber, the
 *     fail-pending + bump-QSN-on-leader-self-removal handling).  Reads
 *     do not flow through the consensus layer in the spec (see README).
 *
 *   - REMOVE_MEMBER / ADD_LEARNER / ADD_OR_PROMOTE_TO_FOLLOWER
 *     UpdateRaftGroupMembersOp entries as replicated log entries,
 *     including leader self-removal that drives the node into
 *     RaftNodeStatus.TERMINATED.  Membership in the spec is the static
 *     CONSTANT Members set.
 *
 *   - LEARNER role / non-voting members.  The spec's role variable
 *     ranges over {Follower, Candidate, Leader}.
 *
 *   - PreVote as a distinct round.  The spec subsumes PreVote into
 *     the leader-stickiness guard on RequestVote (described in that
 *     action's comment).
 *
 *   - Chunked InstallSnapshot transfer (SnapshotChunkCollector).
 *     The spec models the design-target shared-storage CatchUpReference
 *     path as a single atomic action.  Both paths are observationally
 *     equivalent at this abstraction level: the follower's log is
 *     truncated to the snapshot index and the data is recoverable
 *     (HFiles on HDFS in the design-target path; replayed chunks plus
 *     locally-flushed HFiles in the chunked path).
 *
 *   - DurableLogStore on-disk files. The spec captures safety-relevant
 *     consequences via CrashRestartWithLogLoss.
 *
 * Write path actions model the leader's HRegion.doMiniBatchMutate()
 * pipeline for RAFT-enabled regions: BeginWrite (doWALAppend, step 3),
 * WALSyncComplete/WALSyncFail (wal.sync, step 4a), RAFTCommitWrite
 * (consensus.propose, step 4b), CompleteWrite (barrier + memstore.add +
 * mvcc.completeAndWait, steps 5-8), AckWrite (return to client, step 9),
 * WALFailureAbort (RS abort on WAL sync failure).
 *
 * Follower batch apply actions model the consensus apply callback on
 * followers: FollowerBeginBatchApply (collect consecutive mutation
 * entries up to the next marker boundary, mvcc.beginAt(maxBatchSeqId),
 * advancing writePoint), FollowerCompleteBatchApply (stamp cells from
 * all entries + memstore.add + mvcc.completeAndWait, advancing
 * readPoint), FollowerApplyMarker (mvcc.advanceTo(markerSeqId) when
 * the next unapplied entry is a marker, advancing writePoint and
 * readPoint past the marker's seqId).
 *
 * Spec constant to RaftConfig mapping:
 *
 *   Spec constant        RaftConfig parameter
 *   -------------------- -----------------------------------------
 *   ElectionTimeoutMin   leaderHeartbeatTimeoutMillis (follower
 *                        failure detection, the timing-critical
 *                        parameter for lease safety)
 *   LeaderLeaseDuration  leaderLeaseDurationMillis =
 *                          leaderHeartbeatTimeoutMillis
 *                          - 2 * maxClockDriftMillis
 *   MaxClockDrift        maxClockDriftMillis
 *
 * Vote durability is a hard requirement, not configurable;
 * hbase-consensus always uses a durable RaftStore.
 *
 * Durable log store: The consensus log is a single multiplexed append-only
 * log per RegionServer shared by every RAFT group on the server.
 * Each RaftNode receives a per-group adapter (GroupRaftStore) that
 * forwards every RaftStore call to the shared store under its bound
 * group id.  Records are tagged with their group id so a single
 * sequential write stream serves every group at once.
 *
 * Durability tiering on the consensus log:
 *
 *   - Sync-fsynced before the corresponding RPC response: term and
 *     vote (persistAndFlushTerm), local endpoint
 *     (persistAndFlushLocalEndpoint), and initial members
 *     (persistAndFlushInitialGroupMembers).  These are always durable
 *     across any crash.  The spec already treats currentTerm and
 *     votedFor as durable across CrashRestart, matching this tier.
 *
 *   - Coalesced fsync before commit: log entries, snapshot chunks,
 *     and truncation markers are enqueued without a per-record fsync,
 *     but every coalescing window of the writer thread that contains a
 *     RaftStore.flush() barrier ends with a single FileChannel.force
 *     covering the whole batch (see UnifiedRaftStore.flushBarrier).
 *     A follower's AppendEntriesSuccessResponse is sent only after
 *     RaftStore.flush() returns, and the leader's flushedLogIndex (its
 *     own contribution to the commit quorum, in FlushTask) is set only
 *     after RaftStore.flush() returns; the leader counts only on-disk
 *     acks toward commit-index advancement.  In other words, the
 *     implementation enforces fsync-before-commit.
 *
 *   - CRC-tail recovery on load(): on the first non-OK read result
 *     anywhere in the on-disk log, DurableLogStore.load() truncates
 *     the offending segment at the bad offset and deletes every
 *     segment with a higher id.  CRC failures and torn-tail
 *     truncations are treated identically.  The surviving log is a
 *     prefix of the pre-crash log.  Because every committed entry is
 *     fsynced before it can contribute to commit quorum, the suffix
 *     a single member loses to CRC-tail truncation never includes
 *     a committed entry that was not also majority-on-disk.
 *
 * Crash recovery flows through RAFT, not local repair: a node that
 * comes back with a shorter log is treated by the protocol simply as
 * out of date.  The RAFT log up-to-date check on RequestVote
 * prevents it from winning elections against peers with the full
 * committed log, and standard catchup paths refill the missing tail
 * (AppendEntries from a peer with intact log for small gaps,
 * InstallSnapshot via the shared-storage CatchUpReference for entries
 * below an applied flush marker).  CrashRestartWithLogLoss models
 * this behavior; see that action's header for details.
 *
 * HDFS WAL durability assumption:  the HBase WAL on HDFS is a
 * system-level durability mechanism for the leader's mutations.
 * With fsync-before-commit on the consensus log, a RAFT-committed
 * mutation is on a majority of disks the moment it is committed, so
 * CatchUpDataIntegrity's majority-raftLog or HFiles-on-HDFS recovery
 * paths are the primary durability guarantee. The HDFS WAL is
 * an additional system-level safety net for catastrophic correlated
 * faults that simultaneously destroy a majority's RAFT logs.
 *
 * Safety properties (15 invariants):
 *   RAFT consensus:
 *   - LeaderUniqueness: at most one leader per term
 *   - LeaseImpliesLeadership: a valid lease implies the Leader role
 *   - LeaseExpiresBeforeElection: at most one member holds a valid
 *     lease at any time, preventing stale reads across leader transitions
 *   - CatchUpDataIntegrity: every committed entry is recoverable via
 *     RAFT log replay (majority) or HFiles on HDFS (flush with durable
 *     HFiles); subsumes the weaker RaftLogConsistency
 *   - NoFollowerExposureRollback: every memstore-exposed seqId is
 *     recoverable via majority raftLogs or HFiles on HDFS, so an
 *     entry once visible to a Timeline-consistency reader cannot be
 *     rolled back by any combination of crashes
 *   Write path:
 *   - WriteBarrierSafety: a write is visible (Applied) only after
 *     both WAL sync and RAFT commit have completed; subsumes
 *     WALSyncFailureSafety (implied by contraposition)
 *   - FollowerSeqIdConsistency: every memstore entry is RAFT-committed
 *   Flush protocol:
 *   - NoOrphanMemstoreDrop: no member reaches the memstore-drop gate
 *     (RAFTCommitted phase) without the flush marker in markerEntries;
 *     subsumes FlushAtomicity (markerEntries ⊆ committedEntries)
 *   - FlushDropBoundary: every committed flush marker's HFile coverage
 *     boundary (flushDropBound) is strictly below its seqId, ensuring
 *     concurrent in-flight writes survive the memstore drop
 *   - FollowerFlushMemstoreDrop: after a follower applies a flush
 *     marker, no non-marker entry at or below flushDropBound remains
 *     in the memstore
 *   - HFilesBeforeFlushMarker: HFiles are on HDFS before the flush
 *     marker is committed through RAFT; subsumes NoFlushDuplication
 *   Promotion protocol:
 *   - PromotionReadWriteGuard: a write pipeline is active only when the
 *     member has completed promotion (master confirmation + WAL reference)
 *   - PromotionMVCCContinuity: for an active leader (valid lease) that
 *     has completed promotion, no committed entry is unapplied except
 *     the leader's own in-flight write
 *   Catch-up:
 *   - CatchUpCompleteness: once a follower has applied all committed
 *     entries (ApplicableEntries = {}, fApplyBatch = {}), its memstore
 *     is consistent — every committed entry is in memstore or covered
 *     by an applied flush marker (data is in HFiles on HDFS)
 *
 * BecomeLeader is modeled as atomic with the initial heartbeat round.
 * In the real protocol, a candidate that wins the election immediately
 * sends heartbeats to all reachable followers — the gap between winning
 * and heartbeating is microseconds, far below a clock tick.  This atomic
 * model ensures the lease and all followers' election timers are set in
 * the same logical instant as the role transition, which is the
 * prerequisite for the timing analysis that relates LeaderLeaseDuration
 * to ElectionTimeoutMin.  The LeaderHeartbeat action models subsequent
 * periodic heartbeats and lease renewal as a single atomic round (see
 * the LeaderHeartbeat action header for why the broadcast and lease
 * refresh must be modeled atomically).
 *
 * With network partitions, unreachable followers are excluded from the
 * responder set, naturally modeling partial heartbeat rounds where a
 * partitioned leader cannot refresh its lease once it loses a majority.
 *)
EXTENDS Naturals, FiniteSets

CONSTANTS
    Members,             \* The set of RAFT group members
    None,                \* Sentinel: "no vote cast"
    MaxTerm,             \* Upper bound on terms (finite model checking)
    LeaderLeaseDuration, \* Lease validity in clock ticks
    ElectionTimeoutMin,  \* Election timeout in clock ticks
    MaxClockDrift,       \* Max clock skew between any two members
    MaxClock,            \* Upper bound on local clocks
    MaxSeqId             \* Upper bound on sequence IDs (finite model checking)

ASSUME MembersAssumption     == IsFiniteSet(Members) /\ Cardinality(Members) >= 1
ASSUME NoneAssumption        == None \notin Members
ASSUME MaxTermAssumption     == MaxTerm \in Nat \ {0}
ASSUME LeaseAssumption       == LeaderLeaseDuration \in Nat \ {0}
ASSUME ElectionAssumption    == ElectionTimeoutMin \in Nat \ {0}
ASSUME DriftAssumption       == MaxClockDrift \in Nat
ASSUME MaxClockAssumption    == MaxClock \in Nat \ {0}
ASSUME MaxSeqIdAssumption    == MaxSeqId \in Nat \ {0}

Majority == (Cardinality(Members) \div 2) + 1

VARIABLES
    \* ---- RAFT consensus core ----
    role,               \* role[m]: Follower | Candidate | Leader
    currentTerm,        \* currentTerm[m]: monotonically increasing term
    votedFor,           \* votedFor[m]: who m voted for in this term, or None
    votesGranted,       \* votesGranted[m]: set of members who voted for m
    raftLog,            \* raftLog[m]: per-member set of seqIds in durable RAFT log (survives crashes)
    \* ---- Timing and leases ----
    clock,              \* clock[m]: local monotonic clock (bounded integer)
    leaseRemaining,     \* leaseRemaining[m]: countdown ticks until lease expires (0 = expired/none)
    timerRemaining,     \* timerRemaining[m]: countdown ticks until election timer fires (0 = expired)
    \* ---- Network model ----
    partition,          \* partition: set of <<m1, m2>> pairs unable to communicate
    \* ---- RAFT committed state ----
    nextSeqId,          \* nextSeqId: global monotonic sequence ID counter
    committedEntries,   \* committedEntries: set of RAFT-committed entry seqIds
    markerEntries,      \* markerEntries: subset of committed seqIds that are markers (flush, compaction)
    flushMarkerEntries, \* flushMarkerEntries: subset of markerEntries that are flush (not compaction) markers
    \* ---- Durable HDFS state ----
    hdfsHFiles,         \* hdfsHFiles: set of flush seqIds whose HFiles are durable on HDFS (survives crashes)
    \* ---- Per-member data state ----
    memstore,           \* memstore[m]: set of seqIds applied/processed by m
    fApplyBatch,        \* fApplyBatch[m]: set of mutation seqIds being applied as a batch (empty = idle)
    \* ---- Write pipeline ----
    writePhase,         \* writePhase[m]: write pipeline phase (Idle | Pending | Applied)
    walSync,            \* walSync[m]: WAL sync lifecycle (Pending | Done | Failed)
    raftCommitted,      \* raftCommitted[m]: RAFT commit completed for current write
    writeSeqId,         \* writeSeqId[m]: seqId assigned to m's current write (0 = none)
    \* ---- Flush pipeline ----
    flushPhase,         \* flushPhase[m]: flush phase (Idle | FlushStarted | HFilesCommitted | RAFTProposed | RAFTCommitted)
    flushSeqId,         \* flushSeqId[m]: seqId consumed by m's current flush (0 = none)
    snapshotMaxSeqId,   \* snapshotMaxSeqId[m]: max seqId in the memstore snapshot at FlushStart (0 = none/empty)
    flushDropBound,     \* flushDropBound[s]: maps flush marker seqId s to its HFile coverage boundary (snapshotMaxSeqId)
    \* ---- Promotion pipeline ----
    promotionPhase,     \* promotionPhase[m]: promotion state (None | Promoting | AwaitingMaster | Complete)
    masterConfirmedTerm \* masterConfirmedTerm: highest RAFT term confirmed by master for this group (0 = none)

vars == <<role, currentTerm, votedFor, votesGranted, raftLog,
          clock, leaseRemaining, timerRemaining, partition,
          nextSeqId, committedEntries, markerEntries, flushMarkerEntries,
          hdfsHFiles, memstore, fApplyBatch,
          writePhase, walSync, raftCommitted, writeSeqId,
          flushPhase, flushSeqId, snapshotMaxSeqId, flushDropBound,
          promotionPhase, masterConfirmedTerm>>

writeVars == <<writePhase, walSync, raftCommitted, writeSeqId>>

flushVars == <<flushPhase, flushSeqId, snapshotMaxSeqId>>

timerVars == <<clock, leaseRemaining, timerRemaining>>

promotionVars == <<promotionPhase, masterConfirmedTerm>>

globalCommitVars == <<nextSeqId, committedEntries, markerEntries,
                      flushMarkerEntries, hdfsHFiles>>

----
(* ---- Type invariant ---- *)

TypeOK ==
    /\ role \in [Members -> {"Follower", "Candidate", "Leader"}]
    /\ currentTerm \in [Members -> 0..MaxTerm]
    /\ votedFor \in [Members -> Members \union {None}]
    /\ votesGranted \in [Members -> SUBSET Members]
    /\ raftLog \in [Members -> SUBSET (1..MaxSeqId)]
    /\ clock \in [Members -> 0..MaxClock]
    /\ leaseRemaining \in [Members -> 0..LeaderLeaseDuration]
    /\ timerRemaining \in [Members -> 0..ElectionTimeoutMin]
    /\ partition \subseteq (Members \X Members)
    /\ nextSeqId \in 1..(MaxSeqId + 1)
    /\ committedEntries \subseteq 1..MaxSeqId
    /\ markerEntries \subseteq 1..MaxSeqId
    /\ flushMarkerEntries \subseteq 1..MaxSeqId
    /\ hdfsHFiles \subseteq 1..MaxSeqId
    /\ memstore \in [Members -> SUBSET (1..MaxSeqId)]
    /\ fApplyBatch \in [Members -> SUBSET (1..MaxSeqId)]
    /\ writePhase \in [Members -> {"Idle", "Pending", "Applied"}]
    /\ walSync \in [Members -> {"Pending", "Done", "Failed"}]
    /\ raftCommitted \in [Members -> BOOLEAN]
    /\ writeSeqId \in [Members -> 0..MaxSeqId]
    /\ flushPhase \in [Members -> {"Idle", "FlushStarted", "HFilesCommitted", "RAFTProposed", "RAFTCommitted"}]
    /\ flushSeqId \in [Members -> 0..MaxSeqId]
    /\ snapshotMaxSeqId \in [Members -> 0..MaxSeqId]
    /\ flushDropBound \in [1..MaxSeqId -> 0..MaxSeqId]
    /\ promotionPhase \in [Members -> {"None", "Promoting", "AwaitingMaster", "Complete"}]
    /\ masterConfirmedTerm \in 0..MaxTerm

----
(* ---- Helper definitions ---- *)

CanCommunicate(m1, m2) == <<m1, m2>> \notin partition

LeaseValid(m) == leaseRemaining[m] > 0

IsLeader(m) == role[m] = "Leader" /\ LeaseValid(m)

SetMin(S) == CHOOSE s \in S : \A t \in S : s <= t

SetMax(S) == CHOOSE s \in S : \A t \in S : s >= t

\* Derived MVCC writePoint for member m.  Computed as the maximum of all
\* "active" seqIds: entries already in the memstore (including processed
\* marker seqIds, which model mvcc.advanceTo), plus any in-flight
\* leader write (writeSeqId during Pending/Applied phases), plus any
\* in-flight follower batch apply (all seqIds in fApplyBatch).  Returns
\* 0 when no seqIds are active (initial state or after crash-restart
\* with empty memstore).
\*
\* This derivation is equivalent to explicit writePoint tracking in all
\* reachable states.  The only divergence would occur after a leader
\* step-down mid-write (writePoint stays elevated, but writeSeqId resets
\* to 0 and memstore lacks the entry).  In that case the derivation
\* returns a value <= the explicit writePoint, but since no invariant
\* depends on writePoint being >= an abandoned (non-memstore, non-active)
\* seqId, correctness is preserved.
MVCCWritePoint(m) ==
    LET active == memstore[m]
                  \union (IF writePhase[m] \in {"Pending", "Applied"}
                          THEN {writeSeqId[m]} ELSE {})
                  \union fApplyBatch[m]
    IN IF active = {} THEN 0 ELSE SetMax(active)

\* Committed entries that follower m can still apply: committed, not
\* yet in the memstore, and above the flush drop boundary.  Models the
\* monotonic lastAppliedIndex invariant of the real RAFT apply
\* callback combined with RAFT log GC (follower flush-complete step 6),
\* which removes entries at or below snapshotMaxSeqId from the local
\* log.  The watermark is flushDropBound[f] (the actual HFile coverage
\* boundary), not the flush marker seqId itself.  Entries between
\* flushDropBound[f] and f (in-flight writes at flush time) are above
\* the watermark and remain applicable.
ApplicableEntries(m) ==
    LET appliedFlushMarkers == flushMarkerEntries \cap memstore[m]
    IN {s \in committedEntries \ memstore[m] :
            \A f \in appliedFlushMarkers : s > flushDropBound[f]}

Responders(m) ==
    {f \in Members \ {m} :
        /\ currentTerm[m] >= currentTerm[f]
        /\ CanCommunicate(m, f)}

NoHigherTermReachable(m) ==
    ~\E f \in Members \ {m} :
        /\ CanCommunicate(m, f)
        /\ currentTerm[f] > currentTerm[m]

QuorumReachable(m) ==
    /\ NoHigherTermReachable(m)
    /\ Cardinality(Responders(m)) + 1 >= Majority

PhaseAwareMemstoreDrop(m) ==
    IF flushPhase[m] = "RAFTCommitted"
    THEN {s \in memstore[m] : s > snapshotMaxSeqId[m]}
    ELSE memstore[m]

FollowerOrPromoting(m) ==
    \/ role[m] = "Follower"
    \/ promotionPhase[m] \in {"Promoting", "AwaitingMaster"}

MutationBatch(m) ==
    LET applicable == ApplicableEntries(m)
        applicableMarkers == applicable \cap markerEntries
        boundary == IF applicableMarkers # {}
                    THEN SetMin(applicableMarkers)
                    ELSE MaxSeqId + 1
    IN {s \in applicable \ markerEntries : s < boundary}

MutationBatchReady(m) ==
    /\ FollowerOrPromoting(m)
    /\ fApplyBatch[m] = {}
    /\ LET applicable == ApplicableEntries(m)
       IN /\ applicable # {}
          /\ SetMin(applicable) \notin markerEntries

WriteBarrierPassed(m) ==
    /\ writePhase[m] = "Pending"
    /\ walSync[m] = "Done"
    /\ raftCommitted[m]
    /\ role[m] = "Leader"

WritePipelineReset(m) ==
    /\ writePhase'    = [writePhase    EXCEPT ![m] = "Idle"]
    /\ walSync'       = [walSync       EXCEPT ![m] = "Pending"]
    /\ raftCommitted' = [raftCommitted EXCEPT ![m] = FALSE]
    /\ writeSeqId'    = [writeSeqId    EXCEPT ![m] = 0]

----
(* ---- Initial state ---- *)

Init ==
    \* RAFT consensus core
    /\ role            = [m \in Members |-> "Follower"]
    /\ currentTerm     = [m \in Members |-> 0]
    /\ votedFor        = [m \in Members |-> None]
    /\ votesGranted    = [m \in Members |-> {}]
    /\ raftLog         = [m \in Members |-> {}]
    \* Timing and leases
    /\ clock           = [m \in Members |-> 0]
    /\ leaseRemaining  = [m \in Members |-> 0]
    /\ timerRemaining  = [m \in Members |-> 0]
    \* Network
    /\ partition       = {}
    \* Committed state
    /\ nextSeqId       = 1
    /\ committedEntries = {}
    /\ markerEntries   = {}
    /\ flushMarkerEntries = {}
    \* HDFS
    /\ hdfsHFiles      = {}
    \* Per-member data state
    /\ memstore        = [m \in Members |-> {}]
    /\ fApplyBatch     = [m \in Members |-> {}]
    \* Write pipeline
    /\ writePhase      = [m \in Members |-> "Idle"]
    /\ walSync         = [m \in Members |-> "Pending"]
    /\ raftCommitted   = [m \in Members |-> FALSE]
    /\ writeSeqId      = [m \in Members |-> 0]
    \* Flush pipeline
    /\ flushPhase      = [m \in Members |-> "Idle"]
    /\ flushSeqId      = [m \in Members |-> 0]
    /\ snapshotMaxSeqId = [m \in Members |-> 0]
    /\ flushDropBound  = [s \in 1..MaxSeqId |-> 0]
    \* Promotion pipeline
    /\ promotionPhase  = [m \in Members |-> "None"]
    /\ masterConfirmedTerm = 0

----
(* ---- Actions ---- *)

\* ---- RAFT election actions ----

\* A follower or candidate whose election timer has expired starts an
\* election: increment term, become Candidate, vote for self.
\*
\* Implementation: models LeaderElectionTimeoutTask triggering
\* toCandidate() in RaftNodeImpl (see also broadcastVoteRequest()).
\* The consensus core retains a PreVote round; this spec subsumes
\* pre-vote into the leader-stickiness guard on RequestVote, which
\* prevents voting before the election timer fires.  At this
\* abstraction level the pre-vote round only ever delays the term bump
\* — it does not change which candidate eventually wins, and the
\* leader-stickiness guard already prevents the disruptive-candidate
\* scenario PreVote was added to mitigate.
Timeout(m) ==
    /\ role[m] \in {"Follower", "Candidate"}
    /\ currentTerm[m] < MaxTerm
    /\ timerRemaining[m] = 0
    /\ currentTerm'    = [currentTerm    EXCEPT ![m] = @ + 1]
    /\ role'           = [role           EXCEPT ![m] = "Candidate"]
    /\ votedFor'       = [votedFor       EXCEPT ![m] = m]
    /\ votesGranted'   = [votesGranted   EXCEPT ![m] = {m}]
    /\ timerRemaining' = [timerRemaining EXCEPT ![m] = ElectionTimeoutMin]
    /\ leaseRemaining' = [leaseRemaining EXCEPT ![m] = 0]
    /\ fApplyBatch'    = [fApplyBatch    EXCEPT ![m] = {}]
    /\ UNCHANGED <<raftLog, clock, partition,
                   globalCommitVars, memstore,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* A candidate requests and receives a vote from another member (atomic).
\* Requires that candidate and voter can communicate (not partitioned).
\*
\* Leader-stickiness guard: the voter only grants a vote
\* if its election timer has expired, meaning it has not recently received
\* a heartbeat from the current leader.  This prevents a recently-
\* heartbeated follower from immediately voting for a new candidate,
\* which is critical for lease safety: it ensures the old leader's lease
\* expires before any follower can participate in a new election,
\* even by voting (not just by starting its own election).
\*
\* The voter's election timer is NOT reset on vote grant in this spec.
\* The implementation does reset it: VoteRequestHandler.handle() calls
\* node.electionTimerReset() on a successful grant (deferring the
\* voter's own election timer), but explicitly does NOT call
\* node.leaderHeartbeatReceived() — leader-stickiness still uses the
\* unmodified leader-heartbeat-received timestamp, so a recently
\* heartbeated follower will not grant the vote in the first place.
\* The spec models the conservative no-reset case purely as a safety
\* stress test: showing safety holds even when the voter's election
\* timer is not deferred bounds any race the deferral could mask.
\* The BecomeLeader action's atomic initial heartbeat resets all
\* reachable followers' timers immediately upon election, so the
\* practical gap between vote grant and timer reset is one atomic step.
\*
\* If the voter is a Leader in a lower term (possible when two leaders
\* coexist in different terms due to partitions), the voter steps down
\* and its write pipeline is reset (any in-flight write is abandoned).
\*
\* Implementation: models VoteRequestHandler.handle().  The
\* timerRemaining[voter] = 0 guard models the leader-stickiness check
\* (!node.isLeaderHeartbeatTimeoutElapsed()).  The vote-granting logic
\* models state.grantVote(), which calls persistAndFlushTerm() before
\* returning so that votedFor is durable before the response is sent.
RequestVote(candidate, voter) ==
    /\ role[candidate] = "Candidate"
    /\ candidate # voter
    /\ CanCommunicate(candidate, voter)
    /\ timerRemaining[voter] = 0
    /\ currentTerm[candidate] >= currentTerm[voter]
    /\ \/ currentTerm[candidate] > currentTerm[voter]
       \/ /\ currentTerm[candidate] = currentTerm[voter]
          /\ votedFor[voter] = None
    /\ currentTerm'   = [currentTerm   EXCEPT ![voter] = currentTerm[candidate]]
    /\ votedFor'      = [votedFor      EXCEPT ![voter] = candidate]
    /\ role'          = [role          EXCEPT ![voter] =
                            IF currentTerm[candidate] > currentTerm[voter]
                            THEN "Follower"
                            ELSE @]
    /\ leaseRemaining' = [leaseRemaining EXCEPT ![voter] = 0]
    /\ LET steppingDown == currentTerm[candidate] > currentTerm[voter]
       IN /\ writePhase'    = [writePhase    EXCEPT ![voter] =
                                  IF steppingDown THEN "Idle" ELSE @]
          /\ walSync'       = [walSync       EXCEPT ![voter] =
                                  IF steppingDown THEN "Pending" ELSE @]
          /\ raftCommitted' = [raftCommitted EXCEPT ![voter] =
                                  IF steppingDown THEN FALSE ELSE @]
          /\ writeSeqId'    = [writeSeqId    EXCEPT ![voter] =
                                  IF steppingDown THEN 0 ELSE @]
          /\ votesGranted'  = [votesGranted  EXCEPT ![candidate] = @ \union {voter},
                                                    ![voter] =
                                  IF steppingDown THEN {} ELSE @]
          /\ memstore'      = [memstore      EXCEPT ![voter] =
                                  IF steppingDown THEN PhaseAwareMemstoreDrop(voter)
                                  ELSE @]
          /\ flushPhase'    = [flushPhase    EXCEPT ![voter] =
                                  IF steppingDown THEN "Idle" ELSE @]
          /\ flushSeqId'    = [flushSeqId    EXCEPT ![voter] =
                                  IF steppingDown THEN 0 ELSE @]
          /\ snapshotMaxSeqId' = [snapshotMaxSeqId EXCEPT ![voter] =
                                  IF steppingDown THEN 0 ELSE @]
          /\ promotionPhase' = [promotionPhase EXCEPT ![voter] =
                                  IF steppingDown THEN "None" ELSE @]
    /\ UNCHANGED <<raftLog, clock, timerRemaining, partition,
                   globalCommitVars, fApplyBatch,
                   flushDropBound, masterConfirmedTerm>>

\* A candidate with a majority of votes becomes leader AND immediately
\* sends its initial heartbeat round to all reachable followers (atomic).
\* In the real protocol, winning the election and sending the first
\* heartbeat happen within microseconds.  Modeling them atomically
\* ensures the lease and follower election timers are set in the same
\* logical instant as the role transition, preserving the timing
\* relationship LeaderLeaseDuration < ElectionTimeoutMin - 2*MaxClockDrift.
\*
\* Guard: if any reachable member has a higher term, the heartbeat
\* round discovers this via the rejection response, and the candidate
\* steps down instead of becoming leader.  The candidate must also be
\* able to heartbeat a majority of reachable, equal-or-lower-term members.
\*
\* Responders that were leaders in a lower term (possible during
\* partition-heal scenarios) have their write pipelines reset.
\*
\* Implementation: models VoteResponseHandler triggering
\* RaftNodeImpl.toLeader(), whose synchronous body initializes
\* leaseExpiryMillis = quorumResponseTimestamp(quorumSize, now)
\*                     + leaderLeaseDurationMillis
\* (using the freshly-recorded grant timestamps as the initial
\* responseTimestamp values), appends a no-op new-term entry via
\* appendNewTermEntry(), and broadcasts the initial AppendEntries
\* round.  The atomic initial heartbeat is justified by the consensus
\* core's single-threaded actor model (one RaftNodeExecutor per group):
\* no other work can interleave between the election win and the first
\* heartbeat.  The atomic-with-initial-heartbeat model in this spec
\* remains faithful for safety because the lease is set synchronously
\* in toLeader() before any other action can observe the new role.
BecomeLeader(m) ==
    /\ role[m] = "Candidate"
    /\ Cardinality(votesGranted[m]) >= Majority
    /\ QuorumReachable(m)
    /\ LET responders == Responders(m)
       IN
        /\ role' = [r \in Members |->
              IF r = m THEN "Leader"
              ELSE IF r \in responders THEN "Follower"
              ELSE role[r]]
        /\ currentTerm' = [r \in Members |->
              IF r \in responders THEN currentTerm[m] ELSE currentTerm[r]]
        /\ votedFor' = [r \in Members |->
              IF r \in responders /\ currentTerm[m] > currentTerm[r]
              THEN None ELSE votedFor[r]]
        /\ votesGranted' = [r \in Members |->
              IF r = m THEN {}
              ELSE IF r \in responders THEN {}
              ELSE votesGranted[r]]
        /\ timerRemaining' = [r \in Members |->
              IF r \in responders
              THEN ElectionTimeoutMin
              ELSE timerRemaining[r]]
        /\ leaseRemaining' = [r \in Members |->
              IF r = m THEN LeaderLeaseDuration
              ELSE IF r \in responders THEN 0
              ELSE leaseRemaining[r]]
        /\ writePhase' = [r \in Members |->
              IF r \in responders THEN "Idle" ELSE writePhase[r]]
        /\ walSync' = [r \in Members |->
              IF r \in responders THEN "Pending" ELSE walSync[r]]
        /\ raftCommitted' = [r \in Members |->
              IF r \in responders THEN FALSE ELSE raftCommitted[r]]
        /\ writeSeqId' = [r \in Members |->
              IF r \in responders THEN 0 ELSE writeSeqId[r]]
        /\ memstore' = [r \in Members |->
              IF r \in responders THEN PhaseAwareMemstoreDrop(r)
              ELSE memstore[r]]
        /\ flushPhase' = [r \in Members |->
              IF r \in responders THEN "Idle" ELSE flushPhase[r]]
        /\ flushSeqId' = [r \in Members |->
              IF r \in responders THEN 0 ELSE flushSeqId[r]]
        /\ snapshotMaxSeqId' = [r \in Members |->
              IF r \in responders THEN 0 ELSE snapshotMaxSeqId[r]]
        /\ promotionPhase' = [r \in Members |->
              IF r = m THEN "Promoting"
              ELSE IF r \in responders THEN "None"
              ELSE promotionPhase[r]]
        /\ UNCHANGED <<raftLog, clock, partition,
                       globalCommitVars, fApplyBatch,
                       flushDropBound, masterConfirmedTerm>>

\* ---- RAFT leadership actions ----

\* Leader runs one heartbeat round: broadcasts a heartbeat to all
\* responding followers (each resets its election timer) AND refreshes
\* its own lease atomically.  Non-leader leases on responders are
\* cleared.  Only followers whose term is <= the leader's term AND who
\* are reachable (not partitioned from the leader) respond; a follower
\* in a higher term or behind a partition would not respond.
\*
\* The action is modeled atomically even though the implementation
\* splits the round on the wire into two distinct messages
\* (LeaderHeartbeat broadcast + LeaderHeartbeatAck per-follower
\* response, see SweepingHeartbeatScheduler).  The split is an
\* implementation optimization (smaller, independent messages, finer
\* scheduling); at the spec abstraction level the round must be atomic
\* because the lease-safety argument requires the leader's lease
\* refresh and the quorum of follower election-timer resets to be
\* causally bound by the same round-trip.  The implementation
\* maintains this causality via the request-response correlation: an
\* ack only arrives at the leader because a heartbeat broadcast was
\* delivered to the follower, which called node.leaderHeartbeatReceived()
\* to reset its election timer before replying.  Modeling the broadcast
\* and the lease refresh as independent TLA+ actions broke this causal
\* link and admitted a counterexample to LeaseExpiresBeforeElection
\* in which the leader's lease was refreshed without any follower's
\* election timer being reset by this leader; TLC found a state with
\* two valid leases simultaneously (different terms, partitioned
\* leaders, both reachable to the same swing voter).
\*
\* Guard: if any reachable member has a higher term, the heartbeat
\* round discovers this via the rejection response, and the leader
\* steps down instead of broadcasting.  StepDown handles the actual
\* transition; this guard prevents the stale heartbeat.
\*
\* Responders that were leaders in a lower term (possible during
\* partition-heal scenarios) have their write pipelines reset.
\*
\* Implementation: models RaftNodeImpl.broadcastLeaderHeartbeat() called
\* from runHeartbeatTick (HeartbeatScheduler -> SweepingHeartbeatScheduler
\* or DefaultHeartbeatScheduler), composed with the cumulative effect of
\* LeaderHeartbeatAckHandler (per-follower) updating
\* FollowerState.responseTimestamp on each ack and recomputing
\* leaseExpiryMillis = quorumResponseTimestamp(quorumSize, now) +
\* leaderLeaseDurationMillis once a voting-member quorum has acked.
\* On the wire the broadcast is a LeaderHeartbeat message processed by
\* LeaderHeartbeatHandler on the follower; the handler resets the
\* election timer (leaderHeartbeatReceived()) and sends back a
\* LeaderHeartbeatAck.  AppendEntriesRequest is no longer used as a
\* steady-state liveness signal; it is reserved for log replication
\* catch-up, snapshot trigger, matchIndex discovery, and membership-op
\* preparation (sendCatchupAppendsIfNeeded).
LeaderHeartbeat(leader) ==
    /\ role[leader] = "Leader"
    /\ QuorumReachable(leader)
    /\ LET responders == Responders(leader)
       IN
        /\ currentTerm'   = [m \in Members |->
                IF m \in responders THEN currentTerm[leader] ELSE currentTerm[m]]
        /\ role'          = [m \in Members |->
                IF m \in responders THEN "Follower" ELSE role[m]]
        /\ votedFor'      = [m \in Members |->
                IF m \in responders /\ currentTerm[leader] > currentTerm[m]
                THEN None ELSE votedFor[m]]
        /\ votesGranted'  = [m \in Members |->
                IF m \in responders THEN {} ELSE votesGranted[m]]
        /\ timerRemaining' = [m \in Members |->
                IF m \in responders
                THEN ElectionTimeoutMin
                ELSE timerRemaining[m]]
        /\ leaseRemaining' = [m \in Members |->
                IF m = leader THEN LeaderLeaseDuration
                ELSE IF m \in responders THEN 0
                ELSE leaseRemaining[m]]
        /\ memstore'      = [m \in Members |->
                IF m \in responders THEN PhaseAwareMemstoreDrop(m)
                ELSE memstore[m]]
        /\ writePhase'    = [m \in Members |->
                IF m \in responders THEN "Idle" ELSE writePhase[m]]
        /\ walSync'       = [m \in Members |->
                IF m \in responders THEN "Pending" ELSE walSync[m]]
        /\ raftCommitted' = [m \in Members |->
                IF m \in responders THEN FALSE ELSE raftCommitted[m]]
        /\ writeSeqId'    = [m \in Members |->
                IF m \in responders THEN 0 ELSE writeSeqId[m]]
        /\ flushPhase'    = [m \in Members |->
                IF m \in responders THEN "Idle" ELSE flushPhase[m]]
        /\ flushSeqId'    = [m \in Members |->
                IF m \in responders THEN 0 ELSE flushSeqId[m]]
        /\ snapshotMaxSeqId' = [m \in Members |->
                IF m \in responders THEN 0 ELSE snapshotMaxSeqId[m]]
        /\ promotionPhase' = [m \in Members |->
                IF m \in responders THEN "None" ELSE promotionPhase[m]]
        /\ UNCHANGED <<raftLog, clock, partition,
                       globalCommitVars, fApplyBatch,
                       flushDropBound, masterConfirmedTerm>>

\* A member discovers a higher term and steps down to Follower.
\* Abstracts receiving any RPC carrying a higher term.
\* Requires that the member can observe the other's term (not partitioned).
\* Any in-flight write is abandoned (write pipeline reset).
\*
\* Implementation: models RaftNodeImpl.toFollower(higherTerm).  In the
\* current code, every handler that observes a strictly higher term in
\* an inbound message or response calls toFollower(higherTerm) before
\* doing any other work:
\*   - VoteRequestHandler
\*   - VoteResponseHandler
\*   - AppendEntriesRequestHandler
\*   - AppendEntriesSuccessResponseHandler
\*   - AppendEntriesFailureResponseHandler
\*   - InstallSnapshotRequestHandler
\*   - InstallSnapshotResponseHandler
\*   - LeaderHeartbeatHandler
\*   - LeaderHeartbeatAckHandler
\* The spec abstracts the "discover via any RPC" behavior into a single
\* atomic action guarded by reachability of some other member with a
\* higher term.
StepDown(m) ==
    /\ \E other \in Members :
        /\ other # m
        /\ currentTerm[other] > currentTerm[m]
        /\ CanCommunicate(m, other)
        /\ currentTerm' = [currentTerm EXCEPT ![m] = currentTerm[other]]
    /\ role'          = [role          EXCEPT ![m] = "Follower"]
    /\ votedFor'      = [votedFor      EXCEPT ![m] = None]
    /\ votesGranted'  = [votesGranted  EXCEPT ![m] = {}]
    /\ leaseRemaining'  = [leaseRemaining  EXCEPT ![m] = 0]
    /\ timerRemaining'  = [timerRemaining  EXCEPT ![m] = ElectionTimeoutMin]
    /\ memstore'        = [memstore        EXCEPT ![m] = PhaseAwareMemstoreDrop(m)]
    /\ writePhase'      = [writePhase      EXCEPT ![m] = "Idle"]
    /\ walSync'         = [walSync         EXCEPT ![m] = "Pending"]
    /\ raftCommitted'   = [raftCommitted   EXCEPT ![m] = FALSE]
    /\ writeSeqId'      = [writeSeqId      EXCEPT ![m] = 0]
    /\ flushPhase'      = [flushPhase      EXCEPT ![m] = "Idle"]
    /\ flushSeqId'      = [flushSeqId      EXCEPT ![m] = 0]
    /\ snapshotMaxSeqId' = [snapshotMaxSeqId EXCEPT ![m] = 0]
    /\ promotionPhase'  = [promotionPhase  EXCEPT ![m] = "None"]
    /\ UNCHANGED <<raftLog, clock, partition,
                   globalCommitVars, fApplyBatch,
                   flushDropBound, masterConfirmedTerm>>

\* A leader whose lease has expired (it could not refresh leaseExpiryMillis
\* via a quorum of follower acks within the lease duration) voluntarily
\* steps down to Follower.
\*
\* Unlike StepDown (which requires discovering a higher term from a
\* reachable member), LeaderLeaseExpiry fires when the leader simply
\* cannot refresh its lease — e.g., it is fully partitioned from the
\* quorum, or responders are slow.  The term is not bumped
\* (toFollower(currentTerm) preserves the current term when no higher
\* term is discovered), and votedFor is preserved (already voted in
\* this term).
\*
\* State cleanup (write/flush/promotion reset, memstore
\* flush-in-RAFTCommitted handling) is identical to StepDown: any
\* in-flight write or flush is abandoned, and the promotion phase is
\* reset.
\*
\* Implementation: models RaftNodeImpl.demoteToFollowerIfLeaseExpired,
\* which is called from RaftNodeImpl.runHeartbeatTick on every
\* heartbeat tick.  It recomputes leaseExpiryMillis from the freshest
\* quorum response timestamps via
\*   leaseExpiryMillis = quorumResponseTimestamp(quorumSize, now)
\*                       + leaderLeaseDurationMillis,
\* updates leaderState.leaseExpiryMillis monotonically, and calls
\* toFollower(state.term()) when leaseExpiryMillis <= now.  (The
\* legacy demoteToFollowerIfQuorumHeartbeatTimeoutElapsed helper still
\* exists but is no longer invoked from the heartbeat hot path.)
LeaderLeaseExpiry(m) ==
    /\ role[m] = "Leader"
    /\ leaseRemaining[m] = 0
    /\ role'             = [role            EXCEPT ![m] = "Follower"]
    /\ votesGranted'     = [votesGranted    EXCEPT ![m] = {}]
    /\ timerRemaining'   = [timerRemaining  EXCEPT ![m] = ElectionTimeoutMin]
    /\ memstore'         = [memstore        EXCEPT ![m] = PhaseAwareMemstoreDrop(m)]
    /\ writePhase'       = [writePhase      EXCEPT ![m] = "Idle"]
    /\ walSync'          = [walSync         EXCEPT ![m] = "Pending"]
    /\ raftCommitted'    = [raftCommitted   EXCEPT ![m] = FALSE]
    /\ writeSeqId'       = [writeSeqId      EXCEPT ![m] = 0]
    /\ flushPhase'       = [flushPhase      EXCEPT ![m] = "Idle"]
    /\ flushSeqId'       = [flushSeqId      EXCEPT ![m] = 0]
    /\ snapshotMaxSeqId' = [snapshotMaxSeqId EXCEPT ![m] = 0]
    /\ promotionPhase'   = [promotionPhase  EXCEPT ![m] = "None"]
    /\ fApplyBatch'      = [fApplyBatch     EXCEPT ![m] = {}]
    /\ UNCHANGED <<currentTerm, votedFor, raftLog, clock,
                   leaseRemaining, partition,
                   globalCommitVars,
                   flushDropBound, masterConfirmedTerm>>

\* ---- Timing actions ----

\* Advance member m's local clock by one tick.  Guarded by the bounded-drift
\* constraint: m's clock must not move more than MaxClockDrift ahead of
\* any other member's clock.
\*
\* Also guarded by the no-pending-leader constraint: no candidate with
\* majority votes is waiting to become leader.  In the real protocol,
\* a candidate becomes leader and sends its first heartbeat within
\* microseconds of receiving the deciding vote — far less than a clock
\* tick.  This guard prevents the model from exploring unrealistic
\* interleavings where many ticks pass between winning the election
\* and the atomic BecomeLeader+Heartbeat, which would decouple the
\* vote-time election timers from the heartbeat-time lease.
\*
\* Active-countdown guard: at least one member must have a positive
\* timer or lease.  When all countdowns are zero, clock advancement
\* only changes absolute clock values without decrementing anything
\* useful; these states are qualitatively equivalent regardless of
\* clock position.  Timeout fires at timerRemaining = 0 (no tick
\* needed), and BecomeLeader/Heartbeat set countdowns atomically,
\* so no interesting behavior is lost.
ClockTickGuard(m) ==
    /\ clock[m] < MaxClock
    /\ \A other \in Members :
        clock[m] + 1 - clock[other] <= MaxClockDrift
    /\ ~\E c \in Members :
          /\ role[c] = "Candidate"
          /\ Cardinality(votesGranted[c]) >= Majority
    /\ \E m2 \in Members :
          timerRemaining[m2] > 0 \/ leaseRemaining[m2] > 0

ClockTickEffect(m) ==
    /\ clock' = [clock EXCEPT ![m] = @ + 1]
    /\ timerRemaining' = [timerRemaining EXCEPT ![m] = IF @ > 0 THEN @ - 1 ELSE 0]
    /\ leaseRemaining' = [leaseRemaining EXCEPT ![m] = IF @ > 0 THEN @ - 1 ELSE 0]

ClockTick(m) ==
    /\ ClockTickGuard(m)
    /\ ClockTickEffect(m)
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   partition, globalCommitVars, memstore, fApplyBatch,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* ---- Crash recovery actions ----

\* A member crashes and immediately restarts.  Term and votedFor are
\* always durable (hbase-consensus requires a durable RaftStore).
\* All volatile state (role, votes received, lease, write pipeline,
\* memstore, MVCC state) is reset.
\*
\* Guard: the member must have some volatile state worth clearing.
\* A fresh follower (idle write pipeline, empty memstore, no batch,
\* no stale votes, no lease, timer at max) is already in the
\* post-crash state, so crashing it is a no-op.  Pruning this
\* eliminates redundant transitions without changing reachability.
\*
\* Implementation: models crash-recovery via RaftState.restore() from
\* RestoredRaftState.  currentTerm is preserved (UNCHANGED) — the
\* consensus core does NOT increment term on restart.  votedFor is
\* preserved by the durable RaftStore.  Volatile in-memory state — role, votes
\* received, leaseExpiryMillis (LeaderState), in-flight write/flush
\* pipeline state, memstore — is rebuilt from log replay rather than
\* persisted, matching the spec's reset of those variables.
CrashRestartGuard(m) ==
    \/ role[m] # "Follower"
    \/ memstore[m] # {}
    \/ fApplyBatch[m] # {}
    \/ votesGranted[m] # {}
    \/ leaseRemaining[m] > 0
    \/ timerRemaining[m] # ElectionTimeoutMin
    \/ writePhase[m] # "Idle"
    \/ flushPhase[m] # "Idle"
    \/ promotionPhase[m] # "None"

CrashRestartEffect(m) ==
    /\ role'             = [role            EXCEPT ![m] = "Follower"]
    /\ votesGranted'     = [votesGranted    EXCEPT ![m] = {}]
    /\ leaseRemaining'   = [leaseRemaining  EXCEPT ![m] = 0]
    /\ timerRemaining'   = [timerRemaining  EXCEPT ![m] = ElectionTimeoutMin]
    /\ memstore'         = [memstore        EXCEPT ![m] = {}]
    /\ fApplyBatch'      = [fApplyBatch     EXCEPT ![m] = {}]
    /\ writePhase'       = [writePhase      EXCEPT ![m] = "Idle"]
    /\ walSync'          = [walSync         EXCEPT ![m] = "Pending"]
    /\ raftCommitted'    = [raftCommitted   EXCEPT ![m] = FALSE]
    /\ writeSeqId'       = [writeSeqId      EXCEPT ![m] = 0]
    /\ flushPhase'       = [flushPhase      EXCEPT ![m] = "Idle"]
    /\ flushSeqId'       = [flushSeqId      EXCEPT ![m] = 0]
    /\ snapshotMaxSeqId' = [snapshotMaxSeqId EXCEPT ![m] = 0]
    /\ promotionPhase'   = [promotionPhase  EXCEPT ![m] = "None"]

CrashRestart(m) ==
    /\ CrashRestartGuard(m)
    /\ CrashRestartEffect(m)
    /\ UNCHANGED <<currentTerm, votedFor, raftLog, clock, partition,
                   globalCommitVars,
                   flushDropBound, masterConfirmedTerm>>

\* Models a crash-restart in which the durable RAFT log on m loses a
\* suffix relative to its pre-crash state.  Per the implementation, the
\* surviving log is a prefix bounded by the last fsync barrier and
\* possibly truncated further at a CRC failure or torn-write boundary.
\* The torn-write boundary is detected during DurableLogStore.load().  Term
\* and vote remain durable (sync-fsynced; see persistAndFlushTerm).
\* m is treated by the RAFT protocol as a follower whose log is out
\* of date; standard catchup paths (AppendEntries from a peer with
\* intact log for small gaps, InstallSnapshot via the shared-storage
\* CatchUpReference for entries below an applied flush marker) refill
\* the missing tail.
\*
\* Every entry in the lost suffix must remain recoverable either from
\* a majority of OTHER members' raftLogs (RAFT AppendEntries) or from a
\* flush marker with HFiles on HDFS (CatchUpReference). The check covers
\* uncommitted-but-replicated entries as well as committed ones,
\* because the spec models RAFT propose atomically. Once an entry has
\* been placed in a majority of raftLogs the leader has the right to
\* commit it.  Fsync-before-commit makes this guard an enforced property
\* of the durability layer. a follower's AppendEntriesSuccessResponse
\* is sent only after RaftStore.flush() returns and so reflects on-disk
\* content, the leader's flushedLogIndex (its own contribution to the
\* commit quorum) is set only after RaftStore.flush() returns, and
\* commit-index advancement counts only on-disk acks.  Any entry that
\* could be lost from m by suffix truncation is therefore present on
\* disk on a majority of OTHER members.
CrashRestartWithLogLossEffect(m) ==
    \E w \in 0..MaxSeqId :
        /\ \E e \in raftLog[m] : e > w
        /\ \A e \in raftLog[m] :
              e > w =>
                  \/ \E f \in flushMarkerEntries \cap hdfsHFiles :
                        e <= flushDropBound[f]
                  \/ Cardinality({n \in Members \ {m} : e \in raftLog[n]})
                       >= Majority
        /\ raftLog' = [raftLog EXCEPT ![m] = {e \in @ : e <= w}]

CrashRestartWithLogLoss(m) ==
    /\ CrashRestartWithLogLossEffect(m)
    /\ CrashRestartEffect(m)
    /\ UNCHANGED <<currentTerm, votedFor, clock, partition,
                   globalCommitVars,
                   flushDropBound, masterConfirmedTerm>>

\* ---- Network partition actions ----

\* Nondeterministically partition two members (both directions).
\* Models an AZ-level or link-level network failure.
CreatePartition ==
    \E m1, m2 \in Members :
        /\ m1 # m2
        /\ <<m1, m2>> \notin partition
        /\ partition' = partition \union {<<m1, m2>>, <<m2, m1>>}
        /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                       timerVars, globalCommitVars, memstore, fApplyBatch,
                       writeVars, flushVars, flushDropBound,
                       promotionVars>>

\* Nondeterministically heal a partition between two members.
\* Models individual network link recovery.
HealPartition ==
    \E m1, m2 \in Members :
        /\ <<m1, m2>> \in partition
        /\ partition' = partition \ {<<m1, m2>>, <<m2, m1>>}
        /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                       timerVars, globalCommitVars, memstore, fApplyBatch,
                       writeVars, flushVars, flushDropBound,
                       promotionVars>>

\* Heal ALL partitions at once — full network recovery.
\* This action is deterministic (no internal nondeterminism) so
\* SF_vars(HealAllPartitions) guarantees that if the network is ever
\* partitioned, it eventually fully recovers.  SF on HealPartition
\* alone does NOT provide this because its \E nondeterminism allows
\* TLC to always heal the same unhelpful link while leaving other
\* links permanently down.
HealAllPartitions ==
    /\ partition # {}
    /\ partition' = {}
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, globalCommitVars, memstore, fApplyBatch,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* ---- Leader write path actions ----

\* Leader starts a write: mvcc.begin() assigns a sequence ID from the
\* global counter, WAL ring buffer slot is claimed and entry is published
\* (but not synced).  Models doWALAppend (HRegion.doMiniBatchMutate
\* step 3), which is atomic under the MVCC writeQueue lock inside
\* AbstractFSWAL.stampSequenceIdAndPublishToRingBuffer().  The MVCC
\* writePoint is derived (MVCCWritePoint) from writeSeqId and memstore,
\* so no explicit writePoint update is needed.
\*
\* Guard: the leader must have a valid lease (models the isLeader()
\* check at the start of the write path), no other write in progress
\* (one in-flight write per member is sufficient for safety verification),
\* and the global seqId counter has not exceeded MaxSeqId.  No flush
\* exclusion is needed: the snapshot-boundary flush protocol allows
\* writes to proceed concurrently with the flush pipeline.  In-flight
\* writes get seqIds above snapshotMaxSeqId and are NOT included in
\* the HFile snapshot, so the memstore drop (which drops entries <=
\* snapshotMaxSeqId) never discards data from concurrent writes.
BeginWrite(m) ==
    /\ IsLeader(m)
    /\ promotionPhase[m] = "Complete"
    /\ writePhase[m] = "Idle"
    /\ nextSeqId <= MaxSeqId
    /\ writePhase'    = [writePhase    EXCEPT ![m] = "Pending"]
    /\ walSync'       = [walSync       EXCEPT ![m] = "Pending"]
    /\ raftCommitted' = [raftCommitted EXCEPT ![m] = FALSE]
    /\ writeSeqId'    = [writeSeqId    EXCEPT ![m] = nextSeqId]
    /\ nextSeqId' = nextSeqId + 1
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition,
                   committedEntries, markerEntries, flushMarkerEntries,
                   hdfsHFiles, memstore, fApplyBatch, flushVars,
                   flushDropBound, promotionVars>>

\* WAL sync to HDFS completes successfully.  Models wal.sync(txid)
\* returning without error (HRegion.doMiniBatchMutate step 4a).
\* This is one of two parallel I/O operations in the write fork.
WALSyncComplete(m) ==
    /\ writePhase[m] = "Pending"
    /\ walSync[m] = "Pending"
    /\ walSync' = [walSync EXCEPT ![m] = "Done"]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   memstore, fApplyBatch,
                   writePhase, raftCommitted, writeSeqId, flushVars,
                   flushDropBound, promotionVars>>

\* WAL sync to HDFS fails (HDFS pipeline broken, DataNode failure,
\* network timeout).  Nondeterministic.  Models wal.sync(txid) throwing
\* IOException.  Once failed, the WAL sync cannot succeed for this write;
\* the leader must crash (WALFailureAbort).
WALSyncFail(m) ==
    /\ writePhase[m] = "Pending"
    /\ walSync[m] = "Pending"
    /\ walSync' = [walSync EXCEPT ![m] = "Failed"]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   memstore, fApplyBatch,
                   writePhase, raftCommitted, writeSeqId, flushVars,
                   flushDropBound, promotionVars>>

\* RAFT propose succeeds: the entry is committed by majority ack.
\* Models consensus.propose(stampedWALEdit, seqId) completing
\* (HRegion.doMiniBatchMutate step 4b).  Requires the leader to reach a
\* majority of same-or-lower-term, reachable members, mirroring the
\* Heartbeat/BecomeLeader reachability check.  If any reachable member
\* has a higher term, the leader would discover this and step down
\* (handled by StepDown, not this action).
\*
\* This is one of two parallel I/O operations in the write fork.
\* Once committed, the entry is irrevocable — followers have it in
\* their RAFT logs and will apply it via the consensus apply callback.
\* The write's seqId is added to committedEntries, making it available
\* for follower apply callbacks.
RAFTCommitWrite(m) ==
    /\ writePhase[m] = "Pending"
    /\ ~raftCommitted[m]
    /\ role[m] = "Leader"
    /\ QuorumReachable(m)
    /\ LET responders == Responders(m)
       IN raftLog' = [r \in Members |->
              IF r = m \/ r \in responders
              THEN raftLog[r] \union {writeSeqId[m]}
              ELSE raftLog[r]]
    /\ raftCommitted' = [raftCommitted EXCEPT ![m] = TRUE]
    /\ committedEntries' = committedEntries \union {writeSeqId[m]}
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted,
                   timerVars, partition,
                   nextSeqId, markerEntries, flushMarkerEntries,
                   hdfsHFiles, memstore, fApplyBatch,
                   writePhase, walSync, writeSeqId, flushVars,
                   flushDropBound, promotionVars>>

\* Barrier join + memstore apply + visibility.  Both WAL sync and RAFT
\* commit have completed, so the barrier passes.  Models
\* HRegion.doMiniBatchMutate steps 5-8: barrier join -> verify role ->
\* memstore.add() (cells already stamped with seqId) ->
\* mvcc.completeAndWait() (makes cells visible to readers).  The write
\* is now durable (WAL on HDFS + replicated via RAFT) and visible.
\* The write's seqId is added to the leader's memstore.
\*
\* Guard: walSync must be "Done" and raftCommitted must be TRUE.  This
\* is the write barrier — the central safety mechanism ensuring no write
\* becomes visible without both local durability (WAL) and replicated
\* durability (RAFT).
CompleteWrite(m) ==
    /\ WriteBarrierPassed(m)
    /\ writePhase' = [writePhase EXCEPT ![m] = "Applied"]
    /\ memstore' = [memstore EXCEPT ![m] = @ \union {writeSeqId[m]}]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   fApplyBatch,
                   walSync, raftCommitted, writeSeqId, flushVars,
                   flushDropBound, promotionVars>>

\* Write acknowledged to client, pipeline reset.  Models the return from
\* doMiniBatchMutate (step 9) and resets the write pipeline for the
\* next write.
AckWrite(m) ==
    /\ writePhase[m] = "Applied"
    /\ WritePipelineReset(m)
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   memstore, fApplyBatch,
                   flushVars, flushDropBound,
                   promotionVars>>

\* Leader aborts the RegionServer process because WAL sync failed.
\* This is the mandated response when the WAL is broken: the RS cannot
\* guarantee local durability, so it must crash and let SCP/RAFT failover
\* promote a follower that has the committed RAFT entry (if RAFT did
\* commit).  Consistent with HBase's existing behavior on WAL sync
\* failure (AbortServer -> RegionServerAbortedException).
\*
\* The write pipeline is reset and the member restarts as a Follower,
\* identical to CrashRestart.  If raftCommitted was TRUE, the entry is
\* irrevocable on followers; after failover, the promoted replica will
\* serve it.  If raftCommitted was FALSE, the entry is lost, but the
\* client was never acknowledged (the barrier never passed).
WALFailureAbort(m) ==
    /\ writePhase[m] = "Pending"
    /\ walSync[m] = "Failed"
    /\ CrashRestartEffect(m)
    /\ UNCHANGED <<currentTerm, votedFor, raftLog, clock, partition,
                   globalCommitVars,
                   flushDropBound, masterConfirmedTerm>>

\* ---- Marker actions ----

\* Leader proposes a compaction-complete marker entry through RAFT.
\* Compaction markers are atomically committed (the compaction lifecycle
\* does not require multi-step coordination like flush).  The marker
\* receives a seqId from the global counter, is added to committedEntries
\* and markerEntries, and the leader processes it via mvcc.advanceTo.
\*
\* Guard: the leader must have a valid lease, no mutation write or flush
\* in progress, the seqId counter not exhausted, and a majority reachable.
ProposeMarker(m) ==
    /\ IsLeader(m)
    /\ promotionPhase[m] = "Complete"
    /\ writePhase[m] = "Idle"
    /\ flushPhase[m] = "Idle"
    /\ nextSeqId <= MaxSeqId
    /\ QuorumReachable(m)
    /\ LET seqId == nextSeqId
           responders == Responders(m)
       IN
        /\ nextSeqId' = nextSeqId + 1
        /\ committedEntries' = committedEntries \union {seqId}
        /\ markerEntries' = markerEntries \union {seqId}
        /\ memstore' = [memstore EXCEPT ![m] = @ \union {seqId}]
        /\ raftLog' = [r \in Members |->
              IF r = m \/ r \in responders
              THEN raftLog[r] \union {seqId}
              ELSE raftLog[r]]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted,
                   timerVars, partition,
                   flushMarkerEntries, hdfsHFiles, fApplyBatch,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* ---- Flush protocol actions ----
\*
\* The flush protocol models the 14-step primary flush sequence from
\* the design document.  Steps are collapsed into phases that preserve
\* the safety-critical boundaries:
\*
\*   FlushStart       (steps 1-7):  consume flushOpSeqId, record
\*                     snapshotMaxSeqId (= max seqId in memstore at
\*                     flush time, the actual HFile coverage boundary),
\*                     take memstore snapshot, write HFiles to tmp dir
\*   FlushCommitHFiles (step 8):    sfc.commit() — HFiles durable on HDFS
\*   FlushRAFTPropose  (step 9):    consensus.propose(FLUSH_COMPLETE)
\*   FlushRAFTCommit   (step 10):   majority acknowledge
\*   FlushComplete     (steps 11-14): drop memstore entries at or below
\*                     snapshotMaxSeqId, write COMMIT_FLUSH,
\*                     wal.completeCacheFlush, GC RAFT log
\*
\* The flush and write pipelines run concurrently (snapshot-boundary
\* protocol).  BeginWrite has no flushPhase guard and FlushStart has
\* no writePhase guard.  Safety is maintained because the memstore drop
\* boundary is snapshotMaxSeqId (not flushSeqId): concurrent in-flight
\* writes have seqIds above snapshotMaxSeqId and survive the drop.

\* Leader initiates a flush: set flushInProgress, consume a flushOpSeqId,
\* record snapshotMaxSeqId (the actual HFile coverage boundary), write
\* START_FLUSH marker, take memstore snapshot, sync, and write HFiles
\* to a tmp directory (not yet durable).
\*
\* snapshotMaxSeqId captures the highest seqId currently in the leader's
\* memstore — this is the true boundary of the data that the HFiles
\* will contain.  Any concurrent in-flight writes have seqIds assigned
\* from nextSeqId (which is always > max(memstore)), so they are above
\* snapshotMaxSeqId and will NOT be included in the HFile snapshot.
\* The memstore drop (FlushComplete) uses snapshotMaxSeqId (not
\* flushSeqId) as the boundary, allowing concurrent writes to survive
\* the flush without data loss.
\*
\* flushDropBound[flushSeqId] persists the snapshotMaxSeqId for this
\* flush marker, making it accessible to followers when they process
\* the marker via FollowerApplyMarker.
\*
\* Guard: the leader must have a valid lease, no flush already in
\* progress, and the seqId counter not exhausted.  Additionally, the
\* leader's apply queue must be drained
\* (`ApplicableEntries(m) = {}`) AND no write may be in-flight on the
\* leader (`writePhase[m] = "Idle"`).  These two conditions ensure
\* the captured `snapshotMaxSeqId` faithfully reflects every entry
\* the leader is responsible for: every committed entry has been
\* applied to memstore, and there is no in-flight write with a
\* writeSeqId below the upcoming marker's seqId that could later
\* land in the memstore with a seqId at or below
\* `flushDropBound[flushSeqId]`.  This mirrors the implementation:
\* HRegion's flush prepares a non-blocking write barrier
\* (`mvcc.advanceTo` + completion wait) so that all writes whose
\* seqId is below the chosen flush seqId have applied to the
\* memstore before the snapshot is taken; the consensus core's
\* single-threaded `RaftNodeExecutor` then processes any newly
\* committed entries in seqId order before the next operation.  New
\* writes that begin after `FlushStart` are assigned `nextSeqId`
\* values strictly greater than the marker's seqId, so they
\* naturally land above `snapshotMaxSeqId` and survive the drop;
\* this is the snapshot-boundary protocol's notion of
\* "concurrent in-flight writes survive the flush" — it admits
\* writes started during the flush, not writes started before it
\* with lower seqIds that have not yet applied.  Without these two
\* preconditions the spec admits two distinct counterexamples to
\* `FollowerFlushMemstoreDrop`: (a) `FlushStart` captures a
\* snapshot from a memstore missing a raft-committed but
\* not-yet-applied write (advertising HFile coverage of a seqId
\* that was never written to the HFile); (b) `FlushStart` runs
\* while a not-yet-RAFT-committed write holds a lower writeSeqId,
\* which later commits and applies into the memstore at a seqId at
\* or below `flushDropBound[flushSeqId]`.
FlushStart(m) ==
    /\ IsLeader(m)
    /\ promotionPhase[m] = "Complete"
    /\ flushPhase[m] = "Idle"
    /\ nextSeqId <= MaxSeqId
    /\ ApplicableEntries(m) = {}
    /\ writePhase[m] = "Idle"
    /\ LET snapBound == IF memstore[m] = {} THEN 0 ELSE SetMax(memstore[m])
       IN /\ flushPhase' = [flushPhase EXCEPT ![m] = "FlushStarted"]
          /\ flushSeqId' = [flushSeqId EXCEPT ![m] = nextSeqId]
          /\ snapshotMaxSeqId' = [snapshotMaxSeqId EXCEPT ![m] = snapBound]
          /\ flushDropBound' = [flushDropBound EXCEPT ![nextSeqId] = snapBound]
    /\ nextSeqId'  = nextSeqId + 1
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition,
                   committedEntries, markerEntries, flushMarkerEntries,
                   hdfsHFiles, memstore, fApplyBatch, writeVars,
                   promotionVars>>

\* HFiles are moved from the tmp directory to the store directory
\* (sfc.commit()).  After this step, the HFiles are durable on HDFS
\* and accessible to all members via the shared filesystem.
\*
\* Guard: leader role is required (the flush protocol runs on the
\* leader), and flushPhase must be FlushStarted.
FlushCommitHFiles(m) ==
    /\ role[m] = "Leader"
    /\ flushPhase[m] = "FlushStarted"
    /\ flushPhase' = [flushPhase EXCEPT ![m] = "HFilesCommitted"]
    /\ hdfsHFiles' = hdfsHFiles \union {flushSeqId[m]}
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition,
                   nextSeqId, committedEntries, markerEntries,
                   flushMarkerEntries, memstore, fApplyBatch,
                   writeVars, flushSeqId, snapshotMaxSeqId, flushDropBound,
                   promotionVars>>

\* Leader proposes the FLUSH_COMPLETE marker through RAFT.  The marker
\* is proposed but not yet committed; FlushRAFTCommit handles the
\* majority acknowledgement.
\*
\* Guard: leader role, flushPhase = HFilesCommitted (HFiles must be
\* durable before proposing the marker), and a majority must be
\* reachable (same quorum check as RAFTCommitWrite).
FlushRAFTPropose(m) ==
    /\ role[m] = "Leader"
    /\ flushPhase[m] = "HFilesCommitted"
    /\ QuorumReachable(m)
    /\ LET responders == Responders(m)
       IN raftLog' = [r \in Members |->
              IF r = m \/ r \in responders
              THEN raftLog[r] \union {flushSeqId[m]}
              ELSE raftLog[r]]
    /\ flushPhase' = [flushPhase EXCEPT ![m] = "RAFTProposed"]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted,
                   timerVars, partition, globalCommitVars,
                   memstore, fApplyBatch,
                   writeVars, flushSeqId, snapshotMaxSeqId, flushDropBound,
                   promotionVars>>

\* Majority acknowledges the FLUSH_COMPLETE marker.  The marker is now
\* RAFT-committed: its seqId is added to committedEntries and
\* markerEntries.  The leader also processes the marker by adding
\* the seqId to its own memstore (models mvcc.advanceTo on the leader).
\*
\* Guard: leader role, flushPhase = RAFTProposed, and a majority
\* must be reachable for the commit to succeed.
FlushRAFTCommit(m) ==
    /\ role[m] = "Leader"
    /\ flushPhase[m] = "RAFTProposed"
    /\ QuorumReachable(m)
    /\ flushPhase' = [flushPhase EXCEPT ![m] = "RAFTCommitted"]
    /\ committedEntries' = committedEntries \union {flushSeqId[m]}
    /\ markerEntries' = markerEntries \union {flushSeqId[m]}
    /\ flushMarkerEntries' = flushMarkerEntries \union {flushSeqId[m]}
    /\ memstore' = [memstore EXCEPT ![m] = @ \union {flushSeqId[m]}]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition,
                   nextSeqId, hdfsHFiles, fApplyBatch,
                   writeVars, flushSeqId, snapshotMaxSeqId, flushDropBound,
                   promotionVars>>

\* Flush completion: drop memstore entries at or below snapshotMaxSeqId
\* (the actual HFile coverage boundary), write COMMIT_FLUSH to WAL,
\* call wal.completeCacheFlush() to unblock WAL writes, and GC RAFT
\* log entries.  The drop boundary is snapshotMaxSeqId[m], NOT
\* flushSeqId[m]: concurrent in-flight writes that started after
\* FlushStart have seqIds above snapshotMaxSeqId and must survive
\* the drop.  The flushSeqId marker itself (from mvcc.advanceTo in
\* FlushRAFTCommit) is retained because flushSeqId > snapshotMaxSeqId.
\*
\* Guard: leader role, flushPhase = RAFTCommitted.
FlushComplete(m) ==
    /\ role[m] = "Leader"
    /\ flushPhase[m] = "RAFTCommitted"
    /\ memstore' = [memstore EXCEPT ![m] = {s \in @ : s > snapshotMaxSeqId[m]}]
    /\ flushPhase' = [flushPhase EXCEPT ![m] = "Idle"]
    /\ flushSeqId' = [flushSeqId EXCEPT ![m] = 0]
    /\ snapshotMaxSeqId' = [snapshotMaxSeqId EXCEPT ![m] = 0]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   fApplyBatch,
                   writeVars, flushDropBound,
                   promotionVars>>

\* ---- Follower batch apply actions ----

\* Follower receives committed entries from the RAFT log and begins
\* applying a batch of consecutive mutation entries.  The batch collects
\* all unapplied mutation entries (not markers) up to the next unapplied
\* marker boundary.  Models the first step of the batched consensus apply
\* callback: mvcc.beginAt(maxBatchSeqId) advances the follower's MVCC
\* writePoint to the highest seqId in the batch.
\*
\* In the real system, the callback receives a list of committed entries
\* and groups consecutive mutations into a batch.  When a marker entry
\* is encountered, the preceding mutations are applied as a batch first.
\* This action models collecting the batch; FollowerCompleteBatchApply
\* models applying it.
\*
\* The applicable set is computed via ApplicableEntries(m), which
\* excludes entries subsumed by a previously applied flush marker.
\* Without this exclusion, entries dropped from the memstore by a
\* flush would re-appear as applicable and be re-applied, violating
\* FollowerFlushMemstoreDrop.
\*
\* Guard: the member must be a Follower (or a Leader in Promoting phase,
\* modeling step 1 of the promotion protocol: finish consuming remaining
\* RAFT log entries), not currently applying a batch, the next unapplied
\* committed entry must be a mutation (not a marker), and there must be
\* committed entries not yet in its memstore.
FollowerBeginBatchApply(m) ==
    /\ MutationBatchReady(m)
    /\ fApplyBatch' = [fApplyBatch EXCEPT ![m] = MutationBatch(m)]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   memstore,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* Follower completes applying a batch of committed mutation entries:
\* stamp cells with the leader's sequence IDs, add all cells to the
\* memstore, and call mvcc.completeAndWait() to advance readPoint and
\* make the cells visible to scanners.  Models the completion of the
\* batched apply callback: one memstore.add() with the combined cell
\* set from all entries in the batch, then one mvcc.completeAndWait().
\*
\* Guard: the member must be a Follower (or a Leader in Promoting phase)
\* with a non-empty apply batch.
FollowerCompleteBatchApply(m) ==
    /\ FollowerOrPromoting(m)
    /\ fApplyBatch[m] # {}
    /\ memstore' = [memstore EXCEPT ![m] = @ \union fApplyBatch[m]]
    /\ fApplyBatch' = [fApplyBatch EXCEPT ![m] = {}]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* Follower applies a committed marker entry.  When the next unapplied
\* committed entry is a marker (flush-complete, compaction-complete),
\* the follower processes it by calling mvcc.advanceTo(markerSeqId),
\* which advances both writePoint and readPoint past the marker's seqId.
\* The marker seqId is added to memstore to track that it has been
\* processed and to contribute to the derived MVCCWritePoint.
\*
\* This action may only fire when no mutation batch is in progress
\* (fApplyBatch is empty), enforcing the batch boundary: preceding
\* mutations must be fully applied before the marker is processed.
\*
\* Behavior differs by marker type:
\*   Compaction marker: add marker seqId to memstore (mvcc.advanceTo).
\*   Flush marker: guard on HFile accessibility (hdfsHFiles), then
\*     drop memstore entries at or below flushDropBound[marker] (the
\*     actual HFile coverage boundary, i.e., the snapshotMaxSeqId
\*     recorded at FlushStart time) and add the marker.  Entries
\*     between flushDropBound[marker] and the marker seqId (in-flight
\*     writes at flush time) are above the drop boundary and survive.
\*     Models the 6-step follower flush-complete handling: complete
\*     preceding batch -> mvcc.advanceTo -> refresh store files ->
\*     confirm HFiles accessible -> drop memstore -> GC log.
\*
\* Guard: the member must be a Follower (or a Leader in Promoting phase),
\* no batch in progress, and the next unapplied committed entry must be
\* a marker.  For flush markers, the HFiles must be accessible on HDFS.
FollowerApplyMarker(m) ==
    /\ FollowerOrPromoting(m)
    /\ fApplyBatch[m] = {}
    /\ LET applicable == ApplicableEntries(m)
       IN /\ applicable # {}
          /\ LET nextEntry == SetMin(applicable)
             IN /\ nextEntry \in markerEntries
                /\ IF nextEntry \in flushMarkerEntries
                   THEN /\ nextEntry \in hdfsHFiles
                        /\ memstore' = [memstore EXCEPT ![m] =
                            {s \in @ : s > flushDropBound[nextEntry]} \union {nextEntry}]
                   ELSE /\ memstore' = [memstore EXCEPT ![m] = @ \union {nextEntry}]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   fApplyBatch,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* ---- Promotion protocol actions ----

\* Master confirms a RAFT leader's promotion by validating the term
\* and updating META (or in-memory state only for META's own group).
\* Modeled as a nondeterministic oracle with a term-fencing guard,
\* consistent with how the master is modeled in the split and merge
\* lifecycle modules (MasterOpenDaughter, MasterOpenMerged).
\*
\* Guard: the member must be a Leader with a valid lease, in
\* Promoting phase, with all committed entries consumed
\* (ApplicableEntries = {}).  The term-fencing guard
\* (currentTerm[m] > masterConfirmedTerm) ensures the master
\* rejects stale notifications — only the highest term wins.
\* This models the design document's "master validates term >
\* current known term" check in the LeaderChangeHandler.
\*
\* Effect: masterConfirmedTerm is advanced to the leader's term
\* (the master records this as the current known term for the group),
\* and promotionPhase transitions to "AwaitingMaster" (the
\* RegionServer has received the master's confirmation and can
\* proceed to local promotion steps).
MasterConfirmPromotion(m) ==
    /\ role[m] = "Leader"
    /\ LeaseValid(m)
    /\ promotionPhase[m] = "Promoting"
    /\ ApplicableEntries(m) = {}
    /\ currentTerm[m] > masterConfirmedTerm
    /\ masterConfirmedTerm' = currentTerm[m]
    /\ promotionPhase' = [promotionPhase EXCEPT ![m] = "AwaitingMaster"]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   memstore, fApplyBatch,
                   writeVars, flushVars, flushDropBound>>

\* A leader that has received master confirmation completes the
\* local promotion steps: setReadOnly(false) and acquire WAL
\* reference.  This is the critical safety boundary — after this
\* transition, the member can accept writes.
\*
\* Guard: the member must be a Leader with a valid lease, in
\* AwaitingMaster phase (master confirmation received), with no
\* unapplied committed entries.  The ApplicableEntries guard is
\* retained to handle the edge case where new entries are committed
\* (via NewLeaderCommitOrphanEntry) between MasterConfirmPromotion
\* and PromotionComplete.  The lease guard prevents a stale leader
\* whose lease has expired from completing promotion.
\*
\* Implementation: promotion steps run on the actor thread and check
\* RaftNodeImpl.isLeaderWithValidLease(now) before proceeding.  That
\* accessor returns true iff role == LEADER, leaderState != null, and
\* leaderState.leaseExpiryMillis > now — exactly the spec's
\* role[m] = "Leader" /\ LeaseValid(m) conjunction.
PromotionComplete(m) ==
    /\ role[m] = "Leader"
    /\ LeaseValid(m)
    /\ promotionPhase[m] = "AwaitingMaster"
    /\ ApplicableEntries(m) = {}
    /\ promotionPhase' = [promotionPhase EXCEPT ![m] = "Complete"]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   memstore, fApplyBatch,
                   writeVars, flushVars, flushDropBound,
                   masterConfirmedTerm>>

\* ---- Orphan entry commitment ----

\* A new leader's log advancement commits an entry that exists in a
\* majority of members' durable RAFT logs but has not yet been committed.
\* This models RAFT's AdvanceCommitIndex: when the new leader replicates
\* its log (or simply observes that a majority already have an entry),
\* the entry becomes committed.
\*
\* Entry type classification uses s \in hdfsHFiles: a seqId appears in
\* hdfsHFiles only via FlushCommitHFiles (which fires before
\* FlushRAFTPropose), so s \in hdfsHFiles implies the entry is a flush
\* marker.  Mutations and compaction markers never appear as
\* uncommitted-but-in-log (compaction markers use ProposeMarker which
\* atomically commits).
\*
\* The leader atomically applies the committed entry to its own
\* memstore, mirroring the consensus core's single-threaded actor
\* model (one RaftNodeExecutor per group): AdvanceCommitIndexTask and
\* the runOperation() apply callback execute on the same thread with
\* no interleaving.  For flush markers, the
\* leader applies via mvcc.advanceTo(s) and drops memstore entries
\* below s (the data is now in HFiles on HDFS).  For mutations, the
\* leader applies via memstore.add.  In the current model, mutations
\* cannot be orphaned (RAFTCommitWrite atomically proposes and
\* commits), so the mutation branch is unreachable but is included
\* for correctness.
\*
\* Guard: an active leader (role = Leader AND valid lease) must exist
\* (the new leader drives log advancement), the entry must be in a
\* majority of logs, and must not already be committed.  The IsLeader
\* guard ensures that a stale leader with expired lease cannot advance
\* its commit index — in the consensus core, commit-index advancement
\* is driven by AppendEntries / LeaderHeartbeatAck responses, which a
\* stale leader does not receive (followers in a higher term reject
\* its requests).
NewLeaderCommitOrphanEntry ==
    \E s \in 1..MaxSeqId :
        /\ s \notin committedEntries
        /\ Cardinality({m \in Members : s \in raftLog[m]}) >= Majority
        /\ \E leader \in Members :
            /\ IsLeader(leader)
            /\ IF s \in hdfsHFiles
               THEN /\ committedEntries' = committedEntries \union {s}
                    /\ markerEntries' = markerEntries \union {s}
                    /\ flushMarkerEntries' = flushMarkerEntries \union {s}
                    /\ memstore' = [memstore EXCEPT ![leader] =
                          {e \in @ : e > flushDropBound[s]} \union {s}]
               ELSE /\ committedEntries' = committedEntries \union {s}
                    /\ memstore' = [memstore EXCEPT ![leader] = @ \union {s}]
                    /\ UNCHANGED <<markerEntries, flushMarkerEntries>>
        /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                       timerVars, partition,
                       nextSeqId, hdfsHFiles, fApplyBatch,
                       writeVars, flushVars, flushDropBound,
                       promotionVars>>

\* ---- RAFT log GC and catch-up actions ----

\* A member garbage-collects RAFT log entries at or below an applied
\* flush marker's drop boundary (flushDropBound[S]).  After a
\* flush-complete marker with seqId S is applied (S \in memstore[m]),
\* all entries with seqId <= flushDropBound[S] are in HFiles on HDFS
\* and no longer needed in the RAFT log.  Entries between
\* flushDropBound[S] and S (in-flight writes at flush time) are NOT
\* in HFiles and are retained.
\*
\* Guard: the member has applied a flush marker, and there are entries
\* in its log at or below the drop boundary (something to GC).  Any
\* member can GC its own log, independent of role.
\*
\* Effect: entries at or below flushDropBound[S] are removed from
\* raftLog[m].  The flush marker seqId S and any in-flight writes
\* between flushDropBound[S] and S are retained.
RaftLogGCGuard(m) ==
    \E s \in flushMarkerEntries \cap memstore[m] :
        \E e \in raftLog[m] : e <= flushDropBound[s]

RaftLogGCEffect(m) ==
    \E s \in flushMarkerEntries \cap memstore[m] :
        /\ \E e \in raftLog[m] : e <= flushDropBound[s]
        /\ raftLog' = [raftLog EXCEPT ![m] = {e \in @ : e > flushDropBound[s]}]

RaftLogGC(m) ==
    /\ RaftLogGCGuard(m)
    /\ RaftLogGCEffect(m)
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted,
                   timerVars, partition, globalCommitVars,
                   memstore, fApplyBatch,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* Leader sends a catch-up reference (CatchUpReference) to a lagging
\* follower whose needed RAFT log entries have been garbage-collected.
\* Instead of sending the entries via AppendEntries, the leader directs
\* the follower to load HFiles from HDFS and start from the flush
\* boundary with an empty memstore for post-flush entries.  This models
\* the shared-storage catch-up path that replaces standard RAFT's
\* InstallSnapshot RPC (design document: "the 'snapshot' for catch-up
\* is a lightweight CatchUpReference message containing only the list
\* of HFile paths on HDFS and the flush seqId").
\*
\* Guard: the leader can communicate with the follower, the follower
\* has no batch apply in progress, there exists a committed flush
\* marker S with HFiles on HDFS, and the follower has unapplied
\* committed entries below S that are NOT in the leader's raftLog
\* (they have been GC'd, so normal AppendEntries cannot deliver them).
\* The follower may be a Follower or a Leader in Promoting phase;
\* the latter models a newly elected leader that needs catch-up
\* during promotion step 1 (finish consuming RAFT log entries)
\* when the entries it needs have been GC'd from all members' logs.
\*
\* Effect: the follower's memstore drops entries below S and adds S
\* (models mvcc.advanceTo at the flush boundary + HFile load).  The
\* follower's raftLog drops entries below S and adds S (models log
\* truncation at the snapshot point).  Post-flush entries above S
\* remain in memstore/raftLog if already present and can be applied
\* via normal FollowerBeginBatchApply / FollowerApplyMarker.
\*
\* Implementation: this action models the design-target shared-storage
\* state-transfer path. The follower's log is truncated to the snapshot
\* index, and the data that was below the snapshot is recoverable — via
\* HFiles on HDFS in the design-target path, via replayed chunks plus
\* locally-flushed HFiles in the chunked path.
InstallSnapshot(leader, follower) ==
    /\ role[leader] = "Leader"
    /\ follower # leader
    /\ CanCommunicate(leader, follower)
    /\ FollowerOrPromoting(follower)
    /\ fApplyBatch[follower] = {}
    /\ \E s \in flushMarkerEntries \cap hdfsHFiles :
        /\ \E needed \in (committedEntries \ memstore[follower]) :
              needed <= flushDropBound[s] /\ needed \notin raftLog[leader]
        /\ memstore' = [memstore EXCEPT ![follower] =
              {e \in @ : e > flushDropBound[s]} \union {s}]
        /\ raftLog' = [raftLog EXCEPT ![follower] =
              {e \in @ : e > flushDropBound[s]} \union {s}]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted,
                   timerVars, partition, globalCommitVars,
                   fApplyBatch,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* A new member bootstraps into the RAFT group, replacing a member
\* whose instance has been terminated (e.g., a new Kubernetes pod with
\* no persistent local state).  Unlike CrashRestart, which preserves
\* durable local state (raftLog, currentTerm, votedFor), this action
\* models total state loss: the replacement member starts with no local
\* RAFT log, no memstore, and a fresh term/vote state.
\*
\* votedFor is set to the bootstrapping leader (not None) because the
\* leader drives the bootstrap via AppendEntries, which constitutes an
\* implicit vote acknowledgement for this term.  Setting votedFor to
\* None would allow the bootstrapped member to vote for a different
\* candidate in the same term, violating LeaderUniqueness (a member
\* must vote at most once per term in RAFT).
\*
\* The bootstrap is modeled atomically because the new member does not
\* participate in consensus (voting, proposing) during the catch-up
\* phase, so no safety-relevant interleaving can occur between the
\* state reset and the initial log replication.
\*
\* Bootstrap recovers data through two mechanisms:
\*   (1) RAFT log entries from the leader via AppendEntries over the
\*       network: the leader detects the new member at log index 0
\*       and sends its raftLog contents.  Modeled by copying
\*       raftLog[leader] to the new member's raftLog.
\*   (2) Shared HFiles from HDFS: discovered by processing committed
\*       flush markers through the normal follower apply path
\*       (FollowerApplyMarker).  When the catching-up member encounters
\*       a flush marker, it refreshes store files from HDFS and drops
\*       memstore entries below the flush watermark — the same path as
\*       a non-catching-up follower.
\*
\* The member starts with an empty memstore (rather than pre-loading
\* HFiles at bootstrap time).  This enables TLC to verify safety under
\* all interleavings of catch-up entry application with concurrent
\* leader flush.  In particular, it verifies that entries applied from
\* the log and then dropped by a flush marker are not re-applied from
\* the refreshed HFiles (the flush-watermark exclusion in
\* ApplicableEntries prevents this, checked by FollowerFlushMemstoreDrop
\* and CatchUpCompleteness).
\*
\* After this action, follower apply actions (FollowerBeginBatchApply,
\* FollowerCompleteBatchApply, FollowerApplyMarker) rebuild the
\* memstore from all committed entries, processing flush markers
\* inline to discover HFiles and drop pre-flush entries.
\*
\* Guard: a leader must exist and be reachable (the leader drives
\* the bootstrap via AppendEntries / CatchUpReference).  The leader's
\* term must be >= the member's current term to prevent a stale leader
\* from resetting a member's term backward (which would allow the stale
\* leader to refresh its lease via heartbeat, violating
\* LeaseExpiresBeforeElection).  The member must have non-initial state
\* (otherwise it is already in a fresh state and the action would be a
\* no-op).
\*
\* The raftLog is set to the leader's raftLog unioned with all
\* committed entries not covered by a strictly-later flush marker.
\* In the real system, RAFT's leader completeness property guarantees
\* the leader has all committed entries in its log (enforced by the
\* log up-to-date check in RequestVote, which this model omits for
\* simplicity).  The union compensates for the omission: committed
\* entries that must be in a majority of raftLogs (those without a
\* covering flush marker) are included regardless of whether the
\* model's leader happens to have them.  Entries strictly below a
\* flush marker (data covered by HFiles on HDFS) need not be in any
\* raftLog; the flush marker entry itself, however, is at a seqId
\* strictly greater than its own flushDropBound (the marker is the
\* control entry, not part of the snapshot data) and therefore must
\* be preserved in the bootstrapping member's raftLog so that
\* CatchUpDataIntegrity remains satisfied for the marker — using a
\* strict inequality (`f > s`) prevents a marker from being treated
\* as "covered by itself" and silently dropped by bootstrap, which
\* TLC found could leave a marker in only a minority of raftLogs
\* after a stale-log new leader (model abstraction omitting the log
\* up-to-date check) bootstrapped a follower against its empty log.
\*
\* Implementation: this action abstracts both production catch-up paths
\* into one atomic step.  sendAppendEntriesRequest() detects that the
\* follower's nextIndex is 0 (or behind the leader's first available
\* log entry) and either delivers the log tail via AppendEntries
\* (sendCatchupAppendsIfNeeded) or transitions to chunked
\* InstallSnapshot via SnapshotChunkCollector +
\* InstallSnapshotRequestHandler (StateMachine.installSnapshot
\* receives the chunk).  The design-target shared-storage path
\* (CatchUpReference + StateMachine.installSnapshotReference) is
\* defined in protobuf and handler scaffolding but is not populated
\* or invoked in production today; both paths are observationally
\* equivalent at this spec's abstraction level.
NewMemberBootstrap(m) ==
    \E leader \in Members :
        /\ role[leader] = "Leader"
        /\ CanCommunicate(leader, m)
        /\ m # leader
        /\ currentTerm[leader] >= currentTerm[m]
        /\ \/ currentTerm[m] > 0
           \/ role[m] # "Follower"
           \/ memstore[m] # {}
           \/ raftLog[m] # {}
           \/ votesGranted[m] # {}
           \/ leaseRemaining[m] > 0
           \/ writePhase[m] # "Idle"
           \/ flushPhase[m] # "Idle"
           \/ promotionPhase[m] # "None"
        /\ LET uncoveredCommitted == {s \in committedEntries :
                   ~\E f \in flushMarkerEntries : f > s}
           IN
            /\ role'             = [role            EXCEPT ![m] = "Follower"]
            /\ currentTerm'      = [currentTerm     EXCEPT ![m] = currentTerm[leader]]
            /\ votedFor'         = [votedFor        EXCEPT ![m] = leader]
            /\ votesGranted'     = [votesGranted    EXCEPT ![m] = {}]
            /\ leaseRemaining'   = [leaseRemaining  EXCEPT ![m] = 0]
            /\ timerRemaining'   = [timerRemaining  EXCEPT ![m] = ElectionTimeoutMin]
            /\ raftLog'          = [raftLog         EXCEPT ![m] =
                  raftLog[leader] \union uncoveredCommitted]
            /\ memstore'         = [memstore        EXCEPT ![m] = {}]
            /\ fApplyBatch'      = [fApplyBatch     EXCEPT ![m] = {}]
            /\ writePhase'       = [writePhase      EXCEPT ![m] = "Idle"]
            /\ walSync'          = [walSync         EXCEPT ![m] = "Pending"]
            /\ raftCommitted'    = [raftCommitted   EXCEPT ![m] = FALSE]
            /\ writeSeqId'       = [writeSeqId      EXCEPT ![m] = 0]
            /\ flushPhase'       = [flushPhase      EXCEPT ![m] = "Idle"]
            /\ flushSeqId'       = [flushSeqId      EXCEPT ![m] = 0]
            /\ snapshotMaxSeqId' = [snapshotMaxSeqId EXCEPT ![m] = 0]
            /\ promotionPhase'   = [promotionPhase  EXCEPT ![m] = "None"]
        /\ UNCHANGED <<clock, partition, globalCommitVars,
                       flushDropBound, masterConfirmedTerm>>

----
(* ---- Merged actions for data-path domain decomposition ---- *)

\* These are used by GroupDataPathNext (below) and data-path MC
\* configurations.  The rationale for each merge is documented in
\* MCRaftRegionReplica_datapath.tla.

\* Atomic follower batch apply: computes the mutation batch and applies
\* it to memstore in a single step.  Merges FollowerBeginBatchApply +
\* FollowerCompleteBatchApply.  fApplyBatch is never modified.
AtomicFollowerBatchApply(m) ==
    /\ MutationBatchReady(m)
    /\ memstore' = [memstore EXCEPT ![m] = @ \union MutationBatch(m)]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   fApplyBatch,
                   writeVars, flushVars, flushDropBound,
                   promotionVars>>

\* Atomic write completion and ack: applies the write to memstore and
\* resets the write pipeline in a single step.  Merges CompleteWrite +
\* AckWrite, skipping the transient "Applied" phase.
AtomicCompleteWriteAndAck(m) ==
    /\ WriteBarrierPassed(m)
    /\ WritePipelineReset(m)
    /\ memstore'      = [memstore EXCEPT ![m] = @ \union {writeSeqId[m]}]
    /\ UNCHANGED <<role, currentTerm, votedFor, votesGranted, raftLog,
                   timerVars, partition, globalCommitVars,
                   fApplyBatch,
                   flushVars, flushDropBound,
                   promotionVars>>

----
(* ---- Next-state relation and specification ---- *)

\* Per-group actions are partitioned into "gateable" (single-member
\* normal-operation actions that lifecycle composition modules gate with
\* a state-level predicate) and "always-enabled" (FollowerApplyMarker,
\* NewLeaderCommitOrphanEntry, and the two multi-member actions
\* RequestVote and InstallSnapshot which are gated on the initiator by
\* composition modules).
\*
\* GatedMemberActions(m) collects all single-member-parameter normal-op
\* actions into a single disjunction.  Composition modules call it with
\* a per-member gate, e.g.:
\*   \E m \in Members : ParentGroupActive(m) /\ Parent!GatedMemberActions(m)
\* This replaces the original per-action enumeration, eliminating ~200
\* lines of mechanical dispatch per composition module.
\*
\* GatedMemberDataPathActions(m) is the data-path variant with merged
\* write/follower actions and ProposeMarker/WALSyncFail/WALFailureAbort
\* removed.
\*
\* GroupNext / GroupDataPathNext use these building blocks with no
\* gating (all actions always enabled).  Standalone and multi-group
\* modules use these directly.  Split/merge modules construct their own
\* gated Next relations using the building blocks.
\*
\* Shared-impact actions (ClockTick, CrashRestart, CreatePartition,
\* HealPartition, HealAllPartitions, RaftLogGC) are excluded from
\* GroupNext / GroupDataPathNext.  Multi-group and lifecycle composition
\* modules provide custom versions of these actions that correctly
\* apply to all co-located groups.

\* ---- Action group building blocks ----

ElectionAndLeadershipActions(m) ==
    \/ Timeout(m)
    \/ BecomeLeader(m)
    \/ LeaderHeartbeat(m)
    \/ StepDown(m)
    \/ LeaderLeaseExpiry(m)

WritePathCommonActions(m) ==
    \/ BeginWrite(m)
    \/ WALSyncComplete(m)
    \/ RAFTCommitWrite(m)

FlushActions(m) ==
    \/ FlushStart(m)
    \/ FlushCommitHFiles(m)
    \/ FlushRAFTPropose(m)
    \/ FlushRAFTCommit(m)
    \/ FlushComplete(m)

PromotionAndBootstrapActions(m) ==
    \/ MasterConfirmPromotion(m)
    \/ PromotionComplete(m)
    \/ NewMemberBootstrap(m)

\* All single-member-parameter normal-operation actions excluding
\* MasterConfirmPromotion.  Used by multi-group composition to replace
\* MasterConfirmPromotion with a META-gated variant.
GatedMemberActionsNoMasterConfirm(m) ==
    \/ ElectionAndLeadershipActions(m)
    \/ WritePathCommonActions(m)
    \/ WALSyncFail(m)
    \/ CompleteWrite(m)
    \/ AckWrite(m)
    \/ WALFailureAbort(m)
    \/ FlushActions(m)
    \/ ProposeMarker(m)
    \/ FollowerBeginBatchApply(m)
    \/ FollowerCompleteBatchApply(m)
    \/ PromotionComplete(m)
    \/ NewMemberBootstrap(m)

\* All single-member-parameter normal-operation actions.
\* Composition modules gate this on the member for lifecycle control.
GatedMemberActions(m) ==
    \/ GatedMemberActionsNoMasterConfirm(m)
    \/ MasterConfirmPromotion(m)

\* Data-path variant: merged write/follower actions,
\* ProposeMarker/WALSyncFail/WALFailureAbort removed.
GatedMemberDataPathActions(m) ==
    \/ ElectionAndLeadershipActions(m)
    \/ WritePathCommonActions(m)
    \/ AtomicCompleteWriteAndAck(m)
    \/ FlushActions(m)
    \/ AtomicFollowerBatchApply(m)
    \/ PromotionAndBootstrapActions(m)

\* Multi-member and always-enabled actions shared by GroupNext and
\* GroupDataPathNext.
UngatedGroupActions ==
    \/ \E c, v \in Members  : RequestVote(c, v)
    \/ \E l, f \in Members  : InstallSnapshot(l, f)
    \/ \E m \in Members     : FollowerApplyMarker(m)
    \/ NewLeaderCommitOrphanEntry

\* Per-group subset of Next for multi-group composition.  Excludes the
\* shared-impact actions whose effects span all groups on a server.
\* No lifecycle gating — all actions always enabled.
GroupNext ==
    \/ \E m \in Members : GatedMemberActions(m)
    \/ UngatedGroupActions

\* Per-group data-path next-state relation for multi-group composition.
\* Like GroupNext but with action merges and removals per
\* MCRaftRegionReplica_datapath.tla.
GroupDataPathNext ==
    \/ \E m \in Members : GatedMemberDataPathActions(m)
    \/ UngatedGroupActions

\* Full next-state relation: per-group actions plus shared-impact actions.
Next ==
    \/ GroupNext
    \* Timing
    \/ \E m \in Members     : ClockTick(m)
    \* Crash recovery
    \/ \E m \in Members     : CrashRestart(m)
    \/ \E m \in Members     : CrashRestartWithLogLoss(m)
    \* Network
    \/ CreatePartition
    \/ HealPartition
    \/ HealAllPartitions
    \* RAFT log GC
    \/ \E m \in Members     : RaftLogGC(m)

Spec == Init /\ [][Next]_vars

----
(* ---- Safety properties ---- *)

\* ---- RAFT consensus invariants ----

\* At most one member holds the Leader role in any given term.
LeaderUniqueness ==
    \A m1, m2 \in Members :
        (/\ role[m1] = "Leader"
         /\ role[m2] = "Leader"
         /\ currentTerm[m1] = currentTerm[m2])
        => m1 = m2

\* A member with a valid lease must hold the Leader role.
LeaseImpliesLeadership ==
    \A m \in Members :
        LeaseValid(m) => role[m] = "Leader"

\* At most one member holds a valid lease at any time.  Ensures that a
\* stale leader's lease expires before a new leader can acquire one,
\* preventing a window where two leaders serve reads concurrently.
\*
\* This property depends on the timing relationship:
\*   LeaderLeaseDuration < ElectionTimeoutMin - 2 * MaxClockDrift
\* which ensures the leader's lease expires before any follower's
\* election timer fires, accounting for worst-case drift (leader clock
\* slow by MaxClockDrift, follower clock fast by MaxClockDrift).
\*
\* With network partitions, a partitioned leader cannot refresh its
\* lease (Heartbeat requires a majority of reachable followers), so
\* the lease naturally expires.  The timing relationship guarantees
\* this expiry occurs before any follower can start a new election,
\* even under worst-case clock drift.
LeaseExpiresBeforeElection ==
    \A m1, m2 \in Members :
        m1 # m2 => ~(LeaseValid(m1) /\ LeaseValid(m2))

\* Every committed entry is recoverable through at least one path:
\* either via RAFT log replay (the entry is in a majority of members'
\* logs) or via HFile access (the entry is covered by a committed
\* flush marker whose HFiles are durable on HDFS, meaning the entry's
\* seqId is at or below the flush marker's HFile coverage boundary
\* flushDropBound[f]).  Entries between flushDropBound[f] and f
\* (in-flight writes at flush time) are NOT in HFiles and must be
\* recoverable via RAFT logs.
CatchUpDataIntegrity ==
    \A s \in committedEntries :
        \/ Cardinality({m \in Members : s \in raftLog[m]}) >= Majority
        \/ \E f \in flushMarkerEntries \cap hdfsHFiles : s <= flushDropBound[f]

\* Every seqId that is exposed to clients via any member's memstore is
\* recoverable from the durable surface. It is on a majority's raftLogs,
\* or it is covered by a committed flush marker whose HFiles are durable on
\* HDFS. A follower may make an entry visible to Timeline-
\* consistency clients on RAFT commit, and that exposure must remain
\* recoverable even if every member then crashes simultaneously.
\*
\* This is logically implied by FollowerSeqIdConsistency
\* (memstore ⊆ committedEntries) plus CatchUpDataIntegrity, but is stated
\* explicitly so a counterexample directly identifies a phantom-read
\* failure.
NoFollowerExposureRollback ==
    \A m \in Members :
        \A s \in memstore[m] :
            \/ Cardinality({n \in Members : s \in raftLog[n]}) >= Majority
            \/ \E f \in flushMarkerEntries \cap hdfsHFiles :
                  s <= flushDropBound[f]

\* ---- Write path invariants ----

\* A write is made visible to readers (memstore.add + mvcc.completeAndWait)
\* only after both WAL sync and RAFT commit have completed.  This is the
\* core write path safety property: the barrier ensures no write becomes
\* visible without both local durability (WAL on HDFS) and replicated
\* durability (RAFT commit to majority).
\*
\* This is a cross-variable invariant verified to catch any action that
\* might incorrectly set writePhase to "Applied" without ensuring
\* walSync = "Done" and raftCommitted = TRUE.
WriteBarrierSafety ==
    \A m \in Members :
        writePhase[m] = "Applied" => walSync[m] = "Done" /\ raftCommitted[m]

\* Every seqId in any member's memstore is a RAFT-committed entry.
\* This ensures consistency between leader and follower memstores:
\* both only contain entries that were committed through RAFT (majority
\* acknowledgement), and both use the leader-assigned sequence IDs.
\*
\* On the leader, CompleteWrite requires raftCommitted = TRUE, which
\* means the entry's seqId is in committedEntries before it enters the
\* leader's memstore.  ProposeMarker atomically adds the marker seqId
\* to both committedEntries and the leader's memstore.  On followers,
\* FollowerBeginBatchApply picks entries exclusively from
\* committedEntries, and FollowerApplyMarker picks markers from
\* committedEntries.  On crash, memstore resets to {}.  These paths
\* ensure the invariant holds across all state transitions.
FollowerSeqIdConsistency ==
    \A m \in Members :
        memstore[m] \subseteq committedEntries

\* ---- Flush protocol invariants ----

\* If a leader crashes between HFile commit (step 8) and RAFT flush-marker
\* commit (step 10), no member drops its memstore for the uncommitted flush.
\* Equivalently: whenever a member reaches the RAFTCommitted phase (the
\* gate for FlushComplete, which performs the memstore drop), the flush
\* seqId must be in markerEntries (classified as a committed marker).
\*
\* This is a cross-variable invariant (flushPhase x markerEntries) that
\* verifies the atomicity link between FlushRAFTCommit's phase transition
\* and its markerEntries update.  Since markerEntries ⊆ committedEntries
\* (every action that adds to markerEntries atomically adds to
\* committedEntries), this also implies the weaker FlushAtomicity
\* (flushSeqId ∈ committedEntries).
\*
\* Crash recovery at each of the 4 failure points:
\*   FlushStarted:     no HFiles, no marker — CrashRestart resets flush
\*                     state; invariant holds (no member in RAFTCommitted)
\*   HFilesCommitted:  HFiles on HDFS, no marker — orphan HFiles are
\*                     harmless; invariant holds
\*   RAFTProposed:     marker proposed but not committed — invariant holds
\*   RAFTCommitted:    marker committed — invariant holds (flushSeqId is
\*                     in markerEntries); survivors apply via
\*                     FollowerApplyMarker
NoOrphanMemstoreDrop ==
    \A m \in Members :
        flushPhase[m] = "RAFTCommitted" => flushSeqId[m] \in markerEntries

\* The HFile coverage boundary (flushDropBound) for every committed flush
\* marker is strictly less than the flush marker's own seqId.  This is a
\* structural invariant that captures the fundamental property of the
\* snapshot-boundary design: flushOpSeqId is consumed AFTER the memstore
\* snapshot, so the snapshot boundary is always below the marker seqId.
\* This ensures that in-flight writes (seqIds between snapshotMaxSeqId
\* and flushSeqId) survive the memstore drop.
FlushDropBoundary ==
    \A f \in flushMarkerEntries :
        flushDropBound[f] < f

\* After a follower has applied a flush marker with seqId S, no non-marker
\* entry at or below flushDropBound[S] remains in the follower's memstore.
\* The drop boundary is the actual HFile coverage boundary
\* (snapshotMaxSeqId recorded at FlushStart time), NOT the flush marker
\* seqId.  Entries between flushDropBound[S] and S (in-flight writes at
\* flush time) are above the drop boundary and correctly remain in the
\* memstore.  Marker entries (both flush and compaction markers) are
\* excluded from the drop check because they represent mvcc.advanceTo
\* points, not data entries.
\*
\* The invariant is scoped to Followers.  Leaders handle their own flush
\* memstore drop via FlushComplete.  Stepped-down leaders in RAFTCommitted
\* phase atomically complete the memstore drop during step-down (verified
\* by the phase-aware cleanup in StepDown, BecomeLeader, Heartbeat, and
\* RequestVote), so they satisfy this invariant when they become Followers.
FollowerFlushMemstoreDrop ==
    \A m \in Members :
        role[m] = "Follower" =>
            \A s \in flushMarkerEntries \cap memstore[m] :
                \A t \in memstore[m] \ markerEntries :
                    t > flushDropBound[s]

\* HFiles are committed to HDFS before any flush marker can be committed
\* through RAFT.  This is enforced by the phase ordering:
\*   FlushCommitHFiles (adds to hdfsHFiles)
\*   -> FlushRAFTPropose
\*   -> FlushRAFTCommit (adds to flushMarkerEntries)
\* The invariant verifies that no path can add a seqId to
\* flushMarkerEntries without it first being in hdfsHFiles.  This is
\* a prerequisite for the FollowerApplyMarker guard (nextEntry \in
\* hdfsHFiles), which models the follower's "Confirm HFiles are
\* accessible" step with retry-with-backoff.
HFilesBeforeFlushMarker ==
    \A s \in flushMarkerEntries : s \in hdfsHFiles

\* ---- Promotion invariants ----

\* A promoted replica does not acknowledge client writes until the
\* master has confirmed the promotion and the replica holds a WAL
\* reference (promotionPhase = "Complete").  This verifies the design's
\* requirement that the write path gates on both isLeader() and the
\* per-region promotionComplete flag.  During the gap between winning
\* the RAFT election and completing promotion (which now includes master
\* confirmation via MasterConfirmPromotion), writes must be rejected
\* with NotServingRegionException.
\*
\* This is a cross-variable invariant (writePhase x promotionPhase) that
\* catches any action that incorrectly allows a write to proceed during
\* the Promoting or AwaitingMaster phases.  The invariant is enforced by
\* the promotionPhase[m] = "Complete" guard on BeginWrite: even though
\* IsLeader(m) returns true during the Promoting and AwaitingMaster
\* phases, the promotion guard prevents BeginWrite from firing.
PromotionReadWriteGuard ==
    \A m \in Members :
        writePhase[m] # "Idle" => promotionPhase[m] = "Complete"

\* After promotion completes, the new leader's MVCC writePoint correctly
\* accounts for all RAFT-committed entries.  Specifically, every committed
\* entry is either (a) already in memstore[m] (applied during promotion
\* step 1 or via the leader's own write pipeline), (b) covered by a
\* flush marker that the leader has applied (data is in HFiles on HDFS),
\* or (c) the leader's currently in-flight write (writeSeqId, being
\* applied via the write pipeline).  No unapplied committed entry may
\* exist outside the active write pipeline.
\*
\* This property verifies the design's guarantee that the promoted
\* leader does not create MVCC sequence gaps: after promotion, the
\* leader's MVCCWritePoint is at least as large as every committed
\* entry's seqId, and no committed entry is "lost" between the old
\* leader's crash and the new leader's first write.
\*
\* The invariant is conditioned on IsLeader(m) (role = Leader AND
\* lease valid).  A stale leader whose lease has expired may have
\* promotionPhase = "Complete" while missing entries committed by a
\* new leader in a higher term; this is harmless because the stale
\* leader cannot start new writes (BeginWrite requires IsLeader).
\* LeaderLeaseExpiry or StepDown will transition the stale leader
\* to Follower, resetting promotionPhase.
\*
\* Safety argument: LeaseExpiresBeforeElection guarantees at most one
\* member holds a valid lease at any time.  While the promoted leader
\* has a valid lease, no other leader can commit entries (all
\* entry-initiating actions require IsLeader).
\* NewLeaderCommitOrphanEntry atomically applies committed entries
\* to the leader's memstore.
\*
\* The invariant is maintained by:
\*   - PromotionComplete requiring LeaseValid(m) AND
\*     ApplicableEntries(m) = {} (all committed entries applied,
\*     with valid lease, before promotion finishes)
\*   - NewLeaderCommitOrphanEntry atomically applying committed entries
\*     to the leader's memstore (mirroring the consensus core's
\*     single-threaded AdvanceCommitIndexTask + runOperation() callback,
\*     with one RaftNodeExecutor per group)
\*   - BeginWrite assigning writeSeqId from the monotonic nextSeqId
\*     counter, which is always > max(committedEntries)
PromotionMVCCContinuity ==
    \A m \in Members :
        /\ promotionPhase[m] = "Complete"
        /\ IsLeader(m)
        => LET inFlight == IF writePhase[m] # "Idle"
                           THEN {writeSeqId[m]} ELSE {}
           IN ApplicableEntries(m) \subseteq inFlight

\* ---- Catch-up completeness ----

\* Once a follower (or promoting member) has finished processing all
\* committed entries — ApplicableEntries(m) = {} and no batch in
\* progress — the member's memstore is consistent with the committed
\* state: every committed entry is either in memstore or covered by an
\* applied flush marker (the entry's seqId is at or below the marker's
\* HFile coverage boundary flushDropBound[f]).
\*
\* This invariant verifies catch-up completeness after
\* NewMemberBootstrap.  The catching-up member starts with an empty
\* memstore and processes all committed entries through the normal
\* follower apply path (FollowerBeginBatchApply, FollowerCompleteBatchApply,
\* FollowerApplyMarker).  If the leader concurrently flushes during
\* catch-up, the flush marker arrives as a committed entry; the
\* catching-up member processes it via FollowerApplyMarker, which
\* refreshes store files from HDFS and drops memstore entries at or
\* below the flush marker's drop boundary.  After processing, all
\* entries are either in memstore (above the drop boundary) or
\* materialized in HFiles (at or below the drop boundary).  The
\* flush-watermark exclusion in ApplicableEntries prevents those
\* dropped entries from being re-applied.
CatchUpCompleteness ==
    \A m \in Members :
        (/\ ApplicableEntries(m) = {}
         /\ fApplyBatch[m] = {}
         /\ (role[m] = "Follower" \/ promotionPhase[m] \in {"Promoting", "AwaitingMaster"}))
        =>
        \A s \in committedEntries :
            \/ s \in memstore[m]
            \/ \E f \in flushMarkerEntries \cap memstore[m] : s <= flushDropBound[f]

THEOREM SafetyTHM ==
    Spec => [](/\ LeaderUniqueness
               /\ LeaseImpliesLeadership
               /\ LeaseExpiresBeforeElection
               /\ CatchUpDataIntegrity
               /\ NoFollowerExposureRollback
               /\ WriteBarrierSafety
               /\ FollowerSeqIdConsistency
               /\ NoOrphanMemstoreDrop
               /\ FlushDropBoundary
               /\ FollowerFlushMemstoreDrop
               /\ HFilesBeforeFlushMarker
               /\ PromotionReadWriteGuard
               /\ PromotionMVCCContinuity
               /\ CatchUpCompleteness)

----
(* ---- Fairness and liveness properties ---- *)

\* Fairness constraints.
\*
\* Factored into BaseFairness (WF on all non-network-dependent actions)
\* plus per-property SF additions.  This factoring is necessary because
\* TLC converts the fairness formula to DNF, where each SF_vars(A) term
\* contributes 2 disjuncts, giving 2^N branches for N SF terms.  A
\* monolithic fairness with all network-dependent actions as SF would
\* exceed TLC's DNF capacity.  Per-property specs include only the
\* minimum SF terms that the property's progress chain requires.
\*
\* WF (weak fairness) on actions whose enabling conditions do not
\* depend on network connectivity — once enabled, they remain enabled
\* until they fire (no external event disables them).
\*
\* SF (strong fairness) on actions whose enabling conditions check
\* CanCommunicate or require a majority of reachable members.  Because
\* CreatePartition is a perturbation (no fairness), it can fire at any
\* time and repeatedly disable these actions.  SF requires that if the
\* action is enabled infinitely often, it eventually fires — capturing
\* the real-world expectation that network connectivity windows are
\* long enough for atomic protocol steps to complete.
\*
\* No fairness on perturbation actions (CrashRestart, CreatePartition,
\* WALSyncFail) — failures are nondeterministic and must not be forced.
BaseFairness ==
    \* Election (Timeout only needs timerRemaining=0, no network)
    /\ \A m \in Members     : WF_vars(Timeout(m))
    \* Leadership
    /\ \A m \in Members     : WF_vars(LeaderLeaseExpiry(m))
    /\ \A m \in Members     : WF_vars(StepDown(m))
    \* Timing
    /\ \A m \in Members     : WF_vars(ClockTick(m))
    \* Write path (local I/O and pipeline steps)
    /\ \A m \in Members     : WF_vars(BeginWrite(m))
    /\ \A m \in Members     : WF_vars(WALSyncComplete(m))
    /\ \A m \in Members     : WF_vars(CompleteWrite(m))
    /\ \A m \in Members     : WF_vars(AckWrite(m))
    /\ \A m \in Members     : WF_vars(WALFailureAbort(m))
    \* Flush protocol (local steps)
    /\ \A m \in Members     : WF_vars(FlushStart(m))
    /\ \A m \in Members     : WF_vars(FlushCommitHFiles(m))
    /\ \A m \in Members     : WF_vars(FlushComplete(m))
    \* Follower apply (local processing of committed entries)
    /\ \A m \in Members     : WF_vars(FollowerBeginBatchApply(m))
    /\ \A m \in Members     : WF_vars(FollowerCompleteBatchApply(m))
    /\ \A m \in Members     : WF_vars(FollowerApplyMarker(m))
    \* Promotion (master confirmation + local completion, no network)
    /\ \A m \in Members     : WF_vars(MasterConfirmPromotion(m))
    /\ \A m \in Members     : WF_vars(PromotionComplete(m))
    \* Orphan commitment (requires IsLeader but not CanCommunicate)
    /\ WF_vars(NewLeaderCommitOrphanEntry)
    \* Log GC (local)
    /\ \A m \in Members     : WF_vars(RaftLogGC(m))

\* SF additions for ElectionProgress.
\* RequestVote + BecomeLeader + StepDown all require CanCommunicate;
\* partition oscillation can repeatedly disable them.  StepDown is
\* needed because a stale Candidate whose votesGranted >= Majority
\* blocks ClockTick (the "no pending BecomeLeader" guard) even when
\* BecomeLeader itself is disabled (higher-term members exist).
\* SF on HealAllPartitions (not HealPartition) because
\* HealPartition's \E nondeterminism allows TLC to always heal the
\* same unhelpful link.  HealAllPartitions is deterministic: it
\* forces full network recovery, preventing isolated-member cycles.
\* 16 SF terms.
ElectionSF ==
    /\ SF_vars(HealAllPartitions)
    /\ \A c, v \in Members  : SF_vars(RequestVote(c, v))
    /\ \A m \in Members     : SF_vars(BecomeLeader(m))
    /\ \A m \in Members     : SF_vars(StepDown(m))

\* SF additions for WriteCompletion.
\* RAFTCommitWrite requires majority reachable.
\* 4 SF terms.
WriteSF ==
    /\ SF_vars(HealAllPartitions)
    /\ \A m \in Members     : SF_vars(RAFTCommitWrite(m))

\* SF additions for FlushCompletion.
\* FlushRAFTPropose + FlushRAFTCommit require majority reachable.
\* 7 SF terms.
FlushSF ==
    /\ SF_vars(HealAllPartitions)
    /\ \A m \in Members     : SF_vars(FlushRAFTPropose(m))
    /\ \A m \in Members     : SF_vars(FlushRAFTCommit(m))

\* Per-property specification formulas.  Each includes only the SF
\* terms needed for its progress chain to avoid DNF blowup.
LiveSpecElection == Init /\ [][Next]_vars /\ BaseFairness /\ ElectionSF
LiveSpecWrite    == Init /\ [][Next]_vars /\ BaseFairness /\ WriteSF
LiveSpecFlush    == Init /\ [][Next]_vars /\ BaseFairness /\ FlushSF

\* PromotionCompletion and CatchUpCompletion need only local actions
\* (all WF in BaseFairness, no network dependency): follower apply
\* drains committed entries for Promotion/CatchUp.
LiveSpecLocal    == Init /\ [][Next]_vars /\ BaseFairness

\* ---- Liveness properties ----

\* If no member holds a valid leader lease, eventually some member
\* acquires one.  Depends on WF of ClockTick (timers count down)
\* and Timeout (election starts), and SF of RequestVote (votes
\* delivered despite partition oscillation), BecomeLeader (majority
\* wins despite partition oscillation), and HealAllPartitions
\* (network eventually fully recovers).
ElectionProgress ==
    (\A m \in Members : ~IsLeader(m)) ~> (\E m \in Members : IsLeader(m))

\* A write in the Pending phase eventually returns to Idle — either
\* the normal path completes (WAL sync + RAFT commit + barrier +
\* ack) or the WAL fails and the leader aborts (WALFailureAbort,
\* which resets the pipeline via crash).
WriteCompletion ==
    \A m \in Members :
        writePhase[m] = "Pending" ~> writePhase[m] = "Idle"

\* A flush in any non-Idle phase eventually returns to Idle — either
\* the phase chain completes (HFiles + propose + commit + drop) or
\* a crash resets the flush state.
FlushCompletion ==
    \A m \in Members :
        flushPhase[m] # "Idle" ~> flushPhase[m] = "Idle"

\* A member in the Promoting phase eventually leaves it — either
\* MasterConfirmPromotion fires (advancing to AwaitingMaster) or the
\* member steps down / crashes.  A member in the AwaitingMaster phase
\* eventually leaves it — either PromotionComplete fires or the member
\* steps down / crashes.  Together these ensure that promotion is
\* transient: the member eventually reaches Complete or None.
PromotionCompletion ==
    /\ \A m \in Members :
        promotionPhase[m] = "Promoting" ~> promotionPhase[m] # "Promoting"
    /\ \A m \in Members :
        promotionPhase[m] = "AwaitingMaster" ~> promotionPhase[m] # "AwaitingMaster"

\* A follower with unapplied committed entries eventually catches
\* up (ApplicableEntries drains to empty with no batch in flight)
\* or leaves the Follower role (election / crash).
CatchUpCompletion ==
    \A m \in Members :
        (role[m] = "Follower" /\ ApplicableEntries(m) # {})
            ~> (role[m] # "Follower"
                \/ (ApplicableEntries(m) = {} /\ fApplyBatch[m] = {}))

THEOREM ElectionProgressTHM    == LiveSpecElection => ElectionProgress
THEOREM WriteCompletionTHM     == LiveSpecWrite    => WriteCompletion
THEOREM FlushCompletionTHM     == LiveSpecFlush    => FlushCompletion
THEOREM PromotionCompletionTHM == LiveSpecLocal    => PromotionCompletion
THEOREM CatchUpCompletionTHM   == LiveSpecLocal    => CatchUpCompletion

====
