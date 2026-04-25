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
package org.apache.hadoop.hbase.consensus.raft.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class FirewallTest extends BaseTest {
  private final Firewall firewall = new Firewall();

  @Test
  public void dropMessageType() {
    RaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    firewall.dropMessagesTo(endpoint, AppendEntriesRequest.class);
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesRequest.class))).isTrue();
  }

  @Test
  public void dropDoesNotAffectOtherEndpoint() {
    RaftEndpoint endpoint1 = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint endpoint2 = LocalRaftEndpoint.newEndpoint();
    firewall.dropMessagesTo(endpoint1, AppendEntriesRequest.class);
    assertThat(firewall.shouldDropMessage(endpoint2, mock(AppendEntriesRequest.class))).isFalse();
  }

  @Test
  public void dropDoesNotAffectOtherMessageType() {
    RaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    firewall.dropMessagesTo(endpoint, VoteRequest.class);
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesRequest.class))).isFalse();
  }

  @Test
  public void dropAllMessages() {
    RaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    firewall.dropAllMessagesTo(endpoint);
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesRequest.class))).isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesSuccessResponse.class)))
      .isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesFailureResponse.class)))
      .isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(VoteRequest.class))).isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(VoteResponse.class))).isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(PreVoteRequest.class))).isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(PreVoteResponse.class))).isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(InstallSnapshotRequest.class))).isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(InstallSnapshotResponse.class))).isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(TriggerLeaderElectionRequest.class)))
      .isTrue();
  }

  @Test
  public void dropAllDoesNotAffectOtherEndpoint() {
    RaftEndpoint endpoint1 = LocalRaftEndpoint.newEndpoint();
    RaftEndpoint endpoint2 = LocalRaftEndpoint.newEndpoint();
    firewall.dropAllMessagesTo(endpoint1);
    assertThat(firewall.shouldDropMessage(endpoint2, mock(AppendEntriesRequest.class))).isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(AppendEntriesSuccessResponse.class)))
      .isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(AppendEntriesFailureResponse.class)))
      .isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(VoteRequest.class))).isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(VoteResponse.class))).isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(PreVoteRequest.class))).isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(PreVoteResponse.class))).isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(InstallSnapshotRequest.class))).isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(InstallSnapshotResponse.class)))
      .isFalse();
    assertThat(firewall.shouldDropMessage(endpoint2, mock(TriggerLeaderElectionRequest.class)))
      .isFalse();
  }

  @Test
  public void dropMessageTypeForAllEndpoints() {
    firewall.dropMessagesToAll(AppendEntriesRequest.class);
    assertThat(
      firewall.shouldDropMessage(LocalRaftEndpoint.newEndpoint(), mock(AppendEntriesRequest.class)))
      .isTrue();
    assertThat(
      firewall.shouldDropMessage(LocalRaftEndpoint.newEndpoint(), mock(AppendEntriesRequest.class)))
      .isTrue();
  }

  @Test
  public void dropForAllDoesNotAffectOtherType() {
    firewall.dropMessagesToAll(AppendEntriesRequest.class);
    assertThat(firewall.shouldDropMessage(LocalRaftEndpoint.newEndpoint(), mock(VoteRequest.class)))
      .isFalse();
    assertThat(firewall.shouldDropMessage(LocalRaftEndpoint.newEndpoint(), mock(VoteRequest.class)))
      .isFalse();
  }

  @Test
  public void dropAllOverridesSpecific() {
    LocalRaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    firewall.dropMessagesTo(endpoint, AppendEntriesRequest.class);
    firewall.dropAllMessagesTo(endpoint);
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesRequest.class))).isTrue();
    assertThat(firewall.shouldDropMessage(endpoint, mock(VoteRequest.class))).isTrue();
  }

  @Test
  public void allowReversesDrop() {
    LocalRaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    firewall.dropMessagesTo(endpoint, AppendEntriesRequest.class);
    firewall.allowMessagesTo(endpoint, AppendEntriesRequest.class);
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesRequest.class))).isFalse();
  }

  @Test
  public void allowAllReversesDropAll() {
    LocalRaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    firewall.dropAllMessagesTo(endpoint);
    firewall.allowAllMessagesTo(endpoint);
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesRequest.class))).isFalse();
  }

  @Test
  public void allowAllReversesMultipleDrops() {
    LocalRaftEndpoint endpoint = LocalRaftEndpoint.newEndpoint();
    firewall.dropMessagesTo(endpoint, AppendEntriesRequest.class);
    firewall.dropMessagesTo(endpoint, VoteRequest.class);
    firewall.allowAllMessagesTo(endpoint);
    assertThat(firewall.shouldDropMessage(endpoint, mock(AppendEntriesRequest.class))).isFalse();
    assertThat(firewall.shouldDropMessage(endpoint, mock(VoteRequest.class))).isFalse();
  }
}
