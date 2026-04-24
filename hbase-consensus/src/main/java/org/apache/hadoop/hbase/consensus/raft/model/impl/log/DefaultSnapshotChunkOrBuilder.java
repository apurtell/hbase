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
package org.apache.hadoop.hbase.consensus.raft.model.impl.log;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk.SnapshotChunkBuilder;

/**
 * The default impl of the {@link SnapshotChunk} and {@link SnapshotChunkBuilder} interfaces. When
 * an instance of this class is created, it is in the builder mode and its state is populated. Once
 * all fields are set, the object switches to the DTO mode where it no longer allows mutations.
 * <p>
 * Please note that {@link #build()} does not verify if all fields are set or not. It is up to the
 * user to populate the DTO state via the builder.
 */
public class DefaultSnapshotChunkOrBuilder implements SnapshotChunk, SnapshotChunkBuilder {
  private int term;
  private long index;
  private Object operation;
  private int snapshotChunkIndex;
  private int snapshotChunkCount;
  private RaftGroupMembersView groupMembersView;
  private DefaultSnapshotChunkOrBuilder builder = this;

  @Override
  public long getIndex() {
    return index;
  }

  @Override
  public int getTerm() {
    return term;
  }

  @NonNull
  @Override
  public Object getOperation() {
    return operation;
  }

  @Override
  public int getSnapshotChunkIndex() {
    return snapshotChunkIndex;
  }

  @Override
  public int getSnapshotChunkCount() {
    return snapshotChunkCount;
  }

  @NonNull
  @Override
  public RaftGroupMembersView getGroupMembersView() {
    return groupMembersView;
  }

  @NonNull
  @Override
  public SnapshotChunkBuilder setIndex(long index) {
    builder.index = index;
    return this;
  }

  @NonNull
  @Override
  public SnapshotChunkBuilder setTerm(int term) {
    builder.term = term;
    return this;
  }

  @NonNull
  @Override
  public SnapshotChunkBuilder setOperation(@NonNull Object operation) {
    builder.operation = operation;
    return this;
  }

  @NonNull
  @Override
  public SnapshotChunkBuilder setSnapshotChunkIndex(int snapshotChunkIndex) {
    builder.snapshotChunkIndex = snapshotChunkIndex;
    return this;
  }

  @NonNull
  @Override
  public SnapshotChunkBuilder setSnapshotChunkCount(int snapshotChunkCount) {
    builder.snapshotChunkCount = snapshotChunkCount;
    return this;
  }

  @NonNull
  @Override
  public SnapshotChunkBuilder setGroupMembersView(@NonNull RaftGroupMembersView groupMembersView) {
    builder.groupMembersView = groupMembersView;
    return this;
  }

  @NonNull
  @Override
  public SnapshotChunk build() {
    requireNonNull(builder);
    builder = null;
    return this;
  }

  @Override
  public String toString() {
    String header = builder != null ? "SnapshotChunkBuilder" : "SnapshotChunk";
    return header + "{" + "term=" + term + ", index=" + index + ", operation=" + operation
      + ", chunkIndex=" + snapshotChunkIndex + ", chunkCount=" + snapshotChunkCount
      + ", groupMembers=" + groupMembersView + '}';
  }
}
