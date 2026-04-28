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

import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.io.netty.buffer.ByteBuf;
import org.apache.hbase.thirdparty.io.netty.buffer.Unpooled;
import org.apache.hbase.thirdparty.io.netty.channel.embedded.EmbeddedChannel;
import org.apache.hbase.thirdparty.io.netty.handler.codec.CorruptedFrameException;
import org.apache.hbase.thirdparty.io.netty.handler.codec.TooLongFrameException;

/**
 * Tests {@link ConsensusFrameEncoder} / {@link ConsensusFrameDecoder} via {@link EmbeddedChannel},
 * exercising the length-prefix + protobuf framing.
 */
@Tag(SmallTests.TAG)
public class TestConsensusFrameCodec extends TestBase {

  private static final int MAX_FRAME = 1 << 20;

  @Test
  public void testEncodeFrame() {
    EmbeddedChannel ch = new EmbeddedChannel(new ConsensusFrameEncoder());
    ConsensusProtos.ConsensusFrame frame = newVoteFrame("g1", 7);
    assertThat(ch.writeOutbound(frame)).isTrue();
    ByteBuf out = ch.readOutbound();
    try {
      int declared = out.readInt();
      assertThat(declared).isEqualTo(frame.getSerializedSize());
      byte[] body = new byte[declared];
      out.readBytes(body);
      ConsensusProtos.ConsensusFrame parsed = ConsensusProtos.ConsensusFrame.parseFrom(body);
      assertThat(parsed.getKind()).isEqualTo(ConsensusProtos.ConsensusFrame.Kind.VOTE_REQUEST);
      assertThat(parsed.getVoteRequest().getTerm()).isEqualTo(7);
    } catch (Exception e) {
      throw new AssertionError(e);
    } finally {
      out.release();
      ch.finishAndReleaseAll();
    }
  }

  @Test
  public void testDecodeFrame() {
    EmbeddedChannel ch = new EmbeddedChannel(new ConsensusFrameDecoder(MAX_FRAME));
    ConsensusProtos.ConsensusFrame frame = newVoteFrame("g1", 11);
    assertThat(ch.writeInbound(toFramedByteBuf(frame))).isTrue();
    ConsensusProtos.ConsensusFrame parsed = ch.readInbound();
    assertThat(parsed.getKind()).isEqualTo(ConsensusProtos.ConsensusFrame.Kind.VOTE_REQUEST);
    assertThat(parsed.getVoteRequest().getTerm()).isEqualTo(11);
    ch.finishAndReleaseAll();
  }

  @Test
  public void testReassembleFragments() {
    EmbeddedChannel ch = new EmbeddedChannel(new ConsensusFrameDecoder(MAX_FRAME));
    ConsensusProtos.ConsensusFrame frame = newVoteFrame("group-with-some-bytes", 42);
    ByteBuf framed = toFramedByteBuf(frame);
    int total = framed.readableBytes();
    ByteBuf first = framed.readBytes(total / 2);
    ByteBuf second = framed.readBytes(total - total / 2);
    framed.release();

    assertThat(ch.writeInbound(first)).isFalse();
    assertThat(ch.writeInbound(second)).isTrue();
    ConsensusProtos.ConsensusFrame parsed = ch.readInbound();
    assertThat(parsed.getVoteRequest().getTerm()).isEqualTo(42);
    ch.finishAndReleaseAll();
  }

  @Test
  public void testBackToBackFrames() {
    EmbeddedChannel ch = new EmbeddedChannel(new ConsensusFrameDecoder(MAX_FRAME));
    ConsensusProtos.ConsensusFrame f1 = newVoteFrame("g1", 1);
    ConsensusProtos.ConsensusFrame f2 = newVoteFrame("g2", 2);
    ByteBuf merged = Unpooled.buffer();
    merged.writeBytes(toFramedBytes(f1));
    merged.writeBytes(toFramedBytes(f2));
    assertThat(ch.writeInbound(merged)).isTrue();
    ConsensusProtos.ConsensusFrame p1 = ch.readInbound();
    ConsensusProtos.ConsensusFrame p2 = ch.readInbound();
    assertThat(p1.getVoteRequest().getTerm()).isEqualTo(1);
    assertThat(p2.getVoteRequest().getTerm()).isEqualTo(2);
    ch.finishAndReleaseAll();
  }

