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

/**
 * Serializes the opaque operation carried by a
 * {@link org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry} for transport.
 * <p>
 * Each codec is identified by an {@code int} discriminator stored in the {@code LogEntryPB.op_type}
 * field on the wire.
 * <p>
 * Reserved discriminators:
 * <ul>
 * <li>{@code 0} — reserved (do not use)</li>
 * <li>{@code 1} — {@link UpdateRaftGroupMembersOpCodec} (group membership op)</li>
 * <li>{@code 2} — {@link IdentityByteCodec} (identity over {@code byte[]})</li>
 * <li>{@code 3..1023} — reserved for future built-in codecs</li>
 * <li>{@code 1024..} — caller-defined operation types</li>
 * </ul>
 * <p>
 * Implementations must be stateless and thread-safe. The same codec instance is shared by every
 * outbound and inbound thread of a {@link CoalescingTransport}.
 */
@InterfaceAudience.Private
public interface OperationCodec {
  /** First discriminator id available to callers. */
  int FIRST_USER_TYPE_ID = 1024;

  /**
   * @return the discriminator id this codec uses for the given operation
   * @throws IllegalArgumentException if {@link #handles(Object)} returns {@code false}
   */
  int typeId(@NonNull Object operation);

  /**
   * @return the wire encoding of the operation
   * @throws IllegalArgumentException if {@link #handles(Object)} returns {@code false}
   */
  @NonNull
  ByteString encode(@NonNull Object operation);

  /**
   * @return the operation reconstructed from its wire encoding
   * @throws IllegalArgumentException if this codec does not own {@code typeId}
   */
  @NonNull
  Object decode(int typeId, @NonNull ByteString payload);

  /** Returns whether this codec can {@link #encode(Object)} the given operation */
  boolean handles(@NonNull Object operation);

  /** Returns whether this codec can {@link #decode(int, ByteString)} the given discriminator */
  boolean handlesTypeId(int typeId);
}
