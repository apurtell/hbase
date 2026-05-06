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
package org.apache.hadoop.hbase.consensus.raft.impl.state;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.stream.Collectors;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * State maintained by the Raft group leader.
 * @see FollowerState
 */
@InterfaceAudience.Private
public final class LeaderState {
  /** A {@link FollowerState} object will be maintained for each follower. */
  private final Map<RaftEndpoint, FollowerState> followerStates = new HashMap<>();
  /**
   * Contains inflight queries that are waiting to be executed without appending entries to the Raft
   * log.
   */
  private final QueryState queryState = new QueryState();
  private boolean requestBackoffResetTaskScheduled;
  private boolean flushTaskSubmitted;
  private long flushedLogIndex;
  /**
   * Wall-clock time (ms) until which this leader considers its lease valid for leader stickiness /
   * step-down. Refreshed from replication quorum ack timestamps.
   * <p>
   * Volatile + monotonic-max accumulator so the bulk inbound handler can advance it from the netty
   * event-loop thread without entering the per-group executor.
   */
  private volatile long leaseExpiryMillis;
  private static final AtomicLongFieldUpdater<LeaderState> LEASE_EXPIRY =
    AtomicLongFieldUpdater.newUpdater(LeaderState.class, "leaseExpiryMillis");
  /**
   * Wall-clock time (ms) of the last leader-side activity that should reset the idle-group
   * quiescence grace timer. Updated whenever {@code ReplicateTask} runs, a membership change is
   * appended, or a leadership transfer is initiated. Compared against
   * {@code now() - quiescenceGraceMs} by the quiescence sweep to decide whether the group has been
   * idle long enough to enter the {@code Quiescent} state. Initialized to construction time so a
   * freshly elected leader does not immediately quiesce.
   * <p>
   * Maps to the spec's grace gate on {@code Quiesce(leader)}.
   */
  private long lastReplicateActivityMillis;
  /**
   * Set on the active-to-quiescent transition by {@code RaftNodeImpl.quiesce}. While true, the
   * per-group sweep emits zero {@code GroupHeartbeatPB} bytes for this group; failure detection
   * runs on the per-RS keepalive on every {@code HeartbeatBatchPB} envelope. Cleared by
   * {@code RaftNodeImpl.wake} or by {@code toFollower} via {@code RaftState.toFollower}.
   * <p>
   * Maps to the spec's {@code groupQuiescent[leader]}.
   */
  private boolean groupQuiescent;
  /**
   * Wall-clock time (ms) at which the bulk-heartbeat wheel last submitted a synthetic idle-flush
   * proposal for this leader's group. Used by the wheel as a per-group rate-limit so that a single
   * idle window produces at most one synthetic flush; the gate trips again only after
   * {@code RaftConfig.getIdleFlushIntervalMillis()} have elapsed since this stamp. Initialised to
   * construction time so a freshly elected leader does not synthesise a flush immediately.
   * <p>
   * Updated only by the per-group executor thread that owns this {@code LeaderState}, so accessors
   * do not need to be volatile or synchronized.
   */
  private long lastIdleFlushTriggerMillis;
  /**
   * Followers the leader believes are not live at the moment of {@code Quiesce} (master's most
   * recent live-server view did not include them, or {@code matchIndex < lastLogOrSnapshotIndex}
   * after recent replication). Carried into the quiesce notice so receiving followers can refuse to
   * quiesce if they believe themselves live.
   * <p>
   * Maps to the spec's {@code laggingOnQuiesce[leader]}.
   */
  private Set<RaftEndpoint> laggingOnQuiesce = Collections.emptySet();

  LeaderState(Collection<RaftEndpoint> remoteMembers, long lastLogIndex, long currentTimeMillis) {
    remoteMembers.forEach(follower -> followerStates.put(follower,
      new FollowerState(0L, lastLogIndex + 1, currentTimeMillis)));
    flushedLogIndex = lastLogIndex;
    leaseExpiryMillis = 0L;
    lastReplicateActivityMillis = currentTimeMillis;
    lastIdleFlushTriggerMillis = currentTimeMillis;
    groupQuiescent = false;
  }

