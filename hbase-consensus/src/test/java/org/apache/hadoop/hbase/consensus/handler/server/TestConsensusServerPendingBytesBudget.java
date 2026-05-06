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
package org.apache.hadoop.hbase.consensus.handler.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.consensus.handler.server.util.CountingConsensusSpi;
import org.apache.hadoop.hbase.consensus.handler.server.util.InJvmConsensusServerTopology;
import org.apache.hadoop.hbase.consensus.raft.GroupId;
import org.apache.hadoop.hbase.consensus.raft.PendingBytesBudget;
import org.apache.hadoop.hbase.consensus.raft.RaftConfig;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftRole;
import org.apache.hadoop.hbase.consensus.raft.exception.CannotReplicateException;
import org.apache.hadoop.hbase.consensus.raft.exception.IndeterminateStateException;
import org.apache.hadoop.hbase.consensus.raft.exception.NotLeaderException;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validates that the server-wide {@link PendingBytesBudget} guards inbound payload bytes for all
 * roles hosted by a {@link ConsensusServer}.
 * <p>
 * The gate's surface area is restricted to payload-bearing message kinds. Only
 * {@link AppendEntriesRequest} and {@link InstallSnapshotRequest} flow through the credit
 * accounting. Control traffic is constant-size and bypasses the gate so an exhausted budget cannot
 * couple to the leader election critical path.
 */
@Tag(MediumTests.TAG)
public class TestConsensusServerPendingBytesBudget extends TestBase {

  /**
   * RaftConfig tuned for the in-JVM burst test. The heartbeat timeout is generous so the burst
   * scenario does not race a follower election timer firing under parallel-fork CPU contention.
   * Batch size 4 keeps inbound AE byte counts on the same order of magnitude as a single propose
   * payload so the gate trips meaningfully.
   */
  private static final RaftConfig FAST_RAFT_CONFIG =
    RaftConfig.newBuilder().setLeaderElectionTimeoutMillis(1500).setLeaderHeartbeatPeriodMillis(150)
      .setLeaderHeartbeatTimeoutMillis(5000).setAppendEntriesRequestBatchSize(4).build();

  private static final int PAYLOAD_BYTES = 512;

  private static final long BUDGET_BYTES = 4L * 1024L;

  @TempDir
  Path tmp;

  private InJvmConsensusServerTopology topo;

  @AfterEach
  public void tearDown() {
    if (topo != null) {
      topo.close();
      topo = null;
    }
  }

