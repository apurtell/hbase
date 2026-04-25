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
package org.apache.hadoop.hbase.consensus.handler.executor;

import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.InMemoryRaftStore;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalTransport;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Two independent 3-node Raft groups share a single {@link MultiGroupExecutor}. Replication on one
 * group must succeed, the other group must remain healthy when one of the first group's members
 * terminates, and the shared scheduler is never the bottleneck.
 * <p>
 * This test deliberately avoids
 * {@link org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup} because that helper
 * hard-codes {@code DefaultRaftNodeExecutor}; we need to inject our own executor per node.
 */
@Tag(SmallTests.TAG)
public class MultiGroupExecutorRaftIntegrationTest extends BaseTest {

  private MultiGroupExecutor mge;
  private final List<RaftNodeImpl> startedNodes = new ArrayList<>();

  @AfterEach
  public void tearDown() {
    for (RaftNodeImpl node : startedNodes) {
      try {
        node.terminate().join();
      } catch (Throwable t) {
        // best-effort
      }
    }
    startedNodes.clear();
    if (mge != null) {
      mge.closeUnchecked();
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  public void twoGroupsReplicateAndFailIndependently() throws Exception {
    mge = new MultiGroupExecutor();
    Group x = startGroup("group-x", 3);
    Group y = startGroup("group-y", 3);

    RaftNodeImpl xLeader = waitForLeader(x);
    RaftNodeImpl yLeader = waitForLeader(y);
    assertThat(xLeader).isNotNull();
    assertThat(yLeader).isNotNull();

    xLeader.replicate(applyValue("x1")).join();
    xLeader.replicate(applyValue("x2")).join();
    yLeader.replicate(applyValue("y1")).join();

    eventually(() -> {
      for (RaftNodeImpl n : x.nodes) {
        assertThat(x.stateMachines.get(n.getLocalEndpoint()).valueList()).contains("x1", "x2");
      }
      for (RaftNodeImpl n : y.nodes) {
        assertThat(y.stateMachines.get(n.getLocalEndpoint()).valueList()).contains("y1");
      }
    });

    // Tear down one member of group-x and confirm group-y keeps replicating.
    RaftNodeImpl victim =
      x.nodes.stream().filter(n -> !n.equals(xLeader)).findFirst().orElseThrow();
    isolate(victim, x);
    victim.terminate().join();
    startedNodes.remove(victim);

    yLeader.replicate(applyValue("y2")).join();
    eventually(() -> {
      for (RaftNodeImpl n : y.nodes) {
        assertThat(y.stateMachines.get(n.getLocalEndpoint()).valueList()).contains("y1", "y2");
      }
    });

    // group-x must still make progress with the remaining 2-of-3 quorum.
    xLeader.replicate(applyValue("x3")).join();
    eventually(() -> {
      for (RaftNodeImpl n : x.nodes) {
        if (n.equals(victim)) continue;
        assertThat(x.stateMachines.get(n.getLocalEndpoint()).valueList()).contains("x3");
      }
    });

    // Pool must still be alive with both group registries intact (or just group-y if x's
    // remaining leadership cleanup unregistered itself).
    assertThat(mge.activeGroups()).isPositive();
  }

  private Group startGroup(String groupId, int size) {
    Group g = new Group();
    for (int i = 0; i < size; i++) {
      g.endpoints.add(LocalRaftEndpoint.newEndpoint());
    }
    for (int i = 0; i < size; i++) {
      RaftEndpoint endpoint = g.endpoints.get(i);
      LocalTransport transport = new LocalTransport(endpoint);
      SimpleStateMachine sm = new SimpleStateMachine(true);
      RaftNodeImpl node = (RaftNodeImpl) RaftNode.newBuilder().setGroupId(groupId)
        .setLocalEndpoint(endpoint).setInitialGroupMembers(g.endpoints)
        .setExecutor(mge.executorFor(groupId + ":" + endpoint.getId())).setTransport(transport)
        .setStateMachine(sm).setStore(new InMemoryRaftStore()).build();
      g.nodes.add(node);
      g.transports.put(endpoint, transport);
      g.stateMachines.put(endpoint, sm);
      startedNodes.add(node);
    }
    for (RaftNodeImpl a : g.nodes) {
      for (RaftNodeImpl b : g.nodes) {
        if (a == b) continue;
        g.transports.get(a.getLocalEndpoint()).discoverNode(b);
      }
    }
    for (RaftNodeImpl n : g.nodes) {
      n.start();
    }
    return g;
  }

  private RaftNodeImpl waitForLeader(Group g) {
    RaftNodeImpl[] leader = new RaftNodeImpl[1];
    eventually(() -> {
      for (RaftNodeImpl n : g.nodes) {
        if (getRole(n) == RaftRole.LEADER) {
          leader[0] = n;
          return;
        }
      }
      throw new AssertionError("no leader yet");
    });
    return leader[0];
  }

  private void isolate(RaftNodeImpl victim, Group g) {
    for (RaftNodeImpl other : g.nodes) {
      if (other == victim) continue;
      g.transports.get(other.getLocalEndpoint()).undiscoverNode(victim);
      g.transports.get(victim.getLocalEndpoint()).undiscoverNode(other);
    }
  }

  private static class Group {
    final List<RaftEndpoint> endpoints = new ArrayList<>();
    final List<RaftNodeImpl> nodes = new ArrayList<>();
    final Map<RaftEndpoint, LocalTransport> transports = new HashMap<>();
    final Map<RaftEndpoint, SimpleStateMachine> stateMachines = new HashMap<>();
  }
}
