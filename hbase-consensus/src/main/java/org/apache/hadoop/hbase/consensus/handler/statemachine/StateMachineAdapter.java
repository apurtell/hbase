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
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
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
  private final List<CommittedEntry> pending = new ArrayList<>();

  public StateMachineAdapter(@NonNull Object groupId, @NonNull ConsensusSpi spi) {
    this.groupId = requireNonNull(groupId, "groupId");
    this.spi = requireNonNull(spi, "spi");
  }

  @Nullable
  @Override
  public Object runOperation(long commitIndex, @NonNull Object operation) {
    requireNonNull(operation, "operation");
    if (operation instanceof FlushMarker) {
      drainPending();
      spi.onFlushComplete(groupId, (FlushMarker) operation);
      return null;
    }
    if (operation instanceof byte[]) {
      pending.add(new CommittedEntry(commitIndex, (byte[]) operation));
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
    byte[] snapshot = spi.takeStateSnapshot(groupId, commitIndex);
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
    spi.installStateSnapshot(groupId, commitIndex, (byte[]) chunk);
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
    List<CommittedEntry> batch = Collections.unmodifiableList(new ArrayList<>(pending));
    pending.clear();
    spi.onCommit(groupId, batch);
  }
}
