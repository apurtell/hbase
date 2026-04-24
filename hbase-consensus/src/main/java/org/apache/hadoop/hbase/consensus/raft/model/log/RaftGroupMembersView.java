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
package org.apache.hadoop.hbase.consensus.raft.model.log;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModel;

/**
 * Represents the member list of a Raft group with an index identifying on which log index the given
 * member list is appended to the Raft log.
 */
public interface RaftGroupMembersView extends RaftModel {
  /**
   * Returns the Raft log index that contains this Raft group member list.
   * @return the Raft log index that contains this Raft group member list
   */
  long getLogIndex();

  /**
   * Returns the member list of the Raft group.
   * @return the member list of the Raft group
   */
  @NonNull
  Collection<RaftEndpoint> getMembers();

  /**
   * Returns voting members in the Raft group member list.
   * @return voting members in the Raft group member list
   */
  @NonNull
  Collection<RaftEndpoint> getVotingMembers();

  interface RaftGroupMembersViewBuilder {
    @NonNull
    RaftGroupMembersViewBuilder setLogIndex(long logIndex);

    @NonNull
    RaftGroupMembersViewBuilder setMembers(@NonNull Collection<RaftEndpoint> members);

    @NonNull
    RaftGroupMembersViewBuilder setVotingMembers(@NonNull Collection<RaftEndpoint> votingMembers);

    @NonNull
    RaftGroupMembersView build();
  }
}
