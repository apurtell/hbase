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
package org.apache.hadoop.hbase.consensus.raft.impl.task;

import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.PreVoteResponseHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled when the current leader is null, unreachable, or unknown by
 * {@link PreVoteResponseHandler} after a follower receives votes from the majority. A Raft node
 * becomes a candidate via {@link RaftState#toCandidate()} and sends {@link VoteRequest}s to the
 * other Raft group members.
 * <p>
 * Also a {@link LeaderElectionTimeoutTask} is scheduled with the
 * {@link RaftNodeImpl#getLeaderElectionTimeoutMs()} delay to trigger another round of leader
 * election if a leader is not elected yet.
 */
@InterfaceAudience.Private
public final class LeaderElectionTask extends RaftNodeStatusAwareTask implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionTask.class);
  private final boolean sticky;

  public LeaderElectionTask(RaftNodeImpl raftNode, boolean sticky) {
    super(raftNode);
    this.sticky = sticky;
  }

  @Override
  protected void doRun() {
    if (state.leader() != null) {
      LOG.warn("{} No new election round, we already have a LEADER: {}", localEndpointStr(),
        state.leader().getId());
      return;
    }
    node.toCandidate(sticky);
  }
}
