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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
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
 * {@link GroupManager#transferLeadership} moves leadership to a named target. Subsequent
 * {@code replicate} on the new leader succeeds. The prior leader is no longer LEADER.
 */
@Tag(MediumTests.TAG)
public class TestGroupManagerLeadershipTransfer extends TestBase {

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
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  public void testTransferLeadershipChangesLeader() throws Exception {
    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false).build();
    List<RaftEndpoint> members = topo.endpoints();
    String groupId = "lt-group";
    for (InJvmConsensusServerTopology.Node n : topo.nodes()) {
      n.server().addGroup(groupId, members, new CountingConsensusSpi());
    }

    InJvmConsensusServerTopology.Node leaderNode = waitForLeader(groupId);
    RaftEndpoint leader = leaderNode.endpoint();

    // Pick a different target.
    RaftEndpoint target = null;
    for (RaftEndpoint ep : members) {
      if (!ep.equals(leader)) {
        target = ep;
        break;
      }
    }
    assertThat(target).isNotNull();

    Ordered<Object> result = leaderNode.server().transferLeadership(groupId, target).join();
    assertThat(result).isNotNull();

    final RaftEndpoint expectedLeader = target;
    eventually(() -> {
      InJvmConsensusServerTopology.Node observed = waitForLeader(groupId);
      assertThat(observed.endpoint()).isEqualTo(expectedLeader);
    });

    // Replicate on the new leader.
    InJvmConsensusServerTopology.Node newLeaderNode = nodeFor(target);
    GroupHandle h = newLeaderNode.server().getGroup(groupId);
    assertThat(h).isNotNull();
    h.getRaftNode().replicate("hello".getBytes(StandardCharsets.UTF_8)).join();
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

  private InJvmConsensusServerTopology.Node nodeFor(RaftEndpoint ep) {
    for (InJvmConsensusServerTopology.Node n : topo.nodes()) {
      if (n.endpoint().equals(ep)) {
        return n;
      }
    }
    throw new AssertionError("no node for " + ep.getId());
  }
}
