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
package org.apache.hadoop.hbase.consensus.handler.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.hbase.consensus.handler.server.ConsensusServerMetrics;
import org.apache.hadoop.hbase.consensus.handler.server.ConsensusServerMetricsWrapper;
import org.apache.hadoop.hbase.consensus.handler.statemachine.InMemoryConsensusSpi.LeaderElection;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.report.RaftLogStats;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.report.RaftTerm;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LeaderReportListener}: leader-election dedup, no-leader transitions, and
 * lagging-follower notifications with cooldown.
 */
@Tag(SmallTests.TAG)
public class TestLeaderReportListener extends TestBase {

  private static final Object GROUP_ID = "g1";

  private final AtomicLong now = new AtomicLong(0L);

  private ConsensusServerMetrics metrics;

  @AfterEach
  public void closeMetrics() {
    if (metrics != null) {
      metrics.close();
      metrics = null;
    }
  }

  private RaftNodeReport report(RaftRole role, int term, @Nullable RaftEndpoint leader,
    long lastSnapshotIndex, Map<RaftEndpoint, Long> followerMatchIndices) {
    return report(role, term, leader, lastSnapshotIndex, followerMatchIndices, 0L, 0L, 0L, 0L);
  }

  private RaftNodeReport report(RaftRole role, int term, @Nullable RaftEndpoint leader,
    long lastSnapshotIndex, Map<RaftEndpoint, Long> followerMatchIndices, long commitIndex,
    long lastLogOrSnapshotIndex, long quorumHeartbeatTs, long leaderHeartbeatTs) {
    RaftTerm raftTerm = mock(RaftTerm.class);
    when(raftTerm.getTerm()).thenReturn(term);
    when(raftTerm.getLeaderEndpoint()).thenReturn(leader);

    RaftLogStats log = mock(RaftLogStats.class);
    when(log.getLastSnapshotIndex()).thenReturn(lastSnapshotIndex);
    when(log.getFollowerMatchIndices()).thenReturn(followerMatchIndices);
    when(log.getCommitIndex()).thenReturn(commitIndex);
    when(log.getLastLogOrSnapshotIndex()).thenReturn(lastLogOrSnapshotIndex);

    RaftNodeReport rpt = mock(RaftNodeReport.class);
    when(rpt.getGroupId()).thenReturn(GROUP_ID);
    when(rpt.getRole()).thenReturn(role);
    when(rpt.getTerm()).thenReturn(raftTerm);
    when(rpt.getLog()).thenReturn(log);
    when(rpt.getQuorumHeartbeatTimestamp()).thenReturn(quorumHeartbeatTs);
    when(rpt.getLeaderHeartbeatTimestamp()).thenReturn(leaderHeartbeatTs);
    return rpt;
  }

  private static ConsensusServerMetrics newMetrics(String endpointId) {
    return new ConsensusServerMetrics(new ConsensusServerMetricsWrapper() {
      @Override
      public String getEndpointId() {
        return endpointId;
      }

      @Override
      public int getActiveGroups() {
        return 0;
      }

      @Override
      public int getMaxGroups() {
        return 0;
      }

      @Override
      public long getRestoredGroups() {
        return 0L;
      }

      @Override
      public String getLifecycleState() {
        return "TEST";
      }
    });
  }

  @Test
  public void testLeaderElectedFiresOncePerTerm() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();

    listener.accept(report(RaftRole.FOLLOWER, 1, leader, 0L, new HashMap<>()));
    listener.accept(report(RaftRole.FOLLOWER, 1, leader, 0L, new HashMap<>()));
    listener.accept(report(RaftRole.FOLLOWER, 1, leader, 0L, new HashMap<>()));

