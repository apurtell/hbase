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
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.apache.hadoop.hbase.consensus.handler.store.RaftStoreTestFixtures.CountingLogSegment;
import org.apache.hadoop.hbase.consensus.handler.store.RaftStoreTestFixtures.LogStoreConfigs;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * End-to-end behaviours of {@link UnifiedRaftStore} on the write path: coalesced batched writes,
 * synchronous flush barriers, sync-fsync gating, and per-call group routing through the
 * {@link GroupRaftStore} adapter.
 */
@Tag(SmallTests.TAG)
public class TestUnifiedRaftStoreWritePath extends TestBase {

  @TempDir
  Path tmp;

  private UnifiedRaftStore store;

  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
  private final RaftEndpoint a = LocalRaftEndpoint.newEndpoint();
  private final RaftEndpoint b = LocalRaftEndpoint.newEndpoint();

  @AfterEach
  public void tearDown() throws IOException {
    if (store != null) {
      store.close();
      store = null;
    }
  }

  private UnifiedRaftStore newDefaultStore(int shards) {
    return new UnifiedRaftStore(LogStoreConfigs.defaults(tmp.toFile(), shards));
  }

  private UnifiedRaftStore newStrictFsyncStore(int shards) {
    return new UnifiedRaftStore(LogStoreConfigs.strictFsync(tmp.toFile(), shards));
  }

  private UnifiedRaftStore newPeriodicFsyncStore(int shards, long intervalMs) {
    return new UnifiedRaftStore(LogStoreConfigs.periodicFsync(tmp.toFile(), shards, intervalMs));
  }

  private LogSegment activeSegmentForGroup(byte[] groupId) {
    return store.currentSegmentForGroup(groupId);
  }

  private CountingLogSegment installCountingSegmentFor(byte[] groupId) throws IOException {
    store.awaitMailboxDrainedForTesting();
    int shardIdx = store.routeShard(groupId);
    CountingLogSegment counting = new CountingLogSegment(store.currentSegmentForTesting(shardIdx));
    store.replaceCurrentSegmentForTesting(shardIdx, counting);
    store.shardForTesting(shardIdx).resetUnflushedBytesForTesting();
    return counting;
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testEmptyDirLoadCreatesSegment(int shards) throws IOException {
    store = newDefaultStore(shards);
    assertThat(store.load()).isEmpty();
    // Every shard should have created its boot segment.
    for (int i = 0; i < shards; i++) {
      assertThat(store.currentSegmentForTesting(i)).isNotNull();
      assertThat(store.segmentIndexForTesting(i).size()).isEqualTo(1);
      assertThat(store.currentSegmentForTesting(i).currentSize())
        .isGreaterThan((long) LogRecord.PROLOGUE_BYTES);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testFlushBarrierSynchronousProgress(int shards) throws IOException {
    store = newDefaultStore(shards);
    store.load();
    byte[] gid = "g".getBytes();
    RaftStore g = store.newGroupStore(gid);
    LogEntry e1 =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[8]).build();
    g.persistLogEntries(Collections.singletonList(e1));
    g.flush();
    long sizeAfterFirst = activeSegmentForGroup(gid).currentSize();
    assertThat(sizeAfterFirst).isGreaterThan((long) LogRecord.PROLOGUE_BYTES);

    LogEntry e2 =
      factory.createLogEntryBuilder().setIndex(2L).setTerm(1).setOperation(new byte[8]).build();
    g.persistLogEntries(Collections.singletonList(e2));
    g.flush();
    assertThat(activeSegmentForGroup(gid).currentSize()).isGreaterThan(sizeAfterFirst);
  }

  /** {@link RaftStore#flush()} returns after writev to page cache, not after fsync. */
  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testFlushBarrierDoesNotFsyncInLazyMode(int shards) throws IOException {
    store = newDefaultStore(shards);
    store.load();
    byte[] gid = "g".getBytes();
    CountingLogSegment counting = installCountingSegmentFor(gid);

    RaftStore g = store.newGroupStore(gid);
    long before = counting.forceCount.get();
    LogEntry e =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[8]).build();
    g.persistLogEntries(Collections.singletonList(e));
    g.flush();
    assertThat(counting.forceCount.get()).isEqualTo(before);

    g.flush();
    g.flush();
    assertThat(counting.forceCount.get()).isEqualTo(before);
  }

  /** {@link RaftStore#flush()} blocks on {@code force(false)}. */
  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testFlushBarrierFsyncsActiveSegmentInStrictMode(int shards) throws IOException {
    store = newStrictFsyncStore(shards);
    store.load();
    byte[] gid = "g".getBytes();
    CountingLogSegment counting = installCountingSegmentFor(gid);

    RaftStore g = store.newGroupStore(gid);
    LogEntry e =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[8]).build();
    g.persistLogEntries(Collections.singletonList(e));
    long before = counting.forceCount.get();
    g.flush();
    assertThat(counting.forceCount.get()).isGreaterThan(before);

    long beforeBare = counting.forceCount.get();
    g.flush();
    assertThat(counting.forceCount.get()).isGreaterThan(beforeBare);
  }

