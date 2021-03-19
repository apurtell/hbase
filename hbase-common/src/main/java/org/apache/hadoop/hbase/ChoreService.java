/**
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
package org.apache.hadoop.hbase;

import com.google.errorprone.annotations.RestrictedApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;

import org.apache.hadoop.hbase.util.ExecutorPools;
import org.apache.hadoop.hbase.util.ExecutorPools.PoolType;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChoreService is a service that can be used to schedule instances of {@link ScheduledChore} to run
 * periodically while sharing threads.
 * <p>
 * The ChoreService provides the ability to schedule, cancel, and trigger instances of
 * {@link ScheduledChore}. The ChoreService also provides the ability to check on the status of
 * scheduled chores. The number of threads used by the ChoreService changes based on the scheduling
 * load and whether or not the scheduled chores are executing on time. As more chores are scheduled,
 * there may be a need to increase the number of threads if it is noticed that chores are no longer
 * meeting their scheduled start times. On the other hand, as chores are cancelled, an attempt is
 * made to reduce the number of running threads to see if chores can still meet their start times
 * with a smaller thread pool.
 * <p>
 * When finished with a ChoreService it is good practice to call {@link ChoreService#shutdown()}.
 * Calling this method ensures that all scheduled chores are cancelled and cleaned up properly.
 */
@InterfaceAudience.Public
public class ChoreService {
  private static final Logger LOG = LoggerFactory.getLogger(ChoreService.class);

  /**
   * Maps chores to their futures. Futures are used to control a chore's schedule
   */
  private final HashMap<ScheduledChore, ScheduledFuture<?>> scheduledChores;

  /**
   * Maps chores to Booleans which indicate whether or not a chore has caused an increase in the
   * core pool size of the ScheduledThreadPoolExecutor. Each chore should only be allowed to
   * increase the core pool size by 1 (otherwise a single long running chore whose execution is
   * longer than its period would be able to spawn too many threads).
   */
  private final HashMap<ScheduledChore, Boolean> choresMissingStartTime;

  /**
   * @param coreThreadPoolPrefix Prefix that will be applied to the Thread name of all threads
   *          spawned by this service
   * @param corePoolSize The initial size to set the core pool of the ScheduledThreadPoolExecutor
   *          to during initialization. The default size is 1, but specifying a larger size may be
   *          beneficial if you know that 1 thread will not be enough.
   */
  @InterfaceAudience.Private
  public ChoreService() {
    scheduledChores = new HashMap<>();
    choresMissingStartTime = new HashMap<>();
  }

  /**
   * @param chore Chore to be scheduled. If the chore is already scheduled with another ChoreService
   *          instance, that schedule will be cancelled (i.e. a Chore can only ever be scheduled
   *          with a single ChoreService instance).
   * @return true when the chore was successfully scheduled. false when the scheduling failed
   *         (typically occurs when a chore is scheduled during shutdown of service)
   */
  public boolean scheduleChore(ScheduledChore chore) {
    if (chore == null) {
      return false;
    }
    // always lock chore first to prevent dead lock
    synchronized (chore) {
      synchronized (this) {
        try {
          // Chores should only ever be scheduled with a single ChoreService. If the choreService
          // is changing, cancel any existing schedules of this chore.
          if (chore.getChoreService() == this) {
            LOG.warn("Chore {} has already been scheduled with us", chore);
            return false;
          }
          if (chore.getPeriod() <= 0) {
            LOG.info("Chore {} is disabled because its period is not positive.", chore);
            return false;
          }
          LOG.info("Chore {} is enabled.", chore);
          if (chore.getChoreService() != null) {
            LOG.info("Cancel chore {} from its previous service", chore);
            chore.getChoreService().cancelChore(chore);
          }
          chore.setChoreService(this);
          ScheduledFuture<?> future = ExecutorPools.getScheduler(PoolType.CHORE)
            .scheduleAtFixedRate(chore, chore.getInitialDelay(), chore.getPeriod(),
              chore.getTimeUnit());
          scheduledChores.put(chore, future);
          return true;
        } catch (Exception e) {
          LOG.error("Could not successfully schedule chore: {}", chore.getName(), e);
          return false;
        }
      }
    }
  }

  /**
   * @param chore The Chore to be rescheduled. If the chore is not scheduled with this ChoreService
   *          yet then this call is equivalent to a call to scheduleChore.
   */
  private void rescheduleChore(ScheduledChore chore) {
    if (scheduledChores.containsKey(chore)) {
      ScheduledFuture<?> future = scheduledChores.get(chore);
      future.cancel(false);
    }
    ScheduledFuture<?> future = ExecutorPools.getScheduler(PoolType.CHORE)
      .scheduleAtFixedRate(chore, chore.getInitialDelay(), chore.getPeriod(),
        chore.getTimeUnit());
    scheduledChores.put(chore, future);
  }

  /**
   * Cancel any ongoing schedules that this chore has with the implementer of this interface.
   * <p/>
   * Call {@link ScheduledChore#cancel()} to cancel a {@link ScheduledChore}, in
   * {@link ScheduledChore#cancel()} method we will call this method to remove the
   * {@link ScheduledChore} from this {@link ChoreService}.
   */
  @RestrictedApi(explanation = "Should only be called in ScheduledChore", link = "",
    allowedOnPath = ".*/org/apache/hadoop/hbase/(ScheduledChore|ChoreService).java")
  synchronized void cancelChore(ScheduledChore chore) {
    cancelChore(chore, true);
  }

