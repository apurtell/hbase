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
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns a shared {@link ScheduledThreadPoolExecutor} and hands out one {@link RaftNodeExecutor} per
 * Raft group id. {@link GroupExecutor} instances are created on demand and removed when their
 * {@link org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware#onRaftNodeTerminate()}
 * runs.
 */
public final class MultiGroupExecutor {

  /** Default {@link org.jctools.queues.MpscUnboundedArrayQueue} chunk size. */
  public static final int DEFAULT_MAILBOX_CHUNK_SIZE = 256;

  /** Default max runnable executions per drain pass before yielding back to the pool. */
  public static final int DEFAULT_DRAIN_BATCH_CAP = 64;

  private static final Logger LOG = LoggerFactory.getLogger(MultiGroupExecutor.class);
  private static final AtomicInteger POOL_ID = new AtomicInteger();

  private final ScheduledThreadPoolExecutor pool;
  private final ConcurrentMap<Object, GroupExecutor> registry = new ConcurrentHashMap<>();
  private final int drainBatchCap;
  private final int mailboxChunkSize;
  private final Object lifecycleLock = new Object();
  private volatile boolean closed;

  /** Pool size {@code 2 * availableProcessors}, default drain cap and mailbox chunk size. */
  public MultiGroupExecutor() {
    this(Math.max(2, 2 * Runtime.getRuntime().availableProcessors()), DEFAULT_DRAIN_BATCH_CAP,
      DEFAULT_MAILBOX_CHUNK_SIZE);
  }

  /**
   * @param poolSize         shared scheduler worker count (must be at least 1)
   * @param drainBatchCap    max tasks to run per drain pass per group before re-queueing
   * @param mailboxChunkSize JCTools MPSC unbounded queue chunk size
   */
  public MultiGroupExecutor(int poolSize, int drainBatchCap, int mailboxChunkSize) {
    if (poolSize < 1) {
      throw new IllegalArgumentException("poolSize must be >= 1");
    }
    if (drainBatchCap < 1) {
      throw new IllegalArgumentException("drainBatchCap must be >= 1");
    }
    if (mailboxChunkSize < 2) {
      throw new IllegalArgumentException("mailboxChunkSize must be >= 2");
    }
    this.drainBatchCap = drainBatchCap;
    this.mailboxChunkSize = mailboxChunkSize;
    final int id = POOL_ID.getAndIncrement();
    final AtomicInteger threadId = new AtomicInteger();
    ThreadFactory tf = runnable -> {
      Thread t =
        new Thread(runnable, "hbase-consensus-mge-" + id + "-" + threadId.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
    this.pool = new ScheduledThreadPoolExecutor(poolSize, tf);
    this.pool.setRemoveOnCancelPolicy(true);
    this.pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  int drainBatchCap() {
    return drainBatchCap;
  }

  int mailboxChunkSize() {
    return mailboxChunkSize;
  }

  /**
   * Best-effort drain submit; returns {@code true} on success or {@code false} if the underlying
   * pool has been shut down (callers must treat shutdown as terminal).
   */
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

  /**
   * Best-effort schedule; returns {@code null} if the underlying pool has been shut down.
   */
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

  /** Visible for tests. */
  ScheduledThreadPoolExecutor pool() {
    return pool;
  }

  /**
   * Returns the serial executor for the given Raft group id, creating it if absent.
   * @throws IllegalStateException if {@link #close()} has completed
   */
  @NonNull
  public RaftNodeExecutor executorFor(@NonNull Object groupId) {
    final Object gid = requireNonNull(groupId);
    // Fast path: registered groups are looked up without acquiring the
    // lifecycle lock. The lock is only needed to serialize creation against
    // close() so we never insert into the registry after close has snapshotted
    // it for termination.
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
   * Terminates every registered {@link GroupExecutor} (same effect as each Raft node's terminate
   * hook) and shuts down the shared pool.
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
}
