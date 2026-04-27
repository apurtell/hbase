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
import java.util.List;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a {@link FlushMarker} entry replicated through the {@link StateMachineAdapter} acts
 * as a hard break in the commit batch. Any preceding {@code byte[]} entries drain to the SPI as a
 * complete batch via {@code onCommit}, then {@code onFlushComplete} is fired with the marker, then
 * any subsequent entries form a new batch.
 */
@Tag(SmallTests.TAG)
public class TestFlushMarkerBatchBreak extends TestBase {

  private static final Object GROUP_ID = "g1";

  private static byte[] payload(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static FlushMarker marker(long flushOpSeqId) {
    return new FlushMarker(flushOpSeqId, flushOpSeqId, new byte[0]);
  }

  @Test
  public void testFlushMarkerSplitsBatchInsideSingleApplyLoop() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    adapter.runOperation(2L, payload("b"));
    adapter.runOperation(3L, marker(100L));
    adapter.runOperation(4L, payload("c"));
    adapter.onApplyBatchEnd();

    List<List<CommittedEntry>> batches = spi.commitBatches(GROUP_ID);
    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).extracting(CommittedEntry::getCommitIndex).containsExactly(1L, 2L);
    assertThat(batches.get(1)).extracting(CommittedEntry::getCommitIndex).containsExactly(4L);
    assertThat(spi.flushes(GROUP_ID)).containsExactly(marker(100L));
  }

  @Test
  public void testLeadingFlushMarkerFiresFlushOnly() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, marker(10L));
    adapter.runOperation(2L, payload("a"));
    adapter.onApplyBatchEnd();

    assertThat(spi.flushes(GROUP_ID)).containsExactly(marker(10L));
    assertThat(spi.commitBatches(GROUP_ID)).hasSize(1);
    assertThat(spi.commitBatches(GROUP_ID).get(0)).extracting(CommittedEntry::getCommitIndex)
      .containsExactly(2L);
  }

  @Test
  public void testTrailingFlushMarkerDrainsAndFlushes() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    adapter.runOperation(2L, payload("b"));
    adapter.runOperation(3L, marker(50L));
    adapter.onApplyBatchEnd();

    assertThat(spi.commitBatches(GROUP_ID)).hasSize(1);
    assertThat(spi.commitBatches(GROUP_ID).get(0)).extracting(CommittedEntry::getCommitIndex)
      .containsExactly(1L, 2L);
    assertThat(spi.flushes(GROUP_ID)).containsExactly(marker(50L));
  }

  @Test
  public void testConsecutiveFlushMarkersFireSeparately() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    adapter.runOperation(2L, marker(11L));
    adapter.runOperation(3L, marker(12L));
    adapter.runOperation(4L, payload("b"));
    adapter.onApplyBatchEnd();

    assertThat(spi.flushes(GROUP_ID)).containsExactly(marker(11L), marker(12L));
    List<List<CommittedEntry>> batches = spi.commitBatches(GROUP_ID);
    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).extracting(CommittedEntry::getCommitIndex).containsExactly(1L);
    assertThat(batches.get(1)).extracting(CommittedEntry::getCommitIndex).containsExactly(4L);
  }

  @Test
  public void testFlushMarkerAcrossApplyLoops() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter adapter = new StateMachineAdapter(GROUP_ID, spi);

    adapter.runOperation(1L, payload("a"));
    adapter.onApplyBatchEnd();
    adapter.runOperation(2L, marker(20L));
    adapter.onApplyBatchEnd();
    adapter.runOperation(3L, payload("b"));
    adapter.onApplyBatchEnd();

    List<List<CommittedEntry>> batches = spi.commitBatches(GROUP_ID);
    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).extracting(CommittedEntry::getCommitIndex).containsExactly(1L);
    assertThat(batches.get(1)).extracting(CommittedEntry::getCommitIndex).containsExactly(3L);
    assertThat(spi.flushes(GROUP_ID)).containsExactly(marker(20L));
  }
}
