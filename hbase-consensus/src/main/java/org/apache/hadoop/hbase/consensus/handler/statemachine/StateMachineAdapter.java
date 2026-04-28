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

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.handler.server.ConsensusServerMetrics;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * A single-group {@link StateMachine} that bridges the Raft core to a {@link ConsensusSpi}.
 * <p>
 * The adapter buffers committed application entries in a per-instance pending list and drains the
 * list to {@link ConsensusSpi#onCommit(Object, java.util.List)} on two boundaries:
 * <ol>
 * <li>End-of-batch: {@link #onApplyBatchEnd()} is invoked by the Raft core after each contiguous
 * batch of {@link #runOperation(long, Object)} calls inside a single executor task.</li>
 * <li>Flush boundary: a {@link FlushMarker} appears as a log-entry operation. The adapter drains
 * any preceding entries first, then notifies the SPI via
 * {@link ConsensusSpi#onFlushComplete(Object, FlushMarker)}.</li>
 * </ol>
 * Snapshot lifecycle is a single-chunk opaque-bytes round-trip: {@link #takeSnapshot} asks the SPI
 * for the application snapshot bytes and emits them as a single chunk; {@link #installSnapshot}
 * asserts a single chunk and forwards its bytes back to
 * {@link ConsensusSpi#installStateSnapshot(Object, long, byte[])}. The consensus core continues to
 * deliver consensus state (term, log entries, snapshot term/index, group-members view) through its
 * standard {@code AppendEntries} / {@code InstallSnapshot} wire path; the SPI never sees that
 * traffic directly.
 * <p>
 * Methods on this class are invoked from the per-group Raft executor thread that owns the
 * underlying {@code StateMachine}. Implementations therefore do not need to be thread-safe.
 */
@InterfaceAudience.Private
public final class StateMachineAdapter implements StateMachine {

  private final Object groupId;
  private final ConsensusSpi spi;
  @Nullable
  private final ConsensusServerMetrics metrics;
  private List<CommittedEntry> pending = new ArrayList<>();
  /**
   * Running byte total of the {@link #pending} payloads, refreshed in {@link #runOperation} and
   * reset in {@link #drainPending}. Avoids a second pass over the batch to compute
   * {@code commitBatchBytes} for the metrics sink.
   */
  private long pendingBytes = 0L;

  public StateMachineAdapter(@NonNull Object groupId, @NonNull ConsensusSpi spi) {
    this(groupId, spi, null);
  }

  /**
   * Builds an adapter that records SPI commit/flush/snapshot latencies into the supplied
   * {@link ConsensusServerMetrics}. Pass {@code null} to disable metrics recording (the
   * {@code (groupId, spi)} constructor delegates here with {@code null}).
   */
  public StateMachineAdapter(@NonNull Object groupId, @NonNull ConsensusSpi spi,
    @Nullable ConsensusServerMetrics metrics) {
    this.groupId = requireNonNull(groupId, "groupId");
    this.spi = requireNonNull(spi, "spi");
    this.metrics = metrics;
  }

  @Nullable
  @Override
  public Object runOperation(long commitIndex, @NonNull Object operation) {
    requireNonNull(operation, "operation");
    if (operation instanceof FlushMarker) {
      drainPending();
      long start = EnvironmentEdgeManager.currentTime();
      spi.onFlushComplete(groupId, (FlushMarker) operation);
      if (metrics != null) {
        metrics.updateFlushComplete(EnvironmentEdgeManager.currentTime() - start);
      }
      return null;
    }
    if (operation instanceof byte[]) {
      byte[] payload = (byte[]) operation;
      pending.add(new CommittedEntry(commitIndex, payload));
      pendingBytes += payload.length;
      return null;
    }
    throw new IllegalArgumentException(
      "StateMachineAdapter only accepts byte[] or FlushMarker operations; got "
        + operation.getClass().getName());
  }

  @Override
  public void onApplyBatchEnd() {
    drainPending();
  }

  @Override
  public void takeSnapshot(long commitIndex, @NonNull Consumer<Object> snapshotChunkConsumer) {
    requireNonNull(snapshotChunkConsumer, "snapshotChunkConsumer");
    long start = EnvironmentEdgeManager.currentTime();
    byte[] snapshot = spi.takeStateSnapshot(groupId, commitIndex);
    if (metrics != null) {
      metrics.updateTakeSnapshot(EnvironmentEdgeManager.currentTime() - start);
    }
    if (snapshot == null) {
      throw new IllegalStateException("ConsensusSpi.takeStateSnapshot returned null for groupId="
        + groupId + " commitIndex=" + commitIndex);
    }
    snapshotChunkConsumer.accept(snapshot);
  }

  @Override
  public void installSnapshot(long commitIndex, @NonNull List<Object> snapshotChunks) {
    requireNonNull(snapshotChunks, "snapshotChunks");
    if (snapshotChunks.size() != 1) {
      throw new IllegalArgumentException(
        "StateMachineAdapter expects exactly one snapshot chunk; got " + snapshotChunks.size());
    }
    Object chunk = snapshotChunks.get(0);
    if (!(chunk instanceof byte[])) {
      throw new IllegalArgumentException("StateMachineAdapter expects a byte[] snapshot chunk; got "
        + (chunk == null ? "null" : chunk.getClass().getName()));
    }
    pending.clear();
    pendingBytes = 0L;
    long start = EnvironmentEdgeManager.currentTime();
    spi.installStateSnapshot(groupId, commitIndex, (byte[]) chunk);
    if (metrics != null) {
      metrics.updateInstallSnapshot(EnvironmentEdgeManager.currentTime() - start);
    }
  }

  @Nullable
  @Override
  public Object getNewTermOperation() {
    return spi.getNewTermOperation();
  }

  private void drainPending() {
    if (pending.isEmpty()) {
      return;
    }
    int entryCount = pending.size();
    long batchBytes = pendingBytes;
    List<CommittedEntry> batch = pending;
    pending = new ArrayList<>();
    pendingBytes = 0L;
    long start = EnvironmentEdgeManager.currentTime();
    spi.onCommit(groupId, batch);
    if (metrics != null) {
      metrics.updateCommitApply(EnvironmentEdgeManager.currentTime() - start, entryCount,
        batchBytes);
    }
  }
}
