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
 * Server-level composition of the consensus engine.
 * <p>
 * {@link org.apache.hadoop.hbase.consensus.handler.server.ConsensusServer} owns the long-lived
 * components that every Raft group on a single JVM shares (a
 * {@link org.apache.hadoop.hbase.consensus.handler.executor.MultiGroupExecutor},
 * {@link org.apache.hadoop.hbase.consensus.handler.transport.CoalescingTransport},
 * {@link org.apache.hadoop.hbase.consensus.raft.heartbeat.impl.BulkHeartbeatScheduler}, and
 * {@link org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore}) and exposes a
 * {@link org.apache.hadoop.hbase.consensus.handler.server.GroupManager} surface for adding,
 * removing, and inspecting Raft groups.
 * <p>
 * Each registered group is wired to its own
 * {@link org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi} via a
 * {@link org.apache.hadoop.hbase.consensus.handler.statemachine.StateMachineAdapter} and a
 * {@link org.apache.hadoop.hbase.consensus.handler.statemachine.LeaderReportListener}, so the
 * application code that consumes committed entries lives outside the consensus engine.
 */
package org.apache.hadoop.hbase.consensus.handler.server;
