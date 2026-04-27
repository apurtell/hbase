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
import java.nio.file.Paths;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
import org.apache.hadoop.hbase.consensus.handler.server.util.MetricRegistryDumper;
import org.apache.hadoop.hbase.consensus.handler.transport.OutboundChannelStats;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.exception.CannotReplicateException;
import org.apache.hadoop.hbase.consensus.raft.exception.NotLeaderException;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.metrics.MetricSet;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Steady-state scale exercise for {@link ConsensusServer}: 3 in-JVM servers carrying {@code N}
 * concurrent Raft groups under closed-loop {@code replicate} load. The test drives a configurable
 * warmup window and a configurable measurement window, then dumps each server's metric registry to
 * {@code target/scale/<runId>/metrics.txt} via {@link MetricRegistryDumper} for offline diffing
 * across optimization iterations.
 * <p>
 * The four {@code TestConsensusServerScale: ...} log lines (pid + runId, warmup-begins,
 * steady-state-begins, steady-state-ends) are the entire interface between the test and any
 * external profiler workflow. The test itself is unaware of the profiler.
 * <p>
 * <b>System property knobs</b> (CI defaults shown):
 * <ul>
 * <li>{@code hbase.consensus.scale.groups} &mdash; number of groups (default {@code 200}).
 * Stabilization target: {@code 2000} for CI {@code runLargeTests}, {@code 10000} for
 * developer-machine (requires {@code -Dsurefire.Xmx=12g}).</li>
 * <li>{@code hbase.consensus.scale.warmup.seconds}: warmup duration (default {@code 3}).</li>
 * <li>{@code hbase.consensus.scale.duration.seconds}: steady-state duration (default {@code 10}).
 * Phase 7B overrides to {@code 300}.</li>
 * <li>{@code hbase.consensus.scale.drivers}: closed-loop driver thread count (default
 * {@code 16}).</li>
 * <li>{@code hbase.consensus.scale.payload.bytes}: replicate payload size (default
 * {@code 64}).</li>
 * <li>{@code hbase.consensus.scale.inflight}: per-group outstanding-replicate cap (default
 * {@code 4}).</li>
 * <li>{@code hbase.consensus.scale.elect.timeout.seconds}: deadline for first leader to elect on
 * every group (default {@code 60}).</li>
 * </ul>
 * Invocation example:
 *
 * <pre>
 * mvn -pl hbase-consensus -PrunLargeTests \
 *     -Dtest=TestConsensusServerScale \
 *     -Dsurefire.Xmx=12g -Dsurefire.Xms=4g \
 *     -Dhbase.consensus.scale.groups=10000 \
 *     -Dhbase.consensus.scale.duration.seconds=300 \
 *     test
 * </pre>
 */
@Tag(LargeTests.TAG)
public class TestConsensusServerScale extends TestBase {

  private static final Logger LOG = LoggerFactory.getLogger(TestConsensusServerScale.class);

  private static final int GROUPS = Integer.getInteger("hbase.consensus.scale.groups", 200);
  private static final int WARMUP_SECONDS =
    Integer.getInteger("hbase.consensus.scale.warmup.seconds", 3);
  private static final int DURATION_SECONDS =
    Integer.getInteger("hbase.consensus.scale.duration.seconds", 10);
  private static final int DRIVERS = Integer.getInteger("hbase.consensus.scale.drivers", 16);
  private static final int PAYLOAD_BYTES =
    Integer.getInteger("hbase.consensus.scale.payload.bytes", 64);
  private static final int INFLIGHT = Integer.getInteger("hbase.consensus.scale.inflight", 4);
  private static final int ELECT_TIMEOUT_SECONDS =
    Integer.getInteger("hbase.consensus.scale.elect.timeout.seconds", 60);

