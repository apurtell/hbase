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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class GroupExecutorScheduleTest extends BaseTest {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void scheduledTaskFiresAfterDelayAndDoesNotOverlapInlineTasks() throws Exception {
    mge = new MultiGroupExecutor(2, MultiGroupExecutor.DEFAULT_DRAIN_BATCH_CAP, 64);
    RaftNodeExecutor exec = mge.executorFor("g");

    final long delayMillis = 100;
    final long tStartNanos = System.nanoTime();
    final AtomicLong scheduledFiredAtNanos = new AtomicLong();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger overlap = new AtomicInteger();
    final CountDownLatch scheduledDone = new CountDownLatch(1);
    final CountDownLatch inlineDone = new CountDownLatch(50);

    exec.schedule(() -> {
      if (inFlight.incrementAndGet() != 1) {
        overlap.incrementAndGet();
      }
      scheduledFiredAtNanos.set(System.nanoTime());
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      inFlight.decrementAndGet();
      scheduledDone.countDown();
    }, delayMillis, TimeUnit.MILLISECONDS);

    for (int i = 0; i < 50; i++) {
      exec.execute(() -> {
        if (inFlight.incrementAndGet() != 1) {
          overlap.incrementAndGet();
        }
        inFlight.decrementAndGet();
        inlineDone.countDown();
      });
    }

    assertThat(scheduledDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(inlineDone.await(5, TimeUnit.SECONDS)).isTrue();
    long firedAfterMillis = (scheduledFiredAtNanos.get() - tStartNanos) / 1_000_000L;
    assertThat(firedAfterMillis).isGreaterThanOrEqualTo(delayMillis);
    assertThat(overlap.get())
      .as("scheduled task must serialize with inline tasks via the trampoline").isZero();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void terminateCancelsPendingScheduledTask() throws Exception {
    mge = new MultiGroupExecutor(2, MultiGroupExecutor.DEFAULT_DRAIN_BATCH_CAP, 64);
    GroupExecutor exec = (GroupExecutor) mge.executorFor("g");
    final long delayMillis = 200;
    final CountDownLatch ran = new CountDownLatch(1);
    exec.schedule(ran::countDown, delayMillis, TimeUnit.MILLISECONDS);
    ((RaftNodeLifecycleAware) exec).onRaftNodeTerminate();
    // Latch await returns false if the task never counts down within the
    // window. The window must be longer than the schedule delay; if the
    // cancellation regresses, the latch fires immediately and the assertion
    // surfaces the failure without waiting the full window.
    assertThat(ran.await(delayMillis * 5, TimeUnit.MILLISECONDS))
      .as("scheduled task must not run after onRaftNodeTerminate").isFalse();
    assertThat(exec.isTerminated()).isTrue();
  }
}
