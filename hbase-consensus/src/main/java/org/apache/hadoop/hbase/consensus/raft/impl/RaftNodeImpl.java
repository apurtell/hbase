/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.consensus.raft.impl;

import static java.lang.Math.min;
import static java.util.Arrays.sort;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.shuffle;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.ACTIVE;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.INITIAL;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.TERMINATED;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.UPDATING_RAFT_GROUP_MEMBER_LIST;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.isTerminal;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.FOLLOWER;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.LEADER;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.LEARNER;
import static org.apache.hadoop.hbase.consensus.raft.impl.log.RaftLog.FIRST_VALID_LOG_INDEX;
import static org.apache.hadoop.hbase.consensus.raft.impl.log.RaftLog.getLogCapacity;
import static org.apache.hadoop.hbase.consensus.raft.impl.log.RaftLog.getMaxLogEntryCountToKeepAfterSnapshot;
import static org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry.isNonInitial;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.PendingBytesBudget;
import org.apache.hadoop.hbase.consensus.raft.QueryPolicy;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.hadoop.hbase.consensus.raft.exception.CannotReplicateException;
import org.apache.hadoop.hbase.consensus.raft.exception.IndeterminateStateException;
import org.apache.hadoop.hbase.consensus.raft.exception.LaggingCommitIndexException;
import org.apache.hadoop.hbase.consensus.raft.exception.NotLeaderException;
import org.apache.hadoop.hbase.consensus.raft.exception.RaftException;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.AppendEntriesFailureResponseHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.AppendEntriesRequestHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.AppendEntriesSuccessResponseHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.InstallSnapshotRequestHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.InstallSnapshotResponseHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.PreVoteRequestHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.PreVoteResponseHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.TriggerLeaderElectionHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.VoteRequestHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.VoteResponseHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.log.RaftLog;
import org.apache.hadoop.hbase.consensus.raft.impl.report.RaftLogStatsImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.report.RaftNodeReportImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.state.FollowerState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.QueryState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.QueryState.QueryContainer;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftGroupMembersState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftTermState;
import org.apache.hadoop.hbase.consensus.raft.impl.statemachine.InternalCommitAware;
import org.apache.hadoop.hbase.consensus.raft.impl.statemachine.NoOp;
import org.apache.hadoop.hbase.consensus.raft.impl.task.FlushTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.LeaderBackoffResetTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.LeaderElectionTimeoutTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.MembershipChangeTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.PreVoteTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.PreVoteTimeoutTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.QueryTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.RaftStateSummaryPublishTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.ReplicateTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.TransferLeadershipTask;
import org.apache.hadoop.hbase.consensus.raft.impl.util.OrderedFuture;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.RaftGroupOp;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp;
import org.apache.hadoop.hbase.consensus.raft.model.log.BaseLogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest.AppendEntriesRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse;
import org.apache.hadoop.hbase.consensus.raft.persistence.NopRaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.consensus.raft.report.RaftGroupMembers;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport.RaftNodeReportReason;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReportListener;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link RaftNode}.
 * <p>
 * Each Raft node runs in a single-threaded manner with an event-based approach. Raft node uses
 * {@link RaftNodeExecutor} to run its tasks, {@link StateMachine} to execute committed operations
 * on the user-supplied state machine, and {@link RaftStore} to persist internal Raft state to
 * stable storage.
 */
@InterfaceAudience.Private
public final class RaftNodeImpl implements RaftNode {
  private static final Logger LOG = LoggerFactory.getLogger(RaftNode.class);
  private static final int LEADER_ELECTION_TIMEOUT_NOISE_MILLIS = 100;
  private static final long LEADER_BACKOFF_RESET_TASK_PERIOD_MILLIS = 250;
  private static final int MIN_BACKOFF_ROUNDS = 4;

  private final Object groupId;
  private final RaftState state;
  private final RaftConfig config;
  private final Transport transport;
  private final RaftNodeExecutor executor;
  private final StateMachine stateMachine;
  private final RaftModelFactory modelFactory;
  private final RaftStore store;
  private final RaftNodeReportListener raftNodeReportListener;
  private final String localEndpointStr;
  private final Random random;
  private final Clock clock;
  private final HeartbeatScheduler heartbeatScheduler;
  private final IntSupplier activeGroupCountSupplier;
  private final double electionRandomizationGroupScale;
  private final long leaderHeartbeatTimeoutMillis;
  private final int commitCountToTakeSnapshot;
  private final int appendEntriesRequestBatchSize;
  private final int maxPendingLogEntryCount;
  private final PendingBytesBudget pendingBytesBudget;
  /**
   * Per-node bookkeeping of pending-bytes credits this node has acquired from
   * {@link #pendingBytesBudget} and not yet released. Keys are leader-side log indices for which a
   * {@code byte[]} payload was admitted; values are the byte counts reserved for those indices.
   * Mutated only on the per-group executor thread so a plain {@link HashMap} suffices.
   * <p>
   * Used to:
   * <ul>
   * <li>Release the precise number of credits per entry as it is committed in the apply path.</li>
   * <li>Wholesale-release outstanding credits when the leader steps down or terminates without
   * having committed every reserved entry.</li>
   * </ul>
   * Bounded above by {@link #maxPendingLogEntryCount}.
   */
  private final HashMap<Long, Long> reservedBytesByIndex = new HashMap<>();
  private final int maxLogEntryCountToKeepAfterSnapshot;
  private final int maxBackoffRounds;
  private Runnable leaderBackoffResetTask;
  private Runnable leaderFlushTask;
  private final List<RaftNodeLifecycleAware> lifecycleAwareComponents = new ArrayList<>();
  private final List<RaftNodeLifecycleAware> startedLifecycleAwareComponents = new ArrayList<>();
  // Cached slow-path control-lane Runnables submitted by the bulk-heartbeat wheel and the
  // election-timer follower walk. Held as fields so {@code submitControl} does not allocate a
  // capturing lambda per dispatch.
  private final Runnable demoteToFollowerIfLeaseExpiredTask = this::demoteToFollowerIfLeaseExpired;
  private final Runnable sendCatchupAppendsIfNeededTask = this::sendCatchupAppendsIfNeeded;
  private final Runnable resetLeaderAndTryTriggerPreVoteTask =
    () -> resetLeaderAndTryTriggerPreVote(true);
  // Volatile + AtomicLongFieldUpdater for monotonic-max updates so the bulk inbound handler can
  // refresh this field on the netty event-loop thread without entering the per-group executor.
  // Lost updates are harmless. A stale write loses to a fresher concurrent write.
  private volatile long lastLeaderHeartbeatTimestamp;
  // Updated whenever the local follower has a reason to defer starting its own
  // election: a real leader heartbeat (AppendEntries/InstallSnapshot) or a vote
  // grant. Kept distinct from {@link #lastLeaderHeartbeatTimestamp} so that
  // sticky-leader checks in (Pre)VoteRequestHandler continue to reflect lease
  // safety (only true leader heartbeats refresh the lease), while the
  // election scheduler still avoids preempting a candidate it just voted for.
  // Volatile for the same reason as {@link #lastLeaderHeartbeatTimestamp}.
  private volatile long lastElectionTimerResetTimestamp;
  // Per-follower randomized election timeout, re-rolled on every reset (leader heartbeat
  // observed, vote grant) so simultaneous followers de-correlate on the wall clock and avoid
  // election storms at high group counts. Floor stays at leaderHeartbeatTimeoutMillis. Upper end
  // is widened with the active group count via electionRandomizationGroupScale. Volatile so the
  // bulk inbound handler can publish a fresh sample on the netty event-loop thread.
  private volatile long currentElectionTimeoutMillis;
  private static final java.util.concurrent.atomic.AtomicLongFieldUpdater<RaftNodeImpl> LAST_HB =
    java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(RaftNodeImpl.class,
      "lastLeaderHeartbeatTimestamp");
  private static final java.util.concurrent.atomic.AtomicLongFieldUpdater<RaftNodeImpl> LAST_ER =
    java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(RaftNodeImpl.class,
      "lastElectionTimerResetTimestamp");
  private volatile RaftNodeStatus status = INITIAL;
  private int takeSnapshotCount;
  private int installSnapshotCount;

  @SuppressWarnings("checkstyle:executablestatementcount")
  RaftNodeImpl(Object groupId, RaftEndpoint localEndpoint, RaftGroupMembersView initialGroupMembers,
    RaftConfig config, RaftNodeExecutor executor, StateMachine stateMachine, Transport transport,
    RaftModelFactory modelFactory, RaftStore store, RaftNodeReportListener raftNodeReportListener,
    Random random, Clock clock, HeartbeatScheduler heartbeatScheduler,
    IntSupplier activeGroupCountSupplier, PendingBytesBudget pendingBytesBudget) {
    requireNonNull(localEndpoint);
    this.groupId = requireNonNull(groupId);
    this.transport = requireNonNull(transport);
    this.executor = requireNonNull(executor);
    this.stateMachine = requireNonNull(stateMachine);
    this.modelFactory = requireNonNull(modelFactory);
    this.store = requireNonNull(store);
    this.raftNodeReportListener = requireNonNull(raftNodeReportListener);
    this.config = requireNonNull(config);
    this.localEndpointStr = localEndpoint.getId() + "<" + groupId + ">";
    this.leaderHeartbeatTimeoutMillis = config.getLeaderHeartbeatTimeoutMillis();
    this.electionRandomizationGroupScale = config.getElectionRandomizationGroupScale();
    this.commitCountToTakeSnapshot = config.getCommitCountToTakeSnapshot();
    this.appendEntriesRequestBatchSize = config.getAppendEntriesRequestBatchSize();
    this.maxPendingLogEntryCount = config.getMaxPendingLogEntryCount();
    this.pendingBytesBudget = requireNonNull(pendingBytesBudget);
    this.maxLogEntryCountToKeepAfterSnapshot =
      getMaxLogEntryCountToKeepAfterSnapshot(commitCountToTakeSnapshot);
    int logCapacity = getLogCapacity(commitCountToTakeSnapshot, maxPendingLogEntryCount);
    this.state = RaftState.create(groupId, localEndpoint, initialGroupMembers, logCapacity, store,
      modelFactory);
    this.maxBackoffRounds = getMaxBackoffRounds(config);
    this.random = requireNonNull(random);
    this.clock = requireNonNull(clock);
    this.heartbeatScheduler = requireNonNull(heartbeatScheduler);
    this.activeGroupCountSupplier = requireNonNull(activeGroupCountSupplier);
    this.currentElectionTimeoutMillis = sampleElectionTimeoutMillis();
    populateLifecycleAwareComponents();
  }

  @SuppressWarnings("checkstyle:executablestatementcount")
  RaftNodeImpl(Object groupId, RestoredRaftState restoredState, RaftConfig config,
    RaftNodeExecutor executor, StateMachine stateMachine, Transport transport,
    RaftModelFactory modelFactory, RaftStore store, RaftNodeReportListener raftNodeReportListener,
    Random random, Clock clock, HeartbeatScheduler heartbeatScheduler,
    IntSupplier activeGroupCountSupplier, PendingBytesBudget pendingBytesBudget) {
    requireNonNull(store);
    this.groupId = requireNonNull(groupId);
    this.transport = requireNonNull(transport);
    this.executor = requireNonNull(executor);
    this.stateMachine = requireNonNull(stateMachine);
    this.modelFactory = requireNonNull(modelFactory);
    this.store = requireNonNull(store);
    this.raftNodeReportListener = requireNonNull(raftNodeReportListener);
    this.config = requireNonNull(config);
    this.localEndpointStr =
      restoredState.getLocalEndpointPersistentState().getLocalEndpoint().getId() + "<" + groupId
        + ">";
    this.leaderHeartbeatTimeoutMillis = config.getLeaderHeartbeatTimeoutMillis();
    this.electionRandomizationGroupScale = config.getElectionRandomizationGroupScale();
    this.commitCountToTakeSnapshot = config.getCommitCountToTakeSnapshot();
    this.appendEntriesRequestBatchSize = config.getAppendEntriesRequestBatchSize();
    this.maxPendingLogEntryCount = config.getMaxPendingLogEntryCount();
    this.pendingBytesBudget = requireNonNull(pendingBytesBudget);
    this.maxLogEntryCountToKeepAfterSnapshot =
      getMaxLogEntryCountToKeepAfterSnapshot(commitCountToTakeSnapshot);
    int logCapacity = getLogCapacity(commitCountToTakeSnapshot, maxPendingLogEntryCount);
    this.state = RaftState.restore(groupId, restoredState, logCapacity, store, modelFactory);
    this.maxBackoffRounds = getMaxBackoffRounds(config);
    this.random = requireNonNull(random);
    this.clock = requireNonNull(clock);
    this.heartbeatScheduler = requireNonNull(heartbeatScheduler);
    this.activeGroupCountSupplier = requireNonNull(activeGroupCountSupplier);
    this.currentElectionTimeoutMillis = sampleElectionTimeoutMillis();
    populateLifecycleAwareComponents();
  }

