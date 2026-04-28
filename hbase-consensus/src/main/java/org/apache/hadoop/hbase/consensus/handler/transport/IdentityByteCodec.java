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
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.com.google.protobuf.UnsafeByteOperations;

/**
 * Default user {@link OperationCodec}: identity over {@code byte[]}.
 * <p>
 * Reserved type id {@code 2}. Use this when callers replicate raw byte payloads (e.g. tests, or
 * when the operation has been pre-serialised by the caller's state machine layer).
 * <p>
 * {@link #encode(Object)} uses {@link UnsafeByteOperations#unsafeWrap(byte[])} on the supplied byte
 * array. Callers MUST treat the byte array passed to {@code encode} as immutable for the lifetime
 * of the encoded {@link ByteString}. State-machine layers that cannot guarantee this should clone
 * the buffer before handing it to {@code encode}.
 */
@InterfaceAudience.Private
public final class IdentityByteCodec implements OperationCodec {
  /** Reserved discriminator for byte[] identity encoding. */
  public static final int TYPE_ID = 2;

  @Override
  public int typeId(@NonNull Object operation) {
    if (!(operation instanceof byte[])) {
      throw new IllegalArgumentException(
        "IdentityByteCodec only handles byte[] but got " + operation.getClass());
    }
    return TYPE_ID;
  }

  @Override
  @NonNull
  public ByteString encode(@NonNull Object operation) {
    if (!(operation instanceof byte[])) {
      throw new IllegalArgumentException(
        "IdentityByteCodec only handles byte[] but got " + operation.getClass());
    }
    // See class javadoc: zero-copy wrap; caller relinquishes mutation rights on the byte[].
    return UnsafeByteOperations.unsafeWrap((byte[]) operation);
  }

  @Override
  @NonNull
  public Object decode(int typeId, @NonNull ByteString payload) {
    if (typeId != TYPE_ID) {
      throw new IllegalArgumentException(
        "IdentityByteCodec asked to decode unknown typeId " + typeId);
    }
    return payload.toByteArray();
  }

  @Override
  public boolean handles(@NonNull Object operation) {
    return operation instanceof byte[];
  }

  @Override
  public boolean handlesTypeId(int typeId) {
    return typeId == TYPE_ID;
  }
}
