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
package org.apache.hadoop.hbase.consensus.handler.server.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.hadoop.hbase.consensus.handler.transport.EndpointResolver;
import org.apache.hadoop.hbase.consensus.handler.transport.UnknownEndpointException;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;

/**
 * Trivial {@link EndpointResolver} backed by an in-memory map. Tests update the map after each
 * server's transport binds to its ephemeral port. The resolver's {@code resolve} call sees the live
 * address as soon as it is published.
 */
public final class MapEndpointResolver implements EndpointResolver {

  private final ConcurrentMap<RaftEndpoint, InetSocketAddress> map = new ConcurrentHashMap<>();

  /** Publishes {@code addr} as the resolved address for {@code endpoint}. */
  public void put(@NonNull RaftEndpoint endpoint, @NonNull InetSocketAddress addr) {
    map.put(endpoint, addr);
  }

  /** Removes the published address for {@code endpoint}, if any. */
  public void remove(@NonNull RaftEndpoint endpoint) {
    map.remove(endpoint);
  }

  @NonNull
  @Override
  public InetSocketAddress resolve(@NonNull RaftEndpoint endpoint) throws UnknownEndpointException {
    InetSocketAddress addr = map.get(endpoint);
    if (addr == null) {
      throw new UnknownEndpointException(endpoint);
    }
    return addr;
  }

  /** Number of currently-published endpoint mappings. */
  public int size() {
    return map.size();
  }
}
