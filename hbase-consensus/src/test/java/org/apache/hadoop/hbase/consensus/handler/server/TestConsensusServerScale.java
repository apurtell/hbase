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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.consensus.handler.executor.MultiGroupExecutor;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
import org.apache.hadoop.hbase.consensus.handler.server.util.MetricRegistryDumper;
import org.apache.hadoop.hbase.consensus.handler.store.LogStoreConfig;
import org.apache.hadoop.hbase.consensus.handler.transport.OutboundChannelStats;
import org.apache.hadoop.hbase.consensus.handler.transport.TransportConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.exception.CannotReplicateException;
import org.apache.hadoop.hbase.consensus.raft.exception.NotLeaderException;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.impl.BulkHeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.metrics.MetricSet;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Steady-state scale exercise for {@link ConsensusServer}: 3 in-JVM servers carrying {@code N}
 * concurrent Raft groups under closed-loop {@code replicate} load. Groups are added in waves; after
 * each wave's first-leader election the test runs a warmup window followed by a measurement window,
 * then dumps each server's metric registry to {@code target/scale/<runId>/metrics.txt} via
 * {@link MetricRegistryDumper}.
 * <h2>Compiled-in defaults</h2>
 * <ul>
 * <li>{@code groups=100}, {@code drivers=16}, {@code payload=1024} bytes (1 KB),
 * {@code inflight=4}.</li>
 * <li>{@code warmup.seconds=3}, {@code duration.seconds=10}, {@code elect.timeout.seconds=60},
 * {@code warmup.wave.size=100}.</li>
 * </ul>
 * <h2>Asserted bounds</h2> All steady-state assertions are funnelled through an AssertJ
 * {@link org.assertj.core.api.SoftAssertions} so every metric line is logged before the first
 * failure aborts the run.
 * <ol>
 * <li>{@code replicate.p99 <= baseline_ms + slope_ms_per_kb * max(0, payload_kb - 1)} (defaults
 * {@code baseline_ms=5.0}, {@code slope_ms_per_kb=0.05}). Closed-loop wall-clock per
 * {@code RaftNode.replicate(payload).get()}, max across servers.</li>
 * <li>{@code leaderHeartbeatLag.p99 < 2 * leaderHeartbeatTimeoutMillis} (test config: 1500 ms;
 * production default: 10 s).</li>
 * <li>{@code churns_per_group < 0.5} during the steady-state window.</li>
 * <li>{@code hb-frames/s per (server, peer) <= ticksPerSec * 1.5} where
 * {@code ticksPerSec = 1000 / heartbeat.interval.ms}. Production design target is one
 * {@code BULK_HEARTBEAT} frame per peer per tick: at the production interval of 250 ms that is
 * {@code ticksPerSec=4}, design target 4 hb-frames/s, ceiling 6 hb-frames/s. The in-JVM test config
 * in {@link org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology}
 * overrides the interval to 100 ms for faster election convergence at scale, so the runtime numbers
 * evaluated here become {@code ticksPerSec=10}, design target 10 hb-frames/s, ceiling 15
 * hb-frames/s. The bulk frame is structurally one envelope per (server, peer) per tick aggregating
 * one entry per local leader group whose committed membership includes that peer.</li>
 * <li>Sanity: {@code groupsAdded == groups * servers}, {@code opsPerSec > 0},
 * {@code totalReplicateSamples > 0}.</li>
 * </ol>
 */
@Tag(LargeTests.TAG)
public class TestConsensusServerScale extends TestBase {

  private static final Logger LOG = LoggerFactory.getLogger(TestConsensusServerScale.class);

  private static final int GROUPS = Integer.getInteger("hbase.consensus.scale.groups", 100);
  private static final int WARMUP_SECONDS =
    Integer.getInteger("hbase.consensus.scale.warmup.seconds", 3);
  private static final int DURATION_SECONDS =
    Integer.getInteger("hbase.consensus.scale.duration.seconds", 10);
  private static final int DRIVERS = Integer.getInteger("hbase.consensus.scale.drivers", 16);
  private static final int PAYLOAD_BYTES =
    Integer.getInteger("hbase.consensus.scale.payload.bytes", 1024);
  private static final int INFLIGHT = Integer.getInteger("hbase.consensus.scale.inflight", 4);
  private static final int ELECT_TIMEOUT_SECONDS =
    Integer.getInteger("hbase.consensus.scale.elect.timeout.seconds", 60);
  private static final int WARMUP_WAVE_SIZE =
    Math.max(1, Integer.getInteger("hbase.consensus.scale.warmup.wave.size", 100));

