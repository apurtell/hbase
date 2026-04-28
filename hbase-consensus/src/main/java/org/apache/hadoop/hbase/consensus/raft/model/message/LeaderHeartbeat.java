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
package org.apache.hadoop.hbase.consensus.raft.model.message;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Lightweight liveness-only heartbeat sent by a leader to its followers.
 * <p>
 * Distinct from {@link AppendEntriesRequest} so it can bypass log verification entirely. Carries
 * just enough state for a follower to (a) confirm that the leader is still active, (b) discover the
 * current term and leader endpoint, and (c) optionally advance its commit index up to what the
 * follower has actually persisted.
 * <p>
 * Used by the heartbeat scheduler subsystem: see
 * {@code org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler}.
 */
@InterfaceAudience.Private
public interface LeaderHeartbeat extends RaftMessage {
  /**
   * Returns the leader's known commit index. Followers must clamp any local commit advance to
   * {@code min(getCommitIndex(), localLastLogOrSnapshotIndex)}. Heartbeats never reach beyond what
   * the follower has actually persisted.
   */
  long getCommitIndex();

  /**
   * Returns whether this heartbeat carries the leader's active-to-quiescent transition notice.
   * <p>
   * Set by the leader exactly once per quiesce transition. A follower that receives a heartbeat
   * with this flag set applies the {@code shouldFollowerQuiesceOnNotify} checks before
   * transitioning to {@code Quiescent}. On any check failure it silently ignores the flag and
   * continues to run its own election timer. After the leader has quiesced it stops emitting
   * per-group heartbeats for this group. Failure detection runs on the top-level keepalive carried
   * by every {@code HeartbeatBatchPB} envelope.
   */
  default boolean isQuiesced() {
    return false;
  }

  /** The builder interface for {@link LeaderHeartbeat}. */
  interface LeaderHeartbeatBuilder extends RaftMessageBuilder<LeaderHeartbeat> {
    @NonNull
    LeaderHeartbeatBuilder setGroupId(@NonNull Object groupId);

    @NonNull
    LeaderHeartbeatBuilder setSender(@NonNull RaftEndpoint sender);

    @NonNull
    LeaderHeartbeatBuilder setTerm(int term);

    @NonNull
    LeaderHeartbeatBuilder setCommitIndex(long commitIndex);

    /** Sets the {@code quiesced} flag on the heartbeat. */
    @NonNull
    default LeaderHeartbeatBuilder setQuiesced(boolean quiesced) {
      return this;
    }
  }
}