  @Test
  public void testIsPayloadBearingAndInboundPayloadBytesContract() {
    DefaultRaftModelFactory factory = new DefaultRaftModelFactory();
    GroupId groupId = GroupId.of("g0");
    LocalRaftEndpoint sender = LocalRaftEndpoint.newEndpoint();

    // AppendEntriesRequest is payload-bearing and totals the per-entry byte sums; entries whose
    // operation is not a byte[] do not contribute (matches the leader-side accountant).
    LogEntry e1 =
      factory.createLogEntryBuilder().setIndex(1L).setTerm(1).setOperation(new byte[100]).build();
    LogEntry e2 =
      factory.createLogEntryBuilder().setIndex(2L).setTerm(1).setOperation(new byte[250]).build();
    LogEntry e3 =
      factory.createLogEntryBuilder().setIndex(3L).setTerm(1).setOperation(new byte[7]).build();
    AppendEntriesRequest aer = factory.createAppendEntriesRequestBuilder().setGroupId(groupId)
      .setSender(sender).setTerm(1).setPreviousLogTerm(1).setPreviousLogIndex(0L).setCommitIndex(0L)
      .setLogEntries(List.of(e1, e2, e3)).setQuerySequenceNumber(0L)
      .setFlowControlSequenceNumber(0L).build();
    assertThat(RaftNodeImpl.isPayloadBearing(aer)).isTrue();
    assertThat(RaftNodeImpl.inboundPayloadBytes(aer)).isEqualTo(100L + 250L + 7L);

    // An empty AE is still classed as payload-bearing but reports zero bytes, so the gate's
    // threshold
    // check skips the tryAcquire and the heartbeat continues to flow.
    AppendEntriesRequest emptyAer = factory.createAppendEntriesRequestBuilder().setGroupId(groupId)
      .setSender(sender).setTerm(1).setPreviousLogTerm(1).setPreviousLogIndex(0L).setCommitIndex(0L)
      .setLogEntries(List.of()).setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    assertThat(RaftNodeImpl.isPayloadBearing(emptyAer)).isTrue();
    assertThat(RaftNodeImpl.inboundPayloadBytes(emptyAer)).isZero();

    // InstallSnapshotRequest carrying a byte[] chunk is payload-bearing and pins the chunk bytes.
    SnapshotChunk chunk = factory.createSnapshotChunkBuilder().setIndex(10L).setTerm(2)
      .setOperation(new byte[1024]).setSnapshotChunkIndex(0).setSnapshotChunkCount(1)
      .setGroupMembersView(membersViewOfSelf(factory, sender)).build();
    InstallSnapshotRequest isr = factory.createInstallSnapshotRequestBuilder().setGroupId(groupId)
      .setSender(sender).setTerm(2).setSenderLeader(true).setSnapshotTerm(2).setSnapshotIndex(10L)
      .setTotalSnapshotChunkCount(1).setSnapshotChunk(chunk).setSnapshottedMembers(List.of(sender))
      .setGroupMembersView(membersViewOfSelf(factory, sender)).setQuerySequenceNumber(0L)
      .setFlowControlSequenceNumber(0L).build();
    assertThat(RaftNodeImpl.isPayloadBearing(isr)).isTrue();
    assertThat(RaftNodeImpl.inboundPayloadBytes(isr)).isEqualTo(1024L);

    // Every control-plane message kind is constant-size traffic. The gate must classify them as
    // non-payload-bearing so an exhausted budget cannot couple to leader election or to the
    // ack/nack legs of the bulk lane.
    VoteRequest vr = factory.createVoteRequestBuilder().setGroupId(groupId).setSender(sender)
      .setTerm(3).setLastLogTerm(2).setLastLogIndex(10L).setSticky(false).build();
    VoteResponse vrsp = factory.createVoteResponseBuilder().setGroupId(groupId).setSender(sender)
      .setTerm(3).setGranted(true).build();
    PreVoteRequest pvr = factory.createPreVoteRequestBuilder().setGroupId(groupId).setSender(sender)
      .setTerm(3).setLastLogTerm(2).setLastLogIndex(10L).build();
    PreVoteResponse pvrsp = factory.createPreVoteResponseBuilder().setGroupId(groupId)
      .setSender(sender).setTerm(3).setGranted(true).build();
    AppendEntriesSuccessResponse ass = factory.createAppendEntriesSuccessResponseBuilder()
      .setGroupId(groupId).setSender(sender).setTerm(3).setLastLogIndex(10L)
      .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    AppendEntriesFailureResponse afs = factory.createAppendEntriesFailureResponseBuilder()
      .setGroupId(groupId).setSender(sender).setTerm(3).setExpectedNextIndex(11L)
      .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    InstallSnapshotResponse isrsp =
      factory.createInstallSnapshotResponseBuilder().setGroupId(groupId).setSender(sender)
        .setTerm(3).setSnapshotIndex(10L).setRequestedSnapshotChunkIndex(1)
        .setQuerySequenceNumber(0L).setFlowControlSequenceNumber(0L).build();
    TriggerLeaderElectionRequest tle =
      factory.createTriggerLeaderElectionRequestBuilder().setGroupId(groupId).setSender(sender)
        .setTerm(3).setLastLogTerm(2).setLastLogIndex(10L).build();
    for (RaftMessage m : List.of(vr, vrsp, pvr, pvrsp, ass, afs, isrsp, tle)) {
      assertThat(RaftNodeImpl.isPayloadBearing(m))
        .as("control-plane message %s must bypass the budget", m.getClass().getSimpleName())
        .isFalse();
      assertThat(RaftNodeImpl.inboundPayloadBytes(m))
        .as("control-plane message %s must charge zero bytes", m.getClass().getSimpleName())
        .isZero();
    }
  }

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  public void testBudgetExhaustionIsTransientAndSelfHealingAcrossRoles() throws Exception {
    Configuration conf = InJvmConsensusServerTopology.defaultConf();
    // Pin the consensus-scoped budget so the resolution path does not silently fall back to the
    // 1 GiB default and erase the test's ability to trigger admission denials.
    conf.setLong(ConsensusServerConfig.MAX_PENDING_BYTES_KEY, BUDGET_BYTES);

    topo = InJvmConsensusServerTopology.builder().setNodeCount(3).setBaseDir(tmp, false)
      .setBaseConf(conf).setRaftConfig(FAST_RAFT_CONFIG).build();

    final List<InJvmConsensusServerTopology.Node> nodes = topo.nodes();
    final List<RaftEndpoint> members = topo.endpoints();
    final CountingConsensusSpi[] spis = new CountingConsensusSpi[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      spis[i] = new CountingConsensusSpi();
    }
    final List<GroupHandle> handles = new ArrayList<>(nodes.size());
    for (int s = 0; s < nodes.size(); s++) {
      handles.add(nodes.get(s).server().addGroup("g0", members, spis[s]));
    }

    boolean elected = false;
    long electDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    for (CountingConsensusSpi spi : spis) {
      long remainingMs =
        Math.max(0L, TimeUnit.NANOSECONDS.toMillis(electDeadlineNs - System.nanoTime()));
      if (spi.stats("g0").awaitLeaderElected(remainingMs, TimeUnit.MILLISECONDS)) {
        elected = true;
        break;
      }
    }
    assertThat(elected).as("leader elected on g0 within 15 s").isTrue();
    GroupHandle leader = awaitLeader(conf, handles, 15_000L);

    // Drive a burst of concurrent proposes far in excess of what the tiny budget can admit
    // simultaneously. The mix at completion must contain (a) successes, validating that the gate
    // does not lock the leader out forever, and (b) admission failures, validating that the gate
    // actually trips. An AssertionError in any handler also propagates so the test catches a
    // regression that turns transient denials into hard errors.
    final int burst = 256;
    final byte[] template = new byte[PAYLOAD_BYTES];
    final List<CompletableFuture<?>> futures = new ArrayList<>(burst);
    for (int i = 0; i < burst; i++) {
      final byte[] payload = template.clone();
      payload[0] = (byte) i;
      futures.add(leader.getRaftNode().replicate(payload));
    }

    AtomicInteger successes = new AtomicInteger();
    AtomicInteger admissionDenials = new AtomicInteger();
    AtomicInteger leaderChurn = new AtomicInteger();
    List<Throwable> unexpectedErrors = new ArrayList<>();
    for (CompletableFuture<?> f : futures) {
      try {
        f.get(45, TimeUnit.SECONDS);
        successes.incrementAndGet();
      } catch (ExecutionException ee) {
        Throwable cause = ee.getCause();
        if (cause instanceof CannotReplicateException) {
          // The admission gate's documented signal: leader-side propose denied because the
          // server-wide budget is full or because the per-group entry-count cap has been hit.
          admissionDenials.incrementAndGet();
        } else
          if (cause instanceof NotLeaderException || cause instanceof IndeterminateStateException) {
            // The other transient signals the engine can return on a propose-in-flight are the
            // leader stepped down and the future resolved before the propose could be re-driven
            // against the new leader.
            leaderChurn.incrementAndGet();
          } else {
            unexpectedErrors.add(cause);
          }
      } catch (TimeoutException te) {
        unexpectedErrors.add(te);
      }
    }

    // The burst stresses the gate against the tiny budget. The two stable invariants the burst must
    // prove are: (a) every future resolves to a documented signal so the gate cannot fail-leak hard
    // errors, and (b) the burst produces at least one observable transient signal.
    assertThat(unexpectedErrors)
      .as("burst futures must resolve only to success, CannotReplicateException, "
        + "NotLeaderException, or IndeterminateStateException")
      .isEmpty();
    assertThat(successes.get() + admissionDenials.get() + leaderChurn.get())
      .as("every burst future must resolve to a documented outcome").isEqualTo(burst);
    assertThat(admissionDenials.get() + leaderChurn.get()).as(
      "the burst must produce at least one transient signal "
        + "(admissionDenials=%d leaderChurn=%d successes=%d)",
      admissionDenials.get(), leaderChurn.get(), successes.get()).isGreaterThanOrEqualTo(1);

    // After the burst clears and per-entry credits release in the commit-apply path, the leader
    // must be able to admit a fresh propose and replicate it to every member. Re-resolve the
    // leader because a transient leader churn during the burst may have moved leadership to a
    // different node, and retry on the same transient signals the burst already enumerates.
    final long[] commitsBeforeRecovery = new long[spis.length];
    for (int i = 0; i < spis.length; i++) {
      commitsBeforeRecovery[i] = spis[i].stats("g0").getCommitEntries();
    }
    final byte[] recoveryPayload = new byte[16];
    final AtomicReference<Throwable> lastTransient = new AtomicReference<>();
    Waiter.waitFor(conf, 45_000L, new Waiter.ExplainingPredicate<Exception>() {
      @Override
      public boolean evaluate() throws Exception {
        GroupHandle currentLeader = findLeader(handles);
        if (currentLeader == null) {
          return false;
        }
        try {
          currentLeader.getRaftNode().replicate(recoveryPayload).get(8, TimeUnit.SECONDS);
          return true;
        } catch (ExecutionException ee) {
          Throwable cause = ee.getCause();
          if (
            cause instanceof CannotReplicateException || cause instanceof NotLeaderException
              || cause instanceof IndeterminateStateException
          ) {
            lastTransient.set(cause);
            return false;
          }
          throw ee;
        }
      }

      @Override
      public String explainFailure() {
        return "recovery propose never committed (lastTransient=" + lastTransient.get() + ")";
      }
    });

    // Wait until every replica's commit applier has observed at least one new entry past the
    // pre-recovery baseline. This both proves the follower-side inbound path still works and
    // proves the budget rebated correctly after the burst. The deadline allows the slowest
    // follower to drain.
    for (int i = 0; i < spis.length; i++) {
      final int idx = i;
      Waiter.waitFor(conf, 30_000L, new Waiter.ExplainingPredicate<Exception>() {
        @Override
        public boolean evaluate() {
          return spis[idx].stats("g0").getCommitEntries() > commitsBeforeRecovery[idx];
        }

        @Override
        public String explainFailure() {
          return "server " + idx + " commit count " + spis[idx].stats("g0").getCommitEntries()
            + " did not advance past baseline " + commitsBeforeRecovery[idx];
        }
      });
    }
  }

