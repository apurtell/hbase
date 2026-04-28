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
import org.apache.hadoop.hbase.consensus.raft.EndpointId;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Thin {@link RaftEndpoint} reconstructed from the wire. Wraps a single cached {@link EndpointId}
 * which carries the id bytes, the cached {@link ByteString} view, and a pre-built
 * {@code RaftEndpointPB}. Equality is delegated to the cached {@link EndpointId}, so wire endpoints
 * compare equal to each other when they share an id, which is sufficient for the inbound
 * dispatcher's keying.
 */
@InterfaceAudience.Private
public final class WireRaftEndpoint implements RaftEndpoint {
  private final EndpointId endpointId;

  private WireRaftEndpoint(EndpointId endpointId) {
    this.endpointId = endpointId;
  }

  /** Builds a {@link WireRaftEndpoint} from the bytes carried in {@code RaftEndpointPB}. */
  @NonNull
  public static WireRaftEndpoint fromBytes(@NonNull ByteString id) {
    return new WireRaftEndpoint(EndpointId.fromWire(id));
  }

  /** Builds a {@link WireRaftEndpoint} from a copy of the supplied id bytes. */
  @NonNull
  public static WireRaftEndpoint fromBytes(@NonNull byte[] id) {
    return new WireRaftEndpoint(EndpointId.of(id));
  }

  /**
   * Encodes the {@link RaftEndpoint#getId()} as the bytes that go into {@code RaftEndpointPB.id}.
   */
  @NonNull
  public static ByteString toBytes(@NonNull RaftEndpoint endpoint) {
    return endpoint.endpointId().byteString();
  }

  @Override
  @NonNull
  public Object getId() {
    return endpointId;
  }

  @Override
  @NonNull
  public EndpointId endpointId() {
    return endpointId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RaftEndpoint)) {
      return false;
    }
    return endpointId.equals(((RaftEndpoint) o).endpointId());
  }

  @Override
  public int hashCode() {
    return endpointId.hashCode();
  }

  @Override
  public String toString() {
    return "WireRaftEndpoint{id=" + endpointId + '}';
  }
}