  /**
   * {@code persistAndFlushTerm} (and the other Tier A frames) ALWAYS issue {@code force(false)}
   * regardless of {@code fsyncOnCommit} or shard count.
   */
  @ParameterizedTest
  @CsvSource({ "false,1", "false,4", "true,1", "true,4" })
  public void testTermVoteFramesAlwaysFsync(boolean fsyncOnCommit, int shards) throws IOException {
    store = fsyncOnCommit ? newStrictFsyncStore(shards) : newDefaultStore(shards);
    store.load();
    byte[] gid = "g".getBytes();
    CountingLogSegment counting = installCountingSegmentFor(gid);

    RaftStore g = store.newGroupStore(gid);
    long before = counting.forceCount.get();
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(7).setVotedFor(a).build());
    assertThat(counting.forceCount.get()).isGreaterThan(before);
  }

  /** Periodic-fsync timer fires when {@code unflushedBytes > 0} on the writer's shard. */
  @Test
  public void testPeriodicFsyncTimerFiresWhenWriterHasUnflushedBytes() throws IOException {
    long intervalMs = 50L;
    store = newPeriodicFsyncStore(1, intervalMs);
    store.load();
    byte[] gid = "g".getBytes();
    CountingLogSegment counting = installCountingSegmentFor(gid);

    RaftStore g = store.newGroupStore(gid);
    LogEntry e =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[8]).build();
    long before = counting.forceCount.get();
    g.persistLogEntries(Collections.singletonList(e));
    g.flush();
    await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
      .until(() -> counting.forceCount.get() > before);
  }

  /** Periodic-fsync timer is a no-op when its shard has no unflushed bytes. */
  @Test
  public void testPeriodicFsyncTimerIsNoOpWhenIdle() throws IOException {
    long intervalMs = 25L;
    store = newPeriodicFsyncStore(1, intervalMs);
    store.load();
    byte[] gid = "g".getBytes();
    CountingLogSegment counting = installCountingSegmentFor(gid);

    RaftStore g = store.newGroupStore(gid);
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(1).setVotedFor(a).build());
    long settled = counting.forceCount.get();
    await().pollDelay(Duration.ofMillis(intervalMs * 8L))
      .untilAsserted(() -> assertThat(counting.forceCount.get()).isEqualTo(settled));
  }

  /** Disabling the periodic timer ({@code intervalMs <= 0}) skips the schedule entirely. */
  @Test
  public void testPeriodicFsyncTimerDisabled() throws IOException {
    store = newPeriodicFsyncStore(1, 0L);
    store.load();
    byte[] gid = "g".getBytes();
    CountingLogSegment counting = installCountingSegmentFor(gid);

    RaftStore g = store.newGroupStore(gid);
    LogEntry e =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[8]).build();
    g.persistLogEntries(Collections.singletonList(e));
    g.flush();
    long settled = counting.forceCount.get();
    await().pollDelay(Duration.ofMillis(200L))
      .untilAsserted(() -> assertThat(counting.forceCount.get()).isEqualTo(settled));
  }

  @Test
  public void testSyncFsyncFlushTermDurable() throws IOException {
    store = newDefaultStore(1);
    store.load();
    byte[] gid = "g".getBytes();
    RaftStore g = store.newGroupStore(gid);
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(3).setVotedFor(a).build());
    assertThat(activeSegmentForGroup(gid).currentSize())
      .isGreaterThan((long) LogRecord.PROLOGUE_BYTES);
  }

  /**
   * Two groups multiplexed: at {@code shards=1} they share a segment; at {@code shards=4} they may
   * land on different shards. In both cases the per-shard segment count for whichever shard owns
   * the group is exactly one (no spurious roll).
   */
  @ParameterizedTest
  @ValueSource(ints = { 1, 4 })
  public void testTwoGroupsMultiplexed(int shards) throws IOException {
    store = newDefaultStore(shards);
    store.load();
    byte[] gid1 = "g1".getBytes();
    byte[] gid2 = "g2".getBytes();
    RaftStore g1 = store.newGroupStore(gid1);
    RaftStore g2 = store.newGroupStore(gid2);
    g1.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(a).setVoting(true).build());
    g2.persistAndFlushLocalEndpoint(factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(b).setVoting(true).build());
    g1.persistAndFlushInitialGroupMembers(
      factory.createRaftGroupMembersViewBuilder().setLogIndex(0L).setMembers(Arrays.asList(a, b))
        .setVotingMembers(Arrays.asList(a, b)).build());
    g2.persistAndFlushInitialGroupMembers(
      factory.createRaftGroupMembersViewBuilder().setLogIndex(0L).setMembers(Arrays.asList(a, b))
        .setVotingMembers(Arrays.asList(a, b)).build());
    g1.flush();
    g2.flush();
    int s1 = store.routeShard(gid1);
    int s2 = store.routeShard(gid2);
    assertThat(store.segmentIndexForTesting(s1).size()).isEqualTo(1);
    assertThat(store.segmentIndexForTesting(s2).size()).isEqualTo(1);
  }

  @Test
  public void testFlushIdempotent() throws IOException {
    store = newDefaultStore(1);
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    g.flush();
    g.flush();
    g.flush();
  }

  @Test
  public void testCloseDrainsPendingWrites() throws IOException {
    store = newDefaultStore(4);
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    for (int i = 1; i <= 100; i++) {
      LogEntry e =
        factory.createLogEntryBuilder().setIndex(i).setTerm(1).setOperation(new byte[16]).build();
      g.persistLogEntries(Collections.singletonList(e));
    }
    store.close();
    store = null;
  }

  @Test
  public void testNewGroupStoreAfterCloseRejected() throws IOException {
    store = newDefaultStore(1);
    store.load();
    store.close();
    assertThatThrownBy(() -> store.newGroupStore("g".getBytes())).isInstanceOf(IOException.class);
    store = null;
  }

  @Test
  public void testPersistAfterCloseRejected() throws IOException {
    store = newDefaultStore(1);
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    store.close();
    LogEntry e =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[1]).build();
    assertThatThrownBy(() -> g.persistLogEntries(Collections.singletonList(e)))
      .isInstanceOf(IOException.class);
    store = null;
  }

  @Test
  public void testLoadCalledTwiceRejected() throws IOException {
    store = newDefaultStore(1);
    store.load();
    assertThatThrownBy(() -> store.load()).isInstanceOf(IllegalStateException.class);
  }

  /**
   * The same group id always lands on the same shard, and crafted ids that hash to different shards
   * land on different shard subdirectories.
   */
  @Test
  public void testGroupRoutingIsDeterministic() throws IOException {
    store = newDefaultStore(4);
    store.load();
    byte[] gid = "g-deterministic".getBytes();
    int routed1 = store.routeShard(gid);
    int routed2 = store.routeShard(gid);
    assertThat(routed1).isEqualTo(routed2);

    int shardOfA = -1;
    int shardOfB = -1;
    byte[] gA = null;
    byte[] gB = null;
    for (int i = 0; i < 64 && (shardOfA == -1 || shardOfB == -1); i++) {
      byte[] candidate = ("g-routing-" + i).getBytes();
      int s = store.routeShard(candidate);
      if (shardOfA == -1) {
        shardOfA = s;
        gA = candidate;
      } else if (s != shardOfA) {
        shardOfB = s;
        gB = candidate;
      }
    }
    assertThat(gA).isNotNull();
    assertThat(gB).isNotNull();
    assertThat(shardOfA).isNotEqualTo(shardOfB);
    RaftStore rsA = store.newGroupStore(gA);
    RaftStore rsB = store.newGroupStore(gB);
    rsA.persistLogEntries(Collections.singletonList(
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[4]).build()));
    rsB.persistLogEntries(Collections.singletonList(
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[4]).build()));
    rsA.flush();
    rsB.flush();
    Path shardDirA = tmp.resolve(UnifiedRaftStore.SHARD_DIR_PREFIX + shardOfA);
    Path shardDirB = tmp.resolve(UnifiedRaftStore.SHARD_DIR_PREFIX + shardOfB);
    assertThat(java.nio.file.Files.isDirectory(shardDirA)).isTrue();
    assertThat(java.nio.file.Files.isDirectory(shardDirB)).isTrue();
  }
}