  // Per-class generous wall-clock budget. The per-method @Timeout caps each test independently.
  // The class-level upper bound is enforced by HBaseJupiterExtension via @Tag(LargeTests.TAG).
  private static final int METHOD_TIMEOUT_SECONDS =
    Math.max(120, ELECT_TIMEOUT_SECONDS + WARMUP_SECONDS + DURATION_SECONDS + 60);

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
  @Timeout(value = 720, unit = TimeUnit.SECONDS)
  public void testSteadyStateAtScale() throws Exception {
    final int groups = GROUPS;
    final int warmupSecs = WARMUP_SECONDS;
    final int durationSecs = DURATION_SECONDS;
    final int drivers = DRIVERS;
    final int payloadBytes = PAYLOAD_BYTES;
    final int inflight = INFLIGHT;
    final UUID runId = UUID.randomUUID();
    final long pid = ProcessHandle.current().pid();

    LOG.info("TestConsensusServerScale: pid={} runId={} groups={} warmup={} duration={}", pid,
      runId, groups, warmupSecs, durationSecs);
    assertThat(METHOD_TIMEOUT_SECONDS).isLessThanOrEqualTo(720);

    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false)
      .setBaseConf(conf).build();
    final List<InJvmConsensusServerTopology.Node> nodes = topo.nodes();
    final List<RaftEndpoint> members = topo.endpoints();

    // Per-group SPI shared across all three servers (ConcurrentMap keyed by groupId, but the
    // CountingConsensusSpi instance is itself shared per-server so its per-group Stats slot is the
    // simplest book-keeping). One instance per server is enough.
    final CountingConsensusSpi[] spis = new CountingConsensusSpi[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      spis[i] = new CountingConsensusSpi();
    }

    // Add every group on every server. The plan calls for randomized add order to spread leader
    // elections. We shuffle the (groupId, serverIdx) pairs deterministically per runId.
    final List<int[]> addPlan = new ArrayList<>(groups * nodes.size());
    for (int g = 0; g < groups; g++) {
      for (int s = 0; s < nodes.size(); s++) {
        addPlan.add(new int[] { g, s });
      }
    }
    Collections.shuffle(addPlan, new Random(runId.getLeastSignificantBits()));
    final List<List<GroupHandle>> handlesByGroup = new ArrayList<>(groups);
    for (int g = 0; g < groups; g++) {
      List<GroupHandle> per = new ArrayList<>(nodes.size());
      for (int s = 0; s < nodes.size(); s++) {
        per.add(null);
      }
      handlesByGroup.add(per);
    }
    long addStart = EnvironmentEdgeManager.currentTime();
    for (int[] pair : addPlan) {
      int g = pair[0];
      int s = pair[1];
      String groupId = "g-" + g;
      GroupHandle h = nodes.get(s).server().addGroup(groupId, members, spis[s]);
      handlesByGroup.get(g).set(s, h);
    }
    long addElapsed = EnvironmentEdgeManager.currentTime() - addStart;
    LOG.info("TestConsensusServerScale: addGroup wave complete; {} groups x {} servers in {} ms",
      groups, nodes.size(), addElapsed);

    // Wait for first leader on every group via the per-group CountDownLatch on any server's spi.
    long electDeadline =
      EnvironmentEdgeManager.currentTime() + TimeUnit.SECONDS.toMillis(ELECT_TIMEOUT_SECONDS);
    for (int g = 0; g < groups; g++) {
      String groupId = "g-" + g;
      long remaining = Math.max(0L, electDeadline - EnvironmentEdgeManager.currentTime());
      boolean elected = false;
      for (CountingConsensusSpi spi : spis) {
        if (spi.stats(groupId).awaitLeaderElected(remaining, TimeUnit.MILLISECONDS)) {
          elected = true;
          break;
        }
        remaining = Math.max(0L, electDeadline - EnvironmentEdgeManager.currentTime());
      }
      assertThat(elected).as("leader elected for %s within %d s", groupId, ELECT_TIMEOUT_SECONDS)
        .isTrue();
    }

