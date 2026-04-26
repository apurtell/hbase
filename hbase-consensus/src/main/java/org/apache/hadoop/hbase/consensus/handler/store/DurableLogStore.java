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
package org.apache.hadoop.hbase.consensus.handler.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Container SPI for a durable, multiplexed consensus log shared by every Raft group on a
 * RegionServer. The {@link DurableLogStore} owns the on-disk lifecycle and lends a per-group
 * {@link RaftStore} adapter to each group.
 * <p>
 * Lifecycle:
 * <ol>
 * <li>Caller constructs the implementation (configuration is impl-defined; see e.g.
 * {@link UnifiedRaftStore}).</li>
 * <li>Caller invokes {@link #load()} <em>exactly once</em>. {@code load()} performs a single
 * whole-disk replay, returns per-group {@link RestoredRaftState} for every group with on-disk
 * state, and brings the writer machinery online so subsequent {@link #newGroupStore(byte[])} calls
 * are immediately writable. Groups absent from the returned map have no on-disk state and start
 * fresh. Raft's standard {@code AppendEntries}/{@code InstallSnapshot} catchup at the layer above
 * brings them up to date. CRC failures or torn-write tails detected during replay truncate the bad
 * segment at the offending offset, delete every later segment, and continue with whatever state was
 * reconstructed up to the bad point. Recovery flows through Raft, not local repair.</li>
 * <li>Caller calls {@link #newGroupStore(byte[])} once per Raft group hosted on this server. The
 * returned {@link RaftStore} is the per-group view used by {@code RaftNode}.</li>
 * <li>Caller invokes {@link #close()} on shutdown. Close drains in-flight writes, flushes, and
 * closes all open file handles.</li>
 * </ol>
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.TOOLS })
public interface DurableLogStore extends Closeable {
  /**
   * One-shot whole-disk replay; opens the store for writes.
   * <p>
   * Returns per-group {@link RestoredRaftState} for every group that has records on disk. Groups
   * not present in the returned map have no on-disk state and start fresh; the Raft layer above
   * catches them up via {@code AppendEntriesRequest} (small gaps) or {@code InstallSnapshotRequest}
   * (large gaps).
   * <p>
   * On the first non-{@code Ok} read result anywhere in the on-disk log the offending segment is
   * truncated to the bad offset, every segment with a higher id is deleted, and replay stops.
   * Whatever state was accumulated up to that point is the restored state. CRC failures and
   * torn-tail truncations are treated identically. Both fail-fast and let Raft fix the gap from a
   * peer with intact state.
   * <p>
   * Must be called exactly once before {@link #newGroupStore(byte[])}.
   * @return per-group restored state, keyed by read-only {@code ByteBuffer}-wrapped group id
   * @throws IOException if directory access or segment open fails irrecoverably
   */
  @NonNull
  Map<ByteBuffer, RestoredRaftState> load() throws IOException;

  /**
   * Returns the per-group {@link RaftStore} view used by {@code RaftNode} for ongoing operations.
   * <p>
   * Must only be called after {@link #load()} has returned successfully. Calling
   * {@link #newGroupStore(byte[])} a second time for the same {@code groupId} returns a new adapter
   * that shares the underlying writer machinery.
   * @param groupId the Raft group id (keyed verbatim into the multiplexed log)
   * @return a per-group {@link RaftStore} adapter
   * @throws IOException if the underlying store is closed or otherwise unusable
   */
  @NonNull
  RaftStore newGroupStore(@NonNull byte[] groupId) throws IOException;

  /**
   * Drains in-flight writes, flushes the page cache, closes the active segment, and shuts down the
   * writer thread + fdatasync timer. Idempotent.
   */
  @Override
  void close() throws IOException;
}
