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
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Transport-side configuration parsed from a Hadoop {@link Configuration}.
 * <p>
 * All keys are namespaced under {@code hbase.consensus.*}. Defaults are chosen so the transport is
 * usable out-of-the-box for tests; production deployments are expected to override the port and
 * possibly the watermarks based on operator policy.
 * <p>
 * Key-naming convention follows the rest of HBase ({@code XYZ_KEY} string + {@code DEFAULT_XYZ}
 * sibling, both {@code public static final}); see {@code NettyEventLoopGroupConfig} and
 * {@code NettyRpcServer} for the canonical examples.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
public final class TransportConfig {
  /** TCP port the server listens on. */
  public static final String KEY_PORT = "hbase.consensus.port";
  public static final int DEFAULT_PORT = 16080;

  /** Outbound flush window in milliseconds: how often per-peer batches are flushed. */
  public static final String KEY_BATCH_MS = "hbase.consensus.log.sync.batch.ms";
  public static final long DEFAULT_BATCH_MS = 5L;

  /**
   * Outbound compression algorithm name accepted by
   * {@link Compression#getCompressionAlgorithmByName}. The selected algorithm only governs how this
   * node compresses entries it produces. Receivers learn the algorithm from the
   * {@code LogEntryPB.op_payload_compression} ordinal carried on each entry, so this value does not
   * need to match across peers.
   */
  public static final String KEY_COMPRESSION = "hbase.consensus.transport.compression";
  public static final String DEFAULT_COMPRESSION = "none";

  /** Number of Netty IO threads in each event loop group. */
  public static final String KEY_IO_THREADS = "hbase.consensus.transport.io.threads";

  /** TCP connect timeout in milliseconds for the outbound bootstrap. */
  public static final String KEY_CONNECT_TIMEOUT_MS =
    "hbase.consensus.transport.connect.timeout.ms";
  public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

  /** Lower bound of the exponential reconnect backoff in milliseconds. */
  public static final String KEY_RECONNECT_BACKOFF_MIN_MS =
    "hbase.consensus.transport.reconnect.backoff.min.ms";
  public static final long DEFAULT_RECONNECT_BACKOFF_MIN_MS = 100L;

  /** Upper bound of the exponential reconnect backoff in milliseconds. */
  public static final String KEY_RECONNECT_BACKOFF_MAX_MS =
    "hbase.consensus.transport.reconnect.backoff.max.ms";
  public static final long DEFAULT_RECONNECT_BACKOFF_MAX_MS = 2_000L;

  /**
   * Channel write buffer high watermark in bytes. When exceeded the channel becomes unwritable and
   * the per-peer drain bails until back-pressure clears.
   */
  public static final String KEY_WRITE_HIGH_WM_BYTES =
    "hbase.consensus.transport.write.high.watermark.bytes";
  public static final int DEFAULT_WRITE_HIGH_WM_BYTES = 8 * 1024 * 1024;

  /** Channel write buffer low watermark in bytes. */
  public static final String KEY_WRITE_LOW_WM_BYTES =
    "hbase.consensus.transport.write.low.watermark.bytes";
  public static final int DEFAULT_WRITE_LOW_WM_BYTES = 2 * 1024 * 1024;

  /** Maximum decoded frame length in bytes. */
  public static final String KEY_MAX_FRAME_BYTES = "hbase.consensus.transport.max.frame.bytes";
  public static final int DEFAULT_MAX_FRAME_BYTES = 256 * 1024 * 1024;

  /** Per-peer JCTools MPSC mailbox initial chunk size. */
  public static final String KEY_MAILBOX_CHUNK_SIZE = "hbase.consensus.transport.mailbox.chunk";
  public static final int DEFAULT_MAILBOX_CHUNK_SIZE = 256;

  /**
   * Whether the transport may use the Linux Epoll event loop on supported architectures (x86_64,
   * aarch64). Mirrors {@code hbase.netty.nativetransport} and falls back to NIO when {@code false}
   * or when running on a non-Linux platform.
   */
  public static final String KEY_NATIVE_TRANSPORT = "hbase.consensus.netty.nativetransport";
  public static final boolean DEFAULT_NATIVE_TRANSPORT = true;

  /**
   * {@link org.apache.hbase.thirdparty.io.netty.buffer.ByteBufAllocator} selector. Accepted values
   * are {@value #ALLOCATOR_POOLED}, {@value #ALLOCATOR_UNPOOLED} and {@value #ALLOCATOR_HEAP}; any
   * other value is treated as a fully-qualified class name and instantiated reflectively, matching
   * the semantics of {@code hbase.netty.rpcserver.allocator}.
   */
  public static final String KEY_ALLOCATOR = "hbase.consensus.netty.allocator";
  public static final String ALLOCATOR_POOLED = "pooled";
  public static final String ALLOCATOR_UNPOOLED = "unpooled";
  public static final String ALLOCATOR_HEAP = "heap";
  public static final String DEFAULT_ALLOCATOR = ALLOCATOR_POOLED;

