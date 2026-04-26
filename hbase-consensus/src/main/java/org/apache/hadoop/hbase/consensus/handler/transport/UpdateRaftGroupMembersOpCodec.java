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
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.com.google.protobuf.InvalidProtocolBufferException;

/**
 * Built-in codec for {@link UpdateRaftGroupMembersOp}. Reserved discriminator id {@code 1}.
 * <p>
 * This is the only operation type the consensus core itself produces. User operations are
 * dispatched by separately registered codecs. Combine them via
 * {@link OperationCodecs#composite(OperationCodec...)}.
 */
@InterfaceAudience.Private
public final class UpdateRaftGroupMembersOpCodec implements OperationCodec {
  /** Reserved discriminator for the membership-change op. */
  public static final int TYPE_ID = 1;

  private final DefaultRaftModelFactory factory;

  public UpdateRaftGroupMembersOpCodec() {
    this(new DefaultRaftModelFactory());
  }

  public UpdateRaftGroupMembersOpCodec(@NonNull DefaultRaftModelFactory factory) {
    this.factory = factory;
  }

  @Override
  public int typeId(@NonNull Object operation) {
    if (!(operation instanceof UpdateRaftGroupMembersOp)) {
      throw new IllegalArgumentException(
        "UpdateRaftGroupMembersOpCodec only handles UpdateRaftGroupMembersOp but got "
          + operation.getClass());
    }
    return TYPE_ID;
  }

  @Override
  @NonNull
  public ByteString encode(@NonNull Object operation) {
    if (!(operation instanceof UpdateRaftGroupMembersOp)) {
      throw new IllegalArgumentException(
        "UpdateRaftGroupMembersOpCodec only handles UpdateRaftGroupMembersOp but got "
          + operation.getClass());
    }
    UpdateRaftGroupMembersOp op = (UpdateRaftGroupMembersOp) operation;
    ConsensusProtos.UpdateRaftGroupMembersOpPB.Builder builder =
      ConsensusProtos.UpdateRaftGroupMembersOpPB.newBuilder();
    for (RaftEndpoint member : op.getMembers()) {
      builder.addMembers(toEndpointPB(member));
    }
    for (RaftEndpoint voter : op.getVotingMembers()) {
      builder.addVotingMembers(toEndpointPB(voter));
    }
    builder.setEndpoint(toEndpointPB(op.getEndpoint()));
    builder.setMode(toModePB(op.getMode()));
    return builder.build().toByteString();
  }

  @Override
  @NonNull
  public Object decode(int typeId, @NonNull ByteString payload) {
    if (typeId != TYPE_ID) {
      throw new IllegalArgumentException(
        "UpdateRaftGroupMembersOpCodec asked to decode unknown typeId " + typeId);
    }
    final ConsensusProtos.UpdateRaftGroupMembersOpPB pb;
    try {
      pb = ConsensusProtos.UpdateRaftGroupMembersOpPB.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Malformed UpdateRaftGroupMembersOpPB payload", e);
    }
    if (!pb.hasEndpoint()) {
      throw new IllegalArgumentException(
        "UpdateRaftGroupMembersOpPB is missing required field 'endpoint'");
    }
    if (!pb.hasMode()) {
      throw new IllegalArgumentException(
        "UpdateRaftGroupMembersOpPB is missing required field 'mode'");
    }
    if (!pb.getEndpoint().hasId()) {
      throw new IllegalArgumentException(
        "UpdateRaftGroupMembersOpPB.endpoint is missing required field 'id'");
    }
    List<RaftEndpoint> members = new ArrayList<>(pb.getMembersCount());
    for (ConsensusProtos.RaftEndpointPB ep : pb.getMembersList()) {
      if (!ep.hasId()) {
        throw new IllegalArgumentException(
          "UpdateRaftGroupMembersOpPB.members entry is missing required field 'id'");
      }
      members.add(WireRaftEndpoint.fromBytes(ep.getId()));
    }
    List<RaftEndpoint> voters = new ArrayList<>(pb.getVotingMembersCount());
    for (ConsensusProtos.RaftEndpointPB ep : pb.getVotingMembersList()) {
      if (!ep.hasId()) {
        throw new IllegalArgumentException(
          "UpdateRaftGroupMembersOpPB.voting_members entry is missing required field 'id'");
      }
      voters.add(WireRaftEndpoint.fromBytes(ep.getId()));
    }
    return factory.createUpdateRaftGroupMembersOpBuilder().setMembers(members)
      .setVotingMembers(voters).setEndpoint(WireRaftEndpoint.fromBytes(pb.getEndpoint().getId()))
      .setMode(fromModePB(pb.getMode())).build();
  }

  @Override
  public boolean handles(@NonNull Object operation) {
    return operation instanceof UpdateRaftGroupMembersOp;
  }

  @Override
  public boolean handlesTypeId(int typeId) {
    return typeId == TYPE_ID;
  }

  private static ConsensusProtos.RaftEndpointPB toEndpointPB(RaftEndpoint endpoint) {
    return ConsensusProtos.RaftEndpointPB.newBuilder().setId(WireRaftEndpoint.toBytes(endpoint))
      .build();
  }

  private static ConsensusProtos.UpdateRaftGroupMembersOpPB.ChangeMode
    toModePB(MembershipChangeMode mode) {
    switch (mode) {
      case ADD_LEARNER:
        return ConsensusProtos.UpdateRaftGroupMembersOpPB.ChangeMode.ADD_LEARNER;
      case ADD_OR_PROMOTE_TO_FOLLOWER:
        return ConsensusProtos.UpdateRaftGroupMembersOpPB.ChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER;
      case REMOVE_MEMBER:
        return ConsensusProtos.UpdateRaftGroupMembersOpPB.ChangeMode.REMOVE_MEMBER;
      default:
        throw new IllegalArgumentException("Unknown MembershipChangeMode: " + mode);
    }
  }

  private static MembershipChangeMode
    fromModePB(ConsensusProtos.UpdateRaftGroupMembersOpPB.ChangeMode mode) {
    switch (mode) {
      case ADD_LEARNER:
        return MembershipChangeMode.ADD_LEARNER;
      case ADD_OR_PROMOTE_TO_FOLLOWER:
        return MembershipChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER;
      case REMOVE_MEMBER:
        return MembershipChangeMode.REMOVE_MEMBER;
      default:
        throw new IllegalArgumentException("Unknown ChangeMode: " + mode);
    }
  }
}
