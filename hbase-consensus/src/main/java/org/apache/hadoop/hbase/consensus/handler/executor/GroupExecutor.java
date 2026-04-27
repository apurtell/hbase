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
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.yetus.audience.InterfaceAudience;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-group actor.
 */
@InterfaceAudience.Private
final class GroupExecutor implements RaftNodeExecutor, RaftNodeLifecycleAware {

  private static final Logger LOG = LoggerFactory.getLogger(GroupExecutor.class);

  /**
   * Sentinel installed in a schedule trampoline's reference cell when the trampoline runs before
   * the producer has stored the {@link ScheduledFuture} returned from the underlyin pool.
   * Coordinates the handshake without holding a lock.
   */
  private static final ScheduledFuture<?> TRAMPOLINE_SENTINEL = new SentinelFuture();

  private final MultiGroupExecutor parent;
  private final Object groupId;
  private final MpscUnboundedArrayQueue<Runnable> mailbox;
  private final AtomicBoolean scheduled = new AtomicBoolean(false);
  private final Set<ScheduledFuture<?>> scheduledFutures = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean terminated = new AtomicBoolean(false);
  private final AtomicBoolean unregisterDone = new AtomicBoolean(false);
  private final Runnable drainRunnable = this::drain;

  /**
   * The JCTools MPSC queue requires single-consumer semantics for {@code relaxedPoll()} /
   * {@code peek()}, so all mailbox reads happen under this lock.
   */
  private final Object drainLock = new Object();

  /** Tasks the actor actually invoked (does not include drops on terminate). */
  private final AtomicInteger executedTaskCount = new AtomicInteger(0);

  /** Tasks discarded because the group terminated before they ran. */
  private final AtomicInteger droppedTaskCount = new AtomicInteger(0);

  GroupExecutor(MultiGroupExecutor parent, Object groupId, int mailboxChunkSize) {
    this.parent = requireNonNull(parent);
    this.groupId = requireNonNull(groupId);
    this.mailbox = new MpscUnboundedArrayQueue<>(mailboxChunkSize);
  }

  @Override
  public void execute(@NonNull Runnable task) {
    requireNonNull(task);
    if (terminated.get()) {
      droppedTaskCount.incrementAndGet();
      if (LOG.isDebugEnabled()) {
        LOG.debug("execute dropped, group {} terminated", groupId);
      }
      return;
    }
    mailbox.offer(task);
    if (scheduled.compareAndSet(false, true)) {
      if (!parent.submitDrain(drainRunnable)) {
        // Pool is shut down; drop the flag so termination paths are not wedged.
        scheduled.set(false);
      }
    }
  }

  @Override
  public void submit(@NonNull Runnable task) {
    execute(task);
  }

