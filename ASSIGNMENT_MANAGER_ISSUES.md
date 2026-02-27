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

### Root Cause Common Patterns

![][image1]

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
| 13 | **Prevent PEWorker thread exhaustion** during meta assignment by yielding non-meta TRSPs | HBASE-24526, HBASE-24673 | Medium |
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
15. **Prevent PEWorker thread exhaustion** — make non-meta TRSPs yield when meta is unavailable (HBASE-24526, HBASE-24673)  
16. **Master must abort or keep retrying** — eliminate the idle active-master brown-out state (HBASE-24292)  
17. **Clean stale `ServerStateNode` entries** during startup to stop infinite balancer loops (HBASE-23958)  
18. **Verify `ENABLED` system tables** have regions actually assigned during master startup (HBASE-19992)  
19. **Handle `NoServerDispatchException`** by force-transitioning to `ABNORMALLY_CLOSED` (HBASE-21124)

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

[image1]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAi0AAAKsCAMAAAAX2309AAADAFBMVEUAAAAEBggHCw0MBwcKCgoJDhELERUNExgSCgoSEhIRGh8THSMVICcWISgYDQ0eEBAYGBgYJS0eLTceLjgfMDolFBQnJyciND8iNUAmOUYnO0gqFxcsGBguLi4uNDcpP0wqQU4rQlAvSFc1HBwwMDAyOT0zTl80T2A3U2U4Hh4+ISE6Ojo4VWc4Vmg7Wm0+X3M/YHVDJCRBQUFASU9DTVRHU1pAYndDZ31EaH5Ga4FJJydNKSlOTk5Jb4dLcopOd5BOeJFRKytXV1dQXWZTYmtXZnBQbH5QepRTf5pUgJxWhKBYLy9aMDBdXV1cZmtaanReb3pdcn5YhqNZiaZcjatekK9fkbBhNDRgYGBiaGxidH9ldoBljqhnkKhgkrFmm7xoNzdoODhqW1toaGhodn9veX9qkKhqn79onsBqosVtpsluqMx1Pj5zc3N0e390foNxlKhwqs9yrtR0sdd3tt14QEB6Tk57VFR8XV18YGB+bm5+cnJ5fX95f4J+gYN+iI5/mal4t999ud98veV9v+h/weqCRUWHSEiCdHSBeHiAgICCiY6AmqmEvN+Aw+2Cx/GEyfSHzvqOTEyOhYWOjo6Lv9+IxOmKz/qN0PqQTU2WUFCQkJCcU1OampqcnqCkV1elWFikbW2kcnKmf3+gn5+hoaGuXV2oqKiyX1+1YGC2tra3u724YmK8tra4uLjBZ2fEaGjGxsbLbGzKysrRb2/UcXHXfn7XgoLT09Pddnbd3d3gd3fheHjhg4Pm5ubvf3/o6OjwgIDx8fH///8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACnh6ZOAABYkUlEQVR4Xu2dCXxM5/7/vzOTfRMhqUjEmghVsdRFSd262qDVWkO1LqHtbURRrhTlovqLovYlpXalliitWKL9q1tU9RKNKkkUjVgiIctkT8zM/2wzc+bJLOfMnHMyk3nefXXmnM+zzGTOx3Oe5znPIjsLGAxH5KiAwZgEuwXDHewWDHewWzDcwW7BcAe7BcMd7BYMd7BbMNzBbsFwB7sFwx0XVBCUAvB3RTWCtD/fDEA17hSqjGZqlkIVgKKRApVRah89qQjxsRzPIqoB8IM2F8EyrX9EdcvDWJg0ARUJduYO4e+WWjnze0/JXR9lGGSZKbnEi8tqo+l0GUPaZ0/Jt60RrFAreQpqJlcBM613RHXLF4QxxstQlfjl1F6oZJE55/h7hM2yFhvOTT9t5MvoM77wqcv6ji5F+UGGEWxDlEzrCzHdknH6m2Nb4wnLTEt/tv/5hAjd+6TcPWHv/zHmZDGcdoWxuZ6VLk/bbScPap9CUCHx7/LbgGnpLVqeCzqkC717DVZ3mKW/2Nqsoum3IcUHgssHwlnQpmPC777VYi+doEVIKATLSHO4DDoKQxK1B2PIjBPJGAWJcIq4xQUQxd6IwP4n/uy7hJ3tB/QfYJA7nbM28tjH9N8xLR2G/KD9nqxMqQ8kPlj3B+t/Fm2uRMJK4iep89foksw5B91gjfHvIQFiuuWToMChW/+o9IQbMCwmlhC07wTFf0DCxFfoY99jpW/8qSIK7pBtNyflr496/49cSIfdiiH5+tCwNunT2WWLLitdjhSFdLrCACY88EsfJiS+GPy/JCKcg0MB42KPJlRrD3QZPwHQ1odSZPDKkF+YE5IbEBtbN3ckMv1Nq9LhQHDCQCZQnyn1yUePJnjr/mAmF22u7gYJqSTaz9MmKTkHe8JMfQ8JELFNVJjf6SFhlZ8AxsKnIzJB/06gJP53YyIucfWlD5JkTcE/CtpABeGTGdPKDEMN0GWly5GCSVemDfeMDGVC3DxhoDcZwSUAmhFXUXegS0vcHVXMYdWOaZN0OslYIHNDc6fRRaa/KZFhM/DQBuozpT6Q/jzmT2Jy0eZqmJBOwnyeNgmRRwu2bvA9JEBEt2yB07GxlbAeYMJ6z/x3N+jfETyQb+FNvXbtOp66CGgojTarujmS6TzrftSafbCvAKCa/KdO3tB0Bzr8qMtAUvvGVujPDiKyY3Krm7s+Mv1Nq8CFla0+U+oDaZg/SZ8LlathQgb687RJqnVfue73kAJjV0IYNCee/eabbw5A8UOAqFOTIAX078RNh/h/t0F8A5oAvDphwgSW4gVZrDNdVrocS2EH6NIFagNUheXaBAFDYKIKmkNlORQTV1F3oMvYPwhmUuWA5lLls2smAtWUobMlsiNzQ3OnYEWmaAFPi+GW9kyfKfWBlH100Lloc2UnRP8ahubw9KGBzvoeUiCeW24+nRsYGBjcH9bC2B1370Aw6N4JFO3gpZ2mPz7wWXhrw4U5LGUM7M7Q3ihAnxXz1g1mxu0DXbpsbfj9N97VJfnQpXgFBLaDf2ZMgmf9dQdkxnSELyB30IGMBa/80gJuHyCqA3d12RLZkbmhuVPoIjN4BsEw1n1Mlyn1gcTn6UK0uWhz1Ses89doIfIYu+OC8e8hBbKzqCIKlR50Cap9Byj3UBxY539UH6UO+rg0+n4RCiZYG6tWV84jAXXQVJFFO+uAlbGmxJ3SVHIZnYEuWw2Tm5HcdZG1VDIfwKDNVP/JWnSptLl6qv5+mvzEOn+NDk2VwRdAg8VFzDYRC+2vpP+1Pj3nWQkrdKdGQH5Z/W2fhgnWxtKHIgF1kGlDtAesjGXMP33SPlSoLkx7UYzkrousBflobab6T9aiO0dzrfPX6NDmgaaQBIncUpep7U+1nCphIeogyPuaujnbAxLdiTANAnt2MsbewG7BcAe7BcMd7BYMd7BbMNzBbsFwB7sFwx3sFgx3sFsw3MFuwXAHuwXDHewWDHewWzDcwW7BcAe7BcMd7BYMd7BbMNzBbsFwp97G5WIozj+PDE63U55eegFw2VLfFDmGWcClmHzFbqlfNKhgr1BfFLsFwx3sFgx3sFsw3MFuwXAHuwXDHewWDHewWzDcwW7BcAe7BcMd7BbHp2hjwQ6oilmYeadkY2ZM3v2xAIWFMefPA5SNPP/WzY1Fy0vQJFaC3eLw5M3sP38sbOu9MLJ1o/eDfTVXukKVf/HTtn0AkiOCwvJHJYz4SI0msg7sFodn5pzEBW7wbQJxeHDG4YLGP78CT+Stxo3OAEgNzeoT4VkG9yvQRNaB3eLwrF6Y/LEaQh6AumDLyj5+Hn81LZ2mVk2IOwVqxcuvDfacuuLTr7QrktuIYiKqYKQk2/ZF+r17BXf2l3X8aN+vr+5L88/on7/sF83LQ04VfOwme2HOkW8GvOr6OnuRXiv5k/yieN25+uXoq6hip5wgvyi+E2G4g93iINy5gyo0tVVWt3fUhVWoZAE8LtdeKHkdoNHqNqisZTFsQyWC2g9uQGAK1Gq0+7Hoj4gMk/poj41SOmwts4kPK5VZcNliP8RtKpmHahb49cb21CVQNeAUc64/4oDvkQ70AedUuGyxH7pGBpaWHkr9NPL8Z+U9F3hkzFN2TIbUdbAsaseuXo+bQtH45JDpzRPJKH+SKpWotI0vrINVj0fOynJdH04eTSCSpDFZlr417nDp5mAqKzpXRtlx6J+jysevCN9xqDx+lDbVsih2/DmPtu2ACbpvR4Jb0PWLvgVd/fWJY6PnPVX9u+n5uQf/tfdrr/n741+D1OUjfDbLtn/z2vfwRtnuQU22yAZWqf79C6kO8gEIG5W0/NjI57+aGevWM+ranrjuxBGZ5FkfMsN/hFXt6Dfnh9MKMis611haiUms/T9F1Y4B/om18UMV2lSbB7no40PPDd0OLNB9UaoFje9E9sPalFEeQFzmB6AA9+pK5tp4vZpUQG08oYPsaiNUakMIn42vFZBbFsHNkT/rNvr0elW/WQS5VxqVFZ0ro3gcav8BGUq8r6cOQJulLj74tl3K3vKHBLvF/hgAZ0qyRwyATVVFEA0VURGvw56MW+TOgkcOFlAxKJV0UF6eyhsUbnBJfUTxHlEBIY/IQAN3AZUVnSujlD3dcINsSxHvbW+otamoLLXxAd550labAwOut9gfjWdtWvnisEazNh9r9F2jpFX7FVv77L8UQrSYuqZ2pfdBo9TdRBlzcjt4xDWDuO3Fs394e8SNbZOJo9VE4F6Drv7GVFZUroxSNla1liwoiHcP4oBJRWWpiw/dPkDLEtyXW78Y78tVq8gpjOoaak/OKg/d7lb6Ta4olVQqfMlLWquQq0FOvJJHukA9VFZ0rgzaKFVuTHqDVFT8O8GsbHBfrt0ipy6rnL5Y5CtzmVlXm7mQro2oK+gqB7mcfKX+N9z5lYTKis6VQRvFQ5veIBUZXz2vbjaogMHQyPegCnYLhgfYLRjuYLdguIPdguEOdguGO9gt9Yvc6tEp0qKmjILdUr/8LRdV7JN7z5OvuOe/fgmsvIhKtqBcP0WAEdt1UXagHjlgt9QzYWGoYguqnTGoJCT4TtSgqBGlZNGB3dKgKH4OVQQFu6VBUUuPaBAL7JYGRQU59E08sFsaFEWNUUVQsFsaFLleqCIo2C0NigJxtw3AbmlQZOMWNIYzSm4zVK0Fu6VBoRS3bx67pUFRI0MVQcFuaUhwXCrBarBbGhIVoagiLNgtDYlqf1QRFuyWhoTKG1WEBbulIVGtW2ZBHLBbGhJqcTv+sVsaFFWNUEVYsFsaEk9aooqwYLc0JER+BI3d0qCoEPlyipw9RlLKDVcQExzsloZEuTuqCAt2S0OiSOTLKXL2GEkpwmULhjPV4g5vwW5pUFSLfDlFzh4jKdgtGO6IPHQOr7FgHeefF3cqBkqOgstSDGIPncNli3UUSWsWaHkdVYyhFrlJhN1iHRpUsAuwWzDceYrdguEMLlswdgR2SwOiVuThLdgtDQk1bkFjOCP2BBHsFgwPsFsaEE9x2YKxH7BbRKVoY8GOqpiFmXegZGNmTN59gJjCwpjz56Fs5Pm3bsLGouUlaBIbEHn9U+wWccmb2X/+2G29F0a2hkbvB/tqrkBVlX/x07Z9IDkiKCwfRiWM+MhBNhGhwG4Rk5lzEhe4fZtAHh6ccbig8c/wpK281bjRGZAamtUnAjzL4H4Fmsh6xJ6qiN0iKqsXJn+sDnkAaijYsrKPn8dfpdOeV6smxJ1SK15+bXBg2dQVn35lsM+3naOYiCoYDmRHoIpRvHsFd/Z/9qN9vw5U7Evzz+ifv1szwW3IqYKP3V+Yc+SbYeWDXF/nuAjlrXaoYoQCjbhLceO95q3D+BbxInJqIKoYIbOoNyoJC74TYbiD3SIwtRlIm7j25vk6rWQiki5ebZW2VWQspn2B3WIjJf3OG5xXTP3L4Bz+b8oDstlT0m8jQCqjEZGYeLXvD4iJNYxJiDWMwpNqsZ9B41HcYnN24Cj6YP8oI51nv97YHpjHHGtjVsXMek0fw57AbhGEsllZruvDM+YpO35GXO1Ji9uQ4vnPynsu+OzpiRvb6EjTd0PR+OSQ6c0T2UlL2/jSqReSMVPXLYtaB6seZz3aIt8BE9gR7QB8JxIEn08WN3kndequ/yYDrInZRJtl7lenH729EP7JmOUIDLj7tKQC7j1gJexzLLnfSC8yNZAxU5d3n5r3AXw4Ye6tLzJ+msCKaBdgtwjCzZE/B0Gl9tc8Q70+AAW4V+vjyFer9uvPtPhsfK1gF5ma4dUkT/LNt+23S+foY9kJ2C02k11UVHpE8R7AANhUVQQwrevyAlIfAGdKskewIgZOADc4cpAK1JKXp/KGLDJ1FXkaDVERCje4pIZ3qp60ZUe0C7BbbGbH0KHzx7m+3cWj8axzMeMJ4VOXeLJR3HjWpmHRw9gx3/Zr1DX1vEFd9+ToAd/GTSNTU/erRkmvji6Rx/04A7rBB/Z3bXBfrlXU6ctVg7zWFdQ1Hgaiqu6UxlpEqq3wlVOpFbQ5qsgciJM7E9LYeXHqy73QOBKVhAW3iYSBuNSEDeQGZgG5kdIB9Y8r+dyYTk1B5UCctP6vPo7dYOTvwTgqYnfOYbc0KFgtMFHAbsFwB7sFwx3slgaEu4CjNo2C3YLhDnaLVcglHqlfHowqxpArUUVgsFus4m+5qGI7ZgxYkRKFSvUC7p2zisDKi6jEgaS5qMJCublnNKpp8YpDFaN4iD30DrvFOsK4rDFZB7ODrF/8MGi8yCuY2gq+E9kNnsle8eWoyAs5bhM5D7LYL+TTbFktU2Gb2SyD3WJXeK62pXhxsSEtJ7Bb7AvZvHfvohpncNnibIRuXbHB2ruR3MqZJZzBbrE3PNcEWns3QsfOCA5uQdsfsbG1c0InW9GYlhehisDgssUecV3SbmIxKlrGBY9vcU5ilk3ORDWLyLFbnJTAnTuXqVDRAopSVBEY7BYJ4bVUtuuS6HcKUbGewW6REn5N494rZmagmnnc+H0Ab7BbJMTdzKAEYwRsj8qI41O+BFITHsUDu8W+iVox8wKqmYavHfmC3SIh1lzMgC1n59Sioin8RW4UYbfYO4rE8eMNJtqbIQi7peHg/hRVOBG5MTEN1YzjzbfNzRPsFgmx5k5E4r/tT253o2CRh0Ph50QS4sXpkhtBlgDlH/TXrmZompZPyNdKar0gMcBli4S4WVm2kHgnF0yrpA+NdqpUks+VvMipCA+teMLEEewWR0GWMHPSPeroKhJC4XmxkHj5C6B8ZjM0TDCwWyTExkpo2JefHiDLlU+M3tD6TykE1yJQTWlpxVgHjmC3SIi3dW0iHd7JFdOJu5HfHjSAxPWD8YXuRfDJ4zFoiHAoJqIKRjT+am7jBkKyLoNqplb4H816CQ0haNH33YOlqTlN/4UGCAcuWySkkQCPcYjypR3cNdpdF9YUoBRELFqwW6TEz4Y2kQ7ZhJ1QPR9VKZa5Ee2unqgqINgtEuIlQMd8efaOaqIEuYvqJIHkHlv+qCoguHdOQtxtHWVdcOwQczB7j7GWz7x/1gxBNSHBbpEQm9dXCZwwofz+b7t9odT9qrE1OryHHIpBNSFpwG65+7QVKglN7aU+qGQOr4eoYoyUlgpUMqDdIvL16f+Mxers8pDTZ5jnj17hqETTgOst11uhiuC48ru1uOejijFuGLNBHWTGY3VEBWt49mdUYWjAbjH6OEVg+H2G6POUxaYBu8X+EH2esthgt0iIq8jDT0QHu0VC3GxtE9U32C0S4voYVRwM7BYMd7BbpMQXFRwM7BYpcUcFBwO7RUr8UMHBwG4B9f1U9cPzGeraTIDbJVCYel4N6qz7aDQhCEEFq9Fc3nsZNGePq0FzOPfQZYCsfYcI9SRxBLkp6dmq4wBnc2mBiUEnoWJYi1O7pWhjwQ6oGvRlu9LDTRLz8z8BiFcVvtkY5GWxjxNvwsai5QIvhe6NClaRfGOb8qNf/CuUH998NBuyzuwu2aXO+tIFoHh+eWoaFK+ruVD6268ARytogY5BJaGFLdkbrNunwJndkjez//yxsK33wshG7wf7aq50haoq/+KnbftAckRQWD6MShjxkRDjl/QYbO5rLemtDsfuaZkwOHpP+MQ4tfJGwOxY0ORr2oyAr5sE+pZBStTYyeE3A0FZHUELdAwqCS1029t5B5orJ5zZLTPnJC5wg28TAA7OOFzQ+OdX4ElbeatxozMgNTSrTwR4lsF9YXtfbRyWS/Pd2YE+t/oTB9kvgFIFD9rLs70VfTvuPAyZvveatYZrPSDLLy8YrgbIaIGOQSWhhcBqKLWqcGnAIxYssnp68sdb5CEPAtVbTtw65PFX09Jp/dWqCXDqOcXL4Wp52dQVn37lgyayCUFm+owpT+3me72DRuZdDgc7+RX0gjtBmvJJWx+CPLwfEe5SpjoZWdwj90QoI1AxgEpCCbnbBp5OaIFmywVndkvgsuB5AHNmyNrAuKGQ13VcK7f+pcOb+X4u3zTHDb6qWVWzVlizCNOCjlRXaF46canR7D4p3z3zlqYoHPJa3F3nK58Gw9J+fLpY1nH3N67wTEpj15a0AFQMoJJQQkVc+YfWtc4a8F7zJ0QdRkZzjN/AxodctjBbjO5jzxtN4nJU4sfv41GFxpnrLdIjSNliGZmNZjEJdgvUltVt+Ny5A+pCavYP8yYMIrmljO+zbU2WsiwHFTngFPWWkteJSsp641XMgun3AP6LqothW+mwtVG1GjfyDQ21Gl5LoALkrIRF/rCo8P0ObFWFjLFULX0E3kmqSnYdy/CMgsgMoOdY6vjuxphn/2hpGM4Fp3ALwJSIqZ+tRkWS2rdhb5DR4bK+R7yrYma9RryhIdbD/9f+cVgxsqilcv4aQ+HIo5Gtbivn92ZPUpxhcMbwXF9oTh+FfdiUWumFL/y/v0PSub2fEspmZbmuD8+Yp+yYDKnrYBlZZtyqiguBEDj/WXnPBbVvjTtcujkYduzq9bgplI9fcQRWPR4xfkU4GbqklA7dcag8fhSaPVeMzQEyTxb8P+I1d3eBy4AY2JZZ22PsHtg2cW869B6RuzH8zxcHkmOD86NbJMOlipepWJRMnPXd/mrfJX6vE2fyYd2pvPwjtTnd2/T3SMimIiTQ2VA5Gn60EZyk3jK1X9tt4PPJ4ibvpE7d9V/CLMvf6D41jwj4BciW0/m5X51+9La6xHNv4MeQsT0lqSlRYSmp+AA+nEC8UaFAh1btKo8fiubOHWa9Hs4Mf3jop8EALYb38T4OpVdre4yGt2DivovtQ39KV1f4JRFmgZH/zpo29014fiIdi5afn6iuUEF5qbqiUVKbXXRX3OWkz5icyDDqpbyUjk/naPjZRnASt6yNu3Ifbo78OYi4XvSf7PVqErngVlcgFwB8AApwryauChlyBZCKARUKdKjHofbrPzAM5gPfqa2tvc41I5ybllzgQdwbJwddXEqqalCERhOyFx2pxdyIcnLRbjqWTqYiAngS353uQ+4+d7YuDisCGV+bowWcxC3wlkd87RHFewB9YVNVEURDRVQEaYoOHrtTy+4PgDMl2dqC+GXYk3GLOnKDS+TPSYUygWVPN7S9UbcNxRWj6/SYo52auH3AbRlRwCiLVbMDHoEPaPpA9YBOuv619HSVnFDzNHQsWszTeMG17eSMlOycG776vjhWHG0EEsMcTeIsbnFdU/LlONe3u3gcnnUuZjw0Sjr96mjyAbPbV88sf3Vc41mbhkUPY6IG9dm/hh5aII/7cQbxRoUygWWjX7q/1vofjW/ZAgN6/oN47emyIdD1wO3N08v6gbzz0pbR9xZsog1N8NvOGbejune+s5SORWnEWViz7Hyygl6ytpS1KzkrjjYCiWGOJnGqvlw1yGsVcnUNVRZXaUvkqqdeclCrWNvM1eqOiejkGzu0yo1lFp59uXCXw6bjxvpyNSBTyWWgpP75a4jKMn1EU1ZE3iPLvGVMLErzllFN7ZyVMQNl7Mo1K45hW5ydo6m+XCdpE9HIqa0H5bRNdLdv6kDOLi/01mCO2KHs2z5v7nNwizGIy0teWfqCkteafdfwofpWfHSxmDPquCXS3GbHMayfWb4POc+dyE7IQgXHArtFUoz2AzoO2C2Swm9NBrsDu0VSyA5BBwa7RVL4Piy2M7BbJMWPd/ecXdGA3dK8DFUER83352vGYb0fAZ95W0lJe1Rh4PvnOhBRhzhcGhLrO/Jze6CKBRpzKFvG3eE5DEZonl7qhUoMDbl3Li6D0/yO3P3v+YFy/RQu3VOGKDsEoZIFWnF4CB3YQ5R5ktzxmoUqWhqyW4DboDev0TEAms11nhOIQQsu/g2zssNXfBrwnYgjmqXksx61SENmEZpYMxrWfsBumbiRHOdSw/8+ZA1NTa0t6hg4vVsehlEL40tUtng+QhWHwund8tkU6q1WP+BMTDyMbhXjMDi7WwqrBVn3gCsynusx2xnO7pYtc+l3tUR9HIECzmWTHid3y4EYprVazbfnxEp6WTWPx15wbreojnRCJZFpL/7jCBFxbrdcG6odbKiS6OlMiNHNnB0F53YL1TEnKZ5/oYoj4dRuuduF7JijUEvTggaven4GZBtO7Zakd1BFdLzuoYoj4cxuWTYvQHdcJcgCgpaRhXIcRmGXOLFbKm+EopIEhHB5Cm2vOLFbjk5nnaileaoIEOXIw/6d1y2q/VL3tVC0voMqDoTzuuXmaGThDWnwJdfOcFSc1y1LjUxOlwA/R25CO61b5iwz6L2tlqi/BbwLVORbJqo7BM7qluIiSUcqsGhJLhuTKVGDXWCc1S1HyGV86oWuZQC1HxtfjtXecVK3qE6Fo5Ik1AJ0Jioun3biv7qlPeCkbrk2vn4u15gD0OgsPEx/Cw1wDBr0fCKT3EtLRCVpOPT+fqgpT28agQY4Bs5ZtmyIQxWp+My9xi0djCyU7RA4pVvqr0EE/kNDasCtJyo7CE7plvprEAGMUroRnkFVB8EZ3aI6Wj8NIgrZCj9w1KLFKd1ybXT9NIhogjsAve2LA1KnTXS3PgZ98OcvhRXb69A8/GYRKgG1yj8n7v6vNSrxZEBovqBLW6pyRqKSWNQpW66jgn3Syvrv+cX7qMKHi7aaBYBcuV9AFDdQRTTquMWhZ15yofKu0a0wuQ5p47BcT8OljlsaPD+xh8xheOF0btHs7IhKGK44nVty+7A2C8Hww9ncUpyUgEoUcgdf91ganM0t9dmN6/g4mVtMduN6kCPaMBZwMrfcrNduXIfHydyy+hVUwfBANLeo76feVz88n6GG2kyA2yVQmHpeDeqsep0gsWGuqae/cq69c/zQXN57GTRnj6tBczj30GXI2neIEE9eJoJyU9IvZ6uOA5zNpRU6Bp2CimF/iOKWoo0FVYO+bFdTerhJYj7kfwIQryp8szHIy2IfJ96EjUXL66eWoDpNbfhsDIXQk9mzkm9sA+VHv/hXKD+++Wg2ZJ3ZXbJL/aUL0SybX56aBsXrai7sKv3tV4CjFbRCxyBT0OdbsjfQu37bD2K4JW9m//nbei+MbN3o/WBfDVzpClVV/sVP2/aB5IigsHwYlTDiI+t3YrABCWst6ftaHY6FPS0TBkfvCZ8Yp1beCJgdCxpNmxHwdZNA3zJIiRo7GcJvBoKyOoJW6BhkCvq8297OO9Bs6xkx3DJzTuKCb8lujYMzDhc0hp9fgSdt5a3Gjc6A1NCsPhHgWQb3xSn4LSBhreW7l84O9IFb/YnD7BdAqYIH7eXZ3oqOOw9Dpu+9Zq3hWg/IcvfLC4arATJaoWOQKejzwGootbPCpc6IBQFYPT3545AHgeonW07cOuQBfzUtndZfrZoAp55TvByulpdNXfHpV9SmtBJT6G6q1kL8DgLficbsG5HaDXyvd9DIvMvhYCe/gl5wJ0gzaetDkIf3IyK4lKlOBkNxj9wToYxCxQAyBXWeu23g6QSTd876QQy3BC4LnqeaIWvzGYwbCnnNuo5r5da/dHgz38/lm+a4wVc1q2rW1odZ4OuZqCIekQO6VmhkL5241Gh2n5TvnnlLUxQOeS3u7pBPg2FpPz5dLOu4+xvXCHgmpbFrS1oBKgZcIlJQ5xVx5R9KtUwIV+rsNV93h3b7JG0Qqlgi82osKukpXGNkiJQxdgm2jocmcb4wbjg2H1XEQox6i51idlEFeQ2qiI5suTBmkRDObqnNMNPqvcNawoZ9jKAuNLVuuekQwSj0M7ckrqAPplW5dceUlR2vuzWRJsvwWWYZeWosMRsqUj1hpt5SMHJTJIzsP5k+q5i61vRWYothG3JMVNMC12unhtdq6FX0S4cZ5nEQRvWn1qcI3IqEoGhzsAELtRYrGmk5K597B/Zde3clgPeg6BzirTc5rSxzXxFAa3TM1ZM0qDOS+O7GmMH6M9XSR+CdtJBMPIzOEmAvjP2Q6mzweY+QfEZ0IyOtIT+KCpYaM24B4Dqy2RhTIqZ+tpo+rIqZ9Rp14HvE8J/3iaWQorq4fG1zFzQEQZeD9ahOM7Y3jtyqv/X3zEhyaGrn4LQ04trFQ3NCU23V/LN9FqcF2sM+bMo6O/JoZKvbqjI6MZPlzSHwvib94t87eAD09DxzqhsZCXTBUmPWLRSlb407XLo5GOD8YrflIec/K++5oHJ8csj05vFMwI5dvR43hdR1sCyKOSbp3N6PKDIpdR2sejzireGpn4aMXxHOxDtUHj+q6n4TCID2EBQIpeNXNHtr3P7qL5bfIELpOMRHHac/KZHMYQKVBvly3Lk1RIyeuf3/IV/9B58jqz306Ozvazp3h+6QuzH8z+5ZBS4DYmBbZm2PsQAPF1aP6rY3HXqPYAS4t+nvMbkbn8smdCBHROdHtzjZgUycw2SpLO1K/D450DKSeAkYeKGGiqT/RKmxXG9Rl3juDfyYOOiT8srYg3O/Ov3o7aclFXDvAROQsT0liTDL8je6T82jjymm9mu7jVE/gA8nqEvCUyLVJRW0UrWrPH4orFuj+3QiRF0SkQIbVg9IZFKdJz6K+SQgc6DTWEvxygmoZICLVWVLgmZGKfH207xOZLNkz9ek9gC6km/qCr+kEcP7eB+H0qu1PUYTivdCj6P7LrYP/eksIxBxVMT/ckInz0b+O2va3PtUYm2Wu/uzLP7/pgX8h4qk0X2i1JhxixtkE7XPQABdF5E3lIGCNfWGCrgC9Gp/Xq8meWqPCdbGXSEfIJIqLWi7WEjF41D79R/AT8jUJS+Q+dN7Z5NxHhB5sS8hncZaTqH1CATr7kTuY9TXiLcXPx3rS7y99SapNSN+EQovSEsuIO4gvpODLi4lzskWkBoUodEtGYFB1zJqMTeiXMMkprPMeU4bRvCPqIeXqUjpuk+UGjNu8VUcLrml6qw7P1xyOGQonCnJHuEGRw7qtnx7GfZk3IJoqIiKUNDHNG95xNfSqhtc0j0WopWypxva3ihyN9FHR8cZQHyU9pPIHKg01j5e0hxpi0qGWOcWiNT/PABZWWRH/Yuu17/OvXycFG7LiEqsslg1O0C7nWIfqB7QqYIlsEhPV8khUpeYINvVoC93vFuKiowkxi2VG2bqLfIt815XvBROFrUUxcOfbmk8a9PKF4c16praVbdKQVCf/ZdCoFHSqv2K3fQxjeuaf305mVJ94rYXa/u+6HiKsSqPtdeNVFypH4KOE0J81GHmk+REDrPJNGbMbZb7XQRtIuuZMEd/7TZCyxlEGfrOV7/8Io94lhB63trQ/vGBbrvVbmTPPknL6CsL5H3P6QUWv2WAW1Tfk9rEBFfbGERQvJS2Q0ZEiq/bFpcI8325VW7sy6MmSyK1ivzda9k/PnNS5YEG6FUFKx9KIXKOn6c1Vl2oONRHMRmSORh8G359uQveNzrljMUQqu5gGS59ucoSpkzQgIwsCpTsbjjyxEDQUVZEJtMlBljySndWMA0dyQDp+nLNlC0E5DXTQ10rOfVq4AnmhIqMmsWISinESzJbRKDiUB/FJCXfDL8NHy4MtGQWQfHTmoEod8iKnIE3yBOjZgEf6s6sSwwwR3ekh45UT1hbtDsWm7ugSh3czPegYkicwi0FzXS7VpnE3doKtDPhFG45EI8qdcFu4YAzuEVjejiuHvenqIKpgzO4JVeUTn9nxAncUrlgAioZobF13XPORR23kI9RHYAy7k3i395DFWO4cay3RJkZ5lNPmH18Lyh13BJlaTgzx1+VC9ZnVX7IcptYC4fmM3D/yaMu2VkFx+3OOFQSjbq9c7fNjgtSnsl+z3jfEn+Um3taO0bDKw5VTONnuflMokIFE8zKMPsDSU5BD+mWiq7rFrNj2ArnttlXp7vWavpOiTH7acJgfMEWFG/ORYYEX9leUUxEFdPcjW8ePqSPgLsRug1ttmP/S3VuhsJytw2qGOWv5o65wZSkcL9UtctWfNlb8KbohPHvWKoo2chWVDBOI9GHkTcAOLvl3viYNVxrgnyITHr3IaoJSe1NVDGOn/VVbueB451IlXx0DYcOUWvwHTiztYj7xt1szAwWsUChSsQv0VDgVrYUvtNNlIKFwnvLvg3iPQBO5jhT3t2+Wjr2CSe3ZMxc0RvVBESxxHt6LSoKRLnS9Ex5A/CilhywfCcqjm8dNZRbl4XVdBlU+G5PobpxDPjXRo4N/sqrnDrxnBuLZUv25GVSdDAEbv30AqoJQCHHrjkRFodqiFhyS9qGndJ0FXpuOLlM+NrLDxxGttAIvYBLg8S8WzTLfl7NsSS3GcWiVsLXXixNDNGDyxYOmHVL7fRWiwTvjzNNbML4YlSzjYfhnL1eD0tyOB7m3FL+XryZ9XFEIGL95HuoZhPfTEIVk7jiFrRlTLuleGzFdoF36bJIwN6QBQLWXu5CGCqZxA23oC1j0i3Fk9dIU701RLYoKl6w2svW4ahiGiuntjoXptxSOHl9fZiFIGaGULUX1TUenfnYLRww4ZbiKesDUE0qBKu93OczeluGa7mWMe6Wysn1Zxai9iJQT91Wc8sS1sHmpcqcAGNu0UzL2VuPZgHw/KL3gWk2116yW/O6l/rZ/IENH2NuWThY6rZQXWIT3rO19rKdV9GCp59xwIhbDgTZwwLLEctsrL3U5vAqWvDUVg7Udcs984s/Skbg1vkZqMaHOzwXqfPCdyKL1HVL4goeLQkx8dyyz5aOuu0voYp5uE4/c2bquEXzmWiD5PiiWDKX6ySfuvC9EUEQ7nCxCOqWe/HcO8vFZ0n+WN1qiDz5z3pUsUAgvhNZBHGLKnGFoVDPBG9MzEY1TtTm8O0DwFNELKOYaHCa0rODwXm94/Hq/8nboSIHbvlzG+qvp/wpj+cEToph2VJ8guMIeelwXZ1hzVNpvnVcPOifC4ZumZ1kJ+0hFrJEK8bUqfjWcQEP+ueAgVsy23BfFUVCYhPe4zsM8j7/MtJL1CmTDQP2GgsHWiTSB3eftmLJ9UguvaVPxPbyuDkRSJg5NB+moJJF3PNRBYPCKltqj/yNObreSq/WK9e1B96bV6axAyxwn733Bkdci1AFg8Jyy56PtIttWFGrFBvX5J95TH/99g1UsQwemGsZvVtqT7H3w7A7ZIu8Offsak5bUf/Cg+cso3fL6Q/4l96SMmHMO5WoZpy8TlYsSYSHcVtG55a7f4o5MV4QorZXjuU0imHmPFThgEK3tQ7GFDq3JEs7dcg6Ar7kMoqhsCXnOWds8L4QFtG6pTKPd3dWfeC9edsOVKvD/8ajCif88OA5S2jdkkPuZOwAuK7OX2CpDNhH99LwBQ+1tIjWLTt7GMj2iyzxWQvT08rdua7CYQheu90ijFs02Xwf8NcfsdPNPwjIHYYq3MCD5yzCuOUqtQu27ajvp95XPzyfoa7NBLhdAoWp59WgzrqPxrOJyO21Y++iog7Vx1YOQm+Nu+cswbjlXJChbAVFGwt2VA36sl1N6eEmifn5nwDEqwrfbAzystjHiTdhY9Fy4fZT8N+aZHJ+2sP+qMKRYOwWSzBuybZ50be8mf3nj93We2Fk60bvB/tqrnSFqir/4qdt+0ByRFBYPoxKGPGRcEW954bvdqAagzW9/hR4xVyLMG6552Uo82fmnMQFbt+Sa+ofnHG4oPHPr8CTtvJW40ZnQGpoVp8I8CyD+wL+41UklS8w/iDAml5/isb4saIlGLfU2Nzrv3ph8sfqkAegLtiyso+fx19NS6c9r1ZNiDulVrz82uDAsqkrPv1KyM1pZQkvJBhrGxVGWNHrT4GHQ1mEHt+isX3KeOCy4HkwZ4aszWcwbijkdR3Xyq1/6fBmvp/LN81xg69qVtWsFdIsBDGt31tVdzFcK7vmAA+H4gC917xm5CG2aLDffH1iaUP58inbEaVyMqpwpnDNIlTCGELfiRx18RLvzWjTyIY+aXdbp+k3fJh6S1NjVQAD1IWs+TYGJ8LBP1tXtGn0dTfDcx7gIQsWYdziZ6p3tKRfvzKAif0KSodl6VWDEwYiZr+Reahqhn7nUcVYthZYUm7w2EhzrSnrjB8u2C2WYNzStcxQZnMLym4B+B5hzUszONExZW3BZ6jGC+PZmifhBfZjo8edrG/cOertWEIUE6m3kN19WOKf+tmB1V+/lPz2jIjcWNUbPRSDZWFl3gWDZSHESbOSmN1N3s96PiYnZOyPZIdY9ddBf95c1LQkJvCHf79Z+VFUntsrj4OG+zybMXqg78iQcaRaHrPDJ/j/vfua28uDvQF2/COMKE6oSKF0Lso3eni/khGV10T76bfCdV/EJO1ey//XS9rniDM/saF19/2r1ja+nQWmbAn4zVBm8RZcv07vkOqr2PX//MlXpt0647VWeddgWngIE/OHY10j4SzcvQfF/10QHH4V3gtvux/aw1cFhUWUCpA0KqAfHLzeSjeYho6kz+W/QKTUBnIkeOMUZkhdrdKWBSL8cOFiAcYtCneTQ16D/D7sSPf0ehxqv/4D8vUDOoTcDO0BkYP2Sf/auCvk40OvV5M8K+VkkALcq8Gj68mDg2gVwIfwXNtvl85hUmgj6XOp1I2h4IG/dmGgh/wnnbHA6/1YQnttBuUayGyGq96nD8qebmh7Q0283tAHRsPSjbqxsm95xNdGQ0VUhGLApqqiAXCmJHsEUbN4uv9tWqVjvVP1hN6rIbuoqJSOpM9lABAptflxxnPLvgPk+9EBaAgfGmO3WEA7VzH6G5NzAYf7tH9CHZSNVXmslROva/WBQVO2QKC2ruC65l9fTk5atV+xO+TcsUbfzdq08sVhAK0U3s/IKZXuzO0GH9Ae3bEDuq6mIvnqcmk8azORksmPO4qk5ctmyeA042vrwOv9WILuywVQxbJ6c0325Va5kde5ykOvqH8Pe/zOaMOV6shwdQ35omINp9alujMhjZUBFYmdC5WSwVJfLpsD51eWLliDqnxIa23yXwyGQusWyMzRW8SkW4yhtqamUQfjufBxC7mu1QqbLnf2HT5/tzOiu0Yt97FlHsiNXWbeCJFLiNtCmx4M4seKltBdJE8/h39Mktfzy7nWrTtGg1dZsIT+n/TEiyzZITk71HvzSvQxIw/wKguW0Lslwtpbkd1wOgRck9HHjDxwx26xgN4tnn6FLN0BoTpyZchjRj7gh9CWYFUuP5+iP3ZEDi+k3hIW3Ywz9UTdPIpqa33mLLDc4hqubRM0N/NEWlJ4Dcg+rX1eFZH0rnXFJJ4JbQF2w3XSN8xB1CHr/nHaCjpFo7wLIphDpX+iGLx+ilWNYfxY0QLsVQpbnJ7MjA6Jy7B1MkfSFP4zlJSbA3u1ZwteUewzC+SzxlwEfDmF16qGDP7VtjzCdgLYbpENydUu8s/nMhnFzZpu0QG3Vl6Z2cLK8UxnX2adeG/+92D+3wA/KLKAYiLrpMv0GKvWyTHCj6+hCgfkTV4b1EidPetiJP+CSbXQ4GGVYlC7Dcf+ztN4Kpn14zSdAgO3wDN55JgVIfie1xMeNvKmQ6N2b2jejOc4tkdP2KP/SP5Ws24Av0wq/7JmSwEnwvDxTJd1Bqc2YNNM2cDEnTB+Gb+9Zq7W3eoshu8a3vhBkQUM3eLZkt8lMo0Nw2NJXHvvHbc+LhOVzXBY235mwbcpjR8UWQB59BtPDUETANtX+AhetDFnA+eLrSow1pzh2ZTGD4osgLilxWnjCxfwxti144tnzBvL38/m1r+a3xNVKAJ4PZX25jMdyhlht6AJZLvesXoesQG8emFNErqEXORspbvlZvUX01CFxnu7ZscEVDSFq1Jj6XOcG3QQkrdAG4DxbwKbQhbxxYKjb6WZnJNAc60xqmiR8XjMiLv+zWPYgiawoqvDGIUCuY7C829DVXOvRPqiup7y/5lusRMt6VfQfxQm+LEPe8QwBqWOW/xq+fVRmKBY2H4uedOhnXaa6YTJk5vZRrFd64849jr+3lmI+lbDpY5b4FdBOugKdbNThcK7z+sP5tyKMH45D0c3QiUWTZ//4B+cCo37zcxlg6lbRAvTQcfp4vDEtffesSvjMo212n4xv+586PrJnDqSWtj6MLWBU7dseXGu6SoAdyrMVDJswG/A0Kaymx+nRgQYNF4KrrMfKRrBc4RbQgvLVSnZT3zGSDgfdd3ifbKndSufG1Bl/I4hBLImr73ww6eyVqzu4j87WHSCYtDaQjN1G5qnJ3lvDOxU1L0TwcwtqGIFpqqjwhAw4UDnKXP0exV905wVaAJF0l8WN5b2fIQqGDZG3BL2m4W+DS4YyVdQFJHbZ+wde4GZ536Nyy4FssQgS3vteXCq3Tgvxq7qR0dRxS6hnlRTl7cykFtRNuF1o0vs6pHZ4wakdgTS808RlZVh89g5d1QQA9fevaHyp519YAYaYoLevR/OXG+uHIooqbsCL0ZH3VouQZvE4bY+L3nKrTvMdlzbDW/2xf86GraRTOP7j4SedG91sbFGfla4OG25BoJRt7jm+dvcucbt3iAEskYH1v+cpGK3kczgMfjfHak/rsjF0NCV5YR9irzIMPxs0QTG6i3EDX4JqvDFeL7iUOsXELuny0xWG8kcnsxcaT+kduZ5sRCg5R3iqBAPXDCB8avq3eEuKtkxjztQT6pnfTvW8Em1iQYQM1faez8S3n9KITT6E+DeuxY7b5wV426B95JQhScm8hWFfHouSEDC7paTF7DGyt03MfKOmSvtZ7C3AeGioe8Wet6Dgvie+EZkAt3aUAiFc79AJbslbjO7BlJ7aV2XOPqx0bKzO001gDKXbvwtaXmkoZgxz62G+C8Fu8UEpsqAgMZ8BrTWKyqlQXWV9ezxPTA5ijty8SQv+AQRo3xroMYXFy0mMdomIolKEuLhohQU30Ef7vgNePn3BXntPD2+Lz3+somHXn4vLq6oeQlpL79A1Hz9pgkzHqwhYqx3jiLQ66EwY2tF5/ELqEJUYWNeyV1UPWP6vJp3v0RvRpr7e7W330XI7TY45D4ojUw1wdCYLFug23+smZzKIGWPxdG/GRvCJGs0qO+eHcR96vhoNMSvz5svKvO8agBK30A6aV5KcevZ11DC6DFVbyFqLs9w67+od64Y70lUZc+mNi+oMVJ1kYUmpiyLdmsKnyIB3tE1YxEJo8dUm4hkwSTtkgu8UUnXlVs+U3s7SWnJ+1NV5WglJRNpJ9nGpZf4bnFh15guWwA+XIAq9kjRc9qjG7zNAgrULCCoWeD5H1DFoTHnFv8OPOb51Ru5uJ4hGebcAgkLrR3uga4JJiJnbZ9yjeGIWbd4D72KSvbHDWFnLmHMYNYtEFsh1JoLolHcTKqRNBgLboFeRwQYoysq+V1RBSMaFtwiW7gNlTgh3ezz3/AMIOmw4BaIuGTnW4tcwdUW6bDkFkiy806XbJNLcWAEx6Jbgr3uopI9Uesn4RMpp8f0U0UtA9KU/FddkGzI/61Q/aKlP/FafluTfqamuebcH21lmiPXK5tD1g9ZHUGTVtIcIPf7qgdl/ifD4ay8EaVojvj82ZxJQcXgzP0eqOLIWCxbAEass+N20W+tUYUDWck3tik/+sW/QvnxzUezIetMyS511pcuRHN8fnlqGhSvq7mwq/S3XwGOVtBK1pndu9RUCvp8S/aGXDRTZ4CDWxT/4d8uMjlsRmj+sKKSm76v1eHYPS0TBkfvCZ8Yp1beCIgFTb6mzQj4ukmgbxmkRI2dDOE3A0FZHUErNwJmg4ZKQZ9329t5B5qrM8DBLRD5u5GH/nbCIxND48zx3UtnB/rc6k8cZb8AShU8aJ/trejbcedhyPS916w1XOsBWe5+ecFwNUBGKw/ay70VVAr6PLAaSp2xcOFUCCyw3yHd961wy5h9I1K7+V7voJF5l8PBTn4Fve4EaconbX0I8vB+RLhLmepkMBT3yD0RyigFvSAIqBTUee62gacT+FfmHB9ObgluI+yoDwGxZiJq5ICuFZqXTlxqNLtPynfPvKUpCv+lxd11vvJpMCztx6eLZR13f+MaAc+kNHZtSStQFA4tgEpBnVfElX9YZ6iDM2BuNBSLjHMJqGSWWqnaRAsW6Y8Xv6o/tg1N4nyB3HAxHlUcGS71FoKo3/mtbMIxW9sRZ7iCbLlAZmlgcL2si+ejin3AecEyTZaRHVnL6mplx3NQyQRkjrrY1AH3tA4Kp3oLQeBztq/pIgbGd7bJWQngM6IbW7q7MWYw+5xAtfQReCeBqtKHJT5Jg5bUwV4Ym5lMpPm4zSRWMAsyR11s6kB31lDhWrbAe0v59NFxztZWTC1Z2vPvZacMhLAPXzQ4JzjyaOS/Y0A5IxUNoLjJVOw1psYPGsmxocO1bAHXvdNmhaKiSSR7eGNqu6riIogiipjeZb8vnh/R7nvfBXdXxTz3eUSnb3r2WSnr+WvL6UScv11MafkOPICi3M+H95vn89bnwS+Xke3l1VWzAf7bSFc05ZApVil/6HRIueTQT+O7ze2hIQ+JHMnSitYBTisz3qcik5/ZQKs9PAqBpEQTS1zUJ6b2n7qTHTwILkBpGfwJL8fQ7eyL8Fq/Jn8A9HrTv4Q8b/HBMzmfaZpDY7rr5DwM706a5X93yB0ar7EfOhEpwK/j1eoKeBl+yax8gzpkoHWA3mMqnhBvzGc2THi4xfuD3ahU/5gaffOPqIeXQQ2K0OimujXwaoi/1rWWtb9Ji7kR5enMsZr4TxvxOPF/znMAnvAIoNKLTpGWXOBBHAXf+rHd99QhA62TkSmYz2yY8HAL9PqFXzNaCkx+o/FuKao+UD2gkx98s4V+cvE8nMt5wtpwOj1dJQeZD+Rp4Nr2ciI4VZlFyD2aX8iEbFeiwAmT37qbpmbmUd+WEfcdJXR5mtmLOTTQITvHl5w0yXxmw4SPW2RJiaZqfPVGtalvpHipYkfL6HsLNt3qcr/Mm+osjOh1ZVWLV/RRfts543ZUN3nnO0ubZed7E8H35u8k9XiXXZqrbYgD2XBYcbINs690T5cNga4H4GW5e3fm0ECH6rXR5Cn9mUxYQ4NjXy7DjmCuW3JLNW1+WpK+x8VYX67SD463u3P8uXfoU7XBv46yIqrGUuYtY2biKnWFwpJXutOKr+4P0YBMJafPWIf6E13e+lwaWl8uP7fAw0XJ3Gwg1UToDNB3AxlzS33TsNzC505EENzLsBfDJFJNVgxKQxWMePB0C4zbZ6oVUj8EUatuYKSBr1sUSbNN1SsNkGpCkcKPTxczxjb4uoW4F/2CSsaQ6k4EvUy2oTGCw9stMKEd2cFtCfN7dQjIaDuf79Sg4O8WCOyPKkaQrGzxdMe3Ismwwi0wikPhL90jpWG64dScx7pIR3HDmtNvjVtkHB4vSlXLBei2R3s07g63bUTMcFfYMrHkbC9Ucmg4j1hg89EXFkfpWvaTUDS9pu03Duxx3zCIN2fPThH0EU9AA6tUWeWWyK/vcR/qIjaynve1XybM6jU4KTQ7L7pxfbLhnFhzJwJYBHNQCcHUmDYRmDmNUw+QJe6NnXiohtdMaufDOrdAaHtqRyjTCHv/N4uikwC7T2l2zK9+DICX9zCLlW6B8TvNz3YtQgURmfQNqvCmMv7oY3dfYD2ixBjBWrfIPptptiJbd+qFeLQ4beut6N7b1aFzPwPfBj5k32YUE1GFIx6tv/kbqrFIfxZVxENW2cTYvhA8ULw9dFCLtJdd/xxlzUxZ58HasoUotO/dRSUWEtZyAV5NRhWekP165Ud6DdEP2cUYw3q3wBL5+6bvAKbG4otC4Hjbl/UtnLlXFmaP3cH2hA1ugdBh1DBWo5ia5yMO4ej+q/xZRHakUSNrMSaxxS0Qc8XkzagGFURFMeQmKvHknhe509sYVMYYYJNb4PMFpsoQKVvQBKOWogo/NInzyDcH2Ruw3rDNLa7LZpqoulSjgrjYut35qaFUjUWqVWccFdvcAoFfLDR+maTsbyFJ3Gr8e3BjTodYVMIYwUa3AMxbYB/Duj8kt4+3GtseRzoNNrvFdcWHxtoj7rZcO2vwf56clWodKqrSgrGIzW6BgOkrUInAT7rhUAzvfWLMtZzYjbtZuGG7WyAKMlCJKFskd4vrB4dQiSMFnGYxYARxC8xcXXegrpfEjSKCXiesq0FpEpNQCWMcIdyi2F5Z5xlAqPQj8WXbN2eiGgcKJ25j1tzAWEIItxBNiv4bESXQ6kqEDXz4CeXRuiWdGTQzl3FbBwAjlFsgNh+puzSS9CE0g+tns8m3Y6hujoODAlEJYwqB3AL/Wf3Q4LyZ9HcigrAQ0rRHUdkMhSdGoRLGJEK5RbF+psEjI08BxspawcylxVBew722q5m5At+HuCOUW8B7xRR2VcXLsKiRCsWK2Rolj8cOO8fgGi4PFBNRxVp8h35ept+1Tv3tAFaYNBSr3MD3tennin4digYZRzVpeidUw5hBsLKFYNZh/ZM913qo5frnjojbkD37NjxGQ0zwxXv+qIQxh5Buka2arasxuHG/GwhH5D6/kzPfA9Or6BpyL7s3KmHMIqRbwHP9h9p1W1zqwy3guibZjZw5z6mKXYv7cPkiqFsgYM6/mU5dmbRDLXWEpowm7PI1Khvj0//gh4k8sWrWvGki1ix4gZ54HlJpxY6Hxik4zqsSNACy0i1PGak8H/Xjj6hoDlnbAU7f2BbYLQAL41tTU88bVwvllrv/ew6VLNCz2IW95ZBx+vG99q4/vIxKzoawdyIC2bol1HOaxoKtPHexNapYxN+yWfhvilPbUHcG4Y7gbgHXVdPIXt0gwYYs1MszBGPYzRepN4R3C/ivmEKUK8G86hoYh0AEt0Aw2TLyk3AFF4xEiOEWomW0HFrg4YsND1HcAjAr0+sOqmEcHpHcIluaz633HeNIiOQWWP8Jr/GOGIdALLd4ryk1NaEe47CI5RYIdCPb0ZgGhWhugYhV1+eg80bERXN572XQnD2uBs3h3EOXAbL2HSJVIig3Jf1ytuo4wNlc0JwkFCYGneQkmhPGOOK5JbQyqutCVBSJrOQb20D50S/+FcqPbz6aDVlndpfsUmd96QJQPD81DYrX1VzYVfrbrwBHK4rnlxMKHYNKQghpsCV7g253CYwpxHNLYC3EBu1AVVFI39fqcCzsaZkwOHpP+MQ4tfJGwOxY0ORr2oyAr5v4lkFK1NjJEH4zEJTVEV83CSQUOgaVhBDKoNvezjvQbDEo4rmlfRFAwh1JNsn87qWzA33gVn/iMPsFUKrgQXt5treib8edhyHTt1lruNYDstz98oLhaoAs0/ceodAxqCSE0BoCq6EUFy6WEM8t1ByRhceNzKgXnDGnR6QC+F4HDXiXw8FOfgXhcCdIUz6p80OQh4/tBi5lqpPBUPxM7okgUiAUKgadhIyRu23g6QRqc2iMGXjuB82DwjWLyLfa95JsXM1tF9dx+Rq1gtrLWyeU6QYuqBQLh3TXblKtl+kkbMEcZ2agirMhYtnyiHpzXT/T/I4AwiEjr7yMNW5FbwKFpjyceEVlOglbwJhDPLdoR/17r59iBw8BZMvxMtu2I55bFKXMQcCayeJ26549C2XHc9gKcnqWy+2WUyQnR/BxuXqa1jILigaueHerMIN0c1YSt7amY1oZqqch+kmawfYfyOlpapFtMrH3IJPLbdORMOYQr2yBZroSJfizyUI9BXhufPeHG60daNU5plySFn2DRcSy5e95unmjYdszV29g6pi24d+tW2jK/hdW9i77PeFap0PK91e2jiyltonJ/Tyi0zc9x+Z+HvxyWSuA1VWzc8hYi4sOMxGIFlAxdCBKmC4veFFxo6moZKQZdC6ZycP7zfOZTUZpl0KmfZDcua+6A+vjnRwRy5bW7LpD5PgE4VaLcr0ApWVQ1vFqdUU6DBpIe+EivNavyR9wHoZ37wfwvzuTgYr1Z7o2AsDv13u+DhAd156OS0WlIulzoYmOu0+lDZFdu4y3WtQjolt8DWZU9B42Q6BnjNfgGTUoQqPvJBd4QBFoa0Q1xN/iWgtqcKfPjwMVq2mRvsr04qdjCU94aeNSUalI+lzoDSG9mLS+k4Mu2riDQINCRLd43jM4jekzVwC7lKQnZ/n27QPVAzrlyQaTy68eO0z35zwP53KetCfeUpVZAD2aX8ikYvlFaSOwoONSUalITC5ecG07U9mi0xarZgfQ3UYYEhHd4o3MXY/tutB2u1zdebtVoqxl9L0Fm5q5bAh07dI68yp9D4nodWVVi1eIt3vzyW2T4l12hZGxbnXRRmBBx6WiUlkxuYQ1y85n5kZT8q3bm6eX9TNM6tSI1/MPMARdAG5HfiKicMF4z7/Sj+zkVym03fkkatr7Sn1HHHnIisCCjktFZUVix9WFacE9/yK2iaBNObKIwQTYUG6NX4zhR81NVei680mYgpJ1gclDo2Zh4lJRWZHYcXVhGC0i3okgpO50xQTvDaiEcRzEdEsrIxOHEwDbxXER0y3keKg6YLs4MGK6pbHRHYMSyrFdHBUxa7mBp43uP5coYF0XIylili2e91GFIcF7GSphHAEx3QJNTT15TgjisQtiVAmq1BNNUMHpENUtzbQDouowoTX3ft2oS5Lvo2YM99rBqOR0iFlvgfYVJlfRn3Bg7hJUM8WsjLodN9JT0AFvTSOqW7o+CUUlHbGNNJzXCYxCBUz9IOqdqOU+VGERc/V94Ua8YCRBVLegT6ENiZqeYKoWjLFPRHWLq/nF/iNnvGfk2QDGfhHVLRBo3g0Ri0WeO4IRFnHd0qYMVQwJTXrXDmamYbgirltaGXuuyCZ4/eQ6wyAxdou4bon+HlVQAva6x91FRYydIq5b/LJRpS7eGxdYs0U8ph4Q1y1ehsP+jeO5efUFVMPYJeK6RebOpQPOdcN3B1ANY4+I6xZoyen5sSLprw2cHzJi6g/FRFQRlNDr+i2izSDr87dT6wYYH5yPsR9ELluacF52LiYed+zaPSK7xecGqpgkcvEk3FNn54jsFk8l9/pI6JrJeCcJ+0Zkt0Aoj4FMgV8m4o46u0Zst0RY6vtn4705iXM9B1MPiO2WN75FFXO4ftF5A4/x3RiJEdstjTn0/bORJbwwHY+RslfEdgunvn8DYhLe0w56wU1qO0Nst8j8eJcUEUnvMm0jdP0XTD0jtlugA/9OlOCNifT9az8SgKlnRHdLFJ9GEYP/5pXUurY1aACmfhHdLT23owoHXL+IOTCNuIXh7hf7QnS3NOLZKNISG09UdhegKqZeEd0tMgvj/k0SmTQFlHjQrl0h6sxWiueKee8Iobm/l15qs2Y558nSGAkQeXwLgXsB7x3oZH593nwxJ99NBfnDmH1IMPaA+GVLx/G9UQmg4LjFp41hYQA3H8X2Q1ZRFZRhzVAFYw7x3eJqZHnju/97DpWM0hNU5WKuWPtt/3BUwphB9FouQHjdqurF1qhiCoWYZoEeP6AKxhwSuGXgE1TBD4AcFAnc0uIXVME4KBK4pTF2S0NBArd4u/N/sIixSyRwC/Qxv+gPxmGQwi1d8K2ogSCFW4KuoArGMZHCLdY+hsbYG1K4RRaKq7kNA/GfKhI8d7yLoZARZHhuCk36mZrmmnN/tJVpjvj8UNkcsn7I6giatJLmALnfVz0o85fDWXkjSmBi0EmoGBy43wNVMGaQomyBJv9FFctkJd/YBh/94l+h/Pjmo9mQdWZ3yS511pcuAMXzy1PToHhdzYVdpb8BHK2gBTqGkkxCC1uyN+SiuWJsQhK3eFbzHvifvq/V4VhomTA4ek/4xDi18kbA7FjQ5GvajICvmwT6lkFK1NjJEH4TlNURtEDH2EMmoYVuezvvQLPF2IQkboEuj1HFEt+9dHagD/QnjrJfAKUKHrSXZ3sr+nbceRgyfe81aw3XekCWu18eXA2Q0QId4xaZhBYCq6EUFy6CIv6IBZLX/wpGJQuM2TcitRtc76CReZfDwU5+Bb3gTpCmfNLWhyAPJzf0dilTnQyG4twToYxAxQBfMgkl5G4beDqB90AsjDnE3D1cj2bkIYNz4xuC10WjVgC5SbhOKPPRvaoUC4d0N5ApqCRswSwX41EFYwZp7kTWtqFl5JWXsXamoU1AvSo05bqhTCxvUEnYAkY4pHELDM5HFduRLRd1pBSmLhK55VmLi3KzKDueo3s14KyZ2yYRXZOlJG5Q/03JJt/ZUAEY25HILU0uoQrD3FWgnLYX0tlb/j5J+0P3asDp09Tb2WmrkQCgot/deA5gXWrNbfJdTxlQARjbkaZNRPa4GJ/qEfIX3IQ8yOTW9UpzGf6qOzCcIOzDpgD32o7VRDZlqcrjY6gAjO1I0vNP8Oxv7IVz9T3//r/IfvZ42Cnlzcr/3K5cVeS34GSel8uFdsfO9C250M7jK0J6TnlMsfn4gJwFWcWZ7n8nkpy8uvz736IhZ8H/e7zl5Nkf+xMHhbtOP3+hnfsq1/Cc877+FatcPf6T4XkzgEp450azYiIgl8k/rWhbZi/dF8E9/7yQ6E4Ezc6gCk2E/M7jfvC9rP1FeK1fE+LeEx3XHuB/dyaTgRcpya/j1eoKSIdBA32pJFea3wzKIysi/ce6t2pRTRz0HhNZwQwVbwm+kcTbeRjevR+dsHljqtNFm3+vN/05rQ+OMYJUbvHPNrGc3DPZmmj5782ghvgqrrUAXrR8nHypoaS05AIPgCJgJsgq83I2PQKy1uwNCjfqTuoJ7nSYDjWp0AkZtPnjdpQNSOUW6Gmi8z9MHSgLVLeE5+FczpP2jNij+QVyF5rnKem2bDDhkSg4dpiamPR941WrVrlf0+cAkJ1zw9dwI/jnIVWZRSf0yaN8apA/xjokc8vQdFShaQ9h0AyiIKLXlVUtXtGq8S67iFtMBCX1dNkQ6HqgS+vMq9Sd6NpzALLIQqZ5TfXclawtjdampInodW/+Tjqh/M5SWmHnj7EKaXr+CcrnrtGfGO/5V9e1LiVpQKaSy4xMkKXJWRkzkN3fy6D0YxKWeTOBRvLHPf+8kKgFTVQyuj609GSx7sWkJeJik0YxYRZoybIhCz9tQt1TACP5Y3gh3S/Y/yqqYBwN6dwSTDVzMI6MdG5xreayax7GnpHOLdD/IapgHAwJ3TKC/eQQ44hI6BZFoHb9foyDIqFbYIxuTHWUnTyqKcYLifFCSre0+1p7FHXpKTugvig5OwCVMOaQrHeOoOk1jbbLdVaGxTUtJSAAr/XNDyndIuuUp+vOjWIHYBwEKe9EMG8mqmAcCknd4upn3UQRjJ0gqVtgPPfNxDF2iLRu6bwTVTCOhLRu8a7GHXSOjLRugVW4nuvISOwWf8CFiwMjsVsgHo+JcmCkdkvEZlTBOA5Su8WzGbMzOMYBkdotEH8AVTAOg+RuCQvMQCWMoyC5W2CIkeU0MI6B9G7x7HAXlTAOgvRugfeSUAXjINSDW/wb48H/Dko9uAWmrkUVjGNQH24Jjl+GShiHoD7cAmE3yFFReJtfh6Ne3AKLVxEvR1EVY+/Uj1tCiwsB9qMqxt6pH7fAgkUANby3ocHUM9K7RZOZrYKApDiVLx7S7WgoJqKK2MiaVk7Oi/Cr+Sn3743QMIx9I+XsMy2hWyed9B160q0oDA3B2DfSly0ErsPv3bwKqpBn0QCMfSN9vYVElrjBDaDOrg8YO6d+3AIQ9lVTeISKGDtHqHpLwXG+iyb87ebNZFSzCa/BgaiEERaB3HL3f8+hkkV63g0VtmS7Go6rzeIi0PW62BpVOBAm0IdreQbPshYZgS6YXTwhrEEFjMAI5BaMU4DdguEOdguGO9gtGO5gt2C4g92C4Q52C4Y72C0Y7gg0YkG/HbhZNOlnapprzv3RVqY54vNnc8j6IasjaNJKmgPkfl/1oMz/ZDiclTeiFCLGDx2ZFFQMy1SwdyjHCI9kZUtW8o1tyo9+8a9Qfnzz0WzIOrN7lzrrSxeA4vnlqWlQvK7mwq7S334FOFpBK0SMEjWVgj7fkr1Bt6sEpn6Qyi3p+1odjt3TMmFw9J7wiXFq5Y2A2aDJ17QZAV83CfQtg5SosZMh/GYgKKsjaIWIEauhUtDn3fZ23oHmipEWqdzy3UtnB/rc6k8cZb8AShU8aC/3VvTtuPMwZPrea9YarvWALHe/vGC4GiCjFSJGtoJKQZ8HVkMpLlzqF4FGLFhkzL4Rqd18r3fQyLzL4WAnv4JeEKQpn7T1IcjD+xHhLmWqk8FQ3CP3RCijEDHu0Cmo89xtA08ntECzxUiKQLuHG98OvA4atYLaDFx7Xkbt1Uy9qhQLh3TX7hBO6yRUCta5WQpiUAUjKFKVLTQy8tKztoWnTUC9KjTl4bodwvXmoFKwzjH1ibRuMYdsOapg7A2parkEmiwlKnGg7HgOKlGocjWohBEb4cuWnJXEnWNEN1QGuLsxZjCqkbEX+cOiwvc7oCEMT9KgJZDxokfC6pxVZO5Npvpn7isCaD1sZYxfyodkMEYShHcLQE/PM6eMuCXsw6aoRPHjMHLFBc78Tf7L8dFbNf9sn1VGnH3bF5tFOsRwS3ERROWs7F32e3xy68gffBdkJg/vN89n9t1VMc+uhC7tUoiQxQ+SO79AFyetz7y8sfUdUP7Q6ZBySc5KWc9fW06nU0z+IUu5RJdr+p/whHz/s7TV6J01/+wO3SEHfp2CzSIhYtRb7mQHD7oApWVwHgYN9DUMi467T4b8GSK7FkErXSHtEeEbv45Xq8kpSb3e9NduFu3XkVK0Z61auZPvbu4PM3QNq5of9BEwoiOGW/4R9fCyGhSh0TXgqdXUzLsXUCFNfScHLaWV1l7nmhG3qLTkAg/y1I+JSMRLS6YUhnZjxlB3sq6L1UebwRVa7HH1MisKRmTEcAuMd0vpA9UDOrWGY4cLSYdc267flogK8StWzdZObG2njiReb8uIKrC20USnuC3TK3oKv6+tftH1+te5l48DuLvuqxsDIxaiuEXxUkXL6HsLNnm3zrxK3InCmmXne+sCqZBbtzdPJ7v3SQb0/Afx2tNlQ6CrdscIOkVPF72i5+LxpiN83vH85fOvyKb16zUb0AgY0RC95/8zmI1KooF7/kVGjDaRAdJ5BSM6otyJMA0U7BYMd7BbMNzBbsFwB7sFwx3sFgx3BHJLlPbZTn3ytBmqYIRFKLdceopKkuNyKQqVMMIiVO/crAy+a1oKjmIyqmAERii3AP537QQIdCfCOAXYLRjuYLdguCNYvQUjCrWXdt6Xatcv/zbx7VlTA42A3WLXHNjyxlw/ajiyBFQ+3pO+zexeCQKNhsKIwQLvD1BJbJ7Ef2emeMH1Fvul4GYCKolOk25ZqMQCu8V+Wf9RPVydt8xtA1QP3wfDkfT6WK4myNxGLLiWa78Uk7OxXkFVMTkF4G5uOxjsFnvnv6ggHto5OybBbnEI3r8Bis4J4cRRUcJ96PA5ufwReUQd9lfRkUL3UG9FM28BBKYQRyWvU9LI0NXk62sT4Oa/VKDYE0xmB/Aj/1oIdotDUOO3/OiJd440hqIRrttV/xrxrQd1FPSQPNyigu/3T+kCXlTUwmGKzSEZW8nDU3CvqDHxXs3kop7RaqniOxcyu5WMxA/+/sLUB36Rs2aQm9yuUX3dJnxr1T76yIc6bBMe/jL8PTw8hIr5Oext79NnG3Gk3q2Ab9mZPFTOCQyYQPa/+YaHh1tx6a1Igqkf2oMbVP3YNQCgdcgu5og6NKDqfEftEML8ko0eh7TLFZB4wvesMyvAbnEUqpYoWkEpjCKP+6qqmCPy0CBaKbyqPdytaPuG8iErLKDt/jl07JqSklpWAFewWxyDhxNjcjbJoRioDRXCoJQ5Ig8NIhYTZRBN7YlBrqOArvkybOr68+Db5EHB66//xQ7gCK7lOgbB2zKmZoVDMHxPNox+gCae9BF5aBCxGRxOpI9mqVJTAY4NC3enarnV3gCuq4l2UsdkgJC97DScwWWLo/Bch+WF4NM2hbiVlFzpI6ePqEODaN4hx+j5F2VX4o4dO3YENkBYDlF5qVXSa3E1CrzHjs4P7BZHQb5UMUUNS1WTCu6PU3wE1FENfWgQbTnE3izNnAhnYKSPj0/jrleqJj6dV1CwANrAnYNFVRkF/yJilT18+NCwvsMJfCdyGBotnrtrQmBK/Ehou7sR2f9GHNGHBoTs++AdCOkLe7tSK5hPjbvcO277eVCs9QVN8nrwG00uQ1syBmB7GySlZfD4Fvsl+hTx8kqdnv8qhav2qNRfe2hAVSkypqm2VNaIvIuoy59S7yboR32gGUfgssXesfjwRkKwW+ydNaggHtNQAcVMuYTBIGC3OArqbO0+GNnMM2c9qmxkV7i6UYygz5Er+E7kGGR/Te6F4J1EHO0tgmfGtQBYXDiDeL28a5H/yTQ1yP7ZDRY/JqOukpNRmk4gAj/2mUMIH7eZpA36P10aXY7aRLqPMgN2i0OQtVE+6NnK368RZtno+u8/0tb8xw806oMzyLDSRqf9R8vOeQJo3MmVh+WsKFRi8pUJ0qXxhw1MjkwI80HmwW5xBJQbm5GFRMQIKN3Q/CNoMXDhglUAATlz/4+czpFd3TqSfjrkRS4YzESZR0bRQwXp04BSm6M2hAvcPIWpXx5Aa+boPrQl30LVSoCmUeVfkyftAzJX0uvX1+Tk5KiYKK3IKHqoIH0aeKDNURvCBVy2OAL54K87ojZl8YEHfgAT5v0aTZ7N3JCzYDh5VL4SYLAnO4oOKihGnyZfm6MuhAPYLY6AN2h31fAGalWlCmp3FvmIXQfJzjufj06mpWheJA5mAfhd0UYxWFiNCmKl8S5AQziA70SOQBDk6Y6oJkwxhJFv3dvk0FvvDJwO54k3V39/f7k2ips/uNWQR0/JjXuoIFaaIG2O+hDLcIyGqVda+GYze5G2CLheCnA3pzU9WznO9Xdab+lTxMSFFk3oKM2JIqOUaA+VVdPDu7XQaVpoc+QDvhM5BJ+mbyLuRb4h8bDgxpJyCJhBbhBIGMbv86RHvj8dVoN7a3IgQhHZdz/yP2QUiI8EmLF1OlEgUJuf0kG6NADjtTnSIVRlxhL4GbT9Qj+D1j4nUj4IYXYdzAxFd1PPrWS2HdRyo+DHwsYRr/mB6k9ZO1P3D32ODNPwM+iGgp+uJkpuFWdInQnTHTq8mH7mL8ILCu0oXSPoc+QKdov94l9JNnwsPhg2zXRU4EC1bitMI2C32C+dciOoieySojQ3os7UPQ1T/yxZakWzxUaq31mHSiywW+yYjVOlWqBQx9J3jI7dZMBusWP8d46XuHRZVxSLSmwUE1EFYz/4Dkk808bL3L92Iam8M63x52bWKMT9LXbP3eRrUt2O/LtNMbv+KW4T2T1hS1ClHsH1Fgx3sFsw3MFuwXAHuwXDHewWDHewWzDcwW7BcAe7BcMd7BYMd7BbMNzBbsFwB7sFwx3sFgx3sFsw3Pn/UnZxJ+pyNzAAAAAASUVORK5CYII=>