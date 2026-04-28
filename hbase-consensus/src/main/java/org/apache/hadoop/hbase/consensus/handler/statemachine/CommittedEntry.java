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
package org.apache.hadoop.hbase.consensus.handler.statemachine;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * A single entry committed by the Raft layer and delivered to a
 * {@link ConsensusSpi#onCommit(Object, java.util.List) ConsensusSpi.onCommit} batch.
 * <p>
 * The {@code payload} is the opaque operation bytes the application originally proposed. The
 * consensus layer never inspects them.
 * <p>
 * The {@code payload} array is referenced directly; the constructor performs no defensive copy.
 * Callers must not retain a reference to the original byte[] after constructing a
 * {@link CommittedEntry} from it: the consensus core hands its own freshly-decoded payloads in, and
 * SPI consumers that read the array are expected to treat it as read-only.
 */
@InterfaceAudience.Private
public final class CommittedEntry {

  private final long commitIndex;
  private final byte[] payload;

  public CommittedEntry(long commitIndex, @NonNull byte[] payload) {
    this.commitIndex = commitIndex;
    this.payload = requireNonNull(payload, "payload");
  }

  public long getCommitIndex() {
    return commitIndex;
  }

  @NonNull
  public byte[] getPayload() {
    return payload;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CommittedEntry that = (CommittedEntry) o;
    return commitIndex == that.commitIndex && Arrays.equals(payload, that.payload);
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(commitIndex);
    result = 31 * result + Arrays.hashCode(payload);
    return result;
  }

  @Override
  public String toString() {
    return "CommittedEntry{commitIndex=" + commitIndex + ", payload.length=" + payload.length + '}';
  }
}
