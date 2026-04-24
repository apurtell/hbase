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
package org.apache.hadoop.hbase.consensus.raft.model.impl.groupop;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp.UpdateRaftGroupMembersOpBuilder;

/**
 * The default impl of the {@link UpdateRaftGroupMembersOp} and {@link UpdateRaftGroupMembersOp}
 * interfaces. When an instance of this class is created, it is in the builder mode and its state is
 * populated. Once all fields are set, the object switches to the DTO mode where it no longer allows
 * mutations.
 * <p>
 * Please note that {@link #build()} does not verify if all fields are set or not. It is up to the
 * user to populate the DTO state via the builder.
 */
public class DefaultUpdateRaftGroupMembersOpOrBuilder
  implements UpdateRaftGroupMembersOp, UpdateRaftGroupMembersOpBuilder {
  private Collection<RaftEndpoint> members;
  private Collection<RaftEndpoint> votingMembers;
  private RaftEndpoint endpoint;
  private MembershipChangeMode mode;
  private DefaultUpdateRaftGroupMembersOpOrBuilder builder = this;

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
  public RaftEndpoint getEndpoint() {
    return endpoint;
  }

  @NonNull
  @Override
  public MembershipChangeMode getMode() {
    return mode;
  }

  @NonNull
  @Override
  public UpdateRaftGroupMembersOpBuilder setMembers(@NonNull Collection<RaftEndpoint> members) {
    builder.members = members;
    return this;
  }

  @NonNull
  @Override
  public UpdateRaftGroupMembersOpBuilder
    setVotingMembers(@NonNull Collection<RaftEndpoint> votingMembers) {
    builder.votingMembers = votingMembers;
    return this;
  }

  @NonNull
  @Override
  public UpdateRaftGroupMembersOpBuilder setEndpoint(@NonNull RaftEndpoint endpoint) {
    builder.endpoint = endpoint;
    return this;
  }

  @NonNull
  @Override
  public UpdateRaftGroupMembersOpBuilder setMode(@NonNull MembershipChangeMode mode) {
    builder.mode = mode;
    return this;
  }

  @NonNull
  @Override
  public UpdateRaftGroupMembersOp build() {
    requireNonNull(builder);
    builder = null;
    return this;
  }

  @Override
  public String toString() {
    String header =
      builder != null ? "UpdateRaftGroupMembersOpBuilder" : "UpdateRaftGroupMembersOp";
    return header + "{" + "members=" + members + ", votingMembers=" + votingMembers + ", endpoint="
      + endpoint + ", mode=" + mode + '}';
  }
}
