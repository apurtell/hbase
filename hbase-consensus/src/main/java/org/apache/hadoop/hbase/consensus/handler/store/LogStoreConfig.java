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
 * Log Store configuration.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
@InterfaceStability.Evolving
public final class LogStoreConfig {
  /** Filesystem directory holding the multiplexed consensus log. */
  public static final String LOG_DIR_KEY = "hbase.consensus.log.dir";

  /** Target size of a single segment file in megabytes. */
  public static final String SEGMENT_SIZE_MB_KEY =
    ConfigKey.INT("hbase.consensus.log.segment.size.mb", v -> v >= 1);
  public static final int SEGMENT_SIZE_MB_DEFAULT = 256;

  /** MPSC mailbox initial chunk size. */
  public static final String MAILBOX_CHUNK_SIZE_KEY =
    ConfigKey.INT("hbase.consensus.log.writer.mailbox.chunk", v -> v >= 1);
  public static final int MAILBOX_CHUNK_SIZE_DEFAULT = 1024;

  /** Number of independent writer shards. */
  public static final String WRITER_SHARDS_KEY =
    ConfigKey.INT("hbase.consensus.log.writer.shards", v -> v >= 1);
  public static final int WRITER_SHARDS_DEFAULT = 4;

  /** Strict per-commit fsync mode. */
  public static final String FSYNC_ON_COMMIT_KEY =
    ConfigKey.BOOLEAN("hbase.consensus.log.fsync.on.commit");
  public static final boolean FSYNC_ON_COMMIT_DEFAULT = false;

  /** Periodic data-fsync interval (milliseconds). */
  public static final String FSYNC_INTERVAL_MS_KEY =
    ConfigKey.LONG("hbase.consensus.log.fsync.interval.ms");
  public static final long FSYNC_INTERVAL_MS_DEFAULT = 10_000L;

  /** Pre-allocate each segment file to {@code SEGMENT_SIZE_MB_KEY} on open. */
  public static final String PREALLOC_SEGMENT_KEY =
    ConfigKey.BOOLEAN("hbase.consensus.log.segment.prealloc");
  public static final boolean PREALLOC_SEGMENT_DEFAULT = true;

  private final File logDir;
  private final int segmentSizeMb;
  private final int mailboxChunkSize;
  private final int writerShards;
  private final boolean fsyncOnCommit;
  private final long fsyncIntervalMs;
  private final boolean preallocSegment;

  /** Test-only override for segment size in bytes. */
  private final long segmentSizeBytesOverride;

  public LogStoreConfig(@NonNull Configuration conf) {
    String dirStr = conf.get(LOG_DIR_KEY);
    if (dirStr == null || dirStr.isEmpty()) {
      throw new IllegalArgumentException(LOG_DIR_KEY + " is required");
    }
    this.logDir = new File(dirStr);
    this.segmentSizeMb = conf.getInt(SEGMENT_SIZE_MB_KEY, SEGMENT_SIZE_MB_DEFAULT);
    this.mailboxChunkSize = conf.getInt(MAILBOX_CHUNK_SIZE_KEY, MAILBOX_CHUNK_SIZE_DEFAULT);
    this.writerShards = conf.getInt(WRITER_SHARDS_KEY, WRITER_SHARDS_DEFAULT);
    this.fsyncOnCommit = conf.getBoolean(FSYNC_ON_COMMIT_KEY, FSYNC_ON_COMMIT_DEFAULT);
    this.fsyncIntervalMs = conf.getLong(FSYNC_INTERVAL_MS_KEY, FSYNC_INTERVAL_MS_DEFAULT);
    this.preallocSegment = conf.getBoolean(PREALLOC_SEGMENT_KEY, PREALLOC_SEGMENT_DEFAULT);
    this.segmentSizeBytesOverride = -1L;
    validate();
  }

