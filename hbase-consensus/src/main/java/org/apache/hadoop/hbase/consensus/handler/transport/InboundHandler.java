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
import org.apache.hadoop.hbase.consensus.protobuf.generated.ConsensusProtos;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.hadoop.hbase.util.NettyFutureUtils;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelHandler;
import org.apache.hbase.thirdparty.io.netty.channel.ChannelHandlerContext;
import org.apache.hbase.thirdparty.io.netty.channel.SimpleChannelInboundHandler;

/**
 * Inbound dispatcher.
 * <p>
 * Turns each {@link ConsensusProtos.ConsensusFrame} arriving on the wire into one or more
 * {@link RaftMessage}s and hands them to the local {@link RaftNode#handle(RaftMessage)}.
 * <p>
 * {@code BULK_HEARTBEAT} and {@code BULK_HEARTBEAT_ACK} frames are processed in-line on the netty
 * inbound event-loop thread by {@link BulkHeartbeatFrameHandler} and
 * {@link BulkHeartbeatAckFrameHandler}, which run a lock-free fast path against
 * {@code RaftNodeImpl}'s volatile timestamp / lease accumulators and only fall back to the
 * per-group executor when the per-entry state actually needs to transition.
 * <p>
 * Sharable so the inbound pipeline can mount a single instance for every accepted connection.
 */
@InterfaceAudience.Private
@ChannelHandler.Sharable
final class InboundHandler extends SimpleChannelInboundHandler<ConsensusProtos.ConsensusFrame> {

  private static final Logger LOG = LoggerFactory.getLogger(InboundHandler.class);

  private final RegistryDispatcher dispatcher;
  private final ProtoConverter converter;
  private final BulkHeartbeatFrameHandler bulkHeartbeatHandler;
  private final BulkHeartbeatAckFrameHandler bulkHeartbeatAckHandler;

  InboundHandler(@NonNull RegistryDispatcher dispatcher, @NonNull ProtoConverter converter,
    @NonNull Transport transport, @NonNull RaftEndpoint localEndpoint, long localEpoch) {
    this.dispatcher = dispatcher;
    this.converter = converter;
    this.bulkHeartbeatHandler =
      new BulkHeartbeatFrameHandler(dispatcher, converter, transport, localEndpoint, localEpoch);
    this.bulkHeartbeatAckHandler = new BulkHeartbeatAckFrameHandler(dispatcher, converter);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ConsensusProtos.ConsensusFrame frame) {
    if (!frame.hasKind()) {
      LOG.warn("Dropping ConsensusFrame from {} missing required field 'kind'",
        ctx.channel().remoteAddress());
      return;
    }
    try {
      handle(frame);
    } catch (MalformedMessageException e) {
      // Log and drop, keep the channel alive.
      LOG.warn("Dropping malformed ConsensusFrame ({}) from {}: {}", frame.getKind(),
        ctx.channel().remoteAddress(), e.getMessage());
    }
  }