  // Bound formula constants. See class-level Javadoc.
  private static final double REPLICATE_P99_BASELINE_MS = Double
    .parseDouble(System.getProperty("hbase.consensus.scale.replicate.p99.baseline.ms", "5.0"));
  private static final double REPLICATE_P99_SLOPE_MS_PER_KB = Double
    .parseDouble(System.getProperty("hbase.consensus.scale.replicate.p99.slope.ms.per.kb", "0.05"));

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

    final int nodeCount = 3;
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    // Forward harness-provided -Dhbase.consensus.* JVM properties (e.g.
    // -Dhbase.consensus.log.writer.shards=N from run-scale-tests.sh) into the Configuration.
    // Hadoop Configuration does not pick up -D properties on its own.
    absorbConsensusSystemProperties(conf);
    // Bound the per-server resource pools against this server's share of the host CPU so the
    // colocated in-JVM ConsensusServers do not multiply the production-default per-server
    // provisioning (which assumes one server per JVM at full machine cores) into an N-multiple
    // oversubscription. Forwarded harness overrides (above) win.
    applyInJvmScaleOverrides(conf, nodeCount);
    topo = InJvmConsensusServerTopology.builder().setNodeCount(nodeCount).setBaseDir(tmp, false)
      .setBaseConf(conf).build();
    final List<InJvmConsensusServerTopology.Node> nodes = topo.nodes();
    final List<RaftEndpoint> members = topo.endpoints();

    final CountingConsensusSpi[] spis = new CountingConsensusSpi[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      spis[i] = new CountingConsensusSpi();
    }

    // Wave-based group assignment, emulating how the HBase master assigns regions in production.
    // Each wave assigns WARMUP_WAVE_SIZE groups across all servers, then waits for first
    // leader election on every group in the wave before moving to the next wave.
    final List<Integer> groupOrder = new ArrayList<>(groups);
    for (int g = 0; g < groups; g++) {
      groupOrder.add(g);
    }
    Collections.shuffle(groupOrder,
      new Random(runId.getLeastSignificantBits() ^ 0x9E3779B97F4A7C15L));
    final Random serverOrderRng = new Random(runId.getLeastSignificantBits());

    final List<List<GroupHandle>> handlesByGroup = new ArrayList<>(groups);
    for (int g = 0; g < groups; g++) {
      List<GroupHandle> per = new ArrayList<>(nodes.size());
      for (int s = 0; s < nodes.size(); s++) {
        per.add(null);
      }
      handlesByGroup.add(per);
    }
    // Per-group "addGroup invoked on first server" wall-clock timestamp. Compared against the
    // first-leader-elected timestamp captured in CountingConsensusSpi.Stats to derive a per-group
    // "open" latency, which the harness reports as a distribution at end-of-run. This is the
    // production analog of master-side region-assignment-to-online-and-leader latency.
    final long[] groupAddStartMs = new long[groups];

