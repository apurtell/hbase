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
 * Unified durable consensus log: SPI ({@link
 * org.apache.hadoop.hbase.consensus.handler.store.DurableLogStore} container, {@link
 * org.apache.hadoop.hbase.consensus.handler.store.LogStoreSerializer} codec) plus the default
 * hand-rolled local-FS multiplexed append-only implementation
 * ({@link org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore}) structurally inspired
 * by ZooKeeper's {@code FileTxnLog}.
 * <p>
 * One {@code DurableLogStore} instance is shared by every Raft group on a RegionServer; the
 * per-group {@link org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore} view is obtained
 * via {@link org.apache.hadoop.hbase.consensus.handler.store.DurableLogStore#newGroupStore(byte[])}
 * after a one-shot whole-disk replay performed by
 * {@link org.apache.hadoop.hbase.consensus.handler.store.DurableLogStore#load()}.
 * <p>
 * On-disk frames carry a fixed {@code [frame_len:uint32][crc32c:uint32]} preamble followed by
 * varint-encoded fields and a kind-specific payload. Segment files start with a
 * {@code 'CSLG'}+version magic prologue. CRC failures and torn-tail truncations are treated
 * identically. The offending segment is truncated at the bad offset and every later segment is
 * deleted. Whatever state was reconstructed up to that point is the restored state, and Raft's
 * standard {@code AppendEntries} / {@code InstallSnapshot} catchup at the layer above closes any
 * gap automatically.
 */
package org.apache.hadoop.hbase.consensus.handler.store;
