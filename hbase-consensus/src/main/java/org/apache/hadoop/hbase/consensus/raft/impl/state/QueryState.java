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
package org.apache.hadoop.hbase.consensus.raft.impl.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.statemachine.NoOp;
import org.apache.hadoop.hbase.consensus.raft.impl.util.OrderedFuture;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used to keep query operations until a heartbeat round is completed. These query
 * operations are executed with the linearizability guarantee without growing the Raft log.
 * <p>
 * Section 6.4 of the Raft Dissertation: ... Linearizability requires the results of a read to
 * reflect a state of the system sometime after the read was initiated; each read must at least
 * return the results of the latest committed write. ... Fortunately, it is possible to bypass the
 * Raft log for read-only queries and still preserve linearizability.
 */
@InterfaceAudience.Private
public final class QueryState {
  private static final Logger LOG = LoggerFactory.getLogger(QueryState.class);

  /** Queries waiting to be executed. */
  private final List<QueryContainer> queries = new ArrayList<>();
  /** The set of followers acknowledged the leader in the current query round. */
  private final Set<RaftEndpoint> acks = new HashSet<>();
  /**
   * The minimum log index required to be committed and applied on the leader to execute the
   * queries.
   */
  private long readIndex;
  /**
   * The index of the heartbeat round to execute the currently waiting queries. When a query is
   * received and there is no other query waiting to be executed, a new heartbeat round is started
   * by incrementing this field.
   * <p>
   * Value of this field is put into AppendEntriesRPCs sent to followers and bounced back to the
   * leader to complete the heartbeat round and execute the queries.
   */
  private long querySequenceNumber;

  /**
   * Adds the given query to the collection of queries and returns the number of queries waiting to
   * be executed. Also updates the minimum commit index that is expected on the leader to execute
   * the queries.
   */
  public boolean addQuery(long commitIndex, Object query,
    @SuppressWarnings("rawtypes") OrderedFuture resultFuture) {
    if (commitIndex < readIndex) {
      throw new IllegalArgumentException(
        "Cannot execute query: " + query + " at commit index because of the current " + this);
    }
    if (readIndex < commitIndex) {
      readIndex = commitIndex;
    }
    queries.add(new QueryContainer(query, resultFuture));
    boolean firstQuery = queries.size() == 1;
    if (firstQuery) {
      querySequenceNumber++;
    }
    if (LOG.isTraceEnabled()) {
      LOG.trace("TRACE> QueryState.addQuery commitIndex={} readIndex={} queries={} qsn={} first={}",
        commitIndex, readIndex, queries.size(), querySequenceNumber, firstQuery);
    }
    return firstQuery;
  }

