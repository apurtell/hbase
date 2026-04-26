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
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.readRaftState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestDefaultHeartbeatScheduler extends TestBase {
  private LocalRaftGroup group;

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testDefaultSchedulerKeepsLeaseAndDrivesReplication() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG)
      .setHeartbeatScheduler(DefaultHeartbeatScheduler.INSTANCE).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("v1")).join();
    leader.replicate(applyValue("v2")).join();
    eventually(() -> {
      for (RaftNodeImpl follower : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
        assertThat(getCommitIndex(follower)).isEqualTo(getCommitIndex(leader));
      }
    });
    assertThat(getRole(leader)).isEqualTo(RaftRole.LEADER);
    long leaseExpiry =
      readRaftState(leader, () -> leader.state().leaderState().leaseExpiryMillis());
    assertThat(leaseExpiry).isGreaterThan(System.currentTimeMillis());
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testUnregisterIsNoOp() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG)
      .setHeartbeatScheduler(DefaultHeartbeatScheduler.INSTANCE).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    assertThatCode(() -> DefaultHeartbeatScheduler.INSTANCE.unregister(leader))
      .doesNotThrowAnyException();
    assertThatCode(() -> DefaultHeartbeatScheduler.INSTANCE.unregister(leader))
      .doesNotThrowAnyException();
  }
}
