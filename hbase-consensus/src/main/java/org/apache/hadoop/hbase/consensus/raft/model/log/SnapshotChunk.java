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
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;

/**
 * Represents a snapshot chunk.
 * <p>
 * A snapshot entry in the Raft log contains at least one snapshot chunk.
 * <p>
 * Snapshot chunks are ordered by snapshot chunk indices. They contain objects provided to the
 * consumer argument of {@link StateMachine#takeSnapshot(long, Consumer)}. Additionally, a snapshot
 * chunk contains the committed Raft group member list along with its commit index at the time of
 * the snapshot creation.
 */
public interface SnapshotChunk extends BaseLogEntry {
  int getSnapshotChunkIndex();

  int getSnapshotChunkCount();

  @NonNull
  RaftGroupMembersView getGroupMembersView();

  interface SnapshotChunkBuilder {
    @NonNull
    SnapshotChunkBuilder setIndex(long index);

    @NonNull
    SnapshotChunkBuilder setTerm(int term);

    @NonNull
    SnapshotChunkBuilder setOperation(@NonNull Object operation);

    @NonNull
    SnapshotChunkBuilder setSnapshotChunkIndex(int snapshotChunkIndex);

    @NonNull
    SnapshotChunkBuilder setSnapshotChunkCount(int snapshotChunkCount);

    @NonNull
    SnapshotChunkBuilder setGroupMembersView(@NonNull RaftGroupMembersView groupMembersView);

    @NonNull
    SnapshotChunk build();
  }
}
