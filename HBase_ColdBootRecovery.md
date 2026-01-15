# HBase Rapid Cold Boot and Recovery

**Started Thursday, June 19 by Andrew Purtell**

## Summary

This proposal introduces the "Big Red Button" (BRB) workflow for HBase versions 2 and later, addressing a significant regression in recovery time during large-scale outages. Unlike HBase 1's bulk cold start capabilities, HBase 2's granular procedure-based recovery can lead to an unacceptably long time to recovery.

The BRB workflow provides a safe, operator-driven mechanism to bypass HBase 2's granular procedure-based recovery and replace it with an optimized cold start recovery mechanism.

## Motivation

HBase 2's procedure-based recovery framework provides robust, stateful recovery for individual server failures. Procedures like ServerCrashProcedure (SCP) are persisted and retried until completion, ensuring that no recovery work is lost even across Master restarts. This design greatly improves resilience for isolated failures. However, during a wide-scale outage, such as a prolonged network partition, a cloud provider availability zone failure, or a cascading failure induced by a bug, this same durability becomes a liability. A large number of RegionServers failing simultaneously can queue thousands of SCPs and their associated sub-procedures on the Master. When the cluster is brought back online, the Master spends a significant amount of time processing this massive backlog. Many of these procedures may be redundant or destined to fail and retry repeatedly, delaying the assignment of regions and extending the Time-To-Recovery from minutes to potentially several hours.

This behavior is a regression from HBase 1's simpler cold start model, where a full cluster restart would scan for all WALs, split them in parallel, and then assign all regions in bulk. While less safe for partial failures, this model was highly effective for full-cluster recovery.

This proposal outlines a "Big Red Button" (BRB) workflow: a safe, operator-driven, and supported mechanism to perform a cluster cold start in HBase versions 2 and later. The Big Red Button works in concert with new BulkRecoveryProcedure and BulkAssignProcedure procedures to rapidly bring an HBase cluster back online after a major incident. This feature will allow operators to bypass the procedure backlog in catastrophic failure scenarios, clearing the cluster state and prioritizing only the essential recovery work to dramatically reduce TTR and restore service quickly.

Features of this proposal also significantly and independently optimize Master functionality for normal operations:

- Eliminating most if not all duplicate SCPs that might be scheduled when Masters are repeatedly failed over during an incident.
- Optimizing bulk assignment for e.g. enable/disable, or creation of pre-split tables.

## Background

To understand the problem, let us review the current server failure recovery process in HBase 2:

1. The ServerManager on the active Master detects a RegionServer's Zookeeper session has expired.
2. The ServerManager calls `expireServer()`, which transitions the server's in-memory state and queues a ServerCrashProcedure (SCP) for the failed server.
3. The ProcedureExecutor picks up and runs the SCP. The SCP orchestrates the entire recovery for that single server, including:
   - **WAL Splitting:** If configured, the SCP determines which WALs need to be split. It does this by creating and submitting one SplitWALProcedure for each WAL file associated with the crashed server. These become child procedures of the SCP.
   - **Region Reassignment:** The SCP identifies all regions that were hosted on the crashed server and creates and submits a TransitRegionStateProcedure (TRSP) for each one to reassign it to a healthy RegionServer.

This model is effective for one-off failures. However, in a 1,000-node cluster where a network event causes 500 nodes to be declared dead, the Master will queue 500 SCPs. If each server had 2 WALs and 1,000 regions, this would result in:

- 500 root ServerCrashProcedures.
- 1,000 child SplitWALProcedures.
- 500,000 child TransitRegionStateProcedures.

This massive procedure backlog, coupled with retries and timeouts, creates a processing storm that prevents the cluster from recovering in a timely manner.

There is a further complication here where, should a Master failover happen during the processing of the procedure backlog, the newly active server will rescan the WAL directory and any unprocessed WALs found will result in scheduling of duplicate ServerCrashProcedures and their child SplitWALProcedures and TransitRegionStateProcedures. When there are multiple Master failovers during an incident this can deepen the backlog significantly.

The current common operational hack of manually deleting the procedure store from HDFS in order to bypass the procedure backlog, and subsequent execution of HBCK2 assigns commands to restore the cluster from assignment first principles, is dangerous, manual, tedious, error prone, and can leave the cluster in an inconsistent state, requiring further manual intervention.

## Proposed Solution

Consider a "Big Red Button" (BRB) workflow, implemented as a new command-line tool and a corresponding startup mode for the HMaster. This workflow provides a controlled and safe way to reset the cluster's state and perform a fast, parallelized cold start.

The core principle is to transform the state of the cluster from a complex, unknown post-failure state into a simple, known pre-startup state.

This is achieved by an operator-driven tool that runs while the HBase cluster is fully offline. HDFS must be online and healthy enough to support reading and writing HFiles. ZooKeeper availability is not strictly necessary but is desirable to enable pre-flight safety checks. Healthy and online HDFS and ZooKeeper services are ultimately required for a successful HBase level restart.

## The "Big Red Button" Workflow

### Acknowledge Catastrophic Failure

The operator makes the executive decision that a partial, rolling recovery is not feasible and a full cluster restart is the fastest path to recovery.

### Stop the Cluster

The operator ensures all Master and RegionServer processes are stopped.

### Run the BRB Tool

