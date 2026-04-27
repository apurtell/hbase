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
 * Adapter layer between the Raft core's {@code StateMachine} interface and the higher-level
 * {@link org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi}.
 * <p>
 * This package contains:
 * <ul>
 * <li>{@link org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi} — the SPI a
 * consumer implements to receive committed batches, flush completions, leadership
 * notifications, and snapshot lifecycle events from the consensus core.</li>
 * <li>{@link org.apache.hadoop.hbase.consensus.handler.statemachine.CommittedEntry} and
 * {@link org.apache.hadoop.hbase.consensus.handler.statemachine.FlushMarker} value types carried
 * across the SPI.</li>
 * <li>{@link org.apache.hadoop.hbase.consensus.handler.statemachine.StateMachineAdapter} — a
 * single-class implementation of the Raft-core {@code StateMachine} that buffers committed
 * entries into batches, recognises {@code FlushMarker} operations as batch delimiters, and
 * translates {@code takeSnapshot} / {@code installSnapshot} into single-chunk opaque-byte
 * round-trips through the SPI's
 * {@code takeStateSnapshot} / {@code installStateSnapshot} pair.</li>
 * <li>{@link org.apache.hadoop.hbase.consensus.handler.statemachine.LeaderReportListener} — a
 * {@code RaftNodeReportListener} that fans Raft-node lifecycle events (leader-elected,
 * no-leader, follower-lagging) out to the SPI.</li>
 * </ul>
 * <p>
 * Catch-up scope: this layer carries Raft consensus state (term, log entries, snapshot
 * term/index, group-members view) over the wire via the existing chunked
 * {@code AppendEntries} / {@code InstallSnapshot} transport. The SPI's snapshot byte channel is
 * deliberately opaque. The consensus layer never inspects or replicates that payload's contents.
 */
package org.apache.hadoop.hbase.consensus.handler.statemachine;
