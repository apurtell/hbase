# BulkAssignProcedure (BAP) Detailed Design

## Overview

This document provides the detailed design for `BulkAssignProcedure` (BAP), a new `StateMachineProcedure` that efficiently assigns a large number of offline regions for a given table. BAP addresses the inefficiency of submitting many individual `TransitRegionStateProcedure` (TRSP) instances by batching assignment work, reducing procedure fan-out, and providing throttled execution to prevent cluster destabilization.

BAP is designed to serve two use cases:
1. **Cold Boot Recovery**: As a child of `BulkRecoveryProcedure`, assigning all regions for a table after WAL splitting completes.
2. **Normal Operations**: Optimizing bulk assignment during table enable, table creation with pre-splits, or other scenarios involving many region assignments.

## Goals

- Provide a procedure that efficiently assigns many regions in throttled batches
- Reduce procedure fan-out compared to submitting individual TRSPs
- Integrate with `EnableTableProcedure` and `AssignmentManager` for normal operations
- Follow established patterns from `ReopenTableRegionsProcedure` for batching and backoff
- Support both `TableName`-based and `List<RegionInfo>`-based construction
- Maintain compatibility with existing assignment semantics and error handling

## Non-Goals

- Modifying the core TRSP implementation
- Changing how individual region assignments work
- Implementing the `BulkRecoveryProcedure` (Task 5)
- Implementing the `HBaseColdStartTool` (Task 6)

## Design

### Class Hierarchy

```
StateMachineProcedure<MasterProcedureEnv, BulkAssignState>
    └── AbstractStateMachineTableProcedure<BulkAssignState>
            └── BulkAssignProcedure
```

BAP extends `AbstractStateMachineTableProcedure` to leverage the existing table procedure infrastructure, including table locking, owner tracking, and sync latch support.

### State Machine

BAP implements a five-state state machine:

```
BULK_ASSIGN_PREPARE
       │
       ▼
BULK_ASSIGN_CREATE_PLAN
       │
       ▼
BULK_ASSIGN_DISPATCH_BATCH ◄──┐
       │                      │
       ▼                      │
BULK_ASSIGN_CONFIRM_BATCH ────┘ (loop if more regions)
       │
       ▼
BULK_ASSIGN_POST_OPERATION
```

#### State Descriptions

