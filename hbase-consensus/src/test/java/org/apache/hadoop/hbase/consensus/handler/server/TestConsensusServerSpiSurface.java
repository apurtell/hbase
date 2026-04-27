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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validates the {@link GroupManager} read-side surface ({@code getGroup}, {@code hasGroup},
 * {@code groupCount}, {@code groups}, {@code status}, and argument validation on
 * {@code transferLeadership}).
 */
@Tag(MediumTests.TAG)
public class TestConsensusServerSpiSurface extends TestBase {

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
  public void testReadOnlySurface() throws Exception {
    topo = InJvmConsensusServerTopology.builder().setNodeCount(1).setBaseDir(tmp, false).build();
    ConsensusServer server = topo.nodes().get(0).server();
    List<RaftEndpoint> members = topo.endpoints();
    assertThat(server.groupCount()).isZero();
    assertThat(server.hasGroup("absent")).isFalse();
    assertThat(server.getGroup("absent")).isNull();

    GroupHandle h1 = server.addGroup("g1", members, new CountingConsensusSpi());
    GroupHandle h2 = server.addGroup("g2", members, new CountingConsensusSpi());
    assertThat(server.groupCount()).isEqualTo(2);
    assertThat(server.hasGroup("g1")).isTrue();
    assertThat(server.hasGroup("g2")).isTrue();
    assertThat(server.getGroup("g1")).isSameAs(h1);
    assertThat(server.getGroup("g2")).isSameAs(h2);

    Set<Object> seenIds = new HashSet<>();
    Iterator<GroupHandle> iter = server.groups().iterator();
    while (iter.hasNext()) {
      seenIds.add(iter.next().getGroupId());
    }
    assertThat(seenIds).containsExactlyInAnyOrder("g1", "g2");

    // Snapshot semantics: removing a group after taking the iterable does not affect prior view.
    Iterable<GroupHandle> snapshot = server.groups();
    server.removeGroup("g1");
    Set<Object> snapshotIds = new HashSet<>();
    for (GroupHandle gh : snapshot) {
      snapshotIds.add(gh.getGroupId());
    }
    assertThat(snapshotIds).containsExactlyInAnyOrder("g1", "g2");
    assertThat(server.groupCount()).isEqualTo(1);
    assertThat(server.hasGroup("g1")).isFalse();

    // status() and transferLeadership() reject unknown ids.
    assertThatThrownBy(() -> server.status("absent")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> server.transferLeadership("absent", topo.nodes().get(0).endpoint()))
      .isInstanceOf(IllegalArgumentException.class);

    // getServerStatus reflects the registry size.
    assertThat(server.getServerStatus().getActiveGroups()).isEqualTo(1);
    assertThat(server.getServerStatus().getMaxGroups())
      .isEqualTo(server.getServerConfig().getMaxGroups());
  }
}
