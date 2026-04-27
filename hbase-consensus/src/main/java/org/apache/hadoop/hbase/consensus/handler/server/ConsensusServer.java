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
package org.apache.hadoop.hbase.consensus.handler.server;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.consensus.handler.executor.MultiGroupExecutor;
import org.apache.hadoop.hbase.consensus.handler.heartbeat.SweepingHeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.statemachine.LeaderReportListener;
import org.apache.hadoop.hbase.consensus.handler.statemachine.StateMachineAdapter;
import org.apache.hadoop.hbase.consensus.handler.store.LogStoreConfig;
import org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore;
import org.apache.hadoop.hbase.consensus.handler.transport.CoalescingTransport;
import org.apache.hadoop.hbase.consensus.handler.transport.EndpointResolver;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle-managed composition of the consensus engine that hosts an arbitrary number of Raft
 * groups on a single JVM.
 * <p>
 * One {@code ConsensusServer} owns one each of the long-lived Phase 2&ndash;6 components:
 * <ul>
 * <li>{@link MultiGroupExecutor} &mdash; shared serial executor pool.</li>
 * <li>{@link CoalescingTransport} &mdash; Netty + protobuf transport bound to a single local
 * endpoint.</li>
 * <li>{@link SweepingHeartbeatScheduler} &mdash; O(peers) heartbeat sweeper.</li>
 * <li>{@link UnifiedRaftStore} &mdash; multiplexed durable log shared by every group.</li>
 * </ul>
 * Each registered group is wired to its own {@link ConsensusSpi} via a {@link StateMachineAdapter}
 * (for committed entries / snapshot bytes) and a {@link LeaderReportListener} (for leader / no
 * leader / lagging follower events). The Raft core lives on the per-group executor; the SPI runs on
 * the same thread.
 * <p>
 * Lifecycle (idempotent):
 * <ol>
 * <li>Construct.</li>
 * <li>{@link #start()}: load the durable log (one whole-disk replay), bind the transport, start the
 * heartbeat sweeper, flag ready. After this call returns,
 * {@link #addGroup(Object, Collection, ConsensusSpi) addGroup} may be invoked.</li>
 * <li>{@link #stop()}: tear groups down (parallel terminate), then components in reverse order
 * (sweeper, transport, store, executor).</li>
 * <li>{@link #close()}: alias for {@code stop()}.</li>
 * </ol>
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
@InterfaceStability.Evolving
public final class ConsensusServer
  implements GroupManager, Closeable, ConsensusServerMetricsWrapper {

  private static final Logger LOG = LoggerFactory.getLogger(ConsensusServer.class);

  private final Configuration conf;
  private final ConsensusServerConfig serverConfig;
  private final RaftEndpoint localEndpoint;
  private final EndpointResolver resolver;
  private final OperationCodec operationCodec;
  private final InetSocketAddress bindAddress;
  private final Function<Object, byte[]> groupIdEncoder;
  private final RaftConfig defaultRaftConfig;

  private final MultiGroupExecutor executor;
  private final SweepingHeartbeatScheduler scheduler;
  private final UnifiedRaftStore logStore;
  private final CoalescingTransport transport;
  private final ConsensusServerMetrics metrics;
  /** Whether this server owns (and therefore must close) {@link #executor}. */
  private final boolean ownsExecutor;

  private final ConcurrentHashMap<Object, GroupHandle> groups = new ConcurrentHashMap<>();
  private final AtomicReference<ConsensusServerStatus.State> state =
    new AtomicReference<>(ConsensusServerStatus.State.NEW);
  private final Object lifecycleLock = new Object();

  /**
   * Per-group cached restored state populated by {@link #start()}; consumed by {@link #addGroup}.
   */
  private final Map<ByteBuffer, RestoredRaftState> restoredStates = new ConcurrentHashMap<>();

  /**
   * Builds a fully self-owned {@code ConsensusServer}.
   * @param conf           hadoop {@link Configuration} carrying every {@code hbase.consensus.*}
   *                       knob
   * @param self           local Raft endpoint identity
   * @param bindAddress    server-side bind address (typically {@code 127.0.0.1:0} in tests)
   * @param resolver       resolves remote endpoints to socket addresses
   * @param operationCodec encodes/decodes opaque operations carried in {@code LogEntry}s
   */
  public ConsensusServer(@NonNull Configuration conf, @NonNull RaftEndpoint self,
    @NonNull InetSocketAddress bindAddress, @NonNull EndpointResolver resolver,
    @NonNull OperationCodec operationCodec) {
    this(conf, self, bindAddress, resolver, operationCodec, /* executor */ null,
      defaultGroupIdEncoder(), RaftConfig.DEFAULT_RAFT_CONFIG);
  }

  /**
   * Test/integration ctor that lets the caller provide a pre-built {@link MultiGroupExecutor} (e.g.
   * shared across multiple in-JVM servers in a test minicluster). When {@code executor} is non-null
   * this server will <em>not</em> close it on {@link #stop()}.
   * @param groupIdEncoder maps an opaque {@code groupId} (e.g. a {@code String} or an HBase
   *                       region-name byte[]) to the {@code byte[]} key used by
   *                       {@link UnifiedRaftStore#newGroupStore(byte[])}; defaults to
   *                       {@code String.valueOf(groupId).getBytes(UTF_8)}
   */
  public ConsensusServer(@NonNull Configuration conf, @NonNull RaftEndpoint self,
    @NonNull InetSocketAddress bindAddress, @NonNull EndpointResolver resolver,
    @NonNull OperationCodec operationCodec, @Nullable MultiGroupExecutor executor,
    @NonNull Function<Object, byte[]> groupIdEncoder) {
    this(conf, self, bindAddress, resolver, operationCodec, executor, groupIdEncoder,
      RaftConfig.DEFAULT_RAFT_CONFIG);
  }

  /**
   * Most-general ctor. Permits overriding the per-group {@link RaftConfig} (e.g. shorter election
   * timeouts in tests) in addition to all of the inputs accepted by the other ctors.
   */
  public ConsensusServer(@NonNull Configuration conf, @NonNull RaftEndpoint self,
    @NonNull InetSocketAddress bindAddress, @NonNull EndpointResolver resolver,
    @NonNull OperationCodec operationCodec, @Nullable MultiGroupExecutor executor,
    @NonNull Function<Object, byte[]> groupIdEncoder, @NonNull RaftConfig raftConfig) {
    this.conf = requireNonNull(conf, "conf");
    this.serverConfig = new ConsensusServerConfig(conf);
    this.localEndpoint = requireNonNull(self, "self");
    this.bindAddress = requireNonNull(bindAddress, "bindAddress");
    this.resolver = requireNonNull(resolver, "resolver");
    this.operationCodec = requireNonNull(operationCodec, "operationCodec");
    this.groupIdEncoder = requireNonNull(groupIdEncoder, "groupIdEncoder");
    this.defaultRaftConfig = requireNonNull(raftConfig, "raftConfig");
    if (executor != null) {
      this.executor = executor;
      this.ownsExecutor = false;
    } else {
      this.executor = new MultiGroupExecutor(conf);
      this.ownsExecutor = true;
    }
    this.logStore = new UnifiedRaftStore(new LogStoreConfig(conf));
    this.scheduler = new SweepingHeartbeatScheduler(conf);
    this.transport =
      new CoalescingTransport(localEndpoint, bindAddress, resolver, operationCodec, conf);
    this.metrics = new ConsensusServerMetrics(this);
  }

  /** Returns this server's local Raft endpoint. */
  @NonNull
  public RaftEndpoint getLocalEndpoint() {
    return localEndpoint;
  }

  /**
   * Returns the actually-bound address (post-{@link #start()}). Useful for tests that bind to an
   * ephemeral port and need to publish the resolved address into a shared resolver.
   */
  @NonNull
  public InetSocketAddress getBindAddress() {
    return transport.getBindAddress();
  }

  /** Returns the underlying transport. */
  @NonNull
  public CoalescingTransport getTransport() {
    return transport;
  }

  /** Returns the underlying durable log store. */
  @NonNull
  public UnifiedRaftStore getLogStore() {
    return logStore;
  }

  /** Returns the underlying heartbeat scheduler. */
  @NonNull
  public SweepingHeartbeatScheduler getHeartbeatScheduler() {
    return scheduler;
  }

  /** Returns the underlying multi-group executor. */
  @NonNull
  public MultiGroupExecutor getExecutor() {
    return executor;
  }

  /** Returns this server's parsed {@link ConsensusServerConfig}. */
  @NonNull
  public ConsensusServerConfig getServerConfig() {
    return serverConfig;
  }

  /** Returns this server's metrics object. */
  @NonNull
  public ConsensusServerMetrics getMetrics() {
    return metrics;
  }

  /** Returns this server's hadoop {@link Configuration}. */
  @NonNull
  public Configuration getConfiguration() {
    return conf;
  }

  /** Returns a point-in-time snapshot of this server's lifecycle counters. */
  @NonNull
  public ConsensusServerStatus getServerStatus() {
    return new ConsensusServerStatus(state.get(), groups.size(), serverConfig.getMaxGroups(),
      restoredStates.size());
  }

  /**
   * Starts the server.
   * <p>
   * Order:
   * <ol>
   * <li>{@link UnifiedRaftStore#load()} (one whole-disk replay).</li>
   * <li>{@link CoalescingTransport#start()}.</li>
   * <li>{@link SweepingHeartbeatScheduler#start()}.</li>
   * <li>Flag {@link ConsensusServerStatus.State#RUNNING}; {@link #addGroup} becomes legal.</li>
   * </ol>
   * @throws IOException           if the durable log fails to open or replay
   * @throws IllegalStateException if the server has already been stopped
   */
  public void start() throws IOException {
    synchronized (lifecycleLock) {
      ConsensusServerStatus.State current = state.get();
      if (current == ConsensusServerStatus.State.RUNNING) {
        return;
      }
      if (current != ConsensusServerStatus.State.NEW) {
        throw new IllegalStateException("ConsensusServer cannot be started from state " + current);
      }
      state.set(ConsensusServerStatus.State.STARTING);
      boolean storeLoaded = false;
      boolean transportStarted = false;
      try {
        Map<ByteBuffer, RestoredRaftState> restored = logStore.load();
        storeLoaded = true;
        restoredStates.putAll(restored);

        transport.start();
        transportStarted = true;

        scheduler.start();

        state.set(ConsensusServerStatus.State.RUNNING);
        LOG.info("ConsensusServer for {} started; bound at {}; restoredGroups={}; maxGroups={}",
          localEndpoint.getId(), transport.getBindAddress(), restoredStates.size(),
          serverConfig.getMaxGroups());
      } catch (RuntimeException | IOException e) {
        // Best-effort partial-startup unwind so callers can retry / move on cleanly.
        try {
          if (transportStarted) {
            transport.stop();
          }
        } catch (RuntimeException ignored) {
          // best-effort
        }
        try {
          if (storeLoaded) {
            logStore.close();
          }
        } catch (IOException ignored) {
          // best-effort
        }
        state.set(ConsensusServerStatus.State.STOPPED);
        throw e;
      }
    }
  }

  /** Stops the server. */
  public void stop() {
    synchronized (lifecycleLock) {
      ConsensusServerStatus.State current = state.get();
      if (
        current == ConsensusServerStatus.State.STOPPED
          || current == ConsensusServerStatus.State.STOPPING
      ) {
        return;
      }
      state.set(ConsensusServerStatus.State.STOPPING);
    }
    try {
      teardownGroups();
      try {
        scheduler.close();
      } catch (RuntimeException e) {
        LOG.warn("Error stopping heartbeat scheduler", e);
      }
      try {
        transport.stop();
      } catch (RuntimeException e) {
        LOG.warn("Error stopping transport", e);
      }
      try {
        logStore.close();
      } catch (IOException e) {
        LOG.warn("Error closing log store", e);
      }
      if (ownsExecutor) {
        try {
          executor.close();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
          LOG.warn("Error closing executor", e);
        }
      }
      restoredStates.clear();
      try {
        metrics.close();
      } catch (RuntimeException e) {
        LOG.warn("Error closing metrics", e);
      }
      LOG.info("ConsensusServer for {} stopped", localEndpoint.getId());
    } finally {
      state.set(ConsensusServerStatus.State.STOPPED);
    }
  }

  @Override
  public void close() {
    stop();
  }

  private void teardownGroups() {
    List<GroupHandle> snapshot = new ArrayList<>(groups.values());
    if (snapshot.isEmpty()) {
      return;
    }
    List<CompletableFuture<?>> terminations = new ArrayList<>(snapshot.size());
    for (GroupHandle h : snapshot) {
      try {
        terminations.add(h.getRaftNode().terminate());
      } catch (RuntimeException e) {
        LOG.debug("terminate threw for {}", h.getGroupId(), e);
      }
    }
    for (CompletableFuture<?> f : terminations) {
      try {
        f.join();
      } catch (RuntimeException e) {
        LOG.debug("group terminate completed exceptionally", e);
      }
    }
    for (GroupHandle h : snapshot) {
      detachAfterTerminate(h);
      groups.remove(h.getGroupId(), h);
    }
  }

  private void detachAfterTerminate(GroupHandle h) {
    try {
      scheduler.unregister((RaftNodeImpl) h.getRaftNode());
    } catch (RuntimeException e) {
      LOG.debug("scheduler.unregister failed for {}", h.getGroupId(), e);
    }
    try {
      transport.undiscoverNode(h.getRaftNode());
    } catch (RuntimeException e) {
      LOG.debug("transport.undiscoverNode failed for {}", h.getGroupId(), e);
    }
    // The per-group {@link GroupExecutor} self-unregisters from {@link MultiGroupExecutor} via
    // its own {@link RaftNodeLifecycleAware#onRaftNodeTerminate} when the terminated RaftNode
    // notifies it.
  }

  private void requireRunning() {
    ConsensusServerStatus.State s = state.get();
    if (s != ConsensusServerStatus.State.RUNNING) {
      throw new IllegalStateException("ConsensusServer is not running (state=" + s + ")");
    }
  }

  @NonNull
  @Override
  public GroupHandle addGroup(@NonNull Object groupId,
    @NonNull Collection<RaftEndpoint> initialMembers, @NonNull ConsensusSpi spi)
    throws IOException {
    requireNonNull(groupId, "groupId");
    requireNonNull(initialMembers, "initialMembers");
    requireNonNull(spi, "spi");
    requireRunning();

    // Atomic compute that rejects duplicates + enforces maxgroups in one pass.
    final IOException[] ioHolder = { null };
    final RuntimeException[] rtHolder = { null };
    final boolean[] addedNew = { false };
    long startMs = EnvironmentEdgeManager.currentTime();
    GroupHandle handle = groups.compute(groupId, (gid, existing) -> {
      if (existing != null) {
        return existing;
      }
      if (groups.size() >= serverConfig.getMaxGroups()) {
        rtHolder[0] = new MaxGroupsExceededException(serverConfig.getMaxGroups(), gid);
        return null;
      }
      try {
        GroupHandle built = buildAndStartGroup(gid, initialMembers, spi);
        addedNew[0] = true;
        return built;
      } catch (IOException ioe) {
        ioHolder[0] = ioe;
        return null;
      } catch (RuntimeException re) {
        rtHolder[0] = re;
        return null;
      }
    });
    if (ioHolder[0] != null) {
      metrics.incAddGroupFailure();
      throw ioHolder[0];
    }
    if (rtHolder[0] != null) {
      metrics.incAddGroupFailure();
      throw rtHolder[0];
    }
    if (handle == null) {
      metrics.incAddGroupFailure();
      throw new IllegalStateException("addGroup returned null without an exception for " + groupId);
    }
    if (addedNew[0]) {
      metrics.updateAddGroup(EnvironmentEdgeManager.currentTime() - startMs);
    }
    return handle;
  }

  /**
   * Builds the per-group {@link RaftNode}, wires it into the transport / scheduler / executor, and
   * starts it. Called from inside the registry's {@code compute} callback so that races between
   * concurrent {@link #addGroup} calls for the same id are linearised through the
   * {@link ConcurrentHashMap}'s per-bin lock.
   */
  private GroupHandle buildAndStartGroup(Object groupId, Collection<RaftEndpoint> initialMembers,
    ConsensusSpi spi) throws IOException {
    byte[] groupIdBytes = groupIdEncoder.apply(groupId);
    if (groupIdBytes == null) {
      throw new IllegalStateException("groupIdEncoder returned null for " + groupId);
    }
    RaftStore raftStore = logStore.newGroupStore(groupIdBytes);
    RestoredRaftState restored =
      restoredStates.get(ByteBuffer.wrap(groupIdBytes).asReadOnlyBuffer());

    StateMachineAdapter adapter = new StateMachineAdapter(groupId, spi, metrics);
    LeaderReportListener listener = new LeaderReportListener(spi, metrics);

    RaftNodeExecutor groupExec = executor.executorFor(groupId);
    RaftNode.RaftNodeBuilder builder =
      RaftNode.newBuilder().setGroupId(groupId).setExecutor(groupExec).setTransport(transport)
        .setStateMachine(adapter).setStore(raftStore).setRaftNodeReportListener(listener)
        .setHeartbeatScheduler(scheduler).setConfig(defaultRaftConfig);
    if (restored != null) {
      builder.setRestoredState(restored);
    } else {
      builder.setLocalEndpoint(localEndpoint).setInitialGroupMembers(initialMembers);
    }
    RaftNode node;
    try {
      node = builder.build();
    } catch (RuntimeException e) {
      releaseGroupExecutor(groupExec);
      throw e;
    }

    try {
      transport.discoverNode(node);
    } catch (RuntimeException e) {
      releaseGroupExecutor(groupExec);
      throw e;
    }

    try {
      // Register before start so the very first leader heartbeat tick is dispatched on the next
      // sweep.
      scheduler.register((RaftNodeImpl) node);
    } catch (RuntimeException e) {
      try {
        transport.undiscoverNode(node);
      } catch (RuntimeException ignored) {
        // best-effort
      }
      releaseGroupExecutor(groupExec);
      throw e;
    }

    try {
      node.start();
    } catch (RuntimeException e) {
      try {
        scheduler.unregister((RaftNodeImpl) node);
      } catch (RuntimeException ignored) {
        // best-effort
      }
      try {
        transport.undiscoverNode(node);
      } catch (RuntimeException ignored) {
        // best-effort
      }
      releaseGroupExecutor(groupExec);
      throw e;
    }
    return new GroupHandle(groupId, node, spi);
  }

  /**
   * Releases a per-group executor that was created by {@link MultiGroupExecutor#executorFor} but
   * never associated with a successfully-started {@link RaftNode}. Triggers the per-group
   * executor's own self-unregister path via {@link RaftNodeLifecycleAware}, which is what the Raft
   * core would invoke on a normal terminate.
   */
  private static void releaseGroupExecutor(RaftNodeExecutor groupExec) {
    if (groupExec instanceof RaftNodeLifecycleAware) {
      try {
        ((RaftNodeLifecycleAware) groupExec).onRaftNodeTerminate();
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
  }

  @Override
  public boolean removeGroup(@NonNull Object groupId) {
    requireNonNull(groupId, "groupId");
    long startMs = EnvironmentEdgeManager.currentTime();
    // Hold the per-key bin lock for the entire teardown by tearing down inside the compute
    // callback. This serializes against any concurrent addGroup() on the same key, so a racing
    // addGroup cannot allocate a new RaftNode that latches onto the per-group executor we are
    // about to terminate (which would silently drop the new RaftNode's first task and hang any
    // future terminate of it). The CHM bin lock may briefly block addGroup of another id that
    // happens to share the same bin; in practice the teardown is short.
    final boolean[] removed = { false };
    groups.compute(groupId, (gid, handle) -> {
      if (handle == null) {
        return null;
      }
      try {
        handle.getRaftNode().terminate().join();
      } catch (RuntimeException e) {
        LOG.debug("RaftNode.terminate completed exceptionally for {}", gid, e);
      }
      detachAfterTerminate(handle);
      removed[0] = true;
      return null;
    });
    if (!removed[0]) {
      return false;
    }
    metrics.updateRemoveGroup(EnvironmentEdgeManager.currentTime() - startMs);
    return true;
  }

  @Nullable
  @Override
  public GroupHandle getGroup(@NonNull Object groupId) {
    return groups.get(requireNonNull(groupId, "groupId"));
  }

  @Override
  public boolean hasGroup(@NonNull Object groupId) {
    return groups.containsKey(requireNonNull(groupId, "groupId"));
  }

  @Override
  public int groupCount() {
    return groups.size();
  }

  @NonNull
  @Override
  public Iterable<GroupHandle> groups() {
    return Collections.unmodifiableCollection(new ArrayList<>(groups.values()));
  }

  @Override
  public String getEndpointId() {
    return String.valueOf(localEndpoint.getId());
  }

  @Override
  public int getActiveGroups() {
    return groups.size();
  }

  @Override
  public int getMaxGroups() {
    return serverConfig.getMaxGroups();
  }

  @Override
  public long getRestoredGroups() {
    return restoredStates.size();
  }

  @Override
  public String getLifecycleState() {
    return state.get().name();
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<Object>> transferLeadership(@NonNull Object groupId,
    @NonNull RaftEndpoint target) {
    requireNonNull(groupId, "groupId");
    requireNonNull(target, "target");
    GroupHandle h = groups.get(groupId);
    if (h == null) {
      throw new IllegalArgumentException("Unknown groupId: " + groupId);
    }
    long startMs = EnvironmentEdgeManager.currentTime();
    return h.getRaftNode().transferLeadership(target).whenComplete(
      (r, t) -> metrics.updateTransferLeadership(EnvironmentEdgeManager.currentTime() - startMs));
  }

  @NonNull
  @Override
  public CompletableFuture<Ordered<RaftNodeReport>> status(@NonNull Object groupId) {
    requireNonNull(groupId, "groupId");
    GroupHandle h = groups.get(groupId);
    if (h == null) {
      throw new IllegalArgumentException("Unknown groupId: " + groupId);
    }
    return h.getRaftNode().getReport();
  }

  /** Default group-id-to-bytes mapper: {@code String.valueOf(groupId).getBytes(UTF_8)}. */
  public static Function<Object, byte[]> defaultGroupIdEncoder() {
    return groupId -> String.valueOf(groupId).getBytes(StandardCharsets.UTF_8);
  }
}
