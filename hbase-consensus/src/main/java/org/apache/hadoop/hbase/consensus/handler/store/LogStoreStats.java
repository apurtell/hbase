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
package org.apache.hadoop.hbase.consensus.handler.store;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Point-in-time snapshot of {@link UnifiedRaftStore} write-path counters. All counters are
 * monotonic (since process start); diffing two snapshots yields rates over an interval. This is a
 * read-only aggregate intended for tests, scalability harnesses, and operator metrics. The store
 * does not interpret the values.
 */
@InterfaceAudience.LimitedPrivate("test")
@InterfaceStability.Evolving
public final class LogStoreStats {

  private final long fdatasyncCount;
  private final long bytesAppended;
  private final long framesAppended;
  private final long segmentRolls;
  private final long batchesProcessed;
  private final int mailboxDepth;

  public LogStoreStats(long fdatasyncCount, long bytesAppended, long framesAppended,
    long segmentRolls, long batchesProcessed, int mailboxDepth) {
    this.fdatasyncCount = fdatasyncCount;
    this.bytesAppended = bytesAppended;
    this.framesAppended = framesAppended;
    this.segmentRolls = segmentRolls;
    this.batchesProcessed = batchesProcessed;
    this.mailboxDepth = mailboxDepth;
  }

  /** Number of {@code force(false)} (fdatasync-equivalent) calls issued to the active segment. */
  public long getFdatasyncCount() {
    return fdatasyncCount;
  }

  /** Total bytes appended across all segments since process start. */
  public long getBytesAppended() {
    return bytesAppended;
  }

  /** Total frames appended (each frame is a single {@link LogRecord} envelope). */
  public long getFramesAppended() {
    return framesAppended;
  }

  /** Number of times the active segment has rolled over to a new file. */
  public long getSegmentRolls() {
    return segmentRolls;
  }

  /** Number of writer batches processed (a single fsync-coalesced window). */
  public long getBatchesProcessed() {
    return batchesProcessed;
  }

  /** Approximate writer-mailbox depth at snapshot time. */
  public int getMailboxDepth() {
    return mailboxDepth;
  }

  @Override
  public String toString() {
    return "LogStoreStats{" + "fdatasyncCount=" + fdatasyncCount + ", bytesAppended="
      + bytesAppended + ", framesAppended=" + framesAppended + ", segmentRolls=" + segmentRolls
      + ", batchesProcessed=" + batchesProcessed + ", mailboxDepth=" + mailboxDepth + '}';
  }
}