  @Test
  public void testRejectCorruptBytes() {
    EmbeddedChannel ch = new EmbeddedChannel(new ConsensusFrameDecoder(MAX_FRAME));
    byte[] junk = new byte[] { (byte) 0xff, 0x01, 0x02 };
    ByteBuf framed = Unpooled.buffer();
    framed.writeInt(junk.length);
    framed.writeBytes(junk);
    assertThatThrownBy(() -> ch.writeInbound(framed)).isInstanceOf(CorruptedFrameException.class)
      .hasMessageContaining("Malformed ConsensusFrame");
    ch.finishAndReleaseAll();
  }

  @Test
  public void testRejectOversizedFrame() {
    int cap = 64;
    EmbeddedChannel ch = new EmbeddedChannel(new ConsensusFrameDecoder(cap));
    ByteBuf framed = Unpooled.buffer();
    framed.writeInt(cap + 1);
    framed.writeBytes(new byte[cap + 1]);
    assertThatThrownBy(() -> ch.writeInbound(framed)).isInstanceOf(TooLongFrameException.class);
    ch.finishAndReleaseAll();
  }

  @Test
  public void testRoundTrip() {
    EmbeddedChannel encoder = new EmbeddedChannel(new ConsensusFrameEncoder());
    EmbeddedChannel decoder = new EmbeddedChannel(new ConsensusFrameDecoder(MAX_FRAME));
    ConsensusProtos.ConsensusFrame frame = newVoteFrame("gX", 99);
    encoder.writeOutbound(frame);
    ByteBuf encoded = encoder.readOutbound();
    decoder.writeInbound(encoded);
    ConsensusProtos.ConsensusFrame parsed = decoder.readInbound();
    assertThat(parsed.getKind()).isEqualTo(ConsensusProtos.ConsensusFrame.Kind.VOTE_REQUEST);
    assertThat(parsed.getVoteRequest().getTerm()).isEqualTo(99);
    encoder.finishAndReleaseAll();
    decoder.finishAndReleaseAll();
  }

  @Test
  public void testBulkHeartbeatRoundTrip() {
    EmbeddedChannel encoder = new EmbeddedChannel(new ConsensusFrameEncoder());
    EmbeddedChannel decoder = new EmbeddedChannel(new ConsensusFrameDecoder(MAX_FRAME));
    ConsensusProtos.ConsensusFrame frame = newBulkHeartbeat("gA", "gB", 7, 100L);
    encoder.writeOutbound(frame);
    ByteBuf encoded = encoder.readOutbound();
    decoder.writeInbound(encoded);
    ConsensusProtos.ConsensusFrame parsed = decoder.readInbound();
    assertThat(parsed.getKind()).isEqualTo(ConsensusProtos.ConsensusFrame.Kind.BULK_HEARTBEAT);
    assertThat(parsed.getBulkHeartbeat().getEpoch()).isEqualTo(12345L);
    assertThat(parsed.getBulkHeartbeat().getTick()).isEqualTo(7L);
    assertThat(parsed.getBulkHeartbeat().getGroupsCount()).isEqualTo(2);
    assertThat(parsed.getBulkHeartbeat().getGroups(0).getGroupId().toStringUtf8()).isEqualTo("gA");
    assertThat(parsed.getBulkHeartbeat().getGroups(0).getTerm()).isEqualTo(7);
    assertThat(parsed.getBulkHeartbeat().getGroups(0).getCommitIndex()).isEqualTo(100L);
    assertThat(parsed.getBulkHeartbeat().getGroups(1).getGroupId().toStringUtf8()).isEqualTo("gB");
    encoder.finishAndReleaseAll();
    decoder.finishAndReleaseAll();
  }

  @Test
  public void testBulkHeartbeatAckRoundTrip() {
    EmbeddedChannel encoder = new EmbeddedChannel(new ConsensusFrameEncoder());
    EmbeddedChannel decoder = new EmbeddedChannel(new ConsensusFrameDecoder(MAX_FRAME));
    ConsensusProtos.ConsensusFrame frame = newBulkHeartbeatAck("gA", "gB", 7);
    encoder.writeOutbound(frame);
    ByteBuf encoded = encoder.readOutbound();
    decoder.writeInbound(encoded);
    ConsensusProtos.ConsensusFrame parsed = decoder.readInbound();
    assertThat(parsed.getKind()).isEqualTo(ConsensusProtos.ConsensusFrame.Kind.BULK_HEARTBEAT_ACK);
    assertThat(parsed.getBulkHeartbeatAck().getEpoch()).isEqualTo(6789L);
    assertThat(parsed.getBulkHeartbeatAck().getTick()).isEqualTo(7L);
    assertThat(parsed.getBulkHeartbeatAck().getGroupsCount()).isEqualTo(2);
    assertThat(parsed.getBulkHeartbeatAck().getGroups(0).getGroupId().toStringUtf8())
      .isEqualTo("gA");
    assertThat(parsed.getBulkHeartbeatAck().getGroups(0).getTerm()).isEqualTo(7);
    assertThat(parsed.getBulkHeartbeatAck().getGroups(1).getGroupId().toStringUtf8())
      .isEqualTo("gB");
    encoder.finishAndReleaseAll();
    decoder.finishAndReleaseAll();
  }

