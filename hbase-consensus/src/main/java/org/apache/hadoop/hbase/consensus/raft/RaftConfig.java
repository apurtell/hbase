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

  /** Creates a config object with the given parameters. */
  public RaftConfig(long leaderElectionTimeoutMillis, long leaderHeartbeatPeriodMillis,
    long leaderHeartbeatTimeoutMillis, long maxClockDriftMillis, int appendEntriesRequestBatchSize,
    int commitCountToTakeSnapshot, int maxPendingLogEntryCount,
    boolean transferSnapshotsFromFollowersEnabled, int raftNodeReportPublishPeriodSecs) {
    this.leaderElectionTimeoutMillis = leaderElectionTimeoutMillis;
    this.leaderHeartbeatPeriodMillis = leaderHeartbeatPeriodMillis;
    this.leaderHeartbeatTimeoutMillis = leaderHeartbeatTimeoutMillis;
    this.maxClockDriftMillis = maxClockDriftMillis;
    this.appendEntriesRequestBatchSize = appendEntriesRequestBatchSize;
    this.commitCountToTakeSnapshot = commitCountToTakeSnapshot;
    this.maxPendingLogEntryCount = maxPendingLogEntryCount;
    this.transferSnapshotsFromFollowersEnabled = transferSnapshotsFromFollowersEnabled;
    this.raftNodeReportPublishPeriodSecs = raftNodeReportPublishPeriodSecs;
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
   * @return upper bound on clock skew between nodes (milliseconds)
   * @see #maxClockDriftMillis
   */
  public long getMaxClockDriftMillis() {
    return maxClockDriftMillis;
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

  @Override
  public String toString() {
    return "RaftConfig{" + "leaderElectionTimeoutMillis=" + leaderElectionTimeoutMillis
      + ", leaderHeartbeatTimeoutMillis=" + leaderHeartbeatTimeoutMillis + ", maxClockDriftMillis="
      + maxClockDriftMillis + ", leaderHeartbeatPeriodMillis=" + leaderHeartbeatPeriodMillis
      + ", maxPendingLogEntryCount=" + maxPendingLogEntryCount + ", appendEntriesRequestBatchSize="
      + appendEntriesRequestBatchSize + ", commitCountToTakeSnapshot=" + commitCountToTakeSnapshot
      + ", transferSnapshotsFromFollowersEnabled=" + transferSnapshotsFromFollowersEnabled
      + ", raftNodeReportPublishPeriodSecs=" + raftNodeReportPublishPeriodSecs + '}';
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
        raftNodeReportPublishPeriodSecs);
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
        + ", raftNodeReportPublishPeriodSecs=" + raftNodeReportPublishPeriodSecs + '}';
    }
  }
}
