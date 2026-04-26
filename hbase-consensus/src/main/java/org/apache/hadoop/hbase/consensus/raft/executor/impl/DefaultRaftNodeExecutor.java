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
package org.apache.hadoop.hbase.consensus.raft.executor.impl;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.consensus.raft.RaftNode;
import org.apache.hadoop.hbase.consensus.raft.executor.RaftNodeExecutor;
import org.apache.hadoop.hbase.consensus.raft.lifecycle.RaftNodeLifecycleAware;

/**
 * The default implementation of {@link RaftNodeExecutor}.
 * <p>
 * Uses a single-threaded {@link ScheduledExecutorService} to execute tasks submitted and scheduled
 * by {@link RaftNode}.
 * @see RaftNode
 * @see RaftNodeExecutor
 */
public class DefaultRaftNodeExecutor implements RaftNodeExecutor, RaftNodeLifecycleAware {
  private static final AtomicInteger RAFT_THREAD_ID = new AtomicInteger(0);
  private final ThreadGroup threadGroup = new ThreadGroup("RaftThread");
  private final ScheduledExecutorService executor = newSingleThreadScheduledExecutor(
    r -> new Thread(threadGroup, r, "Raft-" + RAFT_THREAD_ID.getAndIncrement()));

  @Override
  public void execute(@NonNull Runnable task) {
    submit(task);
  }

  @Override
  public void submit(@NonNull Runnable task) {
    executor.submit(task);
  }

  @Override
  public void schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit timeUnit) {
    executor.schedule(task, delay, timeUnit);
  }

  @Override
  public void onRaftNodeTerminate() {
    executor.shutdown();
  }

  public ScheduledExecutorService getExecutor() {
    return executor;
  }
}
