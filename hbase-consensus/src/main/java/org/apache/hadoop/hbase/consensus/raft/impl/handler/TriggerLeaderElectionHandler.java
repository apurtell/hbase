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
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.task.LeaderElectionTask;
import org.apache.hadoop.hbase.consensus.raft.model.log.BaseLogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a {@link TriggerLeaderElectionRequest} and initiates a new leader election round if this
 * Raft node accepts the sender as the leader and the local Raft log is up-to-date with the leader's
 * Raft log.
 * @see TriggerLeaderElectionRequest
 */
@InterfaceAudience.Private
public class TriggerLeaderElectionHandler
  extends AbstractMessageHandler<TriggerLeaderElectionRequest> {
  private static final Logger LOG = LoggerFactory.getLogger(TriggerLeaderElectionHandler.class);

  public TriggerLeaderElectionHandler(RaftNodeImpl raftNode, TriggerLeaderElectionRequest request) {
    super(raftNode, request);
  }

  @Override
  protected void handle(@NonNull TriggerLeaderElectionRequest request) {
    requireNonNull(request);
    LOG.debug("{} Received {}", localEndpointStr(), request);
    // Verify the term and the leader.
    // If the requesting leader is legit,
    // I will eventually accept it as the leader with a periodic append request.
    // Once I pass this if block, I know that I am follower and my log is same
    // with the leader's log.
    if (!(request.getTerm() == state.term() && request.getSender().equals(state.leader()))) {
      LOG.debug("{} Ignoring {} since term: {} and leader: {}", localEndpointStr(), request,
        state.term(), state.leader() != null ? state.leader().getId() : "-");
      return;
    }
    // Verify the last log entry
    BaseLogEntry entry = state.log().lastLogOrSnapshotEntry();
    if (
      !(entry.getIndex() == request.getLastLogIndex()
        && entry.getTerm() == request.getLastLogTerm())
    ) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(
          "{} Could not accept leadership transfer because local Raft log is not same with the current "
            + "leader. Last log entry: {}, request: {}",
          localEndpointStr(), entry, request);
      }
      return;
    }
    if (state.role() == LEARNER) {
      // this should not happen!
      LOG.error(
        "{} Could start leader election because the role is: {}. You should not see this log!",
        localEndpointStr(), LEARNER);
      return;
    }
    // I will send a non-sticky VoteRequest to bypass leader stickiness
    LOG.info(
      "{} Starting a new leader election since the current leader: {} in term: {} asked for a "
        + "leadership transfer!",
      localEndpointStr(), request.getSender().getId(), request.getTerm());
    node.leader(null);
    new LeaderElectionTask(node, false).run();
  }
}
