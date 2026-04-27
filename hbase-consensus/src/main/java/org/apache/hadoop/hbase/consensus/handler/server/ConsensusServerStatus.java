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
import org.apache.yetus.audience.InterfaceStability;

/** Immutable point-in-time snapshot of {@link ConsensusServer} state and lifecycle. */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public final class ConsensusServerStatus {

  /** Lifecycle phases for {@link ConsensusServer}. */
  public enum State {
    /** Constructed but {@link ConsensusServer#start()} has not yet been called. */
    NEW,
    /** {@link ConsensusServer#start()} is running. */
    STARTING,
    /** Server has finished {@code start()} and is accepting {@code addGroup} calls. */
    RUNNING,
    /** {@link ConsensusServer#stop()} is running. */
    STOPPING,
    /** Server is fully torn down. */
    STOPPED
  }

  private final State state;
  private final int activeGroups;
  private final int maxGroups;
  private final long restoredGroups;

  public ConsensusServerStatus(State state, int activeGroups, int maxGroups, long restoredGroups) {
    this.state = state;
    this.activeGroups = activeGroups;
    this.maxGroups = maxGroups;
    this.restoredGroups = restoredGroups;
  }

  /** Returns the current lifecycle state. */
  public State getState() {
    return state;
  }

  /** Returns the number of currently registered groups. */
  public int getActiveGroups() {
    return activeGroups;
  }

  /** Returns the configured max groups bound. */
  public int getMaxGroups() {
    return maxGroups;
  }

  /**
   * Returns the number of groups whose state was restored from the durable log on
   * {@link ConsensusServer#start()}.
   */
  public long getRestoredGroups() {
    return restoredGroups;
  }

  @Override
  public String toString() {
    return "ConsensusServerStatus{state=" + state + ", activeGroups=" + activeGroups
      + ", maxGroups=" + maxGroups + ", restoredGroups=" + restoredGroups + "}";
  }
}
