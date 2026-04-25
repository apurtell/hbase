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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Thin {@link RaftEndpoint} reconstructed from the wire. The id is the UTF-8 bytes of the
 * producer's {@code String.valueOf(endpoint.getId())}; equality is over those bytes so wire
 * endpoints compare equal to each other when they share an id, which is sufficient for the inbound
 * dispatcher's keying.
 */
@InterfaceAudience.Private
public final class WireRaftEndpoint implements RaftEndpoint {
  private final byte[] id;
  private final String stringId;

  private WireRaftEndpoint(byte[] id) {
    this.id = id;
    this.stringId = new String(id, StandardCharsets.UTF_8);
  }

  /** Builds a {@link WireRaftEndpoint} from the bytes carried in {@code RaftEndpointPB}. */
  @NonNull
  public static WireRaftEndpoint fromBytes(@NonNull ByteString id) {
    return new WireRaftEndpoint(id.toByteArray());
  }

  /** Builds a {@link WireRaftEndpoint} from the bytes carried in {@code RaftEndpointPB}. */
  @NonNull
  public static WireRaftEndpoint fromBytes(@NonNull byte[] id) {
    return new WireRaftEndpoint(id.clone());
  }

  /**
   * Encodes the {@link RaftEndpoint#getId()} as the bytes that go into {@code RaftEndpointPB.id}.
   */
  @NonNull
  public static ByteString toBytes(@NonNull RaftEndpoint endpoint) {
    return ByteString.copyFromUtf8(String.valueOf(endpoint.getId()));
  }

  @Override
  @NonNull
  public Object getId() {
    return stringId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RaftEndpoint)) {
      return false;
    }
    if (o instanceof WireRaftEndpoint) {
      return Arrays.equals(id, ((WireRaftEndpoint) o).id);
    }
    return stringId.equals(String.valueOf(((RaftEndpoint) o).getId()));
  }

  @Override
  public int hashCode() {
    return stringId.hashCode();
  }

  @Override
  public String toString() {
    return "WireRaftEndpoint{id=" + stringId + '}';
  }
}
