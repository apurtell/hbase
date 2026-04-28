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
 * Default {@link org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler}
 * implementations.
 * <p>
 * {@link org.apache.hadoop.hbase.consensus.raft.heartbeat.impl.BulkHeartbeatScheduler} is a
 * timing-wheel scheduler that owns its own
 * {@link java.util.concurrent.ScheduledThreadPoolExecutor} and walks every registered
 * {@link org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl} once per tick on its wheel
 * thread, aggregating one entry per local leader group whose committed membership includes a
 * given remote peer into a single bulk heartbeat envelope per (sender, peer) per tick. It
 * implements
 * {@link org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware} so that, when
 * installed by the {@link org.apache.hadoop.hbase.consensus.raft.RaftNode.RaftNodeBuilder} as
 * the per-node fallback, its lifetime is automatically tied to the node. Server-scoped embedders
 * share one instance across all of a server's nodes and install it via
 * {@link
 * org.apache.hadoop.hbase.consensus.raft.RaftNode.RaftNodeBuilder#setHeartbeatScheduler}.
 */
package org.apache.hadoop.hbase.consensus.raft.heartbeat.impl;
