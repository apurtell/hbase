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
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Tracks per-RS keepalive timestamps observed on the {@code sender} / {@code epoch} / {@code tick}
 * fields of inbound {@code HeartbeatBatchPB} envelopes. Used by quiescent followers to drive
 * failure detection without per-group heartbeat traffic.
 * <p>
 * Implementations are thread-safe. {@link #onPeerKeepalive} is called from Netty event-loop
 * threads, while {@link #lastKeepaliveMillis} is called from per-group {@code RaftNodeExecutor}
 * threads.
 */
@InterfaceAudience.Private
public interface PeerKeepaliveTracker {
  /**
   * Records that the given {@code sender} emitted an envelope at {@code (epoch, tick)} that the
   * receiver observed at {@code nowMillis} wall-clock. If a higher-numbered observation is already
   * recorded for {@code sender} the call is a no-op.
   */
  void onPeerKeepalive(@NonNull RaftEndpoint sender, long epoch, long tick, long nowMillis);

  /**
   * Returns the most recent wall-clock time (ms) at which an envelope from {@code sender} was
   * observed by this RS, or {@code 0L} if none has been recorded.
   */
  long lastKeepaliveMillis(@NonNull RaftEndpoint sender);
}
