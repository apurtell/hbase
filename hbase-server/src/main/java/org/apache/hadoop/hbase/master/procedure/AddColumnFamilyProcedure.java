/**
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.InvalidFamilyOperationException;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.TableState;
import org.apache.hadoop.hbase.master.MasterCoprocessorHost;
import org.apache.hadoop.hbase.procedure2.StateMachineProcedure;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.MasterProcedureProtos;
import org.apache.hadoop.hbase.protobuf.generated.MasterProcedureProtos.AddColumnFamilyState;
import org.apache.hadoop.hbase.security.User;

/**
 * The procedure to add a column family to an existing table.
 */
@InterfaceAudience.Private
public class AddColumnFamilyProcedure
    extends StateMachineProcedure<MasterProcedureEnv, AddColumnFamilyState>
    implements TableProcedureInterface {
  private static final Log LOG = LogFactory.getLog(AddColumnFamilyProcedure.class);

  private TableName tableName;
  private HTableDescriptor unmodifiedHTableDescriptor;
  private HColumnDescriptor cfDescriptor;
  private User user;

  private List<HRegionInfo> regionInfoList;
  private Boolean traceEnabled;

  // used for compatibility with old clients, until 2.0 the client had a sync behavior
  private final ProcedurePrepareLatch syncLatch;

  public AddColumnFamilyProcedure() {
    this.unmodifiedHTableDescriptor = null;
    this.regionInfoList = null;
    this.traceEnabled = null;
    this.syncLatch = null;
  }

  public AddColumnFamilyProcedure(final MasterProcedureEnv env, final TableName tableName,
      final HColumnDescriptor cfDescriptor) throws IOException {
    this(env, tableName, cfDescriptor, null);
  }

  public AddColumnFamilyProcedure(final MasterProcedureEnv env, final TableName tableName,
      final HColumnDescriptor cfDescriptor, final ProcedurePrepareLatch latch) {
    this.tableName = tableName;
    this.cfDescriptor = cfDescriptor;
    this.user = env.getRequestUser();
    this.setOwner(this.user.getShortName());
    this.unmodifiedHTableDescriptor = null;
    this.regionInfoList = null;
    this.traceEnabled = null;
    this.syncLatch = latch;
  }

  @Override
  protected Flow executeFromState(final MasterProcedureEnv env, final AddColumnFamilyState state)
      throws InterruptedException {
    if (isTraceEnabled()) {
      LOG.trace(this + " execute state=" + state);
    }

    try {
      switch (state) {
      case ADD_COLUMN_FAMILY_PREPARE:
        prepareAdd(env);
        setNextState(AddColumnFamilyState.ADD_COLUMN_FAMILY_PRE_OPERATION);
        break;
      case ADD_COLUMN_FAMILY_PRE_OPERATION:
        preAdd(env, state);
        setNextState(AddColumnFamilyState.ADD_COLUMN_FAMILY_UPDATE_TABLE_DESCRIPTOR);
        break;
      case ADD_COLUMN_FAMILY_UPDATE_TABLE_DESCRIPTOR:
        updateTableDescriptor(env);
        setNextState(AddColumnFamilyState.ADD_COLUMN_FAMILY_POST_OPERATION);
        break;
      case ADD_COLUMN_FAMILY_POST_OPERATION:
        postAdd(env, state);
        setNextState(AddColumnFamilyState.ADD_COLUMN_FAMILY_REOPEN_ALL_REGIONS);
        break;
      case ADD_COLUMN_FAMILY_REOPEN_ALL_REGIONS:
        reOpenAllRegionsIfTableIsOnline(env);
        return Flow.NO_MORE_STATE;
      default:
        throw new UnsupportedOperationException(this + " unhandled state=" + state);
      }
    } catch (IOException e) {
      if (isRollbackSupported(state)) {
        setFailure("master-add-columnfamily", e);
      } else {
        LOG.warn("Retriable error trying to add the column family " + getColumnFamilyName() +
          " to the table " + tableName + " (in state=" + state + ")", e);
      }
    }
    return Flow.HAS_MORE_STATE;
  }

  @Override
  protected void rollbackState(final MasterProcedureEnv env, final AddColumnFamilyState state)
      throws IOException {
    if (state == AddColumnFamilyState.ADD_COLUMN_FAMILY_PREPARE ||
        state == AddColumnFamilyState.ADD_COLUMN_FAMILY_PRE_OPERATION) {
      // nothing to rollback, pre is just table-state checks.
      // We can fail if the table does not exist or is not disabled.
      // TODO: coprocessor rollback semantic is still undefined.
      return;
    }

    // The procedure doesn't have a rollback. The execution will succeed, at some point.
    throw new UnsupportedOperationException("unhandled state=" + state);
  }

  @Override
  protected boolean isRollbackSupported(final AddColumnFamilyState state) {
    switch (state) {
      case ADD_COLUMN_FAMILY_PREPARE:
      case ADD_COLUMN_FAMILY_PRE_OPERATION:
        return true;
      default:
        return false;
    }
  }

  @Override
  protected void completionCleanup(final MasterProcedureEnv env) {
    ProcedurePrepareLatch.releaseLatch(syncLatch, this);
  }

  @Override
  protected AddColumnFamilyState getState(final int stateId) {
    return AddColumnFamilyState.valueOf(stateId);
  }

  @Override
  protected int getStateId(final AddColumnFamilyState state) {
    return state.getNumber();
  }

  @Override
  protected AddColumnFamilyState getInitialState() {
    return AddColumnFamilyState.ADD_COLUMN_FAMILY_PREPARE;
  }

  @Override
  protected boolean acquireLock(final MasterProcedureEnv env) {
    if (env.waitInitialized(this)) return false;
    return env.getProcedureQueue().tryAcquireTableExclusiveLock(this, tableName);
  }

  @Override
  protected void releaseLock(final MasterProcedureEnv env) {
    env.getProcedureQueue().releaseTableExclusiveLock(this, tableName);
  }

  @Override
  public void serializeStateData(final OutputStream stream) throws IOException {
    super.serializeStateData(stream);

    MasterProcedureProtos.AddColumnFamilyStateData.Builder addCFMsg =
        MasterProcedureProtos.AddColumnFamilyStateData.newBuilder()
            .setUserInfo(MasterProcedureUtil.toProtoUserInfo(user))
            .setTableName(ProtobufUtil.toProtoTableName(tableName))
            .setColumnfamilySchema(ProtobufUtil.convertToColumnFamilySchema(cfDescriptor));
    if (unmodifiedHTableDescriptor != null) {
      addCFMsg
          .setUnmodifiedTableSchema(ProtobufUtil.convertToTableSchema(unmodifiedHTableDescriptor));
    }

    addCFMsg.build().writeDelimitedTo(stream);
  }

  @Override
  public void deserializeStateData(final InputStream stream) throws IOException {
    super.deserializeStateData(stream);

    MasterProcedureProtos.AddColumnFamilyStateData addCFMsg =
        MasterProcedureProtos.AddColumnFamilyStateData.parseDelimitedFrom(stream);
    user = MasterProcedureUtil.toUserInfo(addCFMsg.getUserInfo());
    tableName = ProtobufUtil.toTableName(addCFMsg.getTableName());
    cfDescriptor = ProtobufUtil.convertToHColumnDesc(addCFMsg.getColumnfamilySchema());
    if (addCFMsg.hasUnmodifiedTableSchema()) {
      unmodifiedHTableDescriptor = ProtobufUtil.convertToHTableDesc(addCFMsg.getUnmodifiedTableSchema());
    }
  }

  @Override
  public void toStringClassDetails(StringBuilder sb) {
    sb.append(getClass().getSimpleName());
    sb.append(" (table=");
    sb.append(tableName);
    sb.append(", columnfamily=");
    if (cfDescriptor != null) {
      sb.append(getColumnFamilyName());
    } else {
      sb.append("Unknown");
    }
    sb.append(")");
  }

  @Override
  public TableName getTableName() {
    return tableName;
  }

  @Override
  public TableOperationType getTableOperationType() {
    return TableOperationType.EDIT;
  }

  /**
   * Action before any real action of adding column family.
   * @param env MasterProcedureEnv
   * @throws IOException
   */
  private void prepareAdd(final MasterProcedureEnv env) throws IOException {
    // Checks whether the table is allowed to be modified.
    MasterDDLOperationHelper.checkTableModifiable(env, tableName);

    // In order to update the descriptor, we need to retrieve the old descriptor for comparison.
    unmodifiedHTableDescriptor = env.getMasterServices().getTableDescriptors().get(tableName);
    if (unmodifiedHTableDescriptor == null) {
      throw new IOException("HTableDescriptor missing for " + tableName);
    }
    if (unmodifiedHTableDescriptor.hasFamily(cfDescriptor.getName())) {
      throw new InvalidFamilyOperationException("Column family '" + getColumnFamilyName()
          + "' in table '" + tableName + "' already exists so cannot be added");
    }
  }

  /**
   * Action before adding column family.
   * @param env MasterProcedureEnv
   * @param state the procedure state
   * @throws IOException
   * @throws InterruptedException
   */
  private void preAdd(final MasterProcedureEnv env, final AddColumnFamilyState state)
      throws IOException, InterruptedException {
    runCoprocessorAction(env, state);
  }

  /**
   * Add the column family to the file system
   */
  private void updateTableDescriptor(final MasterProcedureEnv env) throws IOException {
    // Update table descriptor
    LOG.info("AddColumn. Table = " + tableName + " HCD = " + cfDescriptor.toString());

    HTableDescriptor htd = env.getMasterServices().getTableDescriptors().get(tableName);

    if (htd.hasFamily(cfDescriptor.getName())) {
      // It is possible to reach this situation, as we could already add the column family
      // to table descriptor, but the master failover happens before we complete this state.
      // We should be able to handle running this function multiple times without causing problem.
      return;
    }

    htd.addFamily(cfDescriptor);
    env.getMasterServices().getTableDescriptors().add(htd);
  }

  /**
   * Restore the table descriptor back to pre-add
   * @param env MasterProcedureEnv
   * @throws IOException
   **/
  private void restoreTableDescriptor(final MasterProcedureEnv env) throws IOException {
    HTableDescriptor htd = env.getMasterServices().getTableDescriptors().get(tableName);
    if (htd.hasFamily(cfDescriptor.getName())) {
      // Remove the column family from file system and update the table descriptor to
      // the before-add-column-family-state
      MasterDDLOperationHelper.deleteColumnFamilyFromFileSystem(env, tableName,
        getRegionInfoList(env), cfDescriptor.getName(), cfDescriptor.isMobEnabled());

      env.getMasterServices().getTableDescriptors().add(unmodifiedHTableDescriptor);

      // Make sure regions are opened after table descriptor is updated.
      reOpenAllRegionsIfTableIsOnline(env);
    }
  }

  /**
   * Action after adding column family.
   * @param env MasterProcedureEnv
   * @param state the procedure state
   * @throws IOException
   * @throws InterruptedException
   */
  private void postAdd(final MasterProcedureEnv env, final AddColumnFamilyState state)
      throws IOException, InterruptedException {
    runCoprocessorAction(env, state);
  }

  /**
   * Last action from the procedure - executed when online schema change is supported.
   * @param env MasterProcedureEnv
   * @throws IOException
   */
  private void reOpenAllRegionsIfTableIsOnline(final MasterProcedureEnv env) throws IOException {
    // This operation only run when the table is enabled.
    if (!env.getMasterServices().getTableStateManager()
        .isTableState(getTableName(), TableState.State.ENABLED)) {
      return;
    }

    if (MasterDDLOperationHelper.reOpenAllRegions(env, getTableName(), getRegionInfoList(env))) {
      LOG.info("Completed add column family operation on table " + getTableName());
    } else {
      LOG.warn("Error on reopening the regions on table " + getTableName());
    }
  }

  /**
   * The procedure could be restarted from a different machine. If the variable is null, we need to
   * retrieve it.
   * @return traceEnabled
   */
  private Boolean isTraceEnabled() {
    if (traceEnabled == null) {
      traceEnabled = LOG.isTraceEnabled();
    }
    return traceEnabled;
  }

  private String getColumnFamilyName() {
    return cfDescriptor.getNameAsString();
  }

  /**
   * Coprocessor Action.
   * @param env MasterProcedureEnv
   * @param state the procedure state
   * @throws IOException
   * @throws InterruptedException
   */
  private void runCoprocessorAction(final MasterProcedureEnv env, final AddColumnFamilyState state)
      throws IOException, InterruptedException {
    final MasterCoprocessorHost cpHost = env.getMasterCoprocessorHost();
    if (cpHost != null) {
      switch (state) {
        case ADD_COLUMN_FAMILY_PRE_OPERATION:
          cpHost.preAddColumnFamilyAction(tableName, cfDescriptor, user);
          break;
        case ADD_COLUMN_FAMILY_POST_OPERATION:
          cpHost.postCompletedAddColumnFamilyAction(tableName, cfDescriptor, user);
          break;
        default:
          throw new UnsupportedOperationException(this + " unhandled state=" + state);
      }
    }
  }

  private List<HRegionInfo> getRegionInfoList(final MasterProcedureEnv env) throws IOException {
    if (regionInfoList == null) {
      regionInfoList = ProcedureSyncWait.getRegionsFromMeta(env, getTableName());
    }
    return regionInfoList;
  }
}
