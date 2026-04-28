#!/usr/bin/env bash
#
#/**
# * Licensed to the Apache Software Foundation (ASF) under one
# * or more contributor license agreements.  See the NOTICE file
# * distributed with this work for additional information
# * regarding copyright ownership.  The ASF licenses this file
# * to you under the Apache License, Version 2.0 (the
# * "License"); you may not use this file except in compliance
# * with the License.  You may obtain a copy of the License at
# *
# *     http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing, software
# * distributed under the License is distributed on an "AS IS" BASIS,
# * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# * See the License for the specific language governing permissions and
# * limitations under the License.
# */
#
# run-scale-tests.sh
#
# Runs TestConsensusServerScale across a matrix of group counts (and optionally payload sizes,
# driver counts, profile events) and asserts the test's compiled-in bounds. On failure dumps the
# actionable subset of the maven and surefire logs.
#
# Default (no env overrides): one cell at groups=100, drivers=16, payload=1 KB, warmup=15 s,
# duration=30 s. The script's defaults override the test's compiled-in warmup (3 s) and duration
# (10 s) for a more stable measurement window; everything else falls through to the test default.
# See the TestConsensusServerScale class Javadoc for the assertion formulas behind the
# summary.tsv columns.
#
# Two iteration modes:
#
#   1. Bounds-driven (CI / regression):
#        bash dev-support/run-scale-tests.sh
#
#   2. Profile-driven (optimisation):
#        SCALE_PROFILE=1 SCALE_PROFILE_EVENT="cpu+wall+alloc+lock" \
#          SCALE_REPLICATE_P99_BASELINE_MS=10000 \
#          bash dev-support/run-scale-tests.sh
#      Renders one flamegraph per scale point per profile event under
#      target/scale/matrix-<stamp>/g<N>/event-<ev>/profile-<ev>.html. The relaxed baseline keeps
#      the test from fail-stopping during a profile run so the full curve is collected.
#
# Environment overrides:
#   SCALE_MATRIX           space-separated group counts (default: "100").
#                          Example sweep: "100 200 500 1000 2000 5000 10000".
#   SCALE_PAYLOAD_MATRIX   space-separated payload sizes in bytes (default: unset; use test default
#                          of 1024). Example: "1024 16384 65536 131072 262144 524288 1048576".
#                          The asserted p99 bound scales linearly per cell via the test's bound
#                          formula.
#   SCALE_DRIVERS          closed-loop driver thread count (default: 16; passed via
#                          -Dhbase.consensus.scale.drivers). Set to 1 to measure intrinsic
#                          per-replicate latency without driver-side contention.
#   SCALE_DURATION_SEC     measurement window in seconds (default: 30).
#   SCALE_WARMUP_LOW       warmup when groups < 2000 (default: 15).
#   SCALE_WARMUP_HIGH      warmup when groups >= 2000 (default: 30).
#   SCALE_SKIP_MVN         if 1, only print the plan (no mvn).
#   SCALE_FAIL_FAST        if 1, exit after the first non-zero mvn exit code (default: 0).
#   SCALE_PROFILE          if 1, attach async-profiler via -agentpath into the test JVM and
#                          render JFR flamegraphs after each run.
#   SCALE_PROFILE_EVENT    profiler event spec when SCALE_PROFILE=1 (default: cpu).
#                          Use + for multiple sequential runs, e.g. cpu+wall+alloc+lock.
#   ASPROF_PATH            async-profiler checkout (default: ~/src/async-profiler).
#   SCALE_REPLICATE_P99_BASELINE_MS
#                          override hbase.consensus.scale.replicate.p99.baseline.ms (default:
#                          unset; test default is 5.0). Set to a large value (e.g. 10000) when
#                          profiling so the test does not fail-stop and we collect the full curve.
#   SCALE_REPLICATE_P99_SLOPE_MS_PER_KB
#                          override hbase.consensus.scale.replicate.p99.slope.ms.per.kb (default:
#                          unset; test default is 0.05). Tighten after a payload sweep.
#   SCALE_HEAP_XMX         surefire JVM max heap (default: 24g; passed via -Dsurefire.Xmx). The
#                          default groups=100 / 1 KB cell needs well under 1 GB; 24g is the
#                          headroom the supported sweep matrices need (groups<=10000,
#                          payload<=1 MB). Lower it explicitly when running tightly.
#   SCALE_HEAP_XMS         surefire JVM initial heap (default: 8g; passed via -Dsurefire.Xms).
#   SCALE_GC_LOG           if 1, emit -Xlog:gc*,safepoint into <run_dir>/gc.log (default: 1).
#   SCALE_GC_ALG           JVM GC algorithm: G1 (default; JDK 17 default and the production
#                          default for HBase RegionServer deployments), ZGC, parallel, serial,
#                          or "" for the JVM default.
#   SCALE_HEAP_DUMP_ON_OOM if 1, pass -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<run_dir>
#                          (default: 1). Hprofs land alongside the per-run gc.log / maven.log.
#   SCALE_ASPROF_DEBUG_FLAGS
#                          if 1, add -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints to
#                          the test JVM (default: 1 when SCALE_PROFILE=1, 0 otherwise).
#   SCALE_NMT              value of -XX:NativeMemoryTracking (default: unset; "summary" or
#                          "detail" recommended for off-heap diagnosis).
#   SCALE_WRITER_SHARDS    UnifiedRaftStore writer shard count. Default unset, in which case the
#                          in-JVM topology auto-scales it against the per-server share of the host
#                          CPU (see InJvmConsensusServerTopology.applyInJvmScaleOverrides). Set
#                          explicitly (e.g. 4 to match the production default, or 1 to opt back
#                          into the legacy single-writer behaviour) for A/B comparisons; the value
#                          is forwarded as -Dhbase.consensus.log.writer.shards=N and wins over the
#                          topology auto-scaler.
#   SCALE_EXECUTOR_THREADS    MultiGroupExecutor drain-pool floor. Default unset (topology
#                             auto-scales to per-server-cores). Forwarded as
#                             -Dhbase.consensus.executor.threads=N.
#   SCALE_EXECUTOR_CEILING    MultiGroupExecutor drain-pool ceiling. Default unset (topology
#                             auto-scales to 4 * per-server-cores). Forwarded as
#                             -Dhbase.consensus.executor.threads.ceiling=N.
#   SCALE_EXTRA_OPTS       arbitrary extra `-D` system properties forwarded to the test JVM via
#                          mvn (e.g. "-Dhbase.consensus.log.fdatasync.deferred.enabled=false").
#                          Any -Dhbase.consensus.* property forwarded this way (or via the named
#                          knobs above) is absorbed into the topology's Configuration before the
#                          builder applies its in-JVM scale overrides; harness overrides win.
#
# Outputs under hbase-consensus/target/scale/matrix-<UTC-stamp>/:
#   summary.tsv            one row per completed mvn (one per profile event when profiling).
#                          Columns: groups, payload_bytes, drivers, profile_event, exit,
#                          ops_per_sec, err, replicate_p{50,75,95,99,max}_ms, p99_baseline_ms,
#                          p99_slope_ms_per_kb, p99_bound_ms, leader_lag_p99_ms, churns_per_group,
#                          hb_frames_per_sec_max, hb_fanout_min, hb_coalesced_pct_{min,max},
#                          hb_post_tick_flushes_total. See the test Javadoc for the assertion
#                          formulas behind these columns.
#   summary.txt            human-readable copy of key lines + exit codes.
#   g<N>/                  per-group-cell artifacts: maven.log, gc.log, metrics.
#   g<N>/p<bytes>/         when SCALE_PAYLOAD_MATRIX is set: maven.log, gc.log, metrics here.
#   g<N>/[p<bytes>/]event-<ev>/
#                          when SCALE_PROFILE=1: maven.log, gc.log, metrics, profile-<ev>.jfr,
#                          profile-<ev>.html.

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT_ROOT="$REPO_ROOT/hbase-consensus/target/scale/matrix-$STAMP"
mkdir -p "$OUT_ROOT"

