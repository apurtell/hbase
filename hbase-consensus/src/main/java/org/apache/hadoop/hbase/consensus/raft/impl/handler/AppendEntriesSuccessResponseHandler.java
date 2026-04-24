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
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.state.FollowerState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.QueryState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles an {@link AppendEntriesSuccessResponse} which can be sent as a response to a
 * append-entries request or an install-snapshot request.
 * <p>
 * Advances {@link RaftState#commitIndex()} according to the match indices of the Raft group
 * members.
 * <p>
 * See <i>5.3 Log replication</i> section of <i>In Search of an Understandable Consensus
 * Algorithm</i> paper by <i>Diego Ongaro</i> and <i>John Ousterhout</i>.
 * @see AppendEntriesRequest
 * @see AppendEntriesSuccessResponse
 * @see AppendEntriesFailureResponse
 */
public class AppendEntriesSuccessResponseHandler
  extends AbstractResponseHandler<AppendEntriesSuccessResponse> {
  private static final Logger LOGGER =
    LoggerFactory.getLogger(AppendEntriesSuccessResponseHandler.class);

  public AppendEntriesSuccessResponseHandler(RaftNodeImpl raftNode,
    AppendEntriesSuccessResponse response) {
    super(raftNode, response);
  }

  @Override
  protected void handleResponse(@NonNull AppendEntriesSuccessResponse response) {
    if (state.role() != LEADER) {
      LOGGER.warn("{} Ignored {}. We are not LEADER anymore.", localEndpointStr(), response);
      return;
    } else if (response.getTerm() > state.term()) {
      LOGGER.warn("{} Ignored invalid response {} for current term: {}", localEndpointStr(),
        response, state.term());
      return;
    }
    LOGGER.debug("{} received {}.", localEndpointStr(), response);
    if (updateFollowerIndices(response)) {
      if (!node.tryAdvanceCommitIndex()) {
        trySendAppendRequest(response);
      }
    } else {
      node.tryRunQueries();
    }
    checkIfQueryAckNeeded(response);
  }

  private boolean updateFollowerIndices(AppendEntriesSuccessResponse response) {
    // If successful: update nextIndex and matchIndex for follower (§5.3)
    LeaderState leaderState = state.leaderState();
    RaftEndpoint follower = response.getSender();
    FollowerState followerState = leaderState.getFollowerStateOrNull(follower);
    if (followerState == null) {
      LOGGER.warn("{} follower/learner: {} not found for {}.", localEndpointStr(), follower.getId(),
        response);
      return false;
    }
    if (
      state.isVotingMember(follower)
        && leaderState.queryState().tryAck(response.getQuerySequenceNumber(), follower)
    ) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(localEndpointStr() + " ack from " + follower.getId()
          + " for query sequence number: " + response.getQuerySequenceNumber());
      }
    }
    long matchIndex = followerState.matchIndex();
    long followerLastLogIndex = response.getLastLogIndex();
    followerState.responseReceived(response.getFlowControlSequenceNumber(),
      node.getClock().millis());
    if (followerLastLogIndex > matchIndex) {
      long newNextIndex = followerLastLogIndex + 1;
      followerState.matchIndex(followerLastLogIndex);
      followerState.nextIndex(newNextIndex);
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(localEndpointStr() + " Updated match index: " + followerLastLogIndex
          + " and next index: " + newNextIndex + " for follower: " + follower.getId());
      }
      return true;
    } else if (followerLastLogIndex < matchIndex && LOGGER.isDebugEnabled()) {
      LOGGER
        .debug(localEndpointStr() + " Will not update match index for follower: " + follower.getId()
          + ". follower last log index: " + followerLastLogIndex + ", match index: " + matchIndex);
    }
    return false;
  }

  private void checkIfQueryAckNeeded(AppendEntriesSuccessResponse response) {
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      // this can happen if this node was removed from the group when
      // the commit index was advanced.
      return;
    } else if (!state.isVotingMember(response.getSender())) {
      // learners are not part of the replication quorum.
      return;
    }
    QueryState queryState = leaderState.queryState();
    if (queryState.isAckNeeded(response.getSender(), state.logReplicationQuorumSize())) {
      node.sendAppendEntriesRequest(response.getSender());
    }
  }

  private void trySendAppendRequest(AppendEntriesSuccessResponse response) {
    long followerLastLogIndex = response.getLastLogIndex();
    if (
      state.log().lastLogOrSnapshotIndex() > followerLastLogIndex
        || state.commitIndex() == followerLastLogIndex
    ) {
      // If the follower is still missing some log entries or has not learnt the
      // latest commit index yet,
      // then send another append request.
      node.sendAppendEntriesRequest(response.getSender());
    }
  }
}
