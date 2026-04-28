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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.yetus.audience.InterfaceAudience;

import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;
import org.apache.hbase.thirdparty.com.google.protobuf.UnsafeByteOperations;

/**
 * Pluggable per-entry payload compression for {@code LogEntryPB.op_payload}.
 * <p>
 * The configured algorithm only governs the >outbound path. Messages produced by this node stamp
 * {@code LogEntryPB.op_payload_compression} with the algorithm's enum ordinal. The inbound path
 * resolves the algorithm from that wire field via {@link #algorithmFromOrdinal(int)}, so a receiver
 * can decode entries compressed with any algorithm regardless of how it itself is configured.
 * <p>
 * Construction-time setup, performed once when {@link CoalescingTransport} is built:
 * <ol>
 * <li>Resolve the algorithm via {@link Compression#getCompressionAlgorithmByName(String)}.</li>
 * <li>If the algorithm is {@link Compression.Algorithm#NONE}, {@link #compress(ByteString)}
 * short-circuits to a pass-through with no allocation and no codec interaction.</li>
 * <li>Otherwise the {@link Compression.Algorithm} enum constant is held as a final field and the
 * codec cache is eagerly warmed so the first per-entry compress on the hot path doesn't pay the
 * lookup cost.</li>
 * </ol>
 * Per-call hot path acquires a {@code Compressor} / {@code Decompressor} from the algorithm's pool,
 * runs the stream, and returns it in a {@code finally} block so codec instances are reused across
 * calls. Decompressors are warmed lazily on first use of each peer-supplied algorithm.
 */
@InterfaceAudience.Private
public final class PayloadCompressor {
  /**
   * Cached snapshot of {@link Compression.Algorithm#values()} so {@link #algorithmFromOrdinal(int)}
   * doesn't allocate a fresh array per call. {@code Compression.Algorithm}'s ordinals are part of
   * the public contract, so this snapshot is safe to hold.
   */
  private static final Compression.Algorithm[] ALGORITHMS = Compression.Algorithm.values();

  private final Compression.Algorithm algorithm;

  /**
   * Builds a compressor for the given outbound algorithm.
   * @param algorithm resolved algorithm (must not be {@code null}); pass
   *                  {@link Compression.Algorithm#NONE} to disable outbound compression
   */
  public PayloadCompressor(@NonNull Compression.Algorithm algorithm) {
    this.algorithm = algorithm;
    if (algorithm != Compression.Algorithm.NONE) {
      Compressor c = algorithm.getCompressor();
      if (c != null) {
        algorithm.returnCompressor(c);
      }
    }
  }

  /** Returns whether outbound compression is wired to {@link Compression.Algorithm#NONE} */
  public boolean isNoop() {
    return algorithm == Compression.Algorithm.NONE;
  }

  /** Returns the configured outbound algorithm */
  @NonNull
  public Compression.Algorithm algorithm() {
    return algorithm;
  }

  /**
   * Resolves a {@link Compression.Algorithm} from its enum ordinal as carried on the wire by
   * {@code LogEntryPB.op_payload_compression}. The {@code Compression.Algorithm} enum guarantees
   * stable ordinals (its javadoc forbids reordering), so this mapping is wire-stable across
   * releases.
   * @throws IllegalArgumentException if {@code ordinal} does not correspond to any known algorithm
   */
  @NonNull
  public static Compression.Algorithm algorithmFromOrdinal(int ordinal) {
    if (ordinal < 0 || ordinal >= ALGORITHMS.length) {
      throw new IllegalArgumentException("Unknown Compression.Algorithm ordinal: " + ordinal);
    }
    return ALGORITHMS[ordinal];
  }

  /**
   * Compresses {@code raw} for the wire using the configured outbound algorithm. Returns
   * {@code raw} unchanged when the algorithm is {@link Compression.Algorithm#NONE} or the input is
   * empty.
   */
  @NonNull
  public ByteString compress(@NonNull ByteString raw) throws IOException {
    return compress(raw, algorithm);
  }

  /**
   * Compresses {@code raw} for the wire using the supplied algorithm. Returns {@code raw} unchanged
   * when {@code alg} is {@link Compression.Algorithm#NONE} or the input is empty.
   */
  @NonNull
  public static ByteString compress(@NonNull ByteString raw, @NonNull Compression.Algorithm alg)
    throws IOException {
    if (alg == Compression.Algorithm.NONE || raw.isEmpty()) {
      return raw;
    }
    Compressor compressor = alg.getCompressor();
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(raw.size());
      try (CompressionOutputStream cos = alg.createPlainCompressionStream(baos, compressor)) {
        raw.writeTo((OutputStream) cos);
        cos.finish();
      }
      // Zero-copy: {@code baos.toByteArray()} returns a freshly allocated array owned solely by
      // this call frame, so wrapping it instead of copying is safe.
      return UnsafeByteOperations.unsafeWrap(baos.toByteArray());
    } finally {
      alg.returnCompressor(compressor);
    }
  }

  /**
   * Decompresses {@code wire} using the algorithm carried alongside it on the wire. Returns
   * {@code wire} unchanged when {@code alg} is {@link Compression.Algorithm#NONE} or the input is
   * empty. The codec is acquired from the algorithm's pool on demand, so receivers do not need to
   * be configured with the same algorithm as the sender.
   */
  @NonNull
  public static ByteString decompress(@NonNull ByteString wire, @NonNull Compression.Algorithm alg)
    throws IOException {
    if (alg == Compression.Algorithm.NONE || wire.isEmpty()) {
      return wire;
    }
    Decompressor decompressor = alg.getDecompressor();
    try (InputStream in = alg
      .createDecompressionStream(new ByteArrayInputStream(wire.toByteArray()), decompressor, 0)) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(wire.size() * 2);
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) {
        baos.write(buf, 0, n);
      }
      return UnsafeByteOperations.unsafeWrap(baos.toByteArray());
    } finally {
      alg.returnDecompressor(decompressor);
    }
  }
}
