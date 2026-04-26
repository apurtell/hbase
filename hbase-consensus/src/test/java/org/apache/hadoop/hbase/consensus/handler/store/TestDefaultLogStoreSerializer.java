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

import java.util.Arrays;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Per-{@code Serializer<T>} round-trip checks for {@link DefaultLogStoreSerializer} including edge
 * cases (null {@code votedFor}, empty members, multi-chunk snapshots).
 */
@Tag(SmallTests.TAG)
public class TestDefaultLogStoreSerializer extends TestBase {

  private final DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
  private final DefaultLogStoreSerializer ser = new DefaultLogStoreSerializer();

  private final RaftEndpoint a = LocalRaftEndpoint.newEndpoint();
  private final RaftEndpoint b = LocalRaftEndpoint.newEndpoint();

  @Test
  public void testRaftEndpointRoundTrip() {
    LogStoreSerializer.Serializer<RaftEndpoint> s = ser.raftEndpointSerializer();
    RaftEndpoint back = s.deserialize(s.serialize(a));
    assertThat(String.valueOf(back.getId())).isEqualTo(String.valueOf(a.getId()));
  }

  @Test
  public void testMembersViewRoundTrip() {
    RaftGroupMembersView view = factory.createRaftGroupMembersViewBuilder().setLogIndex(123L)
      .setMembers(Arrays.asList(a, b)).setVotingMembers(Arrays.asList(a, b)).build();
    LogStoreSerializer.Serializer<RaftGroupMembersView> s = ser.raftGroupMembersViewSerializer();
    RaftGroupMembersView back = s.deserialize(s.serialize(view));
    assertThat(back.getLogIndex()).isEqualTo(123L);
    assertThat(back.getMembers()).hasSize(2);
    assertThat(back.getVotingMembers()).hasSize(2);
  }

  @Test
  public void testEmptyMembersViewRoundTrip() {
    RaftGroupMembersView view = factory.createRaftGroupMembersViewBuilder().setLogIndex(0L)
      .setMembers(Arrays.asList()).setVotingMembers(Arrays.asList()).build();
    LogStoreSerializer.Serializer<RaftGroupMembersView> s = ser.raftGroupMembersViewSerializer();
    RaftGroupMembersView back = s.deserialize(s.serialize(view));
    assertThat(back.getMembers()).isEmpty();
    assertThat(back.getVotingMembers()).isEmpty();
  }

  @Test
  public void testLogEntryRoundTrip() {
    byte[] op = new byte[] { 9, 8, 7 };
    LogEntry e = factory.createLogEntryBuilder().setIndex(11L).setTerm(2).setOperation(op).build();
    LogStoreSerializer.Serializer<LogEntry> s = ser.logEntrySerializer();
    LogEntry back = s.deserialize(s.serialize(e));
    assertThat(back.getIndex()).isEqualTo(11L);
    assertThat(back.getTerm()).isEqualTo(2);
    assertThat((byte[]) back.getOperation()).containsExactly(op);
  }

  @Test
  public void testLargeLogEntryRoundTrip() {
    byte[] op = new byte[64 * 1024];
    for (int i = 0; i < op.length; i++) {
      op[i] = (byte) (i & 0xff);
    }
    LogEntry e = factory.createLogEntryBuilder().setIndex(99L).setTerm(5).setOperation(op).build();
    LogEntry back = ser.logEntrySerializer().deserialize(ser.logEntrySerializer().serialize(e));
    assertThat((byte[]) back.getOperation()).containsExactly(op);
  }

  @Test
  public void testSnapshotChunkRoundTrip() {
    RaftGroupMembersView view = factory.createRaftGroupMembersViewBuilder().setLogIndex(5L)
      .setMembers(Arrays.asList(a, b)).setVotingMembers(Arrays.asList(a, b)).build();
    SnapshotChunk chunk =
      factory.createSnapshotChunkBuilder().setIndex(50L).setTerm(7).setSnapshotChunkIndex(2)
        .setSnapshotChunkCount(5).setGroupMembersView(view).setOperation("body".getBytes()).build();
    SnapshotChunk back =
      ser.snapshotChunkSerializer().deserialize(ser.snapshotChunkSerializer().serialize(chunk));
    assertThat(back.getIndex()).isEqualTo(50L);
    assertThat(back.getTerm()).isEqualTo(7);
    assertThat(back.getSnapshotChunkIndex()).isEqualTo(2);
    assertThat(back.getSnapshotChunkCount()).isEqualTo(5);
    assertThat((byte[]) back.getOperation()).containsExactly("body".getBytes());
  }

  @Test
  public void testRaftEndpointPersistentStateRoundTripVoting() {
    RaftEndpointPersistentState state = factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(a).setVoting(true).build();
    LogStoreSerializer.Serializer<RaftEndpointPersistentState> s =
      ser.raftEndpointPersistentStateSerializer();
    RaftEndpointPersistentState back = s.deserialize(s.serialize(state));
    assertThat(String.valueOf(back.getLocalEndpoint().getId()))
      .isEqualTo(String.valueOf(a.getId()));
    assertThat(back.isVoting()).isTrue();
  }

  @Test
  public void testRaftEndpointPersistentStateRoundTripLearner() {
    RaftEndpointPersistentState state = factory.createRaftEndpointPersistentStateBuilder()
      .setLocalEndpoint(a).setVoting(false).build();
    RaftEndpointPersistentState back = ser.raftEndpointPersistentStateSerializer()
      .deserialize(ser.raftEndpointPersistentStateSerializer().serialize(state));
    assertThat(back.isVoting()).isFalse();
  }

  @Test
  public void testRaftTermPersistentStateNullVotedFor() {
    RaftTermPersistentState state =
      factory.createRaftTermPersistentStateBuilder().setTerm(42).setVotedFor(null).build();
    LogStoreSerializer.Serializer<RaftTermPersistentState> s =
      ser.raftTermPersistentStateSerializer();
    RaftTermPersistentState back = s.deserialize(s.serialize(state));
    assertThat(back.getTerm()).isEqualTo(42);
    assertThat(back.getVotedFor()).isNull();
  }

  @Test
  public void testRaftTermPersistentStateWithVotedFor() {
    RaftTermPersistentState state =
      factory.createRaftTermPersistentStateBuilder().setTerm(7).setVotedFor(a).build();
    RaftTermPersistentState back = ser.raftTermPersistentStateSerializer()
      .deserialize(ser.raftTermPersistentStateSerializer().serialize(state));
    assertThat(back.getTerm()).isEqualTo(7);
    assertThat(String.valueOf(back.getVotedFor().getId())).isEqualTo(String.valueOf(a.getId()));
  }
}
