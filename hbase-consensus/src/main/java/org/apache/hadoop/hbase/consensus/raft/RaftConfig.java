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
package org.apache.hadoop.hbase.consensus.raft;

import org.apache.hadoop.hbase.consensus.raft.exception.CannotReplicateException;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Contains the configuration parameters for the Raft implementation.
 * <p>
 * RaftConfig is an immutable configuration class. You can use a RaftConfigBuilder to build a
 * RaftConfig object.
 */
@InterfaceAudience.Private
public final class RaftConfig {
  /** The default value for {@link #leaderElectionTimeoutMillis}. */
  public static final long DEFAULT_LEADER_ELECTION_TIMEOUT_MILLIS = 1000;
  /** The default value for {@link #leaderHeartbeatTimeoutMillis} (10 seconds). */
  public static final long DEFAULT_LEADER_HEARTBEAT_TIMEOUT_MILLIS = 10_000;
  /** The default value for {@link #leaderHeartbeatPeriodMillis} (2 seconds). */
  public static final long DEFAULT_LEADER_HEARTBEAT_PERIOD_MILLIS = 2000;
  /**
   * Default upper bound on clock skew between nodes (milliseconds), used to derive the leade lease
   * duration {@code leaderHeartbeatTimeoutMillis - 2 * maxClockDriftMillis}.
   */
  public static final long DEFAULT_MAX_CLOCK_DRIFT_MILLIS = 200;
  /** The default value for {@link #maxPendingLogEntryCount}. */
  public static final int DEFAULT_MAX_PENDING_LOG_ENTRY_COUNT = 5000;
  /** The default value for {@link #appendEntriesRequestBatchSize}. */
  public static final int DEFAULT_APPEND_ENTRIES_REQUEST_BATCH_SIZE = 1000;
  /** The default value for {@link #commitCountToTakeSnapshot}. */
  public static final int DEFAULT_COMMIT_COUNT_TO_TAKE_SNAPSHOT = 50000;
  /** The default value for {@link #transferSnapshotsFromFollowersEnabled}. */
  public static final boolean DEFAULT_TRANSFER_SNAPSHOTS_FROM_FOLLOWERS_ENABLED = true;
  /** The default value for {@link #raftNodeReportPublishPeriodSecs}. */
  public static final int DEFAULT_RAFT_NODE_REPORT_PUBLISH_PERIOD_SECS = 10;
  /** The default value for {@link #quiescenceEnabled}. */
  public static final boolean DEFAULT_QUIESCENCE_ENABLED = false;
  /** The default value for {@link #quiescenceGraceMillis}. */
  public static final long DEFAULT_QUIESCENCE_GRACE_MILLIS = 1000;
  /** The default value for {@link #idleFlushEnabled}. */
  public static final boolean DEFAULT_IDLE_FLUSH_ENABLED = false;
  /** The default value for {@link #idleFlushIntervalMillis} (5 minutes). */
  public static final long DEFAULT_IDLE_FLUSH_INTERVAL_MILLIS = 5L * 60L * 1000L;
  /**
   * The default value for {@link #electionRandomizationGroupScale}. The receiver fairness formula
   * widens the upper bound of the per-follower election-timer randomization interval as
   * {@code leaderHeartbeatTimeoutMillis * (2 + electionRandomizationGroupScale * log(activeGroups))}
   * so that with {@code N} simultaneous followers re-arming on the same wall-clock minute the
   * variance of expiry times grows enough to avoid election storms. {@code 0.0} disables the
   * scaling entirely (the upper bound stays at {@code 2 *} the timeout, the classic Raft-paper
   * envelope). {@code 0.1} gives roughly a {@code +85 %} widening at 5000 groups.
   */
  public static final double DEFAULT_ELECTION_RANDOMIZATION_GROUP_SCALE = 0.1;
  /**
   * The default value for {@link #pauseDetectionThresholdMillis}. The detector treats an inter-tick
   * monotonic delta (sampled with {@link System#nanoTime()}) exceeding
   * {@code expected_period + threshold} as a possible JVM pause. Below this floor we charge the
   * latency to ordinary scheduling jitter, leave timestamps alone, and let the normal lease /
   * election-timer logic run.
   */
  public static final long DEFAULT_PAUSE_DETECTION_THRESHOLD_MILLIS = 1000;
  /**
   * The default value for {@link #pauseToleranceCapMillis}. Pauses larger than this are treated as
   * real failures (we genuinely lost time; let the cluster re-elect). 5 s covers normal G1 / ZGC
   * stop-the-world budgets on multi-GB heaps; longer pauses generally mean a degraded host that
   * should lose leadership.
   */
  public static final long DEFAULT_PAUSE_TOLERANCE_CAP_MILLIS = 5000;
  /** The config object with default configuration. */
  public static final RaftConfig DEFAULT_RAFT_CONFIG = new RaftConfigBuilder().build();
  /**
   * Duration of leader election rounds in milliseconds. If a candidate cannot win majority votes
   * before this timeout elapses, a new leader election round is started. See "Section 5.2: Leader
   * Election" in the Raft paper.
   */
  private final long leaderElectionTimeoutMillis;
  /**
   * Duration in milliseconds for a follower to decide on failure of the current leader and start a
   * new leader election round. If this duration is too short, a leader could be considered as
   * failed unnecessarily in case of a small hiccup. If it is too long, it takes longer to detect an
   * actual failure.
   * <p>
   * Even though there is a single "election timeout" parameter in the Raft paper for both
   * timing-out a leader election round and detecting failure of the leader, this implementation
   * uses two different parameters for these cases.
   * <p>
   * You can set {@link #leaderElectionTimeoutMillis} and this field to the same duration to align
   * with the "election timeout" definition in the Raft paper.
   */
  private final long leaderHeartbeatTimeoutMillis;
  /**
   * Upper bound on clock skew between nodes (milliseconds). Used with
   * {@link #leaderHeartbeatTimeoutMillis} to derive {@link #getLeaderLeaseDurationMillis()}.
   */
  private final long maxClockDriftMillis;
  /**
   * Duration in milliseconds for a Raft leader node to send periodic heartbeat requests to its
   * followers in order to denote its liveliness. Periodic heartbeat requests are actually append
   * entries requests and can contain log entries. A periodic heartbeat request is not sent to a
   * follower if an append entries request has been sent to that follower recently.
   */
  private final long leaderHeartbeatPeriodMillis;
  /**
   * Maximum number of pending log entries in the leader's Raft log before temporarily rejecting new
   * requests of clients. This configuration enables a back pressure mechanism to prevent OOME when
   * a Raft leader cannot keep up with the requests sent by the clients. When the "pending log
   * entries buffer" whose capacity is specified with this configuration field is filled, new
   * requests fail with {@link CannotReplicateException} to slow down the clients. You can configure
   * this field by considering the degree of concurrency of your clients.
   */
  private final int maxPendingLogEntryCount;
  /**
   * A leader Raft node sends log entries to its followers in batches to improve throughput. This
   * configuration parameter specifies the maximum number of Raft log entries that can be sent as a
   * batch in a single append entries request.
   */
  private final int appendEntriesRequestBatchSize;
  /**
   * Number of new commits to initiate a new snapshot after the last snapshot taken by a Raft node.
   * This value must be configured wisely as it effects performance of the system in multiple ways.
   * If a small value is set, it means that snapshots are taken too frequently and Raft nodes keep a
   * very short Raft log. If snapshot objects are large and the Raft state is persisted to disk,
   * this can create an unnecessary overhead on IO performance. Moreover, a Raft leader can send too
   * many snapshots to slow followers which can create a network overhead. On the other hand, if a
   * very large value is set, it can create a memory overhead since Raft log entries are going to be
   * kept in memory until the next snapshot.
   */
  private final int commitCountToTakeSnapshot;
  /**
   * If enabled, when a Raft follower falls far behind the Raft leader and needs to install a
   * snapshot, it transfers the snapshot chunks from both the Raft leader and other followers in
   * parallel. This is a safe optimization because snapshots are taken at the same log indices on
   * all Raft nodes.
   */
  private final boolean transferSnapshotsFromFollowersEnabled;
  /**
   * Denotes how frequently a Raft node publishes a report of its internal Raft state.
   * {@link RaftNodeReport} objects can be used for monitoring a running Raft group.
   */
  private final int raftNodeReportPublishPeriodSecs;
  /**
   * Whether idle-group quiescence is enabled. When true, a leader that has held its lease and seen
   * no proposals for {@link #quiescenceGraceMillis} ms transitions to the {@code Quiescent} state.
   * Subsequent bulk heartbeat frames carry the per-group {@code quiesced} bit set so followers can
   * mirror the state without an extra round-trip. Failure detection during quiescence runs on the
   * per-server keepalive carried at envelope level on every bulk heartbeat / heartbeat-ack frame.
   */
  private final boolean quiescenceEnabled;
  /**
   * Grace period (ms) the leader must hold its lease and see no propose-side activity before the
   * bulk-heartbeat wheel is allowed to call {@code RaftNodeImpl.quiesce}.
   */
  private final long quiescenceGraceMillis;
  /**
   * Whether time-based idle-flush forcing is enabled. When true, a leader that holds its lease, has
   * been idle for {@link #idleFlushIntervalMillis} milliseconds, and still has uncovered
   * application data above its current snapshot index will have the bulk-heartbeat wheel synthesise
   * a {@code FlushMarker} on its behalf and follow the flush-marker's commit with a
   * {@code takeSnapshot()} dispatch. The synthesised flush boundary fires
   * {@link org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi#onFlushComplete} so
   * the embedding can durably flush its in-memory state, and the snapshot dispatch advances the
   * per-group GC frontier in the {@code UnifiedRaftStore} so segments that only the dormant group
   * still references can be reclaimed.
   */
  private final boolean idleFlushEnabled;
  /**
   * Idle interval (ms) after which the bulk-heartbeat wheel synthesises a {@code FlushMarker} on an
   * idle leader. Measured against {@link LeaderState#lastReplicateActivityMillis()}; rate- limited
   * per-group via {@code LeaderState.lastIdleFlushTriggerMillis()} so that a single idle window
   * produces at most one synthetic flush. Has no effect unless {@link #idleFlushEnabled} is
   * {@code true}. See {@link #DEFAULT_IDLE_FLUSH_INTERVAL_MILLIS}.
   */
  private final long idleFlushIntervalMillis;
  /**
   * Receiver fairness scaling factor for the per-follower election-timer randomization upper bound.
   * See {@link #DEFAULT_ELECTION_RANDOMIZATION_GROUP_SCALE} for the formula.
   */
  private final double electionRandomizationGroupScale;
  /**
   * Floor (ms) on observed inter-tick monotonic delta above the expected sweep period at which the
   * heartbeat tick treats the gap as a JVM pause. The delta is sampled with
   * {@link System#nanoTime()} so the detector is immune to wall-clock manipulation. See
   * {@link #DEFAULT_PAUSE_DETECTION_THRESHOLD_MILLIS}.
   */
  private final long pauseDetectionThresholdMillis;
  /**
   * Cap (ms) on the pause delta the heartbeat tick will absorb. Deltas above the cap are treated as
   * real failures and not bumped forward. See {@link #DEFAULT_PAUSE_TOLERANCE_CAP_MILLIS}.
   */
  private final long pauseToleranceCapMillis;

