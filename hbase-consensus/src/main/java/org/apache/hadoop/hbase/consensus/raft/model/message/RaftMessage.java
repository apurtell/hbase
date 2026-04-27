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
package org.apache.hadoop.hbase.consensus.raft.model.message;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModel;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModelFactory;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Implemented by request and response classes of the Raft consensus algorithm RPCs. Raft messages
 * are the objects that go back and forth between Raft nodes.
 * <p>
 * Raft message implementations must be treated as immutable and once a Raft message object is
 * created its contents must not be mutated.
 * <p>
 * {@link RaftMessage} objects are created by {@link RaftModelFactory}.
 * @see RaftModel
 * @see RaftModelFactory
 */
@InterfaceAudience.Private
public interface RaftMessage extends RaftModel {
  /**
   * Returns the group id of the Raft node which created this message
   * @return the group id of the Raft node which created this message
   */
  @NonNull
  Object getGroupId();

  /**
   * Returns the endpoint of the Raft node which created this message
   * @return the endpoint of the Raft node which created this message
   */
  @NonNull
  RaftEndpoint getSender();

  /**
   * Returns the term at which the Raft node created this message
   * @return the term at which the Raft node created this message
   */
  int getTerm();

  /**
   * The base builder interface for Raft message classes
   * @param <T> the concrete type of the Raft message
   */
  interface RaftMessageBuilder<T extends RaftMessage> {
    @NonNull
    T build();
  }
}
