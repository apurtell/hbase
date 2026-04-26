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
import static org.apache.hadoop.hbase.consensus.raft.QueryPolicy.EVENTUAL_CONSISTENCY;
import static org.apache.hadoop.hbase.consensus.raft.QueryPolicy.LEADER_LEASE;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.queryLastValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.exception.LaggingCommitIndexException;
import org.apache.hadoop.hbase.consensus.raft.exception.NotLeaderException;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestLocalQuery extends TestBase {
  private LocalRaftGroup group;

  @AfterEach
  public void destroy() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderEmptyDefault() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    Ordered<Object> o =
      leader.query(queryLastValue(), LEADER_LEASE, Optional.empty(), Optional.empty()).join();
    assertThat(o.getResult()).isNull();
    assertThat(o.getCommitIndex()).isEqualTo(0);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderEmptyCommitIndexFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    try {
      leader.query(queryLastValue(), LEADER_LEASE, Optional.of(getCommitIndex(leader) + 1),
        Optional.empty()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerEmptyDefault() {
    group = LocalRaftGroup.start(3);
    RaftNode leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    Ordered<Object> o = follower
      .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
    assertThat(o.getResult()).isNull();
    assertThat(o.getCommitIndex()).isEqualTo(0);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerEmptyCommitIndexDefault() {
    group = LocalRaftGroup.start(3);
    RaftNode leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    try {
      follower.query(queryLastValue(), EVENTUAL_CONSISTENCY,
        Optional.of(getCommitIndex(follower) + 1), Optional.empty()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderReadsLatest() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 3;
    for (int i = 1; i <= count; i++) {
      leader.replicate(applyValue("value" + i)).join();
    }
    long commitIndex = getCommitIndex(leader);
    Ordered<Object> result =
      leader.query(queryLastValue(), LEADER_LEASE, Optional.empty(), Optional.empty()).join();
    assertThat(result.getResult()).isEqualTo("value" + count);
    assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderCommitIndexReadsLatest() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 3;
    for (int i = 1; i <= count; i++) {
      leader.replicate(applyValue("value" + i)).join();
    }
    long commitIndex = getCommitIndex(leader);
    Ordered<Object> result = leader
      .query(queryLastValue(), LEADER_LEASE, Optional.of(commitIndex), Optional.empty()).join();
    assertThat(result.getResult()).isEqualTo("value" + count);
    assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderFurtherCommitIndexFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 3;
    for (int i = 1; i <= count; i++) {
      leader.replicate(applyValue("value" + i)).join();
    }
    long commitIndex = getCommitIndex(leader);
    try {
      leader.query(queryLastValue(), LEADER_LEASE, Optional.of(commitIndex + 1), Optional.empty())
        .join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerLeaseQueryFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value")).join();
    try {
      group.getAnyNodeExcept(leader.getLocalEndpoint())
        .query(queryLastValue(), LEADER_LEASE, Optional.empty(), Optional.empty()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerReadsLatest() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 3;
    for (int i = 1; i <= count; i++) {
      leader.replicate(applyValue("value" + i)).join();
    }
    String latestValue = "value" + count;
    eventually(() -> {
      long commitIndex = getCommitIndex(leader);
      for (RaftNodeImpl follower : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
        assertThat(getCommitIndex(follower)).isEqualTo(commitIndex);
        Ordered<Object> result = follower
          .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
        assertThat(result.getResult()).isEqualTo(latestValue);
        assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerCommitIndexReadsLatest() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 3;
    for (int i = 1; i <= count; i++) {
      leader.replicate(applyValue("value" + i)).join();
    }
    String latestValue = "value" + count;
    eventually(() -> {
      long commitIndex = getCommitIndex(leader);
      for (RaftNodeImpl follower : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
        assertThat(getCommitIndex(follower)).isEqualTo(commitIndex);
        Ordered<Object> result = follower
          .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex), Optional.empty())
          .join();
        assertThat(result.getResult()).isEqualTo(latestValue);
        assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testSlowFollowerReadsStale() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl slowFollower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    Object firstValue = "value1";
    leader.replicate(applyValue(firstValue)).join();
    long leaderCommitIndex = getCommitIndex(leader);
    eventually(() -> assertThat(getCommitIndex(slowFollower)).isEqualTo(leaderCommitIndex));
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint());
    leader.replicate(applyValue("value2")).join();
    Ordered<Object> result = slowFollower
      .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
    assertThat(result.getResult()).isEqualTo(firstValue);
    assertThat(result.getCommitIndex()).isEqualTo(leaderCommitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testSlowFollowerEventuallyLatest() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("value1")).join();
    RaftNodeImpl slowFollower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint());
    Object lastValue = "value2";
    leader.replicate(applyValue(lastValue)).join();
    group.allowAllMessagesTo(leader.getLocalEndpoint(), slowFollower.getLocalEndpoint());
    eventually(() -> {
      long commitIndex = getCommitIndex(leader);
      Ordered<Object> result = slowFollower
        .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
      assertThat(result.getResult()).isEqualTo(lastValue);
      assertThat(result.getCommitIndex()).isEqualTo(commitIndex);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testSplitLeaderReadsStale() {
    group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    Object firstValue = "value1";
    leader.replicate(applyValue(firstValue)).join();
    long firstCommitIndex = getCommitIndex(leader);
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isEqualTo(firstCommitIndex);
      }
    });
    RaftNodeImpl followerNode = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.splitMembers(leader.getLocalEndpoint());
    eventually(() -> {
      RaftEndpoint leaderEndpoint = followerNode.getLeaderEndpoint();
      assertThat(leaderEndpoint).isNotNull().isNotEqualTo(leader.getLocalEndpoint());
    });
    RaftNodeImpl newLeader = group.getNode(followerNode.getLeaderEndpoint());
    Object lastValue = "value2";
    newLeader.replicate(applyValue(lastValue)).join();
    long lastCommitIndex = getCommitIndex(newLeader);
    Ordered<Object> result1 = newLeader
      .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
    assertThat(result1.getResult()).isEqualTo(lastValue);
    assertThat(result1.getCommitIndex()).isEqualTo(lastCommitIndex);
    Ordered<Object> result2 = leader
      .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
    assertThat(result2.getResult()).isEqualTo(firstValue);
    assertThat(result2.getCommitIndex()).isEqualTo(firstCommitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testSplitLeaderLocalQueryFailsAfterDemotion() {
    group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    Object firstValue = "value1";
    leader.replicate(applyValue(firstValue)).join();
    long firstCommitIndex = getCommitIndex(leader);
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isEqualTo(firstCommitIndex);
      }
    });
    group.splitMembers(leader.getLocalEndpoint());
    eventually(() -> {
      try {
        leader.query(queryLastValue(), LEADER_LEASE, Optional.empty(), Optional.empty()).join();
        fail();
      } catch (CompletionException e) {
        assertThat(e).hasCauseInstanceOf(NotLeaderException.class);
      }
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testSplitLeaderEventuallyLatest() {
    group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    Object firstValue = "value1";
    leader.replicate(applyValue(firstValue)).join();
    long leaderCommitIndex = getCommitIndex(leader);
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isEqualTo(leaderCommitIndex);
      }
    });
    RaftNodeImpl followerNode = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.splitMembers(leader.getLocalEndpoint());
    eventually(() -> {
      RaftEndpoint leaderEndpoint = followerNode.getLeaderEndpoint();
      assertThat(leaderEndpoint).isNotNull().isNotEqualTo(leader.getLocalEndpoint());
    });
    RaftNodeImpl newLeader = group.getNode(followerNode.getLeaderEndpoint());
    Object lastValue = "value2";
    newLeader.replicate(applyValue(lastValue)).join();
    long lastCommitIndex = getCommitIndex(newLeader);
    group.merge();
    eventually(() -> {
      Ordered<Object> result = leader
        .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
      assertThat(result.getResult()).isEqualTo(lastValue);
      assertThat(result.getCommitIndex()).isEqualTo(lastCommitIndex);
    });
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerReadsLatest() {
    group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    Object value = "value";
    leader.replicate(applyValue(value)).join();
    RaftNodeImpl newFollower = group.createNewNode();
    leader.changeMembership(newFollower.getLocalEndpoint(), ADD_LEARNER, 0).join();
    eventually(() -> assertThat(getCommitIndex(newFollower)).isEqualTo(getCommitIndex(leader)));
    Ordered<Object> result = newFollower
      .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.empty(), Optional.empty()).join();
    assertThat(result.getCommitIndex()).isEqualTo(getCommitIndex(leader));
    assertThat(result.getResult()).isEqualTo(value);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerStaleCommitIndexReadsStale() {
    group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    Object value1 = "value1";
    leader.replicate(applyValue(value1)).join();
    RaftNodeImpl newFollower = group.createNewNode();
    long commitIndex1 = leader.changeMembership(newFollower.getLocalEndpoint(), ADD_LEARNER, 0)
      .join().getCommitIndex();
    eventually(() -> assertThat(getCommitIndex(newFollower)).isEqualTo(commitIndex1));
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), newFollower.getLocalEndpoint());
    Object value2 = "value2";
    leader.replicate(applyValue(value2)).join();
    Ordered<Object> result = newFollower
      .query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex1), Optional.empty())
      .join();
    assertThat(result.getResult()).isEqualTo(value1);
    assertThat(result.getCommitIndex()).isEqualTo(commitIndex1);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLearnerInvalidCommitIndexFails() {
    group = LocalRaftGroup.start(3, TEST_RAFT_CONFIG);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    Object value = "value";
    leader.replicate(applyValue(value)).join();
    RaftNodeImpl newFollower = group.createNewNode();
    leader.changeMembership(newFollower.getLocalEndpoint(), ADD_LEARNER, 0).join();
    eventually(() -> assertThat(getCommitIndex(newFollower)).isEqualTo(getCommitIndex(leader)));
    try {
      newFollower.query(queryLastValue(), EVENTUAL_CONSISTENCY,
        Optional.of(getCommitIndex(leader) + 1), Optional.empty()).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }
}