  /** Creates a config object with the given parameters. */
  public RaftConfig(long leaderElectionTimeoutMillis, long leaderHeartbeatPeriodMillis,
    long leaderHeartbeatTimeoutMillis, long maxClockDriftMillis, int appendEntriesRequestBatchSize,
    int commitCountToTakeSnapshot, int maxPendingLogEntryCount,
    boolean transferSnapshotsFromFollowersEnabled, int raftNodeReportPublishPeriodSecs,
    boolean quiescenceEnabled, long quiescenceGraceMillis, boolean idleFlushEnabled,
    long idleFlushIntervalMillis, double electionRandomizationGroupScale,
    long pauseDetectionThresholdMillis, long pauseToleranceCapMillis) {
    this.leaderElectionTimeoutMillis = leaderElectionTimeoutMillis;
    this.leaderHeartbeatPeriodMillis = leaderHeartbeatPeriodMillis;
    this.leaderHeartbeatTimeoutMillis = leaderHeartbeatTimeoutMillis;
    this.maxClockDriftMillis = maxClockDriftMillis;
    this.appendEntriesRequestBatchSize = appendEntriesRequestBatchSize;
    this.commitCountToTakeSnapshot = commitCountToTakeSnapshot;
    this.maxPendingLogEntryCount = maxPendingLogEntryCount;
    this.transferSnapshotsFromFollowersEnabled = transferSnapshotsFromFollowersEnabled;
    this.raftNodeReportPublishPeriodSecs = raftNodeReportPublishPeriodSecs;
    this.quiescenceEnabled = quiescenceEnabled;
    this.quiescenceGraceMillis = quiescenceGraceMillis;
    this.idleFlushEnabled = idleFlushEnabled;
    this.idleFlushIntervalMillis = idleFlushIntervalMillis;
    this.electionRandomizationGroupScale = electionRandomizationGroupScale;
    this.pauseDetectionThresholdMillis = pauseDetectionThresholdMillis;
    this.pauseToleranceCapMillis = pauseToleranceCapMillis;
  }

