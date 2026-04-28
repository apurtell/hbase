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
package org.apache.hadoop.hbase.consensus.handler.transport;

import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Point-in-time snapshot of one peer's outbound transport counters.
 * <p>
 * Per-kind frame counters are exposed alongside the {@code AppendEntriesRequest} enqueue counter
 * for the bulk lane. Heartbeat traffic does not flow through the per-message mailbox: the
 * per-server timing wheel hands one pre-aggregated bulk envelope per (peer, tick) straight to the
 * channel via the bulk-heartbeat send path, which increments {@link #getHeartbeatFrames()} once per
 * emission. Heartbeat acks emitted by the inbound bulk handler increment
 * {@link #getHeartbeatAckFrames()} once per emission.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.UNITTEST })
@InterfaceStability.Evolving
public final class OutboundChannelStats {

  private final long heartbeatFrames;
  private final long appendFrames;
  private final long heartbeatAckFrames;
  private final long appendsEnqueued;

  public OutboundChannelStats(long heartbeatFrames, long appendFrames, long heartbeatAckFrames,
    long appendsEnqueued) {
    this.heartbeatFrames = heartbeatFrames;
    this.appendFrames = appendFrames;
    this.heartbeatAckFrames = heartbeatAckFrames;
    this.appendsEnqueued = appendsEnqueued;
  }

  /** Number of {@code BULK_HEARTBEAT} frames this channel has flushed. */
  public long getHeartbeatFrames() {
    return heartbeatFrames;
  }

  /** Number of {@code BATCH_APPEND} frames this channel has flushed. */
  public long getAppendFrames() {
    return appendFrames;
  }

  /** Number of {@code BULK_HEARTBEAT_ACK} frames this channel has flushed. */
  public long getHeartbeatAckFrames() {
    return heartbeatAckFrames;
  }

  /** Number of individual {@code AppendEntriesRequest} messages this channel has enqueued. */
  public long getAppendsEnqueued() {
    return appendsEnqueued;
  }

  @Override
  public String toString() {
    return "OutboundChannelStats{" + "hb=" + heartbeatFrames + ", app=" + appendFrames + ", ack="
      + heartbeatAckFrames + ", appEnq=" + appendsEnqueued + '}';
  }
}
