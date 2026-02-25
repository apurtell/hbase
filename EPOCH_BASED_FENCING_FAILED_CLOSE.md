# RFC: Auto-Recovery for FAILED_CLOSE State

**Updated:** February 2026
**Version:** 1.1
**Status:** Draft

## 1. Objective

Safely automate the resolution of `FAILED_CLOSE` regions by using heartbeat-based epoch fencing to ascertain the true state of the RegionServer. This eliminates manual HBCK2 intervention while preventing the split-brain data corruption windows inherent in asynchronous fencing.

## 2. Architecture and Design

Dependency: [RFC: Epoch-Based Assignment Fencing](https://docs.google.com/document/d/14litc0ex2rR6ScBoNUY0hRej7Q8Ij0LvZ1tGI_DEd38/edit?tab=t.0)

A `FAILED_CLOSE` state indicates that the master attempted to close a region on a RegionServer but does not know whether the close succeeded. The RS may have ignored the close and continued serving, or it may be in a partially closed state. This ambiguity makes `FAILED_CLOSE` the most dangerous recovery scenario because the region may still be actively serving writes on the original RS.

The master cannot simply safely dispatch an `OpenRegionRequest` to a new RS while the original RS may still be serving the region. If it did, both RSes would accept writes concurrently, and the original RS's writes would produce WAL entries that will never be replayed by any subsequent region open, resulting in silent data loss. One RS might decide to compact the store files out from underneath the other RS, corrupting the store and resulting in obvious data loss.

Therefore, `FAILED_CLOSE` recovery must be synchronous with respect to fencing. When a close fails, the `TransitRegionStateProcedure` (TRSP) will not abort. Instead, it will enter an `OBSERVE_FENCING` phase. It will rely on the master's reconciliation loop to monitor the original RS. If the region drops from the RS's heartbeat, the TRSP proceeds with assignment. If the region persists, the TRSP waits for the master's epoch-aware fencing to escalate to the `ServerCrashProcedure` (SCP). Only after SCP physically revokes the original RS's WAL lease at the HDFS layer will the TRSP assign the region to a new RS.

This design is built on the epoch-based assignment fencing mechanism described in *Epoch Fencing Design*. Unlike `FAILED_OPEN` recovery, which uses epoch fencing primarily for stale transition rejection, `FAILED_CLOSE` recovery depends on the full fencing infrastructure for correctness:

1. **Pre-assignment blocking invariant.** The master MUST NOT dispatch an `OpenRegionRequest` to a new RS until it has confirmed that the previous assignment holder has been fenced, either by confirmed region close (the region disappears from the RS's heartbeat report) or by physical fencing (SCP / WAL lease revocation). This invariant (Epoch Fencing Design Section 1.2.1) is the foundational safety property of `FAILED_CLOSE` recovery.

2. **Heartbeat-based detection.** The master's `checkOnlineRegionsReport()` reconciliation loop (Epoch Fencing Design Section 5.3.3) monitors each RS's heartbeat for the region. When the region disappears, the close is confirmed. When it persists with a stale epoch, the master escalates via epoch-aware close RPCs.

3. **SCP escalation.** If the RS is uncooperative after exhausting close attempts, the master calls `expireServer()` to trigger SCP (Epoch Fencing Design Section 5.3.5). SCP acquires the lease on the RS's WAL, physically fencing it at the HDFS layer and guaranteeing that the RS can no longer persist writes, even if it is still alive.

Without epoch fencing, `FAILED_CLOSE` recovery is fundamentally unsafe because the master has no mechanism to confirm that the original RS has stopped serving the region before dispatching a new assignment.

## 3. Implementation Plan

### 3.1 Configuration Additions

The integer property `hbase.assignment.failed.close.heartbeat.wait` (default `5` cycles) specifies the number of heartbeat cycles to observe before considering the RS actively uncooperative. At the default 3-second heartbeat interval, this amounts to 15 seconds of observation before escalating.

The integer property `hbase.assignment.auto.recover.failed.close.max.retries` (default `20`) sets the maximum number of cluster-level retries before leaving the region in a terminal `FAILED_CLOSE` state for manual intervention. Each time a region enters `FAILED_CLOSE`, transitions through `OBSERVE_FENCING`, gets assigned to a new RS, and enters `FAILED_CLOSE` again, one retry is consumed. This budget prevents infinite assignment loops for regions whose close failures are caused by persistent, region-specific problems (e.g., a very large memstore that causes every close to time out on every RS). The default of 20 is deliberately higher than the `FAILED_OPEN` retry budget (default 3) because `FAILED_CLOSE` failures are overwhelmingly RS-specific rather than region-specific, making it likely that retrying on a different RS will succeed. A budget of 20 retries allows the system to cycle through a substantial fraction of the cluster's RS pool before giving up.

### 3.2 State Machine Modifications (TRSP)

#### 3.2.1 Extend RegionStateNode

Add a `failedCloseRetries` integer counter (in-memory only). This counter tracks how many times the master has attempted to auto-recover a `FAILED_CLOSE` region across different RSes at the cluster level. It is distinct from any single close attempt's retry behavior.

```java
// New field in RegionStateNode
private volatile int failedCloseRetries = 0;

public int getFailedCloseRetries() { return failedCloseRetries; }

public void incrementFailedCloseRetries() { failedCloseRetries++; }

public void clearFailedCloseRetries() { failedCloseRetries = 0; }
```

The counter is in-memory only and resets on master failover, following the same design rationale as the `failedOpenRetries` counter in the FAILED_OPEN RFC. When a master failover occurs, the new active master re-scans `hbase:meta` to rebuild all `RegionStateNode` state from scratch. Any regions still recorded as `FAILED_CLOSE` in `hbase:meta` will be detected during this scan, and the recovery process will restart from zero retries on the new master. The worst-case consequence is that a region receives up to `max.retries` additional recovery attempts beyond what the previous master had already tried. Given the default of 20 retries and the high likelihood that `FAILED_CLOSE` resolves on a different RS, this is an acceptable trade-off.

#### 3.2.2 Trigger Transition to OBSERVE_FENCING

When the `CloseRegionProcedure` sub-procedure fails, the existing TRSP code transitions to `FAILED_CLOSE`. With auto-recovery enabled, the TRSP must check the retry budget and redirect to `OBSERVE_FENCING` instead:

```java
// In TRSP close-failure handler (currently transitions to FAILED_CLOSE):
if (autoRecoverFailedClose) {
  int maxRetries = conf.getInt(
      "hbase.assignment.auto.recover.failed.close.max.retries", 20);
  if (regionNode.getFailedCloseRetries() >= maxRetries) {
    LOG.error("Region {} has failed to close on {} different RSes "
        + "(max retries: {}); leaving in FAILED_CLOSE for manual "
        + "investigation",
        regionNode, regionNode.getFailedCloseRetries(), maxRetries);
    master.getMetrics().incrementFailedCloseAutoRecoveryExhausted();
    setRegionState(RegionState.State.FAILED_CLOSE);
    return;
  }
  regionNode.incrementFailedCloseRetries();
  setNextState(REGION_STATE_TRANSITION_OBSERVE_FENCING);
} else {
  setRegionState(RegionState.State.FAILED_CLOSE);
}
```

The retry budget check is performed before entering `OBSERVE_FENCING` to avoid consuming a fencing timeout cycle for a region that has already exhausted its budget. If the budget is exhausted, the TRSP logs an ERROR, increments the `failedCloseAutoRecoveryExhausted` metric, and transitions to the terminal `FAILED_CLOSE` state, preserving the existing operator-intervention model.

#### 3.2.3 New State: REGION_STATE_TRANSITION_OBSERVE_FENCING

When the `CloseRegionProcedure` times out or fails, the TRSP transitions to a new state: `REGION_STATE_TRANSITION_OBSERVE_FENCING`. This state implements the Wait-For-Fence pattern that is the core of `FAILED_CLOSE` recovery.

Because the procedure executor re-enters the same `case` label when the TRSP is awoken (either by `FencingCompletionEvent.wake()` or by timeout expiry), the implementation must use a single `case` block with explicit dispatch logic to distinguish first entry from re-entry. The dispatch checks `fencingEvent.isCompleted()` and timeout state to route to the correct path:

- **First entry** (no existing event): increment epoch, register waiter, suspend.
- **Re-entry after wake** (event completed): fencing confirmed, proceed to OPEN (Section 3.2.6).
- **Re-entry after timeout** (event not completed, timeout expired): fail to FAILED_CLOSE (Section 3.2.7).

The complete `case` block is shown below. Sections 3.2.6 and 3.2.7 describe the re-entry paths in detail.

```java
case REGION_STATE_TRANSITION_OBSERVE_FENCING:
  FencingCompletionEvent fencingEvent = regionNode.getFencingEvent();
  if (fencingEvent != null && fencingEvent.isCompleted()) {
    // Re-entry after wake: fencing is confirmed (Section 3.2.6)
    LOG.info("Fencing confirmed for region {}; proceeding with "
        + "assignment to {}", regionNode, targetServer);
    regionNode.clearFailedCloseRetries();
    master.getMetrics().incrementFailedCloseAutoRecoverySuccesses();
    setNextState(REGION_STATE_TRANSITION_OPEN);
    break;
  } else if (fencingEvent != null && isTimedOut()) {
    // Re-entry after timeout: fencing not confirmed (Section 3.2.7)
    LOG.error("Fencing timeout expired for region {} on {}; "
        + "leaving in FAILED_CLOSE for manual investigation",
        regionNode, previousServer);
    master.getMetrics().incrementFailedCloseAutoRecoveryTimedOut();
    setRegionState(RegionState.State.FAILED_CLOSE);
    return;
  } else {
    // First entry: increment epoch, register waiter, suspend
    regionNode.incrementAssignmentEpoch();
    persistEpochAndTargetToMeta(regionNode, targetServer);

    FencingCompletionEvent newEvent =
        regionNode.getOrCreateFencingEvent();
    newEvent.registerWaiter(this);

    setTimeout(Math.toIntExact(conf.getLong(
        "hbase.assignment.pre.fencing.timeout.ms", 600_000L)));
    setState(ProcedureProtos.ProcedureState.WAITING_TIMEOUT);
    skipPersistence();

    LOG.info("Region {} entered OBSERVE_FENCING; waiting for RS-A {} "
        + "to be fenced before assigning to RS-B {}",
        regionNode, previousServer, targetServer);
    master.getMetrics().incrementFailedCloseAutoRecoveryAttempts();
    break;
  }
```

On first entry, the TRSP performs three actions. First, it increments the epoch to `E_new` and persists it to `hbase:meta` in the same `Put` that records the new target RS (RS-B). This epoch increment is critical: it ensures that the master's `checkOnlineRegionsReport()` reconciliation loop will detect the original RS (RS-A) as reporting a stale epoch on the next heartbeat, triggering the epoch-aware close RPC escalation path. However, the TRSP does **not** dispatch the `OpenRegionRequest` at this point. The epoch is incremented for detection purposes only; the actual assignment is deferred until fencing is confirmed.

Second, the TRSP registers itself on a `FencingCompletionEvent` attached to the `RegionStateNode`. This event is the synchronization mechanism between the TRSP and the master's fencing infrastructure. The TRSP will remain suspended until this event is fired by one of the release triggers described below.

Third, the TRSP suspends its own execution by setting its state to `WAITING_TIMEOUT` with a timeout equal to the fencing timeout (`hbase.assignment.pre.fencing.timeout.ms`, default: 600,000 ms). This releases the PEWorker thread back to the pool while the TRSP waits for fencing confirmation.

#### 3.2.4 The Fencing and Reconciliation Loop

With the epoch incremented in `hbase:meta`, the master's existing 3-second `checkOnlineRegionsReport()` reconciliation loop (Epoch Fencing Design Section 5.3.3) natively detects that RS-A is now reporting a stale epoch for the region. This triggers the existing `closeStaleRegion()` RPC loop with exponential backoff (Epoch Fencing Design Section 5.3.4). No new detection logic is required; the existing epoch fencing infrastructure handles this automatically.

#### 3.2.5 Release Triggers (Waking the TRSP)

The suspended TRSP is awoken by one of two mutually exclusive triggers:

**Path A: Cooperative Close (Phantom Failure).** If the region disappears from RS-A's heartbeat, it means the close actually succeeded (the original `FAILED_CLOSE` was a phantom failure caused by a lost RPC acknowledgment) or the epoch-aware close RPC succeeded in removing the region. The master's `checkOnlineRegionsReport()` clears the stale tracker for this region. This action fires `FencingCompletionEvent.wake()`, which re-enqueues the TRSP on a PEWorker thread.

```java
// In checkOnlineRegionsReport(), when a previously stale region  
// disappears from the RS's heartbeat:  
clearStaleCloseTracker(serverName, encodedRegionName);  
RegionStateNode rsn = regionStates.getRegionStateNode(regionInfo);  
if (rsn != null) {  
  FencingCompletionEvent event = rsn.getFencingEvent();  
  if (event != null) {  
    LOG.info("Fencing confirmed for region {} on {}: "  
        + "region disappeared from heartbeat",  
        regionInfo, serverName);  
    event.wake();  
  }  
}
```

**Path B: Hard Fence (SCP Escalation).** If RS-A ignores the close RPCs, the master escalates to `ServerManager.expireServer(RS-A)` after exhausting the configured SCP escalation threshold (default: 20 attempts with exponential backoff, roughly 8 minutes of sustained non-cooperation). The TRSP remains suspended, becoming a logical child of the resulting `ServerCrashProcedure`. SCP guarantees HDFS lease recovery by acquiring the lease on RS-A's WAL, effectively hard-fencing RS-A. RS-A can no longer persist writes, even if it is still alive. Upon SCP completion, the `FencingCompletionEvent` is fired, waking the TRSP.

```java
// In ServerCrashProcedure, after WAL lease recovery and splitting:  
for (RegionInfo regionInfo : regionsOnCrashedServer) {  
  RegionStateNode rsn = regionStates.getRegionStateNode(regionInfo);  
  if (rsn != null) {  
    FencingCompletionEvent event = rsn.getFencingEvent();  
    if (event != null) {  
      LOG.info("SCP completed for region {} on {}: "  
          + "hard fencing confirmed",  
          regionInfo, rsn.getRegionLocation());  
      event.wake();  
    }  
  }  
}
```

**Path C: RS Already Dead (ZK Session Expiry).** If RS-A's ZooKeeper ephemeral node expires, SCP is triggered independently of epoch fencing. This path converges with Path B: SCP processes RS-A as a crashed server, acquires the WAL lease, splits the WAL, and fires the `FencingCompletionEvent`. The epoch ensures that any late-arriving RPCs or heartbeats from RS-A are rejected.

#### 3.2.6 Resumption and Dispatch

Once awoken by the `FencingCompletionEvent`, the procedure executor re-enters the `REGION_STATE_TRANSITION_OBSERVE_FENCING` case label. The dispatch logic (shown in the unified code block in Section 3.2.3) detects that `fencingEvent.isCompleted()` returns `true` and takes the re-entry-after-wake branch. At this point the TRSP is mathematically guaranteed that RS-A is either completely closed or completely dead. It clears the `failedCloseRetries` counter (since the region has been successfully recovered) and transitions to `REGION_STATE_TRANSITION_OPEN` to safely dispatch the `OpenRegionRequest` to RS-B. The epoch was already incremented during the first entry into the `OBSERVE_FENCING` state, so the new RS receives the correct epoch with its open request.

#### 3.2.7 Timeout Expiry (Safety Net)

If the `FencingCompletionEvent` is not fired within the configured fencing timeout (default: 10 minutes), the TRSP wakes due to timeout expiry and the procedure executor re-enters the `REGION_STATE_TRANSITION_OBSERVE_FENCING` case label. The dispatch logic (shown in the unified code block in Section 3.2.3) detects that `fencingEvent.isCompleted()` returns `false` and `isTimedOut()` returns `true`, taking the re-entry-after-timeout branch. The TRSP logs an ERROR-level message, increments the `failedCloseAutoRecoveryTimedOut` metric, and transitions the region to the terminal `FAILED_CLOSE` state for manual investigation. This preserves the existing operator-intervention model as a fallback and prevents the procedure executor from blocking indefinitely.

### 3.3 FencingCompletionEvent

The `FencingCompletionEvent` is a lightweight synchronization primitive attached to `RegionStateNode`. It bridges the gap between the TRSP (which needs to wait for fencing) and the master's reconciliation and SCP infrastructure (which performs the fencing).

```java
// New class: FencingCompletionEvent  
public class FencingCompletionEvent {  
  private volatile boolean completed = false;  
  private Procedure<?> waiter;

  public synchronized void registerWaiter(Procedure<?> proc) {  
    this.waiter = proc;  
    if (completed) {  
      waiter.getEnvironment().getProcedureExecutor().wake(waiter);  
    }  
  }

  public synchronized void wake() {  
    this.completed = true;  
    if (waiter != null) {  
      // Re-enqueue the waiting procedure on the ProcedureExecutor  
      waiter.getEnvironment().getProcedureExecutor().wake(waiter);  
    }  
  }

  public boolean isCompleted() { return completed; }  
}
```

### 3.4 New TRSP State

Add `REGION_STATE_TRANSITION_OBSERVE_FENCING` to the `RegionStateTransitionState` enum in `MasterProcedureProtos.proto`:

```java
enum RegionStateTransitionState {  
  // ... existing states ...  
  REGION_STATE_TRANSITION_OBSERVE_FENCING = N;  
}
```

### 3.5 Metrics

Five new counters are added to the master metrics. The `failedCloseAutoRecoveryAttempts` counter is incremented each time the TRSP enters the `OBSERVE_FENCING` state for a `FAILED_CLOSE` region. The `failedCloseAutoRecoverySuccesses` counter is incremented when the TRSP successfully confirms fencing and dispatches the open request to a new RS. The `failedCloseAutoRecoveryTimedOut` counter is incremented when the fencing timeout expires before fencing can be confirmed. The `failedCloseAutoRecoveryExhausted` counter is incremented when a region exhausts all configured retries (`hbase.assignment.auto.recover.failed.close.max.retries`, default 20) and is left in the terminal `FAILED_CLOSE` state for manual investigation; operators should alert on `failedCloseAutoRecoveryExhausted > 0`. The `preAssignmentFencingBlocks` counter (shared with the epoch fencing infrastructure) is incremented each time the pre-assignment blocking invariant causes a TRSP to wait.

### 3.6 Epoch Interaction

The epoch increment in the `OBSERVE_FENCING` state serves a dual purpose. First, it enables the master's existing `checkOnlineRegionsReport()` loop to detect RS-A as stale, triggering the epoch-aware close RPC escalation path automatically. Second, it ensures that any delayed RPCs, heartbeats, or transition reports from RS-A carry the old epoch and are rejected by the master.

Unlike `FAILED_OPEN` recovery, where the epoch is incremented and the open is dispatched in a single pass through the state machine, `FAILED_CLOSE` recovery decouples the epoch increment (which happens at `OBSERVE_FENCING` entry) from the open dispatch (which happens only after fencing is confirmed). This decoupling is what makes `FAILED_CLOSE` recovery safe: the epoch is used as a detection and fencing tool, not as a post-hoc cleanup mechanism.

### 3.7 HBCK2 Enhancements (hbase-operator-tools)

When auto-recovery exhausts the configured retry budget (`failedCloseAutoRecoveryExhausted` metric fires) or the fencing timeout expires without confirmation (`failedCloseAutoRecoveryTimedOut` metric fires), the region is left in the terminal `FAILED_CLOSE` state for manual investigation. The following HBCK2 enhancements provide operators with the tools to diagnose the fencing state, fix the underlying cause, and re-enable auto-recovery. These enhancements build on the core epoch inspection and repair commands defined in the Epoch Fencing Design (Epoch Fencing Design Section 5.5).

#### 3.7.1 Recovery Counter Reset

**`resetRecoveryCounters <ENCODED_REGIONNAME>...`**

Reset the in-memory `failedCloseRetries` counter (Section 3.2.1) for specified regions to zero. This is the same command described in the FAILED_OPEN RFC (FAILED_OPEN RFC Section 3.7.1); the underlying `resetFailedRecoveryCounters` Hbck API method resets both `failedOpenRetries` and `failedCloseRetries` on each specified `RegionStateNode` in a single operation.

For `FAILED_CLOSE` recovery, the typical operator workflow after counter reset is:

1. Investigate and resolve the underlying cause of the close failure (e.g., kill a hung RS process, resolve a network partition, reduce the memstore size for a region with an oversized memstore that causes close timeouts).

2. Call `resetRecoveryCounters` to clear the exhausted retry budget.

3. Call `assigns -o` (with the override flag) to bypass any stuck TRSP and submit a fresh `TransitRegionStateProcedure`. The new TRSP will enter the auto-recovery path with a clean retry budget and, because the underlying issue has been resolved, is expected to succeed.

The override flag is typically necessary for `FAILED_CLOSE` recovery because the terminal `FAILED_CLOSE` TRSP may still be associated with the `RegionStateNode` (`setProcedure` asserts only one active TRSP per region). The `assigns -o` override bypasses this assertion.

#### 3.7.2 Fencing State Diagnostics

**`reportFencingState [<ENCODED_REGIONNAME>...]`**

Display the current fencing state for regions whose TRSPs are in the `OBSERVE_FENCING` state. This is the same command described in the FAILED_OPEN RFC (FAILED_OPEN RFC Section 3.7.2); both recovery paths use the shared `OBSERVE_FENCING` infrastructure and the same `getFencingState` Hbck API method.

For `FAILED_CLOSE` recovery specifically, the fencing state report is critical in two scenarios.

First, when `failedCloseAutoRecoveryTimedOut` fires, the report allows the operator to determine: whether the old RS is still alive and reporting the region in heartbeats (suggesting a cooperative close may be possible with manual intervention, e.g., reducing GC pressure on the RS), whether epoch-aware close RPCs have been sent and acknowledged but the region persists (suggesting the RS is ignoring closes and SCP should be triggered manually via `scheduleRecoveries`), or whether the RS is unreachable (suggesting the RS should be killed or restarted, after which SCP will complete the fencing automatically).

Second, when `failedCloseAutoRecoveryExhausted` fires for a region-specific close failure (e.g., a very large memstore that causes every close to time out on every RS), the fencing state history across multiple retries helps the operator identify the pattern. If the report shows that close RPCs succeed (the region disappears from heartbeats) but the region enters `FAILED_CLOSE` again on each new RS, the problem is region-specific rather than RS-specific, and the operator should investigate the region's memstore size, store file count, or compaction state.

## 4. Operational Considerations

### 4.1 When to Disable

Operators should keep `FAILED_CLOSE` auto-recovery disabled (the default) until the epoch fencing infrastructure has been deployed and validated. Specifically, operators should verify that all RSes are reporting epochs in their heartbeats (visible via the per-RS `reportedEpochCount` metric), that the `staleEpochDetected` metric is firing correctly for known stale regions, and that the SCP escalation path is functioning correctly in their environment.

Even after enabling, operators should disable auto-recovery when the cluster has known WAL corruption issues that should be investigated before regions are moved, or when a coordinated maintenance window requires regions to remain in `FAILED_CLOSE` until the underlying issue is resolved.

### 4.2 Tuning the Heartbeat Wait

The default 5-cycle heartbeat wait (15 seconds at the default 3-second heartbeat interval) provides sufficient time for a cooperative RS to process a close RPC and report the region's disappearance in its next heartbeat. For clusters with high RPC latency or heavily loaded RSes where close processing takes longer, the wait can be increased to 10 cycles (30 seconds). For clusters with very fast RPC processing, 3 cycles (9 seconds) may be sufficient.

### 4.3 Monitoring

Operators should alert on `failedCloseAutoRecoveryTimedOut > 0`, which indicates that fencing could not be confirmed within the configured timeout and the region requires manual investigation. Operators should also alert on `failedCloseAutoRecoveryExhausted > 0`, which indicates that a region has exhausted all configured retries across multiple RSes and is stuck in a terminal `FAILED_CLOSE` state. This signals a persistent region-specific problem (e.g., a very large memstore that causes every close to time out) rather than a transient RS issue. The `failedCloseAutoRecoveryAttempts` and `failedCloseAutoRecoverySuccesses` metrics provide visibility into how often auto-recovery fires and how effective it is.

The `staleEpochCloseFailures` metric from the epoch fencing infrastructure provides early warning of RSes that are not responding to close RPCs. A rising value for this metric may indicate RSes that will eventually require SCP escalation.

## 5. Testing Plan

### 5.1 Unit Tests

Unit tests for `TransitRegionStateProcedure` verify that a failed close triggers the `OBSERVE_FENCING` state when auto-recovery is enabled, that the epoch is incremented but no `OpenRegionRequest` is dispatched during the fencing wait, that the TRSP resumes and dispatches the open only after `FencingCompletionEvent.wake()` is called, and that timeout expiry leaves the region in the terminal `FAILED_CLOSE` state and increments the `failedCloseAutoRecoveryTimedOut` metric. They also verify that a region whose `failedCloseRetries` counter has reached the configured maximum is left in the terminal `FAILED_CLOSE` state with the `failedCloseAutoRecoveryExhausted` metric incremented, and that `clearFailedCloseRetries()` resets the counter on successful fencing confirmation.

Unit tests for `FencingCompletionEvent` verify the basic lifecycle (`registerWaiter`, `wake`, `isCompleted`), that multiple calls to `wake()` are idempotent and re-enqueue the waiter exactly once, and that calling `wake()` before `registerWaiter()` still completes correctly when the waiter is subsequently registered.

Unit tests for `RegionStateNode` verify that `failedCloseRetries` initializes to zero, increments correctly, and resets to zero on `clearFailedCloseRetries()`.

### 5.2 Integration Tests

`testFailedCloseAutoRecoveryCooperative` starts a minicluster with three RSes, injects a fault that drops the close RPC acknowledgment while allowing the close to succeed on RS-A, and triggers a region move. The test verifies that the TRSP enters `OBSERVE_FENCING`, that the epoch is incremented in `hbase:meta`, that the region disappears from RS-A's heartbeat within one cycle, and that the TRSP resumes and assigns the region to RS-B.

`testFailedCloseAutoRecoverySCPEscalation` forces a region close to fail and has RS-A continue reporting the region at a stale epoch. The test verifies that the master sends epoch-aware close RPCs with exponential backoff, that SCP is triggered after the configured threshold, that the TRSP resumes after SCP completes, and that RS-A's WAL lease has been revoked.

`testFailedCloseAutoRecoveryTimeout` configures a short fencing timeout and disables SCP escalation. After a forced close failure with RS-A remaining uncooperative, the test verifies that the TRSP times out, leaves the region in `FAILED_CLOSE`, and increments the `failedCloseAutoRecoveryTimedOut` metric.

`testFailedCloseAutoRecoveryExhaustion` configures `max.retries=2` and injects a fault that causes every region close to fail on every RS (e.g., a region with a very large memstore that causes every close to time out). The test verifies that the TRSP enters `OBSERVE_FENCING` on the first failure, successfully fences and reassigns, enters `FAILED_CLOSE` again on the new RS, enters `OBSERVE_FENCING` a second time, fences and reassigns again, and on the third failure leaves the region in the terminal `FAILED_CLOSE` state with the `failedCloseAutoRecoveryExhausted` metric incremented.

`testFailedCloseMasterFailover` triggers `OBSERVE_FENCING`, kills the active master mid-wait, and verifies that the new master detects the `FAILED_CLOSE` region and restarts the recovery process using the epoch already persisted to `hbase:meta`.

### 5.3 HBCK2 / hbase-operator-tools Tests

These tests live in the hbase-operator-tools repository and exercise the HBCK2 commands introduced in Section 3.7 against a MiniHBaseCluster with `FAILED_CLOSE` auto-recovery enabled.

`testResetFailedCloseRetries` configures `max.retries=1` and injects a fault that causes every region close to fail on every RS (e.g., a region with a very large memstore that causes every close to time out). The test verifies the region exhausts its single retry and enters terminal `FAILED_CLOSE` with the `failedCloseAutoRecoveryExhausted` metric incremented. It then removes the fault, calls `resetRecoveryCounters` via HBCK2 for the affected region, calls `assigns -o` (override) to re-trigger recovery, and verifies the region is successfully fenced and reassigned to a new RS.

`testResetFailedCloseRetriesNoEffect` calls `resetRecoveryCounters` for a region that is currently in `OPEN` state (not in `FAILED_CLOSE`), verifies the command succeeds without error, and verifies the region's serving state is unaffected.

`testReportFencingStateFailedClose` injects a close failure so the TRSP enters `OBSERVE_FENCING`, calls `reportFencingState`, and verifies the report contains the correct old RS, target RS, stale and current epoch values, wait duration, and the number of epoch-aware close RPCs sent to the old RS. After the old RS cooperatively closes the region and the TRSP resumes, the test calls `reportFencingState` again and verifies the region is no longer listed.

`testReportFencingStateSCPPending` injects a close failure and makes the old RS unresponsive to epoch-aware close RPCs. The test calls `reportFencingState` repeatedly and verifies that the `release_trigger_status` field transitions from "waiting for cooperative close" to "SCP escalation pending" as close attempts accumulate toward the SCP threshold. After manually calling `scheduleRecoveries` via HBCK2 to trigger SCP for the uncooperative RS, the test verifies SCP completion fires the `FencingCompletionEvent` and the TRSP resumes.

`testScheduleRecoveriesWithFencingInteraction` uses the existing HBCK2 `scheduleRecoveries` command to trigger SCP for an RS that has a region in `OBSERVE_FENCING`. The test verifies that SCP completion fires the `FencingCompletionEvent`, waking the suspended TRSP, and that the region is subsequently assigned to a new RS with the correct epoch.

`testAssignsOverrideAfterFailedCloseTimeout` configures a short fencing timeout, disables SCP escalation, and injects a close failure so the TRSP times out in `OBSERVE_FENCING` and leaves the region in terminal `FAILED_CLOSE`. The test then uses `assigns -o` to override the stuck TRSP, verifies a new TRSP is submitted with an incremented epoch, and verifies the region is successfully fenced and reassigned.

## 6. Availability Impact Analysis

### 6.1 Unavailability Window

Without this feature, a region that reaches `FAILED_CLOSE` remains in an ambiguous state indefinitely until an operator manually intervenes via HBCK2. The region may or may not be serving data on the original RS, and the master cannot safely reassign it without risking data loss.

With auto-recovery enabled, the unavailability window depends on which resolution path is taken:

```java
T_unavail_cooperative = T_heartbeat_wait + T_open  
T_unavail_scp = T_scp_threshold + T_scp_processing + T_open  
T_unavail_zk = T_zk_session_timeout + T_scp_processing + T_open
```

In the cooperative case (Path A), where the close actually succeeded but the acknowledgment was lost, the total unavailability is roughly 15 to 20 seconds (5 heartbeat cycles plus one region open). In the SCP escalation case (Path B), the total is roughly 8 to 10 minutes (SCP threshold plus SCP processing). In the ZK session expiry case (Path C), the total is 90 seconds plus SCP processing time. All three are bounded and deterministic, compared with the unbounded window under the status quo.

### 6.2 Failure Scenarios

In a phantom failure, where the close actually succeeded but the RPC acknowledgment was lost (the most common trigger for `FAILED_CLOSE`), the region is stuck in an ambiguous state indefinitely without auto-recovery. With auto-recovery, the master detects the region's absence from the RS's heartbeat within 15 seconds and safely reassigns it.

When an RS is experiencing a sustained GC pause or network partition and genuinely fails to close the region, the master's epoch-aware close RPCs repeatedly fail. Without auto-recovery, the operator must notice, diagnose, and manually decide whether to force-close or trigger SCP. With auto-recovery, the master automatically escalates to SCP after the configured threshold, physically fencing the RS and recovering the region. The total resolution time is bounded by the SCP threshold.

For a crashed RS where the ZK session expires, SCP is triggered independently of epoch fencing. Auto-recovery ensures that the `FAILED_CLOSE` region is included in SCP's recovery scope, and the `FencingCompletionEvent` wakes the waiting TRSP to complete the reassignment. Without auto-recovery, the operator must manually reconcile the `FAILED_CLOSE` state even after SCP has processed the crashed server.

### 6.3 Worst-Case Timeline

The worst case for `FAILED_CLOSE` auto-recovery depends on whether the failure is RS-specific (the common case) or region-specific (the rare case that the retry budget is designed to catch).

**RS-specific failure (single retry).** For a single `FAILED_CLOSE` event where fencing cannot be confirmed within the configured timeout (default: 10 minutes), the TRSP waits for the full timeout duration and then leaves the region in `FAILED_CLOSE` for manual intervention. With SCP escalation enabled (the default), the worst case for a single cycle is bounded by the SCP threshold (default: 8 minutes) plus SCP processing time (typically 30 to 120 seconds for WAL splitting and region reassignment).

**Region-specific failure (retry budget exhaustion).** For a persistent region-specific problem that causes every close to fail on every RS (e.g., a very large memstore that triggers close timeouts regardless of RS), the TRSP cycles through the full retry budget:

```
T_worst_region_specific = max_retries * T_fencing_timeout
```

With defaults (`max_retries=20`, `T_fencing_timeout=600s`), the theoretical worst-case time before the region is declared terminally `FAILED_CLOSE` is approximately 200 minutes (3.3 hours). However, SCP escalation (triggered after roughly 8 minutes per cycle) will typically resolve each fencing wait well before the timeout expires, yielding a practical worst case of roughly `20 * 10 minutes = 200 minutes` with SCP. In practice, a region-specific close failure is very likely to resolve within far fewer retries because `FAILED_CLOSE` is overwhelmingly caused by RS-specific issues, which is why the default retry budget of 20 is appropriate.

Without the retry budget, the same region-specific failure would cycle indefinitely, consuming fencing timeouts without bound and never reaching a terminal state for operator investigation.

### 6.4 Impact on Cluster-Wide Availability

`FAILED_CLOSE` auto-recovery has a more targeted impact than `FAILED_OPEN` auto-recovery because it involves at most one RS per region (the original RS that failed to close). The SCP escalation path is the primary concern for cluster-wide availability: if multiple regions on the same RS enter `FAILED_CLOSE` simultaneously, the SCP escalation affects all regions on that RS. However, this is identical to the existing SCP behavior and does not represent a new blast radius.

The `OBSERVE_FENCING` state parks the TRSP using `WAITING_TIMEOUT`, releasing the PEWorker thread. Multiple `FAILED_CLOSE` regions can be simultaneously waiting for fencing confirmation without consuming procedure executor resources.

### 6.5 Comparison with Status Quo

The feature strictly improves availability for phantom failures (Path A) and is neutral for persistent failures that exhaust the retry budget. In the cooperative case, regions recover automatically in under 20 seconds rather than waiting for human intervention. In the SCP escalation case, the region recovers in roughly 8 minutes with full HDFS-level safety guarantees. In the retry budget exhaustion case, the region arrives at the same terminal `FAILED_CLOSE` state that exists today, with the added benefit of structured metrics (`failedCloseAutoRecoveryExhausted`, `failedCloseAutoRecoveryTimedOut`) and detailed logs that accelerate the operator's manual investigation. The retry budget (default 20) ensures that even in the rare case of a persistent region-specific close failure, the system converges to a terminal state rather than cycling indefinitely.

The feature does not introduce any new failure modes. The only actions it takes (incrementing the epoch, observing heartbeats, sending epoch-aware close RPCs, and escalating to SCP) are identical to the actions an operator would take manually and that the epoch fencing infrastructure already provides. The Wait-For-Fence pattern ensures that no open request is dispatched until the original RS is fenced, eliminating the split-brain data corruption risk.

## 7. Performance Analysis

### 7.1 Procedure Executor Overhead

The `FAILED_CLOSE` auto-recovery path adds one additional state (`OBSERVE_FENCING`) to the TRSP state machine per failing region. During the fencing wait, the TRSP is parked in `WAITING_TIMEOUT` and releases the PEWorker thread. The procedure framework's timeout scheduler tracks the suspended procedure at O(log P) insertion cost. Because the PEWorker thread is not blocked, the fencing wait imposes zero reduction in the master's capacity to execute other procedures.

The `skipPersistence()` call eliminates one procedure WAL write for the `OBSERVE_FENCING` state transition. If the master crashes during the wait, the new master re-scans `hbase:meta`, discovers the region is still in `FAILED_CLOSE`, and restarts recovery from scratch.

### 7.2 hbase:meta Write Amplification

The `OBSERVE_FENCING` state writes one `Put` to `hbase:meta` to record the incremented epoch and the target RS. This is the same single-row mutation that any normal region assignment performs. No additional `hbase:meta` writes occur during the fencing wait or upon resumption, because the epoch was already incremented at entry.

### 7.3 Network Overhead

The fencing wait itself generates no additional network traffic beyond what the existing epoch fencing infrastructure already produces (heartbeat validation and epoch-aware close RPCs). The `FencingCompletionEvent` is an in-memory notification with zero network cost.

### 7.4 Memory Footprint

The per-region memory cost of the retry budget is a single `int` field (`failedCloseRetries`) added to `RegionStateNode`, contributing 4 bytes per region. On a cluster managing 100,000 regions, this adds approximately 400 KB of heap across all `RegionStateNode` instances, a negligible increase relative to the existing per-region memory footprint.

The `FencingCompletionEvent` adds approximately 24 bytes per region (a boolean, a reference, and object header) to `RegionStateNode`. This object is created on-demand only for regions entering `OBSERVE_FENCING` and is garbage collected after the TRSP resumes. During normal operation with no `FAILED_CLOSE` regions, no `FencingCompletionEvent` objects exist.
