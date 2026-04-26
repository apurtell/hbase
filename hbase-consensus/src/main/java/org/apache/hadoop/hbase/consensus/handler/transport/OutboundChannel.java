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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.util.NettyFutureUtils;
import org.apache.yetus.audience.InterfaceAudience;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.io.netty.bootstrap.Bootstrap;
import org.apache.hbase.thirdparty.io.netty.channel.Channel;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelFuture;

/**
 * Per-peer outbound side of {@link CoalescingTransport}.
 * <p>
 * Owns a JCTools {@link MpscUnboundedArrayQueue} of pending messages, a current Netty
 * {@link Channel} reference, and a tiny reconnect state machine. The transport's flush scheduler
 * pings {@link #flushTick()} at {@code hbase.consensus.log.sync.batch.ms}; producers call
 * {@link #enqueue} from any thread.
 * <p>
 * Single-consumer drain is enforced by an {@link AtomicBoolean} latch: producers pile messages into
 * the MPSC, the first arrival CASes the latch on, and only the drain runnable polls the queue. The
 * drain is always run on the channel's event loop so per-peer FIFO holds even across reconnects.
 */
@InterfaceAudience.Private
final class OutboundChannel {

  private static final Logger LOG = LoggerFactory.getLogger(OutboundChannel.class);

  /** Lightweight enqueue record: every queued message is bucketed during the drain. */
  private static final class Pending {
    final RaftMessage message;
    final boolean immediate;

    Pending(RaftMessage message, boolean immediate) {
      this.message = message;
      this.immediate = immediate;
    }
  }

  private final RaftEndpoint peer;
  private final InetSocketAddress address;
  private final Bootstrap bootstrap;
  private final ProtoConverter converter;
  private final TransportConfig config;

  private final MpscUnboundedArrayQueue<Pending> mailbox;
  private final AtomicBoolean draining = new AtomicBoolean(false);
  private final AtomicReference<Channel> channelRef = new AtomicReference<>();
  private final AtomicBoolean connecting = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private volatile long nextConnectAtMillis = 0L;
  private volatile long currentBackoffMs;

  OutboundChannel(@NonNull RaftEndpoint peer, @NonNull InetSocketAddress address,
    @NonNull Bootstrap bootstrap, @NonNull ProtoConverter converter,
    @NonNull TransportConfig config) {
    this.peer = peer;
    this.address = address;
    this.bootstrap = bootstrap;
    this.converter = converter;
    this.config = config;
    this.mailbox = new MpscUnboundedArrayQueue<>(config.getMailboxChunkSize());
    this.currentBackoffMs = config.getReconnectBackoffMinMs();
  }

  /** Returns the peer this channel serves (debug / equality key in the registry) */
  RaftEndpoint peer() {
    return peer;
  }

  /** Returns the resolved remote address (cached at construction) */
  InetSocketAddress address() {
    return address;
  }

  /** Returns whether this channel is currently connected to its peer */
  boolean isActive() {
    Channel ch = channelRef.get();
    return ch != null && ch.isActive();
  }

  /** Visible for tests. */
  long nextConnectAtMillis() {
    return nextConnectAtMillis;
  }

  /** Visible for tests. */
  long currentBackoffMs() {
    return currentBackoffMs;
  }

  /** Visible for tests. */
  int pendingMailboxSize() {
    return mailbox.size();
  }

  /**
   * Enqueues {@code message} for the peer. Non-blocking. {@code immediate=true} hints the drain
   * that this message should be flushed in its own {@link ConsensusProtos.ConsensusFrame} rather
   * than coalesced into a batch (used for vote / install-snapshot / response messages).
   */
  void enqueue(@NonNull RaftMessage message, boolean immediate) {
    if (closed.get()) {
      return;
    }
    mailbox.offer(new Pending(message, immediate));
    Channel ch = channelRef.get();
    if (ch != null && ch.isActive() && ch.isWritable()) {
      scheduleDrainOn(ch);
    }
    // else: flush tick will pick this up after a connect completes.
  }

  /**
   * Called by the transport's scheduled flush tick. Drains everything that is currently queued and
   * flushes one channel write. May trigger a reconnect attempt if the channel is down and the
   * backoff window has elapsed.
   */
  void flushTick() {
    if (closed.get()) {
      return;
    }
    Channel ch = channelRef.get();
    if (ch == null || !ch.isActive()) {
      maybeConnect();
      return;
    }
    if (mailbox.peek() != null) {
      scheduleDrainOn(ch);
    }
  }

