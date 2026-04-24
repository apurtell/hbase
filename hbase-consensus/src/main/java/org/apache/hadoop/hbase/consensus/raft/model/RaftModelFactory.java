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
package org.apache.hadoop.hbase.consensus.raft.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp.UpdateRaftGroupMembersOpBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry.LogEntryBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView.RaftGroupMembersViewBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk.SnapshotChunkBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry.SnapshotEntryBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse.AppendEntriesFailureResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest.AppendEntriesRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse.AppendEntriesSuccessResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest.InstallSnapshotRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse.InstallSnapshotResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest.PreVoteRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse.PreVoteResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest.TriggerLeaderElectionRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest.VoteRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse.VoteResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState.RaftEndpointPersistentStateBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState.RaftTermPersistentStateBuilder;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;

/**
 * Used for creating {@link RaftModel} objects with the builder pattern.
 * <p>
 * Users of MicroRaft can provide an implementation of this interface while creating
 * {@link RaftNode} instances. Otherwise, {@link DefaultRaftModelFactory} is used. {@link RaftModel}
 * objects created by a Raft model factory implementation are passed to {@link Transport} for
 * networking, and {@link RaftStore} for persistence.
 * <p>
 * A {@link RaftModelFactory} implementation can implement {@link RaftNodeLifecycleAware} to perform
 * initialization and clean up work during {@link RaftNode} startup and termination.
 * {@link RaftNode} calls {@link RaftNodeLifecycleAware#onRaftNodeStart()} before calling any other
 * method on {@link RaftModelFactory}, and finally calls
 * {@link RaftNodeLifecycleAware#onRaftNodeTerminate()} on termination.
 */
public interface RaftModelFactory {
  @NonNull
  LogEntryBuilder createLogEntryBuilder();

  @NonNull
  SnapshotEntryBuilder createSnapshotEntryBuilder();

  @NonNull
  SnapshotChunkBuilder createSnapshotChunkBuilder();

  @NonNull
  AppendEntriesRequestBuilder createAppendEntriesRequestBuilder();

  @NonNull
  AppendEntriesSuccessResponseBuilder createAppendEntriesSuccessResponseBuilder();

  @NonNull
  AppendEntriesFailureResponseBuilder createAppendEntriesFailureResponseBuilder();

  @NonNull
  InstallSnapshotRequestBuilder createInstallSnapshotRequestBuilder();

  @NonNull
  InstallSnapshotResponseBuilder createInstallSnapshotResponseBuilder();

  @NonNull
  PreVoteRequestBuilder createPreVoteRequestBuilder();

  @NonNull
  PreVoteResponseBuilder createPreVoteResponseBuilder();

  @NonNull
  TriggerLeaderElectionRequestBuilder createTriggerLeaderElectionRequestBuilder();

  @NonNull
  VoteRequestBuilder createVoteRequestBuilder();

  @NonNull
  VoteResponseBuilder createVoteResponseBuilder();

  @NonNull
  UpdateRaftGroupMembersOpBuilder createUpdateRaftGroupMembersOpBuilder();

  @NonNull
  RaftGroupMembersViewBuilder createRaftGroupMembersViewBuilder();

  @NonNull
  RaftEndpointPersistentStateBuilder createRaftEndpointPersistentStateBuilder();

  @NonNull
  RaftTermPersistentStateBuilder createRaftTermPersistentStateBuilder();
}
