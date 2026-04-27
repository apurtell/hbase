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
package org.apache.hadoop.hbase.consensus.raft.impl.handler;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.FOLLOWER;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.LEARNER;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.exception.RaftException;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.log.SnapshotChunkCollector;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry.SnapshotEntryBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles an {@link InstallSnapshotRequest} which could be sent by the leader or a follower.
 * <p>
 * Responds with either an {@link InstallSnapshotResponse}, {@link AppendEntriesSuccessResponse}, or
 * {@link AppendEntriesFailureResponse}.
 * <p>
 * See <i>7 Log compaction</i> section of <i>In Search of an Understandable Consensus Algorithm</i>
 * paper by <i>Diego Ongaro</i> and <i>John Ousterhout</i>.
 * <p>
 * If the request contains no snapshot chunk, it means that the Raft leader intends to transfer a
 * new snapshot to the follower. In this case, the follower initializes its
 * {@link SnapshotChunkCollector} state based on the total snapshot chunk count present in the
 * request, then starts asking snapshot chunks via sending {@link InstallSnapshotResponse} objects.
 * Once the follower collects all snapshot chunks, it installs the snapshot and sends an
 * {@link AppendEntriesSuccessResponse} back to the leader.
 * <p>
 * Our Raft log design ensures that every Raft group member takes a snapshot at exactly the same log
 * index. This behaviour enables an optimization. The {@link InstallSnapshotRequest} object sent by
 * the leader contains a list of followers whom are known to be installed the given snapshot. Using
 * this information, when a follower receives an {@link InstallSnapshotRequest} from the leader, it
 * can ask snapshot chunks not only from the leader, but also from the followers provided in the
 * received {@link InstallSnapshotRequest}. By this way, we utilize the bandwidth of the followers
 * and speed up the process by transferring snapshot chunks to the follower in parallel.
 * @see InstallSnapshotRequest
 * @see InstallSnapshotResponse
 * @see AppendEntriesSuccessResponse
 * @see AppendEntriesFailureResponse
 */
@InterfaceAudience.Private
public class InstallSnapshotRequestHandler extends AbstractMessageHandler<InstallSnapshotRequest> {
  private static final Logger LOG = LoggerFactory.getLogger(InstallSnapshotRequestHandler.class);

  public InstallSnapshotRequestHandler(RaftNodeImpl raftNode, InstallSnapshotRequest request) {
    super(raftNode, request);
  }

  @Override
  @SuppressWarnings({ "checkstyle:npathcomplexity", "checkstyle:cyclomaticcomplexity" })
  protected void handle(@NonNull InstallSnapshotRequest request) {
    requireNonNull(request);
    // getCatchUpReference() is reserved for catch-up-by-reference; chunked path ignores it.
    RaftEndpoint sender = request.getSender();
    // Reply false if term < currentTerm (§5.1)
    if (request.getTerm() < state.term()) {
      LOG.info("{} received stale snapshot chunk: {} from {} at snapshot index: {}.",
        localEndpointStr(),
        request.getSnapshotChunk() != null
          ? request.getSnapshotChunk().getSnapshotChunkIndex()
          : "-",
        sender.getId(), request.getSnapshotIndex());
      if (request.isSenderLeader()) {
        RaftMessage response =
          modelFactory.createAppendEntriesFailureResponseBuilder().setGroupId(node.getGroupId())
            .setSender(localEndpoint()).setTerm(state.term()).setExpectedNextIndex(0)
            .setQuerySequenceNumber(0).setFlowControlSequenceNumber(0).build();
        node.send(sender, response);
      }
      return;
    }
    if (request.getSnapshotChunk() == null) {
      List<Object> endpointIds =
        request.getSnapshottedMembers().stream().map(RaftEndpoint::getId).collect(toList());
      LOG.info(
        "{} is going to transfer {} snapshot chunks at log index: {} from: {}, initiated by: {}",
        localEndpointStr(), request.getTotalSnapshotChunkCount(), request.getSnapshotIndex(),
        endpointIds, sender.getId());
    } else {
      LOG.info("{} received snapshot chunk: {} from {} at snapshot index: {}.", localEndpointStr(),
        request.getSnapshotChunk() != null
          ? request.getSnapshotChunk().getSnapshotChunkIndex()
          : "-",
        sender.getId(), request.getSnapshotIndex());
    }
    // Transform into follower if a newer term is seen or another node wins the
    // election of the current term
    if (request.getTerm() > state.term() || (state.role() != FOLLOWER && state.role() != LEARNER)) {
      // If the request term is greater than the local term, update the local term and
      // convert to follower (§5.1)
      LOG.info("{} Moving to new term: {} from current term: {} and sender: {}", localEndpointStr(),
        request.getTerm(), state.term(), sender.getId());
      node.toFollower(request.getTerm());
      if (!request.isSenderLeader()) {
        return;
      }
    }
    if (request.isSenderLeader()) {
      if (!sender.equals(state.leader())) {
        LOG.info("{} Setting leader: {}", localEndpointStr(), sender.getId());
        node.leader(sender);
      }
      node.leaderHeartbeatReceived();
    }
    SnapshotChunkCollector snapshotChunkCollector = getOrCreateSnapshotChunkCollector(request);
    if (snapshotChunkCollector == null) {
      return;
    }
    if (handleSnapshotChunks(request, snapshotChunkCollector)) {
      SnapshotEntryBuilder snapshotEntryBuilder =
        node.getModelFactory().createSnapshotEntryBuilder();
      node.installSnapshot(snapshotChunkCollector.buildSnapshotEntry(snapshotEntryBuilder));
      sendAppendEntriesSuccessResponse(request);
    } else {
      requestMissingSnapshotChunks(request, snapshotChunkCollector);
    }
  }

