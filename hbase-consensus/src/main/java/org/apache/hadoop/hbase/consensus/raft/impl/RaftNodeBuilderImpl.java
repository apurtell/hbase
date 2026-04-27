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
package org.apache.hadoop.hbase.consensus.raft.impl;

import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hbase.consensus.raft.RaftConfig.DEFAULT_RAFT_CONFIG;
import static org.apache.hadoop.hbase.consensus.raft.report.RaftGroupMembers.MAX_LEARNER_COUNT;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Random;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNode.RaftNodeBuilder;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.executor.impl.DefaultRaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.impl.DefaultHeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultRaftGroupMembersViewOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.persistence.NopRaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReportListener;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.yetus.audience.InterfaceAudience;

/** Builder for {@link RaftNode}. */
@InterfaceAudience.Private
public class RaftNodeBuilderImpl implements RaftNodeBuilder {
  private Object groupId;
  private RaftEndpoint localEndpoint;
  private Collection<RaftEndpoint> initialGroupMembers;
  private Collection<RaftEndpoint> initialVotingGroupMembers;
  private RestoredRaftState restoredState;
  private RaftConfig config = DEFAULT_RAFT_CONFIG;
  private RaftNodeExecutor executor = new DefaultRaftNodeExecutor();
  private Transport transport;
  private StateMachine stateMachine;
  private RaftNodeReportListener listener = report -> {
  };
  private RaftStore store = new NopRaftStore();
  private RaftModelFactory modelFactory = new DefaultRaftModelFactory();
  private Random random = new Random();
  private Clock clock = Clock.systemUTC();
  private HeartbeatScheduler heartbeatScheduler = DefaultHeartbeatScheduler.INSTANCE;
  private boolean done;

  @NonNull
  @Override
  public RaftNodeBuilder setGroupId(@NonNull Object groupId) {
    this.groupId = groupId;
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setLocalEndpoint(@NonNull RaftEndpoint localEndpoint) {
    if (this.restoredState != null) {
      throw new IllegalStateException(
        "Local member cannot be set when restored Raft state is provided!");
    }
    this.localEndpoint = requireNonNull(localEndpoint);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder
    setInitialGroupMembers(@NonNull Collection<RaftEndpoint> initialGroupMembers) {
    if (this.restoredState != null) {
      throw new IllegalStateException(
        "Initial group members cannot be set when restored Raft state is provided!");
    }
    this.initialGroupMembers = new LinkedHashSet<>(requireNonNull(initialGroupMembers));
    this.initialVotingGroupMembers = new LinkedHashSet<>(this.initialGroupMembers);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setInitialGroupMembers(
    @NonNull Collection<RaftEndpoint> initialGroupMembers,
    @NonNull Collection<RaftEndpoint> initialVotingGroupMembers) {
    this.initialGroupMembers = new LinkedHashSet<>(requireNonNull(initialGroupMembers));
    Collection<RaftEndpoint> votingMembers = new LinkedHashSet<>(initialGroupMembers);
    votingMembers.retainAll(requireNonNull(initialVotingGroupMembers));
    this.initialVotingGroupMembers = votingMembers;
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setRestoredState(@NonNull RestoredRaftState restoredState) {
    if (this.localEndpoint != null || this.initialGroupMembers != null) {
      throw new IllegalStateException(
        "Restored state cannot be set when either local member or initial group members is provided!");
    }
    this.restoredState = requireNonNull(restoredState);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setConfig(@NonNull RaftConfig config) {
    this.config = requireNonNull(config);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setExecutor(@NonNull RaftNodeExecutor executor) {
    this.executor = requireNonNull(executor);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setTransport(@NonNull Transport transport) {
    this.transport = requireNonNull(transport);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setStateMachine(@NonNull StateMachine stateMachine) {
    this.stateMachine = requireNonNull(stateMachine);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setStore(@NonNull RaftStore store) {
    this.store = requireNonNull(store);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setModelFactory(@NonNull RaftModelFactory modelFactory) {
    this.modelFactory = requireNonNull(modelFactory);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setRaftNodeReportListener(@NonNull RaftNodeReportListener listener) {
    this.listener = requireNonNull(listener);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setRandom(@NonNull Random random) {
    this.random = requireNonNull(random);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setClock(@NonNull Clock clock) {
    this.clock = requireNonNull(clock);
    return this;
  }

  @NonNull
  @Override
  public RaftNodeBuilder setHeartbeatScheduler(@NonNull HeartbeatScheduler heartbeatScheduler) {
    this.heartbeatScheduler = requireNonNull(heartbeatScheduler);
    return this;
  }

  @NonNull
  @Override
  public RaftNode build() {
    if (done) {
      throw new IllegalStateException("Raft node is already built!");
    }
    if (
      !((localEndpoint != null && initialGroupMembers != null && !initialGroupMembers.isEmpty()
        && !initialVotingGroupMembers.isEmpty()
        && initialGroupMembers.size() - initialVotingGroupMembers.size() <= MAX_LEARNER_COUNT)
        || restoredState != null)
    ) {
      String message =
        "Either local Raft endpoint and initial Raft group members, or restored state must be provided! In addition, "
          + "there can be at most " + MAX_LEARNER_COUNT + " " + RaftRole.LEARNER + "s";
      throw new IllegalStateException(message);
    }
    done = true;
    if (restoredState != null) {
      return new RaftNodeImpl(groupId, restoredState, config, executor, stateMachine, transport,
        modelFactory, store, listener, random, clock, heartbeatScheduler);
    } else {
      // this groupMembers object does not hit network or disk.
      RaftGroupMembersView groupMembers = new DefaultRaftGroupMembersViewOrBuilder().setLogIndex(0)
        .setMembers(initialGroupMembers).setVotingMembers(initialVotingGroupMembers).build();
      return new RaftNodeImpl(groupId, localEndpoint, groupMembers, config, executor, stateMachine,
        transport, modelFactory, store, listener, random, clock, heartbeatScheduler);
    }
  }
}
