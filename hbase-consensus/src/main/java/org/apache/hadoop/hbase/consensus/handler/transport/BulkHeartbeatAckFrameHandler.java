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
package org.apache.hadoop.hbase.consensus.handler.transport;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.LeaderHeartbeatAckHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.state.FollowerState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inbound dispatcher for one {@link ConsensusProtos.BulkHeartbeatAckPB} envelope.
 * <p>
 * Runs entirely on the netty inbound event-loop thread on the steady-state path. For every
 * per-group entry whose follower is caught up with the leader's log, the handler advances the
 * per-follower {@link FollowerState#heartbeatAcked(long) responseTimestamp} and recomputes the
 * leader's lease via the lock-free monotonic-max accumulator on
 * {@link LeaderState#leaseExpiryMillis(long)}; no per-group executor task is scheduled.
 * <p>
 * Per-group executor tasks are scheduled only on actual transitions, such as term mismatch, role
 * drop, non-voting follower, or the follower's verified log index lags the leader's log such that
 * catch-up replication is required. The slow path runs {@link LeaderHeartbeatAckHandler}.
 */
@InterfaceAudience.Private
final class BulkHeartbeatAckFrameHandler {

  private static final Logger LOG = LoggerFactory.getLogger(BulkHeartbeatAckFrameHandler.class);

  private final RegistryDispatcher dispatcher;
  private final ProtoConverter converter;

  BulkHeartbeatAckFrameHandler(@NonNull RegistryDispatcher dispatcher,
    @NonNull ProtoConverter converter) {
    this.dispatcher = requireNonNull(dispatcher);
    this.converter = requireNonNull(converter);
  }

  void handle(@NonNull ConsensusProtos.BulkHeartbeatAckPB frame) {
    if (!frame.hasSender()) {
      LOG.debug("Dropping BulkHeartbeatAckPB without sender envelope header");
      return;
    }
    RaftEndpoint sender = ProtoConverter.fromEndpointPB(frame.getSender());
    long now = EnvironmentEdgeManager.currentTime();
    for (ConsensusProtos.GroupBulkHeartbeatAckPB e : frame.getGroupsList()) {
      RaftNode node = dispatcher.lookup(e.getGroupId());
      if (!(node instanceof RaftNodeImpl)) {
        continue;
      }
      RaftNodeImpl rni = (RaftNodeImpl) node;
      if (tryFastPath(rni, sender, e, now)) {
        continue;
      }
      dispatchSlowPath(rni, sender, e);
    }
  }

  private boolean tryFastPath(@NonNull RaftNodeImpl rni, @NonNull RaftEndpoint sender,
    @NonNull ConsensusProtos.GroupBulkHeartbeatAckPB e, long now) {
    if (!e.hasTerm()) {
      return false;
    }
    RaftState state = rni.state();
    if (state.role() != RaftRole.LEADER) {
      return false;
    }
    if (e.getTerm() != state.term()) {
      return false;
    }
    if (!state.committedGroupMembers().isVotingMember(sender)) {
      return false;
    }
    long peerVerified = e.getLastVerifiedLogIndex();
    long leaderLast = state.log().lastLogOrSnapshotIndexVolatile();
    if (peerVerified < leaderLast) {
      return false;
    }
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      return false;
    }
    FollowerState fs = leaderState.getFollowerStateOrNull(sender);
    if (fs == null) {
      return false;
    }
    fs.heartbeatAcked(now);
    int quorumSize = state.logReplicationQuorumSize();
    long quorumTs = leaderState.quorumResponseTimestamp(quorumSize, now);
    RaftConfig cfg = rni.getConfig();
    leaderState.leaseExpiryMillis(quorumTs + cfg.getLeaderLeaseDurationMillis());
    return true;
  }

  private void dispatchSlowPath(@NonNull RaftNodeImpl rni, @NonNull RaftEndpoint sender,
    @NonNull ConsensusProtos.GroupBulkHeartbeatAckPB e) {
    final LeaderHeartbeatAck ack;
    try {
      ack = converter.fromGroupBulkHeartbeatAckPB(e, rni.getGroupId(), sender);
    } catch (RuntimeException ex) {
      LOG.warn("Failed to decode GroupBulkHeartbeatAckPB for group {}", rni.getGroupId(), ex);
      return;
    }
    try {
      // The handler classifies the response and runs the leader-side ack body. It covers
      // term-bumps,
      // follower-lagging catch-up, and lease refresh on the per-group executor thread.
      rni.getExecutor().executeControl(new LeaderHeartbeatAckHandler(rni, ack));
    } catch (RuntimeException ex) {
      LOG.debug("executeControl rejected for slow-path bulk heartbeat ack on group {}",
        rni.getGroupId(), ex);
    }
  }
}
