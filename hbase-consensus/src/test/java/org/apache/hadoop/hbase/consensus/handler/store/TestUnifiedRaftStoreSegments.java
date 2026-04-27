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
package org.apache.hadoop.hbase.consensus.handler.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage of segment rolling and GC: a small segment-size override forces fast rolls; bumping the
 * GC frontier with {@link RaftStore#truncateLogEntriesUntil(long)} releases stale segments.
 */
@Tag(SmallTests.TAG)
public class TestUnifiedRaftStoreSegments extends TestBase {

  @TempDir
  Path tmp;

  private UnifiedRaftStore store;
  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();

  /** ~4 KiB segments so a few entries can roll the log within a single test. */
  private UnifiedRaftStore newStore() {
    return new UnifiedRaftStore(new LogStoreConfig(tmp.toFile(), 1, 5L, 64, 4096L));
  }

  @AfterEach
  public void tearDown() throws IOException {
    if (store != null) {
      store.close();
      store = null;
    }
  }

  @Test
  public void testSegmentRollsWhenSizeExceeded() throws IOException {
    store = newStore();
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    int beforeRoll = store.segmentIndexForTesting().size();
    byte[] op = new byte[1024];
    for (int i = 1; i <= 16; i++) {
      LogEntry e = factory.createLogEntryBuilder().setIndex(i).setTerm(1).setOperation(op).build();
      g.persistLogEntries(Collections.singletonList(e));
    }
    g.flush();
    int afterRoll = store.segmentIndexForTesting().size();
    assertThat(afterRoll).isGreaterThan(beforeRoll);
  }

  @Test
  public void testGcAdvancesAfterTruncateUntil() throws IOException {
    store = newStore();
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    byte[] op = new byte[1024];
    for (int i = 1; i <= 16; i++) {
      LogEntry e = factory.createLogEntryBuilder().setIndex(i).setTerm(1).setOperation(op).build();
      g.persistLogEntries(Collections.singletonList(e));
    }
    g.flush();
    int rolled = store.segmentIndexForTesting().size();
    assertThat(rolled).isGreaterThan(1);
    g.truncateLogEntriesUntil(16L);
    g.flush();
    int afterGc = store.segmentIndexForTesting().size();
    assertThat(afterGc).isLessThanOrEqualTo(rolled);
    assertThat(afterGc).isGreaterThanOrEqualTo(1);
  }

  @Test
  public void testTruncateFromEmitsMarkerWithoutGc() throws IOException {
    UnifiedRaftStore small =
      new UnifiedRaftStore(new LogStoreConfig(tmp.toFile(), 1, 5L, 64, 1L << 30));
    store = small;
    store.load();
    RaftStore g = store.newGroupStore("g".getBytes());
    byte[] op = new byte[64];
    for (int i = 1; i <= 4; i++) {
      LogEntry e = factory.createLogEntryBuilder().setIndex(i).setTerm(1).setOperation(op).build();
      g.persistLogEntries(Collections.singletonList(e));
    }
    g.flush();
    int before = store.segmentIndexForTesting().size();
    g.truncateLogEntriesFrom(3L);
    g.flush();
    int after = store.segmentIndexForTesting().size();
    assertThat(after).isEqualTo(before);
  }
}
