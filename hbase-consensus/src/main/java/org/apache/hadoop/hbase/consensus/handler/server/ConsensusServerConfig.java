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
 * {@code SweepingHeartbeatScheduler}, {@code RaftConfig}) continue to be consumed by their
 * respective components from the same {@link Configuration} instance.
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

  /** Maximum number of log entries per replication batch from a single proposer. */
  public static final String PROPOSE_BATCH_MAX_ENTRIES_KEY =
    ConfigKey.INT("hbase.consensus.propose.batch.max.entries", v -> v >= 1);
  public static final int PROPOSE_BATCH_MAX_ENTRIES_DEFAULT = 1024;

  /** Maximum bytes per replication batch from a single proposer. */
  public static final String PROPOSE_MAX_BYTES_KEY =
    ConfigKey.INT("hbase.consensus.propose.max.bytes", v -> v >= 1);
  public static final int PROPOSE_MAX_BYTES_DEFAULT = 16 * 1024 * 1024;

  private final int maxGroups;
  private final int proposeBatchMaxEntries;
  private final int proposeMaxBytes;

  public ConsensusServerConfig(@NonNull Configuration conf) {
    this.maxGroups = conf.getInt(MAX_GROUPS_KEY, MAX_GROUPS_DEFAULT);
    this.proposeBatchMaxEntries =
      conf.getInt(PROPOSE_BATCH_MAX_ENTRIES_KEY, PROPOSE_BATCH_MAX_ENTRIES_DEFAULT);
    this.proposeMaxBytes = conf.getInt(PROPOSE_MAX_BYTES_KEY, PROPOSE_MAX_BYTES_DEFAULT);
    if (maxGroups < 1) {
      throw new IllegalArgumentException(MAX_GROUPS_KEY + " must be >= 1, got " + maxGroups);
    }
    if (proposeBatchMaxEntries < 1) {
      throw new IllegalArgumentException(
        PROPOSE_BATCH_MAX_ENTRIES_KEY + " must be >= 1, got " + proposeBatchMaxEntries);
    }
    if (proposeMaxBytes < 1) {
      throw new IllegalArgumentException(
        PROPOSE_MAX_BYTES_KEY + " must be >= 1, got " + proposeMaxBytes);
    }
  }

  public int getMaxGroups() {
    return maxGroups;
  }

  public int getProposeBatchMaxEntries() {
    return proposeBatchMaxEntries;
  }

  public int getProposeMaxBytes() {
    return proposeMaxBytes;
  }
}
