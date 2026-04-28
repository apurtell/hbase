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
import org.apache.hadoop.hbase.conf.ConfigKey;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Transport-side configuration parsed from a Hadoop {@link Configuration}.
 * <p>
 * All keys are namespaced under {@code hbase.consensus.*}.
 * <p>
 * Key-naming convention follows the rest of HBase ({@code XYZ_KEY} string + {@code XYZ_DEFAULT}
 * sibling, both {@code public static final}); see {@code NettyEventLoopGroupConfig} and
 * {@code NettyRpcServer} for the canonical examples.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
@InterfaceStability.Evolving
public final class TransportConfig {
  /** TCP port the server listens on. */
  public static final String PORT_KEY =
    ConfigKey.INT("hbase.consensus.port", v -> v > 0 && v < 65536);
  public static final int PORT_DEFAULT = 16080;

  /**
   * Outbound compression algorithm name accepted by
   * {@link Compression#getCompressionAlgorithmByName}. The selected algorithm only governs how this
   * node compresses entries it produces. Receivers learn the algorithm from the
   * {@code LogEntryPB.op_payload_compression} ordinal carried on each entry, so this value does not
   * need to match across peers.
   */
  public static final String COMPRESSION_KEY = "hbase.consensus.transport.compression";
  public static final String COMPRESSION_DEFAULT = "none";

  /** Number of Netty IO threads in each event loop group. */
  public static final String IO_THREADS_KEY =
    ConfigKey.INT("hbase.consensus.transport.io.threads", v -> v >= 1);

  /** TCP connect timeout in milliseconds for the outbound bootstrap. */
  public static final String CONNECT_TIMEOUT_MS_KEY =
    ConfigKey.INT("hbase.consensus.transport.connect.timeout.ms", v -> v >= 1);
  public static final int CONNECT_TIMEOUT_MS_DEFAULT = 5_000;

  /** Lower bound of the exponential reconnect backoff in milliseconds. */
  public static final String RECONNECT_BACKOFF_MIN_MS_KEY =
    ConfigKey.LONG("hbase.consensus.transport.reconnect.backoff.min.ms", v -> v >= 1L);
  public static final long RECONNECT_BACKOFF_MIN_MS_DEFAULT = 100L;

  /** Upper bound of the exponential reconnect backoff in milliseconds. */
  public static final String RECONNECT_BACKOFF_MAX_MS_KEY =
    ConfigKey.LONG("hbase.consensus.transport.reconnect.backoff.max.ms", v -> v >= 1L);
  public static final long RECONNECT_BACKOFF_MAX_MS_DEFAULT = 2_000L;

  /**
   * Channel write buffer high watermark in bytes. When exceeded the channel becomes unwritable and
   * the per-peer drain bails until back-pressure clears.
   */
  public static final String WRITE_HIGH_WM_BYTES_KEY =
    ConfigKey.INT("hbase.consensus.transport.write.high.watermark.bytes", v -> v >= 1);
  public static final int WRITE_HIGH_WM_BYTES_DEFAULT = 8 * 1024 * 1024;

  /** Channel write buffer low watermark in bytes. */
  public static final String WRITE_LOW_WM_BYTES_KEY =
    ConfigKey.INT("hbase.consensus.transport.write.low.watermark.bytes", v -> v >= 1);
  public static final int WRITE_LOW_WM_BYTES_DEFAULT = 2 * 1024 * 1024;

  /** Maximum decoded frame length in bytes. */
  public static final String MAX_FRAME_BYTES_KEY =
    ConfigKey.INT("hbase.consensus.transport.max.frame.bytes", v -> v >= 1);
  public static final int MAX_FRAME_BYTES_DEFAULT = 256 * 1024 * 1024;

  /** Per-peer JCTools MPSC mailbox initial chunk size. */
  public static final String MAILBOX_CHUNK_SIZE_KEY =
    ConfigKey.INT("hbase.consensus.transport.mailbox.chunk", v -> v >= 1);
  public static final int MAILBOX_CHUNK_SIZE_DEFAULT = 256;