    // Cache one leader RaftNode handle per group (lazy, refreshed on NotLeaderException).
    @SuppressWarnings("unchecked")
    final java.util.concurrent.atomic.AtomicReference<GroupHandle>[] leaderRef =
      new java.util.concurrent.atomic.AtomicReference[groups];
    for (int g = 0; g < groups; g++) {
      leaderRef[g] =
        new java.util.concurrent.atomic.AtomicReference<>(findLeader(handlesByGroup.get(g)));
    }

    // Per-group inflight semaphore.
    final Semaphore[] perGroupSem = new Semaphore[groups];
    for (int g = 0; g < groups; g++) {
      perGroupSem[g] = new Semaphore(inflight);
    }

    LOG.info("TestConsensusServerScale: warmup begins at {}", Instant.now());

    final byte[] template = new byte[payloadBytes];
    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicLong opsCounter = new AtomicLong();
    final AtomicLong bytesCounter = new AtomicLong();
    final AtomicLong errCounter = new AtomicLong();

    ExecutorService driverPool = Executors.newFixedThreadPool(drivers, r -> {
      Thread t = new Thread(r, "scale-driver");
      t.setDaemon(true);
      return t;
    });
    List<Future<?>> driverFutures = new ArrayList<>(drivers);
    final CountDownLatch driversReady = new CountDownLatch(drivers);
    for (int i = 0; i < drivers; i++) {
      driverFutures.add(driverPool.submit(() -> {
        driversReady.countDown();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        while (!stop.get()) {
          int g = rng.nextInt(groups);
          Semaphore sem = perGroupSem[g];
          if (!sem.tryAcquire(50, TimeUnit.MILLISECONDS)) {
            continue;
          }
          long t0 = EnvironmentEdgeManager.currentTime();
          try {
            byte[] payload = template; // shared zero-filled buffer; IdentityByteCodec is a no-op.
            replicateOnce(handlesByGroup, leaderRef, g, payload);
            long elapsed = EnvironmentEdgeManager.currentTime() - t0;
            for (InJvmConsensusServerTopology.Node n : nodes) {
              // Record on every server's metrics so the dumped registry reflects the load. In a
              // production deployment the leader would record it. In this in-JVM scenario it is
              // simplest to fan out so the scale-test summary is complete.
              n.server().getMetrics().updateReplicate(elapsed);
            }
            opsCounter.incrementAndGet();
            bytesCounter.addAndGet(payload.length);
          } catch (Throwable th) {
            errCounter.incrementAndGet();
          } finally {
            sem.release();
          }
        }
        return null;
      }));
    }
    driversReady.await();

    // Warmup window.
    Thread.sleep(TimeUnit.SECONDS.toMillis(warmupSecs));

    long steadyStart = EnvironmentEdgeManager.currentTime();
    long opsAtSteadyStart = opsCounter.get();
    long bytesAtSteadyStart = bytesCounter.get();
    Map<RaftEndpoint, Map<RaftEndpoint, OutboundChannelStats>> txStatsAtSteadyStart =
      snapshotTransportStats(nodes);
    // Snapshot election counts at steady-state-start. The cumulative
    // {@code getLeaderElectionsCount()} also counts each server's first observation of a
    // group's initial leader (one increment per group per server during warmup). Bounding
    // the cumulative count cannot distinguish that one-time warmup baseline from genuine
    // mid-run churn, so we measure the delta during the steady window instead.
    long[] electionsAtSteadyStart = new long[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      electionsAtSteadyStart[i] = nodes.get(i).server().getMetrics().getLeaderElectionsCount();
    }
    Instant expectedEnd = Instant.now().plusSeconds(durationSecs);
    LOG.info("TestConsensusServerScale: steady-state begins at {}; expected-end={}", Instant.now(),
      expectedEnd);

    Thread.sleep(TimeUnit.SECONDS.toMillis(durationSecs));

    long steadyEnd = EnvironmentEdgeManager.currentTime();
    long opsInWindow = opsCounter.get() - opsAtSteadyStart;
    long bytesInWindow = bytesCounter.get() - bytesAtSteadyStart;
    long elapsedMillis = Math.max(1L, steadyEnd - steadyStart);
    Map<RaftEndpoint, Map<RaftEndpoint, OutboundChannelStats>> txStatsAtSteadyEnd =
      snapshotTransportStats(nodes);

