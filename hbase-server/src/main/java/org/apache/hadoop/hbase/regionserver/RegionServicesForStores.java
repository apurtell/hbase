/*
 *
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

import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.io.ByteBuffAllocator;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Services a Store needs from a Region.
 * RegionServicesForStores class is the interface through which memstore access services at the
 * region level.
 * For example, when using alternative memory formats or due to compaction the memstore needs to
 * take occasional lock and update size counters at the region level.
 */
@InterfaceAudience.Private
public class RegionServicesForStores {

  private final HRegion region;
  private final RegionServerServices rsServices;

  public RegionServicesForStores(HRegion region, RegionServerServices rsServices) {
    this.region = region;
    this.rsServices = rsServices;
  }

  public void addMemStoreSize(long dataSizeDelta, long heapSizeDelta, long offHeapSizeDelta,
      int cellsCountDelta) {
    region.incMemStoreSize(dataSizeDelta, heapSizeDelta, offHeapSizeDelta, cellsCountDelta);
  }

  public RegionInfo getRegionInfo() {
    return region.getRegionInfo();
  }

  public WAL getWAL() {
    return region.getWAL();
  }

  private static ByteBuffAllocator ALLOCATOR_FOR_TEST;

  private static synchronized ByteBuffAllocator getAllocatorForTest() {
    if (ALLOCATOR_FOR_TEST == null) {
      ALLOCATOR_FOR_TEST = ByteBuffAllocator.HEAP;
    }
    return ALLOCATOR_FOR_TEST;
  }

  public ByteBuffAllocator getByteBuffAllocator() {
    if (rsServices != null && rsServices.getRpcServer() != null) {
      return rsServices.getRpcServer().getByteBuffAllocator();
    } else {
      return getAllocatorForTest();
    }
  }

  public long getMemStoreFlushSize() {
    return region.getMemStoreFlushSize();
  }

  public int getNumStores() {
    return region.getTableDescriptor().getColumnFamilyCount();
  }

  long getMemStoreSize() {
    return region.getMemStoreDataSize();
  }
}
