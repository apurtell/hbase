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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Validates that the bulk lane is not starved by sustained control-producer activity. The
 * cap-then-resubmit drain rule yields back to the bulk lane after every {@code controlBatchCap}
 * control tasks, so an injected bulk task lands within a bounded number of subsequent control
 * bursts even when the control lane is constantly fed.
 */
@Tag(SmallTests.TAG)
public class TestGroupExecutorTwoLaneFairness extends TestBase {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  /**
   * Single bulk task injected during a sustained control burst. Verifies the wait is bounded by the
   * cap-then-resubmit rule's symmetric bulk-lane head-arrival bound. At most one full control burst
   * (plus a small tolerance) before the bulk task runs. Asserts both that the bulk task eventually
   * runs and that the wait is short.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testBulkLaneNotStarvedBySustainedControl() throws Exception {
    final int controlBatchCap = 4;
    final int drainBatchCap = 8;
    final int mailboxChunkSize = 64;
    final long controlTaskNanos = TimeUnit.MILLISECONDS.toNanos(1);
    mge = new MultiGroupExecutor(1, drainBatchCap, controlBatchCap, mailboxChunkSize);
    final RaftNodeExecutor exec = mge.executorFor("g");

    final AtomicInteger controlExecuted = new AtomicInteger();
    final AtomicBoolean stopControl = new AtomicBoolean(false);
    final CountDownLatch bulkServiced = new CountDownLatch(1);
    final long[] bulkEnqueueNanos = new long[1];
    final long[] bulkServiceNanos = new long[1];
    final int[] controlSeenAtEnqueue = new int[1];
    final int[] controlSeenAtService = new int[1];

    Thread control = new Thread(() -> {
      while (!stopControl.get()) {
        exec.executeControl(() -> {
          long end = System.nanoTime() + controlTaskNanos;
          while (System.nanoTime() < end) {
          }
          controlExecuted.incrementAndGet();
        });
        // Submit at a controlled rate so the lane stays continuously busy without unbounded
        // backlog growth.
        long pause = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(100);
        while (System.nanoTime() < pause) {
        }
      }
    }, "control-producer");
    control.start();

    // Wait for control to be steadily busy, then inject one bulk task.
    while (controlExecuted.get() < 4 * controlBatchCap) {
      Thread.onSpinWait();
    }
    bulkEnqueueNanos[0] = System.nanoTime();
    controlSeenAtEnqueue[0] = controlExecuted.get();
    exec.execute(() -> {
      bulkServiceNanos[0] = System.nanoTime();
      controlSeenAtService[0] = controlExecuted.get();
      bulkServiced.countDown();
    });

    assertThat(bulkServiced.await(10, TimeUnit.SECONDS))
      .as("bulk task must eventually run despite sustained control producer").isTrue();
    stopControl.set(true);
    control.join();

    long waitNanos = bulkServiceNanos[0] - bulkEnqueueNanos[0];
    int controlServicedDuringWait = controlSeenAtService[0] - controlSeenAtEnqueue[0];

    // A head-arrival bulk task waits at most controlBatchCap × max(per-control-task wallclock). We
    // pad with a small constant for scheduler / GC noise.
    long boundNanos = (long) (controlBatchCap + 4) * controlTaskNanos;
    assertThat(waitNanos).as(
      "head-arrival bulk wait %d ns must be <= control-burst bound %d ns "
        + "(serviced %d control tasks during wait)",
      waitNanos, boundNanos, controlServicedDuringWait).isLessThanOrEqualTo(boundNanos);
    // Even a small constant slack is allowed for races and the in-flight task at the moment the
    // bulk task enqueues, but the count cannot exceed two full control bursts.
    assertThat(controlServicedDuringWait)
      .as("at most a couple of control bursts (controlBatchCap=%d) "
        + "may run between bulk enqueue and service", controlBatchCap)
      .isLessThanOrEqualTo(2 * controlBatchCap + 2);
  }

  /** Many bulk tasks injected against a sustained control producer all complete in bounded time. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testBulkLaneDrainsFullyUnderSustainedControl() throws Exception {
    final int controlBatchCap = 4;
    final int drainBatchCap = 8;
    final int mailboxChunkSize = 64;
    final int bulkInjections = 256;
    mge = new MultiGroupExecutor(1, drainBatchCap, controlBatchCap, mailboxChunkSize);
    final RaftNodeExecutor exec = mge.executorFor("g");

    final AtomicInteger controlExecuted = new AtomicInteger();
    final AtomicBoolean stopControl = new AtomicBoolean(false);
    final CountDownLatch bulkAllDone = new CountDownLatch(bulkInjections);

    Thread control = new Thread(() -> {
      while (!stopControl.get()) {
        exec.executeControl(controlExecuted::incrementAndGet);
      }
    }, "control-producer");
    control.start();

    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
    if (Thread.interrupted()) {
      Thread.currentThread().interrupt();
    }

    Thread bulk = new Thread(() -> {
      for (int i = 0; i < bulkInjections; i++) {
        exec.execute(bulkAllDone::countDown);
        // Pace bulk producer below control producer so control activity is always sustained.
        long pause = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(50);
        while (System.nanoTime() < pause) {
        }
      }
    }, "bulk-producer");
    bulk.start();

    assertThat(bulkAllDone.await(20, TimeUnit.SECONDS))
      .as("all %d bulk tasks must drain even under sustained control activity", bulkInjections)
      .isTrue();
    stopControl.set(true);
    control.join();
    bulk.join();

    // If controlExecuted is 0 the producer never got CPU and the test is meaningless.
    assertThat(controlExecuted.get())
      .as("control lane must have been actively producing throughout the test").isPositive();
  }
}
