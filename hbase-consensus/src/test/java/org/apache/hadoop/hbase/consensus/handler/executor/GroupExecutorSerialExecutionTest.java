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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class GroupExecutorSerialExecutionTest extends BaseTest {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void perProducerOrderIsPreservedAndTasksDoNotOverlap() throws Exception {
    final int producers = 8;
    final int perProducer = 10_000;
    mge = new MultiGroupExecutor(8, MultiGroupExecutor.DEFAULT_DRAIN_BATCH_CAP, 256);
    RaftNodeExecutor exec = mge.executorFor("g");

    // Per-producer last-seen sequence (tasks for a given producer must run in submission order).
    final int[] lastSeqByProducer = new int[producers];
    for (int i = 0; i < producers; i++) {
      lastSeqByProducer[i] = -1;
    }
    final List<String> orderingFailures = new ArrayList<>();
    // Single-task-at-a-time: an in-flight counter that must never exceed 1.
    final AtomicInteger inFlight = new AtomicInteger(0);
    final AtomicInteger overlap = new AtomicInteger(0);
    final CountDownLatch done = new CountDownLatch(producers * perProducer);

    Thread[] threads = new Thread[producers];
    for (int p = 0; p < producers; p++) {
      final int producerId = p;
      threads[p] = new Thread(() -> {
        for (int s = 0; s < perProducer; s++) {
          final int seq = s;
          exec.execute(() -> {
            int observed = inFlight.incrementAndGet();
            if (observed != 1) {
              overlap.incrementAndGet();
            }
            // Synchronized region is the single-consumer drain critical section, so we can read
            // and update lastSeqByProducer without external locking.
            int prev = lastSeqByProducer[producerId];
            if (seq != prev + 1) {
              orderingFailures
                .add("producer=" + producerId + " expected=" + (prev + 1) + " got=" + seq);
            }
            lastSeqByProducer[producerId] = seq;
            inFlight.decrementAndGet();
            done.countDown();
          });
        }
      });
      threads[p].setName("producer-" + p);
      threads[p].start();
    }
    for (Thread t : threads) {
      t.join();
    }
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    assertThat(overlap.get()).as("concurrent task executions observed").isZero();
    assertThat(orderingFailures).as("per-producer FIFO violations").isEmpty();
    eventually(() -> {
      GroupExecutor ge = (GroupExecutor) exec;
      assertThat(ge.executedTaskCount()).isEqualTo(producers * perProducer);
      assertThat(ge.pendingMailboxSize()).isZero();
    });
  }
}
