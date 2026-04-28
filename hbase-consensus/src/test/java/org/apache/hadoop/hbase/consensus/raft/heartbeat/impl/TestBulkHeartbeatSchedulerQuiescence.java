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

import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end coverage of the leader-side quiescence sweeper inside {@link BulkHeartbeatScheduler}.
 * Drives a real 3-node {@link LocalRaftGroup} with quiescence enabled and a short grace window,
 * then asserts the three observable contracts: (a) an idle caught-up leader transitions to
 * {@code Quiescent} and propagates the flag to followers, (b) a propose drives the leader through
 * {@code wake()} and the group recovers to {@code Quiescent} once the new entry has been applied
 * across the quorum, and (c) a follower whose match index trails the leader's log head holds the
 * leader out of {@code Quiescent} indefinitely.
 */
@Tag(SmallTests.TAG)
public class TestBulkHeartbeatSchedulerQuiescence extends TestBase {

  private static final long QUIESCENCE_GRACE_MS = 200L;

  private static final RaftConfig CONFIG =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(2000).setLeaderHeartbeatPeriodMillis(50)
      .setLeaderHeartbeatTimeoutMillis(1000).setQuiescenceEnabled(true)
      .setQuiescenceGraceMillis(QUIESCENCE_GRACE_MS).build();

  private LocalRaftGroup group;

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
      group = null;
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testIdleLeaderEntersQuiescentAndPropagatesToFollowers() {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());

    eventually(() -> assertThat(leader.state().groupQuiescent())
      .as("leader.groupQuiescent after grace + caught-up gate").isTrue());
    eventually(() -> {
      for (RaftNodeImpl f : followers) {
        assertThat(f.state().groupQuiescent())
          .as("follower %s groupQuiescent after one bulk-heartbeat round-trip",
            f.getLocalEndpoint().getId())
          .isTrue();
      }
    });
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testProposeWakesAndGroupRequiesces() {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();

    eventually(() -> assertThat(leader.state().groupQuiescent()).isTrue());

    leader.replicate(applyValue("x")).join();

    eventually(() -> assertThat(leader.state().groupQuiescent())
      .as("leader returns to Quiescent once followers catch up after the propose").isTrue());
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testLaggingFollowerHoldsLeaderOutOfQuiescent() throws InterruptedException {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl lagging = followers.get(0);

    eventually(() -> assertThat(leader.state().groupQuiescent()).isTrue());

    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), lagging.getLocalEndpoint());
    leader.replicate(applyValue("x")).join();

    await().atMost(QUIESCENCE_GRACE_MS * 5, TimeUnit.MILLISECONDS)
      .untilAsserted(() -> assertThat(leader.state().groupQuiescent())
        .as("leader must not quiesce while %s is lagging at matchIndex < lastLogOrSnapshotIndex",
          lagging.getLocalEndpoint().getId())
        .isFalse());
  }
}
