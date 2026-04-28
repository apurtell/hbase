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
package org.apache.hadoop.hbase.consensus.metrics;

import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.metrics.Histogram;
import org.apache.hadoop.hbase.metrics.Meter;
import org.apache.hadoop.hbase.metrics.Timer;
import org.apache.hadoop.hbase.metrics.impl.DropwizardMeter;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * {@link Timer} backed by an {@link HdrLatencyHistogram} for accurate percentile readouts on
 * latency-sensitive timers.
 * <p>
 * Mirrors the design of {@code TimerImpl} (a {@link Histogram} plus a {@link Meter}) and uses the
 * same internal storage unit ({@link TimeUnit#MICROSECONDS}), so the value returned by the
 * histogram snapshot is microseconds regardless of which time unit the caller passed to
 * {@link #update(long, TimeUnit)}. Callers that want to assert in milliseconds should convert with
 * {@link TimeUnit#MICROSECONDS#toMillis(long)} on the snapshot percentile result.
 */
@InterfaceAudience.Private
public final class HdrLatencyTimer implements Timer {

  /**
   * Default tracking range for replicate / commit-apply / heartbeat-lag timers: 1&nbsp;μs through
   * 60&nbsp;s, three significant digits. 60 s is comfortably above any production-relevant tail and
   * any value above it will pin to the bound rather than be dropped.
   */
  public static final long DEFAULT_LOWEST_TRACKABLE_USEC = 1L;
  public static final long DEFAULT_HIGHEST_TRACKABLE_USEC = TimeUnit.SECONDS.toMicros(60L);
  public static final int DEFAULT_SIGNIFICANT_DIGITS = 3;

  // Same internal unit as TimerImpl, so a snapshot percentile from this Timer is unit-compatible
  // with a snapshot percentile from a TimerImpl-backed sibling.
  private static final TimeUnit DEFAULT_UNIT = TimeUnit.MICROSECONDS;

  private final HdrLatencyHistogram histogram;
  private final DropwizardMeter meter;

  public HdrLatencyTimer() {
    this(DEFAULT_LOWEST_TRACKABLE_USEC, DEFAULT_HIGHEST_TRACKABLE_USEC, DEFAULT_SIGNIFICANT_DIGITS);
  }

  public HdrLatencyTimer(long lowestTrackableValueUsec, long highestTrackableValueUsec,
    int numberOfSignificantValueDigits) {
    this.histogram = new HdrLatencyHistogram(lowestTrackableValueUsec, highestTrackableValueUsec,
      numberOfSignificantValueDigits);
    this.meter = new DropwizardMeter();
  }

  @Override
  public void update(long duration, TimeUnit unit) {
    if (duration < 0L) {
      return;
    }
    histogram.update(DEFAULT_UNIT.convert(duration, unit));
    meter.mark();
  }

  @Override
  public Histogram getHistogram() {
    return histogram;
  }

  @Override
  public Meter getMeter() {
    return meter;
  }
}
