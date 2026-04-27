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
package org.apache.hadoop.hbase.consensus.raft.persistence;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Contains restored state of a {@link RaftNode}. All the fields in this class are persisted via
 * {@link RaftStore}.
 */
@InterfaceAudience.Private
public final class RestoredRaftState {
  private final RaftEndpointPersistentState localEndpointPersistentState;
  private final RaftGroupMembersView initialGroupMembers;
  private final RaftTermPersistentState termPersistentState;
  private final SnapshotEntry snapshotEntry;
  private final List<LogEntry> entries;

  public RestoredRaftState(@NonNull RaftEndpointPersistentState localEndpointPersistentState,
    @NonNull RaftGroupMembersView initialGroupMembers,
    @NonNull RaftTermPersistentState termPersistentState, @Nullable SnapshotEntry snapshotEntry,
    @NonNull List<LogEntry> entries) {
    this.localEndpointPersistentState = requireNonNull(localEndpointPersistentState);
    this.initialGroupMembers = requireNonNull(initialGroupMembers);
    this.termPersistentState = termPersistentState;
    this.snapshotEntry = snapshotEntry;
    this.entries = requireNonNull(entries);
  }

  @NonNull
  public RaftEndpointPersistentState getLocalEndpointPersistentState() {
    return localEndpointPersistentState;
  }

  @NonNull
  public RaftGroupMembersView getInitialGroupMembers() {
    return initialGroupMembers;
  }

  @NonNull
  public RaftTermPersistentState getTermPersistentState() {
    return termPersistentState;
  }

  @Nullable
  public SnapshotEntry getSnapshotEntry() {
    return snapshotEntry;
  }

  @NonNull
  public List<LogEntry> getLogEntries() {
    return entries;
  }
}
