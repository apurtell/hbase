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
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Response for {@link PreVoteRequest}.
 * <p>
 * See <i>Four modifications for the Raft consensus algorithm</i> by Henrik Ingo.
 * @see PreVoteRequest
 * @see VoteResponse
 */
@InterfaceAudience.Private
public interface PreVoteResponse extends RaftMessage {
  boolean isGranted();

  /** The builder interface for {@link PreVoteResponse}. */
  interface PreVoteResponseBuilder extends RaftMessageBuilder<PreVoteResponse> {
    @NonNull
    PreVoteResponseBuilder setGroupId(@NonNull Object groupId);

    @NonNull
    PreVoteResponseBuilder setSender(@NonNull RaftEndpoint sender);

    @NonNull
    PreVoteResponseBuilder setTerm(int term);

    @NonNull
    PreVoteResponseBuilder setGranted(boolean granted);
  }
}