    final int waveCount = (groups + WARMUP_WAVE_SIZE - 1) / WARMUP_WAVE_SIZE;
    // Divide the global ELECT_TIMEOUT_SECONDS budget across waves so a single misbehaving wave
    // cannot hijack the whole startup phase.
    final long perWaveElectMs = Math.max(TimeUnit.SECONDS.toMillis(10),
      TimeUnit.SECONDS.toMillis(ELECT_TIMEOUT_SECONDS) / waveCount);
    long addStart = EnvironmentEdgeManager.currentTime();
    for (int wave = 0; wave < waveCount; wave++) {
      int waveStart = wave * WARMUP_WAVE_SIZE;
      int waveEnd = Math.min(waveStart + WARMUP_WAVE_SIZE, groups);
      long waveAddStart = EnvironmentEdgeManager.currentTime();
      for (int idx = waveStart; idx < waveEnd; idx++) {
        int g = groupOrder.get(idx);
        String groupId = "g-" + g;
        // Shuffle the per-group server-add order so the leader role does not consistently land on
        // the server that adds first.
        int[] serverOrder = new int[nodes.size()];
        for (int s = 0; s < nodes.size(); s++) {
          serverOrder[s] = s;
        }
        for (int i = serverOrder.length - 1; i > 0; i--) {
          int j = serverOrderRng.nextInt(i + 1);
          int tmp = serverOrder[i];
          serverOrder[i] = serverOrder[j];
          serverOrder[j] = tmp;
        }
        // Stamp the per-group add-start at the moment we begin adding to its first server.
        // The first-leader-elected callback on the SPI fires from any of the three servers the
        // moment that server transitions to LEADER, so the elapsed (elected - added) is the
        // production-equivalent "region open" wall clock for this group.
        groupAddStartMs[g] = EnvironmentEdgeManager.currentTime();
        for (int s : serverOrder) {
          GroupHandle h = nodes.get(s).server().addGroup(groupId, members, spis[s]);
          handlesByGroup.get(g).set(s, h);
        }
      }
      long waveAddElapsed = EnvironmentEdgeManager.currentTime() - waveAddStart;

      // Wait for first leader on every group in this wave before issuing the next wave.
      long waveDeadline = EnvironmentEdgeManager.currentTime() + perWaveElectMs;
      int electedInWave = 0;
      for (int idx = waveStart; idx < waveEnd; idx++) {
        int g = groupOrder.get(idx);
        String groupId = "g-" + g;
        long remaining = Math.max(0L, waveDeadline - EnvironmentEdgeManager.currentTime());
        boolean elected = false;
        for (CountingConsensusSpi spi : spis) {
          if (spi.stats(groupId).awaitLeaderElected(remaining, TimeUnit.MILLISECONDS)) {
            elected = true;
            break;
          }
          remaining = Math.max(0L, waveDeadline - EnvironmentEdgeManager.currentTime());
        }
        assertThat(elected).as("first leader elected for %s in wave %d/%d (size=%d) within %d ms",
          groupId, wave + 1, waveCount, waveEnd - waveStart, perWaveElectMs).isTrue();
        if (elected) {
          electedInWave++;
        }
      }
      LOG.info(
        "TestConsensusServerScale: warmup wave {}/{} complete; assigned={} elected={} "
          + "addMs={} totalElapsedMs={}",
        wave + 1, waveCount, waveEnd - waveStart, electedInWave, waveAddElapsed,
        EnvironmentEdgeManager.currentTime() - addStart);
    }
    long addElapsed = EnvironmentEdgeManager.currentTime() - addStart;
    LOG.info(
      "TestConsensusServerScale: addGroup waves complete; {} groups x {} servers in {} ms "
        + "across {} waves of {} groups",
      groups, nodes.size(), addElapsed, waveCount, WARMUP_WAVE_SIZE);

    // Per-group "region open" latency = first-leader-elected timestamp - addGroup-invoked
    // timestamp. Captured here, before the steady-state window, so the report reflects the
    // assignment-and-warmup phase only. The CountingConsensusSpi captures the per-group
    // first-elected timestamp under CAS, so racing observations across the three servers cannot
    // overwrite the earliest-observed value. We aggregate the per-server SPIs by taking the
    // earliest non-zero observation.
    final long[] groupOpenLatencyMs = new long[groups];
    int observedOpens = 0;
    for (int g = 0; g < groups; g++) {
      String groupId = "g-" + g;
      long firstElectedMs = 0L;
      for (CountingConsensusSpi spi : spis) {
        long ts = spi.stats(groupId).getFirstLeaderElectedTimestampMs();
        if (ts != 0L && (firstElectedMs == 0L || ts < firstElectedMs)) {
          firstElectedMs = ts;
        }
      }
      if (firstElectedMs != 0L && groupAddStartMs[g] != 0L) {
        groupOpenLatencyMs[g] = Math.max(0L, firstElectedMs - groupAddStartMs[g]);
        observedOpens++;
      } else {
        groupOpenLatencyMs[g] = -1L;
      }
    }
    long[] sortedOpen = new long[observedOpens];
    {
      int j = 0;
      for (long v : groupOpenLatencyMs) {
        if (v >= 0L) {
          sortedOpen[j++] = v;
        }
      }
      Arrays.sort(sortedOpen);
    }
    long openP50 = percentileSortedMillis(sortedOpen, 0.50);
    long openP95 = percentileSortedMillis(sortedOpen, 0.95);
    long openP99 = percentileSortedMillis(sortedOpen, 0.99);
    long openMax = sortedOpen.length > 0 ? sortedOpen[sortedOpen.length - 1] : 0L;
    long openMin = sortedOpen.length > 0 ? sortedOpen[0] : 0L;
    double groupsPerSec = addElapsed > 0L ? (groups * 1000.0) / addElapsed : 0.0;
    LOG.info(
      "TestConsensusServerScale: group-open count={} observed={} min={} ms p50={} ms p95={} ms "
        + "p99={} ms max={} ms total-wall-ms={} groups-per-sec={}",
      groups, observedOpens, openMin, openP50, openP95, openP99, openMax, addElapsed,
      String.format("%.1f", groupsPerSec));

