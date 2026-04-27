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
 * Tests behaviour of {@link StateMachineAdapter} across the equivalent of a node crash and restart.
 * Because the adapter holds no persistent state itself a fresh adapter on the same
 * {@link InMemoryConsensusSpi} must:
 * <ul>
 * <li>not surface any partial pre-crash batch via {@code onCommit};</li>
 * <li>replay {@code runOperation} entries in commit-index order produced by the new
 * {@code RaftNode} and deliver them as a single new batch;</li>
 * <li>preserve previously committed and flushed state recorded by the SPI.</li>
 * </ul>
 */
@Tag(SmallTests.TAG)
public class TestStateMachineAdapterCrashRestart extends TestBase {

  private static final Object GROUP_ID = "g1";

  private static byte[] payload(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static FlushMarker marker(long flushOpSeqId) {
    return new FlushMarker(flushOpSeqId, flushOpSeqId, new byte[0]);
  }

  @Test
  public void testPendingEntriesLostOnAdapterRestart() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter pre = new StateMachineAdapter(GROUP_ID, spi);

    pre.runOperation(1L, payload("a"));
    pre.runOperation(2L, payload("b"));

    StateMachineAdapter post = new StateMachineAdapter(GROUP_ID, spi);
    post.onApplyBatchEnd();

    assertThat(spi.commitBatches(GROUP_ID))
      .as("entries buffered in the pre-crash adapter must not surface").isEmpty();
  }

  @Test
  public void testReplayPostRestartProducesFreshBatch() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter pre = new StateMachineAdapter(GROUP_ID, spi);

    pre.runOperation(1L, payload("a"));
    pre.runOperation(2L, payload("b"));
    pre.onApplyBatchEnd();
    pre.runOperation(3L, payload("c"));

    StateMachineAdapter post = new StateMachineAdapter(GROUP_ID, spi);
    post.runOperation(3L, payload("c"));
    post.runOperation(4L, payload("d"));
    post.onApplyBatchEnd();

    List<List<CommittedEntry>> batches = spi.commitBatches(GROUP_ID);
    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).extracting(CommittedEntry::getCommitIndex).containsExactly(1L, 2L);
    assertThat(batches.get(1)).extracting(CommittedEntry::getCommitIndex).containsExactly(3L, 4L);
  }

  @Test
  public void testFlushMarkerPriorToCrashIsAlreadyDeliveredAndStaysDelivered() {
    InMemoryConsensusSpi spi = new InMemoryConsensusSpi();
    StateMachineAdapter pre = new StateMachineAdapter(GROUP_ID, spi);

    pre.runOperation(1L, payload("a"));
    pre.runOperation(2L, marker(10L));
    pre.onApplyBatchEnd();

    StateMachineAdapter post = new StateMachineAdapter(GROUP_ID, spi);
    post.onApplyBatchEnd();

    assertThat(spi.flushes(GROUP_ID)).containsExactly(marker(10L));
    assertThat(spi.commitBatches(GROUP_ID)).hasSize(1);
    assertThat(spi.commitBatches(GROUP_ID).get(0)).extracting(CommittedEntry::getCommitIndex)
      .containsExactly(1L);
  }
}
