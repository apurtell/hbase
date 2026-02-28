# Assignment Issues Assessment

**Updated:** February 2026   
**Date:** 2026-02-12

The Assignment Manager v2 (AMv2) contains several open bugs that can cause master startup failures, stuck regions, data unavailability or loss, and cascading workload amplification.

Six recurring root-cause patterns drive these issues: **(1)** insufficient null-safety in procedure lifecycle methods, **(2)** procedure state inconsistency during master failover and WAL replay, **(3)** race conditions between concurrent procedures, **(4)** missing auto-recovery logic, **(5)** unbounded retry loops and missing timeout semantics in region transitions, and **(6)** `PEWorker` thread pool exhaustion.

This report documents 58 open issues across 62 JIRAs, organized into four categories:

- **Master Startup / Availability Failures** (8 issues) — bugs that prevent the master from starting or cause it to abort, resulting in complete cluster unavailability.  
- **Stuck Regions and Hung Procedures** (10 issues) — bugs that cause regions to become permanently unavailable until manual intervention.  
- **Design Issues Causing Operational Hazards** (6 issues) — systemic design weaknesses that amplify failures or make recovery difficult.  
- **Operational and Edge-Case Issues** (34 issues) — problems triggered by specific operational scenarios such as server crashes, upgrades, balancer interactions, and snapshot timing.

The report concludes with 28 prioritized recommendations:

- **Quick wins** (items 1–6): 6 confirmed-valid fixes verified against branch-2.6 source code. Two have active PRs ready for review (HBASE-29806, HBASE-29190); four need new patches but are small, low-risk changes.  
- **Medium-term improvements** (items 7–19): 13 items addressing recovery logic, timeout enforcement, thread exhaustion prevention, and stale state cleanup.  
- **Long-term architecture considerations** (items 20–28): 9 items requiring deeper design work including RS-side timeout enforcement, procedure scheduler fairness, ZK session resilience, and a proposed region assignment watchdog.

## 1\. Master Startup / Availability Failures

These bugs can prevent the HBase master from starting or cause it to abort, resulting in complete cluster unavailability.

### 1.1 [HBASE-29806](https://issues.apache.org/jira/browse/HBASE-29806): NPE in `RegionRemoteProcedureBase.afterReplay()` crashes the Master Procedure Executor

**Problem:** When the master restarts, `RegionRemoteProcedureBase.afterReplay()` unconditionally calls `getParent(env).attachRemoteProc(this)`. The `getParent()` method only checks the active `procedures` map, not the `completed` map. If the parent TRSP has already completed (e.g., during SCP rollback), `getParent()` returns null, causing an NPE that crashes the entire procedure executor.

**Source Reference:** In `RegionRemoteProcedureBase.afterReplay()` (line \~391), the call `getParent(env).attachRemoteProc(this)` is made without a null check — if the parent TRSP has already completed and been removed from the active procedures map, `getParent()` returns null and the call throws an NPE.

**Recommendation:** Add a null check in `afterReplay()`. If the parent is null, log a warning and allow the orphaned child procedure to be cleaned up. This is a straightforward one-line fix with a patch already available.

### 1.2 [HBASE-29552](https://issues.apache.org/jira/browse/HBASE-29552): `RegionRemoteProcedureBase` inconsistent state loading causes startup `AssertionError`

**Problem:** After a power failure, the master loads procedure state from WAL and meta. In `stateLoaded()`, when a `CloseRegionProcedure` has `REGION_REMOTE_PROCEDURE_REPORT_SUCCEED` state, it calls `restoreSucceedState()` which calls `regionNode.transitionState()`. However, the region's in-memory state may be OPENING (loaded from meta) while the procedure expects CLOSING/CLOSED, causing an `UnexpectedStateException` wrapped in an `AssertionError` that aborts the master.

**Source Reference:** The `stateLoaded()` method in `RegionRemoteProcedureBase` calls `restoreSucceedState()` when the procedure's persisted state is `REPORT_SUCCEED`, and wraps any `IOException` in an `AssertionError` — which aborts the master rather than allowing recovery.

**Recommendation:** The `stateLoaded` method must handle state mismatches gracefully instead of throwing `AssertionError`. When the region state is inconsistent with the procedure state, the procedure should log the inconsistency and either skip the state restoration or reset to a safe state, allowing the master to proceed with recovery.

### 1.3 [HBASE-29364](https://issues.apache.org/jira/browse/HBASE-29364): Region persisted as OPEN on dead RS after master failover

**Problem:** During a master failover, `OpenRegionProcedure.restoreSucceedState()` incorrectly persists a FAILED\_OPEN result as OPEN to `hbase:meta`. The root cause is that `restoreSucceedState()` does not check the actual `transitionCode` — it only checks if the region is already OPEN. If the RS that received the open request has already been processed by SCP, the region is recorded as OPEN on a dead server and never reassigned.

**Source Reference:** \[`OpenRegionProcedure` calls `regionOpenedWithoutPersistingToMeta()` without checking if `transitionCode == OPENED`\]

**Recommendation:** In `OpenRegionProcedure.restoreSucceedState()`, add a check for `transitionCode == TransitionCode.OPENED` before calling `regionOpenedWithoutPersistingToMeta()`. If the transition code is `FAILED_OPEN`, the region state should not be modified. A proposed fix exists in the JIRA.

### 1.4 [HBASE-28192](https://issues.apache.org/jira/browse/HBASE-28192): Master stuck forever when `hbase:meta` region state is inconsistent

**Problem:** During active master initialization, `isRegionOnline()` retries with `Integer.MAX_VALUE` and 60-second backoff waiting for the meta region to come online. If the meta region's transition record in ZK references a dead server (same host, old start time), the master loops forever. The only escape is manual HBCK intervention to schedule SCP for the old server address.

**Source Reference:** `HMaster.isRegionOnline()` (line \~1408) polls `RegionStates.isRegionOnline()` (line \~452) in a loop with no upper bound on attempts.

**Recommendation:** Add a timeout for `isRegionOnline()` and automatic detection of stale server references. If the meta region is assigned to a server that is known-dead, the master should auto-schedule an SCP rather than waiting indefinitely. This is a fundamental availability concern.

### 1.5 [HBASE-26754](https://issues.apache.org/jira/browse/HBASE-26754): Master crash from STUCK `hbase:meta` RIT with `FAILED_OPEN, location=null`

**Problem:** After running for several days, the master enters a state where `hbase:meta` is stuck in `FAILED_OPEN` with `location=null`. The RS keeps restarting, the balancer refuses to run because of dead RS processing, and the cluster becomes completely unavailable. The meta region cannot be reassigned because its location is null.

**Source Reference:** `AssignmentManager` RIT timeout chore (inner class `RegionInTransitionChore`, line \~1551) logs warnings for stuck RITs but does not auto-schedule recovery for meta `FAILED_OPEN` with null location.

**Recommendation:** The assignment manager must handle the case where `hbase:meta` enters `FAILED_OPEN` with a null location. It should auto-schedule an SCP or force-reassign meta to any available RS rather than leaving the cluster inoperable. This is closely related to HBASE-28192.

### 1.6 [HBASE-25274](https://issues.apache.org/jira/browse/HBASE-25274): RegionServer crash by `YouAreDeadException` (Blocker)

**Problem:** In Kerberos-enabled clusters, a running RegionServer is declared dead by the master (ZK session expires), causing a `YouAreDeadException` and RS abort. The SCP that follows must reassign all regions from the "dead" RS, creating a cascading failure. The root cause is ZK session management under Kerberos — GC pauses or ticket renewal delays cause ephemeral node expiration.

**Source Reference:** `ServerManager.regionServerReport()` (line \~287) and `ServerManager.checkClockSkew()` (line \~445) throw `YouAreDeadException` (line \~314) when the RS is in the dead servers list. `HRegionServer` (line \~1378) catches this and aborts.

**Recommendation:** Improve ZK session resilience under Kerberos. Consider adding a grace period or re-registration mechanism so that a healthy RS recovering from a transient ZK session loss can reclaim its regions without a full SCP cycle. This is filed as a Blocker.

### 1.7 [HBASE-24924](https://issues.apache.org/jira/browse/HBASE-24924): Stuck `InitMetaProcedure` in `finishActiveMasterInitialization`

**Problem:** If the procedure WAL contains a completed `InitMetaProcedure` but the meta ZK znode does not exist, `finishActiveMasterInitialization` hangs forever. During startup, `InitMetaProcedure` recreates a new `CountDownLatch` even though it has already finished, and the latch is never counted down.

**Source Reference:** `InitMetaProcedure` (line 59\) initializes `CountDownLatch latch = new CountDownLatch(1)` as a field initializer, so it is re-created every time the class is instantiated during procedure replay.

