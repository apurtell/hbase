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
package org.apache.hadoop.hbase.consensus.handler.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Steady-state behavior of {@link SweepingHeartbeatScheduler}: a 3-node Raft group whose heartbeats
 * are driven exclusively by the sweeping scheduler must keep the leader's lease fresh well within
 * the configured {@code leaderHeartbeatTimeoutMillis} window.
 */
@Tag(SmallTests.TAG)
public class TestSweepingHeartbeatSchedulerLeaderBroadcast extends TestBase {
  private LocalRaftGroup group;
  private final List<SweepingHeartbeatScheduler> schedulers = new ArrayList<>();

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
    for (SweepingHeartbeatScheduler s : schedulers) {
      s.close();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testLeaseStaysFreshUnderSweepingHeartbeats() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG)
      .setHeartbeatSchedulerFactory(endpoint -> {
        SweepingHeartbeatScheduler s = new SweepingHeartbeatScheduler(50, 1);
        s.start();
        synchronized (schedulers) {
          schedulers.add(s);
        }
        return s;
      }).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();

    long initialLease =
      RaftTestUtils.readRaftState(leader, () -> leader.state().leaderState().leaseExpiryMillis());
    assertThat(initialLease).isGreaterThan(0L);

    Waiter.waitFor(HBaseConfiguration.create(), 30_000L, 50L,
      () -> RaftTestUtils.readRaftState(leader,
        () -> leader.state().leaderState().leaseExpiryMillis()) > initialLease);

    long laterLease =
      RaftTestUtils.readRaftState(leader, () -> leader.state().leaderState().leaseExpiryMillis());
    assertThat(laterLease).as("lease must advance under sweeping heartbeats")
      .isGreaterThan(initialLease);

    long now = EnvironmentEdgeManager.currentTime();
    assertThat(laterLease - now).as("lease must remain in the future")
      .isGreaterThan(RaftTestUtils.TEST_RAFT_CONFIG.getLeaderHeartbeatTimeoutMillis() / 2);
  }
}
