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
import java.io.IOException;
import java.util.List;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Per-group view onto a {@link UnifiedRaftStore}.
 * <p>
 * Each {@code GroupRaftStore} carries its {@code groupId} and forwards every {@link RaftStore}
 * operation back to the shared writer machinery on the parent store. Stateless beyond the group id,
 * so multiple adapters for the same group are functionally interchangeable.
 */
@InterfaceAudience.Private
final class GroupRaftStore implements RaftStore {

  private final UnifiedRaftStore parent;
  private final byte[] groupId;

  GroupRaftStore(@NonNull UnifiedRaftStore parent, @NonNull byte[] groupId) {
    this.parent = parent;
    this.groupId = groupId;
  }

  @Override
  public void persistAndFlushLocalEndpoint(
    @NonNull RaftEndpointPersistentState localEndpointPersistentState) throws IOException {
    parent.persistAndFlushLocalEndpoint(groupId, localEndpointPersistentState);
  }

  @Override
  public void persistAndFlushInitialGroupMembers(@NonNull RaftGroupMembersView initialGroupMembers)
    throws IOException {
    parent.persistAndFlushInitialGroupMembers(groupId, initialGroupMembers);
  }

  @Override
  public void persistAndFlushTerm(@NonNull RaftTermPersistentState termPersistentState)
    throws IOException {
    parent.persistAndFlushTerm(groupId, termPersistentState);
  }

  @Override
  public void persistLogEntries(@NonNull List<LogEntry> logEntries) throws IOException {
    parent.persistLogEntries(groupId, logEntries);
  }

  @Override
  public void persistLogEntriesAndFlush(@NonNull List<LogEntry> logEntries) throws IOException {
    parent.persistLogEntriesAndFlush(groupId, logEntries);
  }

  @Override
  public void persistSnapshotChunk(@NonNull SnapshotChunk snapshotChunk) throws IOException {
    parent.persistSnapshotChunk(groupId, snapshotChunk);
  }

  @Override
  public void truncateLogEntriesFrom(long logIndexInclusive) throws IOException {
    parent.truncateLogEntriesFrom(groupId, logIndexInclusive);
  }

  @Override
  public void truncateLogEntriesUntil(long logIndexInclusive) throws IOException {
    parent.truncateLogEntriesUntil(groupId, logIndexInclusive);
  }

  @Override
  public void deleteSnapshotChunks(long logIndex, int snapshotChunkCount) throws IOException {
    parent.deleteSnapshotChunks(groupId, logIndex, snapshotChunkCount);
  }

  @Override
  public void flush() throws IOException {
    parent.flushBarrier(groupId);
  }
}