  /**
   * Rolls a fresh per-follower election timeout in
   * {@code [leaderHeartbeatTimeoutMillis, leaderHeartbeatTimeoutMillis * (2 + scale * log(activeGroups)))}.
   * Called from each timer-reset site so simultaneous followers de-correlate on the wall clock.
   */
  private long sampleElectionTimeoutMillis() {
    long lower = leaderHeartbeatTimeoutMillis;
    int groups = Math.max(1, activeGroupCountSupplier.getAsInt());
    double upperMultiplier = 2.0d + (electionRandomizationGroupScale > 0d
      ? electionRandomizationGroupScale * Math.log(groups)
      : 0.0d);
    long upperExclusive = (long) Math.ceil((double) lower * upperMultiplier);
    if (upperExclusive <= lower) {
      return lower;
    }
    long span = upperExclusive - lower;
    long offset = ((long) (ThreadLocalRandom.current().nextDouble() * span));
    if (offset < 0L) {
      offset = 0L;
    } else if (offset >= span) {
      offset = span - 1L;
    }
    return lower + offset;
  }

  private void populateLifecycleAwareComponents() {
    for (Object component : Arrays.asList(executor, transport, stateMachine, store, modelFactory,
      raftNodeReportListener, heartbeatScheduler)) {
      if (component instanceof RaftNodeLifecycleAware) {
        lifecycleAwareComponents.add((RaftNodeLifecycleAware) component);
      }
    }
    shuffle(lifecycleAwareComponents);
  }

  private int getMaxBackoffRounds(RaftConfig config) {
    long durationMillis;
    if (
      config.getLeaderHeartbeatPeriodMillis() == SECONDS.toMillis(1)
        && config.getLeaderHeartbeatTimeoutMillis() > SECONDS.toMillis(1)
    ) {
      durationMillis = SECONDS.toMillis(2);
    } else {
      durationMillis = config.getLeaderHeartbeatPeriodMillis();
    }
    return (int) (durationMillis / LEADER_BACKOFF_RESET_TASK_PERIOD_MILLIS);
  }

  /**
   * Returns true if a new operation is allowed to be replicated. This method must be invoked only
   * when the local Raft node is the Raft group leader.
   * <p>
   * Replication is not allowed when:
   * <ul>
   * <li>The local Raft log has no more empty slots for pending entries.</li>
   * <li>The given operation is a {@link RaftGroupOp} and there's an ongoing membership change in
   * group.</li>
   * <li>The operation is a membership change and there's no committed entry in the current term
   * yet.</li>
   * </ul>
   * <p>
   * The server-wide pending-bytes budget is <i>not</i> consulted here: it is enforced by the
   * propose path via {@link #tryReserveLeaderPendingBytes(long)} immediately before the log append,
   * atomically with the reservation. Splitting the count check (here) from the byte-credit acquire
   * (in the propose path) keeps this method side-effect-free for callers that want a pure-query
   * precondition.
   * @param operation the operation to check for replication
   * @return true if the given operation can be replicated, false otherwise
   * @see RaftNodeStatus
   * @see RaftGroupOp
   * @see RaftConfig#getMaxPendingLogEntryCount()
   * @see #tryReserveLeaderPendingBytes(long)
   * @see StateMachine#getNewTermOperation()
   */
  public boolean canReplicateNewOperation(Object operation) {
    RaftLog log = state.log();
    long lastLogIndex = log.lastLogOrSnapshotIndex();
    long commitIndex = state.commitIndex();
    if (lastLogIndex - commitIndex >= maxPendingLogEntryCount) {
      return false;
    }
    if (status == UPDATING_RAFT_GROUP_MEMBER_LIST) {
      return (state.effectiveGroupMembers().isKnownMember(getLocalEndpoint())
        && !(operation instanceof RaftGroupOp));
    }
    if (operation instanceof UpdateRaftGroupMembersOp) {
      // the leader must have committed an entry in its term to make a membership
      // change
      // https://groups.google.com/forum/#!msg/raft-dev/t4xj6dJTP6E/d2D9LrWRza8J
      // last committed entry is either in the last snapshot or still in the log
      BaseLogEntry lastCommittedEntry =
        commitIndex == log.snapshotIndex() ? log.snapshotEntry() : log.getLogEntry(commitIndex);
      assert lastCommittedEntry != null;
      return lastCommittedEntry.getTerm() == state.term();
    }
    return state.leadershipTransferState() == null;
  }

  /**
   * Atomically reserves {@code bytes} of credit from the server-wide {@link PendingBytesBudget} for
   * an admitted leader-side propose at {@code logIndex}. Returns {@code true} on success (caller
   * must {@link #releaseLeaderPendingBytesAt(long) release} the credit when the entry commits or
   * wholesale-release on step-down / terminate); returns {@code false} if the global budget is
   * exhausted, in which case the propose must fail with {@link CannotReplicateException}.
   * <p>
   * {@code bytes <= 0} is a no-op success: non-{@code byte[]} ops do not occupy heap-pinned payload
   * bytes and so do not need to enter the credit accounting. Callers must invoke this on the
   * per-group executor thread; the per-node bookkeeping map is not synchronized.
   */
  public boolean tryReserveLeaderPendingBytes(long logIndex, long bytes) {
    if (bytes <= 0L) {
      return true;
    }
    if (!pendingBytesBudget.tryAcquire(bytes)) {
      return false;
    }
    Long previous = reservedBytesByIndex.put(logIndex, bytes);
    if (previous != null) {
      // Defensive: re-reserving for the same index would lose track of the prior reservation.
      // This should not happen because each leader-side log index is appended exactly once.
      // Treat the older reservation as still owed and aggregate.
      reservedBytesByIndex.put(logIndex, previous + bytes);
    }
    return true;
  }

  /**
   * Releases the credit (if any) previously reserved at {@code logIndex} via
   * {@link #tryReserveLeaderPendingBytes(long, long)}. Call sites: per-entry on commit-apply (the
   * entry is no longer "uncommitted" on this leader, so the back-pressure should let new proposes
   * flow), and on log truncation that drops a previously reserved index without committing it.
   * No-op if the index has no outstanding reservation.
   */
  public void releaseLeaderPendingBytesAt(long logIndex) {
    Long bytes = reservedBytesByIndex.remove(logIndex);
    if (bytes != null && bytes > 0L) {
      pendingBytesBudget.release(bytes);
    }
  }

  /**
   * Releases <i>all</i> credit this node has currently reserved against the server-wide
   * {@link PendingBytesBudget}. Called when the node steps down to follower or terminates with
   * uncommitted leader-side reservations still outstanding (those entries will either be committed
   * by a different leader, in which case the new leader's credits are what matter, or truncated by
   * a different leader's log).
   */
  public void releaseAllLeaderPendingBytes() {
    if (reservedBytesByIndex.isEmpty()) {
      return;
    }
    long total = 0L;
    for (Long bytes : reservedBytesByIndex.values()) {
      if (bytes != null) {
        total += bytes;
      }
    }
    reservedBytesByIndex.clear();
    if (total > 0L) {
      pendingBytesBudget.release(total);
    }
  }

  /**
   * Returns the {@link PendingBytesBudget} this node consults for leader-side propose admission.
   */
  public PendingBytesBudget pendingBytesBudget() {
    return pendingBytesBudget;
  }

  /**
   * Returns true if a new query is currently allowed to be executed without appending an entry to
   * the Raft log. This method must be invoked only when the local Raft node is the leader.
   * <p>
   * A new linearizable query execution is not allowed when:
   * <ul>
   * <li>If the leader has not yet committed an entry in the current term. See Section 6.4 of Raft
   * Dissertation.</li>
   * <li>There are already a lot of queries waiting to be executed.</li>
   * </ul>
   * @return true if a new query can be executed with the linearizability guarantee without
   *         appending an entry to the Raft log.
   * @see RaftNodeStatus
   * @see RaftConfig#getMaxPendingLogEntryCount()
   */
  public boolean canQueryLinearizable() {
    long commitIndex = state.commitIndex();
    RaftLog log = state.log();
    // If the leader has not yet marked an entry from its current term committed, it
    // waits until it has done so.
    // (§6.4)
    // last committed entry is either in the last snapshot or still in the log
    BaseLogEntry lastCommittedEntry =
      commitIndex == log.snapshotIndex() ? log.snapshotEntry() : log.getLogEntry(commitIndex);
    assert lastCommittedEntry != null;
    if (lastCommittedEntry.getTerm() != state.term()) {
      return false;
    }
    // We can execute multiple queries at one-shot without appending to the Raft log,
    // and we use the maxPendingLogEntryCount configuration parameter to upper-bound
    // the number of queries that are collected until the heartbeat round is done.
    QueryState queryState = state.leaderState().queryState();
    return queryState.queryCount() < maxPendingLogEntryCount;
  }

  @SuppressWarnings("unchecked")
  public void sendSnapshotChunk(RaftEndpoint follower, long snapshotIndex,
    int requestedSnapshotChunkIndex) {
    // this node can be a leader or a follower!
    LeaderState leaderState = state.leaderState();
    FollowerState followerState = null;
    if (leaderState != null) {
      followerState = leaderState.getFollowerStateOrNull(follower);
      if (followerState == null) {
        LOG.warn("{} follower: {} not found to send snapshot chunk.", localEndpointStr,
          follower.getId());
        return;
      }
    }
    SnapshotEntry snapshotEntry = state.log().snapshotEntry();
    SnapshotChunk snapshotChunk = null;
    Collection<RaftEndpoint> snapshottedMembers;
    if (snapshotEntry.getIndex() == snapshotIndex) {
      List<SnapshotChunk> snapshotChunks = (List<SnapshotChunk>) snapshotEntry.getOperation();
      snapshotChunk = snapshotChunks.get(requestedSnapshotChunkIndex);
      if (leaderState != null && snapshotEntry.getTerm() < state.term()) {
        // I am the new leader but there is no new snapshot yet.
        // So I'll send my own snapshotted members list.
        snapshottedMembers = getSnapshottedMembers(leaderState, snapshotEntry);
      } else {
        snapshottedMembers = Collections.emptyList();
      }
      LOG.info("{} sending snapshot chunk: {} to {} for snapshot index: {}", localEndpointStr,
        requestedSnapshotChunkIndex, follower.getId(), snapshotIndex);
    } else if (snapshotEntry.getIndex() > snapshotIndex) {
      if (leaderState == null) {
        return;
      }
      // there is a new snapshot. I'll send a new snapshotted members list.
      snapshottedMembers = getSnapshottedMembers(leaderState, snapshotEntry);
      LOG.info(
        "{} sending empty snapshot chunk list to {} because requested snapshot index: "
          + "{} is smaller than the current snapshot index: {}",
        localEndpointStr, follower.getId(), snapshotIndex, snapshotEntry.getIndex());
    } else {
      LOG.error(
        "{} requested snapshot index: {} for snapshot chunk indices: {} from {} is bigger than "
          + "current snapshot index: {}",
        localEndpointStr, snapshotIndex, requestedSnapshotChunkIndex, follower,
        snapshotEntry.getIndex());
      return;
    }
    RaftMessage request = modelFactory.createInstallSnapshotRequestBuilder()
      .setGroupId(getGroupId()).setSender(getLocalEndpoint()).setTerm(state.term())
      .setSenderLeader(leaderState != null).setSnapshotTerm(snapshotEntry.getTerm())
      .setSnapshotIndex(snapshotEntry.getIndex())
      .setTotalSnapshotChunkCount(snapshotEntry.getSnapshotChunkCount())
      .setSnapshotChunk(snapshotChunk).setSnapshottedMembers(snapshottedMembers)
      .setGroupMembersView(snapshotEntry.getGroupMembersView())
      .setQuerySequenceNumber(
        (leaderState != null) ? leaderState.querySequenceNumber(state.isVotingMember(follower)) : 0)
      .setFlowControlSequenceNumber(followerState != null ? enableBackoff(followerState) : 0)
      .build();
    send(follower, request);
    if (followerState != null) {
      scheduleLeaderRequestBackoffResetTask(leaderState);
    }
  }

