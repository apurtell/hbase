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

import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.readRaftState;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.impl.state.FollowerState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestSendCatchupAppendsIfNeeded extends TestBase {
  private LocalRaftGroup group;

  // Long election + heartbeat timeouts so the natural HeartbeatTask doesn't preempt our explicit
  // sendCatchupAppendsIfNeeded() invocations.
  private static final RaftConfig CONFIG =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(60_000)
      .setLeaderHeartbeatPeriodMillis(60_000).setLeaderHeartbeatTimeoutMillis(120_000).build();

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testNoCatchupWhenAllFollowersAreUpToDate() {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    AtomicInteger aeCount = new AtomicInteger();
    for (RaftNodeImpl follower : followers) {
      group.alterMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(), msg -> {
        if (msg instanceof AppendEntriesRequest) {
          aeCount.incrementAndGet();
        }
        return msg;
      });
    }
    aeCount.set(0);
    leader.getExecutor().execute(leader::sendCatchupAppendsIfNeeded);
    leader.getExecutor().execute(leader::sendCatchupAppendsIfNeeded);
    leader.getExecutor().execute(() -> assertThat(aeCount.get()).isEqualTo(0));
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testCatchupFiresWhenFollowerLags() throws Exception {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("v1")).join();
    leader.replicate(applyValue("v2")).join();
    eventually(() -> {
      for (RaftNodeImpl follower : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
        assertThat(getCommitIndex(follower)).isEqualTo(getCommitIndex(leader));
      }
    });
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl laggingFollower = followers.get(0);
    AtomicInteger aeToLagging = new AtomicInteger();
    AtomicInteger aeToCaughtUp = new AtomicInteger();
    group.alterMessagesTo(leader.getLocalEndpoint(), laggingFollower.getLocalEndpoint(), msg -> {
      if (msg instanceof AppendEntriesRequest) {
        aeToLagging.incrementAndGet();
      }
      return msg;
    });
    group.alterMessagesTo(leader.getLocalEndpoint(), followers.get(1).getLocalEndpoint(), msg -> {
      if (msg instanceof AppendEntriesRequest) {
        aeToCaughtUp.incrementAndGet();
      }
      return msg;
    });
    aeToLagging.set(0);
    aeToCaughtUp.set(0);
    readRaftState(leader, () -> {
      LeaderState ls = leader.state().leaderState();
      FollowerState fs = ls.getFollowerStateOrNull(laggingFollower.getLocalEndpoint());
      fs.matchIndex(0L);
      fs.nextIndex(1L);
      fs.resetRequestBackoff();
      return null;
    });
    leader.getExecutor().execute(leader::sendCatchupAppendsIfNeeded);
    eventually(() -> assertThat(aeToLagging.get()).isGreaterThanOrEqualTo(1));
    leader.getExecutor().execute(() -> assertThat(aeToCaughtUp.get()).isEqualTo(0));
  }
}