  /**
   * Creates a new Raft config builder.
   * @return the builder to populate the parameters for RaftConfig.
   */
  public static RaftConfigBuilder newBuilder() {
    return new RaftConfigBuilder();
  }

  private static void checkPositive(long value, String errorMessage) {
    if (value <= 0) {
      throw new IllegalArgumentException(errorMessage);
    }
  }

  /**
   * @return the leader election timeout in milliseconds
   * @see #leaderElectionTimeoutMillis
   */
  public long getLeaderElectionTimeoutMillis() {
    return leaderElectionTimeoutMillis;
  }

  /**
   * @return leader heartbeat timeout in milliseconds
   * @see #leaderHeartbeatTimeoutMillis
   */
  public long getLeaderHeartbeatTimeoutMillis() {
    return leaderHeartbeatTimeoutMillis;
  }

  /**
   * Leader lease duration used for clock-drift-compensated leader stickiness:
   * {@code leaderHeartbeatTimeoutMillis - 2 * maxClockDriftMillis}. Builder enforces strict
   * inequality {@code leaderHeartbeatTimeoutMillis > 2 * maxClockDriftMillis}.
   */
  public long getLeaderLeaseDurationMillis() {
    return leaderHeartbeatTimeoutMillis - 2 * maxClockDriftMillis;
  }