The operator executes a new tool, `HBaseColdStartTool`, which performs the following actions, in sequence:

1. **Safety Checks:** Verifies that no active Master or RegionServer znodes are present in Zookeeper. (A `--force` flag will be available to bypass this check for advanced use cases where ZK may be inconsistent.)

2. **Reset Procedure Store:** The tool will load the HFiles of the Master's local region, which includes the RegionProcedureStore, and will write new HFiles that clear all existing procedures. It will reset the max procedure ID and take all other necessary measures to ensure correctness upon reload of the RegionProcedureStore. The tool will write new HFiles rather than rewrite so rollback from this action is possible by deleting the newly written HFiles.

3. **Reset Region States:** The tool will load the HFiles of the META table and write new HFiles that add updates for every region's state, setting them to OFFLINE. This ensures the Master knows it needs to assign every region upon startup. The tool will write new HFiles rather than rewrite so rollback from this action is possible by deleting the newly written HFiles.

4. **Scan WALs and Schedule SCP and Assignment Work:**
   - The tool scans the WAL directories (`/hbase/WALs/`) for all servers.
   - For each "dead server" discovered during the WAL file scan, the tool creates and prepares exactly one ServerCrashProcedure.
   - For each table discovered during the read of HFiles for META, the tool will then prepare a BulkAssignProcedure to assign all of the table's regions. This is the key detail that eliminates the extreme fan-out imposed by representing bulk assignment work as individual TRSPs.
   - The tool then writes new HFiles rather than rewrite so rollback from this action is possible by deleting the newly written HFiles. The tool serializes a new BulkRecoveryProcedure which includes all of the ServerCrashProcedure and BulkAssignProcedure instances to execute as children.

### Start the Cluster

The operator starts all of the regionservers in the cluster. This ensures all of the regionservers are available for accepting SplitWALProcedure tasks when the Master begins its cluster bootstrap process.

### Master Startup

The operator starts one or more Masters. One Master assumes the active role.

- The active Master, during its initialization, with the changes proposed below, functions normally given either the normal startup flow and in the new cold boot flow. The key detail is duplicate SCPs are not scheduled for any dead servers discovered during a scan of the WAL directory when recovery work for a given dead server is already scheduled.
- It proceeds to execute the procedures found in the store, which will be only one, the BulkRecoveryProcedure.
- The BulkRecoveryProcedure orchestrates the execution of the optimal number of ServerCrashProcedures, which will manage WAL splitting.
- The Master's SplitWALManager dispatches WAL splitting tasks to the available RegionServers, achieving a fast, parallel WAL recovery.
- The BulkRecoveryProcedure waits until all SCPs are complete and thus all splitting work is completed.
- The BulkRecoveryProcedure then executes all of the BulkAssignProcedures in parallel as children. The BAPs optimize the creation and management of TRSPs for a potentially large set of regions, minimizing procedure fan-out as much as possible.

### Online

The cluster is now fully online.

## High-Level Architectural Changes

This solution leverages existing frameworks and components, minimizing invasive changes while providing a powerful new capability.

- **HBaseColdStartTool:** A new command-line tool that orchestrates the offline state reset. This tool will be the primary interface for the operator.
- **HMaster Startup Change:** A modification to the Master startup flow avoids scheduling duplicative recovery work if SCPs are already scheduled. This also benefits normal HBase operations.
- **BulkRecoveryProcedure:** A new root procedure for orchestrating and optimizing the splitting and assignment activity specifically during the cold start scenario.
- **BulkAssignProcedure:** A new procedure designed to efficiently assign a large number of offline regions. It orchestrates the bulk assignment by creating and managing child TRSP procedures in throttled, manageable batches. This approach provides a very substantial reduction in fan-out of the tree of procedures required to completely assign a table's regions. This also benefits any bulk assignment case in normal operation, such as enable/disable, or creation of a pre-split table.

## Implementation Details

### HBaseColdStartTool

This will be a new class extending `AbstractHBaseTool`.

**Command:** `hbase org.apache.hadoop.hbase.master.HBaseColdStartTool --reset`

**Options:**

- `--reset`: Performs the full cold-start preparation.
- `--force`: Bypasses the check for a live cluster. Dangerous, for expert use only.
- `--wal-dir <path>`: Optional override for the WAL directory path.
- `--root-dir <path>`: Optional override for the HBase root directory.

### Workflow

**Prerequisite:** HDFS must be available and healthy enough to read and write HFiles.

#### 1. Safety Check

Connect to Zookeeper using the provided configuration. Check for the presence of znodes under `/hbase/rs` and `/hbase/master`. If any exist and `--force` is not specified, abort with an error message stating the cluster appears to be live.

The `--force` option also allows the tool to continue even if ZooKeeper is not available.

#### 2. Procedure Store Reset

The tool will directly write HFiles into the store for the Master's local region, which contains the ProcedureStateStore data. It will add delete markers that remove all rows from the proc column family.

To maintain procedure ID continuity, it will read the max procedure ID before the clear, and write a "marker" row with that ID back into the store. This prevents procedure ID reuse, which could cause issues with stale client information.

#### 3. Region State Reset (META update)

The tool will read all of the HFiles of the META table.

The tool will write new HFiles into the store for the META table. For each row, it will store a cell that updates the state of the entry (in the `info:state` column) to OFFLINE.

