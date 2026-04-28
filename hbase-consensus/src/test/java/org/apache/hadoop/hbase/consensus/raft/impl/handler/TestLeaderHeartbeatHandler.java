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

import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getTerm;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestLeaderHeartbeatHandler extends TestBase {
  private LocalRaftGroup group;

  /**
   * Dispatches the given {@link LeaderHeartbeat} to {@code target} on the per-group control lane,
   * mirroring how the production transport delivers heartbeat envelopes (see
   * {@code LocalTransport.sendBulkHeartbeat}). Acks are discarded.
   */
  private static void deliverHeartbeat(RaftNodeImpl target, LeaderHeartbeat hb) {
    Consumer<LeaderHeartbeatAck> ackSink = ack -> {
    };
    target.getExecutor().executeControl(new LeaderHeartbeatHandler(target, hb, ackSink));
  }

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testHigherTermHeartbeatStepsFollowerDown() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0);
    int currentTerm = getTerm(follower);
    int higherTerm = currentTerm + 5;
    LeaderHeartbeat hb =
      follower.getModelFactory().createLeaderHeartbeatBuilder().setGroupId(follower.getGroupId())
        .setSender(leader.getLocalEndpoint()).setTerm(higherTerm).setCommitIndex(0L).build();
    deliverHeartbeat(follower, hb);
    eventually(() -> assertThat(getTerm(follower)).isEqualTo(higherTerm));
    assertThat(getRole(follower)).isEqualTo(RaftRole.FOLLOWER);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testHeartbeatDoesNotTouchLog() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG)
      .enableNewTermOperation().start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("v1")).join();
    leader.replicate(applyValue("v2")).join();
    RaftNodeImpl follower = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0);
    eventually(() -> assertThat(getCommitIndex(follower)).isEqualTo(getCommitIndex(leader)));
    long preCommitIndex = getCommitIndex(follower);
    long preLastLogOrSnapshot =
      RaftTestUtils.readRaftState(follower, () -> follower.state().log().lastLogOrSnapshotIndex());
    int currentTerm = getTerm(follower);
    LeaderHeartbeat hb = follower.getModelFactory().createLeaderHeartbeatBuilder()
      .setGroupId(follower.getGroupId()).setSender(leader.getLocalEndpoint()).setTerm(currentTerm)
      .setCommitIndex(preCommitIndex).build();
    deliverHeartbeat(follower, hb);
    eventually(() -> assertThat(
      RaftTestUtils.readRaftState(follower, () -> follower.state().log().lastLogOrSnapshotIndex()))
      .isEqualTo(preLastLogOrSnapshot));
    assertThat(getCommitIndex(follower)).isEqualTo(preCommitIndex);
    assertThat(getRole(follower)).isEqualTo(RaftRole.FOLLOWER);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testLeaderReceivingSameTermHeartbeatStepsDown() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int currentTerm = getTerm(leader);
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    LeaderHeartbeat hb = leader.getModelFactory().createLeaderHeartbeatBuilder()
      .setGroupId(leader.getGroupId()).setSender(followers.get(0).getLocalEndpoint())
      .setTerm(currentTerm).setCommitIndex(0L).build();
    deliverHeartbeat(leader, hb);
    eventually(() -> assertThat(getRole(leader)).isEqualTo(RaftRole.FOLLOWER));
  }
}
