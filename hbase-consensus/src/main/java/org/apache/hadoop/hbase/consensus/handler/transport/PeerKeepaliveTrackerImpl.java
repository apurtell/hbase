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
package org.apache.hadoop.hbase.consensus.handler.transport;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.hadoop.hbase.consensus.raft.RaftEndpoint;
import org.apache.hadoop.hbase.consensus.raft.transport.PeerKeepaliveTracker;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Concurrent-map-backed implementation of {@link PeerKeepaliveTracker}. The hot path is the inbound
 * dispatch in {@link InboundHandler}, which calls
 * {@link #onPeerKeepalive(RaftEndpoint, long, long, long)} once per {@code HEARTBEAT_BATCH}
 * envelope received from {@code sender}.
 */
@InterfaceAudience.Private
final class PeerKeepaliveTrackerImpl implements PeerKeepaliveTracker {

  private static final class Entry {
    final long epoch;
    final long tick;
    final long observedAtMillis;

    Entry(long epoch, long tick, long observedAtMillis) {
      this.epoch = epoch;
      this.tick = tick;
      this.observedAtMillis = observedAtMillis;
    }

    boolean strictlyAfter(long otherEpoch, long otherTick) {
      if (epoch != otherEpoch) {
        return epoch > otherEpoch;
      }
      return tick > otherTick;
    }
  }

  private final ConcurrentMap<RaftEndpoint, Entry> latest = new ConcurrentHashMap<>();

  @Override
  public void onPeerKeepalive(@NonNull RaftEndpoint sender, long epoch, long tick, long nowMillis) {
    Entry candidate = new Entry(epoch, tick, nowMillis);
    while (true) {
      Entry current = latest.get(sender);
      if (current == null) {
        if (latest.putIfAbsent(sender, candidate) == null) {
          return;
        }
      } else if (!candidate.strictlyAfter(current.epoch, current.tick)) {
        // Out-of-order or duplicate envelope; ignore.
        return;
      } else if (latest.replace(sender, current, candidate)) {
        return;
      }
    }
  }

  @Override
  public long lastKeepaliveMillis(@NonNull RaftEndpoint sender) {
    Entry e = latest.get(sender);
    return e == null ? 0L : e.observedAtMillis;
  }
}
