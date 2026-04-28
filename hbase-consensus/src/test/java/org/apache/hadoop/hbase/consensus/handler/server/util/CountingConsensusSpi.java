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
package org.apache.hadoop.hbase.consensus.handler.server.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.hbase.consensus.handler.statemachine.CommittedEntry;
import org.apache.hadoop.hbase.consensus.handler.statemachine.ConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.statemachine.FlushMarker;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

/**
 * Lightweight {@link ConsensusSpi} fixture for the scalability harness and unit tests. Counts
 * commit batches, commit entries, and leader-elected events per group, with a single per-group
 * {@link CountDownLatch} to let the harness wait for first-leader-elected before driving load.
 * <p>
 * Optionally wraps a {@link ConsensusSpi} delegate (e.g. an {@code InMemoryConsensusSpi}) to layer
 * counting on top of richer per-group state.
 */
public final class CountingConsensusSpi implements ConsensusSpi {

  private static final byte[] EMPTY_SNAPSHOT = new byte[0];

  /** Counters tracked per-group. */
  public static final class Stats {
    private final AtomicLong commitBatches = new AtomicLong();
    private final AtomicLong commitEntries = new AtomicLong();
    private final AtomicLong commitBytes = new AtomicLong();
    private final AtomicLong flushes = new AtomicLong();
    private final AtomicLong leaderElections = new AtomicLong();
    private final AtomicLong noLeaderEvents = new AtomicLong();
    private final CountDownLatch firstLeaderElected = new CountDownLatch(1);
    /**
     * Wall-clock timestamp (ms) of the first time {@link ConsensusSpi#onLeaderElected} fired for
     * this group. Captured under {@link AtomicLong#compareAndSet} so racing callbacks across
     * servers cannot overwrite the earliest-observed value. Sentinel {@code 0L} means "never".
     */
    private final AtomicLong firstLeaderElectedTimestampMs = new AtomicLong(0L);

    public long getCommitBatches() {
      return commitBatches.get();
    }

    public long getCommitEntries() {
      return commitEntries.get();
    }

    public long getCommitBytes() {
      return commitBytes.get();
    }

    public long getFlushes() {
      return flushes.get();
    }

    public long getLeaderElections() {
      return leaderElections.get();
    }

    public long getNoLeaderEvents() {
      return noLeaderEvents.get();
    }

    /** Returns true if and only if {@link ConsensusSpi#onLeaderElected} has fired at least once. */
    public boolean isLeaderElected() {
      return firstLeaderElected.getCount() == 0;
    }

    /**
     * Blocks the calling thread until {@link ConsensusSpi#onLeaderElected} has fired at least once,
     * or the deadline elapses. Returns true if the latch fired.
     */
    public boolean awaitLeaderElected(long timeout, @NonNull TimeUnit unit)
      throws InterruptedException {
      return firstLeaderElected.await(timeout, unit);
    }

    /**
     * @return the wall-clock millis at which the first {@link ConsensusSpi#onLeaderElected} fired
     *         for this group across any server, or {@code 0L} if no leader has been elected yet.
     */
    public long getFirstLeaderElectedTimestampMs() {
      return firstLeaderElectedTimestampMs.get();
    }
  }

  // Keyed on the textual form of the group id so a test that calls {@code stats("g1")} and a
  // production callback driven with a {@code GroupId.of("g1")} resolve to the same Stats object.
  private final ConcurrentMap<String, Stats> perGroup = new ConcurrentHashMap<>();
  @Nullable
  private final ConsensusSpi delegate;
  @Nullable
  private final Object newTermOperation;
  private volatile boolean throwOnCommit;

  public CountingConsensusSpi() {
    this(/* delegate */ null, /* newTermOperation */ null);
  }

  public CountingConsensusSpi(@Nullable Object newTermOperation) {
    this(/* delegate */ null, newTermOperation);
  }

  public CountingConsensusSpi(@Nullable ConsensusSpi delegate, @Nullable Object newTermOperation) {
    this.delegate = delegate;
    this.newTermOperation = newTermOperation;
  }

  /**
   * Toggles a flag that makes every {@link #onCommit} throw a {@link RuntimeException}. Used by the
   * isolation test to verify a misbehaving group does not corrupt other groups' commit flow.
   */
  public void setThrowOnCommit(boolean throwOnCommit) {
    this.throwOnCommit = throwOnCommit;
  }

  @NonNull
  public Stats stats(@NonNull Object groupId) {
    return perGroup.computeIfAbsent(String.valueOf(groupId), k -> new Stats());
  }

  @Override
  public void onCommit(@NonNull Object groupId, @NonNull List<CommittedEntry> batch) {
    Stats s = stats(groupId);
    s.commitBatches.incrementAndGet();
    s.commitEntries.addAndGet(batch.size());
    long bytes = 0L;
    for (CommittedEntry e : batch) {
      bytes += e.getPayload().length;
    }
    s.commitBytes.addAndGet(bytes);
    if (delegate != null) {
      delegate.onCommit(groupId, batch);
    }
    if (throwOnCommit) {
      throw new RuntimeException("CountingConsensusSpi.onCommit(" + groupId + ") faulted by test");
    }
  }

  @Override
  public void onFlushComplete(@NonNull Object groupId, @NonNull FlushMarker marker) {
    stats(groupId).flushes.incrementAndGet();
    if (delegate != null) {
      delegate.onFlushComplete(groupId, marker);
    }
  }

  @Override
  public void onLeaderElected(@NonNull Object groupId, int term, @NonNull RaftEndpoint leader) {
    Stats s = stats(groupId);
    s.leaderElections.incrementAndGet();
    // Capture the very first observation atomically so racing callbacks across servers do not
    // overwrite the earliest-observed timestamp.
    s.firstLeaderElectedTimestampMs.compareAndSet(0L, EnvironmentEdgeManager.currentTime());
    s.firstLeaderElected.countDown();
    if (delegate != null) {
      delegate.onLeaderElected(groupId, term, leader);
    }
  }

  @Override
  public void onNoLeader(@NonNull Object groupId) {
    stats(groupId).noLeaderEvents.incrementAndGet();
    if (delegate != null) {
      delegate.onNoLeader(groupId);
    }
  }

  @Override
  public void onFollowerLagging(@NonNull Object groupId, @NonNull RaftEndpoint peer) {
    if (delegate != null) {
      delegate.onFollowerLagging(groupId, peer);
    }
  }

  @NonNull
  @Override
  public byte[] takeStateSnapshot(@NonNull Object groupId, long commitIndex) {
    if (delegate != null) {
      return delegate.takeStateSnapshot(groupId, commitIndex);
    }
    return EMPTY_SNAPSHOT;
  }

  @Override
  public void installStateSnapshot(@NonNull Object groupId, long commitIndex,
    @NonNull byte[] snapshot) {
    if (delegate != null) {
      delegate.installStateSnapshot(groupId, commitIndex, snapshot);
    }
  }

  @Nullable
  @Override
  public Object getNewTermOperation() {
    if (delegate != null) {
      return delegate.getNewTermOperation();
    }
    return newTermOperation;
  }
}
