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

/**
 * High-precision metric implementations local to the consensus module that plug into the standard
 * {@code hbase-metrics-api} {@link org.apache.hadoop.hbase.metrics.MetricRegistry MetricRegistry}
 * via {@link org.apache.hadoop.hbase.metrics.MetricRegistry#register(String,
 * org.apache.hadoop.hbase.metrics.Metric) register(name, metric)}.
 * <p>
 * The default {@code HistogramImpl}/{@code FastLongHistogram} pair in {@code hbase-metrics} uses
 * 255 self-resizing bins over an unbounded range and reports interpolated quantiles within the bin
 * containing the requested percentile. At sub-50&nbsp;ms latencies the interpolation pancakes
 * adjacent percentiles onto the same bucket boundary, which makes percentile-bound assertions in
 * scale tests effectively unenforceable.
 * <p>
 * The classes in this package wrap {@link org.HdrHistogram.Recorder HdrHistogram.Recorder} behind
 * the {@link org.apache.hadoop.hbase.metrics.Histogram Histogram} and
 * {@link org.apache.hadoop.hbase.metrics.Timer Timer} interfaces so latency-critical metrics
 * (replicate, commit-apply, heartbeat-lag, ...) get accurate fixed-precision percentiles while
 * the rest of the registry keeps the default cheap-and-coarse impl. Other metric consumers
 * (registry dumper, MBean publishers, tests) are unaffected.
 */
@org.apache.yetus.audience.InterfaceAudience.Private
package org.apache.hadoop.hbase.consensus.metrics;