**Recommendation:** When replaying a completed `InitMetaProcedure`, skip the `CountDownLatch` creation. Alternatively, detect the finished state and signal the latch immediately.

### 1.8 [HBASE-24923](https://issues.apache.org/jira/browse/HBASE-24923): Running `InitMetaProcedures` missed if master startup happens after meta assign

**Problem:** In `HMaster` startup, the check for running `InitMetaProcedure` instances is inside a conditional block that only triggers when meta has no `RegionState`. If an `InitMetaProcedure` already passed the `INIT_META_ASSIGN_META` state (which sets the `RegionState`), the master won't find it and won't wait for it to complete. This can cause the master to proceed before namespace creation finishes.

**Source Reference:** `HMaster.java` (lines \~1062–1069) — the `InitMetaProcedure` search via `getProcedures().stream().filter(...)` is nested inside a conditional that checks for null `RegionState`.

**Recommendation:** Move the check for running `InitMetaProcedures` outside the `if` block so it executes regardless of whether meta already has a `RegionState`.

## 2\. Stuck Regions and Hung Procedures

These bugs cause regions to become permanently unavailable until manual intervention.

### 2.1 [HBASE-27711](https://issues.apache.org/jira/browse/HBASE-27711): Regions permanently stuck in `unknown_server` state

**Problem:** After a node restart involving the active master, regions end up with a stale server mapping. The `checkOnlineRegionsReport()` method detects the mismatch and calls `closeRegionSilently()`, but no subsequent assign is triggered. The `CatalogJanitor` reports `unknown_server` entries indefinitely.

