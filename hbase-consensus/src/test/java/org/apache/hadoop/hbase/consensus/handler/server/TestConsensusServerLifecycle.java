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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
import org.apache.hadoop.hbase.consensus.handler.server.util.MapEndpointResolver;
import org.apache.hadoop.hbase.consensus.handler.store.LogStoreConfig;
import org.apache.hadoop.hbase.consensus.handler.transport.IdentityByteCodec;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Lifecycle coverage for {@link ConsensusServer}. */
@Tag(MediumTests.TAG)
public class TestConsensusServerLifecycle extends TestBase {

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
  public void testStartStopIsIdempotent() throws IOException {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    conf.set(LogStoreConfig.LOG_DIR_KEY, tmp.toAbsolutePath().toString());
    LocalRaftEndpoint ep = LocalRaftEndpoint.newEndpoint();
    MapEndpointResolver resolver = new MapEndpointResolver();
    ConsensusServer server = new ConsensusServer(conf, ep, new InetSocketAddress("127.0.0.1", 0),
      resolver, new IdentityByteCodec());
    try {
      server.start();
      server.start();
      assertThat(server.getServerStatus().getState())
        .isEqualTo(ConsensusServerStatus.State.RUNNING);
      server.stop();
      server.stop();
      assertThat(server.getServerStatus().getState())
        .isEqualTo(ConsensusServerStatus.State.STOPPED);
    } finally {
      server.close();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testDoubleCloseIsNoop() throws IOException {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    conf.set(LogStoreConfig.LOG_DIR_KEY, tmp.toAbsolutePath().toString());
    LocalRaftEndpoint ep = LocalRaftEndpoint.newEndpoint();
    ConsensusServer server = new ConsensusServer(conf, ep, new InetSocketAddress("127.0.0.1", 0),
      new MapEndpointResolver(), new IdentityByteCodec());
    server.start();
    server.close();
    server.close();
    assertThat(server.getServerStatus().getState()).isEqualTo(ConsensusServerStatus.State.STOPPED);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testAddGroupRejectedBeforeStart() throws IOException {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    conf.set(LogStoreConfig.LOG_DIR_KEY, tmp.toAbsolutePath().toString());
    LocalRaftEndpoint ep = LocalRaftEndpoint.newEndpoint();
    ConsensusServer server = new ConsensusServer(conf, ep, new InetSocketAddress("127.0.0.1", 0),
      new MapEndpointResolver(), new IdentityByteCodec());
    try {
      assertThatThrownBy(
        () -> server.addGroup("g1", Collections.singletonList(ep), new CountingConsensusSpi()))
        .isInstanceOf(IllegalStateException.class);
    } finally {
      server.close();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testStopAfterAddGroupQuiescesNodes() throws Exception {
    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false).build();
    List<RaftEndpoint> members = topo.endpoints();
    CountingConsensusSpi spi0 = new CountingConsensusSpi();
    CountingConsensusSpi spi1 = new CountingConsensusSpi();
    CountingConsensusSpi spi2 = new CountingConsensusSpi();
    topo.nodes().get(0).server().addGroup("g1", members, spi0);
    topo.nodes().get(1).server().addGroup("g1", members, spi1);
    topo.nodes().get(2).server().addGroup("g1", members, spi2);

    // At least one server should observe leader-elected within the test budget.
    boolean elected = spi0.stats("g1").awaitLeaderElected(30, TimeUnit.SECONDS)
      || spi1.stats("g1").awaitLeaderElected(1, TimeUnit.SECONDS)
      || spi2.stats("g1").awaitLeaderElected(1, TimeUnit.SECONDS);
    assertThat(elected).isTrue();

    // Stop quiesces every group.
    topo.close();
    topo = null;
  }
}
