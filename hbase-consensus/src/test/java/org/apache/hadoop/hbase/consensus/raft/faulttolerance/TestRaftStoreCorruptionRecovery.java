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
package org.apache.hadoop.hbase.consensus.raft.faulttolerance;

import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.TEST_RAFT_CONFIG;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getCommitIndex;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.hadoop.hbase.consensus.handler.store.LogRecord;
import org.apache.hadoop.hbase.consensus.handler.store.UnifiedRaftStore;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage that the documented recovery story works through a real Raft cluster: after a
 * node's on-disk {@link UnifiedRaftStore} state is damaged in a realistic way (torn tail bytes from
 * a partial last write, a mid-stream CRC mismatch from a flipped bit, or a zero-filled tail block
 * from a filesystem-level fault) and the node is restored, {@link UnifiedRaftStore#load()}
 * truncates back to a safe boundary and the leader-side Raft layer catches the node up via
 * {@code AppendEntriesRequest}.
 * <p>
 * The first two scenarios are tested as a single-follower restart. The third (zero-filled tail
 * block, modeling typical filesystem corruption such as ext4 zero-fill on a faulty block) is tested
 * as a full-cluster cold start: power-fail style termination of all three nodes followed by a fresh
 * start of each, with one node's tail block zero-filled while all are down. Because the leader's
 * in-memory follower state is rebuilt from scratch on cold start, this exercises the protocol's
 * normal log-tail-discovery path against a peer whose persistent log shrank under it &mdash; the
 * case a healthy cluster is expected to recover from. A disk wipe under a running process is
 * intentionally not modeled here: it violates the persistent follower-state assumptions Raft makes,
 * and crashing the process is a reasonable response.
 */
@Tag(SmallTests.TAG)
public class TestRaftStoreCorruptionRecovery extends TestBase {

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

  /**
   * Drop the last 8 bytes of the active segment after a clean shutdown. {@code load()} sees a torn
   * frame, truncates back to the last good record, and the leader closes the resulting small gap
   * via {@code AppendEntriesRequest}.
   */
  @Test
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  public void testAppendEntriesCatchupAfterTornTail() throws IOException {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0);
    int initial = 8;
    for (int i = 0; i < initial; i++) {
      leader.replicate(applyValue("v" + i)).join();
    }
    eventually(() -> assertThat(getCommitIndex(follower)).isEqualTo(initial));
    RaftEndpoint followerEndpoint = follower.getLocalEndpoint();
    File followerDir = endpointDir(followerEndpoint);
    closeAndTerminate(follower);

    Path seg = onlySegmentIn(followerDir);
    long size = Files.size(seg);
    try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
      ch.truncate(size - 8L);
    }

    RaftNodeImpl restored =
      group.restoreNodeFromUnifiedStore(followerEndpoint, rootDir.toFile(), stores);
    leader.replicate(applyValue("post-recovery")).join();
    final int expectedAfter = initial + 1;
    eventually(() -> assertThat(getCommitIndex(restored)).isEqualTo(expectedAfter));
    eventually(() -> assertThat(getCommitIndex(restored)).isEqualTo(getCommitIndex(leader)));
  }

  /**
   * Flip a byte mid-stream to break a frame's CRC; reload should stop at the bad frame, the
   * follower's persistent log shrinks, and the leader catches up via {@code AppendEntries}.
   */
  @Test
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  public void testAppendEntriesCatchupAfterCrcCorruption() throws IOException {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    RaftNodeImpl follower = group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0);
    int initial = 12;
    for (int i = 0; i < initial; i++) {
      leader.replicate(applyValue("v" + i)).join();
    }
    eventually(() -> assertThat(getCommitIndex(follower)).isEqualTo(initial));
    RaftEndpoint followerEndpoint = follower.getLocalEndpoint();
    File followerDir = endpointDir(followerEndpoint);
    closeAndTerminate(follower);

    Path seg = onlySegmentIn(followerDir);
    long size = Files.size(seg);
    flipByteAt(seg, size - 24L);

    RaftNodeImpl restored =
      group.restoreNodeFromUnifiedStore(followerEndpoint, rootDir.toFile(), stores);
    leader.replicate(applyValue("post-recovery")).join();
    final int expectedAfter = initial + 1;
    eventually(() -> assertThat(getCommitIndex(restored)).isEqualTo(expectedAfter));
    eventually(() -> assertThat(getCommitIndex(restored)).isEqualTo(getCommitIndex(leader)));
  }

  /**
   * Models a power-failure that takes the whole cluster down at once and leaves one node's active
   * segment with a zero-filled trailing filesystem block (a common ext4-style outcome for a
   * partially-written block on a faulty device). After all three nodes cold-start from disk,
   * {@code load()} on the damaged node detects the run of {@code 0x00} bytes as a bad frame,
   * truncates back to the last good record, and the freshly elected leader closes the resulting
   * tail gap via {@code AppendEntriesRequest}.
   */
  @Test
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  public void testRecoveryAfterClusterRestartWithZeroedTailBlock() throws IOException {
    group = LocalRaftGroup.newBuilder(3).setConfig(TEST_RAFT_CONFIG)
      .setRaftStoreFactory(LocalRaftGroup.unifiedRaftStateStoreFactory(rootDir.toFile(), stores))
      .start();
    RaftNodeImpl leader = group.waitUntilLeaderElected();
    int initial = 32;
    for (int i = 0; i < initial; i++) {
      leader.replicate(applyValue("v" + i)).join();
    }
    List<RaftEndpoint> endpoints =
      group.getNodes().stream().map(RaftNodeImpl::getLocalEndpoint).collect(Collectors.toList());
    for (RaftEndpoint ep : endpoints) {
      RaftNodeImpl n = group.<RaftNodeImpl> getNode(ep);
      eventually(() -> assertThat(getCommitIndex(n)).isEqualTo(initial));
    }
    RaftEndpoint corruptTarget =
      group.<RaftNodeImpl> getNodesExcept(leader.getLocalEndpoint()).get(0).getLocalEndpoint();

    // Power-failure: terminate every node before touching the disk.
    for (RaftEndpoint ep : new ArrayList<>(endpoints)) {
      closeAndTerminate(group.<RaftNodeImpl> getNode(ep));
    }

    // Zero-fill the trailing filesystem block of one node's segment, leaving the prologue
    // and earlier frames intact so load() can recover the surviving prefix.
    Path seg = onlySegmentIn(endpointDir(corruptTarget));
    zeroFillTrailingBlock(seg, 1024);

    Map<RaftEndpoint, RaftNodeImpl> restored = new HashMap<>();
    for (RaftEndpoint ep : endpoints) {
      restored.put(ep, group.restoreNodeFromUnifiedStore(ep, rootDir.toFile(), stores));
    }
    RaftNodeImpl newLeader = group.waitUntilLeaderElected();
    newLeader.replicate(applyValue("post-recovery")).join();
    long expected = getCommitIndex(newLeader);
    for (RaftEndpoint ep : endpoints) {
      RaftNodeImpl n = restored.get(ep);
      eventually(() -> assertThat(getCommitIndex(n)).isEqualTo(expected));
    }
  }

  private File endpointDir(RaftEndpoint endpoint) {
    return new File(rootDir.toFile(), String.valueOf(endpoint.getId()));
  }

  /**
   * Terminates the node and closes its parent {@link UnifiedRaftStore} so the on-disk segment file
   * can be safely mutated. Removes the closed store from the test's tracking list to avoid a
   * double-close in {@link #tearDown()}.
   */
  private void closeAndTerminate(RaftNodeImpl node) throws IOException {
    File dir = endpointDir(node.getLocalEndpoint());
    group.terminateNode(node.getLocalEndpoint());
    UnifiedRaftStore matching = null;
    for (UnifiedRaftStore s : stores) {
      if (s.getConfig().getLogDir().equals(dir)) {
        matching = s;
        break;
      }
    }
    if (matching != null) {
      matching.close();
      stores.remove(matching);
    }
  }

  /** On-disk file prefix used by {@code LogSegment} (mirrored here as test access). */
  private static final String SEGMENT_FILE_PREFIX = "raft-";

  private static Path onlySegmentIn(File dir) throws IOException {
    try (Stream<Path> s = Files.list(dir.toPath())) {
      return s.filter(p -> p.getFileName().toString().startsWith(SEGMENT_FILE_PREFIX)).findFirst()
        .orElseThrow();
    }
  }

  private static void flipByteAt(Path file, long offset) throws IOException {
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

  /**
   * Overwrites the trailing {@code blockSize} bytes of {@code file} with {@code 0x00}, modeling a
   * filesystem that zero-fills a partially-written block on a faulty device. The prologue (first
   * {@link LogRecord#PROLOGUE_BYTES} bytes) is always preserved so that
   * {@link UnifiedRaftStore#load()} can still validate the file and recover the surviving prefix
   * frames.
   */
  private static void zeroFillTrailingBlock(Path file, int blockSize) throws IOException {
    long size = Files.size(file);
    long offset = Math.max(LogRecord.PROLOGUE_BYTES, size - blockSize);
    int len = Math.toIntExact(size - offset);
    if (len <= 0) {
      return;
    }
    ByteBuffer zeros = ByteBuffer.allocate(len);
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
      ch.write(zeros, offset);
    }
  }
}
