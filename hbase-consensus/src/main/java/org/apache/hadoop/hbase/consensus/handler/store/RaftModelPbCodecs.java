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
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.hadoop.hbase.consensus.handler.transport.PayloadCompressor;
import org.apache.hadoop.hbase.consensus.handler.transport.WireRaftEndpoint;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.statemachine.CatchUpReference;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Bidirectional helpers between Raft model interfaces and {@code ConsensusProtos.*PB} for the types
 * that both the wire codec ({@code ProtoConverter}) and the persistent codec
 * ({@link DefaultLogStoreSerializer}) need to understand the same way: {@link RaftEndpoint},
 * {@link RaftGroupMembersView}, {@link LogEntry}, {@link SnapshotChunk}, and
 * {@link CatchUpReference}. The remainder of the wire-only RPC types (votes, heartbeats, append /
 * install responses, etc.) stay private to {@code handler/transport/}.
 * <p>
 * The class is held as an immutable instance because {@link LogEntry} / {@link SnapshotChunk}
 * conversions need a {@link DefaultRaftModelFactory}, an {@link OperationCodec}, and a
 * {@link PayloadCompressor} (the latter two govern outbound compression and inbound algorithm
 * resolution from {@code LogEntryPB.op_payload_compression}). Pure-PB conversions
 * ({@link RaftEndpoint}, {@link RaftGroupMembersView}, {@link CatchUpReference}) are exposed as
 * {@code static} so callers that don't need a fully-initialised converter (the wire layer's inbound
 * pre-dispatch helpers) can reuse them.
 * <p>
 * Field-presence enforcement matches {@code ProtoConverter}'s policy: every field in
 * {@code ConsensusProtocol.proto} is declared {@code optional} for forward-compat, and the semantic
 * invariants are enforced here via {@link #requireField(boolean, String, String)} so a malformed
 * inbound message is rejected before it reaches a builder.
 */
@InterfaceAudience.Private
public final class RaftModelPbCodecs {

  private final DefaultRaftModelFactory factory;
  private final OperationCodec operationCodec;
  private final PayloadCompressor compressor;

  public RaftModelPbCodecs(@NonNull DefaultRaftModelFactory factory,
    @NonNull OperationCodec operationCodec, @NonNull PayloadCompressor compressor) {
    this.factory = factory;
    this.operationCodec = operationCodec;
    this.compressor = compressor;
  }

  /**
   * Inbound proto-message field-presence helper. Every field in {@code ConsensusProtocol.proto} is
   * declared {@code optional} for forward compatibility. The semantic invariants are enforced here.
   * Failures throw {@link IllegalArgumentException}. Wire and store inbound paths translate that
   * into their own diagnostic exception types.
   */
  public static void requireField(boolean present, String pbType, String fieldName) {
    if (!present) {
      throw new IllegalArgumentException(pbType + " is missing required field '" + fieldName + "'");
    }
  }

  @NonNull
  public static ConsensusProtos.RaftEndpointPB toEndpointPB(@NonNull RaftEndpoint endpoint) {
    return ConsensusProtos.RaftEndpointPB.newBuilder().setId(WireRaftEndpoint.toBytes(endpoint))
      .build();
  }

  @NonNull
  public static RaftEndpoint fromEndpointPB(@NonNull ConsensusProtos.RaftEndpointPB pb) {
    requireField(pb.hasId(), "RaftEndpointPB", "id");
    return WireRaftEndpoint.fromBytes(pb.getId());
  }

  @NonNull
  public static ConsensusProtos.RaftGroupMembersViewPB
    toMembersViewPB(@NonNull RaftGroupMembersView view) {
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

  @NonNull
  public RaftGroupMembersView
    fromMembersViewPB(@NonNull ConsensusProtos.RaftGroupMembersViewPB pb) {
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

  @NonNull
  public ConsensusProtos.LogEntryPB toLogEntryPB(@NonNull LogEntry entry) {
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
    if (alg != Compression.Algorithm.NONE) {
      builder.setOpPayloadCompression(alg.ordinal());
    }
    return builder.build();
  }

  @NonNull
  public LogEntry fromLogEntryPB(@NonNull ConsensusProtos.LogEntryPB pb) {
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

  @NonNull
  public ConsensusProtos.SnapshotChunkPB toSnapshotChunkPB(@NonNull SnapshotChunk chunk) {
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

  @NonNull
  public SnapshotChunk fromSnapshotChunkPB(@NonNull ConsensusProtos.SnapshotChunkPB pb) {
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

  @NonNull
  public static ConsensusProtos.CatchUpReferencePB toCatchUpPB(@NonNull CatchUpReference ref) {
    return ConsensusProtos.CatchUpReferencePB.newBuilder().setFlushOpSeqId(ref.getFlushOpSeqId())
      .setSnapshotMaxSeqId(ref.getSnapshotMaxSeqId())
      .setMetadata(ByteString.copyFrom(ref.getMetadata())).build();
  }

  @NonNull
  public static CatchUpReference fromCatchUpPB(@NonNull ConsensusProtos.CatchUpReferencePB pb) {
    requireField(pb.hasFlushOpSeqId(), "CatchUpReferencePB", "flush_op_seq_id");
    requireField(pb.hasSnapshotMaxSeqId(), "CatchUpReferencePB", "snapshot_max_seq_id");
    requireField(pb.hasMetadata(), "CatchUpReferencePB", "metadata");
    return new CatchUpReference(pb.getFlushOpSeqId(), pb.getSnapshotMaxSeqId(),
      pb.getMetadata().toByteArray());
  }
}
