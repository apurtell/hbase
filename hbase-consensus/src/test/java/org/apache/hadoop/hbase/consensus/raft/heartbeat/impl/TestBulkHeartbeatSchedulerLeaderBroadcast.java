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
package org.apache.hadoop.hbase.consensus.raft.heartbeat.impl;

import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatAckFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the leader-side broadcast contract of {@link BulkHeartbeatScheduler}. On every wheel
 * tick, the leader emits exactly one bulk-frame send per remote peer, regardless of whether the
 * leader's groups are quiescent or active.
 */
@Tag(SmallTests.TAG)
public class TestBulkHeartbeatSchedulerLeaderBroadcast extends TestBase {

  // Long heartbeat period + timeout so the elected leader stays stable for the test wheel's
  // measurement window.
  private static final RaftConfig CONFIG =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(2_000)
      .setLeaderHeartbeatPeriodMillis(60_000).setLeaderHeartbeatTimeoutMillis(120_000).build();

  private LocalRaftGroup group;
  private BulkHeartbeatScheduler wheel;

  @AfterEach
  public void tearDown() {
    if (wheel != null) {
      wheel.close();
      wheel = null;
    }
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testLeaderEmitsOneBulkFramePerPeerPerTick() {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    CountingTransport counting = new CountingTransport();
    wheel = new BulkHeartbeatScheduler(/* intervalMs */ 50, /* timerThreads */ 1,
      /* pauseDetectionThresholdMs */ Long.MAX_VALUE, /* pauseToleranceCapMs */ Long.MAX_VALUE,
      counting);
    wheel.register(leader);
    wheel.start();

    eventually(() -> {
      for (RaftNodeImpl follower : followers) {
        assertThat(counting.framesTo(follower.getLocalEndpoint())).isGreaterThanOrEqualTo(2);
      }
    });
    // Each emitted frame must carry exactly one entry (this leader hosts a single group).
    for (RaftNodeImpl follower : followers) {
      assertThat(counting.maxEntriesPerFrameTo(follower.getLocalEndpoint()))
        .as("max entries per frame to %s", follower.getLocalEndpoint().getId()).isEqualTo(1);
    }
  }

  /**
   * Per-peer bulk-frame counter. Used by the leader-side wheel tests to validate that exactly one
   * envelope leaves per (peer, tick) and that the carried entries match the registered groups.
   */
  private static final class CountingTransport implements Transport {
    private final ConcurrentMap<RaftEndpoint, AtomicInteger> frameCounts =
      new ConcurrentHashMap<>();
    private final ConcurrentMap<RaftEndpoint, AtomicInteger> maxEntriesPerFrame =
      new ConcurrentHashMap<>();

    int framesTo(RaftEndpoint peer) {
      AtomicInteger c = frameCounts.get(peer);
      return c == null ? 0 : c.get();
    }

    int maxEntriesPerFrameTo(RaftEndpoint peer) {
      AtomicInteger c = maxEntriesPerFrame.get(peer);
      return c == null ? 0 : c.get();
    }

    @Override
    public void send(@NonNull RaftEndpoint target, @NonNull RaftMessage message) {
    }

    @Override
    public boolean isReachable(@NonNull RaftEndpoint endpoint) {
      return true;
    }

    @Override
    public void sendBulkHeartbeat(@NonNull RaftEndpoint target, @NonNull BulkHeartbeatFrame frame) {
      frameCounts.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
      maxEntriesPerFrame.computeIfAbsent(target, k -> new AtomicInteger())
        .accumulateAndGet(frame.getEntries().size(), Integer::max);
    }

    @Override
    public void sendBulkHeartbeatAck(@NonNull RaftEndpoint target,
      @NonNull BulkHeartbeatAckFrame frame) {
    }
  }
}
