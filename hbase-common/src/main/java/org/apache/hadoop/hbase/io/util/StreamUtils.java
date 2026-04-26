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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import org.apache.hadoop.hbase.nio.ByteBuff;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.common.base.Preconditions;

/*
 * It seems like as soon as somebody sets himself to the task of creating VInt encoding, his mind
 * blanks out for a split-second and he starts the work by wrapping it in the most convoluted
 * interface he can come up with. Custom streams that allocate memory, DataOutput that is only used
 * to write single bytes... We operate on simple streams. Thus, we are going to have a simple
 * implementation copy-pasted from protobuf Coded*Stream.
 */
@InterfaceAudience.Private
public class StreamUtils {

  public static void writeRawVInt32(OutputStream output, int value) throws IOException {
    while (true) {
      if ((value & ~0x7F) == 0) {
        output.write(value);
        return;
      } else {
        output.write((value & 0x7F) | 0x80);
        value >>>= 7;
      }
    }
  }

  public static int readRawVarint32(InputStream input) throws IOException {
    byte tmp = (byte) input.read();
    if (tmp >= 0) {
      return tmp;
    }
    int result = tmp & 0x7f;
    if ((tmp = (byte) input.read()) >= 0) {
      result |= tmp << 7;
    } else {
      result |= (tmp & 0x7f) << 7;
      if ((tmp = (byte) input.read()) >= 0) {
        result |= tmp << 14;
      } else {
        result |= (tmp & 0x7f) << 14;
        if ((tmp = (byte) input.read()) >= 0) {
          result |= tmp << 21;
        } else {
          result |= (tmp & 0x7f) << 21;
          result |= (tmp = (byte) input.read()) << 28;
          if (tmp < 0) {
            // Discard upper 32 bits.
            for (int i = 0; i < 5; i++) {
              if (input.read() >= 0) {
                return result;
              }
            }
            throw new IOException("Malformed varint");
          }
        }
      }
    }
    return result;
  }

  public static int readRawVarint32(ByteBuff input) throws IOException {
    byte tmp = input.get();
    if (tmp >= 0) {
      return tmp;
    }
    int result = tmp & 0x7f;
    if ((tmp = input.get()) >= 0) {
      result |= tmp << 7;
    } else {
      result |= (tmp & 0x7f) << 7;
      if ((tmp = input.get()) >= 0) {
        result |= tmp << 14;
      } else {
        result |= (tmp & 0x7f) << 14;
        if ((tmp = input.get()) >= 0) {
          result |= tmp << 21;
        } else {
          result |= (tmp & 0x7f) << 21;
          result |= (tmp = input.get()) << 28;
          if (tmp < 0) {
            // Discard upper 32 bits.
            for (int i = 0; i < 5; i++) {
              if (input.get() >= 0) {
                return result;
              }
            }
            throw new IOException("Malformed varint");
          }
        }
      }
    }
    return result;
  }

  /**
   * Reads a varInt value stored in an array. Input array where the varInt is available Offset in
   * the input array where varInt is available
   * @return A pair of integers in which first value is the actual decoded varInt value and second
   *         value as number of bytes taken by this varInt for it's storage in the input array.
   * @throws IOException When varint is malformed and not able to be read correctly
   */
  public static Pair<Integer, Integer> readRawVarint32(byte[] input, int offset)
    throws IOException {
    int newOffset = offset;
    byte tmp = input[newOffset++];
    if (tmp >= 0) {
      return new Pair<>((int) tmp, newOffset - offset);
    }
    int result = tmp & 0x7f;
    tmp = input[newOffset++];
    if (tmp >= 0) {
      result |= tmp << 7;
    } else {
      result |= (tmp & 0x7f) << 7;
      tmp = input[newOffset++];
      if (tmp >= 0) {
        result |= tmp << 14;
      } else {
        result |= (tmp & 0x7f) << 14;
        tmp = input[newOffset++];
        if (tmp >= 0) {
          result |= tmp << 21;
        } else {
          result |= (tmp & 0x7f) << 21;
          tmp = input[newOffset++];
          result |= tmp << 28;
          if (tmp < 0) {
            // Discard upper 32 bits.
            for (int i = 0; i < 5; i++) {
              tmp = input[newOffset++];
              if (tmp >= 0) {
                return new Pair<>(result, newOffset - offset);
              }
            }
            throw new IOException("Malformed varint");
          }
        }
      }
    }
    return new Pair<>(result, newOffset - offset);
  }

