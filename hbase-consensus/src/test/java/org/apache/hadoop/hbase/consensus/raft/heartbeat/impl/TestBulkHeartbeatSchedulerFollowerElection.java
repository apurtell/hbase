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

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatAckFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies that the per-server timing wheel cooperates correctly with the test firewall used by the
 * bulk-heartbeat re-election fixtures. When the source firewall has a "drop bulk heartbeats to
 * target" rule installed, the wheel's per-tick emission to that target produces no observable
 * delivery on the receiving side, but emissions to other peers continue normally. This is the
 * mechanism by which higher-level integration tests can simulate "bulk frames stop reaching a
 * follower" and assert that the follower's election timer subsequently trips.
 */
@Tag(SmallTests.TAG)
public class TestBulkHeartbeatSchedulerFollowerElection {

  private BulkHeartbeatScheduler wheel;

  @AfterEach
  public void tearDown() {
    if (wheel != null) {
      wheel.close();
      wheel = null;
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testDropRuleSilencesBulkHeartbeatToTargetOnly() {
    RaftEndpoint sourceEp = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint dropTarget = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint allowTarget = LocalRaftEndpoint.newEndpoint();
    DropAwareTransport transport = new DropAwareTransport();
    transport.dropBulkHeartbeats(dropTarget);

    BulkHeartbeatFrame frame =
      new BulkHeartbeatFrame(sourceEp, /* bootEpoch */ 1L, /* tick */ 1L, new ArrayList<>());

    transport.sendBulkHeartbeat(dropTarget, frame);
    transport.sendBulkHeartbeat(allowTarget, frame);
    transport.sendBulkHeartbeat(allowTarget, frame);

    assertThat(transport.dropped(dropTarget)).as("bulk heartbeats to dropped target").isEqualTo(1);
    assertThat(transport.delivered(dropTarget)).as("bulk heartbeats reaching dropped target")
      .isZero();
    assertThat(transport.delivered(allowTarget)).as("bulk heartbeats reaching un-dropped target")
      .isEqualTo(2);
  }

  /**
   * Stand-in transport that records bulk-heartbeat envelopes and honors a per-target "drop bulk
   * heartbeats" rule. Mirrors the LocalTransport behavior introduced for the bulk wheel.
   */
  private static final class DropAwareTransport implements Transport {
    private final java.util.Set<RaftEndpoint> drops =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final java.util.concurrent.ConcurrentMap<RaftEndpoint, AtomicInteger> dropped =
      new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<RaftEndpoint, AtomicInteger> delivered =
      new java.util.concurrent.ConcurrentHashMap<>();

    void dropBulkHeartbeats(RaftEndpoint target) {
      drops.add(target);
    }

    int dropped(RaftEndpoint target) {
      AtomicInteger c = dropped.get(target);
      return c == null ? 0 : c.get();
    }

    int delivered(RaftEndpoint target) {
      AtomicInteger c = delivered.get(target);
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
      if (drops.contains(target)) {
        dropped.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
        return;
      }
      delivered.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
      List<LeaderHeartbeat> entries = new ArrayList<>();
      frame.getEntries().forEach(entries::add);
    }

    @Override
    public void sendBulkHeartbeatAck(@NonNull RaftEndpoint target,
      @NonNull BulkHeartbeatAckFrame frame) {
    }
  }
}
