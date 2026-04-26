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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER;
import static org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode.REMOVE_MEMBER;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.FOLLOWER;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.LEARNER;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup.IN_MEMORY_RAFT_STATE_STORE_FACTORY;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommittedGroupMembers;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getEffectiveGroupMembers;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getLastApplied;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getLastLogOrSnapshotEntry;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRaftStore;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRestoredState;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getSnapshotEntry;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getTerm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.local.InMemoryRaftStore;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.consensus.raft.report.RaftGroupMembers;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestPersistence extends TestBase {
  private LocalRaftGroup group;

  @AfterEach
  public void destroy() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testTermAndVotePersisted() {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    Set<RaftEndpoint> endpoints = new HashSet<>();
    for (RaftNodeImpl node : group.getNodes()) {
      endpoints.add(node.getLocalEndpoint());
    }
    int term1 = getTerm(leader);
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        RestoredRaftState restoredState = getRestoredState(node);
        assertThat(restoredState.getLocalEndpointPersistentState().getLocalEndpoint())
          .isEqualTo(node.getLocalEndpoint());
        assertThat(restoredState.getTermPersistentState().getTerm()).isEqualTo(term1);
        assertThat(restoredState.getInitialGroupMembers().getMembers()).isEqualTo(endpoints);
      }
    });
    group.terminateNode(leader.getLocalEndpoint());
    eventually(() -> {
      for (RaftNodeImpl node : followers) {
        RaftEndpoint l = node.getLeaderEndpoint();
        assertNotNull(l);
        assertThat(l).isNotEqualTo(leader.getLeaderEndpoint());
      }
    });
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    int term2 = getTerm(newLeader);
    eventually(() -> {
      for (RaftNodeImpl node : followers) {
        RestoredRaftState restoredState = getRestoredState(node);
        assertThat(restoredState.getTermPersistentState().getTerm()).isEqualTo(term2);
        assertThat(restoredState.getTermPersistentState().getVotedFor())
          .isEqualTo(newLeader.getLeaderEndpoint());
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testCommittedEntriesPersisted() {
    group =
      LocalRaftGroup.newBuilder(3).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 10;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        RestoredRaftState restoredState = getRestoredState(node);
        List<LogEntry> entries = restoredState.getLogEntries();
        assertThat(entries).hasSize(count);
        for (int i = 0; i < count; i++) {
          assertThat(entries.get(i).getIndex()).isEqualTo(i + 1);
        }
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testUncommittedEntriesPersisted() {
    group =
      LocalRaftGroup.newBuilder(3).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl responsiveFollower = followers.get(0);
    for (int i = 1; i < followers.size(); i++) {
      group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(),
        followers.get(i).getLocalEndpoint());
    }
    int count = 10;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + i));
    }
    eventually(() -> {
      for (RaftNodeImpl node : Arrays.asList(leader, responsiveFollower)) {
        RestoredRaftState restoredState = getRestoredState(node);
        List<LogEntry> entries = restoredState.getLogEntries();
        assertThat(entries).hasSize(count);
        for (int i = 0; i < count; i++) {
          assertThat(entries.get(i).getIndex()).isEqualTo(i + 1);
        }
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testSnapshotPersisted() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config =
      RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config)
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    for (int i = 0; i < commitCountToTakeSnapshot; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    eventually(() -> {
      assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(commitCountToTakeSnapshot);
      for (RaftNodeImpl node : group.getNodes()) {
        RestoredRaftState restoredState = getRestoredState(node);
        SnapshotEntry snapshot = restoredState.getSnapshotEntry();
        assertNotNull(snapshot);
        assertThat(snapshot.getIndex()).isEqualTo(commitCountToTakeSnapshot);
        assertNotNull(snapshot.getOperation());
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testMinorityAppendsTruncated() {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("val1")).join();
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isEqualTo(1);
      }
    });
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.splitMembers(leader.getLocalEndpoint());
    for (int i = 0; i < 10; i++) {
      leader.replicate(applyValue("isolated" + i));
    }
    eventually(() -> {
      for (RaftNodeImpl node : followers) {
        RaftEndpoint leaderEndpoint = node.getLeaderEndpoint();
        assertNotNull(leaderEndpoint);
        assertThat(leaderEndpoint).isNotEqualTo(leader.getLocalEndpoint());
      }
    });
    eventually(() -> {
      RestoredRaftState restoredState = getRestoredState(leader);
      assertThat(restoredState.getLogEntries()).hasSize(11);
    });
    RaftNodeImpl newLeader = group.getNode(followers.get(0).getLeaderEndpoint());
    for (int i = 0; i < 10; i++) {
      newLeader.replicate(applyValue("valNew" + i)).join();
    }
    eventually(() -> {
      for (RaftNodeImpl node : followers) {
        assertThat(getCommitIndex(node)).isEqualTo(11);
      }
    });
    group.merge();
    RaftNodeImpl finalLeader = group.waitUntilLeaderElected();
    assertThat(finalLeader.getLocalEndpoint()).isNotEqualTo(leader.getLocalEndpoint());
    eventually(() -> {
      RestoredRaftState state = getRestoredState(leader);
      assertThat(state.getLogEntries()).hasSize(11);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderRestartsAsFollower() {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 10;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    RaftEndpoint terminatedEndpoint = leader.getLocalEndpoint();
    group.terminateNode(terminatedEndpoint);
    InMemoryRaftStore stateStore = getRaftStore(leader);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getCommittedGroupMembers(newLeader).getMembersList());
    assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getEffectiveGroupMembers(newLeader).getMembersList());
    eventually(() -> {
      assertThat(restartedNode.getLeaderEndpoint()).isEqualTo(newLeader.getLocalEndpoint());
      assertThat(getTerm(restartedNode)).isEqualTo(getTerm(newLeader));
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(newLeader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(newLeader));
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      assertThat(values).hasSize(count);
      for (int i = 0; i < count; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderRestartsAsLeader() {
    group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 10;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    int term = getTerm(leader);
    long commitIndex = getCommitIndex(leader);
    RaftEndpoint terminatedEndpoint = leader.getLocalEndpoint();
    InMemoryRaftStore stateStore = getRaftStore(leader);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    // Block voting between followers
    // to avoid a leader election before leader restarts.
    blockVotingBetweenFollowers();
    group.terminateNode(terminatedEndpoint);
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    assertThat(restartedNode).isSameAs(newLeader);
    eventually(() -> {
      assertThat(getTerm(restartedNode)).isGreaterThan(term);
      assertThat(getCommitIndex(restartedNode)).isEqualTo(commitIndex + 1);
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      assertThat(values).hasSize(count);
      for (int i = 0; i < count; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
    });
  }

  private void blockVotingBetweenFollowers() {
    for (RaftNodeImpl follower : group.<RaftNodeImpl> getNodesExcept(group.getLeaderEndpoint())) {
      group.dropMessagesToAll(follower.getLocalEndpoint(), PreVoteRequest.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerRestoresState() {
    group =
      LocalRaftGroup.newBuilder(3).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl terminatedFollower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    int count = 10;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    eventually(() -> assertEquals(getCommitIndex(leader), getCommitIndex(terminatedFollower)));
    RaftEndpoint terminatedEndpoint = terminatedFollower.getLocalEndpoint();
    group.terminateNode(terminatedEndpoint);
    InMemoryRaftStore stateStore = getRaftStore(terminatedFollower);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    leader.replicate(applyValue("val" + count)).join();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getCommittedGroupMembers(leader).getMembersList());
    assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getEffectiveGroupMembers(leader).getMembersList());
    eventually(() -> {
      assertThat(restartedNode.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint());
      assertThat(getTerm(restartedNode)).isEqualTo(getTerm(leader));
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(leader));
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      assertThat(values).hasSize(count + 1);
      for (int i = 0; i <= count; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerRestoresState() {
    group = LocalRaftGroup.newBuilder(3, 2).setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY)
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl terminatedLearner = null;
    for (RaftNodeImpl node : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
      if (getRole(node) == LEARNER) {
        terminatedLearner = node;
        break;
      }
    }
    assertNotNull(terminatedLearner);
    int count = 10;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    RaftNodeImpl terminated = terminatedLearner;
    eventually(() -> assertEquals(getCommitIndex(leader), getCommitIndex(terminated)));
    RaftEndpoint terminatedEndpoint = terminatedLearner.getLocalEndpoint();
    group.terminateNode(terminatedEndpoint);
    InMemoryRaftStore stateStore = getRaftStore(terminatedLearner);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    leader.replicate(applyValue("val" + count)).join();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    assertThat(getRole(restartedNode)).isEqualTo(LEARNER);
    assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getCommittedGroupMembers(leader).getMembersList());
    assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getEffectiveGroupMembers(leader).getMembersList());
    eventually(() -> {
      assertThat(restartedNode.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint());
      assertThat(getTerm(restartedNode)).isEqualTo(getTerm(leader));
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(leader));
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      assertThat(values).hasSize(count + 1);
      for (int i = 0; i <= count; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderRestartsAsFollowerWithSnapshot() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config = RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(2000)
      .setLeaderHeartbeatPeriodMillis(1000).setLeaderHeartbeatTimeoutMillis(5000)
      .setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    assertThat(getSnapshotEntry(leader).getIndex()).isGreaterThan(0);
    RaftEndpoint terminatedEndpoint = leader.getLocalEndpoint();
    group.terminateNode(terminatedEndpoint);
    InMemoryRaftStore stateStore = getRaftStore(leader);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getCommittedGroupMembers(newLeader).getMembersList());
    assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getEffectiveGroupMembers(newLeader).getMembersList());
    eventually(() -> {
      assertThat(restartedNode.getLeaderEndpoint()).isEqualTo(newLeader.getLocalEndpoint());
      assertThat(getTerm(restartedNode)).isEqualTo(getTerm(newLeader));
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(newLeader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(newLeader));
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      assertThat(values).hasSize(commitCountToTakeSnapshot + 1);
      for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerRestoresStateWithSnapshot() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config =
      RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getSnapshotEntry(node).getIndex()).isGreaterThan(0);
      }
    });
    RaftNodeImpl terminatedFollower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    RaftEndpoint terminatedEndpoint = terminatedFollower.getLocalEndpoint();
    group.terminateNode(terminatedEndpoint);
    InMemoryRaftStore stateStore = getRaftStore(terminatedFollower);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    leader.replicate(applyValue("val" + (commitCountToTakeSnapshot + 1))).join();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getCommittedGroupMembers(leader).getMembersList());
    assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getEffectiveGroupMembers(leader).getMembersList());
    eventually(() -> {
      assertThat(restartedNode.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint());
      assertThat(getTerm(restartedNode)).isEqualTo(getTerm(leader));
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(leader));
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      assertThat(values).hasSize(commitCountToTakeSnapshot + 2);
      for (int i = 0; i <= commitCountToTakeSnapshot + 1; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerRestoresStateWithSnapshot() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config =
      RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
    group = LocalRaftGroup.newBuilder(3, 2).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getSnapshotEntry(node).getIndex()).isGreaterThan(0);
      }
    });
    RaftNodeImpl terminatedLearner = null;
    for (RaftNodeImpl node : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
      if (getRole(node) == LEARNER) {
        terminatedLearner = node;
        break;
      }
    }
    assertNotNull(terminatedLearner);
    RaftEndpoint terminatedEndpoint = terminatedLearner.getLocalEndpoint();
    group.terminateNode(terminatedEndpoint);
    InMemoryRaftStore stateStore = getRaftStore(terminatedLearner);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    leader.replicate(applyValue("val" + (commitCountToTakeSnapshot + 1))).join();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getCommittedGroupMembers(leader).getMembersList());
    assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getEffectiveGroupMembers(leader).getMembersList());
    assertThat(getRole(restartedNode)).isEqualTo(LEARNER);
    eventually(() -> {
      assertThat(restartedNode.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint());
      assertThat(getTerm(restartedNode)).isEqualTo(getTerm(leader));
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(leader));
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      assertThat(values).hasSize(commitCountToTakeSnapshot + 2);
      for (int i = 0; i <= commitCountToTakeSnapshot + 1; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderRestartsAsLeaderWithSnapshot() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config =
      RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    assertThat(getSnapshotEntry(leader).getIndex()).isGreaterThan(0);
    int term = getTerm(leader);
    long commitIndex = getCommitIndex(leader);
    RaftEndpoint terminatedEndpoint = leader.getLocalEndpoint();
    InMemoryRaftStore stateStore = getRaftStore(leader);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    // Block voting between followers
    // to avoid a leader election before leader restarts.
    blockVotingBetweenFollowers();
    group.terminateNode(terminatedEndpoint);
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    assertThat(newLeader).isSameAs(restartedNode);
    eventually(() -> {
      assertThat(getTerm(restartedNode)).isGreaterThan(term);
      assertThat(getCommitIndex(restartedNode)).isEqualTo(commitIndex + 1);
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      assertThat(values).hasSize(commitCountToTakeSnapshot + 1);
      for (int i = 0; i <= commitCountToTakeSnapshot; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderRestartAppliesMembers() {
    group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl removedFollower = followers.get(0);
    RaftNodeImpl runningFollower = followers.get(1);
    group.terminateNode(removedFollower.getLocalEndpoint());
    leader.replicate(applyValue("val")).join();
    leader.changeMembership(removedFollower.getLocalEndpoint(), REMOVE_MEMBER, 0).join();
    RaftEndpoint terminatedEndpoint = leader.getLocalEndpoint();
    InMemoryRaftStore stateStore = getRaftStore(leader);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    // Block voting between followers
    // to avoid a leader election before leader restarts.
    blockVotingBetweenFollowers();
    group.terminateNode(terminatedEndpoint);
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    assertThat(newLeader).isSameAs(restartedNode);
    eventually(() -> {
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(runningFollower));
      assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getCommittedGroupMembers(runningFollower).getMembersList());
      assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getEffectiveGroupMembers(runningFollower).getMembersList());
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerRestartAppliesMembers() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(30_000).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl removedFollower = followers.get(0);
    RaftNodeImpl terminatedFollower = followers.get(1);
    group.terminateNode(removedFollower.getLocalEndpoint());
    leader.replicate(applyValue("val")).join();
    leader.changeMembership(removedFollower.getLocalEndpoint(), REMOVE_MEMBER, 0).join();
    RaftEndpoint terminatedEndpoint = terminatedFollower.getLocalEndpoint();
    group.terminateNode(terminatedEndpoint);
    InMemoryRaftStore stateStore = getRaftStore(terminatedFollower);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    eventually(() -> {
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(leader));
      assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getCommittedGroupMembers(leader).getMembersList());
      assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getEffectiveGroupMembers(leader).getMembersList());
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerRestartAppliesPromotion() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(30_000).build();
    group = LocalRaftGroup.newBuilder(3, 2).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl terminatedLearner = null;
    for (RaftNodeImpl node : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
      if (getRole(node) == LEARNER) {
        terminatedLearner = node;
        break;
      }
    }
    assertNotNull(terminatedLearner);
    leader.replicate(applyValue("val")).join();
    leader.changeMembership(terminatedLearner.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0)
      .join();
    group.terminateNode(terminatedLearner.getLocalEndpoint());
    InMemoryRaftStore stateStore = getRaftStore(terminatedLearner);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    eventually(() -> {
      assertThat(getRole(restartedNode)).isEqualTo(FOLLOWER);
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(leader));
      assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getCommittedGroupMembers(leader).getMembersList());
      assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getEffectiveGroupMembers(leader).getMembersList());
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderRestartLoadsCommittedMembersFromSnapshot() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config =
      RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl removedFollower = followers.get(0);
    RaftNodeImpl runningFollower = followers.get(1);
    group.terminateNode(removedFollower.getLocalEndpoint());
    leader.replicate(applyValue("val")).join();
    leader.changeMembership(removedFollower.getLocalEndpoint(), REMOVE_MEMBER, 0).join();
    while (getSnapshotEntry(leader).getIndex() == 0) {
      leader.replicate(applyValue("val")).join();
    }
    RaftEndpoint terminatedEndpoint = leader.getLocalEndpoint();
    InMemoryRaftStore stateStore = getRaftStore(leader);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    // Block voting between followers
    // to avoid a leader election before leader restarts.
    blockVotingBetweenFollowers();
    group.terminateNode(terminatedEndpoint);
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    assertThat(newLeader).isSameAs(restartedNode);
    eventually(() -> {
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(runningFollower));
      assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getCommittedGroupMembers(runningFollower).getMembersList());
      assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getEffectiveGroupMembers(runningFollower).getMembersList());
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerRestartAppliesMembersViaSnapshot() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config =
      RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot)
        .setLeaderHeartbeatPeriodMillis(1000).setLeaderHeartbeatTimeoutMillis(30_000).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl removedFollower = followers.get(0);
    RaftNodeImpl terminatedFollower = followers.get(1);
    group.terminateNode(removedFollower.getLocalEndpoint());
    leader.replicate(applyValue("val")).join();
    leader.changeMembership(removedFollower.getLocalEndpoint(), REMOVE_MEMBER, 0).join();
    while (getSnapshotEntry(terminatedFollower).getIndex() == 0) {
      leader.replicate(applyValue("val")).join();
    }
    RaftEndpoint terminatedEndpoint = terminatedFollower.getLocalEndpoint();
    group.terminateNode(terminatedEndpoint);
    InMemoryRaftStore stateStore = getRaftStore(terminatedFollower);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    eventually(() -> {
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(leader));
      assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getCommittedGroupMembers(leader).getMembersList());
      assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getEffectiveGroupMembers(leader).getMembersList());
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerRestartAppliesPromotionViaSnapshot() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config =
      RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot)
        .setLeaderHeartbeatPeriodMillis(1000).setLeaderHeartbeatTimeoutMillis(30_000).build();
    group = LocalRaftGroup.newBuilder(3, 2).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl terminatedLearner = null;
    for (RaftNodeImpl node : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
      if (getRole(node) == LEARNER) {
        terminatedLearner = node;
        break;
      }
    }
    assertNotNull(terminatedLearner);
    leader.replicate(applyValue("val")).join();
    leader.changeMembership(terminatedLearner.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER, 0)
      .join();
    while (getSnapshotEntry(terminatedLearner).getIndex() == 0) {
      leader.replicate(applyValue("val")).join();
    }
    group.terminateNode(terminatedLearner.getLocalEndpoint());
    InMemoryRaftStore stateStore = getRaftStore(terminatedLearner);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    eventually(() -> {
      assertThat(getRole(restartedNode)).isEqualTo(FOLLOWER);
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(leader));
      assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getCommittedGroupMembers(leader).getMembersList());
      assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
        .isEqualTo(getEffectiveGroupMembers(leader).getMembersList());
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerRestartsNonVoting() {
    group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl newNode = group.createNewNode();
    leader.changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD_LEARNER, 0).join();
    eventually(() -> assertThat(getCommitIndex(newNode)).isEqualTo(getCommitIndex(leader)));
    group.terminateNode(newNode.getLocalEndpoint());
    InMemoryRaftStore stateStore = getRaftStore(newNode);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    eventually(() -> assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader)));
    assertThat(getRole(restartedNode)).isEqualTo(LEARNER);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testPromotedMemberRestartsVoting() {
    group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult = leader
      .changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD_LEARNER, 0).join();
    leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
      membershipChangeResult.getCommitIndex()).join();
    eventually(() -> assertThat(getCommitIndex(newNode)).isEqualTo(getCommitIndex(leader)));
    group.terminateNode(newNode.getLocalEndpoint());
    InMemoryRaftStore stateStore = getRaftStore(newNode);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    eventually(() -> assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader)));
    assertThat(getRole(restartedNode)).isEqualTo(RaftRole.FOLLOWER);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testPromotingMemberRestartsVoting() {
    group = LocalRaftGroup.newBuilder(3).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl newNode = group.createNewNode();
    Ordered<RaftGroupMembers> membershipChangeResult = leader
      .changeMembership(newNode.getLocalEndpoint(), MembershipChangeMode.ADD_LEARNER, 0).join();
    for (RaftNodeImpl follower : followers) {
      group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    }
    leader.changeMembership(newNode.getLocalEndpoint(), ADD_OR_PROMOTE_TO_FOLLOWER,
      membershipChangeResult.getCommitIndex());
    eventually(() -> assertThat(getRole(newNode)).isEqualTo(RaftRole.FOLLOWER));
    group.terminateNode(newNode.getLocalEndpoint());
    InMemoryRaftStore stateStore = getRaftStore(newNode);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    eventually(() -> assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(leader)));
    assertThat(getRole(restartedNode)).isEqualTo(RaftRole.FOLLOWER);
  }

  // Check that the old leader's log can be synchronized with a new one when it
  // contains a snapshot
  // and only unreplicated entries
  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderRestartWithStaleLogBecomesFollower() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig config = RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(2000)
      .setLeaderHeartbeatPeriodMillis(1000).setLeaderHeartbeatTimeoutMillis(5000)
      .setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation()
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).start();
    group.start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int index = 0;
    while (getSnapshotEntry(leader).getIndex() == 0) {
      leader.replicate(applyValue("val" + index)).join();
      index++;
    }
    // the number of entries in a log should be exactly equal the
    // commitCountToTakeSnapshot
    assertThat(getLastLogOrSnapshotEntry(leader).getIndex()).isEqualTo(commitCountToTakeSnapshot);
    List<RaftNode> followerEndpoints = group.getNodesExcept(leader.getLeaderEndpoint());
    InMemoryRaftStore followerStateStore1 = getRaftStore(followerEndpoints.get(0));
    RestoredRaftState followerTerminatedState1 = followerStateStore1.toRestoredRaftState();
    InMemoryRaftStore followerStateStore2 = getRaftStore(followerEndpoints.get(1));
    RestoredRaftState followerTerminatedState2 = followerStateStore2.toRestoredRaftState();
    group.terminateNode(followerEndpoints.get(0).getLocalEndpoint());
    group.terminateNode(followerEndpoints.get(1).getLocalEndpoint());
    try {
      // put an entry in a leader's log that will not be replicated and will not be
      // committed
      leader.replicate(applyValue("valNonCommitted")).get(1, SECONDS);
    } catch (TimeoutException | InterruptedException | ExecutionException e) {
    }
    RaftEndpoint terminatedEndpoint = leader.getLeaderEndpoint();
    InMemoryRaftStore stateStore = getRaftStore(leader);
    RestoredRaftState terminatedState = stateStore.toRestoredRaftState();
    group.terminateNode(terminatedEndpoint);
    group.restoreNode(followerTerminatedState1, followerStateStore1);
    group.restoreNode(followerTerminatedState2, followerStateStore2);
    final RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    newLeader.replicate(applyValue("valNewCommitted")).join();
    final RaftNodeImpl restartedNode = group.restoreNode(terminatedState, stateStore);
    assertThat(getCommittedGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getCommittedGroupMembers(newLeader).getMembersList());
    assertThat(getEffectiveGroupMembers(restartedNode).getMembersList())
      .isEqualTo(getEffectiveGroupMembers(newLeader).getMembersList());
    // checks that the log is fully replicated to the old leader
    int finalIndex = index;
    eventually(() -> {
      assertThat(getTerm(restartedNode)).isEqualTo(getTerm(newLeader));
      assertThat(getCommitIndex(restartedNode)).isEqualTo(getCommitIndex(newLeader));
      assertThat(getLastApplied(restartedNode)).isEqualTo(getLastApplied(newLeader));
      SimpleStateMachine stateMachine = group.getStateMachine(restartedNode.getLocalEndpoint());
      List<Object> values = stateMachine.valueList();
      assertNotNull(values);
      for (int i = 0; i < finalIndex; i++) {
        assertThat(values.get(i)).isEqualTo("val" + i);
      }
      assertThat(values.get(finalIndex)).isEqualTo("valNewCommitted");
    });
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testTermAndVotedForSurviveFollowerRestart() {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(IN_MEMORY_RAFT_STATE_STORE_FACTORY).enableNewTermOperation().start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl victim = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0);
    eventually(() -> assertThat(getRestoredState(victim).getTermPersistentState().getTerm())
      .isEqualTo(getTerm(victim)));
    int termWhenStopped = getTerm(victim);
    RaftEndpoint votedFor = getRestoredState(victim).getTermPersistentState().getVotedFor();
    InMemoryRaftStore store = getRaftStore(victim);
    RestoredRaftState snapshot = store.toRestoredRaftState();
    group.terminateNode(victim.getLocalEndpoint());
    RaftNodeImpl restarted = group.restoreNode(snapshot, store);
    eventually(
      () -> assertThat(restarted.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint()));
    assertThat(getTerm(restarted)).isEqualTo(termWhenStopped);
    assertThat(getRestoredState(restarted).getTermPersistentState().getVotedFor())
      .isEqualTo(votedFor);
  }
}
