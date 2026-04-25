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
package org.apache.hadoop.hbase.consensus.raft.impl.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.hbase.consensus.raft.Ordered;
import org.apache.hadoop.hbase.consensus.raft.test.util.BaseTest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(SmallTests.TAG)
public class OrderedFutureTest extends BaseTest {
  private final OrderedFuture<Object> future = new OrderedFuture<>();

  @Test
  public void cannotCancel() {
    assertThrows(UnsupportedOperationException.class, () -> future.cancel(false));
  }

  @Test
  public void completeAfterGetTimeout() throws InterruptedException, ExecutionException {
    try {
      future.get(1, TimeUnit.SECONDS);
      fail(".get() cannot succeed on uncompleted future");
    } catch (TimeoutException e) {
      future.completeNull(1);
    }
    assertThat(future).isCompleted();
  }

  @Test
  public void publicCompleteExceptionallyForbidden() {
    assertThrows(UnsupportedOperationException.class,
      () -> future.completeExceptionally(new NullPointerException()));
  }

  @Test
  public void internalFail() {
    future.fail(new NullPointerException());
    assertThat(future).isCompletedExceptionally();
    try {
      future.join();
      fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseInstanceOf(NullPointerException.class);
    }
  }

  @Test
  public void publicCompleteForbidden() {
    assertThrows(UnsupportedOperationException.class, () -> future.complete(new Ordered<Object>() {
      @Override
      public long getCommitIndex() {
        return 1;
      }

      @Override
      public Object getResult() {
        return null;
      }
    }));
  }

  @Test
  public void completeNullInternal() throws ExecutionException, InterruptedException {
    long commitIndex = 1;
    future.completeNull(commitIndex);
    assertThat(future).isCompleted();
    Ordered<Object> ordered = future.get();
    assertThat(ordered).isNotNull();
    assertThat(ordered.getCommitIndex()).isEqualTo(commitIndex);
    assertThat(ordered.getResult()).isNull();
  }

  @Test
  public void completeValueInternal() throws ExecutionException, InterruptedException {
    long commitIndex = 1;
    Object result = new Object();
    future.complete(commitIndex, result);
    assertThat(future).isCompleted();
    Ordered<Object> ordered = future.get();
    assertThat(ordered.getCommitIndex()).isEqualTo(commitIndex);
    assertThat(ordered.getResult()).isEqualTo(result);
  }
}