  /**
   * Add a new follower with the leader's {@code lastLogIndex}. Follower's {@code nextIndex} will be
   * set to {@code lastLogIndex + 1} and {@code matchIndex} to 0.
   */
  public void add(RaftEndpoint follower, long lastLogIndex, long currentTimeMillis) {
    assert !followerStates.containsKey(follower) : "Already known follower " + follower;
    followerStates.put(follower, new FollowerState(0L, lastLogIndex + 1, currentTimeMillis));
  }

  /** Removes a follower from leader maintained state. */
  public void remove(RaftEndpoint follower) {
    FollowerState removed = followerStates.remove(follower);
    queryState.removeAck(follower);
    assert removed != null : "Unknown follower " + follower;
  }

  /**
   * Returns an array of match indices for all followers. Additionally an empty slot is added at the
   * last index of the array for leader itself.
   */
  public long[] matchIndices(Collection<RaftEndpoint> remoteVotingMembers) {
    // Leader index is put to the last index of the array while calculating
    // quorum match index. That's why we add an extra empty index here.
    long[] indices = new long[remoteVotingMembers.size() + 1];
    int i = 0;
    for (RaftEndpoint member : remoteVotingMembers) {
      indices[i++] = followerStates.get(member).matchIndex();
    }
    return indices;
  }

  /** Returns a non-null follower state object for the given follower. */
  public FollowerState getFollowerState(RaftEndpoint follower) {
    FollowerState followerState = followerStates.get(follower);
    assert followerState != null : "Unknown follower " + follower;
    return followerState;
  }

  public FollowerState getFollowerStateOrNull(RaftEndpoint follower) {
    return followerStates.get(follower);
  }

  /** Returns all follower state objects. */
  public Map<RaftEndpoint, FollowerState> getFollowerStates() {
    return followerStates;
  }

  /** Returns the state object that contains inflight queries. */
  public QueryState queryState() {
    return queryState;
  }

  /**
   * Returns the query sequence number to be acked by the log replication quorum to execute the
   * currently waiting queries. Query sequencer numbers are not sent to learners since they are
   * excluded from the replication quorum.
   */
  public long querySequenceNumber(boolean forVotingMember) {
    return forVotingMember ? queryState.querySequenceNumber() : 0;
  }

  public boolean isRequestBackoffResetTaskScheduled() {
    return requestBackoffResetTaskScheduled;
  }

  public void requestBackoffResetTaskScheduled(boolean backoffResetTaskScheduled) {
    this.requestBackoffResetTaskScheduled = backoffResetTaskScheduled;
  }

  public boolean isFlushTaskSubmitted() {
    return flushTaskSubmitted;
  }

  public void flushTaskSubmitted(boolean flushTaskSubmitted) {
    this.flushTaskSubmitted = flushTaskSubmitted;
  }

  public void flushedLogIndex(long flushedLogIndex) {
    assert flushedLogIndex >= this.flushedLogIndex : "new flushed log index: " + flushedLogIndex
      + " existing flushed log index: " + this.flushedLogIndex;
    this.flushedLogIndex = flushedLogIndex;
  }

  public long flushedLogIndex() {
    return flushedLogIndex;
  }

  public long leaseExpiryMillis() {
    return leaseExpiryMillis;
  }

  /**
   * Sets the lease expiry to {@code expiry} if it is greater than the current value. Otherwise
   * leaves the lease unchanged. The lease is strictly monotonic by construction, but callers do not
   * always know whether their newly-computed value is ahead of or behind the current lease.
   */
  public void leaseExpiryMillis(long expiry) {
    LEASE_EXPIRY.accumulateAndGet(this, expiry, Math::max);
  }

  /**
   * Atomically advances the lease expiry by {@code delta} milliseconds, but only when a real lease
   * has already been established (current value &gt; 0). Used by the JVM-pause absorber which the
   * per-server timing wheel runs from its own thread.
   */
  public void bumpLeaseExpiryMillis(long delta) {
    if (delta <= 0L) {
      return;
    }
    LEASE_EXPIRY.accumulateAndGet(this, delta, (cur, d) -> cur > 0L ? cur + d : cur);
  }

