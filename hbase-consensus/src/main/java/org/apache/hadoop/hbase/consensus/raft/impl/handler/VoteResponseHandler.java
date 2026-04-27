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

import static org.apache.hadoop.hbase.consensus.raft.RaftRole.CANDIDATE;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.state.CandidateState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a {@link VoteResponse} sent for a {@link VoteRequest}.
 * <p>
 * Changes the local Raft node's role to {@link RaftRole#LEADER} via {@link RaftState#toLeader()} if
 * the majority votes has been granted for this term.
 * <p>
 * In the beginning of the new term, the Raft group leader appends a new log entry that contains an
 * operation which is returned via {@link StateMachine#getNewTermOperation()}.
 * <p>
 * See <i>5.2 Leader election</i> section of <i>In Search of an Understandable Consensus
 * Algorithm</i> paper by <i>Diego Ongaro</i> and <i>John Ousterhout</i>.
 * @see VoteRequest
 * @see VoteResponse
 */
@InterfaceAudience.Private
public class VoteResponseHandler extends AbstractResponseHandler<VoteResponse> {
  private static final Logger LOG = LoggerFactory.getLogger(VoteResponseHandler.class);

  public VoteResponseHandler(RaftNodeImpl raftNode, VoteResponse response) {
    super(raftNode, response);
  }

  @Override
  protected void handleResponse(@NonNull VoteResponse response) {
    if (response.getTerm() > state.term()) {
      // If the response term is greater than the local term, update the local term
      // and convert to follower (§5.1), even if we already became leader in a lower term.
      LOG.info("{} Moving to new term: {} from current term: {} after {}", localEndpointStr(),
        response.getTerm(), state.term(), response);
      node.toFollower(response.getTerm());
      return;
    }
    if (response.getTerm() < state.term()) {
      LOG.warn("{} Stale {} is received, current term: {}", localEndpointStr(), response,
        state.term());
      return;
    }
    if (state.role() != CANDIDATE) {
      LOG.debug("{} Ignored {}. We are not CANDIDATE anymore.", localEndpointStr(), response);
      return;
    }
    CandidateState candidateState = state.candidateState();
    if (response.isGranted() && candidateState.grantVote(response.getSender())) {
      LOG.info("{} Vote granted from {} for term: {}, number of votes: {}, majority: {}",
        localEndpointStr(), response.getSender().getId(), state.term(), candidateState.voteCount(),
        candidateState.majority());
    }
    if (candidateState.isMajorityGranted()) {
      LOG.info("{} We are the LEADER!", localEndpointStr());
      node.toLeader();
    }
  }
}
