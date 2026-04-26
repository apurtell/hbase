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

import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class TestFollowerState {
  private static final long TIME = 12345;
  private final FollowerState followerState = new FollowerState(0, 1, TIME);

  @Test
  public void testFirstBackoff() {
    long flowControlSeqNum = followerState.setRequestBackoff(1, 2);
    assertThat(flowControlSeqNum).isGreaterThan(0);
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(1);
  }

  @Test
  public void testValidResponseFirstBackoff() {
    long flowControlSeqNum = followerState.setRequestBackoff(1, 2);
    boolean success = followerState.responseReceived(flowControlSeqNum, TIME);
    assertThat(success).isTrue();
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(0);
  }

  @Test
  public void testInvalidResponseFirstBackoff() {
    long flowControlSeqNum = followerState.setRequestBackoff(1, 2);
    boolean success = followerState.responseReceived(flowControlSeqNum + 1, TIME);
    assertThat(success).isFalse();
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(1);
  }

  @Test
  public void testCompleteFirstBackoff() {
    long flowControlSeqNum = followerState.setRequestBackoff(1, 2);
    boolean backoffCompleted = followerState.completeBackoffRound();
    assertThat(backoffCompleted).isTrue();
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(0);
  }

  @Test
  public void testStaleResponseAfterRetry() {
    long flowControlSeqNum1 = followerState.setRequestBackoff(1, 2);
    followerState.completeBackoffRound();
    long flowControlSeqNum2 = followerState.setRequestBackoff(1, 2);
    assertThat(flowControlSeqNum2).isGreaterThan(flowControlSeqNum1);
    boolean success = followerState.responseReceived(flowControlSeqNum1, TIME);
    assertThat(success).isFalse();
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum2);
    assertThat(followerState.backoffRound()).isEqualTo(2);
  }

  @Test
  public void testSecondBackoff() {
    followerState.setRequestBackoff(1, 2);
    followerState.completeBackoffRound();
    long flowControlSeqNum = followerState.setRequestBackoff(1, 2);
    assertThat(flowControlSeqNum).isGreaterThan(0);
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(2);
  }

  @Test
  public void testValidResponseSecondBackoff() {
    followerState.setRequestBackoff(1, 2);
    followerState.completeBackoffRound();
    long flowControlSeqNum = followerState.setRequestBackoff(1, 2);
    boolean success = followerState.responseReceived(flowControlSeqNum, TIME);
    assertThat(success).isTrue();
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(0);
  }

  @Test
  public void testInvalidResponseSecondBackoff() {
    followerState.setRequestBackoff(1, 2);
    followerState.completeBackoffRound();
    long flowControlSeqNum = followerState.setRequestBackoff(1, 2);
    boolean success = followerState.responseReceived(flowControlSeqNum + 1, TIME);
    assertThat(success).isFalse();
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(2);
  }

  @Test
  public void testCompleteSecondBackoff() {
    followerState.setRequestBackoff(1, 2);
    followerState.completeBackoffRound();
    long flowControlSeqNum = followerState.setRequestBackoff(1, 2);
    boolean backoffCompleted1 = followerState.completeBackoffRound();
    assertThat(backoffCompleted1).isFalse();
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(1);
    boolean backoffCompleted2 = followerState.completeBackoffRound();
    assertThat(backoffCompleted2).isTrue();
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum);
    assertThat(followerState.backoffRound()).isEqualTo(0);
  }

  @Test
  public void testMaxBackoff() {
    long flowControlSeqNum1 = followerState.setRequestBackoff(1, 4);
    followerState.completeBackoffRound();
    long flowControlSeqNum2 = followerState.setRequestBackoff(1, 4);
    followerState.completeBackoffRound();
    followerState.completeBackoffRound();
    long flowControlSeqNum3 = followerState.setRequestBackoff(1, 4);
    followerState.completeBackoffRound();
    followerState.completeBackoffRound();
    followerState.completeBackoffRound();
    followerState.completeBackoffRound();
    long flowControlSeqNum4 = followerState.setRequestBackoff(1, 4);
    assertThat(flowControlSeqNum4).isGreaterThan(flowControlSeqNum3);
    assertThat(flowControlSeqNum3).isGreaterThan(flowControlSeqNum2);
    assertThat(flowControlSeqNum2).isGreaterThan(flowControlSeqNum1);
    assertThat(followerState.flowControlSequenceNumber()).isEqualTo(flowControlSeqNum4);
    assertThat(followerState.backoffRound()).isEqualTo(4);
  }

  @Test
  public void testNoBackoffOverflow() {
    for (int i = 0; i < 64; i++) {
      followerState.setRequestBackoff(1, 64);
      while (followerState.isRequestBackoffSet()) {
        followerState.completeBackoffRound();
      }
    }
    assertThat(followerState.backoffRound()).isEqualTo(0);
    assertThat(followerState.isRequestBackoffSet()).isFalse();
  }
}
