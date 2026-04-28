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
package org.apache.hadoop.hbase.consensus.handler.server;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.conf.ConfigKey;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Server-level configuration for {@link ConsensusServer}.
 * <p>
 * Only knobs that the {@code ConsensusServer} owns directly live here. Existing component-level
 * configs ({@code TransportConfig}, {@code LogStoreConfig}, {@code MultiGroupExecutor},
 * {@code BulkHeartbeatScheduler}, {@code RaftConfig}) continue to be consumed by their respective
 * components from the same {@link Configuration} instance.
 */
@InterfaceAudience.LimitedPrivate({ HBaseInterfaceAudience.CONFIG })
@InterfaceStability.Evolving
public final class ConsensusServerConfig {

  /**
   * Hard cap on the number of Raft groups a single {@link ConsensusServer} may host.
   * {@code addGroup} calls beyond this bound throw {@link MaxGroupsExceededException}.
   */
  public static final String MAX_GROUPS_KEY =
    ConfigKey.INT("hbase.consensus.maxgroups", v -> v >= 1);
  public static final int MAX_GROUPS_DEFAULT = 50_000;

  /**
   * Server-wide budget, in bytes, that bounds the aggregate footprint of uncommitted leader-side
   * propose operations across all Raft groups hosted by a single {@link ConsensusServer}.
   * Resolution order at construction time (see {@link #resolveMaxPendingBytes(Configuration)}):
   * <ol>
   * <li>Explicit value of {@code hbase.consensus.max.pending.bytes} (this key).</li>
   * <li>Otherwise, the value of {@link #HBASE_IPC_SERVER_MAX_CALLQUEUE_SIZE_KEY} from site config
   * (the same knob HBase's RPC handler uses to bound its aggregate in-flight-call byte
   * footprint).</li>
   * <li>Otherwise, {@link #MAX_PENDING_BYTES_DEFAULT}.</li>
   * </ol>
   * <p>
   * Enforced by {@link org.apache.hadoop.hbase.consensus.raft.PendingBytesBudget}, which the
   * {@code ConsensusServer} hands to every group it builds. The Raft propose path borrows credits
   * at admission time and releases them at commit-apply time (or wholesale on leader step-down /
   * terminate).
   * <p>
   * Scope distinction: the RPC site-config key bounds <i>per-server</i> aggregate in-flight-call
   * bytes; this consensus-scoped key bounds <i>per-server</i> aggregate uncommitted-propose bytes
   * across all groups on this {@code ConsensusServer}. They share an order of magnitude (both sized
   * to the same RegionServer's heap budget) which is why the RPC key is a sensible default source —
   * but operators who need them distinct should set this consensus-scoped key explicitly.
   */
  public static final String MAX_PENDING_BYTES_KEY =
    ConfigKey.LONG("hbase.consensus.max.pending.bytes", v -> v >= 1L);

  /**
   * Site-config key the HBase RPC server uses for its aggregate callqueue byte budget
   * ({@code RpcServer.maxQueueSizeInBytes}); see {@code hbase-server}'s
   * {@code RpcServer.DEFAULT_MAX_CALLQUEUE_SIZE}. The consensus engine reads this key only as the
   * second-tier default for {@link #MAX_PENDING_BYTES_KEY} when the latter is not explicitly set,
   * so a single site-level knob covers both layers without overriding consensus-scoped tuning.
   */
  public static final String HBASE_IPC_SERVER_MAX_CALLQUEUE_SIZE_KEY =
    "hbase.ipc.server.max.callqueue.size";

  /**
   * Final fallback for {@link #getMaxPendingBytes()} when neither {@link #MAX_PENDING_BYTES_KEY}
   * nor {@link #HBASE_IPC_SERVER_MAX_CALLQUEUE_SIZE_KEY} is present in site config. Mirrors
   * {@code hbase-server}'s {@code RpcServer.DEFAULT_MAX_CALLQUEUE_SIZE = 1 GiB}.
   */
  public static final long MAX_PENDING_BYTES_DEFAULT = 1L << 30;

  private final int maxGroups;
  private final long maxPendingBytes;

  public ConsensusServerConfig(@NonNull Configuration conf) {
    this.maxGroups = conf.getInt(MAX_GROUPS_KEY, MAX_GROUPS_DEFAULT);
    this.maxPendingBytes = resolveMaxPendingBytes(conf);
    if (maxGroups < 1) {
      throw new IllegalArgumentException(MAX_GROUPS_KEY + " must be >= 1, got " + maxGroups);
    }
    if (maxPendingBytes < 1L) {
      throw new IllegalArgumentException(
        MAX_PENDING_BYTES_KEY + " must be >= 1, got " + maxPendingBytes);
    }
  }

  /**
   * Resolves {@link #getMaxPendingBytes()} from {@code conf} using the three-tier rule:
   * consensus-scoped {@link #MAX_PENDING_BYTES_KEY} wins if explicitly set; otherwise the
   * site-level {@link #HBASE_IPC_SERVER_MAX_CALLQUEUE_SIZE_KEY} is read; otherwise
   * {@link #MAX_PENDING_BYTES_DEFAULT}. Visible for unit testing of the resolution order.
   */
  static long resolveMaxPendingBytes(@NonNull Configuration conf) {
    long fromIpc = conf.getLong(HBASE_IPC_SERVER_MAX_CALLQUEUE_SIZE_KEY, MAX_PENDING_BYTES_DEFAULT);
    return conf.getLong(MAX_PENDING_BYTES_KEY, fromIpc);
  }

  public int getMaxGroups() {
    return maxGroups;
  }

  /**
   * @return the server-wide aggregate budget (bytes) on uncommitted leader-side propose operations
   *         across all Raft groups hosted by the {@link ConsensusServer}
   */
  public long getMaxPendingBytes() {
    return maxPendingBytes;
  }
}
