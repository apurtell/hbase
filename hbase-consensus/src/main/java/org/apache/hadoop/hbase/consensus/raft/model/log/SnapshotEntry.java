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
package org.apache.hadoop.hbase.consensus.raft.model.log;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Represents a snapshot in the Raft log.
 * <p>
 * A snapshot entry is also placed on a Raft log index, just like a regular log entry, but instead
 * of user-provided operations present in log entries, a snapshot entry contains objects that are
 * returned from {@link StateMachine#takeSnapshot(long, Consumer)}. Additionally, a snapshot entry
 * contains the committed Raft group member list along with its commit index at the time of the
 * snapshot creation.
 */
@InterfaceAudience.Private
public interface SnapshotEntry extends BaseLogEntry {
  static boolean isNonInitial(@Nullable SnapshotEntry snapshotEntry) {
    return snapshotEntry != null && snapshotEntry.getIndex() > 0;
  }

  int getSnapshotChunkCount();

  @NonNull
  RaftGroupMembersView getGroupMembersView();

  /** The builder interface for {@link SnapshotEntry}. */
  interface SnapshotEntryBuilder {
    @NonNull
    SnapshotEntryBuilder setIndex(long index);

    @NonNull
    SnapshotEntryBuilder setTerm(int term);

    @NonNull
    SnapshotEntryBuilder setSnapshotChunks(@NonNull List<SnapshotChunk> snapshotChunks);

    @NonNull
    SnapshotEntryBuilder setGroupMembersView(@NonNull RaftGroupMembersView groupMembersView);

    @NonNull
    SnapshotEntry build();
  }
}
