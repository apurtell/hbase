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
import org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Opaque, immutable handle returned by {@link GroupManager#addGroup} and
 * {@link GroupManager#getGroup}.
 * <p>
 * {@link #getRaftNode()} is the canonical entry point for replicate / query / membership-change
 * operations against the group. {@link #getSpi()} is the per-group SPI registered with the server,
 * useful for tests that want to inspect committed entries.
 * <p>
 * Handles are stable for the lifetime of the underlying group and become inert (the
 * {@link RaftNode} is terminated and detached from the server) once
 * {@link GroupManager#removeGroup} returns. Test code is responsible for not holding handles across
 * removal.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public final class GroupHandle {

  private final Object groupId;
  private final RaftNode raftNode;
  private final ConsensusSpi spi;

  GroupHandle(@NonNull Object groupId, @NonNull RaftNode raftNode, @NonNull ConsensusSpi spi) {
    this.groupId = groupId;
    this.raftNode = raftNode;
    this.spi = spi;
  }

  /** Returns the group id this handle was registered under. */
  @NonNull
  public Object getGroupId() {
    return groupId;
  }

  /** Returns the local {@link RaftNode} for this group. */
  @NonNull
  public RaftNode getRaftNode() {
    return raftNode;
  }

  /** Returns the {@link ConsensusSpi} registered for this group. */
  @NonNull
  public ConsensusSpi getSpi() {
    return spi;
  }
}