**Recovery Handling**: When the procedure is recovered from the procedure store after a Master restart, `pendingRegions` will be null (since it's transient and not persisted). The `executeFromState()` method detects this condition at the start of execution and forces a transition to `BULK_ASSIGN_CREATE_PLAN` regardless of the persisted state. This ensures the procedure always rebuilds its transient state from current cluster conditions before resuming assignment work.

1. **BULK_ASSIGN_PREPARE**
   - Perform initial sanity checks
   - Verify table exists and is in an appropriate state
   - If constructed with `TableName`: fetch regions using `RegionStates.getRegionsOfTableForEnabling()`
   - If constructed with `List<RegionInfo>`: use the provided list as `regionsToAssign`
   - Initialize transient tracking structures (retry counts, failed regions list)
   - Transition to `BULK_ASSIGN_CREATE_PLAN`
   - **Idempotency**: Only executed once; on recovery, `pendingRegions == null` forces transition to `CREATE_PLAN`

2. **BULK_ASSIGN_CREATE_PLAN**
   - Initialize configuration values (`batchSizeMax`, `batchBackoffMillis`, `maxRetryPerRegion`) via `initializeBatchSize()`—critical for recovery where PREPARE is skipped
   - Build the pending regions list by filtering `regionsToAssign` to exclude regions that are already OPEN or have an attached procedure
   - Call `LoadBalancer` to generate an assignment plan for pending regions
   - First attempt `retainAssignment()` for regions with known previous locations (from `info:server` in META)
   - Fall back to `roundRobinAssignment()` for regions without previous assignments
   - Store the assignment plan in memory for batch processing (transient, not persisted)
   - Transition to `BULK_ASSIGN_DISPATCH_BATCH`
   - **Idempotency**: Safe to re-execute; rebuilds all transient state from current cluster conditions

3. **BULK_ASSIGN_DISPATCH_BATCH**
   - Reset `batchConfirmed` flag to prepare for new batch
   - Extract the next batch of regions from the assignment plan (respecting `batchSize`)
   - For each region in the batch:
     - Acquire the `RegionStateNode` lock
     - Perform state transition checks (skip if region already has a procedure or is in an incompatible state)
     - If checks pass: create TRSP via `TransitRegionStateProcedure.assign()`, attach to `RegionStateNode`, add as child procedure
     - If checks fail: return region to `pendingRegions` for retry in a future batch
   - Track dispatched regions in `currentBatchRegions`
   - If no regions scheduled and pending non-empty: apply exponential backoff and suspend (stay in this state)
   - Otherwise: transition to `BULK_ASSIGN_CONFIRM_BATCH`
   - **Idempotency**: Safe to re-execute; atomic lock-check-attach pattern prevents duplicate TRSPs; unschedulable regions returned to pending

4. **BULK_ASSIGN_CONFIRM_BATCH**
   - Wait for all child TRSPs from the current batch to complete (automatic via `addChildProcedure()` semantics)
   - If `batchConfirmed` is false (first entry for this batch):
     - Increment `batchesProcessed` counter
     - Check results of completed TRSPs and update counters/handle failures
     - Clear `currentBatchRegions`
     - Set `batchConfirmed = true`
   - If there are more regions to process (pending list non-empty):
     - Progress batch size via `progressBatchSize()` (exponential growth up to max)
     - Set next state to `BULK_ASSIGN_DISPATCH_BATCH`
     - Apply fixed batch backoff delay if configured and suspend
   - If all regions processed: transition to `BULK_ASSIGN_POST_OPERATION`
   - **Idempotency**: Safe to re-execute; `batchConfirmed` flag prevents double-counting; state transition set before suspension

5. **BULK_ASSIGN_POST_OPERATION**
   - Log summary of assignment results
   - Clean up any tracking structures
   - Procedure completes successfully
   - **Idempotency**: Read-only operations; safe to re-execute

### Protobuf Definition

Add to `MasterProcedure.proto`:

```protobuf
enum BulkAssignState {
  BULK_ASSIGN_PREPARE = 1;
  BULK_ASSIGN_CREATE_PLAN = 2;
  BULK_ASSIGN_DISPATCH_BATCH = 3;
  BULK_ASSIGN_CONFIRM_BATCH = 4;
  BULK_ASSIGN_POST_OPERATION = 5;
}

message BulkAssignStateData {
  optional TableName table_name = 1;
  repeated RegionInfo region_info = 2;
}
```

Both this detailed design and the parent design document (`HBase_ColdBootRecovery.md`) use `BULK_ASSIGN_CONFIRM_BATCH` for the confirmation state, consistent with protobuf enum naming conventions used elsewhere in HBase.

**Minimal Serialization Design Rationale**: The `BulkAssignStateData` message intentionally contains only `table_name` and `region_info`. All in-progress state (pending regions, assignment plan, retry counts, batch counters) is **transient** and not persisted. When a Master restarts—whether due to crash, failover, or planned maintenance—the cluster state may have changed significantly: servers may have come online or gone offline, some regions may have been assigned by child TRSPs that completed before the restart, and network/resource conditions may have changed. Persisting and restoring stale in-progress state would lead to suboptimal or incorrect behavior:

- An assignment plan targeting servers that are now offline
- Retry counts that don't reflect the new cluster conditions
- Pending region lists that include already-assigned regions

By persisting only the immutable input (`regionsToAssign`) and regenerating all transient state from current cluster conditions on recovery, BAP ensures correctness and optimal assignments regardless of what happened during the Master outage.

### Table Locking

BAP uses a SHARED table lock via `TableOperationType.REGION_ASSIGN`. This allows:
- Multiple BAPs for different tables to run concurrently
- Region-level operations (split, merge) to proceed on other regions of the same table
- Child TRSPs to acquire their own region-level locks

The shared lock prevents:
- Schema changes (`ModifyTableProcedure`) during bulk assignment
- Table deletion during bulk assignment
- Conflicting exclusive table operations

#### TableOperationType Usage

BAP uses `TableOperationType.REGION_ASSIGN`, which already exists in `TableProcedureInterface` and is already handled by `TableQueue.requireTableExclusiveLock()` to return `false` (shared lock). No changes to these classes are required.

### Constructors

BAP supports two construction modes:

#### Constructor 1: TableName-based

```java
public BulkAssignProcedure(TableName tableName)
```

When constructed with only a `TableName`:
- In `BULK_ASSIGN_PREPARE`, fetch regions via `RegionStates.getRegionsOfTableForEnabling(tableName)`
- This returns all regions except those in `SPLIT` state or where `isSplit()` is true
- Suitable for `EnableTableProcedure` integration and cold boot scenarios

#### Constructor 2: RegionInfo List-based

```java
public BulkAssignProcedure(TableName tableName, List<RegionInfo> regions)
```

When constructed with an explicit `List<RegionInfo>`:
- The provided regions are stored directly as `regionsToAssign`
- No filtering occurs in `BULK_ASSIGN_PREPARE`—all filtering is deferred to `BULK_ASSIGN_CREATE_PLAN`
- This design avoids redundant filtering: since `regionsToAssign` is persisted and `CREATE_PLAN` must filter on every execution (including recovery), filtering in `PREPARE` would have no lasting effect
- Suitable for callers who have already determined the exact set of regions to assign

#### Deserialization Constructor

```java
public BulkAssignProcedure()  // Required for procedure framework
```

### Configuration

BAP uses configuration keys following the RTRP pattern:

| Configuration Key | Type | Default | Description |
|-------------------|------|---------|-------------|
| `hbase.bulk.assign.batch.backoff.ms` | Long | 0 | Milliseconds to wait between batches |
| `hbase.bulk.assign.batch.size.max` | Integer | -1 (disabled) | Maximum batch size; -1 means no limit |
| `hbase.bulk.assign.region.retry.max` | Integer | 3 | Maximum retry attempts per region before giving up |
| `hbase.bulk.assign.threshold` | Integer | 10 | Minimum region count to trigger BAP usage in AssignmentManager |

**Batch Size Initialization** (in constructor, `BULK_ASSIGN_PREPARE`, or `BULK_ASSIGN_CREATE_PLAN` on recovery):

```java
private void initializeBatchSize(Configuration conf) {
  batchSizeMax = conf.getInt(BULK_ASSIGN_BATCH_SIZE_MAX_KEY, 
      BULK_ASSIGN_BATCH_SIZE_MAX_DEFAULT);
  batchBackoffMillis = conf.getLong(BULK_ASSIGN_BATCH_BACKOFF_MS_KEY,
      BULK_ASSIGN_BATCH_BACKOFF_MS_DEFAULT);
  maxRetryPerRegion = conf.getInt(BULK_ASSIGN_REGION_RETRY_MAX_KEY,
      BULK_ASSIGN_REGION_RETRY_MAX_DEFAULT);
  
  if (batchSizeMax < 0) {
    // Disabled - process all regions in one batch
    batchSize = Integer.MAX_VALUE;
    batchSizeMax = Integer.MAX_VALUE;
  } else {
    // Enabled - start small and grow exponentially
    batchSize = 1;
  }
}
```

When `batch.size.max` is disabled (-1):
- `batchSize` is set to `Integer.MAX_VALUE` (all regions in one batch)

When `batch.size.max` is enabled (positive value):
- `batchSize` starts at 1
- After each successful batch, `batchSize = Math.min(batchSizeMax, 2 * batchSize)`
- This progressive growth prevents overwhelming the cluster on startup

### Pending Region Management

BAP maintains several fields to track region assignment progress. All fields except `regionsToAssign` are **transient**—they are not persisted and are rebuilt on procedure recovery based on current cluster state:

1. **`regionsToAssign`**: The complete list of regions to assign, provided at construction or fetched in `BULK_ASSIGN_PREPARE`.

2. **`pendingRegions`** (transient): Regions that still need assignment attempts. Built in `BULK_ASSIGN_CREATE_PLAN` by filtering `regionsToAssign` to exclude regions that are already OPEN or have an attached procedure. Regions are removed from this list as they are dispatched in batches. Regions that cannot be scheduled (e.g., temporarily have another procedure attached) are returned to this list. Failed regions may be re-added for retry (see Error Handling).

3. **`failedRegions`** (transient): Regions that exceeded the maximum retry count during this procedure execution. These regions are tracked but not retried further. They remain in RIT state for operator intervention. On recovery, this list starts empty since retry counts are also transient.

4. **`currentBatchRegions`** (transient): Regions dispatched in the current batch that are awaiting confirmation. Used by `BULK_ASSIGN_CONFIRM_BATCH` to check assignment results.

5. **`batchConfirmed`** (transient): Boolean flag that tracks whether the current batch's results have been processed. This flag ensures idempotent re-entry to `BULK_ASSIGN_CONFIRM_BATCH`—if the procedure suspends for backoff and resumes, the flag prevents double-counting of metrics and double-handling of failures. Reset to `false` at the start of `BULK_ASSIGN_DISPATCH_BATCH`.

6. **`retryCounter`** (transient): Used for exponential backoff when no regions can be scheduled in `BULK_ASSIGN_DISPATCH_BATCH`. Created lazily on first suspension and reset to `null` after successful scheduling. On recovery, starts as `null` and is recreated as needed.

The `getNextBatch()` method extracts the next batch of regions from `pendingRegions`:

```java
/**
 * Extract the next batch of regions to dispatch.
 * Respects the current batchSize and removes returned regions from pendingRegions.
 */
private List<RegionInfo> getNextBatch() {
  int count = Math.min(batchSize, pendingRegions.size());
  List<RegionInfo> batch = new ArrayList<>(count);
  
  // Extract up to batchSize regions from pending list
  Iterator<RegionInfo> iter = pendingRegions.iterator();
  while (iter.hasNext() && batch.size() < count) {
    batch.add(iter.next());
    iter.remove();
  }
  
  return batch;
}
```

The `getTargetServerForRegion()` method looks up the planned target server using a reverse index for O(1) performance:

```java
/**
 * Get the target server for a region from the reverse index.
 * Returns null if no target was planned (balancer will choose at dispatch time).
 */
private ServerName getTargetServerForRegion(RegionInfo region) {
  if (regionToServerMap == null) {
    return null;
  }
  return regionToServerMap.get(region);
}
```

The reverse index `regionToServerMap` is built in `createAssignmentPlan()` for O(1) lookups, avoiding the O(n) cost of iterating through the assignment plan for each region.

The assignment plan uses `retainAssignment()` to preserve data locality when previous host information is available and the host is still online. In cold boot scenarios (via `BulkRecoveryProcedure`), previous host information in META's `info:server` field may be stale if the cluster experienced significant disruption. However, this is handled gracefully: if the previous host is not in the available server list, the region falls back to round-robin assignment. The `retainAssignment()` approach is most beneficial in normal operations (like `EnableTableProcedure`) where locality information is fresh and valuable.

### Batch Processing Logic

Following the RTRP pattern:

The `progressBatchSize()` method implements exponential batch growth to balance cluster stability with assignment throughput. When bulk assignment begins, the procedure starts conservatively with a small batch size (typically 1) to avoid overwhelming the cluster during initial startup when system state may be uncertain. After each successful batch completes, the method doubles the batch size to accelerate assignment as confidence in cluster stability grows. The batch size is capped at `batchSizeMax` to prevent any single batch from becoming too large, which could cause excessive memory usage or RPC timeouts. The method also includes overflow protection: if integer overflow causes the doubled value to wrap around and become smaller than the previous batch size, the method detects this anomaly and resets to the configured maximum. This progressive growth strategy mirrors the approach used by `ReopenTableRegionsProcedure` and ensures that BAP can handle both small tables (where a single batch suffices) and very large tables (where controlled ramp-up prevents cluster destabilization) with the same code path.

```java
private int progressBatchSize() {
  int previousBatchSize = batchSize;
  batchSize = Math.min(batchSizeMax, 2 * batchSize);
  if (batchSize < previousBatchSize) {
    // Overflow protection
    batchSize = batchSizeMax;
  }
  return batchSize;
}
```

### Assignment Plan Creation

In `BULK_ASSIGN_CREATE_PLAN`:

The `createAssignmentPlan()` method builds a comprehensive mapping of regions to target RegionServers before any assignments are dispatched. This upfront planning phase is essential for achieving balanced region distribution and minimizing unnecessary region movements after the cluster stabilizes. The assignment plan is transient and not persisted. On procedure recovery after Master restart, it is regenerated based on current cluster state, ensuring the plan reflects the actual available servers and region states.

First, the method builds the `pendingRegions` list by filtering `regionsToAssign`. For each region, it examines the current `RegionStateNode`:
- Regions already in `OPEN` state are skipped (assignment already complete)
- Regions with an attached procedure are skipped (another operation is handling them)  
- Regions in transitional states (`SPLITTING`, `MERGING`, etc.) are skipped
- Split parents are skipped
- All other regions are added to `pendingRegions`

This filtering is critical for recovery correctness: if the Master restarts mid-procedure, some regions may have been successfully assigned before the restart. By re-examining current state, the procedure avoids re-assigning already-open regions.

Next, the method obtains the current list of available RegionServers via `ServerManager.createDestinationServersList()`. If no servers are available, the procedure cannot proceed and must fail with an appropriate exception—this is a hard prerequisite for bulk assignment.

The method then partitions pending regions into two categories based on their assignment history. For each region, it queries the `RegionStateNode` to retrieve the last known host server (stored in META's `info:server` column). Regions whose last host is still present in the available server list are candidates for locality-preserving assignment; regions without a previous host, or whose previous host is no longer available, require fresh placement.

For regions with known previous locations, the method invokes the `LoadBalancer.retainAssignment()` API, which attempts to return each region to its former host. This preserves data locality—the region's HFiles and block cache entries are likely still present on that server—reducing read latency and avoiding unnecessary data transfer during recovery. If `retainAssignment()` fails (for example, due to balancer misconfiguration or transient errors), those regions gracefully fall back to the round-robin path rather than failing the entire procedure.

For regions without previous assignments (including those that fell back from the retain path), the method uses `LoadBalancer.roundRobinAssignment()` to distribute them evenly across all available servers. This ensures no single server is overwhelmed and provides a reasonable starting distribution that the balancer can later optimize.

As a final fallback, if even round-robin assignment fails, the method assigns regions with a `null` target server. This delegates server selection entirely to the child TRSP, which will invoke the balancer at dispatch time. While less efficient (each TRSP makes its own balancer call), this ensures the procedure can make progress even under degraded conditions.

The resulting assignment plan is stored as a transient `Map<ServerName, List<RegionInfo>>`. Additionally, a reverse index `Map<RegionInfo, ServerName>` is built for O(1) target server lookups during batch dispatch.

```java
// Transient fields for assignment tracking
private Map<ServerName, List<RegionInfo>> assignmentPlan;
private Map<RegionInfo, ServerName> regionToServerMap;  // Reverse index for O(1) lookups

private void createAssignmentPlan(MasterProcedureEnv env) throws IOException {
  // Ensure configuration is loaded (critical for recovery where PREPARE is skipped)
  initializeBatchSize(env.getMasterConfiguration());
  
  AssignmentManager am = env.getAssignmentManager();
  LoadBalancer balancer = am.getBalancer();
  
  // Build pending regions list by filtering regionsToAssign based on current state
  // This is critical for recovery: regions assigned before Master restart are skipped
  pendingRegions = new ArrayList<>();
  for (RegionInfo region : regionsToAssign) {
    RegionStateNode rsn = am.getRegionStates().getRegionStateNode(region);
    if (rsn == null) {
      // No state node yet - needs assignment
      pendingRegions.add(region);
      continue;
    }
    
    // Skip if another procedure is handling this region
    if (rsn.getProcedure() != null) {
      LOG.debug("Skipping {}, already has procedure", region.getShortNameToLog());
      continue;
    }
    
    // Skip if already open
    if (rsn.isInState(State.OPEN)) {
      LOG.debug("Skipping {}, already OPEN", region.getShortNameToLog());
      continue;
    }
    
    // Skip split parents and transitional states
    if (region.isSplit() || rsn.isInState(State.SPLIT, State.SPLITTING, 
        State.MERGING, State.SPLITTING_NEW, State.MERGING_NEW)) {
      LOG.debug("Skipping {}, in state {}", region.getShortNameToLog(), rsn.getState());
      continue;
    }
    
    pendingRegions.add(region);
  }
  
  if (pendingRegions.isEmpty()) {
    LOG.info("No regions need assignment for table {}", tableName);
    assignmentPlan = new HashMap<>();
    return;
  }
  
  List<ServerName> servers = env.getMasterServices().getServerManager()
      .createDestinationServersList(null);
  
  if (servers.isEmpty()) {
    throw new HBaseIOException("No servers available for assignment");
  }
  
  // Separate regions with and without previous assignments
  Map<RegionInfo, ServerName> regionsWithPreviousAssignment = new HashMap<>();
  List<RegionInfo> regionsWithoutPreviousAssignment = new ArrayList<>();
  
  for (RegionInfo region : pendingRegions) {
    RegionStateNode rsn = am.getRegionStates().getRegionStateNode(region);
    ServerName lastHost = (rsn != null) ? rsn.getLastHost() : null;
    if (lastHost != null && servers.contains(lastHost)) {
      regionsWithPreviousAssignment.put(region, lastHost);
    } else {
      regionsWithoutPreviousAssignment.add(region);
    }
  }
  
  // Build assignment plan
  assignmentPlan = new HashMap<>();
  
  // Try to retain assignments for regions with known previous locations
  if (!regionsWithPreviousAssignment.isEmpty()) {
    try {
      Map<ServerName, List<RegionInfo>> retained = 
          balancer.retainAssignment(regionsWithPreviousAssignment, servers);
      mergeIntoPlan(assignmentPlan, retained);
    } catch (IOException e) {
      LOG.warn("Failed to retain assignments, will use round-robin", e);
      regionsWithoutPreviousAssignment.addAll(regionsWithPreviousAssignment.keySet());
    }
  }
  
  // Round-robin for regions without previous assignments
  if (!regionsWithoutPreviousAssignment.isEmpty()) {
    try {
      Map<ServerName, List<RegionInfo>> roundRobin = 
          balancer.roundRobinAssignment(regionsWithoutPreviousAssignment, servers);
      mergeIntoPlan(assignmentPlan, roundRobin);
    } catch (IOException e) {
      LOG.warn("Failed round-robin assignment", e);
      // Fallback: assign without target server, let TRSP figure it out
      for (RegionInfo region : regionsWithoutPreviousAssignment) {
        assignmentPlan.computeIfAbsent(null, k -> new ArrayList<>()).add(region);
      }
    }
  }
  
  // Build reverse index for O(1) lookups during batch dispatch
  regionToServerMap = new HashMap<>();
  for (Map.Entry<ServerName, List<RegionInfo>> entry : assignmentPlan.entrySet()) {
    ServerName server = entry.getKey();
    for (RegionInfo region : entry.getValue()) {
      regionToServerMap.put(region, server);
    }
  }
  
  LOG.info("Created assignment plan for {} regions of table {} ({} with locality, {} round-robin)",
      pendingRegions.size(), tableName, 
      regionsWithPreviousAssignment.size(), regionsWithoutPreviousAssignment.size());
}
```

### Region Assignment And Locking

Following the pattern established by `ReopenTableRegionsProcedure`, the region state validation, TRSP creation, and TRSP attachment to the `RegionStateNode` must all occur atomically while holding the region's lock. This prevents race conditions where another procedure could attach to the region between validation and TRSP creation.

The `tryAssignRegion()` method encapsulates this atomic check-create-attach pattern. It returns the created TRSP if successful, or `null` if the region should be skipped. This approach differs from a separate `canAssignRegion()` validation method because the lock must be held continuously across all three operations—releasing the lock between validation and TRSP creation would introduce a race window.

The method first obtains or creates the `RegionStateNode`. If the node doesn't exist, `getOrCreateRegionStateNode()` atomically creates it, which is safe because we'll immediately attempt to lock and attach a procedure.

The lock is acquired before any state examination. All validation checks occur within the critical section:

1. **Existing procedure check**: If `getProcedure()` returns non-null, another procedure owns this region. Skip to avoid conflicts.

2. **Split parent check**: Regions marked as split (via `RegionInfo.isSplit()` or `State.SPLIT`) are historical artifacts awaiting garbage collection. Never assign these.

3. **Already open check**: If the region is already `OPEN`, no assignment is needed.

4. **Transitional state check**: Regions in `SPLITTING`, `MERGING`, `SPLITTING_NEW`, or `MERGING_NEW` states are being managed by split/merge procedures. Skip to avoid interference.

If all checks pass, the TRSP is created and immediately attached to the `RegionStateNode` via `setProcedure()` while still holding the lock. Only after attachment is the lock released. The `addChildProcedure()` call occurs outside the lock (following RTRP's pattern) since the procedure is already safely attached to the region.

```java
/**
 * Atomically validate region state, create TRSP, and attach to RegionStateNode.
 * Must hold lock across all operations to prevent races with other procedures.
 * 
 * @return The created TRSP, or null if region should be skipped
 */
private TransitRegionStateProcedure tryAssignRegion(MasterProcedureEnv env, 
    RegionInfo region, ServerName targetServer) {
  RegionStateNode regionNode = env.getAssignmentManager().getRegionStates()
      .getOrCreateRegionStateNode(region);
  
  TransitRegionStateProcedure proc;
  regionNode.lock();
  try {
    // Skip if another procedure is already handling this region
    if (regionNode.getProcedure() != null) {
      LOG.debug("Skipping {}, already has procedure {}", 
          region.getShortNameToLog(), regionNode.getProcedure());
      return null;
    }
    
    // Skip split parents
    if (region.isSplit() || regionNode.isInState(State.SPLIT)) {
      LOG.debug("Skipping split parent {}", region.getShortNameToLog());
      return null;
    }
    
    // Skip if already open
    if (regionNode.isInState(State.OPEN)) {
      LOG.debug("Skipping {}, already OPEN", region.getShortNameToLog());
      return null;
    }
    
    // Skip if in a transitional state managed by another operation
    if (regionNode.isInState(State.SPLITTING, State.MERGING, 
        State.SPLITTING_NEW, State.MERGING_NEW)) {
      LOG.debug("Skipping {}, in transitional state {}", 
          region.getShortNameToLog(), regionNode.getState());
      return null;
    }
    
    // All checks passed - create and attach TRSP while holding lock
    proc = TransitRegionStateProcedure.assign(env, region, targetServer);
    regionNode.setProcedure(proc);
  } finally {
    regionNode.unlock();
  }
  
  // Return the procedure for adding as child (outside lock, following RTRP pattern)
  return proc;
}
```

### Error Handling and Retries

BAP tracks retry counts per region:

The `handleFailedAssignment()` method implements bounded retry logic that balances persistence with practicality. Individual region assignments can fail for many reasons: transient network issues, temporary RegionServer unavailability, RPC timeouts, or resource exhaustion. Many of these failures are recoverable if the operation is simply retried after conditions improve. However, some failures indicate persistent problems (corrupt region data, misconfigured table, hardware failures) where unlimited retries would waste resources and delay overall progress.

The method maintains a per-region retry counter using the region's encoded name as a stable key. The encoded name is used rather than the `RegionInfo` object itself because the same logical region may be represented by different `RegionInfo` instances. On each failure, the counter is incremented and compared against the configured maximum retry limit.

When the retry count remains below the threshold, the method logs a warning (providing operational visibility into transient issues) and returns the region to the pending list for inclusion in a subsequent batch. This re-queuing ensures failed regions get additional attempts without blocking progress on other regions—the next batch may include a mix of never-attempted regions and retry candidates.

When the retry count reaches the configured maximum, the method logs an error and moves the region to a permanent failure list. Critically, BAP does not throw an exception or abort the entire procedure at this point. The design philosophy is that partial success is preferable to complete failure: successfully assigning 998 out of 1000 regions is far better than failing the entire operation because two regions have persistent issues. Operators can address stuck regions later using HBCK2 or manual intervention.

The failed region remains in RIT (Region In Transition) state, which ensures it appears in the Master UI's RIT metrics and can be monitored through existing operational tooling. This leverages the existing observability infrastructure rather than requiring BAP-specific dashboards or alerting.

```java
private Map<String, Integer> regionRetryCount = new HashMap<>();
private int maxRetryPerRegion; // From config

private void handleFailedAssignment(RegionInfo region) {
  String encodedName = region.getEncodedName();
  int retries = regionRetryCount.getOrDefault(encodedName, 0) + 1;
  regionRetryCount.put(encodedName, retries);
  
  if (retries < maxRetryPerRegion) {
    LOG.warn("Region {} assignment failed, will retry (attempt {}/{})",
        region.getShortNameToLog(), retries, maxRetryPerRegion);
    // Add back to pending list for next batch
    pendingRegions.add(region);
  } else {
    LOG.error("Region {} assignment failed after {} attempts, giving up. "
        + "Region will remain in RIT state.", 
        region.getShortNameToLog(), retries);
    failedRegions.add(region);
    // Region remains in RIT - existing RIT metrics will track this
  }
}
```

BAP does not fail the entire procedure when individual region assignments fail. Failed region assignments are logged at ERROR level, remain in RIT (Region In Transition) state, are tracked by existing RIT metrics, and can be addressed by operators using HBCK2 or manual intervention. This behavior matches the existing assignment semantics where individual region failures don't cascade to table-level failures.

### Metrics and Observability

BAP exposes metrics following the RTRP pattern. Note that these counters are **transient** and reset on procedure recovery, reflecting only the work done since the last Master start:

```java
public long getRegionsAssigned() {
  return regionsAssigned;
}

public long getBatchesProcessed() {
  return batchesProcessed;
}

public long getFailedRegions() {
  return failedRegions.size();
}
```

### CONFIRM_BATCH Implementation

The `confirmBatch()` method processes completed child TRSPs and manages batch progression. This method is designed to be **idempotent for re-entry**: if the procedure suspends (for backoff) and resumes, or if the Master restarts, re-entering this state produces correct behavior.

**Idempotency Design**:
- The `batchConfirmed` flag tracks whether the current batch has been processed. This prevents double-counting metrics and double-handling of failed regions on re-entry.
- State transitions are set BEFORE throwing `ProcedureSuspendedException`, ensuring the procedure advances to the correct state on resume.
- The flag is reset in `DISPATCH_BATCH` when a new batch is started.

```java
// Transient flag to track if current batch results have been processed
private boolean batchConfirmed = false;

private Flow confirmBatch(MasterProcedureEnv env) 
    throws ProcedureSuspendedException {
  
  // Idempotency: Only process batch results once per batch
  // On re-entry after suspension, skip to state transition logic
  if (!batchConfirmed) {
    batchesProcessed++;
    
    // Check results of child TRSPs from the current batch
    // The procedure framework automatically tracks child completion
    for (RegionInfo region : currentBatchRegions) {
      RegionStateNode rsn = env.getAssignmentManager().getRegionStates()
          .getRegionStateNode(region);
      if (rsn != null && rsn.isInState(State.OPEN)) {
        regionsAssigned++;
      } else {
        // Assignment failed or region not in expected state
        handleFailedAssignment(region);
      }
    }
    currentBatchRegions.clear();
    batchConfirmed = true;
  }
  
  // Check if more work remains
  if (!pendingRegions.isEmpty()) {
    // Progress batch size for next iteration
    progressBatchSize();
    
    // CRITICAL: Set next state BEFORE suspension to ensure correct 
    // state on resume. This is essential for idempotency.
    setNextState(BulkAssignState.BULK_ASSIGN_DISPATCH_BATCH);
    
    // Apply fixed batch backoff if configured
    if (batchBackoffMillis > 0) {
      LOG.debug("Applying batch backoff of {}ms before next batch", batchBackoffMillis);
      setBackoffState(batchBackoffMillis);
      throw new ProcedureSuspendedException();
    }
    
    return Flow.HAS_MORE_STATE;
  }
  
  // All regions processed
  setNextState(BulkAssignState.BULK_ASSIGN_POST_OPERATION);
  return Flow.HAS_MORE_STATE;
}
```

**Note on Two Backoff Mechanisms**: BAP uses two distinct backoff strategies:
1. **Fixed batch backoff** (`hbase.bulk.assign.batch.backoff.ms`): Applied between successful batches to prevent overwhelming the cluster. Default is 0 (no delay).
2. **Exponential retry backoff** (in `dispatchBatch()`): Applied when no regions could be scheduled, using `RetryCounter` for exponential growth with jitter.

Logging:
- INFO level: Batch start/completion with region counts
- DEBUG level: Individual region assignment decisions
- WARN level: Assignment retries
- ERROR level: Regions that exceeded retry limit

Example log output:
```
INFO  BulkAssignProcedure - Starting batch 3/10 for table=mytable, batchSize=8, remaining=42
INFO  BulkAssignProcedure - Batch 3/10 completed, assigned=7, failed=1, total assigned=23/50
WARN  BulkAssignProcedure - Region abc123 assignment failed, will retry (attempt 2/3)
ERROR BulkAssignProcedure - Region def456 assignment failed after 3 attempts, giving up
INFO  BulkAssignProcedure - BulkAssignProcedure completed for table=mytable: 
      assigned=48, failed=2, batches=10, elapsed=45s
```

### Integration with AssignmentManager

Add a new method to `AssignmentManager`:

The `createAssignProceduresOptimized()` method provides a transparent optimization layer that allows existing callers to benefit from bulk assignment without requiring changes to their code. This method serves as the primary integration point between BAP and the rest of the HBase codebase, automatically selecting the most efficient assignment strategy based on the number of regions involved.

The method accepts a list of regions and returns an array of procedures—either a single `BulkAssignProcedure` or multiple individual `TransitRegionStateProcedure` instances. This uniform return type allows callers to use `addChildProcedure()` without knowing which strategy was selected. The optimization is completely transparent to the caller.

A configurable threshold determines when BAP is used versus individual TRSPs. For small region counts (below threshold), the overhead of BAP's batching machinery, state serialization, and plan creation exceeds the benefit; individual TRSPs are simpler and sufficient. For large region counts (at or above threshold), BAP's batched execution, reduced procedure fan-out, and throttled dispatch provide significant performance and stability benefits. The default threshold should be tuned based on empirical testing, but a value around 10 regions provides a reasonable starting point.

The method must validate that all regions in the input list belong to the same table. BAP is designed as a single-table procedure—it acquires a table-level lock and creates an assignment plan assuming uniform table context. If the input contains regions from multiple tables (which could happen if a caller aggregates regions without proper grouping), the method must fall back to individual TRSPs rather than fail or produce incorrect behavior. This mixed-table case is logged at DEBUG level since it represents a suboptimal but valid code path rather than an error.

When BAP is selected, the method extracts the table name from the first region (after validation confirms all regions share it) and constructs a new `BulkAssignProcedure` with the complete region list. The single-element array return allows the caller to treat this identically to the multi-procedure case.

Empty region lists are handled as a fast-path returning an empty array, avoiding unnecessary object creation or configuration lookups when there's no work to do.

```java
/**
 * Create a BulkAssignProcedure for the given regions if the count exceeds
 * the configured threshold, otherwise create individual assign procedures.
 * 
 * @param regions The regions to assign
 * @return A BulkAssignProcedure or an array of TransitRegionStateProcedures
 */
public Procedure<MasterProcedureEnv>[] createAssignProceduresOptimized(
    List<RegionInfo> regions) {
  if (regions.isEmpty()) {
    return new Procedure[0];
  }
  
  int threshold = master.getConfiguration().getInt(
      BulkAssignProcedure.BULK_ASSIGN_THRESHOLD_KEY,
      BulkAssignProcedure.BULK_ASSIGN_THRESHOLD_DEFAULT);
  
  if (regions.size() >= threshold) {
    TableName tableName = regions.get(0).getTable();
    // Verify all regions are for the same table
    for (RegionInfo ri : regions) {
      if (!ri.getTable().equals(tableName)) {
        // Mixed tables - fall back to individual procedures
        LOG.debug("Mixed tables in region list, using individual procedures");
        return createAssignProcedures(regions);
      }
    }
    LOG.info("Using BulkAssignProcedure for {} regions of table {}", 
        regions.size(), tableName);
    return new Procedure[] { new BulkAssignProcedure(tableName, regions) };
  }
  
  return createAssignProcedures(regions);
}
```

### Integration with EnableTableProcedure

Modify `EnableTableProcedure.executeFromState()` in the `ENABLE_TABLE_MARK_REGIONS_ONLINE` state:

The `EnableTableProcedure` is the most immediate beneficiary of BAP integration because table enable operations directly involve assigning all of a table's regions—exactly the workload BAP is designed to optimize. This modification replaces the existing assignment logic with a single call to the optimized method, providing automatic BAP usage for tables with region counts exceeding the configured threshold.

The modification occurs in the `ENABLE_TABLE_MARK_REGIONS_ONLINE` state, which is responsible for transitioning all table regions from offline to online. The existing code path creates individual `TransitRegionStateProcedure` instances for each region, which works correctly but creates significant procedure overhead for large tables. A table with 10,000 regions would generate 10,000 child procedures, each requiring its own procedure state serialization, scheduler queue entry, and execution tracking.

The change is minimal: replace the call to `createAssignProcedures()` with `createAssignProceduresOptimized()`. Because both methods return `Procedure<MasterProcedureEnv>[]`, the existing `addChildProcedure()` call works unchanged. The optimization is selected transparently based on configuration—operators can tune the threshold without code changes, and tables below the threshold continue using the proven individual-TRSP path.

The existing replica handling code (not shown in the snippet) must be preserved. Region replication adds complexity because replica regions need assignment to different servers than their primary. The optimized method receives the complete list including replicas; BAP's assignment plan creation will handle replica placement through the load balancer's existing replica-aware logic.

After adding child procedures (whether one BAP or many TRSPs), the state machine transitions to `ENABLE_TABLE_SET_ENABLED_TABLE_STATE`. This transition occurs immediately—the parent `EnableTableProcedure` does not wait inline for assignments to complete. Instead, the procedure framework automatically suspends the parent until all child procedures finish, then resumes execution in the next state. This child-procedure-wait semantic is fundamental to HBase's procedure framework and requires no special handling by this integration.

```java
case ENABLE_TABLE_MARK_REGIONS_ONLINE:
  TableDescriptor tableDescriptor = 
      env.getMasterServices().getTableDescriptors().get(tableName);
  int configuredReplicaCount = tableDescriptor.getRegionReplication();
  List<RegionInfo> regionsOfTable = 
      env.getAssignmentManager().getRegionStates()
          .getRegionsOfTableForEnabling(tableName);
  
  // ... existing replica handling code ...
  
  // Use optimized assignment - will use BAP if region count exceeds threshold
  addChildProcedure(
      env.getAssignmentManager().createAssignProceduresOptimized(regionsOfTable));
  setNextState(EnableTableState.ENABLE_TABLE_SET_ENABLED_TABLE_STATE);
  break;
```

### Serialization

Override `serializeStateData` and `deserializeStateData`:

The serialization is intentionally minimal, persisting only the essential immutable data:
- `tableName`: The table being processed
- `regionsToAssign`: The complete original list of regions (as `region_info`)

All other state is transient and rebuilt on recovery based on current cluster state:
- `pendingRegions`: Rebuilt by filtering `regionsToAssign` against current region states
- `failedRegions`: Starts empty on recovery
- `assignmentPlan`: Regenerated from current server list and region localities
- `regionRetryCount`: Reset to zero on recovery
- `batchSize`, `batchSizeMax`, `batchBackoffMillis`, `maxRetryPerRegion`: Reloaded from configuration in `CREATE_PLAN`
- `regionsAssigned`, `batchesProcessed`: Reset on recovery
- `retryCounter`: Starts as `null`, recreated lazily when needed

This minimal persistence approach is deliberate: when a Master restarts (due to crash, failover, or maintenance), the cluster state has likely changed significantly. Regenerating state from current conditions produces better assignment decisions than restoring stale state from before the restart.

```java
@Override
protected void serializeStateData(ProcedureStateSerializer serializer) 
    throws IOException {
  super.serializeStateData(serializer);
  
  BulkAssignStateData.Builder builder = BulkAssignStateData.newBuilder()
      .setTableName(ProtobufUtil.toProtoTableName(tableName));
  
  // Serialize original regions to assign - the only state we persist
  for (RegionInfo region : regionsToAssign) {
    builder.addRegionInfo(ProtobufUtil.toRegionInfo(region));
  }
  
  serializer.serialize(builder.build());
}

@Override
protected void deserializeStateData(ProcedureStateSerializer serializer) 
    throws IOException {
  super.deserializeStateData(serializer);
  
  BulkAssignStateData data = serializer.deserialize(BulkAssignStateData.class);
  tableName = ProtobufUtil.toTableName(data.getTableName());
  
  // Deserialize original regions - this is our source of truth
  regionsToAssign = new ArrayList<>();
  for (HBaseProtos.RegionInfo ri : data.getRegionInfoList()) {
    regionsToAssign.add(ProtobufUtil.toRegionInfo(ri));
  }
  
  // Initialize transient state - will be rebuilt in BULK_ASSIGN_CREATE_PLAN
  // pendingRegions = null signals recovery to executeFromState()
  pendingRegions = null;
  failedRegions = new ArrayList<>();
  assignmentPlan = null;
  regionToServerMap = null;  // Reverse index rebuilt with assignment plan
  regionRetryCount = new HashMap<>();
  currentBatchRegions = new ArrayList<>();
  batchSize = 1;  // Will be reinitialized from config in CREATE_PLAN (via initializeBatchSize)
  regionsAssigned = 0;
  batchesProcessed = 0;
  batchConfirmed = false;  // Idempotency flag for CONFIRM_BATCH
  retryCounter = null;  // Recreated lazily when exponential backoff is needed
}
```

### Recovery Semantics

On procedure recovery after a Master restart, the procedure framework restores BAP from the procedure store and calls `deserializeStateData()`. The procedure then resumes execution, but the state machine handles recovery specially.

The recovery detection happens at the start of `executeFromState()`:

```java
@Override
protected Flow executeFromState(MasterProcedureEnv env, BulkAssignState state) 
    throws ProcedureSuspendedException {
  
  // Recovery detection: if pendingRegions is null, we're recovering from a restart
  // Force transition to CREATE_PLAN to rebuild all transient state
  if (pendingRegions == null && state != BulkAssignState.BULK_ASSIGN_PREPARE) {
    LOG.info("Detected recovery for BulkAssignProcedure on table {}, "
        + "rebuilding assignment plan from current cluster state", tableName);
    setNextState(BulkAssignState.BULK_ASSIGN_CREATE_PLAN);
    return Flow.HAS_MORE_STATE;
  }
  
  switch (state) {
    // ... state handling ...
  }
}
```

Recovery behavior details:

1. **State Reset on Recovery**: Regardless of which state the procedure was in when the Master died, on recovery the procedure effectively restarts from `BULK_ASSIGN_CREATE_PLAN`. This is implemented by checking if `pendingRegions` is null (indicating recovery) and transitioning to the plan creation state.

2. **Filtering Already-Assigned Regions**: In `BULK_ASSIGN_CREATE_PLAN`, the procedure rebuilds `pendingRegions` by examining the current state of each region in `regionsToAssign`. Regions that were successfully assigned before the restart will now be in `OPEN` state and are skipped. This prevents duplicate assignment attempts.

3. **Fresh Assignment Plan**: The assignment plan is regenerated based on the current set of available RegionServers. This ensures regions are assigned to servers that are actually online, not to servers that may have died during the Master outage.

4. **Child TRSP Handling**: Child TRSPs that were in-flight when the Master died are handled by the procedure framework's normal recovery mechanism. They will either:
   - Complete successfully (region transitions to OPEN, and BAP's filtering will skip it)
   - Be rolled back and the region returned to an assignable state (BAP will retry it)
   - Remain attached to the `RegionStateNode` (BAP's filtering will skip it as "already has procedure")

5. **Clean Retry Counts**: Retry counts reset to zero on recovery. This is acceptable because the recovery itself represents a significant change in cluster conditions that may resolve previous transient failures.

This recovery approach prioritizes correctness and adaptability over preserving exact pre-restart progress. The trade-off is that some work may be redone (re-evaluating regions, regenerating the plan), but the benefit is that the procedure always operates on current, accurate cluster state rather than potentially stale persisted state.

### State Idempotency

Each state in BAP is designed to be **idempotent for re-entry**. Re-entry can occur due to:
1. **Master restart/failover**: Procedure is recovered from the procedure store
2. **Procedure suspension**: State throws `ProcedureSuspendedException` (e.g., for backoff) and resumes in the same state
3. **Scheduler re-execution**: The procedure framework may re-execute a state under certain conditions

The following table summarizes the idempotency guarantees for each state:

| State | Re-entry Safety | Mechanism |
|-------|-----------------|-----------|
| `BULK_ASSIGN_PREPARE` | Safe | Only executed once; recovery forces transition to `CREATE_PLAN` |
| `BULK_ASSIGN_CREATE_PLAN` | Safe | Rebuilds all transient state from current cluster conditions; regions already OPEN or with attached procedures are filtered out |
| `BULK_ASSIGN_DISPATCH_BATCH` | Safe | Regions that cannot be scheduled are returned to `pendingRegions`; `tryAssignRegion()` atomically checks for existing procedures before creating TRSPs |
| `BULK_ASSIGN_CONFIRM_BATCH` | Safe | `batchConfirmed` flag prevents double-processing of results; state transition set before suspension |
| `BULK_ASSIGN_POST_OPERATION` | Safe | Read-only logging and cleanup; no side effects |

**Key Idempotency Patterns Used**:

1. **Check-before-act with locks**: `tryAssignRegion()` holds the `RegionStateNode` lock while checking for existing procedures and creating the TRSP. This atomic pattern prevents duplicate procedure attachment.

2. **Confirmation flags**: The `batchConfirmed` flag ensures batch results are processed exactly once, even if the state is re-entered after suspension.

3. **State filtering on re-entry**: `createAssignmentPlan()` always examines current region states, automatically skipping regions that were assigned before a restart or by other procedures.

4. **State transition before suspension**: When throwing `ProcedureSuspendedException`, the next state is set BEFORE the throw to ensure the procedure advances correctly on resume.

5. **Return-to-pending for unschedulable regions**: Regions that cannot be scheduled (due to temporary conditions) are returned to `pendingRegions` rather than being lost.

### Batch Dispatch with Procedure Suspension

Following the RTRP pattern for handling cases where regions cannot be scheduled:

The `dispatchBatch()` method is the core execution loop that creates child TRSPs for a batch of regions. Beyond the straightforward case of successfully scheduling regions, this method must handle the scenario where no regions in the current batch can be scheduled—a situation that requires careful handling to avoid busy-waiting or procedure starvation.

The method calls `getNextBatch()` to obtain the next set of regions to process. For each region in the batch, it calls `tryAssignRegion()` which atomically validates the region state, creates the TRSP, and attaches it to the `RegionStateNode` (all while holding the region lock). If `tryAssignRegion()` returns a non-null TRSP, that procedure is added as a child via `addChildProcedure()`. The method tracks whether at least one region was successfully scheduled using the `anyScheduled` flag.

When no regions could be scheduled but the pending list is non-empty, the procedure faces a dilemma. Simply transitioning to the confirm state would be wasteful—there are no children to wait for. Looping immediately back to dispatch would risk busy-waiting if all regions remain unschedulable. The correct behavior is to suspend the procedure with exponential backoff, allowing time for the blocking conditions to resolve.

The `ProcedureSuspendedException` mechanism is the procedure framework's standard way to pause execution. When thrown, the procedure is placed in a suspended state and will be re-woken after the specified backoff interval. The `RetryCounter` provides exponential backoff with jitter, preventing thundering herd problems if many procedures are suspended simultaneously. The retry counter is created lazily on first suspension and reused across subsequent suspensions for proper backoff progression.

The backoff interval starts small (allowing quick recovery from transient blocking) but grows exponentially on repeated failures (preventing resource waste when conditions persist). When scheduling finally succeeds, the retry counter is reset to null so that future suspensions start fresh with the minimum backoff—a successful dispatch indicates conditions have improved.

Logging at INFO level provides operational visibility into suspension events. The log message includes the table name, pending region count, and backoff duration, giving operators the information needed to diagnose why bulk assignment is progressing slowly. Persistent suspension with growing backoff intervals indicates a systemic issue requiring investigation.

After a successful batch dispatch (at least one region scheduled), the method transitions to `BULK_ASSIGN_CONFIRM_BATCH` to wait for child TRSPs to complete. The `Flow.HAS_MORE_STATE` return indicates the state machine has more work to do after children complete.

**Idempotency Design**:
- Regions that cannot be scheduled (e.g., already have a procedure attached, in transitional state) are returned to `pendingRegions` for retry in a future batch.
- The `batchConfirmed` flag (from CONFIRM_BATCH) is reset when starting a new batch dispatch.
- State transition is set BEFORE suspension to ensure correct state on resume.

```java
private Flow dispatchBatch(MasterProcedureEnv env) 
    throws ProcedureSuspendedException {
  
  // Reset confirmation flag for the new batch
  batchConfirmed = false;
  
  List<RegionInfo> currentBatch = getNextBatch();
  boolean anyScheduled = false;
  
  for (RegionInfo region : currentBatch) {
    // Get target server from reverse index (may be null for balancer fallback)
    ServerName targetServer = getTargetServerForRegion(region);
    
    // Atomically validate, create TRSP, and attach to RegionStateNode
    TransitRegionStateProcedure trsp = tryAssignRegion(env, region, targetServer);
    if (trsp != null) {
      addChildProcedure(trsp);
      currentBatchRegions.add(region);  // Track for confirmation
      anyScheduled = true;
    } else {
      // IDEMPOTENCY: Region could not be scheduled in this batch.
      // Return it to pendingRegions for retry in a future batch.
      // This handles cases where the region temporarily has a procedure
      // attached or is in a transitional state.
      pendingRegions.add(region);
    }
  }
  
  if (!anyScheduled && !pendingRegions.isEmpty()) {
    // No regions could be scheduled in this batch, but we have more pending
    // Stay in DISPATCH_BATCH state and back off before retrying
    if (retryCounter == null) {
      retryCounter = ProcedureUtil.createRetryCounter(
          env.getMasterConfiguration());
    }
    long backoffMillis = retryCounter.getBackoffTimeAndIncrementAttempts();
    LOG.info("No regions schedulable for table {}, {} pending. "
        + "Backing off {}ms", tableName, pendingRegions.size(), backoffMillis);
    // Note: We do NOT call setNextState() here - we want to retry DISPATCH_BATCH
    setBackoffState(backoffMillis);
    throw new ProcedureSuspendedException();
  }
  
  if (anyScheduled) {
    retryCounter = null;  // Reset on successful scheduling
  }
  
  setNextState(BulkAssignState.BULK_ASSIGN_CONFIRM_BATCH);
  return Flow.HAS_MORE_STATE;
}
```

## File Changes Summary

### New Files

| File | Description |
|------|-------------|
| `hbase-server/src/main/java/org/apache/hadoop/hbase/master/procedure/BulkAssignProcedure.java` | Main procedure implementation |
| `hbase-server/src/test/java/org/apache/hadoop/hbase/master/procedure/TestBulkAssignProcedure.java` | Unit tests |
| `hbase-server/src/test/java/org/apache/hadoop/hbase/master/procedure/TestBulkAssignProcedureRecovery.java` | Recovery/double-execution tests |

### Modified Files

| File | Change |
|------|--------|
| `hbase-protocol-shaded/src/main/protobuf/server/master/MasterProcedure.proto` | Add `BulkAssignState` enum and `BulkAssignStateData` message |
| `hbase-server/src/main/java/org/apache/hadoop/hbase/master/assignment/AssignmentManager.java` | Add `createAssignProceduresOptimized()` method |
| `hbase-server/src/main/java/org/apache/hadoop/hbase/master/procedure/EnableTableProcedure.java` | Use `createAssignProceduresOptimized()` |

## Testing Strategy

### Unit Tests

1. **State Machine Tests**
   - Test each state transition
   - Verify correct handling of empty region list
   - Verify correct handling of single-region case
   - Verify batch size progression

2. **Region Filtering Tests**
   - Verify split parents are excluded
   - Verify regions with existing procedures are skipped
   - Verify regions in transitional states are skipped

3. **Assignment Plan Tests**
   - Verify `retainAssignment` is used when previous location available
   - Verify `roundRobinAssignment` fallback
   - Verify handling of balancer failures

4. **Error Handling Tests**
   - Verify retry logic for failed regions
   - Verify max retry limit enforcement
   - Verify procedure completes even with some failed regions

5. **State Idempotency Tests**
   - Verify `DISPATCH_BATCH` re-entry does not lose unschedulable regions
   - Verify `CONFIRM_BATCH` re-entry does not double-count `batchesProcessed`
   - Verify `CONFIRM_BATCH` re-entry does not double-call `handleFailedAssignment()`
   - Verify `CONFIRM_BATCH` suspension with backoff transitions to `DISPATCH_BATCH` on resume
   - Verify `DISPATCH_BATCH` suspension without scheduling stays in `DISPATCH_BATCH` on resume
   - Verify `batchConfirmed` flag is reset when entering `DISPATCH_BATCH`

### Acceptance Criteria

Per the parent design document:
- Unit tests for the BulkAssignProcedure state machine
- Unit tests for the new logic in the AssignmentManager

## Out-of-Scope Items Identified

The following items are identified as potentially needed but out of scope for this task:

1. **RIT Dashboard Updates**: If the RIT dashboard needs specific updates to show BAP-related information distinctly from regular assignment, that would be a separate UI task.

2. **HBCK2 Integration**: Adding HBCK2 commands to interact with or inspect BAP state is out of scope.

3. **CreateTableProcedure Integration**: While the design mentions pre-split tables, modifying `CreateTableProcedure` to use BAP is deferred. The `AssignmentManager.createAssignProceduresOptimized()` method will be available for future integration.

4. **Quota/Rate Limiting**: If cluster-wide rate limiting for region assignments is desired beyond the per-procedure batch throttling, that would be a separate feature.