  private void handle(ConsensusProtos.ConsensusFrame frame) {
    switch (frame.getKind()) {
      case BATCH_APPEND:
        requirePayload(frame.hasBatchAppend(), frame.getKind(), "batch_append");
        for (ConsensusProtos.GroupAppendEntriesPB pb : frame.getBatchAppend().getGroupsList()) {
          RaftNode node = lookupOrDrop(pb.getGroupId(), "BATCH_APPEND");
          if (node != null) {
            deliver(node, converter.fromGroupAppendPB(pb, node.getGroupId()));
          }
        }
        break;
      case BULK_HEARTBEAT:
        requirePayload(frame.hasBulkHeartbeat(), frame.getKind(), "bulk_heartbeat");
        bulkHeartbeatHandler.handle(frame.getBulkHeartbeat());
        break;
      case BULK_HEARTBEAT_ACK:
        requirePayload(frame.hasBulkHeartbeatAck(), frame.getKind(), "bulk_heartbeat_ack");
        bulkHeartbeatAckHandler.handle(frame.getBulkHeartbeatAck());
        break;
      case APPEND_SUCCESS: {
        requirePayload(frame.hasAppendSuccess(), frame.getKind(), "append_success");
        ConsensusProtos.GroupAppendSuccessPB pb = frame.getAppendSuccess();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "APPEND_SUCCESS");
        if (node != null) {
          deliver(node, converter.fromAppendSuccessPB(pb, node.getGroupId()));
        }
        break;
      }
      case APPEND_FAILURE: {
        requirePayload(frame.hasAppendFailure(), frame.getKind(), "append_failure");
        ConsensusProtos.GroupAppendFailurePB pb = frame.getAppendFailure();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "APPEND_FAILURE");
        if (node != null) {
          deliver(node, converter.fromAppendFailurePB(pb, node.getGroupId()));
        }
        break;
      }
      case INSTALL_SNAPSHOT: {
        requirePayload(frame.hasInstallRequest(), frame.getKind(), "install_request");
        ConsensusProtos.InstallSnapshotRequestPB pb = frame.getInstallRequest();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "INSTALL_SNAPSHOT");
        if (node != null) {
          deliver(node, converter.fromInstallSnapshotPB(pb, node.getGroupId()));
        }
        break;
      }
      case INSTALL_SNAPSHOT_RESP: {
        requirePayload(frame.hasInstallResponse(), frame.getKind(), "install_response");
        ConsensusProtos.InstallSnapshotResponsePB pb = frame.getInstallResponse();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "INSTALL_SNAPSHOT_RESP");
        if (node != null) {
          deliver(node, converter.fromInstallSnapshotResponsePB(pb, node.getGroupId()));
        }
        break;
      }
      case VOTE_REQUEST: {
        requirePayload(frame.hasVoteRequest(), frame.getKind(), "vote_request");
        ConsensusProtos.VoteRequestPB pb = frame.getVoteRequest();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "VOTE_REQUEST");
        if (node != null) {
          deliver(node, converter.fromVoteRequestPB(pb, node.getGroupId()));
        }
        break;
      }
      case VOTE_RESPONSE: {
        requirePayload(frame.hasVoteResponse(), frame.getKind(), "vote_response");
        ConsensusProtos.VoteResponsePB pb = frame.getVoteResponse();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "VOTE_RESPONSE");
        if (node != null) {
          deliver(node, converter.fromVoteResponsePB(pb, node.getGroupId()));
        }
        break;
      }
      case PRE_VOTE_REQUEST: {
        requirePayload(frame.hasPreVoteRequest(), frame.getKind(), "pre_vote_request");
        ConsensusProtos.PreVoteRequestPB pb = frame.getPreVoteRequest();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "PRE_VOTE_REQUEST");
        if (node != null) {
          deliver(node, converter.fromPreVoteRequestPB(pb, node.getGroupId()));
        }
        break;
      }
      case PRE_VOTE_RESPONSE: {
        requirePayload(frame.hasPreVoteResponse(), frame.getKind(), "pre_vote_response");
        ConsensusProtos.PreVoteResponsePB pb = frame.getPreVoteResponse();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "PRE_VOTE_RESPONSE");
        if (node != null) {
          deliver(node, converter.fromPreVoteResponsePB(pb, node.getGroupId()));
        }
        break;
      }
      case TRIGGER_LEADER_ELECTION: {
        requirePayload(frame.hasTriggerElection(), frame.getKind(), "trigger_election");
        ConsensusProtos.TriggerLeaderElectionPB pb = frame.getTriggerElection();
        RaftNode node = lookupOrDrop(pb.getGroupId(), "TRIGGER_LEADER_ELECTION");
        if (node != null) {
          deliver(node, converter.fromTriggerElectionPB(pb, node.getGroupId()));
        }
        break;
      }
      default:
        LOG.debug("Dropping ConsensusFrame with unknown kind {}", frame.getKind());
        break;
    }
  }

  private static void requirePayload(boolean present, ConsensusProtos.ConsensusFrame.Kind kind,
    String fieldName) {
    if (!present) {
      throw new MalformedMessageException(
        "ConsensusFrame[kind=" + kind + "] is missing required payload '" + fieldName + "'");
    }
  }

  /** Resolves the destination {@link RaftNode} for the wire group-id bytes. */
  private RaftNode lookupOrDrop(ByteString groupIdBytes, String kind) {
    RaftNode node = dispatcher.lookup(groupIdBytes);
    if (node == null && LOG.isDebugEnabled()) {
      LOG.debug("Dropping {} for unknown group bytes (size={})", kind, groupIdBytes.size());
    }
    return node;
  }

  private void deliver(RaftNode node, RaftMessage message) {
    try {
      node.handle(message);
    } catch (RuntimeException e) {
      LOG.warn("RaftNode.handle threw for group {}", node.getGroupId(), e);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOG.warn("inbound channel error from {}", ctx.channel().remoteAddress(), cause);
    NettyFutureUtils.safeClose(ctx);
  }
}
