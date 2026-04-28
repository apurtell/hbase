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
package org.apache.hadoop.hbase.consensus.raft;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Server-wide credit pool that bounds the aggregate byte footprint of uncommitted leader-side
 * propose operations across <i>all</i> Raft groups hosted by a single {@code ConsensusServer}.
 * <p>
 * The Raft propose path borrows credits via {@link #tryAcquire(long)} when admitting a new
 * {@code byte[]} operation on the leader, and releases the same number of credits via
 * {@link #release(long)} when the entry is committed (per-entry, in the commit applier) or when the
 * leader steps down / terminates with reservations still outstanding (wholesale, against the sum
 * tracked by the per-node accountant). The default implementation is backed by a single
 * {@link AtomicLong}; no log walks are performed.
 * <p>
 * Implementations must be safe for concurrent use by every per-group executor in the host server.
 * Use {@link #create(long)} to obtain the default implementation; use {@link #UNLIMITED} for
 * standalone {@code RaftNode} unit tests that should not be affected by the global budget.
 */
@InterfaceAudience.Private
public interface PendingBytesBudget {

  /**
   * Atomically reserves {@code bytes} from the budget if doing so does not exceed
   * {@link #capacity()}. Returns {@code true} on success (caller now owes a matching
   * {@link #release(long)} call); returns {@code false} otherwise without touching the budget.
   * {@code bytes <= 0} is a no-op success.
   */
  boolean tryAcquire(long bytes);

  /**
   * Returns {@code bytes} to the budget. {@code bytes <= 0} is a no-op. Implementations must not
   * underflow below zero in the steady state; callers are expected to release exactly what they
   * previously acquired.
   */
  void release(long bytes);

  /** Currently reserved bytes. Mainly for monitoring and tests. */
  long inUse();

  /** Maximum bytes the budget will admit. */
  long capacity();

  /**
   * Returns the default {@link PendingBytesBudget} implementation, backed by a single
   * {@link AtomicLong}. Lock-free CAS loop on acquire; single {@code addAndGet} on release.
   * @param capacity strictly positive maximum the budget will admit
   * @throws IllegalArgumentException if {@code capacity <= 0}
   */
  static PendingBytesBudget create(long capacity) {
    if (capacity <= 0L) {
      throw new IllegalArgumentException("capacity must be positive, got " + capacity);
    }
    return new AtomicLongImpl(capacity);
  }

  /**
   * No-op budget that always permits acquisition.
   */
  PendingBytesBudget UNLIMITED = new PendingBytesBudget() {
    @Override
    public boolean tryAcquire(long bytes) {
      return true;
    }

    @Override
    public void release(long bytes) {
      // no-op
    }

    @Override
    public long inUse() {
      return 0L;
    }

    @Override
    public long capacity() {
      return Long.MAX_VALUE;
    }

    @Override
    public String toString() {
      return "PendingBytesBudget.UNLIMITED";
    }
  };

  /**
   * Default implementation. Package-private; obtain via {@link PendingBytesBudget#create(long)}.
   */
  final class AtomicLongImpl implements PendingBytesBudget {
    private final long capacity;
    private final AtomicLong inUse = new AtomicLong();

    private AtomicLongImpl(long capacity) {
      this.capacity = capacity;
    }

    @Override
    public boolean tryAcquire(long bytes) {
      if (bytes <= 0L) {
        return true;
      }
      while (true) {
        long current = inUse.get();
        long next = current + bytes;
        if (next < 0L || next > capacity) {
          // overflow protection or budget exhausted
          return false;
        }
        if (inUse.compareAndSet(current, next)) {
          return true;
        }
      }
    }

    @Override
    public void release(long bytes) {
      if (bytes <= 0L) {
        return;
      }
      long updated = inUse.addAndGet(-bytes);
      if (updated < 0L) {
        inUse.compareAndSet(updated, 0L);
      }
    }

    @Override
    public long inUse() {
      return inUse.get();
    }

    @Override
    public long capacity() {
      return capacity;
    }

    @Override
    public String toString() {
      return "PendingBytesBudget{inUse=" + inUse.get() + ", capacity=" + capacity + "}";
    }
  }
}