#### 4. WAL Scan and Procedure Scheduling

The tool will list all server directories under the WAL root (`/hbase/WALs`).

- For each "dead server" discovered during WAL scanning, it will instantiate exactly one ServerCrashProcedure.
- For each table discovered during a read of the HFiles for META, a BulkAssignProcedure will be instantiated.
- A single BulkRecoveryProcedure will be instantiated, with the ServerCrashProcedures and BulkAssignProcedures as its children.

The tool will then serialize and write these new procedures directly into the Master's local region storage as new HFiles.

### HMaster Startup Improvement (Avoid unnecessary/duplicate SCPs)

During a normal Master startup, the RegionServerTracker identifies "dead" servers by finding WAL directories for servers that are not online. Its default behavior is to immediately schedule a SCP for each of these servers. Instead of blindly scheduling new SCPs, as part of this proposal, we will refactor this code such that the Master will then first check if recovery work for a given dead server has already been scheduled. This approach also enhances the efficiency of normal Master failovers. By preventing the Master from scheduling duplicate SCPs for servers for which SCPs have already been queued, we eliminate a known source of inefficiency during standard recovery operations. This is the behavior that operators currently experience when multiple rapid Master failovers cause the scheduling of redundant and unnecessary SCPs.

The refactored code will perform the existing logic of identifying dead servers:

```
candidateDeadServers = serversInWALs - serversOnlineInZK
```

However, instead of immediately calling `expireServer()` on them, it will cross-check a Set of ServerNames with any already scheduled ServerCrashProcedures. This can be done efficiently with a stream and filter operation:

```java
// Collect all servers that already have active recovery procedures.
Set<ServerName> serversWithRecoveryInProgress =
  procExec.getActiveProceduresNoCopy().stream()
  .filter(p -> !p.isFinished())
  .filter(p -> p instanceof ServerCrashProcedure)
  .map(p -> ((ServerCrashProcedure) p).getServerName())
  .collect(Collectors.toSet());

// Determine the set of servers that are dead but have no recovery scheduled.
Set<ServerName> serversToProcess = candidateDeadServers.stream()
  .filter(server -> !serversWithRecoveryInProgress.contains(server))
  .collect(Collectors.toSet());

// Log what we found and what we are skipping.
if (LOG.isInfoEnabled()) {
  Set<ServerName> serversSkipped = new HashSet<>(candidateDeadServers);
  serversSkipped.retainAll(serversWithRecoveryInProgress);
  if (!serversSkipped.isEmpty()) {
    LOG.info(...);
  }
}

// Schedule new ServerCrashProcedures.
// In the cold boot case, this will be empty.
// In normal operation, we save any potential duplicate work but may well have
// dead servers to process, so we schedule SCPs as normal.
// This works for both cases!
if (!serversToProcess.isEmpty()) {
  for (ServerName serverToProcess : serversToProcess) {
    LOG.info(...);
    server.getServerManager().expireServer(serverToProcess);
  }
}
```

If a match is not found the refactored code will proceed to call `serverManager.expireServer(candidateServerName)`, which will schedule a new SCP as it does today.

This should benefit normal operations too, preventing unnecessary duplicative scheduling of recovery work for a "dead server" that already has recovery work in queue. The cost of streaming through the active procedure list at startup is negligible compared to the cost of scheduling, executing, and eventually aborting hundreds or even thousands of redundant procedures. This is a clear performance win for large-scale recovery scenarios.

Let's double check this by walking step by step through the key cases:

#### Normal Master Failover Scenario

1. A few servers crash. The active Master schedules SCPs for them.
2. The Master itself then fails and restarts.
3. The new Master starts up and runs `findDeadServersAndProcess()`. It identifies the same crashed servers from their WAL directories.
4. It then queries the ProcedureExecutor, which has already loaded the previously scheduled (and still active) SCPs from the procedure store.
5. The `serversWithRecoveryInProgress` set will contain the crashed servers.
6. The `serversToProcess` set will be empty.

The Master correctly resumes the existing recovery procedures. No duplicate SCPs are scheduled.

#### "Big Red Button" (BRB) Cold Start Scenario

1. The operator runs the HBaseColdStartTool. The tool clears the procedure store and populates it only with the optimal set of ServerCrashProcedures.
2. The Master starts up and follows its normal startup path.
3. It runs `findDeadServersAndProcess()`. It identifies all servers with WAL directories as candidate dead servers.
4. It then queries the ProcedureExecutor, which has loaded the ServerCrashProcedures created by the tool.
5. The `serversWithRecoveryInProgress` set will contain every server that has a WAL to be split.
6. The `serversToProcess` set will be empty.

The Master proceeds to execute the already-queued SplitWALProcedures. No duplicate SCPs are scheduled.

After they complete, it will move on to execute the BulkAssignProcedures, assigning all regions, which are known to be offline.

#### Concurrent Master Crash

1. One or more servers crash. The active Master does not have time to schedule SCPs for them before it crashes too.
2. The new Master starts up and runs `findDeadServersAndProcess()`. It identifies the crashed servers from their WAL directories.
3. It then queries the ProcedureExecutor, which has no recovery operations scheduled for the crashed servers.
4. The `serversWithRecoveryInProgress` set will be empty.
5. The `serversToProcess` set will contain the crashed servers.
6. SCPs will be scheduled to process the `serversToProcess` set.