# Default cell: groups=100, drivers=16, payload=test-default (1 KB), warmup=15 s, duration=30 s.
# See file header for full env-var contract and how this overrides the test compiled-in defaults.
SCALE_MATRIX=${SCALE_MATRIX:-"100"}
read -r -a SCALE_MATRIX_ARR <<<"$SCALE_MATRIX"
SCALE_PAYLOAD_MATRIX=${SCALE_PAYLOAD_MATRIX:-}
if [[ -n "$SCALE_PAYLOAD_MATRIX" ]]; then
  read -r -a SCALE_PAYLOAD_ARR <<<"$SCALE_PAYLOAD_MATRIX"
else
  # Sentinel "" => use test default; loop runs once per group point with no -D override.
  SCALE_PAYLOAD_ARR=("")
fi
SCALE_DRIVERS=${SCALE_DRIVERS:-16}
SCALE_DURATION_SEC=${SCALE_DURATION_SEC:-30}
SCALE_WARMUP_LOW=${SCALE_WARMUP_LOW:-15}
SCALE_WARMUP_HIGH=${SCALE_WARMUP_HIGH:-30}
SCALE_SKIP_MVN=${SCALE_SKIP_MVN:-0}
SCALE_PROFILE=${SCALE_PROFILE:-0}
SCALE_PROFILE_EVENT=${SCALE_PROFILE_EVENT:-cpu}
SCALE_FAIL_FAST=${SCALE_FAIL_FAST:-0}
SCALE_REPLICATE_P99_BASELINE_MS=${SCALE_REPLICATE_P99_BASELINE_MS:-}
SCALE_REPLICATE_P99_SLOPE_MS_PER_KB=${SCALE_REPLICATE_P99_SLOPE_MS_PER_KB:-}
SCALE_HEAP_XMX=${SCALE_HEAP_XMX:-24g}
SCALE_HEAP_XMS=${SCALE_HEAP_XMS:-8g}
SCALE_GC_LOG=${SCALE_GC_LOG:-1}
SCALE_GC_ALG=${SCALE_GC_ALG:-G1}
SCALE_HEAP_DUMP_ON_OOM=${SCALE_HEAP_DUMP_ON_OOM:-1}
SCALE_ASPROF_DEBUG_FLAGS=${SCALE_ASPROF_DEBUG_FLAGS:-}
SCALE_NMT=${SCALE_NMT:-}
SCALE_WRITER_SHARDS=${SCALE_WRITER_SHARDS:-}
SCALE_EXECUTOR_THREADS=${SCALE_EXECUTOR_THREADS:-}
SCALE_EXECUTOR_CEILING=${SCALE_EXECUTOR_CEILING:-}
ASPROF_PATH=${ASPROF_PATH:-"$HOME/src/async-profiler"}

