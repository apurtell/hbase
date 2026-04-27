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

import org.apache.yetus.audience.InterfaceAudience;

/**
 * Pull-only accessor used by {@link ConsensusServerMetrics} to read live server state for its
 * gauges and to derive the per-endpoint JMX context name.
 */
@InterfaceAudience.Private
public interface ConsensusServerMetricsWrapper {

  /**
   * Returns the local Raft endpoint identifier as a string. Used to suffix the per-server JMX
   * context name; it must be unique per JVM-resident {@link ConsensusServer} so that two servers
   * registered into the same {@code MetricRegistries.global()} singleton get distinct contexts.
   */
  String getEndpointId();

  /**
   * Returns the number of groups currently registered on the server. Powers the
   * {@code activeGroups} gauge.
   */
  int getActiveGroups();

  /**
   * Returns the configured upper bound on simultaneously-registered groups. Powers the
   * {@code maxGroups} gauge.
   */
  int getMaxGroups();

  /**
   * Returns the number of groups whose state was restored from the durable log on bootstrap. Powers
   * the {@code restoredGroups} gauge.
   */
  long getRestoredGroups();

  /**
   * Returns the server's lifecycle state name ({@code NEW}, {@code RUNNING}, {@code STOPPED},
   * etc.). Powers the {@code lifecycleState} gauge.
   */
  String getLifecycleState();
}
