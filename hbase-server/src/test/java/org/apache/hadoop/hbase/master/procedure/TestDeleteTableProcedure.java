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
package org.apache.hadoop.hbase.master.procedure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableNotDisabledException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.SnapshotDescription;
import org.apache.hadoop.hbase.procedure2.Procedure;
import org.apache.hadoop.hbase.procedure2.ProcedureExecutor;
import org.apache.hadoop.hbase.procedure2.ProcedureSuspendedException;
import org.apache.hadoop.hbase.procedure2.ProcedureTestingUtility;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProcedureProtos.DeleteTableState;

@Category({ MasterTests.class, MediumTests.class })
public class TestDeleteTableProcedure extends TestTableDDLProcedureBase {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestDeleteTableProcedure.class);

  private static final Logger LOG = LoggerFactory.getLogger(TestDeleteTableProcedure.class);
  @Rule
  public TestName name = new TestName();

  @Test(expected = TableNotFoundException.class)
  public void testDeleteNotExistentTable() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());

    final ProcedureExecutor<MasterProcedureEnv> procExec = getMasterProcedureExecutor();
    ProcedurePrepareLatch latch = new ProcedurePrepareLatch.CompatibilityLatch();
    long procId = ProcedureTestingUtility.submitAndWait(procExec,
      new DeleteTableProcedure(procExec.getEnvironment(), tableName, latch));
    latch.await();
  }

  @Test(expected = TableNotDisabledException.class)
  public void testDeleteNotDisabledTable() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());

    final ProcedureExecutor<MasterProcedureEnv> procExec = getMasterProcedureExecutor();
    MasterProcedureTestingUtility.createTable(procExec, tableName, null, "f");

    ProcedurePrepareLatch latch = new ProcedurePrepareLatch.CompatibilityLatch();
    long procId = ProcedureTestingUtility.submitAndWait(procExec,
      new DeleteTableProcedure(procExec.getEnvironment(), tableName, latch));
    latch.await();
  }

  @Test
  public void testDeleteDeletedTable() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    final ProcedureExecutor<MasterProcedureEnv> procExec = getMasterProcedureExecutor();

    RegionInfo[] regions =
      MasterProcedureTestingUtility.createTable(procExec, tableName, null, "f");
    UTIL.getAdmin().disableTable(tableName);

    // delete the table (that exists)
    long procId1 =
      procExec.submitProcedure(new DeleteTableProcedure(procExec.getEnvironment(), tableName));
    // delete the table (that will no longer exist)
    long procId2 =
      procExec.submitProcedure(new DeleteTableProcedure(procExec.getEnvironment(), tableName));

    // Wait the completion
    ProcedureTestingUtility.waitProcedure(procExec, procId1);
    ProcedureTestingUtility.waitProcedure(procExec, procId2);

    // First delete should succeed
    ProcedureTestingUtility.assertProcNotFailed(procExec, procId1);
    MasterProcedureTestingUtility.validateTableDeletion(getMaster(), tableName);

    // Second delete should fail with TableNotFound
    Procedure<?> result = procExec.getResult(procId2);
    assertTrue(result.isFailed());
    LOG.debug("Delete failed with exception: " + result.getException());
    assertTrue(ProcedureTestingUtility.getExceptionCause(result) instanceof TableNotFoundException);
  }

  @Test
  public void testSimpleDelete() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    final byte[][] splitKeys = null;
    testSimpleDelete(tableName, splitKeys);
  }

  @Test
  public void testSimpleDeleteWithSplits() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    final byte[][] splitKeys =
      new byte[][] { Bytes.toBytes("a"), Bytes.toBytes("b"), Bytes.toBytes("c") };
    testSimpleDelete(tableName, splitKeys);
  }

  @Test
  public void testDeleteFromMeta() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    RegionInfo[] regions = MasterProcedureTestingUtility.createTable(getMasterProcedureExecutor(),
      tableName, null, "f1", "f2");
    List<RegionInfo> regionsList = new ArrayList<>();
    UTIL.getAdmin().disableTable(tableName);
    MasterProcedureEnv procedureEnv = getMasterProcedureExecutor().getEnvironment();
    assertNotNull("Table should be on TableDescriptors cache.",
      procedureEnv.getMasterServices().getTableDescriptors().get(tableName));
    DeleteTableProcedure.deleteFromMeta(procedureEnv, tableName, regionsList);
    assertNull("Table shouldn't be on TableDescriptors anymore.",
      procedureEnv.getMasterServices().getTableDescriptors().get(tableName));
  }

  private void testSimpleDelete(final TableName tableName, byte[][] splitKeys) throws Exception {
    RegionInfo[] regions = MasterProcedureTestingUtility.createTable(getMasterProcedureExecutor(),
      tableName, splitKeys, "f1", "f2");
    UTIL.getAdmin().disableTable(tableName);

    // delete the table
    final ProcedureExecutor<MasterProcedureEnv> procExec = getMasterProcedureExecutor();
    long procId = ProcedureTestingUtility.submitAndWait(procExec,
      new DeleteTableProcedure(procExec.getEnvironment(), tableName));
    ProcedureTestingUtility.assertProcNotFailed(procExec, procId);
    MasterProcedureTestingUtility.validateTableDeletion(getMaster(), tableName);
  }

  @Test
  public void testRecoveryAndDoubleExecution() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());

    // create the table
    byte[][] splitKeys = null;
    RegionInfo[] regions = MasterProcedureTestingUtility.createTable(getMasterProcedureExecutor(),
      tableName, splitKeys, "f1", "f2");
    UTIL.getAdmin().disableTable(tableName);

    final ProcedureExecutor<MasterProcedureEnv> procExec = getMasterProcedureExecutor();
    ProcedureTestingUtility.waitNoProcedureRunning(procExec);
    ProcedureTestingUtility.setKillAndToggleBeforeStoreUpdate(procExec, true);

    // Start the Delete procedure && kill the executor
    long procId =
      procExec.submitProcedure(new DeleteTableProcedure(procExec.getEnvironment(), tableName));

    // Restart the executor and execute the step twice
    MasterProcedureTestingUtility.testRecoveryAndDoubleExecution(procExec, procId);

    MasterProcedureTestingUtility.validateTableDeletion(getMaster(), tableName);
  }

  @Test
  public void testRecoverySnapshotRollback() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    final String[] families = new String[] { "f1", "f2" };

    // Enable recovery snapshots
    UTIL.getConfiguration().setBoolean(HConstants.SNAPSHOT_BEFORE_DELETE_ENABLED_KEY, true);

    try {
      final ProcedureExecutor<MasterProcedureEnv> procExec = getMasterProcedureExecutor();

      // Create table with data
      MasterProcedureTestingUtility.createTable(procExec, tableName, null, families);
      MasterProcedureTestingUtility.loadData(UTIL.getConnection(), tableName, 100, null, families);
      UTIL.getAdmin().disableTable(tableName);

      // Submit the failing procedure
      long procId = procExec
        .submitProcedure(new FailingDeleteTableProcedure(procExec.getEnvironment(), tableName));

      // Wait for procedure to complete (should fail)
      ProcedureTestingUtility.waitProcedure(procExec, procId);
      Procedure<MasterProcedureEnv> result = procExec.getResult(procId);
      assertTrue("Procedure should have failed", result.isFailed());

      // Verify no recovery snapshots remain after rollback
      boolean snapshotFound = false;
      for (SnapshotDescription snapshot : UTIL.getAdmin().listSnapshots()) {
        if (snapshot.getName().startsWith("auto_" + tableName.getNameAsString())) {
          snapshotFound = true;
          break;
        }
      }
      assertTrue("Recovery snapshot should have been cleaned up during rollback", !snapshotFound);
    } finally {
      // Clean up - disable recovery snapshots
      UTIL.getConfiguration().setBoolean(HConstants.SNAPSHOT_BEFORE_DELETE_ENABLED_KEY, false);
    }
  }

  @Test
  public void testRecoverySnapshotAndRestore() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    final TableName restoredTableName = TableName.valueOf(name.getMethodName() + "_restored");
    final String[] families = new String[] { "f1", "f2" };

    // Enable recovery snapshots
    UTIL.getConfiguration().setBoolean(HConstants.SNAPSHOT_BEFORE_DELETE_ENABLED_KEY, true);

    try {
      final ProcedureExecutor<MasterProcedureEnv> procExec = getMasterProcedureExecutor();

      // Create table with data
      MasterProcedureTestingUtility.createTable(procExec, tableName, null, families);
      MasterProcedureTestingUtility.loadData(UTIL.getConnection(), tableName, 100, null, families);
      UTIL.getAdmin().disableTable(tableName);

      // Delete the table (this should create a recovery snapshot)
      long procId = ProcedureTestingUtility.submitAndWait(procExec,
        new DeleteTableProcedure(procExec.getEnvironment(), tableName));
      ProcedureTestingUtility.assertProcNotFailed(procExec, procId);

      // Verify table is deleted
      MasterProcedureTestingUtility.validateTableDeletion(getMaster(), tableName);

      // Find the recovery snapshot
      String recoverySnapshotName = null;
      for (SnapshotDescription snapshot : UTIL.getAdmin().listSnapshots()) {
        if (snapshot.getName().startsWith("auto_" + tableName.getNameAsString())) {
          recoverySnapshotName = snapshot.getName();
          break;
        }
      }
      assertTrue("Recovery snapshot should exist", recoverySnapshotName != null);

      // Restore from snapshot by cloning to a new table
      UTIL.getAdmin().cloneSnapshot(recoverySnapshotName, restoredTableName);
      UTIL.waitUntilAllRegionsAssigned(restoredTableName);

      // Verify restored table has original data
      assertEquals(100, UTIL.countRows(restoredTableName));

      // Clean up the cloned table
      UTIL.getAdmin().disableTable(restoredTableName);
      UTIL.getAdmin().deleteTable(restoredTableName);
    } finally {
      // Clean up - disable recovery snapshots
      UTIL.getConfiguration().setBoolean(HConstants.SNAPSHOT_BEFORE_DELETE_ENABLED_KEY, false);
    }
  }

  // Create a procedure that will fail after snapshot creation
  static class FailingDeleteTableProcedure extends DeleteTableProcedure {
    private boolean failOnce = false;

    public FailingDeleteTableProcedure() {
      super();
    }

    public FailingDeleteTableProcedure(MasterProcedureEnv env, TableName tableName) {
      super(env, tableName);
    }

    @Override
    protected Flow executeFromState(MasterProcedureEnv env, DeleteTableState state)
      throws InterruptedException, ProcedureSuspendedException {
      if (!failOnce && state == DeleteTableState.DELETE_TABLE_CLEAR_FS_LAYOUT) {
        failOnce = true;
        throw new RuntimeException("Simulated failure");
      }
      return super.executeFromState(env, state);
    }
  }
}
