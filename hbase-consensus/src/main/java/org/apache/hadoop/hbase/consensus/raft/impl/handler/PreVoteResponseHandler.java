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

import static org.apache.hadoop.hbase.consensus.raft.RaftRole.FOLLOWER;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.state.CandidateState;
import org.apache.hadoop.hbase.consensus.raft.impl.task.LeaderElectionTask;
import org.apache.hadoop.hbase.consensus.raft.impl.task.PreVoteTask;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a {@link PreVoteResponse} sent for a {@link PreVoteRequest}.
 * <p>
 * Initiates a new leader election by executing {@link LeaderElectionTask} if the Raft group
 * majority grants "pre-votes" for this pre-voting term.
 * @see PreVoteResponse
 * @see PreVoteTask
 * @see LeaderElectionTask
 */
@InterfaceAudience.Private
public class PreVoteResponseHandler extends AbstractResponseHandler<PreVoteResponse> {
  private static final Logger LOG = LoggerFactory.getLogger(PreVoteResponseHandler.class);

  public PreVoteResponseHandler(RaftNodeImpl raftNode, PreVoteResponse response) {
    super(raftNode, response);
  }

  @Override
  protected void handleResponse(@NonNull PreVoteResponse response) {
    LOG.debug("{} received {}.", localEndpointStr(), response);
    if (state.role() != FOLLOWER) {
      LOG.debug("{} Ignored {}. We are not FOLLOWER anymore.", localEndpointStr(), response);
      return;
    }
    if (response.getTerm() < state.term()) {
      LOG.warn("{} Stale {} is received, current term: {}", localEndpointStr(), response,
        state.term());
      return;
    }
    CandidateState preCandidateState = state.preCandidateState();
    if (preCandidateState == null) {
      LOG.debug("{} Ignoring {}. We are not interested in pre-votes anymore.", localEndpointStr(),
        response);
      return;
    }
    if (response.isGranted() && preCandidateState.grantVote(response.getSender())) {
      LOG.info("{} Pre-vote granted from {} for term: {}, number of votes: {}, majority: {}",
        localEndpointStr(), response.getSender().getId(), response.getTerm(),
        preCandidateState.voteCount(), preCandidateState.majority());
    }
    if (preCandidateState.isMajorityGranted()) {
      LOG.info("{} We have the majority during pre-vote phase. Let's start real election!",
        localEndpointStr());
      new LeaderElectionTask(node, true).run();
    }
  }
}
