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
package org.apache.hadoop.hbase.consensus.raft.model.impl.message;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse.PreVoteResponseBuilder;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * The default impl of the {@link PreVoteResponse} and {@link PreVoteResponseBuilder} interfaces.
 * When an instance of this class is created, it is in the builder mode and its state is populated.
 * Once all fields are set, the object switches to the DTO mode where it no longer allows mutations.
 * <p>
 * Please note that {@link #build()} does not verify if all fields are set or not. It is up to the
 * user to populate the DTO state via the builder.
 */
@InterfaceAudience.Private
public class DefaultPreVoteResponseOrBuilder implements PreVoteResponse, PreVoteResponseBuilder {
  private Object groupId;
  private RaftEndpoint sender;
  private int term;
  private boolean granted;
  private DefaultPreVoteResponseOrBuilder builder = this;

  @Override
  public Object getGroupId() {
    return groupId;
  }

  @NonNull
  @Override
  public RaftEndpoint getSender() {
    return sender;
  }

  @Override
  public int getTerm() {
    return term;
  }

  @Override
  public boolean isGranted() {
    return granted;
  }

  @NonNull
  @Override
  public PreVoteResponseBuilder setGroupId(@NonNull Object groupId) {
    builder.groupId = groupId;
    return this;
  }

  @NonNull
  @Override
  public PreVoteResponseBuilder setSender(@NonNull RaftEndpoint sender) {
    builder.sender = sender;
    return this;
  }

  @NonNull
  @Override
  public PreVoteResponseBuilder setTerm(int term) {
    builder.term = term;
    return this;
  }

  @NonNull
  @Override
  public PreVoteResponseBuilder setGranted(boolean granted) {
    builder.granted = granted;
    return this;
  }

  @NonNull
  @Override
  public PreVoteResponse build() {
    requireNonNull(builder);
    builder = null;
    return this;
  }

  @Override
  public String toString() {
    String header = builder != null ? "PreVoteResponseBuilder" : "PreVoteResponse";
    return header + "{" + "groupId=" + groupId + ", sender=" + sender + ", term=" + term
      + ", granted=" + granted + '}';
  }
}