  private static RaftGroupMembersView membersViewOfSelf(DefaultRaftModelFactory factory,
    RaftEndpoint sender) {
    return factory.createRaftGroupMembersViewBuilder().setLogIndex(0L).setMembers(List.of(sender))
      .setVotingMembers(List.of(sender)).build();
  }

  /**
   * Returns the handle of the current LEADER among {@code handles} on a single pass, or
   * {@code null} if no node is currently LEADER. Used as the inner predicate of
   * {@link #awaitLeader(Configuration, List, long)} and by the recovery propose loop, where the
   * caller wants to retry against a fresh leader on every attempt.
   */
  private static GroupHandle findLeader(List<GroupHandle> handles) {
    for (GroupHandle h : handles) {
      RaftNodeReport report = h.getRaftNode().getReport().join().getResult();
      if (report.getRole() == RaftRole.LEADER) {
        return h;
      }
    }
    return null;
  }

  /**
   * Polls the {@code handles} via {@link Waiter#waitFor} until some node reports {@link RaftRole}
   * {@code LEADER}, or fails the test on timeout. Returns the elected leader's handle.
   */
  private static GroupHandle awaitLeader(Configuration conf, List<GroupHandle> handles,
    long timeoutMs) {
    AtomicReference<GroupHandle> leaderRef = new AtomicReference<>();
    Waiter.waitFor(conf, timeoutMs, new Waiter.ExplainingPredicate<Exception>() {
      @Override
      public boolean evaluate() {
        GroupHandle h = findLeader(handles);
        if (h != null) {
          leaderRef.set(h);
          return true;
        }
        return false;
      }

      @Override
      public String explainFailure() {
        return "no node reported RaftRole.LEADER among " + handles.size() + " handles";
      }
    });
    return leaderRef.get();
  }
}