  /**
   * @return the leader heartbeat period in milliseconds
   * @see #leaderHeartbeatPeriodMillis
   */
  public long getLeaderHeartbeatPeriodMillis() {
    return leaderHeartbeatPeriodMillis;
  }

  /**
   * @return the max pending log entry count
   * @see #maxPendingLogEntryCount
   */
  public int getMaxPendingLogEntryCount() {
    return maxPendingLogEntryCount;
  }

  /**
   * @return the append entries request batch size
   * @see #appendEntriesRequestBatchSize
   */
  public int getAppendEntriesRequestBatchSize() {
    return appendEntriesRequestBatchSize;
  }

  /**
   * @return the commit count to take snapshot
   * @see #commitCountToTakeSnapshot
   */
  public int getCommitCountToTakeSnapshot() {
    return commitCountToTakeSnapshot;
  }

  /**
   * @return true if the transfer snapshots from followers enabled
   * @see #transferSnapshotsFromFollowersEnabled
   */
  public boolean isTransferSnapshotsFromFollowersEnabled() {
    return transferSnapshotsFromFollowersEnabled;
  }

  /**
   * @return the raft node report publish period in seconds
   * @see #raftNodeReportPublishPeriodSecs
   */
  public int getRaftNodeReportPublishPeriodSecs() {
    return raftNodeReportPublishPeriodSecs;
  }

  /**
   * @return whether idle-group quiescence is enabled
   * @see #quiescenceEnabled
   */
  public boolean isQuiescenceEnabled() {
    return quiescenceEnabled;
  }

