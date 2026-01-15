# Task 5: BulkRecoveryProcedure (BRP) - Detailed Design Document

## 1. Overview

This document provides the detailed design for implementing `BulkRecoveryProcedure` (BRP), the orchestration procedure for HBase cold boot recovery. BRP coordinates the entire recovery process by ensuring that all WAL splitting work (via `ServerCrashProcedure`) completes before any region assignment work (via `BulkAssignProcedure`) begins.

BRP implements the HBase 1 cold start assignment pattern within HBase 2+'s Proc-V2 framework, providing a safe and efficient mechanism for rapid cluster recovery after a catastrophic failure.

### 1.1 Goals

1. Orchestrate cold boot recovery as a single root-level procedure
2. Execute all ServerCrashProcedures in parallel for WAL splitting
3. Execute all BulkAssignProcedures after WAL splitting completes
4. Prioritize system tables over user tables during assignment
5. Provide observability via MonitoredTask and JMX metrics
6. Support procedure recovery across Master restarts

### 1.2 Non-Goals

- Implementing the `HBaseColdStartTool` (Task 6)
- Modifying the core `ServerCrashProcedure` implementation
- Automatic recovery of quarantined WALs
- Providing bypass mechanisms that allow assignment before WAL splitting completes

## 2. Background

### 2.1 The Coordination Problem

During cold boot recovery, we must ensure a strict ordering:

1. **Phase 1 - WAL Splitting:** All WALs for crashed servers must be split and recovered edits written to region directories
2. **Phase 2 - Region Assignment:** Only after Phase 1 completes can regions be safely assigned and opened

If a region is opened before its recovered edits file is written, data loss will occur. The recovered edits would be ignored, and writes from the previous incarnation of the region would be lost.

### 2.2 Why a Separate Procedure?

In normal operation, `ServerCrashProcedure` handles both WAL splitting and region assignment for a single crashed server. However, in the cold boot scenario:

1. The HBaseColdStartTool resets all region states to OFFLINE
2. Region-to-server assignment information is deliberately discarded (as it's likely stale/incorrect after a catastrophic failure)
3. SCPs cannot determine which regions were on their crashed server
4. Assignment must be driven separately via `BulkAssignProcedure`

BRP bridges this gap by orchestrating SCPs (for WAL splitting only) and BAPs (for assignment) in two strictly ordered phases.

This design **requires** and **assumes** that some entity has already cleared region location columns and reset region states to OFFLINE in hbase:meta before BRP runs. When the Master starts, RegionStates is rebuilt from META with no regions associated to any ServerStateNode. As a result, ServerCrashProcedure.getRegionsOnCrashedServer() returns empty for all servers, and SCPs proceed through WAL splitting without attempting region assignment.

### 2.3 Dependencies

- **Task 3 (BulkAssignProcedure):** BRP creates and manages BAP instances for region assignment. BAP uses a SHARED table lock via `TableOperationType.REGION_ASSIGN` (see Task 3 design, Section "Table Locking").
- **Task 4 (WAL Split Quarantine):** Ensures SCPs eventually complete even with problematic WALs. Task 4 must provide the HBCK2 `quarantine_wal` command (both `quarantineWALByProcId()` and `quarantineWALByPath()` methods) for manual intervention when automatic quarantine is disabled or insufficient.
- **Task 6 (HBaseColdStartTool):** Must properly clear region-to-server mappings in META so SCPs skip region assignment (see Section 2.2)

## 3. Detailed Design

### 3.1 Class Hierarchy

```
StateMachineProcedure<MasterProcedureEnv, BulkRecoveryState>
    └── BulkRecoveryProcedure
```

BRP extends `StateMachineProcedure` directly (not `AbstractStateMachineTableProcedure`) since it operates at the cluster level, not on a specific table.

### 3.2 State Machine

```
BULK_RECOVERY_PREPARE
       │
       ▼
BULK_RECOVERY_SPLIT_WALS ──────────────────┐
       │                                   │
       │ (wait for all SCPs to complete)   │
       ▼                                   │
BULK_RECOVERY_ASSIGN_SYSTEM_TABLES ────────┤
       │                                   │
       │ (wait for system table BAPs)      │
       ▼                                   │
BULK_RECOVERY_ASSIGN_USER_TABLES ──────────┤
       │                                   │
       │ (wait for user table BAPs)        │
       ▼                                   │
BULK_RECOVERY_POST_OPERATION               │
       │                                   │
       ▼                                   │
   COMPLETE ◄──────────────────────────────┘
                (failure path)
```

#### 3.2.1 State Descriptions

**1. BULK_RECOVERY_PREPARE**
- Log procedure start with summary of work to be done
- Validate inputs (server names and table names)
- Check if WAL quarantine is enabled; if not, log a prominent warning
- Initialize tracking structures
- Initialize MonitoredTask for progress tracking (idempotent: uses null-check)
- Note: Child procedure instances (SCPs, BAPs) are NOT created here. They are created on-demand in the states where they are submitted, ensuring recovery correctness.
- Note: Table partitioning into system/user tables was already done by the constructor
- Transition to `BULK_RECOVERY_SPLIT_WALS`

**2. BULK_RECOVERY_SPLIT_WALS**
- Check if SCPs have already been submitted (via persisted `scpsSubmitted` flag)
- If not submitted: create and submit all SCPs as child procedures via `addChildProcedure()`, set flag, and return
- The procedure framework automatically suspends BRP until all child SCPs complete (via the `childrenLatch` mechanism)
- When all children complete, the framework resumes BRP in the same state
- On re-entry (children done): transition to next state (failures handled by framework; see section 3.9)
- Track progress via MonitoredTask updates
- On completion: record `walSplitCompleteTimeMs`, transition to `BULK_RECOVERY_ASSIGN_SYSTEM_TABLES`
- On failure (any SCP fails with non-quarantine error): fail the entire BRP

The code snippets below use `hasChildren()` and `isChildFailed()` as pseudo-code representing the intended semantics. The actual implementation will use the HBase procedure framework's child tracking mechanisms:
- `hasChildren()` → check `getChildrenLatch() > 0` (from `Procedure.java`)
- When `addChildProcedure()` returns child procedures, the parent is automatically suspended until children complete
- Child failure is propagated automatically by the framework (see section 3.9)

```java
case BULK_RECOVERY_SPLIT_WALS:
  if (!scpsSubmitted) {
    // First entry: create and submit child SCPs
    addChildProcedure(createServerCrashProcedures(env));
    scpsSubmitted = true;
    if (crashedServers.isEmpty()) {
      // No children added; proceed immediately to next state
      walSplitCompleteTimeMs = EnvironmentEdgeManager.currentTime();
      setNextState(BulkRecoveryState.BULK_RECOVERY_ASSIGN_SYSTEM_TABLES);
      return Flow.HAS_MORE_STATE;
    }
    // Framework will suspend this procedure until children complete.
    // Flag is persisted; on recovery, we skip re-submission.
    return Flow.HAS_MORE_STATE;
  }
  // Re-entry: if we reach here, ALL children completed SUCCESSFULLY.
  // If any child failed, the framework would have set BRP to FAILED state
  // and we would never execute this code. See section 3.9 for details.
  walSplitCompleteTimeMs = EnvironmentEdgeManager.currentTime();
  setNextState(BulkRecoveryState.BULK_RECOVERY_ASSIGN_SYSTEM_TABLES);
  return Flow.HAS_MORE_STATE;
```

**3. BULK_RECOVERY_ASSIGN_SYSTEM_TABLES**
- Check if system BAPs have already been submitted (via persisted `systemBapsSubmitted` flag)
- If not submitted: create and submit BAPs for system tables as child procedures, set flag, and return
- The procedure framework automatically suspends BRP until all child BAPs complete
- On re-entry (children done): all children completed successfully (failures handled by framework); transition to next state
- Track progress via MonitoredTask
- Transition to `BULK_RECOVERY_ASSIGN_USER_TABLES`
- If `systemTables` is empty, `addChildProcedure()` receives an empty array, no children are added, and the state immediately transitions to the next state

```java
case BULK_RECOVERY_ASSIGN_SYSTEM_TABLES:
  if (!systemBapsSubmitted) {
    addChildProcedure(createBulkAssignProcedures(systemTables));
    systemBapsSubmitted = true;
    if (systemTables.isEmpty()) {
      // No children added; proceed immediately to next state
      setNextState(BulkRecoveryState.BULK_RECOVERY_ASSIGN_USER_TABLES);
      return Flow.HAS_MORE_STATE;
    }
    return Flow.HAS_MORE_STATE;  // Framework suspends; waits for children
  }
  // Re-entry: if we reach here, ALL children completed SUCCESSFULLY.
  // If any child failed, the framework would have set BRP to FAILED state.
  setNextState(BulkRecoveryState.BULK_RECOVERY_ASSIGN_USER_TABLES);
  return Flow.HAS_MORE_STATE;
```

**4. BULK_RECOVERY_ASSIGN_USER_TABLES**
- Check if user BAPs have already been submitted (via persisted `userBapsSubmitted` flag)
- If not submitted: create and submit BAPs for user tables as child procedures, set flag, and return
- The procedure framework automatically suspends BRP until all child BAPs complete
- On re-entry (children done): all children completed successfully (failures handled by framework); transition to next state
- Track progress via MonitoredTask
- Transition to `BULK_RECOVERY_POST_OPERATION`
- If `userTables` is empty, `addChildProcedure()` receives an empty array, no children are added, and the state immediately transitions to the next state

```java
case BULK_RECOVERY_ASSIGN_USER_TABLES:
  if (!userBapsSubmitted) {
    addChildProcedure(createBulkAssignProcedures(userTables));
    userBapsSubmitted = true;
    if (userTables.isEmpty()) {
      // No children added; proceed immediately to next state
      setNextState(BulkRecoveryState.BULK_RECOVERY_POST_OPERATION);
      return Flow.HAS_MORE_STATE;
    }
    return Flow.HAS_MORE_STATE;  // Framework suspends; waits for children
  }
  // Re-entry: if we reach here, ALL children completed SUCCESSFULLY.
  // If any child failed, the framework would have set BRP to FAILED state.
  setNextState(BulkRecoveryState.BULK_RECOVERY_POST_OPERATION);
  return Flow.HAS_MORE_STATE;
```

**5. BULK_RECOVERY_POST_OPERATION**
- Log completion summary (SCPs processed, BAPs processed, duration, quarantined WAL count)
- Mark MonitoredTask as complete
- Update JMX metrics
- Return `Flow.NO_MORE_STATE` to complete the procedure successfully

```java
case BULK_RECOVERY_POST_OPERATION:
  long totalTimeMs = EnvironmentEdgeManager.currentTime() - startTimeMs;
  long assignTimeMs = totalTimeMs - (walSplitCompleteTimeMs - startTimeMs);
  
  LOG.info("BulkRecoveryProcedure completed: {} servers processed, {} system tables, "
      + "{} user tables, WAL split time={}ms, assign time={}ms, total time={}ms",
      crashedServers.size(), systemTables.size(), userTables.size(),
      walSplitCompleteTimeMs - startTimeMs, assignTimeMs, totalTimeMs);
  
  // Record JMX metrics
  env.getMasterServices().getMasterMetrics().recordBulkRecoveryMetrics(
      walSplitCompleteTimeMs - startTimeMs, assignTimeMs, totalTimeMs);
  
  // Mark MonitoredTask complete
  markComplete("Recovery complete: " + crashedServers.size() + " servers, " 
      + (systemTables.size() + userTables.size()) + " tables");
  
  return Flow.NO_MORE_STATE;  // Procedure completes successfully
```

#### 3.2.2 Recovery Behavior

BRP is designed for resilient recovery after Master restarts. Key design decisions:

1. **Ephemeral Progress State:** Progress counters and timing are not persisted. On recovery, they reset and are recalculated from current conditions.

2. **Child Procedure Management:** The procedure framework handles child procedure persistence. When BRP resumes after a restart:
   - Already-completed child SCPs/BAPs are not re-executed
   - In-progress children resume from their persisted state
   - The framework tracks which children have completed

3. **Persisted Submission Flags:** BRP uses persisted boolean flags (`scpsSubmitted`, `systemBapsSubmitted`, `userBapsSubmitted`) to track which child procedure batches have been submitted. This is required because:
   - The `hasChildren()` method returns true for ALL child procedures (from any state), not just the current state's children
   - Without these flags, transitioning from `SPLIT_WALS` to `ASSIGN_SYSTEM_TABLES` would incorrectly skip BAP submission because completed SCPs still count as children
   - Each flag is set immediately after `addChildProcedure()` and persisted to the procedure store

4. **Idempotent State Transitions:** Each state is designed for safe re-entry after Master restart:
   - `PREPARE`: Re-creates any needed structures; safe to re-execute
   - `SPLIT_WALS`, `ASSIGN_*`: Uses persisted submission flags before calling `addChildProcedure()`. If flag is true (persisted from before restart), skips submission. The framework automatically waits for children to complete via the `childrenLatch` mechanism.
   - `POST_OPERATION`: Safe to re-log and update metrics; returns `Flow.NO_MORE_STATE` to complete

5. **No Stale State Reconciliation:** Rather than persisting progress and trying to reconcile it with potentially different cluster state after restart, BRP relies on the procedure framework's child tracking and queries current cluster state (via RegionStates, etc.) as needed.

### 3.3 Protobuf Definition

Add to `MasterProcedure.proto`:

```protobuf
enum BulkRecoveryState {
  BULK_RECOVERY_PREPARE = 1;
  BULK_RECOVERY_SPLIT_WALS = 2;
  BULK_RECOVERY_ASSIGN_SYSTEM_TABLES = 3;
  BULK_RECOVERY_ASSIGN_USER_TABLES = 4;
  BULK_RECOVERY_POST_OPERATION = 5;
}

message BulkRecoveryStateData {
  // Crashed servers that need WAL splitting
  repeated ServerName crashed_servers = 1;
  
  // Tables that need region assignment (system tables)
  repeated TableName system_tables = 2;
  
  // Tables that need region assignment (user tables)
  repeated TableName user_tables = 3;
  
  // Persisted flags to track which child procedure batches have been submitted.
  // Required for idempotent re-entry after Master restart.
  optional bool scps_submitted = 4 [default = false];
  optional bool system_baps_submitted = 5 [default = false];
  optional bool user_baps_submitted = 6 [default = false];
}
```

### 3.4 Locking Strategy

BRP does not acquire any locks at the procedure level. This is by design:

- Child SCPs acquire server exclusive locks via `ServerProcedureInterface`
- Child BAPs acquire shared table locks via `TableProcedureInterface`
- BRP's role is pure orchestration/coordination

This allows maximum parallelism during recovery while still respecting the necessary ordering constraints.

### 3.5 Constructors

#### Primary Constructor (for HBaseColdStartTool)

```java
public BulkRecoveryProcedure(List<ServerName> crashedServers, List<TableName> tablesToAssign)
```

When constructed:
- `crashedServers`: List of server names with WALs that need splitting
- `tablesToAssign`: List of all table names that need region assignment

The constructor:
1. Stores the server names for SCP creation (stored in `this.crashedServers`)
2. Partitions `tablesToAssign` into system tables and user tables using `TableName.isSystemTable()`. This partitioning happens in the constructor (not in PREPARE) because the partitioned lists are serialized separately to the procedure store.
3. Does NOT create child procedure instances yet (deferred to submission states—child procedure instances are transient and created on-demand in `SPLIT_WALS` and `ASSIGN_*` states)

If `crashedServers` is empty: No SCPs will be created in `SPLIT_WALS`; the state transitions immediately to `ASSIGN_SYSTEM_TABLES` after setting the `scpsSubmitted` flag

If `systemTables` is empty after partitioning: No BAPs for system tables; `ASSIGN_SYSTEM_TABLES` transitions immediately to `ASSIGN_USER_TABLES`

If `userTables` is empty after partitioning: No BAPs for user tables; `ASSIGN_USER_TABLES` transitions immediately to `POST_OPERATION`

If all lists are empty: The procedure completes quickly, performing only logging and metrics updates

#### Deserialization Constructor

```java
public BulkRecoveryProcedure()  // Required for procedure framework
```

### 3.6 SCP Construction

In `BULK_RECOVERY_SPLIT_WALS`, SCPs are created on-demand for each crashed server (only if `scpsSubmitted` is false):

```java
private ServerCrashProcedure[] createServerCrashProcedures(MasterProcedureEnv env) {
  List<ServerCrashProcedure> scps = new ArrayList<>();
  
  for (ServerName serverName : crashedServers) {
    // Check if server is carrying meta (based on WAL file names)
    boolean carryingMeta = checkIfCarryingMeta(env, serverName);
    
    // shouldSplitWal is always true for cold boot recovery
    ServerCrashProcedure scp = new ServerCrashProcedure(env, serverName, 
        true /* shouldSplitWal */, carryingMeta);
    scps.add(scp);
  }
  
  return scps.toArray(new ServerCrashProcedure[0]);
}

private boolean checkIfCarryingMeta(MasterProcedureEnv env, ServerName serverName) {
  try {
    SplitWALManager splitWALManager = env.getMasterServices().getSplitWALManager();
    List<Path> metaWALs = splitWALManager.getWALsToSplit(serverName, true /* meta */);
    return !metaWALs.isEmpty();
  } catch (IOException e) {
    LOG.warn("Failed to check for meta WALs for server {}, assuming not carrying meta", 
        serverName, e);
    return false;
  }
}
```

**Important:** SCPs created here will split WALs (both meta and regular) but will skip region assignment because region states were reset to OFFLINE by HBaseColdStartTool and region-to-server mappings were cleared.

**How SCPs Skip Assignment (Dependency on Task 6):**

`ServerCrashProcedure.getRegionsOnCrashedServer()` calls `AssignmentManager.getRegionsOnServer(serverName)`, which retrieves the region list from `ServerStateNode`. This in-memory structure is rebuilt from `hbase:meta` on Master startup.

For SCPs to correctly skip assignment:
- Task 6 (`HBaseColdStartTool`) must clear `info:server` and `info:sn` columns in META
- On Master restart, `ServerStateNode` instances will have empty region lists
- `getRegionsOnCrashedServer()` returns an empty list
- SCPs proceed through WAL splitting and finish without assigning any regions

See Section 2.2 for detailed explanation of this dependency

### 3.7 BAP Construction

```java
private BulkAssignProcedure[] createBulkAssignProcedures(List<TableName> tables) {
  return tables.stream()
      .map(tableName -> new BulkAssignProcedure(tableName))
      .toArray(BulkAssignProcedure[]::new);
}
```

BAPs are constructed with just the table name. Each BAP will:
1. Fetch regions from RegionStates during its PREPARE phase
2. Create an assignment plan using the LoadBalancer
3. Dispatch batches of TRSPs

### 3.8 Quarantine Configuration Check

At the start of `BULK_RECOVERY_PREPARE`:

```java
private void checkQuarantineConfiguration(MasterProcedureEnv env) {
  Configuration conf = env.getMasterConfiguration();
  boolean quarantineEnabled = conf.getBoolean(
      HConstants.SPLIT_WAL_QUARANTINE_ENABLED,
      HConstants.DEFAULT_SPLIT_WAL_QUARANTINE_ENABLED);
  
  if (!quarantineEnabled) {
    LOG.warn("*************************************************************");
    LOG.warn("WAL split quarantine is DISABLED ({}=false)", 
        HConstants.SPLIT_WAL_QUARANTINE_ENABLED);
    LOG.warn("If any WAL fails to split after retries, this procedure will be STUCK.");
    LOG.warn("Consider enabling quarantine or be prepared to use HBCK2 quarantine_wal");
    LOG.warn("command to manually quarantine problematic WALs.");
    LOG.warn("*************************************************************");
  } else {
    int threshold = conf.getInt(
        HConstants.SPLIT_WAL_QUARANTINE_FAILURE_THRESHOLD,
        HConstants.DEFAULT_SPLIT_WAL_QUARANTINE_FAILURE_THRESHOLD);
    LOG.info("WAL split quarantine is enabled with failure threshold of {} attempts", threshold);
  }
}
```

### 3.9 Child Failure Propagation

#### How the Framework Automatically Propagates Child Failures

The HBase procedure framework automatically propagates child failures to the parent procedure, so BRP does not need to manually check for child failures. This section explains the mechanism for documentation purposes.

When a child procedure fails, `RootProcedureState.addRollbackStep()` is called, which sets `RootProcedureState.state` to `State.FAILED` when `proc.isFailed()` returns true. Subsequently, when BRP attempts to resume after children complete via the `childrenLatch` mechanism, `RootProcedureState.acquire()` returns false because the state is no longer `RUNNING`. The framework then calls `setRollback()`, which returns true when the state is `FAILED` and no procedures are running. This triggers `executeRollback()`, which retrieves the child exception via `procStack.getException()` and calls `rootProc.setFailure(exception)`, automatically marking BRP as failed. Since BRP returns false from `isRollbackSupported()`, the framework calls `executeUnexpectedRollback()` which logs a FATAL error, cleans up children, and marks BRP as failed.

Because the framework handles child failure propagation automatically, BRP does not need to implement `hasFailedChildren()` or similar methods, nor does it need to persist child procedure IDs for failure checking purposes. Each state simply submits children, waits for completion, and transitions to the next state; if any child fails, BRP is automatically marked as failed. This is consistent with how `ServerCrashProcedure` works—it submits child `SplitWALProcedure`s and transitions without checking for failures, relying on the framework for failure propagation.

#### Simplified State Machine Pattern

```java
case BULK_RECOVERY_SPLIT_WALS:
  if (!scpsSubmitted) {
    addChildProcedure(createServerCrashProcedures(env));
    scpsSubmitted = true;
    if (crashedServers.isEmpty()) {
      walSplitCompleteTimeMs = EnvironmentEdgeManager.currentTime();
      setNextState(BulkRecoveryState.BULK_RECOVERY_ASSIGN_SYSTEM_TABLES);
      return Flow.HAS_MORE_STATE;
    }
    return Flow.HAS_MORE_STATE;  // Framework suspends; waits for children
  }
  // Re-entry: if we reach here, ALL children completed SUCCESSFULLY.
  // If any child failed, the framework would have set BRP to FAILED state
  // and we would never execute this code.
  walSplitCompleteTimeMs = EnvironmentEdgeManager.currentTime();
  setNextState(BulkRecoveryState.BULK_RECOVERY_ASSIGN_SYSTEM_TABLES);
  return Flow.HAS_MORE_STATE;
```

### 3.10 Error Handling

#### SCP Failures

BRP expects all SCPs to complete successfully. With Task 4's quarantine feature:
- WAL splitting failures result in quarantine, and the SplitWALProcedure completes with SUCCESS
- The parent SCP sees the child as complete and proceeds

However, SCPs can fail for non-WAL reasons:
- `SERVER_CRASH_GET_REGIONS`: IOException reading region state
- `SERVER_CRASH_CLAIM_REPLICATION_QUEUES`: Replication issues

If any SCP fails (not just completes with some quarantined WALs), BRP will fail entirely:

```java
@Override
protected boolean isRollbackSupported() {
  return false;  // Cannot rollback cold boot recovery
}

private void handleScpFailure(ServerCrashProcedure failedScp) {
  String msg = String.format(
      "ServerCrashProcedure for server %s failed. BulkRecoveryProcedure cannot continue. "
      + "Manual intervention required. Check Master logs for details.", 
      failedScp.getServerName());
  LOG.error(msg);
  throw new IllegalStateException(msg);
}
```

Failing the entire BRP for non-WAL SCP failures is conservative. It prevents partial recovery that could leave the cluster in an inconsistent state. This can be improved in future iterations to be more resilient.

#### BAP Failures

Similar to SCP failures, if any BAP fails, BRP fails. Individual region assignment failures within a BAP are handled by BAP's retry logic (per Task 3 design).

### 3.11 Metrics and Observability

#### MonitoredTask Integration

BRP creates a MonitoredTask to track progress. Initialization is idempotent for safe re-entry after Master restart:

```java
private MonitoredTask status;  // Transient - not persisted

private void initializeMonitoredTask() {
  // Idempotent: only create if not already created (handles re-entry after recovery)
  if (status == null) {
    String description = String.format(
        "BulkRecoveryProcedure: %d servers to recover, %d system tables, %d user tables",
        crashedServers.size(), systemTables.size(), userTables.size());
    status = TaskMonitor.get().createStatus(description);
  }
}

private void updateProgress(String message) {
  initializeMonitoredTask();  // Ensure status exists (recovery case)
  status.setStatus(message);
}

private void markComplete(String summary) {
  initializeMonitoredTask();  // Ensure status exists (recovery case)
  status.markComplete(summary);
}
```

Progress updates at each phase:
- "WAL Splitting: X/Y SCPs completed"
- "System Table Assignment: X/Y tables assigned"
- "User Table Assignment: X/Y tables assigned"

#### JMX Metrics

Add to `MetricsMasterSource.java`:

```java
// BulkRecoveryProcedure metrics
String BULK_RECOVERY_COUNT = "bulkRecoveryCount";
String BULK_RECOVERY_COUNT_DESC = "Number of BulkRecoveryProcedures executed";

String BULK_RECOVERY_WAL_SPLIT_TIME = "bulkRecoveryWalSplitTimeMs";
String BULK_RECOVERY_WAL_SPLIT_TIME_DESC = 
    "Time spent in WAL splitting phase of last BulkRecoveryProcedure (ms)";

String BULK_RECOVERY_ASSIGN_TIME = "bulkRecoveryAssignTimeMs";  
String BULK_RECOVERY_ASSIGN_TIME_DESC = 
    "Time spent in region assignment phase of last BulkRecoveryProcedure (ms)";

String BULK_RECOVERY_TOTAL_TIME = "bulkRecoveryTotalTimeMs";
String BULK_RECOVERY_TOTAL_TIME_DESC = 
    "Total time of last BulkRecoveryProcedure (ms)";
```

Add to `MetricsMasterSourceImpl.java`:

```java
private MutableGaugeLong bulkRecoveryCount;
private MutableGaugeLong bulkRecoveryWalSplitTime;
private MutableGaugeLong bulkRecoveryAssignTime;
private MutableGaugeLong bulkRecoveryTotalTime;

// In init():
bulkRecoveryCount = metricsRegistry.newGauge(BULK_RECOVERY_COUNT, 
    BULK_RECOVERY_COUNT_DESC, 0L);
bulkRecoveryWalSplitTime = metricsRegistry.newGauge(BULK_RECOVERY_WAL_SPLIT_TIME,
    BULK_RECOVERY_WAL_SPLIT_TIME_DESC, 0L);
bulkRecoveryAssignTime = metricsRegistry.newGauge(BULK_RECOVERY_ASSIGN_TIME,
    BULK_RECOVERY_ASSIGN_TIME_DESC, 0L);
bulkRecoveryTotalTime = metricsRegistry.newGauge(BULK_RECOVERY_TOTAL_TIME,
    BULK_RECOVERY_TOTAL_TIME_DESC, 0L);
```

Add to `MetricsMaster.java`:

```java
public void recordBulkRecoveryMetrics(long walSplitTimeMs, long assignTimeMs, long totalTimeMs) {
  masterSource.incrementBulkRecoveryCount();
  masterSource.setBulkRecoveryWalSplitTime(walSplitTimeMs);
  masterSource.setBulkRecoveryAssignTime(assignTimeMs);
  masterSource.setBulkRecoveryTotalTime(totalTimeMs);
}
```

#### Metrics Limitation After Master Restart

**Important:** Timing data (`startTimeMs`, `walSplitCompleteTimeMs`, etc.) is ephemeral and not persisted to the procedure store. If the Master restarts while BRP is running:
- On recovery, `startTimeMs` is reset to the current time
- The recorded metrics will reflect time elapsed since the restart, not total elapsed time from original procedure submission
- This is an acceptable trade-off to avoid complexity in state reconciliation

Operators investigating recovery performance after a Master restart should consult Master logs for timestamps of state transitions.

### 3.12 Serialization and Recovery Philosophy

#### Persisted vs. Ephemeral State

BRP persists **input parameters** and **submission flags**, while progress counters and timing remain ephemeral:

**Persisted state (required for idempotent recovery):**
- **Input parameters** (`crashedServers`, `systemTables`, `userTables`) - The work to be done
- **Submission flags** (`scpsSubmitted`, `systemBapsSubmitted`, `userBapsSubmitted`) - Track which child batches have been submitted. Critical for preventing duplicate child submissions across states.

**Ephemeral state (recalculated on recovery):**
- **Progress counters** (`scpsCompleted`, `systemBapsCompleted`, `userBapsCompleted`) - Not persisted
- **Timing data** (`startTimeMs`, `walSplitCompleteTimeMs`, etc.) - Not persisted
- **Child procedure references** (`scps`, `systemBaps`, `userBaps`) - Not persisted; instances are transient

#### Why Child Procedure Instances Are Transient

Child procedure instances (SCPs, BAPs) are created on-demand in the states where they are submitted, stored only in transient instance fields, and are intentionally not serialized. When `addChildProcedure()` is called, the framework writes child procedures to the procedure store, so BRP does not need to persist references to them. On recovery, the framework restores child-parent relationships, and BRP uses persisted submission flags (e.g., `scpsSubmitted`) to check if children were already submitted. If so, it skips re-creation; the framework automatically tracks child completion via the `childrenLatch` and resumes BRP when all children have finished. If BRP crashes before calling `addChildProcedure()` while the flag is still false, it re-enters the state on recovery, recreates the child instances, submits them, and sets the flag—no work is lost because submission had not yet occurred. Note that using `hasChildren()` alone would be insufficient because it returns true for all child procedures across all states; in a multi-phase procedure like BRP, completed children from phase 1 (SCPs) would cause `hasChildren()` to return true in phase 2 (BAPs), incorrectly skipping BAP submission. The persisted flags solve this by tracking submission per phase.

The rationale for this design is that if the Master crashes or restarts, the cluster state will have fundamentally changed: some child SCPs may have completed, some regions may have been assigned by other means, RegionServer availability may be different, and the in-memory state of RegionStates will be rebuilt from META. Rather than trying to reconcile stale persisted progress with the new reality, BRP recalculates what work remains based on current conditions when it resumes.

#### Serialization Implementation

```java
@Override
protected void serializeStateData(ProcedureStateSerializer serializer) throws IOException {
  super.serializeStateData(serializer);
  
  BulkRecoveryStateData.Builder builder = BulkRecoveryStateData.newBuilder();
  
  // Serialize input parameters
  for (ServerName server : crashedServers) {
    builder.addCrashedServers(ProtobufUtil.toServerName(server));
  }
  
  for (TableName table : systemTables) {
    builder.addSystemTables(ProtobufUtil.toProtoTableName(table));
  }
  
  for (TableName table : userTables) {
    builder.addUserTables(ProtobufUtil.toProtoTableName(table));
  }
  
  // Serialize submission flags - critical for idempotent recovery
  builder.setScpsSubmitted(scpsSubmitted);
  builder.setSystemBapsSubmitted(systemBapsSubmitted);
  builder.setUserBapsSubmitted(userBapsSubmitted);

  serializer.serialize(builder.build());
}

@Override
protected void deserializeStateData(ProcedureStateSerializer serializer) throws IOException {
  super.deserializeStateData(serializer);
  
  BulkRecoveryStateData data = serializer.deserialize(BulkRecoveryStateData.class);
  
  // Restore input parameters
  crashedServers = new ArrayList<>();
  for (HBaseProtos.ServerName server : data.getCrashedServersList()) {
    crashedServers.add(ProtobufUtil.toServerName(server));
  }
  
  systemTables = new ArrayList<>();
  for (HBaseProtos.TableName table : data.getSystemTablesList()) {
    systemTables.add(ProtobufUtil.toTableName(table));
  }
  
  userTables = new ArrayList<>();
  for (HBaseProtos.TableName table : data.getUserTablesList()) {
    userTables.add(ProtobufUtil.toTableName(table));
  }
  
  // Restore submission flags - these prevent duplicate child submissions after restart
  scpsSubmitted = data.getScpsSubmitted();
  systemBapsSubmitted = data.getSystemBapsSubmitted();
  userBapsSubmitted = data.getUserBapsSubmitted();
  
  startTimeMs = EnvironmentEdgeManager.currentTime();
}
```

#### Recovery Behavior by State

When BRP is recovered after a Master restart, it resumes from its persisted state but recalculates work:

**Recovery in BULK_RECOVERY_PREPARE:**
- Re-executes preparation logic
- No special handling needed

**Recovery in BULK_RECOVERY_SPLIT_WALS:**
- The `scpsSubmitted` flag determines whether to submit or wait:
  - If `scpsSubmitted=false`: SCPs were never submitted; create and submit them, set flag, and return
  - If `scpsSubmitted=true`: SCPs were already submitted; the framework automatically waits for child completion via the `childrenLatch` mechanism and resumes BRP when all children finish
- The procedure framework has persisted child SCPs; on recovery, it restores child-parent relationships
- Already-completed SCPs are not re-executed

**Recovery in BULK_RECOVERY_ASSIGN_SYSTEM_TABLES / BULK_RECOVERY_ASSIGN_USER_TABLES:**
- The respective submission flag (`systemBapsSubmitted` or `userBapsSubmitted`) determines whether to submit or wait:
  - If flag is false: BAPs were never submitted; create and submit them, set flag, and return
  - If flag is true: BAPs were already submitted; the framework automatically waits for child completion
- Child BAPs are restored by the procedure framework
- If some regions were assigned before the crash, BAPs will find fewer regions to assign when they query RegionStates

**Recovery in BULK_RECOVERY_POST_OPERATION:**
- Simply re-executes completion logic (logging and metrics)
- Returns `Flow.NO_MORE_STATE` to complete the procedure
- Metrics will reflect time since recovery, not original start time (acceptable trade-off)

### 3.13 Interaction with Existing Procedures

#### Preventing Duplicate SCPs

Task 1 of the Cold Boot Recovery project modified `ServerManager.findDeadServersAndProcess()` to prevent duplicate SCP scheduling. During cold boot:

1. BRP creates and submits SCPs in `BULK_RECOVERY_SPLIT_WALS` (when `scpsSubmitted` is false)
2. When Master startup calls `findDeadServersAndProcess()`, it will find these servers already have SCPs scheduled
3. No duplicate SCPs will be created

This is validated by the check:
```java
Set<ServerName> serversWithRecoveryInProgress =
  procExec.getActiveProceduresNoCopy().stream()
  .filter(p -> !p.isFinished())
  .filter(p -> p instanceof ServerCrashProcedure)
  .map(p -> ((ServerCrashProcedure) p).getServerName())
  .collect(Collectors.toSet());
```

#### Interaction with BAP

BRP creates BAPs with just a `TableName`. Each BAP:
1. Fetches regions from `RegionStates.getRegionsOfTableForEnabling()` in its PREPARE state
2. Creates an assignment plan
3. Dispatches TRSPs in batches

This matches the design from Task 3.

## 4. State Machine Flow Diagram

**Note on Failure Handling:** The diagram shows "[Any * failed]" branches leading to "PROCEDURE FAILS". These failure paths are handled **automatically by the procedure framework**, not by explicit checking in BRP's code. When any child procedure fails, the framework sets `RootProcedureState.state = FAILED`, which prevents BRP from executing and instead initiates rollback/failure handling. See section 3.9 for details.

```
                    ┌─────────────────────────────────────┐
                    │        BULK_RECOVERY_PREPARE        │
                    │                                     │
                    │  - Validate inputs                  │
                    │  - Check quarantine config          │
                    │  - Initialize MonitoredTask         │
                    │  (Child instances created on-demand │
                    │   in later states, not here)        │
                    └───────────────────┬─────────────────┘
                                        │
                                        ▼
                    ┌─────────────────────────────────────┐
                    │      BULK_RECOVERY_SPLIT_WALS       │
                    │                                     │
                    │  - If !scpsSubmitted:               │
                    │      addChildProcedure(SCPs)        │
                    │      scpsSubmitted = true           │
                    │      return (framework waits)       │
                    │  - Re-entry: proceed to next state  │
                    │  - Update progress                  │
                    │  - Record walSplitCompleteTimeMs    │
                    └───────────────────┬─────────────────┘
                                        │
                       ┌────────────────┴────────────────┐
                       │                                 │
                  [All SCPs                         [Any SCP
                   succeeded]                        failed]
                       │                                 │
                       ▼                                 │
    ┌─────────────────────────────────┐                  │
    │ BULK_RECOVERY_ASSIGN_SYSTEM_    │                  │
    │           TABLES                │                  │
    │                                 │                  │
    │  - If !systemBapsSubmitted:     │                  │
    │      addChildProcedure(BAPs)    │                  │
    │      systemBapsSubmitted = true │                  │
    │      return (framework waits)   │                  │
    │  - Re-entry: proceed next state │                  │
    │  - Update progress              │                  │
    └───────────────────┬─────────────┘                  │
                        │                                │
       ┌────────────────┴────────────────┐               │
       │                                 │               │
  [All BAPs                         [Any BAP             │
   succeeded]                        failed]             │
       │                                 │               │
       ▼                                 │               │
    ┌─────────────────────────────────┐  │               │
    │  BULK_RECOVERY_ASSIGN_USER_     │  │               │
    │            TABLES               │  │               │
    │                                 │  │               │
    │  - If !userBapsSubmitted:       │  │               │
    │      addChildProcedure(BAPs)    │  │               │
    │      userBapsSubmitted = true   │  │               │
    │      return (framework waits)   │  │               │
    │  - Re-entry: proceed next state │  │               │
    │  - Update progress              │  │               │
    └───────────────────┬─────────────┘  │               │
                        │                │               │
       ┌────────────────┴────────────────┤               │
       │                                 │               │
  [All BAPs                         [Any BAP             │
   succeeded]                        failed]             │
       │                                 │               │
       ▼                                 ▼               ▼
    ┌─────────────────────────────────┐  ┌──────────────────┐
    │    BULK_RECOVERY_POST_OPERATION │  │  PROCEDURE FAILS │
    │                                 │  │                  │
    │  - Log completion summary       │  │  Manual          │
    │  - Record JMX metrics           │  │  intervention    │
    │  - Mark MonitoredTask complete  │  │  required        │
    │  - return Flow.NO_MORE_STATE    │  │                  │
    └───────────────────┬─────────────┘  └──────────────────┘
                        │
                        ▼
                   ┌─────────┐
                   │ SUCCESS │
                   └─────────┘
```

## 5. Configuration Reference

| Configuration Key | Default | Description |
|-------------------|---------|-------------|
| `hbase.splitwal.quarantine.enabled` | `false` | Enable automatic WAL quarantine (from Task 4). BRP logs warning if disabled. |
| `hbase.splitwal.quarantine.failure.threshold` | `10` | Number of failures before automatic quarantine (from Task 4). |

BRP does not introduce new configuration keys. It relies on:
- Task 4 configuration for WAL quarantine behavior
- Task 3 configuration for BAP batching behavior

## 6. Testing Strategy

### Unit Tests

1. **State Machine Tests**
   - Test each state transition in the normal flow
   - Verify `SPLIT_WALS` returns `Flow.HAS_MORE_STATE` after submitting children
   - Verify `POST_OPERATION` returns `Flow.NO_MORE_STATE` to complete the procedure
   - Verify system tables complete before user table assignment starts

2. **Empty List Handling Tests**
   - Verify correct handling of empty `crashedServers` list (no WAL splitting needed, immediate transition)
   - Verify correct handling of empty `systemTables` list (no system BAPs, immediate transition)
   - Verify correct handling of empty `userTables` list (no user BAPs, immediate transition)
   - Verify procedure completes successfully when all input lists are empty

3. **Table Partitioning Tests**
   - Verify correct partitioning of tables into system vs user using `TableName.isSystemTable()`
   - Verify system tables are assigned in `ASSIGN_SYSTEM_TABLES` state
   - Verify user tables are assigned in `ASSIGN_USER_TABLES` state

4. **SCP Construction Tests**
   - Verify SCPs are created with `shouldSplitWal=true`
   - Verify `carryingMeta` is correctly determined from WAL file presence
   - Verify SCPs are created for all crashed servers in the input list

5. **BAP Construction Tests**
   - Verify BAPs are created with correct table names
   - Verify BAPs are created for both system and user tables

6. **Error Handling Tests**
   - Verify BRP fails entirely if any SCP fails
   - Verify BRP fails entirely if any BAP fails
   - Verify quarantine configuration warning is logged when disabled

7. **Metrics and Observability Tests**
   - Verify MonitoredTask is initialized and updated at each phase
   - Verify JMX metrics are recorded on completion
   - Verify progress updates include correct counts

8. **Serialization Tests**
   - Verify input parameters (crashedServers, systemTables, userTables) are correctly serialized and deserialized
   - Verify submission flags (scpsSubmitted, systemBapsSubmitted, userBapsSubmitted) are correctly serialized and deserialized
   - Verify ephemeral state (progress counters, timing) is NOT persisted

9. **State Idempotency Tests**
   - Verify `PREPARE` re-entry is safe (re-executes preparation logic)
   - Verify `SPLIT_WALS` re-entry with `scpsSubmitted=true` skips `addChildProcedure()` and waits for completion
   - Verify `SPLIT_WALS` re-entry with `scpsSubmitted=false` creates and submits SCPs
   - Verify `ASSIGN_SYSTEM_TABLES` re-entry with `systemBapsSubmitted=true` skips `addChildProcedure()` and waits for completion
   - Verify `ASSIGN_SYSTEM_TABLES` re-entry with `systemBapsSubmitted=false` creates and submits BAPs
   - Verify `ASSIGN_USER_TABLES` re-entry with `userBapsSubmitted=true` skips `addChildProcedure()` and waits for completion
   - Verify `ASSIGN_USER_TABLES` re-entry with `userBapsSubmitted=false` creates and submits BAPs
   - Verify `POST_OPERATION` re-entry is safe (read-only operations, returns `Flow.NO_MORE_STATE`)
   - Verify submission flags prevent duplicate child submissions across states (SCPs don't block BAP submission)

10. **Recovery Tests**
    - Verify recovery during `SPLIT_WALS` correctly uses persisted `scpsSubmitted` flag
    - Verify recovery during `ASSIGN_SYSTEM_TABLES` correctly uses persisted `systemBapsSubmitted` flag
    - Verify recovery during `ASSIGN_USER_TABLES` correctly uses persisted `userBapsSubmitted` flag
    - Verify recovery with partially completed children completes successfully
    - Verify MonitoredTask is re-initialized after recovery (idempotent null-check)

11. **Child Failure Propagation Tests (Framework Behavior)**
    - Verify BRP is marked FAILED when any child SCP fails (framework propagation)
    - Verify BRP is marked FAILED when any child system BAP fails (framework propagation)
    - Verify BRP is marked FAILED when any child user BAP fails (framework propagation)
    - Verify BRP failure includes the child exception details
    - Verify partial child completion (some succeed, one fails) still fails BRP
    - Verify BRP proceeds to next state only when ALL children succeed

### Acceptance Criteria

Per the parent design document:
- Unit tests for the BulkRecoveryProcedure state machine
- Integration tests simulating a cold start and verifying that WAL splitting completes before region assignment begins
- Recovery tests verifying that persisted submission flags prevent duplicate child procedure submissions after Master restart

## 7. File Changes Summary

### New Files

| File | Description |
|------|-------------|
| `hbase-server/src/main/java/org/apache/hadoop/hbase/master/procedure/BulkRecoveryProcedure.java` | Main procedure implementation |
| `hbase-server/src/test/java/org/apache/hadoop/hbase/master/procedure/TestBulkRecoveryProcedure.java` | Unit tests |
| `hbase-server/src/test/java/org/apache/hadoop/hbase/master/procedure/TestBulkRecoveryProcedureRecovery.java` | Recovery/double-execution tests |
| `hbase-server/src/test/java/org/apache/hadoop/hbase/master/procedure/TestBulkRecoveryProcedureIntegration.java` | Integration tests |

### Modified Files

| File | Change |
|------|--------|
| `hbase-protocol-shaded/src/main/protobuf/server/master/MasterProcedure.proto` | Add `BulkRecoveryState` enum and `BulkRecoveryStateData` message |
| `hbase-hadoop-compat/src/main/java/org/apache/hadoop/hbase/master/MetricsMasterSource.java` | Add BRP metric constants |
| `hbase-hadoop2-compat/src/main/java/org/apache/hadoop/hbase/master/MetricsMasterSourceImpl.java` | Add BRP metric implementations |
| `hbase-server/src/main/java/org/apache/hadoop/hbase/master/MetricsMaster.java` | Add BRP metric recording method |

## 8. Operational Considerations

### 8.1 Monitoring During Recovery

Operators should monitor:

1. **Master Web UI - Procedures Page:**
   - BulkRecoveryProcedure should be visible as the root procedure
   - Child SCPs and BAPs should show progress

2. **JMX Metrics:**
   - `Hadoop:service=HBase,name=Master,sub=Server` → `bulkRecoveryTotalTimeMs`
   - Monitor for increasing `quarantinedWALCount` (from Task 4)

3. **Log Monitoring:**
   - INFO level: State transitions, phase completions
   - WARN level: Quarantine configuration warning, retry messages
   - ERROR level: Procedure failures

### 8.2 Troubleshooting

**BRP Stuck in SPLIT_WALS:**
1. Check for stuck SplitWALProcedures in the procedure list
2. Use `quarantine_wal <proc_id>` from HBCK2 (Task 4) to force quarantine
3. Check `splitwal.quarantine.enabled` configuration

**BRP Fails:**
1. Check Master logs for the specific failure
2. SCP failures (non-WAL) require investigation of underlying issue
3. BAP failures indicate assignment problems

### 8.3 Recovery from BRP Failure

If BRP fails:
1. Investigate and fix the underlying issue
2. The operator can re-run `HBaseColdStartTool` to reset state and create a new BRP
3. Or manually submit SCPs/BAPs as needed using HBCK2
