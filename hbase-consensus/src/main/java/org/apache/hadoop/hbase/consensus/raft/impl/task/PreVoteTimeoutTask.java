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

import static org.apache.hadoop.hbase.consensus.raft.RaftRole.FOLLOWER;

import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled by {@link PreVoteTask} to trigger pre-voting again if the local Raft node is still a
 * follower and a leader is not yet available after the leader election timeout.
 */
@InterfaceAudience.Private
public final class PreVoteTimeoutTask extends RaftNodeStatusAwareTask implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(PreVoteTimeoutTask.class);
  private final int term;

  public PreVoteTimeoutTask(RaftNodeImpl raftNode, int term) {
    super(raftNode);
    this.term = term;
  }

  @Override
  protected void doRun() {
    if (state.role() != FOLLOWER) {
      return;
    }
    LOG.debug("{} Pre-vote for term: {} has timed out!", localEndpointStr(), node.state().term());
    new PreVoteTask(node, term).run();
  }
}
