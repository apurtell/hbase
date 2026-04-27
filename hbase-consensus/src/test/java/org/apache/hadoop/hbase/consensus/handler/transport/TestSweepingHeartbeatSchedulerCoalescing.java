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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.apache.hbase.thirdparty.io.netty.bootstrap.ServerBootstrap;
import org.apache.hbase.thirdparty.io.netty.channel.Channel;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelHandlerContext;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelInitializer;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelOption;
import org.apache.hbase.thirdparty.io.netty.channel.SimpleChannelInboundHandler;
import org.apache.hbase.thirdparty.io.netty.channel.nio.NioEventLoopGroup;
import org.apache.hbase.thirdparty.io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Wire-level proof that the sweeping heartbeat path is O(peers) frames per tick: when {@code N}
 * different leader Raft groups all enqueue a {@link LeaderHeartbeat} for the same peer in the same
 * flush window, the {@link CoalescingTransport} emits exactly one {@code HEARTBEAT_BATCH}
 * {@link ConsensusProtos.ConsensusFrame} carrying {@code N} group entries — not {@code N} separate
 * frames.
 */
@Tag(SmallTests.TAG)
public class TestSweepingHeartbeatSchedulerCoalescing extends TestBase {

  private static final int N_GROUPS = 10;

  private CoalescingTransport sender;
  private NioEventLoopGroup serverGroup;
  private Channel serverChannel;

  @AfterEach
  public void tearDown() {
    if (sender != null) {
      try {
        sender.stop();
      } catch (RuntimeException ignored) {
      }
      sender = null;
    }
    if (serverChannel != null) {
      serverChannel.close().awaitUninterruptibly();
      serverChannel = null;
    }
    if (serverGroup != null) {
      serverGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
      serverGroup = null;
    }
  }

  @Test
  public void testManyGroupHeartbeatsCoalesceToOneFramePerPeer() throws Exception {
    AtomicInteger heartbeatBatchFrameCount = new AtomicInteger();
    AtomicInteger totalGroupsAcrossFrames = new AtomicInteger();
    AtomicInteger otherFrameCount = new AtomicInteger();

    // Stand up a tiny Netty server speaking the ConsensusFrame codec; count inbound frames.
    serverGroup = new NioEventLoopGroup(1);
    ServerBootstrap b = new ServerBootstrap();
    b.group(serverGroup).channel(NioServerSocketChannel.class)
      .childOption(ChannelOption.TCP_NODELAY, true).childHandler(new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel ch) {
          ch.pipeline().addLast("frame-decoder", new ConsensusFrameDecoder(64 * 1024 * 1024))
            .addLast("counter", new SimpleChannelInboundHandler<ConsensusProtos.ConsensusFrame>() {
              @Override
              protected void channelRead0(ChannelHandlerContext ctx,
                ConsensusProtos.ConsensusFrame frame) {
                if (frame.getKind() == ConsensusProtos.ConsensusFrame.Kind.HEARTBEAT_BATCH) {
                  heartbeatBatchFrameCount.incrementAndGet();
                  totalGroupsAcrossFrames.addAndGet(frame.getHeartbeatBatch().getGroupsCount());
                } else {
                  otherFrameCount.incrementAndGet();
                }
              }
            });
        }
      });
    serverChannel = b.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly().channel();
    InetSocketAddress serverAddr = (InetSocketAddress) serverChannel.localAddress();

    LocalRaftEndpoint senderEp = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint peerEp = LocalRaftEndpoint.newEndpoint();
    Configuration conf = HBaseConfiguration.create();
    conf.setBoolean(TransportConfig.NATIVE_TRANSPORT_KEY, false);
    // BATCH_MS only governs the periodic backstop flush tick (and connect cadence for the
    // warmup)
    conf.setLong(TransportConfig.BATCH_MS_KEY, 250L);
    conf.setInt(TransportConfig.IO_THREADS_KEY, 1);
    Map<RaftEndpoint, InetSocketAddress> addrs = new HashMap<>();
    addrs.put(peerEp, serverAddr);
    EndpointResolver resolver = ep -> {
      InetSocketAddress addr = addrs.get(ep);
      if (addr == null) {
        throw new UnknownEndpointException(ep);
      }
      return addr;
    };
    sender = new CoalescingTransport(senderEp, new InetSocketAddress("127.0.0.1", 0), resolver,
      OperationCodecs.defaultCodecs(), conf);
    sender.start();

    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    // Warmup: dispatch a single heartbeat to bring up the outbound connection. With an empty
    // mailbox the periodic tick is what triggers the initial connect attempt.
    LeaderHeartbeat warmup = factory.createLeaderHeartbeatBuilder().setGroupId("warmup")
      .setSender(senderEp).setTerm(1).setCommitIndex(0L).build();
    sender.send(peerEp, warmup);
    await().atMost(5, TimeUnit.SECONDS).until(() -> heartbeatBatchFrameCount.get() >= 1);
    // Wait until the OutboundChannel's underlying Netty channel exists and is active so we can
    // grab its event loop for the deterministic burst below.
    await().atMost(5, TimeUnit.SECONDS).until(() -> {
      OutboundChannel out = sender.peerChannelOrNull(peerEp);
      return out != null && out.currentChannel() != null && out.currentChannel().isActive();
    });
    // Reset counters; the rest of the test measures ONLY the steady-state batch.
    heartbeatBatchFrameCount.set(0);
    totalGroupsAcrossFrames.set(0);
    otherFrameCount.set(0);

    // Deterministic coalescing. Enqueue all N heartbeats from inside a single task running on
    // the outbound channel's event loop. The first enqueue CAS-flips OutboundChannel.draining
    // and submits a drain task via Channel#eventLoop().execute(...). Because we're already on
    // the event loop, that drain task is deferred until our enqueue task returns. The other
    // N-1 enqueues observe draining==true and skip submission entirely. When the event loop
    // finally runs the deferred drain it polls all N pending messages from the MPSC mailbox in
    // one pass and emits a single HEARTBEAT_BATCH frame whose groups list size == N. No timing
    // assumptions and no flush-window guesswork.
    OutboundChannel outbound = sender.peerChannelOrNull(peerEp);
    Channel outboundCh = outbound.currentChannel();
    outboundCh.eventLoop().submit(() -> {
      for (int g = 0; g < N_GROUPS; g++) {
        LeaderHeartbeat hb = factory.createLeaderHeartbeatBuilder().setGroupId("g-" + g)
          .setSender(senderEp).setTerm(1).setCommitIndex(0L).build();
        sender.send(peerEp, hb);
      }
    }).sync();

    await().atMost(5, TimeUnit.SECONDS).until(() -> totalGroupsAcrossFrames.get() >= N_GROUPS);

    assertThat(heartbeatBatchFrameCount.get())
      .as("N=%d heartbeats from N groups must coalesce into a single HEARTBEAT_BATCH frame",
        N_GROUPS)
      .isEqualTo(1);
    assertThat(totalGroupsAcrossFrames.get())
      .as("the single HEARTBEAT_BATCH frame must carry exactly N=%d group entries", N_GROUPS)
      .isEqualTo(N_GROUPS);
    assertThat(otherFrameCount.get()).as("no non-HEARTBEAT_BATCH frames expected during the burst")
      .isZero();
  }
}
