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

/**
 * Store-level reference implementation of the
 * {@link org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler} SPI from
 * {@code raft/heartbeat/}.
 * <p>
 * {@link org.apache.hadoop.hbase.consensus.handler.heartbeat.SweepingHeartbeatScheduler} owns a
 * single {@link java.util.concurrent.ScheduledThreadPoolExecutor} that drives every registered
 * {@link org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl} from one timer. On each tick it
 * enqueues {@code node.runHeartbeatTick()} on each node's own
 * {@link org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor} (preserving the
 * per-group serial execution contract). All resulting
 * {@link org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat}s land in the
 * per-peer outbound mailboxes within the same flush window of the
 * {@link org.apache.hadoop.hbase.consensus.handler.transport.CoalescingTransport}, so the wire
 * sees a single {@code HEARTBEAT_BATCH} {@code ConsensusFrame} per peer per tick regardless of
 * the number of registered Raft groups.
 * <p>
 * Callers install the sweeping scheduler via
 * {@link org.apache.hadoop.hbase.consensus.raft.RaftNode.RaftNodeBuilder#setHeartbeatScheduler}.
 */
@org.apache.yetus.audience.InterfaceAudience.Private
package org.apache.hadoop.hbase.consensus.handler.heartbeat;
