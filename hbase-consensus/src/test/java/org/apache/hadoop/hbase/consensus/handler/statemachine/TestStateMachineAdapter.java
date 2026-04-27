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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.hbase.consensus.handler.server.ConsensusServerMetrics;
import org.apache.hadoop.hbase.consensus.handler.server.ConsensusServerMetricsWrapper;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StateMachineAdapter} covering the basic delegation contract: byte[]
 * operations buffer until {@code onApplyBatchEnd}, snapshot take/install round-trips through the
 * single-chunk opaque-bytes path, and {@code getNewTermOperation} is delegated to the SPI.
 */
@Tag(SmallTests.TAG)
public class TestStateMachineAdapter extends TestBase {

  private static final Object GROUP_ID = "g1";

  private ConsensusServerMetrics metrics;

  @AfterEach
  public void closeMetrics() {
    if (metrics != null) {
      metrics.close();
      metrics = null;
    }
  }

  private static byte[] payload(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Builds a stand-alone {@link ConsensusServerMetrics} backed by a stub wrapper so unit tests can
   * exercise the metrics-recording path without a live {@code ConsensusServer}.
   */
  private static ConsensusServerMetrics newMetrics(String endpointId) {
    return new ConsensusServerMetrics(new ConsensusServerMetricsWrapper() {
      @Override
      public String getEndpointId() {
        return endpointId;
      }

      @Override
      public int getActiveGroups() {
        return 0;
      }

      @Override
      public int getMaxGroups() {
        return 0;
      }

      @Override
      public long getRestoredGroups() {
        return 0L;
      }

      @Override
      public String getLifecycleState() {
        return "TEST";
      }
    });
  }

  @Test
  public void testSingleEntryDrainsOnBatchEnd() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    assertThat(spi.commitBatches(GROUP_ID)).isEmpty();

    adapter.onApplyBatchEnd();
    assertThat(spi.commitBatches(GROUP_ID)).hasSize(1);
    assertThat(spi.committedEntries(GROUP_ID)).hasSize(1).first()
      .extracting(CommittedEntry::getCommitIndex, CommittedEntry::getPayload)
      .containsExactly(1L, payload("a"));
  }

  @Test
  public void testMultipleEntriesDeliveredAsSingleBatch() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    adapter.runOperation(2L, payload("b"));
    adapter.runOperation(3L, payload("c"));
    adapter.onApplyBatchEnd();