The Master correctly schedules recovery procedures for crashed servers.

#### Considering the Race Between SCP Execution and findDeadServers

Consider: `finishActiveMasterInitialization()` is called. The ProcedureExecutor and its worker threads are started. It begins executing procedures loaded from the store. A SCP from a previous run or a BRB tool run is picked up by a worker thread and runs to completion very quickly. The Master's main initialization thread now calls `findDeadServersAndProcess()`. The code streams through `procExec.getActiveProceduresNoCopy()`. Because the recovery procedure from Step 3 has already finished, it is not included in this set. The `serversToProcess` set is calculated. It now includes the server from the just-completed procedure. The Master then proceeds to schedule a new, duplicate SCP for this server.

I believe it unlikely that a procedure with real work to do would complete quickly enough, but the race is possible.

In any case scheduling a duplicate SCP in this race condition scenario is harmless to the overall integrity and correctness of the cluster. The only cost is the overhead of scheduling and executing redundant procedures.

To completely close this race, we can consider the following, which is not necessary to implement this proposal, but may be a nice to have: We can hold up procedure execution during initialization until an explicit point when we want the ProcedureExecutor to start its workers. We would schedule procedures but hold up execution of them until after the `serversWithRecoveryInProgress` and `serversToProcess` lists have been collected. This would involve introducing a new state or a gate within the ProcedureExecutor:

- The ProcedureExecutor would start in a "initialization" mode.
- It would load all procedures from the store into its internal queues but would not start its worker threads yet.
- The Master initialization sequence would proceed to the `findDeadServersAndProcess` step and build its lists.
- After this step is complete, the Master would call a new method on the ProcedureExecutor, e.g. `startWorkers()`, which would then create and start the worker threads.

### BulkAssignProcedure

Simply splitting the WALs quickly is only half the battle. The subsequent assignment of perhaps many thousands of regions must also be fast and manageable. To understand the requirements, it's useful to remember how HBase 1 handled bulk assignment, why this approach was abandoned, and why the new assignment approach in HBase 2 currently can work against efficient and timely bulk assignment.

The HBase 1 AssignmentManager was largely stateless and RPC-driven. META and assignment state in ZooKeeper served as ground truth. For a bulk assignment (on startup or for a newly enabled table), it would calculate an assignment plan to map regions to live RegionServers and then iterate through this plan and send openRegion RPCs to the assigned RegionServers, in parallel batches. This was extremely fast for assigning a large number of regions at once because the Master's overhead was minimal. It was a fire-and-forget RPC storm. However, if the Master crashed mid-assignment, all in-flight progress was lost. The new Master would have to re-scan META and start the assignment process over for any regions that were not successfully opened. The state in ZooKeeper was the source of truth on intermediate assignment states, but synchronizing it was complex and prone to consistency bugs. The move to the procedure-based framework (Proc-V2) in HBase 2 was a direct response to these assignment durability and consistency problems.

HBase 2 fundamentally changed the assignment model. Every region state transition is now managed by its own durable, restartable, recoverable procedure, the TransitRegionStateProcedure (TRSP). Therefore, there is no direct equivalent to the HBase 1 bulk assign RPC flow. Instead, HBase 2's concept of "bulk assignment" is the submission of many individual TRSPs at once.

The ProcedureExecutor has a method that can be used to submit all the procedures for assigning a table's regions in a single, atomic WAL write to the RegionProcedureStore. When creating a table or enabling a table, the AssignmentManager's `createRoundRobinAssignProcedures` method is called. It uses the LoadBalancer to generate a plan and creates an array of TRSPs, one for each region. This array is then passed to `submitProcedures`. This is a significant optimization. While each region still has its own procedure, the initial persistence of all these procedures is batched into one write, reducing the overhead on the procedure store. Although submission is batched, the ProcedureExecutor still sees them as thousands of individual procedures to be scheduled, locked, and executed, which incurs significant scheduling and locking overhead on the Master.

Alternatively, the HBCK2 tool provides an `assigns` command that takes a list of encoded region names. An operator can drive this process using HBCK2. Internally, this command makes an RPC call to the `MasterRpcServices#assigns`, which loops through the regions and creates and submits an individual TRSP for each one. This is functionally equivalent to the above, but driven by an operator instead.

To make the Big Red Button cold start effective and to improve large-scale operations in general (like disabling and then re-enabling a very large table), we can consider a new bulk assignment capability implemented on top of the Proc-V2 framework.

Reusing existing, battle-tested code is a great engineering principle. While the existing `ReopenTableRegionsProcedure` (RTRP) contains useful patterns and logic that we can reuse, its core purpose and internal assumptions are fundamentally different from what is required for a bulk assign of offline regions. The primary goal of RTRP is to bounce (unassign and then assign) all regions of an already online table. This is typically scheduled after ModifyTableProcedure when regions must be reopened to pick up the changes (e.g., schema changes affecting store files, coprocessor updates, etc.). Reusing it directly would lead to a confusing, semantically incorrect, and likely buggy implementation.

