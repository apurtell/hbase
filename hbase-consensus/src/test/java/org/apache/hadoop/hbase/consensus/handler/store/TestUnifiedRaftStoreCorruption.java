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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

/**
 * Whole-disk replay survives corruption. The Raft layer fills the gap via {@code AppendEntries} /
 * {@code InstallSnapshot} catch-up. This test exercises the store half.
 */
@Tag(SmallTests.TAG)
public class TestUnifiedRaftStoreCorruption extends TestBase {

  @TempDir
  Path tmp;

  private UnifiedRaftStore store;
  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
  private final RaftEndpoint a = LocalRaftEndpoint.newEndpoint();

  private UnifiedRaftStore newStore() {
    return new UnifiedRaftStore(new LogStoreConfig(tmp.toFile(), 8, 5L, 64));
  }

  @AfterEach
  public void tearDown() throws IOException {
    if (store != null) {
      store.close();
      store = null;
    }
  }

  @Test
  public void testTornTailTruncatedOnReload() throws IOException {
    byte[] gid = "g".getBytes();
    UnifiedRaftStore s1 = newStore();
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    seedMinimal(g);
    for (int i = 1; i <= 4; i++) {
      g.persistLogEntries(Collections.singletonList(
        factory.createLogEntryBuilder().setIndex(i).setTerm(1).setOperation(new byte[64]).build()));
    }
    g.flush();
    s1.close();

    Path seg = onlySegment();
    long size = Files.size(seg);
    // Drop the last 8 bytes — simulates a torn write.
    try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
      ch.truncate(size - 8L);
    }

    store = newStore();
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    List<LogEntry> entries = rs.getLogEntries();
    // We lose the trailing entry; everything before it must still be there.
    assertThat(entries).hasSizeBetween(0, 4);
    long after = Files.size(seg);
    assertThat(after).isLessThanOrEqualTo(size - 8L);
  }

  @Test
  public void testCrcFlipTruncatedOnReload() throws IOException {
    byte[] gid = "g".getBytes();
    UnifiedRaftStore s1 = newStore();
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    seedMinimal(g);
    for (int i = 1; i <= 6; i++) {
      g.persistLogEntries(Collections.singletonList(
        factory.createLogEntryBuilder().setIndex(i).setTerm(1).setOperation(new byte[32]).build()));
    }
    g.flush();
    s1.close();

    Path seg = onlySegment();
    long sizeBefore = Files.size(seg);
    flipByteAt(seg, sizeBefore - 16L);

    store = newStore();
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    assertThat(rs.getLogEntries()).hasSizeBetween(0, 6);
    long sizeAfter = Files.size(seg);
    assertThat(sizeAfter).isLessThanOrEqualTo(sizeBefore);
  }

  @Test
  public void testBadPrologueDeletesSegment() throws IOException {
    byte[] gid = "g".getBytes();
    UnifiedRaftStore s1 = newStore();
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    seedMinimal(g);
    for (int i = 1; i <= 3; i++) {
      g.persistLogEntries(Collections.singletonList(
        factory.createLogEntryBuilder().setIndex(i).setTerm(1).setOperation(new byte[16]).build()));
    }
    g.flush();
    s1.close();

    Path seg = onlySegment();
    // Smash the magic.
    try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
      ByteBuffer bad = ByteBuffer.wrap(new byte[] { 0, 0, 0, 0 });
      ch.write(bad, 0L);
    }

    store = newStore();
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    // Bad-magic segment is deleted by load(); store comes back with no usable groups.
    assertThat(out).isEmpty();
    assertThat(store.currentSegmentForTesting()).isNotNull();
  }

  private Path onlySegment() throws IOException {
    return Files.list(tmp)
      .filter(p -> p.getFileName().toString().startsWith(LogSegment.FILE_PREFIX)).findFirst()
      .orElseThrow();
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

  private void seedMinimal(RaftStore g) throws IOException {
    g.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(a).setVoting(true).build());
    g.persistAndFlushInitialGroupMembers(factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList(a)).setVotingMembers(Arrays.asList(a)).build());
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(1).setVotedFor(a).build());
  }
}
