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

import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.INITIAL;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.UPDATING_RAFT_GROUP_MEMBER_LIST;
import static org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus.isTerminal;
import static org.apache.hadoop.hbase.consensus.raft.RaftRole.LEADER;

import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.hadoop.hbase.consensus.raft.exception.CannotReplicateException;
import org.apache.hadoop.hbase.consensus.raft.exception.NotLeaderException;
import org.apache.hadoop.hbase.consensus.raft.exception.RaftException;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.log.RaftLog;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftState;
import org.apache.hadoop.hbase.consensus.raft.impl.util.OrderedFuture;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends the given operation to the log of the given leader Raft node and replicates the new log
 * entry to the followers.
 * <p>
 * Scheduled by {@link RaftNode#replicate(Object)}, or {@link MembershipChangeTask} for membership
 * changes.
 * <p>
 * If the given Raft node is not the leader, the future is notified with {@link NotLeaderException}.
 * <p>
 * If the given operation could not be appended to the Raft log at the moment, (see
 * {@link RaftNodeImpl#canReplicateNewOperation(Object)}), the future is notified with
 * {@link CannotReplicateException}.
 */
@InterfaceAudience.Private
public final class ReplicateTask implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(ReplicateTask.class);
  private final RaftNodeImpl raftNode;
  private final RaftState state;
  private final Object operation;
  private final OrderedFuture future;

  public ReplicateTask(RaftNodeImpl raftNode, Object operation, OrderedFuture future) {
    this.raftNode = raftNode;
    this.state = raftNode.state();
    this.operation = operation;
    this.future = future;
  }

  @Override
  public void run() {
    try {
      LOG.trace("TRACE> {} ReplicateTask.run operation={} role={} status={} term={}",
        raftNode.localEndpointStr(), operation, state.role(), raftNode.getStatus(), state.term());
      if (!verifyRaftNodeStatus()) {
        return;
      } else if (state.role() != LEADER) {
        future.fail(raftNode.newNotLeaderException());
        return;
      } else if (!raftNode.canReplicateNewOperation(operation)) {
        future.fail(raftNode.newCannotReplicateException());
        return;
      }
      RaftLog log = state.log();
      if (!log.checkAvailableCapacity(1)) {
        future.fail(new IllegalStateException("Not enough capacity in RaftLog!"));
        return;
      }
      long newEntryLogIndex = log.lastLogOrSnapshotIndex() + 1;
      state.registerFuture(newEntryLogIndex, future);
      LogEntry entry = raftNode.getModelFactory().createLogEntryBuilder().setTerm(state.term())
        .setIndex(newEntryLogIndex).setOperation(operation).build();
      log.appendEntry(entry);
      LOG.trace("TRACE> {} ReplicateTask appended index={} term={} op={}",
        raftNode.localEndpointStr(), newEntryLogIndex, state.term(), operation);
      prepareGroupOp(newEntryLogIndex, operation);
      raftNode.broadcastAppendEntriesRequest();
      if (
        state.logReplicationQuorumSize() == 1
          && !raftNode.submitLeaderFlushTask(state.leaderState())
      ) {
        // If this is a singleton Raft group and persistence is enabled,
        // we submit the flush task to amortize disk writes. Otherwise,
        // we commit the new log entry directly.
        raftNode.tryAdvanceCommitIndex();
      }
    } catch (Throwable t) {
      LOG.error(raftNode.localEndpointStr() + " " + operation
        + " could not be replicated to leader: " + raftNode.getLocalEndpoint(), t);
      future.fail(new RaftException("Internal failure", raftNode.getLeaderEndpoint(), t));
    }
  }

  private boolean verifyRaftNodeStatus() {
    RaftNodeStatus status = raftNode.getStatus();
    if (status == INITIAL) {
      LOG.debug("{} Won't run {}, since Raft node is not started.", raftNode.localEndpointStr(),
        operation);
      future.fail(raftNode.newCannotReplicateException());
      return false;
    } else if (isTerminal(status)) {
      LOG.debug("{} Won't run {}, since Raft node is {}.", raftNode.localEndpointStr(), operation,
        status);
      future.fail(raftNode.newNotLeaderException());
      return false;
    }
    return true;
  }

  private void prepareGroupOp(long logIndex, Object operation) {
    if (operation instanceof UpdateRaftGroupMembersOp) {
      UpdateRaftGroupMembersOp groupOp = (UpdateRaftGroupMembersOp) operation;
      LOG.trace(
        "TRACE> {} ReplicateTask.prepareGroupOp logIndex={} mode={} endpoint={} newMembers={}"
          + " newVoting={}",
        raftNode.localEndpointStr(), logIndex, groupOp.getMode(), groupOp.getEndpoint().getId(),
        groupOp.getMembers(), groupOp.getVotingMembers());
      raftNode.setStatus(UPDATING_RAFT_GROUP_MEMBER_LIST);
      raftNode.updateGroupMembers(logIndex, groupOp.getMembers(), groupOp.getVotingMembers());
    }
  }
}