  @Override
  public void schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit timeUnit) {
    requireNonNull(task);
    requireNonNull(timeUnit);
    if (terminated.get()) {
      droppedTaskCount.incrementAndGet();
      if (LOG.isDebugEnabled()) {
        LOG.debug("schedule dropped, group {} terminated", groupId);
      }
      return;
    }
    if (delay <= 0L) {
      execute(task);
      return;
    }
    // Lock-free coordination of the producer/trampoline handshake. The
    // trampoline may run on a pool worker before parent.schedule() returns
    // (e.g. very short delay or producer descheduled before publishing the
    // future), so we use an AtomicReference + sentinel:
    // - Producer: CAS(null -> future). On success, future is tracked for
    // cancellation. On failure, the trampoline already ran (it CAS'd in
    // TRAMPOLINE_SENTINEL); nothing to track.
    // - Trampoline: getAndSet(TRAMPOLINE_SENTINEL). If it observes a real future,
    // that means the producer published before the trampoline fired and
    // the trampoline removes it from the cancellation set.
    AtomicReference<ScheduledFuture<?>> ref = new AtomicReference<>();
    Runnable trampoline = () -> {
      ScheduledFuture<?> f = ref.getAndSet(TRAMPOLINE_SENTINEL);
      if (f != null && f != TRAMPOLINE_SENTINEL) {
        scheduledFutures.remove(f);
      }
      execute(task);
    };
    ScheduledFuture<?> f = parent.schedule(trampoline, delay, timeUnit);
    if (f == null) {
      return; // Pool shut down.
    }
    if (ref.compareAndSet(null, f)) {
      scheduledFutures.add(f);
      // Termination may have raced with our add. Ensure the future is canceled if the
      // terminate path has already snapshotted/cleared the future from the set.
      if (terminated.get() && scheduledFutures.remove(f)) {
        f.cancel(false);
      }
    }
  }

  @Override
  public void onRaftNodeTerminate() {
    if (!terminated.compareAndSet(false, true)) {
      return;
    }
    for (ScheduledFuture<?> f : new ArrayList<>(scheduledFutures)) {
      f.cancel(false);
    }
    scheduledFutures.clear();
    // Eagerly evict ourselves from the parent registry. This must happen before the terminating
    // {@link RaftNodeImpl#terminate()} future completes. Value-aware remove inside
    // unregisterOnce() guards against clobbering a fresh replacement.
    unregisterOnce();
    if (scheduled.compareAndSet(false, true)) {
      if (!parent.submitDrain(drainRunnable)) {
        scheduled.set(false);
      }
    }
  }

  /**
   * Single-threaded drain pass per invocation. Ensures {@link #mailbox} is polled by at most one
   * thread at a time. Runs at most {@link MultiGroupExecutor#drainBatchCap} tasks then yields back
   * to the parent pool by re-submitting itself if work remains. The yield is what bounds any one
   * group's share of a worker thread.
   */
  private void drain() {
    synchronized (drainLock) {
      if (unregisterDone.get()) {
        return;
      }
      if (terminated.get()) {
        discardMailbox();
        unregisterOnce();
        scheduled.set(false);
        return;
      }

      int cap = parent.drainBatchCap();
      int processed = 0;
      while (processed < cap && !terminated.get()) {
        Runnable r = mailbox.relaxedPoll();
        if (r == null) {
          break;
        }
        try {
          r.run();
        } catch (Throwable t) {
          LOG.error("group {} task failed", groupId, t);
        } finally {
          executedTaskCount.incrementAndGet();
        }
        processed++;
      }

      if (terminated.get()) {
        discardMailbox();
        unregisterOnce();
        scheduled.set(false);
        return;
      }

      // Lost-wakeup-safe handoff plus cap-driven yield.
      scheduled.set(false);
      boolean hasMore = mailbox.peek() != null;
      if ((hasMore || terminated.get()) && scheduled.compareAndSet(false, true)) {
        if (!parent.submitDrain(drainRunnable)) {
          scheduled.set(false);
          if (terminated.get()) {
            discardMailbox();
            unregisterOnce();
          }
        }
      }
    }
  }

  private void discardMailbox() {
    Runnable r;
    while ((r = mailbox.relaxedPoll()) != null) {
      droppedTaskCount.incrementAndGet();
    }
  }

  private void unregisterOnce() {
    if (unregisterDone.compareAndSet(false, true)) {
      parent.unregister(groupId, this);
    }
  }

  /**
   * Marker {@link ScheduledFuture} used as the "trampoline already ran" sentinel by
   * {@link #schedule}. All API methods throw or return defaults; the value is only ever compared by
   * reference.
   */
  private static final class SentinelFuture implements ScheduledFuture<Object> {
    @Override
    public long getDelay(TimeUnit unit) {
      return 0L;
    }

    @Override
    public int compareTo(Delayed o) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return true;
    }

    @Override
    public Object get() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
      throw new UnsupportedOperationException();
    }
  }

  /** Visible for tests. */
  int executedTaskCount() {
    return executedTaskCount.get();
  }

  /** Visible for tests. */
  int droppedTaskCount() {
    return droppedTaskCount.get();
  }

  /** Visible for tests. */
  int pendingMailboxSize() {
    return mailbox.size();
  }

  /** Visible for tests. */
  Object groupId() {
    return groupId;
  }

  /** Visible for tests. */
  boolean isTerminated() {
    return terminated.get();
  }
}
