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

import static org.apache.hadoop.hbase.consensus.raft.RaftRole.CANDIDATE;

import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled by {@link LeaderElectionTask} to trigger leader election again if a leader is not
 * elected yet.
 */
@InterfaceAudience.Private
public final class LeaderElectionTimeoutTask extends RaftNodeStatusAwareTask implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionTimeoutTask.class);

  public LeaderElectionTimeoutTask(RaftNodeImpl raftNode) {
    super(raftNode);
  }

  @Override
  protected void doRun() {
    if (state.role() != CANDIDATE) {
      return;
    }
    LOG.warn("{} Leader election for term: {} has timed out!", localEndpointStr(),
      node.state().term());
    new LeaderElectionTask(node, true).run();
  }
}
