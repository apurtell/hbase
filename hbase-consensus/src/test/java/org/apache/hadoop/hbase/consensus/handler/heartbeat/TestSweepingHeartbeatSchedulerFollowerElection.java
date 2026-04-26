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

import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies that under {@link SweepingHeartbeatScheduler}, an isolated leader is detected by the
 * remaining followers within roughly {@code 2 * leaderHeartbeatTimeoutMillis} and a new leader is
 * elected from the surviving majority.
 */
@Tag(SmallTests.TAG)
public class TestSweepingHeartbeatSchedulerFollowerElection extends TestBase {
  private static final RaftConfig CONFIG =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(1_000)
      .setLeaderHeartbeatPeriodMillis(500).setLeaderHeartbeatTimeoutMillis(2_000).build();

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
  public void testIsolatedLeaderTriggersReElection() {
    group =
      LocalRaftGroup.newBuilder(3).setConfig(CONFIG).setHeartbeatSchedulerFactory(endpoint -> {
        SweepingHeartbeatScheduler s = new SweepingHeartbeatScheduler(50, 1);
        s.start();
        synchronized (schedulers) {
          schedulers.add(s);
        }
        return s;
      }).start();

    RaftNodeImpl initialLeader = group.waitUntilLeaderElected();
    RaftEndpoint isolated = initialLeader.getLocalEndpoint();
    List<RaftNodeImpl> survivors = group.getNodesExcept(isolated);

    group.splitMembers(isolated);

    long deadline =
      System.currentTimeMillis() + 2 * CONFIG.getLeaderHeartbeatTimeoutMillis() + 2_000L;

    eventually(() -> {
      RaftEndpoint newLeader = null;
      int leaderTerm = 0;
      for (RaftNodeImpl n : survivors) {
        var term = n.getTerm();
        assertThat(term).isNotNull();
        assertThat(term.getLeaderEndpoint())
          .as("survivor %s sees no leader yet", n.getLocalEndpoint().getId()).isNotNull();
        if (newLeader == null) {
          newLeader = term.getLeaderEndpoint();
          leaderTerm = term.getTerm();
        } else {
          assertThat(term.getLeaderEndpoint()).isEqualTo(newLeader);
          assertThat(term.getTerm()).isEqualTo(leaderTerm);
        }
      }
      assertThat(newLeader).isNotEqualTo(isolated);
    }, Math.max(5L, (deadline - System.currentTimeMillis()) / 1_000));
  }
}
