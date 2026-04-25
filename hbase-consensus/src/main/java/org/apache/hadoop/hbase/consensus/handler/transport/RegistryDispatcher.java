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
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Default {@link InboundDispatcher}: a thread-safe {@code Object -> RaftNode} registry keyed on
 * {@link RaftNode#getGroupId()}. {@link CoalescingTransport} owns one of these and exposes
 * {@link #discoverNode(RaftNode)} / {@link #undiscoverNode(RaftNode)} on its public surface.
 */
@InterfaceAudience.Private
public final class RegistryDispatcher implements InboundDispatcher {
  private final ConcurrentMap<Object, RaftNode> nodes = new ConcurrentHashMap<>();

  @Nullable
  @Override
  public RaftNode lookup(@NonNull Object groupId) {
    return nodes.get(requireNonNull(groupId));
  }

  /**
   * Registers the given Raft node so that frames addressed to its group id are delivered to it.
   * @throws IllegalArgumentException if a different active Raft node is already registered for the
   *                                  same group id
   */
  public void discoverNode(@NonNull RaftNode node) {
    Object groupId = requireNonNull(node).getGroupId();
    RaftNode existing = nodes.putIfAbsent(groupId, node);
    if (existing != null && existing != node && existing.getStatus() != RaftNodeStatus.TERMINATED) {
      throw new IllegalArgumentException(
        "RegistryDispatcher already has an active RaftNode for group " + groupId);
    }
    if (existing != null && existing != node) {
      nodes.put(groupId, node);
    }
  }

  /**
   * Removes the given Raft node from the registry. Frames addressed to its group id will be
   * silently dropped after this call.
   */
  public void undiscoverNode(@NonNull RaftNode node) {
    nodes.remove(requireNonNull(node).getGroupId(), node);
  }

  /** Visible for tests. */
  int size() {
    return nodes.size();
  }
}
