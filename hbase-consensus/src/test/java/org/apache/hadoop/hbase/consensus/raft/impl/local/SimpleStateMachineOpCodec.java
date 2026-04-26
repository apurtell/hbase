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
package org.apache.hadoop.hbase.consensus.raft.impl.local;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.Apply;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.NewTermOp;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.QueryAll;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.QueryLast;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Test-only {@link OperationCodec} for the operation types produced by {@link SimpleStateMachine}:
 * the four user ops ({@link Apply}, {@link QueryLast}, {@link QueryAll}, {@link NewTermOp}) and the
 * {@link Map} chunks emitted by {@link SimpleStateMachine#takeSnapshot} (which the persistent log
 * store routes through {@link OperationCodec#encode} when materializing snapshot chunks). Used by
 * parity / fault-tolerance fixtures that drive the live Raft cluster against
 * {@code UnifiedRaftStore}.
 * <p>
 * The wire format is a hand-rolled tagged binary encoding. Reserves discriminator {@code 4096},
 * well above {@link OperationCodec#FIRST_USER_TYPE_ID}.
 * <p>
 * Layout: {@code [1 byte kind][optional payload bytes]}. Kinds:
 * <ul>
 * <li>{@code 0x01} — {@code Apply} carrying a {@code String} value (UTF-8 length-prefixed)</li>
 * <li>{@code 0x02} — {@code Apply} carrying a {@code byte[]} value (length-prefixed)</li>
 * <li>{@code 0x03} — {@code QueryLast} (no payload)</li>
 * <li>{@code 0x04} — {@code QueryAll} (no payload)</li>
 * <li>{@code 0x05} — {@code NewTermOp} (no payload)</li>
 * <li>{@code 0x06} — snapshot chunk: {@code Map<Long, String|byte[]>}; layout is
 * {@code [4 bytes BE entry count]} followed by per-entry
 * {@code [8 bytes BE key][1 byte value tag (0x01=String, 0x02=byte[])][4 bytes BE value length]
 *       [value bytes]}</li>
 * </ul>
 */
public final class SimpleStateMachineOpCodec implements OperationCodec {
  public static final int TYPE_ID = 4096;

  private static final byte KIND_APPLY_STRING = 0x01;
  private static final byte KIND_APPLY_BYTES = 0x02;
  private static final byte KIND_QUERY_LAST = 0x03;
  private static final byte KIND_QUERY_ALL = 0x04;
  private static final byte KIND_NEW_TERM = 0x05;
  private static final byte KIND_SNAPSHOT_CHUNK = 0x06;
  private static final byte VAL_TAG_STRING = 0x01;
  private static final byte VAL_TAG_BYTES = 0x02;

  @Override
  public int typeId(@NonNull Object operation) {
    if (!handles(operation)) {
      throw new IllegalArgumentException(
        "SimpleStateMachineOpCodec does not handle " + operation.getClass().getName());
    }
    return TYPE_ID;
  }

  @Override
  public boolean handles(@NonNull Object operation) {
    return operation instanceof Apply || operation instanceof QueryLast
      || operation instanceof QueryAll || operation instanceof NewTermOp
      || operation instanceof Map;
  }

  @Override
  public boolean handlesTypeId(int typeId) {
    return typeId == TYPE_ID;
  }

  @NonNull
  @Override
  public ByteString encode(@NonNull Object operation) {
    if (operation instanceof Apply) {
      Object val = ((Apply) operation).getVal();
      if (val instanceof String) {
        return tagged(KIND_APPLY_STRING, ((String) val).getBytes(StandardCharsets.UTF_8));
      }
      if (val instanceof byte[]) {
        return tagged(KIND_APPLY_BYTES, (byte[]) val);
      }
      throw new IllegalArgumentException(
        "SimpleStateMachineOpCodec only supports Apply<String|byte[]>; got "
          + (val == null ? "null" : val.getClass().getName()));
    }
    if (operation instanceof QueryLast) {
      return ByteString.copyFrom(new byte[] { KIND_QUERY_LAST });
    }
    if (operation instanceof QueryAll) {
      return ByteString.copyFrom(new byte[] { KIND_QUERY_ALL });
    }
    if (operation instanceof NewTermOp) {
      return ByteString.copyFrom(new byte[] { KIND_NEW_TERM });
    }
    if (operation instanceof Map) {
      return encodeSnapshotChunk((Map<?, ?>) operation);
    }
    throw new IllegalArgumentException(
      "SimpleStateMachineOpCodec does not handle " + operation.getClass().getName());
  }

  @NonNull
  @Override
  public Object decode(int typeId, @NonNull ByteString payload) {
    if (typeId != TYPE_ID) {
      throw new IllegalArgumentException("unexpected typeId: " + typeId);
    }
    if (payload.isEmpty()) {
      throw new IllegalArgumentException("empty payload");
    }
    byte kind = payload.byteAt(0);
    switch (kind) {
      case KIND_APPLY_STRING:
        return new Apply(new String(readLengthPrefixed(payload, 1), StandardCharsets.UTF_8));
      case KIND_APPLY_BYTES:
        return new Apply(readLengthPrefixed(payload, 1));
      case KIND_QUERY_LAST:
        return new QueryLast();
      case KIND_QUERY_ALL:
        return new QueryAll();
      case KIND_NEW_TERM:
        return new NewTermOp();
      case KIND_SNAPSHOT_CHUNK:
        return decodeSnapshotChunk(payload);
      default:
        throw new IllegalArgumentException(
          "unknown op kind: 0x" + Integer.toHexString(kind & 0xFF));
    }
  }

  private static ByteString encodeSnapshotChunk(Map<?, ?> chunk) {
    int total = 1 + 4;
    for (Map.Entry<?, ?> e : chunk.entrySet()) {
      if (!(e.getKey() instanceof Long)) {
        throw new IllegalArgumentException("snapshot chunk key must be Long; got "
          + (e.getKey() == null ? "null" : e.getKey().getClass().getName()));
      }
      Object v = e.getValue();
      int valueLen;
      if (v instanceof String) {
        valueLen = ((String) v).getBytes(StandardCharsets.UTF_8).length;
      } else if (v instanceof byte[]) {
        valueLen = ((byte[]) v).length;
      } else {
        throw new IllegalArgumentException("snapshot chunk value must be String|byte[]; got "
          + (v == null ? "null" : v.getClass().getName()));
      }
      total += 8 + 1 + 4 + valueLen;
    }
    byte[] out = new byte[total];
    int p = 0;
    out[p++] = KIND_SNAPSHOT_CHUNK;
    int count = chunk.size();
    out[p++] = (byte) ((count >>> 24) & 0xFF);
    out[p++] = (byte) ((count >>> 16) & 0xFF);
    out[p++] = (byte) ((count >>> 8) & 0xFF);
    out[p++] = (byte) (count & 0xFF);
    for (Map.Entry<?, ?> e : chunk.entrySet()) {
      long key = (Long) e.getKey();
      out[p++] = (byte) ((key >>> 56) & 0xFF);
      out[p++] = (byte) ((key >>> 48) & 0xFF);
      out[p++] = (byte) ((key >>> 40) & 0xFF);
      out[p++] = (byte) ((key >>> 32) & 0xFF);
      out[p++] = (byte) ((key >>> 24) & 0xFF);
      out[p++] = (byte) ((key >>> 16) & 0xFF);
      out[p++] = (byte) ((key >>> 8) & 0xFF);
      out[p++] = (byte) (key & 0xFF);
      Object v = e.getValue();
      byte[] bytes;
      if (v instanceof String) {
        bytes = ((String) v).getBytes(StandardCharsets.UTF_8);
        out[p++] = VAL_TAG_STRING;
      } else {
        bytes = (byte[]) v;
        out[p++] = VAL_TAG_BYTES;
      }
      out[p++] = (byte) ((bytes.length >>> 24) & 0xFF);
      out[p++] = (byte) ((bytes.length >>> 16) & 0xFF);
      out[p++] = (byte) ((bytes.length >>> 8) & 0xFF);
      out[p++] = (byte) (bytes.length & 0xFF);
      System.arraycopy(bytes, 0, out, p, bytes.length);
      p += bytes.length;
    }
    return ByteString.copyFrom(out);
  }

  private static Map<Long, Object> decodeSnapshotChunk(ByteString payload) {
    if (payload.size() < 5) {
      throw new IllegalArgumentException("truncated snapshot chunk header");
    }
    int p = 1;
    int count = ((payload.byteAt(p) & 0xFF) << 24) | ((payload.byteAt(p + 1) & 0xFF) << 16)
      | ((payload.byteAt(p + 2) & 0xFF) << 8) | (payload.byteAt(p + 3) & 0xFF);
    p += 4;
    if (count < 0) {
      throw new IllegalArgumentException("invalid snapshot chunk entry count: " + count);
    }
    Map<Long, Object> map = new LinkedHashMap<>(count * 2);
    for (int i = 0; i < count; i++) {
      if (payload.size() < p + 8 + 1 + 4) {
        throw new IllegalArgumentException("truncated snapshot chunk entry header");
      }
      long key =
        ((long) (payload.byteAt(p) & 0xFF) << 56) | ((long) (payload.byteAt(p + 1) & 0xFF) << 48)
          | ((long) (payload.byteAt(p + 2) & 0xFF) << 40)
          | ((long) (payload.byteAt(p + 3) & 0xFF) << 32)
          | ((long) (payload.byteAt(p + 4) & 0xFF) << 24)
          | ((long) (payload.byteAt(p + 5) & 0xFF) << 16)
          | ((long) (payload.byteAt(p + 6) & 0xFF) << 8) | ((long) (payload.byteAt(p + 7) & 0xFF));
      p += 8;
      byte valueTag = payload.byteAt(p++);
      int len = ((payload.byteAt(p) & 0xFF) << 24) | ((payload.byteAt(p + 1) & 0xFF) << 16)
        | ((payload.byteAt(p + 2) & 0xFF) << 8) | (payload.byteAt(p + 3) & 0xFF);
      p += 4;
      if (len < 0 || payload.size() < p + len) {
        throw new IllegalArgumentException("invalid snapshot chunk entry value length: " + len);
      }
      byte[] valueBytes = payload.substring(p, p + len).toByteArray();
      p += len;
      Object value;
      if (valueTag == VAL_TAG_STRING) {
        value = new String(valueBytes, StandardCharsets.UTF_8);
      } else if (valueTag == VAL_TAG_BYTES) {
        value = valueBytes;
      } else {
        throw new IllegalArgumentException(
          "unknown snapshot chunk value tag: 0x" + Integer.toHexString(valueTag & 0xFF));
      }
      map.put(key, value);
    }
    return map;
  }

  private static ByteString tagged(byte kind, byte[] payload) {
    byte[] out = new byte[1 + 4 + payload.length];
    out[0] = kind;
    out[1] = (byte) ((payload.length >>> 24) & 0xFF);
    out[2] = (byte) ((payload.length >>> 16) & 0xFF);
    out[3] = (byte) ((payload.length >>> 8) & 0xFF);
    out[4] = (byte) (payload.length & 0xFF);
    System.arraycopy(payload, 0, out, 5, payload.length);
    return ByteString.copyFrom(out);
  }

  private static byte[] readLengthPrefixed(ByteString payload, int offset) {
    if (payload.size() < offset + 4) {
      throw new IllegalArgumentException("truncated length prefix");
    }
    int len = ((payload.byteAt(offset) & 0xFF) << 24) | ((payload.byteAt(offset + 1) & 0xFF) << 16)
      | ((payload.byteAt(offset + 2) & 0xFF) << 8) | (payload.byteAt(offset + 3) & 0xFF);
    if (len < 0 || len > payload.size() - offset - 4) {
      throw new IllegalArgumentException("invalid length: " + len);
    }
    return payload.substring(offset + 4, offset + 4 + len).toByteArray();
  }
}
