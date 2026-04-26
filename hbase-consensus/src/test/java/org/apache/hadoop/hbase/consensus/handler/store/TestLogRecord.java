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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.io.util.StreamUtils;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Codec / framing / CRC / varint tests for {@link LogRecord}.
 */
@Tag(SmallTests.TAG)
public class TestLogRecord extends TestBase {

  @TempDir
  Path tmp;

  @Test
  public void testEncodeDecodeAllKinds() throws IOException {
    Path file = tmp.resolve("seg.log");
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      writeFully(ch, LogRecord.encodePrologue());
      long seq = 1L;
      for (LogRecord.Kind kind : LogRecord.Kind.values()) {
        byte[] gid = kind == LogRecord.Kind.SEGMENT_HEADER || kind == LogRecord.Kind.SEGMENT_FOOTER
          ? new byte[0]
          : ("g-" + kind.name()).getBytes();
        byte[] payload = ("payload-" + kind.name()).getBytes();
        LogRecord rec = new LogRecord(kind, seq++, gid, payload);
        writeFully(ch, LogRecord.encode(rec));
      }
    }
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
      LogRecord.Reader reader = new LogRecord.Reader(ch)) {
      long seq = 1L;
      for (LogRecord.Kind kind : LogRecord.Kind.values()) {
        LogRecord.ReadResult rr = reader.next();
        assertThat(rr).isInstanceOf(LogRecord.ReadResult.Ok.class);
        LogRecord rec = ((LogRecord.ReadResult.Ok) rr).record();
        assertThat(rec.getKind()).isEqualTo(kind);
        assertThat(rec.getSeq()).isEqualTo(seq++);
        if (kind == LogRecord.Kind.SEGMENT_HEADER || kind == LogRecord.Kind.SEGMENT_FOOTER) {
          assertThat(rec.getGroupId()).isEmpty();
        } else {
          assertThat(new String(rec.getGroupId())).isEqualTo("g-" + kind.name());
        }
        assertThat(new String(rec.getPayload())).isEqualTo("payload-" + kind.name());
      }
      LogRecord.ReadResult eof = reader.next();
      assertThat(eof).isInstanceOf(LogRecord.ReadResult.EndOfFile.class);
    }
  }

  @Test
  public void testCrcMismatchReportsCrc() throws IOException {
    Path file = tmp.resolve("crc.log");
    long crcSlotOffset;
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      writeFully(ch, LogRecord.encodePrologue());
      LogRecord rec =
        new LogRecord(LogRecord.Kind.LOG_ENTRY, 1L, "g".getBytes(), new byte[] { 1, 2, 3, 4 });
      ByteBuffer frame = LogRecord.encode(rec);
      crcSlotOffset = ch.position() + LogRecord.FRAME_LEN_BYTES;
      writeFully(ch, frame);
    }
    // Flip a bit in the CRC field.
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
      ByteBuffer one = ByteBuffer.wrap(new byte[] { (byte) 0xFF });
      ch.write(one, crcSlotOffset);
    }
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
      LogRecord.Reader reader = new LogRecord.Reader(ch)) {
      LogRecord.ReadResult rr = reader.next();
      assertThat(rr).isInstanceOf(LogRecord.ReadResult.Crc.class);
      assertThat(((LogRecord.ReadResult.Crc) rr).offset()).isEqualTo(LogRecord.PROLOGUE_BYTES);
    }
  }

  @Test
  public void testTruncatedTailReportsTruncated() throws IOException {
    Path file = tmp.resolve("tornt.log");
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      writeFully(ch, LogRecord.encodePrologue());
      LogRecord rec = new LogRecord(LogRecord.Kind.LOG_ENTRY, 1L, "g".getBytes(), new byte[1024]);
      ByteBuffer frame = LogRecord.encode(rec);
      // Drop the last 50 bytes — represents a torn write.
      frame.limit(frame.limit() - 50);
      writeFully(ch, frame);
    }
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
      LogRecord.Reader reader = new LogRecord.Reader(ch)) {
      LogRecord.ReadResult rr = reader.next();
      assertThat(rr).isInstanceOf(LogRecord.ReadResult.Truncated.class);
    }
  }

  @Test
  public void testOversizedFrameRejected() throws IOException {
    Path file = tmp.resolve("big.log");
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      writeFully(ch, LogRecord.encodePrologue());
      // Hand-craft a frame_len > MAX_FRAME_BYTES.
      ByteBuffer hdr = ByteBuffer.allocate(LogRecord.FRAME_LEN_BYTES).order(ByteOrder.BIG_ENDIAN);
      hdr.putInt(LogRecord.MAX_FRAME_BYTES + 1);
      hdr.flip();
      writeFully(ch, hdr);
    }
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
      LogRecord.Reader reader = new LogRecord.Reader(ch)) {
      LogRecord.ReadResult rr = reader.next();
      assertThat(rr).isInstanceOf(LogRecord.ReadResult.Truncated.class);
    }
  }

  @Test
  public void testBadMagicRejectedByReader() throws IOException {
    Path file = tmp.resolve("bad.log");
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      ByteBuffer bogus = ByteBuffer.allocate(LogRecord.PROLOGUE_BYTES).order(ByteOrder.BIG_ENDIAN);
      bogus.putInt(0xDEADBEEF);
      bogus.put((byte) 1);
      bogus.put((byte) 0);
      bogus.put((byte) 0);
      bogus.put((byte) 0);
      bogus.flip();
      writeFully(ch, bogus);
    }
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
      Throwable t = catching(() -> new LogRecord.Reader(ch));
      assertThat(t).isInstanceOf(IOException.class);
    }
  }

  @Test
  public void testBadVersionRejectedByReader() throws IOException {
    Path file = tmp.resolve("badver.log");
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      ByteBuffer p = ByteBuffer.allocate(LogRecord.PROLOGUE_BYTES).order(ByteOrder.BIG_ENDIAN);
      p.putInt(LogRecord.SEGMENT_MAGIC);
      p.put((byte) (LogRecord.CODEC_VERSION + 1));
      p.put((byte) 0).put((byte) 0).put((byte) 0);
      p.flip();
      writeFully(ch, p);
    }
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
      Throwable t = catching(() -> new LogRecord.Reader(ch));
      assertThat(t).isInstanceOf(IOException.class);
    }
  }

  @Test
  public void testVarintBoundaries() throws IOException {
    long[] cases =
      { 0L, 1L, 127L, 128L, (1L << 14) - 1, 1L << 14, Integer.MAX_VALUE, Long.MAX_VALUE };
    for (long v : cases) {
      ByteBuffer buf = ByteBuffer.allocate(StreamUtils.vintSize(v));
      StreamUtils.writeRawVInt64(buf, v);
      buf.flip();
      assertThat(StreamUtils.readRawVarint64(buf)).isEqualTo(v);
      assertThat(buf.hasRemaining()).isFalse();
    }
  }

  @Test
  public void testTruncatePayloadRoundTrip() {
    long[] cases = { 0L, 1L, 1234567890L, Long.MAX_VALUE };
    for (long v : cases) {
      assertThat(LogRecord.decodeTruncatePayload(LogRecord.encodeTruncatePayload(v))).isEqualTo(v);
    }
  }

  @Test
  public void testDeleteSnapshotChunksPayloadRoundTrip() {
    long[] hdr = LogRecord
      .decodeDeleteSnapshotChunksPayload(LogRecord.encodeDeleteSnapshotChunksPayload(42L, 7));
    assertThat(hdr[0]).isEqualTo(42L);
    assertThat(hdr[1]).isEqualTo(7L);
  }

  @Test
  public void testSegmentHeaderPayloadRoundTrip() {
    long[] hdr = LogRecord
      .decodeSegmentHeaderPayload(LogRecord.encodeSegmentHeaderPayload(99L, 1234567890123L));
    assertThat(hdr[0]).isEqualTo(99L);
    assertThat(hdr[1]).isEqualTo(1234567890123L);
  }

  private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
    while (buf.hasRemaining()) {
      ch.write(buf);
    }
  }

  private interface IoSupplier<T> {
    T get() throws IOException;
  }

  private static <T> Throwable catching(IoSupplier<T> s) {
    try {
      s.get();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }
}
