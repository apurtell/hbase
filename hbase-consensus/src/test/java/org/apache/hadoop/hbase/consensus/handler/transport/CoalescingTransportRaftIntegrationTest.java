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
package org.apache.hadoop.hbase.consensus.handler.transport;

import static org.apache.hadoop.hbase.consensus.handler.transport.OperationCodecs.composite;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.queryLastValue;
import static org.apache.hadoop.hbase.consensus.raft.test.util.AssertionUtils.eventually;
import static org.apache.hadoop.hbase.consensus.raft.test.util.RaftTestUtils.getRole;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.consensus.handler.executor.MultiGroupExecutor;
import org.apache.hadoop.hbase.consensus.raft.QueryPolicy;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.InMemoryRaftStore;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachineOpCodec;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Three real Raft nodes wired over real Netty {@link CoalescingTransport} instances on loopback
 * ephemeral ports, all driven from a single Phase 2 {@link MultiGroupExecutor}. Each node hosts two
 * concurrent Raft groups (group-x and group-y) that share its transport, so frame demultiplexing by
 * {@code groupId} is exercised for real.
 * <p>
 * The test asserts that:
 * <ul>
 * <li>both groups elect a leader independently,</li>
 * <li>replication on group-x converges across all three nodes' state machines,</li>
 * <li>replication on group-y converges across all three nodes' state machines,</li>
 * <li>leader-side linearizable queries return the most recently committed value, and</li>
 * <li>state machine state stays partitioned by group (group-x replicas don't see group-y values and
 * vice-versa).</li>
 * </ul>
 */
@Tag(LargeTests.TAG)
public class CoalescingTransportRaftIntegrationTest extends BaseTest {

  private static final String GROUP_X = "group-x";
  private static final String GROUP_Y = "group-y";
  private static final RaftConfig FAST =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(2000).setLeaderHeartbeatPeriodMillis(500)
      .setLeaderHeartbeatTimeoutMillis(5000).build();

  private MultiGroupExecutor mge;
  private final List<CoalescingTransport> transports = new ArrayList<>();
  private final List<RaftNodeImpl> nodes = new ArrayList<>();

