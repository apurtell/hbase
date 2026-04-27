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
 * Thrown by {@link GroupManager#addGroup} when a server already hosts
 * {@link ConsensusServerConfig#MAX_GROUPS_KEY} Raft groups and a new {@code addGroup} would push
 * the registry beyond that bound.
 */
@InterfaceAudience.Private
public final class MaxGroupsExceededException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public MaxGroupsExceededException(int maxGroups, Object groupId) {
    super("ConsensusServer has reached the configured maxGroups=" + maxGroups
      + "; cannot add groupId=" + groupId);
  }
}