  public static Pair<Integer, Integer> readRawVarint32(ByteBuffer input, int offset)
    throws IOException {
    int newOffset = offset;
    byte tmp = input.get(newOffset++);
    if (tmp >= 0) {
      return new Pair<>((int) tmp, newOffset - offset);
    }
    int result = tmp & 0x7f;
    tmp = input.get(newOffset++);
    if (tmp >= 0) {
      result |= tmp << 7;
    } else {
      result |= (tmp & 0x7f) << 7;
      tmp = input.get(newOffset++);
      if (tmp >= 0) {
        result |= tmp << 14;
      } else {
        result |= (tmp & 0x7f) << 14;
        tmp = input.get(newOffset++);
        if (tmp >= 0) {
          result |= tmp << 21;
        } else {
          result |= (tmp & 0x7f) << 21;
          tmp = input.get(newOffset++);
          result |= tmp << 28;
          if (tmp < 0) {
            // Discard upper 32 bits.
            for (int i = 0; i < 5; i++) {
              tmp = input.get(newOffset++);
              if (tmp >= 0) {
                return new Pair<>(result, newOffset - offset);
              }
            }
            throw new IOException("Malformed varint");
          }
        }
      }
    }
    return new Pair<>(result, newOffset - offset);
  }

  /**
   * Write a 64-bit unsigned LEB128 (protobuf-style) varint to {@code output}. Values are treated as
   * unsigned, so negative {@code long} values are encoded in the full 10 bytes.
   */
  public static void writeRawVInt64(OutputStream output, long value) throws IOException {
    while (true) {
      if ((value & ~0x7FL) == 0L) {
        output.write((int) value);
        return;
      } else {
        output.write(((int) value & 0x7F) | 0x80);
        value >>>= 7;
      }
    }
  }

