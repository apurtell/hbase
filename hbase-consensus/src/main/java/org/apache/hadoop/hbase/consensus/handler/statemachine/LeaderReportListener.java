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
package org.apache.hadoop.hbase.consensus.handler.statemachine;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.apache.hadoop.hbase.consensus.handler.server.ConsensusServerMetrics;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.report.RaftLogStats;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReportListener;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * A {@link RaftNodeReportListener} that fans Raft lifecycle events out to a {@link ConsensusSpi}.
 * <p>
 * The bridge inspects each incoming {@link RaftNodeReport} and translates it into one of:
 * <ul>
 * <li>{@link ConsensusSpi#onLeaderElected(Object, int, RaftEndpoint)} — fired at most once per
 * (term, leader) pair, even though the underlying report stream may be periodic.</li>
 * <li>{@link ConsensusSpi#onNoLeader(Object)} — fired on transition from a known leader (or local
 * LEADER role) to no known leader for the group.</li>
 * <li>{@link ConsensusSpi#onFollowerLagging(Object, RaftEndpoint)} — fired by a leader for any
 * follower whose match index has fallen below the leader's last snapshot index (i.e., a follower
 * that requires a snapshot install). A per-(group, peer) cooldown rate-limits this to avoid log
 * floods.</li>
 * </ul>
 * <p>
 * {@code RaftNodeReportListener#accept} is invoked from the per-group Raft executor thread that
 * owns the underlying {@code StateMachine}. The bridge maintains all of its bookkeeping inside that
 * thread without explicit synchronization. It must therefore not be shared across groups via a
 * single instance. One bridge per {@link org.apache.hadoop.hbase.consensus.raft.RaftNode RaftNode}
 * is the intended pattern.
 */
@InterfaceAudience.Private
public final class LeaderReportListener implements RaftNodeReportListener {

  /** Default lagging-follower notification cooldown, in milliseconds. */
  public static final long DEFAULT_LAGGING_COOLDOWN_MS = TimeUnit.SECONDS.toMillis(30);

  private final ConsensusSpi spi;
  private final long laggingCooldownMs;
  private final LongSupplier clock;
  @Nullable
  private final ConsensusServerMetrics metrics;

  private int lastFiredLeaderTerm = -1;
  private RaftEndpoint lastFiredLeaderEndpoint;
  private boolean lastSawLeader;

  private final Map<RaftEndpoint, Long> lastLagFireMillis = new HashMap<>();

  public LeaderReportListener(@NonNull ConsensusSpi spi) {
    this(spi, DEFAULT_LAGGING_COOLDOWN_MS, EnvironmentEdgeManager::currentTime, null);
  }

  /**
   * Builds a listener whose per-tick observations populate the supplied
   * {@link ConsensusServerMetrics}. Pass {@code null} to disable metrics recording (the
   * {@code (spi)} constructor delegates here with {@code null}).
   */
  public LeaderReportListener(@NonNull ConsensusSpi spi, @Nullable ConsensusServerMetrics metrics) {
    this(spi, DEFAULT_LAGGING_COOLDOWN_MS, EnvironmentEdgeManager::currentTime, metrics);
  }

  public LeaderReportListener(@NonNull ConsensusSpi spi, long laggingCooldownMs,
    @NonNull LongSupplier clock) {
    this(spi, laggingCooldownMs, clock, null);
  }

  public LeaderReportListener(@NonNull ConsensusSpi spi, long laggingCooldownMs,
    @NonNull LongSupplier clock, @Nullable ConsensusServerMetrics metrics) {
    this.spi = requireNonNull(spi, "spi");
    if (laggingCooldownMs < 0) {
      throw new IllegalArgumentException("laggingCooldownMs must be >= 0: " + laggingCooldownMs);
    }
    this.laggingCooldownMs = laggingCooldownMs;
    this.clock = requireNonNull(clock, "clock");
    this.metrics = metrics;
  }

  @Override
  public void accept(RaftNodeReport report) {
    requireNonNull(report, "report");
    Object groupId = report.getGroupId();
    RaftEndpoint leader = report.getTerm().getLeaderEndpoint();
    int term = report.getTerm().getTerm();

    if (leader != null) {
      boolean newTerm = term != lastFiredLeaderTerm;
      boolean newLeader = !leader.equals(lastFiredLeaderEndpoint);
      if (newTerm || newLeader) {
        lastFiredLeaderTerm = term;
        lastFiredLeaderEndpoint = leader;
        spi.onLeaderElected(groupId, term, leader);
        if (metrics != null) {
          metrics.incLeaderElection();
        }
      }
      lastSawLeader = true;
    } else if (lastSawLeader) {
      lastSawLeader = false;
      lastFiredLeaderEndpoint = null;
      spi.onNoLeader(groupId);
      if (metrics != null) {
        metrics.incNoLeader();
      }
    }

    RaftLogStats log = report.getLog();
    long lastLogIndex = log.getLastLogOrSnapshotIndex();
    long commitIndex = log.getCommitIndex();
    if (metrics != null && lastLogIndex >= commitIndex) {
      metrics.recordCommitBacklog(lastLogIndex - commitIndex);
    }

    if (report.getRole() == RaftRole.LEADER) {
      Map<RaftEndpoint, Long> matchIndices = log.getFollowerMatchIndices();
      if (metrics != null) {
        long qhbTs = report.getQuorumHeartbeatTimestamp();
        if (qhbTs > 0L) {
          metrics.recordQuorumHeartbeatLag(clock.getAsLong() - qhbTs);
        }
        if (matchIndices != null && !matchIndices.isEmpty()) {
          long minMatch = Long.MAX_VALUE;
          for (long m : matchIndices.values()) {
            if (m < minMatch) {
              minMatch = m;
            }
          }
          if (minMatch != Long.MAX_VALUE && lastLogIndex >= minMatch) {
            metrics.recordReplicationLag(lastLogIndex - minMatch);
          }
        }
      }
      long lastSnapshotIndex = log.getLastSnapshotIndex();
      if (matchIndices != null && lastSnapshotIndex > 0) {
        long now = clock.getAsLong();
        for (Map.Entry<RaftEndpoint, Long> e : matchIndices.entrySet()) {
          RaftEndpoint peer = e.getKey();
          long matchIndex = e.getValue();
          if (matchIndex < lastSnapshotIndex) {
            Long lastFire = lastLagFireMillis.get(peer);
            if (lastFire == null || now - lastFire >= laggingCooldownMs) {
              lastLagFireMillis.put(peer, now);
              spi.onFollowerLagging(groupId, peer);
            }
          } else {
            lastLagFireMillis.remove(peer);
          }
        }
      }
    } else {
      if (metrics != null) {
        long lhbTs = report.getLeaderHeartbeatTimestamp();
        if (lhbTs > 0L) {
          metrics.recordLeaderHeartbeatLag(clock.getAsLong() - lhbTs);
        }
      }
      if (!lastLagFireMillis.isEmpty()) {
        lastLagFireMillis.clear();
      }
    }
  }
}
