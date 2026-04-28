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
package org.apache.hadoop.hbase.consensus.handler.server;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Operational surface for adding, removing, and inspecting Raft groups hosted on a
 * {@link ConsensusServer}.
 * <p>
 * {@link ConsensusServer} implements this interface; this is the type that operational and test
 * code should hold instead of the concrete server class.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.COPROC })
@InterfaceStability.Evolving
public interface GroupManager {

  /**
   * Adds the named group to the local server. The {@code groupId} must be unique across this
   * server's lifetime as long as the group is registered. Adding a group that already exists is a
   * no-op and returns the existing handle.
   * <p>
   * The supplied {@code spi} is the application-side bridge for this group. Its lifecycle is
   * decoupled from the {@link ConsensusServer}. The server does not call {@code close} on the SPI
   * on {@link #removeGroup} or {@link ConsensusServer#stop()}.
   * @param groupId        unique group id
   * @param initialMembers initial voting members for a fresh group; ignored when the on-disk store
   *                       carries a {@code RestoredRaftState} for this group
   * @param spi            per-group application bridge
   * @return a handle wrapping the registered {@code RaftNode}
   * @throws MaxGroupsExceededException if the server already hosts
   *                                    {@link ConsensusServerConfig#MAX_GROUPS_KEY} groups
   * @throws IllegalStateException      if the server is not started
   * @throws IOException                if persistent state for the group cannot be opened
   */
  @NonNull
  GroupHandle addGroup(@NonNull Object groupId, @NonNull Collection<RaftEndpoint> initialMembers,
    @NonNull ConsensusSpi spi) throws IOException;

  /**
   * Removes the named group. Returns {@code false} if the group is not registered. The underlying
   * {@link org.apache.hadoop.hbase.consensus.raft.RaftNode} is terminated, unregistered from
   * transport and heartbeat scheduler, and the group's executor is released.
   */
  boolean removeGroup(@NonNull Object groupId);

  /** Returns the registered handle for {@code groupId}, or {@code null} if it is not registered. */
  @Nullable
  GroupHandle getGroup(@NonNull Object groupId);

  /** Returns whether {@code groupId} is currently registered. */
  boolean hasGroup(@NonNull Object groupId);

  /** Returns the number of currently registered groups. */
  int groupCount();

  /**
   * Returns a snapshot iterable of the currently registered groups. The snapshot is stable and may
   * be iterated even while groups are being added or removed concurrently. Mutations to the
   * returned collection have no effect on the manager.
   */
  @NonNull
  Iterable<GroupHandle> groups();

  /**
   * Asks the local Raft node for {@code groupId} to transfer leadership to {@code target}. The
   * returned future completes successfully when the transfer succeeds, or exceptionally if the
   * local node is not leader, the target is not a known voter, or the transfer fails.
   * @throws IllegalArgumentException if {@code groupId} is not registered
   */
  @NonNull
  CompletableFuture<Ordered<Object>> transferLeadership(@NonNull Object groupId,
    @NonNull RaftEndpoint target);

  /**
   * Returns the latest {@link RaftNodeReport} for {@code groupId}.
   * @throws IllegalArgumentException if {@code groupId} is not registered
   */
  @NonNull
  CompletableFuture<Ordered<RaftNodeReport>> status(@NonNull Object groupId);
}
