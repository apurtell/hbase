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
package org.apache.hadoop.hbase.consensus.handler.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Codec SPI for the consensus durable log.
 * <p>
 * Pluggable on the {@link DurableLogStore} constructor.
 * <p>
 * Implementations should be stateless and thread-safe. The same instance is shared across the
 * producing threads of all groups multiplexed onto a single {@link DurableLogStore}.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.TOOLS })
public interface LogStoreSerializer {
  Serializer<RaftGroupMembersView> raftGroupMembersViewSerializer();

  Serializer<RaftEndpoint> raftEndpointSerializer();

  Serializer<LogEntry> logEntrySerializer();

  Serializer<SnapshotChunk> snapshotChunkSerializer();

  Serializer<RaftEndpointPersistentState> raftEndpointPersistentStateSerializer();

  Serializer<RaftTermPersistentState> raftTermPersistentStateSerializer();

  interface Serializer<T> {
    @NonNull
    byte[] serialize(@NonNull T element);

    @NonNull
    T deserialize(@NonNull byte[] element);
  }
}
