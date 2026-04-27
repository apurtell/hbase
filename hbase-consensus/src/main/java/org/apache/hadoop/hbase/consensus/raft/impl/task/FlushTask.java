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
import org.apache.hadoop.hbase.consensus.raft.impl.log.RaftLog;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Flushes the Raft node's local Raft log to the persistent storage and tries to advance the commit
 * index if the Raft node is the leader.
 */
@InterfaceAudience.Private
public class FlushTask extends RaftNodeStatusAwareTask {
  public FlushTask(RaftNodeImpl node) {
    super(node);
  }

  @Override
  protected void doRun() {
    RaftLog log = state.log();
    log.flush();
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      return;
    }
    leaderState.flushTaskSubmitted(false);
    leaderState.flushedLogIndex(log.lastLogOrSnapshotIndex());
    node.tryAdvanceCommitIndex();
  }
}
