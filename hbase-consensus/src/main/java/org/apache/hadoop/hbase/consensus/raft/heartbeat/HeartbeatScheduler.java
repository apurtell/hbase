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
package org.apache.hadoop.hbase.consensus.raft.heartbeat;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;

/**
 * SPI for arranging periodic invocation of {@link RaftNodeImpl#runHeartbeatTick()}.
 * <p>
 * In the default implementation
 * ({@code org.apache.hadoop.hbase.consensus.raft.heartbeat.impl.DefaultHeartbeatScheduler}) each
 * registered node schedules its own self-rescheduling task on its own {@code RaftNodeExecutor} at
 * the configured {@code leaderHeartbeatPeriodMillis} cadence.
 */
public interface HeartbeatScheduler {
  /**
   * Called from {@code RaftNodeImpl.initTasks} during start up. The scheduler arranges for
   * {@code node.runHeartbeatTick()} to be invoked periodically on {@code node.getExecutor()}.
   * <p>
   * Implementations should be safe under the same node being passed multiple times. The recommended
   * behavior is "second register is a no-op" for the same {@code (groupId, node)} pair, and to
   * throw {@code IllegalArgumentException} if a different node is presented for an
   * already-registered groupId.
   */
  void register(@NonNull RaftNodeImpl node);

  /**
   * Called from {@code RaftNodeImpl.terminate} before component teardown. Invocations for a node
   * that was never registered, or registered and already removed, must be no-ops.
   */
  void unregister(@NonNull RaftNodeImpl node);
}
