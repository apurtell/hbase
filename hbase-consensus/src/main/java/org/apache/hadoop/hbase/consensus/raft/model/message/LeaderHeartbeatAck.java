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

/**
 * Acknowledgement of a {@link LeaderHeartbeat}.
 * <p>
 * Carries {@code (groupId, sender, term)} from the base {@link RaftMessage} plus the follower's
 * current {@code lastVerifiedLogIndex}: the highest local log index that has been proven to match
 * the current leader's log (via successful {@link AppendEntriesRequest} verification or snapshot
 * install).
 * <p>
 * The leader uses {@code term} and the sender's voting-membership status to refresh the
 * follower-state response timestamp and recompute the leader lease. It uses
 * {@code lastVerifiedLogIndex} to detect when a follower's runtime verification state lags its
 * persisted log (e.g. after a follower restart where the leader's {@code matchIndex} still
 * references pre-restart entries but the follower has reset {@code lastVerifiedLogIndex} on
 * recovery). When the follower lags, the leader sends a verifying empty
 * {@link AppendEntriesRequest} so commit index can resume advancing on the follower.
 * @see LeaderHeartbeat
 */
public interface LeaderHeartbeatAck extends RaftMessage {
  /**
   * The follower's {@code lastVerifiedLogIndex} at the time the ack was sent.
   */
  long getLastVerifiedLogIndex();

  /**
   * The builder interface for {@link LeaderHeartbeatAck}.
   */
  interface LeaderHeartbeatAckBuilder extends RaftMessageBuilder<LeaderHeartbeatAck> {
    @NonNull
    LeaderHeartbeatAckBuilder setGroupId(@NonNull Object groupId);

    @NonNull
    LeaderHeartbeatAckBuilder setSender(@NonNull RaftEndpoint sender);

    @NonNull
    LeaderHeartbeatAckBuilder setTerm(int term);

    @NonNull
    LeaderHeartbeatAckBuilder setLastVerifiedLogIndex(long lastVerifiedLogIndex);
  }
}
