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
 * Netty + Protobuf coalescing transport for the consensus engine.
 * <p>
 * {@link org.apache.hadoop.hbase.consensus.handler.transport.CoalescingTransport} implements
 * {@link org.apache.hadoop.hbase.consensus.raft.transport.Transport} on top of {@code
 * hbase-shaded-netty} (used through the {@code org.apache.hbase.thirdparty.io.netty.*} package)
 * and a single per-module {@code .proto} compiled into the shaded
 * {@code org.apache.hbase.thirdparty.com.google.protobuf} runtime
 * ({@link org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos}). For each peer
 * one outbound TCP channel is opened lazily and all messages bound for that peer in one flush
 * window are coalesced into a single {@code ConsensusFrame}: {@code AppendEntries} from many
 * groups land in a {@code BatchAppendEntriesPB}, heartbeats land in a {@code HeartbeatBatchPB}
 * (tagged via {@link org.apache.hadoop.hbase.consensus.handler.transport.HeartbeatRaftMessage}),
 * and single-message frames (vote, install snapshot, append responses, etc.) flush immediately
 * in arrival order to preserve per-peer FIFO.
 * <p>
 * Inbound delivery is demultiplexed by group id via
 * {@link org.apache.hadoop.hbase.consensus.handler.transport.RegistryDispatcher} and forwarded to
 * the local
 * {@link org.apache.hadoop.hbase.consensus.raft.RaftNode#handle(org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage)},
 * which itself trampolines through the per-group
 * {@link org.apache.hadoop.hbase.consensus.handler.executor.MultiGroupExecutor} from Phase 2; the
 * transport never blocks on Raft state.
 * <p>
 * Two SPIs are injected at construction:
 * {@link org.apache.hadoop.hbase.consensus.handler.transport.EndpointResolver} resolves a
 * {@link org.apache.hadoop.hbase.consensus.raft.RaftEndpoint} to an
 * {@link java.net.InetSocketAddress}, and
 * {@link org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec} serialises the
 * opaque operation in each
 * {@link org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry}. The built-in codec handles
 * {@link org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp};
 * everything else is plugged in by callers.
 * <p>
 * Optional per-entry payload compression goes through the existing hbase codec registry
 * ({@link org.apache.hadoop.hbase.io.compress.Compression.Algorithm}); the outbound algorithm is
 * resolved once at transport construction, stamped per-entry on the wire as the
 * {@code LogEntryPB.op_payload_compression} ordinal, and the inbound side resolves the codec on
 * demand from that ordinal so peers do not have to share a compression configuration.
 * {@code Compressor}/{@code Decompressor} instances are pulled from
 * {@link org.apache.hadoop.io.compress.CodecPool} per call.
 */
package org.apache.hadoop.hbase.consensus.handler.transport;
