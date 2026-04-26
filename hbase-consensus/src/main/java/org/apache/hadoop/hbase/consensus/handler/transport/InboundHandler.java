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
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
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
 * {@link RaftMessage}s and hands them to the local {@link RaftNode#handle(RaftMessage)} (which
 * itself trampolines through the per-group {@code GroupExecutor}).
 * <p>
 * Sharable so the inbound pipeline can mount a single instance for every accepted connection.
 */
@InterfaceAudience.Private
@ChannelHandler.Sharable
final class InboundHandler extends SimpleChannelInboundHandler<ConsensusProtos.ConsensusFrame> {

  private static final Logger LOG = LoggerFactory.getLogger(InboundHandler.class);

  private final InboundDispatcher dispatcher;
  private final ProtoConverter converter;

  InboundHandler(@NonNull InboundDispatcher dispatcher, @NonNull ProtoConverter converter) {
    this.dispatcher = dispatcher;
    this.converter = converter;
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
      // Single bad message: log and drop, keep the channel alive.
      LOG.warn("Dropping malformed ConsensusFrame ({}) from {}: {}", frame.getKind(),
        ctx.channel().remoteAddress(), e.getMessage());
    }
  }

  private void handle(ConsensusProtos.ConsensusFrame frame) {
    switch (frame.getKind()) {
      case BATCH_APPEND:
        requirePayload(frame.hasBatchAppend(), frame.getKind(), "batch_append");
        for (ConsensusProtos.GroupAppendEntriesPB pb : frame.getBatchAppend().getGroupsList()) {
          dispatch(pb.getGroupId(), converter.fromGroupAppendPB(pb));
        }
        break;
      case HEARTBEAT_BATCH:
        requirePayload(frame.hasHeartbeatBatch(), frame.getKind(), "heartbeat_batch");
        for (ConsensusProtos.GroupHeartbeatPB pb : frame.getHeartbeatBatch().getGroupsList()) {
          dispatch(pb.getGroupId(), converter.fromGroupHeartbeatPB(pb));
        }
        break;
      case APPEND_SUCCESS: {
        requirePayload(frame.hasAppendSuccess(), frame.getKind(), "append_success");
        ConsensusProtos.GroupAppendSuccessPB pb = frame.getAppendSuccess();
        dispatch(pb.getGroupId(), converter.fromAppendSuccessPB(pb));
        break;
      }
      case APPEND_FAILURE: {
        requirePayload(frame.hasAppendFailure(), frame.getKind(), "append_failure");
        ConsensusProtos.GroupAppendFailurePB pb = frame.getAppendFailure();
        dispatch(pb.getGroupId(), converter.fromAppendFailurePB(pb));
        break;
      }
      case INSTALL_SNAPSHOT: {
        requirePayload(frame.hasInstallRequest(), frame.getKind(), "install_request");
        ConsensusProtos.InstallSnapshotRequestPB pb = frame.getInstallRequest();
        dispatch(pb.getGroupId(), converter.fromInstallSnapshotPB(pb));
        break;
      }
      case INSTALL_SNAPSHOT_RESP: {
        requirePayload(frame.hasInstallResponse(), frame.getKind(), "install_response");
        ConsensusProtos.InstallSnapshotResponsePB pb = frame.getInstallResponse();
        dispatch(pb.getGroupId(), converter.fromInstallSnapshotResponsePB(pb));
        break;
      }
      case VOTE_REQUEST: {
        requirePayload(frame.hasVoteRequest(), frame.getKind(), "vote_request");
        ConsensusProtos.VoteRequestPB pb = frame.getVoteRequest();
        dispatch(pb.getGroupId(), converter.fromVoteRequestPB(pb));
        break;
      }
      case VOTE_RESPONSE: {
        requirePayload(frame.hasVoteResponse(), frame.getKind(), "vote_response");
        ConsensusProtos.VoteResponsePB pb = frame.getVoteResponse();
        dispatch(pb.getGroupId(), converter.fromVoteResponsePB(pb));
        break;
      }
      case PRE_VOTE_REQUEST: {
        requirePayload(frame.hasPreVoteRequest(), frame.getKind(), "pre_vote_request");
        ConsensusProtos.PreVoteRequestPB pb = frame.getPreVoteRequest();
        dispatch(pb.getGroupId(), converter.fromPreVoteRequestPB(pb));
        break;
      }
      case PRE_VOTE_RESPONSE: {
        requirePayload(frame.hasPreVoteResponse(), frame.getKind(), "pre_vote_response");
        ConsensusProtos.PreVoteResponsePB pb = frame.getPreVoteResponse();
        dispatch(pb.getGroupId(), converter.fromPreVoteResponsePB(pb));
        break;
      }
      case TRIGGER_LEADER_ELECTION: {
        requirePayload(frame.hasTriggerElection(), frame.getKind(), "trigger_election");
        ConsensusProtos.TriggerLeaderElectionPB pb = frame.getTriggerElection();
        dispatch(pb.getGroupId(), converter.fromTriggerElectionPB(pb));
        break;
      }
      case HEARTBEAT_ACK_BATCH:
        requirePayload(frame.hasHeartbeatAckBatch(), frame.getKind(), "heartbeat_ack_batch");
        for (ConsensusProtos.GroupHeartbeatAckPB pb : frame.getHeartbeatAckBatch()
          .getGroupsList()) {
          dispatch(pb.getGroupId(), converter.fromGroupHeartbeatAckPB(pb));
        }
        break;
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

  private void dispatch(ByteString groupIdBytes, RaftMessage message) {
    Object groupId = ProtoConverter.bytesToGroupId(groupIdBytes);
    RaftNode node = dispatcher.lookup(groupId);
    if (node == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Dropping {} for unknown group {}", message.getClass().getSimpleName(), groupId);
      }
      return;
    }
    try {
      node.handle(message);
    } catch (RuntimeException e) {
      LOG.warn("RaftNode.handle threw for group {}", groupId, e);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOG.warn("inbound channel error from {}", ctx.channel().remoteAddress(), cause);
    NettyFutureUtils.safeClose(ctx);
  }
}