  /**
   * Whether the transport may use the Linux Epoll event loop on supported architectures (x86_64,
   * aarch64). Mirrors {@code hbase.netty.nativetransport} and falls back to NIO when {@code false}
   * or when running on a non-Linux platform.
   */
  public static final String NATIVE_TRANSPORT_KEY =
    ConfigKey.BOOLEAN("hbase.consensus.netty.nativetransport");
  public static final boolean NATIVE_TRANSPORT_DEFAULT = true;

  /**
   * {@link org.apache.hbase.thirdparty.io.netty.buffer.ByteBufAllocator} selector. Accepted values
   * are {@value #ALLOCATOR_POOLED}, {@value #ALLOCATOR_UNPOOLED} and {@value #ALLOCATOR_HEAP}. Any
   * other value is treated as a fully-qualified class name and instantiated reflectively, matching
   * the semantics of {@code hbase.netty.rpcserver.allocator}.
   */
  public static final String ALLOCATOR_KEY = "hbase.consensus.netty.allocator";
  public static final String ALLOCATOR_POOLED = "pooled";
  public static final String ALLOCATOR_UNPOOLED = "unpooled";
  public static final String ALLOCATOR_HEAP = "heap";
  public static final String ALLOCATOR_DEFAULT = ALLOCATOR_POOLED;

  private final int port;
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

  public TransportConfig(@NonNull Configuration conf) {
    this.port = conf.getInt(PORT_KEY, PORT_DEFAULT);
    String algoName = conf.get(COMPRESSION_KEY, COMPRESSION_DEFAULT);
    this.compression = Compression.getCompressionAlgorithmByName(algoName);
    this.ioThreads =
      conf.getInt(IO_THREADS_KEY, Math.max(1, Runtime.getRuntime().availableProcessors()));
    this.connectTimeoutMs = conf.getInt(CONNECT_TIMEOUT_MS_KEY, CONNECT_TIMEOUT_MS_DEFAULT);
    this.reconnectBackoffMinMs =
      conf.getLong(RECONNECT_BACKOFF_MIN_MS_KEY, RECONNECT_BACKOFF_MIN_MS_DEFAULT);
    this.reconnectBackoffMaxMs =
      conf.getLong(RECONNECT_BACKOFF_MAX_MS_KEY, RECONNECT_BACKOFF_MAX_MS_DEFAULT);
    this.writeHighWatermarkBytes =
      conf.getInt(WRITE_HIGH_WM_BYTES_KEY, WRITE_HIGH_WM_BYTES_DEFAULT);
    this.writeLowWatermarkBytes = conf.getInt(WRITE_LOW_WM_BYTES_KEY, WRITE_LOW_WM_BYTES_DEFAULT);
    this.maxFrameBytes = conf.getInt(MAX_FRAME_BYTES_KEY, MAX_FRAME_BYTES_DEFAULT);
    this.mailboxChunkSize = conf.getInt(MAILBOX_CHUNK_SIZE_KEY, MAILBOX_CHUNK_SIZE_DEFAULT);
    this.nativeTransport = conf.getBoolean(NATIVE_TRANSPORT_KEY, NATIVE_TRANSPORT_DEFAULT);
    this.allocator = conf.get(ALLOCATOR_KEY, ALLOCATOR_DEFAULT);
    if (writeLowWatermarkBytes > writeHighWatermarkBytes) {
      throw new IllegalArgumentException(WRITE_LOW_WM_BYTES_KEY + " (" + writeLowWatermarkBytes
        + ") must be <= " + WRITE_HIGH_WM_BYTES_KEY + " (" + writeHighWatermarkBytes + ")");
    }
    if (reconnectBackoffMinMs > reconnectBackoffMaxMs) {
      throw new IllegalArgumentException(RECONNECT_BACKOFF_MIN_MS_KEY + " (" + reconnectBackoffMinMs
        + ") must be <= " + RECONNECT_BACKOFF_MAX_MS_KEY + " (" + reconnectBackoffMaxMs + ")");
    }
  }

  public int getPort() {
    return port;
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

  /** Returns the {@code ByteBufAllocator} selector (see {@link #ALLOCATOR_KEY}) */
  @NonNull
  public String getAllocator() {
    return allocator;
  }
}
