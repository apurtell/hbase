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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
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

/** Verifies that {@link ConsensusServerConfig#MAX_GROUPS_KEY} is enforced. */
@Tag(MediumTests.TAG)
public class TestGroupManagerMaxGroups extends TestBase {

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
  public void testFifthAddRejected() throws Exception {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    conf.setInt(ConsensusServerConfig.MAX_GROUPS_KEY, 4);
    topo = InJvmConsensusServerTopology.builder().setNodeCount(1).setBaseDir(tmp, false)
      .setBaseConf(conf).build();
    ConsensusServer server = topo.nodes().get(0).server();
    List<RaftEndpoint> members = topo.endpoints();
    server.addGroup("g1", members, new CountingConsensusSpi());
    server.addGroup("g2", members, new CountingConsensusSpi());
    server.addGroup("g3", members, new CountingConsensusSpi());
    server.addGroup("g4", members, new CountingConsensusSpi());
    assertThat(server.groupCount()).isEqualTo(4);

    assertThatThrownBy(() -> server.addGroup("g5", members, new CountingConsensusSpi()))
      .isInstanceOf(MaxGroupsExceededException.class).hasMessageContaining("4")
      .hasMessageContaining("g5");
    assertThat(server.groupCount()).isEqualTo(4);

    // Removing one should free up a slot.
    assertThat(server.removeGroup("g1")).isTrue();
    server.addGroup("g5", members, new CountingConsensusSpi());
    assertThat(server.groupCount()).isEqualTo(4);
    assertThat(server.hasGroup("g5")).isTrue();
  }
}
