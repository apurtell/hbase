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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Per-RaftNode self-rescheduling heartbeat task used by the default
 * {@code DefaultHeartbeatScheduler}.
 * <p>
 * The actual per-tick logic (leader heartbeat broadcast, catch-up appends, election-timer checks,
 * pre-vote triggering) lives on {@link RaftNodeImpl#runHeartbeatTick()} so it can be shared with
 * any alternate {@code HeartbeatScheduler}. This task is a thin wrapper that delegates and then
 * re-schedules itself on the node's own executor at {@code config.getLeaderHeartbeatPeriodMillis()}
 * cadence.
 */
@InterfaceAudience.Private
public class HeartbeatTask extends RaftNodeStatusAwareTask {
  public HeartbeatTask(RaftNodeImpl node) {
    super(node);
  }

  @Override
  protected void doRun() {
    try {
      node.runHeartbeatTick();
    } finally {
      node.getExecutor().schedule(this, node.getConfig().getLeaderHeartbeatPeriodMillis(),
        MILLISECONDS);
    }
  }
}
