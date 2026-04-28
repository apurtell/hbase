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
import static org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode.REMOVE_MEMBER;
import static org.apache.hadoop.hbase.consensus.raft.QueryPolicy.EVENTUAL_CONSISTENCY;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup.IN_MEMORY_RAFT_STATE_STORE_FACTORY;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.queryLastValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.allTheTime;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getEffectiveGroupMembers;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getLastLogOrSnapshotEntry;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRaftStore;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRestoredState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.QueryPolicy;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine;
import org.apache.hadoop.hbase.consensus.raft.model.log.BaseLogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.consensus.raft.report.RaftGroupMembers;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestSingletonRaftGroup extends TestBase {
  private LocalRaftGroup group;

  @AfterEach
  public void destroy() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderElected() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(3000).build();
    group = LocalRaftGroup.start(1, config);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    assertThat(leader).isNotNull();
    assertThat(leader.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint());
    assertThat(leader.getTerm().getTerm()).isGreaterThan(0);
    List<RaftNodeReport> reports = group.getRaftNodeReports(leader.getLocalEndpoint());
    assertThat(reports).hasSize(3);
    assertThat(reports.get(0).getRole()).isEqualTo(RaftRole.FOLLOWER);
    assertThat(reports.get(0).getLeaderHeartbeatTimestamp()).isEqualTo(0L);
    assertThat(reports.get(0).getQuorumHeartbeatTimestamp()).isEqualTo(0L);
    assertThat(reports.get(0).getHeartbeatTimestamps()).isEmpty();
    assertThat(reports.get(1).getRole()).isEqualTo(RaftRole.CANDIDATE);
    assertThat(reports.get(1).getLeaderHeartbeatTimestamp()).isEqualTo(0L);
    assertThat(reports.get(1).getQuorumHeartbeatTimestamp()).isEqualTo(0L);
    assertThat(reports.get(1).getHeartbeatTimestamps()).isEmpty();
    assertThat(reports.get(2).getRole()).isEqualTo(RaftRole.LEADER);
    assertThat(reports.get(2).getLeaderHeartbeatTimestamp()).isEqualTo(0L);
    assertThat(reports.get(2).getQuorumHeartbeatTimestamp()).isGreaterThan(0L);
    assertThat(reports.get(2).getHeartbeatTimestamps()).isEmpty();
    long quorumTimestamp = reports.get(2).getQuorumHeartbeatTimestamp();
    assertThat(quorumTimestamp).isGreaterThan(0).isLessThan(Long.MAX_VALUE);
    allTheTime(() -> assertThat(leader.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint()),
      2 * TimeUnit.MILLISECONDS.toSeconds(config.getLeaderHeartbeatTimeoutMillis()));
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testCommitOneEntry() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    Object val = group.getStateMachine(leader.getLocalEndpoint()).get(result.getCommitIndex());
    assertThat(val).isEqualTo(expectedVal);
    long quorumTimestamp = leader.getReport().join().getResult().getQuorumHeartbeatTimestamp();
    assertThat(quorumTimestamp).isGreaterThan(0).isLessThan(Long.MAX_VALUE);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testCommitMultipleEntries() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<Entry<CompletableFuture<Ordered<Object>>, String>> futures = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      String expectedVal = "val" + i;
      CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal));
      futures.add(new SimpleEntry<>(future, expectedVal));
    }
    SimpleStateMachine stateMachine = group.getStateMachine(leader.getLocalEndpoint());
    for (Entry<CompletableFuture<Ordered<Object>>, String> e : futures) {
      Ordered<Object> result = e.getKey().join();
      Object val = stateMachine.get(result.getCommitIndex());
      assertThat(val).isEqualTo(e.getValue());
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testCommitOneEntryWithStore() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    Object val = group.getStateMachine(leader.getLocalEndpoint()).get(result.getCommitIndex());
    assertThat(val).isEqualTo(expectedVal);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testCommitMultipleEntriesWithStore() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<Entry<CompletableFuture<Ordered<Object>>, String>> futures = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      String expectedVal = "val" + i;
      CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal));
      futures.add(new SimpleEntry<>(future, expectedVal));
    }
    SimpleStateMachine stateMachine = group.getStateMachine(leader.getLocalEndpoint());
    for (Entry<CompletableFuture<Ordered<Object>>, String> e : futures) {
      Ordered<Object> result = e.getKey().join();
      Object val = stateMachine.get(result.getCommitIndex());
      assertThat(val).isEqualTo(e.getValue());
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testAddLearnerKeepsQuorum() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .doesNotContain(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);
    eventually(() -> {
      Object val = group.getStateMachine(newNode.getLocalEndpoint()).get(result.getCommitIndex());
      assertThat(val).isEqualTo(expectedVal);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testAddFollowerUpdatesQuorum() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(2);
    eventually(() -> {
      Object val = group.getStateMachine(newNode.getLocalEndpoint()).get(result.getCommitIndex());
      assertThat(val).isEqualTo(expectedVal);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testPromoteLearnerUpdatesQuorum() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult1 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    Ordered<RaftGroupMembers> membershipChangeResult2 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
        membershipChangeResult1.getCommitIndex()).join();
    assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult2.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);
    eventually(() -> {
      Object val = group.getStateMachine(newNode.getLocalEndpoint()).get(result.getCommitIndex());
      assertThat(val).isEqualTo(expectedVal);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testAddLearnerKeepsQuorumWithStore() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .doesNotContain(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);
    eventually(() -> {
      Object val = group.getStateMachine(newNode.getLocalEndpoint()).get(result.getCommitIndex());
      assertThat(val).isEqualTo(expectedVal);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testAddFollowerUpdatesQuorumWithStore() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(2);
    eventually(() -> {
      Object val = group.getStateMachine(newNode.getLocalEndpoint()).get(result.getCommitIndex());
      assertThat(val).isEqualTo(expectedVal);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerPromotionWithStoreIncreasesQuorum() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult1 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    Ordered<RaftGroupMembers> membershipChangeResult2 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
        membershipChangeResult1.getCommitIndex()).join();
    assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult2.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);
    eventually(() -> {
      Object val = group.getStateMachine(newNode.getLocalEndpoint()).get(result.getCommitIndex());
      assertThat(val).isEqualTo(expectedVal);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testAddLearnerCommitsEntry() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .doesNotContain(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);
    String expectedVal2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();
    eventually(() -> {
      Object val1 = group.getStateMachine(newNode.getLocalEndpoint()).get(result1.getCommitIndex());
      assertThat(val1).isEqualTo(expectedVal1);
      Object val2 = group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex());
      assertThat(val2).isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testAddFollowerCommitsEntry() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(2);
    String expectedVal2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();
    eventually(() -> {
      Object val1 = group.getStateMachine(newNode.getLocalEndpoint()).get(result1.getCommitIndex());
      assertThat(val1).isEqualTo(expectedVal1);
      Object val2 = group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex());
      assertThat(val2).isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testPromoteLearnerCommitsEntry() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult1 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    Ordered<RaftGroupMembers> membershipChangeResult2 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
        membershipChangeResult1.getCommitIndex()).join();
    assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult2.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);
    String expectedVal2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();
    eventually(() -> {
      Object val1 = group.getStateMachine(newNode.getLocalEndpoint()).get(result1.getCommitIndex());
      assertThat(val1).isEqualTo(expectedVal1);
      Object val2 = group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex());
      assertThat(val2).isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testAddLearnerCommitsEntryWithStore() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .doesNotContain(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);
    String expectedVal2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();
    eventually(() -> {
      Object val1 = group.getStateMachine(newNode.getLocalEndpoint()).get(result1.getCommitIndex());
      assertThat(val1).isEqualTo(expectedVal1);
      Object val2 = group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex());
      assertThat(val2).isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testAddFollowerCommitsEntryWithStore() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(2);
    String expectedVal2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();
    eventually(() -> {
      Object val1 = group.getStateMachine(newNode.getLocalEndpoint()).get(result1.getCommitIndex());
      assertThat(val1).isEqualTo(expectedVal1);
      Object val2 = group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex());
      assertThat(val2).isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerPromotionWithStoreCommitsNewEntry() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    Ordered<Object> result1 = leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult1 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    Ordered<RaftGroupMembers> membershipChangeResult2 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
        membershipChangeResult1.getCommitIndex()).join();
    assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult2.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);
    String expectedVal2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();
    eventually(() -> {
      Object val1 = group.getStateMachine(newNode.getLocalEndpoint()).get(result1.getCommitIndex());
      assertThat(val1).isEqualTo(expectedVal1);
      Object val2 = group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex());
      assertThat(val2).isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerAddCommitsViaLeaderOnly() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .doesNotContain(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint());
    String expectedVal2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();
    group.allowAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint());
    eventually(() -> {
      assertThat(getCommitIndex(newNode)).isEqualTo(result2.getCommitIndex());
      assertThat(group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex()))
        .isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerPromotionRequiresQuorumNotJustLeader() {
    group = LocalRaftGroup.start(1);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult1 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    Ordered<RaftGroupMembers> membershipChangeResult2 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
        membershipChangeResult1.getCommitIndex()).join();
    assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult2.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint());
    String expectedVal2 = "val2";
    CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal2));
    allTheTime(
      () -> assertThat(getCommitIndex(leader)).isEqualTo(membershipChangeResult2.getCommitIndex()),
      3);
    group.allowAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint());
    Ordered<Object> result2 = future.join();
    eventually(() -> {
      assertThat(getCommitIndex(leader)).isEqualTo(result2.getCommitIndex());
      assertThat(getCommitIndex(newNode)).isEqualTo(result2.getCommitIndex());
      assertThat(group.getStateMachine(leader.getLocalEndpoint()).get(result2.getCommitIndex()))
        .isEqualTo(expectedVal2);
      assertThat(group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex()))
        .isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerAddWithStoreCommitsViaLeaderOnly() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    assertThat(membershipChangeResult.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getVotingMembers())
      .doesNotContain(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult.getResult().getMajorityQuorumSize()).isEqualTo(1);
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint());
    String expectedVal2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(expectedVal2)).join();
    group.allowAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint());
    eventually(() -> {
      assertThat(getCommitIndex(newNode)).isEqualTo(result2.getCommitIndex());
      assertThat(group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex()))
        .isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerPromotionWithStoreRequiresQuorum() {
    group =
      LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal1 = "val1";
    leader.replicate(applyValue(expectedVal1)).join();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult1 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    Ordered<RaftGroupMembers> membershipChangeResult2 =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
        membershipChangeResult1.getCommitIndex()).join();
    assertThat(membershipChangeResult2.getResult().getMembers().size()).isEqualTo(2);
    assertThat(membershipChangeResult2.getResult().getMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getVotingMembers())
      .contains(newNode.getLocalEndpoint());
    assertThat(membershipChangeResult2.getResult().getMajorityQuorumSize()).isEqualTo(2);
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint());
    String expectedVal2 = "val2";
    CompletableFuture<Ordered<Object>> future = leader.replicate(applyValue(expectedVal2));
    allTheTime(
      () -> assertThat(getCommitIndex(leader)).isEqualTo(membershipChangeResult2.getCommitIndex()),
      3);
    group.allowAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newNode.getLocalEndpoint());
    Ordered<Object> result2 = future.join();
    eventually(() -> {
      assertThat(getCommitIndex(leader)).isEqualTo(result2.getCommitIndex());
      assertThat(getCommitIndex(newNode)).isEqualTo(result2.getCommitIndex());
      assertThat(group.getStateMachine(leader.getLocalEndpoint()).get(result2.getCommitIndex()))
        .isEqualTo(expectedVal2);
      assertThat(group.getStateMachine(newNode.getLocalEndpoint()).get(result2.getCommitIndex()))
        .isEqualTo(expectedVal2);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLinearizableQuery() {
    group = LocalRaftGroup.start(1);
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String val = "val";
    Ordered<Object> result = leader.replicate(applyValue(val)).join();
    Ordered<Object> queryResult =
      leader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0L, 0L).join();
    assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
    assertThat(queryResult.getResult()).isEqualTo(val);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderLeaseQuery() {
    group = LocalRaftGroup.start(1);
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String val = "val";
    Ordered<Object> result = leader.replicate(applyValue(val)).join();
    Ordered<Object> queryResult =
      leader.query(queryLastValue(), QueryPolicy.LEADER_LEASE, 0L, 0L).join();
    assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
    assertThat(queryResult.getResult()).isEqualTo(val);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testEventualQuery() {
    group = LocalRaftGroup.start(1);
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String expectedVal = "val";
    Ordered<Object> result = leader.replicate(applyValue(expectedVal)).join();
    Ordered<Object> queryResult =
      leader.query(queryLastValue(), EVENTUAL_CONSISTENCY, 0L, 0L).join();
    assertThat(queryResult.getCommitIndex()).isEqualTo(result.getCommitIndex());
    assertThat(queryResult.getResult()).isEqualTo(expectedVal);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testRestartElectsLeader() {
    group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
      .enableNewTermOperation().build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String val = "val";
    Ordered<Object> result = leader.replicate(applyValue(val)).join();
    int term = leader.getTerm().getTerm();
    RestoredRaftState restoredState = getRestoredState(leader);
    RaftStore raftStore = getRaftStore(leader);
    group.terminateNode(leader.getLocalEndpoint());
    RaftNodeImpl restoredNode = group.restoreNode(restoredState, raftStore);
    eventually(() -> {
      assertThat(restoredNode.getLeaderEndpoint()).isEqualTo(restoredNode.getLocalEndpoint());
      int newTerm = restoredNode.getTerm().getTerm();
      assertThat(newTerm).isGreaterThan(term);
      BaseLogEntry entry = getLastLogOrSnapshotEntry(restoredNode);
      assertThat(entry.getTerm()).isEqualTo(newTerm);
      long commitIndex = getCommitIndex(restoredNode);
      assertThat(entry.getIndex()).isEqualTo(commitIndex);
    });
    Object queryResult =
      restoredNode.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0L, 0L).join().getResult();
    assertThat(queryResult).isEqualTo(val);
    assertThat(group.getStateMachine(restoredNode.getLocalEndpoint()).get(result.getCommitIndex()))
      .isEqualTo(val);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testRestartAfterLearnerAddedElectsNewLeader() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(3000).build();
    group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
      .enableNewTermOperation().setConfig(config).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String val = "val";
    Ordered<Object> result = leader.replicate(applyValue(val)).join();
    int term = leader.getTerm().getTerm();
    RaftNodeImpl newNode = group.createNewNode();
    leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    RestoredRaftState restoredState = getRestoredState(leader);
    RaftStore raftStore = getRaftStore(leader);
    group.terminateNode(leader.getLocalEndpoint());
    RaftNodeImpl restoredNode = group.restoreNode(restoredState, raftStore);
    group.waitUntilLeaderElected();
    eventually(() -> {
      for (RaftNodeImpl node : Arrays.asList(newNode, restoredNode)) {
        int newTerm = node.getTerm().getTerm();
        assertThat(newTerm).isGreaterThan(term);
        BaseLogEntry entry = getLastLogOrSnapshotEntry(node);
        assertThat(entry.getTerm()).isEqualTo(newTerm);
        long commitIndex = getCommitIndex(node);
        assertThat(entry.getIndex()).isEqualTo(commitIndex);
      }
    });
    Object queryResult = restoredNode
      .query(queryLastValue(), QueryPolicy.EVENTUAL_CONSISTENCY, 0L, 0L).join().getResult();
    assertThat(queryResult).isEqualTo(val);
    assertThat(group.getStateMachine(restoredNode.getLocalEndpoint()).get(result.getCommitIndex()))
      .isEqualTo(val);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testRestartAfterFollowerAddedElectsNewLeader() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(3000).build();
    group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
      .enableNewTermOperation().setConfig(config).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String val = "val";
    Ordered<Object> result = leader.replicate(applyValue(val)).join();
    int term = leader.getTerm().getTerm();
    RaftNodeImpl newNode = group.createNewNode();
    leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0).join();
    RestoredRaftState restoredState = getRestoredState(leader);
    RaftStore raftStore = getRaftStore(leader);
    group.terminateNode(leader.getLocalEndpoint());
    RaftNodeImpl restoredNode = group.restoreNode(restoredState, raftStore);
    group.waitUntilLeaderElected();
    eventually(() -> {
      for (RaftNodeImpl node : Arrays.asList(newNode, restoredNode)) {
        int newTerm = node.getTerm().getTerm();
        assertThat(newTerm).isGreaterThan(term);
        BaseLogEntry entry = getLastLogOrSnapshotEntry(node);
        assertThat(entry.getTerm()).isEqualTo(newTerm);
        long commitIndex = getCommitIndex(node);
        assertThat(entry.getIndex()).isEqualTo(commitIndex);
      }
    });
    Object queryResult = restoredNode
      .query(queryLastValue(), QueryPolicy.EVENTUAL_CONSISTENCY, 0L, 0L).join().getResult();
    assertThat(queryResult).isEqualTo(val);
    assertThat(group.getStateMachine(restoredNode.getLocalEndpoint()).get(result.getCommitIndex()))
      .isEqualTo(val);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testRestartAfterExpandElectsLeader() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(3000).build();
    group = LocalRaftGroup.newBuilder(1).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
      .enableNewTermOperation().setConfig(config).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    String val = "val";
    Ordered<Object> result = leader.replicate(applyValue(val)).join();
    int term = leader.getTerm().getTerm();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
      membershipChangeResult.getCommitIndex()).join();
    RestoredRaftState restoredState = getRestoredState(leader);
    RaftStore raftStore = getRaftStore(leader);
    group.terminateNode(leader.getLocalEndpoint());
    RaftNodeImpl restoredNode = group.restoreNode(restoredState, raftStore);
    group.waitUntilLeaderElected();
    eventually(() -> {
      for (RaftNodeImpl node : Arrays.asList(newNode, restoredNode)) {
        int newTerm = node.getTerm().getTerm();
        assertThat(newTerm).isGreaterThan(term);
        BaseLogEntry entry = getLastLogOrSnapshotEntry(node);
        assertThat(entry.getTerm()).isEqualTo(newTerm);
        long commitIndex = getCommitIndex(node);
        assertThat(entry.getIndex()).isEqualTo(commitIndex);
      }
    });
    Object queryResult =
      restoredNode.query(queryLastValue(), EVENTUAL_CONSISTENCY, 0L, 0L).join().getResult();
    assertThat(queryResult).isEqualTo(val);
    assertThat(group.getStateMachine(restoredNode.getLocalEndpoint()).get(result.getCommitIndex()))
      .isEqualTo(val);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerLeavesCommits() {
    LocalRaftGroup group = LocalRaftGroup.start(2);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNode follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    String val1 = "val1";
    leader.replicate(applyValue(val1)).join();
    Ordered<RaftGroupMembers> mewGroupMembers =
      leader.changeMembership(follower.getLocalEndpoint(), REMOVE_MEMBER, 0).join();
    follower.terminate();
    assertThat(mewGroupMembers.getResult().getMembers().size()).isEqualTo(1);
    assertThat(mewGroupMembers.getResult().getMembers()).contains(leader.getLocalEndpoint());
    Ordered<Object> queryResult1 =
      leader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0L, 0L).join();
    assertThat(queryResult1.getResult()).isEqualTo(val1);
    String val2 = "val2";
    Ordered<Object> result2 = leader.replicate(applyValue(val2)).join();
    assertThat(result2.getCommitIndex()).isGreaterThan(queryResult1.getCommitIndex());
    Ordered<Object> queryResult2 =
      leader.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0L, 0L).join();
    assertThat(queryResult2.getResult()).isEqualTo(val2);
    assertThat(queryResult2.getCommitIndex()).isEqualTo(result2.getCommitIndex());
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderLeavesCommits() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(3000).build();
    group = LocalRaftGroup.newBuilder(2).enableNewTermOperation().setConfig(config).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    // this follower will be the new leader
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    String val1 = "val1";
    leader.replicate(applyValue(val1)).join();
    int term = leader.getTerm().getTerm();
    Ordered<RaftGroupMembers> newGroupMembers =
      leader.changeMembership(leader.getLocalEndpoint(), REMOVE_MEMBER, 0).join();
    leader.terminate();
    assertThat(newGroupMembers.getResult().getMembers().size()).isEqualTo(1);
    assertThat(newGroupMembers.getResult().getMembers()).contains(follower.getLocalEndpoint());
    eventually(() -> {
      assertThat(follower.getLeaderEndpoint()).isEqualTo(follower.getLocalEndpoint());
      int newTerm = follower.getTerm().getTerm();
      assertThat(newTerm).isGreaterThan(term);
      assertThat(getCommitIndex(follower)).isGreaterThan(newGroupMembers.getCommitIndex());
    });
    Ordered<Object> queryResult1 =
      follower.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0L, 0L).join();
    assertThat(queryResult1.getResult()).isEqualTo(val1);
    String val2 = "val2";
    Ordered<Object> result2 = follower.replicate(applyValue(val2)).join();
    assertThat(result2.getCommitIndex()).isGreaterThan(queryResult1.getCommitIndex());
    Ordered<Object> queryResult2 =
      follower.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0L, 0L).join();
    assertThat(queryResult2.getResult()).isEqualTo(val2);
    assertThat(queryResult2.getCommitIndex()).isEqualTo(result2.getCommitIndex());
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testUncommittedRemovalCompletes() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(3000).build();
    group = LocalRaftGroup.newBuilder(2).enableNewTermOperation().setConfig(config).build();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    // this follower will be the new leader
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    String val1 = "val1";
    leader.replicate(applyValue(val1)).join();
    int term = leader.getTerm().getTerm();
    group.dropMessagesTo(follower.getLocalEndpoint(), leader.getLocalEndpoint(),
      AppendEntriesSuccessResponse.class);
    leader.changeMembership(leader.getLocalEndpoint(), REMOVE_MEMBER, 0);
    eventually(() -> assertThat(getEffectiveGroupMembers(follower).memberCount()).isEqualTo(1));
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    eventually(() -> {
      assertThat(follower.getLeaderEndpoint()).isEqualTo(follower.getLocalEndpoint());
      int newTerm = follower.getTerm().getTerm();
      assertThat(newTerm).isGreaterThan(term);
    });
    String val2 = "val2";
    Ordered<Object> result2 = follower.replicate(applyValue(val2)).join();
    Ordered<Object> queryResult =
      follower.query(queryLastValue(), QueryPolicy.LINEARIZABLE, 0L, 0L).join();
    assertThat(queryResult.getResult()).isEqualTo(val2);
    assertThat(queryResult.getCommitIndex()).isEqualTo(result2.getCommitIndex());
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testSingletonRejectsRemove() {
    group = LocalRaftGroup.start(1);
    RaftNode leader = group.waitUntilLeaderElected();
    try {
      leader.changeMembership(leader.getLocalEndpoint(), REMOVE_MEMBER, 0).join();
      fail("Cannot remove self from singleton Raft group");
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testSingleVotingRejectsRemove() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(3000).build();
    group = LocalRaftGroup.newBuilder(1).enableNewTermOperation().setConfig(config).build();
    group.start();
    RaftNode leader = group.waitUntilLeaderElected();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> result =
      leader.changeMembership(newNode.getLocalEndpoint(), ADD_LEARNER, 0).join();
    try {
      leader.changeMembership(leader.getLocalEndpoint(), REMOVE_MEMBER, result.getCommitIndex())
        .join();
      fail("Cannot remove self from singleton Raft group");
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
    }
  }
}