    // Cache one leader RaftNode handle per group (lazy, refreshed on NotLeaderException).
    @SuppressWarnings("unchecked")
    final AtomicReference<GroupHandle>[] leaderRef = new AtomicReference[groups];
    for (int g = 0; g < groups; g++) {
      leaderRef[g] = new AtomicReference<>(findLeader(handlesByGroup.get(g)));
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
    // Per-driver startup jitter and per-iteration micro-jitter to decorrelate the driver fleet's
    // wake-ups.
    final long startupJitterMaxMs = Math.max(50L, TimeUnit.SECONDS.toMillis(warmupSecs) / 4L);
    final long perIterationJitterMaxMicros = 250L;
    List<Future<?>> driverFutures = new ArrayList<>(drivers);
    final CountDownLatch driversReady = new CountDownLatch(drivers);
    for (int i = 0; i < drivers; i++) {
      driverFutures.add(driverPool.submit(() -> {
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
          // Sub-millisecond per-iteration park breaks any harmonic that would re-align the
          // driver fleet's iteration phase. {@link LockSupport#parkNanos} on modern JDKs has
          // sub-microsecond resolution and adds negligible overhead even at hot-loop rates.
          long parkNanos =
            TimeUnit.MICROSECONDS.toNanos(rng.nextLong(0L, perIterationJitterMaxMicros + 1L));
          if (parkNanos > 0L) {
            LockSupport.parkNanos(parkNanos);
          }
        }
        return null;
      }));
    }
    driversReady.await();