  /**
   * Returns {@code true} if the given follower's ack is accepted for the current query round. It is
   * accepted only if there are waiting queries to be executed and the {@code querySequenceNumber}
   * argument matches to the current query round.
   */
  public boolean tryAck(long querySequenceNumber, RaftEndpoint follower) {
    // If there is no query waiting to be executed or the received ack
    // belongs to an earlier query, we ignore it.
    if (queries.isEmpty() || this.querySequenceNumber > querySequenceNumber) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("TRACE> QueryState.tryAck IGNORED follower={} ackQsn={} curQsn={} queries={}",
          follower.getId(), querySequenceNumber, this.querySequenceNumber, queries.size());
      }
      return false;
    }
    if (querySequenceNumber != this.querySequenceNumber) {
      throw new IllegalStateException(
        this + ", acked query sequence number: " + querySequenceNumber + ", follower: " + follower);
    }
    boolean added = acks.add(follower);
    if (LOG.isTraceEnabled()) {
      LOG.trace("TRACE> QueryState.tryAck follower={} qsn={} added={} acks={} queries={}",
        follower.getId(), querySequenceNumber, added, acks, queries.size());
    }
    return added;
  }

  /** Returns {@code true} if the given follower is removed from the ack list. */
  public boolean removeAck(RaftEndpoint follower) {
    return acks.remove(follower);
  }

  /** Returns the index of the heartbeat round to execute the currently waiting queries. */
  public long querySequenceNumber() {
    return querySequenceNumber;
  }

  /**
   * Advances the query sequence number to invalidate any in-flight acknowledgments from followers
   * that were issued under the previous sequence number. Used when the effective voting membership
   * changes and the leader can no longer rely on the previously dispatched read-quorum round; any
   * subsequent acks for the old qsn must be treated as stale by
   * {@link #tryAck(long, RaftEndpoint)}.
   */
  public void incrementQuerySequenceNumber() {
    long previous = querySequenceNumber;
    querySequenceNumber++;
    acks.clear();
    if (LOG.isTraceEnabled()) {
      LOG.trace("TRACE> QueryState.incrementQuerySequenceNumber {} -> {} queries={}", previous,
        querySequenceNumber, queries.size());
    }
  }

  /**
   * Returns {@code true} if there are queries waiting and acks are received from the log
   * replication quorum.
   * <p>
   * Fails with {@link IllegalStateException} if the given commit index is smaller than
   * {@link #readIndex}.
   */
  public boolean isQuorumAckReceived(long commitIndex, int quorumSize) {
    if (readIndex > commitIndex) {
      throw new IllegalStateException(
        "Cannot execute: " + this + ", current commit index: " + commitIndex);
    }
    boolean ok = queries.size() > 0 && quorumSize <= ackCount();
    if (LOG.isTraceEnabled()) {
      LOG.trace(
        "TRACE> QueryState.isQuorumAckReceived commitIndex={} quorumSize={} queries={} acks={}"
          + " ackCount={} ok={}",
        commitIndex, quorumSize, queries.size(), acks, ackCount(), ok);
    }
    return ok;
  }

  /** Returns the number of collected acks for the current query round. */
  private int ackCount() {
    // +1 is for the leader itself.
    return acks.size() + 1;
  }

  /** Returns {@code true} if more acks are needed to complete the given quorum size. */
  public boolean isAckNeeded(RaftEndpoint follower, int quorumSize) {
    boolean needed = queryCount() > 0 && !acks.contains(follower) && ackCount() < quorumSize;
    if (LOG.isTraceEnabled()) {
      LOG.trace(
        "TRACE> QueryState.isAckNeeded follower={} quorumSize={} queries={} acks={} ackCount={}"
          + " contains={} needed={}",
        follower.getId(), quorumSize, queries.size(), acks, ackCount(), acks.contains(follower),
        needed);
    }
    return needed;
  }

  /** Returns the number of queries waiting for execution. */
  public int queryCount() {
    return queries.size();
  }

  /** Returns the queries waiting to be executed. */
  public Collection<QueryContainer> queries() {
    return queries;
  }

  /** Fails the pending query futures with the given throwable. */
  public void fail(Throwable t) {
    if (LOG.isTraceEnabled()) {
      LOG.trace("TRACE> QueryState.fail queries={} acks={} qsn={} cause={}", queries.size(), acks,
        querySequenceNumber, t.getClass().getSimpleName(), t);
    }
    for (QueryContainer query : queries) {
      query.fail(t);
    }
    reset();
  }

  /** Resets the collection of waiting queries and acks. */
  public void reset() {
    if (LOG.isTraceEnabled()) {
      LOG.trace("TRACE> QueryState.reset queries={} acks={} qsn={}", queries.size(), acks,
        querySequenceNumber);
    }
    queries.clear();
    acks.clear();
  }

  @Override
  public String toString() {
    return "QueryState{" + "readIndex=" + readIndex + ", querySequenceNumber=" + querySequenceNumber
      + ", queryCount=" + queryCount() + ", acks=" + acks + '}';
  }

  @SuppressWarnings("rawtypes")
  public static class QueryContainer {
    final Object operation;
    final OrderedFuture future;

    public QueryContainer(Object operation, OrderedFuture future) {
      this.operation = operation;
      this.future = future;
    }

    @SuppressWarnings("unchecked")
    public void run(long commitIndex, StateMachine stateMachine) {
      try {
        Object result = null;
        if (!(operation instanceof NoOp)) {
          result = stateMachine.runOperation(commitIndex, operation);
        }
        if (LOG.isTraceEnabled()) {
          LOG.trace("TRACE> QueryContainer.run COMPLETE commitIndex={} operation={} resultNull={}",
            commitIndex, operation, result == null);
        }
        future.complete(commitIndex, result);
      } catch (Throwable t) {
        if (LOG.isTraceEnabled()) {
          LOG.trace("TRACE> QueryContainer.run THREW commitIndex={} operation={} cause={}",
            commitIndex, operation, t.getClass().getSimpleName(), t);
        }
        fail(t);
      }
    }

    public void fail(Throwable t) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("TRACE> QueryContainer.fail operation={} cause={}", operation,
          t.getClass().getSimpleName());
      }
      future.fail(t);
    }
  }
}
