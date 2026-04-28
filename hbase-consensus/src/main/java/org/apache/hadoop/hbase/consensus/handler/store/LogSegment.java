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
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Append-only segment file in the {@link UnifiedRaftStore} multiplexed log.
 * <p>
 * Wraps a single {@link FileChannel} opened with {@code WRITE} + (optionally) {@code CREATE} +
 * {@code APPEND}-style positioning. Tracks the active size so the writer can roll segments without
 * a redundant {@code stat()}.
 */
@InterfaceAudience.Private
class LogSegment implements Closeable {
  static final String FILE_PREFIX = "raft-";
  static final String FILE_SUFFIX = ".log";

  private final long segmentId;
  private final Path path;
  private final FileChannel channel;
  private long currentSize;
  private final Map<ByteBuffer, Long> maxLogIndexByGroup = new HashMap<>();

  LogSegment(long segmentId, Path path, FileChannel channel, long currentSize) {
    this.segmentId = segmentId;
    this.path = path;
    this.channel = channel;
    this.currentSize = currentSize;
  }

  /**
   * Opens a brand-new segment file, pre-allocates it to {@code preallocBytes}, and writes the
   * prologue. Pre-allocation is implemented by writing a single zero byte at offset
   * {@code preallocBytes - 1}, which extends the file length in a single metadata update. The
   * trailing zero region is interpreted by {@link LogRecord.Reader#next()} as {@code TRUNCATED}
   * fails the {@code frameLen < CRC_BYTES + 1} check), so recovery-time replay early-stops at the
   * actual record tail. The graceful close path ({@link UnifiedRaftStore#rollSegment} /
   * {@code close()}) truncates the file back to {@code currentSize()} so non-active segments do not
   * waste disk.
   */
  @NonNull
  static LogSegment create(long segmentId, @NonNull Path path, long preallocBytes)
    throws IOException {
    FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
      StandardOpenOption.READ);
    if (preallocBytes > LogRecord.PROLOGUE_BYTES) {
      ByteBuffer marker = ByteBuffer.allocate(1);
      while (marker.hasRemaining()) {
        ch.write(marker, preallocBytes - 1);
      }
    }
    ByteBuffer prologue = LogRecord.encodePrologue();
    while (prologue.hasRemaining()) {
      ch.write(prologue);
    }
    return new LogSegment(segmentId, path, ch, LogRecord.PROLOGUE_BYTES);
  }

  /**
   * Opens an existing segment file at the end (for append-after-load). Caller has already validated
   * the prologue and the trailing offset is at {@code expectedSize}.
   */
  @NonNull
  static LogSegment openForAppend(long segmentId, @NonNull Path path, long expectedSize)
    throws IOException {
    FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.READ);
    ch.position(expectedSize);
    return new LogSegment(segmentId, path, ch, expectedSize);
  }

  long segmentId() {
    return segmentId;
  }

  @NonNull
  Path path() {
    return path;
  }

  @NonNull
  FileChannel channel() {
    return channel;
  }

  long currentSize() {
    return currentSize;
  }

  /** Appends a single buffer to the file, advancing {@link #currentSize}. */
  void appendFrame(@NonNull ByteBuffer buf) throws IOException {
    while (buf.hasRemaining()) {
      currentSize += channel.write(buf);
    }
  }

  /**
   * Appends the {@code length} pre-encoded frames starting at {@code offset} of {@code bufs} using
   * a single gathered {@code FileChannel.write}. Lets the writer reuse a single ByteBuffer[]
   * scratch array across batches without nulling out trailing slots.
   */
  void appendFrames(@NonNull ByteBuffer[] bufs, int offset, int length) throws IOException {
    long expected = 0L;
    for (int i = offset; i < offset + length; i++) {
      expected += bufs[i].remaining();
    }
    long written = 0L;
    while (written < expected) {
      written += channel.write(bufs, offset, length);
    }
    currentSize += written;
  }

  /** Fsyncs the file to disk. {@code metadata=true} flushes file metadata as well. */
  void force(boolean metadata) throws IOException {
    channel.force(metadata);
  }

  /**
   * Truncates the segment to {@code newSize}. Used by {@code load()} when a CRC / torn-tail
   * truncation point is found.
   */
  void truncate(long newSize) throws IOException {
    channel.truncate(newSize);
    channel.position(newSize);
    this.currentSize = newSize;
  }

  /**
   * Records that {@code groupId} has a frame in this segment with log index {@code logIndex}. Keeps
   * the highest seen value.
   */
  void recordMaxLogIndex(@NonNull byte[] groupId, long logIndex) {
    ByteBuffer key = ByteBuffer.wrap(groupId).asReadOnlyBuffer();
    maxLogIndexByGroup.merge(key, logIndex, Math::max);
  }

  /** Returns the per-group max log index map. */
  @NonNull
  Map<ByteBuffer, Long> maxLogIndexByGroup() {
    return maxLogIndexByGroup;
  }

  /** Deletes the underlying file. Safe to call after {@link #close()}. */
  void deleteFile() throws IOException {
    Files.deleteIfExists(path);
  }

  @Override
  public void close() throws IOException {
    if (channel.isOpen()) {
      channel.close();
    }
  }

  /** Builds the canonical filename for a segment, e.g. {@code raft-0000000000000000007.log}. */
  @NonNull
  static String filename(long segmentId) {
    return String.format("%s%019d%s", FILE_PREFIX, segmentId, FILE_SUFFIX);
  }

  /**
   * Parses a filename produced by {@link #filename(long)}, returning the segment id, or {@code -1}
   * if the name is not a recognised consensus log segment.
   */
  static long parseSegmentId(@NonNull String name) {
    if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) {
      return -1L;
    }
    String idStr = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
    try {
      return Long.parseUnsignedLong(idStr);
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  @Override
  public String toString() {
    return "LogSegment{id=" + segmentId + ", path=" + path + ", size=" + currentSize + '}';
  }
}