    LOG.info("TestConsensusServerScale: steady-state ends at {}; ops={} bytes={}", Instant.now(),
      opsInWindow, bytesInWindow);

    // Stop drivers.
    stop.set(true);
    driverPool.shutdownNow();
    for (Future<?> f : driverFutures) {
      try {
        f.get(10, TimeUnit.SECONDS);
      } catch (Throwable ignored) {
        // best-effort
      }
    }

    // Throughput lower bound is very loose. We want the closed-loop driver to make forward
    // progress.
    double opsPerSec = (opsInWindow * 1000.0) / elapsedMillis;
    LOG.info("TestConsensusServerScale: ops/sec={} err={}", String.format("%.1f", opsPerSec),
      errCounter.get());
    assertThat(opsPerSec).as("aggregate replicate throughput in steady-state").isGreaterThan(0.0);

    int ticksPerSec =
      Math.max(1, 1000 / Math.max(1, conf.getInt("hbase.consensus.heartbeat.interval.ms", 100)));
    int peers = nodes.size() - 1;
    int leaderGroupsPerServer = (groups + nodes.size() - 1) / nodes.size();
    double designBoundPerSec = 4.0 * ticksPerSec * peers;
    double hbBoundPerSec = designBoundPerSec * 4.0;
    for (RaftEndpoint local : txStatsAtSteadyEnd.keySet()) {
      Map<RaftEndpoint, OutboundChannelStats> end = txStatsAtSteadyEnd.get(local);
      Map<RaftEndpoint, OutboundChannelStats> start =
        txStatsAtSteadyStart.getOrDefault(local, Collections.emptyMap());
      for (Map.Entry<RaftEndpoint, OutboundChannelStats> e : end.entrySet()) {
        OutboundChannelStats startStats = start.get(e.getKey());
        long startHb = startStats != null ? startStats.getHeartbeatFrames() : 0L;
        long startEnq = startStats != null ? startStats.getMessagesEnqueued() : 0L;
        long endHb = e.getValue().getHeartbeatFrames();
        long endEnq = e.getValue().getMessagesEnqueued();
        double hbPerSec = ((endHb - startHb) * 1000.0) / elapsedMillis;
        double enqPerSec = ((endEnq - startEnq) * 1000.0) / elapsedMillis;
        double coalescing = hbPerSec > 0 ? (enqPerSec / hbPerSec) : 0.0;
        LOG.info(
          "TestConsensusServerScale: hb-frames/s {}->{}: measured={} design-goal<={} "
            + "bound<={} leader-groups<={} enq/s={} coalescing={}",
          local.getId(), e.getKey().getId(), String.format("%.1f", hbPerSec),
          String.format("%.1f", designBoundPerSec), String.format("%.1f", hbBoundPerSec),
          leaderGroupsPerServer, String.format("%.1f", enqPerSec),
          String.format("%.1f", coalescing));
        assertThat(hbPerSec).as("heartbeat frames/s from %s to %s (groups=%d)", local.getId(),
          e.getKey().getId(), groups).isLessThanOrEqualTo(hbBoundPerSec);
      }
    }

    // Snapshot the leaderHeartbeatLag histograms BEFORE running the registry dumper.
    long leaderHeartbeatTimeoutMs =
      InJvmConsensusServerTopology.DEFAULT_TEST_RAFT_CONFIG.getLeaderHeartbeatTimeoutMillis();
    long leaderHeartbeatLagP99Bound = 2L * leaderHeartbeatTimeoutMs;
    long maxObservedLeaderLagP99 = 0L;
    long maxObservedLeaderLagMax = 0L;
    long totalLeaderLagSamples = 0L;
    for (InJvmConsensusServerTopology.Node n : nodes) {
      var hist = n.server().getMetrics().getLeaderHeartbeatLagHistogram();
      long count = hist.getCount();
      if (count == 0) {
        continue;
      }
      var snap = hist.snapshot();
      long p99 = snap.get99thPercentile();
      maxObservedLeaderLagP99 = Math.max(maxObservedLeaderLagP99, p99);
      maxObservedLeaderLagMax = Math.max(maxObservedLeaderLagMax, snap.getMax());
      totalLeaderLagSamples += count;
    }

