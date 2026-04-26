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
import java.util.List;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.handler.AppendEntriesRequestHandler;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;

/**
 * Raft message for the AppendEntries RPC.
 * <p>
 * See <i>5.3 Log replication</i> section of <i>In Search of an Understandable Consensus
 * Algorithm</i> paper by <i>Diego Ongaro</i> and <i>John Ousterhout</i>.
 * <p>
 * Invoked by leader to replicate log entries (§5.3); also used as heartbeat (§5.2).
 * @see AppendEntriesRequestHandler
 */
public interface AppendEntriesRequest extends RaftMessage {
  int getPreviousLogTerm();

  long getPreviousLogIndex();

  long getCommitIndex();

  @NonNull
  List<LogEntry> getLogEntries();

  long getQuerySequenceNumber();

  long getFlowControlSequenceNumber();

  /** The builder interface for {@link AppendEntriesRequest}. */
  interface AppendEntriesRequestBuilder extends RaftMessageBuilder<AppendEntriesRequest> {
    @NonNull
    AppendEntriesRequestBuilder setGroupId(@NonNull Object groupId);

    @NonNull
    AppendEntriesRequestBuilder setSender(@NonNull RaftEndpoint sender);

    @NonNull
    AppendEntriesRequestBuilder setTerm(int term);

    @NonNull
    AppendEntriesRequestBuilder setPreviousLogTerm(int previousLogTerm);

    @NonNull
    AppendEntriesRequestBuilder setPreviousLogIndex(long previousLogIndex);

    @NonNull
    AppendEntriesRequestBuilder setCommitIndex(long commitIndex);

    @NonNull
    AppendEntriesRequestBuilder setLogEntries(@NonNull List<LogEntry> logEntries);

    @NonNull
    AppendEntriesRequestBuilder setQuerySequenceNumber(long querySequenceNumber);

    @NonNull
    AppendEntriesRequestBuilder setFlowControlSequenceNumber(long flowControlSequenceNumber);
  }
}
