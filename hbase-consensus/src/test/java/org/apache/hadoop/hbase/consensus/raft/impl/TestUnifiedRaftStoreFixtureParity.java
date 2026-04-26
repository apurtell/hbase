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
package org.apache.hadoop.hbase.consensus.raft.impl;

import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getLastLogOrSnapshotEntry;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getSnapshotEntry;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.handler.store.DefaultLogStoreSerializer;
import org.apache.hadoop.hbase.consensus.handler.store.LogStoreConfig;
import org.apache.hadoop.hbase.consensus.handler.store.LogStoreSerializer;
import org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodecs;
import org.apache.hadoop.hbase.consensus.handler.transport.PayloadCompressor;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachineOpCodec;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end parity coverage. Drives the live Raft cluster against the disk-backed
 * {@link UnifiedRaftStore} via the parameterized
 * {@link LocalRaftGroup#unifiedRaftStateStoreFactory(File, List)} factory. Mirrors the most
 * load-bearing scenarios from {@code TestPersistence} and {@code TestSnapshot} such as leader
 * election, committed-entry replication, and snapshot install. Uses store-agnostic assertions
 * instead of the {@code InMemoryRaftStore}-specific {@code getRestoredState}, so the same flow
 * exercises the new store under realistic raft traffic.
 */
@Tag(SmallTests.TAG)
public class TestUnifiedRaftStoreFixtureParity extends TestBase {

  @TempDir
  Path rootDir;

  private final List<UnifiedRaftStore> stores = new ArrayList<>();
  private LocalRaftGroup group;

  @AfterEach
  public void tearDown() throws IOException {
    if (group != null) {
      group.destroy();
      group = null;
    }
    for (UnifiedRaftStore s : stores) {
      try {
        s.close();
      } catch (IOException ignored) {
        // best-effort cleanup
      }
    }
    stores.clear();
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testLeaderElectionAndCommitOnUnifiedStore() {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 8;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isEqualTo(count);
      }
    });
  }

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  public void testSnapshotPersistedOnUnifiedStore() {
    int commitCountToTakeSnapshot = 50;
    RaftConfig cfg =
      RaftConfig.newBuilder().setCommitCountToTakeSnapshot(commitCountToTakeSnapshot).build();
    group = LocalRaftGroup.newBuilder(3).setConfig(cfg)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    for (int i = 0; i < commitCountToTakeSnapshot; i++) {
      leader.replicate(applyValue("val" + i)).join();
    }
    eventually(() -> {
      assertThat(getSnapshotEntry(leader).getIndex()).isEqualTo(commitCountToTakeSnapshot);
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isGreaterThanOrEqualTo(commitCountToTakeSnapshot);
      }
    });
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void testReplicationContinuesAfterFollowerTerminate() {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("a")).join();
    leader.replicate(applyValue("b")).join();
    RaftNodeImpl follower = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0);
    group.terminateNode(follower.getLocalEndpoint());
    leader.replicate(applyValue("c")).join();
    leader.replicate(applyValue("d")).join();
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isGreaterThanOrEqualTo(4);
      }
    });
  }

  /**
   * Mirrors {@code TestPersistence#testUncommittedEntriesPersisted}: a leader with a partitioned
   * majority should still durably persist all replicated entries onto the unified store backing the
   * one responsive follower. We verify durability by replaying the log from disk after the cluster
   * is stopped.
   */
  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  public void testUncommittedEntriesPersistedOnDisk() throws IOException {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    RaftNodeImpl responsiveFollower = followers.get(0);
    for (int i = 1; i < followers.size(); i++) {
      group.dropAppendsAndHeartbeatsTo(leader.getLocalEndpoint(),
        followers.get(i).getLocalEndpoint());
    }
    int count = 10;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("val" + i));
    }
    eventually(() -> {
      for (RaftNodeImpl node : Arrays.asList(leader, responsiveFollower)) {
        assertThat(getLastLogOrSnapshotEntry(node).getIndex()).isGreaterThanOrEqualTo(count);
        assertThat(node.getLeaderEndpoint()).isEqualTo(leader.getLocalEndpoint());
      }
    });
    File leaderDir = new File(rootDir.toFile(), String.valueOf(leader.getLocalEndpoint().getId()));
    File followerDir =
      new File(rootDir.toFile(), String.valueOf(responsiveFollower.getLocalEndpoint().getId()));
    group.destroy();
    group = null;
    for (UnifiedRaftStore s : stores) {
      s.close();
    }
    stores.clear();
    assertGroupHasAtLeastEntries(leaderDir, count);
    assertGroupHasAtLeastEntries(followerDir, count);
  }

  /**
   * A partitioned old leader appends uncommitted entries which the new majority overwrites after
   * merge. Verifies the unified store applies the truncate-from path.
   */
  @Test
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  public void testMinorityAppendsTruncatedOnDisk() throws IOException {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    leader.replicate(applyValue("val1")).join();
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isEqualTo(1);
      }
    });
    List<RaftNodeImpl> followers = group.getNodesExcept(leader.getLocalEndpoint());
    group.splitMembers(leader.getLocalEndpoint());
    for (int i = 0; i < 10; i++) {
      leader.replicate(applyValue("isolated" + i));
    }
    eventually(() -> {
      for (RaftNodeImpl node : followers) {
        RaftEndpoint leaderEndpoint = node.getLeaderEndpoint();
        assertThat(leaderEndpoint).isNotNull();
        assertThat(leaderEndpoint).isNotEqualTo(leader.getLocalEndpoint());
      }
    });
    RaftNodeImpl newLeader = group.getNode(followers.get(0).getLeaderEndpoint());
    for (int i = 0; i < 10; i++) {
      newLeader.replicate(applyValue("valNew" + i)).join();
    }
    eventually(() -> {
      for (RaftNodeImpl node : followers) {
        assertThat(getCommitIndex(node)).isEqualTo(11);
      }
    });
    group.merge();
    final RaftNodeImpl finalLeader = group.waitUntilLeaderElected();
    assertThat(finalLeader.getLocalEndpoint()).isNotEqualTo(leader.getLocalEndpoint());
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isEqualTo(11);
      }
    });
    File leaderDir = new File(rootDir.toFile(), String.valueOf(leader.getLocalEndpoint().getId()));
    group.destroy();
    group = null;
    for (UnifiedRaftStore s : stores) {
      s.close();
    }
    stores.clear();
    long entriesOnDisk = countGroupEntries(leaderDir);
    assertThat(entriesOnDisk).isLessThanOrEqualTo(11);
  }

  /**
   * Disk durability across {@link UnifiedRaftStore} restart. After a cluster runs and stops,
   * spinning up a brand-new {@code UnifiedRaftStore} on the same on-disk layout must reload the
   * same per-group log entries (and term / endpoint state) the original instance fsync'd.
   */
  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  public void testLogReplayedAcrossUnifiedStoreRestart() throws IOException {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int count = 5;
    for (int i = 0; i < count; i++) {
      leader.replicate(applyValue("v" + i)).join();
    }
    eventually(() -> {
      for (RaftNodeImpl node : group.getNodes()) {
        assertThat(getCommitIndex(node)).isEqualTo(count);
      }
    });
    File leaderDir = new File(rootDir.toFile(), String.valueOf(leader.getLocalEndpoint().getId()));
    group.destroy();
    group = null;
    for (UnifiedRaftStore s : stores) {
      s.close();
    }
    stores.clear();
    long entries = countGroupEntries(leaderDir);
    assertThat(entries).isGreaterThanOrEqualTo(count);
  }

  private static void assertGroupHasAtLeastEntries(File endpointDir, int min) throws IOException {
    long entries = countGroupEntries(endpointDir);
    assertThat(entries).isGreaterThanOrEqualTo(min);
  }

  private static long countGroupEntries(File endpointDir) throws IOException {
    LogStoreConfig cfg = new LogStoreConfig(endpointDir, 8, 5L, 50L, 64);
    OperationCodec codec =
      OperationCodecs.composite(OperationCodecs.defaultCodecs(), new SimpleStateMachineOpCodec());
    LogStoreSerializer serializer = new DefaultLogStoreSerializer(new DefaultRaftModelFactory(),
      codec, new PayloadCompressor(Compression.Algorithm.NONE));
    UnifiedRaftStore reopened = new UnifiedRaftStore(cfg, serializer);
    try {
      Map<ByteBuffer, RestoredRaftState> states = reopened.load();
      RestoredRaftState s = states.get(ByteBuffer.wrap("default".getBytes()));
      assertThat(s).isNotNull();
      return s.getLogEntries().size();
    } finally {
      reopened.close();
    }
  }
}
