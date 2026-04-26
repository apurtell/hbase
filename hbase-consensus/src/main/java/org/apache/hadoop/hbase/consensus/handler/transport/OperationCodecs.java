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

/** Static helpers for composing {@link OperationCodec}s. */
@InterfaceAudience.Private
public final class OperationCodecs {
  private OperationCodecs() {
  }

  /**
   * Returns the standard wiring. Built-in {@link UpdateRaftGroupMembersOpCodec} plus
   * {@link IdentityByteCodec} for raw byte payloads. Suitable for tests and any caller whose user
   * operations are already serialised to {@code byte[]}.
   */
  @NonNull
  public static OperationCodec defaultCodecs() {
    return composite(new UpdateRaftGroupMembersOpCodec(), new IdentityByteCodec());
  }

  /**
   * Returns a codec that dispatches {@link OperationCodec#encode}/{@link OperationCodec#typeId} to
   * the first delegate whose {@link OperationCodec#handles(Object)} returns {@code true}, and
   * dispatches {@link OperationCodec#decode} to the first delegate whose
   * {@link OperationCodec#handlesTypeId(int)} returns {@code true}.
   * @throws IllegalArgumentException if no codec is provided
   */
  @NonNull
  public static OperationCodec composite(@NonNull OperationCodec... codecs) {
    if (codecs.length == 0) {
      throw new IllegalArgumentException("composite requires at least one codec");
    }
    final OperationCodec[] snapshot = codecs.clone();
    return new OperationCodec() {
      @Override
      public int typeId(@NonNull Object operation) {
        return resolveByOperation(operation).typeId(operation);
      }

      @Override
      @NonNull
      public ByteString encode(@NonNull Object operation) {
        return resolveByOperation(operation).encode(operation);
      }

      @Override
      @NonNull
      public Object decode(int typeId, @NonNull ByteString payload) {
        return resolveByTypeId(typeId).decode(typeId, payload);
      }

      @Override
      public boolean handles(@NonNull Object operation) {
        for (OperationCodec c : snapshot) {
          if (c.handles(operation)) {
            return true;
          }
        }
        return false;
      }

      @Override
      public boolean handlesTypeId(int typeId) {
        for (OperationCodec c : snapshot) {
          if (c.handlesTypeId(typeId)) {
            return true;
          }
        }
        return false;
      }

      private OperationCodec resolveByOperation(Object operation) {
        for (OperationCodec c : snapshot) {
          if (c.handles(operation)) {
            return c;
          }
        }
        throw new IllegalArgumentException(
          "No registered OperationCodec handles " + operation.getClass());
      }

      private OperationCodec resolveByTypeId(int typeId) {
        for (OperationCodec c : snapshot) {
          if (c.handlesTypeId(typeId)) {
            return c;
          }
        }
        throw new IllegalArgumentException("No registered OperationCodec handles typeId " + typeId);
      }
    };
  }
}
