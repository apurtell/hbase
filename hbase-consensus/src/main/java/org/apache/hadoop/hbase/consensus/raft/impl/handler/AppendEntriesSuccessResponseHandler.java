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
import org.apache.yetus.audience.InterfaceAudience;
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
@InterfaceAudience.Private
public class AppendEntriesSuccessResponseHandler
  extends AbstractResponseHandler<AppendEntriesSuccessResponse> {
  private static final Logger LOG =
    LoggerFactory.getLogger(AppendEntriesSuccessResponseHandler.class);

  public AppendEntriesSuccessResponseHandler(RaftNodeImpl raftNode,
    AppendEntriesSuccessResponse response) {
    super(raftNode, response);
  }

  @Override
  protected void handleResponse(@NonNull AppendEntriesSuccessResponse response) {
    LOG.trace(
      "TRACE> {} AESuccessHandler enter sender={} qsn={} lastLogIndex={} term={} role={}"
        + " status={}",
      localEndpointStr(), response.getSender().getId(), response.getQuerySequenceNumber(),
      response.getLastLogIndex(), state.term(), state.role(), node.getStatus());
    if (response.getTerm() > state.term()) {
      LOG.info("{} Moving to new term: {} from current term: {} after {}", localEndpointStr(),
        response.getTerm(), state.term(), response);
      node.toFollower(response.getTerm());
      return;
    }
    if (state.role() != LEADER) {
      LOG.warn("{} Ignored {}. We are not LEADER anymore.", localEndpointStr(), response);
      return;
    }
    LOG.debug("{} received {}.", localEndpointStr(), response);
    boolean indicesChanged = updateFollowerIndices(response);
    LOG.trace("TRACE> {} AESuccessHandler updateFollowerIndices indicesChanged={}",
      localEndpointStr(), indicesChanged);
    if (indicesChanged) {
      boolean advanced = node.tryAdvanceCommitIndex();
      LOG.trace("TRACE> {} AESuccessHandler tryAdvanceCommitIndex advanced={}", localEndpointStr(),
        advanced);
      if (!advanced) {
        trySendAppendRequest(response);
      }
    } else {
      LOG.trace("TRACE> {} AESuccessHandler indices NOT changed -> tryRunQueries",
        localEndpointStr());
      node.tryRunQueries();
    }
    checkIfQueryAckNeeded(response);
    refreshLeaderLeaseIfVotingSender(response);
  }

  private void refreshLeaderLeaseIfVotingSender(AppendEntriesSuccessResponse response) {
    if (state.role() != LEADER) {
      return;
    }
    if (!state.isVotingMember(response.getSender())) {
      return;
    }
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      return;
    }
    long now = node.getClock().millis();
    long quorumTs = leaderState.quorumResponseTimestamp(state.logReplicationQuorumSize(), now);
    leaderState.leaseExpiryMillis(quorumTs + node.getConfig().getLeaderLeaseDurationMillis());
  }

  private boolean updateFollowerIndices(AppendEntriesSuccessResponse response) {
    // If successful: update nextIndex and matchIndex for follower (§5.3)
    LeaderState leaderState = state.leaderState();
    RaftEndpoint follower = response.getSender();
    FollowerState followerState = leaderState.getFollowerStateOrNull(follower);
    if (followerState == null) {
      LOG.warn("{} follower/learner: {} not found for {}.", localEndpointStr(), follower.getId(),
        response);
      return false;
    }
    if (
      state.isVotingMember(follower)
        && leaderState.queryState().tryAck(response.getQuerySequenceNumber(), follower)
    ) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(localEndpointStr() + " ack from " + follower.getId()
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
      if (LOG.isDebugEnabled()) {
        LOG.debug(localEndpointStr() + " Updated match index: " + followerLastLogIndex
          + " and next index: " + newNextIndex + " for follower: " + follower.getId());
      }
      return true;
    } else if (followerLastLogIndex < matchIndex && LOG.isDebugEnabled()) {
      LOG
        .debug(localEndpointStr() + " Will not update match index for follower: " + follower.getId()
          + ". follower last log index: " + followerLastLogIndex + ", match index: " + matchIndex);
    }
    return false;
  }

  private void checkIfQueryAckNeeded(AppendEntriesSuccessResponse response) {
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      LOG.trace("TRACE> {} checkIfQueryAckNeeded leaderState=null sender={}", localEndpointStr(),
        response.getSender().getId());
      return;
    } else if (!state.isVotingMember(response.getSender())) {
      LOG.trace("TRACE> {} checkIfQueryAckNeeded sender NOT voting sender={} effectiveVoting={}",
        localEndpointStr(), response.getSender().getId(),
        state.effectiveGroupMembers().getVotingMembers());
      return;
    }
    QueryState queryState = leaderState.queryState();
    if (queryState.isAckNeeded(response.getSender(), state.logReplicationQuorumSize())) {
      LOG.trace("TRACE> {} checkIfQueryAckNeeded YES -> sendAppendEntriesRequest sender={}",
        localEndpointStr(), response.getSender().getId());
      node.sendAppendEntriesRequest(response.getSender());
    }
  }

  private void trySendAppendRequest(AppendEntriesSuccessResponse response) {
    long followerLastLogIndex = response.getLastLogIndex();
    long lastLog = state.log().lastLogOrSnapshotIndex();
    long commitIdx = state.commitIndex();
    if (lastLog > followerLastLogIndex || commitIdx == followerLastLogIndex) {
      LOG.trace(
        "TRACE> {} trySendAppendRequest YES sender={} lastLog={} followerLast={} commitIdx={}",
        localEndpointStr(), response.getSender().getId(), lastLog, followerLastLogIndex, commitIdx);
      node.sendAppendEntriesRequest(response.getSender());
    } else {
      LOG.trace(
        "TRACE> {} trySendAppendRequest NO sender={} lastLog={} followerLast={} commitIdx={}",
        localEndpointStr(), response.getSender().getId(), lastLog, followerLastLogIndex, commitIdx);
    }
  }
}
