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
package org.apache.hadoop.hbase.consensus.raft.impl.local;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;

public final class LocalRaftEndpoint implements RaftEndpoint {
  private static final AtomicInteger COUNTER = new AtomicInteger();
  private final String id;

  private LocalRaftEndpoint(String id) {
    this.id = requireNonNull(id);
  }

  /**
   * Returns a new unique Raft endpoint.
   * @return a new unique Raft endpoint
   */
  public static LocalRaftEndpoint newEndpoint() {
    return new LocalRaftEndpoint("node" + COUNTER.incrementAndGet());
  }

  @NonNull
  @Override
  public Object getId() {
    return id;
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LocalRaftEndpoint that = (LocalRaftEndpoint) o;
    return id.equals(that.id);
  }

  @Override
  public String toString() {
    return "LocalRaftEndpoint{" + "id=" + id + '}';
  }
}