  /** Returns the earliest append entries response timestamp of the log replication quorum nodes. */
  public long quorumResponseTimestamp(int quorumSize, long localNodeTimestamp) {
    long[] timestamps = new long[followerStates.size() + 1];
    int i = 0;
    long maxFollowerTimestamp = 0;
    for (FollowerState followerState : followerStates.values()) {
      long followerTimestamp = followerState.responseTimestamp();
      maxFollowerTimestamp = Math.max(maxFollowerTimestamp, followerTimestamp);
      timestamps[i++] = followerTimestamp;
    }
    // All timestamps are local, hence local RaftNode's timestamp cannot be smaller than
    // any of the follower timestamps.
    timestamps[i] = Math.max(maxFollowerTimestamp, localNodeTimestamp);
    Arrays.sort(timestamps);
    return timestamps[timestamps.length - quorumSize];
  }

  /** Returns response timestamps of all followers. */
  public Map<RaftEndpoint, Long> responseTimestamps() {
    return followerStates.entrySet().stream()
      .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().responseTimestamp()));
  }

  /**
   * Returns the wall-clock time (ms) of the last leader-side activity that should reset the
   * idle-group quiescence grace timer.
   */
  public long lastReplicateActivityMillis() {
    return lastReplicateActivityMillis;
  }

  /**
   * Updates the last replicate-activity timestamp. The quiescence sweep gates the
   * active-to-quiescent transition on
   * {@code currentTimeMillis - lastReplicateActivityMillis >= quiescenceGraceMs}. Bumping this
   * timestamp on every {@code ReplicateTask} or membership change run is what makes the gate track
   * real activity rather than the wall clock.
   */
  public void lastReplicateActivityMillis(long currentTimeMillis) {
    if (currentTimeMillis > this.lastReplicateActivityMillis) {
      this.lastReplicateActivityMillis = currentTimeMillis;
    }
  }

  /** Returns whether the leader-side quiescent flag is set for this group. */
  public boolean groupQuiescent() {
    return groupQuiescent;
  }

  /**
   * Sets the leader-side quiescent flag for this group. {@code RaftNodeImpl.quiesce} sets it to
   * {@code true} on the active-to-quiescent transition; {@code RaftNodeImpl.wake} sets it back to
   * {@code false} and clears {@link #laggingOnQuiesce()}.
   */
  public void groupQuiescent(boolean quiescent) {
    this.groupQuiescent = quiescent;
    if (!quiescent) {
      this.laggingOnQuiesce = Collections.emptySet();
    }
  }

  /**
   * Returns the followers the leader believed were lagging at the moment it entered
   * {@code Quiescent}. Empty unless {@link #groupQuiescent()} is true.
   */
  public Set<RaftEndpoint> laggingOnQuiesce() {
    return laggingOnQuiesce;
  }

  /** Records the lagging-on-quiesce set captured at the {@code Quiesce} transition. */
  public void laggingOnQuiesce(Collection<RaftEndpoint> lagging) {
    this.laggingOnQuiesce = lagging.isEmpty() ? Collections.emptySet() : new HashSet<>(lagging);
  }

  /**
   * Returns the wall-clock time (ms) at which the bulk-heartbeat wheel last submitted a synthetic
   * idle-flush proposal for this group. The wheel uses the gap between this stamp and {@code now}
   * as a per-group rate-limit on the synthetic flush path.
   */
  public long lastIdleFlushTriggerMillis() {
    return lastIdleFlushTriggerMillis;
  }

  /**
   * Updates the last idle-flush-trigger timestamp via a monotonic-max accumulator. The wheel stamps
   * this whenever it dispatches a synthetic flush so a follow-up tick within the same idle interval
   * skips its check rather than dogpiling another flush onto an already-pending one.
   */
  public void lastIdleFlushTriggerMillis(long currentTimeMillis) {
    if (currentTimeMillis > this.lastIdleFlushTriggerMillis) {
      this.lastIdleFlushTriggerMillis = currentTimeMillis;
    }
  }
}
