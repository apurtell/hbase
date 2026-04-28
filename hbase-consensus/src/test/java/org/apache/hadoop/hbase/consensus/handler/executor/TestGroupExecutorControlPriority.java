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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the head-arrival control-lane latency bound that {@code GroupExecutorFairness.tla}
 * proves. A control task that finds an empty control mailbox at enqueue is dispatched within
 * {@code BulkBatchCap} bulk-task service times, even when the bulk lane is saturated by a sustained
 * bulk producer on a single-thread parent pool.
 * <p>
 * The test instruments the per-task wallclock by busy-waiting in each bulk task so the wait the
 * head-arrival control task observes is dominated by the cap-then-resubmit drain rule.
 */
@Tag(SmallTests.TAG)
public class TestGroupExecutorControlPriority extends TestBase {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  /**
   * Sustained bulk-producer activity, single-thread parent pool, small caps. The test asserts that
   * an injected head-arrival control task lands within fewer than {@code (drainBatchCap + 1)
   * * bulkTaskNanos} wallclock.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testHeadArrivalControlLatencyBoundedByBulkBurst() throws Exception {
    final int controlBatchCap = 4;
    final int drainBatchCap = 8;
    final int mailboxChunkSize = 64;
    final long bulkTaskNanos = TimeUnit.MILLISECONDS.toNanos(2);
    final int bulkBacklog = 512;
    mge = new MultiGroupExecutor(1, drainBatchCap, controlBatchCap, mailboxChunkSize);
    final RaftNodeExecutor exec = mge.executorFor("g");

    final AtomicInteger bulkExecuted = new AtomicInteger();
    final AtomicBoolean stopBulk = new AtomicBoolean(false);
    final CountDownLatch backlogQueued = new CountDownLatch(1);
    final CountDownLatch controlDone = new CountDownLatch(1);
    final long[] controlEnqueueNanos = new long[1];
    final long[] controlServiceNanos = new long[1];
    final int[] controlSeenBulkAtStart = new int[1];

    Thread bulk = new Thread(() -> {
      for (int i = 0; i < bulkBacklog && !stopBulk.get(); i++) {
        exec.execute(() -> {
          long end = System.nanoTime() + bulkTaskNanos;
          while (System.nanoTime() < end) {
          }
          bulkExecuted.incrementAndGet();
        });
      }
      backlogQueued.countDown();
    }, "bulk-producer");
    bulk.start();

    backlogQueued.await(5, TimeUnit.SECONDS);
    while (bulkExecuted.get() < controlBatchCap) {
      Thread.onSpinWait();
    }

    controlEnqueueNanos[0] = System.nanoTime();
    controlSeenBulkAtStart[0] = bulkExecuted.get();
    exec.executeControl(() -> {
      controlServiceNanos[0] = System.nanoTime();
      controlDone.countDown();
    });

    assertThat(controlDone.await(10, TimeUnit.SECONDS)).as("control task must be dispatched")
      .isTrue();
    stopBulk.set(true);
    bulk.join();

    long waitNanos = controlServiceNanos[0] - controlEnqueueNanos[0];
    int bulkServicedDuringWait = bulkExecuted.get() - controlSeenBulkAtStart[0];

    long boundNanos = (long) (drainBatchCap + 1) * bulkTaskNanos;
    assertThat(waitNanos)
      .as("head-arrival control wait %d ns must be <= bulk-burst bound %d ns "
        + "(serviced %d bulk during wait)", waitNanos, boundNanos, bulkServicedDuringWait)
      .isLessThanOrEqualTo(boundNanos);
    assertThat(bulkServicedDuringWait)
      .as("at most one bulk burst (drainBatchCap=%d) may run between control enqueue and service",
        drainBatchCap)
      .isLessThanOrEqualTo(drainBatchCap);
  }

  /**
   * With many control producers feeding the control lane while a deep bulk backlog drains, every
   * control task is serviced before the bulk backlog finishes. The cap-then-resubmit rule prevents
   * bulk from ever monopolizing the worker.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testControlBurstCompletesBeforeBulkBacklogEvenUnderSustainedBulk() throws Exception {
    final int controlBatchCap = 4;
    final int drainBatchCap = 8;
    final int mailboxChunkSize = 64;
    final int bulkBacklog = 4096;
    final int controlInjections = 64;
    final long bulkTaskNanos = TimeUnit.MICROSECONDS.toNanos(50);
    mge = new MultiGroupExecutor(1, drainBatchCap, controlBatchCap, mailboxChunkSize);
    final RaftNodeExecutor exec = mge.executorFor("g");

    final AtomicInteger bulkExecuted = new AtomicInteger();
    final AtomicInteger controlExecuted = new AtomicInteger();
    final CountDownLatch bulkQueued = new CountDownLatch(1);
    final CountDownLatch allControlServiced = new CountDownLatch(controlInjections);

    Thread bulk = new Thread(() -> {
      for (int i = 0; i < bulkBacklog; i++) {
        exec.execute(() -> {
          long end = System.nanoTime() + bulkTaskNanos;
          while (System.nanoTime() < end) {
          }
          bulkExecuted.incrementAndGet();
        });
      }
      bulkQueued.countDown();
    }, "bulk-producer");
    bulk.start();
    bulkQueued.await(5, TimeUnit.SECONDS);

    final CyclicBarrier startBarrier = new CyclicBarrier(2);
    Thread control = new Thread(() -> {
      try {
        startBarrier.await();
      } catch (Exception e) {
        return;
      }
      for (int i = 0; i < controlInjections; i++) {
        exec.executeControl(() -> {
          controlExecuted.incrementAndGet();
          allControlServiced.countDown();
        });
        // Brief pause so the control mailbox typically returns to empty between injections,
        // exercising the head-arrival path repeatedly. With drainBatchCap=8 and bulkTaskNanos=50us
        // a bulk burst is ~400us; pausing ~200us keeps the producer's tasks reliably head-arrival.
        long wait = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(200);
        while (System.nanoTime() < wait) {
        }
      }
    }, "control-producer");
    control.start();
    startBarrier.await();

    assertThat(allControlServiced.await(20, TimeUnit.SECONDS))
      .as("every control task must be serviced under sustained bulk activity").isTrue();
    int bulkAtControlComplete = bulkExecuted.get();
    assertThat(bulkAtControlComplete)
      .as("control lane must finish *while* bulk backlog still has unserviced work, "
        + "i.e. control is not starved by sustained bulk")
      .isLessThan(bulkBacklog);
    assertThat(controlExecuted.get()).isEqualTo(controlInjections);

    control.join();
    bulk.join();
  }
}
