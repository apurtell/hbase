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
package org.apache.hadoop.hbase.consensus.handler.statemachine;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;

/**
 * In-memory test fixture for {@link ConsensusSpi}. Records every SPI invocation and exposes the
 * per-group state for test assertions.
 * <p>
 * The application "state" is the ordered sequence of every committed payload delivered through
 * {@link #onCommit(Object, List)}. Snapshot bytes are produced and consumed via a trivial
 * length-prefixed serialization, so a snapshot round-trip rebuilds an exact copy of the sender's
 * state.
 * <p>
 * Instances are safe to read from a test thread while the per-group Raft executor thread feeds SPI
 * events. All internal state is held under the instance monitor.
 */
public final class InMemoryConsensusSpi implements ConsensusSpi {

  /**
   * Per-group state accumulated by the fixture.
   */
  public static final class GroupState {
    private final List<CommittedEntry> committed = new ArrayList<>();
    private final List<List<CommittedEntry>> commitBatches = new ArrayList<>();
    private final List<FlushMarker> flushes = new ArrayList<>();
    private final List<LeaderElection> leaderElections = new ArrayList<>();
    private final List<RaftEndpoint> laggingFollowers = new ArrayList<>();
    private final List<Long> snapshotsTaken = new ArrayList<>();
    private final List<Long> snapshotsInstalled = new ArrayList<>();
    private int noLeaderEvents;
  }

  /** One leader-elected event captured by the fixture. */
  public static final class LeaderElection {
    public final int term;
    @NonNull
    public final RaftEndpoint leader;

    public LeaderElection(int term, @NonNull RaftEndpoint leader) {
      this.term = term;
      this.leader = leader;
    }
  }

  private final Map<Object, GroupState> states = new LinkedHashMap<>();
  @Nullable
  private final Object newTermOperation;
  private final AtomicInteger takeSnapshotCount = new AtomicInteger();
  private final AtomicInteger installSnapshotCount = new AtomicInteger();

  public InMemoryConsensusSpi() {
    this(null);
  }

  public InMemoryConsensusSpi(@Nullable Object newTermOperation) {
    this.newTermOperation = newTermOperation;
  }

  private synchronized GroupState state(Object groupId) {
    return states.computeIfAbsent(groupId, k -> new GroupState());
  }

  @Override
  public synchronized void onCommit(@NonNull Object groupId, @NonNull List<CommittedEntry> batch) {
    if (batch.isEmpty()) {
      throw new IllegalArgumentException("onCommit batch must be non-empty");
    }
    GroupState s = state(groupId);
    s.commitBatches.add(Collections.unmodifiableList(new ArrayList<>(batch)));
    s.committed.addAll(batch);
  }

  @Override
  public synchronized void onFlushComplete(@NonNull Object groupId, @NonNull FlushMarker marker) {
    state(groupId).flushes.add(marker);
  }

  @Override
  public synchronized void onLeaderElected(@NonNull Object groupId, int term,
    @NonNull RaftEndpoint leader) {
    state(groupId).leaderElections.add(new LeaderElection(term, leader));
  }

  @Override
  public synchronized void onNoLeader(@NonNull Object groupId) {
    state(groupId).noLeaderEvents++;
  }

  @Override
  public synchronized void onFollowerLagging(@NonNull Object groupId, @NonNull RaftEndpoint peer) {
    state(groupId).laggingFollowers.add(peer);
  }

  @NonNull
  @Override
  public synchronized byte[] takeStateSnapshot(@NonNull Object groupId, long commitIndex) {
    takeSnapshotCount.incrementAndGet();
    GroupState s = state(groupId);
    s.snapshotsTaken.add(commitIndex);
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(baos)) {
      out.writeInt(s.committed.size());
      for (CommittedEntry e : s.committed) {
        out.writeLong(e.getCommitIndex());
        byte[] payload = e.getPayload();
        out.writeInt(payload.length);
        out.write(payload);
      }
      out.flush();
      return baos.toByteArray();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  @Override
  public synchronized void installStateSnapshot(@NonNull Object groupId, long commitIndex,
    @NonNull byte[] snapshot) {
    installSnapshotCount.incrementAndGet();
    GroupState s = state(groupId);
    s.snapshotsInstalled.add(commitIndex);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot))) {
      int n = in.readInt();
      List<CommittedEntry> rebuilt = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        long ci = in.readLong();
        int len = in.readInt();
        byte[] payload = new byte[len];
        in.readFully(payload);
        rebuilt.add(new CommittedEntry(ci, payload));
      }
      s.committed.clear();
      s.committed.addAll(rebuilt);
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  @Nullable
  @Override
  public Object getNewTermOperation() {
    return newTermOperation;
  }

  /**
   * Returns the ordered list of every {@link CommittedEntry} delivered for the given group via
   * {@link #onCommit(Object, List)} (replaced by snapshot installs).
   */
  public synchronized List<CommittedEntry> committedEntries(@NonNull Object groupId) {
    return Collections.unmodifiableList(new ArrayList<>(state(groupId).committed));
  }

  /** Returns the per-batch grouping of committed entries delivered for the given group. */
  public synchronized List<List<CommittedEntry>> commitBatches(@NonNull Object groupId) {
    return Collections.unmodifiableList(new ArrayList<>(state(groupId).commitBatches));
  }

  /** Returns the ordered list of {@link FlushMarker}s observed for the given group. */
  public synchronized List<FlushMarker> flushes(@NonNull Object groupId) {
    return Collections.unmodifiableList(new ArrayList<>(state(groupId).flushes));
  }

  /** Returns every {@link LeaderElection} event recorded for the given group. */
  public synchronized List<LeaderElection> leaderElections(@NonNull Object groupId) {
    return Collections.unmodifiableList(new ArrayList<>(state(groupId).leaderElections));
  }

  /** Returns the number of {@link #onNoLeader(Object)} events recorded for the given group. */
  public synchronized int noLeaderEvents(@NonNull Object groupId) {
    return state(groupId).noLeaderEvents;
  }

  /** Returns the ordered list of follower endpoints reported lagging for the given group. */
  public synchronized List<RaftEndpoint> laggingFollowers(@NonNull Object groupId) {
    return Collections.unmodifiableList(new ArrayList<>(state(groupId).laggingFollowers));
  }

  /**
   * Returns the commit indices at which {@link #takeStateSnapshot(Object, long)} was invoked for
   * the given group.
   */
  public synchronized List<Long> snapshotsTaken(@NonNull Object groupId) {
    return Collections.unmodifiableList(new ArrayList<>(state(groupId).snapshotsTaken));
  }

  /**
   * Returns the commit indices at which {@link #installStateSnapshot(Object, long, byte[])} was
   * invoked for the given group.
   */
  public synchronized List<Long> snapshotsInstalled(@NonNull Object groupId) {
    return Collections.unmodifiableList(new ArrayList<>(state(groupId).snapshotsInstalled));
  }

  /**
   * Returns the total number of {@link #takeStateSnapshot(Object, long)} invocations across all
   * groups.
   */
  public int takeSnapshotCount() {
    return takeSnapshotCount.get();
  }

  /**
   * Returns the total number of {@link #installStateSnapshot(Object, long, byte[])} invocations
   * across all groups.
   */
  public int installSnapshotCount() {
    return installSnapshotCount.get();
  }
}
