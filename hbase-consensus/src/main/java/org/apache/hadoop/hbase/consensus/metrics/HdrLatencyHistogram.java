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

import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.Recorder;
import org.apache.hadoop.hbase.metrics.Histogram;
import org.apache.hadoop.hbase.metrics.Snapshot;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * {@link Histogram} backed by HdrHistogram's {@link Recorder} for high-resolution, accurate
 * latency-style measurement, used as a drop-in replacement for the default
 * {@code HistogramImpl}/{@code FastLongHistogram} pair on the few metrics where percentile fidelity
 * actually matters.
 * <p>
 * The default {@code FastLongHistogram} in {@code hbase-metrics} uses 255 self-resizing bins over
 * an unbounded range and reports an interpolated value within the bin that contains the requested
 * quantile. At sub-50&nbsp;ms latencies that interpolation collapses adjacent percentiles onto the
 * same bucket boundary (we observed {@code p90 / p99 / p999} all returning values within a 39&nbsp;
 * ms span over 290&nbsp;000 samples), which makes percentile bound assertions effectively
 * meaningless. HdrHistogram instead allocates a fixed value range and tracks counts at a
 * configurable precision (default 3 significant value digits, i.e. 0.1&nbsp;%), which preserves
 * tail fidelity at every order of magnitude.
 * <p>
 * <b>Threading.</b> {@link Recorder#recordValue(long) Recorder.recordValue} is lock-free and
 * thread-safe across many producers. {@link Recorder#getIntervalHistogram()
 * Recorder.getIntervalHistogram} swaps the active recording buffer atomically and returns a stable
 * point-in-time histogram for the closed interval; we wrap it in {@link HdrSnapshot} and hand it
 * back through the {@link Histogram#snapshot()} contract. {@link #snapshot()} is therefore
 * destructive on the recorder (matching the contract of the existing
 * {@code HistogramImpl#snapshot()} which calls {@code snapshotAndReset()}), but {@link #getCount()}
 * tracks a separate cumulative {@link LongAdder} that is <i>not</i> reset, mirroring the
 * cumulative-count semantics of {@code HistogramImpl#getCount()}.
 * <p>
 * Values that overflow {@code highestTrackableValue} are clamped to {@code highestTrackableValue}
 * rather than dropped, so an upstream burst beyond the configured bound shows up as a max-pinned
 * tail rather than as silently missing samples.
 */
@InterfaceAudience.Private
public final class HdrLatencyHistogram implements Histogram {

  private final Recorder recorder;
  private final long highestTrackableValue;
  private final LongAdder cumulativeCount = new LongAdder();

  /**
   * @param lowestDiscernibleValue         smallest value the histogram resolves; values below 1 are
   *                                       not supported by HdrHistogram. For latency histograms
   *                                       this should be {@code 1} regardless of the time unit
   *                                       (microseconds or milliseconds) the caller stores values
   *                                       in.
   * @param highestTrackableValue          upper bound on the value range this histogram tracks.
   *                                       Memory footprint scales with
   *                                       {@code log(highest / lowest)}, so over-provisioning by a
   *                                       few orders of magnitude is cheap.
   * @param numberOfSignificantValueDigits 1, 2, or 3. Three digits gives 0.1&nbsp;% precision and
   *                                       is the recommended default for latency tracking.
   */
  public HdrLatencyHistogram(long lowestDiscernibleValue, long highestTrackableValue,
    int numberOfSignificantValueDigits) {
    this.recorder =
      new Recorder(lowestDiscernibleValue, highestTrackableValue, numberOfSignificantValueDigits);
    this.highestTrackableValue = highestTrackableValue;
  }

  @Override
  public void update(int value) {
    update((long) value);
  }

  @Override
  public void update(long value) {
    if (value < 0L) {
      return;
    }
    long capped = Math.min(value, highestTrackableValue);
    recorder.recordValue(capped);
    cumulativeCount.increment();
  }

  @Override
  public long getCount() {
    return cumulativeCount.sum();
  }

  /**
   * Atomically captures the values recorded since the last {@link #snapshot()} and resets the
   * recorder for the next interval. The returned {@link Snapshot} is a stable view of the closed
   * interval and may be safely consumed after subsequent {@code snapshot()} calls.
   */
  @Override
  public synchronized Snapshot snapshot() {
    return new HdrSnapshot(recorder.getIntervalHistogram());
  }
}