ASPROF_LIB=""
JFRCONV=""

elect_timeout_for_groups() {
  local g=$1
  if [[ "$g" -ge 10000 ]]; then
    echo 360
  elif [[ "$g" -ge 5000 ]]; then
    echo 300
  elif [[ "$g" -ge 2000 ]]; then
    echo 180
  elif [[ "$g" -ge 500 ]]; then
    echo 120
  else
    echo 90
  fi
}

warmup_for_groups() {
  local g=$1
  if [[ "$g" -ge 2000 ]]; then
    echo "$SCALE_WARMUP_HIGH"
  else
    echo "$SCALE_WARMUP_LOW"
  fi
}

# Same layout as dev-support/scale-profile.sh (async-profiler + jfrconv).
resolve_asprof() {
  case "$(uname -s)" in
    Darwin) ASPROF_LIB="$ASPROF_PATH/build/lib/libasyncProfiler.dylib" ;;
    Linux)  ASPROF_LIB="$ASPROF_PATH/build/lib/libasyncProfiler.so" ;;
    *)
      echo "run-scale-tests.sh: unsupported OS '$(uname -s)' for profiling" >&2
      return 1
      ;;
  esac
  if [[ ! -f "$ASPROF_LIB" ]]; then
    echo "run-scale-tests.sh: async-profiler library not found at $ASPROF_LIB" >&2
    echo "  set ASPROF_PATH=/path/to/async-profiler or build it first" >&2
    return 1
  fi
  JFRCONV="$ASPROF_PATH/build/bin/jfrconv"
  if [[ ! -x "$JFRCONV" ]]; then
    echo "run-scale-tests.sh: jfrconv not found at $JFRCONV" >&2
    return 1
  fi
  return 0
}

# Compose the asprof attach flag (identical contract to scale-profile.sh asprof_attach).
asprof_attach() {
  local ev=$1
  local jfr=$2
  echo "-agentpath:${ASPROF_LIB}=start,event=${ev},file=${jfr},jfrsync=profile"
}