  /**
   * @return the quiescence grace period in milliseconds
   * @see #quiescenceGraceMillis
   */
  public long getQuiescenceGraceMillis() {
    return quiescenceGraceMillis;
  }

  /** Return whether time-based idle-flush forcing is enabled. */
  public boolean isIdleFlushEnabled() {
    return idleFlushEnabled;
  }

  /**
   * Return the idle interval after which the bulk-heartbeat wheel synthesizes a flush marker on a
   * leader that has otherwise gone silent.
   */
  public long getIdleFlushIntervalMillis() {
    return idleFlushIntervalMillis;
  }

  /**
   * @return the election timer randomization group scale factor
   * @see #electionRandomizationGroupScale
   */
  public double getElectionRandomizationGroupScale() {
    return electionRandomizationGroupScale;
  }

  /**
   * @return floor (ms) on observed inter-tick monotonic delta above the expected sweep period at
   *         which the heartbeat tick treats the gap as a JVM pause
   * @see #pauseDetectionThresholdMillis
   */
  public long getPauseDetectionThresholdMillis() {
    return pauseDetectionThresholdMillis;
  }

  /**
   * @return cap (ms) on the pause delta the heartbeat tick will absorb without triggering
   *         re-election
   * @see #pauseToleranceCapMillis
   */
  public long getPauseToleranceCapMillis() {
    return pauseToleranceCapMillis;
  }

  @Override
  public String toString() {
    return "RaftConfig{" + "leaderElectionTimeoutMillis=" + leaderElectionTimeoutMillis
      + ", leaderHeartbeatTimeoutMillis=" + leaderHeartbeatTimeoutMillis + ", maxClockDriftMillis="
      + maxClockDriftMillis + ", leaderHeartbeatPeriodMillis=" + leaderHeartbeatPeriodMillis
      + ", maxPendingLogEntryCount=" + maxPendingLogEntryCount + ", appendEntriesRequestBatchSize="
      + appendEntriesRequestBatchSize + ", commitCountToTakeSnapshot=" + commitCountToTakeSnapshot
      + ", transferSnapshotsFromFollowersEnabled=" + transferSnapshotsFromFollowersEnabled
      + ", raftNodeReportPublishPeriodSecs=" + raftNodeReportPublishPeriodSecs
      + ", quiescenceEnabled=" + quiescenceEnabled + ", quiescenceGraceMillis="
      + quiescenceGraceMillis + ", idleFlushEnabled=" + idleFlushEnabled
      + ", idleFlushIntervalMillis=" + idleFlushIntervalMillis
      + ", electionRandomizationGroupScale=" + electionRandomizationGroupScale
      + ", pauseDetectionThresholdMillis=" + pauseDetectionThresholdMillis
      + ", pauseToleranceCapMillis=" + pauseToleranceCapMillis + '}';
  }

  /** Builder for Raft config. */
  public static final class RaftConfigBuilder {
    private long leaderElectionTimeoutMillis = DEFAULT_LEADER_ELECTION_TIMEOUT_MILLIS;
    private long leaderHeartbeatPeriodMillis = DEFAULT_LEADER_HEARTBEAT_PERIOD_MILLIS;
    private long leaderHeartbeatTimeoutMillis = DEFAULT_LEADER_HEARTBEAT_TIMEOUT_MILLIS;
    private long maxClockDriftMillis = DEFAULT_MAX_CLOCK_DRIFT_MILLIS;
    private int appendEntriesRequestBatchSize = DEFAULT_APPEND_ENTRIES_REQUEST_BATCH_SIZE;
    private int commitCountToTakeSnapshot = DEFAULT_COMMIT_COUNT_TO_TAKE_SNAPSHOT;
    private int maxPendingLogEntryCount = DEFAULT_MAX_PENDING_LOG_ENTRY_COUNT;
    private boolean transferSnapshotsFromFollowersEnabled =
      DEFAULT_TRANSFER_SNAPSHOTS_FROM_FOLLOWERS_ENABLED;
    private int raftNodeReportPublishPeriodSecs = DEFAULT_RAFT_NODE_REPORT_PUBLISH_PERIOD_SECS;
    private boolean quiescenceEnabled = DEFAULT_QUIESCENCE_ENABLED;
    private long quiescenceGraceMillis = DEFAULT_QUIESCENCE_GRACE_MILLIS;
    private boolean idleFlushEnabled = DEFAULT_IDLE_FLUSH_ENABLED;
    private long idleFlushIntervalMillis = DEFAULT_IDLE_FLUSH_INTERVAL_MILLIS;
    private double electionRandomizationGroupScale = DEFAULT_ELECTION_RANDOMIZATION_GROUP_SCALE;
    private long pauseDetectionThresholdMillis = DEFAULT_PAUSE_DETECTION_THRESHOLD_MILLIS;
    private long pauseToleranceCapMillis = DEFAULT_PAUSE_TOLERANCE_CAP_MILLIS;

