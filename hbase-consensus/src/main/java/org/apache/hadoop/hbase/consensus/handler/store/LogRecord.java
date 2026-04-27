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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32C;
import org.apache.hadoop.hbase.io.util.StreamUtils;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Single record in the unified consensus log.
 * <p>
 * A {@code LogRecord} is both the in-memory value object and the on-disk frame layout. Encoding
 * runs on the producing thread via {@link #encode(LogRecord)} and returns a direct
 * {@link ByteBuffer} ready for gathered I/O, so the writer thread loop is purely drain-and-write.
 * Decoding streams a {@link FileChannel} via {@link Reader}.
 * <h2>Per-frame layout (big-endian throughout)</h2>
 *
 * <pre>
 * [ frame_len    : uint32 ]   // length of CRC + record bytes
 * [ crc32c       : uint32 ]   // {@link CRC32C} over record bytes only
 * [ kind         : uint8  ]
 * [ seq          : varint ]   // monotonically increasing per-store sequence
 * [ group_id_len : varint ]
 * [ group_id     : bytes  ]   // empty for SEGMENT_HEADER / SEGMENT_FOOTER
 * [ payload      : bytes  ]   // kind-specific
 * </pre>
 *
 * Fixed-width {@code uint32} preamble lets the streaming reader skip frames in O(1) without
 * decoding the body; varint everywhere else keeps the small fields small.
 * <h2>Segment file prologue (8 bytes, written once per segment)</h2>
 *
 * <pre>
 * [ magic     : uint32 ]   // 'CSLG' = 0x43534C47
 * [ version   : uint8  ]   // codec version, currently 1
 * [ reserved  : 3 bytes ]  // zero-filled
 * </pre>
 */
@InterfaceAudience.Private
public class LogRecord {
  /** {@code 'CSLG'} segment magic. */
  public static final int SEGMENT_MAGIC = 0x43534C47;
  /** Current frame/segment codec version. */
  public static final byte CODEC_VERSION = 1;
  /** Fixed prologue length in bytes (magic + version + 3 reserved). */
  public static final int PROLOGUE_BYTES = 8;
  /** {@code frame_len} field width in bytes. */
  public static final int FRAME_LEN_BYTES = 4;
  /** {@code crc32c} field width in bytes. */
  public static final int CRC_BYTES = 4;
  /** Defensive cap on per-frame size: 64 MiB. Anything larger is treated as truncation. */
  public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

  private static final byte[] EMPTY = new byte[0];

  /**
   * On-disk record kind. Stored as a {@code uint8}.
   * <p>
   * Ordinal stability matters: NEVER reorder existing entries; only append.
   */
  public enum Kind {
    LOG_ENTRY,
    SNAPSHOT_CHUNK,
    TERM_VOTE,
    LOCAL_ENDPOINT,
    INITIAL_MEMBERS,
    TRUNCATE_FROM,
    TRUNCATE_UNTIL,
    DELETE_SNAPSHOT_CHUNKS,
    SEGMENT_HEADER,
    SEGMENT_FOOTER;

    private static final Kind[] VALUES = values();

    public static Kind fromOrdinal(int ord) {
      if (ord < 0 || ord >= VALUES.length) {
        throw new IllegalArgumentException("Unknown LogRecord.Kind ordinal: " + ord);
      }
      return VALUES[ord];
    }
  }

  private final Kind kind;
  private final long seq;
  private final byte[] groupId;
  private final byte[] payload;

  public LogRecord(@NonNull Kind kind, long seq, @NonNull byte[] groupId, @NonNull byte[] payload) {
    this.kind = kind;
    this.seq = seq;
    this.groupId = groupId;
    this.payload = payload;
  }

  @NonNull
  public Kind getKind() {
    return kind;
  }

  public long getSeq() {
    return seq;
  }

  @NonNull
  public byte[] getGroupId() {
    return groupId;
  }

  @NonNull
  public byte[] getPayload() {
    return payload;
  }

  /**
   * Encodes a record into a direct {@link ByteBuffer} ready for gathered I/O. Caller owns the
   * buffer; the position is at 0 and the limit at the encoded length.
   */
  @NonNull
  public static ByteBuffer encode(@NonNull LogRecord record) {
    int recordLen = recordBytesLen(record);
    int totalLen = FRAME_LEN_BYTES + CRC_BYTES + recordLen;
    if (totalLen > MAX_FRAME_BYTES) {
      throw new IllegalArgumentException(
        "Encoded frame size " + totalLen + " exceeds MAX_FRAME_BYTES (" + MAX_FRAME_BYTES + ")");
    }
    ByteBuffer buf = ByteBuffer.allocateDirect(totalLen).order(ByteOrder.BIG_ENDIAN);
    int frameLen = CRC_BYTES + recordLen;
    buf.putInt(frameLen);
    int crcSlotPos = buf.position();
    buf.putInt(0);
    int recordStart = buf.position();
    buf.put((byte) record.kind.ordinal());
    StreamUtils.writeRawVInt64(buf, record.seq);
    StreamUtils.writeRawVInt32(buf, record.groupId.length);
    buf.put(record.groupId);
    buf.put(record.payload);
    int recordEnd = buf.position();
    int crc = crc32cOf(buf, recordStart, recordEnd - recordStart);
    buf.putInt(crcSlotPos, crc);
    buf.flip();
    return buf;
  }

  /**
   * Encodes the {@code prologue} (magic+version+3 reserved zero bytes) into a fresh direct buffer.
   */
  @NonNull
  public static ByteBuffer encodePrologue() {
    ByteBuffer buf = ByteBuffer.allocateDirect(PROLOGUE_BYTES).order(ByteOrder.BIG_ENDIAN);
    buf.putInt(SEGMENT_MAGIC);
    buf.put(CODEC_VERSION);
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.flip();
    return buf;
  }

  /** Builds a {@link Kind#TRUNCATE_FROM} or {@link Kind#TRUNCATE_UNTIL} payload. */
  @NonNull
  public static byte[] encodeTruncatePayload(long logIndex) {
    ByteBuffer tmp = ByteBuffer.allocate(StreamUtils.vintSize(logIndex));
    StreamUtils.writeRawVInt64(tmp, logIndex);
    tmp.flip();
    byte[] out = new byte[tmp.remaining()];
    tmp.get(out);
    return out;
  }

  /** Decodes a single varint-encoded {@code logIndex} payload. */
  public static long decodeTruncatePayload(@NonNull byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload);
    try {
      return StreamUtils.readRawVarint64(buf);
    } catch (IOException e) {
      throw new IllegalStateException("Malformed truncate payload", e);
    }
  }

  /** Builds a {@link Kind#DELETE_SNAPSHOT_CHUNKS} payload. */
  @NonNull
  public static byte[] encodeDeleteSnapshotChunksPayload(long logIndex, int chunkCount) {
    ByteBuffer tmp =
      ByteBuffer.allocate(StreamUtils.vintSize(logIndex) + StreamUtils.vintSize(chunkCount));
    StreamUtils.writeRawVInt64(tmp, logIndex);
    StreamUtils.writeRawVInt32(tmp, chunkCount);
    tmp.flip();
    byte[] out = new byte[tmp.remaining()];
    tmp.get(out);
    return out;
  }

  /**
   * Decodes a {@link Kind#DELETE_SNAPSHOT_CHUNKS} payload into {@code [logIndex, chunkCount]}.
   */
  @NonNull
  public static long[] decodeDeleteSnapshotChunksPayload(@NonNull byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload);
    try {
      long idx = StreamUtils.readRawVarint64(buf);
      int count = StreamUtils.readRawVarint32(buf);
      return new long[] { idx, count };
    } catch (IOException e) {
      throw new IllegalStateException("Malformed delete-snapshot-chunks payload", e);
    }
  }

  /** Builds a {@link Kind#SEGMENT_HEADER} payload. */
  @NonNull
  public static byte[] encodeSegmentHeaderPayload(long segmentId, long createTimeMs) {
    ByteBuffer tmp =
      ByteBuffer.allocate(StreamUtils.vintSize(segmentId) + StreamUtils.vintSize(createTimeMs));
    StreamUtils.writeRawVInt64(tmp, segmentId);
    StreamUtils.writeRawVInt64(tmp, createTimeMs);
    tmp.flip();
    byte[] out = new byte[tmp.remaining()];
    tmp.get(out);
    return out;
  }

  /** Decodes a {@link Kind#SEGMENT_HEADER} payload into {@code [segmentId, createTimeMs]}. */
  @NonNull
  public static long[] decodeSegmentHeaderPayload(@NonNull byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload);
    try {
      long segId = StreamUtils.readRawVarint64(buf);
      long createTimeMs = StreamUtils.readRawVarint64(buf);
      return new long[] { segId, createTimeMs };
    } catch (IOException e) {
      throw new IllegalStateException("Malformed segment-header payload", e);
    }
  }

  /** Builds a {@link Kind#SEGMENT_FOOTER} payload. */
  @NonNull
  public static byte[] encodeSegmentFooterPayload(long nextSegmentId, boolean cleanShutdown) {
    ByteBuffer tmp = ByteBuffer.allocate(StreamUtils.vintSize(nextSegmentId) + 1);
    StreamUtils.writeRawVInt64(tmp, nextSegmentId);
    tmp.put((byte) (cleanShutdown ? 1 : 0));
    tmp.flip();
    byte[] out = new byte[tmp.remaining()];
    tmp.get(out);
    return out;
  }

  /** Outcome of a single {@link Reader#next()} call. */
  public static final class ReadResult {

    /** Discriminator for {@link ReadResult}. */
    public enum Kind {
      /**
       * Successfully decoded record; {@link ReadResult#offset()} is the file offset just after the
       * decoded frame.
       */
      OK,
      /** CRC mismatch detected; {@link ReadResult#offset()} is the start of the bad frame. */
      CRC,
      /**
       * Declared frame_len overruns the file (torn-write tail) or claims a size larger than
       * {@link LogRecord#MAX_FRAME_BYTES}; {@link ReadResult#offset()} is the start of the bad
       * frame.
       */
      TRUNCATED,
      /** Clean stop: reader hit end-of-file at a frame boundary. */
      END_OF_FILE
    }

    private final Kind kind;
    @Nullable
    private final LogRecord record;
    private final long offset;

    private ReadResult(@NonNull Kind kind, @Nullable LogRecord record, long offset) {
      this.kind = kind;
      this.record = record;
      this.offset = offset;
    }

    public static ReadResult ok(@NonNull LogRecord record, long endOffset) {
      return new ReadResult(Kind.OK, Objects.requireNonNull(record, "record"), endOffset);
    }

    public static ReadResult crc(long offset) {
      return new ReadResult(Kind.CRC, null, offset);
    }

    public static ReadResult truncated(long offset) {
      return new ReadResult(Kind.TRUNCATED, null, offset);
    }

    public static ReadResult endOfFile(long offset) {
      return new ReadResult(Kind.END_OF_FILE, null, offset);
    }

    @NonNull
    public Kind kind() {
      return kind;
    }

    /**
     * Returns the file offset associated with this result. Semantics depend on {@link #kind()}:
     * <ul>
     * <li>{@link Kind#OK}: file offset just after the decoded frame.</li>
     * <li>{@link Kind#CRC} or {@link Kind#TRUNCATED}: start of the bad frame.</li>
     * <li>{@link Kind#END_OF_FILE}: end-of-file at a frame boundary.</li>
     * </ul>
     */
    public long offset() {
      return offset;
    }

    /**
     * Returns the decoded record. Only valid when {@link #kind()} is {@link Kind#OK}.
     * @throws IllegalStateException if called on any other kind
     */
    @NonNull
    public LogRecord record() {
      if (kind != Kind.OK) {
        throw new IllegalStateException("record() valid only for Kind.OK, got " + kind);
      }
      return record;
    }

    @Override
    public String toString() {
      return kind == Kind.OK
        ? "ReadResult{OK, endOffset=" + offset + ", seq=" + record.getSeq() + "}"
        : "ReadResult{" + kind + ", offset=" + offset + "}";
    }
  }

  /**
   * Streams a single segment file. Validates the {@link #PROLOGUE_BYTES} prologue on construction
   * one at a time via {@link #next()}; on the first non-{@link ReadResult.Kind#OK} result the
   * caller should stop reading and treat the offset as the truncation point.
   */
  public static final class Reader implements Closeable {
    private final FileChannel channel;
    private final boolean ownsChannel;
    private final long fileSize;
    private long position;

    /**
     * Validates the prologue and positions the reader at the first frame. {@code offset 0..7} of
     * {@code channel} is consumed by the prologue; reading begins at {@code offset 8}.
     * @throws IOException if the prologue does not match this codec's magic / version
     */
    public Reader(@NonNull FileChannel channel) throws IOException {
      this(channel, false);
    }

    public Reader(@NonNull FileChannel channel, boolean ownsChannel) throws IOException {
      this.channel = channel;
      this.ownsChannel = ownsChannel;
      this.fileSize = channel.size();
      validatePrologue();
      this.position = PROLOGUE_BYTES;
    }

    private void validatePrologue() throws IOException {
      if (fileSize < PROLOGUE_BYTES) {
        throw new IOException(
          "Segment file too small for prologue (size=" + fileSize + " < " + PROLOGUE_BYTES + ")");
      }
      ByteBuffer buf = ByteBuffer.allocate(PROLOGUE_BYTES).order(ByteOrder.BIG_ENDIAN);
      readFully(channel, buf, 0L);
      buf.flip();
      int magic = buf.getInt();
      if (magic != SEGMENT_MAGIC) {
        throw new IOException(
          String.format("Bad segment magic: expected 0x%08X, got 0x%08X", SEGMENT_MAGIC, magic));
      }
      byte version = buf.get();
      if (version != CODEC_VERSION) {
        throw new IOException("Unsupported segment codec version: " + (version & 0xFF)
          + " (expected " + CODEC_VERSION + ")");
      }
    }

    /** Returns the current absolute file offset. */
    public long position() {
      return position;
    }

    /** Returns the next {@link ReadResult}; never returns {@code null}. */
    @NonNull
    public ReadResult next() throws IOException {
      long frameStart = position;
      long remaining = fileSize - position;
      if (remaining == 0) {
        return ReadResult.endOfFile(frameStart);
      }
      if (remaining < FRAME_LEN_BYTES) {
        return ReadResult.truncated(frameStart);
      }
      ByteBuffer hdr = ByteBuffer.allocate(FRAME_LEN_BYTES).order(ByteOrder.BIG_ENDIAN);
      readFully(channel, hdr, position);
      hdr.flip();
      int frameLen = hdr.getInt();
      if (frameLen < CRC_BYTES + 1 || frameLen > MAX_FRAME_BYTES) {
        return ReadResult.truncated(frameStart);
      }
      long total = (long) FRAME_LEN_BYTES + frameLen;
      if (remaining < total) {
        return ReadResult.truncated(frameStart);
      }
      ByteBuffer body = ByteBuffer.allocate(frameLen).order(ByteOrder.BIG_ENDIAN);
      readFully(channel, body, position + FRAME_LEN_BYTES);
      body.flip();
      int storedCrc = body.getInt();
      int recordLen = frameLen - CRC_BYTES;
      int recordStart = body.position();
      int actualCrc = crc32cOf(body, recordStart, recordLen);
      if (storedCrc != actualCrc) {
        return ReadResult.crc(frameStart);
      }
      ByteBuffer rec = body.slice();
      try {
        Kind kind = Kind.fromOrdinal(rec.get() & 0xFF);
        long seq = StreamUtils.readRawVarint64(rec);
        int gidLen = StreamUtils.readRawVarint32(rec);
        if (gidLen < 0 || gidLen > rec.remaining()) {
          return ReadResult.truncated(frameStart);
        }
        byte[] gid = new byte[gidLen];
        rec.get(gid);
        byte[] payload = new byte[rec.remaining()];
        rec.get(payload);
        position = frameStart + total;
        LogRecord lr = new LogRecord(kind, seq, gid, payload);
        return ReadResult.ok(lr, position);
      } catch (RuntimeException | IOException e) {
        return ReadResult.truncated(frameStart);
      }
    }

    @Override
    public void close() throws IOException {
      if (ownsChannel) {
        channel.close();
      }
    }
  }

  static int crc32cOf(@NonNull ByteBuffer buf, int offset, int length) {
    CRC32C crc = new CRC32C();
    if (buf.hasArray()) {
      crc.update(buf.array(), buf.arrayOffset() + offset, length);
    } else {
      ByteBuffer dup = buf.duplicate();
      dup.position(offset);
      dup.limit(offset + length);
      crc.update(dup);
    }
    return (int) crc.getValue();
  }

  static int crc32cOf(@NonNull byte[] bytes) {
    CRC32C crc = new CRC32C();
    crc.update(bytes, 0, bytes.length);
    return (int) crc.getValue();
  }

  private static int recordBytesLen(LogRecord r) {
    int gidLen = r.groupId.length;
    return 1 + StreamUtils.vintSize(r.seq) + StreamUtils.vintSize(gidLen) + gidLen
      + r.payload.length;
  }

  private static void readFully(FileChannel ch, ByteBuffer dst, long startOffset)
    throws IOException {
    long off = startOffset;
    while (dst.hasRemaining()) {
      int n = ch.read(dst, off);
      if (n < 0) {
        throw new EOFException("Unexpected EOF reading at offset=" + off);
      }
      off += n;
    }
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LogRecord)) {
      return false;
    }
    LogRecord other = (LogRecord) o;
    return seq == other.seq && kind == other.kind && Arrays.equals(groupId, other.groupId)
      && Arrays.equals(payload, other.payload);
  }

  @Override
  public int hashCode() {
    int h = kind.hashCode();
    h = 31 * h + Long.hashCode(seq);
    h = 31 * h + Arrays.hashCode(groupId);
    h = 31 * h + Arrays.hashCode(payload);
    return h;
  }

  @Override
  public String toString() {
    return "LogRecord{kind=" + kind + ", seq=" + seq + ", groupIdLen=" + groupId.length
      + ", payloadLen=" + payload.length + '}';
  }
}