  /**
   * Read a 64-bit unsigned LEB128 varint written by {@link #writeRawVInt64(OutputStream, long)}
   * from {@code input}.
   * @throws IOException if the varint is malformed (more than 10 bytes) or the stream ends early
   */
  public static long readRawVarint64(InputStream input) throws IOException {
    long result = 0L;
    int shift = 0;
    while (shift < 64) {
      int b = input.read();
      if (b < 0) {
        throw new EOFException();
      }
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    throw new IOException("Malformed varint");
  }

  /**
   * Read a 64-bit unsigned LEB128 varint from a {@link ByteBuff}, advancing its position.
   */
  public static long readRawVarint64(ByteBuff input) throws IOException {
    long result = 0L;
    int shift = 0;
    while (shift < 64) {
      byte b = input.get();
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    throw new IOException("Malformed varint");
  }

  /**
   * Write a 32-bit unsigned LEB128 varint to {@code out}, advancing its position. The buffer must
   * have at least {@link #vintSize(int) vintSize(value)} bytes remaining.
   */
  public static void writeRawVInt32(ByteBuffer out, int value) {
    while ((value & ~0x7F) != 0) {
      out.put((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.put((byte) value);
  }

  /**
   * Write a 64-bit unsigned LEB128 varint to {@code out}, advancing its position. The buffer must
   * have at least {@link #vintSize(long) vintSize(value)} bytes remaining.
   */
  public static void writeRawVInt64(ByteBuffer out, long value) {
    while ((value & ~0x7FL) != 0L) {
      out.put((byte) (((int) value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.put((byte) value);
  }

  /**
   * Read a 32-bit unsigned LEB128 varint from {@code in}, advancing its position.
   * @throws IOException if the varint is malformed (more than 5 bytes)
   */
  public static int readRawVarint32(ByteBuffer in) throws IOException {
    int result = 0;
    int shift = 0;
    while (shift < 32) {
      byte b = in.get();
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    // We've already consumed 5 bytes of varint payload; protobuf permits up to 10 bytes when an
    // int was sign-extended into a long on the writer side. Drain and discard the upper bytes.
    for (int i = 0; i < 5; i++) {
      if ((in.get() & 0x80) == 0) {
        return result;
      }
    }
    throw new IOException("Malformed varint");
  }

  /**
   * Read a 64-bit unsigned LEB128 varint from {@code in}, advancing its position.
   * @throws IOException if the varint is malformed (more than 10 bytes)
   */
  public static long readRawVarint64(ByteBuffer in) throws IOException {
    long result = 0L;
    int shift = 0;
    while (shift < 64) {
      byte b = in.get();
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    throw new IOException("Malformed varint");
  }

  /**
   * Write a 32-bit unsigned LEB128 varint to {@code dst} starting at {@code offset}.
   * @return the number of bytes written
   */
  public static int writeRawVInt32(byte[] dst, int offset, int value) {
    int p = offset;
    while ((value & ~0x7F) != 0) {
      dst[p++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    dst[p++] = (byte) value;
    return p - offset;
  }

  /**
   * Write a 64-bit unsigned LEB128 varint to {@code dst} starting at {@code offset}.
   * @return the number of bytes written
   */
  public static int writeRawVInt64(byte[] dst, int offset, long value) {
    int p = offset;
    while ((value & ~0x7FL) != 0L) {
      dst[p++] = (byte) (((int) value & 0x7F) | 0x80);
      value >>>= 7;
    }
    dst[p++] = (byte) value;
    return p - offset;
  }

  /**
   * Read a 64-bit unsigned LEB128 varint from {@code input} starting at {@code offset}.
   * @return a pair of (value, bytes-consumed)
   * @throws IOException if the varint is malformed
   */
  public static Pair<Long, Integer> readRawVarint64(byte[] input, int offset) throws IOException {
    long result = 0L;
    int shift = 0;
    int p = offset;
    while (shift < 64) {
      byte b = input[p++];
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        return new Pair<>(result, p - offset);
      }
      shift += 7;
    }
    throw new IOException("Malformed varint");
  }

  /**
   * Number of bytes a 32-bit unsigned LEB128 varint with this value will occupy.
   */
  public static int vintSize(int value) {
    if ((value & (~0 << 7)) == 0) return 1;
    if ((value & (~0 << 14)) == 0) return 2;
    if ((value & (~0 << 21)) == 0) return 3;
    if ((value & (~0 << 28)) == 0) return 4;
    return 5;
  }

  /**
   * Number of bytes a 64-bit unsigned LEB128 varint with this value will occupy.
   */
  public static int vintSize(long value) {
    // Treat negatives as full 10-byte varints, matching protobuf encoding rules.
    if ((value & (~0L << 7)) == 0L) return 1;
    if ((value & (~0L << 14)) == 0L) return 2;
    if ((value & (~0L << 21)) == 0L) return 3;
    if ((value & (~0L << 28)) == 0L) return 4;
    if ((value & (~0L << 35)) == 0L) return 5;
    if ((value & (~0L << 42)) == 0L) return 6;
    if ((value & (~0L << 49)) == 0L) return 7;
    if ((value & (~0L << 56)) == 0L) return 8;
    if ((value & (~0L << 63)) == 0L) return 9;
    return 10;
  }

  /**
   * Read a byte from the given stream using the read method, and throw EOFException if it returns
   * -1, like the implementation in {@code DataInputStream}.
   * <p/>
   * This is useful because casting the return value of read method into byte directly will make us
   * lose the ability to check whether there is a byte and its value is -1 or we reach EOF, as
   * casting int -1 to byte also returns -1.
   */
  public static byte readByte(InputStream in) throws IOException {
    int r = in.read();
    if (r < 0) {
      throw new EOFException();
    }
    return (byte) r;
  }

  public static short toShort(byte hi, byte lo) {
    short s = (short) (((hi & 0xFF) << 8) | (lo & 0xFF));
    Preconditions.checkArgument(s >= 0);
    return s;
  }

  public static void writeShort(OutputStream out, short v) throws IOException {
    Preconditions.checkArgument(v >= 0);
    out.write((byte) (0xff & (v >> 8)));
    out.write((byte) (0xff & v));
  }

  public static void writeInt(OutputStream out, int v) throws IOException {
    out.write((byte) (0xff & (v >> 24)));
    out.write((byte) (0xff & (v >> 16)));
    out.write((byte) (0xff & (v >> 8)));
    out.write((byte) (0xff & v));
  }

  public static void writeLong(OutputStream out, long v) throws IOException {
    out.write((byte) (0xff & (v >> 56)));
    out.write((byte) (0xff & (v >> 48)));
    out.write((byte) (0xff & (v >> 40)));
    out.write((byte) (0xff & (v >> 32)));
    out.write((byte) (0xff & (v >> 24)));
    out.write((byte) (0xff & (v >> 16)));
    out.write((byte) (0xff & (v >> 8)));
    out.write((byte) (0xff & v));
  }

  public static long readLong(InputStream in) throws IOException {
    long result = 0;
    for (int shift = 56; shift >= 0; shift -= 8) {
      long x = in.read();
      if (x < 0) throw new IOException("EOF");
      result |= (x << shift);
    }
    return result;
  }
}
