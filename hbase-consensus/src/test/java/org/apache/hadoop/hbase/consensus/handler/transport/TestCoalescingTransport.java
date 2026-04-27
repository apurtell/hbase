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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

/**
 * End-to-end tests for {@link CoalescingTransport} using two ephemeral-port instances on loopback.
 * Verifies round-trip messaging, per-peer FIFO ordering, append-batch coalescing, back-pressure on
 * small watermarks, testReconnect after the peer goes away, and integrity through the configured
 * payload compressor.
 */
@Tag(SmallTests.TAG)
public class TestCoalescingTransport extends TestBase {

  private static final String GROUP = "g1";

  private final List<CoalescingTransport> transports = new java.util.ArrayList<>();

  @AfterEach
  public void teardown() {
    for (CoalescingTransport t : transports) {
      try {
        t.stop();
      } catch (RuntimeException ignored) {
      }
    }
    transports.clear();
  }

  @Test
  public void testRoundTrip() {
    LocalRaftEndpoint epA = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epB = LocalRaftEndpoint.newEndpoint();

    Configuration conf = baseConf();
    Map<RaftEndpoint, InetSocketAddress> addrs = new HashMap<>();
    EndpointResolver resolver = ep -> {
      InetSocketAddress addr = addrs.get(ep);
      if (addr == null) {
        throw new UnknownEndpointException(ep);
      }
      return addr;
    };

    CoalescingTransport a = newStarted(epA, conf, resolver);
    CoalescingTransport b = newStarted(epB, conf, resolver);
    addrs.put(epA, a.getBindAddress());
    addrs.put(epB, b.getBindAddress());

    ConcurrentLinkedQueue<RaftMessage> seenAtB = new ConcurrentLinkedQueue<>();
    b.discoverNode(mockNode(GROUP, seenAtB));

    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    VoteRequest req = factory.createVoteRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(2)
      .setLastLogTerm(1).setLastLogIndex(5L).setSticky(false).build();

    a.send(epB, req);

    await().atMost(5, TimeUnit.SECONDS).until(() -> seenAtB.size() == 1);
    RaftMessage seen = seenAtB.poll();
    assertThat(seen).isInstanceOf(VoteRequest.class);
    VoteRequest got = (VoteRequest) seen;
    assertThat(got.getTerm()).isEqualTo(2);
    assertThat(got.getLastLogIndex()).isEqualTo(5L);
    assertThat(got.getGroupId().toString()).isEqualTo(GROUP);
  }

  @Test
  public void testFifoOrdering() {
    LocalRaftEndpoint epA = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epB = LocalRaftEndpoint.newEndpoint();

    Configuration conf = baseConf();
    Map<RaftEndpoint, InetSocketAddress> addrs = new HashMap<>();
    EndpointResolver resolver = ep -> {
      InetSocketAddress addr = addrs.get(ep);
      if (addr == null) {
        throw new UnknownEndpointException(ep);
      }
      return addr;
    };

    CoalescingTransport a = newStarted(epA, conf, resolver);
    CoalescingTransport b = newStarted(epB, conf, resolver);
    addrs.put(epA, a.getBindAddress());
    addrs.put(epB, b.getBindAddress());

    ConcurrentLinkedQueue<RaftMessage> seenAtB = new ConcurrentLinkedQueue<>();
    b.discoverNode(mockNode(GROUP, seenAtB));

    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    int total = 100;
    for (int i = 0; i < total; i++) {
      LogEntry e = factory.createLogEntryBuilder().setIndex(i + 1).setTerm(1)
        .setOperation(new byte[] { (byte) i }).build();
      AppendEntriesRequest req =
        factory.createAppendEntriesRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(1)
          .setPreviousLogTerm(1).setPreviousLogIndex(i).setCommitIndex(0L).setLogEntries(List.of(e))
          .setQuerySequenceNumber(i).setFlowControlSequenceNumber(i).build();
      a.send(epB, req);
    }

    await().atMost(10, TimeUnit.SECONDS).until(() -> seenAtB.size() >= total);
    int idx = 0;
    for (RaftMessage m : seenAtB) {
      assertThat(m).isInstanceOf(AppendEntriesRequest.class);
      AppendEntriesRequest aer = (AppendEntriesRequest) m;
      assertThat(aer.getLogEntries()).hasSize(1);
      assertThat(aer.getLogEntries().get(0).getIndex()).isEqualTo(idx + 1);
      idx++;
    }
    assertThat(idx).isEqualTo(total);
  }

