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

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class CandidateStateTest {
  private CandidateState state;
  private int majority;

  @BeforeEach
  public void setUp() {
    majority = 3;
    state = new CandidateState(majority);
  }

  @Test
  public void test_initialState() {
    assertThat(state.majority()).isEqualTo(majority);
    assertThat(state.voteCount()).isEqualTo(0);
    assertThat(state.isMajorityGranted()).isFalse();
  }

  @Test
  public void test_grantVote_withoutMajority() {
    RaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    assertThat(state.grantVote(endpoint)).isTrue();
    assertThat(state.grantVote(endpoint)).isFalse();
    assertThat(state.voteCount()).isEqualTo(1);
    assertThat(state.isMajorityGranted()).isFalse();
  }

  @Test
  public void test_grantVote_withMajority() {
    for (int i = 0; i < majority; i++) {
      RaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
      assertThat(state.grantVote(endpoint)).isTrue();
    }
    assertThat(state.voteCount()).isEqualTo(majority);
    assertThat(state.isMajorityGranted()).isTrue();
  }
}
