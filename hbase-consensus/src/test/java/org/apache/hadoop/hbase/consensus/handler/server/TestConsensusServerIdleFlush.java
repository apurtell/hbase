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
package org.apache.hadoop.hbase.consensus.handler.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validates that an idle leader synthesizes a {@code FlushMarker} once the configured idle window
 * elapses, even though no application traffic is flowing. This is the safety valve that lets the
 * {@code UnifiedRaftStore}'s per-group GC frontier advance for groups that have gone dormant; the
 * concern motivating this test is that in production a 256&nbsp;MB segment is reclaimed only once
 * <em>every</em> group it references has advanced its per-group max-log-index past the segment via
 * a {@code FLUSH_COMPLETE} marker, and a quiescent group that never re-flushes pins those segments
 * forever.
 * <p>
 * The test wires a 3-node {@link InJvmConsensusServerTopology} with a short
 * {@link RaftConfig#getIdleFlushIntervalMillis() idle-flush window} and verifies that:
 * <ol>
 * <li>While the leader is actively driving traffic the wheel does <em>not</em> dispatch synthetic
 * flushes (the gate trips on inactivity, not just on a tick).</li>
 * <li>After replicate traffic stops, the wheel observes the leader's
 * {@code lastReplicateActivityMillis} sit still past {@code idleFlushIntervalMillis}, asks the
 * supplier installed by {@link ConsensusServer} for a fresh {@code FlushMarker}, dispatches it
 * through {@code RaftNodeImpl.proposeIdleFlush(...)}, and the marker commits across the quorum so
 * every node's {@code StateMachineAdapter.onFlushComplete} fires at least once.</li>
 * </ol>
 * The wheel's per-group rate-limit (one synthetic flush per idle window) is also exercised: even
 * when the post-idle wait spans multiple ticks, the test asserts only a small bounded number of
 * synthetic flushes appear within one window.
 */
@Tag(MediumTests.TAG)
public class TestConsensusServerIdleFlush extends TestBase {

  /**
   * Idle-flush window for this test. Picked short enough that the test completes within the JUnit
   * timeout, but long enough that the post-load wait can comfortably observe both the synthetic
   * flush and a follow-up rate-limit-suppressed tick.
   */
  private static final long IDLE_FLUSH_INTERVAL_MS = 300L;

  /**
   * RaftConfig with idle-flush turned on at {@link #IDLE_FLUSH_INTERVAL_MS}, layered on top of the
   * fast-elect timings used by every other in-JVM test (election timeout 500 ms, heartbeat period
   * 100 ms, heartbeat timeout 1500 ms).
   */
  private static final RaftConfig IDLE_FLUSH_RAFT_CONFIG =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(500).setLeaderHeartbeatPeriodMillis(100)
      .setLeaderHeartbeatTimeoutMillis(1500).setIdleFlushEnabled(true)
      .setIdleFlushIntervalMillis(IDLE_FLUSH_INTERVAL_MS).build();

  @TempDir
  Path tmp;

  private InJvmConsensusServerTopology topo;

  @AfterEach
  public void tearDown() {
    if (topo != null) {
      topo.close();
      topo = null;
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testIdleLeaderSynthesizesFlushAcrossQuorum() throws Exception {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false)
      .setBaseConf(conf).setRaftConfig(IDLE_FLUSH_RAFT_CONFIG).build();

    final List<InJvmConsensusServerTopology.Node> nodes = topo.nodes();
    final List<RaftEndpoint> members = topo.endpoints();
    final CountingConsensusSpi[] spis = new CountingConsensusSpi[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      spis[i] = new CountingConsensusSpi();
    }
    final List<GroupHandle> handles = new ArrayList<>(nodes.size());
    for (int s = 0; s < nodes.size(); s++) {
      handles.add(nodes.get(s).server().addGroup("g0", members, spis[s]));
    }

    // Wait for first leader on the group via any server's spi latch.
    boolean elected = false;
    long electDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    for (CountingConsensusSpi spi : spis) {
      long remainingMs =
        Math.max(0L, TimeUnit.NANOSECONDS.toMillis(electDeadlineNs - System.nanoTime()));
      if (spi.stats("g0").awaitLeaderElected(remainingMs, TimeUnit.MILLISECONDS)) {
        elected = true;
        break;
      }
    }
    assertThat(elected).as("leader elected on g0 within 15 s").isTrue();

    GroupHandle leader = resolveLeader(handles);
    assertThat(leader).as("leader handle resolved").isNotNull();

    // Drive a small burst of replicates so the leader's lastApplied moves past snapshotIndex.
    // Without any committed entries the synthetic-flush eligibility check short-circuits because a
    // FlushMarker on top of an already-snapshot-covered log would not advance the per-group GC
    // frontier; the test would then be vacuously satisfied. Five small payloads is enough to push
    // commitIndex (and applied) clearly above the freshly-loaded snapshotIndex of zero.
    final byte[] payload = new byte[64];
    for (int i = 0; i < 5; i++) {
      payload[0] = (byte) i;
      leader.getRaftNode().replicate(payload).get(8, TimeUnit.SECONDS);
    }

    // Snapshot baseline flush counts before going idle so the post-idle assertion measures the
    // synthetic flushes specifically rather than any incidental ones from the warm-up.
    final long[] preIdleFlushes = new long[spis.length];
    for (int i = 0; i < spis.length; i++) {
      preIdleFlushes[i] = spis[i].stats("g0").getFlushes();
    }

    // Sit idle long enough for the wheel to (a) observe lastReplicateActivityMillis stale past
    // IDLE_FLUSH_INTERVAL_MS, (b) dispatch a synthetic FlushMarker via proposeIdleFlush, (c) get
    // the marker committed across the quorum, and (d) get the marker applied through every node's
    // StateMachineAdapter so every per-server CountingConsensusSpi.onFlushComplete fires at least
    // once. Five times the idle window is comfortable headroom on the in-JVM topology.
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(IDLE_FLUSH_INTERVAL_MS * 5));

    // Every node must have observed at least one synthetic flush boundary. We assert per-node so a
    // regression that drops the marker on followers (e.g., codec missing) shows up as a partial
    // pass rather than a blanket fail.
    for (int i = 0; i < spis.length; i++) {
      long postIdle = spis[i].stats("g0").getFlushes();
      assertThat(postIdle - preIdleFlushes[i])
        .as("synthetic-flush onFlushComplete fired on node %d (pre=%d post=%d)", i,
          preIdleFlushes[i], postIdle)
        .isGreaterThanOrEqualTo(1L);
    }

    // The wheel rate-limits one synthetic flush per idle window; sleeping ~5 windows must produce
    // a small bounded number of flushes, not one per heartbeat tick. With heartbeat-tick period of
    // 100 ms and IDLE_FLUSH_INTERVAL_MS=300 ms, an unrate-limited bug would produce ~15 flushes in
    // the same wait. Allow up to 2x the expected number of windows for jitter / clock granularity.
    final long maxAllowedFlushes = 12L;
    for (int i = 0; i < spis.length; i++) {
      long postIdle = spis[i].stats("g0").getFlushes();
      assertThat(postIdle - preIdleFlushes[i])
        .as("rate-limited synthetic-flush count on node %d", i)
        .isLessThanOrEqualTo(maxAllowedFlushes);
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testActiveLeaderDoesNotSynthesizeFlush() throws Exception {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false)
      .setBaseConf(conf).setRaftConfig(IDLE_FLUSH_RAFT_CONFIG).build();

    final List<InJvmConsensusServerTopology.Node> nodes = topo.nodes();
    final List<RaftEndpoint> members = topo.endpoints();
    final CountingConsensusSpi[] spis = new CountingConsensusSpi[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      spis[i] = new CountingConsensusSpi();
    }
    final List<GroupHandle> handles = new ArrayList<>(nodes.size());
    for (int s = 0; s < nodes.size(); s++) {
      handles.add(nodes.get(s).server().addGroup("g0", members, spis[s]));
    }
    boolean elected = false;
    long electDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    for (CountingConsensusSpi spi : spis) {
      long remainingMs =
        Math.max(0L, TimeUnit.NANOSECONDS.toMillis(electDeadlineNs - System.nanoTime()));
      if (spi.stats("g0").awaitLeaderElected(remainingMs, TimeUnit.MILLISECONDS)) {
        elected = true;
        break;
      }
    }
    assertThat(elected).as("leader elected on g0 within 15 s").isTrue();
    GroupHandle leader = resolveLeader(handles);
    assertThat(leader).as("leader handle resolved").isNotNull();

    // Drive replicates continuously for ~3 idle windows. Every replicate calls
    // RaftNodeImpl.wake() which refreshes lastReplicateActivityMillis, so the wheel's idle-gate
    // must keep tripping false and produce zero synthetic flushes.
    final byte[] payload = new byte[64];
    long endNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IDLE_FLUSH_INTERVAL_MS * 3);
    int seq = 0;
    while (System.nanoTime() < endNs) {
      payload[0] = (byte) seq++;
      leader.getRaftNode().replicate(payload).get(8, TimeUnit.SECONDS);
      // Brief pause inside the window so we are not pegging the leader's mailbox; we just want a
      // steady drip well below IDLE_FLUSH_INTERVAL_MS / N spacing.
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
    }
    for (int i = 0; i < spis.length; i++) {
      long flushes = spis[i].stats("g0").getFlushes();
      assertThat(flushes).as("active-leader synthetic-flush count on node %d", i).isZero();
    }
  }

  private static GroupHandle resolveLeader(List<GroupHandle> handles) throws InterruptedException {
    long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadlineNs) {
      for (GroupHandle h : handles) {
        RaftNodeReport report = h.getRaftNode().getReport().join().getResult();
        if (report.getRole() == RaftRole.LEADER) {
          return h;
        }
      }
      Thread.sleep(50L);
    }
    return null;
  }
}
