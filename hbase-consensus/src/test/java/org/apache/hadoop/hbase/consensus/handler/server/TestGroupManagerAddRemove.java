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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * Concurrent {@link GroupManager#addGroup add}/{@link GroupManager#removeGroup remove} race for a
 * single {@link ConsensusServer}, plus per-id idempotency.
 */
@Tag(MediumTests.TAG)
public class TestGroupManagerAddRemove extends TestBase {

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
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testAddGroupIsIdempotent() throws Exception {
    topo = InJvmConsensusServerTopology.builder().setNodeCount(1).setBaseDir(tmp, false).build();
    ConsensusServer server = topo.nodes().get(0).server();
    List<RaftEndpoint> members = topo.endpoints();
    GroupHandle h1 = server.addGroup("g1", members, new CountingConsensusSpi());
    GroupHandle h2 = server.addGroup("g1", members, new CountingConsensusSpi());
    assertThat(h2).isSameAs(h1);
    assertThat(server.groupCount()).isEqualTo(1);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testRemoveUnknownReturnsFalse() throws Exception {
    topo = InJvmConsensusServerTopology.builder().setNodeCount(1).setBaseDir(tmp, false).build();
    ConsensusServer server = topo.nodes().get(0).server();
    assertThat(server.removeGroup("does-not-exist")).isFalse();
  }

  @Test
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  public void testConcurrentAddRemoveConverges() throws Exception {
    topo = InJvmConsensusServerTopology.builder().setNodeCount(1).setBaseDir(tmp, false).build();
    ConsensusServer server = topo.nodes().get(0).server();
    List<RaftEndpoint> members = topo.endpoints();
    final int idCount = 64;
    final int driverCount = 8;
    final int iterationsPerDriver = 100;

    ExecutorService pool = Executors.newFixedThreadPool(driverCount);
    AtomicInteger addOk = new AtomicInteger();
    AtomicInteger removeOk = new AtomicInteger();
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < driverCount; t++) {
        final int seed = t;
        futures.add(pool.submit(() -> {
          Random rnd = new Random(seed);
          for (int i = 0; i < iterationsPerDriver; i++) {
            int id = rnd.nextInt(idCount);
            String groupId = "g-" + id;
            try {
              if (rnd.nextBoolean()) {
                server.addGroup(groupId, members, new CountingConsensusSpi());
                addOk.incrementAndGet();
              } else {
                if (server.removeGroup(groupId)) {
                  removeOk.incrementAndGet();
                }
              }
            } catch (RuntimeException | IOException e) {
              firstFailure.compareAndSet(null, e);
            }
          }
        }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdown();
      pool.awaitTermination(30, TimeUnit.SECONDS);
    }
    if (firstFailure.get() != null) {
      throw new AssertionError("driver threw", firstFailure.get());
    }
    // Steady state: every group should now be removable; the registry size is whatever survived.
    int finalSize = server.groupCount();
    assertThat(finalSize).isLessThanOrEqualTo(idCount);
    // Idempotency check: removing every id eventually drives the registry to zero.
    for (int i = 0; i < idCount; i++) {
      server.removeGroup("g-" + i);
    }
    assertThat(server.groupCount()).isZero();
  }
}
