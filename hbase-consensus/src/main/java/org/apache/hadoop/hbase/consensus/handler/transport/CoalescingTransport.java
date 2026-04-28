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
package org.apache.hadoop.hbase.consensus.handler.transport;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatAckFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.JVM;
import org.apache.hadoop.hbase.util.ReflectionUtils;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.io.netty.bootstrap.Bootstrap;
import org.apache.hbase.thirdparty.io.netty.bootstrap.ServerBootstrap;
import org.apache.hbase.thirdparty.io.netty.buffer.ByteBufAllocator;
import org.apache.hbase.thirdparty.io.netty.buffer.PooledByteBufAllocator;
import org.apache.hbase.thirdparty.io.netty.buffer.UnpooledByteBufAllocator;
import org.apache.hbase.thirdparty.io.netty.channel.Channel;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelInitializer;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelOption;
import org.apache.hbase.thirdparty.io.netty.channel.EventLoopGroup;
import org.apache.hbase.thirdparty.io.netty.channel.ServerChannel;
import org.apache.hbase.thirdparty.io.netty.channel.WriteBufferWaterMark;
import org.apache.hbase.thirdparty.io.netty.channel.epoll.EpollEventLoopGroup;
import org.apache.hbase.thirdparty.io.netty.channel.epoll.EpollServerSocketChannel;
import org.apache.hbase.thirdparty.io.netty.channel.epoll.EpollSocketChannel;
import org.apache.hbase.thirdparty.io.netty.channel.nio.NioEventLoopGroup;
import org.apache.hbase.thirdparty.io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.hbase.thirdparty.io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.hbase.thirdparty.io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Netty + Protobuf coalescing {@link Transport} implementation.
 * <p>
 * One instance per local node owns:
 * <ul>
 * <li>One server bootstrap bound at the configured address accepts inbound consensus frames.</li>
 * <li>One client bootstrap producing per-peer outbound channels.</li>
 * <li>One shared {@link EventLoopGroup} (Linux Epoll on supported CPUs, NIO otherwise) handling
 * both accept and IO.</li>
 * <li>A {@link RegistryDispatcher} that the user populates with local {@link RaftNode}s via
 * {@link #discoverNode(RaftNode)} / {@link #undiscoverNode(RaftNode)}.</li>
 * </ul>
 * <p>
 * The transport drives two distinct outbound paths:
 * <ul>
 * <li><b>Bulk lane.</b> {@link #send(RaftEndpoint, RaftMessage)} enqueues append-entries and
 * immediate frames into a per-peer mailbox. The mailbox drains immediately on enqueue so the
 * {@code RaftNode.replicate(...)} round-trip pays no coalescing costs.</li>
 * <li><b>Bulk heartbeat path.</b> {@link #sendBulkHeartbeat} and {@link #sendBulkHeartbeatAck}
 * bypass the mailbox. The per-server timing wheel hands each call a fully aggregated bulk envelope,
 * the transport encodes it on the caller thread, and the per-peer event loop performs one
 * write+flush.</li>
 * </ul>
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
@InterfaceStability.Evolving
public final class CoalescingTransport implements Transport, RaftNodeLifecycleAware {

  private static final Logger LOG = LoggerFactory.getLogger(CoalescingTransport.class);

  private final RaftEndpoint localEndpoint;
  private final InetSocketAddress bindAddress;
  private final EndpointResolver resolver;
  private final TransportConfig config;
  private final PayloadCompressor compressor;
  private final ProtoConverter converter;
  private final RegistryDispatcher registry = new RegistryDispatcher();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private final ConcurrentMap<RaftEndpoint, OutboundChannel> peers = new ConcurrentHashMap<>();
  /**
   * Wall-clock time (ms) at which this server booted. Owned by the enclosing {@code
   * ConsensusServer} (or supplied by tests) so the transport, the bulk-heartbeat scheduler, and any
   * other component that stamps an envelope-level keepalive header all agree on a single value.
   */
  private final long bootEpochMillis;

  private EventLoopGroup eventLoopGroup;
  private Class<? extends ServerChannel> serverChannelClass;
  private Class<? extends Channel> clientChannelClass;
  private ServerBootstrap serverBootstrap;
  private Bootstrap clientBootstrap;
  private Channel serverChannel;

  public CoalescingTransport(@NonNull RaftEndpoint localEndpoint,
    @NonNull InetSocketAddress bindAddress, @NonNull EndpointResolver resolver,
    @NonNull OperationCodec operationCodec, @NonNull Configuration hadoopConf) {
    this(localEndpoint, bindAddress, resolver, operationCodec, hadoopConf,
      EnvironmentEdgeManager.currentTime());
  }

  public CoalescingTransport(@NonNull RaftEndpoint localEndpoint,
    @NonNull InetSocketAddress bindAddress, @NonNull EndpointResolver resolver,
    @NonNull OperationCodec operationCodec, @NonNull Configuration hadoopConf,
    long bootEpochMillis) {
    this(localEndpoint, bindAddress, resolver, operationCodec, new TransportConfig(hadoopConf),
      new DefaultRaftModelFactory(), bootEpochMillis);
  }

  CoalescingTransport(@NonNull RaftEndpoint localEndpoint, @NonNull InetSocketAddress bindAddress,
    @NonNull EndpointResolver resolver, @NonNull OperationCodec operationCodec,
    @NonNull TransportConfig config, @NonNull DefaultRaftModelFactory modelFactory,
    long bootEpochMillis) {
    requireNonNull(operationCodec, "operationCodec");
    this.localEndpoint = requireNonNull(localEndpoint);
    this.bindAddress = requireNonNull(bindAddress);
    this.resolver = requireNonNull(resolver);
    this.config = requireNonNull(config);
    this.compressor = new PayloadCompressor(config.getCompression());
    this.converter = new ProtoConverter(modelFactory, operationCodec, compressor);
    this.bootEpochMillis = bootEpochMillis;
  }

  public InetSocketAddress getBindAddress() {
    Channel ch = serverChannel;
    if (ch != null && ch.localAddress() instanceof InetSocketAddress) {
      return (InetSocketAddress) ch.localAddress();
    }
    return bindAddress;
  }

  public RaftEndpoint getLocalEndpoint() {
    return localEndpoint;
  }

  /**
   * Registers the given local Raft node so frames addressed to its group id are delivered to it.
   */
  public void discoverNode(@NonNull RaftNode node) {
    registry.discoverNode(node);
  }

  /** Unregisters a previously discovered Raft node. */
  public void undiscoverNode(@NonNull RaftNode node) {
    registry.undiscoverNode(node);
  }

  @Override
  public void send(@NonNull RaftEndpoint target, @NonNull RaftMessage message) {
    if (stopped.get()) {
      return;
    }
    if (localEndpoint.equals(target)) {
      throw new IllegalArgumentException(
        localEndpoint.getId() + " cannot send " + message + " to itself");
    }
    OutboundChannel ch;
    try {
      ch = peerChannel(target);
    } catch (UnknownEndpointException e) {
      LOG.debug("Dropping message to unknown endpoint {}", target.getId());
      return;
    }
    boolean immediate = isImmediate(message);
    ch.enqueue(message, immediate);
  }

  @Override
  public boolean isReachable(@NonNull RaftEndpoint endpoint) {
    OutboundChannel ch = peers.get(endpoint);
    return ch != null && ch.isActive();
  }

  @Override
  public void sendBulkHeartbeat(@NonNull RaftEndpoint target, @NonNull BulkHeartbeatFrame frame) {
    if (stopped.get()) {
      return;
    }
    if (localEndpoint.equals(target)) {
      throw new IllegalArgumentException(
        localEndpoint.getId() + " cannot send a bulk heartbeat to itself");
    }
    OutboundChannel ch;
    try {
      ch = peerChannel(target);
    } catch (UnknownEndpointException e) {
      LOG.debug("Dropping bulk heartbeat to unknown endpoint {}", target.getId());
      return;
    }
    ch.sendBulkHeartbeat(frame);
  }

  @Override
  public void sendBulkHeartbeatAck(@NonNull RaftEndpoint target,
    @NonNull BulkHeartbeatAckFrame frame) {
    if (stopped.get()) {
      return;
    }
    if (localEndpoint.equals(target)) {
      throw new IllegalArgumentException(
        localEndpoint.getId() + " cannot send a bulk heartbeat ack to itself");
    }
    OutboundChannel ch;
    try {
      ch = peerChannel(target);
    } catch (UnknownEndpointException e) {
      LOG.debug("Dropping bulk heartbeat ack to unknown endpoint {}", target.getId());
      return;
    }
    ch.sendBulkHeartbeatAck(frame);
  }

  @Override
  public void onRaftNodeStart() {
    // Transport lifecycle is managed at the ConsensusServer level
  }

  @Override
  public void onRaftNodeTerminate() {
    // See onRaftNodeStart.
  }

  /**
   * Starts the Netty event loops and binds the server. Idempotent.
   * @throws IllegalStateException if the bind fails
   */
  public synchronized void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    boolean useEpoll = useEpoll(config);
    DefaultThreadFactory threadFactory = new DefaultThreadFactory(
      "hbase-consensus-io-" + localEndpoint.getId(), true, Thread.MAX_PRIORITY);
    if (useEpoll) {
      eventLoopGroup = new EpollEventLoopGroup(config.getIoThreads(), threadFactory);
      serverChannelClass = EpollServerSocketChannel.class;
      clientChannelClass = EpollSocketChannel.class;
    } else {
      eventLoopGroup = new NioEventLoopGroup(config.getIoThreads(), threadFactory);
      serverChannelClass = NioServerSocketChannel.class;
      clientChannelClass = NioSocketChannel.class;
    }

    InboundHandler inboundHandler =
      new InboundHandler(registry, converter, this, localEndpoint, bootEpochMillis);
    ConsensusFrameEncoder frameEncoder = new ConsensusFrameEncoder();
    WriteBufferWaterMark watermarks = new WriteBufferWaterMark(config.getWriteLowWatermarkBytes(),
      config.getWriteHighWatermarkBytes());
    ByteBufAllocator allocator = resolveAllocator(config.getAllocator());

    serverBootstrap = new ServerBootstrap();
    serverBootstrap.group(eventLoopGroup).channel(serverChannelClass)
      .childOption(ChannelOption.TCP_NODELAY, true).childOption(ChannelOption.SO_KEEPALIVE, true)
      .childOption(ChannelOption.SO_REUSEADDR, true)
      .childHandler(new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel ch) {
          ch.config().setWriteBufferWaterMark(watermarks);
          ch.config().setAllocator(allocator);
          ch.pipeline()
            .addLast("frame-decoder", new ConsensusFrameDecoder(config.getMaxFrameBytes()))
            .addLast("frame-encoder", frameEncoder).addLast("inbound", inboundHandler);
        }
      });

    clientBootstrap = new Bootstrap();
    clientBootstrap.group(eventLoopGroup).channel(clientChannelClass)
      .option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.SO_KEEPALIVE, true)
      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMs())
      .handler(new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel ch) {
          ch.config().setWriteBufferWaterMark(watermarks);
          ch.config().setAllocator(allocator);
          ch.pipeline()
            .addLast("frame-decoder", new ConsensusFrameDecoder(config.getMaxFrameBytes()))
            .addLast("frame-encoder", frameEncoder).addLast("inbound", inboundHandler);
        }
      });

    try {
      serverChannel = serverBootstrap.bind(bindAddress).syncUninterruptibly().channel();
    } catch (RuntimeException e) {
      shutdownGroups();
      started.set(false);
      throw new IllegalStateException("Failed to bind " + bindAddress, e);
    }

    LOG.info("CoalescingTransport for {} bound at {} (transport={}, allocator={})",
      localEndpoint.getId(), serverChannel.localAddress(), useEpoll ? "epoll" : "nio",
      config.getAllocator());
  }

  /** Stops the Netty event loops and closes the server channel. Idempotent. */
  public synchronized void stop() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    for (OutboundChannel ch : peers.values()) {
      ch.close();
    }
    peers.clear();
    if (serverChannel != null) {
      try {
        serverChannel.close().syncUninterruptibly();
      } catch (RuntimeException e) {
        LOG.warn("Error closing server channel", e);
      }
      serverChannel = null;
    }
    shutdownGroups();
    LOG.info("CoalescingTransport for {} stopped", localEndpoint.getId());
  }

  private void shutdownGroups() {
    if (eventLoopGroup != null) {
      try {
        eventLoopGroup.shutdownGracefully();
      } catch (RuntimeException e) {
        LOG.debug("event loop group shutdown failed", e);
      } finally {
        eventLoopGroup = null;
      }
    }
  }

  private static boolean useEpoll(TransportConfig config) {
    return config.isNativeTransport() && JVM.isLinux() && (JVM.isAmd64() || JVM.isAarch64());
  }

  /**
   * Resolves the configured allocator selector to a concrete {@link ByteBufAllocator}. The "heap"
   * mode here is a pooled allocator with {@code preferDirect=false} (effectively the {@code
   * HeapByteBufAllocator} pattern used by {@code hbase-server.ipc.HeapByteBufAllocator}.
   */
  private static ByteBufAllocator resolveAllocator(String value) {
    String trimmed = value == null ? TransportConfig.ALLOCATOR_DEFAULT : value.trim();
    if (TransportConfig.ALLOCATOR_POOLED.equalsIgnoreCase(trimmed)) {
      return PooledByteBufAllocator.DEFAULT;
    }
    if (TransportConfig.ALLOCATOR_UNPOOLED.equalsIgnoreCase(trimmed)) {
      return UnpooledByteBufAllocator.DEFAULT;
    }
    if (TransportConfig.ALLOCATOR_HEAP.equalsIgnoreCase(trimmed)) {
      return new PooledByteBufAllocator(false);
    }
    try {
      return ReflectionUtils.newInstance(trimmed);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
        "Unknown " + TransportConfig.ALLOCATOR_KEY + " value: " + trimmed, e);
    }
  }

  private OutboundChannel peerChannel(RaftEndpoint target) throws UnknownEndpointException {
    OutboundChannel existing = peers.get(target);
    if (existing != null) {
      return existing;
    }
    InetSocketAddress address = resolver.resolve(target);
    OutboundChannel created = new OutboundChannel(address, clientBootstrap, converter, config);
    OutboundChannel previous = peers.putIfAbsent(target, created);
    return previous == null ? created : previous;
  }

  /**
   * Decide whether {@code message} rides as its own {@link ConsensusProtos.ConsensusFrame} or
   * coalesces into a batch envelope. Append-entries coalesce into the append batch. Bulk heartbeats
   * and bulk heartbeat acks do not flow through this path (see {@link Transport#sendBulkHeartbeat}
   * and {@link Transport#sendBulkHeartbeatAck}).
   */
  private static boolean isImmediate(RaftMessage message) {
    return !(message instanceof AppendEntriesRequest);
  }

  /**
   * Returns an immutable point-in-time snapshot of every per-peer outbound channel's frame
   * counters.
   */
  @NonNull
  public Map<RaftEndpoint, OutboundChannelStats> getOutboundStats() {
    if (peers.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<RaftEndpoint, OutboundChannelStats> out = new HashMap<>(peers.size());
    for (Map.Entry<RaftEndpoint, OutboundChannel> e : peers.entrySet()) {
      out.put(e.getKey(), e.getValue().stats());
    }
    return Collections.unmodifiableMap(out);
  }

}
