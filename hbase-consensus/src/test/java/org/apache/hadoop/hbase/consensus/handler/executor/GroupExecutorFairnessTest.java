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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The drain cap should bound any one group's monopolization of a worker. With one worker and a fat
 * group continuously feeding tasks (each ~10us of busy work), the smaller groups must still
 * complete promptly because the fat group yields back to the pool's FIFO queue every
 * {@code drainBatchCap} tasks.
 */
@Tag(SmallTests.TAG)
public class GroupExecutorFairnessTest extends BaseTest {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void drainCapYieldsWorkerToOtherGroups() throws Exception {
    final int drainCap = 4;
    mge = new MultiGroupExecutor(1, drainCap, 256);
    final RaftNodeExecutor fat = mge.executorFor("fat");
    final int fatTaskCount = 20_000;
    final long fatTaskNanos = 10_000;
    final int smallTaskCountPerGroup = 100;
    final int smallGroupCount = 3;

    final AtomicInteger fatRun = new AtomicInteger();
    final CountDownLatch fatDone = new CountDownLatch(fatTaskCount);
    final CountDownLatch[] smallDone = new CountDownLatch[smallGroupCount];
    final RaftNodeExecutor[] small = new RaftNodeExecutor[smallGroupCount];
    for (int i = 0; i < smallGroupCount; i++) {
      smallDone[i] = new CountDownLatch(smallTaskCountPerGroup);
      small[i] = mge.executorFor("small-" + i);
    }

    final CyclicBarrier startBarrier = new CyclicBarrier(smallGroupCount + 1);

    Thread[] smallThreads = new Thread[smallGroupCount];
    for (int g = 0; g < smallGroupCount; g++) {
      final int gid = g;
      smallThreads[g] = new Thread(() -> {
        try {
          startBarrier.await();
        } catch (Exception e) {
          return;
        }
        for (int s = 0; s < smallTaskCountPerGroup; s++) {
          small[gid].execute(smallDone[gid]::countDown);
        }
      }, "small-producer-" + g);
      smallThreads[g].start();
    }

    Thread fatThread = new Thread(() -> {
      try {
        startBarrier.await();
      } catch (Exception e) {
        return;
      }
      for (int i = 0; i < fatTaskCount; i++) {
        fat.execute(() -> {
          long end = System.nanoTime() + fatTaskNanos;
          while (System.nanoTime() < end) {
            // busy wait so each task occupies the worker long enough to expose unfairness
          }
          fatRun.incrementAndGet();
          fatDone.countDown();
        });
      }
    }, "fat-producer");
    fatThread.start();

    long t0 = System.nanoTime();
    for (int g = 0; g < smallGroupCount; g++) {
      assertThat(smallDone[g].await(20, TimeUnit.SECONDS))
        .as("small group %d must finish quickly", g).isTrue();
      long elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;
      // Total fat work is ~fatTaskCount * fatTaskNanos = 200ms; small groups should finish well
      // inside that window if the cap-and-yield is honored.
      assertThat(elapsedMillis).as("small group %d completed too slowly: %d ms", g, elapsedMillis)
        .isLessThan(2_000);
      assertThat(fatRun.get()).as("fat group must still have work outstanding when small "
        + "group %d completes (drain cap must yield)", g).isLessThan(fatTaskCount);
    }
    assertThat(fatDone.await(60, TimeUnit.SECONDS)).isTrue();
    fatThread.join();
    for (Thread t : smallThreads) {
      t.join();
    }
    assertThat(fatRun.get()).isEqualTo(fatTaskCount);
  }
}
