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
package org.apache.hadoop.hbase.consensus.raft.model.impl.log;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView.RaftGroupMembersViewBuilder;
import org.apache.yetus.audience.InterfaceAudience;

@InterfaceAudience.Private
public class DefaultRaftGroupMembersViewOrBuilder
  implements RaftGroupMembersView, RaftGroupMembersViewBuilder {
  private long logIndex;
  private Collection<RaftEndpoint> members;
  private Collection<RaftEndpoint> votingMembers;
  private DefaultRaftGroupMembersViewOrBuilder builder = this;

  @Override
  public long getLogIndex() {
    return logIndex;
  }

  @NonNull
  @Override
  public Collection<RaftEndpoint> getMembers() {
    return members;
  }

  @NonNull
  @Override
  public Collection<RaftEndpoint> getVotingMembers() {
    return votingMembers;
  }

  @NonNull
  @Override
  public RaftGroupMembersViewBuilder setLogIndex(long logIndex) {
    builder.logIndex = logIndex;
    return this;
  }

  @NonNull
  @Override
  public RaftGroupMembersViewBuilder setMembers(@NonNull Collection<RaftEndpoint> members) {
    builder.members = members;
    return this;
  }

  @NonNull
  @Override
  public RaftGroupMembersViewBuilder
    setVotingMembers(@NonNull Collection<RaftEndpoint> votingMembers) {
    builder.votingMembers = votingMembers;
    return this;
  }

  @NonNull
  @Override
  public RaftGroupMembersView build() {
    requireNonNull(builder);
    builder = null;
    return this;
  }

  @Override
  public String toString() {
    String header = builder != null ? "RaftGroupMembersViewBuilder" : "RaftGroupMembersView";
    return header + "{" + "logIndex=" + logIndex + ", members=" + members + ", votingMembers="
      + votingMembers + '}';
  }
}
