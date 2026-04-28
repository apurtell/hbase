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
package org.apache.hadoop.hbase.consensus.handler.server.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.hbase.metrics.Counter;
import org.apache.hadoop.hbase.metrics.Gauge;
import org.apache.hadoop.hbase.metrics.Histogram;
import org.apache.hadoop.hbase.metrics.Meter;
import org.apache.hadoop.hbase.metrics.Metric;
import org.apache.hadoop.hbase.metrics.MetricSet;
import org.apache.hadoop.hbase.metrics.Snapshot;
import org.apache.hadoop.hbase.metrics.Timer;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Test-only helper that walks any {@link MetricSet} (typically a
 * {@link org.apache.hadoop.hbase.metrics.MetricRegistry MetricRegistry} obtained via
 * {@code ConsensusServerMetrics.getRegistry()}) and renders it as a stable, line-oriented text
 * dump.
 * <p>
 * The dump format is fixed and diffable:
 *
 * <pre>
 * ### context=&lt;context&gt;
 * counter.&lt;name&gt;=&lt;count&gt;
 * gauge.&lt;name&gt;=&lt;value&gt;
 * histogram.&lt;name&gt;.count=&lt;count&gt;
 * histogram.&lt;name&gt;.{min,mean,p50,p75,p90,p95,p98,p99,p999,max}=&lt;value&gt;
 * meter.&lt;name&gt;.count=&lt;count&gt;
 * meter.&lt;name&gt;.{mean_rate,m1,m5,m15}=&lt;value&gt;
 * timer.&lt;name&gt;.{count,min,mean,p50,p75,p90,p95,p98,p99,p999,max,mean_rate,m1,m5,m15}=&lt;value&gt;
 * </pre>
 *
 * Names are sorted alphabetically per metric kind so that two runs with identical metrics produce
 * byte-identical dumps modulo the values themselves.
 */
@InterfaceAudience.Private
public final class MetricRegistryDumper {

  private static final double[] QUANTILES =
    new double[] { 0.5, 0.75, 0.90, 0.95, 0.98, 0.99, 0.999 };
  private static final String[] QUANTILE_NAMES =
    new String[] { "p50", "p75", "p90", "p95", "p98", "p99", "p999" };

  private MetricRegistryDumper() {
  }

  /** Renders {@code metrics} into a text dump and returns it as a {@code String}. */
  @NonNull
  public static String dump(@NonNull String context, @NonNull MetricSet metrics) {
    StringWriter sw = new StringWriter(2048);
    try (PrintWriter pw = new PrintWriter(sw)) {
      writeDump(context, metrics, pw);
    }
    return sw.toString();
  }

  /**
   * Appends one {@code MetricSet} dump (with its own {@code ### context=...} header) to
   * {@code out}.
   */
  public static void writeDump(@NonNull String context, @NonNull MetricSet metrics,
    @NonNull PrintWriter out) {
    out.print("### context=");
    out.println(context);
    Map<String, Metric> sorted = new TreeMap<>(metrics.getMetrics());
    List<String> counterLines = new ArrayList<>();
    List<String> gaugeLines = new ArrayList<>();
    List<String> histogramLines = new ArrayList<>();
    List<String> meterLines = new ArrayList<>();
    List<String> timerLines = new ArrayList<>();
    for (Map.Entry<String, Metric> e : sorted.entrySet()) {
      String name = e.getKey();
      Metric m = e.getValue();
      if (m instanceof Counter) {
        counterLines.add(formatCounter(name, (Counter) m));
      } else if (m instanceof Gauge) {
        gaugeLines.add(formatGauge(name, (Gauge<?>) m));
      } else if (m instanceof Timer) {
        timerLines.add(formatTimer(name, (Timer) m));
      } else if (m instanceof Histogram) {
        histogramLines.add(formatHistogram(name, (Histogram) m));
      } else if (m instanceof Meter) {
        meterLines.add(formatMeter(name, (Meter) m));
      } else {
        // Unknown subtype; render its toString to keep the dump stable.
        counterLines.add("unknown." + name + "=" + String.valueOf(m));
      }
    }
    counterLines.forEach(out::println);
    gaugeLines.forEach(out::println);
    histogramLines.forEach(out::println);
    meterLines.forEach(out::println);
    timerLines.forEach(out::println);
  }