**Source Reference:** \[`AssignmentManager.java:checkOnlineRegionsReport()` closes the region but does not initiate reassignment.

**Recommendation:** After `closeRegionSilently()`, the system should schedule a new TRSP to reassign the region. The current "close and hope" approach leaves regions orphaned.

### 2.2 [HBASE-27773](https://issues.apache.org/jira/browse/HBASE-27773): STUCK Region-In-Transition state

**Problem:** After a node reboot (especially when the rebooted node hosts the active master), regions get stuck in `OPENING` state. Writing data during the reboot increases reproduction probability. The stuck RIT is not automatically resolved.

**Source Reference:** `AssignmentManager.RegionInTransitionChore` (inner class, line \~1551) logs warnings for stuck RITs but does not trigger automatic remediation for OPENING state stuck beyond a threshold.

**Recommendation:** Improve the RIT chore (`RegionInTransitionChore`) to detect and auto-remediate stuck OPENING transitions. Consider adding a hard timeout for TRSP procedures in the OPEN state, as proposed in HBASE-27975.

### 2.3 [HBASE-29256](https://issues.apache.org/jira/browse/HBASE-29256): Multiple split procedures stuck indefinitely waiting for exclusive lock

**Problem:** When a `SplitTableRegionProcedure` fails to persist its state (due to HDFS timeout), it holds the exclusive lock on the region indefinitely. All subsequent split requests queue behind it (8043 in the reported case), and the stuck procedure is never rescheduled until a master restart.

**Source Reference:** `SplitTableRegionProcedure` (line \~99 in `SplitTableRegionProcedure.java`) acquires an exclusive lock on the region during `checkSplittable()` (line \~196). A persistence failure leaves the lock held with no timeout.

**Recommendation:** Related to [HBASE-29251](https://issues.apache.org/jira/browse/HBASE-29251) (resolved). Ensure that procedures that fail to persist state are eventually rescheduled or timed out. Add monitoring for procedures holding locks for excessive durations.

### 2.4 [HBASE-28139](https://issues.apache.org/jira/browse/HBASE-28139): Disable table hangs when SCP creates TRSP but DTP hasn't set table state

**Problem:** Race condition between `ServerCrashProcedure` and `DisableTableProcedure`:

1. RS crashes, SCP starts  
2. DTP starts but hasn't set tableState to DISABLING yet  
3. SCP creates TRSP, which blocks on DTP's xLock  
4. DTP's unassign RPC fails because RS is down  
5. Deadlock: DTP waits for RPC, TRSP waits for DTP's lock

**Source Reference:** `DisableTableProcedure` (line \~46 in `DisableTableProcedure.java`) acquires table-level xLock before setting table state to DISABLING. `ServerCrashProcedure` creates child TRSPs that also need the table-level lock.

**Recommendation:** Apply the available patch. The fix should ensure DTP sets the table state to DISABLING before acquiring the xLock, or that SCP-created TRSPs can proceed independently of DTP's lock state.

### 2.5 [HBASE-27983](https://issues.apache.org/jira/browse/HBASE-27983): `RSGroupAdminEndpoint` prevents `hbase:meta` from coming online

**Problem:** When using `RSGroupAdminEndpoint` and restarting both masters before the RS, a socket error prevents the `hbase:meta` region from coming online.

**Source Reference:** `RSGroupAdminEndpoint` coprocessor hooks into the master lifecycle; interaction with `AssignmentManager.assign()` during meta region placement can fail when the coprocessor encounters a socket error during early startup.

**Recommendation:** Investigate the interaction between RSGroup coprocessor loading and meta region assignment. The coprocessor stack should not prevent system table regions from being assigned.

### 2.6 [HBASE-29692](https://issues.apache.org/jira/browse/HBASE-29692): Merged region can be reassigned via HBCK race condition

**Problem:** After a region merge, there is a brief window before the merged-away region is deleted from meta where an HBCK `assign` command can reassign it. The existing unit test (`TestRegionMergeTransactionOnCluster#testWholesomeMerge`) has weak assertions that don't catch this.

**Source Reference:** `AssignmentManager.createAssignProcedure()` does not check the region's merge/split state in meta before creating a TRSP. `RegionStateNode.isSplit()` / `isMerged()` are available but not consulted.

**Recommendation:** Add a check in the assign path to reject assignment of regions that have already been merged or split. This should be validated against the region's `isSplit()`/merge state in meta.

### 2.7 [HBASE-25059](https://issues.apache.org/jira/browse/HBASE-25059): TRSP waits infinitely on `CONFIRMED_OPEN` when RS is locked up

**Problem:** When a RS becomes unresponsive (e.g., due to HBASE-24896), the TRSP for `hbase:meta` (or any region) waits forever in `WAITING:REGION_STATE_TRANSITION_CONFIRM_OPENED`. The AM has no mechanism to rescind the assignment, roll back the procedure, and retry with a different target RS.

**Source Reference:** `TransitRegionStateProcedure.executeFromState()` (line \~438) handles `REGION_STATE_TRANSITION_CONFIRM_OPENED` by calling `confirmOpened()`, which has no internal timeout — it suspends the procedure and waits indefinitely for a remote report.

**Recommendation:** Add a timeout to the `CONFIRM_OPENED` state in TRSP. When the timeout expires, the procedure should roll back, mark the target RS as suspect, and retry assignment to a different RS. This is closely related to HBASE-27975 and is a fundamental design gap in AMv2.

### 2.8 [HBASE-26283](https://issues.apache.org/jira/browse/HBASE-26283): RS ignores duplicate open request, causing region stuck in RIT

**Problem:** After an unexpected cluster shutdown and restart, `AssignRegionHandler.process()` on the RS detects that a region is already online and returns immediately *without* reporting the state back to the master. The master never receives the transition report and the region stays stuck in RIT indefinitely.

**Source Reference:** `AssignRegionHandler.process()` — early return without `reportRegionStateTransition` when region is already online.

**Recommendation:** When the RS receives an open request for an already-online region, it should report `OPENED` back to the master rather than silently returning. This ensures the master's procedure can advance.

### 2.9 [HBASE-26914](https://issues.apache.org/jira/browse/HBASE-26914): Rebuilding cluster from existing root directory hangs on stale TRSP

**Problem:** When rebuilding a cluster from an existing HDFS root directory (cloud migration scenario), the master recovers unfinished procedures from the procedure WAL. If a `TransitRegionStateProcedure` targets an RS from the old cluster, the procedure waits indefinitely because the old RS will never come back. The master logs warnings but takes no corrective action.

**Source Reference:** `TransitRegionStateProcedure.executeFromState()` dispatches open/close to the target RS without verifying the RS is in `ServerManager`'s live server list. `RegionRemoteProcedureBase.execute()` relies on `RSProcedureDispatcher` which queues indefinitely for dead servers.

**Recommendation:** During master startup, detect TRSPs targeting servers that are not in the current live server list and either re-plan the assignment to an available RS or cancel and reschedule the procedure.

### 2.10 [HBASE-26287](https://issues.apache.org/jira/browse/HBASE-26287): Master initialization stuck when `hbase:namespace` region is not online

**Problem:** After unexpected cluster shutdown and restart, the master gets stuck in `isRegionOnline()` waiting for `hbase:namespace` to come online. The master and meta believe the namespace region is OPEN on a dead RS, but `isRegionOnline()` loops every 60 seconds without taking corrective action (same pattern as HBASE-28192 for meta).

**Source Reference:** `HMaster.java` (line \~1452) calls `isRegionOnline(ri)` in a loop for each system table region (namespace, quota) during `finishActiveMasterInitialization()`.

**Recommendation:** Same fix as HBASE-28192 — add timeout and auto-SCP scheduling. The `isRegionOnline()` pattern needs a general fix for all system table regions, not just meta.

## 3\. Design Issues Causing Operational Hazards

These are systemic design weaknesses that amplify failures or make recovery difficult.

### 3.1 [HBASE-29006](https://issues.apache.org/jira/browse/HBASE-29006): Unconstrained assignment retry causes workload amplification

**Problem:** `AssignmentManager.processAssignmentPlans()` catches `HBaseIOException` from the balancer and retries the assignment without any bound. When retries fail because the RS is overloaded, unbounded retries make the overload worse, creating a positive feedback loop.

**Source Reference:** \[`AssignmentManager.java` — `processAssignmentPlans()` catch block calls `addToPendingAssignment()` without retry limits\]

**Recommendation:** Add configurable retry limits and exponential backoff to `processAssignmentPlans()`. When retries are exhausted, the regions should be queued for later assignment rather than immediately retried.

### 3.2 [HBASE-27975](https://issues.apache.org/jira/browse/HBASE-27975): Region (un)assignment lacks direct timeout

**Problem:** When a RS cannot communicate with the NameNode, the `RS_CLOSE_REGION` handler gets stuck waiting for NN failover. Due to compounding retry configurations across HBase and HDFS layers, the effective timeout can be \~30 minutes before an SCP is triggered. On degraded hardware, assignment handlers may never fail at all, causing indefinite hangs.

**Source Reference:** `AssignRegionHandler` (line \~53 in `AssignRegionHandler.java`) and `UnassignRegionHandler` (line \~50 in `UnassignRegionHandler.java`) — neither has a configurable hard timeout for the HDFS operations within region open/close.

**Recommendation:** Add configurable hard timeouts to `AssignRegionHandler` and `UnassignRegionHandler` on the RS side. This was partially addressed by [HBASE-28048](https://issues.apache.org/jira/browse/HBASE-28048) (RSProcedureDispatcher abort after retries), but the RS-side handlers still lack timeout enforcement.

### 3.3 [HBASE-27614](https://issues.apache.org/jira/browse/HBASE-27614): Region reopen infinite loop when `seqnumDuringOpen` is corrupted

**Problem:** In `OpenRegionProcedure`, when the `openSeqNum` in meta is larger than the actual sequence number (due to data loss or corruption), the `checkReopened()` method keeps returning the region as needing reopening, creating an infinite loop. The `regionOpenedWithoutPersistingToMeta()` only updates the seqnum when the new value is larger.

**Source Reference:** `OpenRegionProcedure.regionOpenedWithoutPersistingToMeta()` (line \~82 in `OpenRegionProcedure.java`) delegates to `AssignmentManager.regionOpenedWithoutPersistingToMeta()` (line \~2245) which conditionally updates seqnum only when new \> old.

**Recommendation:** Fix `regionOpenedWithoutPersistingToMeta()` to always update the seqnum when the region has been successfully opened, regardless of whether the new value is larger or smaller than the stored value. Add a retry limit to the reopen loop.

### 3.4 [HBASE-28013](https://issues.apache.org/jira/browse/HBASE-28013): Procedure WAL grows unbounded after bypassing procedures recursively

**Problem:** When bypassing a TRSP recursively, the TRSP and its child ORP/CRP race on the procedure store. The TRSP calls `store.delete` for the child, but the child subsequently calls `store.update`, which overrides the delete. This leaves orphan procedure entries that prevent WAL cleanup.

**Source Reference:** `ProcedureExecutor.bypassProcedure()` calls `store.delete()` for child procedures, but `ProcedureExecutor.execProcedure()` may concurrently call `store.update()` for the same child, overriding the delete.

**Recommendation:** Fix the ordering of procedure store operations during bypass. The child procedure's `store.update` must not override a `store.delete` from the parent. This requires synchronizing the bypass and execution paths.

### 3.5 [HBASE-29190](https://issues.apache.org/jira/browse/HBASE-29190): Cannot disable table with regions in FAILED\_OPEN state

**Problem:** When a table has regions in `FAILED_OPEN` state, attempting to disable it causes HBase to try reassigning those regions first (via `TransitRegionStateProcedure`), which makes no sense for a disable operation. Operators must manually fix meta via HBCK before they can disable the table.

**Source Reference:** `TransitRegionStateProcedure` creates an ASSIGN transition for FAILED\_OPEN regions during `DisableTableProcedure`, rather than allowing a direct transition to CLOSED/OFFLINE.

**Recommendation:** The PR is available. When disabling a table, regions in `FAILED_OPEN` should transition directly to `CLOSED`/`OFFLINE` without attempting reassignment.

### 3.6 [HBASE-28659](https://issues.apache.org/jira/browse/HBASE-28659): NPE in `RegionStates.setServerState()` during SCP

**Problem:** During `ServerCrashProcedure` execution at the `SERVER_CRASH_SPLIT_LOGS` state, `RegionStates.setServerState()` encounters an NPE because the server node is null after an upgrade from 2.5.8. SCP does not support rollback, so the procedure is marked FAILED permanently.

**Source Reference:** `RegionStates.setServerState()` (line \~377 in `RegionStates.java`) calls `getServerNode(serverName)` which returns null if the server was never registered, then dereferences the result without a null check.

**Recommendation:** Add null-safety in `RegionStates.setServerState()`. If the server node is not found, handle gracefully (create the node or skip the state update) rather than crashing the procedure.

## 4\. Operational and Edge-Case Issues

### 4.1 [HBASE-29555](https://issues.apache.org/jira/browse/HBASE-29555): `TestRollbackSCP` flaky due to non-empty scheduler queue

**Problem:** Test failures during SCP rollback testing suggest timing issues in how procedures are cleaned up from the scheduler after rollback. While primarily a test issue, it may indicate underlying concerns with procedure cleanup during SCP rollback.

**Source Reference:** `ServerCrashProcedure` rollback leaves child procedures in the `MasterProcedureScheduler` queue, causing assertion failures in `TestRollbackSCP`.

**Recommendation:** Fix procedure cleanup during SCP rollback to ensure all child procedures are removed from the scheduler queue.

### 4.2 [HBASE-29537](https://issues.apache.org/jira/browse/HBASE-29537): UseIP switch causes inconsistent RS listings with RSGroup

**Problem:** Switching the `UseIP` feature causes RSGroup-based server listings to become inconsistent, potentially directing assignments to unreachable addresses.

**Source Reference:** `RSGroupInfoManager` stores server entries by `ServerName` (hostname-based); toggling `UseIP` changes the hostname format, causing lookup mismatches.

**Recommendation:** Ensure RSGroup server entries are normalized consistently regardless of the `UseIP` setting, or provide a migration path when toggling the feature.

### 4.3 [HBASE-29710](https://issues.apache.org/jira/browse/HBASE-29710): Skip normalization when table regions contain overlaps/holes

**Problem:** The normalizer does not detect overlaps and holes in the region list, and may make the situation worse by splitting or merging regions based on incorrect assumptions.

**Source Reference:** `RegionNormalizer` processes the region list as-is without validating contiguity of start/end keys before making split/merge decisions.

**Recommendation:** Add a pre-check in the normalizer to detect overlaps and holes in the region list and skip normalization for affected tables.

### 4.4 [HBASE-26883](https://issues.apache.org/jira/browse/HBASE-26883): Crash HM and META-RS during truncate table causes data loss

**Problem:** When the active master and meta RS crash during a `TruncateTableProcedure` (between deleting the old table and creating the new region), the table's data is lost and the new region is never created. The failover master finds an inconsistent state — the table exists in ZK but not in meta — leading to permanent data loss.

**Source Reference:** `TruncateTableProcedure.executeFromState()` (in `TruncateTableProcedure.java`, line \~45) deletes the old table regions before persisting the new region info to meta, creating a crash-vulnerable window.

**Recommendation:** The `TruncateTableProcedure` should be made more crash-resilient, possibly by persisting the new region info to meta before deleting the old regions, or by adding recovery logic to detect and complete partial truncate operations.

### 4.5 [HBASE-25092](https://issues.apache.org/jira/browse/HBASE-25092): `RSGroupBalancer#assignments` loses region plans (patch available)

**Problem:** When RSGroup fallback is enabled and a group's servers are unavailable, `retainAssignment` returns plans targeting RS from other groups. The subsequent `assignments.putAll()` overwrites existing entries for those RS, silently dropping some region plans. This can leave regions unassigned after a balancer run.

**Source Reference:** `RSGroupBasedLoadBalancer.java` (in `hbase-rsgroup`) — the `retainAssignment()` result is merged via `putAll()` which overwrites existing entries for the same `ServerName` key.

**Recommendation:** Apply the available patch. Replace `putAll` with `computeIfAbsent`/`addAll` to merge region lists rather than overwriting them.

### 4.6 [HBASE-26298](https://issues.apache.org/jira/browse/HBASE-26298): Downgrading blocked by refusal to assign system tables to lower version

**Problem:** `getExcludedServersForSystemTable()` prevents system tables (meta, quota, namespace) from being assigned to lower-version RS. During rolling downgrades, this can trap system table regions on the last remaining higher-version RS, making it impossible to drain. The `hbase.min.version.move.system.tables` config is poorly documented and not dynamically reloadable.

**Source Reference:** `AssignmentManager.getExcludedServersForSystemTable()` (line \~2654 in `AssignmentManager.java`) builds an exclusion list based on RS version, controlled by `hbase.min.version.move.system.tables`.

**Recommendation:** Improve documentation for the `hbase.min.version.move.system.tables` config. Consider making it dynamically reloadable and adding an override for planned downgrade operations.

### 4.7 [HBASE-25142](https://issues.apache.org/jira/browse/HBASE-25142): Auto-fix `Unknown Server` via CatalogJanitor

**Problem:** "Unknown Server" entries in meta (server not online, not in dead servers list) accumulate from various failure scenarios — cluster-wide crashes, cloud migrations, moved-aside procedure stores. Currently requires manual HBCK2 intervention to schedule SCP per stale server.

**Source Reference:** `CatalogJanitor` runs periodically and reports Unknown Servers in the HBCK report, but does not schedule SCPs for them. `ServerManager.isServerOnline()` and `isServerDead()` are the relevant checks.

**Recommendation:** Implement auto-detection and auto-repair in `CatalogJanitor`: when it finds an Unknown Server, schedule an SCP to reassign the regions. This directly addresses the root cause behind HBASE-27711 and many operational support tickets.

### 4.8 [HBASE-27465](https://issues.apache.org/jira/browse/HBASE-27465): RS process alive but master shows dead

**Problem:** After a ZK ephemeral node expiration (related to HBASE-25274), the master declares the RS dead and schedules SCP, even though the RS process is still running. The RS never recovers its registration and remains in a zombie state.

**Source Reference:** `RegionServerTracker` watches ZK ephemeral nodes; on deletion, `ServerManager.expireServer()` is called which schedules SCP via `AssignmentManager.submitServerCrash()`.

**Recommendation:** Add a mechanism for a still-alive RS to re-register with the master after a transient ZK session loss, rather than remaining in a zombie state requiring manual restart.

### 4.9 [HBASE-25225](https://issues.apache.org/jira/browse/HBASE-25225): Table creation very slow with many regions (Blocker)

**Problem:** `TransitRegionStateProcedure` acquires an exclusive table-level lock (`xlock`) and spends \~56 seconds waiting for it per region due to scheduling delays. With many regions, table creation time becomes O(n × lock-wait-time). Observed in branch-2.2 but not branch-2.3, suggesting a regression or fix in newer versions.

**Source Reference:** `MasterProcedureScheduler` manages the `TableQueue` where TRSPs wait for xlock. The scheduling delay is in `MasterProcedureScheduler.addToRunQueue()` / `takeLock()`.

**Recommendation:** Verify whether this issue persists in 2.6. If so, investigate the procedure scheduler's table queue priority logic to reduce xlock contention during bulk region creation.

### 4.10 [HBASE-24526](https://issues.apache.org/jira/browse/HBASE-24526): Deadlock executing assign meta procedure (Critical)

**Problem:** During recovery, the master creates an assign procedure for meta and immediately marks meta as assigned in ZooKeeper. It then creates the child `OpenRegionProcedure` to open meta on the target RS. However, all 16 `PEWorker` threads become stuck on other procedures that are waiting to write to `hbase:meta` — which is unavailable because meta itself hasn't been opened yet. The `OpenRegionProcedure` for meta never gets a chance to run because all threads are consumed. The master never recovers from this state.

**Source Reference:** `ServerCrashProcedure.assignRegions()` (line \~512 in `ServerCrashProcedure.java`) schedules child TRSPs. Non-meta TRSPs call `RegionStateStore.updateRegionLocation()` (line \~158 in `RegionStateStore.java`) which writes to `hbase:meta` and blocks when meta is unavailable. With all `PEWorker` threads blocked, the meta assign ORP is never executed.

**Recommendation:** Prioritize meta assignment by ensuring meta TRSPs always get a dedicated worker thread (see HBASE-23597) or by making non-meta TRSPs yield when meta is unavailable (see HBASE-24673). Both approaches prevent the deadlock by ensuring meta can be assigned even when the worker pool is saturated.

### 4.11 [HBASE-24673](https://issues.apache.org/jira/browse/HBASE-24673): TRSP of non-meta regions should yield when meta is unavailable

**Problem:** While meta is unavailable, non-meta region movement TRSPs get stuck on meta RPCs (e.g., `RegionStateStore.updateRegionLocation()`). This consumes all `PEWorker` threads and can lead to the deadlock described in HBASE-24526.

**Source Reference:** `TransitRegionStateProcedure.executeFromState()` calls `RegionStateStore.updateRegionLocation()` which writes to `hbase:meta` — if meta is not available, these RPCs block indefinitely. A PR (\#2014) exists that checks meta state before attempting RPCs.

**Recommendation:** Make non-meta TRSPs check the state of meta before attempting any RPCs. If meta is known to be unavailable, release the thread back to the scheduler by yielding.

### 4.12 [HBASE-24293](https://issues.apache.org/jira/browse/HBASE-24293): SCP gives up assigning meta when region location is null (Critical)

**Problem:** During `ServerCrashProcedure` at `SERVER_CRASH_ASSIGN_META`, the code checks whether the region is still on the crashed server via `isMatchingRegionLocation()`. If a concurrent TRSP has already cleared the region's location (set to null), the SCP skips the assignment entirely — even for meta. With meta unassigned, the cluster is unusable and requires manual intervention.

**Source Reference:** `ServerCrashProcedure.assignRegions()` (line \~529 in `ServerCrashProcedure.java`) calls `isMatchingRegionLocation(regionNode)` (line \~498). If `regionNode.getRegionLocation()` is null (set by a concurrent TRSP), the check `serverName.equals(null)` returns false and meta assignment is silently skipped.

**Recommendation:** Never give up assigning meta. The `assignRegions()` method should treat meta specially — if the region is meta and its location doesn't match the crashed server, verify that another procedure is actively assigning it before skipping. If meta is unassigned and no procedure owns it, force-create a new TRSP.

### 4.13 [HBASE-24292](https://issues.apache.org/jira/browse/HBASE-24292): A "stuck" master idles as active without taking action (Critical)

**Problem:** After SCP for the meta RS fails due to a misconfiguration, and even after the config is fixed and the cluster is restarted, the master enters a "holding pattern" after 15 minutes. It retains Active master status but takes no action — no recovery, no abort. This "brown-out" state is toxic because other masters cannot take over.

**Source Reference:** `HMaster.finishActiveMasterInitialization()` has a timeout on meta assignment. After the timeout, the master continues running but stops attempting recovery, effectively idling.

**Recommendation:** The master should never idle in a brown-out state. After exhausting recovery attempts, it should abort to allow a standby master to take over, or it should continue retrying indefinitely with backoff.

### 4.14 [HBASE-23595](https://issues.apache.org/jira/browse/HBASE-23595): HMaster abort when write to meta fails during SCP

**Problem:** When the RS carrying `hbase:meta` crashes, any concurrent procedure that attempts to write to meta (via `RegionStateStore.updateRegionLocation()`) will fail. The catch block calls `master.abort()`, crashing the master entirely. This is because SCP may not have started processing the dead server yet, so meta is unavailable.

**Source Reference:** `RegionStateStore.updateRegionLocation()` (line \~231 in `RegionStateStore.java`) catches `IOException` and calls `master.abort(msg, e)` — a `// TODO: Revist!!!!` comment acknowledges this is a known problem.

**Recommendation:** Remove the `master.abort()` call from `updateRegionLocation()` or make it conditional. When meta is temporarily unavailable (e.g., during SCP), procedures should retry or yield rather than crashing the master. This is closely related to HBASE-23597 (meta assign priority).

### 4.15 [HBASE-23593](https://issues.apache.org/jira/browse/HBASE-23593): Stalled SCP assigns — OpenRegionProcedure scheduled but never runs

**Problem:** On a heavily loaded cluster, after a server crashes and SCP begins, the `OpenRegionProcedure` (ORP) is created and added to the procedure scheduler but never gets picked up by any `PEWorker` thread. The region is eventually flagged as STUCK RIT. The ORP is logged as initialized and added to the run queue but never executes afterward. Meanwhile, the dead server quickly restarts and is removed from the dead server list while SCP is still splitting WALs.

**Source Reference:** `MasterProcedureScheduler.addToRunQueue()` adds the ORP to the `TableQueue`, but when the table queue has many concurrent procedures with shared locks, the ORP may be starved. The early removal of the dead server from `DeadServer` processing list (via `ServerManager.regionServerReport()`) may also interfere with SCP progress.

**Recommendation:** Investigate the procedure scheduler's fairness guarantees when a table has thousands of concurrent procedures. Consider adding a timeout that auto-wakes stalled ORPs. Also evaluate whether removing a server from the dead list before SCP completes its assign phase causes race conditions.

### 4.16 [HBASE-22780](https://issues.apache.org/jira/browse/HBASE-22780): Assign failure for missing table descriptor causes STUCK RIT forever (patch available)

**Problem:** When a region is assigned to an RS, the RS fails to open it due to a missing table descriptor exception. The `AssignProcedure` handles the failure and retries. If the region is reassigned to the same RS, that RS ignores the request because it already had a failed open for this region. The region is stuck in RIT forever, even after the table descriptor issue is resolved.

**Source Reference:** `HRegionServer.openRegion()` caches the failed open state per region. A subsequent assign to the same RS checks this cache and silently ignores the request rather than retrying.

**Recommendation:** Apply the available patch. Clear the failed-open cache entry when a new assign request is received for the same region, allowing the RS to retry the open.

### 4.17 [HBASE-22631](https://issues.apache.org/jira/browse/HBASE-22631): Assign failure can resurrect a GC'd parent region (patch available)

**Problem:** When a region A is assigned to RS1 and RS1 fails to open it, the `AssignProcedure.handleFailure()` calls `regionNode.offline()` which sets the location to null and state to OFFLINE. The subsequent `undoRegionAsOpening(regionNode)` does nothing because the location is already null. The region remains in the `serverMap` for RS1. If RS1 later restarts, `ServerCrashProcedure` gets the stale region from `serverMap` and re-assigns it — even if the region has since been split and GC'd. This creates ghost regions with HDFS directories.

**Source Reference:** `AssignmentManager.undoRegionAsOpening()` checks region location, but `AssignProcedure.handleFailure()` already sets it to null via `regionNode.offline()`, causing the undo to be a no-op. The stale `serverMap` entry then causes SCP to resurrect the region.

**Recommendation:** Apply the available patch. Ensure `handleFailure()` properly removes the region from the `serverMap` before calling `offline()`, or make `undoRegionAsOpening()` also clean up the `serverMap` regardless of the current location.

### 4.18 [HBASE-22918](https://issues.apache.org/jira/browse/HBASE-22918): RegionServer violates failfast/failstop assumption

**Problem:** HBase uses a single `zookeeper.session.timeout` for both master and RS. The RS should suicide within the lease period, and the master should take over only after a grace period. But with a shared timeout value, the master can declare the RS dead and start SCP (taking over WALs) before the RS has fully shut down — potentially causing WAL corruption or data loss.

**Source Reference:** `ZKWatcher` uses a single `zookeeper.session.timeout` config both for RS connections (which should have a shorter timeout) and master connections (which should have a longer timeout for the grace period invariant to hold).

**Recommendation:** Provide separate configuration properties `regionserver.zookeeper.session.timeout` and `master.zookeeper.session.timeout` so operators can enforce the invariant `RS lease period < master grace period`, satisfying the failstop fault model.

### 4.19 [HBASE-22334](https://issues.apache.org/jira/browse/HBASE-22334): Blocking RPC threads on latch waits causes starvation

**Problem:** When many concurrent `CreateTableProcedure` requests arrive, each blocks an RPC thread with `latch.await()` inside `submitProcedure()`. These blocked RPC threads consume all available slots. When regions being assigned need to report back via `reportRegionStateTransition()`, those calls are also stuck in the RPC queue. This creates a circular dependency: table creation waits for regions to open, but regions can't report because all RPC threads are blocked by table creation.

**Source Reference:** `HMaster.createTable()` calls `ProcedureExecutor.submitProcedure()` with a `CountDownLatch` and blocks the RPC thread on `latch.await()`. `RegionServerRpcServices.reportRegionStateTransition()` must compete with the blocked threads for RPC handler slots.

**Recommendation:** Avoid blocking RPC threads on latch waits. Return a procedure ID to the client immediately and have the client poll for completion, or implement a timeout on the latch with proper cleanup.

### 4.20 [HBASE-23958](https://issues.apache.org/jira/browse/HBASE-23958): Balancer keeps balancing indefinitely to a stale server

**Problem:** After a cluster restart with RIT in progress, `RegionStateStore` populates a `ServerStateNode` with an older timestamp version of a stopped RS. The balancer treats this stale server as part of the cluster and continuously tries to move regions to it. `AssignmentManager` then redirects those moves to active servers, but this is not recorded, causing the balancer to loop indefinitely.

**Source Reference:** `RegionStateStore.visitMeta()` (line \~87 in `RegionStateStore.java`) loads region locations from meta. If a region's recorded location references a dead RS with an old start code, `RegionStates.getOrCreateServer()` creates a `ServerStateNode` for that stale server, making it appear as an available target to the balancer.

**Recommendation:** During master startup, cross-reference servers loaded from meta with the actual online server list. Remove or mark stale `ServerStateNode` entries for servers that are not in `ServerManager.getOnlineServers()`.

### 4.21 [HBASE-22703](https://issues.apache.org/jira/browse/HBASE-22703): Stale `RegionPlan`s in balancer for deleted regions

**Problem:** After a table and its regions are deleted, the `RSGroupBasedLoadBalancer` continues to hold stale `RegionPlan` entries for the deleted regions. The balancer repeatedly tries to move non-existent regions and the `AssignmentManager` logs "Ignored moving region not assigned" for each one.

**Source Reference:** `RSGroupBasedLoadBalancer` caches region plans internally. When regions are deleted, the cached plans are not invalidated, causing the balancer to generate moves for non-existent regions indefinitely.

**Recommendation:** Invalidate cached region plans when regions are deleted (during table drop, merge, or split). The balancer should verify that regions still exist in `RegionStates` before generating plans.

### 4.22 [HBASE-24582](https://issues.apache.org/jira/browse/HBASE-24582): Meta replica regions may be assigned to the same server as primary meta

**Problem:** The `assignMetaReplicas()` implementation does not exclude the server hosting the primary meta region when assigning replicas. This defeats the purpose of meta replicas for HA — if the server hosting primary meta crashes, all replicas go down too.

**Source Reference:** `HMaster.assignMetaReplicas()` creates TRSP instances for replica meta regions without passing an exclusion list for the primary server. In contrast, `SplitTableRegionProcedure` properly uses `AssignmentManagerUtil.createAssignProceduresForOpeningNewRegions()` with a round-robin algo that excludes the parent server.

**Recommendation:** Follow the `SplitTableRegionProcedure` pattern — use round-robin assignment for meta replicas and exclude the server hosting the primary meta region.

### 4.23 [HBASE-22256](https://issues.apache.org/jira/browse/HBASE-22256): Enabling FavoredStochasticBalancer leaves regions unassigned (patch available)

**Problem:** When switching from a different balancer to `FavoredStochasticLoadBalancer`, all regions are considered "misplaced" relative to their (nonexistent) favored nodes. The balancer unassigns these regions and creates `RegionPlan` objects with `source=null`, leading to an NPE. This leaves affected regions unassigned after the balancer run.

**Source Reference:** `FavoredStochasticLoadBalancer.balance()` returns `RegionPlan` objects with null source for misplaced regions. `AssignmentManager.balance()` then attempts to use this null source in `MoveRegionProcedure`, causing NPE.

**Recommendation:** Apply the available patch. Ensure `RegionPlan` always has a valid source server, or handle the null source case gracefully in `AssignmentManager.balance()`.

### 4.24 [HBASE-22105](https://issues.apache.org/jira/browse/HBASE-22105): Snapshot of table fails when region is transitioning

**Problem:** During a `MoveRegionProcedure`, when the unassign completes on the source RS but the assign to the destination hasn't started yet, there is a window where the region is in CLOSED state. If a snapshot is taken during this window, the snapshot fails because it cannot find the region on any RS to flush/snapshot.

**Source Reference:** `SnapshotManager.takeSnapshot()` requires all regions to be in OPEN state. During `MoveRegionProcedure`, between `UnassignProcedure` completion and `AssignProcedure` start, regions are transiently CLOSED.

**Recommendation:** Make the snapshot procedure tolerant of transiently closed regions during moves. Either wait for the move to complete, skip the region and snapshot its latest persisted state, or coordinate with the procedure framework to block moves during snapshots.

### 4.25 [HBASE-21519](https://issues.apache.org/jira/browse/HBASE-21519): Namespace region never assigned in HM failover with multiwal (Critical)

**Problem:** When multiwal is enabled with the `identity` WAL provider strategy, the namespace region is never assigned after a master failover involving cascading RS crashes. The master waits indefinitely for the namespace region to come online, logs `Master startup cannot progress, in holding-pattern until region onlined`, and eventually aborts. The root cause is that during SCP WAL splitting with multiwal, the meta entry for the namespace region still references the crashed RS. The region's state in meta says OPEN on a dead server, but SCP doesn't reassign it because the WAL directory structure differs under multiwal.

**Source Reference:** `HMaster.isRegionOnline()` (called from `waitForNamespaceOnline()` around line \~1166 in `HMaster.java`) polls indefinitely. `ServerCrashProcedure.assignRegions()` skips assignment if `isMatchingRegionLocation()` returns false. Under multiwal, the WAL directory layout `<server>/<walProvider>` differs from the standard layout, causing WAL splitting to miss certain regions' WALs.

**Recommendation:** Ensure SCP properly handles the multiwal directory structure during WAL splitting and region reassignment. The namespace region should be treated with the same priority as meta — if it fails to come online within a timeout, SCP should force-reassign it rather than allowing the master to enter a holding pattern.

### 4.26 [HBASE-21624](https://issues.apache.org/jira/browse/HBASE-21624): Master startup blocks on meta replica assignment

**Problem:** During master startup, `MasterMetaBootstrap.assignMetaReplicas()` blocks via `ProcedureSyncWait.submitAndWaitProcedure()`. If a meta replica is stuck in transition, the `becomeActiveMaster` thread is blocked indefinitely. In the reported case, the thread was stuck for 19+ hours. Additionally, if the meta-hosting server crashes during replica assignment, the master immediately aborts with `HBaseIOException: rit=OFFLINE, location=null`.

**Source Reference:** `MasterMetaBootstrap.assignMetaReplicas()` (line \~84) blocks on `ProcedureSyncWait.submitAndWaitProcedure()`. `AssignmentManager.preTransitCheck()` throws an exception if the region is already in transition when trying to assign a replica, crashing the master.

**Recommendation:** Meta replica assignment should be fire-and-forget — submit the procedures and continue startup without waiting. Normal region handling will manage the replicas afterward. Also add null-safety to `preTransitCheck()` to not abort the master on a transient replica assignment race.

### 4.27 [HBASE-21333](https://issues.apache.org/jira/browse/HBASE-21333): AMv2 large cluster startup is slow

**Problem:** Startup of a cluster with 500+ nodes and \~500K regions takes several hours in AMv2. The master spends most of the time parsing region server reports during initialization, rather than on region assignment itself. This is a scalability issue with the assignment manager's initialization path.

**Source Reference:** During `AssignmentManager.joinCluster()`, the master processes region server reports one by one. For a cluster with 500K regions, each `regionServerReport()` involves iterating over the regions and updating `RegionStates`, which is O(n) per server with high constant factors.

**Recommendation:** Profile and optimize the region server report processing path. Consider batching or parallelizing region state updates during startup. This may also benefit from caching optimizations in `RegionStates` data structures.

### 4.28 [HBASE-20252](https://issues.apache.org/jira/browse/HBASE-20252): Admin.move to nonexistent RS silently reopens on source

**Problem:** When `Admin.move()` is called with a target RS that doesn't exist, the region is unassigned from its current server and then reassigned — but since the target is invalid, it gets reopened on the source RS (or another available RS). The call does not fail or throw an error, which is confusing for operators who expect the move to either succeed at the target or fail explicitly.

**Source Reference:** `AssignmentManager.move()` creates a `MoveRegionProcedure` with the specified target. When the target is not online, the assign phase falls back to the balancer's default placement, which may send it right back to the source.

**Recommendation:** Validate the target server in `Admin.move()` before creating the `MoveRegionProcedure`. If the target is not in `ServerManager.getOnlineServers()`, throw an `UnknownRegionException` or similar error immediately.

### 4.29 [HBASE-20802](https://issues.apache.org/jira/browse/HBASE-20802): No interruptCall for hung remote procedures

**Problem:** RPCs to zombie or slow RegionServers can hang until they timeout (default 3 minutes). During this time, the master's remote procedure dispatch threads are blocked. `ServerCrashProcedure` does cleanup of ongoing assigns/unassigns, but it cannot interrupt the already-dispatched RPCs. The `RemoteProcedure` interface lacks an `interruptCall()` method to cancel in-flight RPCs.

**Source Reference:** `RemoteProcedure` interface (in `procedure2` module) has `remoteCallBuild()`, `remoteCallCompleted()`, and `remoteCallFailed()` but no interrupt/cancel method. `ServerCrashProcedure` calls `serverCrashed()` on existing TRSPs but cannot cancel the underlying RPCs.

**Recommendation:** Add an `interruptCall()` method to the `RemoteProcedure` interface. `ServerCrashProcedure` should call this to cancel any outstanding RPCs during cleanup, freeing dispatch threads immediately rather than waiting for timeout. This is a prerequisite for HBASE-25059 (TRSP timeout/rollback).

### 4.30 [HBASE-21623](https://issues.apache.org/jira/browse/HBASE-21623): SCP can stomp on a RIT for a wrong server (Critical)

**Problem:** When a server dies while a region is being opened on it, the open eventually fails and the TRSP retries on a different server. However, the SCP for the dying server already obtained the region from the dead server's region list and proceeds to overwrite the TRSP's new assignment. Specifically: the TRSP moves the region to `newServer` and updates meta with `regionState=OPENING, regionLocation=newServer`, but then SCP writes `regionState=ABNORMALLY_CLOSED` for the same region, clobbering the in-progress assignment. The region can end up stuck in OPENING state forever.

**Source Reference:** `ServerCrashProcedure` at `SERVER_CRASH_ASSIGN` state finds the existing TRSP for the region but writes `ABNORMALLY_CLOSED` to meta via `RegionStateStore`, overriding the TRSP's `OPENING` state on `newServer`. The race occurs because SCP and TRSP don't coordinate through a shared lock when updating meta for the same region.

**Recommendation:** SCP should check the region's current TRSP state and target server before overwriting. If a TRSP is already retrying on a different server, SCP should defer to it rather than clobber its state. At minimum, SCP should take the region lock before updating meta state.

### 4.31 [HBASE-21124](https://issues.apache.org/jira/browse/HBASE-21124): SCP stuck unable to split WALs (NoServerDispatchException)

**Problem:** When SCP tries to dispatch an unassign to a server that is no longer online, a `NoServerDispatchException` is thrown. The server is already dead but no new SCP is scheduled because the `ServerManager.expireServer()` call finds the server is not in the online list. As a result, the region close never completes, and regions remain in `CLOSING` state, eventually reporting STUCK. Meanwhile the root SCP is stuck at `SERVER_CRASH_SPLIT_LOGS` because WAL splitting failed with `FileNotFoundException` while writing recovered.edits.

**Source Reference:** `RemoteProcedureDispatcher.addOperationToNode()` (line \~177) throws `NoServerDispatchException` when the target server is not online. `UnassignProcedure.updateTransition()` calls `ServerManager.expireServer()` but since the server is not in the online list, no SCP is submitted. The underlying WAL split failure is caused by HDFS returning FNFE for the WAL file.

**Recommendation:** Handle the `NoServerDispatchException` case by force-transitioning the region to `ABNORMALLY_CLOSED` without dispatching, since the target RS is already dead. For WAL split failures, add retry logic with exponential backoff instead of getting stuck permanently.

### 4.32 [HBASE-21627](https://issues.apache.org/jira/browse/HBASE-21627): Race between recovered meta replica RIT and master startup

**Problem:** During master startup, a meta replica RIT is recovered from the procedure WAL and proceeds to assign the replica. It completes successfully, placing the replica in `OPEN` state. Then `MasterMetaBootstrap.assignMetaReplicas()` runs and tries to assign the same replica, but `preTransitCheck()` sees it's already in `OPEN` state and throws `DoNotRetryRegionException("Unexpected state")`, which aborts the master.

**Source Reference:** `AssignmentManager.preTransitCheck()` (line \~548) allows only `CLOSED`, `OFFLINE`, or `SPLIT` states for a new assign. If a recovered procedure already placed the replica to `OPEN`, this check fails with a fatal exception in `HMaster.finishActiveMasterInitialization()`.

**Recommendation:** `assignMetaReplicas()` should skip replicas already in `OPEN` state. If a replica is already assigned and online, there is nothing to do. This is closely related to HBASE-21624 (blocking on meta replica assignment).

### 4.33 [HBASE-19992](https://issues.apache.org/jira/browse/HBASE-19992): Namespace table assign hole → master abort

**Problem:** If the namespace table's assign fails during initial creation, the table gets marked as `ENABLED` in meta but its region is never assigned. On the next master restart, the master waits for the namespace region to come online (it's `ENABLED`), but since no assign is initiated, it times out after 300 seconds. The `ClusterSchemaServiceImpl` fails to start and the master aborts with `IllegalStateException: Expected the service ClusterSchemaServiceImpl [FAILED] to be RUNNING`.

**Source Reference:** `TableNamespaceManager.start()` (line \~107) waits up to 300 seconds for the namespace table to be assigned. `HMaster.initClusterSchemaService()` wraps this, and a timeout causes the master to abort. The root cause is that on restart, the master sees the namespace table as `ENABLED` but doesn't trigger an assign for its region because it was never properly onlined in the first place.

**Recommendation:** During master initialization, add a check that all `ENABLED` system tables (namespace, meta) have their regions actually assigned and online. If a region is not assigned despite being `ENABLED`, force a new assignment procedure.

### 4.34 [HBASE-16276](https://issues.apache.org/jira/browse/HBASE-16276): Meta inconsistency on cluster initialization (Critical)

**Problem:** After a hard cluster crash (e.g., HDFS outage) during a write-heavy pre-split table creation (\~500 regions), restarting the cluster leads to meta inconsistency: `hbase:meta` is found on multiple regions, some RS fail to open any regions, and many regions are stuck in `Pending_Close` or `Failed_Close`. HBCK reports `hbase:meta, replicaId 0 is found on more than one region` and the meta region name differs from the expected format (`hbase:meta,,1` vs `hbase:meta,,1.1588230740`). This indicates corruption of the meta region's own metadata.

**Source Reference:** During cluster initialization with concurrent large table creation, the meta region's state can become inconsistent if the master crashes while meta is being updated. The `RegionStateStore` writes and `InitMetaProcedure` lack atomicity guarantees for meta's own region info entry, leading to duplicate or malformed entries on recovery.

**Recommendation:** Add validation during master startup to detect and repair meta inconsistency (duplicate meta entries, malformed region names). If `hbase:meta` is found on more than one region or with an unexpected region name format, the master should attempt auto-repair before proceeding with normal initialization.

## Analysis

### Recommendations

| \# | Recommendation | Issues Addressed | Effort |
| :---- | :---- | :---- | :---- |
| 1 | **Add null-safety to all procedure lifecycle methods** (`afterReplay`, `stateLoaded`, `restoreSucceedState`, `getParent`) | HBASE-29806, HBASE-29552, HBASE-28659 | Low |
| 2 | **Add hard timeouts to TRSP `CONFIRM_OPENED`** and RS-side handlers | HBASE-27975, HBASE-27773, HBASE-25059 | Medium |
| 3 | **Bound retry loops** in `processAssignmentPlans()` and reopen logic | HBASE-29006, HBASE-27614 | Medium |
| 4 | **Auto-recover stale region assignments** (meta, namespace, user) instead of waiting forever | HBASE-28192, HBASE-26754, HBASE-26287 | Medium |
| 5 | **Auto-fix Unknown Server via CatalogJanitor** — schedule SCP automatically | HBASE-27711, HBASE-25142 | Medium |
| 6 | **Validate transition codes** before persisting state during master failover recovery | HBASE-29364 | Low |
| 7 | **Fix lock ordering** between SCP, DTP, and TRSP to prevent deadlocks | HBASE-28139, HBASE-29256 | High |
| 8 | **RS should report state on duplicate open** instead of silently returning | HBASE-26283 | Low |
| 9 | **Allow disable of tables** with FAILED\_OPEN regions directly | HBASE-29190 | Low (PR exists) |
| 10 | **Detect stale TRSPs targeting dead servers** during master startup/rebuild | HBASE-26914, HBASE-24924 | Medium |
| 11 | **Fix RSGroupBalancer `putAll` overwrite** of region plans | HBASE-25092 | Low (PR exists) |
| 12 | **Improve ZK session resilience** under Kerberos to reduce false RS deaths | HBASE-25274, HBASE-22918 | High |
| 13 | **Prevent PEWorker thread exhaustion** during meta assignment by yielding non-meta TRSPs — **fixed in branch-3 only** via [HBASE-28196](https://issues.apache.org/jira/browse/HBASE-28196) (3.0.0-beta-1); not backported to branch-2 | HBASE-24526, HBASE-24673, HBASE-28196 | Medium |
| 14 | **Never give up assigning meta** — SCP must not skip meta due to null location | HBASE-24293 | Medium |
| 15 | **Master must not idle as active** — abort or keep retrying after recovery failure | HBASE-24292 | Medium |
| 16 | **Remove `master.abort()` from `updateRegionLocation()`** — meta write failure should retry, not crash | HBASE-23595 | Low |
| 17 | **Fix procedure scheduler starvation** for ORP/CRP during heavy load | HBASE-23593, HBASE-22334 | High |
| 18 | **Clean stale `ServerStateNode` entries** during master startup to prevent balancer loops | HBASE-23958, HBASE-22703 | Medium |
| 19 | **Clear failed-open cache on new assign** to prevent permanent RIT | HBASE-22780 | Low (patch available) |
| 20 | **Fix `serverMap` cleanup** on assign failure to prevent GC'd region resurrection | HBASE-22631 | Low (patch available) |
| 21 | **Don't block master startup on meta replica assignment** — fire-and-forget replicas | HBASE-21624 | Medium |
| 22 | **Fix namespace region assignment under multiwal** during HM failover | HBASE-21519 | High |
| 23 | **Add `interruptCall()` to `RemoteProcedure`** to cancel hung RPCs during SCP | HBASE-20802 | High |
| 24 | **Coordinate SCP and TRSP** region state updates to prevent stomping | HBASE-21623 | High |
| 25 | **Handle `NoServerDispatchException`** by force-transitioning regions instead of getting stuck | HBASE-21124 | Medium |
| 26 | **Skip already-OPEN replicas** in `assignMetaReplicas()` to prevent master abort | HBASE-21627, HBASE-21624 | Low |
| 27 | **Verify `ENABLED` system tables** have assigned regions during master startup | HBASE-19992 | Medium |
| 28 | **Validate meta consistency** (no duplicates, correct region name format) during master startup | HBASE-16276 | High |

### Action Items (Quick Wins)

Each item below has been evaluated against branch-2.6 source code, JIRA/PR state, community activity, and implementation risk. Items are ordered by confidence and priority.

#### 1\. HBASE-29806 — NPE in `RegionRemoteProcedureBase.afterReplay()`

`afterReplay()` at L392 calls `getParent(env).attachRemoteProc(this)` but `getParent()` (L277-279) returns null when parent TRSP has completed and moved to the `completed` map. Causes master procedure executor crash on restart. Detailed root cause analysis and stacktrace provided in JIRA. PR exists with `pull-request-available` label, targets 2.6.3/2.6.4. Very low risk — one-line null check, no behavioral change for the normal code path. **Review and merge.** Highest priority — this crashes the master on procedure recovery.

#### 2\. HBASE-29190 — Allow disabling tables with FAILED\_OPEN regions

`TransitRegionStateProcedure` at L400-404 attempts to reassign FAILED\_OPEN regions when the operator is trying to *disable* the table, which is incorrect — a disable should transition them directly to CLOSED. PR [\#6797](https://github.com/apache/hbase/pull/6797) open with active review. Apache9 reviewed May 2025, author (junegunn) responded with revisions. 7 commits, 29 conversations. Low-medium risk — changes TRSP state machine transitions but well-tested by author with new test cases. **Complete review and merge.** Operators currently cannot disable tables with FAILED\_OPEN regions without resorting to HBCK.

#### 3\. HBASE-29364 — `restoreSucceedState()` persists FAILED\_OPEN as OPEN

`OpenRegionProcedure.restoreSucceedState()` (L129-136) only checks `regionNode.getState() == State.OPEN` before calling `regionOpenedWithoutPersistingToMeta()`. It does not check the `transitionCode` field — if the remote procedure completed with `FAILED_OPEN`, this method would incorrectly persist it as OPEN on replay. No patch yet. Fix described in JIRA — small scope: add `transitionCode` check before L135. Low risk, no change to normal path. Recent issue (2025), not yet patched likely due to bandwidth. **Write and submit fix.** Data integrity issue — incorrect state in meta causes operational confusion and may prevent proper reassignment.

#### 4\. HBASE-28659 — NPE in `RegionStates.setServerState()`

`setServerState()` at `RegionStates.java:377-381` calls `synchronized(serverNode)` without null check. `getServerNode()` (L684-685) returns `serverMap.get(serverName)` which can be null if the server was never registered (e.g., after upgrade or if SCP runs for a server that was removed). No patch yet — fix is a 3-line null check \+ log warning. Very low risk. Callers are `metaLogSplitting()`, `metaLogSplit()`, `logSplitting()`, `logSplit()` — all SCP-related. **Write and submit fix.** Small, safe change that prevents SCP crash during upgrade scenarios.

#### 5\. HBASE-26283 — RS doesn't report OPENED on duplicate open request

`AssignRegionHandler.process()` at L108-116: when the RS receives an open request for an already-online region, it logs a warning and returns without reporting OPENED to the master. The comment at L110-114 acknowledges this: *"Just follow the old behavior, do we need to call reportRegionStateTransition? Maybe not?"* — but the answer is yes, we do. Without the report, the master's TRSP stays stuck in RIT waiting for the region to open. No patch yet — fix is \~5 lines: add `reportRegionStateTransition(TransitionCode.OPENED)` before the `return` at L115. Low risk — the region *is* already online, reporting OPENED is the correct state. **Write and submit fix.** This is a root cause of stuck-RIT scenarios after transient network issues. The fix should include `openSeqNum` from the already-online region.

#### 6\. HBASE-28139 — DTP/SCP deadlock on table disable during server crash

`DisableTableProcedure` acquires the table exclusive lock (inherited from `AbstractStateMachineTableProcedure`) during `DISABLE_TABLE_PREPARE` (L88), but doesn't set the table state to `DISABLING` until `DISABLE_TABLE_SET_DISABLING_TABLE_STATE` (L100-101). In between, SCP creates a TRSP which is blocked by the DTP's exclusive lock. When DTP's own unassign RPC fails (because the server is down), it retries — but TRSP is also blocked, creating a deadlock/hang. JIRA-attached patch `HBASE-28139.patch` from Oct 2023, not reviewed, may need light rebase. Medium risk — the fix changes state machine ordering (setting DISABLING earlier), a subtle concurrency fix requiring careful review. **Review the patch approach carefully, then develop a tested fix.**

### Medium-Term Improvements

7. **Remove `master.abort()` from `RegionStateStore.updateRegionLocation()`** — replace with retry/yield (HBASE-23595)  
8. **Validate target server in `Admin.move()`** — throw error for nonexistent RS instead of silently reopening (HBASE-20252)  
9. Add bounded retries to `processAssignmentPlans()` (HBASE-29006)  
10. Add `isRegionOnline()` timeout with auto-SCP scheduling for all system tables (HBASE-28192, HBASE-26287, HBASE-26754)  
11. Implement CatalogJanitor auto-fix for Unknown Server entries (HBASE-25142, HBASE-27711)  
12. Add TRSP timeout on `CONFIRM_OPENED` with rollback and retry (HBASE-25059)  
13. Detect and re-plan stale TRSPs targeting dead servers during master startup (HBASE-26914)  
14. **Never give up assigning meta** — fix SCP to handle null region location for meta (HBASE-24293)  
15. **Prevent PEWorker thread exhaustion** — make non-meta TRSPs yield when meta is unavailable (HBASE-24526, HBASE-24673). **Note:** This is comprehensively fixed in branch-3 via [HBASE-28196](https://issues.apache.org/jira/browse/HBASE-28196) (fix version: 3.0.0-beta-1 only, not backported to branch-2). The fix makes SCP/TRSP suspend and release the PEWorker thread when waiting on async meta updates ([HBASE-28199](https://issues.apache.org/jira/browse/HBASE-28199)) or when unable to acquire the RegionStateNode lock immediately ([HBASE-28240](https://issues.apache.org/jira/browse/HBASE-28240)). The TLA+ model should account for the synchronous meta-blocking behavior in branch-2 as a source of thread pool exhaustion deadlocks.  
16. **Master must abort or keep retrying** — eliminate the idle active-master brown-out state (HBASE-24292)  
17. **Clean stale `ServerStateNode` entries** during startup to stop infinite balancer loops (HBASE-23958)  
18. **Verify `ENABLED` system tables** have regions actually assigned during master startup (HBASE-19992)  
19. **Handle `NoServerDispatchException`** by force-transitioning to `ABNORMALLY_CLOSED` (HBASE-21124)

### Fixes in Branch-3 Not Backported to Branch-2

A systematic review of ~400 JIRA issues related to AssignmentManager identified the following resolved issues that are fixed in branch-3 (3.0.0) but not backported to any released branch-2 version. These represent behavioral differences between branch-2 and branch-3 that the TLA+ model should account for.

#### [HBASE-28196](https://issues.apache.org/jira/browse/HBASE-28196): Yield SCP and TRSP when they are blocked (3.0.0-beta-1 only)

**Problem:** In branch-2, SCP and TRSP procedures hold their PEWorker thread while performing synchronous meta updates and while waiting for RegionStateNode locks. When `hbase:meta` is unavailable (e.g., during meta RS crash recovery), all PEWorker threads can become blocked on meta RPCs, preventing the meta assignment procedure from ever executing — a classic deadlock (see issues 4.10 and 4.11 above).

**Fix (branch-3 only):** HBASE-28196 introduces an async suspend/resume pattern:
- **[HBASE-28199](https://issues.apache.org/jira/browse/HBASE-28199) (Phase I):** When updating region state in `hbase:meta`, use an async client to obtain a `CompletableFuture`, then suspend the procedure. When the future completes, re-add the procedure to the scheduler.
- **[HBASE-28240](https://issues.apache.org/jira/browse/HBASE-28240) (Phase II):** When acquiring the `RegionStateNode` lock, if the lock is not immediately available, suspend the procedure and release the PEWorker thread. When the lock becomes available, re-add the procedure.

**Branch-2 Impact:** In branch-2, `RegionStateStore.updateRegionLocation()` performs synchronous meta writes that block the calling PEWorker thread. There is no `CompletableFuture`-based async path. Confirmed via code analysis: `RegionStateStore.java` in branch-2 has zero references to `CompletableFuture`. The TLA+ model must model this synchronous blocking behavior to accurately capture the deadlock scenarios that are unique to branch-2.

**Source Reference:** `TransitRegionStateProcedure.executeFromState()` → `RegionStateStore.updateRegionLocation()` blocks synchronously. In branch-3, this path uses `AsyncTable` and suspends the procedure via `ProcedureSuspendedException`.

#### [HBASE-28158](https://issues.apache.org/jira/browse/HBASE-28158): Decouple RIT list management from TRSP invocation (pending for branch-2)

**Problem:** When operators bypass in-progress TRSPs, the bypassed regions are removed from the master's RIT list (since the list is built by tracking TRSPs). This makes the regions invisible to operators and monitoring tools even though they are not properly assigned.

**Status:** Fixed in 3.0.0-beta-2 and targeted for 2.6.5 / 2.5.14, but these branch-2 versions are **not yet released** as of February 2026. Currently-deployed branch-2 clusters are affected.

**TLA+ Impact:** The model should track region assignment state independently of procedure lifecycle to avoid masking regions that should appear in the RIT list.

### Long-Term Architecture Considerations

20. **Add configurable hard timeouts** to the `AssignRegionHandler` and `UnassignRegionHandler` on the RS side (HBASE-27975)  
21. **Redesign procedure bypass** to properly synchronize with child procedures (HBASE-28013)  
22. **Improve ZK session resilience** under Kerberos — provide separate RS/master session timeouts to enforce failstop invariant (HBASE-25274, HBASE-22918)  
23. **Stop blocking RPC threads on procedure latches** — return procedure IDs for async polling instead (HBASE-22334)  
24. **Add comprehensive null-safety and state validation** throughout the procedure recovery/replay path  
25. **Improve procedure scheduler fairness** to prevent starvation of critical sub-procedures (ORP/CRP) under heavy load (HBASE-23593)  
26. **Add `interruptCall()` to `RemoteProcedure` interface** for cancelling hung RPCs to zombie RS (HBASE-20802)  
27. **Optimize AMv2 cluster startup** for large clusters (500K+ regions) by parallelizing report processing (HBASE-21333)  
28. **Consider adding a "region assignment watchdog"** that periodically validates all region assignments and auto-heals inconsistencies, reducing reliance on HBCK manual intervention
