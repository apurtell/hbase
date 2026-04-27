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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
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
  public void testEmptyDirLoadCreatesSegment() throws IOException {
    store = newStore();
    assertThat(store.load()).isEmpty();
    assertThat(store.currentSegmentForTesting()).isNotNull();
    assertThat(store.segmentIndexForTesting().size()).isEqualTo(1);
    assertThat(store.currentSegmentForTesting().currentSize())
      .isGreaterThan((long) LogRecord.PROLOGUE_BYTES);
  }

  @Test
  public void testFlushBarrierSynchronousProgress() throws IOException {
    store = newStore();
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    LogEntry e1 =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[8]).build();
    g.persistLogEntries(Collections.singletonList(e1));
    g.flush();
    long sizeAfterFirst = store.currentSegmentForTesting().currentSize();
    assertThat(sizeAfterFirst).isGreaterThan((long) LogRecord.PROLOGUE_BYTES);

    LogEntry e2 =
      factory.createLogEntryBuilder().setIndex(2L).setTerm(1).setOperation(new byte[8]).build();
    g.persistLogEntries(Collections.singletonList(e2));
    g.flush();
    assertThat(store.currentSegmentForTesting().currentSize()).isGreaterThan(sizeAfterFirst);
  }

  /**
   * Fsync-before-commit contract: {@link RaftStore#flush()} must issue a {@code force(false)} on
   * the active segment before returning. Without this, a follower's
   * {@code AppendEntriesSuccessResponse} would acknowledge a page-cache-only frame and the leader's
   * commit-index advancement would count non-durable acks.
   */
  @Test
  public void testFlushBarrierFsyncsActiveSegment() throws IOException {
    store = newStore();
    store.load();
    store.awaitMailboxDrainedForTesting();
    CountingLogSegment counting = new CountingLogSegment(store.currentSegmentForTesting());
    store.replaceCurrentSegmentForTesting(counting);

    RaftStore g = store.newGroupStore("g".getBytes());
    LogEntry e =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[8]).build();
    g.persistLogEntries(Collections.singletonList(e));
    long before = counting.forceCount.get();
    g.flush();
    assertThat(counting.forceCount.get()).isGreaterThan(before);

    // A subsequent bare flush() (no new entries) must still cross another force boundary.
    long beforeBare = counting.forceCount.get();
    g.flush();
    assertThat(counting.forceCount.get()).isGreaterThan(beforeBare);
  }

  @Test
  public void testSyncFsyncFlushTermDurable() throws IOException {
    store = newStore();
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    g.persistAndFlushTerm(
      factory.createRaftTermPersistentStateBuilder().setTerm(3).setVotedFor(a).build());
    assertThat(store.currentSegmentForTesting().currentSize())
      .isGreaterThan((long) LogRecord.PROLOGUE_BYTES);
  }

  @Test
  public void testTwoGroupsMultiplexed() throws IOException {
    store = newStore();
    store.load();
    RaftStore g1 = store.newGroupStore("g1".getBytes());
    RaftStore g2 = store.newGroupStore("g2".getBytes());
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
    // Both groups multiplexed onto the same segment.
    assertThat(store.segmentIndexForTesting().size()).isEqualTo(1);
  }

  @Test
  public void testFlushIdempotent() throws IOException {
    store = newStore();
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    g.flush();
    g.flush();
    g.flush();
  }

  @Test
  public void testCloseDrainsPendingWrites() throws IOException {
    store = newStore();
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
    store = newStore();
    store.load();
    store.close();
    assertThatThrownBy(() -> store.newGroupStore("g".getBytes())).isInstanceOf(IOException.class);
    store = null;
  }

  @Test
  public void testPersistAfterCloseRejected() throws IOException {
    store = newStore();
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
    store = newStore();
    store.load();
    assertThatThrownBy(() -> store.load()).isInstanceOf(IllegalStateException.class);
  }

  /**
   * Test-only {@link LogSegment} subclass used to observe the fsync-before-commit contract by
   * counting {@link LogSegment#force(boolean)} calls. Wraps an existing segment by sharing its
   * {@code FileChannel}, path, id, and current size so the writer thread can route through this
   * instance unchanged.
   */
  private static final class CountingLogSegment extends LogSegment {
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
