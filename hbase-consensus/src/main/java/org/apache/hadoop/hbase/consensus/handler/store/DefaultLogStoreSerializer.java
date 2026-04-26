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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodecs;
import org.apache.hadoop.hbase.consensus.handler.transport.PayloadCompressor;
import org.apache.hadoop.hbase.consensus.handler.transport.WireRaftEndpoint;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.InvalidProtocolBufferException;

/**
 * Default {@link LogStoreSerializer} implementation.
 * <p>
 * PB-backed for the four model types that already have a wire-format peer in
 * {@code ConsensusProtocol.proto} ({@link LogEntry}, {@link SnapshotChunk},
 * {@link RaftGroupMembersView}, {@link RaftEndpoint}).
 * <p>
 * This implementation is stateless and thread-safe; the same instance is shared across the
 * producing threads of all groups multiplexed onto a {@link UnifiedRaftStore}.
 */
@InterfaceAudience.Private
public final class DefaultLogStoreSerializer implements LogStoreSerializer {

  private final DefaultRaftModelFactory factory;
  private final RaftModelPbCodecs modelCodecs;

  public DefaultLogStoreSerializer() {
    this(new DefaultRaftModelFactory(), OperationCodecs.defaultCodecs(),
      new PayloadCompressor(Compression.Algorithm.NONE));
  }

  public DefaultLogStoreSerializer(@NonNull DefaultRaftModelFactory factory,
    @NonNull OperationCodec operationCodec, @NonNull PayloadCompressor compressor) {
    this.factory = factory;
    this.modelCodecs = new RaftModelPbCodecs(factory, operationCodec, compressor);
  }

  @Override
  public Serializer<RaftGroupMembersView> raftGroupMembersViewSerializer() {
    return new Serializer<>() {
      @NonNull
      @Override
      public byte[] serialize(@NonNull RaftGroupMembersView element) {
        return RaftModelPbCodecs.toMembersViewPB(element).toByteArray();
      }

      @NonNull
      @Override
      public RaftGroupMembersView deserialize(@NonNull byte[] element) {
        try {
          return modelCodecs
            .fromMembersViewPB(ConsensusProtos.RaftGroupMembersViewPB.parseFrom(element));
        } catch (InvalidProtocolBufferException e) {
          throw new IllegalArgumentException("Malformed RaftGroupMembersViewPB payload", e);
        }
      }
    };
  }

  @Override
  public Serializer<RaftEndpoint> raftEndpointSerializer() {
    return new Serializer<>() {
      @NonNull
      @Override
      public byte[] serialize(@NonNull RaftEndpoint element) {
        return RaftModelPbCodecs.toEndpointPB(element).toByteArray();
      }

      @NonNull
      @Override
      public RaftEndpoint deserialize(@NonNull byte[] element) {
        try {
          return RaftModelPbCodecs
            .fromEndpointPB(ConsensusProtos.RaftEndpointPB.parseFrom(element));
        } catch (InvalidProtocolBufferException e) {
          throw new IllegalArgumentException("Malformed RaftEndpointPB payload", e);
        }
      }
    };
  }

  @Override
  public Serializer<LogEntry> logEntrySerializer() {
    return new Serializer<>() {
      @NonNull
      @Override
      public byte[] serialize(@NonNull LogEntry element) {
        return modelCodecs.toLogEntryPB(element).toByteArray();
      }

      @NonNull
      @Override
      public LogEntry deserialize(@NonNull byte[] element) {
        try {
          return modelCodecs.fromLogEntryPB(ConsensusProtos.LogEntryPB.parseFrom(element));
        } catch (InvalidProtocolBufferException e) {
          throw new IllegalArgumentException("Malformed LogEntryPB payload", e);
        }
      }
    };
  }

