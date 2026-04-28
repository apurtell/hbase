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
package org.apache.hadoop.hbase.consensus.raft.transport;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModel;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Used for communicating Raft nodes with each other.
 * <p>
 * Transport implementations must be non-blocking. A Raft node must be able to send a Raft message
 * to another Raft node and continue without blocking. This is required because Raft nodes run
 * concurrently with the Actor model.
 * <p>
 * Transport implementations must be able to serialize {@link RaftMessage} objects created by
 * {@link RaftModelFactory}.
 * <p>
 * A {@link Transport} implementation can implement {@link RaftNodeLifecycleAware} to perform
 * initialization and clean up work during {@link RaftNode} startup and termination.
 * {@link RaftNode} calls {@link RaftNodeLifecycleAware#onRaftNodeStart()} before calling any other
 * method on {@link Transport}, and finally calls
 * {@link RaftNodeLifecycleAware#onRaftNodeTerminate()} on termination.
 * @see RaftModel
 * @see RaftMessage
 * @see RaftModelFactory
 * @see RaftNode
 * @see RaftNodeExecutor
 * @see RaftNodeLifecycleAware
 */
@InterfaceAudience.Private
public interface Transport {
  /**
   * Sends the given {@link RaftMessage} object to the given endpoint. This method must not block
   * the caller Raft node instance and return promptly so that the caller can continue its
   * execution.
   * <p>
   * This method must not throw an exception, for example if the given {@link RaftMessage} object
   * has not been sent to the given endpoint or an internal error has occurred. The handling of
   * {@link RaftMessage} objects are designed idempotently. Therefore, if a {@link RaftMessage}
   * object is not sent to the given endpoint, it implies that the source Raft node will not receive
   * a {@link RaftMessage} as response, hence it will re-send the failed {@link RaftMessage} again.
   */
  void send(@NonNull RaftEndpoint target, @NonNull RaftMessage message);

  /**
   * Returns true if the given endpoint is supposedly reachable by the time this method is called,
   * false otherwise.
   * <p>
   * This method is not required to return a precise information. For instance, the Transport
   * implementation does not need to ping the given endpoint to check if it is reachable when this
   * method is called. Instead, the local Raft node could use a local information, such as recency
   * of a message sent by or having a TCP connection to the given Raft endpoint
   * @return true if given endpoint is reachable, false otherwise
   */
  boolean isReachable(@NonNull RaftEndpoint endpoint);

  /**
   * Sends a bulk heartbeat envelope from the local node to {@code target}.
   * <p>
   * The bulk path bypasses the per-{@link RaftMessage} {@link #send} pipeline so that the heartbeat
   * tick can emit one envelope per peer per tick. Implementations must serialize the envelope level
   * keepalive header (sender / epoch / tick) plus the carried per group entries on the wire, or,
   * for the in-process transport, dispatch each per-group entry to the addressed local node
   * directly. The call must not block the caller. Failures are absorbed silently. The next tick
   * will retry.
   */
  void sendBulkHeartbeat(@NonNull RaftEndpoint target, @NonNull BulkHeartbeatFrame frame);

  /**
   * Sends a bulk heartbeat-ack envelope from the local node back to {@code target}, mirroring an
   * inbound {@link BulkHeartbeatFrame} the receiver acks. Same non-blocking and best-effort
   * semantics as {@link #sendBulkHeartbeat}.
   */
  void sendBulkHeartbeatAck(@NonNull RaftEndpoint target, @NonNull BulkHeartbeatAckFrame frame);
}
