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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.RaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;

/** Shared test fixtures for {@code TestUnifiedRaftStore*} suites. */
final class RaftStoreTestFixtures {

  private RaftStoreTestFixtures() {
  }

  /** Stock {@link LogStoreConfig} variants. */
  static final class LogStoreConfigs {

    private LogStoreConfigs() {
    }

    /**
     * Default test config for the load-/multiplex-/segment-style suites: roomy 8 MB segments,
     * 64-record mailbox, no per-commit fsync, periodic-fsync timer disabled, no preallocation.
     * Shard count taken from the caller so tests can parameterize on {@code @ValueSource(ints = {
     * 1, 4 })}.
     */
    static LogStoreConfig defaults(File logDir, int shards) {
      return LogStoreConfig.newBuilder(logDir).setSegmentSizeMb(8).setMailboxChunkSize(64)
        .setFsyncIntervalMs(0L).setPreallocSegment(false).setWriterShards(shards).build();
    }

    /**
     * Same as {@link #defaults(File, int)} but with an explicit {@code segmentSizeBytesOverride}
     * (used by {@code TestUnifiedRaftStoreSegments} to force fast rolls inside one test).
     */
    static LogStoreConfig segmentBytes(File logDir, int shards, long segmentBytes) {
      return LogStoreConfig.newBuilder(logDir).setSegmentSizeMb(1).setMailboxChunkSize(64)
        .setSegmentSizeBytesOverride(segmentBytes).setFsyncIntervalMs(0L).setPreallocSegment(false)
        .setWriterShards(shards).build();
    }

    /** Like {@link #segmentBytes(File, int, long)} but with prealloc on. */
    static LogStoreConfig prealloc(File logDir, int shards, long segmentBytes) {
      return LogStoreConfig.newBuilder(logDir).setSegmentSizeMb(1).setMailboxChunkSize(64)
        .setSegmentSizeBytesOverride(segmentBytes).setFsyncIntervalMs(0L).setPreallocSegment(true)
        .setWriterShards(shards).build();
    }

    /**
     * Tier C strict-fsync mode for the write-path tests: every {@code flush()} barrier blocks on
     * {@code force(false)}.
     */
    static LogStoreConfig strictFsync(File logDir, int shards) {
      return LogStoreConfig.newBuilder(logDir).setSegmentSizeMb(8).setMailboxChunkSize(64)
        .setFsyncOnCommit(true).setFsyncIntervalMs(0L).setPreallocSegment(false)
        .setWriterShards(shards).build();
    }

    /** Periodic-fsync timer enabled at the given interval (ms). */
    static LogStoreConfig periodicFsync(File logDir, int shards, long intervalMs) {
      return LogStoreConfig.newBuilder(logDir).setSegmentSizeMb(8).setMailboxChunkSize(64)
        .setFsyncOnCommit(false).setFsyncIntervalMs(intervalMs).setPreallocSegment(false)
        .setWriterShards(shards).build();
    }
  }

  /**
   * Persists the minimum endpoint / initial-members / term state for a group. Replaces several
   * inline copies of this boilerplate across the {@code TestUnifiedRaftStore*} classes.
   */
  static void seedMinimal(RaftStore g, RaftEndpoint ep, RaftModelFactory factory)
    throws IOException {
    g.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(ep).setVoting(true).build());
    g.persistAndFlushInitialGroupMembers(factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList(ep)).setVotingMembers(Arrays.asList(ep)).build());
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(1).setVotedFor(ep).build());
  }

  /**
   * Appends {@code count} log entries starting at index {@code fromIndex}, all in the given term,
   * each carrying a {@code payloadBytes}-byte zero-filled operation. Replaces six-plus copies of
   * the same {@code for (int i = 1; ...) g.persistLogEntries(Collections.singletonList(...))} loop.
   */
  static void appendEntries(RaftStore g, int fromIndex, int count, int term, int payloadBytes,
    RaftModelFactory factory) throws IOException {
    byte[] op = new byte[payloadBytes];
    for (int i = 0; i < count; i++) {
      LogEntry e = factory.createLogEntryBuilder().setIndex(fromIndex + i).setTerm(term)
        .setOperation(op).build();
      g.persistLogEntries(Collections.singletonList(e));
    }
  }

  /**
   * Returns the segment files for the shard owning {@code groupId}. Used by corruption tests to
   * find the segments to mutate even when the store is sharded across multiple subdirectories.
   */
  static List<Path> segmentsForGroup(UnifiedRaftStore store, byte[] groupId, Path baseDir)
    throws IOException {
    Path shardDir = baseDir.resolve(UnifiedRaftStore.SHARD_DIR_PREFIX + store.routeShard(groupId));
    if (!Files.isDirectory(shardDir)) {
      return Collections.emptyList();
    }
    try (java.util.stream.Stream<Path> stream = Files.list(shardDir)) {
      return stream.filter(p -> p.getFileName().toString().startsWith(LogSegment.FILE_PREFIX))
        .sorted().collect(java.util.stream.Collectors.toList());
    }
  }

  /** The (single) segment in the shard owning {@code groupId}. */
  static Path onlySegmentForGroup(UnifiedRaftStore store, byte[] groupId, Path baseDir)
    throws IOException {
    List<Path> all = segmentsForGroup(store, groupId, baseDir);
    if (all.size() != 1) {
      throw new IllegalStateException(
        "Expected exactly one segment for group, got " + all.size() + ": " + all);
    }
    return all.get(0);
  }

  /**
   * Test-only {@link LogSegment} subclass that observes the fsync-before-commit contract by
   * counting {@link LogSegment#force(boolean)} calls. Wraps an existing segment by sharing its
   * {@code FileChannel}, path, id, and current size.
   */
  static final class CountingLogSegment extends LogSegment {
    final AtomicLong forceCount = new AtomicLong();

    CountingLogSegment(LogSegment original) {
      super(original.segmentId(), original.path(), original.channel(), original.currentSize());
    }

    @Override
    void force(boolean metadata) throws IOException {
      forceCount.incrementAndGet();
      super.force(metadata);
    }
  }
}
