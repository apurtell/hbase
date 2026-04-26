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

import static org.apache.hadoop.hbase.consensus.raft.RaftRole.LEADER;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.state.FollowerState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a {@link LeaderHeartbeatAck} sent by a follower in response to a {@link LeaderHeartbeat}.
 * @see LeaderHeartbeat
 * @see LeaderHeartbeatAck
 */
public class LeaderHeartbeatAckHandler extends AbstractResponseHandler<LeaderHeartbeatAck> {
  private static final Logger LOGGER = LoggerFactory.getLogger(LeaderHeartbeatAckHandler.class);

  public LeaderHeartbeatAckHandler(RaftNodeImpl raftNode, LeaderHeartbeatAck response) {
    super(raftNode, response);
  }

  @Override
  protected void handleResponse(@NonNull LeaderHeartbeatAck response) {
    if (response.getTerm() > state.term()) {
      LOGGER.info("{} moving to new term: {} from current term: {} after {}", localEndpointStr(),
        response.getTerm(), state.term(), response);
      node.toFollower(response.getTerm());
      return;
    }
    if (state.role() != LEADER) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("{} ignored {}: not LEADER anymore.", localEndpointStr(), response);
      }
      return;
    }
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      return;
    }
    FollowerState followerState = leaderState.getFollowerStateOrNull(response.getSender());
    if (followerState == null) {
      return;
    }
    if (state.isVotingMember(response.getSender())) {
      long now = node.getClock().millis();
      followerState.heartbeatAcked(now);
      long quorumTs = leaderState.quorumResponseTimestamp(state.logReplicationQuorumSize(), now);
      leaderState.leaseExpiryMillis(quorumTs + node.getConfig().getLeaderLeaseDurationMillis());
    }
    // Trigger a catch-up AppendEntries when the peer's lastVerifiedLogIndex lags the
    // leader's lastLogOrSnapshotIndex. This applies to both voting followers and learners.
    // It handles the case where the leader's matchIndex for the peer is up-to-date but the
    // peer has reset its runtime lastVerifiedLogIndex. Without this nudge,
    // sendCatchupAppendsIfNeeded skips the peer and heartbeats clamp commit index to the peer's
    // stale lastVerifiedLogIndex, stalling commit advancement.
    long leaderLastIndex = state.log().lastLogOrSnapshotIndex();
    if (
      response.getLastVerifiedLogIndex() < leaderLastIndex && !followerState.isRequestBackoffSet()
    ) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(
          "{} peer {} lags on verification (peerLastVerified={}, leaderLastIndex={});"
            + " triggering catch-up AppendEntries.",
          node.getLocalEndpoint().getId(), response.getSender().getId(),
          response.getLastVerifiedLogIndex(), leaderLastIndex);
      }
      node.sendAppendEntriesRequest(response.getSender());
    }
  }
}
