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
package org.apache.hadoop.hbase.consensus.raft.heartbeat.impl;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.conf.ConfigKey;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.state.FollowerState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultLeaderHeartbeatOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Per-server timing-wheel {@link HeartbeatScheduler} that emits one {@link BulkHeartbeatFrame} per
 * (this-server, peer) per tick, aggregating one entry per local leader group whose committed
 * membership includes that peer.
 * <p>
 * On each tick the wheel thread:
 * <ol>
 * <li>computes the wall-clock JVM-pause delta against the configured detection band and applies it
 * lock-free to every registered node's heartbeat / election / lease accumulators (no per-group
 * executor task);</li>
 * <li>walks every registered {@link RaftNodeImpl} reading volatile / atomic snapshot fields only
 * (no per-group executor task);</li>
 * <li>for each leader group's committed remote member, appends a per-group entry into that peer's
 * outbound bulk-frame accumulator;</li>
 * <li>schedules a small number of slow-path control tasks for the rare transitions that the
 * volatile snapshot reveals: a leader whose lease has expired, a leader whose any-follower
 * verified-log-index lags the leader's log, and a follower whose election timer has elapsed without
 * a fresh leader heartbeat;</li>
 * <li>builds one {@code BulkHeartbeatPB} envelope per peer with the wheel's own
 * {@code (sender, epoch, tick)} keepalive header and hands it to
 * {@link Transport#sendBulkHeartbeat(RaftEndpoint, BulkHeartbeatFrame)}.</li>
 * </ol>
 * <p>
 * Steady-state cost per tick is one volatile-read-only walk of registered nodes plus one outbound
 * bulk frame per peer, with zero per-group executor tasks.
 */
@InterfaceAudience.Private
public final class BulkHeartbeatScheduler
  implements HeartbeatScheduler, RaftNodeLifecycleAware, AutoCloseable {

  /** Tick cadence in milliseconds. */
  public static final String INTERVAL_MS_KEY =
    ConfigKey.INT("hbase.consensus.heartbeat.interval.ms", v -> v >= 1);
  public static final int INTERVAL_MS_DEFAULT = 250;

  /** Number of timer threads in the shared wheel {@link ScheduledThreadPoolExecutor}. */
  public static final String TIMER_THREADS_KEY =
    ConfigKey.INT("hbase.consensus.heartbeat.wheel.threads", v -> v >= 1);
  public static final int TIMER_THREADS_DEFAULT = 1;

  /**
   * Floor (ms) on observed inter-tick wall-clock delta above the expected interval at which the
   * wheel attests a JVM pause to its registered nodes. Mirrors
   * {@link RaftConfig#getPauseDetectionThresholdMillis()}.
   */
  public static final String PAUSE_DETECTION_THRESHOLD_MS_KEY =
    ConfigKey.LONG("hbase.consensus.pause.detection.threshold.millis", v -> v >= 0);
  public static final long PAUSE_DETECTION_THRESHOLD_MS_DEFAULT =
    RaftConfig.DEFAULT_PAUSE_DETECTION_THRESHOLD_MILLIS;

  /**
   * Cap (ms) on the pause delta the wheel will absorb. Above this cap the wheel falls through (no
   * hint emitted); the per-node lease / election-timer logic then demotes on real failure. Mirrors
   * {@link RaftConfig#getPauseToleranceCapMillis()}.
   */
  public static final String PAUSE_TOLERANCE_CAP_MS_KEY =
    ConfigKey.LONG("hbase.consensus.pause.detection.cap.millis", v -> v >= 0);
  public static final long PAUSE_TOLERANCE_CAP_MS_DEFAULT =
    RaftConfig.DEFAULT_PAUSE_TOLERANCE_CAP_MILLIS;

  private static final Logger LOG = LoggerFactory.getLogger(BulkHeartbeatScheduler.class);
  private static final AtomicInteger POOL_ID = new AtomicInteger();

  private final ScheduledThreadPoolExecutor timer;
  private final ConcurrentMap<Object, RaftNodeImpl> registry = new ConcurrentHashMap<>();
  private final int intervalMs;
  private final long pauseDetectionThresholdMs;
  private final long pauseToleranceCapMs;
  private final Transport transport;
  /**
   * Boot-epoch wall-clock millis stamped into every emitted bulk frame's keepalive header so
   * receivers can detect server restarts without per-group state. Owned by the enclosing
   * {@code ConsensusServer} (or supplied by tests) so the transport, the wheel, and any other
   * component that stamps an envelope-level keepalive header all agree on a single value.
   */
  private final long bootEpochMillis;
  private final AtomicLong tickCounter = new AtomicLong();
  private final Object lifecycleLock = new Object();
  /**
   * Wall-clock millis at which the most recent {@link #tick()} began, or {@code 0} until the second
   * tick fires. Read and written exclusively from the wheel thread.
   */
  private long lastTickStartedAtMillis;
  /**
   * Per-tick scratch accumulator from peer to the per-group heartbeat list aimed at that peer.
   * Reused across {@link #tick()} invocations.
   */
  private final Map<RaftEndpoint, List<LeaderHeartbeat>> perPeer = new HashMap<>(8);

  private volatile ScheduledFuture<?> tickFuture;
  private volatile boolean started;
  private volatile boolean closed;

  public BulkHeartbeatScheduler(@NonNull Configuration conf, @NonNull Transport transport) {
    this(conf, transport, EnvironmentEdgeManager.currentTime());
  }

  public BulkHeartbeatScheduler(@NonNull Configuration conf, @NonNull Transport transport,
    long bootEpochMillis) {
    this(requireNonNull(conf).getInt(INTERVAL_MS_KEY, INTERVAL_MS_DEFAULT),
      conf.getInt(TIMER_THREADS_KEY, TIMER_THREADS_DEFAULT),
      conf.getLong(PAUSE_DETECTION_THRESHOLD_MS_KEY, PAUSE_DETECTION_THRESHOLD_MS_DEFAULT),
      conf.getLong(PAUSE_TOLERANCE_CAP_MS_KEY, PAUSE_TOLERANCE_CAP_MS_DEFAULT), transport,
      bootEpochMillis);
  }

  public BulkHeartbeatScheduler(int intervalMs, int timerThreads, long pauseDetectionThresholdMs,
    long pauseToleranceCapMs, @NonNull Transport transport) {
    this(intervalMs, timerThreads, pauseDetectionThresholdMs, pauseToleranceCapMs, transport,
      EnvironmentEdgeManager.currentTime());
  }

  public BulkHeartbeatScheduler(int intervalMs, int timerThreads, long pauseDetectionThresholdMs,
    long pauseToleranceCapMs, @NonNull Transport transport, long bootEpochMillis) {
    if (intervalMs <= 0) {
      throw new IllegalArgumentException("intervalMs must be > 0, got " + intervalMs);
    }
    if (timerThreads < 1) {
      throw new IllegalArgumentException("timerThreads must be >= 1, got " + timerThreads);
    }
    if (pauseDetectionThresholdMs < 0) {
      throw new IllegalArgumentException(
        "pauseDetectionThresholdMs must be >= 0, got " + pauseDetectionThresholdMs);
    }
    if (pauseToleranceCapMs < 0) {
      throw new IllegalArgumentException(
        "pauseToleranceCapMs must be >= 0, got " + pauseToleranceCapMs);
    }
    this.intervalMs = intervalMs;
    this.pauseDetectionThresholdMs = pauseDetectionThresholdMs;
    this.pauseToleranceCapMs = pauseToleranceCapMs;
    this.transport = requireNonNull(transport);
    this.bootEpochMillis = bootEpochMillis;
    final int id = POOL_ID.getAndIncrement();
    ThreadFactory tf =
      new ThreadFactoryBuilder().setNameFormat("hbase-consensus-bulk-heartbeat-wheel-" + id + "-%d")
        .setDaemon(true).setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build();
    this.timer = new ScheduledThreadPoolExecutor(timerThreads, tf);
    this.timer.setRemoveOnCancelPolicy(true);
    this.timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  /** Tick cadence in milliseconds. */
  public int intervalMs() {
    return intervalMs;
  }

  /** Number of registered groups. */
  public int registeredGroups() {
    return registry.size();
  }

  /** Boot-epoch millis stamped into every emitted bulk frame's keepalive header. */
  public long bootEpochMillis() {
    return bootEpochMillis;
  }

  @Override
  public void register(@NonNull RaftNodeImpl node) {
    requireNonNull(node);
    if (closed) {
      throw new IllegalStateException("BulkHeartbeatScheduler is closed");
    }
    Object groupId = requireNonNull(node.getGroupId(), "RaftNode.getGroupId() returned null");
    RaftNodeImpl existing = registry.putIfAbsent(groupId, node);
    if (existing != null && existing != node) {
      throw new IllegalArgumentException(
        "groupId " + groupId + " is already registered to a different RaftNode");
    }
  }

  @Override
  public void unregister(@NonNull RaftNodeImpl node) {
    requireNonNull(node);
    Object groupId = node.getGroupId();
    if (groupId == null) {
      return;
    }
    RaftNodeImpl existing = registry.get(groupId);
    if (existing != null && existing == node) {
      registry.remove(groupId, existing);
    }
  }

  public void start() {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("BulkHeartbeatScheduler is closed");
      }
      if (started) {
        return;
      }
      tickFuture =
        timer.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
      started = true;
    }
  }

  /** Idempotently starts the wheel timer the first time any registered Raft node starts. */
  @Override
  public void onRaftNodeStart() {
    start();
  }

  /**
   * Per-node terminate fires {@link #unregister} on the way in via
   * {@code RaftNodeImpl.terminateComponents}. When the last registered node is gone, close the
   * wheel.
   */
  @Override
  public void onRaftNodeTerminate() {
    if (registry.isEmpty()) {
      close();
    }
  }

  public void stop() {
    synchronized (lifecycleLock) {
      if (!started) {
        return;
      }
      ScheduledFuture<?> f = tickFuture;
      if (f != null) {
        f.cancel(false);
      }
      tickFuture = null;
      started = false;
    }
  }

  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
    }
    stop();
    timer.shutdown();
  }

  private void tick() {
    if (closed) {
      return;
    }
    long now = EnvironmentEdgeManager.currentTime();
    long pauseHintMillis = computePauseHint(now);
    lastTickStartedAtMillis = now;
    if (pauseHintMillis > 0L) {
      LOG.warn(
        "Detected JVM pause of {} ms on bulk-heartbeat wheel; emitting pause hint to {} groups "
          + "(threshold={} ms, cap={} ms).",
        pauseHintMillis, registry.size(), pauseDetectionThresholdMs, pauseToleranceCapMs);
    }
    long tick = tickCounter.incrementAndGet();
    // Clear the inner per-peer lists at the top of the tick to recycle their backing arrays. The
    // map keys persist, and any peer that no longer receives an entry contributes an empty list
    // to the post-walk emission loop, which skips it.
    for (List<LeaderHeartbeat> entries : perPeer.values()) {
      entries.clear();
    }
    for (RaftNodeImpl node : registry.values()) {
      try {
        walkNode(node, now, pauseHintMillis, perPeer);
      } catch (RuntimeException ex) {
        LOG.warn("bulk-heartbeat wheel walk failed for group {}", node.getGroupId(), ex);
      }
    }
    RaftEndpoint sender = transportSender();
    if (sender == null) {
      return;
    }
    for (Map.Entry<RaftEndpoint, List<LeaderHeartbeat>> e : perPeer.entrySet()) {
      List<LeaderHeartbeat> entries = e.getValue();
      if (entries.isEmpty()) {
        continue;
      }
      try {
        transport.sendBulkHeartbeat(e.getKey(),
          new BulkHeartbeatFrame(sender, bootEpochMillis, tick, entries));
      } catch (RuntimeException ex) {
        LOG.debug("sendBulkHeartbeat to {} failed", e.getKey().getId(), ex);
      }
    }
  }

  /**
   * Reads volatile / atomic snapshot fields off {@code node} (no per-group executor entry), applies
   * the pause hint, accumulates one per-group entry into the relevant peer accumulators, and
   * schedules the rare slow-path control tasks (lease expiry, follower lag, election-timer
   * elapsed).
   */
  private void walkNode(@NonNull RaftNodeImpl node, long now, long pauseHintMillis,
    @NonNull Map<RaftEndpoint, List<LeaderHeartbeat>> perPeer) {
    if (pauseHintMillis > 0L) {
      node.absorbPauseIfDetected(now, pauseHintMillis);
    }
    RaftState state = node.state();
    RaftRole role = state.role();
    if (role == RaftRole.LEADER) {
      walkLeader(node, state, perPeer);
    } else if (role == RaftRole.FOLLOWER || role == RaftRole.LEARNER) {
      walkFollower(node, state);
    }
  }

  private void walkLeader(@NonNull RaftNodeImpl node, @NonNull RaftState state,
    @NonNull Map<RaftEndpoint, List<LeaderHeartbeat>> perPeer) {
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      return;
    }
    long now = EnvironmentEdgeManager.currentTime();
    boolean leaseExpired =
      leaderState.leaseExpiryMillis() > 0L && leaderState.leaseExpiryMillis() <= now;
    boolean anyFollowerLagging = false;
    boolean allFollowersCaughtUp = true;
    long lastLogOrSnapshotIndex = state.log().lastLogOrSnapshotIndexVolatile();
    // The leader's per-group heartbeat content (term, commitIndex, quiesced) is identical for
    // every peer in this group, so build one LeaderHeartbeat per group per tick and fan it out to
    // every peer's accumulator. The fan-out target (peer endpoint) is encoded outside the
    // {@link LeaderHeartbeat} payload via the {@link BulkHeartbeatFrame} envelope address.
    LeaderHeartbeat hb = null;
    for (RaftEndpoint follower : state.committedGroupMembers().remoteMembers()) {
      FollowerState fs = leaderState.getFollowerStateOrNull(follower);
      if (fs == null || fs.matchIndex() < lastLogOrSnapshotIndex) {
        allFollowersCaughtUp = false;
        if (fs != null && !fs.isRequestBackoffSet()) {
          anyFollowerLagging = true;
        }
      }
      if (hb == null) {
        hb = new DefaultLeaderHeartbeatOrBuilder().setGroupId(node.getGroupId())
          .setSender(node.getLocalEndpoint()).setTerm(state.term())
          .setCommitIndex(state.commitIndex()).setQuiesced(leaderState.groupQuiescent()).build();
      }
      perPeer.computeIfAbsent(follower, k -> new ArrayList<>(8)).add(hb);
    }
    if (leaseExpired) {
      submitControl(node, node.demoteToFollowerIfLeaseExpiredTask(),
        "demoteToFollowerIfLeaseExpired");
    }
    if (anyFollowerLagging) {
      submitControl(node, node.sendCatchupAppendsIfNeededTask(), "sendCatchupAppendsIfNeeded");
    }
    maybeQuiesce(node, state, leaderState, now, leaseExpired, allFollowersCaughtUp);
  }

  /**
   * Decides whether the leader should transition this group to {@code Quiescent}. Gated by
   * {@link RaftConfig#isQuiescenceEnabled()}; when on, transitions only when (a) the group is not
   * already quiescent, (b) the leader holds a valid lease, (c) at least
   * {@link RaftConfig#getQuiescenceGraceMillis()} have elapsed since the last propose / wake, (d)
   * no leadership transfer is in flight, and (e) every voting follower's match index has caught up
   * to the leader's last log-or-snapshot index. Submits {@link RaftNodeImpl#quiesce(Collection)}
   * with an empty {@code lagging} collection on the per-group control lane; any concurrent propose
   * will call {@code RaftNodeImpl#wake()} which clears the flag before the next tick.
   */
  private void maybeQuiesce(@NonNull RaftNodeImpl node, @NonNull RaftState state,
    @NonNull LeaderState leaderState, long now, boolean leaseExpired,
    boolean allFollowersCaughtUp) {
    RaftConfig cfg = node.getConfig();
    if (!cfg.isQuiescenceEnabled()) {
      return;
    }
    if (state.groupQuiescent()) {
      return;
    }
    if (leaseExpired || leaderState.leaseExpiryMillis() <= 0L) {
      return;
    }
    if (now - leaderState.lastReplicateActivityMillis() < cfg.getQuiescenceGraceMillis()) {
      return;
    }
    if (state.leadershipTransferState() != null) {
      return;
    }
    if (!allFollowersCaughtUp) {
      return;
    }
    submitControl(node, () -> node.quiesce(Collections.emptyList()), "quiesce");
  }

  private void walkFollower(@NonNull RaftNodeImpl node, @NonNull RaftState state) {
    if (state.preCandidateState() != null) {
      return;
    }
    if (!node.isElectionTimerElapsed()) {
      return;
    }
    submitControl(node, node.resetLeaderAndTryTriggerPreVoteTask(),
      "resetLeaderAndTryTriggerPreVote");
  }

  private void submitControl(@NonNull RaftNodeImpl node, @NonNull Runnable body,
    @NonNull String label) {
    try {
      RaftNodeExecutor exec = node.getExecutor();
      exec.executeControl(body);
    } catch (RejectedExecutionException ex) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("control task '{}' for group {} rejected (executor shut down)", label,
          node.getGroupId());
      }
    } catch (Throwable t) {
      LOG.warn("control task '{}' for group {} failed to schedule", label, node.getGroupId(), t);
    }
  }

  /** Returns the single local endpoint shared by every registered node. */
  private RaftEndpoint transportSender() {
    Collection<RaftNodeImpl> nodes = registry.values();
    if (nodes.isEmpty()) {
      return null;
    }
    return nodes.iterator().next().getLocalEndpoint();
  }

  /**
   * Returns the pause delta (ms) to attest to nodes on this tick, or {@code 0} if no pause was
   * detected. Returns {@code 0} on the first tick after start (no baseline) and on any tick whose
   * inter-tick wall-clock delta is below the configured threshold or above the configured cap.
   */
  private long computePauseHint(long now) {
    if (lastTickStartedAtMillis <= 0L) {
      return 0L;
    }
    long delta = (now - lastTickStartedAtMillis) - intervalMs;
    if (delta < pauseDetectionThresholdMs) {
      return 0L;
    }
    if (delta > pauseToleranceCapMs) {
      return 0L;
    }
    return delta;
  }
}
