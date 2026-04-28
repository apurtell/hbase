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
package org.apache.hadoop.hbase.consensus.raft.model.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModel;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp.UpdateRaftGroupMembersOpBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.groupop.DefaultUpdateRaftGroupMembersOpOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultLogEntryOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultRaftGroupMembersViewOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultSnapshotChunkOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultSnapshotEntryOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultAppendEntriesFailureResponseOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultAppendEntriesRequestOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultAppendEntriesSuccessResponseOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultInstallSnapshotRequestOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultInstallSnapshotResponseOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultLeaderHeartbeatAckOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultLeaderHeartbeatOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultPreVoteRequestOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultPreVoteResponseOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultTriggerLeaderElectionRequestOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultVoteRequestOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.message.DefaultVoteResponseOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.persistence.DefaultRaftEndpointPersistentStateOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.persistence.DefaultRaftTermPersistentStateOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry.LogEntryBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView.RaftGroupMembersViewBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk.SnapshotChunkBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry.SnapshotEntryBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse.AppendEntriesFailureResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest.AppendEntriesRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse.AppendEntriesSuccessResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest.InstallSnapshotRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse.InstallSnapshotResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat.LeaderHeartbeatBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck.LeaderHeartbeatAckBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest.PreVoteRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse.PreVoteResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest.TriggerLeaderElectionRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest.VoteRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse.VoteResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState.RaftEndpointPersistentStateBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState.RaftTermPersistentStateBuilder;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * The default implementation of {@link RaftModelFactory}.
 * <p>
 * Creates POJO-style implementations of the {@link RaftModel} objects.
 */
@InterfaceAudience.Private
public final class DefaultRaftModelFactory implements RaftModelFactory {
  @NonNull
  @Override
  public LogEntryBuilder createLogEntryBuilder() {
    return new DefaultLogEntryOrBuilder();
  }

  @NonNull
  @Override
  public SnapshotEntryBuilder createSnapshotEntryBuilder() {
    return new DefaultSnapshotEntryOrBuilder();
  }

  @NonNull
  @Override
  public SnapshotChunkBuilder createSnapshotChunkBuilder() {
    return new DefaultSnapshotChunkOrBuilder();
  }

  @NonNull
  @Override
  public AppendEntriesRequestBuilder createAppendEntriesRequestBuilder() {
    return new DefaultAppendEntriesRequestOrBuilder();
  }

  @NonNull
  @Override
  public AppendEntriesSuccessResponseBuilder createAppendEntriesSuccessResponseBuilder() {
    return new DefaultAppendEntriesSuccessResponseOrBuilder();
  }

  @NonNull
  @Override
  public AppendEntriesFailureResponseBuilder createAppendEntriesFailureResponseBuilder() {
    return new DefaultAppendEntriesFailureResponseOrBuilder();
  }

  @NonNull
  @Override
  public InstallSnapshotRequestBuilder createInstallSnapshotRequestBuilder() {
    return new DefaultInstallSnapshotRequestOrBuilder();
  }

  @NonNull
  @Override
  public InstallSnapshotResponseBuilder createInstallSnapshotResponseBuilder() {
    return new DefaultInstallSnapshotResponseOrBuilder();
  }

  @NonNull
  @Override
  public LeaderHeartbeatBuilder createLeaderHeartbeatBuilder() {
    return new DefaultLeaderHeartbeatOrBuilder();
  }

  @NonNull
  @Override
  public LeaderHeartbeatAckBuilder createLeaderHeartbeatAckBuilder() {
    return new DefaultLeaderHeartbeatAckOrBuilder();
  }

  @NonNull
  @Override
  public PreVoteRequestBuilder createPreVoteRequestBuilder() {
    return new DefaultPreVoteRequestOrBuilder();
  }

  @NonNull
  @Override
  public PreVoteResponseBuilder createPreVoteResponseBuilder() {
    return new DefaultPreVoteResponseOrBuilder();
  }

  @NonNull
  @Override
  public TriggerLeaderElectionRequestBuilder createTriggerLeaderElectionRequestBuilder() {
    return new DefaultTriggerLeaderElectionRequestOrBuilder();
  }

  @NonNull
  @Override
  public VoteRequestBuilder createVoteRequestBuilder() {
    return new DefaultVoteRequestOrBuilder();
  }

  @NonNull
  @Override
  public VoteResponseBuilder createVoteResponseBuilder() {
    return new DefaultVoteResponseOrBuilder();
  }

  @NonNull
  @Override
  public UpdateRaftGroupMembersOpBuilder createUpdateRaftGroupMembersOpBuilder() {
    return new DefaultUpdateRaftGroupMembersOpOrBuilder();
  }

  @NonNull
  @Override
  public RaftGroupMembersViewBuilder createRaftGroupMembersViewBuilder() {
    return new DefaultRaftGroupMembersViewOrBuilder();
  }

  @NonNull
  @Override
  public RaftEndpointPersistentStateBuilder createRaftEndpointPersistentStateBuilder() {
    return new DefaultRaftEndpointPersistentStateOrBuilder();
  }

  @NonNull
  @Override
  public RaftTermPersistentStateBuilder createRaftTermPersistentStateBuilder() {
    return new DefaultRaftTermPersistentStateOrBuilder();
  }
}
