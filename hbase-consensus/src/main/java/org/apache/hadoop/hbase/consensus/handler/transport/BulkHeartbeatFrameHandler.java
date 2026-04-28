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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.LeaderHeartbeatHandler;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeat;
import org.apache.hadoop.hbase.consensus.raft.model.message.LeaderHeartbeatAck;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatAckFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inbound dispatcher for one {@link ConsensusProtos.BulkHeartbeatPB} envelope.
 * <p>
 * Runs entirely on the netty inbound event-loop thread on the steady-state path. For every
 * per-group entry whose state already matches the leader's snapshot, the handler advances the
 * per-group "last leader heartbeat" / "election timer reset" timestamps via lock-free monotonic
 * accumulators on {@link RaftNodeImpl} and appends a synthesized
 * {@link ConsensusProtos.GroupBulkHeartbeatAckPB} entry to the per-frame outbound bulk-ack list. No
 * per-group executor task is scheduled in that case.
 * <p>
 * Per-group executor tasks are scheduled only on actual transitions, such as term changed, leader
 * changed, role drop, commit-advance via heartbeat, or quiesce flip. The slow path runs the
 * {@link LeaderHeartbeatHandler} body with a {@link Consumer} ack sink that appends the synthesized
 * ack into a per-sender pending queue. The next inbound bulk frame from that sender drains the
 * queue into its outgoing bulk-ack frame, so slow-path acks coalesce with the next steady-state ack
 * frame rather than producing their own one-entry envelopes on the wire.
 * <p>
 * After processing every entry, if at least one ack accumulated, the handler builds one
 * {@link BulkHeartbeatAckFrame} stamped with the local server's boot epoch and a monotonic envelope
 * tick and hands it to {@link Transport#sendBulkHeartbeatAck(RaftEndpoint, BulkHeartbeatAckFrame)}.
 */
@InterfaceAudience.Private
final class BulkHeartbeatFrameHandler {

  private static final Logger LOG = LoggerFactory.getLogger(BulkHeartbeatFrameHandler.class);

  private final RegistryDispatcher dispatcher;
  private final ProtoConverter converter;
  private final Transport transport;
  private final RaftEndpoint localEndpoint;
  private final long localEpoch;
  private final AtomicLong outboundTick = new AtomicLong();

  /**
   * Per-sender queue of slow-path acks waiting to be coalesced into the next outbound bulk-ack
   * envelope addressed at that sender. Producers are per-group executor threads running the
   * slow-path {@link LeaderHeartbeatHandler}. The consumer is the netty inbound thread that drains
   * the queue at the start of each {@link #handle} call before processing the new frame.
   */
  private final ConcurrentMap<RaftEndpoint,
    ConcurrentLinkedQueue<LeaderHeartbeatAck>> pendingSlowAcks = new ConcurrentHashMap<>();

  BulkHeartbeatFrameHandler(@NonNull RegistryDispatcher dispatcher,
    @NonNull ProtoConverter converter, @NonNull Transport transport,
    @NonNull RaftEndpoint localEndpoint, long localEpoch) {
    this.dispatcher = requireNonNull(dispatcher);
    this.converter = requireNonNull(converter);
    this.transport = requireNonNull(transport);
    this.localEndpoint = requireNonNull(localEndpoint);
    this.localEpoch = localEpoch;
  }

  void handle(@NonNull ConsensusProtos.BulkHeartbeatPB frame) {
    if (!frame.hasSender()) {
      LOG.debug("Dropping BulkHeartbeatPB without sender envelope header");
      return;
    }
    RaftEndpoint sender = ProtoConverter.fromEndpointPB(frame.getSender());
    long now = EnvironmentEdgeManager.currentTime();
    List<LeaderHeartbeatAck> ackEntries = new ArrayList<>(frame.getGroupsCount() + 4);
    drainSlowAcksFor(sender, ackEntries);
    for (ConsensusProtos.GroupBulkHeartbeatPB e : frame.getGroupsList()) {
      RaftNode node = dispatcher.lookup(e.getGroupId());
      if (!(node instanceof RaftNodeImpl)) {
        continue;
      }
      RaftNodeImpl rni = (RaftNodeImpl) node;
      if (tryFastPath(rni, sender, e, ackEntries, now)) {
        continue;
      }
      dispatchSlowPath(rni, sender, e);
    }
    if (ackEntries.isEmpty()) {
      return;
    }
    BulkHeartbeatAckFrame ackFrame = new BulkHeartbeatAckFrame(localEndpoint, localEpoch,
      outboundTick.incrementAndGet(), ackEntries);
    try {
      transport.sendBulkHeartbeatAck(sender, ackFrame);
    } catch (RuntimeException ex) {
      LOG.debug("sendBulkHeartbeatAck failed for {}; will retry on the next inbound frame",
        sender.getId(), ex);
    }
  }

  private boolean tryFastPath(@NonNull RaftNodeImpl rni, @NonNull RaftEndpoint sender,
    @NonNull ConsensusProtos.GroupBulkHeartbeatPB e, @NonNull List<LeaderHeartbeatAck> ackEntries,
    long now) {
    if (!e.hasTerm() || !e.hasCommitIndex()) {
      return false;
    }
    RaftState state = rni.state();
    RaftRole role = state.role();
    if (role != RaftRole.FOLLOWER && role != RaftRole.LEARNER) {
      return false;
    }
    if (e.getTerm() != state.term()) {
      return false;
    }
    if (e.getQuiesced() != state.groupQuiescent()) {
      return false;
    }
    if (e.getCommitIndex() > state.commitIndex()) {
      return false;
    }
    RaftEndpoint currentLeader = state.leader();
    if (currentLeader == null || !currentLeader.equals(sender)) {
      return false;
    }
    rni.leaderHeartbeatReceived();
    LeaderHeartbeatAck ack = rni.getModelFactory().createLeaderHeartbeatAckBuilder()
      .setGroupId(rni.getGroupId()).setSender(localEndpoint).setTerm(state.term())
      .setLastVerifiedLogIndex(state.lastVerifiedLogIndex()).build();
    ackEntries.add(ack);
    return true;
  }

  private void dispatchSlowPath(@NonNull RaftNodeImpl rni, @NonNull RaftEndpoint sender,
    @NonNull ConsensusProtos.GroupBulkHeartbeatPB e) {
    final LeaderHeartbeat hb;
    try {
      hb = converter.fromGroupBulkHeartbeatPB(e, rni.getGroupId(), sender);
    } catch (RuntimeException ex) {
      LOG.warn("Failed to decode GroupBulkHeartbeatPB for group {}", rni.getGroupId(), ex);
      return;
    }
    final Consumer<LeaderHeartbeatAck> sink =
      ack -> pendingSlowAcks.computeIfAbsent(sender, k -> new ConcurrentLinkedQueue<>()).offer(ack);
    try {
      rni.getExecutor().executeControl(new LeaderHeartbeatHandler(rni, hb, sink));
    } catch (RuntimeException ex) {
      LOG.debug("executeControl rejected for slow-path bulk heartbeat on group {}",
        rni.getGroupId(), ex);
    }
  }

  private void drainSlowAcksFor(@NonNull RaftEndpoint sender,
    @NonNull List<LeaderHeartbeatAck> into) {
    ConcurrentLinkedQueue<LeaderHeartbeatAck> q = pendingSlowAcks.get(sender);
    if (q == null) {
      return;
    }
    LeaderHeartbeatAck a;
    while ((a = q.poll()) != null) {
      into.add(a);
    }
  }
}