  @AfterEach
  public void tearDown() {
    for (RaftNodeImpl n : nodes) {
      try {
        n.terminate().join();
      } catch (Throwable ignored) {
      }
    }
    nodes.clear();
    for (CoalescingTransport t : transports) {
      try {
        t.stop();
      } catch (RuntimeException ignored) {
      }
    }
    transports.clear();
    if (mge != null) {
      mge.closeUnchecked();
      mge = null;
    }
  }

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  public void twoGroupsReplicateIndependently() throws Exception {
    mge = new MultiGroupExecutor();

    // 3 physical nodes; each has its own LocalRaftEndpoint and shared CoalescingTransport.
    LocalRaftEndpoint epA = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epB = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epC = LocalRaftEndpoint.newEndpoint();
    List<RaftEndpoint> all = Arrays.asList(epA, epB, epC);

    Map<RaftEndpoint, InetSocketAddress> addrs = new ConcurrentHashMap<>();
    EndpointResolver resolver = ep -> {
      InetSocketAddress addr = addrs.get(ep);
      if (addr == null) {
        throw new UnknownEndpointException(ep);
      }
      return addr;
    };

    Configuration conf = baseConf();
    OperationCodec codec = composite(new UpdateRaftGroupMembersOpCodec(), new IdentityByteCodec(),
      new SimpleStateMachineOpCodec());

    Map<RaftEndpoint, CoalescingTransport> tx = new HashMap<>();
    for (RaftEndpoint ep : all) {
      CoalescingTransport t = newStarted(ep, conf, resolver, codec);
      tx.put(ep, t);
      addrs.put(ep, t.getBindAddress());
    }

    // Build both raft groups; every member endpoint hosts both groups on its shared transport.
    Cluster x = startGroup(GROUP_X, all, tx);
    Cluster y = startGroup(GROUP_Y, all, tx);

    // Wait for an independent leader in each group.
    RaftNodeImpl xLeader = waitForLeader(x);
    RaftNodeImpl yLeader = waitForLeader(y);
    assertThat(xLeader).isNotNull();
    assertThat(yLeader).isNotNull();

    // Replicate to group-x.
    xLeader.replicate(applyValue(bytes("x1"))).join();
    xLeader.replicate(applyValue(bytes("x2"))).join();
    xLeader.replicate(applyValue(bytes("x3"))).join();

    // Replicate to group-y.
    yLeader.replicate(applyValue(bytes("y1"))).join();
    yLeader.replicate(applyValue(bytes("y2"))).join();

    eventually(() -> {
      for (RaftNodeImpl n : x.nodes) {
        SimpleStateMachine sm = x.stateMachines.get(n.getLocalEndpoint());
        assertThat(toStrings(sm.valueList())).containsExactly("x1", "x2", "x3");
      }
      for (RaftNodeImpl n : y.nodes) {
        SimpleStateMachine sm = y.stateMachines.get(n.getLocalEndpoint());
        assertThat(toStrings(sm.valueList())).containsExactly("y1", "y2");
      }
    });

    // Leader-side linearizable query confirms last applied value.
    Object xLast = xLeader.query(queryLastValue(), QueryPolicy.LEADER_LEASE,
      java.util.Optional.empty(), java.util.Optional.empty()).join().getResult();
    Object yLast = yLeader.query(queryLastValue(), QueryPolicy.LEADER_LEASE,
      java.util.Optional.empty(), java.util.Optional.empty()).join().getResult();
    assertThat(new String((byte[]) xLast, StandardCharsets.UTF_8)).isEqualTo("x3");
    assertThat(new String((byte[]) yLast, StandardCharsets.UTF_8)).isEqualTo("y2");

    // Strict partitioning: group-x replicas must not see y values and vice-versa.
    for (RaftNodeImpl n : x.nodes) {
      SimpleStateMachine sm = x.stateMachines.get(n.getLocalEndpoint());
      assertThat(toStrings(sm.valueList())).doesNotContain("y1", "y2");
    }
    for (RaftNodeImpl n : y.nodes) {
      SimpleStateMachine sm = y.stateMachines.get(n.getLocalEndpoint());
      assertThat(toStrings(sm.valueList())).doesNotContain("x1", "x2", "x3");
    }

    // Pool is alive; transports are reachable.
    assertThat(mge.activeGroups()).isPositive();
    for (RaftEndpoint ep : all) {
      for (RaftEndpoint other : all) {
        if (!ep.equals(other)) {
          assertThat(tx.get(ep).isReachable(other)).isTrue();
        }
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------

  private CoalescingTransport newStarted(RaftEndpoint local, Configuration conf,
    EndpointResolver resolver, OperationCodec codec) {
    CoalescingTransport t =
      new CoalescingTransport(local, new InetSocketAddress("127.0.0.1", 0), resolver, codec, conf);
    transports.add(t);
    t.start();
    return t;
  }

  private Cluster startGroup(String groupId, List<RaftEndpoint> members,
    Map<RaftEndpoint, CoalescingTransport> tx) {
    Cluster c = new Cluster();
    for (RaftEndpoint ep : members) {
      SimpleStateMachine sm = new SimpleStateMachine(true);
      RaftNodeImpl node = (RaftNodeImpl) RaftNode.newBuilder().setGroupId(groupId)
        .setLocalEndpoint(ep).setInitialGroupMembers(members)
        .setExecutor(mge.executorFor(groupId + ":" + ep.getId())).setTransport(tx.get(ep))
        .setStateMachine(sm).setStore(new InMemoryRaftStore()).setConfig(FAST).build();
      c.nodes.add(node);
      c.stateMachines.put(ep, sm);
      nodes.add(node);
    }
    for (RaftNodeImpl n : c.nodes) {
      tx.get(n.getLocalEndpoint()).discoverNode(n);
    }
    for (RaftNodeImpl n : c.nodes) {
      n.start();
    }
    return c;
  }

  private RaftNodeImpl waitForLeader(Cluster c) {
    RaftNodeImpl[] holder = new RaftNodeImpl[1];
    eventually(() -> {
      for (RaftNodeImpl n : c.nodes) {
        if (getRole(n) == RaftRole.LEADER) {
          holder[0] = n;
          return;
        }
      }
      throw new AssertionError("no leader yet for " + c.nodes.get(0).getGroupId());
    }, 30);
    return holder[0];
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static List<String> toStrings(List<Object> values) {
    List<String> out = new ArrayList<>(values.size());
    for (Object o : values) {
      out.add(new String((byte[]) o, StandardCharsets.UTF_8));
    }
    return out;
  }

  private static Configuration baseConf() {
    Configuration c = HBaseConfiguration.create();
    c.setBoolean(TransportConfig.KEY_NATIVE_TRANSPORT, false);
    c.setLong(TransportConfig.KEY_BATCH_MS, 5L);
    c.setInt(TransportConfig.KEY_IO_THREADS, 2);
    return c;
  }

  private static class Cluster {
    final List<RaftNodeImpl> nodes = new ArrayList<>();
    final ConcurrentMap<RaftEndpoint, SimpleStateMachine> stateMachines = new ConcurrentHashMap<>();
  }
}
