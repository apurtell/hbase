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
package org.apache.hadoop.hbase.consensus.handler.heartbeat;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.conf.ConfigKey;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Store-level reference {@link HeartbeatScheduler} that drives every registered
 * {@link RaftNodeImpl} from a single {@link ScheduledThreadPoolExecutor}.
 * <p>
 * On each tick the sweeper enqueues {@link RaftNodeImpl#runHeartbeatTick()} on each registered
 * node's own {@link RaftNodeExecutor}, preserving the per-group serial execution contract. The
 * outbound {@code LeaderHeartbeat}s produced by all groups in that flush window therefore reach the
 * per-peer mailboxes of the {@code CoalescingTransport} together, so the wire sees a single
 * {@code HEARTBEAT_BATCH} {@code ConsensusFrame} per peer per tick regardless of the number of
 * registered groups.
 */
@InterfaceAudience.Private
public final class SweepingHeartbeatScheduler implements HeartbeatScheduler, AutoCloseable {

  /** Sweep interval in milliseconds. */
  public static final String INTERVAL_MS_KEY =
    ConfigKey.INT("hbase.consensus.heartbeat.interval.ms", v -> v >= 1);
  public static final int INTERVAL_MS_DEFAULT = 250;

  /** Number of timer threads in the shared sweeper {@link ScheduledThreadPoolExecutor}. */
  public static final String TIMER_THREADS_KEY =
    ConfigKey.INT("hbase.consensus.heartbeat.sweeper.timer.threads", v -> v >= 1);
  public static final int TIMER_THREADS_DEFAULT = 1;

  private static final Logger LOG = LoggerFactory.getLogger(SweepingHeartbeatScheduler.class);
  private static final AtomicInteger POOL_ID = new AtomicInteger();

  private final ScheduledThreadPoolExecutor timer;
  private final ConcurrentMap<Object, RaftNodeImpl> registry = new ConcurrentHashMap<>();
  private final int intervalMs;
  private final Object lifecycleLock = new Object();
  private volatile ScheduledFuture<?> tickFuture;
  private volatile boolean started;
  private volatile boolean closed;

  public SweepingHeartbeatScheduler(@NonNull Configuration conf) {
    this(requireNonNull(conf).getInt(INTERVAL_MS_KEY, INTERVAL_MS_DEFAULT),
      conf.getInt(TIMER_THREADS_KEY, TIMER_THREADS_DEFAULT));
  }

  public SweepingHeartbeatScheduler(int intervalMs, int timerThreads) {
    if (intervalMs <= 0) {
      throw new IllegalArgumentException("intervalMs must be > 0, got " + intervalMs);
    }
    if (timerThreads < 1) {
      throw new IllegalArgumentException("timerThreads must be >= 1, got " + timerThreads);
    }
    this.intervalMs = intervalMs;
    final int id = POOL_ID.getAndIncrement();
    ThreadFactory tf =
      new ThreadFactoryBuilder().setNameFormat("hbase-consensus-heartbeat-sweeper-" + id + "-%d")
        .setDaemon(true).setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build();
    this.timer = new ScheduledThreadPoolExecutor(timerThreads, tf);
    this.timer.setRemoveOnCancelPolicy(true);
    this.timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  /** Sweep interval in milliseconds. */
  public int intervalMs() {
    return intervalMs;
  }

  /** Number of registered groups. Visible for tests / metrics. */
  public int registeredGroups() {
    return registry.size();
  }

  /**
   * Registers the given node so its {@link RaftNodeImpl#runHeartbeatTick()} is enqueued on every
   * sweep tick. throws {@link IllegalArgumentException}. Throws {@link IllegalStateException} after
   * {@link #close()}.
   */
  @Override
  public void register(@NonNull RaftNodeImpl node) {
    requireNonNull(node);
    if (closed) {
      throw new IllegalStateException("SweepingHeartbeatScheduler is closed");
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
    registry.remove(groupId, node);
  }

  public void start() {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("SweepingHeartbeatScheduler is closed");
      }
      if (started) {
        return;
      }
      tickFuture =
        timer.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
      started = true;
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
    for (RaftNodeImpl node : registry.values()) {
      try {
        RaftNodeExecutor exec = node.getExecutor();
        exec.execute(node::runHeartbeatTick);
      } catch (RejectedExecutionException e) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("dispatch tick for group {} rejected (executor shut down)", node.getGroupId());
        }
      } catch (Throwable t) {
        LOG.warn("dispatch tick for group {} failed", node.getGroupId(), t);
      }
    }
  }
}
