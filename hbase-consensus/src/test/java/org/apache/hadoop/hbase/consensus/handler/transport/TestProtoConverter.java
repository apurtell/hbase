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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.GroupId;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp;
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
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Round-trips every {@link org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage}
 * variant and the auxiliary log model types through {@link ProtoConverter}, asserting the
 * meaningful state survives the trip.
 */
@Tag(SmallTests.TAG)
public class TestProtoConverter extends TestBase {

  private static final String GID = "groupG";

  private final RaftEndpoint a = LocalRaftEndpoint.newEndpoint();
  private final RaftEndpoint b = LocalRaftEndpoint.newEndpoint();
  private final RaftEndpoint c = LocalRaftEndpoint.newEndpoint();
  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
  private final OperationCodec codec = OperationCodecs.defaultCodecs();
  private final PayloadCompressor noCompress = new PayloadCompressor(Compression.Algorithm.NONE);
  private final ProtoConverter converter = new ProtoConverter(factory, codec, noCompress);

  @Test
  public void testEndpointRoundTrip() {
    ConsensusProtos.RaftEndpointPB pb = ProtoConverter.toEndpointPB(a);
    RaftEndpoint back = ProtoConverter.fromEndpointPB(pb);
    assertThat(String.valueOf(back.getId())).isEqualTo(String.valueOf(a.getId()));
  }

  @Test
  public void testGroupIdRoundTrip() {
    ByteString wire = ProtoConverter.groupIdToBytes(GID);
    GroupId back = GroupId.fromWire(wire);
    assertThat(back.toString()).isEqualTo(GID);
  }

  @Test
  public void testGroupIdFromBytes() {
    ByteString wire = ProtoConverter.groupIdToBytes("rawString".getBytes());
    GroupId back = GroupId.fromWire(wire);
    assertThat(back.toString()).isEqualTo("rawString");
  }

  @Test
  public void testLogEntryIdentityRoundTrip() {
    byte[] payload = { 1, 2, 3, 4, 5 };
    LogEntry orig =
      factory.createLogEntryBuilder().setIndex(7L).setTerm(3).setOperation(payload).build();
    ConsensusProtos.LogEntryPB pb = converter.toLogEntryPB(orig);
    // NONE compressor must not stamp the wire field; absence === uncompressed.
    assertThat(pb.hasOpPayloadCompression()).isFalse();
    LogEntry back = converter.fromLogEntryPB(pb);
    assertThat(back.getIndex()).isEqualTo(7L);
    assertThat(back.getTerm()).isEqualTo(3);
    assertThat(back.getOperation()).isInstanceOf(byte[].class);
    assertThat((byte[]) back.getOperation()).containsExactly(payload);
  }