    // Warmup window. Sized to absorb the per-driver startup jitter wave above.
    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(warmupSecs));
    if (Thread.interrupted()) {
      Thread.currentThread().interrupt();
      return;
    }

    long steadyStart = EnvironmentEdgeManager.currentTime();
    long opsAtSteadyStart = opsCounter.get();
    long bytesAtSteadyStart = bytesCounter.get();
    Map<RaftEndpoint, Map<RaftEndpoint, OutboundChannelStats>> txStatsAtSteadyStart =
      snapshotTransportStats(nodes);
    // Snapshot election counts at steady-state-start. The cumulative
    // {@code getLeaderElectionsCount()} also counts each server's first observation of a
    // group's initial leader.
    long[] electionsAtSteadyStart = new long[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      electionsAtSteadyStart[i] = nodes.get(i).server().getMetrics().getLeaderElectionsCount();
    }
    Instant expectedEnd = Instant.now().plusSeconds(durationSecs);
    LOG.info("TestConsensusServerScale: steady-state begins at {}; expected-end={}", Instant.now(),
      expectedEnd);

    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(durationSecs));
    if (Thread.interrupted()) {
      Thread.currentThread().interrupt();
      return;
    }

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

    // All assertions in this test are funnelled through a SoftAssertions instance so that every
    // metric below is logged before the first failure aborts the run. Without this, an early
    // assertion failure (e.g., heartbeat frames/s exceeding the per-(server, peer) coalescing
    // budget at high group counts) would prevent the replicate-latency / leader-lag / churn lines
    // from ever reaching the test output, which is exactly what regression analysis needs to see.
    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(opsPerSec).as("aggregate replicate throughput in steady-state")
      .isGreaterThan(0.0);

    // Per-(server, peer) bulk-heartbeat structural assertion.
    //
    // The bulk-heartbeat timing wheel emits one BULK_HEARTBEAT envelope per (this-server, peer)
    // per tick on the wire, aggregating one entry per local leader group whose committed
    // membership includes that peer. There is no per-message mailbox, no post-tick flush hook,
    // and no coalescing window: the frame is structurally one per peer per tick by construction.
    // We therefore only assert the structural cap that the on-wire frame rate per (server, peer)
    // does not exceed ticksPerSec * (1 + tolerance). The legacy fanout-floor / coalesced-percent /
    // post-tick-flush counters are gone with the per-message mailbox.
    int heartbeatIntervalMs = conf.getInt("hbase.consensus.heartbeat.interval.ms", 250);
    int ticksPerSec = Math.max(1, 1000 / Math.max(1, heartbeatIntervalMs));
    int leaderGroupsPerServer = (groups + nodes.size() - 1) / nodes.size();
    double heartbeatFrameRateTolerance = 0.5; // allow up to 1.5x the design target.
    double maxAcceptableHbFramesPerSec = ticksPerSec * (1.0 + heartbeatFrameRateTolerance);
    for (RaftEndpoint local : txStatsAtSteadyEnd.keySet()) {
      Map<RaftEndpoint, OutboundChannelStats> end = txStatsAtSteadyEnd.get(local);
      Map<RaftEndpoint, OutboundChannelStats> start =
        txStatsAtSteadyStart.getOrDefault(local, Collections.emptyMap());
      for (Map.Entry<RaftEndpoint, OutboundChannelStats> e : end.entrySet()) {
        OutboundChannelStats endStats = e.getValue();
        OutboundChannelStats startStats = start.get(e.getKey());
        long deltaHbFrames = endStats.getHeartbeatFrames()
          - (startStats != null ? startStats.getHeartbeatFrames() : 0L);
        long deltaHbAckFrames = endStats.getHeartbeatAckFrames()
          - (startStats != null ? startStats.getHeartbeatAckFrames() : 0L);
        long deltaAppends = endStats.getAppendsEnqueued()
          - (startStats != null ? startStats.getAppendsEnqueued() : 0L);
        long deltaAppendFrames =
          endStats.getAppendFrames() - (startStats != null ? startStats.getAppendFrames() : 0L);
        double hbFramesPerSec = (deltaHbFrames * 1000.0) / elapsedMillis;
        double hbAckFramesPerSec = (deltaHbAckFrames * 1000.0) / elapsedMillis;
        double appendFramesPerSec = (deltaAppendFrames * 1000.0) / elapsedMillis;
        LOG.info(
          "TestConsensusServerScale: hb-coalesce {}->{}: hb-frames/s={} hb-ack-frames/s={} "
            + "app-frames/s={} app-msg/s={} ticks/s={} max-hb-frames/s<={} leader-groups<={}",
          local.getId(), e.getKey().getId(), String.format("%.2f", hbFramesPerSec),
          String.format("%.2f", hbAckFramesPerSec), String.format("%.2f", appendFramesPerSec),
          String.format("%.1f", (deltaAppends * 1000.0) / elapsedMillis), ticksPerSec,
          String.format("%.2f", maxAcceptableHbFramesPerSec), leaderGroupsPerServer);
        // Frame rate bound. A bulk-heartbeat frame is emitted at most once per wheel tick per
        // peer, with a 50% slack for tick boundaries that fall inside the steady-state window
        // without bracketing it.
        softly.assertThat(hbFramesPerSec)
          .as("hb-frames/s %s -> %s (groups=%d, ticks/s=%d, leader-groups<=%d)", local.getId(),
            e.getKey().getId(), groups, ticksPerSec, leaderGroupsPerServer)
          .isLessThanOrEqualTo(maxAcceptableHbFramesPerSec);
        // Ack frame rate bound: same structural cap as the heartbeat side (one ack envelope per
        // (peer, tick) on follower→leader links).
        softly.assertThat(hbAckFramesPerSec)
          .as("hb-ack-frames/s %s -> %s (groups=%d, ticks/s=%d, leader-groups<=%d)", local.getId(),
            e.getKey().getId(), groups, ticksPerSec, leaderGroupsPerServer)
          .isLessThanOrEqualTo(maxAcceptableHbFramesPerSec);
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

    // Snapshot the replicateTime timer's histogram BEFORE running the registry dumper. The driver
    // loop fans the wall-clock measurement out to every server's metrics on every successful
    // replicate, so each server's histogram should hold an identical population. Percentiles are
    // captured at sub-millisecond precision (the underlying HdrLatencyHistogram records in
    // microseconds) so the payload-aware bound assertion below can compare against a fractional
    // millisecond p99 at the 1 KB baseline. The HdrLatencyHistogram snapshot is destructive.
    double maxObservedReplicateP50Ms = 0.0;
    double maxObservedReplicateP75Ms = 0.0;
    double maxObservedReplicateP95Ms = 0.0;
    double maxObservedReplicateP99Ms = 0.0;
    double maxObservedReplicateMaxMs = 0.0;
    long totalReplicateSamples = 0L;
    for (InJvmConsensusServerTopology.Node n : nodes) {
      var hist = n.server().getMetrics().getReplicateTimer().getHistogram();
      long count = hist.getCount();
      if (count == 0) {
        continue;
      }
      var snap = hist.snapshot();
      double p50Ms = snap.getMedian() / 1000.0;
      double p75Ms = snap.get75thPercentile() / 1000.0;
      double p95Ms = snap.get95thPercentile() / 1000.0;
      double p99Ms = snap.get99thPercentile() / 1000.0;
      double maxMs = snap.getMax() / 1000.0;
      maxObservedReplicateP50Ms = Math.max(maxObservedReplicateP50Ms, p50Ms);
      maxObservedReplicateP75Ms = Math.max(maxObservedReplicateP75Ms, p75Ms);
      maxObservedReplicateP95Ms = Math.max(maxObservedReplicateP95Ms, p95Ms);
      maxObservedReplicateP99Ms = Math.max(maxObservedReplicateP99Ms, p99Ms);
      maxObservedReplicateMaxMs = Math.max(maxObservedReplicateMaxMs, maxMs);
      totalReplicateSamples += count;
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
    softly.assertThat(groupsAddedTotal).as("sum of groupsAdded across servers")
      .isEqualTo(((long) groups) * nodes.size());

    // Tail-latency invariant. The leaderHeartbeatLag histogram is the most direct probe of the
    // bug the deadline-based flush wakeup is designed to close: when a follower's most recent
    // received leader heartbeat lags by N ms, a quorum-vote-and-elect-anew cycle is N ms of
    // wall-clock latency away.
    LOG.info(
      "TestConsensusServerScale: leaderHeartbeatLag samples={} p99(max-across-servers)={} ms "
        + "max(max-across-servers)={} ms bound<={} ms",
      totalLeaderLagSamples, maxObservedLeaderLagP99, maxObservedLeaderLagMax,
      leaderHeartbeatLagP99Bound);
    softly.assertThat(maxObservedLeaderLagP99)
      .as("leaderHeartbeatLag.p99 across servers (groups=%d, lhb-timeout=%d ms)", groups,
        leaderHeartbeatTimeoutMs)
      .isLessThan(leaderHeartbeatLagP99Bound);

    // Raw per-proposal RAFT replicate p50/p75/p95/max observability. Logged in milliseconds with
    // 3-decimal precision because the bound formula evaluates to a fractional millisecond at the
    // 1 KB baseline. The driver loop measures the closed-loop
    // {@code RaftNode.replicate(payload).get()} round-trip wall-clock per replicate. The only
    // asserted figure is the p99 below.
    LOG.info(
      "TestConsensusServerScale: replicate samples={} p50(max-across-servers)={} ms "
        + "p75(max-across-servers)={} ms p95(max-across-servers)={} ms "
        + "p99(max-across-servers)={} ms max(max-across-servers)={} ms",
      totalReplicateSamples, String.format("%.3f", maxObservedReplicateP50Ms),
      String.format("%.3f", maxObservedReplicateP75Ms),
      String.format("%.3f", maxObservedReplicateP95Ms),
      String.format("%.3f", maxObservedReplicateP99Ms),
      String.format("%.3f", maxObservedReplicateMaxMs));

    // Payload-aware p99 design assertion. The bound is computed from the published two-parameter
    // linear formula:
    //
    // bound_p99_ms(payload_bytes) = baseline_ms + slope_ms_per_kb * max(0, payload_kb - 1)
    //
    // baseline_ms (default 5.0) is the user-facing requirement. slope_ms_per_kb (default 0.05)
    // absorbs the linear costs that scale with payload size.
    double payloadKb = payloadBytes / 1024.0;
    double extraBudgetMs = Math.max(0.0, payloadKb - 1.0) * REPLICATE_P99_SLOPE_MS_PER_KB;
    double replicateP99BoundMs = REPLICATE_P99_BASELINE_MS + extraBudgetMs;
    LOG.info(
      "TestConsensusServerScale: replicate-p99-bound payload-bytes={} payload-kb={} "
        + "baseline-ms={} slope-ms-per-kb={} extra-budget-ms={} bound-ms={} measured-p99-ms={}",
      payloadBytes, String.format("%.3f", payloadKb),
      String.format("%.3f", REPLICATE_P99_BASELINE_MS),
      String.format("%.4f", REPLICATE_P99_SLOPE_MS_PER_KB), String.format("%.3f", extraBudgetMs),
      String.format("%.3f", replicateP99BoundMs), String.format("%.3f", maxObservedReplicateP99Ms));
    softly.assertThat(totalReplicateSamples)
      .as("replicate sample count across servers (groups=%d)", groups).isGreaterThan(0L);
    softly.assertThat(maxObservedReplicateP99Ms)
      .as(
        "raw RAFT replicate p99 (ms) across servers (groups=%d, payload-bytes=%d, "
          + "baseline-ms=%.3f, slope-ms-per-kb=%.4f, bound-ms=%.3f)",
        groups, payloadBytes, REPLICATE_P99_BASELINE_MS, REPLICATE_P99_SLOPE_MS_PER_KB,
        replicateP99BoundMs)
      .isLessThanOrEqualTo(replicateP99BoundMs);

    // Election-rate invariant. Repeated leader churn under steady-state load is the user-visible
    // symptom of a starved heartbeat. A follower fails to hear from the leader within the heartbeat
    // timeout, triggers a pre-vote, the new leader inherits the same starvation, and the loop
    // repeats. We bound the rate of churn observed during the steady-state window (excluding the
    // initial warmup wave).
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
    softly.assertThat(churnsPerGroup)
      .as("leader-churns-per-group during steady-state window (groups=%d, window-secs=%d)", groups,
        durationSecs)
      .isLessThan(0.5);

    // Surface every soft-assertion failure together so a single failed cell reports its full
    // metric context (heartbeat fan-out, replicate p99, leader-lag, churn) instead of stopping
    // at the first violation.
    softly.assertAll();
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
    AtomicReference<GroupHandle>[] leaderRef, int groupIdx, byte[] payload)
    throws InterruptedException, ExecutionException {
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
    } catch (TimeoutException e) {
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
   * Returns the q'th percentile of an already-sorted ascending array of millisecond samples using
   * the nearest-rank method. Returns 0 for an empty input.
   */
  private static long percentileSortedMillis(long[] sortedAsc, double q) {
    if (sortedAsc.length == 0) {
      return 0L;
    }
    int rank = (int) Math.ceil(q * sortedAsc.length) - 1;
    if (rank < 0) {
      rank = 0;
    }
    if (rank >= sortedAsc.length) {
      rank = sortedAsc.length - 1;
    }
    return sortedAsc[rank];
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

  /**
   * Copies every {@code hbase.consensus.*} JVM system property into {@code conf}, except keys under
   * the {@code hbase.consensus.scale.*} namespace (those are read directly by this test driver via
   * {@link Integer#getInteger}). Hadoop's {@link Configuration} does not pick up {@code -D...} JVM
   * properties on its own; forwarding them here lets {@code run-scale-tests.sh} (or any other
   * launcher) override the topology's resource-pool defaults via
   * {@code -Dhbase.consensus.<key>=<value>}. Keys already present on {@code conf} are overwritten
   * by the system-property value (the harness wins).
   */
  private static void absorbConsensusSystemProperties(Configuration conf) {
    Properties sysProps = System.getProperties();
    for (String name : sysProps.stringPropertyNames()) {
      if (name.startsWith("hbase.consensus.") && !name.startsWith("hbase.consensus.scale.")) {
        String value = sysProps.getProperty(name);
        if (value != null) {
          conf.set(name, value);
        }
      }
    }
  }

  /**
   * Per-server resource-pool overrides for the in-JVM scale topology. Production code defaults
   * (MultiGroupExecutor / UnifiedRaftStore writer-shards / sweeper timer threads) assume a single
   * ConsensusServer occupies the entire host JVM and provision pool widths against the full machine
   * CPU count. This test colocates {@code nodeCount} ConsensusServers in one JVM, so each
   * per-server pool is bounded against its share of the host CPU instead. Sets keys only if absent
   * on {@code conf} so caller-supplied overrides (including those forwarded by
   * {@link #absorbConsensusSystemProperties}) are preserved.
   * <p>
   * Concretely, with {@code perServerCores = max(1, availableProcessors / nodeCount)}:
   * <ul>
   * <li>{@link MultiGroupExecutor#THREADS_KEY} (drain-pool floor) {@code = max(2,
   * perServerCores)}.</li>
   * <li>{@link MultiGroupExecutor#THREADS_CEILING_KEY} (drain-pool ceiling)
   * {@code = max(4, 4 * perServerCores)}. Mirrors the production formula but on the per-server CPU
   * share, so a mis-set {@code maxgroups} cannot blow the colocated thread budget.</li>
   * <li>{@link MultiGroupExecutor#SCHEDULED_THREADS_KEY} {@code = 1}. The delayed-task pool is
   * trampoline-only and does not need to scale with cores.</li>
   * <li>{@link BulkHeartbeatScheduler#TIMER_THREADS_KEY} {@code = 1}. Already the production
   * default; pinned here for clarity in the colocated topology.</li>
   * <li>{@link LogStoreConfig#WRITER_SHARDS_KEY}
   * {@code = max(1, min(WRITER_SHARDS_DEFAULT, perServerCores / 2))}. Caps the always-on writer
   * threads at the production default (4 per server) but linearly scales down on cores-constrained
   * hosts so the colocated set does not dedicate {@code nodeCount * 4} writer threads on a single
   * laptop.</li>
   * <li>{@link TransportConfig#IO_THREADS_KEY}: not touched ({@link InJvmConsensusServerTopology}
   * already pins this to {@code 2} for the loopback topology).</li>
   * </ul>
   */
  private static void applyInJvmScaleOverrides(Configuration conf, int nodeCount) {
    int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
    int perServerCores = Math.max(1, cores / Math.max(1, nodeCount));

    int executorFloor = Math.max(2, perServerCores);
    int executorCeiling = Math.max(4, 4 * perServerCores);
    int writerShards =
      Math.max(1, Math.min(LogStoreConfig.WRITER_SHARDS_DEFAULT, perServerCores / 2));

    setIfAbsent(conf, MultiGroupExecutor.THREADS_KEY, executorFloor);
    setIfAbsent(conf, MultiGroupExecutor.THREADS_CEILING_KEY, executorCeiling);
    setIfAbsent(conf, MultiGroupExecutor.SCHEDULED_THREADS_KEY, 1);
    setIfAbsent(conf, BulkHeartbeatScheduler.TIMER_THREADS_KEY, 1);
    setIfAbsent(conf, LogStoreConfig.WRITER_SHARDS_KEY, writerShards);

    LOG.info(
      "TestConsensusServerScale: in-JVM scale overrides nodeCount={} cores={} perServerCores={} "
        + "executor.threads={} (floor) executor.threads.ceiling={} executor.scheduled.threads={} "
        + "heartbeat.wheel.timer.threads={} log.writer.shards={} transport.io.threads={}",
      nodeCount, cores, perServerCores, conf.getInt(MultiGroupExecutor.THREADS_KEY, -1),
      conf.getInt(MultiGroupExecutor.THREADS_CEILING_KEY, -1),
      conf.getInt(MultiGroupExecutor.SCHEDULED_THREADS_KEY, -1),
      conf.getInt(BulkHeartbeatScheduler.TIMER_THREADS_KEY, -1),
      conf.getInt(LogStoreConfig.WRITER_SHARDS_KEY, -1),
      conf.getInt(TransportConfig.IO_THREADS_KEY, -1));
  }

  private static void setIfAbsent(Configuration conf, String key, int value) {
    if (conf.get(key) == null) {
      conf.setInt(key, value);
    }
  }
}