# Build the JVM extras string the runner asks the consensus POM to append to the surefire
# argLine. Concatenated into a single space-separated value passed via
# {@code -Dconsensus.scale.argLine.extra=...}. Caller is responsible for ensuring the run
# directory exists. The runner is the only place that talks to the JVM about GC / asprof /
# heap-dump-on-OOME paths so the consensus POM does not need any GC / profiling plumbing.
build_argline_extra() {
  local run_dir=$1
  local agentpath=$2
  local parts=()
  if [[ "$SCALE_GC_LOG" == "1" ]]; then
    parts+=("-Xlog:gc*,safepoint:file=${run_dir}/gc.log:time,uptime,level,tags")
  fi
  if [[ "$SCALE_HEAP_DUMP_ON_OOM" == "1" ]]; then
    parts+=(
      "-XX:+HeapDumpOnOutOfMemoryError"
      "-XX:HeapDumpPath=${run_dir}/heap-dump.hprof"
    )
  fi
  # GC algorithm. G1 is the operator-realistic default (JDK 17 default and the default for
  # production HBase RegionServer deployments). ZGC is available for high-allocation-rate
  # diagnostics (see SCALE_GC_ALG header for rationale). "" leaves the JVM default in place.
  case "${SCALE_GC_ALG:-}" in
    ZGC|zgc)
      parts+=("-XX:+UseZGC")
      ;;
    G1|g1)
      parts+=("-XX:+UseG1GC")
      ;;
    parallel|Parallel)
      parts+=("-XX:+UseParallelGC")
      ;;
    serial|Serial)
      parts+=("-XX:+UseSerialGC")
      ;;
    "")
      ;;
    *)
      echo "run-scale-tests.sh: WARN: unrecognised SCALE_GC_ALG='${SCALE_GC_ALG}', leaving JVM default" >&2
      ;;
  esac
  local enable_asprof_debug=$SCALE_ASPROF_DEBUG_FLAGS
  if [[ -z "$enable_asprof_debug" ]]; then
    if [[ "$SCALE_PROFILE" == "1" ]]; then
      enable_asprof_debug=1
    else
      enable_asprof_debug=0
    fi
  fi
  if [[ "$enable_asprof_debug" == "1" ]]; then
    parts+=(
      "-XX:+UnlockDiagnosticVMOptions"
      "-XX:+DebugNonSafepoints"
    )
  fi
  if [[ -n "$SCALE_NMT" ]]; then
    parts+=("-XX:NativeMemoryTracking=${SCALE_NMT}")
  fi
  if [[ -n "$agentpath" ]]; then
    parts+=("$agentpath")
  fi
  # Demote netty leak detection from "advanced" (parent pom default) to "simple" for scale runs.
  # Later -Ds in the surefire argLine win when the JVM concatenates them, so this overrides the
  # parent pom value without touching it. Keeps the leak detector active but at a tracking cost
  # low enough not to dominate p99.
  parts+=("-Dorg.apache.hbase.thirdparty.io.netty.leakDetection.level=simple")
  echo "${parts[*]}"
}

# Split profiler event list on '+' without touching global IFS (Bash 3.2 / macOS safe).
split_profile_events() {
  PROFILE_EVENTS=()
  local rest=$1
  while [[ "$rest" == *+* ]]; do
    PROFILE_EVENTS+=("${rest%%+*}")
    rest="${rest#*+}"
  done
  PROFILE_EVENTS+=("$rest")
}

# HBase defaults test.output.tofile=true: SLF4J lines from the test JVM are written under
# hbase-consensus/target/surefire-reports/*TestConsensusServerScale*.txt, not to maven.log.
# We merge both when scraping metrics so summary.tsv stays populated on success.

