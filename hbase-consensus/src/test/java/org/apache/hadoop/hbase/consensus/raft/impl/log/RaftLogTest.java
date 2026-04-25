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
package org.apache.hadoop.hbase.consensus.raft.impl.log;

import static org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint.newEndpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultLogEntryOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultRaftGroupMembersViewOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultSnapshotChunkOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultSnapshotEntryOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.BaseLogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class RaftLogTest {
  private final RaftLog log = RaftLog.create(100);
  private RaftGroupMembersView groupMembersView;

  @BeforeEach
  public void setUp() {
    Collection<RaftEndpoint> groupMembers =
      Arrays.asList(newEndpoint(), newEndpoint(), newEndpoint());
    groupMembersView = new DefaultRaftGroupMembersViewOrBuilder().setLogIndex(0)
      .setMembers(groupMembers).setVotingMembers(groupMembers).build();
  }

  @Test
  public void initialState() {
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(0);
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(0);
  }

  @Test
  public void appendSameTerm() {
    log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build());
    log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build());
    LogEntry last = new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build();
    log.appendEntry(last);
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(last.getTerm());
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(last.getIndex());
  }

  @Test
  public void appendHigherTerm() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    LogEntry last = new DefaultLogEntryOrBuilder().setTerm(2).setIndex(4).build();
    log.appendEntry(last);
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(last.getTerm());
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(last.getIndex());
    BaseLogEntry lastLogEntry = log.lastLogOrSnapshotEntry();
    assertThat(lastLogEntry.getTerm()).isEqualTo(last.getTerm());
    assertThat(lastLogEntry.getIndex()).isEqualTo(last.getIndex());
  }

  @Test
  public void appendLowerTermFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build());
    log.appendEntries(entries);
    assertThrows(IllegalArgumentException.class,
      () -> log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build()));
  }

  @Test
  public void appendLowerIndexFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build());
    log.appendEntries(entries);
    assertThrows(IllegalArgumentException.class,
      () -> log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build()));
  }

  @Test
  public void appendEqualIndexFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build());
    log.appendEntries(entries);
    assertThrows(IllegalArgumentException.class,
      () -> log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build()));
  }

  @Test
  public void appendGapFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build());
    log.appendEntries(entries);
    assertThrows(IllegalArgumentException.class,
      () -> log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(5).build()));
  }

  @Test
  public void appendAfterSnapshotSameTerm() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(3)
      .setGroupMembersView(groupMembersView).build());
    LogEntry last = new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build();
    log.appendEntry(last);
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(last.getTerm());
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(last.getIndex());
  }

  @Test
  public void appendAfterSnapshotHigherTerm() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(3)
      .setGroupMembersView(groupMembersView).build());
    LogEntry last = new DefaultLogEntryOrBuilder().setTerm(2).setIndex(4).build();
    log.appendEntry(last);
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(last.getTerm());
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(last.getIndex());
  }

  @Test
  public void appendAfterSnapshotLowerTermFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(2).setIndex(3)
      .setGroupMembersView(groupMembersView).build());
    assertThrows(IllegalArgumentException.class,
      () -> log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build()));
  }

  @Test
  public void appendAfterSnapshotLowerIndexFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(2).setIndex(3)
      .setGroupMembersView(groupMembersView).build());
    assertThrows(IllegalArgumentException.class,
      () -> log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build()));
  }

  @Test
  public void appendAfterSnapshotEqualIndexFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(2).setIndex(3)
      .setGroupMembersView(groupMembersView).build());
    assertThrows(IllegalArgumentException.class,
      () -> log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build()));
  }

  @Test
  public void appendAfterSnapshotGapFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(2).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(2).setIndex(3)
      .setGroupMembersView(groupMembersView).build());
    assertThrows(IllegalArgumentException.class,
      () -> log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(2).setIndex(5).build()));
  }

  @Test
  public void getEntry() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    for (int i = 1; i <= log.lastLogOrSnapshotIndex(); i++) {
      LogEntry entry = log.getLogEntry(i);
      assertThat(entry.getTerm()).isEqualTo(1);
      assertThat(entry.getIndex()).isEqualTo(i);
    }
  }

  @Test
  public void getEntryAfterSnapshot() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(3)
      .setGroupMembersView(groupMembersView).build());
    log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build());
    log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(5).build());
    for (int i = 1; i <= 3; i++) {
      assertThat(log.getLogEntry(i)).isNull();
    }
    for (int i = 4; i <= log.lastLogOrSnapshotIndex(); i++) {
      LogEntry entry = log.getLogEntry(i);
      assertThat(entry.getTerm()).isEqualTo(1);
      assertThat(entry.getIndex()).isEqualTo(i);
    }
  }

  @Test
  public void getEntryUnknownIndex() {
    assertThat(log.getLogEntry(1)).isNull();
  }

  @Test
  public void getEntryRejectsZero() {
    assertThrows(IllegalArgumentException.class, () -> log.getLogEntry(0));
  }

  @Test
  public void getEntriesBetween() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    List<LogEntry> result = log.getLogEntriesBetween(1, 3);
    assertThat(result).isEqualTo(entries);
    result = log.getLogEntriesBetween(1, 2);
    assertThat(result).isEqualTo(entries.subList(0, 2));
    result = log.getLogEntriesBetween(2, 3);
    assertThat(result).isEqualTo(entries.subList(1, 3));
  }

  @Test
  public void getEntriesBetweenAfterSnapshot() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(2)
      .setGroupMembersView(groupMembersView).build());
    List<LogEntry> result = log.getLogEntriesBetween(3, 3);
    assertThat(result).isEqualTo(entries.subList(2, 3));
  }

  @Test
  public void getEntriesBetweenBeforeSnapshotIndex() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(2)
      .setGroupMembersView(groupMembersView).build());
    assertThrows(IllegalArgumentException.class, () -> log.getLogEntriesBetween(2, 3));
  }

  @Test
  public void truncateEntriesFrom() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build());
    log.appendEntries(entries);
    List<LogEntry> truncated = log.truncateEntriesFrom(3);
    assertThat(truncated.size()).isEqualTo(2);
    assertThat(truncated).isEqualTo(entries.subList(2, 4));
    for (int i = 1; i <= 2; i++) {
      assertThat(log.getLogEntry(i)).isEqualTo(entries.get(i - 1));
    }
    assertThat(log.getLogEntry(3)).isNull();
  }

  @Test
  public void truncateAfterSnapshot() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(2)
      .setGroupMembersView(groupMembersView).build());
    List<LogEntry> truncated = log.truncateEntriesFrom(3);
    assertThat(truncated.size()).isEqualTo(2);
    assertThat(truncated).isEqualTo(entries.subList(2, 4));
    assertThat(log.getLogEntry(3)).isNull();
  }

  @Test
  public void truncateOutOfRangeFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    assertThrows(IllegalArgumentException.class, () -> log.truncateEntriesFrom(4));
  }

  @Test
  public void truncateBeforeSnapshotFails() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(2)
      .setGroupMembersView(groupMembersView).build());
    assertThrows(IllegalArgumentException.class, () -> log.truncateEntriesFrom(1));
  }

  @Test
  public void snapshotAtLastIndexSingleEntry() {
    log.appendEntry(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build());
    Object chunkOperation = new Object();
    SnapshotChunk snapshotChunk = new DefaultSnapshotChunkOrBuilder().setTerm(1).setIndex(1)
      .setOperation(chunkOperation).setSnapshotChunkIndex(0).setSnapshotChunkCount(1)
      .setGroupMembersView(groupMembersView).setGroupMembersView(groupMembersView).build();
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(1)
      .setSnapshotChunks(Arrays.asList(snapshotChunk)).setGroupMembersView(groupMembersView)
      .build());
    BaseLogEntry lastLogEntry = log.lastLogOrSnapshotEntry();
    assertThat(lastLogEntry.getTerm()).isEqualTo(1);
    assertThat(lastLogEntry.getIndex()).isEqualTo(1);
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(1);
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(1);
    assertThat(log.snapshotIndex()).isEqualTo(1);
    SnapshotEntry snapshotEntry = log.snapshotEntry();
    assertThat(snapshotEntry.getTerm()).isEqualTo(1);
    assertThat(snapshotEntry.getIndex()).isEqualTo(1);
    assertThat(snapshotEntry.getOperation()).isEqualTo(Arrays.asList(snapshotChunk));
  }

  @Test
  public void snapshotAtLastIndexMultiEntry() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(5).build());
    log.appendEntries(entries);
    log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(5)
      .setGroupMembersView(groupMembersView).build());
    BaseLogEntry lastLogEntry = log.lastLogOrSnapshotEntry();
    assertThat(lastLogEntry.getTerm()).isEqualTo(1);
    assertThat(lastLogEntry.getIndex()).isEqualTo(5);
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(1);
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(5);
    assertThat(log.snapshotIndex()).isEqualTo(5);
    SnapshotEntry snapshot = log.snapshotEntry();
    assertThat(snapshot.getTerm()).isEqualTo(1);
    assertThat(snapshot.getIndex()).isEqualTo(5);
  }

  @Test
  public void setSnapshot() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(5).build());
    log.appendEntries(entries);
    int truncated = log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(3)
      .setGroupMembersView(groupMembersView).build());
    assertThat(truncated).isEqualTo(3);
    for (int i = 1; i <= 3; i++) {
      assertThat(log.containsLogEntry(i)).isFalse();
      assertThat(log.getLogEntry(i)).isNull();
    }
    for (int i = 4; i <= 5; i++) {
      assertThat(log.containsLogEntry(i)).isTrue();
      assertThat(log.getLogEntry(i)).isNotNull();
    }
    BaseLogEntry lastLogEntry = log.lastLogOrSnapshotEntry();
    assertThat(lastLogEntry.getTerm()).isEqualTo(1);
    assertThat(lastLogEntry.getIndex()).isEqualTo(5);
    assertThat(log.getLogEntry(lastLogEntry.getIndex())).isSameAs(lastLogEntry);
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(1);
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(5);
    assertThat(log.snapshotIndex()).isEqualTo(3);
    SnapshotEntry snapshot = log.snapshotEntry();
    assertThat(snapshot.getTerm()).isEqualTo(1);
    assertThat(snapshot.getIndex()).isEqualTo(3);
  }

  @Test
  public void setSnapshotMultiple() {
    List<LogEntry> entries =
      Arrays.asList(new DefaultLogEntryOrBuilder().setTerm(1).setIndex(1).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(2).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(3).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(4).build(),
        new DefaultLogEntryOrBuilder().setTerm(1).setIndex(5).build());
    log.appendEntries(entries);
    int truncated = log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(2)
      .setGroupMembersView(groupMembersView).build());
    assertThat(truncated).isEqualTo(2);
    for (int i = 1; i <= 2; i++) {
      assertThat(log.containsLogEntry(i)).isFalse();
      assertThat(log.getLogEntry(i)).isNull();
    }
    for (int i = 3; i <= 5; i++) {
      assertThat(log.containsLogEntry(i)).isTrue();
      assertThat(log.getLogEntry(i)).isNotNull();
    }
    Object chunkOperation = new Object();
    SnapshotChunk snapshotChunk = new DefaultSnapshotChunkOrBuilder().setTerm(1).setIndex(4)
      .setOperation(chunkOperation).setSnapshotChunkIndex(0).setSnapshotChunkCount(1)
      .setGroupMembersView(groupMembersView).build();
    truncated = log.setSnapshot(new DefaultSnapshotEntryOrBuilder().setTerm(1).setIndex(4)
      .setSnapshotChunks(Arrays.asList(snapshotChunk)).setGroupMembersView(groupMembersView)
      .build());
    assertThat(truncated).isEqualTo(2);
    for (int i = 1; i <= 4; i++) {
      assertThat(log.containsLogEntry(i)).isFalse();
      assertThat(log.getLogEntry(i)).isNull();
    }
    assertThat(log.containsLogEntry(5)).isTrue();
    assertThat(log.getLogEntry(5)).isNotNull();
    BaseLogEntry lastLogEntry = log.lastLogOrSnapshotEntry();
    assertThat(lastLogEntry.getTerm()).isEqualTo(1);
    assertThat(lastLogEntry.getIndex()).isEqualTo(5);
    assertThat(log.getLogEntry(lastLogEntry.getIndex())).isSameAs(lastLogEntry);
    assertThat(log.lastLogOrSnapshotTerm()).isEqualTo(1);
    assertThat(log.lastLogOrSnapshotIndex()).isEqualTo(5);
    assertThat(log.snapshotIndex()).isEqualTo(4);
    SnapshotEntry snapshotEntry = log.snapshotEntry();
    assertThat(snapshotEntry.getTerm()).isEqualTo(1);
    assertThat(snapshotEntry.getIndex()).isEqualTo(4);
    assertThat(snapshotEntry.getOperation()).isEqualTo(Arrays.asList(snapshotChunk));
  }
}
