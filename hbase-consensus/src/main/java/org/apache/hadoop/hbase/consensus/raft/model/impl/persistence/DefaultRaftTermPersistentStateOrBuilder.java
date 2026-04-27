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
package org.apache.hadoop.hbase.consensus.raft.model.impl.persistence;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState.RaftTermPersistentStateBuilder;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * The default impl of the {@link RaftTermPersistentState} and
 * {@link RaftTermPersistentStateBuilder} interfaces. When an instance of this class is created, it
 * is in the builder mode and its state is populated. Once all fields are set, the object switches
 * to the DTO mode where it no longer allows mutations.
 * <p>
 * Please note that {@link #build()} does not verify if all fields are set or not. It is up to the
 * user to populate the DTO state via the builder.
 */
@InterfaceAudience.Private
public class DefaultRaftTermPersistentStateOrBuilder
  implements RaftTermPersistentState, RaftTermPersistentStateBuilder {
  private int term;
  private RaftEndpoint votedFor;
  private DefaultRaftTermPersistentStateOrBuilder builder = this;

  @Override
  public int getTerm() {
    return term;
  }

  @Override
  @Nullable
  public RaftEndpoint getVotedFor() {
    return votedFor;
  }

  @Override
  @NonNull
  public RaftTermPersistentStateBuilder setTerm(int term) {
    builder.term = term;
    return this;
  }

  @Override
  @NonNull
  public RaftTermPersistentStateBuilder setVotedFor(@Nullable RaftEndpoint votedFor) {
    builder.votedFor = votedFor;
    return this;
  }

  @Override
  @NonNull
  public RaftTermPersistentState build() {
    requireNonNull(builder);
    builder = null;
    return this;
  }

  @Override
  public String toString() {
    String header = builder != null
      ? "DefaultRaftTermPersistentStateOrBuilder"
      : "DefaultRaftTermPersistentState";
    return header + "{" + "term=" + term + ", votedFor=" + votedFor + '}';
  }
}
