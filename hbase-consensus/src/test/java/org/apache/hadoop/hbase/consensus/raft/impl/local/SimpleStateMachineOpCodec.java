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
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * {@link OperationCodec} for the operation types produced by {@link SimpleStateMachine}.
 * <p>
 * This codec is intended for end-to-end transport / Raft integration tests where {@code Apply}
 * payloads need to be serialised across a real network. Discriminator ids start at the first
 * caller-defined slot ({@link OperationCodec#FIRST_USER_TYPE_ID}).
 * <ul>
 * <li>{@code 1024} — {@link SimpleStateMachine.Apply}, payload is the raw {@code byte[]} value</li>
 * <li>{@code 1025} — {@link SimpleStateMachine.QueryLast}, empty payload</li>
 * <li>{@code 1026} — {@link SimpleStateMachine.QueryAll}, empty payload</li>
 * <li>{@code 1027} — {@link SimpleStateMachine.NewTermOp}, empty payload</li>
 * </ul>
 * <p>
 * Only {@code byte[]} {@code Apply} values are supported; other value types must be serialised by
 * the caller before being wrapped in {@link SimpleStateMachine#applyValue(Object)}.
 */
@InterfaceAudience.Private
public final class SimpleStateMachineOpCodec implements OperationCodec {
  /** Discriminator id for {@link SimpleStateMachine.Apply}. */
  public static final int APPLY_TYPE_ID = FIRST_USER_TYPE_ID;
  /** Discriminator id for {@link SimpleStateMachine.QueryLast}. */
  public static final int QUERY_LAST_TYPE_ID = FIRST_USER_TYPE_ID + 1;
  /** Discriminator id for {@link SimpleStateMachine.QueryAll}. */
  public static final int QUERY_ALL_TYPE_ID = FIRST_USER_TYPE_ID + 2;
  /** Discriminator id for {@link SimpleStateMachine.NewTermOp}. */
  public static final int NEW_TERM_TYPE_ID = FIRST_USER_TYPE_ID + 3;

  @Override
  public int typeId(@NonNull Object operation) {
    if (operation instanceof SimpleStateMachine.Apply) {
      return APPLY_TYPE_ID;
    } else if (operation instanceof SimpleStateMachine.QueryLast) {
      return QUERY_LAST_TYPE_ID;
    } else if (operation instanceof SimpleStateMachine.QueryAll) {
      return QUERY_ALL_TYPE_ID;
    } else if (operation instanceof SimpleStateMachine.NewTermOp) {
      return NEW_TERM_TYPE_ID;
    }
    throw new IllegalArgumentException(
      "SimpleStateMachineOpCodec does not handle " + operation.getClass());
  }

  @Override
  @NonNull
  public ByteString encode(@NonNull Object operation) {
    if (operation instanceof SimpleStateMachine.Apply) {
      Object val = ((SimpleStateMachine.Apply) operation).getVal();
      if (!(val instanceof byte[])) {
        throw new IllegalArgumentException(
          "SimpleStateMachineOpCodec only supports byte[] Apply values but got "
            + (val == null ? "null" : val.getClass()));
      }
      return ByteString.copyFrom((byte[]) val);
    } else if (
      operation instanceof SimpleStateMachine.QueryLast
        || operation instanceof SimpleStateMachine.QueryAll
        || operation instanceof SimpleStateMachine.NewTermOp
    ) {
      return ByteString.EMPTY;
    }
    throw new IllegalArgumentException(
      "SimpleStateMachineOpCodec does not handle " + operation.getClass());
  }

  @Override
  @NonNull
  public Object decode(int typeId, @NonNull ByteString payload) {
    switch (typeId) {
      case APPLY_TYPE_ID:
        return SimpleStateMachine.applyValue(payload.toByteArray());
      case QUERY_LAST_TYPE_ID:
        return SimpleStateMachine.queryLastValue();
      case QUERY_ALL_TYPE_ID:
        return SimpleStateMachine.queryAllValues();
      case NEW_TERM_TYPE_ID:
        return new SimpleStateMachine.NewTermOp();
      default:
        throw new IllegalArgumentException(
          "SimpleStateMachineOpCodec asked to decode unknown typeId " + typeId);
    }
  }

  @Override
  public boolean handles(@NonNull Object operation) {
    return operation instanceof SimpleStateMachine.Apply
      || operation instanceof SimpleStateMachine.QueryLast
      || operation instanceof SimpleStateMachine.QueryAll
      || operation instanceof SimpleStateMachine.NewTermOp;
  }

  @Override
  public boolean handlesTypeId(int typeId) {
    return typeId == APPLY_TYPE_ID || typeId == QUERY_LAST_TYPE_ID || typeId == QUERY_ALL_TYPE_ID
      || typeId == NEW_TERM_TYPE_ID;
  }
}
