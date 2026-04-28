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
 * Per-group actor with a two-lane mailbox.
 * <p>
 * Vote / pre-vote / heartbeat / heartbeat-ack handlers must not be head-of-line blocked behind bulk
 * {@code AppendEntriesRequest} payloads. Producers use {@link #executeControl(Runnable)} for
 * control-class tasks. Everything else goes through {@link #execute(Runnable)}. A single drain pass
 * services up to {@code controlBatchCap} control tasks before touching the bulk mailbox. The
 * cap-then-resubmit fairness rule applies to the combined drain pass.
 */
@InterfaceAudience.Private
final class GroupExecutor implements RaftNodeExecutor, RaftNodeLifecycleAware {

  private static final Logger LOG = LoggerFactory.getLogger(GroupExecutor.class);

  /**
   * Sentinel installed in a schedule trampoline's reference cell when the trampoline runs before
   * the producer has stored the {@link ScheduledFuture} returned from the underlying pool.
   * Coordinates the handshake without holding a lock.
   */
  private static final ScheduledFuture<?> TRAMPOLINE_SENTINEL = new SentinelFuture();

  private final MultiGroupExecutor parent;
  private final Object groupId;
  private final MpscUnboundedArrayQueue<Runnable> controlMailbox;
  private final MpscUnboundedArrayQueue<Runnable> bulkMailbox;
  private final AtomicBoolean scheduled = new AtomicBoolean(false);
  private final Set<ScheduledFuture<?>> scheduledFutures = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean terminated = new AtomicBoolean(false);
  private final AtomicBoolean unregisterDone = new AtomicBoolean(false);
  private final Runnable drainRunnable = this::drain;

  /**
   * The MPSC queues require single-consumer semantics for {@code relaxedPoll()} / {@code peek()},
   * so all mailbox reads happen under this lock. Both lanes are read by the same single-threaded
   * drain so they share one lock.
   */
  private final Object drainLock = new Object();

  /** Tasks the actor actually invoked (does not include drops on terminate). */
  private final AtomicInteger executedTaskCount = new AtomicInteger(0);

  /** Tasks discarded because the group terminated before they ran. */
  private final AtomicInteger droppedTaskCount = new AtomicInteger(0);

  GroupExecutor(MultiGroupExecutor parent, Object groupId, int mailboxChunkSize) {
    this.parent = requireNonNull(parent);
    this.groupId = requireNonNull(groupId);
    this.controlMailbox = new MpscUnboundedArrayQueue<>(mailboxChunkSize);
    this.bulkMailbox = new MpscUnboundedArrayQueue<>(mailboxChunkSize);
  }

  @Override
  public void execute(@NonNull Runnable task) {
    enqueue(bulkMailbox, requireNonNull(task), false);
  }

  @Override
  public void executeControl(@NonNull Runnable task) {
    enqueue(controlMailbox, requireNonNull(task), true);
  }

  @Override
  public void submit(@NonNull Runnable task) {
    execute(task);
  }

  private void enqueue(MpscUnboundedArrayQueue<Runnable> mailbox, Runnable task,
    boolean controlLane) {
    if (terminated.get()) {
      droppedTaskCount.incrementAndGet();
      if (LOG.isDebugEnabled()) {
        LOG.debug("{} dropped, group {} terminated", controlLane ? "CONTROL" : "BULK", groupId);
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
  public void schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit timeUnit) {
    scheduleInternal(task, delay, timeUnit, false);
  }

  @Override
  public void scheduleControl(@NonNull Runnable task, long delay, @NonNull TimeUnit timeUnit) {
    scheduleInternal(task, delay, timeUnit, true);
  }

  private void scheduleInternal(@NonNull Runnable task, long delay, @NonNull TimeUnit timeUnit,
    boolean controlLane) {
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
      if (controlLane) {
        executeControl(task);
      } else {
        execute(task);
      }
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
      if (controlLane) {
        executeControl(task);
      } else {
        execute(task);
      }
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
   * Single-threaded drain pass per invocation. Ensures both mailboxes are polled by at most one
   * thread at a time.
   * <p>
   * Drain rule:
   * <ol>
   * <li>Pop up to {@code controlBatchCap} entries from the control mailbox.</li>
   * <li>Pop up to {@code drainBatchCap} entries from the bulk mailbox.</li>
   * <li>Yield back to the parent pool by re-submitting {@code drainRunnable} if either lane has
   * remaining work, preserving the cap-then-resubmit fairness rule.</li>
   * </ol>
   * Per-group serial happens-before across both lanes is preserved by single-threaded drain.
   */
  private void drain() {
    synchronized (drainLock) {
      if (unregisterDone.get()) {
        return;
      }
      if (terminated.get()) {
        discardMailboxes();
        unregisterOnce();
        scheduled.set(false);
        return;
      }

      int controlCap = parent.controlBatchCap();
      int bulkCap = parent.drainBatchCap();
      // Phase 1: control burst.
      drainLane(controlMailbox, controlCap);
      // Phase 2: bulk burst (only if not terminated by control side-effects).
      if (!terminated.get()) {
        drainLane(bulkMailbox, bulkCap);
      }

      if (terminated.get()) {
        discardMailboxes();
        unregisterOnce();
        scheduled.set(false);
        return;
      }

      // Lost-wakeup-safe handoff plus cap-driven yield. Either lane having a remaining head
      // re-arms the drain.
      scheduled.set(false);
      boolean hasMore = controlMailbox.peek() != null || bulkMailbox.peek() != null;
      if ((hasMore || terminated.get()) && scheduled.compareAndSet(false, true)) {
        if (!parent.submitDrain(drainRunnable)) {
          scheduled.set(false);
          if (terminated.get()) {
            discardMailboxes();
            unregisterOnce();
          }
        }
      }
    }
  }

  /**
   * Pops up to {@code cap} tasks from the lane and runs them. Caller holds drainLock.
   * <p>
   * The {@code terminated.get()} check is intentionally evaluated per iteration so that a task that
   * triggers {@link #onRaftNodeTerminate()} (or a concurrent terminator on another thread) stops
   * the rest of the batch from running. Surviving queued tasks are then drained as
   * {@code droppedTaskCount} by {@link #discardMailboxes()} on the trailing edge of
   * {@link #drain()}, which is the contract relied on by tests asserting "no further work runs
   * after terminate".
   */
  private void drainLane(MpscUnboundedArrayQueue<Runnable> mailbox, int cap) {
    int processed = 0;
    while (processed < cap) {
      if (terminated.get()) {
        return;
      }
      Runnable t = mailbox.relaxedPoll();
      if (t == null) {
        break;
      }
      try {
        t.run();
      } catch (Throwable e) {
        LOG.error("group {} task failed", groupId, e);
      } finally {
        executedTaskCount.incrementAndGet();
      }
      processed++;
    }
  }

  private void discardMailboxes() {
    while (controlMailbox.relaxedPoll() != null) {
      droppedTaskCount.incrementAndGet();
    }
    while (bulkMailbox.relaxedPoll() != null) {
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
    return controlMailbox.size() + bulkMailbox.size();
  }

  /** Visible for tests. */
  boolean isTerminated() {
    return terminated.get();
  }
}
