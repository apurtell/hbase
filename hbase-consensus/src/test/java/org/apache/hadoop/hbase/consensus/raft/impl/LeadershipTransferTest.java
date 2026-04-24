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
package org.apache.hadoop.hbase.consensus.raft.impl;

import static org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode.ADD_LEARNER;
import static org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode.REMOVE_MEMBER;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.FOLLOWER;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getTerm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.exception.CannotReplicateException;
import org.apache.hadoop.hbase.consensus.raft.exception.NotLeaderException;
import org.apache.hadoop.hbase.consensus.raft.exception.RaftException;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class LeadershipTransferTest extends BaseTest {
  private LocalRaftGroup group;

  @AfterEach
  public void destroy() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_leaderTransfersLeadershipToItself_then_leadershipTransferSucceeds() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.transferLeadership(leader.getLocalEndpoint()).join();
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_leaderTransfersLeadershipToNull_then_leadershipTransferFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    try {
      leader.transferLeadership(null);
      fail();
    } catch (NullPointerException ignored) {
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_leaderTransfersLeadershipToNonGroupMemberEndpoint_then_leadershipTransferFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftEndpoint invalidEndpoint = LocalRaftEndpoint.newEndpoint();
    try {
      leader.transferLeadership(invalidEndpoint).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_leaderTransfersLeadershipToNonVotingMember_then_leadershipTransferFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("val")).join();
    RaftNodeImpl newNode = group.createNewNode();
    leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    try {
      leader.transferLeadership(newNode.getLocalEndpoint()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_leadershipTransferTriggeredOnFollower_then_leadershipTransferFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    try {
      follower.transferLeadership(leader.getLocalEndpoint()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(RaftException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_leadershipTransferTriggeredDuringMembershipChange_then_leadershipTransferFails() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodSecs(30)
      .setLeaderHeartbeatTimeoutSecs(30).build();
    group = LocalRaftGroup.start(3, config);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("val")).join();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(),
      AppendEntriesRequest.class);
    leader.changeMembership(followers.get(0).getLocalEndpoint(), REMOVE_MEMBER, 0);
    try {
      leader.transferLeadership(followers.get(0).getLocalEndpoint()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_leadershipTransferTriggeredDuringNoOperationCommitted_then_leadershipTransferSucceeds() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int term1 = getTerm(leader);
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    leader.transferLeadership(follower.getLocalEndpoint()).join();
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    int term2 = getTerm(newLeader);
    assertThat(newLeader).isNotSameAs(leader);
    assertThat(term2).isGreaterThan(term1);
    assertThat(getRole(leader)).isEqualTo(FOLLOWER);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_leadershipTransferTriggeredDuringOperationsCommitted_then_leadershipTransferSucceeds() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int term1 = getTerm(leader);
    leader.replicate(applyValue("val")).join();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    leader.transferLeadership(follower.getLocalEndpoint()).join();
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    int term2 = getTerm(newLeader);
    assertThat(newLeader).isNotSameAs(leader);
    assertThat(term2).isGreaterThan(term1);
    assertThat(getRole(leader)).isEqualTo(FOLLOWER);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_targetEndpointCannotCatchesUpTheLeaderInTime_then_leadershipTransferFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(),
      AppendEntriesRequest.class);
    leader.replicate(applyValue("val")).join();
    try {
      leader.transferLeadership(follower.getLocalEndpoint()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(TimeoutException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_sameLeadershipTransferTriggeredMultipleTimes_then_leadershipTransferSucceeds() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(),
      AppendEntriesRequest.class);
    leader.replicate(applyValue("val")).join();
    CompletableFuture<Ordered<Object>> f1 = leader.transferLeadership(follower.getLocalEndpoint());
    CompletableFuture<Ordered<Object>> f2 = leader.transferLeadership(follower.getLocalEndpoint());
    group.allowAllMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    f1.join();
    f2.join();
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_secondLeadershipTransfersTriggeredForDifferentEndpoint_then_secondLeadershipTransferFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl follower1 = followers.get(0);
    RaftNodeImpl follower2 = followers.get(1);
    group.dropMessagesTo(leader.getLocalEndpoint(), follower1.getLocalEndpoint(),
      AppendEntriesRequest.class);
    leader.replicate(applyValue("val")).join();
    leader.transferLeadership(follower1.getLocalEndpoint());
    try {
      leader.transferLeadership(follower2.getLocalEndpoint()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_newOperationIsReplicatedDuringLeadershipTransfer_then_replicateFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(),
      AppendEntriesRequest.class);
    leader.replicate(applyValue("val")).join();
    leader.transferLeadership(follower.getLocalEndpoint());
    try {
      leader.replicate(applyValue("val")).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(CannotReplicateException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_leadershipTransferCompleted_then_oldLeaderCannotReplicate() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    leader.transferLeadership(follower.getLocalEndpoint()).join();
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    assertThat(newLeader).isNotSameAs(leader);
    try {
      leader.replicate(applyValue("val")).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_leadershipTransferCompleted_then_newLeaderCanCommit() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    leader.transferLeadership(follower.getLocalEndpoint()).join();
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    assertThat(newLeader).isNotSameAs(leader);
    newLeader.replicate(applyValue("val")).join();
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_thereAreInflightOperationsDuringLeadershipTransfer_then_inflightOperationsAreCommitted() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(),
      AppendEntriesRequest.class);
    CompletableFuture<Ordered<Object>> f1 = leader.replicate(applyValue("val"));
    CompletableFuture<Ordered<Object>> f2 =
      leader.transferLeadership(followers.get(0).getLocalEndpoint());
    group.allowAllMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint());
    group.allowAllMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint());
    f2.join();
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    assertThat(newLeader).isNotSameAs(leader);
    f1.join();
  }
}