  private LogStoreConfig(Builder b) {
    this.logDir = b.logDir;
    this.segmentSizeMb = b.segmentSizeMb;
    this.mailboxChunkSize = b.mailboxChunkSize;
    this.writerShards = b.writerShards;
    this.segmentSizeBytesOverride = b.segmentSizeBytesOverride;
    this.fsyncOnCommit = b.fsyncOnCommit;
    this.fsyncIntervalMs = b.fsyncIntervalMs;
    this.preallocSegment = b.preallocSegment;
    validate();
  }

  /** Returns a new {@link Builder} seeded with the cross-cutting defaults. */
  @NonNull
  public static Builder newBuilder(@NonNull File logDir) {
    return new Builder(logDir);
  }

  private void validate() {
    if (logDir == null) {
      throw new IllegalArgumentException(LOG_DIR_KEY + " must not be null");
    }
    if (segmentSizeMb < 1) {
      throw new IllegalArgumentException(
        SEGMENT_SIZE_MB_KEY + " must be >= 1, got " + segmentSizeMb);
    }
    if (mailboxChunkSize < 1) {
      throw new IllegalArgumentException(
        MAILBOX_CHUNK_SIZE_KEY + " must be >= 1, got " + mailboxChunkSize);
    }
    if (writerShards < 1) {
      throw new IllegalArgumentException(WRITER_SHARDS_KEY + " must be >= 1, got " + writerShards);
    }
  }

  @NonNull
  public File getLogDir() {
    return logDir;
  }

  public long getSegmentSizeBytes() {
    return segmentSizeBytesOverride > 0
      ? segmentSizeBytesOverride
      : (long) segmentSizeMb * 1024L * 1024L;
  }

  public int getMailboxChunkSize() {
    return mailboxChunkSize;
  }

  public int getWriterShards() {
    return writerShards;
  }

  public boolean isFsyncOnCommit() {
    return fsyncOnCommit;
  }

  public long getFsyncIntervalMs() {
    return fsyncIntervalMs;
  }

  public boolean isPreallocSegment() {
    return preallocSegment;
  }

  /** Builder for {@link LogStoreConfig}. */
  public static final class Builder {
    private final File logDir;
    private int segmentSizeMb = SEGMENT_SIZE_MB_DEFAULT;
    private int mailboxChunkSize = MAILBOX_CHUNK_SIZE_DEFAULT;
    private int writerShards = WRITER_SHARDS_DEFAULT;
    private long segmentSizeBytesOverride = -1L;
    private boolean fsyncOnCommit = FSYNC_ON_COMMIT_DEFAULT;
    private long fsyncIntervalMs = FSYNC_INTERVAL_MS_DEFAULT;
    private boolean preallocSegment = PREALLOC_SEGMENT_DEFAULT;

    private Builder(@NonNull File logDir) {
      this.logDir = logDir;
    }

    @NonNull
    public Builder setSegmentSizeMb(int segmentSizeMb) {
      this.segmentSizeMb = segmentSizeMb;
      return this;
    }

    @NonNull
    public Builder setMailboxChunkSize(int mailboxChunkSize) {
      this.mailboxChunkSize = mailboxChunkSize;
      return this;
    }

    @NonNull
    public Builder setWriterShards(int writerShards) {
      this.writerShards = writerShards;
      return this;
    }

    /** For tests only. Bypasses {@code segmentSizeMb}. */
    @NonNull
    public Builder setSegmentSizeBytesOverride(long bytes) {
      this.segmentSizeBytesOverride = bytes;
      return this;
    }

    @NonNull
    public Builder setFsyncOnCommit(boolean fsyncOnCommit) {
      this.fsyncOnCommit = fsyncOnCommit;
      return this;
    }

    @NonNull
    public Builder setFsyncIntervalMs(long fsyncIntervalMs) {
      this.fsyncIntervalMs = fsyncIntervalMs;
      return this;
    }

    @NonNull
    public Builder setPreallocSegment(boolean preallocSegment) {
      this.preallocSegment = preallocSegment;
      return this;
    }

    @NonNull
    public LogStoreConfig build() {
      return new LogStoreConfig(this);
    }
  }
}
