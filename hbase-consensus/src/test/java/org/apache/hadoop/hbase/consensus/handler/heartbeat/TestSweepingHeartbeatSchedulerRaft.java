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
package org.apache.hadoop.hbase.consensus.handler.heartbeat;

import static org.apache.hadoop.hbase.consensus.handler.transport.OperationCodecs.composite;
import static org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine.applyValue;
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
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.consensus.handler.executor.MultiGroupExecutor;
import org.apache.hadoop.hbase.consensus.handler.transport.CoalescingTransport;
import org.apache.hadoop.hbase.consensus.handler.transport.EndpointResolver;
import org.apache.hadoop.hbase.consensus.handler.transport.IdentityByteCodec;
import org.apache.hadoop.hbase.consensus.handler.transport.OperationCodec;
import org.apache.hadoop.hbase.consensus.handler.transport.TransportConfig;
import org.apache.hadoop.hbase.consensus.handler.transport.UnknownEndpointException;
import org.apache.hadoop.hbase.consensus.handler.transport.UpdateRaftGroupMembersOpCodec;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.heartbeat.HeartbeatScheduler;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.InMemoryRaftStore;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachine;
import org.apache.hadoop.hbase.consensus.raft.impl.local.SimpleStateMachineOpCodec;
import org.apache.hadoop.hbase.consensus.raft.impl.state.RaftTermState;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end integration: two concurrent 3-node Raft groups co-tenant on the same physical nodes,
 * each physical node owning a single shared {@link MultiGroupExecutor}, a single shared
 * {@link CoalescingTransport}, and a single shared {@link SweepingHeartbeatScheduler} multiplexing
 * heartbeats for both groups. Verifies steady-state replication on both groups and that
 * partition-driven re-election still happens for one group while the other keeps making progress.
 */
@Tag(LargeTests.TAG)
public class TestSweepingHeartbeatSchedulerRaft extends TestBase {

  private static final String GROUP_X = "group-x";
  private static final String GROUP_Y = "group-y";

  // Election deadlines drive how long after a partition we wait for re-election. The
  // SweepingHeartbeatScheduler at intervalMs=50 drives heartbeat sends; the leaderHeartbeatPeriod
  // value below is only honoured by the per-RaftNode tick to skip per-peer heartbeats that have
  // been sent too recently.
  private static final RaftConfig FAST =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(2_000)
      .setLeaderHeartbeatPeriodMillis(500).setLeaderHeartbeatTimeoutMillis(5_000).build();

