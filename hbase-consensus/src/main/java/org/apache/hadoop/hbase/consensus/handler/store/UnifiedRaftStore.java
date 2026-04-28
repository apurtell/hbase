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
package org.apache.hadoop.hbase.consensus.handler.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.consensus.raft.model.impl.log.DefaultSnapshotEntryOrBuilder;
import org.apache.hadoop.hbase.consensus.raft.model.log.LogEntry;
import org.apache.hadoop.hbase.consensus.raft.model.log.RaftGroupMembersView;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotChunk;
import org.apache.hadoop.hbase.consensus.raft.model.log.SnapshotEntry;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftEndpointPersistentState;
import org.apache.hadoop.hbase.consensus.raft.model.persistence.RaftTermPersistentState;
import org.apache.hadoop.hbase.consensus.raft.persistence.RaftStore;
import org.apache.hadoop.hbase.consensus.raft.persistence.RestoredRaftState;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link DurableLogStore} implementation.
 * <p>
 * This is a multiplexed append-only log shared by every Raft group on the local server, inspired by
 * ZooKeeper's {@code FileTxnLog}. Group ids are routed to one of {@code N} independent
 * {@link WriterShard}s by stable hashing. Each shard owns a private MPSC mailbox, a dedicated
 * writer thread, and an isolated segment chain under {@code <log.dir>/shard-<i>/}. Per-group
 * ordering is preserved because all of a group's frames land on the same shard.
 * <p>
 * <b>Write path.</b> Records are encoded on the producing thread (via
 * {@link LogRecord#encode(LogRecord)} into a direct {@link ByteBuffer}) and offered to the target
 * shard's {@code MpscUnboundedArrayQueue} mailbox. Each iteration polls every record currently
 * queued, gathers them into a single {@link ByteBuffer} array, writes it to the active segment,
 * issues a single {@link FileChannel#write(ByteBuffer[]) gathered write}, calls
 * {@link FileChannel#force(boolean) force(false)} when any frame in the batch requested fsync, then
 * completes every {@link CompletableFuture} in the batch.
 * <p>
 * <b>Durability tiers.</b> Three independent triggers dictate when {@code force(false)} is issued
 * on the active segment of each shard:
 * <ul>
 * <li><b>Tier A (synchronous, mandatory).</b>
 * {@code persistAndFlush{Term,LocalEndpoint,InitialGroupMembers}} block until {@code force(false)}
 * returns (per-call fsync). These cover election-safety state (current term, vote, the local
 * endpoint id, the initial group-members view) which Raft requires on disk before the calling
 * thread can proceed. Rare path; the synchronous fsync cost does not show up in the steady-state
 * commit pipeline.</li>
 * <li><b>Tier B (default, segment-roll + periodic).</b> Log entries, snapshot chunks, and
 * truncations are page-cache durable on writev. {@code force(false)} fires (i) when the active
 * segment rolls (either on {@code segment-size} threshold or on graceful close), and (ii) every
 * {@link LogStoreConfig#FSYNC_INTERVAL_MS_KEY} milliseconds <i>iff</i> the writer has appended
 * bytes since the last fsync (the per-shard {@code unflushedBytes} counter). The periodic safety
 * net bounds the unfsynced tail under light load. {@link RaftStore#flush()} barriers in this tier
 * complete after the batch's write returns.</li>
 * <li><b>Tier C (opt-in strict, per-commit).</b> Setting {@link LogStoreConfig#FSYNC_ON_COMMIT_KEY}
 * to {@code true} restores per-{@link RaftStore#flush()} {@code force(false)}: every flush barrier
 * blocks on fsync, the leader's {@code flushedLogIndex} and the follower's
 * {@code AppendEntriesSuccessResponse} both advance only after disk durability.</li>
 * </ul>
 * <p>
 * <b>Flush barrier.</b> {@link RaftStore#flush()} on a per-group adapter enqueues an empty-frame
 * {@code PendingWrite} on the group's shard. {@code persistLogEntriesAndFlush} additionally fuses
 * the trailing barrier into the last {@code LOG_ENTRY} frame's {@code PendingWrite}.
 * <p>
 * <b>Recovery.</b> {@link #load()} replays each shard's directory in segment-id order. The first
 * non-{@code OK} read result inside a shard ({@link LogRecord.ReadResult.Kind#CRC} or
 * {@link LogRecord.ReadResult.Kind#TRUNCATED}) truncates the offending segment at the bad offset,
 * deletes every later segment in that shard, and stops replay for that shard. Whatever per-group
 * state was reconstructed up to that point is returned. Other shards replay independently. Gaps are
 * closed at the layer above by Raft's {@code AppendEntries}/{@code InstallSnapshot} catch-up.
 */
@InterfaceAudience.Private
public class UnifiedRaftStore implements DurableLogStore {

  private static final Logger LOG = LoggerFactory.getLogger(UnifiedRaftStore.class);

  /** Subdirectory prefix for per-shard segment chains: {@code <log.dir>/shard-<i>/}. */
  static final String SHARD_DIR_PREFIX = "shard-";

  /**
   * Single message in a writer shard's mailbox. Empty {@code encodedFrame} marks a {@code flush()}
   * barrier or a periodic-fsync timer marker.
   * <p>
   * The two fsync flags are deliberately distinct:
   * <ul>
   * <li>{@code fsyncRequested} signals "this is a flush barrier — caller wants to know the writer
   * has at least observed the preceding writev". In Tier C strict mode it gates a
   * {@code force(false)}.</li>
   * <li>{@code mandatoryFsync} signals "always issue {@code force(false)} for this batch,
   * regardless of {@code fsyncOnCommit}". Set on Tier A frames (term / vote / local-endpoint /
   * initial-members) and on the periodic-fsync timer marker.</li>
   * </ul>
   */
  static final class PendingWrite {
    @Nullable
    final ByteBuffer encodedFrame;
    final byte[] groupId;
    @Nullable
    final LogRecord.Kind kind;
    final long logIndex;
    final boolean fsyncRequested;
    final boolean mandatoryFsync;
    final CompletableFuture<Void> done = new CompletableFuture<>();

    PendingWrite(@Nullable ByteBuffer encodedFrame, @NonNull byte[] groupId,
      @Nullable LogRecord.Kind kind, long logIndex, boolean fsyncRequested,
      boolean mandatoryFsync) {
      this.encodedFrame = encodedFrame;
      this.groupId = groupId;
      this.kind = kind;
      this.logIndex = logIndex;
      this.fsyncRequested = fsyncRequested;
      this.mandatoryFsync = mandatoryFsync;
    }

    boolean isFlushBarrier() {
      return encodedFrame == null;
    }
  }

  private static final byte[] EMPTY_GROUP_ID = new byte[0];

  private final LogStoreConfig config;
  private final LogStoreSerializer serializer;
  private final WriterShard[] shards;

  /**
   * Single-threaded scheduler that owns the Tier B periodic-fsync timer. {@code null} when the
   * timer is disabled ({@link LogStoreConfig#getFsyncIntervalMs()} {@code <= 0}). The scheduler
   * thread does no blocking I/O; it only reads each shard's {@code unflushedBytes} and offers an
   * empty {@link PendingWrite} with {@code mandatoryFsync=true} onto that shard's mailbox.
   */
  @Nullable
  private ScheduledExecutorService fsyncScheduler;

  /** Handle to the recurring periodic-fsync task; cancelled on {@link #close()}. */
  @Nullable
  private ScheduledFuture<?> fsyncSchedulerHandle;

  private volatile boolean running = false;
  private volatile boolean loaded = false;
  private volatile boolean closed = false;

  public UnifiedRaftStore(@NonNull LogStoreConfig config) {
    this(config, new DefaultLogStoreSerializer());
  }

  public UnifiedRaftStore(@NonNull LogStoreConfig config, @NonNull LogStoreSerializer serializer) {
    this.config = config;
    this.serializer = serializer;
    int n = config.getWriterShards();
    this.shards = new WriterShard[n];
    Path baseDir = config.getLogDir().toPath();
    for (int i = 0; i < n; i++) {
      Path shardDir = baseDir.resolve(SHARD_DIR_PREFIX + i);
      this.shards[i] = new WriterShard(this, config, serializer, i, shardDir);
    }
  }

  @NonNull
  public LogStoreConfig getConfig() {
    return config;
  }

  /** Stable shard routing for a group id. Used by the SPI fast-path. */
  int routeShard(@NonNull byte[] groupId) {
    return (Arrays.hashCode(groupId) & 0x7fffffff) % shards.length;
  }

  boolean isRunning() {
    return running;
  }

  @NonNull
  @Override
  public synchronized Map<ByteBuffer, RestoredRaftState> load() throws IOException {
    if (loaded) {
      throw new IllegalStateException("load() must be called exactly once");
    }
    Path baseDir = config.getLogDir().toPath();
    if (!Files.exists(baseDir)) {
      Files.createDirectories(baseDir);
    }
    if (!Files.isDirectory(baseDir)) {
      throw new IOException(LogStoreConfig.LOG_DIR_KEY + " is not a directory: " + baseDir);
    }
    detectLayoutMismatch(baseDir);

    Map<ByteBuffer, RestoredRaftState> out = new HashMap<>();
    for (WriterShard sh : shards) {
      Path shardDir = sh.shardDir();
      if (!Files.exists(shardDir)) {
        Files.createDirectories(shardDir);
      }
      Map<ByteBuffer, RestoredRaftState> shardOut = loadShard(sh, shardDir);
      // Per the routing invariant, a group id appears in at most one shard. We don't bother
      // checking for collisions here.
      out.putAll(shardOut);
    }

    running = true;
    loaded = true;
    for (WriterShard sh : shards) {
      sh.start("UnifiedRaftStore-writer-" + sh.shardIndex());
    }

    long intervalMs = config.getFsyncIntervalMs();
    if (intervalMs > 0L) {
      fsyncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "UnifiedRaftStore-periodic-fsync");
        t.setDaemon(true);
        return t;
      });
      fsyncSchedulerHandle = fsyncScheduler.scheduleWithFixedDelay(this::periodicFsyncTick,
        intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    LOG.info(
      "UnifiedRaftStore loaded: {} group(s), {} shard(s), fsyncOnCommit={}, fsyncIntervalMs={},"
        + " preallocSegment={}",
      out.size(), shards.length, config.isFsyncOnCommit(), intervalMs, config.isPreallocSegment());
    return out;
  }

  /**
   * Refuse to start if the on-disk layout disagrees with the configured shard count. This is a
   * defence against silently splitting an existing log across the wrong number of writer shards.
   */
  private void detectLayoutMismatch(Path baseDir) throws IOException {
    Set<Integer> presentShardIds = new HashSet<>();
    boolean strayLogFiles = false;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
      for (Path p : stream) {
        String name = p.getFileName().toString();
        if (Files.isDirectory(p)) {
          if (name.startsWith(SHARD_DIR_PREFIX)) {
            String idStr = name.substring(SHARD_DIR_PREFIX.length());
            try {
              presentShardIds.add(Integer.parseInt(idStr));
            } catch (NumberFormatException nfe) {
              // not a shard subdirectory; ignore
            }
          }
        } else if (
          name.startsWith(LogSegment.FILE_PREFIX) && name.endsWith(LogSegment.FILE_SUFFIX)
            && LogSegment.parseSegmentId(name) >= 0
        ) {
          strayLogFiles = true;
        }
      }
    }
    if (strayLogFiles) {
      throw new IOException("Found legacy flat-layout log segments under " + baseDir
        + "; this UnifiedRaftStore requires the per-shard layout (<log.dir>/shard-<i>/raft-NN.log)."
        + " Auto-migration is not supported.");
    }
    if (presentShardIds.isEmpty()) {
      // Fresh directory; no mismatch possible.
      return;
    }
    int maxShardId = Collections.max(presentShardIds);
    int minShardId = Collections.min(presentShardIds);
    boolean dense = presentShardIds.size() == maxShardId + 1;
    if (minShardId < 0 || !dense || maxShardId + 1 != shards.length) {
      throw new IOException("On-disk shard layout under " + baseDir + " has shards "
        + presentShardIds + " but configured shard count is " + shards.length
        + ". Layout mismatch is not auto-migrated.");
    }
  }

  @NonNull
  @Override
  public RaftStore newGroupStore(@NonNull byte[] groupId) throws IOException {
    if (!loaded) {
      throw new IllegalStateException("load() must be called before newGroupStore()");
    }
    if (closed) {
      throw new IOException("UnifiedRaftStore is closed");
    }
    return new GroupRaftStore(this, groupId.clone());
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    running = false;
    if (fsyncSchedulerHandle != null) {
      fsyncSchedulerHandle.cancel(false);
      fsyncSchedulerHandle = null;
    }
    if (fsyncScheduler != null) {
      fsyncScheduler.shutdownNow();
      try {
        if (!fsyncScheduler.awaitTermination(5L, TimeUnit.SECONDS)) {
          LOG.warn("Periodic-fsync scheduler did not terminate within 5s");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      fsyncScheduler = null;
    }
    for (WriterShard sh : shards) {
      sh.shutdown();
    }
    for (WriterShard sh : shards) {
      sh.closeSegments();
    }
  }

  /**
   * Periodic-fsync timer tick. Runs on a dedicated single-threaded scheduler. For every shard whose
   * writer has appended bytes since the last fsync, enqueues a fire-and-forget empty
   * {@link PendingWrite} with {@code mandatoryFsync=true}. The shard's writer thread picks it up
   * and issues a single {@code force(false)} for the batch. When a shard's {@code unflushedBytes}
   * is zero this is a no-op for that shard.
   */
  private void periodicFsyncTick() {
    if (!running || closed) {
      return;
    }
    for (WriterShard sh : shards) {
      if (sh.unflushedBytes() <= 0L) {
        continue;
      }
      PendingWrite marker = new PendingWrite(null, EMPTY_GROUP_ID, null, -1L, true, true);
      sh.submit(marker);
    }
  }

  void persistLogEntries(@NonNull byte[] groupId, @NonNull List<LogEntry> entries)
    throws IOException {
    requireOpen();
    WriterShard sh = shards[routeShard(groupId)];
    LogStoreSerializer.Serializer<LogEntry> ser = serializer.logEntrySerializer();
    for (LogEntry entry : entries) {
      byte[] payload = ser.serialize(entry);
      enqueueAsync(sh, LogRecord.Kind.LOG_ENTRY, groupId, payload, entry.getIndex());
    }
  }

  /**
   * Encode all entries onto the shard, mark the trailing frame as a flush barrier, and await it.
   */
  void persistLogEntriesAndFlush(@NonNull byte[] groupId, @NonNull List<LogEntry> entries)
    throws IOException {
    requireOpen();
    if (entries.isEmpty()) {
      flushBarrier(groupId);
      return;
    }
    WriterShard sh = shards[routeShard(groupId)];
    LogStoreSerializer.Serializer<LogEntry> ser = serializer.logEntrySerializer();
    PendingWrite last = null;
    int n = entries.size();
    for (int i = 0; i < n; i++) {
      LogEntry entry = entries.get(i);
      byte[] payload = ser.serialize(entry);
      LogRecord rec = new LogRecord(LogRecord.Kind.LOG_ENTRY, sh.nextSequence(), groupId, payload);
      ByteBuffer frame = LogRecord.encode(rec);
      boolean fsyncBarrier = (i == n - 1);
      PendingWrite pw = new PendingWrite(frame, groupId, LogRecord.Kind.LOG_ENTRY, entry.getIndex(),
        fsyncBarrier, /* mandatoryFsync */ false);
      sh.submit(pw);
      if (fsyncBarrier) {
        last = pw;
      }
    }
    await(last);
  }

  void persistSnapshotChunk(@NonNull byte[] groupId, @NonNull SnapshotChunk chunk)
    throws IOException {
    requireOpen();
    byte[] payload = serializer.snapshotChunkSerializer().serialize(chunk);
    enqueueAsync(shards[routeShard(groupId)], LogRecord.Kind.SNAPSHOT_CHUNK, groupId, payload,
      chunk.getIndex());
  }

  void persistAndFlushTerm(@NonNull byte[] groupId, @NonNull RaftTermPersistentState state)
    throws IOException {
    requireOpen();
    byte[] payload = serializer.raftTermPersistentStateSerializer().serialize(state);
    awaitFsync(shards[routeShard(groupId)], LogRecord.Kind.TERM_VOTE, groupId, payload, -1L);
  }

  void persistAndFlushLocalEndpoint(@NonNull byte[] groupId,
    @NonNull RaftEndpointPersistentState state) throws IOException {
    requireOpen();
    byte[] payload = serializer.raftEndpointPersistentStateSerializer().serialize(state);
    awaitFsync(shards[routeShard(groupId)], LogRecord.Kind.LOCAL_ENDPOINT, groupId, payload, -1L);
  }

  void persistAndFlushInitialGroupMembers(@NonNull byte[] groupId,
    @NonNull RaftGroupMembersView view) throws IOException {
    requireOpen();
    byte[] payload = serializer.raftGroupMembersViewSerializer().serialize(view);
    awaitFsync(shards[routeShard(groupId)], LogRecord.Kind.INITIAL_MEMBERS, groupId, payload, -1L);
  }

  void truncateLogEntriesFrom(@NonNull byte[] groupId, long logIndexInclusive) throws IOException {
    requireOpen();
    byte[] payload = LogRecord.encodeTruncatePayload(logIndexInclusive);
    enqueueAsync(shards[routeShard(groupId)], LogRecord.Kind.TRUNCATE_FROM, groupId, payload,
      logIndexInclusive);
  }

  void truncateLogEntriesUntil(@NonNull byte[] groupId, long logIndexInclusive) throws IOException {
    requireOpen();
    byte[] payload = LogRecord.encodeTruncatePayload(logIndexInclusive);
    enqueueAsync(shards[routeShard(groupId)], LogRecord.Kind.TRUNCATE_UNTIL, groupId, payload,
      logIndexInclusive);
  }

  void deleteSnapshotChunks(@NonNull byte[] groupId, long logIndex, int chunkCount)
    throws IOException {
    requireOpen();
    byte[] payload = LogRecord.encodeDeleteSnapshotChunksPayload(logIndex, chunkCount);
    enqueueAsync(shards[routeShard(groupId)], LogRecord.Kind.DELETE_SNAPSHOT_CHUNKS, groupId,
      payload, logIndex);
  }

  void flushBarrier(@NonNull byte[] groupId) throws IOException {
    requireOpen();
    PendingWrite pw = new PendingWrite(null, groupId, null, -1L, true, false);
    shards[routeShard(groupId)].submit(pw);
    await(pw);
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("UnifiedRaftStore is closed");
    }
    if (!loaded) {
      throw new IOException("UnifiedRaftStore.load() has not been called");
    }
  }

  private void enqueueAsync(WriterShard sh, LogRecord.Kind kind, byte[] groupId, byte[] payload,
    long logIndex) {
    LogRecord rec = new LogRecord(kind, sh.nextSequence(), groupId, payload);
    ByteBuffer frame = LogRecord.encode(rec);
    sh.submit(new PendingWrite(frame, groupId, kind, logIndex, false, false));
  }

  private void awaitFsync(WriterShard sh, LogRecord.Kind kind, byte[] groupId, byte[] payload,
    long logIndex) throws IOException {
    LogRecord rec = new LogRecord(kind, sh.nextSequence(), groupId, payload);
    ByteBuffer frame = LogRecord.encode(rec);
    PendingWrite pw = new PendingWrite(frame, groupId, kind, logIndex, true, true);
    sh.submit(pw);
    await(pw);
  }

  private static void await(PendingWrite pw) throws IOException {
    try {
      pw.done.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for log write", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw new IOException("Log write failed", cause);
    }
  }

  /**
   * Replays one shard's directory and (re-)opens it for append. Returns per-group restored state
   * for groups whose data lives in this shard.
   */
  private Map<ByteBuffer, RestoredRaftState> loadShard(WriterShard sh, Path shardDir)
    throws IOException {
    NavigableMap<Long, Path> segmentsOnDisk = scanSegmentDir(shardDir);

    Map<ByteBuffer, GroupReplayState> perGroup = new HashMap<>();
    Map<Long, Map<ByteBuffer, Long>> perSegmentMax = new HashMap<>();
    Map<ByteBuffer, Long> shardFrontier = new HashMap<>();
    long highestSeq = 0L;

    boolean stoppedEarly = false;
    Long stoppedAtSegmentId = null;
    long stoppedAtOffset = -1L;
    Set<Long> goodSegmentIds = new HashSet<>();
    for (Map.Entry<Long, Path> e : segmentsOnDisk.entrySet()) {
      long segmentId = e.getKey();
      Path path = e.getValue();
      Map<ByteBuffer, Long> segMax = new HashMap<>();
      ReplayResult rr;
      try {
        rr = replaySegment(segmentId, path, perGroup, segMax, shardFrontier);
      } catch (IOException ioe) {
        LOG.warn("Segment {} failed prologue/header validation; treating as truncation point", path,
          ioe);
        stoppedEarly = true;
        stoppedAtSegmentId = segmentId;
        stoppedAtOffset = -1L;
        break;
      }
      highestSeq = Math.max(highestSeq, rr.highestSeq);
      perSegmentMax.put(segmentId, segMax);
      if (!rr.cleanlyEnded) {
        stoppedEarly = true;
        stoppedAtSegmentId = segmentId;
        stoppedAtOffset = rr.stopOffset;
        // Survivor; truncated below to the bad offset.
        goodSegmentIds.add(segmentId);
        break;
      }
      goodSegmentIds.add(segmentId);
    }

    long activeSegmentSize = -1L;
    if (stoppedEarly) {
      activeSegmentSize =
        truncateAndDeleteAfter(segmentsOnDisk, stoppedAtSegmentId, stoppedAtOffset);
      if (activeSegmentSize < 0) {
        goodSegmentIds.remove(stoppedAtSegmentId);
        perSegmentMax.remove(stoppedAtSegmentId);
      }
      Iterator<Map.Entry<Long, Path>> it =
        segmentsOnDisk.tailMap(stoppedAtSegmentId, false).entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<Long, Path> e = it.next();
        goodSegmentIds.remove(e.getKey());
        perSegmentMax.remove(e.getKey());
      }
    }

    rebuildSegmentIndexFromSurvivors(sh, shardDir, segmentsOnDisk, goodSegmentIds, perSegmentMax,
      activeSegmentSize, highestSeq);
    sh.seedGcFrontier(shardFrontier);

    Map<ByteBuffer, RestoredRaftState> out = new HashMap<>();
    for (Map.Entry<ByteBuffer, GroupReplayState> e : perGroup.entrySet()) {
      RestoredRaftState rs = e.getValue().build();
      if (rs != null) {
        out.put(e.getKey(), rs);
      }
    }
    return out;
  }

  /**
   * Streams a single segment file in one pass, accumulating per-group state, populating the
   * supplied {@code segMax} (per-group max log index this segment carries, used for GC), and
   * applying any {@code TRUNCATE_UNTIL} markers to the shard-local GC frontier.
   */
  private ReplayResult replaySegment(long segmentId, Path path,
    Map<ByteBuffer, GroupReplayState> perGroup, Map<ByteBuffer, Long> segMax,
    Map<ByteBuffer, Long> shardFrontier) throws IOException {
    long highestSeq = 0L;
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
      LogRecord.Reader reader = new LogRecord.Reader(ch)) {
      while (true) {
        LogRecord.ReadResult rr = reader.next();
        switch (rr.kind()) {
          case OK:
            highestSeq = Math.max(highestSeq, rr.record().getSeq());
            applyRecord(rr.record(), perGroup, segMax, shardFrontier);
            break;
          case END_OF_FILE:
            return new ReplayResult(true, reader.position(), highestSeq);
          case CRC:
            LOG.warn("CRC mismatch in segment {} at offset {}", path, rr.offset());
            return new ReplayResult(false, rr.offset(), highestSeq);
          case TRUNCATED:
            LOG.info("Torn-write tail in segment {} at offset {}", path, rr.offset());
            return new ReplayResult(false, rr.offset(), highestSeq);
          default:
            throw new IOException("Unexpected ReadResult: " + rr);
        }
      }
    }
  }

  private void applyRecord(LogRecord rec, Map<ByteBuffer, GroupReplayState> perGroup,
    Map<ByteBuffer, Long> segMax, Map<ByteBuffer, Long> shardFrontier) {
    if (
      rec.getKind() == LogRecord.Kind.SEGMENT_HEADER
        || rec.getKind() == LogRecord.Kind.SEGMENT_FOOTER
    ) {
      return;
    }
    ByteBuffer key =
      ByteBuffer.wrap(Arrays.copyOf(rec.getGroupId(), rec.getGroupId().length)).asReadOnlyBuffer();
    GroupReplayState gs = perGroup.computeIfAbsent(key, k -> new GroupReplayState(serializer));
    long carriedIndex = gs.apply(rec);
    if (carriedIndex >= 0) {
      segMax.merge(key, carriedIndex, Math::max);
    }
    if (rec.getKind() == LogRecord.Kind.TRUNCATE_UNTIL) {
      long idx = LogRecord.decodeTruncatePayload(rec.getPayload());
      shardFrontier.merge(key, idx, Math::max);
    }
  }

  /**
   * Truncates / deletes the on-disk segments after a load-time bad frame. Returns the new size of
   * the truncated segment, or {@code -1} if the offending segment had to be deleted entirely.
   */
  private long truncateAndDeleteAfter(NavigableMap<Long, Path> segmentsOnDisk,
    long stoppedAtSegmentId, long stoppedAtOffset) throws IOException {
    long survivorSize = -1L;
    Path stoppedAtPath = segmentsOnDisk.get(stoppedAtSegmentId);
    if (stoppedAtPath != null) {
      if (stoppedAtOffset >= LogRecord.PROLOGUE_BYTES) {
        try (FileChannel ch = FileChannel.open(stoppedAtPath, StandardOpenOption.WRITE)) {
          ch.truncate(stoppedAtOffset);
        }
        survivorSize = stoppedAtOffset;
        LOG.warn("Truncated segment {} to offset {}", stoppedAtPath, stoppedAtOffset);
      } else {
        Files.deleteIfExists(stoppedAtPath);
        LOG.warn("Deleted unreadable segment {}", stoppedAtPath);
      }
    }
    for (Map.Entry<Long, Path> e : segmentsOnDisk.tailMap(stoppedAtSegmentId, false).entrySet()) {
      Files.deleteIfExists(e.getValue());
      LOG.warn("Deleted post-truncation segment {}", e.getValue());
    }
    return survivorSize;
  }

  /**
   * Rebuilds the shard's segment index from the segments that survived load-time replay. The
   * highest surviving segment id becomes the active segment open for append. If the shard has no
   * segments yet (post-truncation or first-boot), creates a fresh segment {@code 0} with the
   * standard prologue + {@code SEGMENT_HEADER}.
   */
  private void rebuildSegmentIndexFromSurvivors(WriterShard sh, Path shardDir,
    NavigableMap<Long, Path> segmentsOnDisk, Set<Long> goodSegmentIds,
    Map<Long, Map<ByteBuffer, Long>> perSegmentMax, long activeSegmentSize, long highestSeqSeen)
    throws IOException {
    if (goodSegmentIds.isEmpty()) {
      long bootId = 0L;
      Path firstPath = shardDir.resolve(LogSegment.filename(bootId));
      LogSegment seg = LogSegment.create(bootId, firstPath,
        config.isPreallocSegment() ? config.getSegmentSizeBytes() : 0L);
      long seedSeq = highestSeqSeen + 1L;
      LogRecord header = new LogRecord(LogRecord.Kind.SEGMENT_HEADER, seedSeq, EMPTY_GROUP_ID,
        LogRecord.encodeSegmentHeaderPayload(bootId, EnvironmentEdgeManager.currentTime()));
      seg.appendFrame(LogRecord.encode(header));
      seg.force(true);
      sh.setCurrentSegment(seg);
      sh.seedNextSequence(seedSeq + 1L);
      return;
    }
    long activeId = Collections.max(goodSegmentIds);
    LogSegment activeSeg = null;
    for (Long id : goodSegmentIds) {
      Path p = segmentsOnDisk.get(id);
      long size;
      if (id == activeId && activeSegmentSize >= 0) {
        size = activeSegmentSize;
      } else {
        size = Files.size(p);
      }
      LogSegment seg = LogSegment.openForAppend(id, p, size);
      Map<ByteBuffer, Long> segMax = perSegmentMax.get(id);
      if (segMax != null) {
        for (Map.Entry<ByteBuffer, Long> e : segMax.entrySet()) {
          byte[] gid = readOnlyBufferToBytes(e.getKey());
          seg.recordMaxLogIndex(gid, e.getValue());
        }
      }
      if (id == activeId) {
        activeSeg = seg;
      } else {
        sh.segmentIndex().register(seg);
      }
    }
    if (activeSeg != null) {
      sh.setCurrentSegment(activeSeg);
    }
    sh.seedNextSequence(highestSeqSeen + 1L);
  }

  private static byte[] readOnlyBufferToBytes(ByteBuffer b) {
    ByteBuffer dup = b.duplicate();
    dup.rewind();
    byte[] out = new byte[dup.remaining()];
    dup.get(out);
    return out;
  }

  private NavigableMap<Long, Path> scanSegmentDir(Path dir) throws IOException {
    NavigableMap<Long, Path> out = new TreeMap<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path p : stream) {
        if (!Files.isRegularFile(p)) {
          continue;
        }
        long id = LogSegment.parseSegmentId(p.getFileName().toString());
        if (id < 0) {
          continue;
        }
        out.put(id, p);
      }
    }
    return out;
  }

  /** Accumulator for one group's {@code RestoredRaftState} during whole-disk replay. */
  static final class GroupReplayState {
    private final LogStoreSerializer serializer;
    @Nullable
    RaftEndpointPersistentState localEndpoint;
    @Nullable
    RaftGroupMembersView initialGroupMembers;
    @Nullable
    RaftTermPersistentState termPersistentState;
    final List<LogEntry> entries = new ArrayList<>();
    /**
     * Snapshot chunks accumulated by {@code (snapshotIndex)}. We keep the latest in-flight snapshot
     * only. A {@code DELETE_SNAPSHOT_CHUNKS} for a different index resets the buffer.
     */
    @Nullable
    SnapshotPersistenceState snapshotPersistenceState;
    @Nullable
    SnapshotEntry flushedSnapshotEntry;

    GroupReplayState(@NonNull LogStoreSerializer serializer) {
      this.serializer = serializer;
    }

    /**
     * Applies a record to this group's accumulator and returns the log index this record carries
     * (for {@code LOG_ENTRY} / {@code SNAPSHOT_CHUNK}), or {@code -1L} if it's a non-indexed
     * record.
     */
    long apply(@NonNull LogRecord r) {
      switch (r.getKind()) {
        case LOCAL_ENDPOINT:
          localEndpoint =
            serializer.raftEndpointPersistentStateSerializer().deserialize(r.getPayload());
          return -1L;
        case INITIAL_MEMBERS:
          initialGroupMembers =
            serializer.raftGroupMembersViewSerializer().deserialize(r.getPayload());
          return -1L;
        case TERM_VOTE:
          termPersistentState =
            serializer.raftTermPersistentStateSerializer().deserialize(r.getPayload());
          return -1L;
        case LOG_ENTRY: {
          LogEntry e = serializer.logEntrySerializer().deserialize(r.getPayload());
          entries.add(e);
          return e.getIndex();
        }
        case SNAPSHOT_CHUNK: {
          SnapshotChunk chunk = serializer.snapshotChunkSerializer().deserialize(r.getPayload());
          if (
            snapshotPersistenceState == null
              || snapshotPersistenceState.snapshotIndex != chunk.getIndex()
          ) {
            snapshotPersistenceState = new SnapshotPersistenceState(chunk.getTerm(),
              chunk.getIndex(), chunk.getSnapshotChunkCount(), chunk.getGroupMembersView());
          }
          snapshotPersistenceState.chunks.put(chunk.getSnapshotChunkIndex(), chunk);
          if (snapshotPersistenceState.isCompleted()) {
            flushedSnapshotEntry = snapshotPersistenceState.toSnapshotEntry();
          }
          return chunk.getIndex();
        }
        case TRUNCATE_FROM: {
          long idx = LogRecord.decodeTruncatePayload(r.getPayload());
          entries.removeIf(le -> le.getIndex() >= idx);
          return -1L;
        }
        case TRUNCATE_UNTIL: {
          long idx = LogRecord.decodeTruncatePayload(r.getPayload());
          entries.removeIf(le -> le.getIndex() <= idx);
          return -1L;
        }
        case DELETE_SNAPSHOT_CHUNKS: {
          long[] hdr = LogRecord.decodeDeleteSnapshotChunksPayload(r.getPayload());
          if (
            snapshotPersistenceState != null && snapshotPersistenceState.snapshotIndex == hdr[0]
          ) {
            snapshotPersistenceState = null;
          }
          return -1L;
        }
        case SEGMENT_HEADER:
        case SEGMENT_FOOTER:
        default:
          return -1L;
      }
    }

    @Nullable
    RestoredRaftState build() {
      if (
        localEndpoint == null && initialGroupMembers == null && termPersistentState == null
          && entries.isEmpty() && flushedSnapshotEntry == null
      ) {
        return null;
      }
      List<LogEntry> restored = new ArrayList<>();
      for (LogEntry e : entries) {
        if (flushedSnapshotEntry == null || e.getIndex() > flushedSnapshotEntry.getIndex()) {
          restored.add(e);
        }
      }
      // RestoredRaftState requires non-null localEndpoint and initialGroupMembers. If either is
      // missing the Raft layer treats that as "no on-disk state" and starts the group fresh, so
      // we drop the partial accumulation here.
      if (localEndpoint == null || initialGroupMembers == null) {
        return null;
      }
      return new RestoredRaftState(localEndpoint, initialGroupMembers, termPersistentState,
        flushedSnapshotEntry, restored);
    }
  }

  /** Snapshot reassembly state mirroring {@code InMemoryRaftStore.SnapshotPersistenceState}. */
  static final class SnapshotPersistenceState {
    final int term;
    final long snapshotIndex;
    final int chunkCount;
    final RaftGroupMembersView membersView;
    final NavigableMap<Integer, SnapshotChunk> chunks = new TreeMap<>();

    SnapshotPersistenceState(int term, long snapshotIndex, int chunkCount,
      RaftGroupMembersView membersView) {
      this.term = term;
      this.snapshotIndex = snapshotIndex;
      this.chunkCount = chunkCount;
      this.membersView = membersView;
    }

    boolean isCompleted() {
      return chunks.size() == chunkCount;
    }

    @Nullable
    SnapshotEntry toSnapshotEntry() {
      if (!isCompleted()) {
        return null;
      }
      return new DefaultSnapshotEntryOrBuilder().setTerm(term).setIndex(snapshotIndex)
        .setSnapshotChunks(new ArrayList<>(chunks.values())).setGroupMembersView(membersView)
        .build();
    }
  }

  /** Outcome of replaying a single segment; tells {@code load()} whether to stop and where. */
  private static final class ReplayResult {
    final boolean cleanlyEnded;
    final long stopOffset;
    final long highestSeq;

    ReplayResult(boolean cleanlyEnded, long stopOffset, long highestSeq) {
      this.cleanlyEnded = cleanlyEnded;
      this.stopOffset = stopOffset;
      this.highestSeq = highestSeq;
    }
  }

  /** Visible for tests. */
  @Nullable
  LogSegment currentSegmentForTesting(int shardIndex) {
    return shards[shardIndex].currentSegment();
  }

  /** Visible for tests. */
  @Nullable
  LogSegment currentSegmentForGroup(@NonNull byte[] groupId) {
    return shards[routeShard(groupId)].currentSegment();
  }

  /** Visible for tests. */
  void replaceCurrentSegmentForTesting(int shardIndex, @NonNull LogSegment segment) {
    shards[shardIndex].setCurrentSegment(segment);
  }

  /** Visible for tests. */
  @NonNull
  SegmentIndex segmentIndexForTesting(int shardIndex) {
    return shards[shardIndex].segmentIndex();
  }

  /** Visible for tests. */
  void awaitMailboxDrainedForTesting() throws IOException {
    for (WriterShard sh : shards) {
      PendingWrite pw = new PendingWrite(null, EMPTY_GROUP_ID, null, -1L, true, false);
      sh.submit(pw);
      await(pw);
    }
  }

  /** Visible for tests. */
  WriterShard shardForTesting(int shardIndex) {
    return shards[shardIndex];
  }
}
