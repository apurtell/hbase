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
package org.apache.hadoop.hbase.consensus.raft.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.RaftNodeStatus;
import org.apache.hadoop.hbase.consensus.raft.executor.impl.DefaultRaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;
import org.apache.hadoop.hbase.consensus.raft.model.groupop.UpdateRaftGroupMembersOp.UpdateRaftGroupMembersOpBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.impl.DefaultRaftModelFactory;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry.LogEntryBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk.SnapshotChunkBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry.SnapshotEntryBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesFailureResponse.AppendEntriesFailureResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesRequest;
import org.apache.hadoop.hbase.consensus.raft.model.message.AppendEntriesSuccessResponse;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotRequest.InstallSnapshotRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.InstallSnapshotResponse.InstallSnapshotResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteRequest.PreVoteRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.PreVoteResponse.PreVoteResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.model.message.TriggerLeaderElectionRequest.TriggerLeaderElectionRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteRequest.VoteRequestBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.message.VoteResponse.VoteResponseBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReport;
import org.apache.hadoop.hbase.consensus.raft.report.RaftNodeReportListener;
import org.apache.hadoop.hbase.consensus.raft.statemachine.StateMachine;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class RaftNodeLifecycleAwareTest extends BaseTest {
  private final RaftEndpoint localEndpoint = LocalRaftEndpoint.newEndpoint();
  private final List<RaftEndpoint> initialMembers =
    Arrays.asList(localEndpoint, LocalRaftEndpoint.newEndpoint(), LocalRaftEndpoint.newEndpoint());
  private final DelegatingRaftNodeExecutor executor = new DelegatingRaftNodeExecutor();
  private final NopTransport transport = new NopTransport();
  private final NopStateMachine stateMachine = new NopStateMachine();
  private final DelegatingRaftModelFactory modelFactory = new DelegatingRaftModelFactory();
  private final NopRaftNodeReportListener reportListener = new NopRaftNodeReportListener();
  private final NopRaftStore store = new NopRaftStore();
  private RaftNode raftNode;

  @BeforeEach
  public void init() {
    raftNode = RaftNode.newBuilder().setGroupId("default").setLocalEndpoint(localEndpoint)
      .setInitialGroupMembers(initialMembers).setExecutor(executor).setTransport(transport)
      .setStateMachine(stateMachine).setModelFactory(modelFactory)
      .setRaftNodeReportListener(reportListener).setStore(store).build();
  }

  @AfterEach
  public void tearDown() {
    if (raftNode != null) {
      raftNode.terminate();
    }
  }

  @Test
  public void executorStart() {
    raftNode.start().join();
    assertThat(executor.startCall).isGreaterThan(0);
    assertThat(executor.executeCall).isGreaterThan(0);
    assertThat(executor.executeCall).isLessThan(executor.startCall);
  }

  @Test
  public void executorTerminate() {
    raftNode.start().join();
    raftNode.terminate().join();
    assertThat(executor.startCall).isGreaterThan(0);
    assertThat(executor.executeCall).isGreaterThan(0);
    assertThat(executor.terminateCall).isGreaterThan(0);
    assertThat(executor.executeCall).isLessThan(executor.startCall);
    assertThat(executor.lastExecuteCall).isLessThan(executor.terminateCall);
  }

  @Test
  public void transportStart() {
    raftNode.start().join();
    assertThat(transport.startCall).isGreaterThan(0);
    assertThat(transport.sendCall).isGreaterThan(0);
    assertThat(transport.startCall).isLessThan(transport.sendCall);
  }

  @Test
  public void transportTerminate() {
    raftNode.start().join();
    raftNode.terminate().join();
    assertThat(transport.startCall).isGreaterThan(0);
    assertThat(transport.sendCall).isGreaterThan(0);
    assertThat(transport.terminateCall).isGreaterThan(0);
    assertThat(transport.startCall).isLessThan(transport.sendCall);
    assertThat(transport.sendCall).isLessThan(transport.terminateCall);
    assertThat(transport.lastSendCall).isLessThan(transport.terminateCall);
  }

  @Test
  public void stateMachineStart() {
    raftNode.start().join();
    assertThat(stateMachine.startCall).isGreaterThan(0);
  }

  @Test
  public void stateMachineTerminate() {
    raftNode.start().join();
    raftNode.terminate().join();
    assertThat(stateMachine.startCall).isGreaterThan(0);
    assertThat(stateMachine.terminateCall).isGreaterThan(0);
    assertThat(stateMachine.startCall).isLessThan(stateMachine.terminateCall);
  }

  @Test
  public void modelFactoryStart() {
    raftNode.start().join();
    assertThat(modelFactory.startCall).isGreaterThan(0);
    assertThat(modelFactory.createCall).isGreaterThan(0);
    assertThat(modelFactory.startCall).isLessThan(modelFactory.createCall);
  }

  @Test
  public void modelFactoryTerminate() {
    raftNode.start().join();
    raftNode.terminate().join();
    assertThat(modelFactory.startCall).isGreaterThan(0);
    assertThat(modelFactory.createCall).isGreaterThan(0);
    assertThat(modelFactory.terminateCall).isGreaterThan(0);
    assertThat(modelFactory.startCall).isLessThan(modelFactory.createCall);
    assertThat(modelFactory.createCall).isLessThan(modelFactory.terminateCall);
    assertThat(modelFactory.lastCreateCall).isLessThan(modelFactory.terminateCall);
  }

  @Test
  public void reportListenerStart() {
    raftNode.start().join();
    assertThat(reportListener.startCall).isGreaterThan(0);
    assertThat(reportListener.acceptCall).isGreaterThan(0);
    assertThat(reportListener.startCall).isLessThan(reportListener.acceptCall);
  }

  @Test
  public void reportListenerTerminate() {
    raftNode.start().join();
    raftNode.terminate().join();
    assertThat(reportListener.startCall).isGreaterThan(0);
    assertThat(reportListener.acceptCall).isGreaterThan(0);
    assertThat(reportListener.terminateCall).isGreaterThan(0);
    assertThat(reportListener.startCall).isLessThan(reportListener.acceptCall);
    assertThat(reportListener.acceptCall).isLessThan(reportListener.terminateCall);
    assertThat(reportListener.lastAcceptCall).isLessThan(reportListener.terminateCall);
  }

  @Test
  public void storeStart() {
    raftNode.start().join();
    assertThat(store.startCall).isGreaterThan(0);
    assertThat(store.persistCall).isGreaterThan(0);
    assertThat(store.startCall).isLessThan(store.persistCall);
  }

  @Test
  public void storeTerminate() {
    raftNode.start().join();
    raftNode.terminate().join();
    assertThat(store.startCall).isGreaterThan(0);
    assertThat(store.persistCall).isGreaterThan(0);
    assertThat(store.terminateCall).isGreaterThan(0);
    assertThat(store.startCall).isLessThan(store.persistCall);
    assertThat(store.persistCall).isLessThan(store.terminateCall);
    assertThat(store.lastPersistCall).isLessThan(store.terminateCall);
  }

  @Test
  public void terminateAllOnStartFailure() {
    stateMachine.failOnStart = true;
    try {
      raftNode.start().join();
      fail("Start should fail when any component fails on start");
    } catch (CompletionException ignored) {
    }
    assertThat(raftNode.getStatus()).isEqualTo(RaftNodeStatus.TERMINATED);
    assertThat(stateMachine.terminateCall).isGreaterThan(0);
    if (executor.startCall > 0) {
      assertThat(executor.terminateCall).isGreaterThan(0);
    }
    if (transport.startCall > 0) {
      assertThat(transport.terminateCall).isGreaterThan(0);
    }
    if (store.startCall > 0) {
      assertThat(store.terminateCall).isGreaterThan(0);
    }
    if (modelFactory.startCall > 0) {
      assertThat(modelFactory.terminateCall).isGreaterThan(0);
    }
    if (reportListener.startCall > 0) {
      assertThat(reportListener.terminateCall).isGreaterThan(0);
    }
  }

  @Test
  public void terminateAllOnStartAndTerminateFailure() {
    stateMachine.failOnStart = true;
    stateMachine.failOnTerminate = true;
    try {
      raftNode.start().join();
      fail("Start should fail when any component fails on start");
    } catch (CompletionException ignored) {
    }
    assertThat(raftNode.getStatus()).isEqualTo(RaftNodeStatus.TERMINATED);
    assertThat(stateMachine.terminateCall).isGreaterThan(0);
    if (executor.startCall > 0) {
      assertThat(executor.terminateCall).isGreaterThan(0);
    }
    if (transport.startCall > 0) {
      assertThat(transport.terminateCall).isGreaterThan(0);
    }
    if (store.startCall > 0) {
      assertThat(store.terminateCall).isGreaterThan(0);
    }
    if (modelFactory.startCall > 0) {
      assertThat(modelFactory.terminateCall).isGreaterThan(0);
    }
    if (reportListener.startCall > 0) {
      assertThat(reportListener.terminateCall).isGreaterThan(0);
    }
  }

  private static class NopTransport implements Transport, RaftNodeLifecycleAware {
    private volatile int startCall;
    private volatile int terminateCall;
    private volatile int sendCall;
    private volatile int lastSendCall;
    private volatile int callOrder;

    @Override
    public void onRaftNodeStart() {
      if (startCall == 0) {
        startCall = ++callOrder;
      }
    }

    @Override
    public void onRaftNodeTerminate() {
      if (terminateCall == 0) {
        terminateCall = ++callOrder;
      }
    }

    @Override
    public void send(@NonNull RaftEndpoint target, @NonNull RaftMessage message) {
      lastSendCall = ++callOrder;
      if (sendCall == 0) {
        sendCall = lastSendCall;
      }
    }

    @Override
    public boolean isReachable(@NonNull RaftEndpoint endpoint) {
      return false;
    }
  }

  private static class NopStateMachine implements StateMachine, RaftNodeLifecycleAware {
    private volatile int startCall;
    private volatile int terminateCall;
    private volatile int callOrder;
    private volatile boolean failOnStart;
    private volatile boolean failOnTerminate;

    @Override
    public void onRaftNodeStart() {
      if (startCall == 0) {
        startCall = ++callOrder;
      }
      if (failOnStart) {
        throw new RuntimeException("failed on purpose!");
      }
    }

    @Override
    public void onRaftNodeTerminate() {
      if (terminateCall == 0) {
        terminateCall = ++callOrder;
      }
      if (failOnTerminate) {
        throw new RuntimeException("failed on purpose!");
      }
    }

    @Override
    public Object runOperation(long commitIndex, @NonNull Object operation) {
      return null;
    }

    @Override
    public void takeSnapshot(long commitIndex, Consumer<Object> snapshotChunkConsumer) {
    }

    @Override
    public void installSnapshot(long commitIndex, @NonNull List<Object> snapshotChunks) {
    }

    @NonNull
    @Override
    public Object getNewTermOperation() {
      return null;
    }
  }

  private static class DelegatingRaftNodeExecutor extends DefaultRaftNodeExecutor
    implements RaftNodeLifecycleAware {
    private volatile int startCall;
    private volatile int terminateCall;
    private volatile int executeCall;
    private volatile int lastExecuteCall;
    private volatile int callOrder;

    @Override
    public void onRaftNodeStart() {
      if (startCall == 0) {
        startCall = ++callOrder;
      }
    }

    @Override
    public void onRaftNodeTerminate() {
      if (terminateCall == 0) {
        terminateCall = ++callOrder;
      }
    }

    @Override
    public void execute(@NonNull Runnable task) {
      lastExecuteCall = ++callOrder;
      if (executeCall == 0) {
        executeCall = lastExecuteCall;
      }
      super.execute(task);
    }

    @Override
    public void submit(@NonNull Runnable task) {
      lastExecuteCall = ++callOrder;
      if (executeCall == 0) {
        executeCall = lastExecuteCall;
      }
      super.submit(task);
    }

    @Override
    public void schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit timeUnit) {
      lastExecuteCall = ++callOrder;
      if (executeCall == 0) {
        executeCall = lastExecuteCall;
      }
      super.schedule(task, delay, timeUnit);
    }
  }

  public static class DelegatingRaftModelFactory extends DefaultRaftModelFactory
    implements RaftNodeLifecycleAware {
    private volatile int startCall;
    private volatile int terminateCall;
    private volatile int createCall;
    private volatile int lastCreateCall;
    private volatile int callOrder;

    @Override
    public void onRaftNodeStart() {
      if (startCall == 0) {
        startCall = ++callOrder;
      }
    }

    @Override
    public void onRaftNodeTerminate() {
      if (terminateCall == 0) {
        terminateCall = ++callOrder;
      }
    }

    @NonNull
    @Override
    public LogEntryBuilder createLogEntryBuilder() {
      recordCall();
      return super.createLogEntryBuilder();
    }

    @NonNull
    @Override
    public SnapshotEntryBuilder createSnapshotEntryBuilder() {
      recordCall();
      return super.createSnapshotEntryBuilder();
    }

    @NonNull
    @Override
    public SnapshotChunkBuilder createSnapshotChunkBuilder() {
      recordCall();
      return super.createSnapshotChunkBuilder();
    }

    @NonNull
    @Override
    public AppendEntriesRequest.AppendEntriesRequestBuilder createAppendEntriesRequestBuilder() {
      recordCall();
      return super.createAppendEntriesRequestBuilder();
    }

    @NonNull
    @Override
    public AppendEntriesSuccessResponse.AppendEntriesSuccessResponseBuilder
      createAppendEntriesSuccessResponseBuilder() {
      recordCall();
      return super.createAppendEntriesSuccessResponseBuilder();
    }

    @NonNull
    @Override
    public AppendEntriesFailureResponseBuilder createAppendEntriesFailureResponseBuilder() {
      recordCall();
      return super.createAppendEntriesFailureResponseBuilder();
    }

    @NonNull
    @Override
    public InstallSnapshotRequestBuilder createInstallSnapshotRequestBuilder() {
      recordCall();
      return super.createInstallSnapshotRequestBuilder();
    }

    @NonNull
    @Override
    public InstallSnapshotResponseBuilder createInstallSnapshotResponseBuilder() {
      recordCall();
      return super.createInstallSnapshotResponseBuilder();
    }

    @NonNull
    @Override
    public PreVoteRequestBuilder createPreVoteRequestBuilder() {
      recordCall();
      return super.createPreVoteRequestBuilder();
    }

    @NonNull
    @Override
    public PreVoteResponseBuilder createPreVoteResponseBuilder() {
      recordCall();
      return super.createPreVoteResponseBuilder();
    }

    @NonNull
    @Override
    public TriggerLeaderElectionRequestBuilder createTriggerLeaderElectionRequestBuilder() {
      recordCall();
      return super.createTriggerLeaderElectionRequestBuilder();
    }

    @NonNull
    @Override
    public VoteRequestBuilder createVoteRequestBuilder() {
      recordCall();
      return super.createVoteRequestBuilder();
    }

    @NonNull
    @Override
    public VoteResponseBuilder createVoteResponseBuilder() {
      recordCall();
      return super.createVoteResponseBuilder();
    }

    @NonNull
    @Override
    public UpdateRaftGroupMembersOpBuilder createUpdateRaftGroupMembersOpBuilder() {
      recordCall();
      return super.createUpdateRaftGroupMembersOpBuilder();
    }

    private void recordCall() {
      lastCreateCall = ++callOrder;
      if (createCall == 0) {
        createCall = lastCreateCall;
      }
    }
  }

  private static class NopRaftNodeReportListener
    implements RaftNodeReportListener, RaftNodeLifecycleAware {
    private volatile int startCall;
    private volatile int terminateCall;
    private volatile int acceptCall;
    private volatile int lastAcceptCall;
    private volatile int callOrder;

    @Override
    public void onRaftNodeStart() {
      if (startCall == 0) {
        startCall = ++callOrder;
      }
    }

    @Override
    public void onRaftNodeTerminate() {
      if (terminateCall == 0) {
        terminateCall = ++callOrder;
      }
    }

    @Override
    public void accept(RaftNodeReport report) {
      lastAcceptCall = ++callOrder;
      if (acceptCall == 0) {
        acceptCall = lastAcceptCall;
      }
    }
  }

  private static class NopRaftStore implements RaftStore, RaftNodeLifecycleAware {
    private volatile int startCall;
    private volatile int terminateCall;
    private volatile int persistCall;
    private volatile int lastPersistCall;
    private volatile int callOrder;

    @Override
    public void onRaftNodeStart() {
      if (startCall == 0) {
        startCall = ++callOrder;
      }
    }

    @Override
    public void onRaftNodeTerminate() {
      if (terminateCall == 0) {
        terminateCall = ++callOrder;
      }
    }

    @Override
    public void persistAndFlushLocalEndpoint(
      @NonNull RaftEndpointPersistentState localEndpointPersistentState) {
      recordCall();
    }

    @Override
    public void
      persistAndFlushInitialGroupMembers(@NonNull RaftGroupMembersView initialGroupMembers) {
      recordCall();
    }

    @Override
    public void persistAndFlushTerm(@NonNull RaftTermPersistentState termPersistentState) {
      recordCall();
    }

    @Override
    public void persistLogEntries(@NonNull List<LogEntry> logEntries) throws IOException {
      recordCall();
    }

    @Override
    public void persistSnapshotChunk(@NonNull SnapshotChunk snapshotChunk) {
      recordCall();
    }

    @Override
    public void truncateLogEntriesFrom(long logIndexInclusive) {
      recordCall();
    }

    @Override
    public void truncateLogEntriesUntil(long logIndexInclusive) {
      recordCall();
    }

    @Override
    public void deleteSnapshotChunks(long logIndex, int snapshotChunkCount) {
      recordCall();
    }

    @Override
    public void flush() {
      recordCall();
    }

    private void recordCall() {
      lastPersistCall = ++callOrder;
      if (persistCall == 0) {
        persistCall = lastPersistCall;
      }
    }
  }
}
