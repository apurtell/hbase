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
package org.apache.hadoop.hbase.consensus.raft.impl.handler;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.FOLLOWER;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.LEARNER;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a {@link LeaderHeartbeat} sent by the Raft group leader and responds with a
 * {@link LeaderHeartbeatAck}.
 * <p>
 * Skips all log verification / append / group-op handling. The heartbeat is liveness-only.
 * @see LeaderHeartbeat
 * @see LeaderHeartbeatAck
 */
@InterfaceAudience.Private
public class LeaderHeartbeatHandler extends AbstractMessageHandler<LeaderHeartbeat> {
  private static final Logger LOG = LoggerFactory.getLogger(LeaderHeartbeatHandler.class);

  private final Consumer<LeaderHeartbeatAck> ackSink;

  public LeaderHeartbeatHandler(RaftNodeImpl raftNode, LeaderHeartbeat request,
    @NonNull Consumer<LeaderHeartbeatAck> ackSink) {
    super(raftNode, request);
    this.ackSink = requireNonNull(ackSink);
  }

  @Override
  protected void preHandle() {
    // Heartbeats interpret the quiesce bit explicitly in handle(). Do not pre-wake.
  }

  @Override
  protected void handle(@NonNull LeaderHeartbeat request) {
    requireNonNull(request);
    if (LOG.isDebugEnabled()) {
      LOG.debug("{} received {}.", localEndpointStr(), request);
    }
    RaftEndpoint leader = request.getSender();
    if (request.getTerm() < state.term()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("{} stale {} received in current term: {}", localEndpointStr(), request,
          state.term());
      }
      return;
    }
    if (request.getTerm() > state.term() || (state.role() != FOLLOWER && state.role() != LEARNER)) {
      LOG.info("{} moving to new term: {} and leader: {} from current term: {} after heartbeat",
        localEndpointStr(), request.getTerm(), leader.getId(), state.term());
      node.toFollower(request.getTerm());
    }
    if (!leader.equals(state.leader())) {
      LOG.info("{} setting leader: {}", localEndpointStr(), leader.getId());
      node.leader(leader);
    }
    node.leaderHeartbeatReceived();
    if (request.getCommitIndex() > state.commitIndex()) {
      // Heartbeats carry no prevLogIndex/prevLogTerm consistency check, so the safe upper bound
      // for advancing commit index from a heartbeat is the highest local log index already proven
      // to match the current leader's log (lastVerifiedLogIndex), NOT the raw
      // lastLogOrSnapshotIndex
      // (which may include uncommitted entries from a previous term that the leader will eventually
      // have us truncate). Clamping to lastLogOrSnapshotIndex would let a heartbeat commit an entry
      // that is destined to be truncated.
      long newCommitIndex = min(request.getCommitIndex(), state.lastVerifiedLogIndex());
      if (newCommitIndex > state.commitIndex()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("{} setting commit index from heartbeat: {}", localEndpointStr(),
            newCommitIndex);
        }
        state.commitIndex(newCommitIndex);
        node.applyLogEntries();
        node.tryRunScheduledQueries();
      }
    }
    // Apply the fire-and-forget quiesce notice if all the local consistency checks pass. The
    // follower must (a) be in the same term and commit position as the leader, (b) have no
    // in-flight local proposal, and (c) recognize the sender as the current leader. On any check
    // fail, ignore the flag silently and let the next inbound non-heartbeat signal re-enable
    // normal operation.
    if (request.isQuiesced()) {
      maybeQuiesceFromNotice(request, leader);
    } else if (state.groupQuiescent()) {
      // Any non-quiesce heartbeat from the current leader implicitly leaves the quiescent state;
      // the leader must have woken (e.g. a propose ran) and we should resume normal failure
      // detection on the per-group timer.
      state.groupQuiescent(false);
    }
    // Report the follower's lastVerifiedLogIndex so the leader can detect runtime verification
    // lag (e.g. after a follower restart where the leader's matchIndex still references
    // pre-restart entries but the follower has reset its lastVerifiedLogIndex on recovery) and
    // send a catch-up AppendEntries to re-establish log matching, allowing commit index to
    // resume advancing on the follower.
    LeaderHeartbeatAck ack = modelFactory.createLeaderHeartbeatAckBuilder()
      .setGroupId(node.getGroupId()).setSender(localEndpoint()).setTerm(state.term())
      .setLastVerifiedLogIndex(state.lastVerifiedLogIndex()).build();
    ackSink.accept(ack);
  }

  private void maybeQuiesceFromNotice(LeaderHeartbeat request, RaftEndpoint leader) {
    if (state.role() != FOLLOWER && state.role() != LEARNER) {
      return;
    }
    if (request.getTerm() != state.term()) {
      return;
    }
    if (!leader.equals(state.leader())) {
      return;
    }
    if (request.getCommitIndex() != state.commitIndex()) {
      return;
    }
    if (state.commitIndex() != state.log().lastLogOrSnapshotIndex()) {
      return;
    }
    state.groupQuiescent(true);
    if (LOG.isDebugEnabled()) {
      LOG.debug("{} entering Quiescent (notice from leader {})", localEndpointStr(),
        leader.getId());
    }
  }
}
