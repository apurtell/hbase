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

import org.HdrHistogram.AbstractHistogram;
import org.apache.hadoop.hbase.metrics.Snapshot;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * {@link Snapshot} adapter over an HdrHistogram interval snapshot.
 * <p>
 * The underlying {@link AbstractHistogram} is the (caller-owned) result of
 * {@link org.HdrHistogram.Recorder#getIntervalHistogram() Recorder.getIntervalHistogram}, so the
 * recorder has already swapped its active buffer and the histogram referenced here is a stable
 * point-in-time view of the closed interval. Percentile queries are O(1) on HdrHistogram and have
 * sub-percent precision at the configured number of significant value digits.
 */
@InterfaceAudience.Private
public final class HdrSnapshot implements Snapshot {

  private static final double[] DEFAULT_QUANTILES =
    new double[] { 0.25d, 0.5d, 0.75d, 0.90d, 0.95d, 0.98d, 0.99d, 0.999d };

  private final AbstractHistogram histogram;

  public HdrSnapshot(AbstractHistogram histogram) {
    this.histogram = histogram;
  }

  @Override
  public long[] getQuantiles(double[] quantiles) {
    long[] out = new long[quantiles.length];
    for (int i = 0; i < quantiles.length; i++) {
      out[i] = valueAtPercentile(quantiles[i] * 100.0d);
    }
    return out;
  }

  @Override
  public long[] getQuantiles() {
    return getQuantiles(DEFAULT_QUANTILES);
  }

  @Override
  public long getCount() {
    return histogram.getTotalCount();
  }

  @Override
  public long getCountAtOrBelow(long val) {
    if (histogram.getTotalCount() == 0L || val < 0L) {
      return 0L;
    }
    return histogram.getCountBetweenValues(0L, val);
  }

  @Override
  public long get25thPercentile() {
    return valueAtPercentile(25.0d);
  }

  @Override
  public long get75thPercentile() {
    return valueAtPercentile(75.0d);
  }

  @Override
  public long get90thPercentile() {
    return valueAtPercentile(90.0d);
  }

  @Override
  public long get95thPercentile() {
    return valueAtPercentile(95.0d);
  }

  @Override
  public long get98thPercentile() {
    return valueAtPercentile(98.0d);
  }

  @Override
  public long get99thPercentile() {
    return valueAtPercentile(99.0d);
  }

  @Override
  public long get999thPercentile() {
    return valueAtPercentile(99.9d);
  }

  @Override
  public long getMedian() {
    return valueAtPercentile(50.0d);
  }

  @Override
  public long getMax() {
    return histogram.getTotalCount() == 0L ? 0L : histogram.getMaxValue();
  }

  @Override
  public long getMean() {
    return histogram.getTotalCount() == 0L ? 0L : (long) histogram.getMean();
  }

  @Override
  public long getMin() {
    return histogram.getTotalCount() == 0L ? 0L : histogram.getMinValue();
  }

  private long valueAtPercentile(double percentile) {
    if (histogram.getTotalCount() == 0L) {
      return 0L;
    }
    return histogram.getValueAtPercentile(percentile);
  }
}