Although RTRP itself is not fit for direct reuse, its design pattern is almost exactly what we need for a new BulkAssignProcedure. Its mechanism for controlling batch sizes and the backoff between batches is a reasonable way to manage large-scale operations without destabilizing the cluster. This should be copied and adapted. The state machine flow is also a good fit — GET_REGIONS → DISPATCH_BATCH → CONFIRM_BATCH_COMPLETED → (loop or finish) — being robust and allowing for clear progress tracking and recovery. Finally, the self-suspension of procedure execution when the cluster is too busy to accept a new batch is a good self-throttling pattern that can be replicated.

The new procedure BulkAssignProcedure is designed for assigning a large set of offline regions for a given table. It is parameterized with a TableName or a list of RegionInfo objects to assign. The master creates a BulkAssignProcedure (BAP) after determining that the number of OFFLINE regions for the given table exceeds some configurable threshold. In the cold boot scenario, a BAP is scheduled for every table on the cluster and they execute in parallel. The BAP takes a SHARED table lock, just like RTRP, to prevent schema changes but otherwise not impede other operations. (SHARED locks are necessary anyway when TRSPs will be child procedures.) This makes BAP also highly useful during normal operations when a table is re-enabled or when a significant number of regions for the table must be reassigned at once.

#### State Machine

1. **BULK_ASSIGN_PREPARE:** Initial sanity checks. Transition to BULK_ASSIGN_CREATE_PLAN.
2. **BULK_ASSIGN_CREATE_PLAN:** Call the LoadBalancer with the full list of regions to get an efficient, balanced assignment plan. This is more efficient than assigning regions one by one. Transition to BULK_ASSIGN_DISPATCH_BATCH.
3. **BULK_ASSIGN_DISPATCH_BATCH:** Take the next batch of regions from the plan. For each region in the batch, create a simple assign TRSP, and submit the TRSPs as child procedures. Transition to BULK_ASSIGN_CONFIRM_BATCH_ASSIGNED.
4. **BULK_ASSIGN_CONFIRM_BATCH_ASSIGNED:** Wait for all child procedures in the batch to complete. Check if there are more regions in the plan. If so, transition back to the BULK_ASSIGN_DISPATCH_BATCH state. If not, transition to BULK_ASSIGN_POST_OPERATION.
5. **BULK_ASSIGN_POST_OPERATION:** Perform any cleanup and finish.

This new procedure would be clean, semantically correct, and would reuse the best patterns from RTRP without inheriting its unsuitable assumptions.

### BulkRecoveryProcedure

In a normal, single-server failure, the SCP is the single parent procedure responsible for both splitting the WALs and reassigning the specific regions that were on the crashed server (`getRegionsOnCrashedServer`). This is possible because, when the SCP is created, the Master knows for certain the complete set of regions assigned to the given server. The SCP flow then insures the reassignment step (creating TRSP children) only happens after the WAL splitting step (waiting for child SplitWALProcedures) is complete within that same SCP. This enforces a strict, correct sequence.

For implementing the proposed HBaseColdStartTool we want to intentionally wipe the slate completely clean. The potentially complex state arrived at during partial or complete failure conditions is to be completely discarded so the cluster can start up in a completely known state regarding assignment. Therefore, the tool resets all region states in META to OFFLINE. Last known region assignment locations are technically retained in META's `info:server` field, but we must consider this information of highly dubious utility, because assignment likely was severely disturbed during such a major incident as to necessitate the BRB workflow.

Because we choose to clear all of the assignment state before the Master boots (and we choose to ignore the `info:server` information for the above described reason), now when the SCPs execute the Master cannot determine which regions were assigned a given server. SCPs continue to effectively drive and coordinate WAL splitting. This is the key behavior we preserve and leverage from them. But when the WAL splitting completes, in the absence of assignment state, no TRSPs will be scheduled by any SCP. For cold start we will not rely on SCP to drive assignments. Instead, we will rely on BulkAssignProcedures to drive assignments.

This presents a coordination challenge. BAPs know nothing of WAL splitting, and cannot know. BAP is not a replacement or alternative to SCP, it is by design only an assignment optimization. We must have some other means to ensure all WAL splitting is completed before region assignment begins. More specifically, we must ensure that no BulkAssignProcedures begin executing until all of the SplitWALProcedure child procedures of all the ServerCrashProcedures have completed. This is the last big design hurdle to clear.

We solve this problem in a simple and straightforward way by orchestrating the entire cold boot scenario with a single root level procedure, BulkRecoveryProcedure, which separates the stage of WAL splitting (via SCP execution) and bulk assignment (via BAP) into two disjoint phases. This ensures that at no time will we ever try to assign a region before WAL splitting work has been completed. This is critical to avoid potential data loss should a region be opened before a recovered.edits file is written into the region store by the WAL splitter.

BulkRecoveryProcedure is a new StateMachineProcedure that takes a list of ServerCrashProcedures to execute in the first phase and a list of BulkAssignProcedures to execute in the second phase.

#### State Machine

1. **BULK_RECOVERY_PREPARE:** Performs basic sanity checks. Transitions to BULK_RECOVERY_SPLIT_WALS.
2. **BULK_RECOVERY_SPLIT_WALS:** This state is responsible for executing all of the SCPs provided, as child procedures, as many in parallel as possible. `addChildProcedure()` ensures BulkRecoveryProcedure will automatically wait in this state until all its children (the SCPs) have completed. Transitions to BULK_RECOVERY_ASSIGN_REGIONS.
3. **BULK_RECOVERY_ASSIGN_REGIONS:** This state is responsible for executing all of the BAPs provided, as child procedures, as many in parallel as possible. `addChildProcedure()` ensures BulkRecoveryProcedure will automatically wait in this state until all its children (the BAPs) have completed. Transitions to BULK_RECOVERY_POST_OPERATION.
4. **BULK_RECOVERY_POST_OPERATION:** Final state for any cleanup.

