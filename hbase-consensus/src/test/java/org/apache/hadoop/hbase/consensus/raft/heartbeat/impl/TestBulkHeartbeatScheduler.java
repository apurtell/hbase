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
package org.apache.hadoop.hbase.consensus.raft.heartbeat.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.impl.RaftNodeImpl;
import org.apache.hadoop.hbase.consensus.raft.impl.local.LocalRaftGroup;
import org.apache.hadoop.hbase.consensus.raft.model.message.RaftMessage;
import org.apache.hadoop.hbase.consensus.raft.test.util.TestBase;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatAckFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.BulkHeartbeatFrame;
import org.apache.hadoop.hbase.consensus.raft.transport.Transport;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the lifecycle / register-unregister / closed-state contract of
 * {@link BulkHeartbeatScheduler}. Cadence and per-tick wire emission are exercised in
 * {@code TestRunHeartbeatTick}, which drives a real Raft leader through the wheel.
 */
@Tag(SmallTests.TAG)
public class TestBulkHeartbeatScheduler extends TestBase {

  private static final NopTransport NOP = new NopTransport();

  @Test
  public void testStartIsIdempotent() {
    BulkHeartbeatScheduler s = new BulkHeartbeatScheduler(/* intervalMs */ 50, /* timerThreads */ 1,
      /* pauseDetectionThresholdMs */ Long.MAX_VALUE, /* pauseToleranceCapMs */ Long.MAX_VALUE,
      NOP);
    try {
      s.start();
      s.start();
      assertThat(s.intervalMs()).isEqualTo(50);
    } finally {
      s.close();
    }
  }

  @Test
  public void testStopIsIdempotent() {
    BulkHeartbeatScheduler s = new BulkHeartbeatScheduler(/* intervalMs */ 50, /* timerThreads */ 1,
      /* pauseDetectionThresholdMs */ Long.MAX_VALUE, /* pauseToleranceCapMs */ Long.MAX_VALUE,
      NOP);
    try {
      s.start();
      s.stop();
      s.stop();
    } finally {
      s.close();
    }
  }

  @Test
  public void testCloseIsIdempotent() {
    BulkHeartbeatScheduler s = new BulkHeartbeatScheduler(/* intervalMs */ 50, /* timerThreads */ 1,
      /* pauseDetectionThresholdMs */ Long.MAX_VALUE, /* pauseToleranceCapMs */ Long.MAX_VALUE,
      NOP);
    s.start();
    s.close();
    s.close();
  }

  @Test
  public void testRegisterAfterCloseFails() {
    BulkHeartbeatScheduler s = new BulkHeartbeatScheduler(/* intervalMs */ 50, /* timerThreads */ 1,
      /* pauseDetectionThresholdMs */ Long.MAX_VALUE, /* pauseToleranceCapMs */ Long.MAX_VALUE,
      NOP);
    s.close();
    assertThatThrownBy(() -> s.register(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  public void testConstructorRejectsBadArgs() {
    assertThatThrownBy(() -> new BulkHeartbeatScheduler(0, 1, 0L, 0L, NOP))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BulkHeartbeatScheduler(1, 0, 0L, 0L, NOP))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BulkHeartbeatScheduler(1, 1, -1L, 0L, NOP))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BulkHeartbeatScheduler(1, 1, 0L, -1L, NOP))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testConfigurationConstructor() {
    Configuration conf = HBaseConfiguration.create();
    conf.setInt(BulkHeartbeatScheduler.INTERVAL_MS_KEY, 250);
    BulkHeartbeatScheduler s = new BulkHeartbeatScheduler(conf, NOP);
    try {
      assertThat(s.intervalMs()).isEqualTo(250);
      assertThat(s.registeredGroups()).isZero();
      assertThat(s.bootEpochMillis()).isPositive();
    } finally {
      s.close();
    }
  }

  @Test
  public void testUnregisterUnknownIsNoop() {
    BulkHeartbeatScheduler s = new BulkHeartbeatScheduler(50, 1, 0L, 0L, NOP);
    LocalRaftGroup group = LocalRaftGroup.newBuilder(1).build();
    try {
      RaftNodeImpl node = group.getNodes().get(0);
      // Unregister must be a quiet no-op and not flip the registered-group counter.
      s.unregister(node);
      assertThat(s.registeredGroups()).isZero();
    } finally {
      group.destroy();
      s.close();
    }
  }

  @AfterEach
  public void tearDownPerMethod() {
  }

  /** Transport that absorbs every send. */
  private static final class NopTransport implements Transport {
    @Override
    public void send(@NonNull RaftEndpoint target, @NonNull RaftMessage message) {
    }

    @Override
    public boolean isReachable(@NonNull RaftEndpoint endpoint) {
      return true;
    }

    @Override
    public void sendBulkHeartbeat(@NonNull RaftEndpoint target, @NonNull BulkHeartbeatFrame frame) {
    }

    @Override
    public void sendBulkHeartbeatAck(@NonNull RaftEndpoint target,
      @NonNull BulkHeartbeatAckFrame frame) {
    }
  }
}