    assertThat(spi.commitBatches(GROUP_ID)).hasSize(1);
    List<CommittedEntry> batch = spi.commitBatches(GROUP_ID).get(0);
    assertThat(batch).hasSize(3);
    assertThat(batch.get(0).getCommitIndex()).isEqualTo(1L);
    assertThat(batch.get(1).getCommitIndex()).isEqualTo(2L);
    assertThat(batch.get(2).getCommitIndex()).isEqualTo(3L);
  }

  @Test
  public void testEmptyBatchEndIsNoop() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.onApplyBatchEnd();
    adapter.onApplyBatchEnd();

    assertThat(spi.commitBatches(GROUP_ID)).isEmpty();
    assertThat(spi.committedEntries(GROUP_ID)).isEmpty();
  }

  @Test
  public void testSuccessiveBatchesAreIndependent() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    adapter.onApplyBatchEnd();
    adapter.runOperation(2L, payload("b"));
    adapter.runOperation(3L, payload("c"));
    adapter.onApplyBatchEnd();

    assertThat(spi.commitBatches(GROUP_ID)).hasSize(2);
    assertThat(spi.commitBatches(GROUP_ID).get(0)).hasSize(1);
    assertThat(spi.commitBatches(GROUP_ID).get(1)).hasSize(2);
  }

  @Test
  public void testRejectsNonByteArrayOperation() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    assertThatThrownBy(() -> adapter.runOperation(1L, "not-bytes"))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte[] or FlushMarker");
  }

  @Test
  public void testTakeSnapshotProducesSingleOpaqueChunk() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    adapter.runOperation(2L, payload("b"));
    adapter.onApplyBatchEnd();

    List<Object> chunks = new ArrayList<>();
    adapter.takeSnapshot(2L, chunks::add);

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0)).isInstanceOf(byte[].class);
    assertThat(spi.snapshotsTaken(GROUP_ID)).containsExactly(2L);
  }

  @Test
  public void testInstallSnapshotRequiresExactlyOneChunk() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    assertThatThrownBy(() -> adapter.installSnapshot(5L, List.of()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("exactly one snapshot chunk");
    assertThatThrownBy(() -> adapter.installSnapshot(5L, List.of(new byte[0], new byte[0])))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("exactly one snapshot chunk");
  }

  @Test
  public void testInstallSnapshotRequiresByteArrayChunk() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    assertThatThrownBy(() -> adapter.installSnapshot(5L, List.of("not-bytes")))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte[] snapshot chunk");
  }

  @Test
  public void testInstallSnapshotDelegatesAndDiscardsPending() {
    InMemoryConsensusSpi srcSpi = new InMemoryConsensusSpi();
    StateMachineAdapter src = new StateMachineAdapter(GROUP_ID, srcSpi);
    src.runOperation(10L, payload("snap"));
    src.onApplyBatchEnd();
    List<Object> chunks = new ArrayList<>();
    src.takeSnapshot(10L, chunks::add);

    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));

    adapter.installSnapshot(10L, chunks);

    adapter.onApplyBatchEnd();

    assertThat(spi.snapshotsInstalled(GROUP_ID)).containsExactly(10L);
    assertThat(spi.commitBatches(GROUP_ID))
      .as("pending entries before install must be discarded; no onCommit fired").isEmpty();
  }

  @Test
  public void testGetNewTermOperationDelegates() {
    Object marker = new Object();
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi(marker);
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    assertThat(adapter.getNewTermOperation()).isSameAs(marker);
  }

  @Test
  public void testGetNewTermOperationNullByDefault() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    assertThat(adapter.getNewTermOperation()).isNull();
  }

  @Test
  public void testCommitApplyMetricsRecordedOnDrain() {
    metrics = newMetrics("sm-1");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi, metrics);

    adapter.runOperation(1L, payload("aa"));
    adapter.runOperation(2L, payload("bbb"));
    adapter.onApplyBatchEnd();

    assertThat(metrics.getCommitApplyTimer().getHistogram().getCount())
      .as("one batch -> one commitApply sample").isEqualTo(1);
    assertThat(metrics.getCommitBatchSizeHistogram().getCount()).isEqualTo(1);
    assertThat(metrics.getCommitBatchBytesHistogram().getCount()).isEqualTo(1);
    assertThat(metrics.getCommitBatchSizeHistogram().snapshot().getMax()).isEqualTo(2);
    assertThat(metrics.getCommitBatchBytesHistogram().snapshot().getMax()).isEqualTo(5);
    assertThat(metrics.getCommitBatchesCount()).isEqualTo(1);
    assertThat(metrics.getCommitEntriesCount()).isEqualTo(2);
    assertThat(metrics.getCommitBytesCount()).isEqualTo(5);
  }

  @Test
  public void testFlushCompleteMetricsRecorded() {
    metrics = newMetrics("sm-2");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi, metrics);

    adapter.runOperation(1L, payload("a"));
    adapter.runOperation(2L, new FlushMarker(7L, 0L, new byte[0]));
    adapter.onApplyBatchEnd();

    assertThat(metrics.getFlushCompleteTimer().getHistogram().getCount()).isEqualTo(1);
    assertThat(metrics.getFlushCompletesCount()).isEqualTo(1);
    assertThat(metrics.getCommitApplyTimer().getHistogram().getCount())
      .as("flush marker drains the pre-flush batch -> one commitApply sample").isEqualTo(1);
  }

  @Test
  public void testSnapshotMetricsRecorded() {
    metrics = newMetrics("sm-3");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi, metrics);

    adapter.runOperation(1L, payload("a"));
    adapter.onApplyBatchEnd();
    List<Object> chunks = new ArrayList<>();
    adapter.takeSnapshot(1L, chunks::add);

    assertThat(metrics.getTakeSnapshotTimer().getHistogram().getCount()).isEqualTo(1);
    assertThat(chunks).hasSize(1);

    InMemoryConsensusSpi destSpi = new InMemoryConsensusSpi();
    StateMachineAdapter dest = new StateMachineAdapter(GROUP_ID, destSpi, metrics);
    dest.installSnapshot(1L, chunks);

    assertThat(metrics.getInstallSnapshotTimer().getHistogram().getCount()).isEqualTo(1);
  }

  @Test
  public void testNoMetricsWhenWrapperOmitted() {
    metrics = newMetrics("sm-4");
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    adapter.onApplyBatchEnd();
    adapter.runOperation(2L, new FlushMarker(7L, 0L, new byte[0]));
    adapter.onApplyBatchEnd();

    assertThat(metrics.getCommitApplyTimer().getHistogram().getCount())
      .as("legacy ctor must not push into the metrics object").isZero();
    assertThat(metrics.getFlushCompleteTimer().getHistogram().getCount()).isZero();
  }
}
