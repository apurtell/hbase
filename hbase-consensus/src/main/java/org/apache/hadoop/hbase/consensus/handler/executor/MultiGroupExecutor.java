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
package org.apache.hadoop.hbase.consensus.handler.executor;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.conf.ConfigKey;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Multi-group executor.
 * <p>
 * Owns two shared worker pools and hands out one {@link RaftNodeExecutor} per Raft group id.
 * {@link GroupExecutor} instances are created on demand.
 * <p>
 * <b>Pool split.</b> Producers hit two distinct hot paths:
 * <ul>
 * <li>Immediate drain dispatches via {@link #submitDrain} (every {@link GroupExecutor#enqueue} that
 * flips the group's {@code scheduled} flag and every drain re-submission when a lane has remaining
 * work).</li>
 * <li>Delayed scheduling via {@link #schedule} (used by per-group timers and rebackoff
 * trampolines).</li>
 * </ul>
 * The first path is the dominant producer at scale ({@code N groups * sweep frequency} plus inbound
 * dispatch). It does not need delayed-task semantics, so a {@link ThreadPoolExecutor} backed by a
 * lock-light {@link LinkedTransferQueue} is sufficient and avoids the heap-+-{@code ReentrantLock}
 * contention that {@link ScheduledThreadPoolExecutor}'s {@code DelayedWorkQueue} imposes when many
 * producers concurrently submit zero-delay tasks. The second path keeps a small dedicated
 * {@link ScheduledThreadPoolExecutor} for the actual delayed-scheduling case; its trampolines post
 * the eventual real work back through {@link #submitDrain}, so the
 * {@link ScheduledThreadPoolExecutor} never carries the high-fan-out load itself.
 */
@InterfaceAudience.Private
public final class MultiGroupExecutor {

  /**
   * Worker count of the shared {@link ScheduledThreadPoolExecutor} backing every group's drain and
   * scheduled tasks. Defaults to {@code max(2, 2 * availableProcessors)} when unset.
   */
  public static final String POOL_SIZE_KEY =
    ConfigKey.INT("hbase.consensus.executor.pool.size", v -> v >= 1);

  /**
   * The literal worker-thread floor. Takes precedence over {@code POOL_SIZE_KEY} when both are set.
   * Either key is treated as a floor by the {@link #MultiGroupExecutor(Configuration, int)}, which
   * scales the effective thread count up with {@link #THREADS_PER_GROUP_TARGET_KEY} when
   * {@code maxGroups} is high enough to warrant it.
   */
  public static final String THREADS_KEY =
    ConfigKey.INT("hbase.consensus.executor.threads", v -> v >= 1);

  /**
   * With {@code maxGroups} groups configured, the {@link #MultiGroupExecutor(Configuration, int)}
   * resolves the effective pool size as
   * {@code min(threadsCeiling, max(threads, ceil(maxGroups * threadsPerGroupTarget)))}, where
   * {@code threadsCeiling} comes from {@link #THREADS_CEILING_KEY} (defaulting to {@code 4 *
   * availableProcessors}). The default {@code 0.05} provides one worker thread per twenty groups.
   * Set to {@code 0} to disable per-group scaling and use only the literal {@link #THREADS_KEY} /
   * {@link #POOL_SIZE_KEY} floor.
   */
  public static final String THREADS_PER_GROUP_TARGET_KEY =
    "hbase.consensus.executor.threads.per.group.target";
  public static final double THREADS_PER_GROUP_TARGET_DEFAULT = 0.05d;

  /**
   * Ceiling on the resolved drain-pool worker count. The
   * {@link #MultiGroupExecutor(Configuration, int)} constructor caps the per-group-scaled pool size
   * at this value so a misconfigured {@code maxGroups} cannot silently provision hundreds of worker
   * threads. Defaults to {@code 4 * availableProcessors}.
   */
  public static final String THREADS_CEILING_KEY =
    ConfigKey.INT("hbase.consensus.executor.threads.ceiling", v -> v >= 1);

  /**
   * Maximum number of bulk-class mailbox messages a single drain pass on a {@link GroupExecutor}
   * consumes before yielding the worker thread back to the shared pool. By the cap-then-resubmit
   * fairness rule, this cap also bounds the control-lane head-arrival latency at
   * {@code drainBatchCap * max(per-bulk-task wallclock)}. A control task that finds an empty
   * control mailbox at enqueue waits at most one full bulk burst before being serviced. Sizing this
   * cap (and bounding worst-case bulk-task wallclock) is therefore the primary lever for
   * bulk-blocking-control latency.
   */
  public static final String DRAIN_BATCH_CAP_KEY =
    ConfigKey.INT("hbase.consensus.executor.drain.batch.cap", v -> v >= 1);
  public static final int DRAIN_BATCH_CAP_DEFAULT = 64;

  /**
   * Maximum number of control-class tasks (vote / pre-vote / heartbeat / heartbeat-ack handlers) a
   * single drain pass on a {@link GroupExecutor} consumes before yielding to the bulk mailbox.
   */
  public static final String CONTROL_BATCH_CAP_KEY =
    ConfigKey.INT("hbase.consensus.executor.control.batch.cap", v -> v >= 1);
  public static final int CONTROL_BATCH_CAP_DEFAULT = 32;

  /** Initial chunk size of the per-group MPSC mailbox. */
  public static final String MAILBOX_CHUNK_SIZE_KEY =
    ConfigKey.INT("hbase.consensus.executor.mailbox.chunk", v -> v >= 2);
  public static final int MAILBOX_CHUNK_SIZE_DEFAULT = 256;

  /**
   * Worker count of the dedicated {@link ScheduledThreadPoolExecutor} that handles only the
   * delayed-scheduling path. The drain hot path uses a separate {@link ThreadPoolExecutor} (sized
   * via {@link #THREADS_KEY} / {@link #POOL_SIZE_KEY}) to keep the two contention domains disjoint.
   */
  public static final String SCHEDULED_THREADS_KEY =
    ConfigKey.INT("hbase.consensus.executor.scheduled.threads", v -> v >= 1);
  public static final int SCHEDULED_THREADS_DEFAULT = 2;

  private static final Logger LOG = LoggerFactory.getLogger(MultiGroupExecutor.class);
  private static final AtomicInteger POOL_ID = new AtomicInteger();

  private final ThreadPoolExecutor drainPool;
  private final ScheduledThreadPoolExecutor scheduledPool;
  private final ConcurrentMap<Object, GroupExecutor> registry = new ConcurrentHashMap<>();
  private final int drainBatchCap;
  private final int controlBatchCap;
  private final int mailboxChunkSize;
  private final Object lifecycleLock = new Object();
  private volatile boolean closed;

  public MultiGroupExecutor() {
    this(defaultPoolSize(), DRAIN_BATCH_CAP_DEFAULT, CONTROL_BATCH_CAP_DEFAULT,
      MAILBOX_CHUNK_SIZE_DEFAULT);
  }

  /**
   * Resolves the effective worker-thread count as
   * {@code min(threadsCeiling, max(threadsFloor, ceil(maxGroups * threadsPerGroupTarget)))} where
   * {@code threadsFloor} comes from {@link #THREADS_KEY} (or {@link #POOL_SIZE_KEY} for backward
   * compat), {@code threadsPerGroupTarget} from {@link #THREADS_PER_GROUP_TARGET_KEY}, and
   * {@code threadsCeiling} from {@link #THREADS_CEILING_KEY} (defaulting to
   * {@code 4 * availableProcessors}). A deployment that pre-declares a high
   * {@code hbase.consensus.maxgroups} therefore gets a wider pool automatically without manual
   * tuning.
   */
  public MultiGroupExecutor(@NonNull Configuration conf, int maxGroups) {
    this(resolveEffectivePoolSize(requireNonNull(conf), maxGroups),
      conf.getInt(DRAIN_BATCH_CAP_KEY, DRAIN_BATCH_CAP_DEFAULT),
      conf.getInt(CONTROL_BATCH_CAP_KEY, CONTROL_BATCH_CAP_DEFAULT),
      conf.getInt(MAILBOX_CHUNK_SIZE_KEY, MAILBOX_CHUNK_SIZE_DEFAULT),
      conf.getInt(SCHEDULED_THREADS_KEY, SCHEDULED_THREADS_DEFAULT));
  }

  /**
   * Resolves the effective pool size from the floor / per-group-target / maxGroups inputs, capped
   * at {@link #THREADS_CEILING_KEY} (defaulting to {@code 4 * availableProcessors}) so a
   * misconfigured {@code maxGroups} cannot silently provision hundreds of worker threads. Visible
   * for tests.
   */
  static int resolveEffectivePoolSize(@NonNull Configuration conf, int maxGroups) {
    int floor = conf.getInt(THREADS_KEY, conf.getInt(POOL_SIZE_KEY, defaultPoolSize()));
    double target = conf.getDouble(THREADS_PER_GROUP_TARGET_KEY, THREADS_PER_GROUP_TARGET_DEFAULT);
    int scaled = target > 0d && maxGroups > 0 ? (int) Math.ceil((double) maxGroups * target) : 0;
    int ceiling = conf.getInt(THREADS_CEILING_KEY, 4 * Runtime.getRuntime().availableProcessors());
    int desired = Math.max(floor, scaled);
    return Math.max(1, Math.min(ceiling, desired));
  }

  public MultiGroupExecutor(int poolSize, int drainBatchCap, int mailboxChunkSize) {
    this(poolSize, drainBatchCap, CONTROL_BATCH_CAP_DEFAULT, mailboxChunkSize,
      SCHEDULED_THREADS_DEFAULT);
  }

  public MultiGroupExecutor(int poolSize, int drainBatchCap, int controlBatchCap,
    int mailboxChunkSize) {
    this(poolSize, drainBatchCap, controlBatchCap, mailboxChunkSize, SCHEDULED_THREADS_DEFAULT);
  }

  public MultiGroupExecutor(int poolSize, int drainBatchCap, int controlBatchCap,
    int mailboxChunkSize, int scheduledThreads) {
    if (poolSize < 1) {
      throw new IllegalArgumentException(POOL_SIZE_KEY + " must be >= 1, got " + poolSize);
    }
    if (drainBatchCap < 1) {
      throw new IllegalArgumentException(
        DRAIN_BATCH_CAP_KEY + " must be >= 1, got " + drainBatchCap);
    }
    if (controlBatchCap < 1) {
      throw new IllegalArgumentException(
        CONTROL_BATCH_CAP_KEY + " must be >= 1, got " + controlBatchCap);
    }
    if (mailboxChunkSize < 2) {
      throw new IllegalArgumentException(
        MAILBOX_CHUNK_SIZE_KEY + " must be >= 2, got " + mailboxChunkSize);
    }
    if (scheduledThreads < 1) {
      throw new IllegalArgumentException(
        SCHEDULED_THREADS_KEY + " must be >= 1, got " + scheduledThreads);
    }
    this.drainBatchCap = drainBatchCap;
    this.controlBatchCap = controlBatchCap;
    this.mailboxChunkSize = mailboxChunkSize;
    final int id = POOL_ID.getAndIncrement();
    ThreadFactory drainTf =
      new ThreadFactoryBuilder().setNameFormat("hbase-consensus-mge-" + id + "-drain-%d")
        .setDaemon(true).setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build();
    // LinkedTransferQueue is lock-free for the producer side under contention; ThreadPoolExecutor
    // tolerates the unbounded queue because every producer is well-formed. Drain
    // submissions are coalesced by GroupExecutor's atomic "scheduled" flag, so the queue depth is
    // bounded by activeGroups even at peak fan-out.
    this.drainPool = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
      new LinkedTransferQueue<>(), drainTf);
    ThreadFactory schedTf =
      new ThreadFactoryBuilder().setNameFormat("hbase-consensus-mge-" + id + "-sched-%d")
        .setDaemon(true).setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build();
    this.scheduledPool = new ScheduledThreadPoolExecutor(scheduledThreads, schedTf);
    this.scheduledPool.setRemoveOnCancelPolicy(true);
    this.scheduledPool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  private static int defaultPoolSize() {
    return Math.max(2, 2 * Runtime.getRuntime().availableProcessors());
  }

  int drainBatchCap() {
    return drainBatchCap;
  }

  int controlBatchCap() {
    return controlBatchCap;
  }

  boolean submitDrain(@NonNull Runnable drain) {
    requireNonNull(drain);
    try {
      drainPool.execute(drain);
      return true;
    } catch (RejectedExecutionException e) {
      if (!drainPool.isShutdown()) {
        throw e;
      }
      LOG.debug("drain rejected (pool shut down)");
      return false;
    }
  }

  @Nullable
  ScheduledFuture<?> schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit unit) {
    requireNonNull(task);
    requireNonNull(unit);
    try {
      return scheduledPool.schedule(task, delay, unit);
    } catch (RejectedExecutionException e) {
      if (!scheduledPool.isShutdown()) {
        throw e;
      }
      LOG.debug("schedule rejected (pool shut down)");
      return null;
    }
  }

  /**
   * Removes the registry entry for {@code groupId} only if the current value is {@code self}. This
   * is essential to prevent a terminating GroupExecutor's late unregister from clobbering a fresh
   * replacement that a racing executorFor call has already installed for the same group id.
   */
  void unregister(@NonNull Object groupId, @NonNull GroupExecutor self) {
    registry.remove(requireNonNull(groupId), requireNonNull(self));
  }

  /**
   * Returns the serial executor for the given Raft group id, creating a new one if the registry is
   * empty for that id or the registered entry is already terminated. The latter handles the narrow
   * window where a previous owner's {@code onRaftNodeTerminate} has flipped the terminated flag but
   * the unregister has not yet been observed by this lookup. Returning a terminated executor would
   * silently drop the very first task submitted to it and hang the caller.
   * @throws IllegalStateException if {@link #close()} has completed
   */
  @NonNull
  public RaftNodeExecutor executorFor(@NonNull Object groupId) {
    final Object gid = requireNonNull(groupId);
    // Fast path. Registered groups are looked up without acquiring the lifecycle lock. The lock is
    // only needed to serialize creation against close() so we never insert into the registry after
    // close has snapshotted it for termination.
    GroupExecutor existing = registry.get(gid);
    if (existing != null && !existing.isTerminated()) {
      if (closed) {
        throw new IllegalStateException("MultiGroupExecutor is closed");
      }
      return existing;
    }
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("MultiGroupExecutor is closed");
      }
      // compute() runs under per-bin lock, so any terminate / executorFor is serialized
      for (;;) {
        GroupExecutor result = registry.compute(gid,
          (k, prev) -> (prev != null && !prev.isTerminated())
            ? prev
            : new GroupExecutor(this, k, mailboxChunkSize));
        if (!result.isTerminated()) {
          return result;
        }
      }
    }
  }

  /** Number of group executors currently registered (including not yet terminated). */
  public int activeGroups() {
    return registry.size();
  }

  /** Terminates every registered {@link GroupExecutor} and shuts down both shared pools. */
  public void close() throws InterruptedException {
    synchronized (lifecycleLock) {
      closed = true;
    }
    for (GroupExecutor ge : new ArrayList<>(registry.values())) {
      ge.onRaftNodeTerminate();
    }
    scheduledPool.shutdown();
    drainPool.shutdown();
    // Bounded wait; callers may follow up with shutdownNow if needed
    scheduledPool.awaitTermination(60, TimeUnit.SECONDS);
    drainPool.awaitTermination(60, TimeUnit.SECONDS);
  }

  /** Same as {@link #close()} but does not throw {@link InterruptedException}. */
  public void closeUnchecked() {
    try {
      close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      scheduledPool.shutdownNow();
      drainPool.shutdownNow();
    }
  }

  /** Drain pool. Visible for tests. */
  ThreadPoolExecutor drainPool() {
    return drainPool;
  }

  /** Scheduled pool. Visible for tests. */
  ScheduledThreadPoolExecutor scheduledPool() {
    return scheduledPool;
  }
}
