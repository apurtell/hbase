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
package org.apache.hadoop.hbase.consensus.raft.exception;

import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Base exception class for Raft-related exceptions.
 * <p>
 * <b>Note:</b> This extends {@link RuntimeException} rather than the standard HBase
 * {@code HBaseException}. Raft state-machine exceptions surface from asynchronous
 * {@link java.util.concurrent.CompletableFuture}s on the executor thread. The API surface wraps
 * these in unchecked exceptions so callers can either inspect the {@code CompletionException}'s
 * cause or let {@link java.util.concurrent.CompletableFuture#exceptionally} handle them.
 */
@InterfaceAudience.Private
public class RaftException extends RuntimeException {
  private static final long serialVersionUID = 3165333502175586105L;
  private final RaftEndpoint leader;

  /**
   * Creates an instance of this exception with no leader hint.
   * @param cause the underlying reason for which this exception is being thrown
   */
  public RaftException(Throwable cause) {
    super(cause);
    this.leader = null;
  }

  /**
   * Creates an instance of this exception carrying the leader endpoint known to the local Raft
   * node.
   * @param leader the Raft endpoint of the leader known by the local Raft node, or {@code null} if
   *               unknown
   */
  public RaftException(RaftEndpoint leader) {
    this.leader = leader;
  }

  /**
   * Creates an instance of this exception with a message and the leader endpoint known to the local
   * Raft node.
   * @param message the exception message
   * @param leader  the Raft endpoint of the leader known by the local Raft node, or {@code null} if
   *                unknown
   */
  public RaftException(String message, RaftEndpoint leader) {
    super(message);
    this.leader = leader;
  }

  /**
   * Creates an instance of this exception with a message, the leader endpoint known to the local
   * Raft node, and an underlying cause.
   * @param message the exception message
   * @param leader  the Raft endpoint of the leader known by the local Raft node, or {@code null} if
   *                unknown
   * @param cause   the underlying reason for which this exception is being thrown
   */
  public RaftException(String message, RaftEndpoint leader, Throwable cause) {
    super(message, cause);
    this.leader = leader;
  }

  /**
   * Returns the leader endpoint of the related Raft group, if available and known by the Raft node
   * by the time this exception is thrown.
   * @return the leader endpoint of the related Raft group, if available and known by the Raft node
   *         by the time this exception is thrown.
   */
  public RaftEndpoint getLeader() {
    return leader;
  }

  @Override
  public String toString() {
    return "RaftException{leader=" + getLeader() + "}";
  }
}
