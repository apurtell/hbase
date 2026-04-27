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
import org.apache.hadoop.hbase.conf.ConfigKey;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Configuration parsed from a Hadoop {@link Configuration}.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
@InterfaceStability.Evolving
public final class LogStoreConfig {
  /**
   * Filesystem directory holding the multiplexed consensus log. The store creates and owns
   * {@code raft-NNN.log} files inside this directory.
   */
  public static final String LOG_DIR_KEY = "hbase.consensus.log.dir";

  /**
   * Target size of a single segment file in megabytes. The writer rolls to a new segment as soon as
   * the active segment crosses this size after a coalesced write batch.
   */
  public static final String SEGMENT_SIZE_MB_KEY =
    ConfigKey.INT("hbase.consensus.log.segment.size.mb", v -> v >= 1);
  public static final int SEGMENT_SIZE_MB_DEFAULT = 256;

  /**
   * Coalescing window in milliseconds for the writer thread. After taking the first
   * {@code PendingWrite} off the mailbox, the writer drains additional pending writes for up to
   * this much wall-clock time before issuing a single gathered {@code FileChannel.write}. Shares
   * the same key string with {@code TransportConfig.BATCH_MS_KEY}.
   */
  public static final String BATCH_MS_KEY =
    ConfigKey.LONG("hbase.consensus.log.sync.batch.ms", v -> v >= 1L);
  public static final long BATCH_MS_DEFAULT = 10L;

  /**
   * Cadence in milliseconds at which the {@code DurableLogStore} fires
   * {@code FileChannel.force(false)} on the active segment to bound page-cache loss for entries
   * that did not request synchronous fsync. Sync-fsync paths ({@code persistAndFlush*}) always
   * force inline regardless of this knob.
   */
  public static final String FDATASYNC_INTERVAL_MS_KEY =
    ConfigKey.LONG("hbase.consensus.log.fdatasync.interval.ms", v -> v >= 1L);
  public static final long FDATASYNC_INTERVAL_MS_DEFAULT = 100L;

  /** MPSC mailbox initial chunk size. */
  public static final String MAILBOX_CHUNK_SIZE_KEY =
    ConfigKey.INT("hbase.consensus.log.writer.mailbox.chunk", v -> v >= 1);
  public static final int MAILBOX_CHUNK_SIZE_DEFAULT = 1024;

  private final File logDir;
  private final int segmentSizeMb;
  private final long batchMs;
  private final long fdatasyncIntervalMs;
  private final int mailboxChunkSize;

  /** Test-only override for segment size in bytes. */
  private final long segmentSizeBytesOverride;

  public LogStoreConfig(@NonNull Configuration conf) {
    String dirStr = conf.get(LOG_DIR_KEY);
    if (dirStr == null || dirStr.isEmpty()) {
      throw new IllegalArgumentException(LOG_DIR_KEY + " is required");
    }
    this.logDir = new File(dirStr);
    this.segmentSizeMb = conf.getInt(SEGMENT_SIZE_MB_KEY, SEGMENT_SIZE_MB_DEFAULT);
    this.batchMs = conf.getLong(BATCH_MS_KEY, BATCH_MS_DEFAULT);
    this.fdatasyncIntervalMs =
      conf.getLong(FDATASYNC_INTERVAL_MS_KEY, FDATASYNC_INTERVAL_MS_DEFAULT);
    this.mailboxChunkSize = conf.getInt(MAILBOX_CHUNK_SIZE_KEY, MAILBOX_CHUNK_SIZE_DEFAULT);
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
      throw new IllegalArgumentException(LOG_DIR_KEY + " must not be null");
    }
    if (segmentSizeMb < 1) {
      throw new IllegalArgumentException(
        SEGMENT_SIZE_MB_KEY + " must be >= 1, got " + segmentSizeMb);
    }
    if (batchMs < 1) {
      throw new IllegalArgumentException(BATCH_MS_KEY + " must be >= 1, got " + batchMs);
    }
    if (fdatasyncIntervalMs < batchMs) {
      throw new IllegalArgumentException(FDATASYNC_INTERVAL_MS_KEY + " (" + fdatasyncIntervalMs
        + ") must be >= " + BATCH_MS_KEY + " (" + batchMs + ")");
    }
    if (mailboxChunkSize < 1) {
      throw new IllegalArgumentException(
        MAILBOX_CHUNK_SIZE_KEY + " must be >= 1, got " + mailboxChunkSize);
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
