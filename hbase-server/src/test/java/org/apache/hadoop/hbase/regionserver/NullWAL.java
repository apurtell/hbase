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
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.regionserver.wal.FailedLogCloseException;
import org.apache.hadoop.hbase.regionserver.wal.WALActionsListener;
import org.apache.hadoop.hbase.regionserver.wal.WALCoprocessorHost;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALEdit;
import org.apache.hadoop.hbase.wal.WALKeyImpl;

public class NullWAL implements WAL {

  private final WALCoprocessorHost host;
  private final AtomicLong nextTxnId = new AtomicLong();
  private final List<WALActionsListener> listeners = new ArrayList<>();

  public NullWAL(Configuration conf) {
    this.host = new WALCoprocessorHost(this, conf);
  }

  @Override
  public void registerWALActionsListener(WALActionsListener listener) {
    listeners.add(listener);
  }

  @Override
  public boolean unregisterWALActionsListener(WALActionsListener listener) {
    return listeners.remove(listener);
  }

  @Override
  public Map<byte[], List<byte[]>> rollWriter() throws FailedLogCloseException, IOException {
    return null;
  }

  @Override
  public Map<byte[], List<byte[]>> rollWriter(boolean force) throws IOException {
    return null;
  }

  @Override
  public void shutdown() throws IOException {
  }

  @Override
  public void close() throws IOException {
  }

  @Override
  public long appendData(RegionInfo info, WALKeyImpl key, WALEdit edits) throws IOException {
    long[] txid = { 0 };
    MultiVersionConcurrencyControl.WriteEntry we = key.getMvcc().begin(() -> {
      txid[0] = nextTxnId.getAndIncrement();
    });
    key.setWriteEntry(we);
    return txid[0];
  }

  @Override
  public long appendMarker(RegionInfo info, WALKeyImpl key, WALEdit edits) throws IOException {
    return appendData(info, key, edits);
  }

  @Override
  public void sync() throws IOException {
  }

  @Override
  public void sync(long txid) throws IOException {
  }

  @Override
  public OptionalLong getLogFileSizeIfBeingWritten(Path path) {
    return OptionalLong.empty();
  }

  @Override
  public void updateStore(byte[] encodedRegionName, byte[] familyName, Long sequenceid,
      boolean onlyIfGreater) {
  }

  @Override
  public Long startCacheFlush(byte[] encodedRegionName, Set<byte[]> families) {
    return null;
  }

  @Override
  public Long startCacheFlush(byte[] encodedRegionName, Map<byte[], Long> familyToSeq) {
    return HConstants.NO_SEQNUM;
  }

  @Override
  public void completeCacheFlush(byte[] encodedRegionName, long maxFlushedSeqId) {
  }

  @Override
  public void abortCacheFlush(byte[] encodedRegionName) {
  }

  @Override
  public WALCoprocessorHost getCoprocessorHost() {
    return host;
  }

  @Override
  public long getEarliestMemStoreSeqNum(byte[] encodedRegionName) {
    return HConstants.NO_SEQNUM;
  }

  @Override
  public long getEarliestMemStoreSeqNum(byte[] encodedRegionName, byte[] familyName) {
    return HConstants.NO_SEQNUM;
  }

}
