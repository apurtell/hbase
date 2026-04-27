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
package org.apache.hadoop.hbase.consensus.handler.transport;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.hadoop.hbase.consensus.handler.store.RaftModelPbCodecs;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Bidirectional conversion between RAFT interfaces and {@code ConsensusProtos.*PB}.
 * <p>
 * The injected {@link PayloadCompressor} drives the outbound entry-payload compression path. The
 * inbound side ignores the configured algorithm and resolves a codec on demand from the wire
 * ordinal via {@link PayloadCompressor#algorithmFromOrdinal(int)} and
 * {@link PayloadCompressor#decompress(ByteString, org.apache.hadoop.hbase.io.compress.Compression.Algorithm)}.
 */
@InterfaceAudience.Private
final class ProtoConverter {

  private final DefaultRaftModelFactory factory;
  private final RaftModelPbCodecs modelCodecs;

  ProtoConverter(@NonNull DefaultRaftModelFactory factory, @NonNull OperationCodec operationCodec,
    @NonNull PayloadCompressor compressor) {
    this.factory = factory;
    this.modelCodecs = new RaftModelPbCodecs(factory, operationCodec, compressor);
  }

  /**
   * Inbound proto-message field-presence helper. Delegates to {@link RaftModelPbCodecs} so the
   * shared persistence-model converters and the wire-only converters here apply the same policy.
   * The {@link IllegalArgumentException} the helper throws is wrapped in a
   * {@link MalformedMessageException} for the wire path so the inbound dispatcher can log + drop
   * the offending message rather than tearing down the channel.
   */
  static void requireField(boolean present, String pbType, String fieldName) {
    try {
      RaftModelPbCodecs.requireField(present, pbType, fieldName);
    } catch (IllegalArgumentException e) {
      throw new MalformedMessageException(e.getMessage());
    }
  }

  static ConsensusProtos.RaftEndpointPB toEndpointPB(RaftEndpoint endpoint) {
    return RaftModelPbCodecs.toEndpointPB(endpoint);
  }

  static RaftEndpoint fromEndpointPB(ConsensusProtos.RaftEndpointPB pb) {
    requireField(pb.hasId(), "RaftEndpointPB", "id");
    return WireRaftEndpoint.fromBytes(pb.getId());
  }

  private static void requireGroupId(boolean present, String pbType) {
    requireField(present, pbType, "group_id");
  }

  private static void requireSender(boolean present, String pbType) {
    requireField(present, pbType, "sender");
  }

  private static void requireTerm(boolean present, String pbType) {
    requireField(present, pbType, "term");
  }

  static ByteString groupIdToBytes(Object groupId) {
    if (groupId instanceof byte[]) {
      return ByteString.copyFrom((byte[]) groupId);
    }
    return ByteString.copyFromUtf8(String.valueOf(groupId));
  }

  /** Decodes the on-wire group id back to the local representation. */
  static Object bytesToGroupId(ByteString bytes) {
    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
  }

  ConsensusProtos.LogEntryPB toLogEntryPB(LogEntry entry) {
    return modelCodecs.toLogEntryPB(entry);
  }

  LogEntry fromLogEntryPB(ConsensusProtos.LogEntryPB pb) {
    try {
      return modelCodecs.fromLogEntryPB(pb);
    } catch (IllegalArgumentException e) {
      throw new MalformedMessageException(e.getMessage());
    }
  }

  static ConsensusProtos.RaftGroupMembersViewPB toMembersViewPB(RaftGroupMembersView view) {
    return RaftModelPbCodecs.toMembersViewPB(view);
  }

  RaftGroupMembersView fromMembersViewPB(ConsensusProtos.RaftGroupMembersViewPB pb) {
    try {
      return modelCodecs.fromMembersViewPB(pb);
    } catch (IllegalArgumentException e) {
      throw new MalformedMessageException(e.getMessage());
    }
  }

  ConsensusProtos.SnapshotChunkPB toSnapshotChunkPB(SnapshotChunk chunk) {
    return modelCodecs.toSnapshotChunkPB(chunk);
  }

  SnapshotChunk fromSnapshotChunkPB(ConsensusProtos.SnapshotChunkPB pb) {
    try {
      return modelCodecs.fromSnapshotChunkPB(pb);
    } catch (IllegalArgumentException e) {
      throw new MalformedMessageException(e.getMessage());
    }
  }

  ConsensusProtos.GroupAppendEntriesPB toGroupAppendPB(AppendEntriesRequest req) {
    ConsensusProtos.GroupAppendEntriesPB.Builder builder =
      ConsensusProtos.GroupAppendEntriesPB.newBuilder().setGroupId(groupIdToBytes(req.getGroupId()))
        .setSender(toEndpointPB(req.getSender())).setTerm(req.getTerm())
        .setPrevLogTerm(req.getPreviousLogTerm()).setPrevLogIndex(req.getPreviousLogIndex())
        .setCommitIndex(req.getCommitIndex()).setQuerySeq(req.getQuerySequenceNumber())
        .setFlowControlSeq(req.getFlowControlSequenceNumber());
    for (LogEntry entry : req.getLogEntries()) {
      builder.addEntries(toLogEntryPB(entry));
    }
    return builder.build();
  }

  AppendEntriesRequest fromGroupAppendPB(ConsensusProtos.GroupAppendEntriesPB pb) {
    requireGroupId(pb.hasGroupId(), "GroupAppendEntriesPB");
    requireSender(pb.hasSender(), "GroupAppendEntriesPB");
    requireTerm(pb.hasTerm(), "GroupAppendEntriesPB");
    requireField(pb.hasPrevLogTerm(), "GroupAppendEntriesPB", "prev_log_term");
    requireField(pb.hasPrevLogIndex(), "GroupAppendEntriesPB", "prev_log_index");
    requireField(pb.hasCommitIndex(), "GroupAppendEntriesPB", "commit_index");
    requireField(pb.hasQuerySeq(), "GroupAppendEntriesPB", "query_seq");
    requireField(pb.hasFlowControlSeq(), "GroupAppendEntriesPB", "flow_control_seq");
    List<LogEntry> entries = new ArrayList<>(pb.getEntriesCount());
    for (ConsensusProtos.LogEntryPB e : pb.getEntriesList()) {
      entries.add(fromLogEntryPB(e));
    }
    return factory.createAppendEntriesRequestBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm())
      .setPreviousLogTerm(pb.getPrevLogTerm()).setPreviousLogIndex(pb.getPrevLogIndex())
      .setCommitIndex(pb.getCommitIndex()).setLogEntries(entries)
      .setQuerySequenceNumber(pb.getQuerySeq()).setFlowControlSequenceNumber(pb.getFlowControlSeq())
      .build();
  }

  ConsensusProtos.GroupHeartbeatPB toGroupHeartbeatPB(LeaderHeartbeat hb) {
    return ConsensusProtos.GroupHeartbeatPB.newBuilder().setGroupId(groupIdToBytes(hb.getGroupId()))
      .setSender(toEndpointPB(hb.getSender())).setTerm(hb.getTerm())
      .setCommitIndex(hb.getCommitIndex()).build();
  }

  LeaderHeartbeat fromGroupHeartbeatPB(ConsensusProtos.GroupHeartbeatPB pb) {
    requireGroupId(pb.hasGroupId(), "GroupHeartbeatPB");
    requireSender(pb.hasSender(), "GroupHeartbeatPB");
    requireTerm(pb.hasTerm(), "GroupHeartbeatPB");
    requireField(pb.hasCommitIndex(), "GroupHeartbeatPB", "commit_index");
    return factory.createLeaderHeartbeatBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm())
      .setCommitIndex(pb.getCommitIndex()).build();
  }

  ConsensusProtos.GroupHeartbeatAckPB toGroupHeartbeatAckPB(LeaderHeartbeatAck ack) {
    return ConsensusProtos.GroupHeartbeatAckPB.newBuilder()
      .setGroupId(groupIdToBytes(ack.getGroupId())).setSender(toEndpointPB(ack.getSender()))
      .setTerm(ack.getTerm()).setLastVerifiedLogIndex(ack.getLastVerifiedLogIndex()).build();
  }

  LeaderHeartbeatAck fromGroupHeartbeatAckPB(ConsensusProtos.GroupHeartbeatAckPB pb) {
    requireGroupId(pb.hasGroupId(), "GroupHeartbeatAckPB");
    requireSender(pb.hasSender(), "GroupHeartbeatAckPB");
    requireTerm(pb.hasTerm(), "GroupHeartbeatAckPB");
    // last_verified_log_index is optional for wire-compatibility; default 0 is safe (the leader
    // will treat absent / 0 as "follower may need verification" and send a catch-up AE).
    return factory.createLeaderHeartbeatAckBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm())
      .setLastVerifiedLogIndex(pb.hasLastVerifiedLogIndex() ? pb.getLastVerifiedLogIndex() : 0L)
      .build();
  }

  ConsensusProtos.GroupAppendSuccessPB toAppendSuccessPB(AppendEntriesSuccessResponse resp) {
    return ConsensusProtos.GroupAppendSuccessPB.newBuilder()
      .setGroupId(groupIdToBytes(resp.getGroupId())).setSender(toEndpointPB(resp.getSender()))
      .setTerm(resp.getTerm()).setLastLogIndex(resp.getLastLogIndex())
      .setQuerySeq(resp.getQuerySequenceNumber())
      .setFlowControlSeq(resp.getFlowControlSequenceNumber()).build();
  }

  AppendEntriesSuccessResponse fromAppendSuccessPB(ConsensusProtos.GroupAppendSuccessPB pb) {
    requireGroupId(pb.hasGroupId(), "GroupAppendSuccessPB");
    requireSender(pb.hasSender(), "GroupAppendSuccessPB");
    requireTerm(pb.hasTerm(), "GroupAppendSuccessPB");
    requireField(pb.hasLastLogIndex(), "GroupAppendSuccessPB", "last_log_index");
    requireField(pb.hasQuerySeq(), "GroupAppendSuccessPB", "query_seq");
    requireField(pb.hasFlowControlSeq(), "GroupAppendSuccessPB", "flow_control_seq");
    return factory.createAppendEntriesSuccessResponseBuilder()
      .setGroupId(bytesToGroupId(pb.getGroupId())).setSender(fromEndpointPB(pb.getSender()))
      .setTerm(pb.getTerm()).setLastLogIndex(pb.getLastLogIndex())
      .setQuerySequenceNumber(pb.getQuerySeq()).setFlowControlSequenceNumber(pb.getFlowControlSeq())
      .build();
  }

  ConsensusProtos.GroupAppendFailurePB toAppendFailurePB(AppendEntriesFailureResponse resp) {
    return ConsensusProtos.GroupAppendFailurePB.newBuilder()
      .setGroupId(groupIdToBytes(resp.getGroupId())).setSender(toEndpointPB(resp.getSender()))
      .setTerm(resp.getTerm()).setExpectedNextIndex(resp.getExpectedNextIndex())
      .setQuerySeq(resp.getQuerySequenceNumber())
      .setFlowControlSeq(resp.getFlowControlSequenceNumber()).build();
  }

  AppendEntriesFailureResponse fromAppendFailurePB(ConsensusProtos.GroupAppendFailurePB pb) {
    requireGroupId(pb.hasGroupId(), "GroupAppendFailurePB");
    requireSender(pb.hasSender(), "GroupAppendFailurePB");
    requireTerm(pb.hasTerm(), "GroupAppendFailurePB");
    requireField(pb.hasExpectedNextIndex(), "GroupAppendFailurePB", "expected_next_index");
    requireField(pb.hasQuerySeq(), "GroupAppendFailurePB", "query_seq");
    requireField(pb.hasFlowControlSeq(), "GroupAppendFailurePB", "flow_control_seq");
    return factory.createAppendEntriesFailureResponseBuilder()
      .setGroupId(bytesToGroupId(pb.getGroupId())).setSender(fromEndpointPB(pb.getSender()))
      .setTerm(pb.getTerm()).setExpectedNextIndex(pb.getExpectedNextIndex())
      .setQuerySequenceNumber(pb.getQuerySeq()).setFlowControlSequenceNumber(pb.getFlowControlSeq())
      .build();
  }

  ConsensusProtos.VoteRequestPB toVoteRequestPB(VoteRequest req) {
    return ConsensusProtos.VoteRequestPB.newBuilder().setGroupId(groupIdToBytes(req.getGroupId()))
      .setSender(toEndpointPB(req.getSender())).setTerm(req.getTerm())
      .setLastLogTerm(req.getLastLogTerm()).setLastLogIndex(req.getLastLogIndex())
      .setSticky(req.isSticky()).build();
  }

  VoteRequest fromVoteRequestPB(ConsensusProtos.VoteRequestPB pb) {
    requireGroupId(pb.hasGroupId(), "VoteRequestPB");
    requireSender(pb.hasSender(), "VoteRequestPB");
    requireTerm(pb.hasTerm(), "VoteRequestPB");
    requireField(pb.hasLastLogTerm(), "VoteRequestPB", "last_log_term");
    requireField(pb.hasLastLogIndex(), "VoteRequestPB", "last_log_index");
    requireField(pb.hasSticky(), "VoteRequestPB", "sticky");
    return factory.createVoteRequestBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm())
      .setLastLogTerm(pb.getLastLogTerm()).setLastLogIndex(pb.getLastLogIndex())
      .setSticky(pb.getSticky()).build();
  }

  ConsensusProtos.VoteResponsePB toVoteResponsePB(VoteResponse resp) {
    return ConsensusProtos.VoteResponsePB.newBuilder().setGroupId(groupIdToBytes(resp.getGroupId()))
      .setSender(toEndpointPB(resp.getSender())).setTerm(resp.getTerm())
      .setGranted(resp.isGranted()).build();
  }

  VoteResponse fromVoteResponsePB(ConsensusProtos.VoteResponsePB pb) {
    requireGroupId(pb.hasGroupId(), "VoteResponsePB");
    requireSender(pb.hasSender(), "VoteResponsePB");
    requireTerm(pb.hasTerm(), "VoteResponsePB");
    requireField(pb.hasGranted(), "VoteResponsePB", "granted");
    return factory.createVoteResponseBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm()).setGranted(pb.getGranted())
      .build();
  }

  ConsensusProtos.PreVoteRequestPB toPreVoteRequestPB(PreVoteRequest req) {
    return ConsensusProtos.PreVoteRequestPB.newBuilder()
      .setGroupId(groupIdToBytes(req.getGroupId())).setSender(toEndpointPB(req.getSender()))
      .setTerm(req.getTerm()).setLastLogTerm(req.getLastLogTerm())
      .setLastLogIndex(req.getLastLogIndex()).build();
  }

  PreVoteRequest fromPreVoteRequestPB(ConsensusProtos.PreVoteRequestPB pb) {
    requireGroupId(pb.hasGroupId(), "PreVoteRequestPB");
    requireSender(pb.hasSender(), "PreVoteRequestPB");
    requireTerm(pb.hasTerm(), "PreVoteRequestPB");
    requireField(pb.hasLastLogTerm(), "PreVoteRequestPB", "last_log_term");
    requireField(pb.hasLastLogIndex(), "PreVoteRequestPB", "last_log_index");
    return factory.createPreVoteRequestBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm())
      .setLastLogTerm(pb.getLastLogTerm()).setLastLogIndex(pb.getLastLogIndex()).build();
  }

  ConsensusProtos.PreVoteResponsePB toPreVoteResponsePB(PreVoteResponse resp) {
    return ConsensusProtos.PreVoteResponsePB.newBuilder()
      .setGroupId(groupIdToBytes(resp.getGroupId())).setSender(toEndpointPB(resp.getSender()))
      .setTerm(resp.getTerm()).setGranted(resp.isGranted()).build();
  }

  PreVoteResponse fromPreVoteResponsePB(ConsensusProtos.PreVoteResponsePB pb) {
    requireGroupId(pb.hasGroupId(), "PreVoteResponsePB");
    requireSender(pb.hasSender(), "PreVoteResponsePB");
    requireTerm(pb.hasTerm(), "PreVoteResponsePB");
    requireField(pb.hasGranted(), "PreVoteResponsePB", "granted");
    return factory.createPreVoteResponseBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm()).setGranted(pb.getGranted())
      .build();
  }

  ConsensusProtos.TriggerLeaderElectionPB toTriggerElectionPB(TriggerLeaderElectionRequest req) {
    return ConsensusProtos.TriggerLeaderElectionPB.newBuilder()
      .setGroupId(groupIdToBytes(req.getGroupId())).setSender(toEndpointPB(req.getSender()))
      .setTerm(req.getTerm()).setLastLogTerm(req.getLastLogTerm())
      .setLastLogIndex(req.getLastLogIndex()).build();
  }

  TriggerLeaderElectionRequest fromTriggerElectionPB(ConsensusProtos.TriggerLeaderElectionPB pb) {
    requireGroupId(pb.hasGroupId(), "TriggerLeaderElectionPB");
    requireSender(pb.hasSender(), "TriggerLeaderElectionPB");
    requireTerm(pb.hasTerm(), "TriggerLeaderElectionPB");
    requireField(pb.hasLastLogTerm(), "TriggerLeaderElectionPB", "last_log_term");
    requireField(pb.hasLastLogIndex(), "TriggerLeaderElectionPB", "last_log_index");
    return factory.createTriggerLeaderElectionRequestBuilder()
      .setGroupId(bytesToGroupId(pb.getGroupId())).setSender(fromEndpointPB(pb.getSender()))
      .setTerm(pb.getTerm()).setLastLogTerm(pb.getLastLogTerm())
      .setLastLogIndex(pb.getLastLogIndex()).build();
  }

  ConsensusProtos.InstallSnapshotRequestPB toInstallSnapshotPB(InstallSnapshotRequest req) {
    ConsensusProtos.InstallSnapshotRequestPB.Builder builder =
      ConsensusProtos.InstallSnapshotRequestPB.newBuilder()
        .setGroupId(groupIdToBytes(req.getGroupId())).setSender(toEndpointPB(req.getSender()))
        .setTerm(req.getTerm()).setSenderLeader(req.isSenderLeader())
        .setSnapshotTerm(req.getSnapshotTerm()).setSnapshotIndex(req.getSnapshotIndex())
        .setTotalSnapshotChunkCount(req.getTotalSnapshotChunkCount())
        .setGroupMembersView(toMembersViewPB(req.getGroupMembersView()))
        .setQuerySeq(req.getQuerySequenceNumber())
        .setFlowControlSeq(req.getFlowControlSequenceNumber());
    SnapshotChunk chunk = req.getSnapshotChunk();
    if (chunk != null) {
      builder.setSnapshotChunk(toSnapshotChunkPB(chunk));
    }
    for (RaftEndpoint m : req.getSnapshottedMembers()) {
      builder.addSnapshottedMembers(toEndpointPB(m));
    }
    return builder.build();
  }

  InstallSnapshotRequest fromInstallSnapshotPB(ConsensusProtos.InstallSnapshotRequestPB pb) {
    requireGroupId(pb.hasGroupId(), "InstallSnapshotRequestPB");
    requireSender(pb.hasSender(), "InstallSnapshotRequestPB");
    requireTerm(pb.hasTerm(), "InstallSnapshotRequestPB");
    requireField(pb.hasSenderLeader(), "InstallSnapshotRequestPB", "sender_leader");
    requireField(pb.hasSnapshotTerm(), "InstallSnapshotRequestPB", "snapshot_term");
    requireField(pb.hasSnapshotIndex(), "InstallSnapshotRequestPB", "snapshot_index");
    requireField(pb.hasTotalSnapshotChunkCount(), "InstallSnapshotRequestPB",
      "total_snapshot_chunk_count");
    requireField(pb.hasGroupMembersView(), "InstallSnapshotRequestPB", "group_members_view");
    requireField(pb.hasQuerySeq(), "InstallSnapshotRequestPB", "query_seq");
    requireField(pb.hasFlowControlSeq(), "InstallSnapshotRequestPB", "flow_control_seq");
    Collection<RaftEndpoint> snapshotted = new ArrayList<>(pb.getSnapshottedMembersCount());
    for (ConsensusProtos.RaftEndpointPB ep : pb.getSnapshottedMembersList()) {
      snapshotted.add(fromEndpointPB(ep));
    }
    @Nullable
    SnapshotChunk chunk = pb.hasSnapshotChunk() ? fromSnapshotChunkPB(pb.getSnapshotChunk()) : null;
    return factory.createInstallSnapshotRequestBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm())
      .setSenderLeader(pb.getSenderLeader()).setSnapshotTerm(pb.getSnapshotTerm())
      .setSnapshotIndex(pb.getSnapshotIndex())
      .setTotalSnapshotChunkCount(pb.getTotalSnapshotChunkCount()).setSnapshotChunk(chunk)
      .setSnapshottedMembers(snapshotted)
      .setGroupMembersView(fromMembersViewPB(pb.getGroupMembersView()))
      .setQuerySequenceNumber(pb.getQuerySeq()).setFlowControlSequenceNumber(pb.getFlowControlSeq())
      .build();
  }

  ConsensusProtos.InstallSnapshotResponsePB
    toInstallSnapshotResponsePB(InstallSnapshotResponse resp) {
    return ConsensusProtos.InstallSnapshotResponsePB.newBuilder()
      .setGroupId(groupIdToBytes(resp.getGroupId())).setSender(toEndpointPB(resp.getSender()))
      .setTerm(resp.getTerm()).setSnapshotIndex(resp.getSnapshotIndex())
      .setRequestedSnapshotChunkIndex(resp.getRequestedSnapshotChunkIndex())
      .setQuerySeq(resp.getQuerySequenceNumber())
      .setFlowControlSeq(resp.getFlowControlSequenceNumber()).build();
  }

  InstallSnapshotResponse
    fromInstallSnapshotResponsePB(ConsensusProtos.InstallSnapshotResponsePB pb) {
    requireGroupId(pb.hasGroupId(), "InstallSnapshotResponsePB");
    requireSender(pb.hasSender(), "InstallSnapshotResponsePB");
    requireTerm(pb.hasTerm(), "InstallSnapshotResponsePB");
    requireField(pb.hasSnapshotIndex(), "InstallSnapshotResponsePB", "snapshot_index");
    requireField(pb.hasRequestedSnapshotChunkIndex(), "InstallSnapshotResponsePB",
      "requested_snapshot_chunk_index");
    requireField(pb.hasQuerySeq(), "InstallSnapshotResponsePB", "query_seq");
    requireField(pb.hasFlowControlSeq(), "InstallSnapshotResponsePB", "flow_control_seq");
    return factory.createInstallSnapshotResponseBuilder()
      .setGroupId(bytesToGroupId(pb.getGroupId())).setSender(fromEndpointPB(pb.getSender()))
      .setTerm(pb.getTerm()).setSnapshotIndex(pb.getSnapshotIndex())
      .setRequestedSnapshotChunkIndex(pb.getRequestedSnapshotChunkIndex())
      .setQuerySequenceNumber(pb.getQuerySeq()).setFlowControlSequenceNumber(pb.getFlowControlSeq())
      .build();
  }

  ConsensusProtos.ConsensusFrame toFrame(@NonNull RaftMessage message) {
    ConsensusProtos.ConsensusFrame.Builder builder = ConsensusProtos.ConsensusFrame.newBuilder();
    if (message instanceof AppendEntriesSuccessResponse) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.APPEND_SUCCESS)
        .setAppendSuccess(toAppendSuccessPB((AppendEntriesSuccessResponse) message)).build();
    }
    if (message instanceof AppendEntriesFailureResponse) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.APPEND_FAILURE)
        .setAppendFailure(toAppendFailurePB((AppendEntriesFailureResponse) message)).build();
    }
    if (message instanceof InstallSnapshotRequest) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.INSTALL_SNAPSHOT)
        .setInstallRequest(toInstallSnapshotPB((InstallSnapshotRequest) message)).build();
    }
    if (message instanceof InstallSnapshotResponse) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.INSTALL_SNAPSHOT_RESP)
        .setInstallResponse(toInstallSnapshotResponsePB((InstallSnapshotResponse) message)).build();
    }
    if (message instanceof VoteRequest) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.VOTE_REQUEST)
        .setVoteRequest(toVoteRequestPB((VoteRequest) message)).build();
    }
    if (message instanceof VoteResponse) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.VOTE_RESPONSE)
        .setVoteResponse(toVoteResponsePB((VoteResponse) message)).build();
    }
    if (message instanceof PreVoteRequest) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.PRE_VOTE_REQUEST)
        .setPreVoteRequest(toPreVoteRequestPB((PreVoteRequest) message)).build();
    }
    if (message instanceof PreVoteResponse) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.PRE_VOTE_RESPONSE)
        .setPreVoteResponse(toPreVoteResponsePB((PreVoteResponse) message)).build();
    }
    if (message instanceof TriggerLeaderElectionRequest) {
      return builder.setKind(ConsensusProtos.ConsensusFrame.Kind.TRIGGER_LEADER_ELECTION)
        .setTriggerElection(toTriggerElectionPB((TriggerLeaderElectionRequest) message)).build();
    }
    throw new IllegalArgumentException(
      "Unsupported single-message RaftMessage type for direct framing: " + message.getClass());
  }
}
