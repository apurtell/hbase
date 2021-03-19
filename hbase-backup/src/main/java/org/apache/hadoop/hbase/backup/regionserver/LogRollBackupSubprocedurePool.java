/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.backup.regionserver;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.errorhandling.ForeignException;
import org.apache.hadoop.hbase.util.ExecutorPools;
import org.apache.hadoop.hbase.util.ExecutorPools.PoolType;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle running each of the individual tasks for completing a backup procedure on a region
 * server.
 */
@InterfaceAudience.Private
public class LogRollBackupSubprocedurePool implements Closeable, Abortable {
  private static final Logger LOG = LoggerFactory.getLogger(LogRollBackupSubprocedurePool.class);

  private final ExecutorCompletionService<Void> taskPool;
  private volatile boolean aborted;
  private final List<Future<Void>> futures = new ArrayList<>();
  private final String name;

  public LogRollBackupSubprocedurePool(String name, Configuration conf) {
    this.name = name;
    this.taskPool = new ExecutorCompletionService<>(ExecutorPools.getPool(PoolType.PROCEDURE));
  }

  /**
   * Submit a task to the pool.
   */
  public void submitTask(final Callable<Void> task) {
    Future<Void> f = this.taskPool.submit(task);
    futures.add(f);
  }

  /**
   * Wait for all of the currently outstanding tasks submitted via {@link #submitTask(Callable)}
   * @return <tt>true</tt> on success, <tt>false</tt> otherwise
   * @throws ForeignException exception
   */
  public boolean waitForOutstandingTasks() throws ForeignException {
    LOG.debug("Waiting for backup procedure to finish.");

    try {
      for (Future<Void> f : futures) {
        f.get();
      }
      return true;
    } catch (InterruptedException e) {
      if (aborted) {
        throw new ForeignException("Interrupted and found to be aborted while waiting for tasks!",
            e);
      }
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof ForeignException) {
        throw (ForeignException) e.getCause();
      }
      throw new ForeignException(name, e.getCause());
    } finally {
      // close off remaining tasks
      for (Future<Void> f : futures) {
        if (!f.isDone()) {
          f.cancel(true);
        }
      }
    }
    return false;
  }

  @Override
  public void close() {
    // TODO: Wait for all futures registered with taskPool to complete
  }

  @Override
  public void abort(String why, Throwable e) {
    if (this.aborted) {
      return;
    }
    // TODO: Abort all pending futures registered with taskPool
    this.aborted = true;
    LOG.warn("Aborting because: " + why, e);
  }

  @Override
  public boolean isAborted() {
    return this.aborted;
  }
}
