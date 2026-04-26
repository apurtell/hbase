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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
import org.apache.hadoop.hbase.consensus.raft.statemachine.CatchUpReference;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Bidirectional conversion between RAFT interfaces and {@code ConsensusProtos.*PB}.
 * <p>
 * The injected {@link PayloadCompressor} drives the outbound entry-payload compression path (its
 * configured algorithm is stamped onto each {@code LogEntryPB.op_payload_compression}). The inbound
 * side ignores the configured algorithm and resolves a codec on demand from the wire ordinal via
 * {@link PayloadCompressor#algorithmFromOrdinal(int)} and
 * {@link PayloadCompressor#decompress(ByteString, org.apache.hadoop.hbase.io.compress.Compression.Algorithm)},
 * so peers do not need to share a compression configuration.
 */
@InterfaceAudience.Private
final class ProtoConverter {

  private final DefaultRaftModelFactory factory;
  private final OperationCodec operationCodec;
  private final PayloadCompressor compressor;

  ProtoConverter(@NonNull DefaultRaftModelFactory factory, @NonNull OperationCodec operationCodec,
    @NonNull PayloadCompressor compressor) {
    this.factory = factory;
    this.operationCodec = operationCodec;
    this.compressor = compressor;
  }

  /**
   * Inbound proto-message field-presence helper. Every field in {@code ConsensusProtocol.proto} is
   * declared {@code optional} for forward compatibility (so individual fields can be deprecated
   * without a wire break — see the schema's preamble); the semantic invariants — "an
   * {@code AppendEntries} carries a group id", "a {@code ConsensusFrame} carries a kind", etc. —
   * are enforced here, called from each {@code from*PB} converter (and from {@link InboundHandler}
   * for the top-level frame). Failures throw {@link MalformedMessageException}; the inbound
   * dispatcher catches that and logs + drops the offending message rather than tearing down the
   * channel.
   */
  static void requireField(boolean present, String pbType, String fieldName) {
    if (!present) {
      throw new MalformedMessageException(
        pbType + " is missing required field '" + fieldName + "'");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Endpoints
  // ---------------------------------------------------------------------------------------------

  static ConsensusProtos.RaftEndpointPB toEndpointPB(RaftEndpoint endpoint) {
    return ConsensusProtos.RaftEndpointPB.newBuilder().setId(WireRaftEndpoint.toBytes(endpoint))
      .build();
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

  /**
   * Decodes the on-wire group id back to the local representation. Local Raft nodes typically use a
   * {@code String} group id (see {@code LocalRaftEndpoint} / {@code SimpleStateMachine}); the
   * registry dispatcher keys on whatever object the producer of
   * {@link org.apache.hadoop.hbase.consensus.raft.RaftNode#getGroupId()} returned, so we decode to
   * a {@code String} and rely on {@link String#equals(Object)}.
   */
  static Object bytesToGroupId(ByteString bytes) {
    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------------------------
  // Log entries
  // ---------------------------------------------------------------------------------------------

  ConsensusProtos.LogEntryPB toLogEntryPB(LogEntry entry) {
    Object op = entry.getOperation();
    int typeId = operationCodec.typeId(op);
    ByteString payload = operationCodec.encode(op);
    Compression.Algorithm alg = compressor.algorithm();
    try {
      payload = compressor.compress(payload);
    } catch (IOException e) {
      throw new UncheckedIOException(
        "Failed to compress LogEntry payload at index " + entry.getIndex(), e);
    }
    ConsensusProtos.LogEntryPB.Builder builder = ConsensusProtos.LogEntryPB.newBuilder()
      .setIndex(entry.getIndex()).setTerm(entry.getTerm()).setOpType(typeId).setOpPayload(payload);
    // Only stamp the ordinal when we actually compressed; absence means NONE on the wire.
    if (alg != Compression.Algorithm.NONE) {
      builder.setOpPayloadCompression(alg.ordinal());
    }
    return builder.build();
  }

  LogEntry fromLogEntryPB(ConsensusProtos.LogEntryPB pb) {
    requireField(pb.hasIndex(), "LogEntryPB", "index");
    requireField(pb.hasTerm(), "LogEntryPB", "term");
    requireField(pb.hasOpType(), "LogEntryPB", "op_type");
    requireField(pb.hasOpPayload(), "LogEntryPB", "op_payload");
    ByteString payload = pb.getOpPayload();
    Compression.Algorithm alg = pb.hasOpPayloadCompression()
      ? PayloadCompressor.algorithmFromOrdinal(pb.getOpPayloadCompression())
      : Compression.Algorithm.NONE;
    try {
      payload = PayloadCompressor.decompress(payload, alg);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to decompress LogEntry payload at index "
        + pb.getIndex() + " (algorithm=" + alg + ")", e);
    }
    Object op = operationCodec.decode(pb.getOpType(), payload);
    return factory.createLogEntryBuilder().setIndex(pb.getIndex()).setTerm(pb.getTerm())
      .setOperation(op).build();
  }

  // ---------------------------------------------------------------------------------------------
  // Group members view
  // ---------------------------------------------------------------------------------------------

  static ConsensusProtos.RaftGroupMembersViewPB toMembersViewPB(RaftGroupMembersView view) {
    ConsensusProtos.RaftGroupMembersViewPB.Builder builder =
      ConsensusProtos.RaftGroupMembersViewPB.newBuilder().setLogIndex(view.getLogIndex());
    for (RaftEndpoint m : view.getMembers()) {
      builder.addMembers(toEndpointPB(m));
    }
    for (RaftEndpoint m : view.getVotingMembers()) {
      builder.addVotingMembers(toEndpointPB(m));
    }
    return builder.build();
  }

  RaftGroupMembersView fromMembersViewPB(ConsensusProtos.RaftGroupMembersViewPB pb) {
    requireField(pb.hasLogIndex(), "RaftGroupMembersViewPB", "log_index");
    List<RaftEndpoint> members = new ArrayList<>(pb.getMembersCount());
    for (ConsensusProtos.RaftEndpointPB ep : pb.getMembersList()) {
      members.add(fromEndpointPB(ep));
    }
    List<RaftEndpoint> voters = new ArrayList<>(pb.getVotingMembersCount());
    for (ConsensusProtos.RaftEndpointPB ep : pb.getVotingMembersList()) {
      voters.add(fromEndpointPB(ep));
    }
    return factory.createRaftGroupMembersViewBuilder().setLogIndex(pb.getLogIndex())
      .setMembers(members).setVotingMembers(voters).build();
  }

  // ---------------------------------------------------------------------------------------------
  // Snapshot chunk
  // ---------------------------------------------------------------------------------------------

  ConsensusProtos.SnapshotChunkPB toSnapshotChunkPB(SnapshotChunk chunk) {
    Object op = chunk.getOperation();
    ByteString payload;
    if (op instanceof byte[]) {
      payload = ByteString.copyFrom((byte[]) op);
    } else {
      payload = operationCodec.encode(op);
    }
    return ConsensusProtos.SnapshotChunkPB.newBuilder().setIndex(chunk.getIndex())
      .setTerm(chunk.getTerm()).setChunkIndex(chunk.getSnapshotChunkIndex())
      .setChunkCount(chunk.getSnapshotChunkCount())
      .setMembersView(toMembersViewPB(chunk.getGroupMembersView())).setPayload(payload).build();
  }

  SnapshotChunk fromSnapshotChunkPB(ConsensusProtos.SnapshotChunkPB pb) {
    requireField(pb.hasIndex(), "SnapshotChunkPB", "index");
    requireField(pb.hasTerm(), "SnapshotChunkPB", "term");
    requireField(pb.hasChunkIndex(), "SnapshotChunkPB", "chunk_index");
    requireField(pb.hasChunkCount(), "SnapshotChunkPB", "chunk_count");
    requireField(pb.hasMembersView(), "SnapshotChunkPB", "members_view");
    requireField(pb.hasPayload(), "SnapshotChunkPB", "payload");
    return factory.createSnapshotChunkBuilder().setIndex(pb.getIndex()).setTerm(pb.getTerm())
      .setSnapshotChunkIndex(pb.getChunkIndex()).setSnapshotChunkCount(pb.getChunkCount())
      .setGroupMembersView(fromMembersViewPB(pb.getMembersView()))
      .setOperation(pb.getPayload().toByteArray()).build();
  }

  // ---------------------------------------------------------------------------------------------
  // CatchUpReference
  // ---------------------------------------------------------------------------------------------

  static ConsensusProtos.CatchUpReferencePB toCatchUpPB(CatchUpReference ref) {
    return ConsensusProtos.CatchUpReferencePB.newBuilder().setFlushOpSeqId(ref.getFlushOpSeqId())
      .setSnapshotMaxSeqId(ref.getSnapshotMaxSeqId())
      .setMetadata(ByteString.copyFrom(ref.getMetadata())).build();
  }

  static CatchUpReference fromCatchUpPB(ConsensusProtos.CatchUpReferencePB pb) {
    requireField(pb.hasFlushOpSeqId(), "CatchUpReferencePB", "flush_op_seq_id");
    requireField(pb.hasSnapshotMaxSeqId(), "CatchUpReferencePB", "snapshot_max_seq_id");
    requireField(pb.hasMetadata(), "CatchUpReferencePB", "metadata");
    return new CatchUpReference(pb.getFlushOpSeqId(), pb.getSnapshotMaxSeqId(),
      pb.getMetadata().toByteArray());
  }

  // ---------------------------------------------------------------------------------------------
  // AppendEntries
  // ---------------------------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------------------------
  // LeaderHeartbeat / LeaderHeartbeatAck — lightweight, log-free liveness signaling.
  // ---------------------------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------------------------
  // Append responses
  // ---------------------------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------------------------
  // Vote / PreVote / TriggerElection
  // ---------------------------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------------------------
  // InstallSnapshot
  // ---------------------------------------------------------------------------------------------

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
    CatchUpReference cur = req.getCatchUpReference();
    if (cur != null) {
      builder.setCatchUpReference(toCatchUpPB(cur));
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
    @Nullable
    CatchUpReference cur =
      pb.hasCatchUpReference() ? fromCatchUpPB(pb.getCatchUpReference()) : null;
    return factory.createInstallSnapshotRequestBuilder().setGroupId(bytesToGroupId(pb.getGroupId()))
      .setSender(fromEndpointPB(pb.getSender())).setTerm(pb.getTerm())
      .setSenderLeader(pb.getSenderLeader()).setSnapshotTerm(pb.getSnapshotTerm())
      .setSnapshotIndex(pb.getSnapshotIndex())
      .setTotalSnapshotChunkCount(pb.getTotalSnapshotChunkCount()).setSnapshotChunk(chunk)
      .setSnapshottedMembers(snapshotted)
      .setGroupMembersView(fromMembersViewPB(pb.getGroupMembersView()))
      .setQuerySequenceNumber(pb.getQuerySeq()).setFlowControlSequenceNumber(pb.getFlowControlSeq())
      .setCatchUpReference(cur).build();
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

  // ---------------------------------------------------------------------------------------------
  // Single-frame top-level wrap
  // ---------------------------------------------------------------------------------------------

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
