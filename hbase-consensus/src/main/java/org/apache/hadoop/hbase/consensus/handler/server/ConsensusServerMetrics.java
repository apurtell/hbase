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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.consensus.metrics.HdrLatencyHistogram;
import org.apache.hadoop.hbase.consensus.metrics.HdrLatencyTimer;
import org.apache.hadoop.hbase.metrics.Counter;
import org.apache.hadoop.hbase.metrics.Gauge;
import org.apache.hadoop.hbase.metrics.Histogram;
import org.apache.hadoop.hbase.metrics.MetricRegistries;
import org.apache.hadoop.hbase.metrics.MetricRegistry;
import org.apache.hadoop.hbase.metrics.MetricRegistryInfo;
import org.apache.hadoop.hbase.metrics.Timer;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Server-level metrics for {@link ConsensusServer}.
 * <p>
 * The recording API ({@code update*}, {@code inc*}, {@code record*}) is called by
 * {@link ConsensusServer} and the per-group
 * {@link org.apache.hadoop.hbase.consensus.handler.statemachine.StateMachineAdapter} /
 * {@link org.apache.hadoop.hbase.consensus.handler.statemachine.LeaderReportListener} bridges. The
 * underlying metrics are registered into the JVM-wide {@link MetricRegistries#global()} singleton
 * under a per-server {@link MetricRegistryInfo}.
 * <p>
 * This class exposes the RAFT-side timings and per-tick lag samples needed to attribute end-user
 * commit latency to one (or both) of:
 * <ul>
 * <li>The local Raft log fsync (covered indirectly by {@link #updateReplicate(long)} +
 * {@link #recordCommitBacklog(long)}).</li>
 * <li>Quorum replication, i.e. follower acknowledgement latency on the leader
 * ({@link #recordQuorumHeartbeatLag(long)}, {@link #recordReplicationLag(long)}).</li>
 * <li>State-machine apply on the SPI side, i.e. the cost of HBase actually applying the committed
 * payload past the barrier ({@link #updateCommitApply(long, int, long)},
 * {@link #updateFlushComplete(long)}).</li>
 * <li>Snapshot lifecycle, which can stall both apply and replication
 * ({@link #updateTakeSnapshot(long)}, {@link #updateInstallSnapshot(long)}).</li>
 * </ul>
 * <p>
 * {@link #close()} decrements the global ref count and removes the registry, mirroring the {@code
 * BaseSourceImpl} lifecycle.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
@InterfaceStability.Evolving
public final class ConsensusServerMetrics implements Closeable {

  /** Prefix for the JMX context; suffixed with the local endpoint id at construction time. */
  public static final String METRICS_NAME = "Consensus";
  public static final String METRICS_DESCRIPTION = "Metrics about Consensus Server";
  public static final String METRICS_CONTEXT = "consensus";
  public static final String METRICS_JMX_CONTEXT_PREFIX = "Consensus,sub=ConsensusServer";

  // Lifecycle / membership counters.
  private final Counter groupsAdded;
  private final Counter groupsRemoved;
  private final Counter addGroupFailures;

  // Raft event counters (fed from LeaderReportListener / StateMachineAdapter via the SPI).
  private final Counter leaderElections;
  private final Counter noLeaderEvents;
  private final Counter commitBatches;
  private final Counter commitEntries;
  private final Counter commitBytes;
  private final Counter flushCompletes;
  private final Counter snapshotsTaken;
  private final Counter snapshotsInstalled;

  // Latency timers for the public {@link GroupManager} API surface.
  private final Timer addGroupTimer;
  private final Timer removeGroupTimer;
  private final Timer transferLeadershipTimer;
  private final Timer replicateTimer;

  // Latency timers for the SPI-bridge commit / snapshot pipeline. These are the "below replicate"
  // timings that attribute end-user commit latency to specific stages of the RAFT pipeline.
  private final Timer commitApplyTimer;
  private final Timer flushCompleteTimer;
  private final Timer takeSnapshotTimer;
  private final Timer installSnapshotTimer;

  // Sample histograms over per-event scalars (entries, bytes) and per-tick lag observations
  // (millis, entries). These power operator dashboards / alerts that need distributional signal,
  // not just totals.
  private final Histogram commitBatchSizeHistogram;
  private final Histogram commitBatchBytesHistogram;
  private final Histogram quorumHeartbeatLagHistogram;
  private final Histogram leaderHeartbeatLagHistogram;
  private final Histogram commitBacklogHistogram;
  private final Histogram replicationLagHistogram;

  private final MetricRegistryInfo registryInfo;
  private final MetricRegistry registry;

  /**
   * Builds a new metrics object that pulls its gauge values from the supplied
   * {@link ConsensusServerMetricsWrapper}. Registers the metrics with
   * {@link MetricRegistries#global()} under a per-endpoint {@link MetricRegistryInfo} derived from
   * {@link ConsensusServerMetricsWrapper#getEndpointId()}.
   */
  public ConsensusServerMetrics(@NonNull ConsensusServerMetricsWrapper wrapper) {
    String endpointId = wrapper.getEndpointId();
    String jmxContext = METRICS_JMX_CONTEXT_PREFIX + "-" + endpointId;
    this.registryInfo = new MetricRegistryInfo(METRICS_NAME, METRICS_DESCRIPTION, jmxContext,
      METRICS_CONTEXT, /* existingSource */ false);
    this.registry = MetricRegistries.global().create(registryInfo);

    this.groupsAdded = registry.counter("groupsAdded");
    this.groupsRemoved = registry.counter("groupsRemoved");
    this.addGroupFailures = registry.counter("addGroupFailures");

    this.leaderElections = registry.counter("leaderElections");
    this.noLeaderEvents = registry.counter("noLeaderEvents");
    this.commitBatches = registry.counter("commitBatches");
    this.commitEntries = registry.counter("commitEntries");
    this.commitBytes = registry.counter("commitBytes");
    this.flushCompletes = registry.counter("flushCompletes");
    this.snapshotsTaken = registry.counter("snapshotsTaken");
    this.snapshotsInstalled = registry.counter("snapshotsInstalled");

    this.addGroupTimer = registry.timer("addGroupTime");
    this.removeGroupTimer = registry.timer("removeGroupTime");
    this.transferLeadershipTimer = registry.timer("transferLeadershipTime");
    // replicateTime / commitApplyTime drive the user-visible commit-latency SLO and are bounded by
    // hard percentile assertions in TestConsensusServerScale, so we back them with HdrHistogram
    // instead of the default FastLongHistogram. See package-info in
    // org.apache.hadoop.hbase.consensus.metrics for the why; the choice of 1 us .. 60 s range with
    // 3 significant digits gives 0.1 % precision across the full latency spectrum we care about.
    this.replicateTimer = (Timer) registry.register("replicateTime", new HdrLatencyTimer());

    this.commitApplyTimer = (Timer) registry.register("commitApplyTime", new HdrLatencyTimer());
    this.flushCompleteTimer = registry.timer("flushCompleteTime");
    this.takeSnapshotTimer = registry.timer("takeSnapshotTime");
    this.installSnapshotTimer = registry.timer("installSnapshotTime");

    this.commitBatchSizeHistogram = registry.histogram("commitBatchSize");
    this.commitBatchBytesHistogram = registry.histogram("commitBatchBytes");
    // {leader,quorum}HeartbeatLagMillis drive the heartbeat-lag SLO assertion and update with raw
    // millisecond values. HdrLatencyHistogram parameters are unit-agnostic; here the range is
    // 1 ms .. 600 s, three significant digits.
    this.quorumHeartbeatLagHistogram = (Histogram) registry.register("quorumHeartbeatLagMillis",
      new HdrLatencyHistogram(1L, TimeUnit.SECONDS.toMillis(600L), 3));
    this.leaderHeartbeatLagHistogram = (Histogram) registry.register("leaderHeartbeatLagMillis",
      new HdrLatencyHistogram(1L, TimeUnit.SECONDS.toMillis(600L), 3));
    this.commitBacklogHistogram = registry.histogram("commitBacklogEntries");
    this.replicationLagHistogram = registry.histogram("replicationLagEntries");

    registerGauges(wrapper);
  }

  private void registerGauges(@NonNull ConsensusServerMetricsWrapper wrapper) {
    registry.register("activeGroups", (Gauge<Integer>) wrapper::getActiveGroups);
    registry.register("maxGroups", (Gauge<Integer>) wrapper::getMaxGroups);
    registry.register("restoredGroups", (Gauge<Long>) wrapper::getRestoredGroups);
    registry.register("lifecycleState", (Gauge<String>) wrapper::getLifecycleState);
  }

  /** Returns the underlying registry for assertions/inspection. */
  @NonNull
  public MetricRegistry getRegistry() {
    return registry;
  }

  /** Returns the registry's {@link MetricRegistryInfo}. */
  @NonNull
  public MetricRegistryInfo getRegistryInfo() {
    return registryInfo;
  }

  /**
   * Records a successful {@code addGroup} call. Times are reported in milliseconds, matching the
   * publishing granularity that the rest of HBase server-side metrics use; sub-millisecond calls
   * fall into the zero bucket.
   */
  public void updateAddGroup(long timeMillis) {
    addGroupTimer.update(timeMillis, TimeUnit.MILLISECONDS);
    groupsAdded.increment();
  }

  /** Records a failed {@code addGroup} call (does not record time). */
  public void incAddGroupFailure() {
    addGroupFailures.increment();
  }

  /** Records a successful {@code removeGroup} call's elapsed time, in milliseconds. */
  public void updateRemoveGroup(long timeMillis) {
    removeGroupTimer.update(timeMillis, TimeUnit.MILLISECONDS);
    groupsRemoved.increment();
  }

  /** Records a {@code transferLeadership} call's elapsed time, in milliseconds. */
  public void updateTransferLeadership(long timeMillis) {
    transferLeadershipTimer.update(timeMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Records the elapsed time of a successful {@code replicate} call, in milliseconds. The consensus
   * server itself does not own {@code replicate} (callers go through
   * {@link GroupHandle#getRaftNode()}); this is the recording sink that an SPI bridge or a
   * scalability driver pushes into.
   */
  public void updateReplicate(long timeMillis) {
    replicateTimer.update(timeMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Records that a per-group commit batch has been delivered to the SPI. Increments the commit
   * counters but does <em>not</em> sample size/bytes histograms or the apply-time timer; prefer
   * {@link #updateCommitApply(long, int, long)} on call sites that have a wall-clock measurement.
   */
  public void incCommit(int entries, long bytes) {
    commitBatches.increment();
    if (entries > 0) {
      commitEntries.increment(entries);
    }
    if (bytes > 0) {
      commitBytes.increment(bytes);
    }
  }

  /**
   * Records a single SPI {@code onCommit} delivery, including its wall time, batch size in entries,
   * and batch size in bytes. This is the recommended entry point for the SPI bridge: it updates the
   * commit counters, the {@code commitApplyTime} timer, and the per-batch {@code commitBatchSize} /
   * {@code commitBatchBytes} histograms in one call so the per-call cost is fixed. Times are
   * reported in milliseconds.
   * <p>
   * For diagnosing slow HBase commits: a rising {@code commitApplyTime} mean / p99 with steady
   * {@link #updateReplicate(long) replicateTime} indicates the consensus layer is committing on
   * time but the SPI's apply path (e.g. HBase WAL fsync) is the bottleneck. The inverse pattern
   * indicates RAFT replication / fsync, not application apply, is the cause.
   */
  public void updateCommitApply(long timeMillis, int entries, long bytes) {
    commitApplyTimer.update(timeMillis, TimeUnit.MILLISECONDS);
    commitBatches.increment();
    if (entries > 0) {
      commitEntries.increment(entries);
      commitBatchSizeHistogram.update(entries);
    }
    if (bytes > 0) {
      commitBytes.increment(bytes);
      commitBatchBytesHistogram.update(bytes);
    }
  }

  /**
   * Records the wall time, in milliseconds, of a single SPI {@code onFlushComplete} callback and
   * increments the {@code flushCompletes} counter. {@code FlushMarker} is the explicit barrier that
   * separates pre-flush commits from post-flush commits; on a HBase deployment a flush marker is
   * inserted by the WAL flush path, so this timer measures how long the SPI takes to acknowledge
   * the flush barrier (typically dominated by HBase's own flush completion work).
   */
  public void updateFlushComplete(long timeMillis) {
    flushCompleteTimer.update(timeMillis, TimeUnit.MILLISECONDS);
    flushCompletes.increment();
  }

  /**
   * Records the wall time, in milliseconds, of a single SPI {@code takeStateSnapshot} call and
   * increments the {@code snapshotsTaken} counter. Snapshots run on the per-group Raft executor and
   * block both apply and replication for the duration; outliers in this timer correlate with
   * commit-latency spikes on the affected group.
   */
  public void updateTakeSnapshot(long timeMillis) {
    takeSnapshotTimer.update(timeMillis, TimeUnit.MILLISECONDS);
    snapshotsTaken.increment();
  }

  /**
   * Records the wall time, in milliseconds, of a single SPI {@code installStateSnapshot} call and
   * increments the {@code snapshotsInstalled} counter. Install events fire on a follower that has
   * fallen behind the leader's snapshot index; persistent samples here mean either an
   * underprovisioned follower or sustained leader-side trim ahead of the slowest follower.
   */
  public void updateInstallSnapshot(long timeMillis) {
    installSnapshotTimer.update(timeMillis, TimeUnit.MILLISECONDS);
    snapshotsInstalled.increment();
  }

  /**
   * Records the leader-side observation of "milliseconds since the most recent quorum heartbeat",
   * i.e. {@code now - quorumHeartbeatTimestamp}. Sampled per
   * {@link org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport RaftNodeReport} tick on the
   * leader. A non-zero p99 in this histogram shows the network / followers are taking that long to
   * round-trip a heartbeat; that lag is a lower bound on {@code replicateTime} latency for any
   * concurrent client commit. A negative argument is ignored.
   */
  public void recordQuorumHeartbeatLag(long lagMillis) {
    if (lagMillis < 0) {
      return;
    }
    quorumHeartbeatLagHistogram.update(lagMillis);
  }

  /**
   * Records the follower-side observation of "milliseconds since the most recent leader heartbeat
   * received", i.e. {@code now - leaderHeartbeatTimestamp}. Sampled per
   * {@link org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport RaftNodeReport} tick on
   * non-leader nodes. Outliers here are an early warning of leader unreachability (election storm
   * risk). A negative argument is ignored.
   */
  public void recordLeaderHeartbeatLag(long lagMillis) {
    if (lagMillis < 0) {
      return;
    }
    leaderHeartbeatLagHistogram.update(lagMillis);
  }

  /**
   * Records the per-tick observation of "appended-but-not-yet-committed" entries, i.e.
   * {@code lastLogOrSnapshotIndex - commitIndex}. Sampled per
   * {@link org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport RaftNodeReport} tick on any
   * node. A growing or persistently non-zero value is the canonical signal that the local log is
   * outrunning the commit pipeline (slow fsync on a quorum peer, slow network, GC stall, etc.) and
   * that any client {@code replicate} call's commit-wait component will be bounded below by this
   * backlog. A negative argument is ignored.
   */
  public void recordCommitBacklog(long entries) {
    if (entries < 0) {
      return;
    }
    commitBacklogHistogram.update(entries);
  }

  /**
   * Records the leader-side observation of the slowest follower's replication distance, i.e.
   * {@code lastLogOrSnapshotIndex - min(followerMatchIndex)}. Sampled per
   * {@link org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport RaftNodeReport} tick on the
   * leader. This isolates the contribution of the slowest peer to overall commit latency: when this
   * histogram's tail exceeds {@link #recordCommitBacklog(long) commitBacklog}'s tail by a wide
   * margin, the slowest follower is the bottleneck. A negative argument is ignored.
   */
  public void recordReplicationLag(long entries) {
    if (entries < 0) {
      return;
    }
    replicationLagHistogram.update(entries);
  }

  /** Records that a leader-elected event has fired for some group. */
  public void incLeaderElection() {
    leaderElections.increment();
  }

  /** Records that a no-leader event has fired for some group. */
  public void incNoLeader() {
    noLeaderEvents.increment();
  }

  public long getGroupsAddedCount() {
    return groupsAdded.getCount();
  }

  public long getGroupsRemovedCount() {
    return groupsRemoved.getCount();
  }

  public long getAddGroupFailuresCount() {
    return addGroupFailures.getCount();
  }

  public long getCommitBatchesCount() {
    return commitBatches.getCount();
  }

  public long getCommitEntriesCount() {
    return commitEntries.getCount();
  }

  public long getCommitBytesCount() {
    return commitBytes.getCount();
  }

  public long getFlushCompletesCount() {
    return flushCompletes.getCount();
  }

  public long getLeaderElectionsCount() {
    return leaderElections.getCount();
  }

  public long getNoLeaderEventsCount() {
    return noLeaderEvents.getCount();
  }

  /** Returns the {@link Timer} for {@code replicate} latencies. */
  @NonNull
  public Timer getReplicateTimer() {
    return replicateTimer;
  }

  /** Returns the {@link Timer} for {@code addGroup} latencies. */
  @NonNull
  public Timer getAddGroupTimer() {
    return addGroupTimer;
  }

  /** Returns the {@link Timer} for {@code removeGroup} latencies. */
  @NonNull
  public Timer getRemoveGroupTimer() {
    return removeGroupTimer;
  }

  /** Returns the {@link Timer} for {@code transferLeadership} latencies. */
  @NonNull
  public Timer getTransferLeadershipTimer() {
    return transferLeadershipTimer;
  }

  /** Returns the {@link Timer} for SPI {@code onCommit} apply latencies. */
  @NonNull
  public Timer getCommitApplyTimer() {
    return commitApplyTimer;
  }

  /** Returns the {@link Timer} for SPI {@code onFlushComplete} barrier latencies. */
  @NonNull
  public Timer getFlushCompleteTimer() {
    return flushCompleteTimer;
  }

  /** Returns the {@link Timer} for SPI {@code takeStateSnapshot} latencies. */
  @NonNull
  public Timer getTakeSnapshotTimer() {
    return takeSnapshotTimer;
  }

  /** Returns the {@link Timer} for SPI {@code installStateSnapshot} latencies. */
  @NonNull
  public Timer getInstallSnapshotTimer() {
    return installSnapshotTimer;
  }

  /** Returns the {@link Histogram} sampling commit-batch entry counts. */
  @NonNull
  public Histogram getCommitBatchSizeHistogram() {
    return commitBatchSizeHistogram;
  }

  /** Returns the {@link Histogram} sampling commit-batch byte sizes. */
  @NonNull
  public Histogram getCommitBatchBytesHistogram() {
    return commitBatchBytesHistogram;
  }

  /** Returns the {@link Histogram} sampling leader-side quorum heartbeat lag, in milliseconds. */
  @NonNull
  public Histogram getQuorumHeartbeatLagHistogram() {
    return quorumHeartbeatLagHistogram;
  }

  /** Returns the {@link Histogram} sampling follower-side leader heartbeat lag, in milliseconds. */
  @NonNull
  public Histogram getLeaderHeartbeatLagHistogram() {
    return leaderHeartbeatLagHistogram;
  }

  /** Returns the {@link Histogram} sampling appended-but-not-yet-committed entry counts. */
  @NonNull
  public Histogram getCommitBacklogHistogram() {
    return commitBacklogHistogram;
  }

  /** Returns the {@link Histogram} sampling leader-side slowest-follower replication lag. */
  @NonNull
  public Histogram getReplicationLagHistogram() {
    return replicationLagHistogram;
  }

  @Override
  public void close() {
    MetricRegistries.global().remove(registryInfo);
  }
}
