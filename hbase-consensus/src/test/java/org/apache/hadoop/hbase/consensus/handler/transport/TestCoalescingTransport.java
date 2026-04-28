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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.consensus.raft.GroupId;
import org.apache.hadoop.hbase.consensus.raft.MembershipChangeMode;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.QueryPolicy;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.report.RaftGroupMembers;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.report.RaftTerm;
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
 * small watermarks, and reconnect after the peer goes away.
 */
@Tag(SmallTests.TAG)
public class TestCoalescingTransport extends TestBase {

  private static final String GROUP = "g1";

  private final List<CoalescingTransport> transports = new ArrayList<>();

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

    // Lazy coalescing groups concurrent enqueues into one BATCH_APPEND frame per peer per drain
    // pass. The total number of received messages and per-message ordering is what we check, not
    // the wire-frame count.
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

    // Tiny watermarks force the channel unwritable quickly under load. The drain bails until
    // back-pressure clears and the next channelWritabilityChanged event picks up the leftover.
    Configuration conf = baseConf();
    conf.setInt(TransportConfig.WRITE_LOW_WM_BYTES_KEY, 256);
    conf.setInt(TransportConfig.WRITE_HIGH_WM_BYTES_KEY, 1024);
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

  /**
   * After a single AppendEntries, the receiver should observe the frame within the event-loop
   * scheduling window.
   */
  @Test
  public void testLoneEnqueueDrainsWithoutTimer() {
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

    // Warm up the channel.
    a.send(epB, factory.createVoteRequestBuilder().setGroupId(GROUP).setSender(epA).setTerm(1)
      .setLastLogTerm(0).setLastLogIndex(0L).setSticky(false).build());
    await().atMost(10, TimeUnit.SECONDS).until(() -> a.isReachable(epB));
    await().atMost(5, TimeUnit.SECONDS).until(() -> seenAtB.size() >= 1);
    seenAtB.clear();

    // Lazy coalescing schedules a drain on enqueue, so the receiver should observe the
    // AppendEntries
    // within the event-loop scheduling window.
    AppendEntriesRequest req = factory.createAppendEntriesRequestBuilder().setGroupId(GROUP)
      .setSender(epA).setTerm(1).setPreviousLogTerm(1).setPreviousLogIndex(0L).setCommitIndex(0L)
      .setLogEntries(List.of(factory.createLogEntryBuilder().setIndex(1L).setTerm(1)
        .setOperation(new byte[] { 0x01 }).build()))
      .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    long t0 = System.nanoTime();
    a.send(epB, req);
    await().atMost(2, TimeUnit.SECONDS).until(() -> seenAtB.size() >= 1);
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    assertThat(elapsedMillis)
      .as("lone enqueue must drain within event-loop scheduling latency, not a fixed timer "
        + "interval")
      .isLessThan(1_000L);
  }

