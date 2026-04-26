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
 * Heartbeat scheduling SPI used by the RAFT core.
 * <p>
 * {@link org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler} is the abstraction
 * that arranges periodic invocation of
 * {@link org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl#runHeartbeatTick()} on each
 * Raft node's executor. The default implementation
 * ({@link org.apache.hadoop.hbase.consensus.raft.heartbeat.impl.DefaultHeartbeatScheduler})
 * schedules a per-Raft-node {@code HeartbeatTask} on the node's own executor. Store-level
 * implementations (e.g. the {@code SweepingHeartbeatScheduler} under
 * {@code handler/heartbeat/}) can sweep all registered nodes from a single timer to coalesce
 * heartbeat traffic in the transport layer.
 */
package org.apache.hadoop.hbase.consensus.raft.heartbeat;
