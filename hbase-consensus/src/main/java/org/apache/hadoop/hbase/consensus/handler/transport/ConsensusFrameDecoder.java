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

import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.InvalidProtocolBufferException;
import org.apache.hbase.thirdparty.io.netty.buffer.ByteBuf;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelHandlerContext;
import org.apache.hbase.thirdparty.io.netty.handler.codec.CorruptedFrameException;
import org.apache.hbase.thirdparty.io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Inbound decoder.
 * <p>
 * Strips the 4-byte length prefix and parses the remaining bytes as a
 * {@link ConsensusProtos.ConsensusFrame}. We do not chain to Netty's {@code ProtobufDecoder} from
 * the shaded netty jar because that class is compiled against the un-shaded
 * {@code com.google.protobuf} runtime, while our generated PB classes use the shaded
 * {@code org.apache.hbase.thirdparty.com.google.protobuf} runtime; doing the parse ourselves keeps
 * everything on the shaded namespace.
 * <p>
 * Byte extraction mirrors {@code NettyRpcFrameDecoder.getHeader} in {@code hbase-server}: prefer
 * the backing array when the {@link ByteBuf} exposes one to avoid an extra copy.
 */
@InterfaceAudience.Private
final class ConsensusFrameDecoder extends LengthFieldBasedFrameDecoder {

  ConsensusFrameDecoder(int maxFrameLength) {
    super(maxFrameLength, 0, 4, 0, 4);
  }

  @Override
  protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
    ByteBuf framed = (ByteBuf) super.decode(ctx, in);
    if (framed == null) {
      return null;
    }
    try {
      int length = framed.readableBytes();
      byte[] array;
      int offset;
      if (framed.hasArray()) {
        array = framed.array();
        offset = framed.arrayOffset() + framed.readerIndex();
      } else {
        array = new byte[length];
        framed.getBytes(framed.readerIndex(), array, 0, length);
        offset = 0;
      }
      try {
        return ConsensusProtos.ConsensusFrame.parser().parseFrom(array, offset, length);
      } catch (InvalidProtocolBufferException e) {
        throw new CorruptedFrameException("Malformed ConsensusFrame: " + e.getMessage(), e);
      }
    } finally {
      framed.release();
    }
  }
}
