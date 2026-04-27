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
package org.apache.hadoop.hbase.consensus.handler.statemachine;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Service-provider interface consumed by the consensus-side {@link StateMachineAdapter} and
 * {@link LeaderReportListener}, implemented by the application embedding the consensus core.
 * <p>
 * The SPI cleanly separates two distinct catch-up concerns:
 * <ul>
 * <li><b>Raft consensus state</b> (term, log entries, snapshot term/index, group-members view) is
 * delivered by the core's standard {@code AppendEntries} and chunked {@code InstallSnapshot} wire
 * path. The SPI does not see this traffic at all. It only observes the application-visible commit
 * batches and snapshot take/install endpoints.</li>
 * <li><b>Application data state</b> is the concern of the SPI implementer. The byte payload
 * exchanged via {@link #takeStateSnapshot(Object, long)} /
 * {@link #installStateSnapshot(Object, long, byte[])} is opaque to the consensus layer.</li>
 * </ul>
 * <p>
 * SPI methods are invoked from the per-group Raft executor thread that owns the underlying
 * {@code StateMachine}. Implementations therefore do not need to be thread-safe with respect to a
 * single group, but must not block.
 */
@InterfaceAudience.Private
public interface ConsensusSpi {

  /**
   * Invoked once per drained batch with the committed entries the Raft node applied since the
   * previous {@code onCommit} or {@link #onFlushComplete(Object, FlushMarker)} call. The list is
   * non-empty and ordered by ascending {@code commitIndex}.
   * @param groupId the Raft group id this batch belongs to
   * @param batch   the committed entries, in commit-index order; never empty
   */
  void onCommit(@NonNull Object groupId, @NonNull List<CommittedEntry> batch);

  /**
   * Invoked when the Raft node applies a {@link FlushMarker} log entry. The implementation must
   * have already received any preceding entries via {@link #onCommit(Object, List)}. This call
   * signals the boundary so the SPI can finalise the flush.
   * @param groupId the Raft group id
   * @param marker  the flush marker delivered as the log-entry operation
   */
  void onFlushComplete(@NonNull Object groupId, @NonNull FlushMarker marker);

  /**
   * Invoked exactly once per term when the Raft node observes a leader being elected.
   * @param groupId the Raft group id
   * @param term    the term the leader was elected in
   * @param leader  the elected leader's endpoint (may be the local endpoint)
   */
  void onLeaderElected(@NonNull Object groupId, int term, @NonNull RaftEndpoint leader);

  /**
   * Invoked when the Raft node transitions out of the {@code LEADER} role, or otherwise loses
   * recognition of any leader for the group.
   * @param groupId the Raft group id
   */
  void onNoLeader(@NonNull Object groupId);

  /**
   * Optional notification fired by the leader when a follower has been observed lagging far enough
   * behind to potentially require a snapshot install. Implementations may use this to schedule a
   * {@link #takeStateSnapshot(Object, long)} eagerly.
   * <p>
   * The default implementation is a no-op.
   * @param groupId the Raft group id
   * @param peer    the lagging follower's endpoint
   */
  default void onFollowerLagging(@NonNull Object groupId, @NonNull RaftEndpoint peer) {
  }

  /**
   * Captures an opaque snapshot of the application state at the given commit index. The returned
   * bytes are forwarded by the consensus core to lagging followers as a single chunk via the
   * standard {@code InstallSnapshot} wire path. They are not interpreted by the consensus layer.
   * @param groupId     the Raft group id
   * @param commitIndex the commit index at which the snapshot is taken
   * @return opaque snapshot bytes; must be non-null but may be empty
   */
  @NonNull
  byte[] takeStateSnapshot(@NonNull Object groupId, long commitIndex);

  /**
   * Installs the opaque snapshot bytes previously produced by
   * {@link #takeStateSnapshot(Object, long)} on the leader. The implementer interprets the bytes to
   * reconstruct the application state.
   * @param groupId     the Raft group id
   * @param commitIndex the commit index the snapshot is anchored at
   * @param snapshot    the opaque snapshot bytes; never null
   */
  void installStateSnapshot(@NonNull Object groupId, long commitIndex, @NonNull byte[] snapshot);

  /**
   * Returns the operation to be appended by a newly-elected leader as its first log entry of a new
   * term. Returning {@code null} means the consensus core's default new-term operation is used.
   * <p>
   * The default implementation returns {@code null}.
   * @return the application-supplied new-term operation, or {@code null}
   */
  @Nullable
  default Object getNewTermOperation() {
    return null;
  }
}
