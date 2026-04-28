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

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.yetus.audience.InterfaceAudience;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The independent writer shard inside a {@link UnifiedRaftStore}. Owns a private
 * {@link MpscUnboundedArrayQueue} mailbox, a dedicated writer thread, a per-shard
 * {@link SegmentIndex} + active {@link LogSegment}, and a per-shard sequence counter / GC frontier.
 * <p>
 * Group ids hash to shard via {@link UnifiedRaftStore#routeShard(byte[])}.
 */
@InterfaceAudience.Private
final class WriterShard {

  private static final Logger LOG = LoggerFactory.getLogger(WriterShard.class);

  /** Watchdog park duration when the mailbox is observed empty. */
  private static final long WRITER_IDLE_PARK_MILLIS = 1_000L;

  private static final byte[] EMPTY_GROUP_ID = new byte[0];

  private final UnifiedRaftStore parent;
  private final LogStoreConfig config;
  private final int shardIndex;
  private final Path shardDir;
  private final MpscUnboundedArrayQueue<UnifiedRaftStore.PendingWrite> mailbox;
  private final Thread writer;
  private final List<UnifiedRaftStore.PendingWrite> batch = new ArrayList<>();
  private final List<ByteBuffer> frames = new ArrayList<>();
  private final SegmentIndex segmentIndex = new SegmentIndex();
  /** Per-group GC frontier. */
  private final Map<ByteBuffer, Long> lastAppliedFlushSeqId = new HashMap<>();
  /** Per-shard sequence counter. Segment-relative; collisions across shards are fine. */
  private final AtomicLong nextSeq = new AtomicLong(0L);

  private ByteBuffer[] gather = new ByteBuffer[0];
  @Nullable
  private volatile LogSegment currentSegment;
  /** Bytes appended via writev since the last successful {@code force(*)} on the active segment. */
  private volatile long unflushedBytes = 0L;

  WriterShard(@NonNull UnifiedRaftStore parent, @NonNull LogStoreConfig config,
    @NonNull LogStoreSerializer serializer, int shardIndex, @NonNull Path shardDir) {
    requireNonNull(serializer, "serializer");
    this.parent = requireNonNull(parent, "parent");
    this.config = config;
    this.shardIndex = shardIndex;
    this.shardDir = shardDir;
    this.mailbox = new MpscUnboundedArrayQueue<>(config.getMailboxChunkSize());
    this.writer = new Thread(this::writerLoop);
  }

  int shardIndex() {
    return shardIndex;
  }

  @NonNull
  Path shardDir() {
    return shardDir;
  }

  long nextSequence() {
    return nextSeq.getAndIncrement();
  }

  /** Seeds the per-shard sequence counter during {@code load()} replay. */
  void seedNextSequence(long value) {
    this.nextSeq.set(value);
  }

  long unflushedBytes() {
    return unflushedBytes;
  }

  @Nullable
  LogSegment currentSegment() {
    return currentSegment;
  }

  void setCurrentSegment(@NonNull LogSegment segment) {
    this.currentSegment = segment;
    segmentIndex.register(segment);
  }

  @NonNull
  SegmentIndex segmentIndex() {
    return segmentIndex;
  }

  @NonNull
  Map<ByteBuffer, Long> gcFrontier() {
    return lastAppliedFlushSeqId;
  }

  void seedGcFrontier(@NonNull Map<ByteBuffer, Long> frontier) {
    this.lastAppliedFlushSeqId.putAll(frontier);
  }

  void start(@NonNull String threadName) {
    writer.setName(threadName);
    writer.setDaemon(true);
    writer.setUncaughtExceptionHandler(
      (t, e) -> LOG.error("Uncaught exception on {}: {}", threadName, e.getMessage(), e));
    writer.start();
  }

  /** Submits a {@link UnifiedRaftStore.PendingWrite} to this shard's mailbox. */
  void submit(@NonNull UnifiedRaftStore.PendingWrite pw) {
    mailbox.offer(pw);
    LockSupport.unpark(writer);
  }

  /** Stops the writer and joins it. Called from {@link UnifiedRaftStore#close()}. */
  void shutdown() {
    if (writer.isAlive()) {
      LockSupport.unpark(writer);
      try {
        writer.join(TimeUnit.SECONDS.toMillis(30));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Final close of all segment file handles. Called after {@link #shutdown()} in
   * {@link UnifiedRaftStore#close()}. The active segment is fsynced + truncated back to the actual
   * record tail before close so non-active segments do not waste disk.
   */
  void closeSegments() {
    if (currentSegment != null) {
      try {
        if (config.isPreallocSegment()) {
          currentSegment.truncate(currentSegment.currentSize());
        }
        currentSegment.force(true);
        unflushedBytes = 0L;
      } catch (IOException ignore) {
        // best-effort
      }
      try {
        currentSegment.close();
      } catch (IOException ignore) {
        // best-effort
      }
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

  private void writerLoop() {
    while (parent.isRunning()) {
      UnifiedRaftStore.PendingWrite first = mailbox.poll();
      if (first == null) {
        LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(WRITER_IDLE_PARK_MILLIS));
        continue;
      }
      batch.clear();
      batch.add(first);
      // Lazy / opportunistic coalescing: drain whatever else is currently queued. Records that
      // arrive while the in-flight runBatch is inside {@code writev + force} accumulate in the
      // mailbox and are picked up by the next iteration in a single batch.
      UnifiedRaftStore.PendingWrite next;
      while ((next = mailbox.relaxedPoll()) != null) {
        batch.add(next);
      }
      runBatch(batch);
    }
    // Drain anything left in the mailbox before we exit.
    batch.clear();
    UnifiedRaftStore.PendingWrite p;
    while ((p = mailbox.poll()) != null) {
      batch.add(p);
    }
    if (!batch.isEmpty()) {
      runBatch(batch);
    }
  }

  private void runBatch(List<UnifiedRaftStore.PendingWrite> batch) {
    try {
      processBatch(batch);
    } catch (IOException e) {
      LOG.error("WriterShard {} batch failed", shardIndex, e);
      for (UnifiedRaftStore.PendingWrite pw : batch) {
        if (!pw.done.isDone()) {
          pw.done.completeExceptionally(e);
        }
      }
    }
  }

  private void processBatch(List<UnifiedRaftStore.PendingWrite> batch) throws IOException {
    frames.clear();
    boolean anyMandatoryFsync = false;
    boolean anyBarrierFsync = false;
    long batchBytes = 0L;
    for (UnifiedRaftStore.PendingWrite pw : batch) {
      if (pw.encodedFrame != null) {
        frames.add(pw.encodedFrame);
        batchBytes += pw.encodedFrame.remaining();
      }
      if (pw.mandatoryFsync) {
        anyMandatoryFsync = true;
      } else if (pw.fsyncRequested) {
        anyBarrierFsync = true;
      }
      if (pw.kind == LogRecord.Kind.LOG_ENTRY || pw.kind == LogRecord.Kind.SNAPSHOT_CHUNK) {
        currentSegment.recordMaxLogIndex(pw.groupId, pw.logIndex);
      } else if (pw.kind == LogRecord.Kind.TRUNCATE_UNTIL) {
        ByteBuffer key = ByteBuffer.wrap(pw.groupId).asReadOnlyBuffer();
        lastAppliedFlushSeqId.merge(key, pw.logIndex, Math::max);
      }
    }
    if (!frames.isEmpty()) {
      // Reuse a single ByteBuffer[] across batches.
      if (gather.length < frames.size()) {
        gather = new ByteBuffer[Math.max(frames.size(), gather.length * 2)];
      }
      frames.toArray(gather);
      currentSegment.appendFrames(gather, 0, frames.size());
      unflushedBytes += batchBytes;
      // Drop references to the just-written buffers in the slots we used so they are not pinned
      // until the next batch.
      for (int i = 0; i < frames.size(); i++) {
        gather[i] = null;
      }
    }
    // Tier A entries (term/vote/local-endpoint/initial-members) and the periodic-fsync timer
    // marker carry mandatoryFsync=true and always fsync. Tier B flush barriers carry
    // fsyncRequested=true && mandatoryFsync=false; they fsync only when fsyncOnCommit is set
    // (Tier C strict mode), otherwise the barrier completes after writev.
    boolean shouldFsync = anyMandatoryFsync || (config.isFsyncOnCommit() && anyBarrierFsync);
    if (shouldFsync && currentSegment != null) {
      currentSegment.force(false);
      unflushedBytes = 0L;
    }
    if (currentSegment != null && currentSegment.currentSize() >= config.getSegmentSizeBytes()) {
      rollSegment();
    }
    maybeGcSegments();
    for (UnifiedRaftStore.PendingWrite pw : batch) {
      pw.done.complete(null);
    }
    frames.clear();
  }

  private void rollSegment() throws IOException {
    long currentId = currentSegment.segmentId();
    long nextId = currentId + 1L;
    appendHousekeepingFrame(LogRecord.Kind.SEGMENT_FOOTER,
      LogRecord.encodeSegmentFooterPayload(nextId, false));
    if (config.isPreallocSegment()) {
      currentSegment.truncate(currentSegment.currentSize());
    }
    currentSegment.force(true);
    unflushedBytes = 0L;
    currentSegment.close();
    Path newPath = shardDir.resolve(LogSegment.filename(nextId));
    LogSegment newSeg = LogSegment.create(nextId, newPath,
      config.isPreallocSegment() ? config.getSegmentSizeBytes() : 0L);
    segmentIndex.register(newSeg);
    currentSegment = newSeg;
    appendHousekeepingFrame(LogRecord.Kind.SEGMENT_HEADER,
      LogRecord.encodeSegmentHeaderPayload(nextId, EnvironmentEdgeManager.currentTime()));
    currentSegment.force(true);
    unflushedBytes = 0L;
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

  /** Test-only hook. Resets the periodic-fsync watermark to zero. */
  void resetUnflushedBytesForTesting() {
    this.unflushedBytes = 0L;
  }
}
