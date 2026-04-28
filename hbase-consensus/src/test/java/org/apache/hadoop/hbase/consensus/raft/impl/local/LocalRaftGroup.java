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
package org.apache.hadoop.hbase.consensus.raft.impl.local;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hbase.consensus.raft.RaftConfig.DEFAULT_RAFT_CONFIG;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.hadoop.hbase.consensus.handler.store.DefaultLogStoreSerializer;
import org.apache.hadoop.hbase.consensus.handler.store.LogStoreConfig;
import org.apache.hadoop.hbase.consensus.handler.store.LogStoreSerializer;
import org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodecs;
import org.apache.hadoop.hbase.consensus.handler.transport.PayloadCompressor;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNode.RaftNodeBuilder;
import org.apache.hadoop.hbase.consensus.raft.executor.impl.DefaultRaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftTermState;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.persistence.NopRaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReportListener;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
import org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used for running a Raft group with local Raft node.
 * @see LocalRaftEndpoint
 * @see SimpleStateMachine
 * @see Firewall
 */
public final class LocalRaftGroup {
  public static final BiFunction<RaftEndpoint, RaftConfig,
    RaftStore> IN_MEMORY_RAFT_STATE_STORE_FACTORY = (endpoint, config) -> {
      return new InMemoryRaftStore();
    };

  private static final Logger LOG = LoggerFactory.getLogger(LocalRaftGroup.class);
  private final RaftConfig config;
  private final boolean newTermEntryEnabled;
  private final List<RaftEndpoint> initialMembers = new ArrayList<>();
  private final Map<RaftEndpoint, RaftNodeContext> nodeContexts = new HashMap<>();
  private final BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory;
  private final HeartbeatScheduler heartbeatScheduler;
  private final Function<RaftEndpoint, HeartbeatScheduler> heartbeatSchedulerFactory;

