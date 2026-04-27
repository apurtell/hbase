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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
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
 * Owns a shared {@link ScheduledThreadPoolExecutor} and hands out one {@link RaftNodeExecutor} per
 * Raft group id. {@link GroupExecutor} instances are created on demand.
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
   * Maximum number of mailbox messages a single drain pass on a {@link GroupExecutor} consumes
   * before yielding the worker thread back to the shared pool.
   */
  public static final String DRAIN_BATCH_CAP_KEY =
    ConfigKey.INT("hbase.consensus.executor.drain.batch.cap", v -> v >= 1);
  public static final int DRAIN_BATCH_CAP_DEFAULT = 64;

  /** Initial chunk size of the per-group MPSC mailbox. */
  public static final String MAILBOX_CHUNK_SIZE_KEY =
    ConfigKey.INT("hbase.consensus.executor.mailbox.chunk", v -> v >= 2);
  public static final int MAILBOX_CHUNK_SIZE_DEFAULT = 256;

  private static final Logger LOG = LoggerFactory.getLogger(MultiGroupExecutor.class);
  private static final AtomicInteger POOL_ID = new AtomicInteger();

  private final ScheduledThreadPoolExecutor pool;
  private final ConcurrentMap<Object, GroupExecutor> registry = new ConcurrentHashMap<>();
  private final int drainBatchCap;
  private final int mailboxChunkSize;
  private final Object lifecycleLock = new Object();
  private volatile boolean closed;

  public MultiGroupExecutor() {
    this(defaultPoolSize(), DRAIN_BATCH_CAP_DEFAULT, MAILBOX_CHUNK_SIZE_DEFAULT);
  }

  public MultiGroupExecutor(@NonNull Configuration conf) {
    this(requireNonNull(conf).getInt(POOL_SIZE_KEY, defaultPoolSize()),
      conf.getInt(DRAIN_BATCH_CAP_KEY, DRAIN_BATCH_CAP_DEFAULT),
      conf.getInt(MAILBOX_CHUNK_SIZE_KEY, MAILBOX_CHUNK_SIZE_DEFAULT));
  }

  public MultiGroupExecutor(int poolSize, int drainBatchCap, int mailboxChunkSize) {
    if (poolSize < 1) {
      throw new IllegalArgumentException(POOL_SIZE_KEY + " must be >= 1, got " + poolSize);
    }
    if (drainBatchCap < 1) {
      throw new IllegalArgumentException(
        DRAIN_BATCH_CAP_KEY + " must be >= 1, got " + drainBatchCap);
    }
    if (mailboxChunkSize < 2) {
      throw new IllegalArgumentException(
        MAILBOX_CHUNK_SIZE_KEY + " must be >= 2, got " + mailboxChunkSize);
    }
    this.drainBatchCap = drainBatchCap;
    this.mailboxChunkSize = mailboxChunkSize;
    final int id = POOL_ID.getAndIncrement();
    ThreadFactory tf = new ThreadFactoryBuilder().setNameFormat("hbase-consensus-mge-" + id + "-%d")
      .setDaemon(true).setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER).build();
    this.pool = new ScheduledThreadPoolExecutor(poolSize, tf);
    this.pool.setRemoveOnCancelPolicy(true);
    this.pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  private static int defaultPoolSize() {
    return Math.max(2, 2 * Runtime.getRuntime().availableProcessors());
  }

  int drainBatchCap() {
    return drainBatchCap;
  }

  int mailboxChunkSize() {
    return mailboxChunkSize;
  }

  boolean submitDrain(@NonNull Runnable drain) {
    requireNonNull(drain);
    try {
      pool.execute(drain);
      return true;
    } catch (RejectedExecutionException e) {
      if (!pool.isShutdown()) {
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
      return pool.schedule(task, delay, unit);
    } catch (RejectedExecutionException e) {
      if (!pool.isShutdown()) {
        throw e;
      }
      LOG.debug("schedule rejected (pool shut down)");
      return null;
    }
  }

  void unregister(@NonNull Object groupId) {
    registry.remove(requireNonNull(groupId));
  }

  /**
   * Returns the serial executor for the given Raft group id, creating it if absent.
   * @throws IllegalStateException if {@link #close()} has completed
   */
  @NonNull
  public RaftNodeExecutor executorFor(@NonNull Object groupId) {
    final Object gid = requireNonNull(groupId);
    // Fast path. Registered groups are looked up without acquiring the lifecycle lock. The lock is
    // only needed to serialize creation against close() so we never insert into the registry after
    // close has snapshotted it for termination.
    GroupExecutor existing = registry.get(gid);
    if (existing != null) {
      if (closed) {
        throw new IllegalStateException("MultiGroupExecutor is closed");
      }
      return existing;
    }
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("MultiGroupExecutor is closed");
      }
      return registry.computeIfAbsent(gid, id -> new GroupExecutor(this, id, mailboxChunkSize));
    }
  }

  /** Number of group executors currently registered (including not yet terminated). */
  public int activeGroups() {
    return registry.size();
  }

  /**
   * Terminates every registered {@link GroupExecutor} and shuts down the shared pool.
   */
  public void close() throws InterruptedException {
    synchronized (lifecycleLock) {
      closed = true;
    }
    for (GroupExecutor ge : new ArrayList<>(registry.values())) {
      ge.onRaftNodeTerminate();
    }
    pool.shutdown();
    // Bounded wait; callers may follow up with shutdownNow if needed
    pool.awaitTermination(60, TimeUnit.SECONDS);
  }

  /** Same as {@link #close()} but does not throw {@link InterruptedException}. */
  public void closeUnchecked() {
    try {
      close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
  }

  /** Visible for tests. */
  ScheduledThreadPoolExecutor pool() {
    return pool;
  }
}