    assertThat(spi.leaderElections(GROUP_ID)).hasSize(1);
    LeaderElection e = spi.leaderElections(GROUP_ID).get(0);
    assertThat(e.term).isEqualTo(1);
    assertThat(e.leader).isEqualTo(leader);
  }

  @Test
  public void testLeaderElectedFiresAgainOnNewTerm() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get);
    RaftEndpoint leader1 = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint leader2 = LocalRaftEndpoint.newEndpoint();

    listener.accept(report(RaftRole.FOLLOWER, 1, leader1, 0L, new HashMap<>()));
    listener.accept(report(RaftRole.FOLLOWER, 2, leader2, 0L, new HashMap<>()));
    listener.accept(report(RaftRole.FOLLOWER, 2, leader2, 0L, new HashMap<>()));

    assertThat(spi.leaderElections(GROUP_ID)).hasSize(2);
    assertThat(spi.leaderElections(GROUP_ID).get(0).term).isEqualTo(1);
    assertThat(spi.leaderElections(GROUP_ID).get(1).term).isEqualTo(2);
  }

  @Test
  public void testLeaderElectedFiresAgainOnLeaderChangeSameTerm() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get);
    RaftEndpoint leader1 = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint leader2 = LocalRaftEndpoint.newEndpoint();

    listener.accept(report(RaftRole.FOLLOWER, 5, leader1, 0L, new HashMap<>()));
    listener.accept(report(RaftRole.FOLLOWER, 5, leader2, 0L, new HashMap<>()));

    assertThat(spi.leaderElections(GROUP_ID)).hasSize(2);
    assertThat(spi.leaderElections(GROUP_ID).get(0).leader).isEqualTo(leader1);
    assertThat(spi.leaderElections(GROUP_ID).get(1).leader).isEqualTo(leader2);
  }

  @Test
  public void testNoLeaderFiresOnceOnTransition() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();

    listener.accept(report(RaftRole.FOLLOWER, 1, leader, 0L, new HashMap<>()));
    listener.accept(report(RaftRole.FOLLOWER, 1, null, 0L, new HashMap<>()));
    listener.accept(report(RaftRole.FOLLOWER, 1, null, 0L, new HashMap<>()));

    assertThat(spi.noLeaderEvents(GROUP_ID)).isEqualTo(1);
  }

  @Test
  public void testNoLeaderNotFiredWithoutPriorLeader() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get);

    listener.accept(report(RaftRole.FOLLOWER, 1, null, 0L, new HashMap<>()));
    listener.accept(report(RaftRole.FOLLOWER, 1, null, 0L, new HashMap<>()));

    assertThat(spi.noLeaderEvents(GROUP_ID)).isZero();
  }

  @Test
  public void testFollowerLaggingFiredOnceUntilCooldownElapses() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 1000L, now::get);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint slow = LocalRaftEndpoint.newEndpoint();

    Map<RaftEndpoint, Long> matches = new LinkedHashMap<>();
    matches.put(slow, 5L);

    now.set(0L);
    listener.accept(report(RaftRole.LEADER, 1, leader, 100L, matches));
    now.set(500L);
    listener.accept(report(RaftRole.LEADER, 1, leader, 100L, matches));
    now.set(999L);
    listener.accept(report(RaftRole.LEADER, 1, leader, 100L, matches));

    assertThat(spi.laggingFollowers(GROUP_ID)).containsExactly(slow);

    now.set(1000L);
    listener.accept(report(RaftRole.LEADER, 1, leader, 100L, matches));
    assertThat(spi.laggingFollowers(GROUP_ID)).containsExactly(slow, slow);
  }

  @Test
  public void testFollowerLaggingClearedWhenFollowerCatchesUp() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 10_000L, now::get);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint slow = LocalRaftEndpoint.newEndpoint();

    Map<RaftEndpoint, Long> behind = new LinkedHashMap<>();
    behind.put(slow, 5L);
    Map<RaftEndpoint, Long> caughtUp = new LinkedHashMap<>();
    caughtUp.put(slow, 200L);

    listener.accept(report(RaftRole.LEADER, 1, leader, 100L, behind));
    listener.accept(report(RaftRole.LEADER, 1, leader, 100L, caughtUp));
    listener.accept(report(RaftRole.LEADER, 1, leader, 100L, behind));

    assertThat(spi.laggingFollowers(GROUP_ID)).containsExactly(slow, slow);
  }

  @Test
  public void testFollowerLaggingNotFiredFromFollowerRole() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint slow = LocalRaftEndpoint.newEndpoint();

    Map<RaftEndpoint, Long> matches = new LinkedHashMap<>();
    matches.put(slow, 5L);

    listener.accept(report(RaftRole.FOLLOWER, 1, leader, 100L, matches));

    assertThat(spi.laggingFollowers(GROUP_ID)).isEmpty();
  }

  @Test
  public void testNegativeCooldownRejected() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    assertThatThrownBy(() -> new LeaderReportListener(spi, -1L, now::get))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(">= 0");
  }

  @Test
  public void testCommitBacklogSampledOnEveryTick() {
    metrics = newMetrics("lr-1");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get, metrics);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();

    listener
      .accept(report(RaftRole.FOLLOWER, 1, leader, 0L, Collections.emptyMap(), 4L, 10L, 0L, 0L));

    assertThat(metrics.getCommitBacklogHistogram().getCount()).isEqualTo(1);
    assertThat(metrics.getCommitBacklogHistogram().snapshot().getMax()).isEqualTo(6);
  }

  @Test
  public void testQuorumHeartbeatLagSampledOnLeader() {
    metrics = newMetrics("lr-2");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get, metrics);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();

    now.set(1_000L);
    listener
      .accept(report(RaftRole.LEADER, 1, leader, 0L, Collections.emptyMap(), 0L, 0L, 950L, 0L));

    assertThat(metrics.getQuorumHeartbeatLagHistogram().getCount()).isEqualTo(1);
    assertThat(metrics.getQuorumHeartbeatLagHistogram().snapshot().getMax()).isEqualTo(50);
    assertThat(metrics.getLeaderHeartbeatLagHistogram().getCount())
      .as("LEADER role must not record leader-heartbeat lag (leader-side has no inbound heartbeat)")
      .isZero();
  }

  @Test
  public void testReplicationLagSampledFromSlowestFollower() {
    metrics = newMetrics("lr-3");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get, metrics);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint a = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint b = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint c = LocalRaftEndpoint.newEndpoint();

    Map<RaftEndpoint, Long> matches = new LinkedHashMap<>();
    matches.put(a, 8L);
    matches.put(b, 5L);
    matches.put(c, 9L);

    listener.accept(report(RaftRole.LEADER, 1, leader, 0L, matches, 5L, 10L, 0L, 0L));

    assertThat(metrics.getReplicationLagHistogram().getCount()).isEqualTo(1);
    assertThat(metrics.getReplicationLagHistogram().snapshot().getMax())
      .as("slowest follower (b) is 5 entries behind lastLogIndex=10").isEqualTo(5);
  }

  @Test
  public void testLeaderHeartbeatLagSampledOnFollower() {
    metrics = newMetrics("lr-4");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get, metrics);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();

    now.set(2_000L);
    listener
      .accept(report(RaftRole.FOLLOWER, 1, leader, 0L, Collections.emptyMap(), 0L, 0L, 0L, 1_975L));

    assertThat(metrics.getLeaderHeartbeatLagHistogram().getCount()).isEqualTo(1);
    assertThat(metrics.getLeaderHeartbeatLagHistogram().snapshot().getMax()).isEqualTo(25);
    assertThat(metrics.getQuorumHeartbeatLagHistogram().getCount())
      .as("FOLLOWER role must not record quorum-heartbeat lag").isZero();
  }

  @Test
  public void testLeaderElectionAndNoLeaderCounters() {
    metrics = newMetrics("lr-5");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    LeaderReportListener listener = new LeaderReportListener(spi, 0L, now::get, metrics);
    RaftEndpoint leader = LocalRaftEndpoint.newEndpoint();

    listener.accept(report(RaftRole.FOLLOWER, 1, leader, 0L, Collections.emptyMap()));
    listener.accept(report(RaftRole.FOLLOWER, 1, leader, 0L, Collections.emptyMap()));
    listener.accept(report(RaftRole.FOLLOWER, 1, null, 0L, Collections.emptyMap()));

    assertThat(metrics.getLeaderElectionsCount())
      .as("idempotent re-fire across same (term, leader)").isEqualTo(1);
    assertThat(metrics.getNoLeaderEventsCount()).isEqualTo(1);
  }
}
