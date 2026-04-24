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
import static org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER;
import static org.apache.hadoop.hbase.consensus.raft.QueryPolicy.LINEARIZABLE;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.FOLLOWER;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.queryLastValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getLeaderQuerySequenceNumber;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getSnapshotEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.hadoop.hbase.consensus.raft.exception.CannotReplicateException;
import org.apache.hadoop.hbase.consensus.raft.exception.LaggingCommitIndexException;
import org.apache.hadoop.hbase.consensus.raft.exception.NotLeaderException;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.apache.hadoop.hbase.consensus.raft.report.RaftGroupMembers;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class LinearizableQueryTest extends BaseTest {
  private LocalRaftGroup group;

  private void startGroup(int groupSize, RaftConfig config) {
    group = LocalRaftGroup.newBuilder(groupSize).setConfig(config).enableNewTermOperation().start();
  }

  @AfterEach
  public void destroy() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_linearizableQueryIsIssuedWithoutCommitIndex_then_itReadsLastState() {
    startGroup(5, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    long commitIndex1 = getCommitIndex(leader);
    Ordered<Object> o1 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty()).join();
    assertThat(o1.getResult()).isEqualTo("value1");
    assertThat(o1.getCommitIndex()).isEqualTo(commitIndex1);
    long leaderQuerySeqNum1 = getLeaderQuerySequenceNumber(leader);
    assertThat(leaderQuerySeqNum1).isGreaterThan(0);
    assertThat(getCommitIndex(leader)).isEqualTo(commitIndex1);
    leader.replicate(applyValue("value2")).join();
    long commitIndex2 = getCommitIndex(leader);
    Ordered<Object> o2 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty()).join();
    assertThat(o2.getResult()).isEqualTo("value2");
    assertThat(o2.getCommitIndex()).isEqualTo(commitIndex2);
    long leaderQuerySeqNum2 = getLeaderQuerySequenceNumber(leader);
    assertThat(leaderQuerySeqNum2).isEqualTo(leaderQuerySeqNum1 + 1);
    assertThat(getCommitIndex(leader)).isEqualTo(commitIndex2);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_linearizableQueryIsIssuedWithCommitIndex_then_itReadsLastState() {
    startGroup(5, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    long commitIndex1 = getCommitIndex(leader);
    Ordered<Object> o1 = leader
      .query(queryLastValue(), LINEARIZABLE, Optional.of(commitIndex1), Optional.empty()).join();
    assertThat(o1.getResult()).isEqualTo("value1");
    assertThat(o1.getCommitIndex()).isEqualTo(commitIndex1);
    long leaderQuerySeqNum1 = getLeaderQuerySequenceNumber(leader);
    assertThat(leaderQuerySeqNum1).isGreaterThan(0);
    assertThat(getCommitIndex(leader)).isEqualTo(commitIndex1);
    leader.replicate(applyValue("value2")).join();
    long commitIndex2 = getCommitIndex(leader);
    Ordered<Object> o2 = leader
      .query(queryLastValue(), LINEARIZABLE, Optional.of(commitIndex2), Optional.empty()).join();
    assertThat(o2.getResult()).isEqualTo("value2");
    assertThat(o2.getCommitIndex()).isEqualTo(commitIndex2);
    long leaderQuerySeqNum2 = getLeaderQuerySequenceNumber(leader);
    assertThat(leaderQuerySeqNum2).isEqualTo(leaderQuerySeqNum1 + 1);
    assertThat(getCommitIndex(leader)).isEqualTo(commitIndex2);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_linearizableQueryIsIssuedWithFurtherCommitIndex_then_itFails() {
    startGroup(5, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    long commitIndex = getCommitIndex(leader);
    try {
      leader.query(queryLastValue(), LINEARIZABLE, Optional.of(commitIndex + 1), Optional.empty())
        .join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_newCommitIsDoneWhileThereIsWaitingQuery_then_queryRunsAfterNewCommit() {
    startGroup(5, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(2).getLocalEndpoint(),
      AppendEntriesRequest.class);
    CompletableFuture<Ordered<Object>> replicateFuture = leader.replicate(applyValue("value2"));
    CompletableFuture<Ordered<Object>> queryFuture =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    group.resetAllRulesFrom(leader.getLocalEndpoint());
    Ordered<Object> replicationResult = replicateFuture.join();
    Ordered<Object> queryResult = queryFuture.join();
    assertThat(queryResult.getResult()).isEqualTo("value2");
    assertThat(queryResult.getCommitIndex()).isEqualTo(replicationResult.getCommitIndex());
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_multipleQueriesAreIssuedBeforeHeartbeatAcksReceived_then_allQueriesExecutedAtOnce() {
    startGroup(5, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(2).getLocalEndpoint(),
      AppendEntriesRequest.class);
    CompletableFuture<Ordered<Object>> queryFuture1 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    CompletableFuture<Ordered<Object>> queryFuture2 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    group.resetAllRulesFrom(leader.getLocalEndpoint());
    long commitIndex = getCommitIndex(leader);
    Ordered<Object> result1 = queryFuture1.join();
    Ordered<Object> result2 = queryFuture2.join();
    assertThat(result1.getResult()).isEqualTo("value1");
    assertThat(result2.getResult()).isEqualTo("value1");
    assertThat(result1.getCommitIndex()).isEqualTo(commitIndex);
    assertThat(result2.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_newCommitsAreDoneWhileThereAreMultipleQueries_then_allQueriesRunAfterCommits() {
    startGroup(5, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(2).getLocalEndpoint(),
      AppendEntriesRequest.class);
    CompletableFuture<Ordered<Object>> replicateFuture1 = leader.replicate(applyValue("value2"));
    CompletableFuture<Ordered<Object>> replicateFuture2 = leader.replicate(applyValue("value3"));
    CompletableFuture<Ordered<Object>> queryFuture1 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    CompletableFuture<Ordered<Object>> queryFuture2 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    group.resetAllRulesFrom(leader.getLocalEndpoint());
    replicateFuture1.join().getCommitIndex();
    long commitIndex = replicateFuture2.join().getCommitIndex();
    Ordered<Object> queryResult1 = queryFuture1.join();
    Ordered<Object> queryResult2 = queryFuture2.join();
    assertThat(queryResult1.getResult()).isEqualTo("value3");
    assertThat(queryResult2.getResult()).isEqualTo("value3");
    assertThat(queryResult1.getCommitIndex()).isEqualTo(commitIndex);
    assertThat(queryResult2.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_linearizableQueryIsIssuedToFollower_then_queryFails() {
    startGroup(5, TEST_RAFT_CONFIG);
    RaftNode leader = group.waitUntilLeaderElected();
    try {
      group.getAnyNodeExcept(leader.getLocalEndpoint())
        .query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_multipleQueryLimitIsReachedBeforeHeartbeatAcks_then_noNewQueryIsAccepted() {
    RaftConfig config = RaftConfig.newBuilder().setMaxPendingLogEntryCount(1).build();
    startGroup(5, config);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    eventually(() -> assertThat(getCommitIndex(leader)).isEqualTo(1));
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(2).getLocalEndpoint(),
      AppendEntriesRequest.class);
    CompletableFuture<Ordered<Object>> queryFuture1 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    CompletableFuture<Ordered<Object>> queryFuture2 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    try {
      queryFuture2.join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(CannotReplicateException.class);
    }
    group.resetAllRulesFrom(leader.getLocalEndpoint());
    queryFuture1.join();
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_leaderDemotesToFollowerWhileThereIsOngoingQuery_then_queryFails() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(5000).build();
    startGroup(3, config);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    group.splitMembers(leader.getLocalEndpoint());
    CompletableFuture<Ordered<Object>> queryFuture =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    eventually(() -> assertThat(getRole(leader)).isEqualTo(FOLLOWER));
    try {
      queryFuture.join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_followersInstallSnapshot_then_queryIsExecutedOnLeaderWithInstallSnapshotResponse() {
    RaftConfig config = RaftConfig.newBuilder()
      .setLeaderElectionTimeoutMillis(TEST_RAFT_CONFIG.getLeaderElectionTimeoutMillis())
      .setLeaderHeartbeatPeriodMillis(TEST_RAFT_CONFIG.getLeaderHeartbeatPeriodMillis())
      .setLeaderHeartbeatTimeoutMillis(TEST_RAFT_CONFIG.getLeaderHeartbeatTimeoutMillis())
      .setCommitCountToTakeSnapshot(100).build();
    startGroup(3, config);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(),
      InstallSnapshotRequest.class);
    int i = 0;
    while (getSnapshotEntry(leader).getIndex() == 0) {
      leader.replicate(applyValue("val" + (++i))).join();
    }
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint(),
      InstallSnapshotRequest.class);
    CompletableFuture<Ordered<Object>> f =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    eventually(() -> assertThat(getLeaderQuerySequenceNumber(leader)).isEqualTo(1));
    group.allowAllMessagesTo(leader.getLocalEndpoint(), followers.get(0).getLocalEndpoint());
    group.allowAllMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint());
    Ordered<Object> result = f.join();
    assertThat(result.getResult()).isEqualTo("val" + i);
  }

  @Test
  public void
    when_followerFallsBehind_then_queryIsExecutedOnLeaderWithAppendEntriesFailureResponse() {
    startGroup(2, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    int count = 10;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + (i + 1))).join();
    }
    RaftNodeImpl newFollower = group.createNewNode();
    group.dropMessagesTo(leader.getLocalEndpoint(), newFollower.getLocalEndpoint(),
      AppendEntriesRequest.class);
    group.dropMessagesTo(newFollower.getLocalEndpoint(), leader.getLocalEndpoint(),
      AppendEntriesSuccessResponse.class);
    group.dropMessagesTo(newFollower.getLocalEndpoint(), leader.getLocalEndpoint(),
      AppendEntriesFailureResponse.class);
    leader.changeMembership(newFollower.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();
    group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(),
      AppendEntriesRequest.class);
    CompletableFuture<Ordered<Object>> f =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    group.allowMessagesTo(newFollower.getLocalEndpoint(), leader.getLocalEndpoint(),
      AppendEntriesFailureResponse.class);
    group.allowMessagesTo(leader.getLocalEndpoint(), newFollower.getLocalEndpoint(),
      AppendEntriesRequest.class);
    f.join();
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_leaderLeavesRaftGroupWhileThereIsOngoingQuery_then_queryFails() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(5000).build();
    startGroup(3, config);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    for (RaftNodeImpl follower : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
      group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(),
        AppendEntriesRequest.class);
    }
    // Start the linearizable query before membership removal so it is waiting on the
    // leader's query path when the REMOVE_MEMBER entry commits (avoids racing on UPDATING).
    CompletableFuture<Ordered<Object>> queryFuture =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    CompletableFuture<Ordered<RaftGroupMembers>> membershipChangeFuture =
      leader.changeMembership(leader.getLocalEndpoint(), MembershipChangeMode.REMOVE_MEMBER, 0);
    for (RaftNodeImpl follower : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
      group.allowAllMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    }
    membershipChangeFuture.join();
    try {
      queryFuture.join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void
    when_newCommitsAreDoneWhileThereAreMembershipChangeAndMultipleQueries_then_allQueriesRunAfterCommits() {
    startGroup(5, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    RaftNodeImpl learner = group.createNewNode();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    for (RaftNodeImpl follower : followers) {
      group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(),
        AppendEntriesRequest.class);
      group.dropMessagesTo(follower.getLocalEndpoint(), leader.getLocalEndpoint(),
        AppendEntriesSuccessResponse.class);
      group.dropMessagesTo(follower.getLocalEndpoint(), leader.getLocalEndpoint(),
        AppendEntriesFailureResponse.class);
    }
    leader.changeMembership(learner.getLocalEndpoint(), ADD_LEARNER, 0);
    eventually(() -> {
      assertThat(leader.getStatus()).isEqualTo(RaftNodeStatus.UPDATING_RAFT_GROUP_MEMBER_LIST);
    });
    CompletableFuture<Ordered<Object>> replicateFuture1 = leader.replicate(applyValue("value2"));
    CompletableFuture<Ordered<Object>> replicateFuture2 = leader.replicate(applyValue("value3"));
    CompletableFuture<Ordered<Object>> queryFuture1 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    CompletableFuture<Ordered<Object>> queryFuture2 =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    group.resetAllRulesFrom(leader.getLocalEndpoint());
    for (RaftNodeImpl follower : followers) {
      group.resetAllRulesFrom(follower.getLocalEndpoint());
    }
    replicateFuture1.join().getCommitIndex();
    long commitIndex = replicateFuture2.join().getCommitIndex();
    Ordered<Object> queryResult1 = queryFuture1.join();
    Ordered<Object> queryResult2 = queryFuture2.join();
    assertThat(queryResult1.getResult()).isEqualTo("value3");
    assertThat(queryResult2.getResult()).isEqualTo("value3");
    assertThat(queryResult1.getCommitIndex()).isEqualTo(commitIndex);
    assertThat(queryResult2.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void when_learnerPresentInRaftGroup_then_queryQuorumIgnoresLearner() {
    startGroup(3, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    RaftNodeImpl learner = group.createNewNode();
    leader.changeMembership(learner.getLocalEndpoint(), ADD_LEARNER, 0).join();
    List<RaftNodeImpl> otherNodes = group.getNodesExcept(leader.getLocalEndpoint());
    for (RaftNodeImpl node : otherNodes) {
      if (node != learner) {
        group.dropMessagesTo(leader.getLocalEndpoint(), node.getLocalEndpoint(),
          AppendEntriesRequest.class);
        group.dropMessagesTo(node.getLocalEndpoint(), leader.getLocalEndpoint(),
          AppendEntriesSuccessResponse.class);
        group.dropMessagesTo(node.getLocalEndpoint(), leader.getLocalEndpoint(),
          AppendEntriesFailureResponse.class);
      }
    }
    CompletableFuture<Ordered<Object>> queryFuture =
      leader.query(queryLastValue(), LINEARIZABLE, Optional.empty(), Optional.empty());
    try {
      queryFuture.join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
    }
  }
}