    private RaftConfigBuilder() {
    }

    /**
     * the leader election timeout in milliseconds value to set
     * @return the builder object for fluent calls
     * @see RaftConfig#leaderElectionTimeoutMillis
     */
    public RaftConfigBuilder setLeaderElectionTimeoutMillis(long leaderElectionTimeoutMillis) {
      checkPositive(leaderElectionTimeoutMillis,
        "leader election timeout millis must be positive!");
      this.leaderElectionTimeoutMillis = leaderElectionTimeoutMillis;
      return this;
    }

    /**
     * the leader heartbeat timeout in milliseconds value to set
     * @return the builder object for fluent calls
     * @see RaftConfig#leaderHeartbeatTimeoutMillis
     */
    public RaftConfigBuilder setLeaderHeartbeatTimeoutMillis(long leaderHeartbeatTimeoutMillis) {
      checkPositive(leaderHeartbeatTimeoutMillis,
        "leader heartbeat timeout millis must be positive!");
      this.leaderHeartbeatTimeoutMillis = leaderHeartbeatTimeoutMillis;
      return this;
    }

    /**
     * Upper bound on clock skew between nodes (milliseconds). Must satisfy
     * {@code leaderHeartbeatTimeoutMillis > 2 * maxClockDriftMillis}.
     * @return the builder object for fluent calls
     */
    public RaftConfigBuilder setMaxClockDriftMillis(long maxClockDriftMillis) {
      if (maxClockDriftMillis < 0) {
        throw new IllegalArgumentException("max clock drift millis must be non-negative!");
      }
      this.maxClockDriftMillis = maxClockDriftMillis;
      return this;
    }

    /**
     * the leader heartbeat period in milliseconds value to set
     * @return the builder object for fluent calls
     * @see RaftConfig#leaderHeartbeatPeriodMillis
     */
    public RaftConfigBuilder setLeaderHeartbeatPeriodMillis(long leaderHeartbeatPeriodMillis) {
      checkPositive(leaderHeartbeatPeriodMillis,
        "leader heartbeat period millis must be positive!");
      this.leaderHeartbeatPeriodMillis = leaderHeartbeatPeriodMillis;
      return this;
    }

    /**
     * the append entries request batch size value to set
     * @return the builder object for fluent calls
     * @see RaftConfig#appendEntriesRequestBatchSize
     */
    public RaftConfigBuilder setAppendEntriesRequestBatchSize(int appendEntriesRequestBatchSize) {
      checkPositive(appendEntriesRequestBatchSize,
        "append entries request batch size must be positive!");
      this.appendEntriesRequestBatchSize = appendEntriesRequestBatchSize;
      return this;
    }

    /**
     * the commit count to take snapshot value to set
     * @return the builder object for fluent calls
     * @see RaftConfig#commitCountToTakeSnapshot
     */
    public RaftConfigBuilder setCommitCountToTakeSnapshot(int commitCountToTakeSnapshot) {
      checkPositive(commitCountToTakeSnapshot, "commit count to take snapshot must be positive!");
      this.commitCountToTakeSnapshot = commitCountToTakeSnapshot;
      return this;
    }

