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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hadoop.hbase.consensus.handler.store.RaftStoreTestFixtures.LogStoreConfigs;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Coverage of segment rolling and GC */
@Tag(SmallTests.TAG)
public class TestUnifiedRaftStoreSegments extends TestBase {

  @TempDir
  Path tmp;

  private UnifiedRaftStore store;
  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();

  @AfterEach
  public void tearDown() throws IOException {
    if (store != null) {
      store.close();
      store = null;
    }
  }

  /** ~4 KiB segments so a few entries can roll the log within a single test. */
  private UnifiedRaftStore newStore(int shards, boolean prealloc) {
    return new UnifiedRaftStore(prealloc
      ? LogStoreConfigs.prealloc(tmp.toFile(), shards, 4096L)
      : LogStoreConfigs.segmentBytes(tmp.toFile(), shards, 4096L));
  }

  private int shardOf(byte[] groupId) {
    return store.routeShard(groupId);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testSegmentRollsWhenSizeExceeded(int shards) throws IOException {
    store = newStore(shards, false);
    store.load();
    byte[] gid = "g".getBytes();
    int beforeRoll = store.segmentIndexForTesting(shardOf(gid)).size();
    RaftStore g = store.newGroupStore(gid);
    RaftStoreTestFixtures.appendEntries(g, 1, 16, 1, 1024, factory);
    g.flush();
    int afterRoll = store.segmentIndexForTesting(shardOf(gid)).size();
    assertThat(afterRoll).isGreaterThan(beforeRoll);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testGcAdvancesAfterTruncateUntil(int shards) throws IOException {
    store = newStore(shards, false);
    store.load();
    byte[] gid = "g".getBytes();
    RaftStore g = store.newGroupStore(gid);
    RaftStoreTestFixtures.appendEntries(g, 1, 16, 1, 1024, factory);
    g.flush();
    int rolled = store.segmentIndexForTesting(shardOf(gid)).size();
    assertThat(rolled).isGreaterThan(1);
    g.truncateLogEntriesUntil(16L);
    g.flush();
    int afterGc = store.segmentIndexForTesting(shardOf(gid)).size();
    assertThat(afterGc).isLessThanOrEqualTo(rolled);
    assertThat(afterGc).isGreaterThanOrEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testTruncateFromEmitsMarkerWithoutGc(int shards) throws IOException {
    UnifiedRaftStore small =
      new UnifiedRaftStore(LogStoreConfigs.segmentBytes(tmp.toFile(), shards, 1L << 30));
    store = small;
    store.load();
    byte[] gid = "g".getBytes();
    RaftStore g = store.newGroupStore(gid);
    RaftStoreTestFixtures.appendEntries(g, 1, 4, 1, 64, factory);
    g.flush();
    int before = store.segmentIndexForTesting(shardOf(gid)).size();
    g.truncateLogEntriesFrom(3L);
    g.flush();
    int after = store.segmentIndexForTesting(shardOf(gid)).size();
    assertThat(after).isEqualTo(before);
  }

  /**
   * With {@code preallocSegment=true}, each shard's open active segment file is exactly
   * {@code segmentSizeBytes} on disk, while its in-memory {@code currentSize()} tracks the actual
   * record tail.
   */
  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testPreallocatedSegmentMatchesSegmentSizeWhileActive(int shards) throws IOException {
    store = newStore(shards, true);
    store.load();
    for (int i = 0; i < shards; i++) {
      LogSegment seg = store.currentSegmentForTesting(i);
      assertThat(Files.size(seg.path())).isEqualTo(4096L);
      assertThat(seg.currentSize()).isLessThan(4096L);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testPreallocatedSegmentTruncatedToTailOnClose(int shards) throws IOException {
    UnifiedRaftStore s = newStore(shards, true);
    s.load();
    byte[] gid = "g".getBytes();
    RaftStore g = s.newGroupStore(gid);
    RaftStoreTestFixtures.appendEntries(g, 1, 1, 1, 8, factory);
    g.flush();
    Path activePath = s.currentSegmentForTesting(s.routeShard(gid)).path();
    long tailSize = s.currentSegmentForTesting(s.routeShard(gid)).currentSize();
    s.close();
    long fileSize = Files.size(activePath);
    assertThat(fileSize).isEqualTo(tailSize);
    assertThat(fileSize).isLessThan(4096L);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testRolledSegmentsTruncatedToTail(int shards) throws IOException {
    store = newStore(shards, true);
    store.load();
    byte[] gid = "g".getBytes();
    RaftStore g = store.newGroupStore(gid);
    RaftStoreTestFixtures.appendEntries(g, 1, 16, 1, 1024, factory);
    g.flush();
    int sIdx = shardOf(gid);
    long active = store.currentSegmentForTesting(sIdx).segmentId();
    int rolled = 0;
    for (LogSegment seg : store.segmentIndexForTesting(sIdx).segmentsById().values()) {
      if (seg.segmentId() == active) {
        continue;
      }
      rolled++;
      assertThat(Files.size(seg.path())).isEqualTo(seg.currentSize());
    }
    assertThat(rolled).isGreaterThan(0);
  }
}
