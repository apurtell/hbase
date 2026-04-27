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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
import org.apache.hadoop.hbase.consensus.handler.server.util.MetricRegistryDumper;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.metrics.MetricRegistries;
import org.apache.hadoop.hbase.metrics.MetricRegistry;
import org.apache.hadoop.hbase.metrics.MetricRegistryInfo;
import org.apache.hadoop.hbase.metrics.MetricSet;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validates that {@link ConsensusServerMetrics}: (a) is registered into the JVM-wide
 * {@link MetricRegistries#global()} singleton at server-construction time, (b) records server-side
 * counters and timers as the {@link GroupManager} surface is exercised, (c) walks cleanly through
 * {@link MetricRegistryDumper} producing the stable text format, and (d) is removed from the global
 * registry on {@link ConsensusServer#stop()}.
 */
@Tag(MediumTests.TAG)
public class TestConsensusServerMetrics extends TestBase {

  @TempDir
  Path tmp;

  private InJvmConsensusServerTopology topo;
  /**
   * Holds a stand-alone {@link ConsensusServerMetrics} for tests that exercise the recorder API
   * without spinning up a {@link ConsensusServer}. Topology-based tests must <em>not</em> assign to
   * this field; their metrics object is owned by the {@link ConsensusServer} they construct and is
   * closed by {@code topo.close()}.
   */
  private ConsensusServerMetrics metrics;

  @AfterEach
  public void tearDown() {
    if (metrics != null) {
      metrics.close();
      metrics = null;
    }
    if (topo != null) {
      topo.close();
      topo = null;
    }
  }

  /**
   * Builds a stand-alone {@link ConsensusServerMetrics} backed by a stub
   * {@link ConsensusServerMetricsWrapper} so the recorder-only tests don't have to spin up an
   * entire {@link ConsensusServer}.
   */
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
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testMetricsRegisteredAndRecordedAndRemoved() throws Exception {
    topo = InJvmConsensusServerTopology.builder().setNodeCount(1).setBaseDir(tmp, false).build();
    ConsensusServer server = topo.nodes().get(0).server();
    List<RaftEndpoint> members = topo.endpoints();

    assertThat(MetricRegistries.global().get(server.getMetrics().getRegistryInfo()))
      .as("metrics registry must be in the global singleton while server is up").isPresent();

    assertThat(server.getMetrics().getGroupsAddedCount()).isZero();
    assertThat(server.getMetrics().getGroupsRemovedCount()).isZero();
    assertThat(server.getMetrics().getAddGroupTimer().getHistogram().getCount()).isZero();

    GroupHandle h1 = server.addGroup("g1", members, new CountingConsensusSpi());
    server.addGroup("g2", members, new CountingConsensusSpi());
    assertThat(server.getMetrics().getGroupsAddedCount()).isEqualTo(2);
    assertThat(server.getMetrics().getAddGroupTimer().getHistogram().getCount()).isEqualTo(2);

    GroupHandle h1Again = server.addGroup("g1", members, new CountingConsensusSpi());
    assertThat(h1Again).isSameAs(h1);
    assertThat(server.getMetrics().getGroupsAddedCount())
      .as("idempotent addGroup must not increment groupsAdded").isEqualTo(2);

    server.removeGroup("g2");
    assertThat(server.getMetrics().getGroupsRemovedCount()).isEqualTo(1);
    assertThat(server.getMetrics().getRemoveGroupTimer().getHistogram().getCount()).isEqualTo(1);

    String dump = MetricRegistryDumper.dump("server-1", server.getMetrics().getRegistry());
    assertThat(dump).startsWith("### context=server-1");
    assertThat(dump).contains("counter.groupsAdded=" + server.getMetrics().getGroupsAddedCount());
    assertThat(dump)
      .contains("counter.groupsRemoved=" + server.getMetrics().getGroupsRemovedCount());
    assertThat(dump).contains("gauge.activeGroups=" + server.groupCount());
    assertThat(dump).contains("timer.addGroupTime.count=2");
    assertThat(dump).contains("timer.removeGroupTime.count=1");
    assertThat(dump).contains("timer.replicateTime.count=0");
    assertThat(dump).contains("timer.transferLeadershipTime.count=0");

    // writeDumpsToFile produces a valid text file with a header per server.
    Path out = tmp.resolve("metrics.txt");
    List<Map.Entry<String, ? extends MetricSet>> contexts = new ArrayList<>();
    contexts.add(new AbstractMap.SimpleEntry<>("server-1", server.getMetrics().getRegistry()));
    MetricRegistryDumper.writeDumpsToFile(out, contexts);
    String onDisk = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
    assertThat(onDisk).contains("### context=server-1");
    assertThat(onDisk).contains("counter.groupsAdded=2");

    MetricRegistryInfo info = server.getMetrics().getRegistryInfo();
    server.stop();
    assertThat(MetricRegistries.global().get(info))
      .as("metrics registry must be removed from the global singleton after stop()").isEmpty();
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testFailedAddGroupRecordsFailure() throws Exception {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    conf.setInt(ConsensusServerConfig.MAX_GROUPS_KEY, 1);
    topo = InJvmConsensusServerTopology.builder().setNodeCount(1).setBaseDir(tmp, false)
      .setBaseConf(conf).build();
    ConsensusServer server = topo.nodes().get(0).server();
    List<RaftEndpoint> members = topo.endpoints();

    server.addGroup("g1", members, new CountingConsensusSpi());
    assertThat(server.getMetrics().getGroupsAddedCount()).isEqualTo(1);
    assertThat(server.getMetrics().getAddGroupFailuresCount()).isZero();

    try {
      server.addGroup("g2", members, new CountingConsensusSpi());
    } catch (MaxGroupsExceededException expected) {
      // expected
    }
    assertThat(server.getMetrics().getGroupsAddedCount()).isEqualTo(1);
    assertThat(server.getMetrics().getAddGroupFailuresCount()).isEqualTo(1);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testRecorderApiSelfMeasured() {
    metrics = newMetrics("recorder-1");

    metrics.updateReplicate(1);
    metrics.updateReplicate(5);
    metrics.updateReplicate(10);
    assertThat(metrics.getReplicateTimer().getHistogram().getCount()).isEqualTo(3);

    metrics.incCommit(4, 1024);
    metrics.incCommit(2, 512);
    assertThat(metrics.getCommitBatchesCount()).isEqualTo(2);
    assertThat(metrics.getCommitEntriesCount()).isEqualTo(6);
    assertThat(metrics.getCommitBytesCount()).isEqualTo(1536);

    MetricRegistry registry = metrics.getRegistry();
    assertThat(registry.getMetrics()).containsKeys("groupsAdded", "groupsRemoved", "replicateTime",
      "commitBatches", "activeGroups", "lifecycleState");
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testCommitPipelineLatencyHistograms() {
    metrics = newMetrics("recorder-2");

    // updateCommitApply rolls counters + timer + size/byte histograms together.
    metrics.updateCommitApply(2, 4, 1024);
    metrics.updateCommitApply(7, 1, 256);
    metrics.updateCommitApply(11, 0, 0);
    assertThat(metrics.getCommitBatchesCount()).isEqualTo(3);
    assertThat(metrics.getCommitEntriesCount()).isEqualTo(5);
    assertThat(metrics.getCommitBytesCount()).isEqualTo(1280);
    assertThat(metrics.getCommitApplyTimer().getHistogram().getCount()).isEqualTo(3);
    assertThat(metrics.getCommitBatchSizeHistogram().getCount()).isEqualTo(2);
    assertThat(metrics.getCommitBatchBytesHistogram().getCount()).isEqualTo(2);

    metrics.updateFlushComplete(3);
    metrics.updateFlushComplete(4);
    assertThat(metrics.getFlushCompletesCount()).isEqualTo(2);
    assertThat(metrics.getFlushCompleteTimer().getHistogram().getCount()).isEqualTo(2);

    metrics.updateTakeSnapshot(50);
    metrics.updateInstallSnapshot(75);
    assertThat(metrics.getTakeSnapshotTimer().getHistogram().getCount()).isEqualTo(1);
    assertThat(metrics.getInstallSnapshotTimer().getHistogram().getCount()).isEqualTo(1);

    metrics.recordQuorumHeartbeatLag(8);
    metrics.recordQuorumHeartbeatLag(0);
    metrics.recordQuorumHeartbeatLag(-1);
    assertThat(metrics.getQuorumHeartbeatLagHistogram().getCount())
      .as("negative lag must be ignored, zero must be sampled").isEqualTo(2);

    metrics.recordLeaderHeartbeatLag(15);
    metrics.recordLeaderHeartbeatLag(-5);
    assertThat(metrics.getLeaderHeartbeatLagHistogram().getCount()).isEqualTo(1);

    metrics.recordCommitBacklog(0);
    metrics.recordCommitBacklog(7);
    metrics.recordCommitBacklog(-1);
    assertThat(metrics.getCommitBacklogHistogram().getCount()).isEqualTo(2);

    metrics.recordReplicationLag(3);
    metrics.recordReplicationLag(0);
    metrics.recordReplicationLag(-1);
    assertThat(metrics.getReplicationLagHistogram().getCount()).isEqualTo(2);

    MetricRegistry registry = metrics.getRegistry();
    assertThat(registry.getMetrics()).containsKeys("commitApplyTime", "flushCompleteTime",
      "takeSnapshotTime", "installSnapshotTime", "commitBatchSize", "commitBatchBytes",
      "quorumHeartbeatLagMillis", "leaderHeartbeatLagMillis", "commitBacklogEntries",
      "replicationLagEntries", "flushCompletes");

    String dump = MetricRegistryDumper.dump("server-1", registry);
    assertThat(dump).contains("counter.flushCompletes=2");
    assertThat(dump).contains("histogram.commitBatchSize.count=2");
    assertThat(dump).contains("histogram.quorumHeartbeatLagMillis.count=2");
    assertThat(dump).contains("histogram.commitBacklogEntries.count=2");
    assertThat(dump).contains("timer.commitApplyTime.count=3");
    assertThat(dump).contains("timer.flushCompleteTime.count=2");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testEndToEndCommitWiringPopulatesHistograms() throws Exception {
    // Dial the periodic RaftNodeReport down so the lag histograms get a sample within the test
    // budget.
    RaftConfig fastReport = RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(500)
      .setLeaderHeartbeatPeriodMillis(100).setLeaderHeartbeatTimeoutMillis(1500)
      .setRaftNodeReportPublishPeriodSecs(1).build();
    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false)
      .setRaftConfig(fastReport).build();
    List<RaftEndpoint> members = topo.endpoints();
    Object groupId = "g-e2e";

    // addGroup on every node and pick the elected leader's GroupHandle.
    GroupHandle leaderHandle = null;
    for (InJvmConsensusServerTopology.Node n : topo.nodes()) {
      CountingConsensusSpi spi = new CountingConsensusSpi();
      GroupHandle h = n.server().addGroup(groupId, members, spi);
      if (spi.stats(groupId).awaitLeaderElected(15, TimeUnit.SECONDS) && leaderHandle == null) {
        leaderHandle = h;
      }
    }
    assertThat(leaderHandle).as("at least one node must have observed a leader").isNotNull();

    // Drive a few commits on the leader so the StateMachineAdapter records commitApply samples.
    // (FlushMarker requires a registered OperationCodec entry that this fixture does not install;
    // {@link TestStateMachineAdapter#testFlushCompleteMetricsRecorded} covers the flushComplete
    // wiring directly.)
    for (int i = 0; i < 5; i++) {
      leaderHandle.getRaftNode().replicate(("payload-" + i).getBytes(StandardCharsets.UTF_8))
        .get(10, TimeUnit.SECONDS);
    }

    // The StateMachineAdapter samples are immediate (commit-apply path), so they should be visible
    // on at least one node's metrics. The lag histograms are sampled on the next periodic
    // RaftNodeReport tick (1s), so we wait for them via `eventually`.
    AssertionUtils.eventually(() -> {
      boolean anyCommitApply = false;
      boolean anyCommitBacklog = false;
      boolean anyQuorumLag = false;
      boolean anyLeaderHbLag = false;
      for (InJvmConsensusServerTopology.Node n : topo.nodes()) {
        ConsensusServerMetrics m = n.server().getMetrics();
        if (m.getCommitApplyTimer().getHistogram().getCount() > 0) {
          anyCommitApply = true;
        }
        if (m.getCommitBacklogHistogram().getCount() > 0) {
          anyCommitBacklog = true;
        }
        if (m.getQuorumHeartbeatLagHistogram().getCount() > 0) {
          anyQuorumLag = true;
        }
        if (m.getLeaderHeartbeatLagHistogram().getCount() > 0) {
          anyLeaderHbLag = true;
        }
      }
      assertThat(anyCommitApply).as("commitApplyTime must record on at least one node").isTrue();
      assertThat(anyCommitBacklog).as("commitBacklog must sample on at least one node").isTrue();
      assertThat(anyQuorumLag).as("quorumHeartbeatLag must sample on the leader").isTrue();
      assertThat(anyLeaderHbLag).as("leaderHeartbeatLag must sample on at least one follower")
        .isTrue();
    }, 20);

    // Cross-check a leader-side counter that the LeaderReportListener bridge increments.
    long totalLeaderElections = 0L;
    for (InJvmConsensusServerTopology.Node n : topo.nodes()) {
      totalLeaderElections += n.server().getMetrics().getLeaderElectionsCount();
    }
    assertThat(totalLeaderElections)
      .as("at least one node must have recorded a leader-elected event").isGreaterThan(0L);
  }
}
