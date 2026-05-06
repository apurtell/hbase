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
import org.apache.hadoop.hbase.consensus.handler.statemachine.FlushMarker;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.com.google.protobuf.InvalidProtocolBufferException;

/**
 * Built-in codec for {@link FlushMarker}. Reserved discriminator id {@code 3}.
 * <p>
 * Required by every embedding that ever proposes a {@link FlushMarker}, including the synthetic
 * idle-flush dispatched by the bulk-heartbeat wheel on a leader that has been silent past
 * {@code RaftConfig.getIdleFlushIntervalMillis()}. Combine with
 * {@link UpdateRaftGroupMembersOpCodec} and {@link IdentityByteCodec} via
 * {@link OperationCodecs#composite(OperationCodec...)} (or use
 * {@link OperationCodecs#defaultCodecs()} which already wires it in).
 * <p>
 * Wire shape is {@link ConsensusProtos.FlushMarkerPB}: {@code flush_op_seq_id},
 * {@code snapshot_max_seq_id}, and {@code metadata}, all optional; the codec round-trips defaults
 * cleanly so a synthetic empty marker (zero seq ids, empty metadata) replicates as an extremely
 * compact entry.
 */
@InterfaceAudience.Private
public final class FlushMarkerCodec implements OperationCodec {
  /** Reserved discriminator for the flush-marker op. */
  public static final int TYPE_ID = 3;

  /** Singleton empty metadata array reused on the decode side when the wire field is absent. */
  private static final byte[] EMPTY_METADATA = new byte[0];

  @Override
  public int typeId(@NonNull Object operation) {
    if (!(operation instanceof FlushMarker)) {
      throw new IllegalArgumentException(
        "FlushMarkerCodec only handles FlushMarker but got " + operation.getClass());
    }
    return TYPE_ID;
  }

  @Override
  @NonNull
  public ByteString encode(@NonNull Object operation) {
    if (!(operation instanceof FlushMarker)) {
      throw new IllegalArgumentException(
        "FlushMarkerCodec only handles FlushMarker but got " + operation.getClass());
    }
    FlushMarker marker = (FlushMarker) operation;
    ConsensusProtos.FlushMarkerPB.Builder builder = ConsensusProtos.FlushMarkerPB.newBuilder()
      .setFlushOpSeqId(marker.getFlushOpSeqId()).setSnapshotMaxSeqId(marker.getSnapshotMaxSeqId());
    byte[] metadata = marker.getMetadata();
    if (metadata.length > 0) {
      // Defensive copy intentionally omitted: FlushMarker.getMetadata() does not expose its
      // internal array (its constructor copied it on the way in) and ByteString.copyFrom takes its
      // own copy, so the wire bytes remain immutable.
      builder.setMetadata(ByteString.copyFrom(metadata));
    }
    return builder.build().toByteString();
  }

  @Override
  @NonNull
  public Object decode(int typeId, @NonNull ByteString payload) {
    if (typeId != TYPE_ID) {
      throw new IllegalArgumentException(
        "FlushMarkerCodec asked to decode unknown typeId " + typeId);
    }
    final ConsensusProtos.FlushMarkerPB pb;
    try {
      pb = ConsensusProtos.FlushMarkerPB.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Malformed FlushMarkerPB payload", e);
    }
    long flushOpSeqId = pb.hasFlushOpSeqId() ? pb.getFlushOpSeqId() : 0L;
    long snapshotMaxSeqId = pb.hasSnapshotMaxSeqId() ? pb.getSnapshotMaxSeqId() : 0L;
    byte[] metadata = pb.hasMetadata() ? pb.getMetadata().toByteArray() : EMPTY_METADATA;
    return new FlushMarker(flushOpSeqId, snapshotMaxSeqId, metadata);
  }

  @Override
  public boolean handles(@NonNull Object operation) {
    return operation instanceof FlushMarker;
  }

  @Override
  public boolean handlesTypeId(int typeId) {
    return typeId == TYPE_ID;
  }
}