  private boolean checkSnapshotIndex(InstallSnapshotRequest request) {
    if (request.getSnapshotIndex() < state.commitIndex()) {
      LOG.debug(
        "{} ignored stale snapshot chunk: {} at log index: {} from: {}. current commit index: {}",
        localEndpointStr(),
        request.getSnapshotChunk() != null
          ? request.getSnapshotChunk().getSnapshotChunkIndex()
          : "-",
        request.getSnapshotIndex(), request.getSender().getId(), state.commitIndex());
      return false;
    } else if (request.getSnapshotIndex() == state.commitIndex()) {
      LOG.debug(
        "{} ignored snapshot chunk: {} at log index: {} from: {} since commit index is same.",
        localEndpointStr(),
        request.getSnapshotChunk() != null
          ? request.getSnapshotChunk().getSnapshotChunkIndex()
          : "-",
        request.getSnapshotIndex(), request.getSender().getId());
      if (request.isSenderLeader()) {
        sendAppendEntriesSuccessResponse(request);
      }
      return false;
    }
    return true;
  }

  private void sendAppendEntriesSuccessResponse(InstallSnapshotRequest request) {
    RaftMessage response = modelFactory.createAppendEntriesSuccessResponseBuilder()
      .setGroupId(node.getGroupId()).setSender(localEndpoint()).setTerm(state.term())
      .setLastLogIndex(request.getSnapshotIndex())
      .setQuerySequenceNumber(request.getQuerySequenceNumber())
      .setFlowControlSequenceNumber(request.getFlowControlSequenceNumber()).build();
    node.send(state.leader(), response);
  }

  private SnapshotChunkCollector getOrCreateSnapshotChunkCollector(InstallSnapshotRequest request) {
    if (!checkSnapshotIndex(request)) {
      return null;
    }
    SnapshotChunkCollector snapshotChunkCollector = state.snapshotChunkCollector();
    if (snapshotChunkCollector == null) {
      snapshotChunkCollector = new SnapshotChunkCollector(state.store(), request);
      state.snapshotChunkCollector(snapshotChunkCollector);
      return snapshotChunkCollector;
    } else if (snapshotChunkCollector.getSnapshotIndex() > request.getSnapshotIndex()) {
      LOG.warn(
        "{} current snapshot chunks at log index: {} are more recent than received snapshot "
          + "chunks at log index: {} from sender: {} (is leader: {})",
        localEndpointStr(), snapshotChunkCollector.getSnapshotIndex(), request.getSnapshotIndex(),
        request.getSender().getId(), request.isSenderLeader());
      return null;
    } else if (snapshotChunkCollector.getSnapshotIndex() < request.getSnapshotIndex()) {
      if (snapshotChunkCollector.getChunks().size() > 0) {
        try {
          state.store().deleteSnapshotChunks(snapshotChunkCollector.getSnapshotIndex(),
            snapshotChunkCollector.getChunkCount());
          LOG.warn("{} truncated {} snapshot chunks at log index: {}", localEndpointStr(),
            snapshotChunkCollector.getChunks().size(), snapshotChunkCollector.getSnapshotIndex());
        } catch (IOException e) {
          throw new RaftException("Could not truncate snapshot chunks at log index: "
            + snapshotChunkCollector.getSnapshotIndex(), node.getLeaderEndpoint(), e);
        }
      }
      snapshotChunkCollector = new SnapshotChunkCollector(state.store(), request);
      state.snapshotChunkCollector(snapshotChunkCollector);
    } else {
      snapshotChunkCollector.updateSnapshottedMembers(request.getSnapshottedMembers());
    }
    if (snapshotChunkCollector.getSnapshotTerm() != request.getSnapshotTerm()) {
      throw new IllegalStateException("snapshot index: " + request.getSnapshotIndex()
        + " snapshot term: " + request.getSnapshotTerm() + " snapshot collector term: "
        + snapshotChunkCollector.getSnapshotTerm());
    }
    return snapshotChunkCollector;
  }

