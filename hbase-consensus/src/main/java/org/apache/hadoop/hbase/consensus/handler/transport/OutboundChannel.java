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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatAckFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatFrame;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
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
 * Owns one {@link MpscUnboundedArrayQueue} for the bulk lane (append-entries plus immediates), a
 * current Netty {@link Channel} reference, and a tiny reconnect state machine. Producers call
 * {@link #enqueue} from any thread.
 * <p>
 * <b>Bulk lane drain.</b> {@link AppendEntriesRequest}, {@link LeaderHeartbeatAck} (if any future
 * caller produces them through {@link #enqueue}), and every {@code immediate} message (vote /
 * pre-vote / install-snapshot / append responses) ride the bulk mailbox. Enqueue calls
 * {@link #scheduleDrainOn(Channel)}, so the message hits the wire on the next event-loop turn with
 * no wall-clock floor. This preserves the strict {@code RaftNode.replicate(...)} round-trip budget.
 * <p>
 * <b>Bulk heartbeat path.</b> {@link #sendBulkHeartbeat(BulkHeartbeatFrame)} and
 * {@link #sendBulkHeartbeatAck(BulkHeartbeatAckFrame)} bypass the mailbox entirely: the per-server
 * timing wheel has already aggregated every relevant group into a single envelope, so there is
 * nothing to coalesce. The encoded envelope is handed straight to the channel's event loop with a
 * single write+flush per call. One bulk frame per (server, peer) per tick is the design's target
 * steady-state heartbeat fanout.
 * <p>
 * Single-consumer drain on the bulk mailbox is enforced by an {@link AtomicBoolean} latch.
 * Producers pile messages into the MPSC. Only the first arrival of a fresh batch CASes the latch on
 * and submits a drain task; subsequent enqueues whose CAS loses are absorbed by the in-flight
 * drain's poll loop. The drain runs on the channel's event loop, so per-peer FIFO holds within the
 * lane even across reconnects.
 */
@InterfaceAudience.Private
final class OutboundChannel {

  private static final Logger LOG = LoggerFactory.getLogger(OutboundChannel.class);

  private final InetSocketAddress address;
  private final Bootstrap bootstrap;
  private final ProtoConverter converter;
  private final TransportConfig config;
  private final MpscUnboundedArrayQueue<RaftMessage> mailbox;
  private final AtomicBoolean draining = new AtomicBoolean(false);
  private final AtomicReference<Channel> channelRef = new AtomicReference<>();
  private final AtomicBoolean connecting = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private volatile long nextConnectAtMillis = 0L;
  // Read and written from netty future callbacks scheduled by bootstrap.connect(). Multiple
  // connect attempts can land on different event-loop threads (the bootstrap event-loop group is
  // multi-threaded), so the doubled-backoff updates and the read on the next failed-connect path
  // must be visible across threads. Keep volatile.
  private volatile long currentBackoffMs;

  private final AtomicLong heartbeatFrames = new AtomicLong();
  private final AtomicLong appendFrames = new AtomicLong();
  private final AtomicLong heartbeatAckFrames = new AtomicLong();
  private final AtomicLong appendsEnqueued = new AtomicLong();

  // Per-drain scratch buckets reused across {@link #drainOnce(Channel)} invocations.
  private final List<ConsensusProtos.GroupAppendEntriesPB> appendBucket = new ArrayList<>();
  private final List<ConsensusProtos.ConsensusFrame> immediateBucket = new ArrayList<>();

  OutboundChannel(@NonNull InetSocketAddress address, @NonNull Bootstrap bootstrap,
    @NonNull ProtoConverter converter, @NonNull TransportConfig config) {
    this.address = address;
    this.bootstrap = bootstrap;
    this.converter = converter;
    this.config = config;
    this.mailbox = new MpscUnboundedArrayQueue<>(config.getMailboxChunkSize());
    this.currentBackoffMs = config.getReconnectBackoffMinMs();
  }

  /** Returns whether this channel is currently connected to its peer */
  boolean isActive() {
    Channel ch = channelRef.get();
    return ch != null && ch.isActive();
  }

  /**
   * Enqueues {@code message} for the peer. Non-blocking.
   * <p>
   * {@code immediate=true} flushes this message in its own {@link ConsensusProtos.ConsensusFrame}
   * (used for vote / install-snapshot / response messages); coalescible append-entries join the
   * {@code BATCH_APPEND} envelope. Bulk heartbeats and bulk heartbeat acks do not flow through this
   * path; see {@link #sendBulkHeartbeat(BulkHeartbeatFrame)} and
   * {@link #sendBulkHeartbeatAck(BulkHeartbeatAckFrame)}.
   */
  void enqueue(@NonNull RaftMessage message, boolean immediate) {
    if (closed.get()) {
      return;
    }
    mailbox.offer(message);
    if (!immediate) {
      appendsEnqueued.incrementAndGet();
    }
    Channel ch = channelRef.get();
    if (ch == null || !ch.isActive()) {
      // No connection yet (or dropped). Probe the reconnect state machine. The message stays on
      // the mailbox and is drained once the channel comes up.
      maybeConnect();
      return;
    }
    if (!ch.isWritable()) {
      // Writable watermark exceeded. Skip scheduling. The next channelWritabilityChanged or enqueue
      // will pick up this message.
      return;
    }
    scheduleDrainOn(ch);
  }

  /**
   * Direct bulk-heartbeat send. Encodes the envelope on the caller thread, schedules a single
   * write-and-flush onto this peer's event loop, and returns.
   */
  void sendBulkHeartbeat(@NonNull BulkHeartbeatFrame frame) {
    if (closed.get()) {
      return;
    }
    Channel ch = channelRef.get();
    if (ch == null || !ch.isActive()) {
      maybeConnect();
      return;
    }
    if (!ch.isWritable()) {
      return;
    }
    ConsensusProtos.ConsensusFrame envelope;
    try {
      List<ConsensusProtos.GroupBulkHeartbeatPB> entries =
        new ArrayList<>(frame.getEntries().size());
      for (LeaderHeartbeat hb : frame.getEntries()) {
        entries.add(converter.toGroupBulkHeartbeatPB(hb));
      }
      envelope = ConsensusProtos.ConsensusFrame.newBuilder()
        .setKind(ConsensusProtos.ConsensusFrame.Kind.BULK_HEARTBEAT).setBulkHeartbeat(ProtoConverter
          .buildBulkHeartbeatPB(frame.getSender(), frame.getEpoch(), frame.getTick(), entries))
        .build();
    } catch (RuntimeException ex) {
      LOG.warn("Failed to encode bulk heartbeat for {}; dropping", address, ex);
      return;
    }
    Channel target = ch;
    try {
      target.eventLoop().execute(() -> {
        if (!target.isActive() || !target.isWritable()) {
          return;
        }
        NettyFutureUtils.safeWrite(target, envelope);
        heartbeatFrames.incrementAndGet();
        target.flush();
      });
    } catch (Throwable t) {
      LOG.debug("bulk heartbeat submission rejected for {}", address, t);
    }
  }

  /**
   * Direct bulk-heartbeat-ack send. Mirrors {@link #sendBulkHeartbeat(BulkHeartbeatFrame)} on the
   * reverse path.
   */
  void sendBulkHeartbeatAck(@NonNull BulkHeartbeatAckFrame frame) {
    if (closed.get()) {
      return;
    }
    Channel ch = channelRef.get();
    if (ch == null || !ch.isActive()) {
      maybeConnect();
      return;
    }
    if (!ch.isWritable()) {
      return;
    }
    ConsensusProtos.ConsensusFrame envelope;
    try {
      List<ConsensusProtos.GroupBulkHeartbeatAckPB> entries =
        new ArrayList<>(frame.getEntries().size());
      for (LeaderHeartbeatAck ack : frame.getEntries()) {
        entries.add(converter.toGroupBulkHeartbeatAckPB(ack));
      }
      envelope = ConsensusProtos.ConsensusFrame.newBuilder()
        .setKind(ConsensusProtos.ConsensusFrame.Kind.BULK_HEARTBEAT_ACK)
        .setBulkHeartbeatAck(ProtoConverter.buildBulkHeartbeatAckPB(frame.getSender(),
          frame.getEpoch(), frame.getTick(), entries))
        .build();
    } catch (RuntimeException ex) {
      LOG.warn("Failed to encode bulk heartbeat ack for {}; dropping", address, ex);
      return;
    }
    Channel target = ch;
    try {
      target.eventLoop().execute(() -> {
        if (!target.isActive() || !target.isWritable()) {
          return;
        }
        NettyFutureUtils.safeWrite(target, envelope);
        heartbeatAckFrames.incrementAndGet();
        target.flush();
      });
    } catch (Throwable t) {
      LOG.debug("bulk heartbeat ack submission rejected for {}", address, t);
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
    // Intentionally do not call mailbox.clear(). AbstractQueue.clear() polls the unbounded MPSC
    // one element at a time on the caller's thread. All becomes eligible for GC reclamation.
  }

  /**
   * Trigger a connect if (a) we have nothing pending in flight, (b) the backoff timer has elapsed,
   * and (c) we are not already connected.
   */
  private void maybeConnect() {
    if (closed.get()) {
      return;
    }
    if (EnvironmentEdgeManager.currentTime() < nextConnectAtMillis) {
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
            long backoff = config.getReconnectBackoffMinMs();
            nextConnectAtMillis = EnvironmentEdgeManager.currentTime() + backoff;
            // Self-arm a probe so reconnect makes progress without depending on enqueue cadence.
            scheduleReconnectProbe(backoff);
          });
          // Add a writability handler that re-arms the drain when back-pressure clears.
          ch.pipeline().addLast("outbound-writability",
            new org.apache.hbase.thirdparty.io.netty.channel.ChannelInboundHandlerAdapter() {
              @Override
              public void channelWritabilityChanged(
                org.apache.hbase.thirdparty.io.netty.channel.ChannelHandlerContext ctx) {
                Channel writableCh = ctx.channel();
                if (writableCh.isWritable()) {
                  if (mailbox.peek() != null) {
                    scheduleDrainOn(writableCh);
                  }
                }
                ctx.fireChannelWritabilityChanged();
              }
            });
          // Drain whatever was queued while we were down.
          scheduleDrainOn(ch);
        } else {
          long backoff = currentBackoffMs;
          nextConnectAtMillis = EnvironmentEdgeManager.currentTime() + backoff;
          long doubled = Math.min(backoff * 2L, config.getReconnectBackoffMaxMs());
          currentBackoffMs = doubled;
          if (LOG.isDebugEnabled()) {
            LOG.debug("connect to {} failed; next attempt in {}ms", address, backoff,
              future.cause());
          }
          scheduleReconnectProbe(backoff);
        }
      });
    } catch (Throwable t) {
      connecting.set(false);
      LOG.warn("bootstrap.connect threw for {}", address, t);
    }
  }

  /**
   * Schedule a {@link #maybeConnect()} probe on the bootstrap's event loop after {@code delayMs}.
   * Idempotent because {@link #maybeConnect()}'s {@link #connecting} guard absorbs concurrent or
   * already-in-flight connect attempts.
   */
  private void scheduleReconnectProbe(long delayMs) {
    if (closed.get()) {
      return;
    }
    try {
      bootstrap.config().group().schedule((Runnable) this::maybeConnect, Math.max(1L, delayMs),
        java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (Throwable t) {
      LOG.debug("reconnect probe scheduling rejected for {}", address, t);
    }
  }

  /**
   * Schedule a bulk-lane drain pass on {@code ch}'s event loop. Lost-wakeup-safe via the
   * {@link #draining} latch.
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
          if (mailbox.peek() != null && ch.isActive() && ch.isWritable()) {
            scheduleDrainOn(ch);
          }
        });
      } catch (Throwable t) {
        draining.set(false);
        LOG.debug("bulk drain submission rejected for {}", address, t);
      }
    }
  }

  /**
   * Single-pass bulk-lane drain on the event loop thread. Bails when the writable watermark is
   * exceeded and re-arms when the channel becomes writable again.
   */
  private void drainOnce(Channel ch) {
    if (!ch.isActive() || !ch.isWritable()) {
      return;
    }
    appendBucket.clear();
    immediateBucket.clear();

    try {
      RaftMessage m;
      while ((m = mailbox.relaxedPoll()) != null) {
        try {
          if (m instanceof AppendEntriesRequest) {
            appendBucket.add(converter.toGroupAppendPB((AppendEntriesRequest) m));
          } else {
            immediateBucket.add(converter.toFrame(m));
          }
        } catch (RuntimeException ex) {
          // If conversion fails (e.g. compression, unknown op type), drop just this message and
          // keep draining the rest.
          LOG.warn("Failed to encode {} for {}; dropping", m.getClass().getName(), address, ex);
        }
      }

      boolean wrote = false;
      if (!appendBucket.isEmpty()) {
        ConsensusProtos.ConsensusFrame frame = ConsensusProtos.ConsensusFrame.newBuilder()
          .setKind(ConsensusProtos.ConsensusFrame.Kind.BATCH_APPEND)
          .setBatchAppend(
            ConsensusProtos.BatchAppendEntriesPB.newBuilder().addAllGroups(appendBucket).build())
          .build();
        NettyFutureUtils.safeWrite(ch, frame);
        appendFrames.incrementAndGet();
        wrote = true;
      }
      for (ConsensusProtos.ConsensusFrame frame : immediateBucket) {
        NettyFutureUtils.safeWrite(ch, frame);
        wrote = true;
      }
      if (wrote) {
        ch.flush();
      }
    } finally {
      appendBucket.clear();
      immediateBucket.clear();
    }
  }

  /** Returns a point-in-time snapshot of this channel's outbound frame counters. */
  @NonNull
  OutboundChannelStats stats() {
    return new OutboundChannelStats(heartbeatFrames.get(), appendFrames.get(),
      heartbeatAckFrames.get(), appendsEnqueued.get());
  }
}