  @Test
  public void testBatchCoalescing() {
    LocalRaftEndpoint epA = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epB = LocalRaftEndpoint.newEndpoint();

    // Big batch window so many enqueues land on the same flush tick.
    Configuration conf = baseConf();
    conf.setLong(TransportConfig.BATCH_MS_KEY, 200L);
    Map<RaftEndpoint, InetSocketAddress> addrs = new HashMap<>();
    EndpointResolver resolver = ep -> {
      InetSocketAddress addr = addrs.get(ep);
      if (addr == null) {
        throw new UnknownEndpointException(ep);
      }
      return addr;
    };

    CoalescingTransport a = newStarted(epA, conf, resolver);
    CoalescingTransport b = newStarted(epB, conf, resolver);
    addrs.put(epA, a.getBindAddress());
    addrs.put(epB, b.getBindAddress());

    ConcurrentLinkedQueue<RaftMessage> seenAtB = new ConcurrentLinkedQueue<>();
    b.discoverNode(mockNode(GROUP, seenAtB));

    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    int total = 50;
    for (int i = 0; i < total; i++) {
      LogEntry e = factory.createLogEntryBuilder().setIndex(i + 1).setTerm(1)
        .setOperation(new byte[] { (byte) i }).build();
      AppendEntriesRequest req =
        factory.createAppendEntriesRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(1)
          .setPreviousLogTerm(1).setPreviousLogIndex(i).setCommitIndex(0L).setLogEntries(List.of(e))
          .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
      a.send(epB, req);
    }

    await().atMost(10, TimeUnit.SECONDS).until(() -> seenAtB.size() >= total);
    assertThat(seenAtB).hasSize(total);
    int idx = 1;
    for (RaftMessage m : seenAtB) {
      assertThat(((AppendEntriesRequest) m).getLogEntries().get(0).getIndex()).isEqualTo(idx++);
    }
  }

  @Test
  public void testBackpressure() {
    LocalRaftEndpoint epA = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epB = LocalRaftEndpoint.newEndpoint();

    // Tiny watermarks force the channel unwritable quickly under load; the drain bails until
    // the kernel buffer drains and the next flush tick re-arms.
    Configuration conf = baseConf();
    conf.setInt(TransportConfig.WRITE_LOW_WM_BYTES_KEY, 256);
    conf.setInt(TransportConfig.WRITE_HIGH_WM_BYTES_KEY, 1024);
    conf.setLong(TransportConfig.BATCH_MS_KEY, 1L);
    Map<RaftEndpoint, InetSocketAddress> addrs = new HashMap<>();
    EndpointResolver resolver = ep -> {
      InetSocketAddress addr = addrs.get(ep);
      if (addr == null) {
        throw new UnknownEndpointException(ep);
      }
      return addr;
    };

    CoalescingTransport a = newStarted(epA, conf, resolver);
    CoalescingTransport b = newStarted(epB, conf, resolver);
    addrs.put(epA, a.getBindAddress());
    addrs.put(epB, b.getBindAddress());

    ConcurrentLinkedQueue<RaftMessage> seenAtB = new ConcurrentLinkedQueue<>();
    b.discoverNode(mockNode(GROUP, seenAtB));

    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    int total = 500;
    byte[] big = new byte[2048];
    Arrays.fill(big, (byte) 0xa5);
    for (int i = 0; i < total; i++) {
      LogEntry e =
        factory.createLogEntryBuilder().setIndex(i + 1).setTerm(1).setOperation(big).build();
      AppendEntriesRequest req =
        factory.createAppendEntriesRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(1)
          .setPreviousLogTerm(1).setPreviousLogIndex(i).setCommitIndex(0L).setLogEntries(List.of(e))
          .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
      a.send(epB, req);
    }

    await().atMost(30, TimeUnit.SECONDS).until(() -> seenAtB.size() >= total);
    assertThat(seenAtB).hasSize(total);
  }