  @NonNull
  @Override
  public Object getGroupId() {
    return groupId;
  }

  @NonNull
  @Override
  public RaftEndpoint getLocalEndpoint() {
    return state.localEndpoint();
  }

  @NonNull
  @Override
  public RaftConfig getConfig() {
    return config;
  }

  @NonNull
  @Override
  public RaftTermState getTerm() {
    // volatile read
    return state.termState();
  }

  @NonNull
  @Override
  public RaftNodeStatus getStatus() {
    // volatile read
    return status;
  }

  /**
   * Updates status of the Raft node with the given status. the new status to set on the local Raft
   * node.
   */
  public void setStatus(RaftNodeStatus newStatus) {
    if (isTerminal(status)) {
      throw new IllegalStateException(
        "Cannot set status: " + newStatus + " since already " + this.status);
    } else if (this.status == newStatus) {
      return;
    }
    RaftNodeStatus previousStatus = this.status;
    this.status = newStatus;
    if (LOG.isTraceEnabled()) {
      LOG.trace("TRACE> {} setStatus {} -> {}", localEndpointStr, previousStatus, newStatus,
        new Throwable("setStatus call site"));
    }
    if (newStatus == ACTIVE) {
      LOG.info("{} Status is set to {}", localEndpointStr, newStatus);
    } else {
      LOG.warn("{} Status is set to {}", localEndpointStr, newStatus);
    }
    if (isTerminal(newStatus)) {
      releaseAllLeaderPendingBytes();
    }
    publishRaftNodeReport(RaftNodeReportReason.STATUS_CHANGE);
  }

  @NonNull
  @Override
  public RaftGroupMembersState getInitialMembers() {
    return state.initialMembers();
  }

  @NonNull
  @Override
  public RaftGroupMembersState getCommittedMembers() {
    return state.committedGroupMembers();
  }

