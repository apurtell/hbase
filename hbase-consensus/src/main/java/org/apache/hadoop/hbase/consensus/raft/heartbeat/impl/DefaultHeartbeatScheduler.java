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
package org.apache.hadoop.hbase.consensus.raft.heartbeat.impl;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.task.HeartbeatTask;

/**
 * Default {@link HeartbeatScheduler}: schedules a per-RaftNode {@link HeartbeatTask} on the node's
 * own executor.
 * <p>
 * Stateless singleton. The per-node {@code HeartbeatTask} self-reschedules and self-cancels when
 * the underlying executor is shut down on terminate, so {@link #unregister(RaftNodeImpl)} is a
 * no-op.
 */
public final class DefaultHeartbeatScheduler implements HeartbeatScheduler {

  public static final DefaultHeartbeatScheduler INSTANCE = new DefaultHeartbeatScheduler();

  private DefaultHeartbeatScheduler() {
  }

  @Override
  public void register(@NonNull RaftNodeImpl node) {
    node.getExecutor().schedule(new HeartbeatTask(node),
      node.getConfig().getLeaderHeartbeatPeriodMillis(), MILLISECONDS);
  }

  @Override
  public void unregister(@NonNull RaftNodeImpl node) {
    // No per-node bookkeeping needed in the default scheduler.
  }
}