  /**
   * Builds a {@link BiFunction} factory that creates a fresh disk-backed
   * {@link org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore} per Raft endpoint
   * under {@code rootDir/<endpointId>}. The returned {@link RaftStore} is the
   * {@code "default"}-group adapter; the caller owns the underlying parent stores listed in
   * {@code stores} and must {@link UnifiedRaftStore#close()} them on test teardown.
   */
  public static BiFunction<RaftEndpoint, RaftConfig, RaftStore>
    unifiedRaftStateStoreFactory(File rootDir, List<UnifiedRaftStore> stores) {
    return (endpoint, config) -> {
      try {
        File subdir = new File(rootDir, String.valueOf(endpoint.getId()));
        if (!subdir.exists() && !subdir.mkdirs()) {
          throw new IOException("Failed to create " + subdir);
        }
        LogStoreConfig cfg =
          LogStoreConfig.newBuilder(subdir).setSegmentSizeMb(8).setMailboxChunkSize(64).build();
        OperationCodec codec = OperationCodecs.composite(OperationCodecs.defaultCodecs(),
          new SimpleStateMachineOpCodec());
        LogStoreSerializer serializer = new DefaultLogStoreSerializer(new DefaultRaftModelFactory(),
          codec, new PayloadCompressor(Compression.Algorithm.NONE));
        UnifiedRaftStore parent = new UnifiedRaftStore(cfg, serializer);
        parent.load();
        stores.add(parent);
        return parent.newGroupStore("default".getBytes());
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    };
  }

  private LocalRaftGroup(int groupSize, int votingMemberCount, RaftConfig config,
    boolean newTermEntryEnabled, BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory,
    HeartbeatScheduler heartbeatScheduler,
    Function<RaftEndpoint, HeartbeatScheduler> heartbeatSchedulerFactory) {
    this.config = config;
    this.newTermEntryEnabled = newTermEntryEnabled;
    this.raftStoreFactory = raftStoreFactory;
    this.heartbeatScheduler = heartbeatScheduler;
    this.heartbeatSchedulerFactory = heartbeatSchedulerFactory;
    createNodes(groupSize, votingMemberCount, config, raftStoreFactory);
  }

  /**
   * Creates and starts a Raft group for the given number of Raft nodes. the number of Raft nodes to
   * create the Raft group
   * @return the created and started Raft group
   */
  public static LocalRaftGroup start(int groupSize) {
    LocalRaftGroup group = new LocalRaftGroupBuilder(groupSize).build();
    group.start();
    return group;
  }

  public static LocalRaftGroup start(int groupSize, int votingMemberCount) {
    LocalRaftGroup group = new LocalRaftGroupBuilder(groupSize, votingMemberCount).build();
    group.start();
    return group;
  }

  /**
   * Creates and starts a Raft group for the given number of Raft nodes. the number of Raft nodes to
   * create the Raft group the RaftConfig object to create the Raft nodes with
   * @return the created and started Raft group
   */
  public static LocalRaftGroup start(int groupSize, RaftConfig config) {
    LocalRaftGroup group = new LocalRaftGroupBuilder(groupSize).setConfig(config).build();
    group.start();
    return group;
  }

  public static LocalRaftGroup start(int groupSize, int votingMemberCount, RaftConfig config) {
    LocalRaftGroup group =
      new LocalRaftGroupBuilder(groupSize, votingMemberCount).setConfig(config).build();
    group.start();
    return group;
  }

  /**
   * Returns a new Raft group builder object the number of Raft nodes to create the Raft group
   * @return a new Raft group builder object
   */
  public static LocalRaftGroupBuilder newBuilder(int groupSize) {
    return new LocalRaftGroupBuilder(groupSize);
  }

  public static LocalRaftGroupBuilder newBuilder(int groupSize, int votingMemberCount) {
    return new LocalRaftGroupBuilder(groupSize, votingMemberCount);
  }

  private void createNodes(int groupSize, int votingMemberCount, RaftConfig config,
    BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory) {
    for (int i = 0; i < groupSize; i++) {
      RaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
      initialMembers.add(endpoint);
    }
    List<RaftEndpoint> initialVotingMembers = initialMembers.subList(0, votingMemberCount);
    for (int i = 0; i < groupSize; i++) {
      RaftEndpoint endpoint = initialMembers.get(i);
      LocalTransport transport = new LocalTransport(endpoint);
      SimpleStateMachine stateMachine = new SimpleStateMachine(newTermEntryEnabled);
      RaftNodeBuilder nodeBuilder = RaftNode.newBuilder().setGroupId("default")
        .setLocalEndpoint(endpoint).setInitialGroupMembers(initialMembers, initialVotingMembers)
        .setConfig(config).setTransport(transport).setStateMachine(stateMachine)
        .setRaftNodeReportListener(new RecordingRaftNodeReportListener());
      if (raftStoreFactory != null) {
        nodeBuilder.setStore(raftStoreFactory.apply(endpoint, config));
      }
      if (heartbeatSchedulerFactory != null) {
        HeartbeatScheduler scheduler = heartbeatSchedulerFactory.apply(endpoint);
        if (scheduler != null) {
          nodeBuilder.setHeartbeatScheduler(scheduler);
        }
      } else if (heartbeatScheduler != null) {
        nodeBuilder.setHeartbeatScheduler(heartbeatScheduler);
      }
      RaftNodeImpl node = (RaftNodeImpl) nodeBuilder.build();
      RaftNodeContext context = new RaftNodeContext((DefaultRaftNodeExecutor) node.getExecutor(),
        transport, stateMachine, node);
      nodeContexts.put(endpoint, context);
    }
  }

  /**
   * Enables discovery between the created Raft nodes and starts them.
   */
  public void start() {
    initDiscovery();
    startNodes();
  }

  private void initDiscovery() {
    for (RaftNodeContext ctx1 : nodeContexts.values()) {
      for (RaftNodeContext ctx2 : nodeContexts.values()) {
        if (ctx2.isExecutorRunning() && !ctx1.getLocalEndpoint().equals(ctx2.getLocalEndpoint())) {
          ctx1.transport.discoverNode(ctx2.node);
        }
      }
    }
  }

  private void startNodes() {
    for (RaftNodeContext ctx : nodeContexts.values()) {
      ctx.node.start();
    }
  }

  /**
   * Creates a new Raft node and makes the other Raft nodes discover it.
   * @return the created Raft node.
   */
  public RaftNodeImpl createNewNode() {
    LocalRaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    LocalTransport transport = new LocalTransport(endpoint);
    SimpleStateMachine stateMachine = new SimpleStateMachine(newTermEntryEnabled);
    RaftStore raftStore =
      raftStoreFactory != null ? raftStoreFactory.apply(endpoint, config) : new NopRaftStore();
    RaftNodeImpl node = (RaftNodeImpl) RaftNode.newBuilder().setGroupId("default")
      .setLocalEndpoint(endpoint).setInitialGroupMembers(initialMembers).setConfig(config)
      .setTransport(transport).setStateMachine(stateMachine).setStore(raftStore).build();
    nodeContexts.put(endpoint, new RaftNodeContext((DefaultRaftNodeExecutor) node.getExecutor(),
      transport, stateMachine, node));
    node.start();
    initDiscovery();
    return node;
  }

  /**
   * Restores a Raft node with the given {@link RestoredRaftState} object. The Raft node to be
   * restored must be created in this local Raft group.
   * <p>
   * If there exists a running Raft node with the same endpoint, this method fails with
   * {@link IllegalStateException}. the restored Raft state object to start the Raft node the Raft
   * store object to start the Raft node
   * @return the restored Raft node if there exists a running Raft node with the same endpoint
   */
  public RaftNodeImpl restoreNode(RestoredRaftState restoredState, RaftStore store) {
    boolean exists = nodeContexts.values().stream()
      .filter(ctx -> ctx.getLocalEndpoint()
        .equals(restoredState.getLocalEndpointPersistentState().getLocalEndpoint()))
      .anyMatch(RaftNodeContext::isExecutorRunning);
    if (exists) {
      throw new IllegalStateException(
        restoredState.getLocalEndpointPersistentState().getLocalEndpoint()
          + " is already running!");
    }
    requireNonNull(restoredState);
    LocalTransport transport =
      new LocalTransport(restoredState.getLocalEndpointPersistentState().getLocalEndpoint());
    SimpleStateMachine stateMachine = new SimpleStateMachine(newTermEntryEnabled);
    RaftNodeImpl node = (RaftNodeImpl) RaftNode.newBuilder().setGroupId("default")
      .setRestoredState(restoredState).setConfig(config).setTransport(transport)
      .setStateMachine(stateMachine).setStore(store).build();
    nodeContexts.put(restoredState.getLocalEndpointPersistentState().getLocalEndpoint(),
      new RaftNodeContext((DefaultRaftNodeExecutor) node.getExecutor(), transport, stateMachine,
        node));
    node.start();
    initDiscovery();
    return node;
  }

  /**
   * Restores a Raft node by re-opening its on-disk
   * {@link org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore} under
   * {@code rootDir/<endpointId>}, calling {@code load()}, and rebuilding the node from whatever
   * state survived. Used by corruption / fault-tolerance fixtures: if {@code load()} returns a
   * {@link RestoredRaftState} for the {@code "default"} group the node is restored from it; if no
   * state survives (e.g. the directory is empty or all segments were deleted) the node is recreated
   * fresh against the original {@code initialMembers} so the leader can drive
   * {@code InstallSnapshot} catchup. The freshly opened parent store is appended to {@code stores}
   * so the test can {@code close()} it on teardown.
   */
  public RaftNodeImpl restoreNodeFromUnifiedStore(RaftEndpoint endpoint, File rootDir,
    List<UnifiedRaftStore> stores) throws IOException {
    boolean running =
      nodeContexts.values().stream().filter(ctx -> ctx.getLocalEndpoint().equals(endpoint))
        .anyMatch(RaftNodeContext::isExecutorRunning);
    if (running) {
      throw new IllegalStateException(endpoint + " is already running!");
    }
    File subdir = new File(rootDir, String.valueOf(endpoint.getId()));
    if (!subdir.exists() && !subdir.mkdirs()) {
      throw new IOException("Failed to create " + subdir);
    }
    LogStoreConfig cfg =
      LogStoreConfig.newBuilder(subdir).setSegmentSizeMb(8).setMailboxChunkSize(64).build();
    OperationCodec codec =
      OperationCodecs.composite(OperationCodecs.defaultCodecs(), new SimpleStateMachineOpCodec());
    LogStoreSerializer serializer = new DefaultLogStoreSerializer(new DefaultRaftModelFactory(),
      codec, new PayloadCompressor(Compression.Algorithm.NONE));
    UnifiedRaftStore parent = new UnifiedRaftStore(cfg, serializer);
    Map<ByteBuffer, RestoredRaftState> states = parent.load();
    stores.add(parent);
    RaftStore store = parent.newGroupStore("default".getBytes());
    RestoredRaftState restored = states.get(ByteBuffer.wrap("default".getBytes()));
    LocalTransport transport = new LocalTransport(endpoint);
    SimpleStateMachine stateMachine = new SimpleStateMachine(newTermEntryEnabled);
    RaftNodeImpl node;
    if (restored != null) {
      node = (RaftNodeImpl) RaftNode.newBuilder().setGroupId("default").setRestoredState(restored)
        .setConfig(config).setTransport(transport).setStateMachine(stateMachine).setStore(store)
        .build();
    } else {
      List<RaftEndpoint> votingMembers =
        initialMembers.subList(0, Math.min(initialMembers.size(), initialMembers.size()));
      node = (RaftNodeImpl) RaftNode.newBuilder().setGroupId("default").setLocalEndpoint(endpoint)
        .setInitialGroupMembers(initialMembers, votingMembers).setConfig(config)
        .setTransport(transport).setStateMachine(stateMachine).setStore(store).build();
    }
    nodeContexts.put(endpoint, new RaftNodeContext((DefaultRaftNodeExecutor) node.getExecutor(),
      transport, stateMachine, node));
    node.start();
    initDiscovery();
    return node;
  }

  /**
   * Returns all Raft nodes currently running in this local Raft group.
   * @return all Raft nodes currently running in this local Raft group
   */
  public List<RaftNodeImpl> getNodes() {
    return nodeContexts.values().stream().map(ctx -> ctx.node).collect(toList());
  }

  /**
   * Returns all Raft nodes currently running in this local Raft group except the given Raft
   * endpoint. the Raft endpoint to excluded in the returned Raft node list
   * @return all Raft nodes currently running in this local Raft group except the given Raft
   *         endpoint
   */
  @SuppressWarnings("unchecked")
  public <T extends RaftNode> List<T> getNodesExcept(RaftEndpoint endpoint) {
    requireNonNull(endpoint);
    List<RaftNodeImpl> nodes = nodeContexts.values().stream().map(ctx -> ctx.node)
      .filter(node -> !node.getLocalEndpoint().equals(endpoint)).collect(toList());
    if (nodes.size() != nodeContexts.size() - 1) {
      throw new IllegalArgumentException("Unknown endpoint: " + endpoint);
    }
    return (List<T>) nodes;
  }

  /** Returns the currently running Raft node of the given Raft endpoint. */
  @SuppressWarnings("unchecked")
  public <T extends RaftNode> T getNode(RaftEndpoint endpoint) {
    requireNonNull(endpoint);
    return (T) nodeContexts.get(endpoint).node;
  }

  /**
   * Returns the current leader Raft endpoint of the Raft group, or null if there is no elected
   * leader.
   * <p>
   * If different Raft nodes see different leaders, this method fails with {@link AssertionError}.
   * @return the current leader Raft endpoint of the Raft group, or null if there is no elected
   *         leader if different Raft nodes see different leaders
   */
  public RaftEndpoint getLeaderEndpoint() {
    RaftEndpoint leaderEndpoint = null;
    int leaderTerm = 0;
    for (RaftNodeContext nodeContext : nodeContexts.values()) {
      if (nodeContext.isExecutorShutdown()) {
        continue;
      }
      RaftTermState term = nodeContext.node.getTerm();
      if (term.getLeaderEndpoint() != null) {
        if (leaderEndpoint == null) {
          leaderEndpoint = term.getLeaderEndpoint();
          leaderTerm = term.getTerm();
        } else
          if (!(leaderEndpoint.equals(term.getLeaderEndpoint()) && leaderTerm == term.getTerm())) {
            leaderEndpoint = null;
            leaderTerm = 0;
          }
      } else {
        throw new AssertionError("Group doesn't have a single leader endpoint yet!");
      }
    }
    return leaderEndpoint;
  }

  /** Returns the state machine object for the given Raft endpoint. */
  public SimpleStateMachine getStateMachine(RaftEndpoint endpoint) {
    requireNonNull(endpoint);
    return nodeContexts.get(endpoint).stateMachine;
  }

  private Firewall getFirewall(RaftEndpoint endpoint) {
    requireNonNull(endpoint);
    return nodeContexts.get(endpoint).transport.getFirewall();
  }

  /**
   * Waits until a leader is elected in this Raft group.
   * <p>
   * Fails with {@link AssertionError} if no leader is elected during
   * {@link AssertionUtils#EVENTUAL_ASSERTION_TIMEOUT_SECS}.
   * @return the leader Raft node
   */
  @SuppressWarnings("unchecked")
  public <T extends RaftNode> T waitUntilLeaderElected() {
    RaftNodeImpl[] leaderRef = new RaftNodeImpl[1];
    eventually(() -> {
      RaftNodeImpl leaderNode = getLeaderNode();
      assertThat(leaderNode).isNotNull();
      leaderRef[0] = leaderNode;
    });
    return (T) leaderRef[0];
  }

  /**
   * Returns the current leader Raft node of the Raft group, or null if there is no elected leader.
   * <p>
   * If different Raft nodes see different leaders or the Raft node of the leader Raft endpoint is
   * not found, this method fails with {@link AssertionError}.
   * @return the current leader Raft endpoint of the Raft group, or null if there is no elected
   *         leader if different Raft nodes see different leaders or the Raft node of the leader
   *         Raft endpoint is not found
   */
  @SuppressWarnings("unchecked")
  public <T extends RaftNode> T getLeaderNode() {
    RaftEndpoint leaderEndpoint = getLeaderEndpoint();
    if (leaderEndpoint == null) {
      return null;
    }
    RaftNodeContext leaderCtx = nodeContexts.get(leaderEndpoint);
    if (leaderCtx == null || leaderCtx.isExecutorShutdown()) {
      throw new AssertionError(
        "Leader endpoint is " + leaderEndpoint + ", but leader node could not be found!");
    }
    return (T) leaderCtx.node;
  }

  /**
   * Returns a random Raft node other than the given Raft endpoint.
   * <p>
   * If no running Raft node is found for given Raft endpoint, then this method fails with
   * {@link NullPointerException}.
   * @return a random Raft node other than the given Raft endpoint if no running Raft node is found
   *         for given Raft endpoint
   */
  @SuppressWarnings("unchecked")
  public <T extends RaftNode> T getAnyNodeExcept(RaftEndpoint endpoint) {
    requireNonNull(endpoint);
    requireNonNull(nodeContexts.get(endpoint));
    for (Entry<RaftEndpoint, RaftNodeContext> e : nodeContexts.entrySet()) {
      if (!e.getKey().equals(endpoint)) {
        return (T) e.getValue().node;
      }
    }
    throw new IllegalArgumentException("Unknown endpoint: " + endpoint);
  }

  /**
   * Returns all RaftNodeReport objects reported by the given Raft endpoint the Raft endpoint to get
   * the RaftNodeReport objects
   * @return all RaftNodeReport objects reported by the given Raft endpoint
   */
  public List<RaftNodeReport> getRaftNodeReports(RaftEndpoint endpoint) {
    requireNonNull(endpoint);
    return ((RecordingRaftNodeReportListener) nodeContexts.get(endpoint).node
      .getRaftNodeReportListener()).getReports();
  }

  /**
   * Stops all Raft nodes running in the Raft group.
   */
  public void destroy() {
    for (RaftNodeContext ctx : nodeContexts.values()) {
      ctx.node.terminate();
    }
    for (RaftNodeContext ctx : nodeContexts.values()) {
      ctx.executor.getExecutor().shutdown();
    }
    nodeContexts.clear();
  }

  /**
   * Creates an artificial load on the given Raft node by sleeping its thread for the given
   * duration.
   */
  public void slowDownNode(RaftEndpoint endpoint, int seconds) {
    nodeContexts.get(endpoint).executor.submit(() -> {
      LOG.info(endpoint.getId() + " is under high load for " + seconds + " seconds.");
      AssertionUtils.sleepMillis(TimeUnit.SECONDS.toMillis(seconds));
    });
  }

  /**
   * Split the given Raft endpoints from the rest of the Raft group. It means that the communication
   * between the given endpoints and the other Raft nodes will be blocked completely.
   * <p>
   * This method fails with {@link NullPointerException} if no Raft node is found for any of the
   * given endpoints list.
   */
  public void splitMembers(RaftEndpoint... endpoints) {
    splitMembers(Arrays.asList(endpoints));
  }

  /**
   * Split the given Raft endpoints from the rest of the Raft group. It means that the communication
   * between the given endpoints and the other Raft nodes will be blocked completely.
   * <p>
   * This method fails with {@link NullPointerException} if no Raft node is found for any of the
   * given endpoints list.
   */
  public void splitMembers(List<RaftEndpoint> endpoints) {
    for (RaftEndpoint endpoint : endpoints) {
      requireNonNull(nodeContexts.get(endpoint));
    }
    List<RaftNodeContext> side1 = endpoints.stream().map(nodeContexts::get).collect(toList());
    List<RaftNodeContext> side2 = new ArrayList<>(nodeContexts.values());
    side2.removeAll(side1);
    for (RaftNodeContext ctx1 : side1) {
      for (RaftNodeContext ctx2 : side2) {
        ctx1.transport.undiscoverNode(ctx2.node);
        ctx2.transport.undiscoverNode(ctx1.node);
      }
    }
  }

  /**
   * Returns a random set of Raft nodes.
   * <p>
   * The number of Raft nodes to return denotes whether if the leader Raft node can be returned or
   * not.
   * @return the randomly selected Raft node set
   */
  public List<RaftEndpoint> getRandomNodes(int nodeCount, boolean includeLeader) {
    RaftEndpoint leaderEndpoint = getLeaderEndpoint();
    List<RaftEndpoint> split = new ArrayList<>();
    if (includeLeader) {
      split.add(leaderEndpoint);
    }
    List<RaftNodeContext> contexts = new ArrayList<>(nodeContexts.values());
    Collections.shuffle(contexts);
    for (RaftNodeContext ctx : contexts) {
      if (leaderEndpoint.equals(ctx.getLocalEndpoint())) {
        continue;
      }
      split.add(ctx.getLocalEndpoint());
      if (split.size() == nodeCount) {
        break;
      }
    }
    return split;
  }

  /**
   * Cancels all network partitions and enables the Raft nodes to reach to each other again.
   */
  public void merge() {
    initDiscovery();
  }

  /**
   * Adds a one-way drop-message rule for the given source and target Raft endpoints and the Raft
   * message type.
   * <p>
   * After this call, Raft messages of the given type sent from the given source Raft endpoint to
   * the given target Raft endpoint are silently dropped.
   */
  public <T extends RaftMessage> void dropMessagesTo(RaftEndpoint source, RaftEndpoint target,
    Class<T> messageType) {
    getFirewall(source).dropMessagesTo(target, messageType);
  }

  /**
   * Deletes the one-way drop-message rule for the given source and target Raft endpoints and the
   * Raft message type.
   */
  public <T extends RaftMessage> void allowMessagesTo(RaftEndpoint source, RaftEndpoint target,
    Class<T> messageType) {
    getFirewall(source).allowMessagesTo(target, messageType);
  }

  /**
   * Adds a one-way drop-all-messages rule for the given source and target Raft endpoints.
   * <p>
   * After this call, all Raft messages sent from the source Raft endpoint to the target Raft
   * endpoint are silently dropped.
   * <p>
   * If there were drop-message rules from the source Raft endpoint to the target Raft endpoint,
   * they are replaced with a drop-all-messages rule.
   */
  public void dropAllMessagesTo(RaftEndpoint source, RaftEndpoint target) {
    getFirewall(source).dropAllMessagesTo(target);
  }

  /**
   * Deletes all one-way drop-message and drop-all-messages rules created for the source Raft
   * endpoint and the target Raft endpoint.
   */
  public void allowAllMessagesTo(RaftEndpoint source, RaftEndpoint target) {
    getFirewall(source).allowAllMessagesTo(target);
  }

  /**
   * Adds a one-way drop-message rule for the given Raft message type from the source Raft endpoint
   * to all other Raft endpoints.
   */
  public <T extends RaftMessage> void dropMessagesToAll(RaftEndpoint source, Class<T> messageType) {
    getFirewall(source).dropMessagesToAll(messageType);
  }

  /**
   * Deletes the one-way drop-all-messages rule for the given Raft message type from the source Raft
   * endpoint to any other Raft endpoint.
   */
  public <T extends RaftMessage> void allowMessagesToAll(RaftEndpoint source,
    Class<T> messageType) {
    getFirewall(source).allowMessagesToAll(messageType);
  }

  /**
   * Drops {@link AppendEntriesRequest}, {@link LeaderHeartbeat}, and bulk-heartbeat envelopes from
   * the source to the target Raft endpoint.
   */
  public void dropAppendsAndHeartbeatsTo(RaftEndpoint source, RaftEndpoint target) {
    getFirewall(source).dropMessagesTo(target, AppendEntriesRequest.class);
    getFirewall(source).dropMessagesTo(target, LeaderHeartbeat.class);
    getFirewall(source).dropBulkHeartbeatsTo(target);
  }

  /**
   * Re-allows {@link AppendEntriesRequest}, {@link LeaderHeartbeat}, and bulk-heartbeat envelopes
   * from the source to the target Raft endpoint, undoing a prior call to
   * {@link #dropAppendsAndHeartbeatsTo(RaftEndpoint, RaftEndpoint)}.
   */
  public void allowAppendsAndHeartbeatsTo(RaftEndpoint source, RaftEndpoint target) {
    getFirewall(source).allowMessagesTo(target, AppendEntriesRequest.class);
    getFirewall(source).allowMessagesTo(target, LeaderHeartbeat.class);
    getFirewall(source).allowBulkHeartbeatsTo(target);
  }

  /**
   * Drops {@link AppendEntriesRequest}, {@link LeaderHeartbeat}, and bulk-heartbeat envelopes from
   * the source Raft endpoint to all other Raft endpoints. See
   * {@link #dropAppendsAndHeartbeatsTo(RaftEndpoint, RaftEndpoint)} for rationale.
   */
  public void dropAppendsAndHeartbeatsToAll(RaftEndpoint source) {
    getFirewall(source).dropMessagesToAll(AppendEntriesRequest.class);
    getFirewall(source).dropMessagesToAll(LeaderHeartbeat.class);
    getFirewall(source).dropBulkHeartbeatsToAll();
  }

  /**
   * Re-allows {@link AppendEntriesRequest}, {@link LeaderHeartbeat}, and bulk-heartbeat envelopes
   * from the source Raft endpoint to all other Raft endpoints, undoing a prior call to
   * {@link #dropAppendsAndHeartbeatsToAll(RaftEndpoint)}.
   */
  public void allowAppendsAndHeartbeatsToAll(RaftEndpoint source) {
    getFirewall(source).allowMessagesToAll(AppendEntriesRequest.class);
    getFirewall(source).allowMessagesToAll(LeaderHeartbeat.class);
    getFirewall(source).allowBulkHeartbeatsToAll();
  }

  /** Resets all drop rules from the source Raft endpoint. */
  public void resetAllRulesFrom(RaftEndpoint source) {
    getFirewall(source).resetAllRules();
  }

  /**
   * Applies the given function an all Raft messages sent from the source Raft endpoint to the
   * target Raft endpoint.
   * <p>
   * If the given function is not altering a given Raft message, it should return it as it is,
   * instead of returning null.
   * <p>
   * Only a single alter rule can be created in the source Raft endpoint for a given target Raft
   * endpoint and a new alter rule overwrites the previous one.
   */
  public void alterMessagesTo(RaftEndpoint source, RaftEndpoint target,
    Function<RaftMessage, RaftMessage> function) {
    getFirewall(source).alterMessagesTo(target, function);
  }

  /**
   * Deletes the alter-message rule from the source Raft endpoint to the target Raft endpoint.
   */
  void removeAlterMessageRuleTo(RaftEndpoint source, RaftEndpoint target) {
    getFirewall(source).removeAlterMessageFunctionTo(target);
  }

  /**
   * Terminates the Raft node with the given Raft endpoint and removes it from the discovery state
   * of the other Raft nodes. It means that the other Raft nodes will see the terminated Raft node
   * as unreachable.
   * <p>
   * This method fails with {@link NullPointerException} if there is no running Raft node with the
   * given endpoint.
   */
  public void terminateNode(RaftEndpoint endpoint) {
    RaftNodeContext ctx = nodeContexts.get(requireNonNull(endpoint));
    requireNonNull(ctx);
    ctx.node.terminate().join();
    splitMembers(ctx.getLocalEndpoint());
    ctx.executor.getExecutor().shutdown();
    nodeContexts.remove(endpoint);
  }

  /** Builder for creating and starting Raft groups. */
  public static final class LocalRaftGroupBuilder {
    private final int groupSize;
    private final int votingMemberCount;
    private RaftConfig config = DEFAULT_RAFT_CONFIG;
    private boolean newTermOperationEnabled;
    private BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory;
    private HeartbeatScheduler heartbeatScheduler;
    private Function<RaftEndpoint, HeartbeatScheduler> heartbeatSchedulerFactory;

    private LocalRaftGroupBuilder(int groupSize) {
      if (groupSize < 1) {
        throw new IllegalArgumentException("Raft groups must have at least 1 Raft node!");
      }
      this.groupSize = groupSize;
      this.votingMemberCount = groupSize;
    }

    private LocalRaftGroupBuilder(int groupSize, int votingMemberCount) {
      if (groupSize < 1) {
        throw new IllegalArgumentException("Raft groups must have at least 1 Raft node!");
      } else if (votingMemberCount < 1) {
        throw new IllegalArgumentException("Raft groups must have at least 1 voting Raft node!");
      } else if (votingMemberCount > groupSize) {
        throw new IllegalArgumentException(
          "Raft group size must be greater than or equal to voting member count!");
      }
      this.groupSize = groupSize;
      this.votingMemberCount = votingMemberCount;
    }

    /**
     * Sets the RaftConfig object to create Raft nodes.
     * @return the builder object for fluent calls
     */
    public LocalRaftGroupBuilder setConfig(RaftConfig config) {
      requireNonNull(config);
      this.config = config;
      return this;
    }

    /**
     * @return the builder object for fluent calls
     * @see StateMachine#getNewTermOperation()
     */
    public LocalRaftGroupBuilder enableNewTermOperation() {
      this.newTermOperationEnabled = true;
      return this;
    }

    /**
     * Sets the factory object for creating Raft state stores.
     * @return the builder object for fluent calls
     */
    public LocalRaftGroupBuilder
      setRaftStoreFactory(BiFunction<RaftEndpoint, RaftConfig, RaftStore> raftStoreFactory) {
      requireNonNull(raftStoreFactory);
      this.raftStoreFactory = raftStoreFactory;
      return this;
    }

    /**
     * Sets a shared {@link HeartbeatScheduler} that every Raft node in this group will register
     * with via the {@code RaftNodeBuilder.setHeartbeatScheduler} seam.
     * @return the builder object for fluent calls
     */
    public LocalRaftGroupBuilder setHeartbeatScheduler(HeartbeatScheduler heartbeatScheduler) {
      requireNonNull(heartbeatScheduler);
      this.heartbeatScheduler = heartbeatScheduler;
      return this;
    }

    /**
     * Sets a per-node {@link HeartbeatScheduler} factory. Each Raft node in this group is built
     * with the {@code HeartbeatScheduler} returned by {@code factory.apply(endpoint)}. Useful for
     * tests of {@code SweepingHeartbeatScheduler}, where every test "physical node" needs its own
     * scheduler instance because a sweeping scheduler is keyed by groupId. If both
     * {@link #setHeartbeatScheduler} and this method are configured, this factory wins.
     * @return the builder object for fluent calls
     */
    public LocalRaftGroupBuilder
      setHeartbeatSchedulerFactory(Function<RaftEndpoint, HeartbeatScheduler> factory) {
      requireNonNull(factory);
      this.heartbeatSchedulerFactory = factory;
      return this;
    }

    /**
     * Builds the local Raft group with the configured settings. Please note that the returned Raft
     * group is not started yet.
     * @return the created local Raft group
     */
    public LocalRaftGroup build() {
      return new LocalRaftGroup(groupSize, votingMemberCount, config, newTermOperationEnabled,
        raftStoreFactory, heartbeatScheduler, heartbeatSchedulerFactory);
    }

    /**
     * Builds and starts the local Raft group with the configured settings.
     * @return the created and started local Raft group
     */
    public LocalRaftGroup start() {
      LocalRaftGroup group = new LocalRaftGroup(groupSize, votingMemberCount, config,
        newTermOperationEnabled, raftStoreFactory, heartbeatScheduler, heartbeatSchedulerFactory);
      group.start();
      return group;
    }
  }

  private static class RaftNodeContext {
    final DefaultRaftNodeExecutor executor;
    final LocalTransport transport;
    final SimpleStateMachine stateMachine;
    final RaftNodeImpl node;

    RaftNodeContext(DefaultRaftNodeExecutor executor, LocalTransport transport,
      SimpleStateMachine stateMachine, RaftNodeImpl node) {
      this.executor = executor;
      this.transport = transport;
      this.stateMachine = stateMachine;
      this.node = node;
    }

    RaftEndpoint getLocalEndpoint() {
      return node.getLocalEndpoint();
    }

    boolean isExecutorRunning() {
      return !isExecutorShutdown();
    }

    boolean isExecutorShutdown() {
      return executor.getExecutor().isShutdown();
    }
  }

  private static class RecordingRaftNodeReportListener implements RaftNodeReportListener {
    private List<RaftNodeReport> reports = new ArrayList<>();

    public synchronized List<RaftNodeReport> getReports() {
      List<RaftNodeReport> reports = new ArrayList<>();
      reports.addAll(this.reports);
      return reports;
    }

    @Override
    public synchronized void accept(RaftNodeReport report) {
      reports.add(report);
    }
  }
}
