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
package org.apache.hadoop.hbase.consensus.handler.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestSweepingHeartbeatSchedulerLifecycle extends TestBase {
  private LocalRaftGroup group;
  private SweepingHeartbeatScheduler scheduler;

  @AfterEach
  public void tearDown() {
    if (scheduler != null) {
      scheduler.close();
    }
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  public void testPrimitiveCtorValidatesInterval() {
    assertThatThrownBy(() -> new SweepingHeartbeatScheduler(0, 1))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("intervalMs");
    assertThatThrownBy(() -> new SweepingHeartbeatScheduler(-5, 1))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("intervalMs");
  }

  @Test
  public void testPrimitiveCtorValidatesThreads() {
    assertThatThrownBy(() -> new SweepingHeartbeatScheduler(50, 0))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timerThreads");
  }

  @Test
  public void testStartStopCloseAreIdempotent() {
    scheduler = new SweepingHeartbeatScheduler(50, 1);
    scheduler.start();
    scheduler.start();
    scheduler.stop();
    scheduler.stop();
    scheduler.close();
    scheduler.close();
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testRegisterAfterCloseThrows() {
    scheduler = new SweepingHeartbeatScheduler(50, 1);
    scheduler.close();
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    final SweepingHeartbeatScheduler s = scheduler;
    assertThatThrownBy(() -> s.register(leader)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testDoubleRegisterSamePairIsNoOp() {
    scheduler = new SweepingHeartbeatScheduler(50, 1);
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int before = scheduler.registeredGroups();
    scheduler.register(leader);
    scheduler.register(leader);
    assertThat(scheduler.registeredGroups()).isEqualTo(before + 1);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testRegisterDifferentNodeForSameGroupIdThrows() {
    scheduler = new SweepingHeartbeatScheduler(50, 1);
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl other = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0);
    scheduler.register(leader);
    final SweepingHeartbeatScheduler s = scheduler;
    assertThatThrownBy(() -> s.register(other)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testUnregisterUnknownIsNoOp() {
    scheduler = new SweepingHeartbeatScheduler(50, 1);
    group = LocalRaftGroup.newBuilder(3).setConfig(RaftTestUtils.TEST_RAFT_CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    assertThatCode(() -> scheduler.unregister(leader)).doesNotThrowAnyException();
  }
}
