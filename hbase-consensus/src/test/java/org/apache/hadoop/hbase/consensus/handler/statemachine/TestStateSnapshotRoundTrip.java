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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies the single-chunk opaque-bytes snapshot round-trip wired by {@link StateMachineAdapter}:
 * <ul>
 * <li>{@code takeSnapshot} produces exactly one chunk via the SPI's {@code takeStateSnapshot}.</li>
 * <li>The chunk is forwarded to a destination adapter whose {@code installSnapshot} reconstructs
 * SPI state identical to the source's at the snapshot index.</li>
 * <li>The consensus core sees only opaque bytes. No consensus state is multiplexed onto this
 * payload.</li>
 * </ul>
 */
@Tag(SmallTests.TAG)
public class TestStateSnapshotRoundTrip extends TestBase {

  private static final Object GROUP_ID = "g1";

  private static byte[] payload(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void testRoundTripBetweenAdapters() {
    InMemoryConsensusSpi srcSpi = new InMemoryConsensusSpi();
    StateMachineAdapter srcAdapter = new StateMachineAdapter(GROUP_ID, srcSpi);

    srcAdapter.runOperation(1L, payload("alpha"));
    srcAdapter.runOperation(2L, payload("beta"));
    srcAdapter.runOperation(3L, payload("gamma"));
    srcAdapter.onApplyBatchEnd();

    List<Object> chunks = new ArrayList<>();
    srcAdapter.takeSnapshot(3L, chunks::add);
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0)).isInstanceOf(byte[].class);

    InMemoryConsensusSpi destSpi = new InMemoryConsensusSpi();
    StateMachineAdapter destAdapter = new StateMachineAdapter(GROUP_ID, destSpi);
    destAdapter.installSnapshot(3L, chunks);

    assertThat(destSpi.committedEntries(GROUP_ID))
      .containsExactlyElementsOf(srcSpi.committedEntries(GROUP_ID));
    assertThat(destSpi.snapshotsInstalled(GROUP_ID)).containsExactly(3L);
    assertThat(srcSpi.snapshotsTaken(GROUP_ID)).containsExactly(3L);
  }

  @Test
  public void testEmptyStateRoundTrip() {
    InMemoryConsensusSpi srcSpi = new InMemoryConsensusSpi();
    StateMachineAdapter srcAdapter = new StateMachineAdapter(GROUP_ID, srcSpi);

    List<Object> chunks = new ArrayList<>();
    srcAdapter.takeSnapshot(0L, chunks::add);
    assertThat(chunks).hasSize(1);

    InMemoryConsensusSpi destSpi = new InMemoryConsensusSpi();
    StateMachineAdapter destAdapter = new StateMachineAdapter(GROUP_ID, destSpi);
    destAdapter.installSnapshot(0L, chunks);

    assertThat(destSpi.committedEntries(GROUP_ID)).isEmpty();
    assertThat(destSpi.snapshotsInstalled(GROUP_ID)).containsExactly(0L);
  }

  @Test
  public void testInstallReplacesPriorState() {
    InMemoryConsensusSpi srcSpi = new InMemoryConsensusSpi();
    StateMachineAdapter srcAdapter = new StateMachineAdapter(GROUP_ID, srcSpi);
    srcAdapter.runOperation(1L, payload("x1"));
    srcAdapter.runOperation(2L, payload("x2"));
    srcAdapter.onApplyBatchEnd();

    List<Object> chunks = new ArrayList<>();
    srcAdapter.takeSnapshot(2L, chunks::add);

    InMemoryConsensusSpi destSpi = new InMemoryConsensusSpi();
    StateMachineAdapter destAdapter = new StateMachineAdapter(GROUP_ID, destSpi);
    destAdapter.runOperation(1L, payload("stale"));
    destAdapter.onApplyBatchEnd();
    assertThat(destSpi.committedEntries(GROUP_ID)).hasSize(1);

    destAdapter.installSnapshot(2L, chunks);

    assertThat(destSpi.committedEntries(GROUP_ID))
      .containsExactlyElementsOf(srcSpi.committedEntries(GROUP_ID));
  }

  @Test
  public void testChunkIsIndependentOfConsensusState() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);
    adapter.runOperation(1L, payload("hello"));
    adapter.onApplyBatchEnd();

    List<Object> chunks = new ArrayList<>();
    adapter.takeSnapshot(1L, chunks::add);

    byte[] bytes = (byte[]) chunks.get(0);
    String asString = new String(bytes, StandardCharsets.UTF_8);
    assertThat(asString).doesNotContain("term").doesNotContain("snapshotIndex")
      .doesNotContain("logIndex").doesNotContain("groupMembersView");
  }
}