  @Override
  public Serializer<SnapshotChunk> snapshotChunkSerializer() {
    return new Serializer<>() {
      @NonNull
      @Override
      public byte[] serialize(@NonNull SnapshotChunk element) {
        return modelCodecs.toSnapshotChunkPB(element).toByteArray();
      }

      @NonNull
      @Override
      public SnapshotChunk deserialize(@NonNull byte[] element) {
        try {
          return modelCodecs
            .fromSnapshotChunkPB(ConsensusProtos.SnapshotChunkPB.parseFrom(element));
        } catch (InvalidProtocolBufferException e) {
          throw new IllegalArgumentException("Malformed SnapshotChunkPB payload", e);
        }
      }
    };
  }

  @Override
  public Serializer<RaftEndpointPersistentState> raftEndpointPersistentStateSerializer() {
    return new Serializer<>() {
      @NonNull
      @Override
      public byte[] serialize(@NonNull RaftEndpointPersistentState element) {
        byte[] idBytes = endpointIdBytes(element.getLocalEndpoint());
        ByteBuffer buf =
          ByteBuffer.allocate(LogRecord.varIntLen(idBytes.length) + idBytes.length + 1);
        LogRecord.putVarInt(buf, idBytes.length);
        buf.put(idBytes);
        buf.put((byte) (element.isVoting() ? 1 : 0));
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
      }

      @NonNull
      @Override
      public RaftEndpointPersistentState deserialize(@NonNull byte[] element) {
        ByteBuffer buf = ByteBuffer.wrap(element);
        int idLen = LogRecord.getVarInt(buf);
        if (idLen < 0 || idLen > buf.remaining()) {
          throw new IllegalArgumentException(
            "RaftEndpointPersistentState id length out of range: " + idLen);
        }
        byte[] id = new byte[idLen];
        buf.get(id);
        if (buf.remaining() != 1) {
          throw new IllegalArgumentException(
            "RaftEndpointPersistentState trailing voting byte missing or extra bytes present");
        }
        boolean voting = buf.get() != 0;
        RaftEndpoint ep = WireRaftEndpoint.fromBytes(id);
        return factory.createRaftEndpointPersistentStateBuilder().setLocalEndpoint(ep)
          .setVoting(voting).build();
      }
    };
  }

  @Override
  public Serializer<RaftTermPersistentState> raftTermPersistentStateSerializer() {
    return new Serializer<>() {
      @NonNull
      @Override
      public byte[] serialize(@NonNull RaftTermPersistentState element) {
        byte[] idBytes =
          element.getVotedFor() == null ? new byte[0] : endpointIdBytes(element.getVotedFor());
        ByteBuffer buf = ByteBuffer.allocate(LogRecord.varLongLen(element.getTerm())
          + LogRecord.varIntLen(idBytes.length) + idBytes.length);
        LogRecord.putVarLong(buf, element.getTerm() & 0xFFFFFFFFL);
        LogRecord.putVarInt(buf, idBytes.length);
        buf.put(idBytes);
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
      }

      @NonNull
      @Override
      public RaftTermPersistentState deserialize(@NonNull byte[] element) {
        ByteBuffer buf = ByteBuffer.wrap(element);
        long termLong = LogRecord.getVarLong(buf);
        int term = (int) (termLong & 0xFFFFFFFFL);
        int idLen = LogRecord.getVarInt(buf);
        if (idLen < 0 || idLen > buf.remaining()) {
          throw new IllegalArgumentException(
            "RaftTermPersistentState voted_for id length out of range: " + idLen);
        }
        @Nullable
        RaftEndpoint votedFor = null;
        if (idLen > 0) {
          byte[] id = new byte[idLen];
          buf.get(id);
          votedFor = WireRaftEndpoint.fromBytes(id);
        }
        return factory.createRaftTermPersistentStateBuilder().setTerm(term).setVotedFor(votedFor)
          .build();
      }
    };
  }

  private static byte[] endpointIdBytes(RaftEndpoint endpoint) {
    return WireRaftEndpoint.toBytes(endpoint).toByteArray();
  }
}
