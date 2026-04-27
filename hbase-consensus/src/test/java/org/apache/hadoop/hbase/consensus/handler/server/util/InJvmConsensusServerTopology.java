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
package org.apache.hadoop.hbase.consensus.handler.server.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.consensus.handler.server.ConsensusServer;
import org.apache.hadoop.hbase.consensus.handler.store.LogStoreConfig;
import org.apache.hadoop.hbase.consensus.handler.transport.IdentityByteCodec;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.hadoop.hbase.consensus.handler.transport.TransportConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder/teardown helper for an in-JVM topology of {@link ConsensusServer}s wired over real Netty
 * and {@link org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore}, sharing a single
 * {@link MapEndpointResolver}.
 * <p>
 * After {@link Builder#build()} returns, every server's transport has bound to a loopback ephemeral
 * port, every server's address is published into the shared resolver, and every server has
 * completed its log-store load. The caller may immediately invoke
 * {@link ConsensusServer#addGroup(Object, java.util.Collection, org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi)
 * addGroup}.
 * <p>
 * {@link #close()} terminates every group on every server, stops every server in reverse order,
 * removes every published resolver entry, and best-effort deletes every per-server temp directory.
 */
public final class InJvmConsensusServerTopology implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(InJvmConsensusServerTopology.class);

  /** Per-server bookkeeping the test caller needs after {@link Builder#build()}. */
  public static final class Node {
    private final RaftEndpoint endpoint;
    private final ConsensusServer server;
    private final Path logDir;

    Node(RaftEndpoint endpoint, ConsensusServer server, Path logDir) {
      this.endpoint = endpoint;
      this.server = server;
      this.logDir = logDir;
    }

    @NonNull
    public RaftEndpoint endpoint() {
      return endpoint;
    }

    @NonNull
    public ConsensusServer server() {
      return server;
    }

    @NonNull
    public Path logDir() {
      return logDir;
    }

    @NonNull
    public InetSocketAddress address() {
      return server.getBindAddress();
    }
  }

  private final List<Node> nodes;
  private final MapEndpointResolver resolver;
  private final Path baseDir;
  private final boolean deleteBaseDir;

  private InJvmConsensusServerTopology(List<Node> nodes, MapEndpointResolver resolver, Path baseDir,
    boolean deleteBaseDir) {
    this.nodes = nodes;
    this.resolver = resolver;
    this.baseDir = baseDir;
    this.deleteBaseDir = deleteBaseDir;
  }

  /** Returns the per-node handles. */
  @NonNull
  public List<Node> nodes() {
    return Collections.unmodifiableList(nodes);
  }

  /** Returns the list of endpoints, in node order. */
  @NonNull
  public List<RaftEndpoint> endpoints() {
    List<RaftEndpoint> out = new ArrayList<>(nodes.size());
    for (Node n : nodes) {
      out.add(n.endpoint());
    }
    return out;
  }

  /** Returns the shared resolver. */
  @NonNull
  public MapEndpointResolver resolver() {
    return resolver;
  }

  /** Returns the base directory under which each server's per-server log dir lives. */
  @NonNull
  public Path baseDir() {
    return baseDir;
  }

  @Override
  public void close() {
    // Reverse order so dependents (later-built nodes) tear down before earlier ones.
    for (int i = nodes.size() - 1; i >= 0; i--) {
      Node n = nodes.get(i);
      try {
        n.server.close();
      } catch (RuntimeException e) {
        LOG.warn("Error closing ConsensusServer for {}", n.endpoint().getId(), e);
      }
      try {
        resolver.remove(n.endpoint);
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
    if (deleteBaseDir) {
      try {
        deleteRecursively(baseDir);
      } catch (IOException e) {
        LOG.warn("Error deleting base dir {}", baseDir, e);
      }
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // best-effort
        }
      });
    }
  }

  /** Returns a new {@link Builder}. */
  @NonNull
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for {@link InJvmConsensusServerTopology}. */
  public static final class Builder {

    private int nodeCount = 3;
    private Path baseDir;
    private boolean deleteBaseDirOnClose = true;
    private Configuration baseConf;
    private OperationCodec codec;
    private RaftConfig raftConfig;
    private MapEndpointResolver resolver;

    private Builder() {
    }

    /** Sets the number of {@link ConsensusServer}s in the topology. Default {@code 3}. */
    @NonNull
    public Builder setNodeCount(int nodeCount) {
      if (nodeCount < 1) {
        throw new IllegalArgumentException("nodeCount must be >= 1, got " + nodeCount);
      }
      this.nodeCount = nodeCount;
      return this;
    }

    /**
     * Sets the base directory under which each server's per-server log dir lives. The builder
     * creates {@code baseDir/<endpointId>/} for every server. If unset, a temp dir is created and
     * deleted on close.
     */
    @NonNull
    public Builder setBaseDir(@NonNull Path baseDir, boolean deleteOnClose) {
      this.baseDir = baseDir;
      this.deleteBaseDirOnClose = deleteOnClose;
      return this;
    }

    /**
     * Sets the base {@link Configuration} forwarded to every server. The builder will <em>copy</em>
     * this object and per-server-stamp it with {@link LogStoreConfig#LOG_DIR_KEY}.
     */
    @NonNull
    public Builder setBaseConf(@NonNull Configuration conf) {
      this.baseConf = conf;
      return this;
    }

    /** Sets the per-group {@link OperationCodec}. Defaults to {@link IdentityByteCodec}. */
    @NonNull
    public Builder setOperationCodec(@NonNull OperationCodec codec) {
      this.codec = codec;
      return this;
    }

    /**
     * Sets the {@link RaftConfig} used for every group on every server. Defaults to a fast-elect
     * config tuned for in-JVM tests (election timeout 500 ms, heartbeat period 100 ms, heartbeat
     * timeout 1500 ms).
     */
    @NonNull
    public Builder setRaftConfig(@NonNull RaftConfig raftConfig) {
      this.raftConfig = raftConfig;
      return this;
    }

    /**
     * Pre-supplies the shared {@link MapEndpointResolver}. Useful if an external orchestrator owns
     * the resolver lifecycle. Defaults to a freshly-allocated one.
     */
    @NonNull
    public Builder setResolver(@NonNull MapEndpointResolver resolver) {
      this.resolver = resolver;
      return this;
    }

    /** Builds and starts the topology. */
    @NonNull
    public InJvmConsensusServerTopology build() throws IOException {
      Path baseDirToUse = baseDir;
      boolean deleteBase = deleteBaseDirOnClose;
      if (baseDirToUse == null) {
        baseDirToUse = Files.createTempDirectory("hbase-consensus-server-topo-");
        deleteBase = true;
      } else {
        Files.createDirectories(baseDirToUse);
      }
      Configuration confTemplate = baseConf != null ? new Configuration(baseConf) : defaultConf();
      OperationCodec codecToUse = codec != null ? codec : new IdentityByteCodec();
      RaftConfig raftCfg = raftConfig != null ? raftConfig : DEFAULT_TEST_RAFT_CONFIG;
      MapEndpointResolver resolverToUse = resolver != null ? resolver : new MapEndpointResolver();

      List<Node> built = new ArrayList<>(nodeCount);
      try {
        for (int i = 0; i < nodeCount; i++) {
          LocalRaftEndpoint ep = LocalRaftEndpoint.newEndpoint();
          Path logDir = baseDirToUse.resolve(String.valueOf(ep.getId()));
          Files.createDirectories(logDir);
          Configuration nodeConf = new Configuration(confTemplate);
          nodeConf.set(LogStoreConfig.LOG_DIR_KEY, logDir.toAbsolutePath().toString());
          ConsensusServer server =
            new ConsensusServer(nodeConf, ep, new InetSocketAddress("127.0.0.1", 0), resolverToUse,
              codecToUse, /* executor */ null, ConsensusServer.defaultGroupIdEncoder(), raftCfg);
          server.start();
          resolverToUse.put(ep, server.getBindAddress());
          built.add(new Node(ep, server, logDir));
        }
      } catch (RuntimeException | IOException e) {
        // Roll back any partially-built nodes.
        for (int i = built.size() - 1; i >= 0; i--) {
          try {
            built.get(i).server.close();
          } catch (RuntimeException ignored) {
            // best-effort
          }
        }
        throw e;
      }
      return new InJvmConsensusServerTopology(built, resolverToUse, baseDirToUse, deleteBase);
    }
  }

  /** Fast-elect config tuned for in-JVM tests. */
  public static final RaftConfig DEFAULT_TEST_RAFT_CONFIG =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(500).setLeaderHeartbeatPeriodMillis(100)
      .setLeaderHeartbeatTimeoutMillis(1500).build();

  /** Default base configuration for tests: NIO Netty, fast batch, fast heartbeat tick. */
  @NonNull
  public static Configuration defaultConf() {
    Configuration c = HBaseConfiguration.create();
    c.setBoolean(TransportConfig.NATIVE_TRANSPORT_KEY, false);
    c.setLong(TransportConfig.BATCH_MS_KEY, 5L);
    // Match the production default explicitly so test runs make the deadline-based fail-safe
    // wakeup visible in OutboundChannelStats.getForcedFlushesByDeadline rather than relying on
    // a silent default.
    c.setLong(TransportConfig.FLUSH_DEADLINE_MS_KEY, 250L);
    c.setInt(TransportConfig.IO_THREADS_KEY, 2);
    c.setLong(LogStoreConfig.BATCH_MS_KEY, 5L);
    c.setInt("hbase.consensus.heartbeat.interval.ms", 100);
    return c;
  }

  /**
   * Convenience: builds a temp directory under the given parent (or a JVM-temp dir if {@code
   * parent} is null) for a one-shot test topology.
   */
  @NonNull
  public static File newTempLogParent(@NonNull String prefix) throws IOException {
    return Files.createTempDirectory(prefix).toFile();
  }
}
