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

import static org.apache.hadoop.hbase.consensus.raft.QueryPolicy.EVENTUAL_CONSISTENCY;
import static org.apache.hadoop.hbase.consensus.raft.QueryPolicy.LEADER_LEASE;
import static org.apache.hadoop.hbase.consensus.raft.QueryPolicy.LINEARIZABLE;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.queryLastValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.exception.LaggingCommitIndexException;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestQueryTimeout extends TestBase {
  private LocalRaftGroup group;

  @AfterEach
  public void destroy() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerFutureIndexExecutesOnAdvance() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    CompletableFuture<Ordered<Object>> f = follower.query(queryLastValue(), EVENTUAL_CONSISTENCY,
      Optional.of(commitIndex), Optional.of(Duration.ofSeconds(30)));
    group.allowAllMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    Ordered<Object> o = f.join();
    assertThat(o.getResult()).isEqualTo("value");
    assertThat(o.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testMultipleFollowerQueriesAdvance() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    List<CompletableFuture<Ordered<Object>>> futures = new ArrayList<>();
    int queryCount = 5;
    for (int i = 0; i < queryCount; i++) {
      CompletableFuture<Ordered<Object>> f = follower.query(queryLastValue(), EVENTUAL_CONSISTENCY,
        Optional.of(commitIndex), Optional.of(Duration.ofSeconds(30)));
      futures.add(f);
    }
    group.allowAllMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    for (CompletableFuture<Ordered<Object>> f : futures) {
      Ordered<Object> o = f.join();
      assertThat(o.getResult()).isEqualTo("value");
      assertThat(o.getCommitIndex()).isEqualTo(commitIndex);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testMultipleFollowerQueriesAdvanceViaSnapshot() {
    int logSize = 10;
    RaftConfig config = RaftConfig.newBuilder().setCommitCountToTakeSnapshot(logSize).build();
    group = LocalRaftGroup.start(3, config);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(),
      InstallSnapshotRequest.class);
    String latestValue = null;
    long latestCommitIndex = 0;
    for (int i = 0; i < logSize; i++) {
      latestValue = "value" + i;
      latestCommitIndex = leader.replicate(applyValue(latestValue)).join().getCommitIndex();
    }
    CompletableFuture<Ordered<Object>> f = follower.query(queryLastValue(), EVENTUAL_CONSISTENCY,
      Optional.of(latestCommitIndex - 1), Optional.of(Duration.ofSeconds(30)));
    group.allowAllMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    Ordered<Object> o = f.join();
    assertThat(o.getResult()).isEqualTo(latestValue);
    assertThat(o.getCommitIndex()).isEqualTo(latestCommitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerPastIndexImmediate() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    eventually(() -> {
      assertThat(getCommitIndex(follower)).isEqualTo(commitIndex);
    });
    Ordered<Object> o = follower.query(queryLastValue(), EVENTUAL_CONSISTENCY,
      Optional.of(commitIndex), Optional.of(Duration.ofSeconds(1))).join();
    assertThat(o.getResult()).isEqualTo("value");
    assertThat(o.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerFutureIndexTimesOut() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    try {
      follower.query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex),
        Optional.of(Duration.ofSeconds(1))).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerFutureIndexNegativeTimeoutFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    try {
      follower.query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex + 1),
        Optional.of(Duration.ofSeconds(-1))).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderLeaseFutureIndexFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    try {
      leader.query(queryLastValue(), LEADER_LEASE, Optional.of(commitIndex + 1),
        Optional.of(Duration.ofSeconds(1))).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderLinearizableFutureIndexFails() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    try {
      leader.query(queryLastValue(), LINEARIZABLE, Optional.of(commitIndex + 1),
        Optional.of(Duration.ofSeconds(1))).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderEventualQueryFutureIndexTimesOut() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    try {
      leader.query(queryLastValue(), EVENTUAL_CONSISTENCY, Optional.of(commitIndex + 1),
        Optional.of(Duration.ofSeconds(1))).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerFutureIndexFailsOnTerminate() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    CompletableFuture<Ordered<Object>> f = follower.query(queryLastValue(), EVENTUAL_CONSISTENCY,
      Optional.of(commitIndex), Optional.of(Duration.ofSeconds(30)));
    follower.terminate().join();
    try {
      f.join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderFutureIndexFailsOnLeave() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    long commitIndex = leader.replicate(applyValue("value")).join().getCommitIndex();
    CompletableFuture<Ordered<Object>> f = leader.query(queryLastValue(), EVENTUAL_CONSISTENCY,
      Optional.of(commitIndex + 100), Optional.of(Duration.ofSeconds(300)));
    leader.changeMembership(leader.getLocalEndpoint(), MembershipChangeMode.REMOVE_MEMBER, 0)
      .join();
    try {
      f.join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerBarrierPastImmediate() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    long commitIndex1 = leader.replicate(applyValue("value1")).join().getCommitIndex();
    long commitIndex2 = leader.replicate(applyValue("value2")).join().getCommitIndex();
    eventually(() -> {
      assertThat(getCommitIndex(follower)).isEqualTo(commitIndex2);
    });
    Ordered<Object> o = follower.waitFor(commitIndex1, Duration.ofSeconds(100)).join();
    assertThat(o.getResult()).isNull();
    assertThat(o.getCommitIndex()).isEqualTo(commitIndex2);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerBarrierCurrentImmediate() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    long commitIndex = leader.replicate(applyValue("value1")).join().getCommitIndex();
    eventually(() -> {
      assertThat(getCommitIndex(follower)).isEqualTo(commitIndex);
    });
    Ordered<Object> o = follower.waitFor(commitIndex, Duration.ofSeconds(100)).join();
    assertThat(o.getResult()).isNull();
    assertThat(o.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testFollowerBarrierFutureTimesOut() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.getAnyNodeExcept(leader.getLocalEndpoint());
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    long commitIndex = leader.replicate(applyValue("value1")).join().getCommitIndex();
    try {
      follower.waitFor(commitIndex, Duration.ofSeconds(5)).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderBarrierPastImmediate() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    long commitIndex1 = leader.replicate(applyValue("value1")).join().getCommitIndex();
    long commitIndex2 = leader.replicate(applyValue("value2")).join().getCommitIndex();
    Ordered<Object> o = leader.waitFor(commitIndex1, Duration.ofSeconds(100)).join();
    assertThat(o.getResult()).isNull();
    assertThat(o.getCommitIndex()).isEqualTo(commitIndex2);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderBarrierCurrentImmediate() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    long commitIndex = leader.replicate(applyValue("value1")).join().getCommitIndex();
    Ordered<Object> o = leader.waitFor(commitIndex, Duration.ofSeconds(100)).join();
    assertThat(o.getResult()).isNull();
    assertThat(o.getCommitIndex()).isEqualTo(commitIndex);
  }

  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  public void testLeaderBarrierFutureTimesOut() {
    group = LocalRaftGroup.start(3);
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    long commitIndex = leader.replicate(applyValue("value1")).join().getCommitIndex();
    try {
      leader.waitFor(commitIndex + 1, Duration.ofSeconds(5)).join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(LaggingCommitIndexException.class);
    }
  }
}
