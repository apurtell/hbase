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
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getTerm;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class HigherTermStepDownTest extends BaseTest {
  private LocalRaftGroup group;

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void appendEntriesSuccessResponseHigherTermDemotesLeader() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG)
      .enableNewTermOperation().start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("x")).join();
    int term = getTerm(leader);
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    AppendEntriesSuccessResponse response = leader.getModelFactory()
      .createAppendEntriesSuccessResponseBuilder().setGroupId(leader.getGroupId())
      .setSender(followers.get(0).getLocalEndpoint()).setTerm(term + 1).setLastLogIndex(1L)
      .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    leader.handle(response);
    assertThat(getRole(leader)).isEqualTo(RaftRole.FOLLOWER);
    assertThat(getTerm(leader)).isEqualTo(term + 1);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void installSnapshotResponseHigherTermDemotesLeader() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG)
      .enableNewTermOperation().start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("x")).join();
    int term = getTerm(leader);
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    InstallSnapshotResponse response =
      leader.getModelFactory().createInstallSnapshotResponseBuilder()
        .setGroupId(leader.getGroupId()).setSender(followers.get(0).getLocalEndpoint())
        .setTerm(term + 1).setSnapshotIndex(0L).setRequestedSnapshotChunkIndex(0)
        .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    leader.handle(response);
    assertThat(getRole(leader)).isEqualTo(RaftRole.FOLLOWER);
    assertThat(getTerm(leader)).isEqualTo(term + 1);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void voteResponseHigherTermDemotesLeader() {
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG)
      .enableNewTermOperation().start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("x")).join();
    int term = getTerm(leader);
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    VoteResponse response =
      leader.getModelFactory().createVoteResponseBuilder().setGroupId(leader.getGroupId())
        .setSender(followers.get(0).getLocalEndpoint()).setTerm(term + 1).setGranted(false).build();
    leader.handle(response);
    assertThat(getRole(leader)).isEqualTo(RaftRole.FOLLOWER);
    assertThat(getTerm(leader)).isEqualTo(term + 1);
  }
}
