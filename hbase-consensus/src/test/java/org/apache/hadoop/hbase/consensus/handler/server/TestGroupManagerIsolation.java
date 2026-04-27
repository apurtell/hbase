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

import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
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
 * Verifies that a faulting SPI on one group does not stall a sibling group hosted on the same
 * {@link ConsensusServer}. The shared executor pool, transport, scheduler, and log store must not
 * be wedged by an exception thrown out of one group's commit callback.
 */
@Tag(MediumTests.TAG)
public class TestGroupManagerIsolation extends TestBase {

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
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  public void testFaultingGroupDoesNotStarveSiblings() throws Exception {
    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false).build();
    List<RaftEndpoint> members = topo.endpoints();

    List<CountingConsensusSpi> faultySpis = new ArrayList<>();
    List<CountingConsensusSpi> healthySpis = new ArrayList<>();
    for (InJvmConsensusServerTopology.Node n : topo.nodes()) {
      CountingConsensusSpi faulty = new CountingConsensusSpi();
      CountingConsensusSpi healthy = new CountingConsensusSpi();
      n.server().addGroup("faulty", members, faulty);
      n.server().addGroup("healthy", members, healthy);
      faultySpis.add(faulty);
      healthySpis.add(healthy);
    }

    InJvmConsensusServerTopology.Node faultyLeader = waitForLeader("faulty");
    InJvmConsensusServerTopology.Node healthyLeader = waitForLeader("healthy");

    // Drive replication on the healthy leader, prove a baseline commit before injecting fault.
    healthyLeader.server().getGroup("healthy").getRaftNode()
      .replicate("baseline".getBytes(StandardCharsets.UTF_8)).join();
    eventually(() -> {
      long total = 0;
      for (CountingConsensusSpi s : healthySpis) {
        total += s.stats("healthy").getCommitEntries();
      }
      assertThat(total).isGreaterThanOrEqualTo(3L);
    });

    // Inject a fault on every replica of the faulty group; subsequent commits will throw.
    for (CountingConsensusSpi s : faultySpis) {
      s.setThrowOnCommit(true);
    }
    // Best-effort: drive the faulty group; the future may complete normally or exceptionally,
    // but neither outcome should block the healthy group below.
    try {
      faultyLeader.server().getGroup("faulty").getRaftNode()
        .replicate("fault-trigger".getBytes(StandardCharsets.UTF_8)).get(10, TimeUnit.SECONDS);
    } catch (RuntimeException | ExecutionException | TimeoutException ignored) {
      // tolerated
    }

    // Healthy group must continue replicating and committing despite the sibling's faults.
    long before = sumCommitEntries(healthySpis, "healthy");
    for (int i = 0; i < 32; i++) {
      healthyLeader.server().getGroup("healthy").getRaftNode()
        .replicate(("after-fault-" + i).getBytes(StandardCharsets.UTF_8)).join();
    }
    eventually(() -> {
      long after = sumCommitEntries(healthySpis, "healthy");
      assertThat(after - before).isGreaterThanOrEqualTo(32L);
    });
  }

  private static long sumCommitEntries(List<CountingConsensusSpi> spis, String groupId) {
    long total = 0;
    for (CountingConsensusSpi s : spis) {
      total += s.stats(groupId).getCommitEntries();
    }
    return total;
  }

  private InJvmConsensusServerTopology.Node waitForLeader(String groupId) {
    InJvmConsensusServerTopology.Node[] holder = new InJvmConsensusServerTopology.Node[1];
    eventually(() -> {
      for (InJvmConsensusServerTopology.Node n : topo.nodes()) {
        GroupHandle h = n.server().getGroup(groupId);
        assertThat(h).isNotNull();
        RaftNodeReport report = h.getRaftNode().getReport().join().getResult();
        if (report.getRole() == RaftRole.LEADER) {
          holder[0] = n;
          return;
        }
      }
      throw new AssertionError("no leader for " + groupId);
    });
    return holder[0];
  }
}
