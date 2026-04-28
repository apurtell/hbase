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

import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestGroupExecutorLifecycle extends TestBase {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testTerminateClearsMailbox() throws Exception {
    mge = new MultiGroupExecutor(2, MultiGroupExecutor.DRAIN_BATCH_CAP_DEFAULT, 64);
    GroupExecutor a = (GroupExecutor) mge.executorFor("group-a");
    final AtomicInteger ran = new AtomicInteger();
    final CountDownLatch firstStarted = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    // First task pins the worker so subsequent enqueues remain in the mailbox until terminate.
    a.execute(() -> {
      firstStarted.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ran.incrementAndGet();
    });
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

    final int pendingTasks = 5;
    for (int i = 0; i < pendingTasks; i++) {
      a.execute(ran::incrementAndGet);
    }
    final long scheduleDelayMillis = 100;
    final CountDownLatch scheduledRan = new CountDownLatch(1);
    a.schedule(() -> {
      ran.incrementAndGet();
      scheduledRan.countDown();
    }, scheduleDelayMillis, TimeUnit.MILLISECONDS);

    assertThat(mge.activeGroups()).isEqualTo(1);
    ((RaftNodeLifecycleAware) a).onRaftNodeTerminate();
    release.countDown();

    eventually(() -> assertThat(mge.activeGroups()).isZero());
    eventually(() -> assertThat(a.droppedTaskCount()).isEqualTo(pendingTasks));
    // The scheduled task must remain cancelled. Waiting on the latch for at
    // least the original schedule delay catches a regression as soon as it
    // fires while keeping the test fast in the happy path.
    assertThat(scheduledRan.await(scheduleDelayMillis * 5, TimeUnit.MILLISECONDS))
      .as("scheduled task must not run after onRaftNodeTerminate").isFalse();
    assertThat(ran.get()).as("only the first (pinning) task may run after terminate").isEqualTo(1);
    assertThat(a.isTerminated()).isTrue();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testTerminateKeepsPoolAlive() throws Exception {
    mge = new MultiGroupExecutor(2, MultiGroupExecutor.DRAIN_BATCH_CAP_DEFAULT, 64);
    GroupExecutor a = (GroupExecutor) mge.executorFor("group-a");
    GroupExecutor b = (GroupExecutor) mge.executorFor("group-b");
    final CountDownLatch bRan = new CountDownLatch(1);

    ((RaftNodeLifecycleAware) a).onRaftNodeTerminate();
    eventually(() -> assertThat(mge.activeGroups()).isEqualTo(1));

    b.execute(bRan::countDown);
    assertThat(bRan.await(5, TimeUnit.SECONDS))
      .as("parent pool must still execute work after one group terminated").isTrue();
    assertThat(mge.drainPool().isShutdown()).isFalse();
    assertThat(mge.scheduledPool().isShutdown()).isFalse();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testCloseShutsDownPool() throws Exception {
    mge = new MultiGroupExecutor(2, MultiGroupExecutor.DRAIN_BATCH_CAP_DEFAULT, 64);
    RaftNodeExecutor a = mge.executorFor("a");
    final CountDownLatch ran = new CountDownLatch(1);
    a.execute(ran::countDown);
    assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();

    mge.close();
    assertThat(mge.drainPool().isShutdown()).isTrue();
    assertThat(mge.scheduledPool().isShutdown()).isTrue();
    assertThatThrownBy(() -> mge.executorFor("b")).isInstanceOf(IllegalStateException.class);
  }
}
