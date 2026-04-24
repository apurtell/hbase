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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Stress the producer/scheduled-flag handoff: many producers, single drain worker, low drain cap,
 * many iterations. Any lost wakeup leaves tasks orphaned in the mailbox and the eventual-assertion
 * fails.
 */
@Tag(SmallTests.TAG)
public class GroupExecutorLostWakeupTest extends BaseTest {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  public void noLostWakeupUnderHeavyContention() throws Exception {
    final int iterations = 5;
    final int producers = 16;
    final int perProducer = 50_000;
    for (int it = 0; it < iterations; it++) {
      mge = new MultiGroupExecutor(1, 8, 256);
      RaftNodeExecutor exec = mge.executorFor("g");
      final AtomicInteger counter = new AtomicInteger();

      Thread[] threads = new Thread[producers];
      for (int p = 0; p < producers; p++) {
        threads[p] = new Thread(() -> {
          for (int s = 0; s < perProducer; s++) {
            exec.execute(counter::incrementAndGet);
          }
        });
        threads[p].setName("producer-" + p);
        threads[p].start();
      }
      for (Thread t : threads) {
        t.join();
      }
      final int expected = producers * perProducer;
      eventually(() -> {
        assertThat(counter.get()).isEqualTo(expected);
        assertThat(((GroupExecutor) exec).pendingMailboxSize()).isZero();
      });
      mge.close();
      mge = null;
    }
  }
}
