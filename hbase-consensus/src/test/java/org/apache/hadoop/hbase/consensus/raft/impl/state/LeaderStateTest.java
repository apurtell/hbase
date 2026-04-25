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
package org.apache.hadoop.hbase.consensus.raft.impl.state;

import static org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint.newEndpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class LeaderStateTest {
  private static final int TIME = 12345;
  private final Random random = new Random();
  private LeaderState state;
  private List<RaftEndpoint> remoteEndpoints;
  private int lastLogIndex;

  @BeforeEach
  public void setUp() {
    lastLogIndex = 123;
    remoteEndpoints = Arrays.asList(newEndpoint(), newEndpoint(), newEndpoint(), newEndpoint());
    state = new LeaderState(remoteEndpoints, lastLogIndex, TIME);
  }

  @Test
  public void initialState() {
    for (RaftEndpoint endpoint : remoteEndpoints) {
      FollowerState followerState = state.getFollowerState(endpoint);
      assertThat(followerState.matchIndex()).isEqualTo(0);
      assertThat(followerState.nextIndex()).isEqualTo(lastLogIndex + 1);
    }
    long[] matchIndices = state.matchIndices(remoteEndpoints);
    assertThat(matchIndices.length).isEqualTo(remoteEndpoints.size() + 1);
    for (long index : matchIndices) {
      assertThat(index).isEqualTo(0);
    }
  }

  @Test
  public void nextIndex() {
    Map<RaftEndpoint, Integer> indices = new HashMap<>();
    for (RaftEndpoint endpoint : remoteEndpoints) {
      int index = 1 + random.nextInt(100);
      state.getFollowerState(endpoint).nextIndex(index);
      indices.put(endpoint, index);
    }
    for (RaftEndpoint endpoint : remoteEndpoints) {
      int index = indices.get(endpoint);
      assertThat(state.getFollowerState(endpoint).nextIndex()).isEqualTo(index);
    }
  }

  @Test
  public void matchIndex() {
    Map<RaftEndpoint, Long> indices = new HashMap<>();
    for (RaftEndpoint endpoint : remoteEndpoints) {
      long index = 1 + random.nextInt(100);
      state.getFollowerState(endpoint).matchIndex(index);
      indices.put(endpoint, index);
    }
    for (RaftEndpoint endpoint : remoteEndpoints) {
      long index = indices.get(endpoint);
      assertThat(state.getFollowerState(endpoint).matchIndex()).isEqualTo(index);
    }
    long[] matchIndices = state.matchIndices(remoteEndpoints);
    assertThat(matchIndices.length).isEqualTo(indices.size() + 1);
    for (int i = 0; i < matchIndices.length - 1; i++) {
      long index = matchIndices[i];
      assertThat(indices.containsValue(index)).isTrue();
    }
  }

  @Test
  public void matchIndexNonVoting() {
    int logIndex = 10;
    for (RaftEndpoint endpoint : remoteEndpoints) {
      state.getFollowerState(endpoint).matchIndex(++logIndex);
    }
    long[] indices = state.matchIndices(remoteEndpoints.subList(0, remoteEndpoints.size() - 1));
    assertThat(indices).hasSize(remoteEndpoints.size());
    for (long index : indices) {
      assertThat(index).isLessThan(logIndex);
    }
    assertThat(indices[indices.length - 1]).isEqualTo(0);
  }
}
