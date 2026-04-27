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
 * A consensus-level marker that delimits an application-side flush boundary inside the replicated
 * log.
 * <p>
 * When an application proposes a {@code FlushMarker} as the operation of a Raft log entry, the
 * {@link StateMachineAdapter} treats it specially: it drains any buffered {@link CommittedEntry}s
 * via {@link ConsensusSpi#onCommit(Object, java.util.List) ConsensusSpi.onCommit}, then fires
 * {@link ConsensusSpi#onFlushComplete(Object, FlushMarker) ConsensusSpi.onFlushComplete} with the
 * marker, breaking the next batch.
 * <p>
 * The {@code metadata} bytes are opaque to the consensus layer.
 */
@InterfaceAudience.Private
public final class FlushMarker {

  private final long flushOpSeqId;
  private final long snapshotMaxSeqId;
  private final byte[] metadata;

  public FlushMarker(long flushOpSeqId, long snapshotMaxSeqId, @NonNull byte[] metadata) {
    this.flushOpSeqId = flushOpSeqId;
    this.snapshotMaxSeqId = snapshotMaxSeqId;
    this.metadata = requireNonNull(metadata, "metadata").clone();
  }

  public long getFlushOpSeqId() {
    return flushOpSeqId;
  }

  public long getSnapshotMaxSeqId() {
    return snapshotMaxSeqId;
  }

  @NonNull
  public byte[] getMetadata() {
    return metadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    FlushMarker that = (FlushMarker) o;
    return flushOpSeqId == that.flushOpSeqId && snapshotMaxSeqId == that.snapshotMaxSeqId
      && Arrays.equals(metadata, that.metadata);
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(flushOpSeqId);
    result = 31 * result + Long.hashCode(snapshotMaxSeqId);
    result = 31 * result + Arrays.hashCode(metadata);
    return result;
  }

  @Override
  public String toString() {
    return "FlushMarker{flushOpSeqId=" + flushOpSeqId + ", snapshotMaxSeqId=" + snapshotMaxSeqId
      + ", metadata.length=" + metadata.length + '}';
  }
}
