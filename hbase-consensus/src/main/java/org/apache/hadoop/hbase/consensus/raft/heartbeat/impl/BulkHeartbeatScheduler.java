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
import edu.umd.cs.findbugs.annotations.Nullable;
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
import java.util.function.Supplier;
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
 * <li>computes the JVM-pause delta from a {@link System#nanoTime() monotonic} inter-tick reading
 * against the configured detection band and applies it lock-free to every registered node's
 * heartbeat / election / lease accumulators;</li>
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
   * Floor (ms) on observed inter-tick monotonic delta above the expected interval at which the
   * wheel attests a JVM pause to its registered nodes. The delta is measured with
   * {@link System#nanoTime()}, which is local-monotonic. Mirrors
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
   * {@link System#nanoTime()} reading captured at the start of the most recent {@link #tick()}.
   * Used exclusively by the JVM-pause detector. Local-monotonic. Read and written exclusively from
   * the wheel thread; meaningful only while {@link #lastTickStartedAtNanosSet} is {@code true}.
   */
  private long lastTickStartedAtNanos;
  /**
   * Set to {@code true} after the wheel records its first tick. Required because {@code 0L} is a
   * valid {@link System#nanoTime()} return value. Read and written exclusively from the wheel
   * thread.
   */
  private boolean lastTickStartedAtNanosSet;
  /**
   * Per-tick scratch accumulator from peer to the per-group heartbeat list aimed at that peer.
   * Reused across {@link #tick()} invocations.
   */
  private final Map<RaftEndpoint, List<LeaderHeartbeat>> perPeer = new HashMap<>(8);

  private volatile ScheduledFuture<?> tickFuture;
  private volatile boolean started;
  private volatile boolean closed;
  /**
   * Optional embedding-supplied factory of idle flush operations. When non-null and the leader walk
   * decides a group has been idle past {@link RaftConfig#getIdleFlushIntervalMillis()}, the wheel
   * asks the supplier for a fresh marker and dispatches it via
   * {@link RaftNodeImpl#proposeIdleFlush(Object)}. The supplier runs on the wheel thread and must
   * be cheap and non-blocking. Held {@code volatile} because it is set on the constructing thread
   * and read on the wheel thread.
   */
  @Nullable
  private volatile Supplier<Object> idleFlushOperationSupplier;

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

  /**
   * Installs an embedding-supplied factory of idle flush operations. When set, the wheel's leader
   * walk gates each group on {@link RaftConfig#isIdleFlushEnabled()} and the per group
   * {@link RaftConfig#getIdleFlushIntervalMillis()}, asks the supplier for a fresh marker on each
   * idle trip, and dispatches it via {@link RaftNodeImpl#proposeIdleFlush(Object)} on the group's
   * control lane. Pass {@code null} to disable. The supplier runs on the wheel thread once per
   * per-group dispatch, so it must be cheap and nonblocking. The latest value wins from the next
   * tick.
   */
  public void setIdleFlushOperationSupplier(@Nullable Supplier<Object> supplier) {
    this.idleFlushOperationSupplier = supplier;
  }

  /**
   * Returns the currently-installed synthetic flush operation supplier, or {@code null} if
   * idle-flush dispatching is disabled. Exposed primarily for tests.
   */
  @Nullable
  public Supplier<Object> getIdleFlushOperationSupplier() {
    return idleFlushOperationSupplier;
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
    // Sample the monotonic clock for pause detection BEFORE the wall clock so the inter-tick
    // delta captures only elapsed-on-this-CPU time. The wall clock is still required below for
    // lease / activity / quiesce-grace comparisons against fields stored as wall-clock millis.
    long nowNanos = System.nanoTime();
    long pauseHintMillis = computePauseHint(nowNanos);
    lastTickStartedAtNanos = nowNanos;
    lastTickStartedAtNanosSet = true;
    long now = EnvironmentEdgeManager.currentTime();
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
    maybeProposeIdleFlush(node, state, leaderState, now, leaseExpired);
    maybeQuiesce(node, state, leaderState, now, leaseExpired, allFollowersCaughtUp);
  }

  /**
   * Decides whether the leader should synthesize a flush boundary on this group's behalf so that
   * the {@code UnifiedRaftStore}'s per-group GC frontier can advance even when the application is
   * not driving any traffic. Gated by {@link RaftConfig#isIdleFlushEnabled()} and the presence of
   * an embedding-supplied {@link #idleFlushOperationSupplier}; when both are present, the wheel
   * dispatches when (a) the leader holds a valid lease, (b) at least
   * {@link RaftConfig#getIdleFlushIntervalMillis()} have elapsed since the last propose / wake, (c)
   * at least the same interval has elapsed since the last synthetic dispatch on this group
   * (rate-limit), and (d) the per-group last-applied has moved past the current snapshot index
   * (otherwise a fresh flush would not produce a new GC frontier). The wheel does the cheap
   * volatile-snapshot pre-check; the per-group executor body in
   * {@link RaftNodeImpl#proposeIdleFlush(Object)} re-runs every check against the live executor
   * view before issuing the actual {@link RaftNodeImpl#replicate(Object) replicate} +
   * {@link RaftNodeImpl#takeSnapshot()} pair, which is what is needed to handle the case where the
   * snapshot or activity timestamp moved between the wheel's sample and the executor turn.
   * <p>
   * Quiescent groups are eligible. The wake will cause a brief departure from quiescence.
   * {@link #maybeQuiesce} re-evaluates each tick and will return the group to quiescent on the next
   * tick after the idle flush replicate + snapshot completes.
   */
  private void maybeProposeIdleFlush(@NonNull RaftNodeImpl node, @NonNull RaftState state,
    @NonNull LeaderState leaderState, long now, boolean leaseExpired) {
    Supplier<Object> supplier = idleFlushOperationSupplier;
    if (supplier == null) {
      return;
    }
    RaftConfig cfg = node.getConfig();
    if (!cfg.isIdleFlushEnabled()) {
      return;
    }
    if (leaseExpired || leaderState.leaseExpiryMillis() <= 0L) {
      return;
    }
    long interval = cfg.getIdleFlushIntervalMillis();
    if (now - leaderState.lastReplicateActivityMillis() < interval) {
      return;
    }
    if (now - leaderState.lastIdleFlushTriggerMillis() < interval) {
      return;
    }
    long lastApplied = state.lastApplied();
    long snapshotIndex = state.log().snapshotIndex();
    if (lastApplied <= snapshotIndex) {
      return;
    }
    Object marker;
    try {
      marker = supplier.get();
    } catch (RuntimeException ex) {
      LOG.warn("idle flush operation supplier threw for group {}", node.getGroupId(), ex);
      return;
    }
    if (marker == null) {
      return;
    }
    submitControl(node, () -> node.proposeIdleFlush(marker), "proposeIdleFlush");
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
   * detected. The inter-tick delta is computed from {@link System#nanoTime()} readings so the
   * detector is immune to wall-clock manipulation. The delta is then converted to milliseconds for
   * the threshold / cap comparison and the emitted hint, which is consumed by
   * {@code RaftNodeImpl.absorbPauseIfDetected}. Returns {@code 0} on the first tick after start and
   * on any tick whose inter-tick delta is below the configured threshold or above the cap.
   * @param nowNanos the current {@link System#nanoTime()} reading captured at the top of
   *                 {@link #tick()}
   */
  private long computePauseHint(long nowNanos) {
    if (!lastTickStartedAtNanosSet) {
      return 0L;
    }
    // Subtraction is correct under nanoTime wraparound (two's complement).
    long elapsedNanos = nowNanos - lastTickStartedAtNanos;
    long deltaNanos = elapsedNanos - TimeUnit.MILLISECONDS.toNanos(intervalMs);
    long deltaMillis = TimeUnit.NANOSECONDS.toMillis(deltaNanos);
    if (deltaMillis < pauseDetectionThresholdMs) {
      return 0L;
    }
    if (deltaMillis > pauseToleranceCapMs) {
      return 0L;
    }
    return deltaMillis;
  }
}
