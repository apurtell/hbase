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

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/** Point-in-time snapshot of one peer's outbound transport counters. */
@InterfaceAudience.LimitedPrivate("test")
@InterfaceStability.Evolving
public final class OutboundChannelStats {

  private final long heartbeatFrames;
  private final long appendFrames;
  private final long heartbeatAckFrames;
  private final long immediateFrames;
  private final long messagesEnqueued;
  private final int pendingMailboxDepth;
  private final long forcedFlushesByDeadline;

  public OutboundChannelStats(long heartbeatFrames, long appendFrames, long heartbeatAckFrames,
    long immediateFrames, long messagesEnqueued, int pendingMailboxDepth,
    long forcedFlushesByDeadline) {
    this.heartbeatFrames = heartbeatFrames;
    this.appendFrames = appendFrames;
    this.heartbeatAckFrames = heartbeatAckFrames;
    this.immediateFrames = immediateFrames;
    this.messagesEnqueued = messagesEnqueued;
    this.pendingMailboxDepth = pendingMailboxDepth;
    this.forcedFlushesByDeadline = forcedFlushesByDeadline;
  }

  /** Number of {@code HEARTBEAT_BATCH} frames this channel has flushed. */
  public long getHeartbeatFrames() {
    return heartbeatFrames;
  }

  /** Number of {@code BATCH_APPEND} frames this channel has flushed. */
  public long getAppendFrames() {
    return appendFrames;
  }

  /** Number of {@code HEARTBEAT_ACK_BATCH} frames this channel has flushed. */
  public long getHeartbeatAckFrames() {
    return heartbeatAckFrames;
  }

  /** Number of immediately-sent (non-coalesced) frames this channel has flushed. */
  public long getImmediateFrames() {
    return immediateFrames;
  }

  /** Total {@link #getHeartbeatFrames()} + {@link #getAppendFrames()} + ack + immediate frames. */
  public long getTotalFrames() {
    return heartbeatFrames + appendFrames + heartbeatAckFrames + immediateFrames;
  }

  /** Number of individual messages this channel has enqueued for batching. */
  public long getMessagesEnqueued() {
    return messagesEnqueued;
  }

  /** Approximate mailbox depth at the moment this snapshot was taken. */
  public int getPendingMailboxDepth() {
    return pendingMailboxDepth;
  }

  /**
   * Number of times the deadline-based fail-safe wakeup fired (head of mailbox aged past
   * {@code hbase.consensus.transport.flush.deadline.ms}). A persistently non-zero value indicates
   * the periodic batch tick is being starved by event-loop contention; a tail-latency regression
   * test in {@code TestConsensusServerScale} alarms when this happens at scale.
   */
  public long getForcedFlushesByDeadline() {
    return forcedFlushesByDeadline;
  }

  @Override
  public String toString() {
    return "OutboundChannelStats{" + "hb=" + heartbeatFrames + ", app=" + appendFrames + ", ack="
      + heartbeatAckFrames + ", imm=" + immediateFrames + ", enq=" + messagesEnqueued + ", depth="
      + pendingMailboxDepth + ", deadlineFlushes=" + forcedFlushesByDeadline + '}';
  }
}