  /**
   * Appends multiple {@code MetricSet} dumps to a single file. Existing file content is
   * overwritten. Each dump gets its own {@code ### context=...} header so a single file can carry
   * the metrics of an entire in-JVM topology.
   */
  public static void writeDumpsToFile(@NonNull Path file,
    @NonNull List<? extends Map.Entry<String, ? extends MetricSet>> contexts) throws IOException {
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
      PrintWriter pw = new PrintWriter(w)) {
      for (Map.Entry<String, ? extends MetricSet> ctx : contexts) {
        writeDump(ctx.getKey(), ctx.getValue(), pw);
      }
    }
  }

  private static String formatCounter(String name, Counter c) {
    return "counter." + name + "=" + c.getCount();
  }

  private static String formatGauge(String name, Gauge<?> g) {
    Object v = g.getValue();
    return "gauge." + name + "=" + (v == null ? "null" : v.toString());
  }

  private static String formatHistogram(String name, Histogram h) {
    Snapshot s = h.snapshot();
    StringBuilder sb = new StringBuilder(256);
    appendHistogramLines(sb, "histogram." + name, h.getCount(), s);
    // Drop trailing newline to match the line-per-call convention; writeDump uses println.
    int len = sb.length();
    if (len > 0 && sb.charAt(len - 1) == '\n') {
      sb.setLength(len - 1);
    }
    return sb.toString();
  }

  private static String formatMeter(String name, Meter m) {
    StringBuilder sb = new StringBuilder(256);
    appendMeterLines(sb, "meter." + name, m);
    int len = sb.length();
    if (len > 0 && sb.charAt(len - 1) == '\n') {
      sb.setLength(len - 1);
    }
    return sb.toString();
  }

  private static String formatTimer(String name, Timer t) {
    StringBuilder sb = new StringBuilder(512);
    Histogram h = t.getHistogram();
    Snapshot s = h.snapshot();
    appendHistogramLines(sb, "timer." + name, h.getCount(), s);
    appendMeterLines(sb, "timer." + name, t.getMeter());
    int len = sb.length();
    if (len > 0 && sb.charAt(len - 1) == '\n') {
      sb.setLength(len - 1);
    }
    return sb.toString();
  }

  private static void appendHistogramLines(StringBuilder sb, String prefix, long count,
    @Nullable Snapshot s) {
    sb.append(prefix).append(".count=").append(count).append('\n');
    if (s == null) {
      return;
    }
    sb.append(prefix).append(".min=").append(s.getMin()).append('\n');
    sb.append(prefix).append(".mean=").append(s.getMean()).append('\n');
    long[] q = s.getQuantiles(QUANTILES);
    for (int i = 0; i < QUANTILES.length; i++) {
      sb.append(prefix).append('.').append(QUANTILE_NAMES[i]).append('=').append(q[i]).append('\n');
    }
    sb.append(prefix).append(".max=").append(s.getMax()).append('\n');
  }

  private static void appendMeterLines(StringBuilder sb, String prefix, @NonNull Meter m) {
    sb.append(prefix).append(".count=").append(m.getCount()).append('\n');
    sb.append(prefix).append(".mean_rate=").append(formatRate(m.getMeanRate())).append('\n');
    sb.append(prefix).append(".m1=").append(formatRate(m.getOneMinuteRate())).append('\n');
    sb.append(prefix).append(".m5=").append(formatRate(m.getFiveMinuteRate())).append('\n');
    sb.append(prefix).append(".m15=").append(formatRate(m.getFifteenMinuteRate())).append('\n');
  }

  /** Stable formatting of meter rates so the text dump is byte-stable across locales. */
  private static String formatRate(double v) {
    if (Double.isNaN(v)) {
      return "NaN";
    }
    if (Double.isInfinite(v)) {
      return v > 0 ? "Infinity" : "-Infinity";
    }
    return String.format(Locale.ROOT, "%.6f", v);
  }
}
