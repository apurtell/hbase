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

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.hadoop.hbase.consensus.raft.GroupId;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

/**
 * Looks up the local {@link RaftNode} that owns the given Raft group id when an inbound frame
 * arrives. The lookup key is the wire-bytes view ({@link ByteString}) carried in the parsed
 * protobuf message so the inbound path does not need to allocate a {@link String} or
 * {@link GroupId} per message.
 * <p>
 * A thread-safe registry from on-wire group-id bytes ({@link ByteString}) to the local
 * {@link RaftNode}. {@link CoalescingTransport} owns one of these and exposes
 * {@link #discoverNode(RaftNode)} / {@link #undiscoverNode(RaftNode)} on its public surface.
 * <p>
 * The map is keyed on {@link ByteString} so that the inbound path can dispatch directly with the
 * wire bytes carried in the parsed protobuf message ({@code pb.getGroupId()}) without having to
 * decode a {@link String} or allocate a {@link GroupId} per message. The registered key is sourced
 * from the registered node's {@link GroupId#byteString()}; {@link ByteString#equals(Object)} is
 * content-based, so a wire-derived {@link ByteString} matches the cached one.
 */
@InterfaceAudience.Private
public final class RegistryDispatcher {
  private final ConcurrentMap<ByteString, RaftNode> nodes = new ConcurrentHashMap<>();

  /**
   * Returns the local RaftNode for the given on-wire group id bytes, or {@code null} if no node is
   * registered for those bytes.
   */
  @Nullable
  public RaftNode lookup(@NonNull ByteString groupIdBytes) {
    return nodes.get(requireNonNull(groupIdBytes));
  }

  /**
   * Registers the given Raft node so that frames addressed to its group id are delivered to it.
   * @throws IllegalArgumentException if a different active Raft node is already registered for the
   *                                  same group id
   * @throws IllegalArgumentException if the node's {@link RaftNode#getGroupId()} is not a
   *                                  {@link GroupId}; the registry needs the cached
   *                                  {@link ByteString} view to key lookups against the wire
   */
  public void discoverNode(@NonNull RaftNode node) {
    Object groupId = requireNonNull(node).getGroupId();
    if (!(groupId instanceof GroupId)) {
      throw new IllegalArgumentException("RaftNode group id must be a GroupId; got "
        + groupId.getClass().getName() + " (" + groupId + ")");
    }
    ByteString key = ((GroupId) groupId).byteString();
    RaftNode existing = nodes.putIfAbsent(key, node);
    if (existing != null && existing != node && existing.getStatus() != RaftNodeStatus.TERMINATED) {
      throw new IllegalArgumentException(
        "RegistryDispatcher already has an active RaftNode for group " + groupId);
    }
    if (existing != null && existing != node) {
      nodes.put(key, node);
    }
  }

  /**
   * Removes the given Raft node from the registry. Frames addressed to its group id will be
   * silently dropped after this call.
   */
  public void undiscoverNode(@NonNull RaftNode node) {
    Object groupId = requireNonNull(node).getGroupId();
    if (groupId instanceof GroupId) {
      nodes.remove(((GroupId) groupId).byteString(), node);
    }
  }

  /** Visible for tests. */
  int size() {
    return nodes.size();
  }
}
