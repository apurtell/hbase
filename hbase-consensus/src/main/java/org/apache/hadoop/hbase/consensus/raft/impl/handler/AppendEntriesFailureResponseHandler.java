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
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles an {@link AppendEntriesFailureResponse} which can be sent as a response to a previous
 * append-entries request or an install-snapshot request.
 * <p>
 * Decrements {@code nextIndex} of the follower by 1 if the response is valid.
 * <p>
 * See <i>5.3 Log replication</i> section of <i>In Search of an Understandable Consensus
 * Algorithm</i> paper by <i>Diego Ongaro</i> and <i>John Ousterhout</i>.
 * @see AppendEntriesRequest
 * @see InstallSnapshotRequest
 */
public class AppendEntriesFailureResponseHandler
  extends AbstractResponseHandler<AppendEntriesFailureResponse> {
  private static final Logger LOGGER =
    LoggerFactory.getLogger(AppendEntriesFailureResponseHandler.class);

  public AppendEntriesFailureResponseHandler(RaftNodeImpl raftNode,
    AppendEntriesFailureResponse response) {
    super(raftNode, response);
  }

  @Override
  protected void handleResponse(@NonNull AppendEntriesFailureResponse response) {
    if (state.role() != LEADER) {
      LOGGER.warn("{} {} is ignored since we are not LEADER.", localEndpointStr(), response);
      return;
    }
    if (response.getTerm() > state.term()) {
      // If the response term is greater than the local term, update the local term
      // and convert to follower (§5.1)
      LOGGER.info("{} Switching to term: {} after {} from current term: {}", localEndpointStr(),
        response.getTerm(), response, state.term());
      node.toFollower(response.getTerm());
      return;
    }
    LOGGER.debug("{} received {}.", localEndpointStr(), response);
    node.tryAckQuery(response.getQuerySequenceNumber(), response.getSender());
    if (updateNextIndex(response)) {
      node.sendAppendEntriesRequest(response.getSender());
    }
  }

  private boolean updateNextIndex(AppendEntriesFailureResponse response) {
    RaftEndpoint follower = response.getSender();
    LeaderState leaderState = state.leaderState();
    FollowerState followerState = leaderState.getFollowerStateOrNull(follower);
    if (followerState == null) {
      LOGGER.warn("{} follower/learner: {} not found for {}.", localEndpointStr(), follower.getId(),
        response);
      return false;
    }
    long nextIndex = followerState.nextIndex();
    long matchIndex = followerState.matchIndex();
    followerState.responseReceived(response.getFlowControlSequenceNumber(),
      node.getClock().millis());
    if (response.getExpectedNextIndex() == nextIndex) {
      // this is the response of the request I have sent for this nextIndex
      nextIndex--;
      if (nextIndex <= matchIndex) {
        LOGGER.error("{} Cannot decrement next index: {} below match index: {} for follower: {}",
          localEndpointStr(), nextIndex, matchIndex, follower.getId());
        return false;
      }
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(localEndpointStr() + " Updating next index: " + nextIndex + " for follower: "
          + follower.getId());
      }
      followerState.nextIndex(nextIndex);
      return true;
    }
    return false;
  }
}
