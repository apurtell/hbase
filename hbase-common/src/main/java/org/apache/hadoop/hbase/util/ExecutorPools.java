/*
 *
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

package org.apache.hadoop.hbase.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.util.concurrent.MoreExecutors;
import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

@InterfaceAudience.Private
public class ExecutorPools {

  public static final int CORES = Runtime.getRuntime().availableProcessors();

  public static final int COMPACTION_MIN = 2;
  public static final int COMPACTION_MAX = 5;

  /** Executors are separated into distinct pools. This grouping by activity is for resource
      control and to avoid priority inversion scenarios. */
  public static enum PoolType {
    /** Scheduled chores */
    CHORE         ( CORES, CORES * 2 ),
    /** Client request dispatch and miscellaneous */
    CLIENT        ( CORES, CORES * 100 ),
    /** Compaction queue work */
    COMPACTION    ( COMPACTION_MIN, COMPACTION_MAX ),
    /** HFile related IO */
    FILE          ( CORES ),
    /** Handler (e.g. RPC) */
    HANDLER       ( CORES, CORES * 10 ),
    /** Handler (e.g. RPC) for priority requests */
    HANDLER_PRIO  ( CORES, CORES * 10 ),
    /** IO that is not HFile or snapshot related */
    IO            ( CORES ),
    /** Metrics */
    METRICS       ( CORES ),
    /** Procedure execution */
    PROCEDURE     ( CORES, CORES * 10 ),
    /** Region management activity */
    REGION        ( CORES ),
    /** Replication housekeeping */
    REPLICATION   ( CORES ),
    /** Remote RPC */
    REMOTE_RPC    ( CORES, CORES * 10 ),
    /** Security processes */
    SECURITY      ( CORES ),
    /** Snapshot related IO */
    SNAPSHOT      ( CORES ),
    /** Split queue work */
    SPLIT         ( CORES ),
    /** WAL related activity */
    WAL           ( CORES ),
    ;

    int coreThreads;
    int maxThreads;

    PoolType(int threads) {
      this.coreThreads = this.maxThreads = threads;
    }

    PoolType(int coreThreads, int maxThreads) {
      this.coreThreads = coreThreads;
      this.maxThreads = maxThreads;
    }

    public int getCoreThreads() {
      return coreThreads;
    }

    public int getMaxThreads() {
      return maxThreads;
    }
  }

  public static String EXECUTORS_SHUTDOWN_TIMEOUT_KEY = "hbase.executors.shutdown.timeout";
  public static int DEFAULT_EXECUTORS_SHUTDOWN_TIMEOUT = 60 * 1000;

  private static final Logger LOG = LoggerFactory.getLogger(ExecutorPools.class);
  private static Configuration CONF = HBaseConfiguration.create();
  private static ArrayList<ThreadPoolExecutor> POOLS = new ArrayList<>(PoolType.values().length);
  private static ArrayList<ScheduledExecutorService> SCHEDULERS = new ArrayList<>(PoolType.values().length);

  public static ThreadPoolExecutor getPool(PoolType p) {
    ThreadPoolExecutor pool = POOLS.get(p.ordinal());
    if (pool == null) {
      synchronized (POOLS) {
        pool = POOLS.get(p.ordinal());
        if (pool != null) {
          return pool;
        }
        String name = p.name().toLowerCase();
        int corePoolSize =
          CONF.getInt("hbase.executors.pool." + name + ".core.threads",
            p.getCoreThreads());
        int maxPoolSize =
          CONF.getInt("hbase.executors.pool." + name + ".max.threads",
            p.getMaxThreads());
        long keepAliveTime =
          CONF.getInt("hbase.executors.pool." + name + ".threads.keepalivetime",
            60);
        LOG.info("Creating new ThreadPoolExecutor for pool type \"" + p.name() +
          "\", corePoolSize=" + corePoolSize + ", maxPoolSize=" + maxPoolSize + 
          ", keepAliveTime=" + keepAliveTime);
        pool = new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new ThreadFactoryBuilder()
              .setDaemon(true)
              .setNameFormat(name + "-%d")
              .setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER)
              .build(),
            new ThreadPoolExecutor.AbortPolicy());
        POOLS.set(p.ordinal(), pool);
      }
    }
    return pool;
  }

  public static ScheduledExecutorService getScheduler(PoolType p) {
    ScheduledExecutorService scheduler = SCHEDULERS.get(p.ordinal());
    if (scheduler == null) {
      synchronized (SCHEDULERS) {
        scheduler = SCHEDULERS.get(p.ordinal());
        if (scheduler != null) {
          return scheduler;
        }
        String name = p.name().toLowerCase();
        int corePoolSize =
          CONF.getInt("hbase.executors.scheduler." + name + ".threads",
            p.getCoreThreads());
        LOG.info("Creating new ScheduledThreadPoolExecutor for scheduler type \"" + p.name() +
          "\", corePoolSize=" + corePoolSize);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(corePoolSize,
            new ThreadFactoryBuilder()
              .setDaemon(true)
              .setNameFormat(name + "-%d")
              .setUncaughtExceptionHandler(Threads.LOGGING_EXCEPTION_HANDLER)
              .build(),
            new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        scheduler = MoreExecutors.listeningDecorator(executor);
        SCHEDULERS.set(p.ordinal(), scheduler);
      }
    }
    return scheduler;
  }

  public static void shutdownPools() {
    for (PoolType pt: PoolType.values()) {
      ExecutorService e = getPool(pt);
      if (e != null) {
        e.shutdown();
        try {
          e.awaitTermination(
            CONF.getInt(EXECUTORS_SHUTDOWN_TIMEOUT_KEY, DEFAULT_EXECUTORS_SHUTDOWN_TIMEOUT),
              TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
          LOG.warn("Timed out waiting for executor pool " + pt + " to shut down", ex);
        }
      }
    }
  }

  public static void shutdownSchedulers() {
    for (PoolType pt: PoolType.values()) {
      ScheduledExecutorService e = getScheduler(pt);
      if (e != null) {
        e.shutdown();
        try {
          e.awaitTermination(
            CONF.getInt(EXECUTORS_SHUTDOWN_TIMEOUT_KEY, DEFAULT_EXECUTORS_SHUTDOWN_TIMEOUT),
              TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
          LOG.warn("Timed out waiting for executor pool " + pt + " to shut down", ex);
        }
      }
    }
  }

  public static void dumpPool(PoolType p, PrintWriter out) throws IOException {
    // TODO
  }

  public static void dumpScheduler(PoolType p, PrintWriter out) throws IOException {
    // TODO
  }
}
