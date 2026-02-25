# RFC: Auto-Recovery for FAILED_OPEN State

**Updated:** February 2026
**Version:** 1.1
**Status:** Draft

## 1. Objective

Eliminate manual operator intervention via HBCK2 for regions that have reached the `FAILED_OPEN` terminal state due to exhausted RegionServer-level retries. Safely automate the reassignment of these regions to healthy nodes while preventing infinite assignment loops.

## 2. Architecture and Design

Dependency: [RFC: Epoch-Based Assignment Fencing](https://docs.google.com/document/d/14litc0ex2rR6ScBoNUY0hRej7Q8Ij0LvZ1tGI_DEd38/edit?tab=t.0)  
Dependency: [RFC: Auto-Recovery for FAILED_CLOSE State](https://docs.google.com/document/d/1oP-DUpnMvog0qlASL4JgTtZ-_wCtWp-DGNVuy3BACkQ/edit?tab=t.0)

A `FAILED_OPEN` state indicates that the master attempted to open a region on a RegionServer but the open was not confirmed. The safety of automatic recovery depends critically on why the open failed, because different failure modes carry different levels of ambiguity about the state of the RS.

### 2.1 Failure Classification

The state machine must distinguish between two categories of `FAILED_OPEN` failure:

Definitive failures are failures where the RegionServer explicitly reported that the open could not proceed, and the nature of the error proves that the region is not being served. Examples include `FileNotFoundException` (the region's HFiles are missing or moved), `CorruptHFileException` (data corruption prevents the region from initializing), `IOExceptions` during store file open that the RS catches and reports before entering the serving state, and explicit `FAILED_OPEN` transition reports where the RS attaches a concrete, non-retriable error. In all these cases, the RS never reached the point of serving reads or accepting writes for the region. The master can safely apply a cooldown, increment the epoch, and dispatch a new `OpenRegionRequest` to the RS.

Ambiguous failures are failures where the master lost contact with the RS during the open attempt and cannot determine whether the open succeeded or failed. Examples include RPC timeouts (the `OpenRegionRequest` RPC or subsequent transition report was lost or timed out), network partitions (the master and the RS are partitioned, but the RS may be alive and progressing through the open), and the RS becoming unresponsive without a definitive error (e.g., a long GC pause that prevents the RS from reporting the transition, but during which the open may have completed). In these cases, the master does not know whether the RS is actively serving the region. If the master simply waits out a cooldown and dispatches to another RS, both RSes could serve the region concurrently, creating the same split-brain data corruption window that the epoch fencing RFC's pre-assignment blocking invariant (Epoch Fencing Design Section 1.2.1) is designed to prevent.

### 2.2 Safety Invariant

The main epoch fencing RFC establishes a fundamental safety invariant: the master MUST NOT dispatch an `OpenRegionRequest` to a new RS until it has confirmed that the previous assignment holder has been fenced.

### 2.3 Differentiated Recovery Paths

To maintain the safety invariant, the TRSP applies different recovery strategies depending on the failure class:

**Path 1: Definitive Failure Recovery.** When the RS explicitly reported a non-retriable error proving that the region was never served, the master applies a cooldown, increments the epoch, forces a new assignment plan, and dispatches an `OpenRegionRequest` to another RS. This is safe because the RS confirmed it never entered the serving state. Epoch fencing provides defense-in-depth: if the RS later reports a stale transition (a bug), the master rejects it via `reportRegionStateTransition()` (Epoch Fencing Design Section 5.3.6).

**Path 2: Ambiguous Failure Recovery.** When the master cannot determine whether the RS is serving the region, it must treat the failure exactly like a `FAILED_CLOSE`. The TRSP sends an epoch-aware `CancelOpenRequest` to the RS, then enters the `OBSERVE_FENCING` state from the FAILED_CLOSE RFC (FAILED_CLOSE RFC Section 3.2.1). It increments the epoch for detection purposes but does NOT dispatch the `OpenRegionRequest` to another RS. Instead, it waits for the master's reconciliation loop to confirm that the RS has been fenced, either by cooperative close (the region disappears from the RS's heartbeat) or by hard fence (SCP / WAL lease revocation). Only after fencing is confirmed does the TRSP dispatch to another RS.

This ensures that no `OpenRegionRequest` is dispatched to another RS while the RS may still be serving, satisfying the pre-assignment blocking invariant for all failure modes.

## 3. Implementation Plan

### 3.1 Configuration Additions

The integer property `hbase.assignment.auto.recover.failed.open.delay` (default `30000` ms) specifies the cooldown period before retrying, to prevent overwhelming the master or HDFS.

The integer property `hbase.assignment.auto.recover.failed.open.max.retries` (default `3`) sets the maximum number of cluster-level retries before leaving the region in a terminal state for manual intervention. This catches fundamentally corrupted regions that would otherwise loop indefinitely.

### 3.2 State Machine Modifications (TRSP)

#### 3.2.1 Extend RegionStateNode

Add a `failedOpenRetries` integer counter (in-memory only). This counter tracks how many times the master has attempted to auto-recover a `FAILED_OPEN` region across different RSes at the cluster level. It is distinct from the RS-level open retry count, which tracks retries on a single RS.

```java
// New field in RegionStateNode  
private volatile int failedOpenRetries = 0;

public int getFailedOpenRetries() { return failedOpenRetries; }

public void incrementFailedOpenRetries() { failedOpenRetries++; }

public void clearFailedOpenRetries() { failedOpenRetries = 0; }
```

The counter is in-memory only and resets on master failover. This is an intentional design choice. When a master failover occurs, the new active master re-scans `hbase:meta` to rebuild all `RegionStateNode` state from scratch. Any regions that are still recorded as `FAILED_OPEN` in `hbase:meta` will be detected during this scan, and the recovery process will restart from zero retries on the new master.

The worst-case consequence of a master failover resetting the counter is that a region receives up to `max.retries` additional recovery attempts beyond what the previous master had already tried. Given the small default value of three retries, this is an acceptable trade-off.

#### 3.2.2 Modify TransitRegionStateProcedure

##### REGION_STATE_TRANSITION_OPEN Handler

The core of the auto-recovery logic lives in the `REGION_STATE_TRANSITION_OPEN` handler, which the TRSP enters after dispatching an `OpenRegionProcedure` to a RegionServer. When that sub-procedure completes with a `FAILED_OPEN` result, the handler must decide whether to terminate the TRSP (leaving the region in the terminal `FAILED_OPEN` state, which is the existing behavior) or to schedule an automatic retry on a different RegionServer.

The decision proceeds through four stages.

First, the handler checks whether the region has already exhausted its cluster-level retry budget. The `failedOpenRetries` counter on the `RegionStateNode` (introduced in Section 3.2.1) tracks how many times the master has attempted auto-recovery for this specific region across different RegionServers. If this counter has reached or exceeded the configured maximum (`hbase.assignment.auto.recover.failed.open.max.retries`, default 3), the handler logs an ERROR-level message identifying the region, the number of distinct RegionServers that were tried, and the configured maximum. It then increments the `failedOpenAutoRecoveryExhausted` metric so that operator alerting can detect the condition, transitions the region to the terminal `FAILED_OPEN` state, and returns. This guard prevents infinite assignment loops for regions whose open failures are caused by persistent, non-transient problems such as corrupt HFiles or missing HDFS blocks.

Then, the handler classifies the failure as definitive or ambiguous based on the error information attached to the `FAILED_OPEN` transition report (Section 2.1). The classification is performed by `FailedOpenClassifier.classify()`, which inspects the exception chain from the `OpenRegionProcedure` sub-procedure. The classifier returns `DEFINITIVE` when the error chain contains a recognized non-retriable exception that proves the RS never entered the serving state (e.g., `FileNotFoundException`, `CorruptHFileException`, or any `DoNotRetryIOException` subclass). It returns `AMBIGUOUS` when the error indicates loss of contact with the RS (e.g., `CallTimeoutException`, `ConnectionClosedException`, `CallQueueTooBigException` from an overloaded RS, or when no error report was received at all because the RS became unreachable). Unrecognized exceptions default to `AMBIGUOUS` to err on the side of safety.

Then, the handler proceeds with the recovery attempt using the path appropriate to the failure class.

**Definitive failure path.** The handler increments the `failedOpenRetries` counter, logs an INFO-level message, increments the `failedOpenAutoRecoveryAttempts` metric, calls `setForceNewPlan(true)`, and transitions to `REGION_STATE_TRANSITION_OPEN_COOLDOWN`. This path applies the cooldown, then re-enters the normal assignment flow to dispatch to a different RS. This is safe because the RS explicitly confirmed that the region was never served.

**Ambiguous failure path.** The handler increments the `failedOpenRetries` counter, logs a WARN-level message noting the ambiguous failure, increments both `failedOpenAutoRecoveryAttempts` and `failedOpenAmbiguousRecoveryAttempts` metrics, calls `setForceNewPlan(true)`, and transitions to `REGION_STATE_TRANSITION_CANCEL_OPEN`. This path sends an epoch-aware `CancelOpenRequest` to RS-A and then enters the `OBSERVE_FENCING` Wait-For-Fence state (shared with the FAILED_CLOSE RFC) to guarantee that RS-A is fenced before dispatching to RS-B. This preserves the pre-assignment blocking invariant (Epoch Fencing Design Section 1.2.1) for all failure modes where the region may be actively serving.

```java
// In TRSP, after OpenRegionProcedure reports FAILED_OPEN:  
if (regionNode.getFailedOpenRetries() >= maxRetries) {  
  LOG.error("Region {} has failed to open on {} different RSes "  
      + "(max retries: {}); leaving in FAILED_OPEN for manual investigation",  
      regionNode, regionNode.getFailedOpenRetries(), maxRetries);  
  master.getMetrics().incrementFailedOpenAutoRecoveryExhausted();  
  setRegionState(RegionState.State.FAILED_OPEN);  
  return;  
}

regionNode.incrementFailedOpenRetries();  
setForceNewPlan(true);

FailureClass failureClass = FailedOpenClassifier.classify(openProcedure);
if (failureClass == FailureClass.DEFINITIVE) {
  LOG.info("Region {} entered FAILED_OPEN (definitive: {}); scheduling "
      + "auto-recovery attempt {}/{} with {}ms cooldown (forceNewPlan=true)",
      regionNode, openProcedure.getException(),
      regionNode.getFailedOpenRetries(), maxRetries, cooldownDelay);
  master.getMetrics().incrementFailedOpenAutoRecoveryAttempts();
  setNextState(REGION_STATE_TRANSITION_OPEN_COOLDOWN);
} else {
  LOG.warn("Region {} entered FAILED_OPEN (ambiguous: {}); entering "
      + "Wait-For-Fence path, attempt {}/{} (forceNewPlan=true)",
      regionNode, openProcedure.getException(),
      regionNode.getFailedOpenRetries(), maxRetries);
  master.getMetrics().incrementFailedOpenAutoRecoveryAttempts();
  master.getMetrics().incrementFailedOpenAmbiguousRecoveryAttempts();
  setNextState(REGION_STATE_TRANSITION_CANCEL_OPEN);
}
```

##### FailedOpenClassifier

The `FailedOpenClassifier` is a utility class that inspects the exception chain from a failed `OpenRegionProcedure` and returns a `FailureClass` enum value.

```java
public class FailedOpenClassifier {
  public enum FailureClass { DEFINITIVE, AMBIGUOUS }

  public static FailureClass classify(OpenRegionProcedure proc) {
    Throwable cause = proc.getException();
    if (cause == null) {
      // No error report received; RS may be unreachable
      return FailureClass.AMBIGUOUS;
    }
    // Walk the exception chain
    Throwable t = cause;
    while (t != null) {
      if (t instanceof FileNotFoundException
          || t instanceof CorruptHFileException
          || t instanceof DoNotRetryIOException) {
        return FailureClass.DEFINITIVE;
      }
      if (t instanceof CallTimeoutException
          || t instanceof ConnectionClosedException) {
        return FailureClass.AMBIGUOUS;
      }
      t = t.getCause();
    }
    // Unknown exception; default to AMBIGUOUS for safety
    return FailureClass.AMBIGUOUS;
  }
}
```

##### New State: REGION_STATE_TRANSITION_OPEN_COOLDOWN

This new state implements the cooldown period between the moment a region enters `FAILED_OPEN` and the next auto-recovery attempt. The cooldown serves two purposes: it gives transient infrastructure problems (such as a DataNode restart or an HDFS pipeline recovery) time to resolve before the master dispatches another open request, and it rate-limits retries so that a large batch of simultaneously failing regions does not overwhelm the master's procedure executor or flood HDFS with concurrent open attempts.

The implementation uses the procedure framework's built-in timer API rather than sleeping on the ProcedureExecutor worker (PEWorker) thread. The handler reads the configured delay from `hbase.assignment.auto.recover.failed.open.delay` (default 30,000 ms) and calls `setTimeout()` to register the procedure with the master's timeout scheduler. It then sets the procedure's state to `WAITING_TIMEOUT`, which tells the procedure framework to remove the procedure from the runnable queue and park it until the timeout expires. This is critical for resource efficiency: the master maintains a fixed-size pool of PEWorker threads, and blocking one of them with a `Thread.sleep()` call would reduce the master's capacity to execute other procedures during the cooldown window. With `WAITING_TIMEOUT`, the PEWorker thread is immediately released back to the pool.

The handler also calls `skipPersistence()` to avoid writing this intermediate state transition to the procedure WAL. The `OPEN_COOLDOWN` state is purely transient: if the master crashes during the cooldown, the new active master will re-scan `hbase:meta`, discover the region is still in `FAILED_OPEN`, and restart recovery from scratch (as described in Section 3.2.1). Writing this state to the WAL would add unnecessary I/O on every retry with no correctness benefit.

When the timeout expires, the procedure framework wakes the procedure and re-enqueues it on a PEWorker thread. The handler sets the next state to `REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE`, which is the standard entry point for the assignment flow. From this point forward, the TRSP follows the normal path: it asks the load balancer for a new assignment plan (with the previous RS excluded because `forceNewPlan` was set to `true` in the preceding handler), increments the assignment epoch in `hbase:meta`, and dispatches a new `OpenRegionRequest` to the selected RegionServer. The epoch increment is handled by the existing assignment code path and ensures that any delayed response from the original RS is rejected as stale.

```java
case REGION_STATE_TRANSITION_OPEN_COOLDOWN:  
  // Suspend the procedure using the Master's ProcedureEvent timer API.  
  // This releases the PEWorker thread during the cooldown, avoiding  
  // resource waste.  
  long delay = conf.getLong(  
      "hbase.assignment.auto.recover.failed.open.delay", 30_000L);  
  setTimeout(Math.toIntExact(delay));  
  setState(ProcedureProtos.ProcedureState.WAITING_TIMEOUT);  
  skipPersistence();  
  // Upon wakeup (after timeout expires), transition back to  
  // GET_ASSIGN_CANDIDATE. The normal assignment flow will generate a  
  // new plan, increment the epoch in hbase:meta, and dispatch the  
  // open request to a different RS.  
  setNextState(REGION_STATE_TRANSITION_GET_ASSIGN_CANDIDATE);  
  break;
```

##### New State: REGION_STATE_TRANSITION_CANCEL_OPEN

This state implements the ambiguous failure recovery path. When the TRSP enters this state, the master does not know whether RS-A is still attempting to open the region or has already succeeded and is serving it. The TRSP must fence RS-A before dispatching to RS-B.

The handler sends an epoch-aware `CancelOpenRequest` RPC to RS-A. This is a new RPC that instructs RS-A to abort any in-progress open for the specified region if the open's epoch is less than or equal to the epoch in the request. If RS-A receives the `CancelOpenRequest` while it is mid-open, it aborts the open, rolls back any partial initialization, and confirms the cancellation. If RS-A has already completed the open and is serving the region, it treats the `CancelOpenRequest` as an epoch-aware close: it closes the region and confirms. If RS-A is unreachable (the RPC times out), the handler cannot confirm cancellation.

Regardless of the `CancelOpenRequest` outcome, the handler then transitions the TRSP to `REGION_STATE_TRANSITION_OBSERVE_FENCING`, the same Wait-For-Fence state used by the FAILED_CLOSE RFC (FAILED_CLOSE RFC Section 3.2.1). The `CancelOpenRequest` is a best-effort optimization to accelerate fencing; it is not relied upon for correctness. Even if the RPC fails, the `OBSERVE_FENCING` state will detect the region via heartbeats and escalate through the standard fencing infrastructure.

```java
case REGION_STATE_TRANSITION_CANCEL_OPEN:
  ServerName previousServer = regionNode.getRegionLocation();
  long currentEpoch = regionNode.getAssignmentEpoch();
  try {
    CancelOpenResponse response = master.getServerManager()
        .sendCancelOpenRegion(previousServer, regionNode.getRegionInfo(),
            currentEpoch);
    if (response.getCancelled()) {
      LOG.info("CancelOpen confirmed by RS {} for region {} at epoch {}",
          previousServer, regionNode, currentEpoch);
    } else {
      LOG.warn("CancelOpen rejected by RS {} for region {} at epoch {}; "
          + "proceeding to OBSERVE_FENCING",
          previousServer, regionNode, currentEpoch);
    }
  } catch (IOException e) {
    LOG.warn("CancelOpen RPC to RS {} failed for region {}; "
        + "proceeding to OBSERVE_FENCING",
        previousServer, regionNode, e);
  }
  setNextState(REGION_STATE_TRANSITION_OBSERVE_FENCING);
  break;
```

After the `CancelOpenRequest`, the TRSP enters the `OBSERVE_FENCING` Wait-For-Fence state. This state is shared with the FAILED_CLOSE RFC and is described in FAILED_CLOSE RFC Section 3.2.1. To summarize the behavior:

1. The TRSP increments the epoch to E_new and persists it to `hbase:meta` along with the new target RS (RS-B). This enables the master's `checkOnlineRegionsReport()` reconciliation loop to detect RS-A as reporting a stale epoch.

2. The TRSP registers on a `FencingCompletionEvent` attached to the `RegionStateNode` and suspends in `WAITING_TIMEOUT`, releasing the PEWorker thread.

3. The TRSP is awoken by one of the same release triggers as FAILED_CLOSE recovery:
   - **Path A (Cooperative):** The region disappears from RS-A's heartbeat (the CancelOpen or close succeeded), confirming fencing.
   - **Path B (Hard Fence):** RS-A ignores close RPCs and the master escalates to SCP, which acquires the WAL lease and physically fences RS-A.
   - **Path C (RS Dead):** RS-A's ZK session expires, triggering SCP independently.

4. Only after fencing is confirmed does the TRSP transition to `REGION_STATE_TRANSITION_OPEN` and dispatch the `OpenRegionRequest` to RS-B.

If the fencing timeout expires without confirmation (default: 10 minutes), the TRSP leaves the region in the terminal `FAILED_OPEN` state for manual investigation, preserving the safety invariant.

##### Successful OPENED Transition

Upon a successful `OPENED` transition (in the `REGION_STATE_TRANSITION_CONFIRM_OPENED` handler), clear the retry counter:

```java
// Reset the counter on successful open  
regionNode.clearFailedOpenRetries();
```

### 3.3 New TRSP States

Add two new states to the `RegionStateTransitionState` enum in `MasterProcedureProtos.proto`:

```java
enum RegionStateTransitionState {  
  // ... existing states ...  
  REGION_STATE_TRANSITION_OPEN_COOLDOWN = N;
  REGION_STATE_TRANSITION_CANCEL_OPEN = N+1;  
}
```

`REGION_STATE_TRANSITION_OPEN_COOLDOWN` is used by the definitive failure path (Section 3.2.2) to implement the cooldown before reassignment.

`REGION_STATE_TRANSITION_CANCEL_OPEN` is used by the ambiguous failure path (Section 3.2.2) to send the `CancelOpenRequest` RPC before entering the `OBSERVE_FENCING` Wait-For-Fence state.

The `REGION_STATE_TRANSITION_OBSERVE_FENCING` state used by the ambiguous failure path is defined in the FAILED_CLOSE RFC (Section 3.4) and is shared between both recovery features. It does not need to be added again.

### 3.4 Protobuf Changes: CancelOpenRequest / CancelOpenResponse

The ambiguous failure path (Section 3.2.2) introduces a new `CancelOpenRegion` RPC that the master sends to an RS to abort an in-progress open or close an already-opened region when the master has determined that the assignment is stale.

#### Message Definitions

```java
message CancelOpenRequest {
  required RegionSpecifier region = 1;
  optional uint64 assignment_epoch = 2;
}

message CancelOpenResponse {
  required bool cancelled = 1;
}
```

The `assignment_epoch` field carries the epoch that the master recorded when it initiated the open. The RS uses this to determine whether the cancellation request applies to its current open attempt or to a superseded one.

#### Service Method

Add the RPC to the `AdminService` definition in `Admin.proto`:

```java
service AdminService {
  // ... existing methods ...
  rpc CancelOpenRegion(CancelOpenRequest) returns (CancelOpenResponse);
}
```

#### RS-Side Handling Semantics

When a RegionServer receives a `CancelOpenRequest`, it evaluates the request against the region's current state on that RS:

**Region is mid-open and its epoch <= the request epoch.** The RS aborts the in-progress open, rolls back any partial initialization (closes store file readers, releases MemStore allocations, removes the region from the online regions map if it was partially registered), and responds with `cancelled = true`. This is the common case when the master timed out waiting for an open that is still in progress.

**Region has completed opening and is serving.** The RS treats the `CancelOpenRequest` as an epoch-aware close: it initiates a region close (flush MemStore, close store files, deregister from the online regions map), waits for the close to complete, and responds with `cancelled = true`. This handles the case where the open succeeded but the master never received the confirmation (e.g., the `OPENED` transition report was lost).

**Region is not known to the RS** (the RS never received the `OpenRegionRequest`, or the region was already closed). The RS responds with `cancelled = true`. This makes the RPC idempotent: the master can safely retry without side effects.

**Region's epoch > the request epoch.** This occurs if a newer assignment arrived at the RS after the master sent the `CancelOpenRequest` (a race during master failover or concurrent TRSP execution). The RS responds with `cancelled = false` to indicate that it holds a more recent assignment than the one the master is trying to cancel. The TRSP handles this by proceeding to `OBSERVE_FENCING` regardless (Section 3.2.2), so the `cancelled = false` response does not block recovery.

The RS-side handler is registered in `RSRpcServices` alongside the existing `closeRegion()` handler:

```java
@Override
public CancelOpenResponse cancelOpenRegion(RpcController controller,
    CancelOpenRequest request) throws ServiceException {
  RegionSpecifier spec = request.getRegion();
  long requestEpoch = request.hasAssignmentEpoch()
      ? request.getAssignmentEpoch() : Long.MAX_VALUE;

  HRegion region = getRegionByEncodedName(spec);
  if (region == null) {
    OpenRegionHandler handler = getOpeningRegion(spec);
    if (handler != null && handler.getEpoch() <= requestEpoch) {
      handler.abort();
      return CancelOpenResponse.newBuilder().setCancelled(true).build();
    }
    return CancelOpenResponse.newBuilder().setCancelled(true).build();
  }

  if (region.getAssignmentEpoch() > requestEpoch) {
    return CancelOpenResponse.newBuilder().setCancelled(false).build();
  }

  closeRegionForCancel(region);
  return CancelOpenResponse.newBuilder().setCancelled(true).build();
}
```

### 3.5 Metrics

Four new counters are added to the master metrics. The `failedOpenAutoRecoveryAttempts` counter is incremented each time the TRSP schedules an auto-recovery attempt for a `FAILED_OPEN` region (both definitive and ambiguous paths). The `failedOpenAmbiguousRecoveryAttempts` counter is incremented each time the TRSP enters the Wait-For-Fence path for an ambiguous failure, providing visibility into how often the more expensive recovery path is triggered. The `failedOpenAutoRecoveryExhausted` counter is incremented when a region exhausts all configured retries and is left in the terminal `FAILED_OPEN` state for manual investigation. The `failedOpenAutoRecoverySuccesses` counter is incremented when a previously `FAILED_OPEN` region successfully transitions to `OPENED`, indicating that the auto-recovery resolved the issue.

The ambiguous path also shares the `preAssignmentFencingBlocks` and `failedCloseAutoRecoveryTimedOut` counters with the FAILED_CLOSE auto-recovery feature, since both paths use the same `OBSERVE_FENCING` infrastructure.

### 3.6 Epoch Interaction

The epoch interacts differently with the two recovery paths.

**Definitive failure path.** When the TRSP resumes from `REGION_STATE_TRANSITION_OPEN_COOLDOWN` and enters `GET_ASSIGN_CANDIDATE`, the normal epoch lifecycle applies. The TRSP generates a new assignment plan with `forceNewPlan=true`, which excludes the previous RS from candidacy, and increments the epoch (`RegionStateNode.assignmentEpoch + 1`). The new epoch is persisted to `hbase:meta` in the same `Put` that records the new server assignment, and the `OpenRegionRequest` sent to the new RS includes the incremented epoch. Any delayed `OPENED` report from the original RS carries the old epoch and is rejected by `reportRegionStateTransition()` (Epoch Fencing Design Section 5.3.6). This is sufficient because the RS explicitly confirmed it never served the region.

**Ambiguous failure path.** The epoch interaction follows the FAILED_CLOSE pattern: the epoch is incremented when the TRSP enters `OBSERVE_FENCING`, but the `OpenRegionRequest` is NOT dispatched at that point. The epoch increment serves a detection-only purpose: it causes the master's `checkOnlineRegionsReport()` reconciliation loop to identify RS-A as stale on the next heartbeat, triggering epoch-aware close RPC escalation. The actual dispatch to RS-B occurs only after fencing is confirmed via the `FencingCompletionEvent`. This decoupling between epoch increment and open dispatch is what makes the ambiguous failure path safe. See FAILED_CLOSE RFC Section 3.6 for a detailed analysis of why this decoupling is necessary.

### 3.7 HBCK2 Enhancements (hbase-operator-tools)

When auto-recovery exhausts the configured retry budget (`failedOpenAutoRecoveryExhausted` metric fires), the region is left in the terminal `FAILED_OPEN` state for manual investigation. The following HBCK2 enhancements provide operators with the tools to diagnose the failure, fix the underlying cause, and re-enable auto-recovery without requiring a master restart. These enhancements build on the core epoch inspection and repair commands defined in the Epoch Fencing Design (Epoch Fencing Design Section 5.5).

#### 3.7.1 Recovery Counter Reset

**`resetRecoveryCounters <ENCODED_REGIONNAME>...`**

Reset the in-memory `failedOpenRetries` counter (Section 3.2.1) for specified regions to zero. This allows the auto-recovery state machine to retry after the operator has addressed the underlying issue (e.g., repaired corrupt HFiles via HBCK2's `filesystem` command, restored missing HDFS blocks, resolved a network partition). Without this command, the only way to reset the counter is a master failover, which is disruptive and resets counters for all regions indiscriminately.

The command requires a new method on the `Hbck` service interface:

```java
void resetFailedRecoveryCounters(List<String> encodedRegionNames)
    throws IOException;
```

The implementation resets both `failedOpenRetries` and `failedCloseRetries` on each specified `RegionStateNode`. Resetting both counters in a single operation is intentional: the underlying `RegionStateNode` may have accumulated counts from both failure types across its lifetime, and operators recovering a region should start with a clean slate. The command requires `ADMIN` permission.

After resetting the counter, the operator uses the existing HBCK2 `assigns` command to re-trigger a `TransitRegionStateProcedure` for the region. The new TRSP follows the auto-recovery path from the beginning with a fresh retry budget. Because `assigns` goes through the epoch-aware TRSP path (Epoch Fencing Design Section 5.5.4), the epoch is incremented and any stale state from prior assignments is fenced.

#### 3.7.2 Fencing State Diagnostics

**`reportFencingState [<ENCODED_REGIONNAME>...]`**

Display the current fencing state for regions whose TRSPs are in the `OBSERVE_FENCING` state. For each region, the report includes: the encoded region name, the old RS (being fenced), the target RS (waiting for assignment), the stale epoch and current epoch, the fencing wait duration, the release trigger status (waiting for cooperative close, SCP escalation pending, or timed out), and the number of epoch-aware close RPCs sent to the old RS.

This command is shared with the FAILED_CLOSE RFC (FAILED_CLOSE RFC Section 3.7.2) since both recovery paths use the same `OBSERVE_FENCING` infrastructure. It requires a new method on the `Hbck` service interface:

```java
List<FencingStateInfo> getFencingState(
    List<String> encodedRegionNames) throws IOException;
```

If no region names are provided, the command returns fencing state for all regions currently in `OBSERVE_FENCING`. The `FencingStateInfo` message is defined as:

```java
message FencingStateInfo {
  required string encoded_region_name = 1;
  required ServerName old_server = 2;
  required ServerName target_server = 3;
  required uint64 stale_epoch = 4;
  required uint64 current_epoch = 5;
  required uint64 wait_duration_ms = 6;
  required string release_trigger_status = 7;
  required int32 close_rpcs_sent = 8;
}
```

For `FAILED_OPEN` recovery specifically, this command is most useful when the `failedOpenAmbiguousRecoveryAttempts` metric is elevated. The report allows the operator to determine: whether the old RS is cooperating (close RPCs being acknowledged and region expected to disappear from heartbeats shortly), whether the old RS is ignoring close RPCs (suggesting SCP escalation is imminent or should be triggered manually via `scheduleRecoveries`), or whether the fencing timeout is about to expire (suggesting the operator should intervene before the region falls to terminal `FAILED_OPEN`).

## 4. Operational Considerations

### 4.1 Tuning the Cooldown

The default 30-second cooldown gives transient HDFS issues such as DataNode restarts and pipeline recovery time to resolve before the next attempt, prevents rapid-fire retries that would exacerbate master load if many regions fail simultaneously, and provides operators with a window to investigate before the next attempt. For clusters with fast HDFS recovery the cooldown can be reduced to 10 seconds. For clusters with slow HDFS recovery (e.g., cold data on spinning disks), 60 seconds or more may be appropriate.

### 4.2 Monitoring

Operators should alert on `failedOpenAutoRecoveryExhausted > 0`, which indicates regions that have exhausted all auto-recovery retries and require manual investigation. The `failedOpenAutoRecoveryAttempts` and `failedOpenAutoRecoverySuccesses` metrics provide visibility into how often auto-recovery fires and how effective it is. The `failedOpenAmbiguousRecoveryAttempts` metric tracks how often the more expensive Wait-For-Fence path is triggered; a high ratio of ambiguous to total attempts may indicate widespread network instability or RS unresponsiveness that warrants investigation.

## 5. Testing Plan

### 5.1 Unit Tests

Unit tests for `TransitRegionStateProcedure` verify that a `FAILED_OPEN` with a definitive exception (e.g., `FileNotFoundException`) transitions to `OPEN_COOLDOWN`, that an ambiguous exception (e.g., `CallTimeoutException`) transitions to `CANCEL_OPEN`, that an unrecognized exception defaults to the ambiguous path, and that `forceNewPlan` is set to `true` on both paths. They also verify that a region whose `failedOpenRetries` counter has reached the configured maximum is left in the terminal `FAILED_OPEN` state with the `failedOpenAutoRecoveryExhausted` metric incremented, that `clearFailedOpenRetries()` resets the counter on a successful `OPENED` transition, and that auto-recovery is skipped entirely when the feature is disabled.

Unit tests for `FailedOpenClassifier` verify that each recognized definitive exception type (`FileNotFoundException`, `CorruptHFileException`, `DoNotRetryIOException` subclasses) returns `DEFINITIVE`, that each recognized ambiguous exception type (`CallTimeoutException`, `ConnectionClosedException`) and a `null` exception return `AMBIGUOUS`, that unrecognized exception types default to `AMBIGUOUS`, and that exception chain walking correctly identifies a definitive cause wrapped inside a higher-level `IOException`.

Unit tests for `RegionStateNode` verify that `failedOpenRetries` initializes to zero, increments correctly, and resets to zero on `clearFailedOpenRetries()`.

### 5.2 Integration Tests

`testFailedOpenAutoRecoveryDefinitive` starts a minicluster with three RSes and injects a fault on RS-A that produces a `FileNotFoundException` on region open. The test verifies that the TRSP enters `OPEN_COOLDOWN` (not `OBSERVE_FENCING`), waits for the cooldown to expire, dispatches to a different RS, and that the region reaches `OPENED` with an incremented epoch in `hbase:meta`. It also verifies that a simulated delayed `OPENED` report from RS-A at the old epoch is rejected.

`testFailedOpenAutoRecoveryAmbiguous` injects a fault that causes the `OpenRegionRequest` RPC to RS-A to time out. The test verifies that the TRSP classifies the failure as ambiguous, transitions to `CANCEL_OPEN`, sends a `CancelOpenRequest` to RS-A, enters `OBSERVE_FENCING`, and does not dispatch to RS-B until the region disappears from RS-A's heartbeat.

`testFailedOpenAmbiguousWithActiveRS` instruments RS-A to delay its open processing so the master records `FAILED_OPEN` while RS-A completes the open and begins serving. The test verifies that the master detects RS-A's stale epoch via heartbeats, sends epoch-aware close RPCs, and that at no point do both RS-A and RS-B serve the region concurrently — the critical pre-assignment blocking invariant.

`testFailedOpenAmbiguousSCPEscalation` makes RS-A unresponsive to both the `CancelOpenRequest` and all epoch-aware close RPCs. The test verifies that the master escalates to SCP after the configured threshold, that SCP revokes RS-A's WAL lease, and that the TRSP resumes and assigns the region to RS-B.

`testFailedOpenAutoRecoveryExhaustion` configures `max.retries=2` and injects faults on all RSes so every open fails. The test verifies that after exhausting all retries, the region is left in the terminal `FAILED_OPEN` state and the `failedOpenAutoRecoveryExhausted` metric is incremented.

`testFailedOpenMasterFailover` triggers auto-recovery, kills the active master during the cooldown (definitive path) or `OBSERVE_FENCING` (ambiguous path), and verifies that the standby master restarts recovery from scratch and the region eventually reaches `OPENED`.

### 5.3 HBCK2 / hbase-operator-tools Tests

These tests live in the hbase-operator-tools repository and exercise the HBCK2 commands introduced in Section 3.7 against a MiniHBaseCluster with auto-recovery enabled.

`testResetFailedOpenRetries` configures `max.retries=1` and injects a fault on all RSes that produces a `FileNotFoundException` on region open. The test verifies the region exhausts its single retry and enters terminal `FAILED_OPEN` with the `failedOpenAutoRecoveryExhausted` metric incremented. It then removes the fault, calls `resetRecoveryCounters` via HBCK2 for the affected region, calls `assigns` to re-trigger recovery, and verifies the region successfully opens on a healthy RS with an incremented epoch.

`testResetFailedOpenRetriesNoEffect` calls `resetRecoveryCounters` for a region that is currently in `OPEN` state (not in `FAILED_OPEN`), verifies the command succeeds without error (the counter is simply set to zero), and verifies the region's serving state is unaffected.

`testReportFencingStateFailedOpen` injects an ambiguous failure (RPC timeout on the `OpenRegionRequest`) so the TRSP enters the `CANCEL_OPEN` -> `OBSERVE_FENCING` path. The test calls `reportFencingState` and verifies the report contains the correct old RS, target RS, stale and current epoch values, and that the wait duration is increasing. After the old RS cooperatively closes the region and the TRSP resumes, the test calls `reportFencingState` again and verifies the region is no longer listed.

`testReportFencingStateEmpty` calls `reportFencingState` on a healthy cluster with no regions in `OBSERVE_FENCING` and verifies an empty report is returned without error.

`testAssignsAfterExhaustedRetries` exhausts auto-recovery retries for a region (terminal `FAILED_OPEN`), uses HBCK2 `assigns` to manually assign the region, and verifies the epoch is incremented in `hbase:meta` and the region opens successfully on the target RS.

`testAssignsWithOverrideAfterAmbiguousFailure` puts a region into the ambiguous `OBSERVE_FENCING` path, then uses `assigns -o` (override) to bypass the stuck TRSP and force a new assignment. The test verifies the override submits a new TRSP that increments the epoch and that the old RS is fenced via heartbeat detection after the new assignment completes.

## 6. Availability Impact Analysis

### 6.1 Unavailability Window

Without this feature, a region that reaches `FAILED_OPEN` remains unavailable indefinitely until an operator manually intervenes via HBCK2. The mean time to recovery (MTTR) depends entirely on how quickly an operator notices the alert, diagnoses the root cause, and issues the appropriate HBCK2 command. In practice this ranges from minutes to hours.

With auto-recovery enabled, the unavailability window depends on the failure class.

**Definitive failures (cooldown-and-reassign path):**

```
T_unavail_definitive = T_rs_retries + (N * T_cooldown) + T_open
```

where `T_rs_retries` is the time the original RS spends exhausting its local open retries (typically under 60 seconds with default settings), `N` is the number of cluster-level auto-recovery attempts needed (1 in the common case of a single faulty RS), `T_cooldown` is the configured cooldown delay (default 30 seconds), and `T_open` is the time the new RS takes to open the region (typically seconds for small regions, potentially minutes for large regions with many store files). In the common single-retry case, the total unavailability is roughly 90 to 120 seconds, compared with an unbounded window under the status quo.

**Ambiguous failures (Wait-For-Fence path):**

```
T_unavail_ambiguous_cooperative = T_rs_retries + T_cancel_open + T_heartbeat_wait + T_open
T_unavail_ambiguous_scp = T_rs_retries + T_cancel_open + T_scp_threshold + T_scp_processing + T_open
```

In the cooperative case, where RS-A responds to the `CancelOpenRequest` or the epoch-aware close RPCs and the region disappears from its heartbeat, the total unavailability is roughly 75 to 90 seconds (60s RS retries + 15s heartbeat wait + region open time). In the SCP escalation case, where RS-A is unresponsive, the total is roughly 8 to 10 minutes, identical to the FAILED_CLOSE SCP path. Both are bounded and deterministic, compared with the unbounded window under the status quo. The ambiguous path is slower than the definitive path but preserves the safety invariant.

### 6.2 Failure Scenarios

The following paragraphs describe how auto-recovery affects availability across representative failure scenarios.

In a single RS crash or OOM kill where the RS explicitly reported the failure before dying (a definitive failure), the TRSP applies the fast cooldown-and-reassign path. The region is typically serving again within roughly 90 seconds of the original failure.

When a transient HDFS issue such as a DataNode restart or pipeline recovery causes the open to fail with an explicit error (e.g., `FileNotFoundException` because a block is temporarily unreachable), the failure is classified as definitive. The cooldown period naturally absorbs the HDFS recovery window. By the time the TRSP wakes from the cooldown and dispatches the retry, the DataNode has typically rejoined the cluster and the region opens successfully on the first auto-recovery attempt, with no operator involvement required.

When the open fails due to an RPC timeout or network partition (an ambiguous failure), the master cannot determine whether RS-A completed the open and is serving. The TRSP enters the Wait-For-Fence path: it sends a `CancelOpenRequest` to RS-A, then enters `OBSERVE_FENCING` to wait for heartbeat-based confirmation that RS-A is no longer serving the region. In the common case where RS-A was genuinely down or responds to the cancel, fencing is confirmed within 15 seconds (5 heartbeat cycles) and the region is reassigned. If RS-A is alive and serving (the GC-pause scenario), the master detects this via heartbeats, sends epoch-aware close RPCs, and waits for RS-A to close before dispatching to RS-B. This path is slower than the definitive path but eliminates the split-brain window that would otherwise exist.

For persistent data-layer failures such as corrupt HFiles or permanently missing HDFS blocks, the RS typically reports a definitive error (`CorruptHFileException`, `FileNotFoundException`). The TRSP cycles through all configured retries via the fast path, each targeting a different RS via `forceNewPlan`. After all retries are exhausted, the region is left in the terminal `FAILED_OPEN` state, the same end state as the manual path, but the `failedOpenAutoRecoveryExhausted` metric fires immediately, giving monitoring systems faster detection than a purely operator-driven workflow.

In the case of widespread RS failures such as a rack loss, many regions enter `FAILED_OPEN` simultaneously. If the failures are definitive (RSes crashed and reported errors), the cooldown and `forceNewPlan` mechanisms spread retries across the surviving RS pool. If the failures are ambiguous (network partition isolating a rack), all affected regions enter the Wait-For-Fence path concurrently. The `OBSERVE_FENCING` state parks all affected TRSPs using `WAITING_TIMEOUT`, releasing PEWorker threads. The master's reconciliation loop handles detection for all regions on the partitioned RSes. If the surviving pool is too small to absorb the load, some retries will fail and the per-region retry budget will prevent runaway assignment storms; regions that cannot be placed are left in `FAILED_OPEN` for manual triage, same as the status quo.

If a master failover occurs while one or more regions are in the cooldown phase or in `OBSERVE_FENCING`, the in-memory `failedOpenRetries` counters and any `FencingCompletionEvent` state are lost along with the old master's state. When the new active master starts, it re-scans `hbase:meta` during initialization, discovers all regions still recorded as `FAILED_OPEN`, and restarts the auto-recovery process from scratch with retry counters reset to zero. The new master will re-classify the failure and select the appropriate path. The total additional delay is one master election cycle plus the first recovery attempt on the new master. Without auto-recovery, the same master failover would leave the regions in `FAILED_OPEN` until the operator intervenes on the new master, so the feature strictly reduces the recovery window even in this case.

### 6.3 Worst-Case Timeline

The worst case depends on the failure class.

**Definitive failures.** The worst case occurs when the underlying failure is persistent (e.g., an HFile that is corrupt on all HDFS replicas) and the region cannot be opened on any RS. The TRSP cycles through all configured retries via the fast cooldown path:

```
T_worst_definitive = T_rs_retries + (max_retries * T_cooldown)
```

With defaults (`max_retries=3`, `T_cooldown=30s`, `T_rs_retries~60s`), the worst-case time before the region is declared terminally `FAILED_OPEN` is approximately 150 seconds.

**Ambiguous failures.** The worst case occurs when RS-A is unresponsive and the fencing timeout expires on each retry:

```
T_worst_ambiguous = T_rs_retries + (max_retries * T_fencing_timeout)
```

With defaults (`max_retries=3`, `T_fencing_timeout=600s`), the worst-case time is approximately 30 minutes. However, SCP escalation (which is triggered after roughly 8 minutes of sustained non-cooperation) will typically resolve the situation before the fencing timeout expires, yielding a practical worst case of roughly 8 to 10 minutes per retry.

In all cases, during the wait window the region is unavailable, the master's procedure executor is not blocked (the TRSP uses `WAITING_TIMEOUT` rather than sleeping on a PEWorker thread), and each attempt is logged and metered for operator visibility. After exhaustion, the region is in the same state it would have been in without the feature, but the operator now has detailed metrics and logs showing which RSes were tried and why each attempt failed.

### 6.4 Impact on Cluster-Wide Availability

For large-scale failures where many regions enter `FAILED_OPEN` simultaneously, the behavior depends on the failure class. Definitive failures use the cooldown as a natural rate limiter; all affected regions are suspended concurrently (not sequentially), so wall-clock recovery time does not scale linearly with the number of failing regions. Ambiguous failures enter `OBSERVE_FENCING` concurrently and are all parked via `WAITING_TIMEOUT`, releasing PEWorker threads. If many ambiguous failures target the same RS, the master's SCP escalation for that RS resolves all of them in a single SCP cycle, because the `FencingCompletionEvent` fires for all regions on the crashed server when SCP completes.

The `forceNewPlan` flag distributes retries across the surviving RS pool, avoiding hotspotting a single replacement RS with a burst of concurrent opens. If the surviving RS pool is too small to absorb the load, some retries will fail and the per-region retry budget prevents runaway assignment storms. Operators can further protect the cluster by increasing the cooldown or reducing `max.retries` for clusters known to experience correlated bulk failures.

### 6.5 Comparison with Status Quo

The feature strictly improves availability for transient failures and is neutral for persistent failures. In the definitive-failure case, regions recover automatically in under two minutes rather than waiting for human intervention. In the ambiguous-failure case, regions recover after fencing is confirmed, typically within 15 to 20 seconds for cooperative RSes or 8 to 10 minutes for uncooperative RSes requiring SCP escalation. This is slower than the definitive path but eliminates the window of dual service that would otherwise exist. In the persistent case, the region exhausts its retry budget and arrives at the same terminal `FAILED_OPEN` state that exists today, with the added benefit of structured metrics (`failedOpenAutoRecoveryExhausted`, `failedOpenAmbiguousRecoveryAttempts`) and detailed logs that accelerate the operator's manual investigation.

The feature does not introduce any new failure modes. For definitive failures, the actions taken (forcing a new assignment plan, incrementing the epoch, and dispatching an `OpenRegionRequest`) are identical to the actions an operator would take via HBCK2. For ambiguous failures, the actions taken (sending `CancelOpenRequest`, entering `OBSERVE_FENCING`, waiting for heartbeat confirmation or SCP escalation) are identical to the FAILED_CLOSE recovery path, which provides the same HDFS-layer safety guarantees. The differentiated handling ensures that the pre-assignment blocking invariant (Epoch Fencing Design Section 1.2.1) is satisfied for all failure modes.

## 7. Performance Analysis

### 7.1 Procedure Executor Overhead

Both recovery paths reuse the existing TRSP rather than spawning a new procedure.

**Definitive failure path.** Each retry adds exactly one additional pass through the TRSP state machine (`OPEN_COOLDOWN` -> `GET_ASSIGN_CANDIDATE` -> `OPEN`), which is identical in cost to the work the procedure executor already performs for a normal region assignment. During the cooldown phase the TRSP is parked in the `WAITING_TIMEOUT` state, which removes it from the runnable queue entirely and releases the PEWorker thread back to the pool. The procedure framework's timeout scheduler tracks the suspended procedure in a priority queue keyed by wake-up time, adding O(log P) insertion cost where P is the number of concurrently parked procedures, a negligible contribution even on clusters with thousands of regions in flight. Because the PEWorker thread is not blocked, the cooldown period imposes zero reduction in the master's capacity to execute other procedures such as splits, merges, or balancer-driven assignments.

**Ambiguous failure path.** Each retry adds two additional states (`CANCEL_OPEN` -> `OBSERVE_FENCING`) before the TRSP can proceed to `OPEN`. The `CANCEL_OPEN` state executes one RPC to RS-A and returns immediately regardless of outcome. The `OBSERVE_FENCING` state parks the TRSP in `WAITING_TIMEOUT`, releasing the PEWorker thread for the duration of the fencing wait (up to 10 minutes in the worst case). The per-procedure cost of the fencing wait is identical to the FAILED_CLOSE recovery path (FAILED_CLOSE RFC Section 7.1). During the fencing wait, the master's reconciliation loop handles detection and close-RPC escalation using existing infrastructure with no additional per-region cost.

The `skipPersistence()` call in the `OPEN_COOLDOWN` and `OBSERVE_FENCING` handlers eliminates one procedure WAL write per state transition. Over the life of a single auto-recovery sequence with the default three retries, this saves three to six WAL syncs depending on the failure classes encountered. Each WAL sync is an HDFS append followed by an `hsync()`, so the savings are proportional to the HDFS round-trip latency of the master's procedure WAL directory, typically 5-15 ms per sync on a healthy cluster.

### 7.2 hbase:meta Write Amplification

Each auto-recovery attempt generates one additional `Put` to `hbase:meta` to record the new server assignment and the incremented epoch. This is the same single-row mutation that any normal region assignment performs and is not an additional cost introduced by the feature; it is simply the cost of re-assigning the region. With the default configuration of three maximum retries, a region that fails on every attempt produces at most three extra `hbase:meta` writes over its lifetime in `FAILED_OPEN`. For context, a region that undergoes a normal balancer-driven move also produces one `hbase:meta` write, so three retries are equivalent to three balancer moves from a `hbase:meta` I/O perspective.

For the definitive failure path, the cooldown period between retries naturally rate-limits these writes. With a 30-second default cooldown, even a burst of 1,000 simultaneously failing regions produces at most 1,000 `hbase:meta` writes per 30-second window, which is well within the throughput capacity of a healthy `hbase:meta` table (which routinely sustains tens of thousands of mutations per second during rolling restarts and large-scale rebalancing operations).

For the ambiguous failure path, the `hbase:meta` write occurs at entry to `OBSERVE_FENCING` (to record the incremented epoch), and no additional writes occur during the fencing wait or upon resumption. This is identical to the FAILED_CLOSE write amplification profile (FAILED_CLOSE RFC Section 7.2).

### 7.3 RegionServer-Side Cost

When the TRSP dispatches an `OpenRegionRequest` to a new RS, the RS performs the full region open sequence: loading the `RegionInfo` from `hbase:meta`, opening each HFile's reader (which involves reading the file trailer and loading the block index), replaying any unrecovered WAL edits, and initializing the MemStore. The cost of this work is dominated by the number and size of HFiles in the region's stores and by whether the HDFS block cache is warm or cold on the new RS. Auto-recovery does not change any aspect of this open cost. The retried open is identical to the open that an operator would trigger via HBCK2 or that the balancer would trigger during a normal region move.

The ambiguous failure path adds one `CancelOpenRequest` RPC to RS-A per retry. This is a lightweight RPC that checks whether the region is mid-open or serving, and either aborts the open or initiates a close. The RS-side cost is dominated by the close operation if the region was serving, which is identical to a normal region close.

### 7.4 Memory Footprint

The per-region memory cost of the feature is a single `int` field (`failedOpenRetries`) added to `RegionStateNode`, contributing 4 bytes per region. On a cluster managing 100,000 regions, this adds approximately 400 KB of heap across all `RegionStateNode` instances, a negligible increase relative to the existing per-region memory footprint of `RegionStateNode` (which includes the `RegionInfo`, location state, and assignment metadata).

For the definitive failure path, no additional data structures are allocated during the cooldown phase. The `WAITING_TIMEOUT` state is tracked by the procedure framework's existing timeout scheduler, which uses a `DelayQueue` that is already present regardless of whether auto-recovery is enabled.

For the ambiguous failure path, a `FencingCompletionEvent` object is created on-demand (approximately 24 bytes per region, as described in FAILED_CLOSE RFC Section 7.4). This object exists only while the TRSP is in the `OBSERVE_FENCING` state and is garbage collected after the TRSP resumes. During normal operation with no ambiguous `FAILED_OPEN` regions, no `FencingCompletionEvent` objects exist.

### 7.5 Scaling Under Bulk Failures

The most performance-sensitive scenario is a correlated bulk failure where hundreds or thousands of regions enter `FAILED_OPEN` simultaneously, for example due to a rack power loss or a widespread HDFS outage. The scaling behavior depends on the failure class.

**Definitive bulk failures** (e.g., RS crashes with explicit error reports): All affected TRSPs enter the `OPEN_COOLDOWN` state and park in the timeout scheduler concurrently. The scheduler's `DelayQueue` absorbs the burst with one O(log P) insertion per TRSP, and because all procedures share approximately the same cooldown expiry time, they wake up in a narrow window. When they wake, they re-enter the runnable queue and contend for PEWorker threads. The procedure executor processes them in FIFO order, and the per-procedure work (`GET_ASSIGN_CANDIDATE` -> `OPEN` dispatch) is lightweight: it involves one load-balancer call and one RPC to the target RS. The load-balancer call is the most expensive step (it evaluates cluster-wide cost functions), but HBase's `StochasticLoadBalancer` already handles concurrent invocations and the `forceNewPlan` flag narrows the candidate set, reducing the search space.

**Ambiguous bulk failures** (e.g., a network partition isolating a rack): All affected TRSPs send `CancelOpenRequest` RPCs to RS-A and then enter `OBSERVE_FENCING` concurrently. The `CancelOpenRequest` RPCs will likely fail (the RS is unreachable), so the TRSPs quickly transition to the fencing wait. All TRSPs park in `WAITING_TIMEOUT`, releasing PEWorker threads. The master's reconciliation loop detects all affected regions as stale on the next heartbeat cycle and begins epoch-aware close RPC escalation. If the entire rack is lost, SCP is triggered once per affected RS (not per region), and each SCP resolves all `FAILED_OPEN` regions on that RS simultaneously by firing all their `FencingCompletionEvent` objects. This means the total SCP cost scales with the number of affected RSes, not the number of affected regions.

If the bulk failure is large enough that the PEWorker thread pool becomes saturated, the surplus TRSPs simply wait in the runnable queue until a thread becomes available. This is identical to the queuing behavior during a large-scale balancer run or a rolling restart, and the procedure framework's existing backpressure mechanisms apply without modification. The per-region retry budget (default 3) caps the total number of retry cycles, so the maximum additional load from auto-recovery is bounded by `R * max_retries` procedure executions where R is the number of simultaneously failing regions, spread across `max_retries` cooldown or fencing windows.

For extremely large bursts, operators can increase the cooldown to spread retries over a wider time window, or reduce `max.retries` to limit the total number of retry cycles.
