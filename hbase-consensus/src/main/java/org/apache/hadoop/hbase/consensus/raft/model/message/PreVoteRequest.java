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
 * Raft message for the PreVoteRequest RPC.
 * <p>
 * See <i>Four modifications for the Raft consensus algorithm</i> by Henrik Ingo.
 * @see VoteRequest
 */
public interface PreVoteRequest extends RaftMessage {
  int getLastLogTerm();

  long getLastLogIndex();

  /**
   * The builder interface for {@link PreVoteRequest}.
   */
  interface PreVoteRequestBuilder extends RaftMessageBuilder<PreVoteRequest> {
    @NonNull
    PreVoteRequestBuilder setGroupId(@NonNull Object groupId);

    @NonNull
    PreVoteRequestBuilder setSender(@NonNull RaftEndpoint sender);

    @NonNull
    PreVoteRequestBuilder setTerm(int term);

    @NonNull
    PreVoteRequestBuilder setLastLogTerm(int lastLogTerm);

    @NonNull
    PreVoteRequestBuilder setLastLogIndex(long lastLogIndex);
  }
}
