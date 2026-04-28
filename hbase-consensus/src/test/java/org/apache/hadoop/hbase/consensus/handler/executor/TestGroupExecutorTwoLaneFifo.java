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
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Per-lane FIFO and bounded cross-lane interleaving of the two-lane {@link GroupExecutor} mailbox.
 * Tasks submitted by the same producer through the same lane must be drained in submission order,
 * and the cap-then-resubmit drain rule must not interleave more than {@code controlBatchCap}
 * control tasks before yielding to the bulk lane.
 */
@Tag(SmallTests.TAG)
public class TestGroupExecutorTwoLaneFifo extends TestBase {

  private MultiGroupExecutor mge;

  @AfterEach
  public void tearDown() {
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testPerLaneFifoAcrossProducers() throws Exception {
    final int producers = 6;
    final int perProducerControl = 200;
    final int perProducerBulk = 200;
    final int controlBatchCap = 4;
    final int drainBatchCap = 8;
    mge = new MultiGroupExecutor(2, drainBatchCap, controlBatchCap, 64);
    final RaftNodeExecutor exec = mge.executorFor("g");

    // Per-producer × per-lane last-seen sequence. The drain runs on a single thread per group
    // so we can read/update without external synchronization.
    final int[][] lastControlSeq = new int[producers][];
    final int[][] lastBulkSeq = new int[producers][];
    for (int p = 0; p < producers; p++) {
      lastControlSeq[p] = new int[] { -1 };
      lastBulkSeq[p] = new int[] { -1 };
    }

    final List<String> orderingFailures = new ArrayList<>();
    final CountDownLatch done =
      new CountDownLatch(producers * (perProducerControl + perProducerBulk));

    Thread[] threads = new Thread[producers];
    for (int p = 0; p < producers; p++) {
      final int producerId = p;
      threads[p] = new Thread(() -> {
        for (int i = 0; i < Math.max(perProducerControl, perProducerBulk); i++) {
          if (i < perProducerControl) {
            final int seq = i;
            exec.executeControl(() -> {
              int prev = lastControlSeq[producerId][0];
              if (seq != prev + 1) {
                synchronized (orderingFailures) {
                  orderingFailures.add(
                    "CONTROL producer=" + producerId + " expected=" + (prev + 1) + " got=" + seq);
                }
              }
              lastControlSeq[producerId][0] = seq;
              done.countDown();
            });
          }
          if (i < perProducerBulk) {
            final int seq = i;
            exec.execute(() -> {
              int prev = lastBulkSeq[producerId][0];
              if (seq != prev + 1) {
                synchronized (orderingFailures) {
                  orderingFailures
                    .add("BULK producer=" + producerId + " expected=" + (prev + 1) + " got=" + seq);
                }
              }
              lastBulkSeq[producerId][0] = seq;
              done.countDown();
            });
          }
        }
      }, "producer-" + p);
      threads[p].start();
    }
    for (Thread t : threads) {
      t.join();
    }
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(orderingFailures).as("per-lane FIFO violations").isEmpty();
    eventually(() -> {
      GroupExecutor ge = (GroupExecutor) exec;
      assertThat(ge.executedTaskCount())
        .isEqualTo(producers * (perProducerControl + perProducerBulk));
      assertThat(ge.pendingMailboxSize()).isZero();
    });
  }

  /**
   * Bounded cross-lane interleaving: while BOTH lanes have unserviced work, no run of
   * consecutively-dispatched same-lane tasks may exceed that lane's batch cap. After one lane fully
   * drains the cap on the still-busy lane no longer applies (cap-then-resubmit only yields across
   * non-empty lanes).
   * <p>
   * The test arranges for both lanes to be deep at drain start by submitting a single "blocker"
   * control task that holds the drain inside its first task long enough for the producer to
   * pre-queue a large mixed workload behind it. After the blocker releases, the drain performs its
   * cap-then-resubmit passes against full lanes; the recorded dispatch order is walked to verify
   * per-pass caps held over every run except the final tail (the lane that exhausts last).
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testCapThenResubmitInterleaving() throws Exception {
    final int controlBatchCap = 3;
    final int drainBatchCap = 5;
    final int mixedTotal = 4_000;
    mge = new MultiGroupExecutor(1, drainBatchCap, controlBatchCap, 64);
    final RaftNodeExecutor exec = mge.executorFor("g");

    final CountDownLatch blockerActive = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    exec.executeControl(() -> {
      blockerActive.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("blocker timed out waiting for release");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    assertThat(blockerActive.await(5, TimeUnit.SECONDS))
      .as("blocker control task must reach the drain before pre-queueing").isTrue();

    final List<String> dispatchOrder = new ArrayList<>(mixedTotal);
    final CountDownLatch done = new CountDownLatch(mixedTotal);
    int totalControl = 0;
    int totalBulk = 0;
    for (int i = 0; i < mixedTotal; i++) {
      final int idx = i;
      if ((i & 1) == 0) {
        exec.executeControl(() -> {
          dispatchOrder.add("C-" + idx);
          done.countDown();
        });
        totalControl++;
      } else {
        exec.execute(() -> {
          dispatchOrder.add("B-" + idx);
          done.countDown();
        });
        totalBulk++;
      }
    }

    release.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS))
      .as("all mixed tasks must complete after the blocker releases").isTrue();
    assertThat(dispatchOrder).hasSize(mixedTotal);

    // Walk dispatchOrder, splitting into runs at every lane change. A run is "interesting" iff
    // the lane it just yielded TO had outstanding pending work at the moment of the lane change
    // (i.e., the cap-then-resubmit rule actually had to enforce yield). The final tail run does
    // not yield to the other lane, so it is excluded by construction.
    int controlMaxWhileBulkPending = 0;
    int bulkMaxWhileControlPending = 0;
    int controlServicedSoFar = 0;
    int bulkServicedSoFar = 0;
    int currentRunLen = 0;
    char currentRunLane = 0;
    for (String dispatch : dispatchOrder) {
      char lane = dispatch.charAt(0);
      if (currentRunLane == 0) {
        currentRunLane = lane;
        currentRunLen = 1;
      } else if (lane == currentRunLane) {
        currentRunLen++;
      } else {
        // Run just ended (lane changed). The other lane (the one we yielded TO) clearly had
        // pending work right now (we are about to dispatch from it). So the run was a real
        // cap-then-resubmit yield and the cap must hold.
        if (currentRunLane == 'C') {
          controlMaxWhileBulkPending = Math.max(controlMaxWhileBulkPending, currentRunLen);
        } else {
          bulkMaxWhileControlPending = Math.max(bulkMaxWhileControlPending, currentRunLen);
        }
        currentRunLane = lane;
        currentRunLen = 1;
      }
      if (lane == 'C') {
        controlServicedSoFar++;
      } else {
        bulkServicedSoFar++;
      }
    }

    assertThat(controlMaxWhileBulkPending)
      .as("max consecutive control tasks while bulk lane still had pending work; "
        + "controlBatchCap=%d", controlBatchCap)
      .isLessThanOrEqualTo(controlBatchCap);
    assertThat(bulkMaxWhileControlPending).as(
      "max consecutive bulk tasks while control lane still had pending work; " + "drainBatchCap=%d",
      drainBatchCap).isLessThanOrEqualTo(drainBatchCap);

    // Per-lane FIFO across the single mixed producer: idx values appear in increasing order
    // within each lane.
    int lastControlIdx = -1;
    int lastBulkIdx = -1;
    final List<String> orderViolations = new ArrayList<>();
    for (String dispatch : dispatchOrder) {
      int idx = Integer.parseInt(dispatch.substring(2));
      if (dispatch.charAt(0) == 'C') {
        if (idx <= lastControlIdx) {
          orderViolations.add("control out-of-order: prev=" + lastControlIdx + " got=" + idx);
        }
        lastControlIdx = idx;
      } else {
        if (idx <= lastBulkIdx) {
          orderViolations.add("bulk out-of-order: prev=" + lastBulkIdx + " got=" + idx);
        }
        lastBulkIdx = idx;
      }
    }
    assertThat(orderViolations).as("per-lane FIFO across the mixed producer").isEmpty();
    assertThat(controlServicedSoFar).isEqualTo(totalControl);
    assertThat(bulkServicedSoFar).isEqualTo(totalBulk);
  }
}
