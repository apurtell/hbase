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

package org.apache.hadoop.hbase.regionserver.wal;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.OperationWithAttributes;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.replication.ReplicationAdmin;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.io.FSDataInputStreamWrapper;
import org.apache.hadoop.hbase.io.Reference;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.regionserver.DeleteTracker;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.MiniBatchOperationInProgress;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.Region.Operation;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFile.Reader;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.replication.ReplicationPeerConfig;
import org.apache.hadoop.hbase.replication.VerifyWALEntriesReplicationEndpoint;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALKey;
import org.apache.hadoop.hbase.zookeeper.ZKConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.ImmutableList;

@Category(MediumTests.class)
public class TestEndToEndAttributes {

  static final Log LOG = LogFactory.getLog(TestEndToEndAttributes.class);
  static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  static final TableName TABLE_NAME = TableName.valueOf("TestEndToEndAttributes");
  static final byte[] F = Bytes.toBytes("i");
  static final byte[] Q1 = Bytes.toBytes("1");
  static final byte[] Q2 = Bytes.toBytes("2");
  static final byte[] Q3 = Bytes.toBytes("3");
  static final String MUTATION_ATTR_KEY = "MUTATION_KEY";
  static final byte[] WAL_ATTR_KEY = Bytes.toBytes("CONTEXT_KEY");
  static final AtomicLong ROW_KEY_SEQUENCE = new AtomicLong();
  static final AtomicLong ATTR_VALUE_SEQUENCE = new AtomicLong();
  static final int MAX_VALUE_SIZE = 100;
  static final Map<byte[],byte[]> EXPECTED_MAP =
    Collections.synchronizedMap(new TreeMap<byte[],byte[]>(Bytes.BYTES_COMPARATOR));
  static final Set<byte[]> CONFIRMED_SET =
    Collections.synchronizedSet(new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR));
  static final ThreadLocal<byte[]> TL_WAL_ATTR = new ThreadLocal<byte[]>();
  static final ThreadLocal<Boolean> TL_BATCH_IN_PROGRESS = new ThreadLocal<Boolean>() {
    @Override protected Boolean initialValue() { return false; }    
  };

  private static byte[] nextRowKey() {
    return Bytes.toBytes(String.format("%08d", ROW_KEY_SEQUENCE.getAndIncrement()));
  }

  private static byte[] nextAttribute() {
    return Bytes.toBytes(String.format("%08d", ATTR_VALUE_SEQUENCE.getAndIncrement()));
  }

  private static byte[] nextValue() {
    byte[] value = new byte[ThreadLocalRandom.current().nextInt(MAX_VALUE_SIZE)];
    Bytes.random(value);
    return value;
  }

  private static void setExpectedAttr(byte[] row, byte[] attr) {
    EXPECTED_MAP.put(row, attr);
  }

  private static boolean verifyWALKeyAttribute(byte[] row, WALKey key) {
    byte[] value = key.getAttribute(WAL_ATTR_KEY);
    if (value != null) {
      byte[] expected = EXPECTED_MAP.get(row);
      if (expected != null) {
        int result = Bytes.compareTo(expected, value);
        if (result != 0) {
          LOG.error("Expected value for attribute for " + Bytes.toStringBinary(row) +
            " did not match: is=" + Bytes.toStringBinary(value) +
            " want=" + Bytes.toStringBinary(expected));
          return false;
        } else {
          CONFIRMED_SET.add(row);
        }
      } else {
        LOG.error("Expected value for attribute for " + Bytes.toStringBinary(row) +
          " not found, somehow we missed this edit??");
        return false;
      }
    }
    return true;
  }

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    Configuration conf = TEST_UTIL.getConfiguration();
    TEST_UTIL.startMiniCluster();
    try (ReplicationAdmin rAdmin = new ReplicationAdmin(conf)) {
      rAdmin.addPeer("test",
        new ReplicationPeerConfig()
          .setClusterKey(ZKConfig.getZooKeeperClusterKey(conf))
          .setReplicationEndpointImpl(TestReplicationEndpoint.class.getName()));
    }
    try (HBaseAdmin admin = TEST_UTIL.getHBaseAdmin()) {
      HTableDescriptor desc = new HTableDescriptor(TABLE_NAME);
      desc.addFamily(new HColumnDescriptor(F));
      desc.addCoprocessor(TestRegionObserver.class.getName());
      admin.createTable(desc);
    }
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test
  public void testEndToEndAttributes() throws Exception {
    byte[] row, attr;

    // Single put case

    try (Table table = TEST_UTIL.getConnection().getTable(TABLE_NAME)) {
      row = nextRowKey();
      attr = nextAttribute();
      setExpectedAttr(row, attr);
      table.put(new Put(row).addColumn(F, Q1, nextValue()).setAttribute(MUTATION_ATTR_KEY, attr));
    }

    // Single delete case

    try (Table table = TEST_UTIL.getConnection().getTable(TABLE_NAME)) {
      row = nextRowKey();
      attr = nextAttribute();
      setExpectedAttr(row, attr);
      table.delete(new Delete(row).addColumn(F, Q1).setAttribute(MUTATION_ATTR_KEY, attr));
    }

    // Batch put case

    try (Table table = TEST_UTIL.getConnection().getTable(TABLE_NAME)) {
      List<Row> actions = new ArrayList<>();
      actions.add(new Put(nextRowKey()).addColumn(F, Q1, nextValue()));
      actions.add(new Put(nextRowKey()).addColumn(F, Q1, nextValue()));
      actions.add(new Put(nextRowKey()).addColumn(F, Q1, nextValue()));
      actions.add(new Put(nextRowKey()).addColumn(F, Q1, nextValue()));
      actions.add(new Put(nextRowKey()).addColumn(F, Q1, nextValue()));
      // We must have the attribute set on the first mutation in the batch and the server,
      // depending on HBase version, may sort the incoming batch lexiographically by rowkey
      // before processing it. Do the sort here to ensure we get it right no matter what
      // nextRowKey is doing.
      Collections.sort(actions, new Comparator<Row>() {
        @Override public int compare(Row r1, Row r2) { return r1.compareTo(r2); } });
      row = actions.get(0).getRow();
      attr = nextAttribute();
      ((OperationWithAttributes)actions.get(0)).setAttribute(MUTATION_ATTR_KEY, attr);
      setExpectedAttr(row, attr);
      Object[] results = new Object[actions.size()];
      table.batch(actions, results);
    }

    // Batch of mixed puts and deletes

    try (Table table = TEST_UTIL.getConnection().getTable(TABLE_NAME)) {
      List<Row> actions = new ArrayList<>();
      actions.add(new Put(nextRowKey()).addColumn(F, Q1, nextValue()));
      actions.add(new Put(nextRowKey()).addColumn(F, Q1, nextValue()));
      actions.add(new Delete(nextRowKey()).addColumn(F, Q1));
      actions.add(new Delete(nextRowKey()).addColumn(F, Q1));
      actions.add(new Put(nextRowKey()).addColumn(F, Q1, nextValue()));
      // We must have the attribute set on the first mutation in the batch and the server,
      // depending on HBase version, may sort the incoming batch lexiographically by rowkey
      // before processing it. Do the sort here to ensure we get it right no matter what
      // nextRowKey is doing.
      Collections.sort(actions, new Comparator<Row>() {
        @Override public int compare(Row r1, Row r2) { return r1.compareTo(r2); } });
      row = actions.get(0).getRow();
      attr = nextAttribute();
      ((OperationWithAttributes)actions.get(0)).setAttribute(MUTATION_ATTR_KEY, attr);
      setExpectedAttr(row, attr);
      Object[] results = new Object[actions.size()];
      table.batch(actions, results);
    }

    // RowMutations case

    try (Table table = TEST_UTIL.getConnection().getTable(TABLE_NAME)) {
      row = nextRowKey();
      attr = nextAttribute();
      setExpectedAttr(row, attr);
      RowMutations rm = new RowMutations(row);
      rm.add(new Put(row).addColumn(F, Q1, nextValue()).setAttribute(MUTATION_ATTR_KEY, attr));
      rm.add(new Put(row).addColumn(F, Q2, nextValue()));
      rm.add(new Put(row).addColumn(F, Q3, nextValue()));
      table.mutateRow(rm);
    }

    // Append case

    try (Table table = TEST_UTIL.getConnection().getTable(TABLE_NAME)) {
      row = nextRowKey();
      attr = nextAttribute();
      setExpectedAttr(row, attr);
      table.append(new Append(row).add(F, Q1, nextValue()).setAttribute(MUTATION_ATTR_KEY, attr));
    }

    // Increment case

    try (Table table = TEST_UTIL.getConnection().getTable(TABLE_NAME)) {
      row = nextRowKey();
      attr = nextAttribute();
      setExpectedAttr(row, attr);
      table.increment(new Increment(row).addColumn(F, Q1, 1).setAttribute(MUTATION_ATTR_KEY, attr));
    }

    // Give replication some time to complete
    Thread.sleep(5 * 1000);

    // For every row we put into EXPECTED_MAP on the client side, we should have confirmed
    // an attribute in the associated WALedit in the replication endpoint.
    for (byte[] expectedRow: EXPECTED_MAP.keySet()) {
      if (!CONFIRMED_SET.contains(expectedRow)) {
        fail("We did not find expected row " + Bytes.toStringBinary(expectedRow) +
          " in the confirmed set; replication didn't see this WALentry" +
          " or the end-to-end attribute path is incomplete.");
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  // TestRegionWALObserver
  //

  public static class TestRegionObserver implements RegionObserver {

    // Test implementation

    @Override
    public void preBatchMutate(ObserverContext<RegionCoprocessorEnvironment> c,
        MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException {
      // For a batch operation the mutation attribute for the entire batch will be
      // attached only to the first operation in the RPC.
      Mutation m = miniBatchOp.getOperation(0);
      byte[] attr = m.getAttribute(MUTATION_ATTR_KEY);
      if (attr != null) {
        TL_WAL_ATTR.set(attr);
      } else {
        throw new IOException("No attribute with key " + MUTATION_ATTR_KEY +
          " found for batch beginning with " + m);
      }
      // Signal a batch operation in progress so we don't have other hooks for individual
      // actions called out of batch processing attempting to extract the attribute from
      // those. For batch operations we expect the attribute will only be attached to the
      // first operation submitted in the batch.
      TL_BATCH_IN_PROGRESS.set(true);
    }

    @Override
    public void postBatchMutate(ObserverContext<RegionCoprocessorEnvironment> c,
        MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException {
      // The batch operation is completed, so deassert the condition.
      TL_BATCH_IN_PROGRESS.set(false);
    }

    @Override
    public Result preAppend(ObserverContext<RegionCoprocessorEnvironment> c, Append append)
        throws IOException {
      if (!TL_BATCH_IN_PROGRESS.get()) {
        byte[] attr = append.getAttribute(MUTATION_ATTR_KEY);
        if (attr != null) {
          TL_WAL_ATTR.set(attr);
        } else {
          throw new IOException("No attribute with key " + MUTATION_ATTR_KEY +
            " found for append " + append);
        }
      }
      return null;
    }

    @Override
    public Result preIncrement(ObserverContext<RegionCoprocessorEnvironment> c, Increment increment)
        throws IOException {
      if (!TL_BATCH_IN_PROGRESS.get()) {
        byte[] attr = increment.getAttribute(MUTATION_ATTR_KEY);
        if (attr != null) {
          TL_WAL_ATTR.set(attr);
        } else {
          throw new IOException("No attribute with key " + MUTATION_ATTR_KEY +
            " found for increment " + increment);
        }
      }
      return null;
    }

    @Override
    public void preWALAppend(ObserverContext<RegionCoprocessorEnvironment> ctx, WALKey key,
        WALEdit edit) throws IOException {
      byte[] attr = TL_WAL_ATTR.get();
      key.setAttribute(WAL_ATTR_KEY, attr);
    }

    @Override
    public void prePut(ObserverContext<RegionCoprocessorEnvironment> c, Put put, WALEdit edit,
        Durability durability) throws IOException {
      // Nothing to do, will be handled by preBatchMutate
    }

    @Override
    public void preDelete(ObserverContext<RegionCoprocessorEnvironment> c, Delete delete,
        WALEdit edit, Durability durability) throws IOException {
      // Nothing to do, will be handled by preBatchMutate
    }

    // Default methods

    @Override
    public void start(CoprocessorEnvironment env) throws IOException { }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException { }

    @Override
    public void preOpen(ObserverContext<RegionCoprocessorEnvironment> c) throws IOException { }

    @Override
    public void postOpen(ObserverContext<RegionCoprocessorEnvironment> c) { }

    @Override
    public void postLogReplay(ObserverContext<RegionCoprocessorEnvironment> c) { }

    @Override
    public InternalScanner preFlushScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c,
        Store store, KeyValueScanner memstoreScanner, InternalScanner s)
        throws IOException { return null; }

    @Override
    public InternalScanner preFlushScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c,
        Store store, KeyValueScanner memstoreScanner, InternalScanner s, long readPoint)
        throws IOException { return null; }

    @Override
    public void preFlush(ObserverContext<RegionCoprocessorEnvironment> c) throws IOException { }

    @Override
    public InternalScanner preFlush(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        InternalScanner scanner) throws IOException { return scanner; }

    @Override
    public void postFlush(ObserverContext<RegionCoprocessorEnvironment> c) throws IOException { }

    @Override
    public void postFlush(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        StoreFile resultFile) throws IOException { }

    @Override
    public void preCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        List<StoreFile> candidates, CompactionRequest request) throws IOException { }

    @Override
    public void preCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        List<StoreFile> candidates) throws IOException { }

    @Override
    public void postCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        ImmutableList<StoreFile> selected, CompactionRequest request) { }

    @Override
    public void postCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        ImmutableList<StoreFile> selected) { }

    @Override
    public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        InternalScanner scanner, ScanType scanType, CompactionRequest request)
        throws IOException { return scanner; }

    @Override
    public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        InternalScanner scanner, ScanType scanType) throws IOException { return scanner; }

    @Override
    public InternalScanner preCompactScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c,
        Store store, List<? extends KeyValueScanner> scanners, ScanType scanType,
        long earliestPutTs, InternalScanner s, CompactionRequest request)
        throws IOException { return null; }

    @Override
    public InternalScanner preCompactScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c,
        Store store, List<? extends KeyValueScanner> scanners, ScanType scanType,
        long earliestPutTs, InternalScanner s, CompactionRequest request,
        long readPoint) throws IOException { return null; }

    @Override
    public InternalScanner preCompactScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c,
        Store store, List<? extends KeyValueScanner> scanners, ScanType scanType,
        long earliestPutTs, InternalScanner s) throws IOException { return null; }

    @Override
    public void postCompact(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        StoreFile resultFile, CompactionRequest request) throws IOException { }

    @Override
    public void postCompact(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
        StoreFile resultFile) throws IOException { }

    @Override
    public void preSplit(ObserverContext<RegionCoprocessorEnvironment> c) throws IOException { }

    @Override
    public void preSplit(ObserverContext<RegionCoprocessorEnvironment> c, byte[] splitRow)
        throws IOException { }

    @Override
    public void postSplit(ObserverContext<RegionCoprocessorEnvironment> c, Region l, Region r)
        throws IOException { }

    @Override
    public void preSplitBeforePONR(ObserverContext<RegionCoprocessorEnvironment> ctx,
        byte[] splitKey, List<Mutation> metaEntries) throws IOException { }

    @Override
    public void preSplitAfterPONR(ObserverContext<RegionCoprocessorEnvironment> ctx)
        throws IOException { }

    @Override
    public void preRollBackSplit(ObserverContext<RegionCoprocessorEnvironment> ctx)
        throws IOException { }

    @Override
    public void postRollBackSplit(ObserverContext<RegionCoprocessorEnvironment> ctx)
        throws IOException { }

    @Override
    public void postCompleteSplit(ObserverContext<RegionCoprocessorEnvironment> ctx)
        throws IOException { }

    @Override
    public void preClose(ObserverContext<RegionCoprocessorEnvironment> c, boolean abortRequested)
        throws IOException { }

    @Override
    public void postClose(ObserverContext<RegionCoprocessorEnvironment> c, boolean abortRequested) { }

    @Override
    public void preGetClosestRowBefore(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row,
        byte[] family, Result result) throws IOException { }

    @Override
    public void postGetClosestRowBefore(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row,
        byte[] family, Result result) throws IOException { }

    @Override
    public void preGetOp(ObserverContext<RegionCoprocessorEnvironment> c, Get get,
        List<Cell> result) throws IOException { }

    @Override
    public void postGetOp(ObserverContext<RegionCoprocessorEnvironment> c, Get get,
        List<Cell> result) throws IOException { }

    @Override
    public boolean preExists(ObserverContext<RegionCoprocessorEnvironment> c, Get get,
        boolean exists) throws IOException { return exists; }

    @Override
    public boolean postExists(ObserverContext<RegionCoprocessorEnvironment> c, Get get,
        boolean exists) throws IOException { return exists; }

    @Override
    public void postPut(ObserverContext<RegionCoprocessorEnvironment> c, Put put, WALEdit edit,
        Durability durability) throws IOException { }

    @Override
    public void prePrepareTimeStampForDeleteVersion(ObserverContext<RegionCoprocessorEnvironment> c,
        Mutation mutation, Cell cell, byte[] byteNow, Get get) throws IOException { }

    @Override
    public void postDelete(ObserverContext<RegionCoprocessorEnvironment> c, Delete delete,
        WALEdit edit, Durability durability) throws IOException { }

    @Override
    public void postStartRegionOperation(ObserverContext<RegionCoprocessorEnvironment> ctx,
        Operation operation) throws IOException { }

    @Override
    public void postCloseRegionOperation(ObserverContext<RegionCoprocessorEnvironment> ctx,
        Operation operation) throws IOException { }

    @Override
    public void postBatchMutateIndispensably(ObserverContext<RegionCoprocessorEnvironment> ctx,
        MiniBatchOperationInProgress<Mutation> miniBatchOp, boolean success) throws IOException { }

    @Override
    public boolean preCheckAndPut(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row,
        byte[] family, byte[] qualifier, CompareOp compareOp, ByteArrayComparable comparator,
        Put put, boolean result) throws IOException { return result; }

    @Override
    public boolean preCheckAndPutAfterRowLock(ObserverContext<RegionCoprocessorEnvironment> c,
        byte[] row, byte[] family, byte[] qualifier, CompareOp compareOp,
        ByteArrayComparable comparator, Put put, 
        boolean result) throws IOException { return result; }

    @Override
    public boolean postCheckAndPut(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row,
        byte[] family, byte[] qualifier, CompareOp compareOp, ByteArrayComparable comparator,
        Put put, boolean result) throws IOException { return result; }

    @Override
    public boolean preCheckAndDelete(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row,
        byte[] family, byte[] qualifier, CompareOp compareOp, ByteArrayComparable comparator,
        Delete delete, boolean result) throws IOException { return result; }

    @Override
    public boolean preCheckAndDeleteAfterRowLock(ObserverContext<RegionCoprocessorEnvironment> c,
        byte[] row, byte[] family, byte[] qualifier, CompareOp compareOp,
        ByteArrayComparable comparator, Delete delete,
        boolean result) throws IOException { return result; }

    @Override
    public boolean postCheckAndDelete(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row,
        byte[] family, byte[] qualifier, CompareOp compareOp, ByteArrayComparable comparator,
        Delete delete, boolean result) throws IOException { return result; }

    @Override
    public long preIncrementColumnValue(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row,
        byte[] family, byte[] qualifier, long amount, boolean writeToWAL)
        throws IOException { return amount; }

    @Override
    public long postIncrementColumnValue(ObserverContext<RegionCoprocessorEnvironment> c,
        byte[] row, byte[] family, byte[] qualifier, long amount, boolean writeToWAL, long result)
        throws IOException { return amount; }

    @Override
    public Result preAppendAfterRowLock(ObserverContext<RegionCoprocessorEnvironment> c,
        Append append) throws IOException { return null; }

    @Override
    public Result postAppend(ObserverContext<RegionCoprocessorEnvironment> c, Append append,
        Result result) throws IOException { return result; }

    @Override
    public Result preIncrementAfterRowLock(ObserverContext<RegionCoprocessorEnvironment> c,
        Increment increment) throws IOException { return null; }

    @Override
    public Result postIncrement(ObserverContext<RegionCoprocessorEnvironment> c,
        Increment increment, Result result) throws IOException { return result; }

    @Override
    public RegionScanner preScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Scan scan,
        RegionScanner s) throws IOException { return s; }

    @Override
    public KeyValueScanner preStoreScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c,
        Store store, Scan scan, NavigableSet<byte[]> targetCols, KeyValueScanner s)
        throws IOException { return s; }

    @Override
    public RegionScanner postScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Scan scan,
        RegionScanner s) throws IOException { return s; }

    @Override
    public boolean preScannerNext(ObserverContext<RegionCoprocessorEnvironment> c,
        InternalScanner s, List<Result> result, int limit, boolean hasNext)
        throws IOException { return hasNext; }

    @Override
    public boolean postScannerNext(ObserverContext<RegionCoprocessorEnvironment> c,
        InternalScanner s, List<Result> result, int limit, boolean hasNext)
        throws IOException { return hasNext; }

    @Override
    public boolean postScannerFilterRow(ObserverContext<RegionCoprocessorEnvironment> c,
        InternalScanner s, byte[] currentRow, int offset, short length, boolean hasMore)
        throws IOException { return hasMore; }

    @Override
    public void preScannerClose(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s)
        throws IOException { }

    @Override
    public void postScannerClose(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s)
        throws IOException { }

    @Override
    public void preWALRestore(ObserverContext<? extends RegionCoprocessorEnvironment> ctx,
        HRegionInfo info, WALKey logKey, WALEdit logEdit) throws IOException { }

    @Override
    public void preWALRestore(ObserverContext<RegionCoprocessorEnvironment> ctx, HRegionInfo info,
        HLogKey logKey, WALEdit logEdit) throws IOException { }

    @Override
    public void postWALRestore(ObserverContext<? extends RegionCoprocessorEnvironment> ctx,
        HRegionInfo info, WALKey logKey, WALEdit logEdit) throws IOException { }

    @Override
    public void postWALRestore(ObserverContext<RegionCoprocessorEnvironment> ctx, HRegionInfo info,
        HLogKey logKey, WALEdit logEdit) throws IOException { }

    @Override
    public void preBulkLoadHFile(ObserverContext<RegionCoprocessorEnvironment> ctx,
        List<Pair<byte[], String>> familyPaths) throws IOException { }

    @Override
    public void preCommitStoreFile(ObserverContext<RegionCoprocessorEnvironment> ctx, byte[] family,
        List<Pair<Path, Path>> pairs) throws IOException { }

    @Override
    public void postCommitStoreFile(ObserverContext<RegionCoprocessorEnvironment> ctx,
        byte[] family, Path srcPath, Path dstPath) throws IOException { }

    @Override
    public boolean postBulkLoadHFile(ObserverContext<RegionCoprocessorEnvironment> ctx,
        List<Pair<byte[], String>> familyPaths, boolean hasLoaded)
        throws IOException { return hasLoaded; }

    @Override
    public Reader preStoreFileReaderOpen(ObserverContext<RegionCoprocessorEnvironment> ctx,
        FileSystem fs, Path p, FSDataInputStreamWrapper in, long size, CacheConfig cacheConf,
        Reference r, Reader reader) throws IOException { return reader; }

    @Override
    public Reader postStoreFileReaderOpen(ObserverContext<RegionCoprocessorEnvironment> ctx,
        FileSystem fs, Path p, FSDataInputStreamWrapper in, long size, CacheConfig cacheConf,
        Reference r, Reader reader) throws IOException { return reader; }

    @Override
    public Cell postMutationBeforeWAL(ObserverContext<RegionCoprocessorEnvironment> ctx,
        MutationType opType, Mutation mutation, Cell oldCell, Cell newCell)
        throws IOException { return newCell; }

    @Override
    public DeleteTracker postInstantiateDeleteTracker(
        ObserverContext<RegionCoprocessorEnvironment> ctx, DeleteTracker delTracker)
        throws IOException { return delTracker; }

    @Override
    public void postWALAppend(ObserverContext<RegionCoprocessorEnvironment> ctx, WALKey key,
        WALEdit edit, long txid) throws IOException { }

  }

  ///////////////////////////////////////////////////////////////////////////////////////
  // TestReplicationEndpoint
  //

  public static class TestReplicationEndpoint extends VerifyWALEntriesReplicationEndpoint {
    @Override
    public boolean replicate(ReplicateContext replicateContext) {
      for (WAL.Entry entry : replicateContext.getEntries()) {
        // Look up the entry in the expected values map for this edit. Confirm the value
        // retrieved from the WALKey here has the expected value.
        WALKey key = entry.getKey();
        if (TABLE_NAME.equals(key.getTablename())) {
          byte[] row = entry.getEdit().getCells().get(0).getRow(); // just check the first cell
          verifyWALKeyAttribute(row, key);
        }
      }
      return super.replicate(replicateContext);
    }
  }

}
