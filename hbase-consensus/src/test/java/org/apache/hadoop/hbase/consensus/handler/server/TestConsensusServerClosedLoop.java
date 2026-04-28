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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.hadoop.conf.Configuration;
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
 * Smoke coverage for closed-loop blocking-replicate commit progress on a 3-node Raft group.
 * <p>
 * Spins up a 3-node {@link ConsensusServer} topology, single Raft group, multiple driver threads
 * issuing back-to-back blocking {@code RaftNode.replicate(payload).get()} calls. The drivers form a
 * closed loop. Each one is parked in {@code get()} until its own replicate commits, so any
 * commit-quorum stall pins throughput to zero immediately and the test fails fast.
 * <p>
 * The test asserts the cluster elects a leader and commits a meaningful number of replicates within
 * a tight wall-clock budget against the {@link InJvmConsensusServerTopology#defaultConf() global
 * default config}.
 */
@Tag(MediumTests.TAG)
public class TestConsensusServerClosedLoop extends TestBase {

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

  /**
   * Closed-loop replicate over a single 3-node Raft group with the global default config. Multiple
   * driver threads each block on {@code replicate(...).get()}, so a commit-quorum stall pins
   * throughput to zero immediately.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testClosedLoopReplicateAdvancesCommit() throws Exception {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false)
      .setBaseConf(conf).build();
    runClosedLoopReplicateScenario();
  }

  private void runClosedLoopReplicateScenario() throws Exception {
    final List<InJvmConsensusServerTopology.Node> nodes = topo.nodes();
    final List<RaftEndpoint> members = topo.endpoints();
    final CountingConsensusSpi[] spis = new CountingConsensusSpi[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      spis[i] = new CountingConsensusSpi();
    }
    final List<GroupHandle> handlesByServer = new ArrayList<>(nodes.size());
    for (int s = 0; s < nodes.size(); s++) {
      handlesByServer.add(nodes.get(s).server().addGroup("g0", members, spis[s]));
    }

    // Wait for the first leader on the group via any server's spi latch. Bound the wait far
    // below the @Timeout so a regression that prevents election (rather than commit) surfaces
    // distinctly.
    boolean elected = false;
    long electDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    for (CountingConsensusSpi spi : spis) {
      long remainingMs =
        Math.max(0L, TimeUnit.NANOSECONDS.toMillis(electDeadline - System.nanoTime()));
      if (spi.stats("g0").awaitLeaderElected(remainingMs, TimeUnit.MILLISECONDS)) {
        elected = true;
        break;
      }
    }
    assertThat(elected).as("leader elected on g0 within 20 s").isTrue();

    // Resolve the current leader handle.
    GroupHandle leaderHandle = null;
    for (GroupHandle h : handlesByServer) {
      RaftNodeReport report = h.getRaftNode().getReport().join().getResult();
      if (report.getRole() == RaftRole.LEADER) {
        leaderHandle = h;
        break;
      }
    }
    assertThat(leaderHandle).as("leader handle resolved").isNotNull();
    final GroupHandle leader = leaderHandle;

    // Closed-loop drivers. Pick a random small payload, replicate, block on get(), increment a
    // counter. Two drivers are enough to wedge the cluster if commit-quorum stalls.
    final int driverCount = 2;
    final int payloadBytes = 256;
    final long durationNanos = TimeUnit.SECONDS.toNanos(5);
    // Per-driver startup jitter so the two drivers do not begin their replicate loops on the same
    // wall-clock instant. Without jitter the two driver threads launched from the same submit
    // batch wake up tightly synchronized on the executor and form a phase-locked back-to-back
    // pair on the leader's mailbox.
    final long startupJitterMaxMs = 50L;
    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicLong opsCounter = new AtomicLong();
    final AtomicLong errCounter = new AtomicLong();
    ExecutorService pool = Executors.newFixedThreadPool(driverCount, r -> {
      Thread t = new Thread(r, "closed-loop-driver");
      t.setDaemon(true);
      return t;
    });
    final CountDownLatch driversReady = new CountDownLatch(driverCount);
    List<Future<?>> futures = new ArrayList<>(driverCount);
    final long endNanos = System.nanoTime() + durationNanos;
    for (int i = 0; i < driverCount; i++) {
      futures.add(pool.submit(() -> {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long startupJitterMs = rng.nextLong(0L, startupJitterMaxMs + 1L);
        driversReady.countDown();
        if (startupJitterMs > 0L) {
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(startupJitterMs));
          if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return null;
          }
        }
        byte[] payload = new byte[payloadBytes];
        while (!stop.get() && System.nanoTime() < endNanos) {
          rng.nextBytes(payload);
          try {
            leader.getRaftNode().replicate(payload).get(8, TimeUnit.SECONDS);
            opsCounter.incrementAndGet();
          } catch (Throwable t) {
            errCounter.incrementAndGet();
          }
        }
        return null;
      }));
    }
    driversReady.await();
    LockSupport.parkNanos(durationNanos);
    if (Thread.interrupted()) {
      Thread.currentThread().interrupt();
    }
    stop.set(true);
    pool.shutdownNow();
    for (Future<?> f : futures) {
      try {
        f.get(5, TimeUnit.SECONDS);
      } catch (Throwable ignored) {
        // best-effort
      }
    }

    long ops = opsCounter.get();
    long errs = errCounter.get();
    assertThat(ops).as("closed-loop driver completed at least 10 replicates (errs=%d)", errs)
      .isGreaterThanOrEqualTo(10L);
  }
}
