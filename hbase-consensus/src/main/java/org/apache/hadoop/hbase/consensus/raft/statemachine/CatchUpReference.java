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
package org.apache.hadoop.hbase.consensus.raft.statemachine;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;

/** Lightweight catch-up metadata for state transfer by reference. */
public final class CatchUpReference {
  private final long flushOpSeqId;
  private final long snapshotMaxSeqId;
  private final byte[] metadata;

  public CatchUpReference(long flushOpSeqId, long snapshotMaxSeqId, @NonNull byte[] metadata) {
    this.flushOpSeqId = flushOpSeqId;
    this.snapshotMaxSeqId = snapshotMaxSeqId;
    this.metadata = requireNonNull(metadata).clone();
  }

  public long getFlushOpSeqId() {
    return flushOpSeqId;
  }

  public long getSnapshotMaxSeqId() {
    return snapshotMaxSeqId;
  }

  @NonNull
  public byte[] getMetadata() {
    return metadata.clone();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CatchUpReference that = (CatchUpReference) o;
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
    return "CatchUpReference{flushOpSeqId=" + flushOpSeqId + ", snapshotMaxSeqId="
      + snapshotMaxSeqId + ", metadata.length=" + metadata.length + '}';
  }
}
