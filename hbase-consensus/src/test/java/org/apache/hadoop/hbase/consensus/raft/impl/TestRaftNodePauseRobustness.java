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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the JVM-pause detector.
 * <p>
 * The detector treats a sweeper-attested wall-clock delta in
 * {@code [pauseDetectionThresholdMillis, pauseToleranceCapMillis]} (defaults: 1 s and 5 s) as an
 * absorbed JVM pause, sliding {@link LeaderState#leaseExpiryMillis(long) leaseExpiryMillis},
 * {@code lastLeaderHeartbeatTimestamp}, and {@code lastElectionTimerResetTimestamp} forward by the
 * pause delta so the resuming JVM does not see its lease appear instantly expired. Pauses outside
 * the band fall through to the normal lease / election logic.
 */
@Tag(SmallTests.TAG)
public class TestRaftNodePauseRobustness extends TestBase {
  private LocalRaftGroup group;

  // Long heartbeat period + timeout so the natural sweeper does not preempt our explicit
  // absorbPauseIfDetected calls. Election round timeout stays short so a first-round split-vote
  // recovers in ~2 s instead of stretching the test out to the heartbeat-timeout horizon.
  // Default pause-detection threshold (1000) and cap (5000) in effect.
  private static final RaftConfig CONFIG =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(2_000)
      .setLeaderHeartbeatPeriodMillis(60_000).setLeaderHeartbeatTimeoutMillis(120_000).build();

  @AfterEach
  public void tearDown() {
    if (group != null) {
      group.destroy();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testFourSecondPauseHintBumpsLeaseAndKeepsLeader() throws Exception {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();

    long leaseBefore = readLeaseExpiry(leader);
    runHeartbeatTickWithHint(leader, 4_000L);
    long leaseAfter = readLeaseExpiry(leader);

    // 4 s sits inside [threshold=1000, cap=5000] so the absorber bumps the lease forward by
    // exactly the hint.
    assertThat(leaseAfter - leaseBefore).isEqualTo(4_000L);
    assertThat(leader.state().role()).isEqualTo(RaftRole.LEADER);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testSixSecondPauseHintExceedsCapAndDoesNotBumpLease() throws Exception {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();

    long leaseBefore = readLeaseExpiry(leader);
    runHeartbeatTickWithHint(leader, 6_000L);
    long leaseAfter = readLeaseExpiry(leader);

    // 6 s exceeds cap=5000 so the absorber declines the hint; the lease is left untouched and
    // a real pause of this size will trip normal lease-expiry / re-election.
    assertThat(leaseAfter).isEqualTo(leaseBefore);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testSubThresholdHintIsScheduleJitterAndDoesNotBumpLease() throws Exception {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();

    long leaseBefore = readLeaseExpiry(leader);
    runHeartbeatTickWithHint(leader, 500L);
    long leaseAfter = readLeaseExpiry(leader);

    // 500 ms sits below threshold=1000 - that is ordinary scheduling jitter, not a pause
    assertThat(leaseAfter).isEqualTo(leaseBefore);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testFollowersDoNotPreVoteOnAbsorbedPause() throws Exception {
    group = LocalRaftGroup.newBuilder(3).setConfig(CONFIG).start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint());

    // Drive every node through one absorbPauseIfDetected pass with a 4 s hint. A follower's
    // pause-absorber bumps lastElectionTimerResetTimestamp, so even with the heartbeat timeout
    // effectively frozen on the wall clock, no follower should start a pre-vote round.
    runHeartbeatTickWithHint(leader, 4_000L);
    for (RaftNodeImpl follower : followers) {
      runHeartbeatTickWithHint(follower, 4_000L);
    }

    eventually(() -> {
      for (RaftNodeImpl follower : followers) {
        assertThat(follower.state().role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(follower.state().preCandidateState()).isNull();
      }
      assertThat(leader.state().role()).isEqualTo(RaftRole.LEADER);
    });
  }

  /**
   * Submits {@link RaftNodeImpl#absorbPauseIfDetected(long, long)} on the node's executor and
   * blocks until the task completes. The control-lane ordering keeps the call serial with all other
   * state mutations, so post-call state reads from this method's caller observe the absorbed delta.
   */
  private static void runHeartbeatTickWithHint(RaftNodeImpl node, long hint) throws Exception {
    CompletableFuture<Void> done = new CompletableFuture<>();
    node.getExecutor().execute(() -> {
      try {
        node.absorbPauseIfDetected(System.currentTimeMillis(), hint);
        done.complete(null);
      } catch (Throwable t) {
        done.completeExceptionally(t);
      }
    });
    done.get(30, TimeUnit.SECONDS);
  }

  /**
   * Reads the leader's current {@link LeaderState#leaseExpiryMillis()} on the node's executor
   * thread (the only thread allowed to mutate it). Fails the test if the node is not the leader.
   */
  private static long readLeaseExpiry(RaftNodeImpl leader) throws Exception {
    CompletableFuture<Long> result = new CompletableFuture<>();
    leader.getExecutor().execute(() -> {
      try {
        LeaderState ls = leader.state().leaderState();
        if (ls == null) {
          result.completeExceptionally(new AssertionError("expected leader, but leaderState=null"));
          return;
        }
        result.complete(ls.leaseExpiryMillis());
      } catch (Throwable t) {
        result.completeExceptionally(t);
      }
    });
    return result.get(30, TimeUnit.SECONDS);
  }
}
