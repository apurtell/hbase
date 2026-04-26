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
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
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
public class LeaderHeartbeatHandler extends AbstractMessageHandler<LeaderHeartbeat> {
  private static final Logger LOGGER = LoggerFactory.getLogger(LeaderHeartbeatHandler.class);

  public LeaderHeartbeatHandler(RaftNodeImpl raftNode, LeaderHeartbeat request) {
    super(raftNode, request);
  }

  @Override
  protected void handle(@NonNull LeaderHeartbeat request) {
    requireNonNull(request);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("{} received {}.", localEndpointStr(), request);
    }
    RaftEndpoint leader = request.getSender();
    if (request.getTerm() < state.term()) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("{} stale {} received in current term: {}", localEndpointStr(), request,
          state.term());
      }
      return;
    }
    if (request.getTerm() > state.term() || (state.role() != FOLLOWER && state.role() != LEARNER)) {
      LOGGER.info("{} moving to new term: {} and leader: {} from current term: {} after heartbeat",
        localEndpointStr(), request.getTerm(), leader.getId(), state.term());
      node.toFollower(request.getTerm());
    }
    if (!leader.equals(state.leader())) {
      LOGGER.info("{} setting leader: {}", localEndpointStr(), leader.getId());
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
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("{} setting commit index from heartbeat: {}", localEndpointStr(),
            newCommitIndex);
        }
        state.commitIndex(newCommitIndex);
        node.applyLogEntries();
        node.tryRunScheduledQueries();
      }
    }
    // Report the follower's lastVerifiedLogIndex so the leader can detect runtime verification
    // lag (e.g. after a follower restart where the leader's matchIndex still references
    // pre-restart entries but the follower has reset its lastVerifiedLogIndex on recovery) and
    // send a catch-up AppendEntries to re-establish log matching, allowing commit index to
    // resume advancing on the follower.
    RaftMessage ack = modelFactory.createLeaderHeartbeatAckBuilder().setGroupId(node.getGroupId())
      .setSender(localEndpoint()).setTerm(state.term())
      .setLastVerifiedLogIndex(state.lastVerifiedLogIndex()).build();
    node.send(leader, ack);
  }
}
