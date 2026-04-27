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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
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
 * Owns a {@link MpscUnboundedArrayQueue} of pending messages, a current Netty {@link Channel}
 * reference, and a tiny reconnect state machine. The transport's flush scheduler pings
 * {@link #flushTick()} at {@code hbase.consensus.log.sync.batch.ms}; producers call
 * {@link #enqueue} from any thread.
 * <p>
 * Single-consumer drain is enforced by an {@link AtomicBoolean} latch. Producers pile messages into
 * the MPSC, the first arrival CASes the latch on, and only the drain runnable polls the queue. The
 * drain is always run on the channel's event loop so per-peer FIFO holds even across reconnects.
 */
@InterfaceAudience.Private
final class OutboundChannel {

  private static final Logger LOG = LoggerFactory.getLogger(OutboundChannel.class);

  /** Lightweight enqueue record. */
  private static final class Pending {
    final RaftMessage message;
    final boolean immediate;
    final long enqueueTimeMillis;

    Pending(RaftMessage message, boolean immediate, long enqueueTimeMillis) {
      this.message = message;
      this.immediate = immediate;
      this.enqueueTimeMillis = enqueueTimeMillis;
    }
  }

  private final RaftEndpoint peer;
  private final InetSocketAddress address;
  private final Bootstrap bootstrap;
  private final ProtoConverter converter;
  private final TransportConfig config;
  private final long flushDeadlineMs;
  private final MpscUnboundedArrayQueue<Pending> mailbox;
  private final AtomicBoolean draining = new AtomicBoolean(false);
  private final AtomicReference<Channel> channelRef = new AtomicReference<>();
  private final AtomicBoolean connecting = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  // Producer-visible head-of-line timestamp for the deadline wake-up. The AtomicLong holds the
  // approximate enqueue time (in millis from {@link EnvironmentEdgeManager}) of the oldest message
  // not yet observed by a drain pass, or 0L when "no untracked message". Producers do
  // {@code compareAndSet(0L, now)} after their offer so the *first* message of a fresh batch wins
  // the slot. Later producers in the same batch read the existing value to compare against. The
  // drain runnable resets the slot to 0L before its poll loop so producers that enqueue during
  // the drain start a fresh batch's tracking and can re-arm the wakeup.
  private final AtomicLong oldestUndrainedEnqueueMs = new AtomicLong();

  private volatile long nextConnectAtMillis = 0L;
  private volatile long currentBackoffMs;

  // Lightweight counters powering {@link #stats()}. Single-writer for frame counters. Multi-writer
  // for the enqueue counter and the deadline-flush counter.
  private final AtomicLong heartbeatFrames = new AtomicLong();
  private final AtomicLong appendFrames = new AtomicLong();
  private final AtomicLong heartbeatAckFrames = new AtomicLong();
  private final AtomicLong immediateFrames = new AtomicLong();
  private final AtomicLong messagesEnqueued = new AtomicLong();
  private final AtomicLong forcedFlushesByDeadline = new AtomicLong();

  OutboundChannel(@NonNull RaftEndpoint peer, @NonNull InetSocketAddress address,
    @NonNull Bootstrap bootstrap, @NonNull ProtoConverter converter,
    @NonNull TransportConfig config) {
    this.peer = peer;
    this.address = address;
    this.bootstrap = bootstrap;
    this.converter = converter;
    this.config = config;
    this.flushDeadlineMs = config.getFlushDeadlineMs();
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

  /**
   * Enqueues {@code message} for the peer. Non-blocking.
   * <p>
   * {@code immediate=true} flushes this message in its own {@link ConsensusProtos.ConsensusFrame}
   * (used for vote / install-snapshot / response messages) <i>and</i> wakes the event-loop drain
   * synchronously so latency-sensitive control messages do not pay for the
   * {@link TransportConfig#BATCH_MS_KEY batch interval}. Coalescible messages
   * ({@link LeaderHeartbeat}, {@link AppendEntriesRequest}, {@link LeaderHeartbeatAck}) deposit to
   * the mailbox and rely on {@link CoalescingTransport}'s periodic
   * {@link OutboundChannel#flushTick() flushTick} to drain &mdash; this is what gives the design
   * its O(peers) heartbeat-frame collapse: a single drain pass on the event loop coalesces every
   * per-group beat enqueued during the previous {@code BATCH_MS_KEY} window into one
   * {@code HEARTBEAT_BATCH} (or {@code BATCH_APPEND}) frame per peer per tick. Unconditional
   * producer-side wakeups from coalescible enqueues would defeat that coalescing by causing the
   * drain to ping-pong with the producer at sub-tick rate.
   * <p>
   * As a fail-safe, however, this method <i>does</i> wake the drain when the head of the mailbox
   * has aged past {@link TransportConfig#FLUSH_DEADLINE_MS_KEY}. That bounds the head-of-line
   * latency a single peer's queue can accumulate when the periodic flush tick is starved by
   * event-loop contention (e.g. another channel running a long {@code drainOnce}). The wakeup is
   * latch-protected exactly like the immediate path: if a drain is already in flight the in-flight
   * drain absorbs the message via its unbounded poll loop. The resulting head-of-line latency is
   * bounded by {@code BATCH_MS_KEY + drain wall time} when the tick fires on schedule, and by
   * {@code FLUSH_DEADLINE_MS_KEY + drain wall time} when it does not.
   */
  void enqueue(@NonNull RaftMessage message, boolean immediate) {
    if (closed.get()) {
      return;
    }
    long now = EnvironmentEdgeManager.currentTime();
    mailbox.offer(new Pending(message, immediate, now));
    messagesEnqueued.incrementAndGet();
    // Try to claim the head-of-line timestamp slot. Only one producer per batch wins; later
    // producers see the older timestamp and may trigger the deadline wakeup below. The slot is
    // reset to 0L by drainOnce() before its poll loop so the next batch starts tracking afresh.
    oldestUndrainedEnqueueMs.compareAndSet(0L, now);
    Channel ch = channelRef.get();
    if (ch == null || !ch.isActive() || !ch.isWritable()) {
      return;
    }
    if (immediate) {
      scheduleDrainOn(ch);
      return;
    }
    long oldest = oldestUndrainedEnqueueMs.get();
    if (oldest != 0L && now - oldest >= flushDeadlineMs) {
      forcedFlushesByDeadline.incrementAndGet();
      scheduleDrainOn(ch);
    }
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
    // Intentionally do not call mailbox.clear(). AbstractQueue.clear() polls the unbounded MPSC
    // one element at a time on the caller's thread. The OutboundChannel is now
    // unreachable from CoalescingTransport.peers via the close-side teardown, so the mailbox and
    // its still-undelivered Pendings become eligible for normal GC reclamation.
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
            nextConnectAtMillis =
              EnvironmentEdgeManager.currentTime() + config.getReconnectBackoffMinMs();
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
   * the event loop, satisfying the MPSC's single-consumer invariant.
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
          // Lost-wakeup-safe re-arm. Three cases close the window between our last poll and the
          // latch clear:
          // (a) An IMMEDIATE message arrived (vote / install-snapshot / response): re-arm
          // so the latency-sensitive frame does not wait for the next batch tick.
          // (b) The head of the mailbox has aged past flushDeadlineMs: re-arm with the
          // deadline counter bumped, so the head-of-line latency a single peer can
          // accumulate is bounded by FLUSH_DEADLINE_MS_KEY even when the periodic
          // tick is starved by event-loop contention.
          // (c) Otherwise the leftover messages are coalescible and within the deadline; they
          // wait for the next CoalescingTransport.tick(), which is the periodic flush
          // window that gives the transport its O(peers) coalescing property. Re-arming
          // here for fresh coalescibles would let a fast producer ping-pong the drain at
          // sub-tick rate.
          Pending head = mailbox.peek();
          if (head != null && ch.isActive() && ch.isWritable()) {
            long now = EnvironmentEdgeManager.currentTime();
            boolean staleHead = !head.immediate && now - head.enqueueTimeMillis >= flushDeadlineMs;
            if (head.immediate || staleHead) {
              if (staleHead) {
                forcedFlushesByDeadline.incrementAndGet();
              }
              scheduleDrainOn(ch);
            }
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
    // Reset the head-of-line timestamp slot before polling.
    oldestUndrainedEnqueueMs.set(0L);
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
        // If conversion fails (e.g. compression, unknown op type), drop just this message and
        // keep draining the rest.
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
      appendFrames.incrementAndGet();
    }
    if (!heartbeatBucket.isEmpty()) {
      ConsensusProtos.ConsensusFrame frame = ConsensusProtos.ConsensusFrame.newBuilder()
        .setKind(ConsensusProtos.ConsensusFrame.Kind.HEARTBEAT_BATCH)
        .setHeartbeatBatch(
          ConsensusProtos.HeartbeatBatchPB.newBuilder().addAllGroups(heartbeatBucket).build())
        .build();
      NettyFutureUtils.safeWrite(ch, frame);
      heartbeatFrames.incrementAndGet();
    }
    if (!heartbeatAckBucket.isEmpty()) {
      ConsensusProtos.ConsensusFrame frame = ConsensusProtos.ConsensusFrame.newBuilder()
        .setKind(ConsensusProtos.ConsensusFrame.Kind.HEARTBEAT_ACK_BATCH)
        .setHeartbeatAckBatch(
          ConsensusProtos.BatchHeartbeatAckPB.newBuilder().addAllGroups(heartbeatAckBucket).build())
        .build();
      NettyFutureUtils.safeWrite(ch, frame);
      heartbeatAckFrames.incrementAndGet();
    }
    for (ConsensusProtos.ConsensusFrame frame : immediates) {
      NettyFutureUtils.safeWrite(ch, frame);
      immediateFrames.incrementAndGet();
    }
    if (
      !appendBucket.isEmpty() || !heartbeatBucket.isEmpty() || !heartbeatAckBucket.isEmpty()
        || !immediates.isEmpty()
    ) {
      ch.flush();
    }
  }

  /** Returns a point-in-time snapshot of this channel's outbound frame counters. */
  @NonNull
  OutboundChannelStats stats() {
    return new OutboundChannelStats(heartbeatFrames.get(), appendFrames.get(),
      heartbeatAckFrames.get(), immediateFrames.get(), messagesEnqueued.get(), mailbox.size(),
      forcedFlushesByDeadline.get());
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

  /** Visible for tests. */
  @Nullable
  Channel currentChannel() {
    return channelRef.get();
  }
}