    /**
     * the max pending log entry count value to set
     * @return the builder object for fluent calls
     * @see RaftConfig#maxPendingLogEntryCount
     */
    public RaftConfigBuilder setMaxPendingLogEntryCount(int maxPendingLogEntryCount) {
      checkPositive(maxPendingLogEntryCount,
        "max pending entry count to reject new appends must be positive!");
      this.maxPendingLogEntryCount = maxPendingLogEntryCount;
      return this;
    }

    /**
     * the transfer snapshot from followers value to set
     * @return the builder object for fluent calls
     * @see #transferSnapshotsFromFollowersEnabled
     */
    public RaftConfigBuilder
      setTransferSnapshotsFromFollowersEnabled(boolean transferSnapshotsFromFollowersEnabled) {
      this.transferSnapshotsFromFollowersEnabled = transferSnapshotsFromFollowersEnabled;
      return this;
    }

    /**
     * the raft node report publish period value to set
     * @return the builder object for fluent calls
     * @see #raftNodeReportPublishPeriodSecs
     */
    public RaftConfigBuilder
      setRaftNodeReportPublishPeriodSecs(int raftNodeReportPublishPeriodSecs) {
      checkPositive(raftNodeReportPublishPeriodSecs,
        "raft node state snapshot publish period seconds must be positive!");
      this.raftNodeReportPublishPeriodSecs = raftNodeReportPublishPeriodSecs;
      return this;
    }

    /**
     * Toggles idle-group quiescence.
     * @return the builder object for fluent calls
     * @see RaftConfig#quiescenceEnabled
     */
    public RaftConfigBuilder setQuiescenceEnabled(boolean quiescenceEnabled) {
      this.quiescenceEnabled = quiescenceEnabled;
      return this;
    }

    /**
     * Sets the idle-group quiescence grace period in milliseconds.
     * @return the builder object for fluent calls
     * @see RaftConfig#quiescenceGraceMillis
     */
    public RaftConfigBuilder setQuiescenceGraceMillis(long quiescenceGraceMillis) {
      checkPositive(quiescenceGraceMillis, "quiescence grace millis must be positive!");
      this.quiescenceGraceMillis = quiescenceGraceMillis;
      return this;
    }

    /** Toggles time-based idle-flush forcing. */
    public RaftConfigBuilder setIdleFlushEnabled(boolean idleFlushEnabled) {
      this.idleFlushEnabled = idleFlushEnabled;
      return this;
    }

    /**
     * Sets the idle interval (ms) after which the bulk-heartbeat wheel synthesizes a flush marker
     * on a leader that has otherwise gone silent. Must be positive.
     * @return the builder object for fluent calls
     * @see RaftConfig#idleFlushIntervalMillis
     */
    public RaftConfigBuilder setIdleFlushIntervalMillis(long idleFlushIntervalMillis) {
      checkPositive(idleFlushIntervalMillis, "idle flush interval millis must be positive!");
      this.idleFlushIntervalMillis = idleFlushIntervalMillis;
      return this;
    }

    /**
     * Sets the election timer randomization group-scale factor. Must be non-negative. {@code 0.0}
     * disables the per-group widening.
     * @return the builder object for fluent calls
     * @see RaftConfig#electionRandomizationGroupScale
     */
    public RaftConfigBuilder
      setElectionRandomizationGroupScale(double electionRandomizationGroupScale) {
      if (electionRandomizationGroupScale < 0d || Double.isNaN(electionRandomizationGroupScale)) {
        throw new IllegalArgumentException("election randomization group scale must be >= 0, got "
          + electionRandomizationGroupScale);
      }
      this.electionRandomizationGroupScale = electionRandomizationGroupScale;
      return this;
    }