  private final int port;
  private final long batchMs;
  private final Compression.Algorithm compression;
  private final int ioThreads;
  private final int connectTimeoutMs;
  private final long reconnectBackoffMinMs;
  private final long reconnectBackoffMaxMs;
  private final int writeHighWatermarkBytes;
  private final int writeLowWatermarkBytes;
  private final int maxFrameBytes;
  private final int mailboxChunkSize;
  private final boolean nativeTransport;
  private final String allocator;

  /** Builds a transport config from the given Hadoop {@link Configuration}. */
  public TransportConfig(@NonNull Configuration conf) {
    this.port = conf.getInt(KEY_PORT, DEFAULT_PORT);
    this.batchMs = conf.getLong(KEY_BATCH_MS, DEFAULT_BATCH_MS);
    String algoName = conf.get(KEY_COMPRESSION, DEFAULT_COMPRESSION);
    this.compression = Compression.getCompressionAlgorithmByName(algoName);
    this.ioThreads =
      conf.getInt(KEY_IO_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors()));
    this.connectTimeoutMs = conf.getInt(KEY_CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS);
    this.reconnectBackoffMinMs =
      conf.getLong(KEY_RECONNECT_BACKOFF_MIN_MS, DEFAULT_RECONNECT_BACKOFF_MIN_MS);
    this.reconnectBackoffMaxMs =
      conf.getLong(KEY_RECONNECT_BACKOFF_MAX_MS, DEFAULT_RECONNECT_BACKOFF_MAX_MS);
    this.writeHighWatermarkBytes =
      conf.getInt(KEY_WRITE_HIGH_WM_BYTES, DEFAULT_WRITE_HIGH_WM_BYTES);
    this.writeLowWatermarkBytes = conf.getInt(KEY_WRITE_LOW_WM_BYTES, DEFAULT_WRITE_LOW_WM_BYTES);
    this.maxFrameBytes = conf.getInt(KEY_MAX_FRAME_BYTES, DEFAULT_MAX_FRAME_BYTES);
    this.mailboxChunkSize = conf.getInt(KEY_MAILBOX_CHUNK_SIZE, DEFAULT_MAILBOX_CHUNK_SIZE);
    this.nativeTransport = conf.getBoolean(KEY_NATIVE_TRANSPORT, DEFAULT_NATIVE_TRANSPORT);
    this.allocator = conf.get(KEY_ALLOCATOR, DEFAULT_ALLOCATOR);
    if (writeLowWatermarkBytes > writeHighWatermarkBytes) {
      throw new IllegalArgumentException(KEY_WRITE_LOW_WM_BYTES + " (" + writeLowWatermarkBytes
        + ") must be <= " + KEY_WRITE_HIGH_WM_BYTES + " (" + writeHighWatermarkBytes + ")");
    }
    if (reconnectBackoffMinMs > reconnectBackoffMaxMs) {
      throw new IllegalArgumentException(KEY_RECONNECT_BACKOFF_MIN_MS + " (" + reconnectBackoffMinMs
        + ") must be <= " + KEY_RECONNECT_BACKOFF_MAX_MS + " (" + reconnectBackoffMaxMs + ")");
    }
  }

  public int getPort() {
    return port;
  }

  public long getBatchMs() {
    return batchMs;
  }

  @NonNull
  public Compression.Algorithm getCompression() {
    return compression;
  }

  public int getIoThreads() {
    return ioThreads;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public long getReconnectBackoffMinMs() {
    return reconnectBackoffMinMs;
  }

  public long getReconnectBackoffMaxMs() {
    return reconnectBackoffMaxMs;
  }

  public int getWriteHighWatermarkBytes() {
    return writeHighWatermarkBytes;
  }

  public int getWriteLowWatermarkBytes() {
    return writeLowWatermarkBytes;
  }

  public int getMaxFrameBytes() {
    return maxFrameBytes;
  }

  public int getMailboxChunkSize() {
    return mailboxChunkSize;
  }

  /** Returns whether the transport may use the Linux Epoll native event loop on supported CPUs */
  public boolean isNativeTransport() {
    return nativeTransport;
  }

  /** Returns the {@code ByteBufAllocator} selector (see {@link #KEY_ALLOCATOR}) */
  @NonNull
  public String getAllocator() {
    return allocator;
  }
}
