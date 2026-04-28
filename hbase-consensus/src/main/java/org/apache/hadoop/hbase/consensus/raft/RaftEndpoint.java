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
package org.apache.hadoop.hbase.consensus.raft;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Represents an endpoint that participates to at least one Raft group and executes the Raft
 * consensus algorithm with a {@link RaftNode} instance.
 * <p>
 * For the Raft algorithm implementation, it is sufficient to differentiate members of a Raft group
 * with a unique id, and that is why we only have a single method in this interface. It is users'
 * responsibility to assign unique ids to different Raft endpoints.
 */
@InterfaceAudience.Private
public interface RaftEndpoint {
  /** Returns the unique identifier of the Raft endpoint. */
  @NonNull
  Object getId();

  /**
   * Returns the {@link EndpointId} value object that wraps this endpoint's id bytes for hot-path
   * use (cached {@code ByteString} view, pre-built {@code RaftEndpointPB}).
   * <p>
   * The default implementation derives the value from {@link #getId()} on every call, which
   * allocates. Implementations should override this to return a cached instance built once at
   * construction time.
   */
  @NonNull
  default EndpointId endpointId() {
    return EndpointId.of(getId());
  }
}
