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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

/**
 * Whole-disk replay coverage for {@link UnifiedRaftStore#load()}: multi-group state, segment rolls,
 * truncate-from / truncate-until, and snapshot reassembly survive a close-then-reload cycle.
 */
@Tag(SmallTests.TAG)
public class TestUnifiedRaftStoreLoad extends TestBase {

  @TempDir
  Path tmp;

  private UnifiedRaftStore store;

  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
  private final RaftEndpoint a = LocalRaftEndpoint.newEndpoint();
  private final RaftEndpoint b = LocalRaftEndpoint.newEndpoint();

  private UnifiedRaftStore newStore() {
    return new UnifiedRaftStore(new LogStoreConfig(tmp.toFile(), 8, 2L, 64));
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
    store = newStore();
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    assertThat(out).isEmpty();
  }

  @Test
  public void testRestoreSingleGroupAfterRestart() throws IOException {
    byte[] gid = "g1".getBytes();

    UnifiedRaftStore s1 = newStore();
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

    store = newStore();
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

  @Test
  public void testTruncateFromAppliedDuringReplay() throws IOException {
    byte[] gid = "g1".getBytes();
    UnifiedRaftStore s1 = newStore();
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    g.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(a).setVoting(true).build());
    g.persistAndFlushInitialGroupMembers(factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList(a)).setVotingMembers(Arrays.asList(a)).build());
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(1).setVotedFor(a).build());
    for (int i = 1; i <= 5; i++) {
      g.persistLogEntries(Collections.singletonList(factory.createLogEntryBuilder().setIndex(i)
        .setTerm(1).setOperation(new byte[] { (byte) i }).build()));
    }
    g.flush();
    g.truncateLogEntriesFrom(3L);
    g.flush();
    s1.close();

    store = newStore();
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    assertThat(rs.getLogEntries()).hasSize(2);
    assertThat(rs.getLogEntries().get(0).getIndex()).isEqualTo(1L);
    assertThat(rs.getLogEntries().get(1).getIndex()).isEqualTo(2L);
  }

  @Test
  public void testTruncateUntilAppliedDuringReplay() throws IOException {
    byte[] gid = "g1".getBytes();
    UnifiedRaftStore s1 = newStore();
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    g.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(a).setVoting(true).build());
    g.persistAndFlushInitialGroupMembers(factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList(a)).setVotingMembers(Arrays.asList(a)).build());
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(1).setVotedFor(a).build());
    for (int i = 1; i <= 5; i++) {
      g.persistLogEntries(Collections.singletonList(factory.createLogEntryBuilder().setIndex(i)
        .setTerm(1).setOperation(new byte[] { (byte) i }).build()));
    }
    g.flush();
    g.truncateLogEntriesUntil(3L);
    g.flush();
    s1.close();

    store = newStore();
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    List<LogEntry> entries = rs.getLogEntries();
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).getIndex()).isEqualTo(4L);
    assertThat(entries.get(1).getIndex()).isEqualTo(5L);
  }

  @Test
  public void testSnapshotReassembledAcrossRestart() throws IOException {
    byte[] gid = "g1".getBytes();
    UnifiedRaftStore s1 = newStore();
    s1.load();
    RaftStore g = s1.newGroupStore(gid);
    g.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(a).setVoting(true).build());
    g.persistAndFlushInitialGroupMembers(factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList(a)).setVotingMembers(Arrays.asList(a)).build());
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

    store = newStore();
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    RestoredRaftState rs = out.values().iterator().next();
    assertThat(rs.getSnapshotEntry()).isNotNull();
    assertThat(rs.getSnapshotEntry().getIndex()).isEqualTo(10L);
    assertThat(rs.getSnapshotEntry().getTerm()).isEqualTo(3);
  }

  @Test
  public void testTwoGroupsRestoreIndependently() throws IOException {
    UnifiedRaftStore s1 = newStore();
    s1.load();
    RaftStore g1 = s1.newGroupStore("g1".getBytes());
    RaftStore g2 = s1.newGroupStore("g2".getBytes());
    seedMinimal(g1, a);
    seedMinimal(g2, b);
    g1.persistLogEntries(Collections.singletonList(factory.createLogEntryBuilder().setIndex(1L)
      .setTerm(1).setOperation(new byte[] { 1 }).build()));
    g2.persistLogEntries(Collections.singletonList(factory.createLogEntryBuilder().setIndex(1L)
      .setTerm(1).setOperation(new byte[] { 2 }).build()));
    g1.flush();
    g2.flush();
    s1.close();

    store = newStore();
    Map<ByteBuffer, RestoredRaftState> out = store.load();
    assertThat(out).hasSize(2);
  }

  private void seedMinimal(RaftStore g, RaftEndpoint ep) throws IOException {
    g.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(ep).setVoting(true).build());
    g.persistAndFlushInitialGroupMembers(factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList(ep)).setVotingMembers(Arrays.asList(ep)).build());
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(1).setVotedFor(ep).build());
  }
}