    /**
     * Sets the pause-detection threshold in milliseconds. Inter-tick deltas (sampled with
     * {@link System#nanoTime()}, so immune to wall-clock manipulation) above the expected sweep
     * period plus this threshold are treated as JVM pauses. Must be non-negative. {@code 0}
     * disables the floor and treats any positive jitter as a pause.
     * @return the builder object for fluent calls
     * @see RaftConfig#pauseDetectionThresholdMillis
     */
    public RaftConfigBuilder setPauseDetectionThresholdMillis(long pauseDetectionThresholdMillis) {
      if (pauseDetectionThresholdMillis < 0) {
        throw new IllegalArgumentException(
          "pause detection threshold millis must be >= 0, got " + pauseDetectionThresholdMillis);
      }
      this.pauseDetectionThresholdMillis = pauseDetectionThresholdMillis;
      return this;
    }

    /**
     * Sets the pause tolerance cap in milliseconds. Pauses whose detected delta exceeds this cap
     * are treated as real failures (no timestamp bumping, normal lease / election timer logic
     * runs). Must be non-negative.
     * @return the builder object for fluent calls
     * @see RaftConfig#pauseToleranceCapMillis
     */
    public RaftConfigBuilder setPauseToleranceCapMillis(long pauseToleranceCapMillis) {
      if (pauseToleranceCapMillis < 0) {
        throw new IllegalArgumentException(
          "pause tolerance cap millis must be >= 0, got " + pauseToleranceCapMillis);
      }
      this.pauseToleranceCapMillis = pauseToleranceCapMillis;
      return this;
    }

    /**
     * Builds the RaftConfig object.
     * @return the RaftConfig object.
     */
    public RaftConfig build() {
      if (leaderHeartbeatTimeoutMillis < leaderHeartbeatPeriodMillis) {
        throw new IllegalArgumentException(
          "leader heartbeat timeout millis: " + leaderHeartbeatTimeoutMillis
            + " cannot be smaller than leader heartbeat period millis: "
            + leaderHeartbeatPeriodMillis);
      }
      if (leaderHeartbeatTimeoutMillis <= 2 * maxClockDriftMillis) {
        throw new IllegalArgumentException(
          "leader heartbeat timeout millis must be strictly greater than 2 * maxClockDriftMillis: "
            + "timeout=" + leaderHeartbeatTimeoutMillis + ", maxClockDriftMillis="
            + maxClockDriftMillis);
      }
      return new RaftConfig(leaderElectionTimeoutMillis, leaderHeartbeatPeriodMillis,
        leaderHeartbeatTimeoutMillis, maxClockDriftMillis, appendEntriesRequestBatchSize,
        commitCountToTakeSnapshot, maxPendingLogEntryCount, transferSnapshotsFromFollowersEnabled,
        raftNodeReportPublishPeriodSecs, quiescenceEnabled, quiescenceGraceMillis, idleFlushEnabled,
        idleFlushIntervalMillis, electionRandomizationGroupScale, pauseDetectionThresholdMillis,
        pauseToleranceCapMillis);
    }

    @Override
    public String toString() {
      return "RaftConfigBuilder{" + "leaderElectionTimeoutMillis=" + leaderElectionTimeoutMillis
        + ", leaderHeartbeatPeriodMillis=" + leaderHeartbeatPeriodMillis
        + ", leaderHeartbeatTimeoutMillis=" + leaderHeartbeatTimeoutMillis
        + ", maxClockDriftMillis=" + maxClockDriftMillis + ", appendEntriesRequestBatchSize="
        + appendEntriesRequestBatchSize + ", commitCountToTakeSnapshot=" + commitCountToTakeSnapshot
        + ", maxPendingLogEntryCount=" + maxPendingLogEntryCount
        + ", transferSnapshotsFromFollowersEnabled=" + transferSnapshotsFromFollowersEnabled
        + ", raftNodeReportPublishPeriodSecs=" + raftNodeReportPublishPeriodSecs
        + ", quiescenceEnabled=" + quiescenceEnabled + ", quiescenceGraceMillis="
        + quiescenceGraceMillis + ", idleFlushEnabled=" + idleFlushEnabled
        + ", idleFlushIntervalMillis=" + idleFlushIntervalMillis
        + ", electionRandomizationGroupScale=" + electionRandomizationGroupScale
        + ", pauseDetectionThresholdMillis=" + pauseDetectionThresholdMillis
        + ", pauseToleranceCapMillis=" + pauseToleranceCapMillis + '}';
    }
  }
}