# Newest surefire stdout/stderr capture for this test class (may be absent before first run).
latest_surefire_scale_txt() {
  local repdir="$REPO_ROOT/hbase-consensus/target/surefire-reports"
  [[ -d "$repdir" ]] || return 0
  shopt -s nullglob
  local files=("$repdir"/*TestConsensusServerScale*.txt)
  shopt -u nullglob
  [[ ${#files[@]} -eq 0 ]] && return 0
  ls -t "${files[@]}" 2>/dev/null | head -1
}

# Concatenate Maven capture and surefire test output for grep-based metric extraction.
combined_scrape() {
  local logf=$1
  cat "$logf"
  local sf
  sf=$(latest_surefire_scale_txt)
  [[ -n "$sf" && -f "$sf" ]] && cat "$sf"
}

# Extract last field=value from the combined scrape stream (stdin: prefix + pattern).
extract_field_stdin() {
  local line_prefix=$1
  local field_pattern=$2
  { grep -F "$line_prefix" | grep -Eo "$field_pattern" | tail -1 | cut -d= -f2; } || true
}

extract_field_combined() {
  local logf=$1
  local line_prefix=$2
  local field_pattern=$3
  combined_scrape "$logf" | extract_field_stdin "$line_prefix" "$field_pattern"
}

# When mvn test fails, print actionable context (BUILD line + tail of logs).
dump_mvn_failure() {
  local logf=$1
  local groups=$2
  {
    echo "run-scale-tests.sh: Maven exit non-zero for groups=${groups}; full mvn log:"
    echo "  ${logf}"
    echo "--- mvn: Tests run / BUILD (if present) ---"
    grep -E 'Tests run:|BUILD SUCCESS|BUILD FAILURE|Failures:' "$logf" 2>/dev/null | tail -8 || true
    echo "--- mvn log (last 30 lines) ---"
    tail -30 "$logf" 2>/dev/null || true
    local sf
    sf=$(latest_surefire_scale_txt)
    if [[ -n "$sf" && -f "$sf" ]]; then
      echo "--- surefire test output (last 40 lines): ${sf} ---"
      tail -40 "$sf" 2>/dev/null || true
    fi
    echo ""
  } | tee -a "$TXT"
}

if [[ "$SCALE_PROFILE" == "1" ]]; then
  resolve_asprof
fi

TSV="$OUT_ROOT/summary.tsv"
TXT="$OUT_ROOT/summary.txt"
{
  echo "# run-scale-tests.sh matrix @ ${STAMP}"
  echo "# REPO_ROOT=${REPO_ROOT}"
  echo "# duration=${SCALE_DURATION_SEC}s warmup_low=${SCALE_WARMUP_LOW}s warmup_high=${SCALE_WARMUP_HIGH}s (high when groups>=2000)"
  echo "# SCALE_PROFILE=${SCALE_PROFILE} SCALE_PROFILE_EVENT=${SCALE_PROFILE_EVENT}"
  echo "# SCALE_FAIL_FAST=${SCALE_FAIL_FAST}"
  echo "# SCALE_DRIVERS=${SCALE_DRIVERS:-<test-default>}"
  echo "# SCALE_PAYLOAD_MATRIX=${SCALE_PAYLOAD_MATRIX:-<test-default>}"
  echo "# SCALE_REPLICATE_P99_BASELINE_MS=${SCALE_REPLICATE_P99_BASELINE_MS:-<test-default>} SCALE_REPLICATE_P99_SLOPE_MS_PER_KB=${SCALE_REPLICATE_P99_SLOPE_MS_PER_KB:-<test-default>}"
  echo "# SCALE_HEAP_XMX=${SCALE_HEAP_XMX} SCALE_HEAP_XMS=${SCALE_HEAP_XMS} SCALE_GC_ALG=${SCALE_GC_ALG:-<jvm-default>}"
  echo "# SCALE_GC_LOG=${SCALE_GC_LOG} SCALE_HEAP_DUMP_ON_OOM=${SCALE_HEAP_DUMP_ON_OOM} SCALE_NMT=${SCALE_NMT:-<unset>}"
  echo "# SCALE_WRITER_SHARDS=${SCALE_WRITER_SHARDS:-<topology-auto>} SCALE_EXECUTOR_THREADS=${SCALE_EXECUTOR_THREADS:-<topology-auto>} SCALE_EXECUTOR_CEILING=${SCALE_EXECUTOR_CEILING:-<topology-auto>}"
  echo "# SCALE_EXTRA_OPTS=${SCALE_EXTRA_OPTS:-<unset>}"
  echo ""
} | tee "$TXT"

echo -e "groups\tpayload_bytes\tdrivers\tprofile_event\texit\tops_per_sec\terr\treplicate_p50_ms\treplicate_p75_ms\treplicate_p95_ms\treplicate_p99_ms\treplicate_max_ms\tp99_baseline_ms\tp99_slope_ms_per_kb\tp99_bound_ms\tleader_lag_p99_ms\tchurns_per_group\thb_frames_per_sec_max\thb_fanout_min\thb_coalesced_pct_min\thb_coalesced_pct_max\thb_post_tick_flushes_total" >"$TSV"

# When the payload matrix is empty, the inner loop runs once with payload="" and the test
# uses its compiled-in default. When SCALE_PAYLOAD_MATRIX is set, every group point fans out
# across every payload point, producing groups x payloads runs. The run_dir layout matches:
#   single payload:  $OUT_ROOT/g<N>/[event-<ev>/]
#   payload sweep:   $OUT_ROOT/g<N>/p<bytes>/[event-<ev>/]
for NUM_GROUPS in "${SCALE_MATRIX_ARR[@]}"; do
  warmup=$(warmup_for_groups "$NUM_GROUPS")
  elect=$(elect_timeout_for_groups "$NUM_GROUPS")
  group_dir="$OUT_ROOT/g${NUM_GROUPS}"
  mkdir -p "$group_dir"

  for PAYLOAD in "${SCALE_PAYLOAD_ARR[@]}"; do
    if [[ -n "$PAYLOAD" ]]; then
      payload_dir="$group_dir/p${PAYLOAD}"
      payload_label="${PAYLOAD}"
      mkdir -p "$payload_dir"
      run_dir="$payload_dir"
    else
      payload_label="<test-default>"
      run_dir="$group_dir"
    fi

    {
      echo "======== groups=${NUM_GROUPS} payload=${payload_label} drivers=${SCALE_DRIVERS:-<test-default>} warmup=${warmup}s duration=${SCALE_DURATION_SEC}s elect_timeout=${elect}s ========"
    } | tee -a "$TXT"

    if [[ "$SCALE_SKIP_MVN" == "1" ]]; then
      echo "(SCALE_SKIP_MVN=1, skipping mvn)" | tee -a "$TXT"
      printf '%s\t%s\t%s\t-\tskipped\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\n' "$NUM_GROUPS" "${PAYLOAD:-}" "${SCALE_DRIVERS:-}" >>"$TSV"
      continue
    fi

    if [[ "$SCALE_PROFILE" == "1" ]]; then
      split_profile_events "$SCALE_PROFILE_EVENT"
    else
      PROFILE_EVENTS=( "")
    fi

    for ev in "${PROFILE_EVENTS[@]}"; do
      agentpath=""
      if [[ "$SCALE_PROFILE" == "1" ]]; then
        work_dir="$run_dir/event-${ev}"
        mkdir -p "$work_dir"
        logf="$work_dir/maven.log"
        jfr_file="$work_dir/profile-${ev}.jfr"
        html_file="$work_dir/profile-${ev}.html"
        agentpath=$(asprof_attach "$ev" "$jfr_file")
        profile_label="$ev"
        {
          echo "---- profile event=${ev} work_dir=${work_dir} ----"
        } | tee -a "$TXT"
      else
        work_dir="$run_dir"
        logf="$work_dir/maven.log"
        profile_label="-"
        jfr_file=""
        html_file=""
      fi

      # Per-run JVM extras (GC log path, heap-dump-on-OOM path, asprof attach, debug flags) are
      # composed here and forwarded to the consensus POM's surefire <argLine> as a single
      # property. The POM no longer hardcodes any of these; this is the single source of truth.
      argline_extra=$(build_argline_extra "$work_dir" "$agentpath")

      bound_args=(
        -Dsurefire.Xmx="$SCALE_HEAP_XMX"
        -Dsurefire.Xms="$SCALE_HEAP_XMS"
        -Dconsensus.scale.argLine.extra="$argline_extra"
      )
      [[ -n "$SCALE_REPLICATE_P99_BASELINE_MS"     ]] && bound_args+=(-Dhbase.consensus.scale.replicate.p99.baseline.ms="$SCALE_REPLICATE_P99_BASELINE_MS")
      [[ -n "$SCALE_REPLICATE_P99_SLOPE_MS_PER_KB" ]] && bound_args+=(-Dhbase.consensus.scale.replicate.p99.slope.ms.per.kb="$SCALE_REPLICATE_P99_SLOPE_MS_PER_KB")
      [[ -n "$SCALE_DRIVERS"                       ]] && bound_args+=(-Dhbase.consensus.scale.drivers="$SCALE_DRIVERS")
      [[ -n "$PAYLOAD"                   ]] && bound_args+=(-Dhbase.consensus.scale.payload.bytes="$PAYLOAD")
      # Resource-pool overrides. Unset => let InJvmConsensusServerTopology auto-scale against the
      # per-server share of the host CPU. Set => harness wins; the topology preserves whatever
      # the test JVM finds in its Configuration after absorbConsensusSystemProperties.
      [[ -n "$SCALE_WRITER_SHARDS"   ]] && bound_args+=(-Dhbase.consensus.log.writer.shards="$SCALE_WRITER_SHARDS")
      [[ -n "$SCALE_EXECUTOR_THREADS" ]] && bound_args+=(-Dhbase.consensus.executor.threads="$SCALE_EXECUTOR_THREADS")
      [[ -n "$SCALE_EXECUTOR_CEILING" ]] && bound_args+=(-Dhbase.consensus.executor.threads.ceiling="$SCALE_EXECUTOR_CEILING")
      # SCALE_EXTRA_OPTS lets callers pass arbitrary -D properties through to the test JVM
      # (e.g. -Dhbase.consensus.log.fdatasync.deferred.enabled=false to disable the deferred-
      # fsync code path). Word-split into the bound_args array so quoted multi-flag values work.
      if [[ -n "${SCALE_EXTRA_OPTS:-}" ]]; then
        # shellcheck disable=SC2206
        extra_arr=( $SCALE_EXTRA_OPTS )
        bound_args+=("${extra_arr[@]}")
      fi

      set +e
      (
        cd "$REPO_ROOT"
        mvn -pl hbase-consensus \
          -PconsensusScaleProfile -PrunLargeTests \
          -Dtest=TestConsensusServerScale \
          -DfailIfNoTests=false \
          -Dconsensus.scale.outDir="$work_dir" \
          -Dhbase.consensus.scale.groups="$NUM_GROUPS" \
          -Dhbase.consensus.scale.warmup.seconds="$warmup" \
          -Dhbase.consensus.scale.duration.seconds="$SCALE_DURATION_SEC" \
          -Dhbase.consensus.scale.elect.timeout.seconds="$elect" \
          ${bound_args[@]+"${bound_args[@]}"} \
          test
      ) >"$logf" 2>&1
      rc=$?
      set -e

      if [[ "$SCALE_PROFILE" == "1" && -n "$jfr_file" ]]; then
        if [[ -f "$jfr_file" ]]; then
          echo "run-scale-tests.sh: rendering flamegraph -> ${html_file}" | tee -a "$TXT"
          "$JFRCONV" --output html "$jfr_file" "$html_file" || \
            echo "run-scale-tests.sh: WARN: jfrconv failed; jfr is at ${jfr_file}" | tee -a "$TXT"
        else
          echo "run-scale-tests.sh: WARN: jfr file not produced at ${jfr_file}" | tee -a "$TXT" >&2
        fi
      fi

      if [[ "$rc" -ne 0 ]]; then
        dump_mvn_failure "$logf" "$NUM_GROUPS"
        if [[ "$SCALE_FAIL_FAST" == "1" ]]; then
          echo "run-scale-tests.sh: SCALE_FAIL_FAST=1, exiting." | tee -a "$TXT"
          exit "$rc"
        fi
      fi

      # Log scraping must not trip set -e under pipefail when a pattern is missing.
      set +e
      ops=$(combined_scrape "$logf" | grep -F 'TestConsensusServerScale: ops/sec=' | grep -Eo 'ops/sec=[0-9.]+' | tail -1 | cut -d= -f2)
      err=$(combined_scrape "$logf" | grep -F 'TestConsensusServerScale: ops/sec=' | grep -Eo 'err=[0-9]+' | tail -1 | cut -d= -f2)
      rep_line='TestConsensusServerScale: replicate samples='
      rp50=$(extract_field_combined "$logf" "$rep_line" 'p50\(max-across-servers\)=[0-9.]+')
      rp75=$(extract_field_combined "$logf" "$rep_line" 'p75\(max-across-servers\)=[0-9.]+')
      rp95=$(extract_field_combined "$logf" "$rep_line" 'p95\(max-across-servers\)=[0-9.]+')
      rp99=$(extract_field_combined "$logf" "$rep_line" 'p99\(max-across-servers\)=[0-9.]+')
      rmax=$(extract_field_combined "$logf" "$rep_line" 'max\(max-across-servers\)=[0-9.]+')
      bound_line='TestConsensusServerScale: replicate-p99-bound '
      p99_baseline=$(extract_field_combined "$logf" "$bound_line" 'baseline-ms=[0-9.]+')
      p99_slope=$(extract_field_combined "$logf" "$bound_line" 'slope-ms-per-kb=[0-9.]+')
      p99_bound=$(extract_field_combined "$logf" "$bound_line" 'bound-ms=[0-9.]+')
      lag_line='TestConsensusServerScale: leaderHeartbeatLag samples='
      lagp99=$(extract_field_combined "$logf" "$lag_line" 'p99\(max-across-servers\)=[0-9]+')
      churn=$(combined_scrape "$logf" | grep -F 'TestConsensusServerScale: election observations' | grep -Eo 'churns-per-group=[0-9.]+' | tail -1 | cut -d= -f2)
      # Heartbeat coalescing observability. The test logs one "hb-coalesce {server}->{peer}" line
      # per (server, peer) pair with hb-frames/s, fanout (heartbeats per HEARTBEAT_BATCH frame),
      # coalesced-pct, post-tick-flushes etc. We surface the worst-case (highest hb-frames/s,
      # lowest fanout, lowest coalesced-pct) so a single TSV row captures the worst pair on the
      # cell.
      hb_line='TestConsensusServerScale: hb-coalesce '
      hb_max_frames_per_sec=$(combined_scrape "$logf" | grep -F "$hb_line" | grep -Eo 'hb-frames/s=[0-9.]+' | sed 's/hb-frames\/s=//' | sort -g | tail -1)
      hb_min_fanout=$(combined_scrape "$logf" | grep -F "$hb_line" | grep -Eo 'fanout=[0-9.]+' | sed 's/fanout=//' | sort -g | head -1)
      hb_min_pct=$(combined_scrape "$logf" | grep -F "$hb_line" | grep -Eo 'coalesced-pct=[0-9.]+%' | sed 's/coalesced-pct=//;s/%//' | sort -g | head -1)
      hb_max_pct=$(combined_scrape "$logf" | grep -F "$hb_line" | grep -Eo 'coalesced-pct=[0-9.]+%' | sed 's/coalesced-pct=//;s/%//' | sort -g | tail -1)
      hb_post_tick_total=$(combined_scrape "$logf" | grep -F "$hb_line" | grep -Eo 'post-tick-flushes=[0-9]+' | sed 's/post-tick-flushes=//' | awk '{s+=$1}END{print s+0}')
      set -e

      {
        echo "exit_code=${rc} profile_event=${profile_label}"
        echo "ops_sec=${ops:-?} err=${err:-?}"
        echo "replicate_ms p50=${rp50:-?} p75=${rp75:-?} p95=${rp95:-?} p99=${rp99:-?} max=${rmax:-?}"
        echo "p99_baseline_ms=${p99_baseline:-?} p99_slope_ms_per_kb=${p99_slope:-?} p99_bound_ms=${p99_bound:-?} measured_p99_ms=${rp99:-?}"
        echo "leader_lag_p99_ms=${lagp99:-?}"
        echo "churns_per_group=${churn:-?}"
        echo "hb-frames/s_max=${hb_max_frames_per_sec:-?} fanout_min=${hb_min_fanout:-?} coalesced_pct_min=${hb_min_pct:-?}% coalesced_pct_max=${hb_max_pct:-?}% post_tick_flushes_total=${hb_post_tick_total:-?}"
        echo ""
      } | tee -a "$TXT"

      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$NUM_GROUPS" "${PAYLOAD:-}" "${SCALE_DRIVERS:-}" "$profile_label" "$rc" "${ops:-}" "${err:-}" "${rp50:-}" "${rp75:-}" "${rp95:-}" "${rp99:-}" "${rmax:-}" "${p99_baseline:-}" "${p99_slope:-}" "${p99_bound:-}" "${lagp99:-}" "${churn:-}" "${hb_max_frames_per_sec:-}" "${hb_min_fanout:-}" "${hb_min_pct:-}" "${hb_max_pct:-}" "${hb_post_tick_total:-}" >>"$TSV"
    done
  done
done

echo "run-scale-tests.sh: done. Outputs in ${OUT_ROOT}" | tee -a "$TXT"