  /**
   * Inbound dispatcher classifies decoded {@link RaftMessage}s into the dual executor lanes:
   * {@code AppendEntries*}, {@code InstallSnapshot*}, and {@code TriggerLeaderElection} on the bulk
   * lane ({@link RaftNodeExecutor#execute(Runnable)}); votes, pre-votes, leader heartbeats, and
   * heartbeat-acks on the control lane ({@link RaftNodeExecutor#executeControl(Runnable)}).
   * <p>
   * The test sends one wire frame of every supported {@link RaftMessage} type from A to B over a
   * real transport pair. The receiver registers a {@link ClassifyingRaftNode} that mirrors the
   * production routing in {@link RaftNodeImpl#handle(RaftMessage)} via the public classifier
   * {@link RaftNodeImpl#isControlLaneMessage(RaftMessage)} and forwards each task to a
   * {@link RecordingExecutor} which logs the lane each Java message class landed on. The
   * expected-lane mapping is hard-coded in the test so a regression in the production classifier
   * (e.g. demoting a heartbeat to bulk) breaks the test by design.
   */
  @Test
  public void testInboundDualLaneClassification() {
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

    RecordingExecutor exec = new RecordingExecutor();
    b.discoverNode(new ClassifyingRaftNode(GROUP, exec));

    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    LogEntry entry = factory.createLogEntryBuilder().setIndex(1L).setTerm(1)
      .setOperation(new byte[] { 0x7f }).build();
    RaftGroupMembersView membersView = factory.createRaftGroupMembersViewBuilder().setLogIndex(1L)
      .setMembers(List.of(epA, epB)).setVotingMembers(List.of(epA, epB)).build();

    // One of every Raft message type the inbound dispatcher decodes. Each is paired with the
    // expected executor lane the receiver must dispatch the handler on.
    List<MessageWithLane> messages = new ArrayList<>();
    messages
      .add(new MessageWithLane(factory.createVoteRequestBuilder().setGroupId(GROUP).setSender(epA)
        .setTerm(2).setLastLogTerm(1).setLastLogIndex(1L).setSticky(false).build(), Lane.CONTROL));
    messages.add(new MessageWithLane(factory.createVoteResponseBuilder().setGroupId(GROUP)
      .setSender(epA).setTerm(2).setGranted(true).build(), Lane.CONTROL));
    messages.add(new MessageWithLane(factory.createPreVoteRequestBuilder().setGroupId(GROUP)
      .setSender(epA).setTerm(3).setLastLogTerm(1).setLastLogIndex(1L).build(), Lane.CONTROL));
    messages.add(new MessageWithLane(factory.createPreVoteResponseBuilder().setGroupId(GROUP)
      .setSender(epA).setTerm(3).setGranted(true).build(), Lane.CONTROL));
    messages.add(new MessageWithLane(factory.createAppendEntriesRequestBuilder().setGroupId(GROUP)
      .setSender(epA).setTerm(2).setPreviousLogTerm(1).setPreviousLogIndex(0L).setCommitIndex(0L)
      .setLogEntries(List.of(entry)).setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L)
      .build(), Lane.BULK));
    messages.add(new MessageWithLane(factory.createAppendEntriesSuccessResponseBuilder()
      .setGroupId(GROUP).setSender(epA).setTerm(2).setLastLogIndex(1L).setQuerySequenceNumber(0L)
      .setFlowControlSequenceNumber(0L).build(), Lane.BULK));
    messages.add(new MessageWithLane(factory.createAppendEntriesFailureResponseBuilder()
      .setGroupId(GROUP).setSender(epA).setTerm(2).setExpectedNextIndex(1L)
      .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build(), Lane.BULK));
    messages.add(new MessageWithLane(factory.createInstallSnapshotRequestBuilder().setGroupId(GROUP)
      .setSender(epA).setTerm(2).setSenderLeader(true).setSnapshotTerm(1).setSnapshotIndex(1L)
      .setTotalSnapshotChunkCount(0).setSnapshotChunk(null).setSnapshottedMembers(List.of(epA, epB))
      .setGroupMembersView(membersView).setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L)
      .build(), Lane.BULK));
    messages
      .add(new MessageWithLane(factory.createInstallSnapshotResponseBuilder().setGroupId(GROUP)
        .setSender(epA).setTerm(2).setSnapshotIndex(1L).setRequestedSnapshotChunkIndex(0)
        .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build(), Lane.BULK));
    messages
      .add(new MessageWithLane(factory.createTriggerLeaderElectionRequestBuilder().setGroupId(GROUP)
        .setSender(epA).setTerm(2).setLastLogTerm(1).setLastLogIndex(1L).build(), Lane.CONTROL));

    for (MessageWithLane mwl : messages) {
      a.send(epB, mwl.message);
    }

    await().atMost(15, TimeUnit.SECONDS).until(() -> exec.totalDispatches() >= messages.size());

    // For each Java message type sent, exactly one dispatch on the expected lane must have been
    // recorded; no message must have crossed lanes.
    for (MessageWithLane mwl : messages) {
      Class<? extends RaftMessage> cls = mwl.message.getClass();
      long control = exec.countDispatches(Lane.CONTROL, cls);
      long bulk = exec.countDispatches(Lane.BULK, cls);
      assertThat(control + bulk)
        .as("exactly one dispatch must be recorded for %s", cls.getSimpleName()).isEqualTo(1L);
      switch (mwl.expected) {
        case CONTROL:
          assertThat(control).as("%s must route through executeControl", cls.getSimpleName())
            .isEqualTo(1L);
          break;
        case BULK:
          assertThat(bulk).as("%s must route through execute", cls.getSimpleName()).isEqualTo(1L);
          break;
        default:
          throw new AssertionError("unreachable");
      }
    }

    // Tighter end-to-end count check: the two lanes between them carry exactly the messages we
    // sent, no duplicates and nothing extra.
    assertThat(exec.totalDispatches()).isEqualTo(messages.size());
    assertThat(exec.countLane(Lane.CONTROL))
      .isEqualTo(messages.stream().filter(m -> m.expected == Lane.CONTROL).count());
    assertThat(exec.countLane(Lane.BULK))
      .isEqualTo(messages.stream().filter(m -> m.expected == Lane.BULK).count());
  }

  private enum Lane {
    CONTROL,
    BULK
  }

  private static final class MessageWithLane {
    final RaftMessage message;
    final Lane expected;

    MessageWithLane(RaftMessage message, Lane expected) {
      this.message = message;
      this.expected = expected;
    }
  }

  /**
   * Records, per (lane, message-class) pair, how many tasks the producing {@link RaftNode} has
   * forwarded. Only {@link #execute(Runnable)} and {@link #executeControl(Runnable)} are wired —
   * the other {@link RaftNodeExecutor} methods are unreachable in this test path.
   */
  private static final class RecordingExecutor implements RaftNodeExecutor {
    private final ConcurrentHashMap<Class<? extends RaftMessage>, AtomicInteger> controlCounts =
      new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<? extends RaftMessage>, AtomicInteger> bulkCounts =
      new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();

    void recordDispatch(Lane lane, Class<? extends RaftMessage> cls) {
      ConcurrentHashMap<Class<? extends RaftMessage>, AtomicInteger> map =
        lane == Lane.CONTROL ? controlCounts : bulkCounts;
      map.computeIfAbsent(cls, k -> new AtomicInteger()).incrementAndGet();
      total.incrementAndGet();
    }

    long countDispatches(Lane lane, Class<? extends RaftMessage> cls) {
      ConcurrentHashMap<Class<? extends RaftMessage>, AtomicInteger> map =
        lane == Lane.CONTROL ? controlCounts : bulkCounts;
      AtomicInteger c = map.get(cls);
      return c == null ? 0L : c.get();
    }

    long countLane(Lane lane) {
      ConcurrentHashMap<Class<? extends RaftMessage>, AtomicInteger> map =
        lane == Lane.CONTROL ? controlCounts : bulkCounts;
      long sum = 0L;
      for (AtomicInteger c : map.values()) {
        sum += c.get();
      }
      return sum;
    }

    int totalDispatches() {
      return total.get();
    }

    @Override
    public void execute(@NonNull Runnable task) {
      task.run();
    }

    @Override
    public void executeControl(@NonNull Runnable task) {
      task.run();
    }

    @Override
    public void submit(@NonNull Runnable task) {
      task.run();
    }

    @Override
    public void schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit timeUnit) {
      throw new UnsupportedOperationException("schedule is not used by this test");
    }
  }

  /**
   * Minimal {@link RaftNode} stub used by {@link #testInboundDualLaneClassification()}: exposes
   * {@code groupId} / {@link RaftNodeStatus#ACTIVE} status to the inbound dispatcher and forwards
   * every received message to a {@link RecordingExecutor} via the production
   * {@link RaftNodeImpl#isControlLaneMessage(RaftMessage)} classifier.
   */
  private static final class ClassifyingRaftNode implements RaftNode {
    private final GroupId groupId;
    private final RecordingExecutor executor;

    ClassifyingRaftNode(@NonNull String groupId, @NonNull RecordingExecutor executor) {
      this.groupId = GroupId.of(groupId);
      this.executor = executor;
    }

    @Override
    public void handle(@NonNull RaftMessage message) {
      Lane lane = RaftNodeImpl.isControlLaneMessage(message) ? Lane.CONTROL : Lane.BULK;
      Runnable record = () -> executor.recordDispatch(lane, message.getClass());
      if (lane == Lane.CONTROL) {
        executor.executeControl(record);
      } else {
        executor.execute(record);
      }
    }

    @NonNull
    @Override
    public Object getGroupId() {
      return groupId;
    }

    @NonNull
    @Override
    public RaftNodeStatus getStatus() {
      return RaftNodeStatus.ACTIVE;
    }

    @NonNull
    @Override
    public RaftEndpoint getLocalEndpoint() {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public RaftConfig getConfig() {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public RaftTerm getTerm() {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public RaftGroupMembers getInitialMembers() {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public RaftGroupMembers getCommittedMembers() {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public RaftGroupMembers getEffectiveMembers() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isLeaderWithValidLease(long nowMillis) {
      return false;
    }

    @Override
    public boolean isLeaderHeartbeatTimeoutElapsed() {
      return false;
    }

    @Override
    public boolean demoteToFollowerIfLeaseExpired() {
      return false;
    }

    @NonNull
    @Override
    public CompletableFuture<Ordered<Object>> start() {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public CompletableFuture<Ordered<Object>> terminate() {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public <T> CompletableFuture<Ordered<T>> replicate(@NonNull Object operation) {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public <T> CompletableFuture<Ordered<T>> query(@NonNull Object operation,
      @NonNull QueryPolicy queryPolicy, long minCommitIndex, long timeoutMillis) {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public CompletableFuture<Ordered<Object>> waitFor(long minCommitIndex, Duration timeout) {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public CompletableFuture<Ordered<RaftGroupMembers>> changeMembership(
      @NonNull RaftEndpoint endpoint, @NonNull MembershipChangeMode mode,
      long expectedGroupMembersCommitIndex) {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public CompletableFuture<Ordered<Object>> transferLeadership(@NonNull RaftEndpoint endpoint) {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public CompletableFuture<Ordered<RaftNodeReport>> getReport() {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public CompletableFuture<Ordered<RaftNodeReport>> takeSnapshot() {
      throw new UnsupportedOperationException();
    }
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
    assertThatThrownBy(() -> a.send(epA, ok)).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("itself");
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
    // The dispatcher requires a {@link GroupId} value-class instance so it can key its registry on
    // the wire ByteString (zero-copy) instead of decoding inbound bytes back to a String per
    // message.
    when(node.getGroupId()).thenReturn(GroupId.of(groupId));
    when(node.getStatus()).thenReturn(RaftNodeStatus.ACTIVE);
    Mockito.doAnswer((InvocationOnMock inv) -> {
      sink.add(inv.getArgument(0));
      return null;
    }).when(node).handle(Mockito.any(RaftMessage.class));
    return node;
  }

  private static Configuration baseConf() {
    Configuration c = HBaseConfiguration.create();
    c.setBoolean(TransportConfig.NATIVE_TRANSPORT_KEY, false);
    c.setInt(TransportConfig.IO_THREADS_KEY, 2);
    return c;
  }

  private static int ephemeralPort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      s.setReuseAddress(true);
      return s.getLocalPort();
    }
  }
}
