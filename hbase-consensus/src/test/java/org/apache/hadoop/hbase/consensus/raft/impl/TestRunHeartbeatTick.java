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

import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestRunHeartbeatTick extends TestBase {
  private LocalRaftGroup group;

  // Long election + heartbeat timeouts so the natural HeartbeatTask doesn't preempt our explicit
  // runHeartbeatTick() invocations.
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
  public void testLeaderPathBroadcastsLeaderHeartbeatPerFollower() {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    ConcurrentMap<RaftEndpoint, AtomicInteger> hbCounts = new ConcurrentHashMap<>();
    for (RaftNodeImpl follower : followers) {
      AtomicInteger hb = new AtomicInteger();
      hbCounts.put(follower.getLocalEndpoint(), hb);
      group.alterMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(), msg -> {
        if (msg instanceof LeaderHeartbeat) {
          hb.incrementAndGet();
        }
        return msg;
      });
    }
    leader.getExecutor().execute(leader::runHeartbeatTick);
    eventually(() -> {
      for (AtomicInteger c : hbCounts.values()) {
        assertThat(c.get()).isGreaterThanOrEqualTo(1);
      }
    });
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testRunHeartbeatTickIsIdempotent() {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());
    AtomicInteger hbCount = new AtomicInteger();
    for (RaftNodeImpl follower : followers) {
      group.alterMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(), msg -> {
        if (msg instanceof LeaderHeartbeat) {
          hbCount.incrementAndGet();
        }
        return msg;
      });
    }
    int ticks = 5;
    int peers = followers.size();
    for (int i = 0; i < ticks; i++) {
      leader.getExecutor().execute(leader::runHeartbeatTick);
    }
    eventually(() -> assertThat(hbCount.get()).isGreaterThanOrEqualTo(ticks * peers));
  }
}
