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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Configuration parsed from a Hadoop {@link Configuration}.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
public final class LogStoreConfig {
  /**
   * Filesystem directory holding the multiplexed consensus log. The store creates and owns
   * {@code raft-NNN.log} files inside this directory.
   */
  public static final String KEY_LOG_DIR = "hbase.consensus.log.dir";

  /**
   * Target size of a single segment file in megabytes. The writer rolls to a new segment as soon as
   * the active segment crosses this size after a coalesced write batch.
   */
  public static final String KEY_SEGMENT_SIZE_MB = "hbase.consensus.log.segment.size.mb";
  public static final int DEFAULT_SEGMENT_SIZE_MB = 256;

  /**
   * Coalescing window in milliseconds for the writer thread. After taking the first
   * {@code PendingWrite} off the mailbox, the writer drains additional pending writes for up to
   * this much wall-clock time before issuing a single gathered {@code FileChannel.write}. Shares
   * the same key string with {@code TransportConfig.KEY_BATCH_MS}.
   */
  public static final String KEY_BATCH_MS = "hbase.consensus.log.sync.batch.ms";
  public static final long DEFAULT_BATCH_MS = 10L;

  /**
   * Cadence in milliseconds at which the {@code DurableLogStore} fires
   * {@code FileChannel.force(false)} on the active segment to bound page-cache loss for entries
   * that did not request synchronous fsync. Sync-fsync paths ({@code persistAndFlush*}) always
   * force inline regardless of this knob.
   */
  public static final String KEY_FDATASYNC_INTERVAL_MS =
    "hbase.consensus.log.fdatasync.interval.ms";
  public static final long DEFAULT_FDATASYNC_INTERVAL_MS = 100L;

  /** MPSC mailbox initial chunk size. */
  public static final String KEY_MAILBOX_CHUNK_SIZE = "hbase.consensus.log.writer.mailbox.chunk";
  public static final int DEFAULT_MAILBOX_CHUNK_SIZE = 1024;

  private final File logDir;
  private final int segmentSizeMb;
  private final long batchMs;
  private final long fdatasyncIntervalMs;
  private final int mailboxChunkSize;

  /** Test-only override for segment size in bytes. */
  private final long segmentSizeBytesOverride;

  public LogStoreConfig(@NonNull Configuration conf) {
    String dirStr = conf.get(KEY_LOG_DIR);
    if (dirStr == null || dirStr.isEmpty()) {
      throw new IllegalArgumentException(KEY_LOG_DIR + " is required");
    }
    this.logDir = new File(dirStr);
    this.segmentSizeMb = conf.getInt(KEY_SEGMENT_SIZE_MB, DEFAULT_SEGMENT_SIZE_MB);
    this.batchMs = conf.getLong(KEY_BATCH_MS, DEFAULT_BATCH_MS);
    this.fdatasyncIntervalMs =
      conf.getLong(KEY_FDATASYNC_INTERVAL_MS, DEFAULT_FDATASYNC_INTERVAL_MS);
    this.mailboxChunkSize = conf.getInt(KEY_MAILBOX_CHUNK_SIZE, DEFAULT_MAILBOX_CHUNK_SIZE);
    this.segmentSizeBytesOverride = -1L;
    validate();
  }

  public LogStoreConfig(@NonNull File logDir, int segmentSizeMb, long batchMs,
    long fdatasyncIntervalMs, int mailboxChunkSize) {
    this(logDir, segmentSizeMb, batchMs, fdatasyncIntervalMs, mailboxChunkSize, -1L);
  }

  public LogStoreConfig(@NonNull File logDir, int segmentSizeMb, long batchMs,
    long fdatasyncIntervalMs, int mailboxChunkSize, long segmentSizeBytesOverride) {
    this.logDir = logDir;
    this.segmentSizeMb = segmentSizeMb;
    this.batchMs = batchMs;
    this.fdatasyncIntervalMs = fdatasyncIntervalMs;
    this.mailboxChunkSize = mailboxChunkSize;
    this.segmentSizeBytesOverride = segmentSizeBytesOverride;
    validate();
  }

  private void validate() {
    if (logDir == null) {
      throw new IllegalArgumentException(KEY_LOG_DIR + " must not be null");
    }
    if (segmentSizeMb < 1) {
      throw new IllegalArgumentException(
        KEY_SEGMENT_SIZE_MB + " must be >= 1, got " + segmentSizeMb);
    }
    if (batchMs < 1) {
      throw new IllegalArgumentException(KEY_BATCH_MS + " must be >= 1, got " + batchMs);
    }
    if (fdatasyncIntervalMs < batchMs) {
      throw new IllegalArgumentException(KEY_FDATASYNC_INTERVAL_MS + " (" + fdatasyncIntervalMs
        + ") must be >= " + KEY_BATCH_MS + " (" + batchMs + ")");
    }
    if (mailboxChunkSize < 1) {
      throw new IllegalArgumentException(
        KEY_MAILBOX_CHUNK_SIZE + " must be >= 1, got " + mailboxChunkSize);
    }
  }

  @NonNull
  public File getLogDir() {
    return logDir;
  }

  public int getSegmentSizeMb() {
    return segmentSizeMb;
  }

  public long getSegmentSizeBytes() {
    return segmentSizeBytesOverride > 0
      ? segmentSizeBytesOverride
      : (long) segmentSizeMb * 1024L * 1024L;
  }

  public long getBatchMs() {
    return batchMs;
  }

  public long getFdatasyncIntervalMs() {
    return fdatasyncIntervalMs;
  }

  public int getMailboxChunkSize() {
    return mailboxChunkSize;
  }
}