  private boolean handleSnapshotChunks(InstallSnapshotRequest request,
    SnapshotChunkCollector snapshotChunkCollector) {
    SnapshotChunk snapshotChunk = request.getSnapshotChunk();
    try {
      if (
        snapshotChunkCollector.handleReceivedSnapshotChunk(request.getSender(),
          request.getSnapshotIndex(), snapshotChunk) && snapshotChunk != null
      ) {
        LOG.debug(
          localEndpointStr() + " added new snapshot chunk: " + snapshotChunk.getSnapshotChunkIndex()
            + " at snapshot index: " + request.getSnapshotIndex());
      }
      return snapshotChunkCollector.isSnapshotCompleted();
    } catch (IOException e) {
      throw new RaftException("Could not persist snapshot chunk: "
        + snapshotChunk.getSnapshotChunkIndex() + " at snapshot index: " + snapshotChunk.getIndex()
        + " and term: " + snapshotChunk.getTerm(), node.getLeaderEndpoint(), e);
    }
  }

  private void requestMissingSnapshotChunks(InstallSnapshotRequest request,
    SnapshotChunkCollector snapshotChunkCollector) {
    Map<RaftEndpoint, Integer> requestedSnapshotChunkIndices = snapshotChunkCollector
      .requestSnapshotChunks(node.getConfig().isTransferSnapshotsFromFollowersEnabled());
    if (requestedSnapshotChunkIndices.isEmpty()) {
      return;
    }
    log(request.getSnapshotIndex(), requestedSnapshotChunkIndices);
    for (Entry<RaftEndpoint, Integer> e : requestedSnapshotChunkIndices.entrySet()) {
      RaftEndpoint target = e.getKey();
      RaftMessage response = node.getModelFactory().createInstallSnapshotResponseBuilder()
        .setGroupId(node.getGroupId()).setSender(localEndpoint()).setTerm(state.term())
        .setSnapshotIndex(request.getSnapshotIndex()).setRequestedSnapshotChunkIndex(e.getValue())
        .setQuerySequenceNumber(
          state.leader().equals(target) ? request.getQuerySequenceNumber() : 0)
        .setFlowControlSequenceNumber(request.getFlowControlSequenceNumber()).build();
      node.send(target, response);
      if (node.getConfig().isTransferSnapshotsFromFollowersEnabled()) {
        node.getExecutor()
          .schedule(() -> handleUnresponsiveEndpoint(state.term(), target,
            request.getSnapshotIndex(), e.getValue()),
            node.getConfig().getLeaderHeartbeatPeriodMillis(), MILLISECONDS);
      }
    }
  }

  private void log(long snapshotIndex, Map<RaftEndpoint, Integer> requestedSnapshotChunkIndices) {
    Map<String, Integer> endpointIds = requestedSnapshotChunkIndices.entrySet().stream()
      .collect(Collectors.toMap(e -> e.getKey().getId().toString(), Entry::getValue));
    LOG.info("{} requesting snapshot chunks: {} at snapshot index: {}.", localEndpointStr(),
      endpointIds, snapshotIndex);
  }

  private void handleUnresponsiveEndpoint(int term, RaftEndpoint endpoint, long snapshotIndex,
    int snapshotChunkIndex) {
    SnapshotChunkCollector snapshotChunkCollector = state.snapshotChunkCollector();
    if (
      state.term() != term || snapshotChunkCollector == null
        || snapshotChunkCollector.getSnapshotIndex() != snapshotIndex
    ) {
      return;
    }
    assert state.leaderState() == null;
    if (!snapshotChunkCollector.cancelSnapshotChunkRequest(endpoint, snapshotChunkIndex)) {
      return;
    }
    LOG.warn(
      "{} marked {} as unresponsive after requesting snapshot chunk: {} at snapshot index: {}",
      localEndpointStr(), endpoint.getId(), snapshotChunkIndex, snapshotIndex);
    Map<RaftEndpoint, Integer> requestedSnapshotChunkIndices =
      snapshotChunkCollector.requestSnapshotChunks(true);
    if (requestedSnapshotChunkIndices.isEmpty()) {
      return;
    }
    log(snapshotIndex, requestedSnapshotChunkIndices);
    for (Entry<RaftEndpoint, Integer> e : requestedSnapshotChunkIndices.entrySet()) {
      RaftEndpoint target = e.getKey();
      RaftMessage response = node.getModelFactory().createInstallSnapshotResponseBuilder()
        .setGroupId(node.getGroupId()).setSender(localEndpoint()).setTerm(state.term())
        .setSnapshotIndex(snapshotIndex).setRequestedSnapshotChunkIndex(e.getValue())
        .setQuerySequenceNumber(0).setFlowControlSequenceNumber(0).build();
      node.send(target, response);
      node.getExecutor().schedule(
        () -> handleUnresponsiveEndpoint(term, target, snapshotIndex, e.getValue()),
        node.getConfig().getLeaderHeartbeatPeriodMillis(), MILLISECONDS);
    }
  }
}
