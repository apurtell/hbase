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
package org.apache.hadoop.hbase.consensus.handler.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Per-store registry of {@link LogSegment}s keyed by segment id, plus per-segment per-group
 * {@code maxLogIndex} accounting for GC.
 * <p>
 * All access happens from the writer thread, so this class is intentionally not synchronized. The
 * GC pass and the write loop both run on the same thread.
 */
@InterfaceAudience.Private
final class SegmentIndex {
  private final NavigableMap<Long, LogSegment> segmentsById = new TreeMap<>();

  void register(@NonNull LogSegment segment) {
    segmentsById.put(segment.segmentId(), segment);
  }

  void unregister(long segmentId) {
    segmentsById.remove(segmentId);
  }

  @Nullable
  LogSegment get(long segmentId) {
    return segmentsById.get(segmentId);
  }

  @NonNull
  NavigableMap<Long, LogSegment> segmentsById() {
    return segmentsById;
  }

  /** Highest seen segment id; {@code -1} if the registry is empty. */
  long highestSegmentId() {
    return segmentsById.isEmpty() ? -1L : segmentsById.lastKey();
  }

  int size() {
    return segmentsById.size();
  }
}
