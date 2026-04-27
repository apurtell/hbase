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
package org.apache.hadoop.hbase.consensus.raft.impl.task;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.ACTIVE;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.isTerminal;

import java.util.concurrent.TimeoutException;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeaderState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.LeadershipTransferState;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.impl.util.OrderedFuture;
import org.apache.hadoop.hbase.consensus.raft.model.log.BaseLogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triggers the leadership transfer process for the given Raft endpoint. If the local Raft node is
 * not leader, or there is an ongoing membership change, or the target Raft endpoint is not in the
 * committed raft group members, the leadership transfer process immediately fails.
 * <p>
 * New appends are temporarily rejected until the leadership transfer process completes.
 */
@InterfaceAudience.Private
public class TransferLeadershipTask implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(TransferLeadershipTask.class);
  private final RaftNodeImpl node;
  private final RaftEndpoint targetEndpoint;
  private final OrderedFuture<Object> future;

  public TransferLeadershipTask(RaftNodeImpl node, RaftEndpoint targetEndpoint,
    OrderedFuture<Object> future) {
    this.node = node;
    this.targetEndpoint = targetEndpoint;
    this.future = future;
  }

  @Override
  public void run() {
    RaftState state = node.state();
    if (checkLeadershipTransfer(state)) {
      return;
    }
    if (node.getLocalEndpoint().equals(targetEndpoint)) {
      LOG.warn("{} I am already the leader... There is no leadership transfer to myself.",
        node.localEndpointStr());
      future.completeNull(state.commitIndex());
      return;
    }
    if (state.initLeadershipTransfer(targetEndpoint, future)) {
      transferLeadership(state);
    }
  }

  private boolean checkLeadershipTransfer(RaftState state) {
    RaftNodeStatus status = node.getStatus();
    if (isTerminal(status)) {
      future.fail(node.newNotLeaderException());
      return true;
    }
    if (!node.getCommittedMembers().getVotingMembers().contains(targetEndpoint)) {
      future.fail(new IllegalArgumentException("Cannot transfer leadership to " + targetEndpoint
        + " because it is not in the committed voting group members!"));
      return true;
    }
    if (status != ACTIVE) {
      future.fail(new IllegalStateException(
        "Cannot transfer leadership to " + targetEndpoint + " because the status is " + status));
      return true;
    }
    if (state.leaderState() == null) {
      future.fail(node.newNotLeaderException());
      return true;
    }
    return false;
  }

  private void transferLeadership(RaftState state) {
    LeaderState leaderState = state.leaderState();
    if (leaderState == null) {
      LOG.debug("{} not retrying leadership transfer since not leader...", node.localEndpointStr());
      // no need to notify the future here because it is already
      // completed when the leader steps down to the follower role for
      // any reason.
      assert future.isDone();
      return;
    }
    LeadershipTransferState leadershipTransferState = state.leadershipTransferState();
    if (
      leadershipTransferState == null || !leadershipTransferState.endpoint().equals(targetEndpoint)
    ) {
      LOG.error("{} no leadership transfer state for target endpoint: {}", node.localEndpointStr(),
        targetEndpoint.getId());
      return;
    }
    int tryCount = leadershipTransferState.incrementTryCount();
    RaftConfig config = node.getConfig();
    if (
      config.getLeaderHeartbeatTimeoutMillis() <= tryCount * config.getLeaderHeartbeatPeriodMillis()
    ) {
      String msg = node.localEndpointStr() + " leadership transfer to " + targetEndpoint.getId()
        + " timed out!";
      LOG.warn(msg);
      state.completeLeadershipTransfer(new TimeoutException(msg));
      return;
    }
    if (state.commitIndex() < state.log().lastLogOrSnapshotIndex()) {
      LOG.warn(
        "{} waiting until all appended entries to be committed before transferring leadership to {}",
        node.localEndpointStr(), targetEndpoint.getId());
      scheduleRetry(state);
      return;
    }
    if (tryCount > 1) {
      LOG.debug("{} retrying leadership transfer to {}", node.localEndpointStr(),
        targetEndpoint.getId());
    } else {
      LOG.info("{} transferring leadership to {}", node.localEndpointStr(), targetEndpoint.getId());
    }
    leaderState.getFollowerState(targetEndpoint).resetRequestBackoff();
    node.sendAppendEntriesRequest(targetEndpoint);
    BaseLogEntry entry = state.log().lastLogOrSnapshotEntry();
    RaftMessage request = node.getModelFactory().createTriggerLeaderElectionRequestBuilder()
      .setGroupId(node.getGroupId()).setSender(node.getLocalEndpoint()).setTerm(state.term())
      .setLastLogTerm(entry.getTerm()).setLastLogIndex(entry.getIndex()).build();
    node.send(targetEndpoint, request);
    scheduleRetry(state);
  }

  private void scheduleRetry(RaftState state) {
    try {
      node.getExecutor().schedule(() -> transferLeadership(state),
        node.getConfig().getLeaderHeartbeatPeriodMillis(), MILLISECONDS);
    } catch (Throwable t) {
      LOG.error(node.localEndpointStr() + " failed to schedule retry of leadership transfer to "
        + targetEndpoint.getId(), t);
    }
  }
}