  private MultiGroupExecutor mge;
  private final List<CoalescingTransport> transports = new ArrayList<>();
  private final List<SweepingHeartbeatScheduler> schedulers = new ArrayList<>();
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
    for (SweepingHeartbeatScheduler s : schedulers) {
      try {
        s.close();
      } catch (RuntimeException ignored) {
      }
    }
    schedulers.clear();
    if (mge != null) {
      mge.closeUnchecked();
      mge = null;
    }
  }

  @Test
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  public void testTwoGroupsShareOneSweeperRecoverFromPartition() throws Exception {
    mge = new MultiGroupExecutor();

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
    Map<RaftEndpoint, SweepingHeartbeatScheduler> hb = new HashMap<>();
    for (RaftEndpoint ep : all) {
      CoalescingTransport t =
        new CoalescingTransport(ep, new InetSocketAddress("127.0.0.1", 0), resolver, codec, conf);
      transports.add(t);
      t.start();
      tx.put(ep, t);
      addrs.put(ep, t.getBindAddress());

      SweepingHeartbeatScheduler s = new SweepingHeartbeatScheduler(50, 1);
      s.start();
      schedulers.add(s);
      hb.put(ep, s);
    }

    Cluster x = startGroup(GROUP_X, all, tx, hb);
    Cluster y = startGroup(GROUP_Y, all, tx, hb);

    RaftNodeImpl xLeader = waitForLeader(x);
    RaftNodeImpl yLeader = waitForLeader(y);
    assertThat(xLeader).isNotNull();
    assertThat(yLeader).isNotNull();

    xLeader.replicate(applyValue(bytes("x1"))).join();
    xLeader.replicate(applyValue(bytes("x2"))).join();
    yLeader.replicate(applyValue(bytes("y1"))).join();

    eventually(() -> {
      for (RaftNodeImpl n : x.nodes) {
        SimpleStateMachine sm = x.stateMachines.get(n.getLocalEndpoint());
        assertThat(toStrings(sm.valueList())).containsExactly("x1", "x2");
      }
      for (RaftNodeImpl n : y.nodes) {
        SimpleStateMachine sm = y.stateMachines.get(n.getLocalEndpoint());
        assertThat(toStrings(sm.valueList())).containsExactly("y1");
      }
    });

    // Partition the GROUP_X leader from its peers (drop both directions of every TCP socket from
    // its physical node). Group Y is co-tenant on the same physical nodes; if its leader happens
    // to live on the same isolated endpoint it will also be cut off, but a survivor majority for
    // each group still has 2/3 nodes reachable through the remaining mesh, so each group must
    // re-elect on its own.
    RaftEndpoint isolated = xLeader.getLocalEndpoint();
    isolatePhysicalNode(isolated, tx);

    // Assert each group elects a NEW leader (not the isolated one) within a few heartbeat
    // timeouts. The two groups time out independently.
    long timeoutSecs = (3 * FAST.getLeaderHeartbeatTimeoutMillis()) / 1000 + 5;
    assertNewLeaderEventually(x, isolated, timeoutSecs);
    assertNewLeaderEventually(y, isolated, timeoutSecs);
  }

  private Cluster startGroup(String groupId, List<RaftEndpoint> members,
    Map<RaftEndpoint, CoalescingTransport> tx, Map<RaftEndpoint, SweepingHeartbeatScheduler> hb) {
    Cluster c = new Cluster();
    for (RaftEndpoint ep : members) {
      SimpleStateMachine sm = new SimpleStateMachine(true);
      HeartbeatScheduler scheduler = hb.get(ep);
      RaftNodeImpl node =
        (RaftNodeImpl) RaftNode.newBuilder().setGroupId(groupId).setLocalEndpoint(ep)
          .setInitialGroupMembers(members).setExecutor(mge.executorFor(groupId + ":" + ep.getId()))
          .setTransport(tx.get(ep)).setStateMachine(sm).setStore(new InMemoryRaftStore())
          .setHeartbeatScheduler(scheduler).setConfig(FAST).build();
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

  private void assertNewLeaderEventually(Cluster c, RaftEndpoint avoid, long timeoutSecs) {
    eventually(() -> {
      RaftNodeImpl newLeader = null;
      for (RaftNodeImpl n : c.nodes) {
        if (n.getLocalEndpoint().equals(avoid)) {
          continue;
        }
        RaftTermState term = n.getTerm();
        if (term == null) {
          throw new AssertionError(n.getLocalEndpoint() + " has no term yet");
        }
        if (
          term.getLeaderEndpoint() != null && !term.getLeaderEndpoint().equals(avoid)
            && getRole(n) == RaftRole.LEADER
        ) {
          newLeader = n;
          break;
        }
      }
      assertThat(newLeader).as("no new leader yet for %s after partitioning %s",
        c.nodes.get(0).getGroupId(), avoid.getId()).isNotNull();
    }, timeoutSecs);
  }

  private static void isolatePhysicalNode(RaftEndpoint isolated,
    Map<RaftEndpoint, CoalescingTransport> tx) {
    // CoalescingTransport doesn't expose a firewall like LocalTransport, so we simulate a partition
    // by stopping the transport on the isolated node. Outbound sends become no-ops, the server
    // socket is closed so peers' reconnect attempts fail, and the rest of the mesh remains up.
    CoalescingTransport t = tx.get(isolated);
    if (t != null) {
      try {
        t.stop();
      } catch (RuntimeException ignored) {
      }
    }
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
    c.setBoolean(TransportConfig.NATIVE_TRANSPORT_KEY, false);
    c.setLong(TransportConfig.BATCH_MS_KEY, 5L);
    c.setInt(TransportConfig.IO_THREADS_KEY, 2);
    return c;
  }

  private static class Cluster {
    final List<RaftNodeImpl> nodes = new ArrayList<>();
    final ConcurrentHashMap<RaftEndpoint, SimpleStateMachine> stateMachines =
      new ConcurrentHashMap<>();
  }
}