BulkRecoveryProcedure is an implementation of the HBase 1 cold start assignment pattern within HBase 2+'s Proc-V2 framework.

## Risks and Mitigations

### Inappropriate Use

An operator might mistakenly run the BRB tool on a partially live or flapping cluster.

**Mitigation:** The tool will have strict safety checks that verify no servers are registered in Zookeeper. A `--force` flag, with strong warnings, will be required to override this.

### Tool Failure

The HBaseColdStartTool could fail midway (e.g., after clearing the procedure store but before scheduling WAL splits).

**Mitigation:** The tool will be designed to be idempotent. Re-running the tool will detect the state of completion and resume from where it left off. For example, if the procedure store is already empty, it will skip that step.

### Data Loss

This tool does not recover lost WAL files. If the underlying storage for WALs is compromised, data will be lost.

**Mitigation:** This is out of scope for the BRB. The tool's purpose is to accelerate recovery when all data and WAL files are intact. This will be clearly stated in the documentation.

### HDFS Failure

This tool requires that HDFS be sufficiently healthy to read and write HFiles.

**Mitigation:** This is out of scope for the BRB. The tool's purpose is to accelerate recovery when all data and WAL files are intact and HDFS is healthy enough to support HBase restart. This will be clearly stated in the documentation.

### WAL Splitting Issues

The proposal currently expects that WAL splitting successfully succeeds in all cases in a timely manner.

**Mitigation:** See Appendix "Handling WAL Splitting Failures"

### ZooKeeper Failure

The tool uses ZooKeeper to verify no servers are registered as being online. A `--force` flag, with strong warnings, can override this behavior and proceed without the checks.

**Mitigation:** The operator can use the force flag to bypass the safety checks, and then ZooKeeper access is not required and will not be attempted. However, the tool's purpose is to accelerate recovery when all data and WAL files are intact and HDFS and ZooKeeper, both of them critical dependencies, are healthy enough to support HBase restart.

## Appendix: Handling WAL Splitting Failures

The proposed BulkRecoveryProcedure provides a significant optimization by sequencing recovery work into two distinct phases: WAL splitting followed by region assignment. This is enforced by having the BRP wait for all its child ServerCrashProcedure instances to complete before it proceeds to execute its child BulkAssignProcedure instances. We introduce the risk that if even one SplitWALProcedure (a grandchild of the BRP) becomes stuck or fails repeatedly, it will prevent its parent SCP from completing. This, in turn, will cause the BRP to wait indefinitely in its BULK_RECOVERY_SPLIT_WALS state, blocking the assignment of all regions for all tables across the cluster. This scenario would defeat the primary goal of this proposal: rapid recovery. This scenario is no different than what would happen if WAL splitting on an unchanged/unpatched HBase cluster is stuck recovering the WAL for a system table like hbase:meta or hbase:namespace. The changes proposed here are generally useful as independent resilience improvements.

A SplitWALProcedure can fail for several reasons, most of which manifest as an IOException during the remote execution on a RegionServer. The WAL splitting process involves reading the source WAL file and writing recovered edits to new files under the appropriate region directories. Persistent read errors (e.g., block corruption on multiple DataNode replicas) or persistent write errors (e.g., NameNode unavailability, disk full errors on DataNodes, permission issues) will cause the split attempt to fail repeatedly. A WAL file may contain a malformed or corrupted entry that the parsing logic cannot process. Or, the SplitWALProcedure must acquire a worker from the SplitWALManager, and if all available RegionServers are busy, unhealthy, or stuck processing other problematic WALs, the procedure will be suspended, unable to make progress. Any of these scenarios can lead to a single SplitWALProcedure becoming effectively permanently stuck, thereby blocking the entire cluster from recovery. (This was also an issue in HBase 1 cold start.)

### Handling WAL Splitting Failures

We would handle pathological WAL splitting issues with a two pronged approach.

1. We would automatically quarantine the WAL and continue so as not to continue to block recovery, accepting the possibility of transient data loss, assuming the WAL can be ultimately recovered. Although, even if the data loss is permanent, the recovery trajectory for the cluster will be the sidelining and ultimate removal of the corrupt WAL. This is as true in operations today as it would be after implementation of this proposal.
2. We would provide the operator escape hatch tooling in HBCK2.

### WAL Split Quarantine

We would modify the SplitWALProcedure to take automated action after repeated failures. After a configurable number of failed attempts on different workers, the procedure would enter a new QUARANTINE_WAL state. In this state, it would move the problematic WAL file to a dedicated quarantine directory in HDFS (e.g., `/hbase/WALs/quarantine/`). After the move, the procedure would terminate with a SUCCESS status.

The default configuration can be set to very conservative values.

This unblocks recovery and allows the BRP to proceed, enabling the rest of the cluster to come online, while preserving data. The problematic WAL is not deleted. It is safely isolated for later offline analysis and manual data recovery by way of tools like WALPlayer. This is a default-safe, automated behavior that prioritizes cluster availability without discarding potentially valuable data. However, this does introduce a new operational concept (quarantine and its associated directory) that operators must monitor. The presence of any file in the quarantine directory should trigger a high priority alert.