    Path outDir = Paths.get("target", "scale", runId.toString());
    Path metricsTxt = outDir.resolve("metrics.txt");
    List<Map.Entry<String, ? extends MetricSet>> contexts = new ArrayList<>();
    for (InJvmConsensusServerTopology.Node n : nodes) {
      contexts.add(new AbstractMap.SimpleEntry<>(n.endpoint().getId().toString(),
        n.server().getMetrics().getRegistry()));
    }
    MetricRegistryDumper.writeDumpsToFile(metricsTxt, contexts);
    LOG.info("TestConsensusServerScale: metrics dump written to {}", metricsTxt.toAbsolutePath());

    // Surface a couple of registry-wide sanity checks so a regression on metrics propagation is
    // caught.
    long groupsAddedTotal = 0L;
    for (InJvmConsensusServerTopology.Node n : nodes) {
      groupsAddedTotal += n.server().getMetrics().getGroupsAddedCount();
    }
    assertThat(groupsAddedTotal).as("sum of groupsAdded across servers")
      .isEqualTo(((long) groups) * nodes.size());

    // Tail-latency invariant. The leaderHeartbeatLag histogram is the most direct probe of the
    // bug the deadline-based flush wakeup is designed to close: when a follower's most recent
    // received leader heartbeat lags by N ms, a quorum-vote-and-elect-anew cycle is N ms of
    // wall-clock latency away. The OutboundChannelFlush TLA+ side spec proves the per-peer
    // outbound mailbox latency is bounded by FLUSH_DEADLINE_MS_KEY + drain wall-clock under
    // sustained producer activity, so the lag p99 must stay well below the
    // leaderHeartbeatTimeoutMillis budget that drives the election decision.
    LOG.info(
      "TestConsensusServerScale: leaderHeartbeatLag samples={} p99(max-across-servers)={} ms "
        + "max(max-across-servers)={} ms bound<={} ms",
      totalLeaderLagSamples, maxObservedLeaderLagP99, maxObservedLeaderLagMax,
      leaderHeartbeatLagP99Bound);
    assertThat(maxObservedLeaderLagP99)
      .as("leaderHeartbeatLag.p99 across servers (groups=%d, lhb-timeout=%d ms)", groups,
        leaderHeartbeatTimeoutMs)
      .isLessThan(leaderHeartbeatLagP99Bound);

    // Election-rate invariant. Repeated leader churn under steady-state load is the user-visible
    // symptom of a starved heartbeat: a follower fails to hear from the leader within the heartbeat
    // timeout, triggers a pre-vote, the new leader inherits the same starvation, and the loop
    // repeats. We bound the rate of churn observed during the steady-state window (excluding the
    // initial warmup wave). One genuine global leader change for a group fires
    // {@link LeaderReportListener#accept(RaftNodeReport)} on every server (the new leader's own
    // listener observes itself as leader; the followers observe the new (term, leader) pair), so
    // we divide the cross-server delta by {@code nodes.size()} to recover an estimate of the
    // global churn count.
    long totalElectionsAtEnd = 0L;
    long totalElectionsAtStart = 0L;
    for (int i = 0; i < nodes.size(); i++) {
      totalElectionsAtEnd += nodes.get(i).server().getMetrics().getLeaderElectionsCount();
      totalElectionsAtStart += electionsAtSteadyStart[i];
    }
    long electionObservationDeltaInWindow = totalElectionsAtEnd - totalElectionsAtStart;
    double estimatedGlobalChurns =
      ((double) electionObservationDeltaInWindow) / (double) nodes.size();
    double churnsPerGroup = estimatedGlobalChurns / (double) groups;
    LOG.info(
      "TestConsensusServerScale: election observations end={} start={} delta={} "
        + "estimated-global-churns={} churns-per-group={}",
      totalElectionsAtEnd, totalElectionsAtStart, electionObservationDeltaInWindow,
      String.format("%.2f", estimatedGlobalChurns), String.format("%.4f", churnsPerGroup));
    assertThat(churnsPerGroup)
      .as("leader-churns-per-group during steady-state window (groups=%d, window-secs=%d)", groups,
        durationSecs)
      .isLessThan(0.5);

