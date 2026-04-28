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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.apache.hadoop.hbase.consensus.handler.store.RaftStoreTestFixtures.LogStoreConfigs;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
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

/** Whole-disk replay coverage for {@link UnifiedRaftStore#load()}. */
@Tag(SmallTests.TAG)
public class TestUnifiedRaftStoreLoad extends TestBase {

  @TempDir
  Path tmp;

  private UnifiedRaftStore store;

  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
  private final RaftEndpoint a = LocalRaftEndpoint.newEndpoint();
  private final RaftEndpoint b = LocalRaftEndpoint.newEndpoint();

  private UnifiedRaftStore newStore(int shards) {
    return new UnifiedRaftStore(LogStoreConfigs.defaults(tmp.toFile(), shards));
  }

  private UnifiedRaftStore newPreallocStore(int shards) {
    return new UnifiedRaftStore(LogStoreConfigs.prealloc(tmp.toFile(), shards, 4096L));
  }

  @AfterEach
  public void tearDown() throws IOException {
    if (store != null) {
      store.close();
      store = null;
    }
  }

  @Test
  public void testLoadEmptyDirReturnsEmptyMap() throws IOException {
    store = newStore(1);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    assertThat(out).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testRestoreSingleGroupAfterRestart(int shards) throws IOException {
    byte[] gid = "g1".getBytes();

    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    g.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(a).setVoting(true).build());
    g.persistAndFlushInitialGroupMembers(factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList(a, b)).setVotingMembers(Arrays.asList(a, b)).build());
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(2).setVotedFor(a).build());
    g.persistLogEntries(Arrays.asList(
      factory.createLogEntryBuilder().setIndex(1L).setTerm(2).setOperation(new byte[] { 1 })
        .build(),
      factory.createLogEntryBuilder().setIndex(2L).setTerm(2).setOperation(new byte[] { 2 })
        .build()));
    g.flush();
    s1.close();

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    assertThat(out).hasSize(1);
    RestoredRaftState rs = out.values().iterator().next();
    assertThat(rs.getTermPersistentState().getTerm()).isEqualTo(2);
    assertThat(String.valueOf(rs.getLocalEndpointPersistentState().getLocalEndpoint().getId()))
      .isEqualTo(String.valueOf(a.getId()));
    assertThat(rs.getInitialGroupMembers().getMembers()).hasSize(2);
    List<LogEntry> entries = rs.getLogEntries();
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).getIndex()).isEqualTo(1L);
    assertThat(entries.get(1).getIndex()).isEqualTo(2L);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testTruncateFromAppliedDuringReplay(int shards) throws IOException {
    byte[] gid = "g1".getBytes();
    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    RaftStoreTestFixtures.seedMinimal(g, a, factory);
    RaftStoreTestFixtures.appendEntries(g, 1, 5, 1, 1, factory);
    g.flush();
    g.truncateLogEntriesFrom(3L);
    g.flush();
    s1.close();

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    assertThat(rs.getLogEntries()).hasSize(2);
    assertThat(rs.getLogEntries().get(0).getIndex()).isEqualTo(1L);
    assertThat(rs.getLogEntries().get(1).getIndex()).isEqualTo(2L);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testTruncateUntilAppliedDuringReplay(int shards) throws IOException {
    byte[] gid = "g1".getBytes();
    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    RaftStoreTestFixtures.seedMinimal(g, a, factory);
    RaftStoreTestFixtures.appendEntries(g, 1, 5, 1, 1, factory);
    g.flush();
    g.truncateLogEntriesUntil(3L);
    g.flush();
    s1.close();

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    List<LogEntry> entries = rs.getLogEntries();
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).getIndex()).isEqualTo(4L);
    assertThat(entries.get(1).getIndex()).isEqualTo(5L);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testSnapshotReassembledAcrossRestart(int shards) throws IOException {
    byte[] gid = "g1".getBytes();
    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    RaftStoreTestFixtures.seedMinimal(g, a, factory);
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(3).setVotedFor(a).build());
    RaftGroupMembersView snapMembers = factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList(a)).setVotingMembers(Arrays.asList(a)).build();
    int chunkCount = 3;
    for (int i = 0; i < chunkCount; i++) {
      SnapshotChunk c = factory.createSnapshotChunkBuilder().setIndex(10L).setTerm(3)
        .setSnapshotChunkIndex(i).setSnapshotChunkCount(chunkCount).setGroupMembersView(snapMembers)
        .setOperation(new byte[] { (byte) i }).build();
      g.persistSnapshotChunk(c);
    }
    g.flush();
    s1.close();

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    assertThat(rs.getSnapshotEntry()).isNotNull();
    assertThat(rs.getSnapshotEntry().getIndex()).isEqualTo(10L);
    assertThat(rs.getSnapshotEntry().getTerm()).isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testTwoGroupsRestoreIndependently(int shards) throws IOException {
    UnifiedRaftStore s1 = newStore(shards);
    s1.load();
    RaftStore g1 = s1.newGroupStore("g1".getBytes());
    RaftStore g2 = s1.newGroupStore("g2".getBytes());
    RaftStoreTestFixtures.seedMinimal(g1, a, factory);
    RaftStoreTestFixtures.seedMinimal(g2, b, factory);
    g1.persistLogEntries(Collections.singletonList(factory.createLogEntryBuilder().setIndex(1L)
      .setTerm(1).setOperation(new byte[] { 1 }).build()));
    g2.persistLogEntries(Collections.singletonList(factory.createLogEntryBuilder().setIndex(1L)
      .setTerm(1).setOperation(new byte[] { 2 }).build()));
    g1.flush();
    g2.flush();
    s1.close();

    store = newStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    assertThat(out).hasSize(2);
  }

  /**
   * Hard-crash recovery with pre-allocated segments: write N records, simulate a {@code kill -9},
   * then reopen.
   */
  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testHardCrashWithPreallocStopsAtZeroTail(int shards) throws IOException {
    byte[] gid = "g1".getBytes();
    UnifiedRaftStore s1 = newPreallocStore(shards);
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    RaftStoreTestFixtures.seedMinimal(g, a, factory);
    RaftStoreTestFixtures.appendEntries(g, 1, 4, 1, 1, factory);
    g.flush();
    Path activePath = s1.currentSegmentForGroup(gid).path();
    long recordTail = s1.currentSegmentForGroup(gid).currentSize();
    s1.close();
    try (FileChannel ch = FileChannel.open(activePath, StandardOpenOption.WRITE)) {
      java.nio.ByteBuffer marker = java.nio.ByteBuffer.allocate(1);
      while (marker.hasRemaining()) {
        ch.write(marker, 4096L - 1L);
      }
    }
    assertThat(Files.size(activePath)).isEqualTo(4096L);
    assertThat(recordTail).isLessThan(4096L);

    store = newPreallocStore(shards);
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    assertThat(rs.getLogEntries()).hasSize(4);
    for (int i = 0; i < 4; i++) {
      assertThat(rs.getLogEntries().get(i).getIndex()).isEqualTo((long) (i + 1));
    }
  }

  /** Refuse to start when the on-disk shard count differs from the configured shard count. */
  @Test
  public void testLayoutMismatchRefusedOnLoad() throws IOException {
    UnifiedRaftStore s4 = newStore(4);
    s4.load();
    RaftStore g = s4.newGroupStore("g".getBytes());
    RaftStoreTestFixtures.seedMinimal(g, a, factory);
    s4.close();

    UnifiedRaftStore downsize = newStore(1);
    try {
      assertThatThrownBy(downsize::load).isInstanceOf(IOException.class)
        .hasMessageContaining("shard");
    } finally {
      downsize.close();
    }

    UnifiedRaftStore upsize = newStore(8);
    try {
      assertThatThrownBy(upsize::load).isInstanceOf(IOException.class)
        .hasMessageContaining("shard");
    } finally {
      upsize.close();
    }
  }
}
