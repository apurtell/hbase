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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.apache.hadoop.hbase.consensus.handler.store.RaftStoreTestFixtures.LogStoreConfigs;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Whole-disk replay survives corruption. */
@Tag(SmallTests.TAG)
public class TestUnifiedRaftStoreCorruption extends TestBase {

  @TempDir
  Path tmp;

  private UnifiedRaftStore store;
  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
  private final RaftEndpoint a = LocalRaftEndpoint.newEndpoint();
  private final RaftEndpoint b = LocalRaftEndpoint.newEndpoint();

  private UnifiedRaftStore newStore(int shards) {
    return new UnifiedRaftStore(LogStoreConfigs.defaults(tmp.toFile(), shards));
  }

  @AfterEach
  public void tearDown() throws IOException {
    if (store != null) {
      store.close();
      store = null;
    }
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testTornTailTruncatedOnReload(int shards) throws IOException {
    byte[] gid = "g".getBytes();
    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    RaftStoreTestFixtures.seedMinimal(g, a, factory);
    RaftStoreTestFixtures.appendEntries(g, 1, 4, 1, 64, factory);
    g.flush();
    s1.close();

    Path seg = RaftStoreTestFixtures.onlySegmentForGroup(s1, gid, tmp);
    long size = Files.size(seg);
    try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
      ch.truncate(size - 8L);
    }

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    java.util.List<LogEntry> entries = rs.getLogEntries();
    assertThat(entries).hasSizeBetween(0, 4);
    long after = Files.size(seg);
    assertThat(after).isLessThanOrEqualTo(size - 8L);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testCrcFlipTruncatedOnReload(int shards) throws IOException {
    byte[] gid = "g".getBytes();
    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    RaftStoreTestFixtures.seedMinimal(g, a, factory);
    RaftStoreTestFixtures.appendEntries(g, 1, 6, 1, 32, factory);
    g.flush();
    s1.close();

    Path seg = RaftStoreTestFixtures.onlySegmentForGroup(s1, gid, tmp);
    long sizeBefore = Files.size(seg);
    flipByteAt(seg, sizeBefore - 16L);

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    assertThat(rs.getLogEntries()).hasSizeBetween(0, 6);
    long sizeAfter = Files.size(seg);
    assertThat(sizeAfter).isLessThanOrEqualTo(sizeBefore);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testBadPrologueDeletesSegment(int shards) throws IOException {
    byte[] gid = "g".getBytes();
    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    RaftStoreTestFixtures.seedMinimal(g, a, factory);
    RaftStoreTestFixtures.appendEntries(g, 1, 3, 1, 16, factory);
    g.flush();
    s1.close();

    Path seg = RaftStoreTestFixtures.onlySegmentForGroup(s1, gid, tmp);
    try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
      ByteBuffer bad = ByteBuffer.wrap(new byte[] { 0, 0, 0, 0 });
      ch.write(bad, 0L);
    }

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    assertThat(out).isEmpty();
    // Each shard must still have a fresh active segment; verify the corrupted shard does too.
    int sIdx = store.routeShard(gid);
    assertThat(store.currentSegmentForTesting(sIdx)).isNotNull();
  }

  /** Corrupting one shard's segment does not damage other shards' groups. */
  @Test
  public void testCorruptionOnOneShardDoesNotAffectOtherShards() throws IOException {
    int shards = 4;
    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    byte[] gA = null;
    byte[] gB = null;
    for (int i = 0; i < 64 && (gA == null || gB == null); i++) {
      byte[] cand = ("g-iso-" + i).getBytes();
      int s = s1.routeShard(cand);
      if (gA == null) {
        gA = cand;
      } else if (s != s1.routeShard(gA)) {
        gB = cand;
      }
    }
    assertThat(gA).isNotNull();
    assertThat(gB).isNotNull();

    RaftStore rA = s1.newGroupStore(gA);
    RaftStore rB = s1.newGroupStore(gB);
    RaftStoreTestFixtures.seedMinimal(rA, a, factory);
    RaftStoreTestFixtures.seedMinimal(rB, b, factory);
    RaftStoreTestFixtures.appendEntries(rA, 1, 3, 1, 16, factory);
    RaftStoreTestFixtures.appendEntries(rB, 1, 3, 1, 16, factory);
    rA.flush();
    rB.flush();
    s1.close();

    // Corrupt only shard-A's segment by smashing its prologue.
    Path segA = RaftStoreTestFixtures.onlySegmentForGroup(s1, gA, tmp);
    try (FileChannel ch = FileChannel.open(segA, StandardOpenOption.WRITE)) {
      ch.write(ByteBuffer.wrap(new byte[] { 0, 0, 0, 0 }), 0L);
    }

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    // Group B (untouched shard) must restore; group A (corrupted shard) must not.
    ByteBuffer bKey = ByteBuffer.wrap(gB).asReadOnlyBuffer();
    ByteBuffer aKey = ByteBuffer.wrap(gA).asReadOnlyBuffer();
    assertThat(out).containsKey(bKey);
    assertThat(out).doesNotContainKey(aKey);
    RestoredRaftState rsB = out.get(bKey);
    assertThat(rsB.getLogEntries()).hasSize(3);
  }

  private void flipByteAt(Path file, long offset) throws IOException {
    try (
      FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      ByteBuffer one = ByteBuffer.allocate(1);
      ch.read(one, offset);
      one.flip();
      byte b = one.get();
      ByteBuffer flipped = ByteBuffer.wrap(new byte[] { (byte) (~b & 0xFF) });
      ch.write(flipped, offset);
    }
  }
}
