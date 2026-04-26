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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Tests {@link OperationCodec} implementations: built-in {@link UpdateRaftGroupMembersOpCodec},
 * {@link IdentityByteCodec}, the {@link OperationCodecs#composite composite} dispatcher, and the
 * public {@link OperationCodecs} defaults.
 */
@Tag(SmallTests.TAG)
public class TestOperationCodec extends TestBase {

  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();

  // -------------------------------------------------------------------------------------------
  // IdentityByteCodec
  // -------------------------------------------------------------------------------------------

  @Test
  public void testIdentityRoundTrip() {
    IdentityByteCodec codec = new IdentityByteCodec();
    byte[] payload = { 1, 2, 3, 4, 5 };
    assertThat(codec.handles(payload)).isTrue();
    assertThat(codec.handlesTypeId(IdentityByteCodec.TYPE_ID)).isTrue();
    assertThat(codec.typeId(payload)).isEqualTo(IdentityByteCodec.TYPE_ID);
    ByteString encoded = codec.encode(payload);
    assertThat(encoded.toByteArray()).containsExactly(payload);
    Object decoded = codec.decode(IdentityByteCodec.TYPE_ID, encoded);
    assertThat(decoded).isInstanceOf(byte[].class);
    assertThat((byte[]) decoded).containsExactly(payload);
  }

  @Test
  public void testIdentityRejectsNonBytes() {
    IdentityByteCodec codec = new IdentityByteCodec();
    assertThat(codec.handles("a string")).isFalse();
    assertThatThrownBy(() -> codec.encode("a string")).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("byte[]");
    assertThatThrownBy(() -> codec.typeId("a string")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testIdentityRejectsForeignTypeId() {
    IdentityByteCodec codec = new IdentityByteCodec();
    assertThat(codec.handlesTypeId(IdentityByteCodec.TYPE_ID + 1)).isFalse();
    assertThatThrownBy(() -> codec.decode(IdentityByteCodec.TYPE_ID + 1, ByteString.EMPTY))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown typeId");
  }

  // -------------------------------------------------------------------------------------------
  // UpdateRaftGroupMembersOpCodec
  // -------------------------------------------------------------------------------------------

  @Test
  public void testMembershipRoundTrip() {
    UpdateRaftGroupMembersOpCodec codec = new UpdateRaftGroupMembersOpCodec(factory);
    RaftEndpoint a = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint b = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint c = LocalRaftEndpoint.newEndpoint();
    UpdateRaftGroupMembersOp op = factory.createUpdateRaftGroupMembersOpBuilder()
      .setMembers(Arrays.asList(a, b, c)).setVotingMembers(Arrays.asList(a, b)).setEndpoint(c)
      .setMode(MembershipChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER).build();

    assertThat(codec.handles(op)).isTrue();
    assertThat(codec.handlesTypeId(UpdateRaftGroupMembersOpCodec.TYPE_ID)).isTrue();
    assertThat(codec.typeId(op)).isEqualTo(UpdateRaftGroupMembersOpCodec.TYPE_ID);

    ByteString encoded = codec.encode(op);
    Object decoded = codec.decode(UpdateRaftGroupMembersOpCodec.TYPE_ID, encoded);
    assertThat(decoded).isInstanceOf(UpdateRaftGroupMembersOp.class);
    UpdateRaftGroupMembersOp back = (UpdateRaftGroupMembersOp) decoded;
    assertThat(idsOf(back.getMembers()))
      .containsExactlyInAnyOrderElementsOf(idsOf(op.getMembers()));
    assertThat(idsOf(back.getVotingMembers()))
      .containsExactlyInAnyOrderElementsOf(idsOf(op.getVotingMembers()));
    assertThat(String.valueOf(back.getEndpoint().getId()))
      .isEqualTo(String.valueOf(op.getEndpoint().getId()));
    assertThat(back.getMode()).isEqualTo(op.getMode());
  }

  @Test
  public void testMembershipRejectsOther() {
    UpdateRaftGroupMembersOpCodec codec = new UpdateRaftGroupMembersOpCodec(factory);
    assertThat(codec.handles(new byte[1])).isFalse();
    assertThatThrownBy(() -> codec.encode(new byte[1])).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("UpdateRaftGroupMembersOp");
    assertThatThrownBy(() -> codec.typeId(new byte[1]))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testMembershipRejectsForeignTypeId() {
    UpdateRaftGroupMembersOpCodec codec = new UpdateRaftGroupMembersOpCodec(factory);
    assertThat(codec.handlesTypeId(UpdateRaftGroupMembersOpCodec.TYPE_ID + 1)).isFalse();
    assertThatThrownBy(() -> codec.decode(IdentityByteCodec.TYPE_ID, ByteString.EMPTY))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown typeId");
  }

  @Test
  public void testMembershipRejectsMalformed() {
    UpdateRaftGroupMembersOpCodec codec = new UpdateRaftGroupMembersOpCodec(factory);
    ByteString junk = ByteString.copyFrom(new byte[] { (byte) 0xff, 0x00, 0x01 });
    assertThatThrownBy(() -> codec.decode(UpdateRaftGroupMembersOpCodec.TYPE_ID, junk))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Malformed UpdateRaftGroupMembersOpPB");
  }

  @Test
  public void testMembershipRejectsMissingEndpoint() {
    UpdateRaftGroupMembersOpCodec codec = new UpdateRaftGroupMembersOpCodec(factory);
    ByteString payload = ConsensusProtos.UpdateRaftGroupMembersOpPB.newBuilder()
      .setMode(ConsensusProtos.UpdateRaftGroupMembersOpPB.ChangeMode.ADD_LEARNER).build()
      .toByteString();
    assertThatThrownBy(() -> codec.decode(UpdateRaftGroupMembersOpCodec.TYPE_ID, payload))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'endpoint'");
  }

  @Test
  public void testMembershipRejectsMissingMode() {
    UpdateRaftGroupMembersOpCodec codec = new UpdateRaftGroupMembersOpCodec(factory);
    ConsensusProtos.RaftEndpointPB endpoint = ConsensusProtos.RaftEndpointPB.newBuilder()
      .setId(WireRaftEndpoint.toBytes(LocalRaftEndpoint.newEndpoint())).build();
    ByteString payload = ConsensusProtos.UpdateRaftGroupMembersOpPB.newBuilder()
      .setEndpoint(endpoint).build().toByteString();
    assertThatThrownBy(() -> codec.decode(UpdateRaftGroupMembersOpCodec.TYPE_ID, payload))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'mode'");
  }

  @Test
  public void testMembershipRejectsEndpointMissingId() {
    UpdateRaftGroupMembersOpCodec codec = new UpdateRaftGroupMembersOpCodec(factory);
    ByteString payload = ConsensusProtos.UpdateRaftGroupMembersOpPB.newBuilder()
      .setEndpoint(ConsensusProtos.RaftEndpointPB.newBuilder().build())
      .setMode(ConsensusProtos.UpdateRaftGroupMembersOpPB.ChangeMode.ADD_LEARNER).build()
      .toByteString();
    assertThatThrownBy(() -> codec.decode(UpdateRaftGroupMembersOpCodec.TYPE_ID, payload))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'id'");
  }

  // -------------------------------------------------------------------------------------------
  // OperationCodecs (composite + defaults)
  // -------------------------------------------------------------------------------------------

  @Test
  public void testCompositeRequiresOne() {
    assertThatThrownBy(OperationCodecs::composite).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("at least one codec");
  }

  @Test
  public void testCompositeDispatches() {
    OperationCodec composite = OperationCodecs.defaultCodecs();

    byte[] bytes = { 9, 8, 7 };
    assertThat(composite.handles(bytes)).isTrue();
    assertThat(composite.typeId(bytes)).isEqualTo(IdentityByteCodec.TYPE_ID);
    ByteString enc = composite.encode(bytes);
    Object back = composite.decode(IdentityByteCodec.TYPE_ID, enc);
    assertThat((byte[]) back).containsExactly(bytes);

    UpdateRaftGroupMembersOp op = factory.createUpdateRaftGroupMembersOpBuilder()
      .setMembers(List.of(LocalRaftEndpoint.newEndpoint()))
      .setVotingMembers(List.of(LocalRaftEndpoint.newEndpoint()))
      .setEndpoint(LocalRaftEndpoint.newEndpoint()).setMode(MembershipChangeMode.ADD_LEARNER)
      .build();
    assertThat(composite.handles(op)).isTrue();
    assertThat(composite.typeId(op)).isEqualTo(UpdateRaftGroupMembersOpCodec.TYPE_ID);
    ByteString opEnc = composite.encode(op);
    Object opBack = composite.decode(UpdateRaftGroupMembersOpCodec.TYPE_ID, opEnc);
    assertThat(opBack).isInstanceOf(UpdateRaftGroupMembersOp.class);
  }

  @Test
  public void testCompositeFailsUnknown() {
    OperationCodec composite = OperationCodecs.defaultCodecs();
    assertThat(composite.handles("nope")).isFalse();
    assertThatThrownBy(() -> composite.typeId("nope")).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("No registered");
    assertThatThrownBy(() -> composite.encode("nope")).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("No registered");
    assertThat(composite.handlesTypeId(99_999)).isFalse();
    assertThatThrownBy(() -> composite.decode(99_999, ByteString.EMPTY))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No registered");
  }

  @Test
  public void testCompositeFirstMatch() {
    // A delegate that claims everything but only handles String, registered first; the built-in
    // IdentityByteCodec is registered second. composite() must route by handles()/handlesTypeId().
    OperationCodec stringCodec = new OperationCodec() {
      @Override
      public int typeId(@NonNull Object op) {
        if (!(op instanceof String)) {
          throw new IllegalArgumentException("StringCodec only handles String");
        }
        return 5000;
      }

      @Override
      @NonNull
      public ByteString encode(@NonNull Object op) {
        return ByteString.copyFromUtf8((String) op);
      }

      @Override
      @NonNull
      public Object decode(int typeId, @NonNull ByteString p) {
        if (typeId != 5000) {
          throw new IllegalArgumentException("StringCodec only handles 5000");
        }
        return p.toStringUtf8();
      }

      @Override
      public boolean handles(@NonNull Object op) {
        return op instanceof String;
      }

      @Override
      public boolean handlesTypeId(int typeId) {
        return typeId == 5000;
      }
    };

    OperationCodec composite = OperationCodecs.composite(stringCodec, new IdentityByteCodec());
    Object back = composite.decode(5000, composite.encode("hi"));
    assertThat(back).isEqualTo("hi");
    Object b = composite.decode(IdentityByteCodec.TYPE_ID, composite.encode(new byte[] { 1, 2 }));
    assertThat((byte[]) b).containsExactly(new byte[] { 1, 2 });
  }

  private static List<String> idsOf(Collection<? extends RaftEndpoint> eps) {
    return eps.stream().map(e -> String.valueOf(e.getId())).collect(Collectors.toList());
  }
}