  private static ConsensusProtos.ConsensusFrame newBulkHeartbeat(String g1, String g2, int term,
    long commitIndex) {
    ConsensusProtos.RaftEndpointPB sender =
      ConsensusProtos.RaftEndpointPB.newBuilder().setId(ByteString.copyFromUtf8("node-1")).build();
    ConsensusProtos.BulkHeartbeatPB.Builder bulk =
      ConsensusProtos.BulkHeartbeatPB.newBuilder().setSender(sender).setEpoch(12345L).setTick(term)
        .addGroups(ConsensusProtos.GroupBulkHeartbeatPB.newBuilder()
          .setGroupId(ByteString.copyFromUtf8(g1)).setTerm(term).setCommitIndex(commitIndex))
        .addGroups(ConsensusProtos.GroupBulkHeartbeatPB.newBuilder()
          .setGroupId(ByteString.copyFromUtf8(g2)).setTerm(term).setCommitIndex(commitIndex));
    return ConsensusProtos.ConsensusFrame.newBuilder()
      .setKind(ConsensusProtos.ConsensusFrame.Kind.BULK_HEARTBEAT).setBulkHeartbeat(bulk).build();
  }

  private static ConsensusProtos.ConsensusFrame newBulkHeartbeatAck(String g1, String g2,
    int term) {
    ConsensusProtos.RaftEndpointPB sender =
      ConsensusProtos.RaftEndpointPB.newBuilder().setId(ByteString.copyFromUtf8("node-2")).build();
    ConsensusProtos.BulkHeartbeatAckPB.Builder bulk = ConsensusProtos.BulkHeartbeatAckPB
      .newBuilder().setSender(sender).setEpoch(6789L).setTick(term)
      .addGroups(ConsensusProtos.GroupBulkHeartbeatAckPB.newBuilder()
        .setGroupId(ByteString.copyFromUtf8(g1)).setTerm(term).setLastVerifiedLogIndex(50L))
      .addGroups(ConsensusProtos.GroupBulkHeartbeatAckPB.newBuilder()
        .setGroupId(ByteString.copyFromUtf8(g2)).setTerm(term).setLastVerifiedLogIndex(60L));
    return ConsensusProtos.ConsensusFrame.newBuilder()
      .setKind(ConsensusProtos.ConsensusFrame.Kind.BULK_HEARTBEAT_ACK).setBulkHeartbeatAck(bulk)
      .build();
  }

  private static ConsensusProtos.ConsensusFrame newVoteFrame(String groupId, int term) {
    return ConsensusProtos.ConsensusFrame.newBuilder()
      .setKind(ConsensusProtos.ConsensusFrame.Kind.VOTE_REQUEST)
      .setVoteRequest(
        ConsensusProtos.VoteRequestPB.newBuilder().setGroupId(ByteString.copyFromUtf8(groupId))
          .setSender(
            ConsensusProtos.RaftEndpointPB.newBuilder().setId(ByteString.copyFromUtf8("node-1")))
          .setTerm(term).setLastLogTerm(0).setLastLogIndex(0L).setSticky(false))
      .build();
  }

  private static ByteBuf toFramedByteBuf(ConsensusProtos.ConsensusFrame frame) {
    return Unpooled.wrappedBuffer(toFramedBytes(frame));
  }

  private static byte[] toFramedBytes(ConsensusProtos.ConsensusFrame frame) {
    int size = frame.getSerializedSize();
    byte[] out = new byte[Integer.BYTES + size];
    out[0] = (byte) ((size >>> 24) & 0xFF);
    out[1] = (byte) ((size >>> 16) & 0xFF);
    out[2] = (byte) ((size >>> 8) & 0xFF);
    out[3] = (byte) (size & 0xFF);
    byte[] body = frame.toByteArray();
    System.arraycopy(body, 0, out, Integer.BYTES, body.length);
    return out;
  }
}