  @Test
  public void testReconnect() throws IOException {
    LocalRaftEndpoint epA = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epB = LocalRaftEndpoint.newEndpoint();

    Configuration conf = baseConf();
    conf.setLong(TransportConfig.RECONNECT_BACKOFF_MIN_MS_KEY, 10L);
    conf.setLong(TransportConfig.RECONNECT_BACKOFF_MAX_MS_KEY, 50L);
    conf.setLong(TransportConfig.BATCH_MS_KEY, 5L);

    int port = ephemeralPort();
    Map<RaftEndpoint, InetSocketAddress> addrs = new HashMap<>();
    EndpointResolver resolver = ep -> {
      InetSocketAddress addr = addrs.get(ep);
      if (addr == null) {
        throw new UnknownEndpointException(ep);
      }
      return addr;
    };

    CoalescingTransport a = newStarted(epA, conf, resolver);
    CoalescingTransport b =
      newStarted(epB, conf, resolver, new InetSocketAddress("127.0.0.1", port));
    addrs.put(epA, a.getBindAddress());
    addrs.put(epB, b.getBindAddress());

    ConcurrentLinkedQueue<RaftMessage> seenAtB = new ConcurrentLinkedQueue<>();
    b.discoverNode(mockNode(GROUP, seenAtB));

    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    a.send(epB, factory.createVoteRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(1)
      .setLastLogTerm(0).setLastLogIndex(0L).setSticky(false).build());
    await().atMost(5, TimeUnit.SECONDS).until(() -> seenAtB.size() == 1);
    seenAtB.clear();

    b.stop();
    transports.remove(b);

    a.send(epB, factory.createVoteRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(2)
      .setLastLogTerm(0).setLastLogIndex(0L).setSticky(false).build());

    CoalescingTransport b2 =
      newStarted(epB, conf, resolver, new InetSocketAddress("127.0.0.1", port));
    ConcurrentLinkedQueue<RaftMessage> seenAtB2 = new ConcurrentLinkedQueue<>();
    b2.discoverNode(mockNode(GROUP, seenAtB2));

    a.send(epB, factory.createVoteRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(3)
      .setLastLogTerm(0).setLastLogIndex(0L).setSticky(false).build());

    await().atMost(15, TimeUnit.SECONDS).until(() -> !seenAtB2.isEmpty());
    boolean sawTerm3 = false;
    for (RaftMessage m : seenAtB2) {
      if (m instanceof VoteRequest && ((VoteRequest) m).getTerm() == 3) {
        sawTerm3 = true;
        break;
      }
    }
    assertThat(sawTerm3).as("VoteRequest term=3 must arrive after testReconnect").isTrue();
  }