### New HBCK2 `quarantine_wal` Command

We would introduce a new HBCK2 command, `quarantine_wal <proc_id>`, that an operator can use to target a stuck SplitWALProcedure. This command would immediately force transition the stuck WAL split procedure into the QUARANTINE_WAL state, and from there it would terminate with SUCCESS as described above. The parent SCP would then consider the child complete and proceed.

This provides ultimate control to an operator in an emergency. Perhaps the default configuration of the QUARANTINE_WAL capability is set to very conservative values yet the operator makes the executive decision that cluster availability should be immediately prioritized.

The drawback is there is a high risk of operator induced data loss if an operator bypasses a recoverable WAL. As with any HBCK2 command there must be clear and sufficient documentation of the risks in the runbook and a process for review of and layered approvals for any proposed HBCK2 command execution in production.

## Appendix: Requirements for 10-minute Recovery

The workflow begins with the operator ensuring all Master and RegionServer processes are stopped. The time taken for this manual step is not included in the 10-minute recovery, so for the entire workflow itself to meet the deadline, this prerequisite must be met quickly, but for initial delivery of this solution and its related performance testing the clock will start once operators have completed emergency shutdown.

The SplitWALManager dispatches WAL splitting tasks to the available RegionServers, achieving a fast, parallel WAL recovery. To meet a 10-minute goal, the parallelism must be very effective, with sufficient RegionServer resources to handle the workload concurrently.

The BulkAssignProcedure is designed to efficiently assign a large number of offline regions and provides a very substantial reduction in fan-out. For a 10-minute recovery, this procedure needs to optimally batch with minimal overhead, considering the potential for many thousands of regions requiring assignment. The Master must not become a bottleneck due to procedure scheduling, locking, or execution overhead.

We introduce WAL quarantine to prevent a single stuck SplitWALProcedure from blocking the entire recovery. For a 10-minute recovery, this automated quarantine mechanism must be enabled, effective, and timely.

### Recovery Time Objective (RTO)

The HBase cluster shall achieve full operational status (all regions assigned and serving reads/writes) within 10 minutes of initiating the 'Big Red Button' cold start workflow, assuming healthy HDFS and ZooKeeper.

Specific targets for WAL splitting throughput (e.g., X MB/second per RegionServer) and region assignment rates (e.g., Y regions/second per Master) must be defined based on typical cluster sizes to support the 10-minute RTO. Additionally, we may need to define sub-system latency targets for HDFS reads/writes, ZooKeeper operations, and inter-Master/RegionServer RPCs during recovery.

### Performance Testing

This section outlines the performance testing strategy to validate that the "Big Red Button" (BRB) workflow consistently achieves the RTO of 10 minutes for return to operational status after a major incident, while maintaining data consistency and system stability. It will focus on simulating large-scale outages and measuring the end-to-end recovery time.

#### Key Performance Indicators

- **RTO:** Total time from the invocation of HBaseColdStartTool until all regions are assigned and online. Target: <= 10 minutes.
- **WAL Splitting Throughput:** Rate at which WAL files are processed and recovered edits are written (MB/second per RegionServer).
- **Region Assignment Rate:** Rate at which regions are assigned and become online (regions/second)
- **Procedure Backlog:** Number of active or pending procedures on the Master.
- **Data Consistency:** Verification that no data loss or corruption occurred during recovery.

