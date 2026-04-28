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
package org.apache.hadoop.hbase.consensus.raft.transport;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Immutable carrier for one bulk heartbeat emission by the per-server timing wheel.
 * <p>
 * One frame per (sender, peer) per tick aggregates one entry per local leader group whose committed
 * membership includes the target peer. The envelope-level {@code sender} / {@code epoch} /
 * {@code tick} fields back the per-RS keepalive that quiescent followers consult.
 */
@InterfaceAudience.Private
public final class BulkHeartbeatFrame {

  private final RaftEndpoint sender;
  private final long epoch;
  private final long tick;
  private final List<LeaderHeartbeat> entries;

  public BulkHeartbeatFrame(@NonNull RaftEndpoint sender, long epoch, long tick,
    @NonNull List<LeaderHeartbeat> entries) {
    this.sender = sender;
    this.epoch = epoch;
    this.tick = tick;
    this.entries = Collections.unmodifiableList(entries);
  }

  @NonNull
  public RaftEndpoint getSender() {
    return sender;
  }

  public long getEpoch() {
    return epoch;
  }

  public long getTick() {
    return tick;
  }

  /** Returns the per-group heartbeat entries carried by this envelope. */
  @NonNull
  public List<LeaderHeartbeat> getEntries() {
    return entries;
  }
}