  @NonNull
  @Override
  public RaftGroupMembersState getEffectiveMembers() {
    return state.effectiveGroupMembers();
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<Object>> start() {
    OrderedFuture<Object> future = new OrderedFuture<>();
    if (status != INITIAL) {
      future.fail(new IllegalStateException("Cannot start RaftNode when " + status));
      return future;
    }
    executor.execute(() -> {
      if (status != INITIAL) {
        future.fail(new IllegalStateException(
          "Cannot start RaftNode of `" + localEndpointStr + " when " + status));
        return;
      }
      LOG.info("{} Starting for {} with {} members: {} and voting members; {}.", localEndpointStr,
        groupId, state.memberCount(), state.members(), state.votingMembers());
      Throwable failure = null;
      try {
        initTasks();
        startComponents();
        initRestoredState();
        state.persistInitialState(modelFactory.createRaftGroupMembersViewBuilder());
        // the status could be UPDATING_GROUP_MEMBER_LIST after
        // restoring Raft state so we only switch to ACTIVE only if
        // the status is INITIAL.
        if (status == INITIAL) {
          setStatus(ACTIVE);
        }
        LOG.info(
          "{} -> committed members: {} effective members: {} initial members: {} leader election quorum: {} is local member voting? {} initial voting members: {}",
          localEndpointStr, state.committedGroupMembers(), state.effectiveGroupMembers(),
          state.initialMembers(), state.leaderElectionQuorumSize(),
          state.initialMembers().getVotingMembers().contains(state.localEndpoint()),
          state.initialMembers().getVotingMembers());
        if (
          state.committedGroupMembers().getLogIndex() == 0
            && state.effectiveGroupMembers().getLogIndex() == 0
            && state.initialMembers().getVotingMembers().contains(state.localEndpoint())
            && state.leaderElectionQuorumSize() == 1
        ) {
          // this node is starting for the first time as a singleton Raft group
          LOG.info("{} is the single voting member in the Raft group.", localEndpointStr());
          toSingletonLeader();
        } else {
          LOG.info("{} started.", localEndpointStr);
          runPreVote();
        }
      } catch (Throwable t) {
        failure = t;
        LOG.error(localEndpointStr + " could not start.", t);
        setStatus(TERMINATED);
        terminateComponents();
      } finally {
        if (failure == null) {
          future.completeNull(state.commitIndex());
        } else {
          future.fail(failure);
        }
      }
    });
    return future;
  }

  public RaftNodeReportListener getRaftNodeReportListener() {
    return raftNodeReportListener;
  }

  private void initTasks() {
    if (!(store instanceof NopRaftStore)) {
      leaderFlushTask = new FlushTask(this);
    }
    leaderBackoffResetTask = new LeaderBackoffResetTask(this);
    heartbeatScheduler.register(this);
    executor.schedule(new RaftStateSummaryPublishTask(this),
      config.getRaftNodeReportPublishPeriodSecs(), SECONDS);
  }

  private void startComponents() {
    for (RaftNodeLifecycleAware component : lifecycleAwareComponents) {
      startedLifecycleAwareComponents.add(component);
      component.onRaftNodeStart();
    }
  }

  private void terminateComponents() {
    LOG.trace("TRACE> {} terminateComponents enter status={} role={}", localEndpointStr, status,
      state.role());
    try {
      heartbeatScheduler.unregister(this);
    } catch (Throwable t) {
      LOG.error(localEndpointStr + " failure during heartbeat scheduler unregister", t);
    }
    for (RaftNodeLifecycleAware component : startedLifecycleAwareComponents) {
      try {
        component.onRaftNodeTerminate();
      } catch (Throwable t) {
        LOG.error(localEndpointStr + " failure during termination of " + component, t);
      }
    }
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<Object>> terminate() {
    if (isTerminal(status)) {
      return CompletableFuture.completedFuture(null);
    }
    OrderedFuture<Object> future = new OrderedFuture<>();
    executor.execute(() -> {
      if (isTerminal(status)) {
        future.completeNull(state.commitIndex());
        return;
      }
      Throwable failure = null;
      boolean shouldTerminate = (status != INITIAL);
      try {
        if (shouldTerminate) {
          toFollower(state.term());
        }
        setStatus(TERMINATED);
        state.invalidateScheduledQueries();
      } catch (Throwable t) {
        failure = t;
        LOG.error("Failure during termination of " + localEndpointStr, t);
        if (status != TERMINATED) {
          setStatus(TERMINATED);
        }
      } finally {
        if (shouldTerminate) {
          terminateComponents();
        }
        if (failure == null) {
          future.completeNull(state.commitIndex());
        } else {
          future.fail(failure);
        }
      }
    });
    return future;
  }

  @Override
  @SuppressWarnings("checkstyle:cyclomaticcomplexity")
  public void handle(@NonNull RaftMessage message) {
    if (isTerminal(status)) {
      if (LOG.isDebugEnabled()) {
        LOG.warn("{} will not handle {} because {}", localEndpointStr, message, status);
      } else {
        LOG.warn("{} will not handle {} because {}", localEndpointStr,
          message.getClass().getSimpleName(), status);
      }
      return;
    }
    Runnable handler;
    if (message instanceof AppendEntriesRequest) {
      handler = new AppendEntriesRequestHandler(this, (AppendEntriesRequest) message);
    } else if (message instanceof AppendEntriesSuccessResponse) {
      handler =
        new AppendEntriesSuccessResponseHandler(this, (AppendEntriesSuccessResponse) message);
    } else if (message instanceof AppendEntriesFailureResponse) {
      handler =
        new AppendEntriesFailureResponseHandler(this, (AppendEntriesFailureResponse) message);
    } else if (message instanceof InstallSnapshotRequest) {
      handler = new InstallSnapshotRequestHandler(this, (InstallSnapshotRequest) message);
    } else if (message instanceof InstallSnapshotResponse) {
      handler = new InstallSnapshotResponseHandler(this, (InstallSnapshotResponse) message);
    } else if (message instanceof VoteRequest) {
      handler = new VoteRequestHandler(this, (VoteRequest) message);
    } else if (message instanceof VoteResponse) {
      handler = new VoteResponseHandler(this, (VoteResponse) message);
    } else if (message instanceof PreVoteRequest) {
      handler = new PreVoteRequestHandler(this, (PreVoteRequest) message);
    } else if (message instanceof PreVoteResponse) {
      handler = new PreVoteResponseHandler(this, (PreVoteResponse) message);
    } else if (message instanceof TriggerLeaderElectionRequest) {
      handler = new TriggerLeaderElectionHandler(this, (TriggerLeaderElectionRequest) message);
    } else {
      throw new IllegalArgumentException("Invalid Raft msg: " + message);
    }
    try {
      if (isControlLaneMessage(message)) {
        executor.executeControl(handler);
      } else {
        executor.execute(handler);
      }
    } catch (Throwable t) {
      if (LOG.isDebugEnabled()) {
        LOG.error(localEndpointStr + " could not handle " + message, t);
      }
    }
  }

  /**
   * {@link RaftMessage} interfaces whose implementations must be handled on the
   * {@link RaftNodeExecutor#executeControl(Runnable) control} lane. Concrete message classes are
   * classified once via {@link #CONTROL_LANE_MESSAGE_CACHE} so the per-RPC hot path remains a
   * single identity-keyed map lookup, while still honoring {@code instanceof} subtype semantics for
   * any {@link RaftMessage} implementation. Keep this list in sync with
   * {@link #isControlLaneMessage(RaftMessage)} and {@link #handle(RaftMessage)}.
   */
  private static final Set<Class<? extends RaftMessage>> CONTROL_LANE_MESSAGE_INTERFACES;
  static {
    Set<Class<? extends RaftMessage>> s = new HashSet<>(8);
    s.add(VoteRequest.class);
    s.add(VoteResponse.class);
    s.add(PreVoteRequest.class);
    s.add(PreVoteResponse.class);
    s.add(TriggerLeaderElectionRequest.class);
    CONTROL_LANE_MESSAGE_INTERFACES = Collections.unmodifiableSet(s);
  }

  /**
   * Per-concrete-class cache of control-lane membership. Each {@link RaftMessage} concrete class is
   * classified once against {@link #CONTROL_LANE_MESSAGE_INTERFACES} via
   * {@link Class#isAssignableFrom(Class)} and the result is memoized for subsequent dispatches.
   */
  private static final ClassValue<Boolean> CONTROL_LANE_MESSAGE_CACHE = new ClassValue<Boolean>() {
    @Override
    protected Boolean computeValue(Class<?> type) {
      for (Class<? extends RaftMessage> iface : CONTROL_LANE_MESSAGE_INTERFACES) {
        if (iface.isAssignableFrom(type)) {
          return Boolean.TRUE;
        }
      }
      return Boolean.FALSE;
    }
  };

  /**
   * Returns true if the given Raft message must be handled on the
   * {@link RaftNodeExecutor#executeControl(Runnable) control} lane of the per-group executor
   * mailbox.
   * <p>
   * Control-lane messages are leader election traffic (vote, pre-vote,
   * {@link TriggerLeaderElectionRequest}). Heartbeat traffic does not flow through
   * {@link #handle(RaftMessage)} (the per-server bulk envelope path inbound-dispatches it directly
   * via {@code BulkHeartbeatFrameHandler} / {@code BulkHeartbeatAckFrameHandler}).
   * <p>
   * Bulk handlers ({@link AppendEntriesRequest} and its acks, {@link InstallSnapshotRequest} and
   * its ack) keep the default {@link RaftNodeExecutor#execute(Runnable)} lane: every ack runs on
   * the same lane as the request that produced it, so an
   * {@link AppendEntriesSuccessResponse}/{@link AppendEntriesFailureResponse} that advances
   * commitIndex (and therefore unblocks a client {@code replicate} future) is processed on the bulk
   * lane behind any preceding bulk work, not in front of an unrelated heartbeat tick.
   * <p>
   * The classifier is exposed for tests that wire alternative {@link RaftNode} implementations
   * (e.g. spies in the inbound-classification transport tests) without forcing them to redefine the
   * same classification logic as {@link #handle(RaftMessage)} above.
   */
  public static boolean isControlLaneMessage(@NonNull RaftMessage message) {
    return CONTROL_LANE_MESSAGE_CACHE.get(message.getClass());
  }

  @NonNull
  @Override
  public <T> CompletableFuture<Ordered<T>> replicate(@NonNull Object operation) {
    OrderedFuture<T> future = new OrderedFuture<>();
    return executeIfRunning(new ReplicateTask(this, requireNonNull(operation), future), future);
  }

  @NonNull
  @Override
  public <T> CompletableFuture<Ordered<T>> query(@NonNull Object operation,
    @NonNull QueryPolicy queryPolicy, long minCommitIndex, long timeoutMillis) {
    OrderedFuture<T> future = new OrderedFuture<>();
    Runnable task = new QueryTask(this, requireNonNull(operation), queryPolicy,
      Math.max(minCommitIndex, 0L), Math.max(timeoutMillis, 0L), future);
    return executeIfRunning(task, future);
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<Object>> waitFor(long minCommitIndex, Duration timeout) {
    return query(NoOp.INSTANCE, QueryPolicy.EVENTUAL_CONSISTENCY, minCommitIndex,
      timeout.toMillis());
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<RaftGroupMembers>> changeMembership(
    @NonNull RaftEndpoint endpoint, @NonNull MembershipChangeMode mode,
    long expectedGroupMembersCommitIndex) {
    OrderedFuture<RaftGroupMembers> future = new OrderedFuture<>();
    Runnable task = new MembershipChangeTask(this, future, requireNonNull(endpoint),
      requireNonNull(mode), expectedGroupMembersCommitIndex);
    return executeIfRunning(task, future);
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<Object>> transferLeadership(@NonNull RaftEndpoint endpoint) {
    requireNonNull(endpoint);
    OrderedFuture<Object> future = new OrderedFuture<>();
    Runnable task = new TransferLeadershipTask(this, endpoint, future);
    return executeIfRunning(task, future);
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<RaftNodeReport>> getReport() {
    OrderedFuture<RaftNodeReport> future = new OrderedFuture<>();
    return executeIfRunning(() -> {
      try {
        future.complete(state.commitIndex(), newReport(RaftNodeReportReason.API_CALL));
      } catch (Throwable t) {
        future.fail(t);
      }
    }, future);
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<RaftNodeReport>> takeSnapshot() {
    OrderedFuture<RaftNodeReport> future = new OrderedFuture<>();
    return executeIfRunning(() -> {
      try {
        if (status == INITIAL || isTerminal(status)) {
          future.fail(newNotRunningException());
          return;
        }
        if (state.commitIndex() < FIRST_VALID_LOG_INDEX) {
          future.fail(new IllegalStateException(
            localEndpointStr + " cannot take a snapshot before committing a log entry!"));
        }
        applyLogEntries();
        if (isTerminal(status)) {
          LOG.warn("{} cannot take snapshot since it is {}", localEndpointStr, status);
          future.fail(newNotRunningException());
          return;
        }
        RaftNodeReport report = null;
        if (state.log().snapshotIndex() < state.lastApplied()) {
          takeSnapshot(state.log(), state.lastApplied());
          report = newReport(RaftNodeReportReason.TAKE_SNAPSHOT);
          LOG.info("{} took a snapshot via manual trigger at log index: {}", localEndpointStr,
            state.lastApplied());
        }
        future.complete(state.lastApplied(), report);
      } catch (Throwable t) {
        future.fail(t);
      }
    }, future);
  }

  private RaftNodeReportImpl newReport(RaftNodeReportReason reason) {
    LeaderState leaderState = state.leaderState();
    Map<RaftEndpoint, Long> heartbeatTimestamps =
      leaderState != null ? leaderState.responseTimestamps() : Collections.emptyMap();
    long quorumTimestamp = leaderState != null
      ? leaderState.quorumResponseTimestamp(state.logReplicationQuorumSize(), clock.millis())
      : 0L;
    // Non-zero if this node is not leader and received at least one heartbeat from the leader.
    long leaderHeartbeatTimestamp = (quorumTimestamp == 0L && this.lastLeaderHeartbeatTimestamp > 0)
      ? Math.min(this.lastLeaderHeartbeatTimestamp, clock.millis())
      : 0L;
    return new RaftNodeReportImpl(requireNonNull(reason), groupId, state.localEndpoint(),
      state.initialMembers(), state.committedGroupMembers(), state.effectiveGroupMembers(),
      state.role(), status, state.termState(), newLogReport(), heartbeatTimestamps, quorumTimestamp,
      leaderHeartbeatTimestamp);
  }

  private RaftLogStatsImpl newLogReport() {
    LeaderState leaderState = state.leaderState();
    Map<RaftEndpoint, Long> followerMatchIndices;
    if (leaderState != null) {
      followerMatchIndices = leaderState.getFollowerStates().entrySet().stream()
        .collect(toMap(Entry::getKey, e -> e.getValue().matchIndex()));
    } else {
      followerMatchIndices = emptyMap();
    }
    return new RaftLogStatsImpl(state.commitIndex(), state.log().lastLogOrSnapshotEntry(),
      state.log().snapshotEntry(), takeSnapshotCount, installSnapshotCount, followerMatchIndices);
  }

  private <T> OrderedFuture<T> executeIfRunning(Runnable task, OrderedFuture<T> future) {
    if (!isTerminal(status)) {
      executor.execute(task);
    } else {
      future.fail(newNotRunningException());
    }
    return future;
  }

  public RaftException newNotLeaderException() {
    return new NotLeaderException(getLocalEndpoint(),
      isTerminal(status) ? null : getLeaderEndpoint());
  }

  private RuntimeException newNotRunningException() {
    return new IllegalStateException(localEndpointStr + " is not running!");
  }

  public RaftException newLaggingCommitIndexException(long minCommitIndex) {
    assert minCommitIndex > state.commitIndex()
      : "Cannot create LaggingCommitIndexException since min commit index: " + minCommitIndex
        + " is not greater than commit index: " + state.commitIndex();
    return new LaggingCommitIndexException(state.commitIndex(), minCommitIndex, state.leader());
  }

  /**
   * Returns the leader Raft endpoint currently known by the local Raft node. The returned leader
   * information might be stale.
   * @return the leader Raft endpoint currently known by the local Raft node
   */
  @Nullable
  public RaftEndpoint getLeaderEndpoint() {
    // volatile read
    return state.leader();
  }

  /**
   * Schedules a task to reset append entries request backoff periods, if not scheduled already.
   */
  public void scheduleLeaderRequestBackoffResetTask(LeaderState leaderState) {
    if (!leaderState.isRequestBackoffResetTaskScheduled()) {
      executor.schedule(leaderBackoffResetTask, LEADER_BACKOFF_RESET_TASK_PERIOD_MILLIS,
        MILLISECONDS);
      leaderState.requestBackoffResetTaskScheduled(true);
    }
  }

  public RaftNodeExecutor getExecutor() {
    return executor;
  }

  /**
   * Cached {@link Runnable} that submits {@link #demoteToFollowerIfLeaseExpired()} on this node's
   * executor. Held as a field so the bulk-heartbeat wheel does not allocate a capturing lambda per
   * dispatch.
   */
  @NonNull
  public Runnable demoteToFollowerIfLeaseExpiredTask() {
    return demoteToFollowerIfLeaseExpiredTask;
  }

  /**
   * Cached {@link Runnable} that submits {@link #sendCatchupAppendsIfNeeded()} on this node's
   * executor. Held as a field so the bulk-heartbeat wheel does not allocate a capturing
   * method-reference per dispatch.
   */
  @NonNull
  public Runnable sendCatchupAppendsIfNeededTask() {
    return sendCatchupAppendsIfNeededTask;
  }

  /**
   * Cached {@link Runnable} that submits {@code resetLeaderAndTryTriggerPreVote(true)} on this
   * node's executor. Held as a field so the bulk-heartbeat wheel's per-follower walk does not
   * allocate a capturing lambda per dispatch.
   */
  @NonNull
  public Runnable resetLeaderAndTryTriggerPreVoteTask() {
    return resetLeaderAndTryTriggerPreVoteTask;
  }

  /**
   * Applies the committed log entries between {@code lastApplied} and {@code commitIndex}, if
   * there's any available. If new entries are applied, {@link RaftState}'s {@code lastApplied}
   * field is also updated.
   * @see RaftState#lastApplied()
   * @see RaftState#commitIndex()
   */
  public void applyLogEntries() {
    assert state.commitIndex() >= state.lastApplied() : localEndpointStr + " commit index: "
      + state.commitIndex() + " cannot be smaller than last applied: " + state.lastApplied();
    assert state.role() == LEADER || state.role() == FOLLOWER || state.role() == LEARNER
      : localEndpointStr + " trying to apply log entries in role: " + state.role();
    // Apply all committed but not-yet-applied log entries
    RaftLog log = state.log();
    while (state.lastApplied() < state.commitIndex()) {
      for (long logIndex = state.lastApplied() + 1,
          nextSnapshotIndex = log.snapshotIndex()
            - (log.snapshotIndex() % commitCountToTakeSnapshot) + commitCountToTakeSnapshot,
          applyUntil = min(state.commitIndex(), nextSnapshotIndex); logIndex
              <= applyUntil; logIndex++) {
        LogEntry entry = log.getLogEntry(logIndex);
        if (entry == null) {
          String msg = localEndpointStr + " failed to get log entry at index: " + logIndex;
          LOG.error(msg);
          throw new AssertionError(msg);
        }
        applyLogEntry(entry);
      }
      stateMachine.onApplyBatchEnd();
      if (state.lastApplied() % commitCountToTakeSnapshot == 0 && !isTerminal(status)) {
        // If the status is terminal, then there will be no new append or commit.
        takeSnapshot(log, state.lastApplied());
      }
    }
    assert (status != TERMINATED || state.commitIndex() == log.lastLogOrSnapshotIndex())
      : localEndpointStr + " commit index: " + state.commitIndex() + " must be equal to "
        + log.lastLogOrSnapshotIndex() + " on termination.";
  }

  /**
   * Applies the log entry by executing its operation and sets execution result to the related
   * future if any available.
   */
  private void applyLogEntry(LogEntry entry) {
    LOG.trace("TRACE> {} applyLogEntry index={} term={} op={}", localEndpointStr, entry.getIndex(),
      entry.getTerm(), entry.getOperation());
    long logIndex = entry.getIndex();
    Object operation = entry.getOperation();
    // Return the global pending-bytes credit (if any) reserved for this log index at admission
    // time. Map presence is the evidence that this node actually held the credit.
    releaseLeaderPendingBytesAt(logIndex);
    Object response;
    if (operation instanceof RaftGroupOp) {
      if (operation instanceof UpdateRaftGroupMembersOp) {
        UpdateRaftGroupMembersOp groupOp = (UpdateRaftGroupMembersOp) operation;
        if (state.effectiveGroupMembers().getLogIndex() < logIndex) {
          setStatus(UPDATING_RAFT_GROUP_MEMBER_LIST);
          updateGroupMembers(logIndex, groupOp.getMembers(), groupOp.getVotingMembers());
        }
        assert status == UPDATING_RAFT_GROUP_MEMBER_LIST : localEndpointStr + " STATUS: " + status;
        assert state.effectiveGroupMembers().getLogIndex() == logIndex
          : localEndpointStr + " effective group members log index: "
            + state.effectiveGroupMembers().getLogIndex() + " applied log index: " + logIndex;
        state.commitGroupMembers();
        if (
          groupOp.getEndpoint().equals(getLocalEndpoint())
            && groupOp.getMode() == MembershipChangeMode.REMOVE_MEMBER
        ) {
          LOG.trace("TRACE> {} applyLogEntry self-REMOVE -> setStatus(TERMINATED) at index={}",
            localEndpointStr, logIndex);
          setStatus(TERMINATED);
        } else {
          setStatus(ACTIVE);
        }
        response = state.committedGroupMembers();
        if (stateMachine instanceof InternalCommitAware) {
          ((InternalCommitAware) stateMachine).onInternalCommit(logIndex);
        }
      } else {
        response = new IllegalArgumentException("Invalid Raft group operation: " + operation);
      }
    } else {
      try {
        response = stateMachine.runOperation(logIndex, operation);
      } catch (Throwable t) {
        LOG.error(localEndpointStr + " execution of " + operation + " at commit index: " + logIndex
          + " failed.", t);
        response = t;
      }
    }
    state.lastApplied(logIndex);
    state.completeFuture(logIndex, response);
  }

  /**
   * Updates the last leader heartbeat timestamp to now. Also defers the local election timer (a
   * real leader heartbeat must suppress the local pre-vote pass too).
   * <p>
   * Safe to call from the per-group executor or from the bulk inbound netty event-loop thread. Both
   * timestamp fields are advanced via monotonic-max accumulators so a stale concurrent write loses
   * to a fresher one and never travels backwards.
   */
  public void leaderHeartbeatReceived() {
    long now = clock.millis();
    LAST_HB.accumulateAndGet(this, now, Math::max);
    LAST_ER.accumulateAndGet(this, now, Math::max);
    currentElectionTimeoutMillis = sampleElectionTimeoutMillis();
  }

  /**
   * Defers the local election timer without claiming a fresh leader heartbeat. Called by
   * {@link org.apache.hadoop.hbase.consensus.raft.impl.handler.VoteRequestHandler} after granting a
   * vote so that the heartbeat scheduler does not immediately preempt the candidate we just voted
   * for. This does NOT refresh {@link #lastLeaderHeartbeatTimestamp}. A vote grant is not a leader
   * heartbeat, so sticky leader checks in (Pre)VoteRequestHandler still reflect whether a real
   * leader is alive.
   */
  public void electionTimerReset() {
    LAST_ER.accumulateAndGet(this, clock.millis(), Math::max);
    currentElectionTimeoutMillis = sampleElectionTimeoutMillis();
  }

  /**
   * Returns the internal Raft state
   * @return the internal Raft state
   */
  public RaftState state() {
    return state;
  }

  /**
   * Visible for testing. Read on the per-node executor thread (the only thread allowed to mutate
   * this field). See {@code TestRaftNodePauseRobustness}.
   */
  public long lastLeaderHeartbeatTimestampForTesting() {
    return lastLeaderHeartbeatTimestamp;
  }

  /**
   * Visible for testing. Read on the per-node executor thread (the only thread allowed to mutate
   * this field). See {@code TestRaftNodePauseRobustness}.
   */
  public long lastElectionTimerResetTimestampForTesting() {
    return lastElectionTimerResetTimestamp;
  }

  /** Volatile read of the most recent leader-heartbeat-observed timestamp. */
  public long lastLeaderHeartbeatTimestampVolatile() {
    return lastLeaderHeartbeatTimestamp;
  }

  /** Volatile read of the most recent election-timer-reset timestamp. */
  public long lastElectionTimerResetTimestampVolatile() {
    return lastElectionTimerResetTimestamp;
  }

  /** Volatile read of the per-follower randomized election timeout. */
  public long currentElectionTimeoutMillisVolatile() {
    return currentElectionTimeoutMillis;
  }

  /**
   * Recomputes the per-follower randomized election timeout and publishes it via the volatile
   * field.
   */
  public void publishFreshElectionTimeoutSample() {
    currentElectionTimeoutMillis = sampleElectionTimeoutMillis();
  }

  private void takeSnapshot(RaftLog log, long snapshotIndex) {
    if (snapshotIndex == log.snapshotIndex()) {
      LOG.warn(
        "{} is skipping to take snapshot at index: {} because it is the latest snapshot index.",
        localEndpointStr, snapshotIndex);
      return;
    }
    LOG.debug("{} is taking snapshot at index: {}", localEndpointStr, snapshotIndex);
    List<Object> chunkObjects = new ArrayList<>();
    try {
      stateMachine.takeSnapshot(snapshotIndex, chunkObjects::add);
    } catch (Throwable t) {
      throw new RaftException(
        localEndpointStr + " Could not take snapshot at applied index: " + snapshotIndex,
        state.leader(), t);
    }
    ++takeSnapshotCount;
    int snapshotTerm = log.getLogEntry(snapshotIndex).getTerm();
    RaftGroupMembersView groupMembersView =
      state.committedGroupMembers().populate(modelFactory.createRaftGroupMembersViewBuilder());
    List<SnapshotChunk> snapshotChunks = new ArrayList<>();
    for (int chunkIndex = 0, chunkCount = chunkObjects.size(); chunkIndex
        < chunkCount; chunkIndex++) {
      SnapshotChunk snapshotChunk =
        modelFactory.createSnapshotChunkBuilder().setTerm(snapshotTerm).setIndex(snapshotIndex)
          .setOperation(chunkObjects.get(chunkIndex)).setSnapshotChunkIndex(chunkIndex)
          .setSnapshotChunkCount(chunkCount).setGroupMembersView(groupMembersView).build();
      snapshotChunks.add(snapshotChunk);
      try {
        store.persistSnapshotChunk(snapshotChunk);
      } catch (IOException e) {
        throw new RaftException(
          "Persist failed at snapshot index: " + snapshotIndex + ", chunk index: " + chunkIndex,
          getLeaderEndpoint(), e);
      }
    }
    try {
      store.flush();
    } catch (IOException e) {
      throw new RaftException("Flush failed at snapshot index: " + snapshotIndex,
        getLeaderEndpoint(), e);
    }
    // we flushed the snapshot to the storage.
    // it is safe to modify the memory state now.
    SnapshotEntry snapshotEntry =
      modelFactory.createSnapshotEntryBuilder().setTerm(snapshotTerm).setIndex(snapshotIndex)
        .setSnapshotChunks(snapshotChunks).setGroupMembersView(groupMembersView).build();
    long highestLogIndexToTruncate = findHighestLogIndexToTruncateUntilSnapshotIndex(snapshotIndex);
    // the following call will also modify the persistent state
    // to truncate stale log entries. we will schedule an async flush
    // task below.
    int truncatedEntryCount = log.setSnapshot(snapshotEntry, highestLogIndexToTruncate);
    if (LOG.isDebugEnabled()) {
      LOG.debug(localEndpointStr + " " + snapshotEntry + " is taken. " + truncatedEntryCount
        + " entries are " + "truncated.");
    } else {
      LOG.info("{} took snapshot at term: {} and log index: {} and truncated {} entries.",
        localEndpointStr, snapshotEntry.getTerm(), snapshotEntry.getIndex(), truncatedEntryCount);
    }
    publishRaftNodeReport(RaftNodeReportReason.TAKE_SNAPSHOT);
    // this will flush the truncation of the stale log entries
    // asynchronously. if this node is the leader, it can append new log
    // entries in the meantime, this task will flush them to the storage.
    executor.submit(new FlushTask(this));
  }

  private long findHighestLogIndexToTruncateUntilSnapshotIndex(long snapshotIndex) {
    long limit =
      Math.max(FIRST_VALID_LOG_INDEX, snapshotIndex - maxLogEntryCountToKeepAfterSnapshot);
    long truncationIndex = limit;
    LeaderState leaderState = state.leaderState();
    if (leaderState != null) {
      long[] matchIndices = leaderState.matchIndices(state.remoteVotingMembers());
      // Last slot is reserved for the leader and always zero.
      // If there is at least one follower with unknown match index,
      // its log can be close to the leader's log so we are keeping the old log
      // entries.
      boolean allMatchIndicesKnown =
        Arrays.stream(matchIndices, 0, matchIndices.length - 1).noneMatch(i -> i == 0);
      if (allMatchIndicesKnown) {
        // Otherwise, we will keep the log entries until the minimum match index
        // that is bigger than (commitIndex - maxNumberOfLogsToKeepAfterSnapshot).
        // If there is no such follower (all of the minority followers are far behind),
        // then there is no need to keep the old log entries.
        truncationIndex = Arrays.stream(matchIndices)
          // No need to keep any log entry if all followers are up to date
          .filter(i -> i < snapshotIndex).filter(i -> i > limit)
          // We should not delete the smallest matchIndex
          .map(i -> i - 1).sorted().findFirst().orElse(snapshotIndex);
      }
    }
    return truncationIndex;
  }

  /**
   * Installs the snapshot sent by the leader if it's not already installed. This method assumes
   * that the given snapshot is already persisted and flushed to the storage. the snapshot entry
   * object to install to the local Raft node
   */
  @SuppressWarnings("unchecked")
  public void installSnapshot(SnapshotEntry snapshotEntry) {
    long commitIndex = state.commitIndex();
    if (commitIndex >= snapshotEntry.getIndex()) {
      throw new IllegalArgumentException("Cannot install snapshot at index: "
        + snapshotEntry.getIndex() + " because the current commit index is: " + commitIndex);
    }
    RaftLog log = state.log();
    int truncated = log.setSnapshot(snapshotEntry);
    // local state is updated here after log.setSnapshot() because
    // the storage might fail.
    state.commitIndex(snapshotEntry.getIndex());
    state.snapshotChunkCollector(null);
    if (truncated > 0) {
      LOG.info("{} {} entries are truncated to install snapshot at commit index: {}",
        localEndpointStr, truncated, snapshotEntry.getIndex());
    }
    List<Object> chunkOperations = ((List<SnapshotChunk>) snapshotEntry.getOperation()).stream()
      .map(SnapshotChunk::getOperation).collect(toList());
    stateMachine.installSnapshot(snapshotEntry.getIndex(), chunkOperations);
    ++installSnapshotCount;
    publishRaftNodeReport(RaftNodeReportReason.INSTALL_SNAPSHOT);
    // If I am installing a snapshot, it means I am still present
    // in the last member list, but it is possible that the last entry
    // I appended before the snapshot could be a membership change.
    // Because of this, I need to update my status. Nevertheless, I may
    // not be present in the restored member list, which is ok.
    setStatus(ACTIVE);
    if (state.installGroupMembers(snapshotEntry.getGroupMembersView())) {
      publishRaftNodeReport(RaftNodeReportReason.GROUP_MEMBERS_CHANGE);
    }
    state.lastApplied(snapshotEntry.getIndex());
    LOG.info("{} snapshot is installed at commit index: {}", localEndpointStr,
      snapshotEntry.getIndex());
    state.invalidateFuturesUntil(snapshotEntry.getIndex(),
      new IndeterminateStateException(state.leader()));
    tryRunScheduledQueries();
    // log.setSnapshot() truncates stale log entries from disk.
    // we are submitting an async flush task here to flush those
    // changes to the storage.
    executor.submit(new FlushTask(this));
  }

  /**
   * Updates Raft group members. the log index on which the given Raft group members are appended
   * the list of all Raft endpoints in the Raft group the list of voting Raft endpoints in the Raft
   * group (must be a subset of the "members" parameter)
   * @see RaftState#updateGroupMembers(long, Collection, Collection, long)
   */
  public void updateGroupMembers(long logIndex, Collection<RaftEndpoint> members,
    Collection<RaftEndpoint> votingMembers) {
    state.updateGroupMembers(logIndex, members, votingMembers, clock.millis());
    // If we are the leader and this membership change excludes us from the voting set
    // (typically a self-targeted REMOVE_MEMBER), our tenure as a quorum participant is over.
    // Any in-flight linearizable query that has been dispatched but not yet completed cannot be
    // safely satisfied by acks issued under the previous query sequence number: those acks
    // reflect a quorum computation that included this leader as an implicit ack, but the
    // effective voting set has just shrunk underneath us. To prevent a query from completing
    // successfully on a stale quorum, we (1) fail any pending queries immediately with
    // NotLeaderException, and (2) advance the query sequence number so that any subsequent acks
    // for the old sequence number are explicitly treated as stale by QueryState.tryAck.
    LeaderState leaderState = state.leaderState();
    if (leaderState != null && !votingMembers.contains(getLocalEndpoint())) {
      QueryState queryState = leaderState.queryState();
      int pending = queryState.queryCount();
      if (pending > 0) {
        LOG.trace(
          "TRACE> {} updateGroupMembers self removed from voting -> failing {} pending queries"
            + " logIndex={} newVoting={}",
          localEndpointStr, pending, logIndex, votingMembers);
        queryState.fail(newNotLeaderException());
      }
      queryState.incrementQuerySequenceNumber();
    }
    publishRaftNodeReport(RaftNodeReportReason.GROUP_MEMBERS_CHANGE);
  }

  /**
   * Reverts the Raft group members back to the committed Raft group members.
   * @see RaftState#revertGroupMembers()
   */
  public void revertGroupMembers() {
    state.revertGroupMembers();
    publishRaftNodeReport(RaftNodeReportReason.GROUP_MEMBERS_CHANGE);
  }

  /**
   * Publishes a new Raft node report for the given reason the underlying reason that triggers this
   * RaftNodeReport publish.
   * @see RaftNodeReport
   */
  public void publishRaftNodeReport(RaftNodeReportReason reason) {
    RaftNodeReportImpl report = newReport(reason);
    if (
      (reason == RaftNodeReportReason.STATUS_CHANGE || reason == RaftNodeReportReason.ROLE_CHANGE
        || reason == RaftNodeReportReason.GROUP_MEMBERS_CHANGE)
    ) {
      Object groupId = state.groupId();
      RaftGroupMembers members = report.getEffectiveMembers();
      StringBuilder sb = new StringBuilder(localEndpointStr).append(" lastLogIndex: ")
        .append(report.getLog().getLastLogOrSnapshotIndex()).append(", commitIndex: ")
        .append(report.getLog().getCommitIndex()).append(", snapshotIndex: ")
        .append(report.getLog().getLastSnapshotIndex()).append(", Raft Group Members {")
        .append("groupId: ").append(groupId).append(", size: ").append(members.getMembers().size())
        .append(", term: ").append(report.getTerm().getTerm()).append(", logIndex: ")
        .append(members.getLogIndex()).append("} [");
      members.getMembers().forEach(member -> {
        sb.append("\n\t").append(member.getId());
        if (getLocalEndpoint().equals(member)) {
          sb.append(" - ").append(state.role()).append(" this (").append(status).append(")");
        } else if (member.equals(state.leader())) {
          sb.append(" - ").append(LEADER);
        }
      });
      sb.append("\n] reason: ").append(reason).append("\n");
      LOG.info(sb.toString());
    }
    try {
      raftNodeReportListener.accept(report);
    } catch (Throwable t) {
      LOG.error(
        localEndpointStr + "'s listener: " + raftNodeReportListener + " failed for " + report, t);
    }
  }

  /** Updates the known leader endpoint. the discovered leader endpoint. */
  public void leader(RaftEndpoint member) {
    state.leader(member);
    publishRaftNodeReport(RaftNodeReportReason.ROLE_CHANGE);
  }

  /**
   * Switches this Raft node to the leader role by performing the following steps:
   * <ul>
   * <li>Setting the local endpoint as the current leader,</li>
   * <li>Clearing (pre)candidate states,</li>
   * <li>Initializing the leader state for the current members,</li>
   * <li>Appending an operation to the Raft log if enabled.</li>
   * </ul>
   */
  public void toLeader() {
    state.toLeader(clock.millis());
    LeaderState ls = state.leaderState();
    long now = clock.millis();
    long quorumTs = ls.quorumResponseTimestamp(state.logReplicationQuorumSize(), now);
    ls.leaseExpiryMillis(quorumTs + config.getLeaderLeaseDurationMillis());
    appendNewTermEntry();
    broadcastAppendEntriesRequest();
    publishRaftNodeReport(RaftNodeReportReason.ROLE_CHANGE);
  }

  /**
   * Broadcasts append entries requests to all group members according to their nextIndex
   * parameters.
   */
  public void broadcastAppendEntriesRequest() {
    LOG.trace("TRACE> {} broadcastAppendEntriesRequest remotes={}", localEndpointStr,
      state.remoteMembers());
    for (RaftEndpoint follower : state.remoteMembers()) {
      sendAppendEntriesRequest(follower);
    }
  }

  /**
   * Bumps {@link #lastLeaderHeartbeatTimestamp}, {@link #lastElectionTimerResetTimestamp}, and (if
   * leader) {@link LeaderState#leaseExpiryMillis(long)} forward by {@code pauseHintMillis} when the
   * sweeper-attested delta falls between {@link RaftConfig#getPauseDetectionThresholdMillis()} and
   * {@link RaftConfig#getPauseToleranceCapMillis()}. Called once per per-server timing-wheel tick
   * for every registered node so a JVM resuming from a stop-the-world pause does not see its lease
   * appear instantly expired.
   * @param pauseHintMillis externally-observed JVM-pause delta in milliseconds, or {@code 0} for a
   *                        no-op
   */
  public void absorbPauseIfDetected(long now, long pauseHintMillis) {
    if (pauseHintMillis <= 0L) {
      return;
    }
    long delta = pauseHintMillis;
    long threshold = config.getPauseDetectionThresholdMillis();
    long cap = config.getPauseToleranceCapMillis();
    if (delta < threshold || delta > cap) {
      return;
    }
    LOG.warn(
      "{} Detected JVM pause of {} ms; bumping heartbeat / election / lease timestamps "
        + "forward to absorb it (threshold={} ms, cap={} ms).",
      localEndpointStr, delta, threshold, cap);
    // Only advance fields that already hold a meaningful wall-clock reference. A field at 0L
    // means no prior event, no leader heartbeat seen yet, no election-timer reset yet.
    final long bump = delta;
    LAST_HB.accumulateAndGet(this, bump, (cur, d) -> cur > 0L ? cur + d : cur);
    LAST_ER.accumulateAndGet(this, bump, (cur, d) -> cur > 0L ? cur + d : cur);
    LeaderState ls = state.leaderState();
    if (ls != null) {
      ls.bumpLeaseExpiryMillis(bump);
    }
  }

  /**
   * Marks this group quiescent on the leader. The leader's bulk-heartbeat emission continues to
   * include this group on every tick with the per-group quiesce bit set in the bulk frame, so
   * followers see the transition without requiring a dedicated per-group fire-and-forget. Failure
   * detection thereafter runs on the per-server keepalive carried at envelope level on every bulk
   * frame and bulk acknowledgement.
   * <p>
   * Caller must have established that the eligibility checks pass: leader role, valid lease, grace
   * elapsed, no in-flight propose, no leadership transfer, all reachable voting followers caught
   * up. Followers the leader believes are not live may be passed in {@code lagging}; they are
   * recorded in {@link LeaderState#laggingOnQuiesce()} so the receiving follower can refuse to
   * quiesce if it believes itself live (see {@code LeaderHeartbeatHandler}).
   */
  public void quiesce(Collection<RaftEndpoint> lagging) {
    if (state.role() != LEADER || state.groupQuiescent()) {
      return;
    }
    LeaderState ls = state.leaderState();
    if (ls == null) {
      return;
    }
    ls.groupQuiescent(true);
    ls.laggingOnQuiesce(lagging == null ? Collections.emptyList() : lagging);
    state.groupQuiescent(true);
    LOG.debug("{} entering Quiescent (lagging={})", localEndpointStr, ls.laggingOnQuiesce());
  }

  /**
   * Wakes this group from {@code Quiescent} on the leader. Idempotent. Safe to call from any
   * propose path, membership change, leadership transfer, observed-higher-term, lease-expiry, or
   * heartbeat-tick observation. Clears {@link RaftState#groupQuiescent()},
   * {@link LeaderState#groupQuiescent()}, and {@link LeaderState#laggingOnQuiesce()}, and bumps the
   * leader's activity timestamp so the grace gate restarts.
   * <p>
   * Followers are not pinged here. The next normal heartbeat broadcast will reach them and the
   * receiving handler will call {@link RaftState#groupQuiescent(boolean)} {@code (false)} on any
   * non-quiesce signal.
   */
  public void wake() {
    if (state.role() != LEADER) {
      // Non-leader paths clear groupQuiescent directly via state.groupQuiescent(false).
      state.groupQuiescent(false);
      return;
    }
    LeaderState ls = state.leaderState();
    if (ls == null) {
      return;
    }
    ls.lastReplicateActivityMillis(clock.millis());
    if (!state.groupQuiescent()) {
      return;
    }
    LOG.debug("{} leaving Quiescent (wake)", localEndpointStr);
    ls.groupQuiescent(false);
    state.groupQuiescent(false);
  }

  /**
   * Fires {@link #sendAppendEntriesRequest(RaftEndpoint)} only for those followers whose state
   * indicates they need it. All other followers are kept in sync via the cheaper regular leader
   * heartbeats path. Followers with active request backoff are skipped and followed up by
   * {@code AppendEntriesSuccessResponseHandler.trySendAppendRequest} on response receipt or by the
   * backoff reset task on timeout.
   */
  public void sendCatchupAppendsIfNeeded() {
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      return;
    }
    RaftLog log = state.log();
    long lastLogOrSnapshotIndex = log.lastLogOrSnapshotIndex();
    long snapshotIndex = log.snapshotIndex();
    for (RaftEndpoint follower : state.remoteMembers()) {
      FollowerState fs = leaderState.getFollowerStateOrNull(follower);
      if (fs == null || fs.isRequestBackoffSet()) {
        continue;
      }
      boolean needsAppendCatchup = fs.matchIndex() < lastLogOrSnapshotIndex;
      boolean needsSnapshot = fs.nextIndex() <= snapshotIndex;
      if (needsAppendCatchup || needsSnapshot) {
        sendAppendEntriesRequest(follower);
      }
    }
  }

  /**
   * Resets this node's known leader (optionally) and runs a pre-vote round, or promotes itself to a
   * singleton leader if it is the only voting member left.
   */
  public void resetLeaderAndTryTriggerPreVote(boolean resetLeader) {
    if (resetLeader) {
      leader(null);
    }
    if (state.role() == LEARNER) {
      LOG.debug("{} is not starting pre-vote since it is {}", localEndpointStr, LEARNER);
      return;
    }
    if (state.leaderElectionQuorumSize() > 1) {
      runPreVote();
    } else if (state.effectiveGroupMembers().getVotingMembers().contains(getLocalEndpoint())) {
      // we can encounter this case if the leader crashes before it
      // commit the replicated membership change while it is leaving.
      LOG.info("{} is the single voting member left in the Raft group.", localEndpointStr);
      toSingletonLeader();
    }
  }

  /**
   * Sends an append entries request to the given follower.
   * <p>
   * Log entries between follower's known nextIndex and the latest appended entry index are sent as
   * a batch, whose size can be at most {@link RaftConfig#getAppendEntriesRequestBatchSize()}.
   * <p>
   * If the given follower's nextIndex is behind the latest snapshot index, then an
   * {@link InstallSnapshotRequest} is sent.
   * <p>
   * If the leader doesn't know the given follower's matchIndex then an empty append entries request
   * is sent to save bandwidth until the leader discovers the real matchIndex of the follower.
   */
  @SuppressWarnings({ "checkstyle:npathcomplexity", "checkstyle:cyclomaticcomplexity",
    "checkstyle:methodlength", })
  public void sendAppendEntriesRequest(RaftEndpoint target) {
    RaftLog log = state.log();
    LeaderState leaderState = state.leaderState();
    FollowerState followerState = leaderState.getFollowerStateOrNull(target);
    if (followerState == null) {
      LOG.warn("{} follower/learner: {} not found to send append entries request.",
        localEndpointStr, target.getId());
      return;
    } else if (followerState.isRequestBackoffSet()) {
      LOG.trace(
        "TRACE> {} sendAppendEntriesRequest SKIP backoff target={} matchIndex={} nextIndex={}",
        localEndpointStr, target.getId(), followerState.matchIndex(), followerState.nextIndex());
      // The target still has not sent a response for the last append request.
      // We will send a new append request either when the follower sends a response
      // or a back-off timeout occurs.
      return;
    }
    LOG.trace(
      "TRACE> {} sendAppendEntriesRequest target={} matchIndex={} nextIndex={} lastLogIndex={}"
        + " commitIndex={} status={} role={}",
      localEndpointStr, target.getId(), followerState.matchIndex(), followerState.nextIndex(),
      log.lastLogOrSnapshotIndex(), state.commitIndex(), status, state.role());
    long nextIndex = followerState.nextIndex();
    // we never send query sequencer number to learners
    // since they are excluded from the replication quorum.
    long querySequenceNumber = leaderState.querySequenceNumber(state.isVotingMember(target));
    // if the first log entry to be sent is put into the snapshot, check if we still
    // keep it in the log
    // if we still keep that log entry and its previous entry, we don't need to send
    // a snapshot
    if (
      nextIndex <= log.snapshotIndex() && (!log.containsLogEntry(nextIndex)
        || (nextIndex > 1 && !log.containsLogEntry(nextIndex - 1)))
    ) {
      // We send an empty request to notify the target so that it could
      // trigger the actual snapshot installation process...
      SnapshotEntry snapshotEntry = log.snapshotEntry();
      List<RaftEndpoint> snapshottedMembers = getSnapshottedMembers(leaderState, snapshotEntry);
      RaftMessage request =
        modelFactory.createInstallSnapshotRequestBuilder().setGroupId(getGroupId())
          .setSender(getLocalEndpoint()).setTerm(state.term()).setSenderLeader(true)
          .setSnapshotTerm(snapshotEntry.getTerm()).setSnapshotIndex(snapshotEntry.getIndex())
          .setTotalSnapshotChunkCount(snapshotEntry.getSnapshotChunkCount()).setSnapshotChunk(null)
          .setSnapshottedMembers(snapshottedMembers)
          .setGroupMembersView(snapshotEntry.getGroupMembersView())
          .setQuerySequenceNumber(querySequenceNumber)
          .setFlowControlSequenceNumber(enableBackoff(followerState)).build();
      if (LOG.isDebugEnabled()) {
        LOG.debug(localEndpointStr + " Sending " + request + " to " + target.getId()
          + " since next index: " + nextIndex + " <= snapshot index: " + log.snapshotIndex());
      }
      send(target, request);
      scheduleLeaderRequestBackoffResetTask(leaderState);
      return;
    }
    AppendEntriesRequestBuilder requestBuilder = modelFactory.createAppendEntriesRequestBuilder()
      .setGroupId(getGroupId()).setSender(getLocalEndpoint()).setTerm(state.term())
      .setCommitIndex(state.commitIndex()).setQuerySequenceNumber(querySequenceNumber);
    List<LogEntry> entries;
    boolean backoff = true;
    long lastLogIndex = log.lastLogOrSnapshotIndex();
    if (nextIndex > 1) {
      long prevEntryIndex = nextIndex - 1;
      requestBuilder.setPreviousLogIndex(prevEntryIndex);
      BaseLogEntry prevEntry = (log.snapshotIndex() == prevEntryIndex)
        ? log.snapshotEntry()
        : log.getLogEntry(prevEntryIndex);
      assert prevEntry != null : localEndpointStr + " prev entry index: " + prevEntryIndex
        + ", snapshot: " + log.snapshotIndex();
      requestBuilder.setPreviousLogTerm(prevEntry.getTerm());
      long matchIndex = followerState.matchIndex();
      if (matchIndex == 0) {
        // Until the leader has discovered where it and the target's logs match,
        // the leader can send AppendEntries with no entries (like heartbeats) to save
        // bandwidth.
        // We still need to enable append request backoff here because we do not want to
        // bombard the follower before we learn its match index.
        entries = emptyList();
      } else if (nextIndex <= lastLogIndex) {
        // Then, once the matchIndex immediately precedes the nextIndex,
        // the leader should begin to send the actual entries.
        entries = log.getLogEntriesBetween(nextIndex,
          min(nextIndex + appendEntriesRequestBatchSize, lastLogIndex));
      } else {
        // The target has caught up with the leader. Sending an empty append request as
        // a heartbeat...
        entries = emptyList();
        // amortize the cost of multiple queries.
        backoff = leaderState.queryState().queryCount() > 0;
      }
    } else if (nextIndex == 1 && lastLogIndex > 0) {
      // Entries will be sent to the target for the first time...
      entries = log.getLogEntriesBetween(nextIndex,
        min(nextIndex + appendEntriesRequestBatchSize, lastLogIndex));
    } else {
      // There is no entry in the Raft log. Sending an empty append request as a
      // heartbeat...
      entries = emptyList();
      // amortize cost of multiple queries.
      backoff = leaderState.queryState().queryCount() > 0;
    }
    if (backoff) {
      requestBuilder.setFlowControlSequenceNumber(enableBackoff(followerState));
    }
    RaftMessage request = requestBuilder.setLogEntries(entries).build();
    LOG.trace(
      "TRACE> {} sendAppendEntriesRequest SEND target={} nextIndex={} entries={} qsn={}"
        + " backoff={}",
      localEndpointStr, target.getId(), nextIndex, entries.size(), querySequenceNumber, backoff);
    send(target, request);
    if (backoff) {
      scheduleLeaderRequestBackoffResetTask(leaderState);
    }
    if (
      entries.size() > 0
        && entries.get(entries.size() - 1).getIndex() > leaderState.flushedLogIndex()
    ) {
      // If any non-flushed entry is being sent to the target, the local flush task is triggered
      // so that the leader and followers flush in parallel. The leader flush ideally completes
      // before the responses come back from a majority of the followers. This is a very important
      // optimization for end-to-end commit latency.
      submitLeaderFlushTask(leaderState);
    }
  }

  private List<RaftEndpoint> getSnapshottedMembers(LeaderState leaderState,
    SnapshotEntry snapshotEntry) {
    if (!config.isTransferSnapshotsFromFollowersEnabled()) {
      return Arrays.asList(state.localEndpoint());
    }
    long now = clock.millis();
    List<RaftEndpoint> snapshottedMembers = new ArrayList<>();
    snapshottedMembers.add(state.localEndpoint());
    for (Entry<RaftEndpoint, FollowerState> e : leaderState.getFollowerStates().entrySet()) {
      RaftEndpoint follower = e.getKey();
      FollowerState followerState = e.getValue();
      if (
        followerState.matchIndex() > snapshotEntry.getIndex() && transport.isReachable(follower)
          && !isLeaderHeartbeatTimeoutElapsed(followerState.responseTimestamp(), now)
      ) {
        snapshottedMembers.add(follower);
      }
    }
    return snapshottedMembers;
  }

  private long enableBackoff(FollowerState followerState) {
    return followerState.setRequestBackoff(MIN_BACKOFF_ROUNDS, maxBackoffRounds);
  }

  /**
   * Sends the given Raft message to the given Raft endpoint. the target Raft endpoint to send the
   * given Raft message the Raft message to send
   */
  public void send(RaftEndpoint target, RaftMessage message) {
    try {
      transport.send(target, message);
    } catch (Throwable t) {
      if (LOG.isDebugEnabled()) {
        LOG.error("Could not send " + message + " to " + target, t);
      } else {
        LOG.error("Could not send " + message.getClass().getSimpleName() + " to " + target, t);
      }
    }
  }

  /**
   * Returns true if the leader flush task is submitted either by the current call or a previous
   * call of this method. Returns false if the leader flush task is not submitted, because this Raft
   * node is created with {@link NopRaftStore}
   * @return true if the leader flush task is submitted either by the current call or a previous
   *         call of this method
   */
  public boolean submitLeaderFlushTask(LeaderState leaderState) {
    if (leaderFlushTask == null) {
      return false;
    }
    if (!leaderState.isFlushTaskSubmitted()) {
      executor.submit(leaderFlushTask);
      leaderState.flushTaskSubmitted(true);
    }
    return true;
  }

  private void appendNewTermEntry() {
    Object operation = stateMachine.getNewTermOperation();
    if (operation != null) {
      // this null check is on purpose. this operation can be null in tests.
      RaftLog log = state.log();
      LogEntry entry = modelFactory.createLogEntryBuilder().setTerm(state.term())
        .setIndex(log.lastLogOrSnapshotIndex() + 1).setOperation(operation).build();
      log.appendEntry(entry);
    }
  }

  /**
   * Switches this Raft node to the candidate role for the next term and starts a new leader
   * election round. Regular leader elections are sticky, meaning that leader stickiness will be
   * considered by other Raft nodes when they receive vote requests. A non-sticky leader election
   * occurs when the current Raft group leader tries to transfer leadership to another member.
   */
  public void toCandidate(boolean sticky) {
    state.toCandidate();
    BaseLogEntry lastLogEntry = state.log().lastLogOrSnapshotEntry();
    LOG.info("{} Leader election started for term: {}, last log index: {}, last log term: {}",
      localEndpointStr, state.term(), lastLogEntry.getIndex(), lastLogEntry.getTerm());
    publishRaftNodeReport(RaftNodeReportReason.ROLE_CHANGE);
    RaftMessage request = modelFactory.createVoteRequestBuilder().setGroupId(getGroupId())
      .setSender(getLocalEndpoint()).setTerm(state.term()).setLastLogTerm(lastLogEntry.getTerm())
      .setLastLogIndex(lastLogEntry.getIndex()).setSticky(sticky).build();
    for (RaftEndpoint member : state.remoteVotingMembers()) {
      send(member, request);
    }
    // Election-timeout firing must not be head-of-line blocked behind a bulk burst on the
    // per-group mailbox: a delayed timeout extends the no-leader window and risks an election
    // storm under load. Schedule onto the control lane.
    executor.scheduleControl(new LeaderElectionTimeoutTask(this), getLeaderElectionTimeoutMs(),
      MILLISECONDS);
  }

  /**
   * Returns the leader election timeout with a small and randomised extension.
   * @return the leader election timeout with a small and randomised extension.
   * @see RaftConfig#getLeaderElectionTimeoutMillis()
   */
  public long getLeaderElectionTimeoutMs() {
    return (((int) config.getLeaderElectionTimeoutMillis())
      + random.nextInt(LEADER_ELECTION_TIMEOUT_NOISE_MILLIS));
  }

  /**
   * Initiates the pre-voting step for the next term. The pre-voting step is executed to check if
   * other group members would vote for this Raft node if it would start a new leader election.
   */
  public void preCandidate() {
    state.initPreCandidateState();
    int nextTerm = state.term() + 1;
    BaseLogEntry entry = state.log().lastLogOrSnapshotEntry();
    RaftMessage request = modelFactory.createPreVoteRequestBuilder().setGroupId(getGroupId())
      .setSender(getLocalEndpoint()).setTerm(nextTerm).setLastLogTerm(entry.getTerm())
      .setLastLogIndex(entry.getIndex()).build();
    LOG.info("{} Pre-vote started for next term: {}, last log index: {}, last log term: {}",
      localEndpointStr, nextTerm, entry.getIndex(), entry.getTerm());
    for (RaftEndpoint member : state.remoteVotingMembers()) {
      send(member, request);
    }
    // Pre-vote timeout firing must not be head-of-line blocked behind a bulk burst on the
    // per-group mailbox; same reasoning as the candidate election timeout above. Schedule onto
    // the control lane.
    executor.scheduleControl(new PreVoteTimeoutTask(this, state.term()),
      getLeaderElectionTimeoutMs(), MILLISECONDS);
  }

  public RaftException newCannotReplicateException() {
    return new CannotReplicateException(isTerminal(status) ? null : getLeaderEndpoint());
  }

  private long findQuorumMatchIndex() {
    LeaderState leaderState = state.leaderState();
    long[] indices = leaderState.matchIndices(state.remoteVotingMembers());
    // if the leader is leaving, it should not count its vote for quorum...
    if (state.isKnownMember(getLocalEndpoint())) {
      // Raft dissertation Section 10.2.1:
      // The leader may even commit an entry before it has been written to its own
      // disk,
      // if a majority of followers have written it to their disks; this is still
      // safe.
      long leaderLogIndex = leaderFlushTask == null
        ? state.log().lastLogOrSnapshotIndex()
        : leaderState.flushedLogIndex();
      indices[indices.length - 1] = leaderLogIndex;
    } else {
      // Remove the last empty slot reserved for leader index
      indices = Arrays.copyOf(indices, indices.length - 1);
    }
    sort(indices);
    // 4 nodes: [0, 1, 2, 3] => Qlr = 2, quorum index = 2
    // 5 nodes: [0, 1, 2, 3, 4] => Qlr = 3, quorum index = 2
    long quorumMatchIndex = indices[state.votingMemberCount() - state.logReplicationQuorumSize()];
    if (LOG.isDebugEnabled()) {
      LOG.debug(localEndpointStr + " Quorum match index: " + quorumMatchIndex + ", indices: "
        + Arrays.toString(indices));
    }
    return quorumMatchIndex;
  }

  public boolean tryAdvanceCommitIndex() {
    // If there exists an N such that N > commitIndex, a majority of matchIndex[i] ≥
    // N, and log[N].term == currentTerm: set commitIndex = N (§5.3, §5.4)
    long quorumMatchIndex = findQuorumMatchIndex();
    long commitIndex = state.commitIndex();
    LOG.trace("TRACE> {} tryAdvanceCommitIndex enter quorumMatchIndex={} commitIndex={} term={}",
      localEndpointStr, quorumMatchIndex, commitIndex, state.term());
    RaftLog log = state.log();
    for (; quorumMatchIndex > commitIndex; quorumMatchIndex--) {
      // Only log entries from the leader’s current term are committed by counting
      // replicas; once an entry
      // from the current term has been committed in this way, then all prior entries
      // are committed indirectly
      // because of the Log Matching Property.
      LogEntry entry = log.getLogEntry(quorumMatchIndex);
      if (entry.getTerm() == state.term()) {
        LOG.trace("TRACE> {} tryAdvanceCommitIndex committing index={} entryTerm={}",
          localEndpointStr, quorumMatchIndex, entry.getTerm());
        commitEntries(quorumMatchIndex);
        return true;
      } else if (LOG.isDebugEnabled()) {
        LOG.debug(localEndpointStr + " cannot commit " + entry
          + " since an entry from the current term: " + state.term() + " is needed.");
      }
    }
    LOG.trace("TRACE> {} tryAdvanceCommitIndex no-advance commitIndex={} quorumMatchIndex={}",
      localEndpointStr, commitIndex, quorumMatchIndex);
    return false;
  }

  private void commitEntries(long commitIndex) {
    LOG.trace("TRACE> {} commitEntries enter commitIndex={} status={} role={}", localEndpointStr,
      commitIndex, status, state.role());
    state.commitIndex(commitIndex);
    applyLogEntries();
    // the leader might have left the Raft group, but still we can send
    // an append request at this point
    broadcastAppendEntriesRequest();
    LOG.trace("TRACE> {} commitEntries afterApply status={} role={} commitIndex={}",
      localEndpointStr, status, state.role(), state.commitIndex());
    if (status != TERMINATED) {
      // the leader is still part of the Raft group
      tryRunQueries();
      tryRunScheduledQueries();
    } else {
      // the leader has left the Raft group
      LOG.trace("TRACE> {} commitEntries leader removed -> invalidate+toFollower+terminate",
        localEndpointStr);
      state.invalidateScheduledQueries();
      toFollower(state.term());
      terminateComponents();
    }
  }

  public void tryAckQuery(long querySequenceNumber, RaftEndpoint sender) {
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      LOG.trace("TRACE> {} tryAckQuery leaderState=null sender={} qsn={}", localEndpointStr,
        sender.getId(), querySequenceNumber);
      return;
    } else if (!state.isVotingMember(sender)) {
      LOG.trace("TRACE> {} tryAckQuery sender NOT voting sender={} qsn={}", localEndpointStr,
        sender.getId(), querySequenceNumber);
      return;
    }
    QueryState queryState = leaderState.queryState();
    if (queryState.tryAck(querySequenceNumber, sender)) {
      LOG.trace("TRACE> {} tryAckQuery acked sender={} qsn={} -> tryRunQueries", localEndpointStr,
        sender.getId(), querySequenceNumber);
      tryRunQueries();
    }
  }

  /** Returns a short string that represents identity of the local Raft endpoint. */
  public String localEndpointStr() {
    return localEndpointStr;
  }

  public void tryRunQueries() {
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      LOG.trace("TRACE> {} tryRunQueries leaderState=null status={} role={}", localEndpointStr,
        status, state.role());
      return;
    }
    QueryState queryState = leaderState.queryState();
    long commitIndex = state.commitIndex();
    int quorumSize = state.logReplicationQuorumSize();
    boolean localIsVoting = state.isVotingMember(getLocalEndpoint());
    LOG.trace(
      "TRACE> {} tryRunQueries enter status={} role={} commitIndex={} quorumSize={}"
        + " localIsVoting={} effectiveVoting={}",
      localEndpointStr, status, state.role(), commitIndex, quorumSize, localIsVoting,
      state.effectiveGroupMembers().getVotingMembers());
    if (!queryState.isQuorumAckReceived(commitIndex, quorumSize)) {
      return;
    }
    Collection<QueryContainer> operations = queryState.queries();
    LOG.trace("TRACE> {} tryRunQueries RUNNING count={} commitIndex={} qsn={}", localEndpointStr,
      operations.size(), commitIndex, queryState.querySequenceNumber());
    for (QueryContainer query : operations) {
      query.run(commitIndex, stateMachine);
    }
    queryState.reset();
  }

  public void tryRunScheduledQueries() {
    long lastApplied = state.lastApplied();
    Collection<QueryContainer> queries = state.collectScheduledQueriesToExecute();
    for (QueryContainer query : queries) {
      query.run(lastApplied, stateMachine);
    }
    if (queries.size() > 0 && LOG.isDebugEnabled()) {
      LOG.debug("{} executed {} waiting queries at log index: {}.", localEndpointStr,
        queries.size(), lastApplied);
    }
  }

  /**
   * Executes the given query operation and sets execution result to the future if the current
   * commit index is greater than or equal to the given commit index.
   * <p>
   * Please note that the given operation must not make any mutation on the state machine.
   * @param timeoutMillis duration in milliseconds to wait before timing out the query when the
   *                      local commit index is below {@code minCommitIndex}; {@code 0L} fails the
   *                      query immediately if the commit index has not advanced.
   */
  public void runOrScheduleQuery(QueryContainer query, long minCommitIndex, long timeoutMillis) {
    try {
      long lastApplied = state.lastApplied();
      if (lastApplied >= minCommitIndex) {
        query.run(lastApplied, stateMachine);
      } else if (timeoutMillis > 0L) {
        state.addScheduledQuery(minCommitIndex, query);
        executor.schedule(() -> {
          try {
            if (state.removeScheduledQuery(minCommitIndex, query)) {
              if (LOG.isDebugEnabled()) {
                LOG.debug(
                  "{} query waiting to be executed at commit index: {} timed out!"
                    + " Current commit index: {}",
                  localEndpointStr, minCommitIndex, state.commitIndex());
              }
              query.fail(newLaggingCommitIndexException(minCommitIndex));
            }
          } catch (Throwable t) {
            LOG.error(localEndpointStr + " timing out of query for expected commit index: "
              + minCommitIndex + " failed.", t);
            query.fail(t);
          }
        }, timeoutMillis, MILLISECONDS);
      } else {
        query.fail(newLaggingCommitIndexException(minCommitIndex));
      }
    } catch (Throwable t) {
      LOG.error(localEndpointStr + " query scheduling failed with {}", t);
      query.fail(t);
    }
  }

  @Override
  public boolean isLeaderWithValidLease(long nowMillis) {
    return state.role() == LEADER && state.leaderState() != null
      && state.leaderState().leaseExpiryMillis() > nowMillis;
  }

  /**
   * If this node is leader and its leader lease has expired, steps down to follower (same term).
   * Before checking expiry, recomputes the lease from the current quorum response timestamps (this
   * is what allows singleton leaders to keep their lease and a multi-member leader to pick up
   * follower acks observed since the last AppendEntriesSuccessResponseHandler invocation).
   * @return true if the node stepped down due to lease expiry
   */
  @Override
  public boolean demoteToFollowerIfLeaseExpired() {
    if (state.role() != LEADER) {
      return false;
    }
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      return false;
    }
    long now = clock.millis();
    long quorumTs = leaderState.quorumResponseTimestamp(state.logReplicationQuorumSize(), now);
    long candidateExpiry = quorumTs + config.getLeaderLeaseDurationMillis();
    if (candidateExpiry > leaderState.leaseExpiryMillis()) {
      leaderState.leaseExpiryMillis(candidateExpiry);
    }
    if (leaderState.leaseExpiryMillis() <= now) {
      LOG.warn("{} Demoting to {} since leader lease expired (leaseExpiryMillis={} now={}).",
        localEndpointStr, FOLLOWER, leaderState.leaseExpiryMillis(), now);
      toFollower(state.term());
      return true;
    }
    return false;
  }

  @Override
  public boolean isLeaderHeartbeatTimeoutElapsed() {
    return isLeaderHeartbeatTimeoutElapsed(lastLeaderHeartbeatTimestamp, clock.millis());
  }

  /**
   * @return whether the local election timer has elapsed. The election timer is deferred by either
   *         a real leader heartbeat or by granting a vote, so this returning {@code false} means
   *         the per-server bulk-heartbeat wheel must NOT schedule a new pre-vote pass yet.
   */
  public boolean isElectionTimerElapsed() {
    return clock.millis() - lastElectionTimerResetTimestamp >= currentElectionTimeoutMillis;
  }

  private boolean isLeaderHeartbeatTimeoutElapsed(long timestamp, long now) {
    return now - timestamp >= leaderHeartbeatTimeoutMillis;
  }

  /** Visible for tests. */
  public long getCurrentElectionTimeoutMillis() {
    return currentElectionTimeoutMillis;
  }

  @SuppressWarnings("unchecked")
  private void initRestoredState() {
    SnapshotEntry snapshotEntry = state.log().snapshotEntry();
    if (isNonInitial(snapshotEntry)) {
      List<Object> chunkOperations = ((List<SnapshotChunk>) snapshotEntry.getOperation()).stream()
        .map(SnapshotChunk::getOperation).collect(toList());
      stateMachine.installSnapshot(snapshotEntry.getIndex(), chunkOperations);
      publishRaftNodeReport(RaftNodeReportReason.INSTALL_SNAPSHOT);
      if (LOG.isDebugEnabled()) {
        LOG.info(localEndpointStr + " restored " + snapshotEntry);
      } else {
        LOG.info(localEndpointStr + " restored snapshot commitIndex=" + snapshotEntry.getIndex());
      }
    }
    applyRestoredRaftGroupOps(snapshotEntry);
  }

  private void applyRestoredRaftGroupOps(SnapshotEntry snapshot) {
    // If there is a single Raft group operation after the last snapshot,
    // here we cannot know if the that operation is committed or not so we
    // just "prepare" the operation without committing it.
    // If there are multiple Raft group operations, it is definitely known
    // that all the operations up to the last Raft group operation are
    // committed, but the last Raft group operation may not be committed.
    // This conclusion boils down to the fact that once you append a Raft
    // group operation, you cannot append a new one before committing
    // the former.
    RaftLog log = state.log();
    LogEntry committedEntry = null;
    LogEntry lastAppliedEntry = null;
    for (long i = snapshot != null ? snapshot.getIndex() + 1 : 1; i
        <= log.lastLogOrSnapshotIndex(); i++) {
      LogEntry entry = log.getLogEntry(i);
      assert entry != null : localEndpointStr + " missing log entry at index: " + i;
      if (entry.getOperation() instanceof RaftGroupOp) {
        committedEntry = lastAppliedEntry;
        lastAppliedEntry = entry;
      }
    }
    if (committedEntry != null) {
      state.commitIndex(committedEntry.getIndex());
      applyLogEntries();
    }
    if (lastAppliedEntry != null) {
      if (lastAppliedEntry.getOperation() instanceof UpdateRaftGroupMembersOp) {
        setStatus(UPDATING_RAFT_GROUP_MEMBER_LIST);
        UpdateRaftGroupMembersOp groupOp =
          (UpdateRaftGroupMembersOp) lastAppliedEntry.getOperation();
        updateGroupMembers(lastAppliedEntry.getIndex(), groupOp.getMembers(),
          groupOp.getVotingMembers());
      } else {
        throw new IllegalStateException("Invalid Raft group op restored: " + lastAppliedEntry);
      }
    }
  }

  public RaftModelFactory getModelFactory() {
    return modelFactory;
  }

  public Transport getTransport() {
    return transport;
  }

  /**
   * Switches this node to the follower role by clearing the known leader endpoint and (pre)
   * candidate states, and updating the term. If this Raft node was leader before switching to the
   * follower state, it may have some queries waiting to be executed. Those queries are also failed
   * with {@link NotLeaderException}. the new term to switch
   */
  public void toFollower(int term) {
    if (LOG.isTraceEnabled()) {
      LOG.trace("TRACE> {} toFollower enter newTerm={} prevTerm={} prevRole={} status={}",
        localEndpointStr, term, state.term(), state.role(), status,
        new Throwable("toFollower call site"));
    }
    // Surrender any outstanding leader-side pending-bytes credit.
    releaseAllLeaderPendingBytes();
    state.toFollower(term);
    publishRaftNodeReport(RaftNodeReportReason.ROLE_CHANGE);
  }

  public void runPreVote() {
    new PreVoteTask(this, state.term()).run();
  }

  /**
   * Switches this Raft node directly to the leader role for the next term.
   * @see #toLeader()
   */
  public void toSingletonLeader() {
    state.toCandidate();
    BaseLogEntry lastLogEntry = state.log().lastLogOrSnapshotEntry();
    LOG.info("{} Leader election started for term: {}, last log index: {}, last log term: {}",
      localEndpointStr, state.term(), lastLogEntry.getIndex(), lastLogEntry.getTerm());
    publishRaftNodeReport(RaftNodeReportReason.ROLE_CHANGE);
    toLeader();
    LOG.info("{} We are the LEADER!", localEndpointStr());
    if (leaderFlushTask != null) {
      leaderFlushTask.run();
    } else {
      tryAdvanceCommitIndex();
    }
  }

  public Clock getClock() {
    return clock;
  }
}
