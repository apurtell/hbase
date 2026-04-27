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
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * A Raft leader may demote to the follower role after it appends an entry to its local Raft log,
 * but before discovering its commit status. In some of these cases, which cause this exception to
 * be thrown, it may happen that the Raft node cannot determine if the operation is committed or
 * not. In this case, the {@code CompletableFuture} objects returned for these operations are
 * notified with this exception.
 * <p>
 * It is up to the clients to decide on retry upon receiving this exception. If the operation is
 * retried either on the same or different Raft node, it could be committed twice, hence causes
 * at-least-once execution. On the contrary, if {@link RaftNode#replicate(Object)} is not called
 * again, then at-most-once execution happens.
 * <p>
 * Idempotent operations can be retried on indeterminate situations.
 */
@InterfaceAudience.Private
public class IndeterminateStateException extends RaftException {
  private static final long serialVersionUID = -736303015926722821L;

  /**
   * Creates an instance of the exception.
   */
  public IndeterminateStateException() {
    this(null);
  }

  /**
   * Creates an instance of the exception. The leader endpoint can be passed if it is known. the
   * leader endpoint if it is known, null otherwise
   */
  public IndeterminateStateException(RaftEndpoint leader) {
    super(leader);
  }

  @Override
  public String toString() {
    return "IndeterminateStateException{leader=" + getLeader() + "}";
  }
}
