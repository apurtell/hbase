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

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.task.RaftNodeStatusAwareTask;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.yetus.audience.InterfaceAudience;

/** Base class for {@link RaftMessage} handlers. */
@InterfaceAudience.Private
public abstract class AbstractMessageHandler<T extends RaftMessage>
  extends RaftNodeStatusAwareTask {
  protected final T message;

  AbstractMessageHandler(RaftNodeImpl raftNode, T message) {
    super(raftNode);
    this.message = message;
  }

  @Override
  protected final void doRun() {
    preHandle();
    handle(message);
  }

  /**
   * Hook run before the typed handler. The default implementation clears the follower's local
   * quiescence flag. {@link LeaderHeartbeatHandler} overrides this hook and decides quiescence
   * transitions explicitly.
   */
  protected void preHandle() {
    if (state.role() != RaftRole.LEADER && state.groupQuiescent()) {
      state.groupQuiescent(false);
    }
  }

  protected abstract void handle(@NonNull T message);
}
