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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.hadoop.conf.Configuration;
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
import org.apache.hadoop.hbase.util.Threads;
import org.apache.yetus.audience.InterfaceAudience;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link DurableLogStore} implementation.
 * <p>
 * This is a single multiplexed append-only log shared by every Raft group on the local server,
 * inspired by ZooKeeper's {@code FileTxnLog}.
 * <p>
 * <b>Write path.</b> Records are encoded on the producing thread (via
 * {@link LogRecord#encode(LogRecord)} into a direct {@link ByteBuffer}) and offered to a
 * {@code MpscUnboundedArrayQueue} mailbox. A dedicated writer thread drains the mailbox in
 * coalescing windows of {@link LogStoreConfig#getBatchMs() batchMs}, issues a single
 * {@link FileChannel#write(ByteBuffer[]) gathered write}, calls {@link FileChannel#force(boolean)
 * force(false)} when any frame in the batch requested sync-fsync, then completes every
 * {@link CompletableFuture} in the batch.
 * <p>
 * <b>Fsync-before-commit contract.</b> {@code persistAndFlush{Term,LocalEndpoint,
 * InitialGroupMembers}} block until {@code force(false)} returns (per-call fsync). Log entries and
 * snapshot chunks are enqueued without a per-record fsync, but every caller of
 * {@link RaftStore#flush()} blocks until the writer issues an {@code force(false)} that covers the
 * active segment, so all log-entry frames written before the call are on disk by the time
 * {@code flush()} returns. This is the durability anchor that Raft commit-index advancement relies
 * on: a follower's {@code AppendEntriesSuccessResponse.lastLogIndex} is sent only after
 * {@link RaftStore#flush()} returns, and the leader's {@code FlushTask} sets {@code
 * leaderState.flushedLogIndex()} only after {@link RaftStore#flush()} returns. The leader's
 * commit-index advancement counts only on-disk acks; an entry on a majority's logs is, by
 * construction, on a majority's disks.
 * <p>
 * <b>Flush barrier.</b> {@link RaftStore#flush()} on a per-group adapter enqueues an empty-frame
 * {@code PendingWrite} with {@code fsyncRequested=true}. The writer recognises the empty frame as a
 * no-op marker, so nothing is written for it, but the marker drives the batch's {@code
 * anyFsync} flag and the marker's {@link CompletableFuture} is completed only after the writer has
 * issued {@code force(false)} for the batch. Multiple groups blocked on {@code flush()} collapse
 * onto the same window and amortise the single fsync.
 * <p>
 * <b>Segment rolling, GC, truncation.</b> When the active segment crosses
 * {@link LogStoreConfig#getSegmentSizeBytes()}, the writer appends a best-effort
 * {@link LogRecord.Kind#SEGMENT_FOOTER}, fsyncs+closes, and opens a new segment with a magic +
 * version prologue and a {@link LogRecord.Kind#SEGMENT_HEADER}. Per-tick GC deletes a non-active
 * segment when every group with data in it has advanced past that segment's {@code maxLogIndex}.
 * {@link LogRecord.Kind#TRUNCATE_UNTIL} advances the GC frontier.
 * {@link LogRecord.Kind#TRUNCATE_FROM} is a marker for replay-time tail discard.
 * <p>
 * <b>Recovery.</b> {@link #load()} replays the on-disk log once, in segment-id order. The first
 * non-{@code OK} read result anywhere ({@link LogRecord.ReadResult.Kind#CRC} or
 * {@link LogRecord.ReadResult.Kind#TRUNCATED}) truncates the offending segment at the bad offset,
 * deletes every later segment, and stops replay. Whatever per-group state was reconstructed up to
 * that point is returned. Gaps are closed at the layer above by Raft's
 * {@code AppendEntries}/{@code InstallSnapshot} catch-up.
 */
@InterfaceAudience.Private
public class UnifiedRaftStore implements DurableLogStore {

  private static final Logger LOG = LoggerFactory.getLogger(UnifiedRaftStore.class);

  /**
   * Single message in the writer mailbox. Empty {@code encodedFrame} marks a {@code flush()}
   * barrier.
   */
  static final class PendingWrite {
    @Nullable
    final ByteBuffer encodedFrame;
    final byte[] groupId;
    @Nullable
    final LogRecord.Kind kind;
    final long logIndex;
    final boolean fsyncRequested;
    final CompletableFuture<Void> done = new CompletableFuture<>();

    PendingWrite(@Nullable ByteBuffer encodedFrame, @NonNull byte[] groupId,
      @Nullable LogRecord.Kind kind, long logIndex, boolean fsyncRequested) {
      this.encodedFrame = encodedFrame;
      this.groupId = groupId;
      this.kind = kind;
      this.logIndex = logIndex;
      this.fsyncRequested = fsyncRequested;
    }

    boolean isFlushBarrier() {
      return encodedFrame == null;
    }
  }

  private static final byte[] EMPTY_GROUP_ID = new byte[0];

  private final LogStoreConfig config;
  private final LogStoreSerializer serializer;

  // write-path state
  private final MpscUnboundedArrayQueue<PendingWrite> mailbox;
  private final Thread writer;

  // segment + GC state
  private final SegmentIndex segmentIndex = new SegmentIndex();
  @Nullable
  private volatile LogSegment currentSegment;

  /**
   * Per-group GC frontier. The highest {@code TRUNCATE_UNTIL} log index applied for that group. A
   * non-active segment is GCable when every group with frames in it has advanced past that
   * segment's {@code maxLogIndex} for the group.
   */
  private final Map<ByteBuffer, Long> lastAppliedFlushSeqId = new HashMap<>();

  private final AtomicLong nextSeq = new AtomicLong(0L);

  private volatile boolean running = false;
  private volatile boolean loaded = false;
  private volatile boolean closed = false;

  public UnifiedRaftStore(@NonNull LogStoreConfig config) {
    this(config, new DefaultLogStoreSerializer());
  }

  public UnifiedRaftStore(@NonNull LogStoreConfig config, @NonNull LogStoreSerializer serializer) {
    this.config = config;
    this.serializer = serializer;
    this.mailbox = new MpscUnboundedArrayQueue<>(config.getMailboxChunkSize());
    this.writer = new Thread(this::writerLoop);
  }

  public UnifiedRaftStore(@NonNull Configuration conf) {
    this(new LogStoreConfig(conf));
  }

  @NonNull
  public LogStoreConfig getConfig() {
    return config;
  }

  @NonNull
  @Override
  public synchronized Map<ByteBuffer, RestoredRaftState> load() throws IOException {
    if (loaded) {
      throw new IllegalStateException("load() must be called exactly once");
    }
    Path dir = config.getLogDir().toPath();
    if (!Files.exists(dir)) {
      Files.createDirectories(dir);
    }
    if (!Files.isDirectory(dir)) {
      throw new IOException(LogStoreConfig.LOG_DIR_KEY + " is not a directory: " + dir);
    }

    NavigableMap<Long, Path> segmentsOnDisk = scanSegmentDir(dir);

    Map<ByteBuffer, GroupReplayState> perGroup = new HashMap<>();
    Map<Long, Map<ByteBuffer, Long>> perSegmentMax = new HashMap<>();
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
        rr = replaySegment(segmentId, path, perGroup, segMax);
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
        // This segment was deleted entirely.
        goodSegmentIds.remove(stoppedAtSegmentId);
        perSegmentMax.remove(stoppedAtSegmentId);
      }
      // Drop accounting for any segment we removed.
      Iterator<Map.Entry<Long, Path>> it =
        segmentsOnDisk.tailMap(stoppedAtSegmentId, false).entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<Long, Path> e = it.next();
        goodSegmentIds.remove(e.getKey());
        perSegmentMax.remove(e.getKey());
      }
    }

    rebuildSegmentIndexFromSurvivors(dir, segmentsOnDisk, goodSegmentIds, perSegmentMax,
      activeSegmentSize, highestSeq);

    Map<ByteBuffer, RestoredRaftState> out = new HashMap<>();
    for (Map.Entry<ByteBuffer, GroupReplayState> e : perGroup.entrySet()) {
      RestoredRaftState rs = e.getValue().build();
      if (rs != null) {
        out.put(e.getKey(), rs);
      }
    }

    running = true;
    loaded = true;
    Threads.setDaemonThreadRunning(writer, "UnifiedRaftStore-writer",
      Threads.LOGGING_EXCEPTION_HANDLER);

    LOG.info("UnifiedRaftStore loaded: {} group(s), {} segment(s), nextSeq={}", out.size(),
      segmentIndex.size(), nextSeq.get());
    return out;
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
    if (writer.isAlive()) {
      LockSupport.unpark(writer);
      try {
        writer.join(TimeUnit.SECONDS.toMillis(30));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (currentSegment != null) {
      try {
        currentSegment.force(true);
      } catch (IOException ignore) {
        // best-effort
      }
      currentSegment.close();
    }
    for (LogSegment s : segmentIndex.segmentsById().values()) {
      if (s != currentSegment) {
        try {
          s.close();
        } catch (IOException ignore) {
          // best-effort
        }
      }
    }
  }

  void persistLogEntries(@NonNull byte[] groupId, @NonNull List<LogEntry> entries)
    throws IOException {
    requireOpen();
    LogStoreSerializer.Serializer<LogEntry> ser = serializer.logEntrySerializer();
    for (LogEntry entry : entries) {
      byte[] payload = ser.serialize(entry);
      enqueueAsync(LogRecord.Kind.LOG_ENTRY, groupId, payload, entry.getIndex());
    }
  }

  void persistSnapshotChunk(@NonNull byte[] groupId, @NonNull SnapshotChunk chunk)
    throws IOException {
    requireOpen();
    byte[] payload = serializer.snapshotChunkSerializer().serialize(chunk);
    enqueueAsync(LogRecord.Kind.SNAPSHOT_CHUNK, groupId, payload, chunk.getIndex());
  }

  void persistAndFlushTerm(@NonNull byte[] groupId, @NonNull RaftTermPersistentState state)
    throws IOException {
    requireOpen();
    byte[] payload = serializer.raftTermPersistentStateSerializer().serialize(state);
    awaitFsync(LogRecord.Kind.TERM_VOTE, groupId, payload, -1L);
  }

  void persistAndFlushLocalEndpoint(@NonNull byte[] groupId,
    @NonNull RaftEndpointPersistentState state) throws IOException {
    requireOpen();
    byte[] payload = serializer.raftEndpointPersistentStateSerializer().serialize(state);
    awaitFsync(LogRecord.Kind.LOCAL_ENDPOINT, groupId, payload, -1L);
  }

  void persistAndFlushInitialGroupMembers(@NonNull byte[] groupId,
    @NonNull RaftGroupMembersView view) throws IOException {
    requireOpen();
    byte[] payload = serializer.raftGroupMembersViewSerializer().serialize(view);
    awaitFsync(LogRecord.Kind.INITIAL_MEMBERS, groupId, payload, -1L);
  }

  void truncateLogEntriesFrom(@NonNull byte[] groupId, long logIndexInclusive) throws IOException {
    requireOpen();
    byte[] payload = LogRecord.encodeTruncatePayload(logIndexInclusive);
    enqueueAsync(LogRecord.Kind.TRUNCATE_FROM, groupId, payload, logIndexInclusive);
  }

  void truncateLogEntriesUntil(@NonNull byte[] groupId, long logIndexInclusive) throws IOException {
    requireOpen();
    byte[] payload = LogRecord.encodeTruncatePayload(logIndexInclusive);
    enqueueAsync(LogRecord.Kind.TRUNCATE_UNTIL, groupId, payload, logIndexInclusive);
  }

  void deleteSnapshotChunks(@NonNull byte[] groupId, long logIndex, int chunkCount)
    throws IOException {
    requireOpen();
    byte[] payload = LogRecord.encodeDeleteSnapshotChunksPayload(logIndex, chunkCount);
    enqueueAsync(LogRecord.Kind.DELETE_SNAPSHOT_CHUNKS, groupId, payload, logIndex);
  }

  void flushBarrier(@NonNull byte[] groupId) throws IOException {
    requireOpen();
    // fsyncRequested = true: the writer thread coalesces this barrier with any other concurrent
    // entries / barriers in the same window and issues a single force(false) at the end of the
    // window. flush() therefore returns only after the active segment has been fsynced, so every
    // log-entry frame appended before this call is on disk. This is the durability anchor that
    // Raft commit-index advancement depends on. A follower sends its AppendEntriesSuccessResponse
    // after log.flush() returns, so the matchIndex it advertises reflects on-disk content. The
    // leader's FlushTask sets flushedLogIndex (its own contribution to the commit quorum) after
    // log.flush() returns for the same reason.
    PendingWrite pw = new PendingWrite(null, groupId, null, -1L, true);
    submit(pw);
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

  private void enqueueAsync(LogRecord.Kind kind, byte[] groupId, byte[] payload, long logIndex) {
    LogRecord rec = new LogRecord(kind, nextSeq.getAndIncrement(), groupId, payload);
    ByteBuffer frame = LogRecord.encode(rec);
    submit(new PendingWrite(frame, groupId, kind, logIndex, false));
  }

  private void awaitFsync(LogRecord.Kind kind, byte[] groupId, byte[] payload, long logIndex)
    throws IOException {
    LogRecord rec = new LogRecord(kind, nextSeq.getAndIncrement(), groupId, payload);
    ByteBuffer frame = LogRecord.encode(rec);
    PendingWrite pw = new PendingWrite(frame, groupId, kind, logIndex, true);
    submit(pw);
    await(pw);
  }

  private void submit(PendingWrite pw) {
    while (!mailbox.offer(pw)) {
      // MpscUnboundedArrayQueue.offer never returns false (unbounded), but treat as a backpressure
      // hook for symmetry with bounded variants.
      Thread.onSpinWait();
    }
    LockSupport.unpark(writer);
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

  private void writerLoop() {
    final long batchMs = config.getBatchMs();
    while (running) {
      PendingWrite first = mailbox.poll();
      if (first == null) {
        LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(Math.max(1L, batchMs)));
        continue;
      }
      List<PendingWrite> batch = new ArrayList<>();
      batch.add(first);
      drainBatch(batch, batchMs);
      runBatch(batch);
    }
    // Drain anything left in the mailbox before we exit.
    List<PendingWrite> tail = new ArrayList<>();
    PendingWrite p;
    while ((p = mailbox.poll()) != null) {
      tail.add(p);
    }
    if (!tail.isEmpty()) {
      runBatch(tail);
    }
  }

  private void drainBatch(List<PendingWrite> batch, long batchMs) {
    long deadline = EnvironmentEdgeManager.currentTime() + Math.max(1L, batchMs);
    long now;
    while ((now = EnvironmentEdgeManager.currentTime()) < deadline) {
      PendingWrite next = mailbox.poll();
      if (next != null) {
        batch.add(next);
      } else {
        long sleepMs = Math.max(1L, deadline - now);
        LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(Math.min(sleepMs, 1L)));
      }
    }
    PendingWrite next;
    while ((next = mailbox.poll()) != null) {
      batch.add(next);
    }
  }

  private void runBatch(List<PendingWrite> batch) {
    try {
      processBatch(batch);
    } catch (IOException e) {
      LOG.error("UnifiedRaftStore writer batch failed", e);
      for (PendingWrite pw : batch) {
        if (!pw.done.isDone()) {
          pw.done.completeExceptionally(e);
        }
      }
    }
  }

  private void processBatch(List<PendingWrite> batch) throws IOException {
    List<ByteBuffer> framesList = new ArrayList<>(batch.size());
    boolean anyFsync = false;
    for (PendingWrite pw : batch) {
      if (pw.encodedFrame != null) {
        framesList.add(pw.encodedFrame);
      }
      if (pw.fsyncRequested) {
        anyFsync = true;
      }
    }
    if (!framesList.isEmpty()) {
      currentSegment.appendFrames(framesList.toArray(new ByteBuffer[0]));
    }
    for (PendingWrite pw : batch) {
      if (pw.kind == LogRecord.Kind.LOG_ENTRY || pw.kind == LogRecord.Kind.SNAPSHOT_CHUNK) {
        currentSegment.recordMaxLogIndex(pw.groupId, pw.logIndex);
      } else if (pw.kind == LogRecord.Kind.TRUNCATE_UNTIL) {
        ByteBuffer key =
          ByteBuffer.wrap(Arrays.copyOf(pw.groupId, pw.groupId.length)).asReadOnlyBuffer();
        lastAppliedFlushSeqId.merge(key, pw.logIndex, Math::max);
      }
    }
    if (anyFsync && currentSegment != null) {
      currentSegment.force(false);
    }
    if (currentSegment != null && currentSegment.currentSize() >= config.getSegmentSizeBytes()) {
      rollSegment();
    }
    maybeGcSegments();
    for (PendingWrite pw : batch) {
      pw.done.complete(null);
    }
  }

  private void rollSegment() throws IOException {
    long currentId = currentSegment.segmentId();
    long nextId = currentId + 1L;
    appendHousekeepingFrame(LogRecord.Kind.SEGMENT_FOOTER,
      LogRecord.encodeSegmentFooterPayload(nextId, false));
    currentSegment.force(true);
    currentSegment.close();
    Path newPath = config.getLogDir().toPath().resolve(LogSegment.filename(nextId));
    LogSegment newSeg = LogSegment.create(nextId, newPath);
    segmentIndex.register(newSeg);
    currentSegment = newSeg;
    appendHousekeepingFrame(LogRecord.Kind.SEGMENT_HEADER,
      LogRecord.encodeSegmentHeaderPayload(nextId, EnvironmentEdgeManager.currentTime()));
    currentSegment.force(true);
  }

  private void appendHousekeepingFrame(LogRecord.Kind kind, byte[] payload) throws IOException {
    LogRecord rec = new LogRecord(kind, nextSeq.getAndIncrement(), EMPTY_GROUP_ID, payload);
    currentSegment.appendFrame(LogRecord.encode(rec));
  }

  private void maybeGcSegments() {
    Iterator<Map.Entry<Long, LogSegment>> it = segmentIndex.segmentsById().entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Long, LogSegment> e = it.next();
      LogSegment s = e.getValue();
      if (s == currentSegment) {
        continue;
      }
      boolean canDelete = true;
      for (Map.Entry<ByteBuffer, Long> g : s.maxLogIndexByGroup().entrySet()) {
        Long frontier = lastAppliedFlushSeqId.get(g.getKey());
        if (frontier == null || frontier < g.getValue()) {
          canDelete = false;
          break;
        }
      }
      if (canDelete) {
        try {
          s.close();
          s.deleteFile();
          it.remove();
          LOG.debug("GC'd segment {}", s.path());
        } catch (IOException ioe) {
          LOG.warn("Failed to GC segment {}", s, ioe);
        }
      }
    }
  }

  /**
   * Streams a single segment file in one pass, accumulating per-group state, populating the
   * supplied {@code segMax} (per-group max log index this segment carries, used for GC), and
   * applying any {@code TRUNCATE_UNTIL} markers to the global GC frontier. Returns whether the
   * segment ended cleanly (EndOfFile at a frame boundary) and where it stopped if it didn't.
   */
  private ReplayResult replaySegment(long segmentId, Path path,
    Map<ByteBuffer, GroupReplayState> perGroup, Map<ByteBuffer, Long> segMax) throws IOException {
    long highestSeq = 0L;
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
      LogRecord.Reader reader = new LogRecord.Reader(ch)) {
      while (true) {
        LogRecord.ReadResult rr = reader.next();
        switch (rr.kind()) {
          case OK:
            highestSeq = Math.max(highestSeq, rr.record().getSeq());
            applyRecord(rr.record(), perGroup, segMax);
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
    Map<ByteBuffer, Long> segMax) {
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
      lastAppliedFlushSeqId.merge(key, idx, Math::max);
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
   * Rebuilds {@link #segmentIndex} from the segments that survived load-time replay. The highest
   * surviving segment id becomes the active segment open for append. If the directory is empty
   * (post-truncation or first-boot), creates a fresh segment {@code 0} with the standard prologue +
   * {@code SEGMENT_HEADER}.
   */
  private void rebuildSegmentIndexFromSurvivors(Path dir, NavigableMap<Long, Path> segmentsOnDisk,
    Set<Long> goodSegmentIds, Map<Long, Map<ByteBuffer, Long>> perSegmentMax,
    long activeSegmentSize, long highestSeqSeen) throws IOException {
    if (goodSegmentIds.isEmpty()) {
      long bootId = 0L;
      Path firstPath = dir.resolve(LogSegment.filename(bootId));
      LogSegment seg = LogSegment.create(bootId, firstPath);
      segmentIndex.register(seg);
      currentSegment = seg;
      long seedSeq = highestSeqSeen + 1L;
      LogRecord header = new LogRecord(LogRecord.Kind.SEGMENT_HEADER, seedSeq, EMPTY_GROUP_ID,
        LogRecord.encodeSegmentHeaderPayload(bootId, EnvironmentEdgeManager.currentTime()));
      seg.appendFrame(LogRecord.encode(header));
      seg.force(true);
      nextSeq.set(seedSeq + 1L);
      return;
    }
    long activeId = Collections.max(goodSegmentIds);
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
      segmentIndex.register(seg);
      if (id == activeId) {
        currentSegment = seg;
      }
    }
    nextSeq.set(highestSeqSeen + 1L);
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

  /** Visible for tests */
  @Nullable
  LogSegment currentSegmentForTesting() {
    return currentSegment;
  }

  /**
   * Visible for tests. Installs {@code segment} as the active segment, also re-registering it under
   * the same id in the {@link SegmentIndex} so the writer's own GC pass treats it as the active
   * segment. Caller must quiesce the writer first (e.g. via
   * {@link #awaitMailboxDrainedForTesting()}) and must not exercise segment roll while the wrapper
   * is in place.
   */
  void replaceCurrentSegmentForTesting(@NonNull LogSegment segment) {
    this.currentSegment = segment;
    segmentIndex.register(segment);
  }

  /** Visible for tests */
  SegmentIndex segmentIndexForTesting() {
    return segmentIndex;
  }

  /** Visible for tests */
  Map<ByteBuffer, Long> gcFrontierForTesting() {
    return new HashMap<>(lastAppliedFlushSeqId);
  }

  /** Visible for tests */
  void awaitMailboxDrainedForTesting() throws IOException {
    flushBarrier(EMPTY_GROUP_ID);
  }
}
