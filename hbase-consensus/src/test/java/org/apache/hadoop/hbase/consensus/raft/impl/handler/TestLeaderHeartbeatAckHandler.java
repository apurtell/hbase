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
package org.apache.hadoop.hbase.consensus.raft.impl.handler;

import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getTerm;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.readRaftState;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.impl.state.FollowerState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestLeaderHeartbeatAckHandler extends TestBase {
  private LocalRaftGroup group;

  /**
   * Dispatches the given {@link LeaderHeartbeatAck} to {@code target} on the per-group control
   * lane, mirroring how the production transport delivers heartbeat ack envelopes (see
   * {@code LocalTransport.sendBulkHeartbeatAck}).
   */
  private static void deliverAck(RaftNodeImpl target, LeaderHeartbeatAck ack) {
    target.getExecutor().executeControl(new LeaderHeartbeatAckHandler(target, ack));
  }

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testHigherTermAckStepsLeaderDown() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int currentTerm = getTerm(leader);
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    LeaderHeartbeatAck ack =
      leader.getModelFactory().createLeaderHeartbeatAckBuilder().setGroupId(leader.getGroupId())
        .setSender(followers.get(0).getLocalEndpoint()).setTerm(currentTerm + 5).build();
    deliverAck(leader, ack);
    eventually(() -> assertThat(getTerm(leader)).isEqualTo(currentTerm + 5));
    assertThat(getRole(leader)).isEqualTo(RaftRole.FOLLOWER);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testVotingAckRefreshesLeaseAndFollowerTimestamp() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int currentTerm = getTerm(leader);
    RaftEndpoint followerEndpoint =
      group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0).getLocalEndpoint();
    long preTs = readRaftState(leader, () -> {
      LeaderState ls = leader.state().leaderState();
      FollowerState fs = ls.getFollowerStateOrNull(followerEndpoint);
      return fs == null ? 0L : fs.responseTimestamp();
    });
    long preLeaseExpiry =
      readRaftState(leader, () -> leader.state().leaderState().leaseExpiryMillis());
    LeaderHeartbeatAck ack = leader.getModelFactory().createLeaderHeartbeatAckBuilder()
      .setGroupId(leader.getGroupId()).setSender(followerEndpoint).setTerm(currentTerm).build();
    deliverAck(leader, ack);
    eventually(() -> {
      long postTs = readRaftState(leader, () -> {
        LeaderState ls = leader.state().leaderState();
        FollowerState fs = ls.getFollowerStateOrNull(followerEndpoint);
        return fs == null ? 0L : fs.responseTimestamp();
      });
      assertThat(postTs).isGreaterThanOrEqualTo(preTs);
      long postLeaseExpiry =
        readRaftState(leader, () -> leader.state().leaderState().leaseExpiryMillis());
      assertThat(postLeaseExpiry).isGreaterThanOrEqualTo(preLeaseExpiry);
    });
    assertThat(getRole(leader)).isEqualTo(RaftRole.LEADER);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testAckFromUnknownPeerIsIgnored() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int currentTerm = getTerm(leader);
    RaftEndpoint unknown = LocalRaftEndpoint.newEndpoint();
    LeaderHeartbeatAck ack = leader.getModelFactory().createLeaderHeartbeatAckBuilder()
      .setGroupId(leader.getGroupId()).setSender(unknown).setTerm(currentTerm).build();
    deliverAck(leader, ack);
    assertThat(getRole(leader)).isEqualTo(RaftRole.LEADER);
    assertThat(getTerm(leader)).isEqualTo(currentTerm);
  }
}