  @Test
  public void testCompression() {
    LocalRaftEndpoint epA = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epB = LocalRaftEndpoint.newEndpoint();

    Configuration conf = baseConf();
    conf.set(TransportConfig.COMPRESSION_KEY, Compression.Algorithm.LZ4.getName());
    conf.set("hbase.io.compress.lz4.codec",
      "org.apache.hadoop.hbase.io.compress.aircompressor.Lz4Codec");
    Compression.Algorithm.LZ4.reload(conf);
    Map<RaftEndpoint, InetSocketAddress> addrs = new HashMap<>();
    EndpointResolver resolver = ep -> {
      InetSocketAddress addr = addrs.get(ep);
      if (addr == null) {
        throw new UnknownEndpointException(ep);
      }
      return addr;
    };

    CoalescingTransport a = newStarted(epA, conf, resolver);
    CoalescingTransport b = newStarted(epB, conf, resolver);
    addrs.put(epA, a.getBindAddress());
    addrs.put(epB, b.getBindAddress());

    ConcurrentLinkedQueue<RaftMessage> seenAtB = new ConcurrentLinkedQueue<>();
    b.discoverNode(mockNode(GROUP, seenAtB));

    byte[] payload = new byte[8192];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i & 0xff);
    }
    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    LogEntry e =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(payload).build();
    AppendEntriesRequest req =
      factory.createAppendEntriesRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(1)
        .setPreviousLogTerm(1).setPreviousLogIndex(0L).setCommitIndex(0L).setLogEntries(List.of(e))
        .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    a.send(epB, req);

    await().atMost(5, TimeUnit.SECONDS).until(() -> !seenAtB.isEmpty());
    RaftMessage seen = seenAtB.poll();
    assertThat(seen).isInstanceOf(AppendEntriesRequest.class);
    LogEntry receivedEntry = ((AppendEntriesRequest) seen).getLogEntries().get(0);
    assertThat(receivedEntry.getIndex()).isEqualTo(1L);
    assertThat(receivedEntry.getOperation()).isInstanceOf(byte[].class);
    assertThat((byte[]) receivedEntry.getOperation()).containsExactly(payload);
  }

  @Test
  public void testRejectSelfDropUnknown() {
    LocalRaftEndpoint epA = LocalRaftEndpoint.newEndpoint();
    LocalRaftEndpoint epUnknown = LocalRaftEndpoint.newEndpoint();
    Configuration conf = baseConf();
    EndpointResolver empty = ep -> {
      throw new UnknownEndpointException(ep);
    };
    CoalescingTransport a = newStarted(epA, conf, empty);

    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    AppendEntriesSuccessResponse ok = factory.createAppendEntriesSuccessResponseBuilder()
      .setGroupId(GROUP).setSender(epA).setTerm(1).setLastLogIndex(0L).setQuerySequenceNumber(0L)
      .setFlowControlSequenceNumber(0L).build();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> a.send(epA, ok))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("itself");
    a.send(epUnknown, ok);
    assertThat(a.isReachable(epUnknown)).isFalse();
  }

  private CoalescingTransport newStarted(RaftEndpoint local, Configuration conf,
    EndpointResolver resolver) {
    return newStarted(local, conf, resolver, new InetSocketAddress("127.0.0.1", 0));
  }

  private CoalescingTransport newStarted(RaftEndpoint local, Configuration conf,
    EndpointResolver resolver, InetSocketAddress bind) {
    CoalescingTransport t =
      new CoalescingTransport(local, bind, resolver, OperationCodecs.defaultCodecs(), conf);
    transports.add(t);
    t.start();
    return t;
  }

  private static RaftNode mockNode(String groupId, ConcurrentLinkedQueue<RaftMessage> sink) {
    RaftNode node = Mockito.mock(RaftNode.class);
    when(node.getGroupId()).thenReturn(groupId);
    when(node.getStatus()).thenReturn(RaftNodeStatus.ACTIVE);
    Mockito.doAnswer((InvocationOnMock inv) -> {
      sink.add(inv.getArgument(0));
      return null;
    }).when(node).handle(Mockito.any(RaftMessage.class));
    return node;
  }

  private static Configuration baseConf() {
    Configuration c = HBaseConfiguration.create();
    // Keep tests Linux-portable: don't try to load Epoll JNI on macOS CI where it's missing.
    c.setBoolean(TransportConfig.NATIVE_TRANSPORT_KEY, false);
    c.setLong(TransportConfig.BATCH_MS_KEY, 5L);
    c.setInt(TransportConfig.IO_THREADS_KEY, 2);
    return c;
  }

  private static int ephemeralPort() throws IOException {
    try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
      s.setReuseAddress(true);
      return s.getLocalPort();
    }
  }
}
