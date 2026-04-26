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
package org.apache.hadoop.hbase.consensus.raft.model.message;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.statemachine.CatchUpReference;

/**
 * Raft message for the InstallSnapshot RPC.
 * <p>
 * See <i>7 Log compaction</i> section of <i>In Search of an Understandable Consensus Algorithm</i>
 * paper by <i>Diego Ongaro</i> and <i>John Ousterhout</i>.
 * <p>
 * Invoked by leader to send chunks of a snapshot to a follower. Chunks are sent in the order
 * defined by the follower and the follower is free to request the chunks in any order.
 * @see InstallSnapshotResponse
 */
public interface InstallSnapshotRequest extends RaftMessage {
  boolean isSenderLeader();

  int getSnapshotTerm();

  long getSnapshotIndex();

  int getTotalSnapshotChunkCount();

  @Nullable
  SnapshotChunk getSnapshotChunk();

  @NonNull
  Collection<RaftEndpoint> getSnapshottedMembers();

  @NonNull
  RaftGroupMembersView getGroupMembersView();

  long getQuerySequenceNumber();

  long getFlowControlSequenceNumber();

  /** Optional catch-up-by-reference metadata. Ignored by the current chunked install path. */
  @Nullable
  CatchUpReference getCatchUpReference();

  /** The builder interface for {@link InstallSnapshotRequest}. */
  interface InstallSnapshotRequestBuilder extends RaftMessageBuilder<InstallSnapshotRequest> {
    @NonNull
    InstallSnapshotRequestBuilder setGroupId(@NonNull Object groupId);

    @NonNull
    InstallSnapshotRequestBuilder setSender(@NonNull RaftEndpoint sender);

    @NonNull
    InstallSnapshotRequestBuilder setTerm(int term);

    @NonNull
    InstallSnapshotRequestBuilder setSenderLeader(boolean leader);

    @NonNull
    InstallSnapshotRequestBuilder setSnapshotTerm(int snapshotTerm);

    @NonNull
    InstallSnapshotRequestBuilder setSnapshotIndex(long snapshotIndex);

    @NonNull
    InstallSnapshotRequestBuilder setTotalSnapshotChunkCount(int totalSnapshotChunkCount);

    @NonNull
    InstallSnapshotRequestBuilder setSnapshotChunk(@Nullable SnapshotChunk snapshotChunk);

    @NonNull
    InstallSnapshotRequestBuilder
      setSnapshottedMembers(@NonNull Collection<RaftEndpoint> snapshottedMembers);

    @NonNull
    InstallSnapshotRequestBuilder
      setGroupMembersView(@NonNull RaftGroupMembersView groupMembersView);

    @NonNull
    InstallSnapshotRequestBuilder setQuerySequenceNumber(long querySequenceNumber);

    @NonNull
    InstallSnapshotRequestBuilder setFlowControlSequenceNumber(long flowControlSequenceNumber);

    @NonNull
    InstallSnapshotRequestBuilder setCatchUpReference(@Nullable CatchUpReference catchUpReference);
  }
}
