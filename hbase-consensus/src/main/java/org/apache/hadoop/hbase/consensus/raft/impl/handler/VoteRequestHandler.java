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

import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.LEARNER;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.task.LeaderElectionTask;
import org.apache.hadoop.hbase.consensus.raft.model.log.BaseLogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse.VoteResponseBuilder;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a {@link VoteRequest} sent by a candidate and responds with a {@link VoteResponse}.
 * <p>
 * Leader election is initiated by {@link LeaderElectionTask}.
 * <p>
 * See <i>5.2 Leader election</i> section of <i>In Search of an Understandable Consensus
 * Algorithm</i> paper by <i>Diego Ongaro</i> and <i>John Ousterhout</i>.
 * @see VoteRequest
 * @see VoteResponse
 * @see LeaderElectionTask
 */
@InterfaceAudience.Private
public class VoteRequestHandler extends AbstractMessageHandler<VoteRequest> {
  private static final Logger LOG = LoggerFactory.getLogger(VoteRequestHandler.class);

  public VoteRequestHandler(RaftNodeImpl raftNode, VoteRequest request) {
    super(raftNode, request);
  }

  @Override
  @SuppressWarnings({ "checkstyle:npathcomplexity", "checkstyle:cyclomaticcomplexity" })
  // Justification: It is easier to follow the RequestVoteRPC logic in a single
  // method
  protected void handle(@NonNull VoteRequest request) {
    requireNonNull(request);
    VoteResponseBuilder responseBuilder = modelFactory.createVoteResponseBuilder()
      .setGroupId(node.getGroupId()).setSender(localEndpoint());
    RaftEndpoint candidate = request.getSender();
    int candidateTerm = request.getTerm();
    // Reply false if term < currentTerm (§5.1)
    if (state.term() > candidateTerm) {
      LOG.info("{} Rejecting {} since current term: {} is bigger.", localEndpointStr(), request,
        state.term());
      node.send(candidate, responseBuilder.setTerm(state.term()).setGranted(false).build());
      if (state.leaderState() != null) {
        node.sendAppendEntriesRequest(candidate);
      }
      return;
    }
    // (Raft thesis - Section 4.2.3) This check conflicts with the leadership
    // transfer mechanism,
    // in which a server legitimately starts an election without waiting a leader
    // heartbeat timeout.
    // Those VoteRequest objects are marked as "non-sticky" to bypass leader
    // stickiness.
    // Also if the request comes from the current leader, then the leader stickiness
    // check is skipped.
    // Since the current leader may have restarted by recovering its persistent
    // state.
    if (
      request.isSticky() && (state.leaderState() != null || !node.isLeaderHeartbeatTimeoutElapsed())
        && !candidate.equals(state.leader())
    ) {
      LOG.info("{} Rejecting {} since the leader is still alive...", localEndpointStr(), request);
      node.send(candidate, responseBuilder.setTerm(state.term()).setGranted(false).build());
      return;
    }
    if (state.term() < candidateTerm) {
      // If the request term is greater than the local term, update the local term and
      // convert to follower (§5.1)
      LOG.info("{} Moving to new term: {} from current term: {} after {}", localEndpointStr(),
        candidateTerm, state.term(), request);
      node.toFollower(candidateTerm);
    }
    if (state.leader() != null && !candidate.equals(state.leader())) {
      LOG.warn("{} Rejecting {} since we have a leader: {}", localEndpointStr(), request,
        state.leader().getId());
      node.send(candidate, responseBuilder.setTerm(candidateTerm).setGranted(false).build());
      return;
    }
    if (state.votedEndpoint() != null) {
      boolean granted = (candidate.equals(state.votedEndpoint()));
      if (granted) {
        LOG.debug("{} Vote granted for {} (duplicate)", localEndpointStr(), request);
      } else {
        LOG.debug("{} no vote for {}. currently voted-for: {}", localEndpointStr(), request,
          state.votedEndpoint().getId());
      }
      node.send(candidate, responseBuilder.setTerm(candidateTerm).setGranted(granted).build());
      return;
    }
    BaseLogEntry lastLogEntry = state.log().lastLogOrSnapshotEntry();
    if (lastLogEntry.getTerm() > request.getLastLogTerm()) {
      LOG.info("{} Rejecting {} since our last log term: {} is greater.", localEndpointStr(),
        request, lastLogEntry.getTerm());
      node.send(candidate, responseBuilder.setTerm(candidateTerm).setGranted(false).build());
      return;
    }
    if (
      lastLogEntry.getTerm() == request.getLastLogTerm()
        && lastLogEntry.getIndex() > request.getLastLogIndex()
    ) {
      LOG.info("{} Rejecting {} since our last log index: {} is greater.", localEndpointStr(),
        request, lastLogEntry.getIndex());
      node.send(candidate, responseBuilder.setTerm(candidateTerm).setGranted(false).build());
      return;
    }
    if (state.role() == LEARNER) {
      LOG.info("{} is {} but {} asked for vote.", localEndpointStr(), LEARNER, candidate.getId());
    }
    LOG.info("{} Granted vote for {}", localEndpointStr(), request);
    state.grantVote(candidateTerm, candidate);
    // Defer our own pre-vote pass so the candidate we just voted for has a
    // chance to win, but do NOT touch lastLeaderHeartbeatTimestamp. A vote
    // grant is not a leader heartbeat, and refreshing it would make
    // (Pre)VoteRequestHandler reject any other higher-term candidate that
    // arrives before the timer elapses, which deadlocks elections when
    // the original candidate fails to gather a quorum.
    node.electionTimerReset();
    node.send(candidate, responseBuilder.setTerm(candidateTerm).setGranted(true).build());
  }
}