(Measurement of these KPIs with today's code will provide a baseline for comparison with implementation improvements and also aid in estimating level of effort.)

#### Additional Performance Indicators

- **Master CPU and Memory Utilization:** Resource consumption of the Master during recovery.
- **RegionServer CPU and Memory and Disk I/O Utilization:** Resource consumption of RegionServers during WAL splitting and region opening.
- **HDFS Latency and Throughput:** Performance of HDFS during WAL reads and recovered edits and HFile writes.

#### Test Environment

A dedicated test HBase cluster sufficiently scaled to approximate production scale. Consider at least 100 RegionServers, with approximately 1000 regions assigned per server.

### Acceptance Test Scenarios

#### 1. RegionServer Outage Simulating AZ Failure (Core Scenario)

Load the cluster with a significant amount of data and continuous write activity to generate a large WAL backlog.

Simultaneously bring down 33% (e.g. 33/100) of RegionServers.

The number of WALs to split and total number of regions to reassign will correspond to the number of deliberately crashed servers. The target range is 2-5 WALs/server and 1000 regions/server.

Quickly stop all Master and RegionServer processes (simulating the operator's "Stop the Cluster" step), before any significant recovery actions can be performed.

Execute `HBaseColdStartTool --reset`.

Start all RegionServers.

Start all Masters.

Monitor recovery progress, KPIs, and end-to-end recovery time.

**Success Criteria:**
- RTO <= 10 minutes
- No data loss
- All regions online

#### 2. Master Failover During Recovery (Core Scenario)

Introduce a Master crash and failover during the BulkRecoveryProcedure execution. The test configuration is the same as Scenario 1 for setting the initial conditions.

Initiate Scenario 1.

Mid-recovery gracefully or forcefully stop the active Master. Allow a standby Master to become active.

Monitor if the recovery process resumes correctly and completes within the RTO.

**Success Criteria:**
- RTO <= 10 minutes, inclusive of failover time
- No data loss
- All regions online
- No duplicate SCPs observed

#### 3. Pathological WAL Stuck Case (Stretch Goal)

Configure SplitWALProcedure to automatically quarantine after a low number of retries, for testing automated mitigation. Introduce one problematic WAL file that will cause a SplitWALProcedure to get stuck.

Initiate Scenario 1.

Deliberately corrupt one WAL file before restarting the Masters.

Confirm that the problematic WAL is quarantined successfully and if the overall recovery proceeds to completion. Monitor if the recovery process resumes correctly and completes within the RTO.

**Success Criteria:**
- RTO <= 10 minutes, inclusive of WAL quarantine time
- No data loss
- All regions online

## Appendix: Development Plan

### Task 1: HMaster Startup Improvement - Avoid Duplicate SCP Scheduling

This task will modify the HMaster startup process to prevent the scheduling of redundant ServerCrashProcedures (SCPs) for servers that already have an SCP in progress. This will improve the efficiency of normal master failovers.

**Owner:** Andrew Purtell

**Dependencies:** None

**Implementation Details:**
- Refactor the `findDeadServersAndProcess` method in HMaster.
- Introduce a mechanism to cross-check candidate dead servers with already running SCPs.

**Acceptance Criteria:**
- Unit tests demonstrating that duplicate SCPs are not scheduled during a master failover.
- Integration tests simulating a master crash and verifying the correct recovery behavior.

**Status:** COMPLETE (commit 630fdcdf2505b28efde9075ee3c557c1800c0f41)

---

### Task 2: HMaster Startup Improvement - Procedure Execution Hold

This task will introduce a mechanism to delay the execution of procedures during HMaster initialization until after the `findDeadServersAndProcess` step is complete. This will prevent race conditions between SCP execution and the identification of dead servers.

**Owner:** Andrew Purtell

**Dependencies:**
- Task 1

**Implementation Details:**
- Ensure the ProcedureExecutor implementation can support holding up the execution of procedures until triggered.
- Modify the HMaster initialization sequence to trigger the start of procedure execution at the appropriate time.

**Acceptance Criteria:**
- Unit tests to verify that procedures are not executed until the hold is released.

**Status:** COMPLETE (prerequisites already satisfied)

---

### Task 3: Implement BulkAssignProcedure (BAP)

This task involves creating the BulkAssignProcedure for efficiently assigning a large number of offline regions. This procedure will be a more efficient replacement for submitting many individual TransitRegionStateProcedures (TRSP).

**Owner:** Andrew Purtell

**Dependencies:** None

**Implementation Details:**
- Create a new StateMachineProcedure for bulk assignment.
- Implement the logic for batching region assignments and managing child TRSP procedures.
- Implement logic in the Assignment Manager for when BAP should be used instead of direct scheduling of TRSPs

**Acceptance Criteria:**
- Unit tests for the BulkAssignProcedure state machine.
- Unit tests for the new logic in the Assignment Manager.
- Integration tests demonstrating the successful assignment of a large number of regions using the BAP.

---

### Task 4: Implement WAL Split Quarantine

This task will add a quarantine mechanism for problematic WAL files. This will prevent a single failed WAL split from blocking the entire cluster recovery.

**Owner:** Andrew Purtell

**Dependencies:** None

**Implementation Details:**
- Modify the SplitWALProcedure to include a QUARANTINE_WAL state.
- Implement the logic to move problematic WALs to a quarantine directory.
- Create the new HBCK2 `quarantine_wal` command.

**Acceptance Criteria:**
- Unit tests for the WAL quarantine logic.
- Integration tests simulating a failed WAL split and verifying that the cluster can still recover.

---

### Task 5: Implement BulkRecoveryProcedure (BRP)

This task will create the BulkRecoveryProcedure, which will orchestrate the entire cold start recovery process. The BRP will manage the execution of SCPs for WAL splitting and BAPs for region assignment.

**Owner:** Andrew Purtell

**Dependencies:**
- Task 3
- Task 4

**Implementation Details:**
- Create a new StateMachineProcedure for bulk recovery.
- Implement the logic to first execute all ServerCrashProcedures and then all BulkAssignProcedures.

**Acceptance Criteria:**
- Unit tests for the BulkRecoveryProcedure state machine.
- Integration tests simulating a cold start and verifying that WAL splitting completes before region assignment begins.

---

### Task 6: Implement HBaseColdStartTool

This task involves creating the HBaseColdStartTool, the command-line tool that operators will use to initiate the BRB workflow.

**Owner:** Andrew Purtell

**Dependencies:**
- Task 3
- Task 5

**Implementation Details:**
- Create a new command-line tool that performs the necessary safety checks.
- Implement the logic to reset the procedure store and region states.
- Implement the logic to schedule the BulkRecoveryProcedure.

**Acceptance Criteria:**
- Unit tests for the HBaseColdStartTool.
- End-to-end tests demonstrating the successful execution of the BRB workflow from the command line.

---

### Task 7: End-to-End Testing and Documentation

This task will involve comprehensive end-to-end testing of the BRB workflow in a realistic cluster environment. It will also include the creation of detailed documentation for operators.

Refer to the Performance Testing section of this document.
