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
package org.apache.hadoop.hbase.consensus.raft.faulttolerance;

import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.QueryPolicy;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class RaftLeaderFailureTest extends BaseTest {
  private LocalRaftGroup group;

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  public void leaderFailure() {
    RaftConfig config = RaftConfig.newBuilder().setLeaderHeartbeatTimeoutMillis(5000).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(config).start();
    RaftNode leader = group.waitUntilLeaderElected();
    // the leader can replicate log entries to the followers, but it won't
    // get any response back since we are blocking responses here, so even
    // though it replicates our operation, it won't be able to commit it
    // and send us the response
    for (RaftNode follower : group.getNodesExcept(leader.getLocalEndpoint())) {
      group.dropMessagesTo(follower.getLocalEndpoint(), leader.getLocalEndpoint(),
        AppendEntriesSuccessResponse.class);
    }
    String value = "value";
    leader.replicate(SimpleStateMachine.applyValue(value));
    // wait until the followers get the log entry by checking their log
    // indices repeatedly
    eventually(() -> {
      RaftNodeReport leaderReport = leader.getReport().join().getResult();
      long leaderLastLogIndex = leaderReport.getLog().getLastLogOrSnapshotIndex();
      assertThat(leaderLastLogIndex).isGreaterThan(0);
      for (RaftNode follower : group.getNodesExcept(leader.getLocalEndpoint())) {
        RaftNodeReport followerReport = follower.getReport().join().getResult();
        long followerLastLogIndex = followerReport.getLog().getLastLogOrSnapshotIndex();
        assertThat(followerLastLogIndex).isEqualTo(leaderLastLogIndex);
      }
    });
    // now the followers have our operation. let's kill the leader
    // now we don't know what happened to our first operation
    group.terminateNode(leader.getLocalEndpoint());
    // we will get a new leader in a second
    RaftNode newLeader = group.waitUntilLeaderElected();
    // we replicate our operation again
    newLeader.replicate(SimpleStateMachine.applyValue(value)).join();
    Ordered<List<String>> queryResult =
      newLeader.<List<String>> query(SimpleStateMachine.queryAllValues(), QueryPolicy.LEADER_LEASE,
        Optional.empty(), Optional.empty()).join();
    // it turns out that our operation is committed twice
    assertThat(queryResult.getResult()).hasSize(2);
  }

  @Test
  public void defaultLeaseInsideTimeout() {
    RaftConfig c = RaftConfig.DEFAULT_RAFT_CONFIG;
    assertThat(c.getLeaderLeaseDurationMillis()).isLessThan(c.getLeaderHeartbeatTimeoutMillis());
    assertThat(c.getLeaderHeartbeatTimeoutMillis()).isGreaterThan(2 * c.getMaxClockDriftMillis());
    assertThat(c.getLeaderLeaseDurationMillis() + 2 * c.getMaxClockDriftMillis())
      .isEqualTo(c.getLeaderHeartbeatTimeoutMillis());
  }
}