    // Surface deadline-flush usage across all peer channels. Non-zero is healthy but it should not
    // be the dominant frame source.
    long totalDeadlineFlushes = 0L;
    long totalFrames = 0L;
    for (Map<RaftEndpoint, OutboundChannelStats> perPeer : txStatsAtSteadyEnd.values()) {
      for (OutboundChannelStats s : perPeer.values()) {
        totalDeadlineFlushes += s.getForcedFlushesByDeadline();
        totalFrames += s.getTotalFrames();
      }
    }
    LOG.info("TestConsensusServerScale: deadline-flushes(total)={} totalFrames={} ratio={}",
      totalDeadlineFlushes, totalFrames,
      totalFrames == 0
        ? "NaN"
        : String.format("%.4f", (double) totalDeadlineFlushes / totalFrames));
  }

  /**
   * Picks the current leader handle out of the per-server handles for a group. Returns the first
   * server whose {@link RaftNodeReport#getRole()} reports {@link RaftRole#LEADER}, or {@code null}
   * if no server currently sees itself as leader.
   */
  private static GroupHandle findLeader(List<GroupHandle> handles) {
    for (GroupHandle h : handles) {
      try {
        RaftNodeReport report = h.getRaftNode().getReport().join().getResult();
        if (report.getRole() == RaftRole.LEADER) {
          return h;
        }
      } catch (Throwable ignored) {
        // try the next server
      }
    }
    return null;
  }

  /**
   * Performs a single closed-loop {@code replicate} on the cached leader for {@code groupIdx},
   * refreshing the cached leader once on {@link NotLeaderException} or
   * {@link CannotReplicateException}.
   */
  private static void replicateOnce(List<List<GroupHandle>> handlesByGroup,
    java.util.concurrent.atomic.AtomicReference<GroupHandle>[] leaderRef, int groupIdx,
    byte[] payload) throws InterruptedException, ExecutionException {
    GroupHandle leader = leaderRef[groupIdx].get();
    if (leader == null) {
      leader = findLeader(handlesByGroup.get(groupIdx));
      if (leader == null) {
        throw new IllegalStateException("no leader for group " + groupIdx);
      }
      leaderRef[groupIdx].set(leader);
    }
    try {
      leader.getRaftNode().replicate(payload).get(10, TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new ExecutionException(e);
    } catch (ExecutionException e) {
      Throwable cause =
        e.getCause() instanceof CompletionException ? e.getCause().getCause() : e.getCause();
      if (cause instanceof NotLeaderException || cause instanceof CannotReplicateException) {
        GroupHandle refreshed = findLeader(handlesByGroup.get(groupIdx));
        if (refreshed != null) {
          leaderRef[groupIdx].set(refreshed);
        }
      }
      throw e;
    }
  }

  /**
   * Snapshots {@link OutboundChannelStats} for every (server,peer) pair, keyed by local endpoint.
   */
  private static Map<RaftEndpoint, Map<RaftEndpoint, OutboundChannelStats>>
    snapshotTransportStats(List<InJvmConsensusServerTopology.Node> nodes) {
    Map<RaftEndpoint, Map<RaftEndpoint, OutboundChannelStats>> out = new ConcurrentHashMap<>();
    for (InJvmConsensusServerTopology.Node n : nodes) {
      out.put(n.endpoint(), n.server().getTransport().getOutboundStats());
    }
    return out;
  }
}