  /**
   * Cancel any ongoing schedules that this chore has with the implementer of this interface.
   * <p/>
   * Call {@link ScheduledChore#cancel(boolean)} to cancel a {@link ScheduledChore}, in
   * {@link ScheduledChore#cancel(boolean)} method we will call this method to remove the
   * {@link ScheduledChore} from this {@link ChoreService}.
   */
  @RestrictedApi(explanation = "Should only be called in ScheduledChore", link = "",
    allowedOnPath = ".*/org/apache/hadoop/hbase/(ScheduledChore|ChoreService).java")
  synchronized void cancelChore(ScheduledChore chore, boolean mayInterruptIfRunning) {
    if (scheduledChores.containsKey(chore)) {
      ScheduledFuture<?> future = scheduledChores.get(chore);
      future.cancel(mayInterruptIfRunning);
      scheduledChores.remove(chore);

      // Removing a chore that was missing its start time means it may be possible
      // to reduce the number of threads
      if (choresMissingStartTime.containsKey(chore)) {
        choresMissingStartTime.remove(chore);
      }
    }
  }

  /**
   * @return true when the chore is scheduled with the implementer of this interface
   */
  @InterfaceAudience.Private
  public synchronized boolean isChoreScheduled(ScheduledChore chore) {
    return chore != null && scheduledChores.containsKey(chore)
        && !scheduledChores.get(chore).isDone();
  }

  /**
   * This method tries to execute the chore immediately. If the chore is executing at the time of
   * this call, the chore will begin another execution as soon as the current execution finishes
   */
  @RestrictedApi(explanation = "Should only be called in ScheduledChore", link = "",
    allowedOnPath = ".*/org/apache/hadoop/hbase/ScheduledChore.java")
  synchronized void triggerNow(ScheduledChore chore) {
    assert chore.getChoreService() == this;
    rescheduleChore(chore);
  }

  /**
   * @return number of chores that this service currently has scheduled
   */
  int getNumberOfScheduledChores() {
    return scheduledChores.size();
  }

  /**
   * @return number of chores that this service currently has scheduled that are missing their
   *         scheduled start time
   */
  int getNumberOfChoresMissingStartTime() {
    return choresMissingStartTime.size();
  }

  /**
   * A callback that tells the implementer of this interface that one of the scheduled chores is
   * missing its start time. The implication of a chore missing its start time is that the service's
   * current means of scheduling may not be sufficient to handle the number of ongoing chores (the
   * other explanation is that the chore's execution time is greater than its scheduled period). The
   * service should try to increase its concurrency when this callback is received.
   * @param chore The chore that missed its start time
   */
  @RestrictedApi(explanation = "Should only be called in ScheduledChore", link = "",
    allowedOnPath = ".*/org/apache/hadoop/hbase/ScheduledChore.java")
  synchronized void onChoreMissedStartTime(ScheduledChore chore) {
    if (!scheduledChores.containsKey(chore)) {
      return;
    }
    // Must reschedule the chore to prevent unnecessary delays of chores in the scheduler. If
    // the chore is NOT rescheduled, future executions of this chore will be delayed more and
    // more on each iteration. This hurts us because the ScheduledThreadPoolExecutor allocates
    // idle threads to chores based on how delayed they are.
    rescheduleChore(chore);
    printChoreDetails("onChoreMissedStartTime", chore);
  }

  private boolean stopped = false;

  /**
   * shutdown the service. Any chores that are scheduled for execution will be cancelled. Any chores
   * in the middle of execution will be interrupted and shutdown. This service will be unusable
   * after this method has been called (i.e. future scheduling attempts will fail).
   * <p/>
   * Notice that, this will only clean the chore from this ChoreService but you could still schedule
   * the chore with other ChoreService.
   */
  public synchronized void shutdown() {
    if (isShutdown()) {
      return;
    }
    stopped = true;
    LOG.info("Chore service for: had {} on shutdown", scheduledChores.keySet());
    cancelAllChores(true);
    scheduledChores.clear();
    choresMissingStartTime.clear();
  }

  /**
   * @return true when the service is shutdown and thus cannot be used anymore
   */
  public boolean isShutdown() {
    return stopped;
  }

  private void cancelAllChores(final boolean mayInterruptIfRunning) {
    // Build list of chores to cancel so we can iterate through a set that won't change
    // as chores are cancelled. If we tried to cancel each chore while iterating through
    // keySet the results would be undefined because the keySet would be changing
    ArrayList<ScheduledChore> choresToCancel = new ArrayList<>(scheduledChores.keySet());

    for (ScheduledChore chore : choresToCancel) {
      cancelChore(chore, mayInterruptIfRunning);
    }
  }

  /**
   * Prints a summary of important details about the chore. Used for debugging purposes
   */
  private void printChoreDetails(final String header, ScheduledChore chore) {
    if (!LOG.isTraceEnabled()) {
      return;
    }
    LinkedHashMap<String, String> output = new LinkedHashMap<>();
    output.put(header, "");
    output.put("Chore name: ", chore.getName());
    output.put("Chore period: ", Integer.toString(chore.getPeriod()));
    output.put("Chore timeBetweenRuns: ", Long.toString(chore.getTimeBetweenRuns()));

    for (Entry<String, String> entry : output.entrySet()) {
      LOG.trace(entry.getKey() + entry.getValue());
    }
  }

  /**
   * Prints a summary of important details about the service. Used for debugging purposes
   */
  private void printChoreServiceDetails(final String header) {
    if (!LOG.isTraceEnabled()) {
      return;
    }
    LinkedHashMap<String, String> output = new LinkedHashMap<>();
    output.put(header, "");
    output.put("ChoreService scheduledChores: ", Integer.toString(getNumberOfScheduledChores()));
    output.put("ChoreService missingStartTimeCount: ",
      Integer.toString(getNumberOfChoresMissingStartTime()));

    for (Entry<String, String> entry : output.entrySet()) {
      LOG.trace(entry.getKey() + entry.getValue());
    }
  }
}