  @Test
  public void testLogEntryCompressionTravelsOnTheWire() {
    Configuration conf = HBaseConfiguration.create();
    conf.set("hbase.io.compress.lz4.codec",
      "org.apache.hadoop.hbase.io.compress.aircompressor.Lz4Codec");
    Compression.Algorithm.LZ4.reload(conf);

    PayloadCompressor lz4 = new PayloadCompressor(Compression.Algorithm.LZ4);
    ProtoConverter encoder = new ProtoConverter(factory, codec, lz4);
    ProtoConverter decoder = new ProtoConverter(factory, codec, noCompress);

    byte[] payload = new byte[8192];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i & 0xff);
    }
    LogEntry orig =
      factory.createLogEntryBuilder().setIndex(99L).setTerm(4).setOperation(payload).build();

    ConsensusProtos.LogEntryPB pb = encoder.toLogEntryPB(orig);
    assertThat(pb.hasOpPayloadCompression()).isTrue();
    assertThat(pb.getOpPayloadCompression()).isEqualTo(Compression.Algorithm.LZ4.ordinal());

    LogEntry back = decoder.fromLogEntryPB(pb);
    assertThat(back.getIndex()).isEqualTo(99L);
    assertThat(back.getTerm()).isEqualTo(4);
    assertThat(back.getOperation()).isInstanceOf(byte[].class);
    assertThat((byte[]) back.getOperation()).containsExactly(payload);
  }

  @Test
  public void testAlgorithmFromOrdinalRejectsUnknown() {
    assertThatThrownBy(() -> PayloadCompressor.algorithmFromOrdinal(-1))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PayloadCompressor.algorithmFromOrdinal(Integer.MAX_VALUE))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testLogEntryMembershipRoundTrip() {
    UpdateRaftGroupMembersOp op = factory.createUpdateRaftGroupMembersOpBuilder()
      .setMembers(Arrays.asList(a, b, c)).setVotingMembers(Arrays.asList(a, b)).setEndpoint(c)
      .setMode(MembershipChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER).build();
    LogEntry orig =
      factory.createLogEntryBuilder().setIndex(42L).setTerm(11).setOperation(op).build();
    LogEntry back = converter.fromLogEntryPB(converter.toLogEntryPB(orig));
    assertThat(back.getIndex()).isEqualTo(42L);
    assertThat(back.getTerm()).isEqualTo(11);
    assertThat(back.getOperation()).isInstanceOf(UpdateRaftGroupMembersOp.class);
    UpdateRaftGroupMembersOp opBack = (UpdateRaftGroupMembersOp) back.getOperation();
    assertThat(idsOf(opBack.getMembers()))
      .containsExactlyInAnyOrderElementsOf(idsOf(op.getMembers()));
    assertThat(idsOf(opBack.getVotingMembers()))
      .containsExactlyInAnyOrderElementsOf(idsOf(op.getVotingMembers()));
    assertThat(String.valueOf(opBack.getEndpoint().getId()))
      .isEqualTo(String.valueOf(op.getEndpoint().getId()));
    assertThat(opBack.getMode()).isEqualTo(op.getMode());
  }

  @Test
  public void testMembersViewRoundTrip() {
    RaftGroupMembersView view = factory.createRaftGroupMembersViewBuilder().setLogIndex(99L)
      .setMembers(Arrays.asList(a, b, c)).setVotingMembers(Arrays.asList(a, b)).build();
    RaftGroupMembersView back = converter.fromMembersViewPB(ProtoConverter.toMembersViewPB(view));
    assertThat(back.getLogIndex()).isEqualTo(99L);
    assertThat(idsOf(back.getMembers()))
      .containsExactlyInAnyOrderElementsOf(idsOf(view.getMembers()));
    assertThat(idsOf(back.getVotingMembers()))
      .containsExactlyInAnyOrderElementsOf(idsOf(view.getVotingMembers()));
  }

  @Test
  public void testSnapshotChunkRoundTrip() {
    RaftGroupMembersView view = factory.createRaftGroupMembersViewBuilder().setLogIndex(1L)
      .setMembers(Arrays.asList(a, b)).setVotingMembers(Arrays.asList(a, b)).build();
    byte[] raw = "snapshot-bytes".getBytes();
    SnapshotChunk chunk =
      factory.createSnapshotChunkBuilder().setIndex(5L).setTerm(2).setSnapshotChunkIndex(0)
        .setSnapshotChunkCount(1).setGroupMembersView(view).setOperation(raw).build();
    SnapshotChunk back = converter.fromSnapshotChunkPB(converter.toSnapshotChunkPB(chunk));
    assertThat(back.getIndex()).isEqualTo(5L);
    assertThat(back.getTerm()).isEqualTo(2);
    assertThat(back.getSnapshotChunkIndex()).isEqualTo(0);
    assertThat(back.getSnapshotChunkCount()).isEqualTo(1);
    assertThat((byte[]) back.getOperation()).containsExactly(raw);
  }

  @Test
  public void testAppendEntriesRequestRoundTrip() {
    LogEntry e1 = factory.createLogEntryBuilder().setIndex(10L).setTerm(2)
      .setOperation(new byte[] { 1, 2 }).build();
    LogEntry e2 = factory.createLogEntryBuilder().setIndex(11L).setTerm(2)
      .setOperation(new byte[] { 3, 4, 5 }).build();
    AppendEntriesRequest req = factory.createAppendEntriesRequestBuilder().setGroupId(GID)
      .setSender(a).setTerm(2).setPreviousLogTerm(1).setPreviousLogIndex(9L).setCommitIndex(8L)
      .setLogEntries(Arrays.asList(e1, e2)).setQuerySequenceNumber(100L)
      .setFlowControlSequenceNumber(50L).build();
    AppendEntriesRequest back =
      converter.fromGroupAppendPB(converter.toGroupAppendPB(req), GroupId.of(GID));
    assertThat(back.getGroupId().toString()).isEqualTo(GID);
    assertThat(back.getTerm()).isEqualTo(2);
    assertThat(back.getPreviousLogTerm()).isEqualTo(1);
    assertThat(back.getPreviousLogIndex()).isEqualTo(9L);
    assertThat(back.getCommitIndex()).isEqualTo(8L);
    assertThat(back.getLogEntries()).hasSize(2);
    assertThat(back.getLogEntries().get(0).getIndex()).isEqualTo(10L);
    assertThat(back.getLogEntries().get(1).getIndex()).isEqualTo(11L);
    assertThat(back.getQuerySequenceNumber()).isEqualTo(100L);
    assertThat(back.getFlowControlSequenceNumber()).isEqualTo(50L);
    assertThat(String.valueOf(back.getSender().getId())).isEqualTo(String.valueOf(a.getId()));
  }

  @Test
  public void testBulkHeartbeatRoundTrip() {
    LeaderHeartbeat hb1 = factory.createLeaderHeartbeatBuilder().setGroupId(GID).setSender(a)
      .setTerm(7).setCommitIndex(99L).setQuiesced(false).build();
    LeaderHeartbeat hb2 = factory.createLeaderHeartbeatBuilder().setGroupId("groupH").setSender(a)
      .setTerm(8).setCommitIndex(100L).setQuiesced(true).build();
    ConsensusProtos.GroupBulkHeartbeatPB e1 = converter.toGroupBulkHeartbeatPB(hb1);
    ConsensusProtos.GroupBulkHeartbeatPB e2 = converter.toGroupBulkHeartbeatPB(hb2);
    ConsensusProtos.BulkHeartbeatPB pb =
      ProtoConverter.buildBulkHeartbeatPB(a, /* epoch */ 12345L, /* tick */ 42L, List.of(e1, e2));
    assertThat(pb.hasSender()).isTrue();
    assertThat(pb.getEpoch()).isEqualTo(12345L);
    assertThat(pb.getTick()).isEqualTo(42L);
    assertThat(pb.getGroupsCount()).isEqualTo(2);
    LeaderHeartbeat back1 = converter.fromGroupBulkHeartbeatPB(pb.getGroups(0), GroupId.of(GID), a);
    assertThat(back1.getTerm()).isEqualTo(7);
    assertThat(back1.getCommitIndex()).isEqualTo(99L);
    assertThat(back1.isQuiesced()).isFalse();
    assertThat(String.valueOf(back1.getSender().getId())).isEqualTo(String.valueOf(a.getId()));
    LeaderHeartbeat back2 =
      converter.fromGroupBulkHeartbeatPB(pb.getGroups(1), GroupId.of("groupH"), a);
    assertThat(back2.getTerm()).isEqualTo(8);
    assertThat(back2.getCommitIndex()).isEqualTo(100L);
    assertThat(back2.isQuiesced()).isTrue();
  }

  @Test
  public void testBulkHeartbeatAckRoundTrip() {
    LeaderHeartbeatAck ack1 = factory.createLeaderHeartbeatAckBuilder().setGroupId(GID).setSender(a)
      .setTerm(7).setLastVerifiedLogIndex(20L).build();
    LeaderHeartbeatAck ack2 = factory.createLeaderHeartbeatAckBuilder().setGroupId("groupH")
      .setSender(a).setTerm(8).setLastVerifiedLogIndex(30L).build();
    ConsensusProtos.GroupBulkHeartbeatAckPB e1 = converter.toGroupBulkHeartbeatAckPB(ack1);
    ConsensusProtos.GroupBulkHeartbeatAckPB e2 = converter.toGroupBulkHeartbeatAckPB(ack2);
    ConsensusProtos.BulkHeartbeatAckPB pb =
      ProtoConverter.buildBulkHeartbeatAckPB(a, /* epoch */ 6789L, /* tick */ 11L, List.of(e1, e2));
    assertThat(pb.hasSender()).isTrue();
    assertThat(pb.getEpoch()).isEqualTo(6789L);
    assertThat(pb.getTick()).isEqualTo(11L);
    assertThat(pb.getGroupsCount()).isEqualTo(2);
    LeaderHeartbeatAck back1 =
      converter.fromGroupBulkHeartbeatAckPB(pb.getGroups(0), GroupId.of(GID), a);
    assertThat(back1.getTerm()).isEqualTo(7);
    assertThat(back1.getLastVerifiedLogIndex()).isEqualTo(20L);
    assertThat(String.valueOf(back1.getSender().getId())).isEqualTo(String.valueOf(a.getId()));
    LeaderHeartbeatAck back2 =
      converter.fromGroupBulkHeartbeatAckPB(pb.getGroups(1), GroupId.of("groupH"), a);
    assertThat(back2.getTerm()).isEqualTo(8);
    assertThat(back2.getLastVerifiedLogIndex()).isEqualTo(30L);
  }

  @Test
  public void testAppendSuccessRoundTrip() {
    AppendEntriesSuccessResponse resp =
      factory.createAppendEntriesSuccessResponseBuilder().setGroupId(GID).setSender(a).setTerm(3)
        .setLastLogIndex(42L).setQuerySequenceNumber(11L).setFlowControlSequenceNumber(22L).build();
    AppendEntriesSuccessResponse back =
      converter.fromAppendSuccessPB(converter.toAppendSuccessPB(resp), GroupId.of(GID));
    assertThat(back.getTerm()).isEqualTo(3);
    assertThat(back.getLastLogIndex()).isEqualTo(42L);
    assertThat(back.getQuerySequenceNumber()).isEqualTo(11L);
    assertThat(back.getFlowControlSequenceNumber()).isEqualTo(22L);
  }

  @Test
  public void testAppendFailureRoundTrip() {
    AppendEntriesFailureResponse resp = factory.createAppendEntriesFailureResponseBuilder()
      .setGroupId(GID).setSender(a).setTerm(3).setExpectedNextIndex(13L).setQuerySequenceNumber(8L)
      .setFlowControlSequenceNumber(7L).build();
    AppendEntriesFailureResponse back =
      converter.fromAppendFailurePB(converter.toAppendFailurePB(resp), GroupId.of(GID));
    assertThat(back.getTerm()).isEqualTo(3);
    assertThat(back.getExpectedNextIndex()).isEqualTo(13L);
  }

  @Test
  public void testVoteRequestRoundTrip() {
    VoteRequest req = factory.createVoteRequestBuilder().setGroupId(GID).setSender(a).setTerm(4)
      .setLastLogTerm(3).setLastLogIndex(99L).setSticky(true).build();
    VoteRequest back = converter.fromVoteRequestPB(converter.toVoteRequestPB(req), GroupId.of(GID));
    assertThat(back.getTerm()).isEqualTo(4);
    assertThat(back.getLastLogTerm()).isEqualTo(3);
    assertThat(back.getLastLogIndex()).isEqualTo(99L);
    assertThat(back.isSticky()).isTrue();
  }

  @Test
  public void testVoteResponseRoundTrip() {
    VoteResponse resp = factory.createVoteResponseBuilder().setGroupId(GID).setSender(a).setTerm(4)
      .setGranted(true).build();
    VoteResponse back =
      converter.fromVoteResponsePB(converter.toVoteResponsePB(resp), GroupId.of(GID));
    assertThat(back.isGranted()).isTrue();
  }

  @Test
  public void testPreVoteRequestRoundTrip() {
    PreVoteRequest req = factory.createPreVoteRequestBuilder().setGroupId(GID).setSender(a)
      .setTerm(7).setLastLogTerm(6).setLastLogIndex(50L).build();
    PreVoteRequest back =
      converter.fromPreVoteRequestPB(converter.toPreVoteRequestPB(req), GroupId.of(GID));
    assertThat(back.getTerm()).isEqualTo(7);
    assertThat(back.getLastLogIndex()).isEqualTo(50L);
  }

  @Test
  public void testPreVoteResponseRoundTrip() {
    PreVoteResponse resp = factory.createPreVoteResponseBuilder().setGroupId(GID).setSender(a)
      .setTerm(7).setGranted(false).build();
    PreVoteResponse back =
      converter.fromPreVoteResponsePB(converter.toPreVoteResponsePB(resp), GroupId.of(GID));
    assertThat(back.isGranted()).isFalse();
  }

  @Test
  public void testTriggerLeaderElectionRoundTrip() {
    TriggerLeaderElectionRequest req = factory.createTriggerLeaderElectionRequestBuilder()
      .setGroupId(GID).setSender(a).setTerm(2).setLastLogTerm(1).setLastLogIndex(20L).build();
    TriggerLeaderElectionRequest back =
      converter.fromTriggerElectionPB(converter.toTriggerElectionPB(req), GroupId.of(GID));
    assertThat(back.getTerm()).isEqualTo(2);
    assertThat(back.getLastLogIndex()).isEqualTo(20L);
  }

  @Test
  public void testInstallSnapshotWithChunk() {
    RaftGroupMembersView view = factory.createRaftGroupMembersViewBuilder().setLogIndex(1L)
      .setMembers(Arrays.asList(a, b)).setVotingMembers(Arrays.asList(a, b)).build();
    SnapshotChunk chunk = factory.createSnapshotChunkBuilder().setIndex(5L).setTerm(2)
      .setSnapshotChunkIndex(0).setSnapshotChunkCount(1).setGroupMembersView(view)
      .setOperation(new byte[] { 9, 9, 9 }).build();
    InstallSnapshotRequest req = factory.createInstallSnapshotRequestBuilder().setGroupId(GID)
      .setSender(a).setTerm(3).setSenderLeader(true).setSnapshotTerm(2).setSnapshotIndex(5L)
      .setTotalSnapshotChunkCount(1).setSnapshotChunk(chunk)
      .setSnapshottedMembers(Arrays.asList(a, b)).setGroupMembersView(view)
      .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    InstallSnapshotRequest back =
      converter.fromInstallSnapshotPB(converter.toInstallSnapshotPB(req), GroupId.of(GID));
    assertThat(back.getTerm()).isEqualTo(3);
    assertThat(back.isSenderLeader()).isTrue();
    assertThat(back.getSnapshotTerm()).isEqualTo(2);
    assertThat(back.getSnapshotIndex()).isEqualTo(5L);
    assertThat(back.getTotalSnapshotChunkCount()).isEqualTo(1);
    assertThat(back.getSnapshotChunk()).isNotNull();
    assertThat(back.getSnapshotChunk().getIndex()).isEqualTo(5L);
    assertThat(idsOf(back.getSnapshottedMembers()))
      .containsExactlyInAnyOrderElementsOf(idsOf(req.getSnapshottedMembers()));
  }

  @Test
  public void testInstallSnapshotOmitsOptional() {
    RaftGroupMembersView view = factory.createRaftGroupMembersViewBuilder().setLogIndex(1L)
      .setMembers(Arrays.asList(a, b)).setVotingMembers(Arrays.asList(a, b)).build();
    InstallSnapshotRequest req = factory.createInstallSnapshotRequestBuilder().setGroupId(GID)
      .setSender(a).setTerm(3).setSenderLeader(false).setSnapshotTerm(2).setSnapshotIndex(5L)
      .setTotalSnapshotChunkCount(0).setSnapshotChunk(null).setSnapshottedMembers(new ArrayList<>())
      .setGroupMembersView(view).setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L)
      .build();
    InstallSnapshotRequest back =
      converter.fromInstallSnapshotPB(converter.toInstallSnapshotPB(req), GroupId.of(GID));
    assertThat(back.getSnapshotChunk()).isNull();
    assertThat(back.getSnapshottedMembers()).isEmpty();
  }

  @Test
  public void testInstallSnapshotResponseRoundTrip() {
    InstallSnapshotResponse resp = factory.createInstallSnapshotResponseBuilder().setGroupId(GID)
      .setSender(a).setTerm(3).setSnapshotIndex(5L).setRequestedSnapshotChunkIndex(2)
      .setQuerySequenceNumber(1L).setFlowControlSequenceNumber(2L).build();
    InstallSnapshotResponse back = converter
      .fromInstallSnapshotResponsePB(converter.toInstallSnapshotResponsePB(resp), GroupId.of(GID));
    assertThat(back.getTerm()).isEqualTo(3);
    assertThat(back.getSnapshotIndex()).isEqualTo(5L);
    assertThat(back.getRequestedSnapshotChunkIndex()).isEqualTo(2);
  }

  @Test
  public void testToFrameKinds() {
    VoteRequest vr = factory.createVoteRequestBuilder().setGroupId(GID).setSender(a).setTerm(1)
      .setLastLogTerm(0).setLastLogIndex(0L).setSticky(false).build();
    assertThat(converter.toFrame(vr).getKind())
      .isEqualTo(ConsensusProtos.ConsensusFrame.Kind.VOTE_REQUEST);

    VoteResponse vresp = factory.createVoteResponseBuilder().setGroupId(GID).setSender(a).setTerm(1)
      .setGranted(true).build();
    assertThat(converter.toFrame(vresp).getKind())
      .isEqualTo(ConsensusProtos.ConsensusFrame.Kind.VOTE_RESPONSE);

    PreVoteRequest pvr = factory.createPreVoteRequestBuilder().setGroupId(GID).setSender(a)
      .setTerm(1).setLastLogTerm(0).setLastLogIndex(0L).build();
    assertThat(converter.toFrame(pvr).getKind())
      .isEqualTo(ConsensusProtos.ConsensusFrame.Kind.PRE_VOTE_REQUEST);

    AppendEntriesSuccessResponse ok =
      factory.createAppendEntriesSuccessResponseBuilder().setGroupId(GID).setSender(a).setTerm(1)
        .setLastLogIndex(0L).setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    assertThat(converter.toFrame(ok).getKind())
      .isEqualTo(ConsensusProtos.ConsensusFrame.Kind.APPEND_SUCCESS);

    AppendEntriesFailureResponse bad = factory.createAppendEntriesFailureResponseBuilder()
      .setGroupId(GID).setSender(a).setTerm(1).setExpectedNextIndex(0L).setQuerySequenceNumber(0L)
      .setFlowControlSequenceNumber(0L).build();
    assertThat(converter.toFrame(bad).getKind())
      .isEqualTo(ConsensusProtos.ConsensusFrame.Kind.APPEND_FAILURE);
  }

  @Test
  public void testFromEndpointPBRejectsMissingId() {
    ConsensusProtos.RaftEndpointPB empty = ConsensusProtos.RaftEndpointPB.newBuilder().build();
    assertThatThrownBy(() -> ProtoConverter.fromEndpointPB(empty))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("RaftEndpointPB")
      .hasMessageContaining("'id'");
  }

  @Test
  public void testFromLogEntryPBRejectsMissingFields() {
    // index missing
    assertThatThrownBy(() -> converter.fromLogEntryPB(ConsensusProtos.LogEntryPB.newBuilder()
      .setTerm(1).setOpType(0).setOpPayload(ByteString.EMPTY).build()))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("LogEntryPB")
      .hasMessageContaining("'index'");
    // term missing
    assertThatThrownBy(() -> converter.fromLogEntryPB(ConsensusProtos.LogEntryPB.newBuilder()
      .setIndex(1L).setOpType(0).setOpPayload(ByteString.EMPTY).build()))
      .hasMessageContaining("'term'");
    // op_type missing
    assertThatThrownBy(() -> converter.fromLogEntryPB(ConsensusProtos.LogEntryPB.newBuilder()
      .setIndex(1L).setTerm(1).setOpPayload(ByteString.EMPTY).build()))
      .hasMessageContaining("'op_type'");
    // op_payload missing
    assertThatThrownBy(() -> converter.fromLogEntryPB(
      ConsensusProtos.LogEntryPB.newBuilder().setIndex(1L).setTerm(1).setOpType(0).build()))
      .hasMessageContaining("'op_payload'");
  }

  @Test
  public void testFromMembersViewPBRejectsMissingLogIndex() {
    assertThatThrownBy(() -> converter
      .fromMembersViewPB(ConsensusProtos.RaftGroupMembersViewPB.newBuilder().build()))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("'log_index'");
  }

  @Test
  public void testFromGroupAppendPBRejectsMissingGroupId() {
    AppendEntriesRequest req = factory.createAppendEntriesRequestBuilder().setGroupId(GID)
      .setSender(a).setTerm(1).setPreviousLogTerm(0).setPreviousLogIndex(0L).setCommitIndex(0L)
      .setLogEntries(List.of()).setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    ConsensusProtos.GroupAppendEntriesPB pb =
      converter.toGroupAppendPB(req).toBuilder().clearGroupId().build();
    assertThatThrownBy(() -> converter.fromGroupAppendPB(pb, GroupId.of(GID)))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("GroupAppendEntriesPB")
      .hasMessageContaining("'group_id'");
  }

  @Test
  public void testFromGroupBulkHeartbeatPBRejectsMissingTerm() {
    LeaderHeartbeat hb = factory.createLeaderHeartbeatBuilder().setGroupId(GID).setSender(a)
      .setTerm(1).setCommitIndex(0L).build();
    ConsensusProtos.GroupBulkHeartbeatPB pb =
      converter.toGroupBulkHeartbeatPB(hb).toBuilder().clearTerm().build();
    assertThatThrownBy(() -> converter.fromGroupBulkHeartbeatPB(pb, GroupId.of(GID), a))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("'term'");
  }

  @Test
  public void testFromGroupBulkHeartbeatAckPBRejectsMissingTerm() {
    LeaderHeartbeatAck ack =
      factory.createLeaderHeartbeatAckBuilder().setGroupId(GID).setSender(a).setTerm(1).build();
    ConsensusProtos.GroupBulkHeartbeatAckPB pb =
      converter.toGroupBulkHeartbeatAckPB(ack).toBuilder().clearTerm().build();
    assertThatThrownBy(() -> converter.fromGroupBulkHeartbeatAckPB(pb, GroupId.of(GID), a))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("'term'");
  }

  @Test
  public void testFromVoteRequestPBRejectsMissingTerm() {
    VoteRequest req = factory.createVoteRequestBuilder().setGroupId(GID).setSender(a).setTerm(1)
      .setLastLogTerm(0).setLastLogIndex(0L).setSticky(false).build();
    ConsensusProtos.VoteRequestPB pb =
      converter.toVoteRequestPB(req).toBuilder().clearTerm().build();
    assertThatThrownBy(() -> converter.fromVoteRequestPB(pb, GroupId.of(GID)))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("'term'");
  }

  @Test
  public void testFromVoteRequestPBRejectsMissingSticky() {
    VoteRequest req = factory.createVoteRequestBuilder().setGroupId(GID).setSender(a).setTerm(1)
      .setLastLogTerm(0).setLastLogIndex(0L).setSticky(true).build();
    ConsensusProtos.VoteRequestPB pb =
      converter.toVoteRequestPB(req).toBuilder().clearSticky().build();
    assertThatThrownBy(() -> converter.fromVoteRequestPB(pb, GroupId.of(GID)))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("'sticky'");
  }

  @Test
  public void testFromInstallSnapshotPBRejectsMissingMembersView() {
    RaftGroupMembersView view = factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(List.of(a, b, c)).setVotingMembers(List.of(a, b, c)).build();
    InstallSnapshotRequest req = factory.createInstallSnapshotRequestBuilder().setGroupId(GID)
      .setSender(a).setTerm(2).setSenderLeader(true).setSnapshotTerm(1).setSnapshotIndex(7L)
      .setTotalSnapshotChunkCount(1).setSnapshottedMembers(List.of(a)).setGroupMembersView(view)
      .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    ConsensusProtos.InstallSnapshotRequestPB pb =
      converter.toInstallSnapshotPB(req).toBuilder().clearGroupMembersView().build();
    assertThatThrownBy(() -> converter.fromInstallSnapshotPB(pb, GroupId.of(GID)))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("'group_members_view'");
  }

  @Test
  public void testFromTriggerElectionPBRejectsMissingLastLogIndex() {
    TriggerLeaderElectionRequest req = factory.createTriggerLeaderElectionRequestBuilder()
      .setGroupId(GID).setSender(a).setTerm(1).setLastLogTerm(0).setLastLogIndex(0L).build();
    ConsensusProtos.TriggerLeaderElectionPB pb =
      converter.toTriggerElectionPB(req).toBuilder().clearLastLogIndex().build();
    assertThatThrownBy(() -> converter.fromTriggerElectionPB(pb, GroupId.of(GID)))
      .isInstanceOf(MalformedMessageException.class).hasMessageContaining("'last_log_index'");
  }

  private static List<String> idsOf(Collection<? extends RaftEndpoint> eps) {
    return eps.stream().map(e -> String.valueOf(e.getId())).collect(Collectors.toList());
  }
}