  /** Closes this channel. Idempotent. */
  void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Channel ch = channelRef.getAndSet(null);
    if (ch != null) {
      NettyFutureUtils.safeClose(ch);
    }
    mailbox.clear();
  }

  /**
   * Trigger a connect if (a) we have nothing pending in flight, (b) the backoff timer has elapsed,
   * and (c) we are not already connected.
   */
  private void maybeConnect() {
    if (closed.get()) {
      return;
    }
    if (System.currentTimeMillis() < nextConnectAtMillis) {
      return;
    }
    if (!connecting.compareAndSet(false, true)) {
      return;
    }
    try {
      ChannelFuture cf = bootstrap.connect(address);
      NettyFutureUtils.addListener(cf, future -> {
        connecting.set(false);
        if (future.isSuccess()) {
          Channel ch = cf.channel();
          channelRef.set(ch);
          currentBackoffMs = config.getReconnectBackoffMinMs();
          nextConnectAtMillis = 0L;
          NettyFutureUtils.addListener(ch.closeFuture(), closeFuture -> {
            channelRef.compareAndSet(ch, null);
            // Schedule next connect attempt at min backoff.
            nextConnectAtMillis = System.currentTimeMillis() + config.getReconnectBackoffMinMs();
          });
          // Drain whatever was queued while we were down.
          scheduleDrainOn(ch);
        } else {
          long backoff = currentBackoffMs;
          nextConnectAtMillis = System.currentTimeMillis() + backoff;
          long doubled = Math.min(backoff * 2L, config.getReconnectBackoffMaxMs());
          currentBackoffMs = doubled;
          if (LOG.isDebugEnabled()) {
            LOG.debug("connect to {} failed; next attempt in {}ms", address, backoff,
              future.cause());
          }
        }
      });
    } catch (Throwable t) {
      connecting.set(false);
      LOG.warn("bootstrap.connect threw for {}", address, t);
    }
  }

  /**
   * Schedule a drain pass on {@code ch}'s event loop. Lost-wakeup-safe via {@link #draining}
   * (mirrors the {@code GroupExecutor.scheduled} pattern). The drain itself runs single-threaded on
   * the event loop, satisfying JCTools MPSC's single-consumer invariant.
   */
  private void scheduleDrainOn(Channel ch) {
    if (closed.get()) {
      return;
    }
    if (draining.compareAndSet(false, true)) {
      try {
        ch.eventLoop().execute(() -> {
          try {
            drainOnce(ch);
          } finally {
            draining.set(false);
          }
          // Lost-wakeup-safe re-arm: if a producer enqueued after our last poll
          // but before we cleared the latch, schedule another drain pass.
          if (mailbox.peek() != null && ch.isActive() && ch.isWritable()) {
            scheduleDrainOn(ch);
          }
        });
      } catch (Throwable t) {
        // Event loop rejected, e.g. shutting down.
        draining.set(false);
        LOG.debug("drain submission rejected for {}", address, t);
      }
    }
  }

  /**
   * Single-pass drain on the event loop thread. Bails when the writable watermark is exceeded
   * (back-pressure) and re-arms on the next flush tick.
   */
  private void drainOnce(Channel ch) {
    if (!ch.isActive() || !ch.isWritable()) {
      return;
    }
    List<ConsensusProtos.GroupAppendEntriesPB> appendBucket = new ArrayList<>();
    List<ConsensusProtos.GroupHeartbeatPB> heartbeatBucket = new ArrayList<>();
    List<ConsensusProtos.GroupHeartbeatAckPB> heartbeatAckBucket = new ArrayList<>();
    List<ConsensusProtos.ConsensusFrame> immediates = new ArrayList<>();

    Pending p;
    while ((p = mailbox.relaxedPoll()) != null) {
      try {
        if (p.immediate) {
          immediates.add(converter.toFrame(p.message));
        } else if (p.message instanceof LeaderHeartbeat) {
          heartbeatBucket.add(converter.toGroupHeartbeatPB((LeaderHeartbeat) p.message));
        } else if (p.message instanceof LeaderHeartbeatAck) {
          heartbeatAckBucket.add(converter.toGroupHeartbeatAckPB((LeaderHeartbeatAck) p.message));
        } else if (p.message instanceof AppendEntriesRequest) {
          appendBucket.add(converter.toGroupAppendPB((AppendEntriesRequest) p.message));
        } else {
          immediates.add(converter.toFrame(p.message));
        }
      } catch (RuntimeException ex) {
        // Defensive: if conversion fails (e.g. compression, unknown op type), drop just this
        // message and keep draining the rest.
        LOG.warn("Failed to encode {} for {}; dropping", p.message.getClass().getName(), address,
          ex);
      }
    }

    if (!appendBucket.isEmpty()) {
      ConsensusProtos.ConsensusFrame frame = ConsensusProtos.ConsensusFrame.newBuilder()
        .setKind(ConsensusProtos.ConsensusFrame.Kind.BATCH_APPEND)
        .setBatchAppend(
          ConsensusProtos.BatchAppendEntriesPB.newBuilder().addAllGroups(appendBucket).build())
        .build();
      NettyFutureUtils.safeWrite(ch, frame);
    }
    if (!heartbeatBucket.isEmpty()) {
      ConsensusProtos.ConsensusFrame frame = ConsensusProtos.ConsensusFrame.newBuilder()
        .setKind(ConsensusProtos.ConsensusFrame.Kind.HEARTBEAT_BATCH)
        .setHeartbeatBatch(
          ConsensusProtos.HeartbeatBatchPB.newBuilder().addAllGroups(heartbeatBucket).build())
        .build();
      NettyFutureUtils.safeWrite(ch, frame);
    }
    if (!heartbeatAckBucket.isEmpty()) {
      ConsensusProtos.ConsensusFrame frame = ConsensusProtos.ConsensusFrame.newBuilder()
        .setKind(ConsensusProtos.ConsensusFrame.Kind.HEARTBEAT_ACK_BATCH)
        .setHeartbeatAckBatch(
          ConsensusProtos.BatchHeartbeatAckPB.newBuilder().addAllGroups(heartbeatAckBucket).build())
        .build();
      NettyFutureUtils.safeWrite(ch, frame);
    }
    for (ConsensusProtos.ConsensusFrame frame : immediates) {
      NettyFutureUtils.safeWrite(ch, frame);
    }
    if (
      !appendBucket.isEmpty() || !heartbeatBucket.isEmpty() || !heartbeatAckBucket.isEmpty()
        || !immediates.isEmpty()
    ) {
      ch.flush();
    }
  }

  /** Visible for tests. */
  @Nullable
  Channel currentChannel() {
    return channelRef.get();
  }
}
