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
package org.apache.hadoop.hbase.consensus.raft.impl.state;

import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestLeaderLease extends TestBase {
  private LocalRaftGroup group;

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  public void testRejectInvalidLeaseConfig() {
    assertThatThrownBy(() -> RaftConfig.newBuilder().setLeaderHeartbeatPeriodMillis(1000)
      .setLeaderHeartbeatTimeoutMillis(400).setMaxClockDriftMillis(200).build())
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testLeaseExpiryMonotonic() {
    LeaderState ls = new LeaderState(Collections.emptyList(), 0, 0L);
    ls.leaseExpiryMillis(10L);
    // A lower value must not lower the lease (pause-absorber + in-flight ack races make this
    // legitimately reachable).
    assertThatNoException().isThrownBy(() -> ls.leaseExpiryMillis(5L));
    assertThat(ls.leaseExpiryMillis()).isEqualTo(10L);
    // A higher value advances normally.
    ls.leaseExpiryMillis(20L);
    assertThat(ls.leaseExpiryMillis()).isEqualTo(20L);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testLeaseExpiryStepsDown() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(5000)
      .setLeaderHeartbeatPeriodMillis(1000).setLeaderHeartbeatTimeoutMillis(3000)
      .setMaxClockDriftMillis(100).build();
    assertThat(config.getLeaderLeaseDurationMillis()).isEqualTo(2800L);
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation().start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("warmup")).join();
    assertThat(leader.isLeaderWithValidLease(leader.getClock().millis())).isTrue();
    for (RaftNodeImpl follower : group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint())) {
      group.dropAllMessagesTo(follower.getLocalEndpoint(), leader.getLocalEndpoint());
    }
    eventually(() -> {
      assertThat(getRole(leader)).isEqualTo(RaftRole.FOLLOWER);
      assertThat(leader.isLeaderWithValidLease(leader.getClock().millis())).isFalse();
    });
  }
}
