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
package org.apache.hadoop.hbase.io.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.nio.SingleByteBuff;
import org.apache.hadoop.hbase.testclassification.MiscTests;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.util.Pair;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Round-trip and edge-case tests for the LEB128 (protobuf-style) varint helpers in
 * {@link StreamUtils}.
 */
@Category({ MiscTests.class, SmallTests.class })
public class TestStreamUtils {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestStreamUtils.class);

  private static final long[] LONG_VALUES = { 0L, 1L, 0x7FL, 0x80L, 0x3FFFL, 0x4000L, 0x1FFFFFL,
    0x200000L, 0xFFFFFFFFL, 0x100000000L, Long.MAX_VALUE, -1L, Long.MIN_VALUE };

  private static final int[] INT_VALUES = { 0, 1, 0x7F, 0x80, 0x3FFF, 0x4000, 0x1FFFFF, 0x200000,
    0xFFFFFFF, 0x10000000, Integer.MAX_VALUE, -1, Integer.MIN_VALUE };

  @Test
  public void testVintSize32MatchesRoundTripLength() throws IOException {
    for (int v : INT_VALUES) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      StreamUtils.writeRawVInt32(out, v);
      assertEquals("size disagrees with stream encoding for " + v, out.size(),
        StreamUtils.vintSize(v));
    }
  }

  @Test
  public void testVintSize64MatchesRoundTripLength() throws IOException {
    for (long v : LONG_VALUES) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      StreamUtils.writeRawVInt64(out, v);
      assertEquals("size disagrees with stream encoding for " + v, out.size(),
        StreamUtils.vintSize(v));
    }
  }

  @Test
  public void testByteBuffer32RoundTrip() throws IOException {
    for (int v : INT_VALUES) {
      ByteBuffer buf = ByteBuffer.allocate(StreamUtils.vintSize(v));
      StreamUtils.writeRawVInt32(buf, v);
      assertEquals("did not advance position fully for " + v, buf.capacity(), buf.position());
      buf.flip();
      int decoded = StreamUtils.readRawVarint32(buf);
      assertEquals(v, decoded);
      assertEquals("did not consume entire varint for " + v, buf.capacity(), buf.position());
    }
  }

  @Test
  public void testByteBuffer64RoundTrip() throws IOException {
    for (long v : LONG_VALUES) {
      ByteBuffer buf = ByteBuffer.allocate(StreamUtils.vintSize(v));
      StreamUtils.writeRawVInt64(buf, v);
      assertEquals("did not advance position fully for " + v, buf.capacity(), buf.position());
      buf.flip();
      long decoded = StreamUtils.readRawVarint64(buf);
      assertEquals(v, decoded);
      assertEquals("did not consume entire varint for " + v, buf.capacity(), buf.position());
    }
  }

  @Test
  public void testInputOutputStream64RoundTrip() throws IOException {
    for (long v : LONG_VALUES) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      StreamUtils.writeRawVInt64(out, v);
      ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
      assertEquals(v, StreamUtils.readRawVarint64(in));
      assertEquals("trailing bytes after decode for " + v, 0, in.available());
    }
  }

  @Test
  public void testCrossFormatStreamAndBufferAreByteForByteIdentical() throws IOException {
    for (long v : LONG_VALUES) {
      ByteBuffer buf = ByteBuffer.allocate(StreamUtils.vintSize(v));
      StreamUtils.writeRawVInt64(buf, v);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      StreamUtils.writeRawVInt64(out, v);
      assertArrayEquals("buffer and stream encodings differ for " + v + ": "
        + Arrays.toString(buf.array()) + " vs " + Arrays.toString(out.toByteArray()),
        out.toByteArray(), buf.array());
    }
  }

  @Test
  public void testByteBuffRoundTrip() throws IOException {
    for (long v : LONG_VALUES) {
      ByteBuffer raw = ByteBuffer.allocate(StreamUtils.vintSize(v));
      StreamUtils.writeRawVInt64(raw, v);
      raw.flip();
      SingleByteBuff sbb = new SingleByteBuff(raw);
      assertEquals(v, StreamUtils.readRawVarint64(sbb));
    }
  }

  @Test
  public void testByteArrayIndexed64RoundTrip() throws IOException {
    for (long v : LONG_VALUES) {
      byte[] dst = new byte[StreamUtils.vintSize(v) + 4];
      int written = StreamUtils.writeRawVInt64(dst, 2, v);
      assertEquals(StreamUtils.vintSize(v), written);
      Pair<Long, Integer> decoded = StreamUtils.readRawVarint64(dst, 2);
      assertEquals(v, (long) decoded.getFirst());
      assertEquals(written, (int) decoded.getSecond());
    }
  }

  @Test
  public void testByteArrayIndexed32RoundTrip() throws IOException {
    for (int v : INT_VALUES) {
      byte[] dst = new byte[StreamUtils.vintSize(v) + 4];
      int written = StreamUtils.writeRawVInt32(dst, 1, v);
      assertEquals(StreamUtils.vintSize(v), written);
      Pair<Integer, Integer> decoded = StreamUtils.readRawVarint32(dst, 1);
      assertEquals(v, (int) decoded.getFirst());
      assertEquals(written, (int) decoded.getSecond());
    }
  }

  @Test
  public void testMalformedVarint64FromByteBufferThrows() {
    // 11 continuation bytes -> never terminates.
    byte[] junk = new byte[11];
    Arrays.fill(junk, (byte) 0x80);
    ByteBuffer buf = ByteBuffer.wrap(junk);
    assertThrows(IOException.class, () -> StreamUtils.readRawVarint64(buf));
  }

  @Test
  public void testTruncatedVarintFromInputStreamThrows() {
    byte[] truncated = { (byte) 0x80, (byte) 0x80 }; // continues, then EOF
    ByteArrayInputStream in = new ByteArrayInputStream(truncated);
    assertThrows(IOException.class, () -> StreamUtils.readRawVarint64(in));
  }
}
