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
package org.apache.hadoop.hbase.consensus.raft.impl.handler;

import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.log.BaseLogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag(SmallTests.TAG)
public class TestVoteRequestHandlerTimerReset extends TestBase {
  private LocalRaftGroup group;

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testGrantedVoteResetsTimer() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(10_000)
      .setLeaderHeartbeatPeriodMillis(1000).setLeaderHeartbeatTimeoutMillis(2000).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).enableNewTermOperation().start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("x")).join();
    RaftNodeImpl follower = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0);
    group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(), follower.getLocalEndpoint());
    group.dropMessagesTo(leader.getLocalEndpoint(), follower.getLocalEndpoint(),
      InstallSnapshotRequest.class);
    eventually(() -> assertThat(follower.isLeaderHeartbeatTimeoutElapsed()).isTrue());
    BaseLogEntry last = follower.state().log().lastLogOrSnapshotEntry();
    int candidateTerm = follower.getTerm().getTerm() + 1;
    VoteRequest request = follower.getModelFactory().createVoteRequestBuilder()
      .setGroupId(follower.getGroupId()).setSender(leader.getLocalEndpoint()).setTerm(candidateTerm)
      .setLastLogTerm(last.getTerm()).setLastLogIndex(last.getIndex()).setSticky(false).build();
    follower.handle(request);
    // handle() is asynchronous. Wait until the grant has been processed and the
    // election timer reset. The leader-heartbeat timer must remain elapsed
    // (a vote grant is not a real heartbeat), but the local
    // election-timer-reset timestamp must have been refreshed so HeartbeatTask
    // does not preempt the candidate.
    eventually(() -> {
      assertThat(follower.getTerm().getTerm()).isGreaterThanOrEqualTo(candidateTerm);
      assertThat(follower.isElectionTimerElapsed()).isFalse();
    });
    assertThat(follower.isLeaderHeartbeatTimeoutElapsed()).isTrue();
  }
}
