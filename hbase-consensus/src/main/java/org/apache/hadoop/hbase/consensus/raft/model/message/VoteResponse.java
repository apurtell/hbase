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

/**
 * Response for {@link VoteRequest}.
 * <p>
 * See <i>5.2 Leader election</i> section of <i>In Search of an Understandable Consensus
 * Algorithm</i> paper by <i>Diego Ongaro</i> and <i>John Ousterhout</i>.
 * @see VoteRequest
 */
public interface VoteResponse extends RaftMessage {
  boolean isGranted();

  /**
   * The builder interface for {@link VoteResponse}.
   */
  interface VoteResponseBuilder extends RaftMessageBuilder<VoteResponse> {
    @NonNull
    VoteResponseBuilder setGroupId(@NonNull Object groupId);

    @NonNull
    VoteResponseBuilder setSender(@NonNull RaftEndpoint sender);

    @NonNull
    VoteResponseBuilder setTerm(int term);

    @NonNull
    VoteResponseBuilder setGranted(boolean granted);
  }
}
