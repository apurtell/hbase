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

import java.io.IOException;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.io.netty.buffer.ByteBuf;
import org.apache.hbase.thirdparty.io.netty.buffer.ByteBufAllocator;
import org.apache.hbase.thirdparty.io.netty.buffer.ByteBufOutputStream;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelHandler;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelHandlerContext;
import org.apache.hbase.thirdparty.io.netty.handler.codec.MessageToByteEncoder;

/**
 * Outbound encoder.
 * <p>
 * Writes {@code int32 length || protobuf bytes} for one {@link ConsensusProtos.ConsensusFrame}. The
 * matching decoder strips the same 4-byte length prefix.
 * <p>
 * The protobuf message is serialized straight into the pooled {@link ByteBuf} via
 * {@link ByteBufOutputStream}, mirroring the zero-copy idiom used by {@code hbase-server}'s netty
 * response encoders.
 * <p>
 * {@link ChannelHandler.Sharable} so the same encoder instance can sit on both the server's child
 * pipeline and every outbound client pipeline without per-channel allocation.
 */
@InterfaceAudience.Private
@ChannelHandler.Sharable
final class ConsensusFrameEncoder extends MessageToByteEncoder<ConsensusProtos.ConsensusFrame> {

  @Override
  protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ConsensusProtos.ConsensusFrame frame,
    boolean preferDirect) {
    int size = frame.getSerializedSize();
    int total = Integer.BYTES + size;
    ByteBufAllocator alloc = ctx.alloc();
    return preferDirect ? alloc.ioBuffer(total, total) : alloc.heapBuffer(total, total);
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, ConsensusProtos.ConsensusFrame frame,
    ByteBuf out) throws IOException {
    int size = frame.getSerializedSize();
    out.writeInt(size);
    int writerIndex = out.writerIndex();
    try (ByteBufOutputStream stream = new ByteBufOutputStream(out)) {
      frame.writeTo(stream);
    }
    if (out.writerIndex() - writerIndex != size) {
      throw new IllegalStateException("ConsensusFrame serialized size mismatch: declared=" + size
        + ", written=" + (out.writerIndex() - writerIndex));
    }
  }
}
