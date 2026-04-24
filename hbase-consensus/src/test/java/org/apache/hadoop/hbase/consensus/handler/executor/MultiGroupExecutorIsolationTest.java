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
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class MultiGroupExecutorIsolationTest extends BaseTest {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void slowGroupDoesNotBlockOtherGroup() throws Exception {
    mge = new MultiGroupExecutor(4, MultiGroupExecutor.DEFAULT_DRAIN_BATCH_CAP, 64);
    RaftNodeExecutor a = mge.executorFor("group-a");
    RaftNodeExecutor b = mge.executorFor("group-b");

    final long blockMillis = 2_000;
    final int bTasks = 100;
    final CountDownLatch aStarted = new CountDownLatch(1);
    final CountDownLatch bDone = new CountDownLatch(bTasks);

    a.execute(() -> {
      aStarted.countDown();
      try {
        Thread.sleep(blockMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

    long t0 = System.nanoTime();
    for (int i = 0; i < bTasks; i++) {
      b.execute(bDone::countDown);
    }
    assertThat(bDone.await(blockMillis - 200, TimeUnit.MILLISECONDS))
      .as("group-b must not be blocked by group-a's sleeping task").isTrue();
    long elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;
    assertThat(elapsedMillis).isLessThan(blockMillis);
  }
}
