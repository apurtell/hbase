# RFC: Epoch-Based Assignment Fencing

**Updated:** February 2026
**Version:** 1.1
**Status:** Draft

## 1. Problem Statement

HBase's Assignment Manager (AMv2) deliberately stops short of automated recovery when regions reach indeterminate states such as `FAILED_OPEN` or `FAILED_CLOSED`. This is a safety boundary: the risk of double-assigning a region, where two RegionServers simultaneously serve writes for the same region, can cause silent data loss, since HBase uses a single-replica-per-region architecture with no quorum-based consensus to arbitrate writes.

Today, operators must manually intervene via HBCK to resolve these situations. This proposal introduces **epoch-based assignment fencing**, a mechanism that enables the master to authoritatively determine whether a RegionServer's claim to a region is current or stale, and to take corrective action automatically without risking double assignment.

Epoch-based assignment fencing enables follow on work:

* [RFC: Auto-Recovery for FAILED_OPEN State](https://docs.google.com/document/d/1qp8GCvQMvlNeXO_BLgirJWUa2Gfm4gIl9MCtzGwZ1Vw/edit?tab=t.0)  
* [RFC: Auto-Recovery for FAILED_CLOSE State](https://docs.google.com/document/d/1oP-DUpnMvog0qlASL4JgTtZ-_wCtWp-DGNVuy3BACkQ/edit?tab=t.0)

### 1.1 Contrast with Consensus-Based Systems

Systems like CockroachDB (Raft leader leases), TiDB (PD + Raft), and Spanner (Paxos) inherently solve the double-assignment problem because each data range has multiple replicas with quorum-based leadership. A stale leader's writes are rejected by the quorum. HBase's single-replica architecture means there is no quorum to reject stale writes/assignments. The HMaster must provide fencing to prevent stale assignments.

### 1.2 Kleppmann's Fencing Token Pattern

The theoretical foundation for this design comes from Martin Kleppmann's analysis of distributed locking in *Designing Data-Intensive Applications* (2017, Chapter 8). The core argument is that a distributed lock alone is insufficient to prevent concurrent access to a resource. The lock must be accompanied by a fencing token that the resource itself validates.

Kleppmann's pattern works as follows:

1. A **lock service** grants a lock to a client, along with a monotonically increasing **fencing token** (an integer that increases every time the lock is granted).

2. The client includes this token in every request to the **resource** (e.g., a storage service or database).

3. The resource tracks the highest token it has seen. If a request arrives with a token lower than the highest seen, the resource rejects it. The client's lock has been superseded.

This is safe even under the most adversarial network conditions because the token is a logical ordering, not a wall-clock timeout. A client that believes it still holds the lock but has actually been superseded will be fenced by the resource on its next operation.

Kleppmann's pattern maps directly onto HBase region assignment. In his model, a lock service grants exclusive access to a resource with a fencing token attached. In HBase, the `TransitRegionStateProcedure` (TRSP) plays the role of the lock service: each time it assigns a region to a RegionServer, it issues a new assignment epoch, a monotonically increasing `long` value persisted in `hbase:meta`, that serves as the fencing token for that assignment. The epoch is a per-region counter, not a global one: each region independently tracks its own epoch, which is incremented only when that specific region is reassigned.

We considered using a single global long for epoch generation, which would provide globally unique epoch values (useful for log forensics). However, a global counter requires durable persistence of the counter itself, separate from any individual region. The natural home is a dedicated row in `hbase:meta`, updated via `Increment`. To avoid hot-spotting that single row on every assignment, a batch reservation pattern would be needed: the master Increments by a large delta (e.g., 1000), hands out values from the reserved range in memory, and only hits meta again when the batch is exhausted. This adds complexity: batch reservation logic, failover initialization, batch size tuning, and edge-case handling at batch boundaries during master failover.

Either approach requires writing the assigned epoch to the region's own meta row during assignment, so the master can compare it against heartbeat reports. With a per-region counter, this is the *only* write: the epoch is an additional column (`info:assignmentEpoch`) in the same `Put` mutation that TRSP already performs to write `info:server` and `info:openSeqNum`. Zero additional RPCs, zero contention, no new infrastructure. This approach also makes rolling upgrades trivially safe: old RSes omit the `assignmentEpoch` protobuf field from their heartbeats and transition reports. The master detects this via the protobuf-generated `hasAssignmentEpoch()` method, treats the field as unsupported, and bypasses the epoch comparison entirely, falling back to the existing server-name-only comparison. Once all nodes are upgraded, epoch fencing activates automatically with no operator action. The per-region approach wins on simplicity.

The storage overhead is 8 bytes per region in `hbase:meta`, negligible even for clusters with hundreds of thousands of regions. A Java signed `long` has a maximum value of 2^63 - 1 (approximately 9.2 x 10^18). Since the epoch is incremented only on region assignment, not on every read or write, rollover is not a practical concern. Even at one reassignment per second for a single region, it would take approximately 292 billion years to exhaust the counter. Real-world reassignment rates are orders of magnitude lower, typically a few per hour per region at most, driven by load balancing, splits, and failure recovery. We therefore treat the epoch as effectively inexhaustible and do not implement wraparound handling.

The client in Kleppmann's model is the process that holds the lock and includes the fencing token in every request to the protected resource. In HBase, the RegionServer fills this role. It receives the assignment epoch when the master tells it to open a region, stores it locally, and then presents it back to the master in two ways: in the `RegionLoad` proto included with every periodic heartbeat (every 3 seconds), and in the `RegionStateTransition` proto sent when reporting state changes such as OPENED or CLOSED.

Finally, Kleppmann's resource, the downstream system that validates the fencing token and rejects stale operations, maps to the master itself, specifically the `checkOnlineRegionsReport()` method (which validates epochs from heartbeats) and the `reportRegionStateTransition()` method (which validates epochs from state-change reports). When either method detects an epoch older than the current epoch for a region, the master knows the reporting RS holds a zombie region from a superseded assignment and initiates a procedure-tracked force-close.

The master serves as both the lock service and the enforcement layer. It does so through two channels:

**Heartbeat validation (proactive, every 3 seconds):** The existing RS heartbeat already reports all online regions. By adding the epoch to this report, the master can detect stale RSes even when they make no state transitions. The RS "presents its token" for each region to the master on every heartbeat. If the epoch is stale, the master sends a targeted close RPC to the stale RS for that region. The heartbeat loop itself provides natural retry: if the close fails, the next heartbeat (3 seconds later) will detect the stale epoch again and retry. If the RS is dead, SCP handles it.

**Transition validation (reactive, on state change):** When an RS reports a state transition (OPENED, CLOSED), the master validates the epoch before accepting it. If the epoch is stale, the master returns a `TRANSITION_REJECTED_STALE_EPOCH` error. The RS receives this error and self-fences by closing the region locally.

This two-channel approach compensates for the fact that HRegion (the ultimate resource) does not enforce fencing tokens directly. The master acts as a proxy enforcer, detecting stale RSes and directing them to release regions. However, detection alone is insufficient (see Section 1.2.1).

#### 1.2.1 Pre-Assignment Blocking

In Kleppmann's original model, the resource (e.g., a storage service) synchronously validates the fencing token on every write. A stale client cannot write a single byte without being rejected. This synchronous validation is what makes the pattern safe under arbitrary network delays and process pauses.

In HBase, HRegion, the ultimate resource, is unaware of assignment epochs. It does not validate fencing tokens on writes. The master acts as a proxy enforcer, but enforcement is asynchronous: it relies on the 3-second heartbeat cycle to detect stale epochs. If the master were to proactively increment the epoch and dispatch an `OpenRegionRequest` to RS-B while RS-A is still alive and serving the region, RS-B would open the region and begin accepting new writes. Meanwhile, RS-A remains completely unaware it has been superseded and will continue accepting and flushing writes for up to 3 seconds (or longer, depending on heartbeat timing and RPC latency) until the stale epoch is detected and a close RPC is processed. This could result in silent data loss: RS-B advances `openSeqNum` when it opens the region, and RS-A's late writes produce WAL entries that will never be replayed by any subsequent region open.

This means epoch fencing in HBase must be treated as a pre-assignment blocking condition, not a concurrent cleanup mechanism:

**The Pre-Assignment Blocking Invariant:** The master MUST NOT dispatch an `OpenRegionRequest` to a new RegionServer until it has definitively confirmed that the previous assignment holder has been fenced, either by confirmed region close (the region disappears from the RS's heartbeat report) or by physical fencing (SCP / WAL lease revocation). This ordering constraint, close-then-open, never open-then-close, is what makes epoch fencing safe in an architecture where the storage layer does not synchronously validate fencing tokens.

The two-channel detection mechanism (heartbeat validation and transition rejection) described above serves two complementary roles under this constraint:

**Before new assignment (primary role):** The heartbeat channel provides the signal the master uses to confirm that the old RS has released the region. The TRSP blocks on this confirmation before dispatching the new assignment (Section 5.3.2).

**After new assignment (defense-in-depth):** If a bug or race condition allows a region to be opened on a new RS before the old one is fenced, the heartbeat and transition channels act as a second line of defense, detecting and closing zombie regions that escaped the primary fencing step (Section 5.3.4).

### 1.3 Other Prior Art

#### Leader epochs (Raft, ZAB)

Term/epoch numbers incremented on each leadership change; log entries from stale terms are rejected. The assignment epoch serves an analogous role: each new region assignment is a "leadership change" for that region.

#### STONITH (Shoot The Other Node In The Head)

Isolation of a failed node before reassigning its resources. Epoch fencing is a logical (non-physical) complement; Section 10.2 discusses a pluggable physical fencing interface for cases where logical fencing is insufficient.

## 2. Motivation

Epoch fencing is valuable in its own right. Even before any automated recovery logic is built on top of it. It provides three distinct benefits.

### 2.1 A Safety Foundation for Automated Recovery

Today, the Assignment Manager cannot safely automate recovery for regions in indeterminate states (`FAILED_OPEN`, `FAILED_CLOSE`, stuck RITs) because it has no way to prove that a previous RegionServer has released its claim on a region. The only safe option is to stop and wait for an operator to intervene via manual fencing of the regionserver and subsequent invocation of HBCK2 to restart the region's assignment.

Epoch fencing changes this equation. With a monotonically increasing epoch per region assignment, the master can answer a question it currently cannot: "Is this RS's claim to this region current, or is it a stale remnant of a previous assignment?" This is the prerequisite for every form of automated recovery:

Section 10.1 proposes a recovery model built on this foundation. `FAILED_OPEN` regions can be auto-retried on a different RS with `forceNewPlan=true`; the epoch increment ensures any stale state from the original RS is detected and rejected on the next heartbeat (Section 10.1.1). `FAILED_CLOSE` regions require the most careful reasoning and are subject to the pre-assignment blocking invariant (Section 1.2.1): the master must confirm the old assignment is fenced, either by observing the region disappear from the RS's heartbeat (close succeeded) or by physically fencing the RS via SCP (Section 5.3.5), before dispatching a new assignment. Epoch fencing enables this by providing the detection signals and escalation path, but the safety comes from the serialized ordering (fence-then-open), not from detection speed (Section 10.1.2).

Without epoch fencing, any automated recovery in an indeterminate state risks double assignment. Epoch fencing is the safety floor that makes automated recovery possible.

### 2.2 Reducing the Blast Radius of Existing Bugs

Six recurring root-cause patterns emerge in open bugs for the Assignment Manager: 1) insufficient null-safety, 2) procedure state inconsistency, 3) race conditions, 4) missing auto-recovery logic, 5) unbounded retries, and 6) PEWorker thread exhaustion. Many of these bugs can produce a common catastrophic outcome: a region served by a stale RegionServer while the master believes it is elsewhere (or nowhere).

Epoch fencing does not fix these bugs directly, but it bounds the damage any of them can cause:

**Procedure state inconsistency** is one of the most dangerous classes. Bugs like HBASE-29364, where region state is persisted incorrectly during master failover, can cause the master to recover with a wrong region-to-RS mapping. The stale RS continues serving the region with no corrective signal. With epoch fencing, the stale RS's heartbeat carries the old epoch and is detected within 3 seconds, triggering an epoch-aware close.

**Race conditions** such as HBASE-26283, where an RS does not report OPENED on a duplicate open request, can leave the master with no record of a region open. The master re-assigns to another RS, and both serve simultaneously. With epoch fencing, the new assignment increments the epoch, so the stale RS's heartbeat carries an outdated value and is fenced on the next reconciliation cycle.

**Missing auto-recovery** bugs like HBASE-29190 leave regions stuck in `FAILED_OPEN` indefinitely. An operator intervening with HBCK2 may accidentally cause a double-assign through a typo or stale information. Even a mistaken HBCK2s reassignment increments the epoch, so if the old RS is still serving the region, it is detected and fenced.

**NPE and crash bugs** such as HBASE-29806, an NPE in `afterReplay` during master restart, can cause the master to crash mid-assignment. The in-flight region may continue to be served by the old RS after the master restarts. With epoch fencing, the epoch is loaded from `hbase:meta` on restart, and any stale RS is detected on the first heartbeat.

**Unbounded retries** like HBASE-23682, infinite retry on a failed open, can cause multiple TRSPs to attempt opening the same region on different RSes simultaneously. With epoch fencing, each TRSP increments the epoch. Only the latest epoch is valid; all prior attempts are automatically fenced. Moreover, a stale TRSP that discovers its epoch has been superseded can early-out before dispatching to the RS, freeing the PEWorker thread for other work.

**PEWorker exhaustion**, where stuck procedures block new TRSPs, leaves regions in limbo with no procedure to track them. Manual intervention to recover these regions can inadvertently create duplicates. The epoch provides an authoritative record of which assignment is current, independent of procedure state, so any stale copy is detectable. Additionally, redundant or expired TRSPs that detect their epoch has been superseded can early-out rather than continuing to retry, directly relieving executor pressure and queue jamming.

Epoch fencing provides this blast-radius reduction passively. It does not require any of the existing bugs to be fixed first. The epoch is validated on every RS heartbeat (every 3 seconds) and on every `reportRegionStateTransition` call. This means that even if a bug causes the master to lose track of a region, the heartbeat reconciliation loop will detect and fence the stale assignment within seconds.

### 2.3 Completing HBase's Existing Proto-Fencing Mechanisms

HBase already has several mechanisms that collectively form a "proto-fencing" system. Each covers a specific aspect of the assignment lifecycle, but they leave a gap at the region-assignment level that epoch fencing fills:

**`openSeqNum`** operates at the WAL level. When a region is opened, its `openSeqNum` is set to the most recent WAL entry; any WAL edit with a lower sequence number is skipped during replay. This protects against WAL-level data corruption during recovery, but it does not prevent a stale RS from accepting new writes. It only governs WAL replay. A zombie region on a stale RS can accept and persist new writes that will never be replayed by the current RS, causing silent data loss. Epoch fencing prevents this by detecting the zombie RS and forcing it to close the region.

**ZooKeeper ephemeral nodes** detect RS process death. When an RS's ZK session expires, the master triggers a Server Crash Procedure (SCP), marking all regions on the dead RS as crashed and beginning reassignment. However, ZK ephemeral nodes only detect process death, not stale processes. If an RS is alive but has a zombie region from a previous assignment, due to a race condition, slow network, or bug, ZK does not detect this because the RS's session is healthy. Epoch fencing detects stale regions on healthy RSes.

**The `ServerStateNode` write lock** is acquired during SCP to prevent `reportRegionStateTransition` calls from the dead RS's in-flight RPCs from being processed. This avoids stale updates during crash handling. However, the write lock only protects against RPCs that were already in-flight when the RS died. It does not cover the case where the RS is alive and generating new RPCs for a stale region. Epoch fencing covers this gap by rejecting any RPC, whether in-flight or new, that carries an outdated epoch.

**The RS heartbeat and `checkOnlineRegionsReport()`** is the closest existing mechanism to epoch fencing. It compares the RS's reported online regions against the master's in-memory state, detects regions on the wrong server, and attempts a best-effort `closeRegionSilently()`. However, it has three critical weaknesses (detailed in Section 3.3): it compares by server name only, not by assignment generation; `closeRegionSilently` is fire-and-forget with no tracking or retry; and it skips checks entirely when it cannot acquire the `RegionStateNode` lock. Epoch fencing upgrades this mechanism with an authoritative, monotonic epoch number and an epoch-aware close with dedup tracking and escalation (Section 5.3.4).

Epoch fencing completes these mechanisms and bundles them into a defense-in-depth stack. At the foundation, `openSeqNum` prevents WAL replay of edits that have already been applied, protecting against data corruption during region recovery. Above that, ZooKeeper ephemeral nodes detect RS process death, triggering SCP to reassign regions from dead servers. The `ServerStateNode` write lock blocks stale RPCs from a dead RS's in-flight messages during SCP. The existing `checkOnlineRegionsReport()` provides periodic reconciliation, comparing the RS's reported regions against the master's state on every heartbeat. Epoch fencing sits at the top of this stack, filling the critical gap: no existing mechanism detects a live, healthy RS that is serving a region it should no longer own. Each layer protects against a different failure mode, and together they provide comprehensive coverage from WAL recovery through process death through stale assignment detection.

## 3. Analysis of Existing Mechanisms

This design is grounded in a detailed code analysis of the existing RegionServer heartbeat and region-state reporting mechanisms. The key finding is that **HBase already has ~80% of the infrastructure needed**. What's missing is the epoch number and a robust response to stale regions.

### 3.1 Current Heartbeat Data Flow

The RegionServer already sends a full list of its online regions to the master on every heartbeat. The flow begins in the RS main loop (`HRegionServer.java` L1162), which calls `tryRegionServerReport()` every `msgInterval` (default 3 seconds). This method invokes `buildServerLoad()` (`HRegionServer.java` L1479-1517), which iterates over all regions returned by `getOnlineRegionsLocalContext()` and constructs a `RegionLoad` protobuf message for each one. The assembled `ServerLoad` is then sent to the master via the `regionServerReport` RPC (`HRegionServer.java` L1374).

On the master side, `MasterRpcServices` receives the report (`MasterRpcServices.java` L557-573), deserializes it, and forwards it to `ServerManager.regionServerReport()`. From there, the AssignmentManager's `reportOnlineRegions()` method (`AssignmentManager.java` L1433-1474) stores the report in `rsReports` and calls `checkOnlineRegionsReport()`.

The reconciliation step in `checkOnlineRegionsReport()` (`AssignmentManager.java` L1496-1546) iterates over each region in the report and compares it against the master's in-memory state. If a region is found on the wrong server, it calls `closeRegionSilently()`, a best-effort, fire-and-forget close with the limitations described in Section 3.3.

This means the RS is already performing periodic OPEN -> OPEN heartbeats (!). The master knows every 3 seconds which regions each RS considers itself to be hosting.

### 3.2 The Acknowledged Fencing Gap

The code comment at `AssignmentManager.java` L1427-1429 explicitly acknowledges:

*"This is because that there is no fencing between the `reportRegionStateTransition` > method and `regionServerReport` method, so there could be race and introduce > inconsistency here, but actually there is no problem."*

The authors recognized the fencing gap but judged it acceptable. Epoch fencing would close this gap definitively.

### 3.3 Why the Current Response Is Insufficient

When `checkOnlineRegionsReport()` detects a region on the wrong server, it calls `closeRegionSilently()` (AssignmentManager.java L1482-1490):

```java
private void closeRegionSilently(ServerName sn, byte[] regionName) {  
    try {  
      RegionInfo ri = MetaTableAccessor.parseRegionInfoFromRegionName(regionName);  
      // Pass -1 for timeout. Means do not wait.  
      ServerManager.closeRegionSilentlyAndWait(  
        this.master.getClusterConnection(), sn, ri, -1);  
    } catch (Exception e) {  
      LOG.error("Failed trying to close {} on {}",  
        Bytes.toStringBinary(regionName), sn, e);  
    }  
}
```

This approach has several problems. The master fires the RPC and moves on without learning the outcome. There is no retry logic. The close is attempted exactly once, and all exceptions are swallowed by a catch-all handler that only logs. The close is invisible to the assignment system because no TRSP manages it, so there is no procedure tracking, no state machine, and no record in the procedure store. Consequently, the master never learns whether the close succeeded. The RS is not obligated to honor the request. It can fail to process the close and continue serving the region indefinitely with no further corrective pressure.

The broader `checkOnlineRegionsReport()` method has additional limitations. The method uses `tryLock()` on each region's `RegionStateNode` (L1515); if the lock is held, for example by an in-flight TRSP, the entire check for that region is silently skipped until the next heartbeat. If the AssignmentManager does not believe the region is in `OPENING` or `OPEN` state, the check only produces a warning log with no corrective action (L1528-1535). Most critically, the comparison is by server name only (L1523): the method can determine that a region is on a different server than expected, but it cannot distinguish between "this RS was legitimately assigned this region" and "this RS has a zombie region from a previous assignment that was never cleaned up."

### 3.4 The Separate State-Transition Path

`reportRegionStateTransition` (HRegionServer.java L2698) is a completely separate, one-shot RPC called only on actual state changes (OPENED, CLOSED, etc.). Once a region is stably OPEN, the RS never calls this method again for that region, so the master never gets a chance to reject it via this path.

### 3.5 RS-Side Open Command Path

Region open commands arrive via the `executeProcedures` RPC (RSRpcServices.java L4045), which dispatches to `executeOpenRegionProcedures()` (L3969-4002). This creates an `AssignRegionHandler` that opens the region and calls `postOpenDeployTasks()` (HRegionServer.java L2606), which in turn calls `reportRegionStateTransition` to report OPENED.

Procedure results (success/failure) are reported back via `RemoteProcedureResultReporter` (RemoteProcedureResultReporter.java L39-114), a separate background thread that batches and retries result reports.

## 4. Epoch-Based Assignment Fencing

The design introduces a single new concept, an **assignment epoch**, threaded through all three layers of the HBase architecture. In the storage layer, the epoch is durably persisted as a column in `hbase:meta`, surviving master failovers. In the master layer, the `RegionStateNode` holds the epoch in memory and `TransitRegionStateProcedure` increments it on every new assignment. The master validates the epoch on two channels: the periodic RS heartbeat (every 3 seconds) and each `reportRegionStateTransition` call. In the RS layer, each `HRegion` stores its assigned epoch and includes it in both its heartbeat `RegionLoad` and any state transition reports. When the master detects a heartbeat carrying an epoch older than its current value, it sends an epoch-aware close to the stale RS. When a state transition carries a stale epoch, the master rejects it and the RS self-fences by closing the region locally.

### 4.1 Core Concept

Every region assignment is tagged with a monotonically increasing assignment epoch. `TransitRegionStateProcedure` increments the epoch on each new assignment. The epoch is persisted in `hbase:meta` alongside `openSeqNum` and `server`, ensuring durability across master failovers. In memory, it is stored on the `RegionStateNode`. When the master sends an `OpenRegionRequest` to a RegionServer, the epoch is included in the request, and the RS stores it per `HRegion`. From that point forward, the RS includes the epoch in the `RegionLoad` it sends with every heartbeat, giving the master a chance to validate it every 3 seconds. The epoch is also included in every `reportRegionStateTransition` call, so the master can reject stale transitions at the moment they arrive.

Any RS reporting a region with an epoch older than the master's current epoch for that region is considered stale and is fenced.

Stale-epoch detection is necessary but not sufficient for safety. Because HRegion does not synchronously validate fencing tokens on writes (Section 1.2.1), the master must enforce a strict ordering constraint: the `TransitRegionStateProcedure` MUST NOT dispatch an `OpenRegionRequest` for a new assignment until the previous assignment holder is confirmed fenced. This pre-assignment blocking step (Section 5.3.2) is what prevents the data loss scenario described in Section 4.3 Scenario D.

### 4.2 Epoch Lifecycle

The epoch lifecycle begins when a `TransitRegionStateProcedure` is created for an assignment of region R. The TRSP increments the region's epoch by computing `RegionStateNode.assignmentEpoch + 1`. This new value is persisted to `hbase:meta` in the `info:assignmentEpoch` column as part of the same row mutation that records the server assignment, so no additional RPC is required. The value is also stored in the `RegionStateNode` in memory.

If the TRSP is retried or resumed after a master failover, it checks whether its epoch has been superseded by a subsequent assignment. If so, the TRSP early-outs and releases its PEWorker thread rather than dispatching a stale open to the RS.

Once the TRSP dispatches the assignment, the epoch is sent to the target RS in the `OpenRegionRequest`. The RS extracts the epoch and stores it in `HRegion.assignmentEpoch`. From that point forward, the RS includes the epoch in the `RegionLoad` it sends with every heartbeat, and in the `RegionStateTransition` message when it calls `reportRegionStateTransition` to report that the region is OPENED.

Region splits and merges create new region entities that require epoch initialization. When a region splits, `SplitTableRegionProcedure` creates two new daughter regions, each with a distinct `RegionInfo` and a new row in `hbase:meta`. When regions merge, `MergeTableRegionsProcedure` creates a single new merged region. In both cases, the new regions have no prior assignment history, so their `assignmentEpoch` is initialized to `1` in the same `Put` mutation that creates the `hbase:meta` row. The parent region's epoch is irrelevant because the daughters (or merged region) are entirely new entities: they have different encoded names, different row keys in `hbase:meta`, and independent epoch counters. Subsequent assignments of the daughter or merged regions proceed through the normal TRSP path, incrementing the epoch from `1` onward.

The `hbase:meta` region is a special case: it has no self-referential row in `hbase:meta`, so its epoch cannot be persisted through the normal column-write path. Section 4.4 describes the bootstrapping design that resolves this circular dependency.

### 4.3 Fencing Scenarios

Four scenarios illustrate how epoch fencing detects and resolves stale region assignments. The primary path is pre-assignment blocking: the master confirms the old assignment is fenced before dispatching a new one. The heartbeat channel provides the confirmation signal. The secondary path is transition-based rejection: if a stale RS attempts to report a state change, the master rejects it and the RS self-fences. The third scenario covers master failover, where the epoch's durability in `hbase:meta` ensures continuity. The fourth scenario describes the unsafe interleaving that the design prevents.

#### Scenario A: Pre-Assignment Fencing via Heartbeat (Primary Path)

1. Region R is currently assigned to RS-A with `epoch=5`

2. Master decides to move R to RS-B (e.g., load balancing, or RS-A reported `FAILED_CLOSE`)

3. Master sends an epoch-aware close RPC to RS-A for region R

4. RS-A processes the close: flushes memstore, closes the region, and stops including it in heartbeat reports

5. Master's `checkOnlineRegionsReport` observes that region R has disappeared from RS-A's heartbeat -> fencing confirmed

6. TRSP increments epoch to 6, dispatches `OpenRegionRequest` to RS-B with `epoch=6`

7. RS-B opens R, reports `OPENED` with `epoch=6` -> accepted

If RS-A ignores the close or fails to process it, the master retries with exponential backoff (Section 5.3.4). If RS-A remains uncooperative after exhausting close attempts, the master escalates to SCP (Section 5.3.5), which physically fences RS-A via WAL lease revocation before proceeding with the new assignment.

#### Scenario B: Stale RS Attempts a State Transition

1. Master assigns region R to RS-B with `epoch=6`

2. RS-A (slow network, previous assignment at `epoch=5`) finally tries to report `OPENED` with `epoch=5`

3. Master's `reportRegionStateTransition`: `epoch 5 < current epoch 6` -> REJECT

4. RS-A receives rejection error -> self-fences by closing R locally

#### Scenario C: Master Failover

1. New master loads `assignmentEpoch` from `hbase:meta` for each region

2. Epochs are durable, no need for in-memory reconstruction

3. The first heartbeat from any RS will be validated against the persisted epoch

#### Scenario D: The Unsafe Interleaving (What the Design Prevents)

The following sequence is explicitly prohibited by the pre-assignment blocking invariant (Section 1.2.1). It is described here to motivate the design constraint:

1. Master decides to move region R from RS-A (`epoch=5`) to RS-B

2. **UNSAFE:** Master increments epoch to 6 and immediately dispatches `OpenRegionRequest` to RS-B *without first confirming RS-A has closed region R*

3. RS-B opens R, advances `openSeqNum`, begins serving new writes

4. RS-A is still alive, still serving region R with `epoch=5`. It continues accepting client writes and flushing to WAL/HFiles

5. RS-A's next heartbeat (up to 3 seconds later) arrives at the master

6. Master detects stale epoch, sends close RPC to RS-A

7. RS-A processes the close, but the writes it accepted in steps 4-6 are **silently lost**: RS-B opened with a new `openSeqNum`, so RS-A's WAL entries will never be replayed by any subsequent region open

The design prevents this scenario by requiring the TRSP to **block** (step 2) until RS-A's close is confirmed (Scenario A) or RS-A is physically fenced via SCP (Section 5.3.5). This serialization, close-then-open, eliminates the window during which two RSes can simultaneously serve the same region.

### 4.4 hbase:meta Bootstrapping

Per-region assignment epochs are persisted in `hbase:meta`. However, `hbase:meta` is itself a region that must be assigned during master startup, before `hbase:meta` is available for reads or writes. 

The assignment epoch for `hbase:meta` is stored in a dedicated ZooKeeper znode (`/hbase/meta-assignment-epoch`) rather than in a `hbase:meta` row. This is consistent with how `hbase:meta`'s lifecycle is already managed. The `/hbase/meta-region-server` znode stores the `ServerName` of the RS hosting meta, and the epoch znode sits alongside it as a peer in the ZooKeeper namespace.

The lifecycle for `hbase:meta`'s epoch mirrors the general epoch lifecycle (Section 4.2) with the persistence layer swapped from `hbase:meta` to ZooKeeper:

1. **Master startup.** The master reads the epoch from the ZK znode during initialization, before attempting to assign `hbase:meta`. If the znode does not exist (fresh cluster or pre-epoch-fencing upgrade), the master creates it with an initial value of `1`. This read happens once per master lifetime and does not depend on `hbase:meta` being online.

2. **Meta assignment.** When the TRSP for `hbase:meta` assigns the meta region, it increments the epoch and writes the new value to ZK via a `setData` call. The epoch is included in the `OpenRegionRequest` sent to the target RS, exactly as for any other region.

3. **Heartbeat and transition validation.** Once the RS opens `hbase:meta` and begins reporting it in heartbeats, the master validates the reported epoch against the value stored in the `RegionStateNode` for meta, which was populated from ZK on startup or updated by the TRSP's increment. The validation path in `checkOnlineRegionsReport()` and `reportRegionStateTransition()` is identical to any other region. Only the persistence backing differs.

4. **Master failover.** The new master reads the epoch from ZK during initialization, before attempting to assign `hbase:meta`. Durability is provided by ZK's replicated log, not by `hbase:meta`. This is the same durability model that `hbase:meta`'s location already relies on. If ZK loses data, the state is recreated through bootstrapping (again).

The pre-assignment blocking invariant (Section 1.2.1) applies to `hbase:meta` exactly as it does to any other region. The TRSP for meta must confirm the previous assignment holder has released the meta region before dispatching a new `OpenRegionRequest`. The heartbeat-driven detection, epoch-aware close, and SCP escalation paths all operate identically.

## 5. Detailed Changes

### 5.1 Protobuf Changes

The epoch is represented as `uint64` on the wire (protobuf) and as a signed `long` in Java. The `uint64` wire type can represent all positive values of a Java `long` without loss. As discussed in Section 1.2, rollover of this counter is not a practical concern.

#### RegionLoad (in ServerLoad, sent with every heartbeat)

**message** RegionLoad {  
  *// ... existing fields ...*  
  **optional** uint64 assignment_epoch = <next_tag>;  
}

#### OpenRegionRequest.RegionOpenInfo

```java
message RegionOpenInfo {  
  // ... existing fields ...  
  optional uint64 assignment_epoch = <next_tag>;  
}
```

#### RegionStateTransition (in ReportRegionStateTransitionRequest)

```java
message RegionStateTransition {  
  // ... existing fields ...  
  optional uint64 assignment_epoch = <next_tag>;  
}
```

#### CloseRegionRequest (Extended)

Add optional epoch fields to the existing `CloseRegionRequest` so the RS understands *why* it is being told to close and can stop accepting writes immediately. Old masters that don't set these fields will not change RS behavior (the fields are optional).

```java
message CloseRegionRequest {  
  // ... existing fields ...  
  optional uint64 stale_epoch = <next_tag>;   // RS's epoch  
  optional uint64 current_epoch = <next_tag>; // master's epoch  
}
```

### 5.2 hbase:meta Schema Change

Add column `info:assignmentEpoch` (type: `long`, big-endian bytes).

| Row | regioninfo | server | openSeqNum | assignmentEpoch |
| :---- | :---- | :---- | :---- | :---- |
| `region123` | `(RegionInfo PB)` | `serverB:16020` | `20100` | `6` |

This column is present for all user-table and system-table regions that have rows in `hbase:meta`. The `hbase:meta` region itself does not have a row in `hbase:meta` (its location is tracked via ZooKeeper), so its assignment epoch is persisted in a dedicated ZooKeeper znode instead (Section 4.4, Section 5.3.9).

### 5.3 Master-Side Changes

#### 5.3.1 RegionStateNode

The epoch is stored as a Java signed `long`, initialized to `1` for new regions and loaded from `hbase:meta` on master startup. This matches the initial epoch used for daughter/merged regions (Section 5.3.8), migration backfill (Section 6.2), and `hbase:meta` ZK bootstrap (Section 5.3.9), ensuring a consistent starting value across all region creation paths. The `++` increment is safe because epoch updates occur under the `RegionStateNode` lock, which is already held by TRSP during assignment. As noted in Section 1.2, this counter will not roll over under any realistic workload.

```java
// New field  
private long assignmentEpoch = 1;

public long getAssignmentEpoch() { return assignmentEpoch; }

public long incrementAndGetAssignmentEpoch() {  
  return ++assignmentEpoch;  
}
```

#### 5.3.2 TransitRegionStateProcedure

The TRSP enforces the pre-assignment blocking invariant (Section 1.2.1). Before dispatching an `OpenRegionRequest` to a new RS, the TRSP must confirm that the previous assignment holder has been fenced. This is implemented as a blocking step in the TRSP state machine.

In the `OPEN` state handler (when preparing to send `OpenRegionRequest`):

```java
// Step 1: Pre-assignment fencing check.  
// If the region was previously assigned to a different RS, confirm  
// that the old RS has released it before dispatching the new assignment.  
ServerName previousServer = regionNode.getLastHost();  
if (previousServer != null && !previousServer.equals(targetServer)) {  
  // Fast path: Check authoritative transition state.  
  // If the master has already processed a CLOSED transition for this  
  // region via reportRegionStateTransition(), the RegionStateNode  
  // will be in CLOSED state. This is the common case.  
  if (regionNode.isInState(State.CLOSED)) {  
    LOG.info("Pre-assignment fencing confirmed for {} via transition "  
        + "report (fast path)", regionNode);  
    // Fall through to epoch increment and dispatch.  
  } else if (!isRegionStillReportedBy(previousServer, regionNode)) {  
    // Slow path: Heartbeat-based fallback.  
    // The region is not in CLOSED state (e.g., master restarted and  
    // lost in-memory transition state, or unassign TRSP failed  
    // partway through), but the old RS's heartbeat no longer lists  
    // it. This path may stall for up to one heartbeat interval  
    // (default 3s) while waiting for the old RS's next report.  
    LOG.info("Pre-assignment fencing confirmed for {}: {} no longer "  
        + "reports region (heartbeat path)", regionNode, previousServer);  
  } else {  
    // Neither tier confirms fencing. Block.  
    if (!hasPendingFencingClose(previousServer, regionNode)) {  
      sendFencingClose(previousServer, regionNode);  
    }  
    LOG.info("TRSP for {} blocking on pre-assignment fencing: {} still "  
        + "reports region; will retry after fencing confirmed",  
        regionNode, previousServer);  
    if (fencingWaitExceeded()) {  
      LOG.error("TRSP for {} timed out waiting for fencing of {} ({}ms); "  
          + "failing assignment", regionNode, previousServer,  
          fencingTimeoutMs);  
      setFailure(new IOException("Pre-assignment fencing timeout"));  
      return Flow.NO_MORE_STATE;  
    }  
    throw new ProcedureSuspendedException();  
  }  
}

// Step 2: Increment epoch and dispatch.  
long newEpoch = regionNode.incrementAndGetAssignmentEpoch();  
// Persist to meta along with the server assignment  
env.getAssignmentManager().persistToMeta(regionNode);  
// Include newEpoch in the OpenRegionRequest
```

The pre-assignment fencing check uses a two-tier approach to avoid unnecessary stalls during normal operation.

**Fast path: Authoritative transition state.** In the common case of a cooperative RS on a functioning network the master sends a close RPC to RS-A, RS-A closes the region, RS-A calls `reportRegionStateTransition(CLOSED)`, and the master updates the `RegionStateNode` to `CLOSED`. All of this completes before the TRSP(assign) for RS-B begins its pre-assignment check. The check sees `State.CLOSED` and proceeds immediately with zero stall. This is the path that every normal load-balancer region move follows.

**Slow path: Heartbeat-based fallback.** The `isRegionStillReportedBy()` method checks the most recent heartbeat report from the old RS (stored in `rsReports`) to determine whether the region is still listed. This path is necessary when the authoritative transition state is unavailable: (a) after master failover, when the new master reconstructs `RegionStateNode` from `hbase:meta` and the transient `CLOSED` state is lost; (b) when the unassign TRSP failed partway through (e.g., RS crashed mid-close before calling `reportRegionStateTransition`), leaving the `RegionStateNode` in `OPEN` or `CLOSING` state; (c) edge cases where the TRSP(assign) starts before the master has finished processing the CLOSED transition. This path may stall for up to one heartbeat interval (default 3s) while waiting for the old RS's next report. However, for cooperative closes where the RS successfully reports `CLOSED` via `reportRegionStateTransition()`, the synchronous `rsReports` cleanup (Section 5.3.7) eliminates this stall by removing the region from `rsReports` immediately upon processing the CLOSED transition, so the slow path returns the correct answer without waiting for the next heartbeat.

If neither path confirms fencing, the TRSP suspends itself via `ProcedureSuspendedException` and will be re-woken by the procedure executor on its next scheduling cycle. The heartbeat-driven stale close path (Section 5.3.4) runs concurrently, retrying close RPCs with exponential backoff and escalating to SCP if necessary.

The fencing timeout is controlled by `hbase.assignment.pre.fencing.timeout.ms` (default: 600000, i.e., 10 minutes). If the timeout expires, the TRSP fails the assignment and leaves the region in its current state. This is safe: it preserves the existing behavior of requiring operator intervention for stuck assignments, and prevents a single uncooperative RS from blocking the procedure executor indefinitely.

On retry or resume, the TRSP checks whether its epoch has been superseded by a subsequent assignment. If so, it early-outs to release its PEWorker thread:

```java
// At the start of each execution step (before dispatching to RS):  
long myEpoch = this.assignedEpoch;  
long currentEpoch = regionNode.getAssignmentEpoch();  
if (myEpoch < currentEpoch) {  
  LOG.info("TRSP for {} is stale (epoch {} < current {}); early-out",  
    regionNode, myEpoch, currentEpoch);  
  return Flow.NO_MORE_STATE;  // Release PEWorker  
}
```

#### 5.3.3 AssignmentManager.checkOnlineRegionsReport() (Enhanced)

Replace the server-name-only check with epoch-aware validation. The epoch comparison handles three cases explicitly: `reportedEpoch < currentEpoch` (stale, initiate close), `reportedEpoch > currentEpoch` (impossible), and the implicit equal case which falls through to the existing server-name check:

```java
private void checkOnlineRegionsReport(ServerStateNode serverNode,  
    Set<byte[]> regionNames, Map<byte[], Long> regionEpochs) {  
  ServerName serverName = serverNode.getServerName();  
  for (byte[] regionName : regionNames) {  
    RegionStateNode regionNode = regionStates.getRegionStateNodeFromName(regionName);  
    if (regionNode == null) {  
      closeRegionSilently(serverName, regionName);  
      continue;  
    }  
    if (regionNode.tryLock()) {  
      try {  
        Long reportedEpoch = regionEpochs.get(regionName);  
        long currentEpoch = regionNode.getAssignmentEpoch();

        if (reportedEpoch != null && reportedEpoch < currentEpoch) {  
          // STALE: This RS has a zombie region from a previous assignment  
          LOG.warn("Stale region detected: {} on {} reports epoch {} "  
            + "but current epoch is {}; initiating epoch-aware close",  
            regionNode, serverName, reportedEpoch, currentEpoch);  
          closeStaleRegion(serverName, regionNode, reportedEpoch, currentEpoch);  
        } else if (reportedEpoch != null && reportedEpoch > currentEpoch) {  
          LOG.error("Region {} on {} reports epoch {} > master epoch {}; "  
            + "possible state corruption", regionNode, serverName,  
            reportedEpoch, currentEpoch);  
        } else if (regionNode.isInState(State.OPENING, State.OPEN)) {  
          // Existing logic for wrong-server detection (not epoch-related)  
          if (!regionNode.getRegionLocation().equals(serverName)) {  
            long diff = EnvironmentEdgeManager.currentTime()  
              - regionNode.getLastUpdate();  
            if (diff > lag) {  
              closeRegionSilently(serverName, regionName);  
            }  
          }  
        }  
      } finally {  
        regionNode.unlock();  
      }  
    }  
  }  
}
```

#### 5.3.4 AssignmentManager.closeStaleRegion() (Defense-in-Depth)

This mechanism serves as a second line of defense. The primary fencing mechanism is the TRSP pre-assignment blocking step (Section 5.3.2), which ensures the master never dispatches a new assignment before the old one is fenced. The heartbeat-driven stale close path described here handles two additional cases: (a) zombie regions that escaped the primary fencing step due to bugs or race conditions, and (b) providing the close-with-retry logic that the TRSP's pre-assignment blocking step relies on to drive uncooperative RSes toward fencing.

A TRSP cannot be used to close a stale region because (a) `RegionStateNode` permits only one active TRSP per region (`setProcedure` asserts `this.procedure == null`), and the region may already have a completed or in-flight TRSP for its *current* assignment to RS-B, and (b) `TRSP.unassign()` targets the RS recorded in `RegionStateNode`, which is RS-B, not the stale RS-A.

Instead, we send an epoch-aware close RPC to the stale RS using the extended `CloseRegionRequest` (with the new `stale_epoch` and `current_epoch` optional fields). The corresponding RS-side changes (Section 5.4.6) use these fields to (a) validate the request by comparing the `stale_epoch` against the region's current epoch, (b) immediately stop accepting writes for that region by calling `region.setClosing()` before beginning the full close sequence, and (c) log meaningful diagnostics identifying the epoch mismatch.

To avoid spamming the stale RS with close RPCs on every heartbeat, the master tracks pending stale closes in a `ConcurrentHashMap`. Each entry records the first detection time and the number of attempts. The master only retries after an exponentially increasing backoff (capped at 30 seconds). After a configurable number of attempts — regardless of whether the close RPCs succeeded or failed — (controlled by the SCP escalation threshold, default: 20), the master escalates to ERROR-level logging, increments a dedicated metric (`staleEpochCloseFailures`), and triggers SCP for the uncooperative RS (see Section 5.3.5). This is critical because an RS that acknowledges close RPCs but silently ignores them must still be escalated. An entry is removed when the stale region disappears from the RS's heartbeat. When an RS crashes or is killed via SCP escalation, heartbeats stop arriving, so the per-region `clearStaleCloseTracker` callback will never fire. To prevent map entries from leaking indefinitely, a bulk cleanup hook, `purgeStaleClosesForServer()`, is called from `ServerCrashProcedure` (and from the `expireServer()` path that triggers it) to remove all entries keyed to the dead server in a single pass.

#### 5.3.5 SCP Escalation for Uncooperative RSes

After exhausting close attempts — whether they succeeded or failed — the master escalates to a forced SCP for the offending server by calling `ServerManager.expireServer()`. SCP fences the RS at the HDFS level by acquiring the lease on its WAL, killing the server's ability to persist further writes, and then proceeds with standard crash recovery (WAL splitting, region reassignment). This is the definitive resolution: once SCP completes, the stale region is guaranteed to be fenced regardless of RS behavior.

This pattern already has precedent in HBase. The `RSProcedureDispatcher`'s `scheduleForRetry()` method (RSProcedureDispatcher.java L331-341) already calls `expireServer()` after `failFastRetryLimit` (default: 10) failed RPC attempts with network, SASL, or call-queue errors. The `submitServerCrash()` method in `AssignmentManager` (AssignmentManager.java L1947-2018) acquires the `ServerStateNode` write lock, sets the server state to `CRASHED` (which fences all further `reportRegionStateTransition` calls from that server), and submits the SCP to the procedure executor. The epoch fencing escalation follows the same established path, adding a new trigger condition: sustained inability to retire a stale region, whether due to RPC failures or an RS that acknowledges close requests but ignores them.

The escalation threshold is controlled by `hbase.assignment.stale.epoch.scp.threshold` (default: 20 attempts). After the configured number of close attempts — regardless of RPC outcome — the master calls `expireServer()` for the uncooperative RS. This is enabled by default because indefinite stale region service is a correctness hazard that epoch fencing is specifically designed to eliminate. The default of 20 attempts with exponential backoff (3s -> 6s -> 12s -> 24s -> 30s cap) means that SCP will not trigger until roughly 8 minutes of sustained non-cooperation, providing ample time for a transiently unhealthy RS (e.g., long GC pause, brief network partition) to recover and honor a close request.

The threshold should be tuned to the operator's failure environment. Setting it too low risks compounding a gray failure with a self-inflicted hard failure, killing an RS that was temporarily unresponsive but has since recovered, forcing unnecessary WAL splitting and region reassignment across the cluster. Setting it too high leaves stale regions serving data longer than necessary. Operators can disable the escalation entirely by setting the threshold to 0.

```java
// Tracking structure for pending stale closes  
private final ConcurrentHashMap<Pair<ServerName, String>, StaleCloseTracker>  
    pendingStaleCloses = new ConcurrentHashMap<>();

private static class StaleCloseTracker {  
  final long firstDetectedMs;  
  int attemptCount;  
  long lastAttemptMs;  
}

private void closeStaleRegion(ServerName serverName,  
    RegionStateNode regionNode, long reportedEpoch, long currentEpoch) {  
  String encodedName = regionNode.getRegionInfo().getEncodedName();  
  Pair<ServerName, String> key = Pair.newPair(serverName, encodedName);  
  long now = EnvironmentEdgeManager.currentTime();

  StaleCloseTracker tracker = pendingStaleCloses  
      .computeIfAbsent(key, k -> new StaleCloseTracker(now));

  // Exponential backoff: 3s, 6s, 12s, ... capped at 30s  
  long backoff = Math.min(30_000L,  
      msgInterval * (1L << Math.min(tracker.attemptCount, 4)));  
  if (now - tracker.lastAttemptMs < backoff) {  
    return; // Too soon to retry  
  }

  tracker.attemptCount++;  
  tracker.lastAttemptMs = now;

  boolean rpcFailed = false;  
  try {  
    RegionInfo ri = regionNode.getRegionInfo();  
    master.getServerManager().sendCloseRegionForEpoch(  
        serverName, ri, reportedEpoch, currentEpoch);  
    LOG.info("Sent epoch-aware close for stale region {} to {} "  
        + "(stale epoch {} < current {}; attempt #{})",  
        encodedName, serverName, reportedEpoch, currentEpoch,  
        tracker.attemptCount);  
    master.getMetrics().incrementStaleEpochClosesSent();  
  } catch (Exception e) {  
    rpcFailed = true;  
    LOG.warn("Failed to close stale region {} on {} (attempt #{}); "  
        + "will retry with backoff",  
        encodedName, serverName, tracker.attemptCount, e);  
  }

  if (scpEscalationThreshold > 0 && tracker.attemptCount >= scpEscalationThreshold) {  
    LOG.error("Stale region {} on {} has resisted {} close attempts "  
        + "over {}ms; escalating to SCP",  
        encodedName, serverName, tracker.attemptCount,  
        now - tracker.firstDetectedMs);  
    master.getMetrics().incrementStaleEpochCloseFailures();  
    master.getServerManager().expireServer(serverName);  
  }  
}

// Called when a region disappears from an RS's heartbeat report  
private void clearStaleCloseTracker(ServerName serverName,  
    String encodedRegionName) {  
  pendingStaleCloses.remove(  
      Pair.newPair(serverName, encodedRegionName));  
}

// Called from ServerCrashProcedure / expireServer() to bulk-remove all  
// tracking entries for a dead server. Without this, entries for crashed  
// RSes would leak indefinitely because heartbeats stop arriving and  
// the per-region clearStaleCloseTracker callback never fires.  
public void purgeStaleClosesForServer(ServerName serverName) {  
  int removed = 0;  
  Iterator<Map.Entry<Pair<ServerName, String>, StaleCloseTracker>> it =  
      pendingStaleCloses.entrySet().iterator();  
  while (it.hasNext()) {  
    if (it.next().getKey().getFirst().equals(serverName)) {  
      it.remove();  
      removed++;  
    }  
  }  
  if (removed > 0) {  
    LOG.info("Purged {} stale close tracker entries for crashed server {}",  
        removed, serverName);  
  }  
}
```

#### 5.3.6 AssignmentManager.reportRegionStateTransition() (Enhanced)

In the existing transition validation logic, add epoch check:

```java
// After existing validation (server state check, procedure check):  
// Use hasAssignmentEpoch() to distinguish old RSes (field absent) from  
// new RSes reporting a stale epoch. If the field is absent, the RS  
// predates epoch fencing and we fall back to existing server-name  
// comparison. If the field is present, we validate it.  
if (transition.hasAssignmentEpoch()) {  
  long reportedEpoch = transition.getAssignmentEpoch();  
  long currentEpoch = regionNode.getAssignmentEpoch();  
  if (reportedEpoch < currentEpoch) {  
    LOG.warn("Rejecting stale transition for {} from {}: "  
      + "reported epoch {} < current epoch {}",  
      regionNode, serverName, reportedEpoch, currentEpoch);  
    return TRANSITION_REJECTED_STALE_EPOCH;  
  } else if (reportedEpoch > currentEpoch) {  
    LOG.error("Region {} transition from {} reports epoch {} > master "  
      + "epoch {}; possible state corruption",  
      regionNode, serverName, reportedEpoch, currentEpoch);  
  }  
}  
// else: old RS without epoch support; fall through to existing validation
```

#### 5.3.7 Synchronous rsReports Cleanup on CLOSED Transition

The `rsReports` map in `AssignmentManager` stores the set of online regions reported by each RS in its most recent heartbeat. The TRSP's pre-assignment fencing check (Section 5.3.2) queries this map via `isRegionStillReportedBy()` on the slow path to determine whether a region is still listed in the old RS's most recent heartbeat report.

During a normal region move, a timing gap exists between the CLOSED transition report and the next heartbeat:

1. RS-A's most recent heartbeat (stored in `rsReports`) lists region R as online.

2. RS-A closes region R and reports `CLOSED` via `reportRegionStateTransition()`.

3. The master validates and accepts the CLOSED transition, updating the `RegionStateNode` to `CLOSED`.

4. **Gap:** `rsReports` still contains the pre-close heartbeat listing region R on RS-A. The next heartbeat from RS-A, up to 3 seconds later, will omit region R and correct the map.

If the assign TRSP for the new RS falls to the slow path during this gap, `isRegionStillReportedBy()` returns `true` and the TRSP blocks unnecessarily, waiting for the next heartbeat to confirm that RS-A no longer reports the region. While the fast path (checking the `RegionStateNode` for `CLOSED` state) handles the common case, the fast path is unavailable when: (a) a concurrent state change has already moved the `RegionStateNode` out of `CLOSED` (e.g., the assign TRSP itself transitioning the state to `OPENING`); (b) the TRSP code path executes the slow-path check without first consulting the fast path (e.g., a defensive re-check after `ProcedureSuspendedException` resume); or (c) a race between CLOSED processing and TRSP scheduling causes the TRSP to observe an intermediate state.

To eliminate this stall, `reportRegionStateTransition()` must synchronously remove the region from the `rsReports` entry for the reporting RS immediately upon successfully processing a valid CLOSED transition:

```java
// In reportRegionStateTransition(), after successfully processing a
// CLOSED transition for region R from server serverName:
if (transitionCode == TransitionCode.CLOSED) {
  removeRegionFromRsReports(serverName,
      regionNode.getRegionInfo().getRegionName());
}

private void removeRegionFromRsReports(ServerName serverName,
    byte[] regionName) {
  Set<byte[]> reportedRegions = rsReports.get(serverName);
  if (reportedRegions != null) {
    reportedRegions.remove(regionName);
    LOG.debug("Synchronously removed {} from rsReports for {} "
        + "on CLOSED transition",
        Bytes.toStringBinary(regionName), serverName);
  }
}
```

This update is safe because the CLOSED transition has already been fully validated: the epoch check passed (Section 5.3.6), the server identity is confirmed, and the procedure state is consistent. The master has authoritative knowledge that the RS no longer hosts the region. Removing the region from `rsReports` makes the stored heartbeat data consistent with this knowledge immediately, rather than waiting for the next heartbeat to reflect the RS's post-close state.

Thread safety requires that the `rsReports` value sets support concurrent modification, since the heartbeat path (`reportOnlineRegions()`) replaces the entire set for an RS while the transition path removes individual entries. The implementation should use `ConcurrentHashMap.newKeySet()` for the region sets, or perform the removal via atomic `compute()` on the outer map to avoid races with concurrent heartbeat updates that replace the set. A practical approach is to use `rsReports.computeIfPresent(serverName, (k, regions) -> { regions.remove(regionName); return regions; })`, which executes the removal atomically with respect to other `compute` operations on the same key.

If a stale heartbeat (sent before the RS closed the region but arriving at the master after the CLOSED transition is processed) replaces the `rsReports` entry after the synchronous removal, the region will reappear in `rsReports` temporarily. This is harmless: the next heartbeat from the RS (which will omit the region) will correct the entry, and the TRSP's fast path remains available via the `RegionStateNode` CLOSED state. The synchronous removal is a best-effort optimization that eliminates the stall in the common case, not an invariant that must hold under all interleavings.

#### 5.3.8 SplitTableRegionProcedure and MergeTableRegionsProcedure

Region splits and merges create new region entities that are not covered by the normal TRSP epoch increment path. These procedures must explicitly initialize the `assignmentEpoch` for the newly created regions.

**Splits:** `SplitTableRegionProcedure` creates two daughter regions and writes them to `hbase:meta` in the `CREATE_DAUGHTER_REGIONS` step. The epoch for each daughter is initialized to `1` in the same `Put` mutation that writes `info:regioninfo`, `info:server`, and `info:openSeqNum`:

```java
// In SplitTableRegionProcedure, when writing daughter regions to meta:  
Put daughterPut = MetaTableAccessor.makePutFromRegionInfo(daughterRegionInfo);  
// ... existing columns (server, openSeqNum, etc.) ...  
daughterPut.addColumn(HConstants.CATALOG_FAMILY,  
    Bytes.toBytes("assignmentEpoch"),  
    Bytes.toBytes(1L));
```

The `RegionStateNode` for each daughter is also initialized with `assignmentEpoch = 1` in memory. When the daughter regions are subsequently assigned via TRSP (which happens as the final step of the split procedure), the normal TRSP path increments the epoch from `1` to `2` and persists it.

**Merges:** `MergeTableRegionsProcedure` creates a single merged region and writes it to `hbase:meta` in the `CREATE_MERGED_REGION` step. The initialization is identical: the merged region's `assignmentEpoch` is set to `1` in the `Put` mutation that creates the meta row. When the merged region is subsequently assigned via TRSP, the epoch increments from `1` to `2`.

In both cases, the parent region(s) retain their existing epoch values in `hbase:meta` until they are cleaned up by the catalog janitor. These parent epochs are inert: the parent regions are marked as split/merged and will not be assigned again, so their epochs are never validated against heartbeats.

#### 5.3.9 hbase:meta Epoch Persistence via ZooKeeper

As described in Section 4.4, the `hbase:meta` region's assignment epoch is stored in a dedicated ZooKeeper znode rather than in a `hbase:meta` row. The implementation requires three changes to the master's epoch management code.

**ZK znode path.** The epoch is stored at `/hbase/meta-assignment-epoch` (relative to the HBase ZK root). The znode holds an 8-byte big-endian `long`, the same serialization used for `info:assignmentEpoch` values in `hbase:meta` rows. The path constant is defined alongside the existing meta-related ZK paths in `ZNodePaths`:

```java
public static final String META_ASSIGNMENT_EPOCH_NODE = "meta-assignment-epoch";
```

**Loading on master startup.** During `AssignmentManager` initialization, after the master establishes its ZK session but before it attempts to assign `hbase:meta`, the master reads the epoch from ZK. If the znode does not exist, it is created with an initial value of `1`:

```java
private long loadMetaAssignmentEpochFromZK() throws KeeperException {
  String zkPath = ZNodePaths.joinZNode(
      watcher.getZNodePaths().baseZNode, META_ASSIGNMENT_EPOCH_NODE);
  byte[] data = ZKUtil.getDataNoWatch(watcher, zkPath, null);
  if (data == null || data.length == 0) {
    long initialEpoch = 1L;
    ZKUtil.createAndWatch(watcher, zkPath, Bytes.toBytes(initialEpoch));
    return initialEpoch;
  }
  return Bytes.toLong(data);
}
```

The returned value is stored in the `RegionStateNode` for `hbase:meta`, following the same in-memory representation used for all other regions. From that point forward, heartbeat and transition validation use the `RegionStateNode` epoch without awareness of the underlying persistence mechanism.

**Persisting on meta assignment.** The TRSP's epoch persistence step (Section 5.3.2) is conditioned on whether the target region is `hbase:meta`. For user-table regions, the epoch is written to `hbase:meta` as an additional column in the existing `Put` mutation. For the meta region, the epoch is written to ZK:

```java
long newEpoch = regionNode.incrementAndGetAssignmentEpoch();
if (regionNode.getRegionInfo().isMetaRegion()) {
  persistMetaEpochToZK(newEpoch);
} else {
  env.getAssignmentManager().persistToMeta(regionNode);
}

private void persistMetaEpochToZK(long epoch) throws KeeperException {
  String zkPath = ZNodePaths.joinZNode(
      watcher.getZNodePaths().baseZNode, META_ASSIGNMENT_EPOCH_NODE);
  ZKUtil.setData(watcher, zkPath, Bytes.toBytes(epoch));
}
```

The `isMetaRegion()` check mirrors the existing pattern used throughout the assignment code to special-case `hbase:meta` (e.g., in `MetaTableLocator`, `AssignmentManager.assignMeta()`, and the meta-specific TRSP subclass). This is the only point where the persistence path diverges; all other epoch operations (in-memory storage, heartbeat validation, transition validation, pre-assignment blocking) are identical for meta and non-meta regions.

**Failure atomicity.** For user-table regions, the epoch is written atomically with `info:server` and `info:openSeqNum` in a single `Put` to `hbase:meta`. For the meta region, the ZK `setData` call is a separate operation from the ZK write that updates the meta location znode. If the epoch `setData` succeeds but the meta location update fails (or vice versa), the TRSP will retry the entire assignment step, which re-reads the epoch from ZK and re-increments it. This is safe because the epoch is monotonically increasing: a re-increment after a partial failure simply skips one epoch value, which is harmless. The same retry-from-scratch behavior already applies to `hbase:meta` assignment failures in the existing code; the epoch write does not introduce a new partial-failure mode.

### 5.4 RegionServer-Side Changes

#### 5.4.1 HRegion: Store Epoch

```java
// New field in HRegion  
private volatile long assignmentEpoch;

public long getAssignmentEpoch() { return assignmentEpoch; }  
public void setAssignmentEpoch(long epoch) { this.assignmentEpoch = epoch; }
```

#### 5.4.2 AssignRegionHandler: Receive Epoch from Master

When the RS receives an `OpenRegionRequest`, extract and store the epoch:

```java
// In AssignRegionHandler, after opening the region:  
region.setAssignmentEpoch(regionOpenInfo.getAssignmentEpoch());
```

#### 5.4.3 HRegionServer.createRegionLoad(): Include Epoch in Heartbeat

```java
// In createRegionLoad (called by buildServerLoad for every heartbeat):  
regionLoadBldr.setAssignmentEpoch(region.getAssignmentEpoch());
```

#### 5.4.4 HRegionServer.reportRegionStateTransition(): Include Epoch

```java
// When building RegionStateTransition proto:  
transition.setAssignmentEpoch(region.getAssignmentEpoch());
```

#### 5.4.5 RS Self-Fencing on Stale Epoch Error

In the `reportRegionStateTransition` error handling path (HRegionServer.java L2698-2756):

```java
if (response.hasErrorCode()  
    && response.getErrorCode() == TRANSITION_REJECTED_STALE_EPOCH) {  
  LOG.error("Region {} has stale epoch; self-fencing by closing",  
    regionInfo.getEncodedName());  
  closeRegionIgnoreErrors(regionInfo);  
  return;  
}
```

#### 5.4.6 RS Handling of Epoch-Aware Close

When the RS receives a `CloseRegionRequest` that includes the `stale_epoch` and `current_epoch` fields, it can confirm the close is legitimate and take immediate action:

```java
// In the close-region handler, before beginning the close sequence:  
if (request.hasStaleEpoch() && request.hasCurrentEpoch()) {  
  HRegion region = getRegion(request.getRegion());  
  if (region != null  
      && region.getAssignmentEpoch() == request.getStaleEpoch()) {  
    LOG.warn("Epoch-aware close: region {} has stale epoch {} "  
        + "(current is {}); freezing writes and closing",  
        region.getRegionInfo().getEncodedName(),  
        request.getStaleEpoch(), request.getCurrentEpoch());  
    // Immediately reject new writes before the full close completes.  
    // This minimizes the window for data corruption.  
    region.setClosing();  
  }  
}
```

If the epoch fields are absent (old master), the RS processes the close as it does today.

### 5.5 HBCK2 Enhancements (hbase-operator-tools)

Epoch-based fencing introduces new persistent state (`info:assignmentEpoch` in `hbase:meta`, the meta epoch in ZooKeeper, and the `pendingStaleCloses` tracking map) that operators may need to inspect and repair when automated mechanisms fail or produce unexpected results. The failure mode analysis in Section 9.5 explicitly identifies scenarios where HBCK2-based reconciliation is the prescribed operator response. The following enhancements to HBCK2 in the hbase-operator-tools repository provide the necessary operator-facing tooling.

#### 5.5.1 Epoch Inspection and Repair

**`getAssignmentEpoch <ENCODED_REGIONNAME>...`**

Read the persisted `info:assignmentEpoch` value from `hbase:meta` for one or more regions and display it alongside the region's current state, server assignment, and `openSeqNum`. This is the primary diagnostic tool for the "Master epoch state desynchronized from meta" failure mode (Section 9.5): when the master logs an ERROR indicating `reportedEpoch > currentEpoch`, the operator uses this command to determine the authoritative epoch value in `hbase:meta` and compare it against the master's in-memory state.

**`setAssignmentEpoch <ENCODED_REGIONNAME> <EPOCH>`**

Manually set the `info:assignmentEpoch` column in `hbase:meta` for a region and update the master's in-memory `RegionStateNode` to match. This is the repair tool for epoch desynchronization. Section 9.5 prescribes this action: "The ERROR log provides the diagnostic signal for operators to investigate whether the master's state or `hbase:meta` is the source of truth, and to reconcile them via HBCK2 if necessary."

The implementation writes the epoch value to `hbase:meta` via a direct `Put` and then calls a new master RPC to update the `RegionStateNode.assignmentEpoch` in memory. Updating both meta and memory atomically ensures the master does not drift again on the next heartbeat cycle. If the master is unreachable, the operator can set the meta value and rely on the master loading the corrected epoch from meta on restart.

The command validates that the provided epoch is a positive `long` and warns (but does not block) if the value is lower than the current persisted epoch, since lowering an epoch could cause a legitimately assigned RS to be incorrectly fenced on the next heartbeat.

#### 5.5.2 Meta Region Epoch Commands

The `hbase:meta` region's assignment epoch is stored in a ZooKeeper znode rather than in a `hbase:meta` row (Section 4.4). Two commands provide inspection and repair for this epoch.

**`getMetaEpoch`**

Read the `hbase:meta` region's assignment epoch from the `/hbase/meta-assignment-epoch` ZK znode and display the value.

**`setMetaEpoch <EPOCH>`**

Write a new epoch value to the ZK znode. Needed if the znode is lost or corrupted (e.g., after ZK data loss), or if the epoch is out of sync with the master's in-memory state after a failed meta reassignment. The command updates ZK directly via `ZKUtil.setData()` and, if the master is reachable, also updates the meta region's `RegionStateNode` in memory.

#### 5.5.3 Stale Region Diagnostics

**`reportStaleRegions`**

Query the master's `pendingStaleCloses` tracking map (Section 5.3.4) and display a report for each tracked entry: the region's encoded name, the stale RegionServer, the reported epoch, the current epoch, the number of close attempts, the time since first detection, and whether SCP escalation is pending. This gives operators the information they need to decide whether to wait for automated resolution, manually trigger SCP via `scheduleRecoveries`, or investigate the uncooperative RS.

This command requires a new method on the `Hbck` service interface:

```java
List<StaleRegionInfo> getStaleRegions() throws IOException;
```

The `StaleRegionInfo` message includes the fields listed above. An empty list indicates no regions are currently tracked as stale.

#### 5.5.4 Epoch-Aware assigns and unassigns

The existing HBCK2 `assigns` command submits a `TransitRegionStateProcedure` to the master via the `Hbck` admin interface. With epoch fencing enabled, the TRSP path already increments the epoch on assignment (Section 5.3.2), so `assigns` is inherently epoch-aware without HBCK2-side code changes. However, two enhancements are needed.

First, the `assigns` response must report the epoch that was assigned. This allows operators to verify that the epoch was incremented and to correlate with subsequent heartbeat reports. The `Hbck.assigns()` return value should be extended to include the assigned epoch alongside the existing procedure ID.

Second, the `assigns` and `unassigns` help text and user-facing documentation must be updated to describe epoch interaction: that `assigns` increments the epoch and that any RS still serving the region at a prior epoch will be fenced on the next heartbeat cycle.

#### 5.5.5 Hbck Admin API Additions

The master-side `Hbck` service interface requires the following new methods to support the commands above:

```java
long getAssignmentEpoch(String encodedRegionName) throws IOException;

void setAssignmentEpoch(String encodedRegionName, long epoch)
    throws IOException;

long getMetaAssignmentEpoch() throws IOException;

void setMetaAssignmentEpoch(long epoch) throws IOException;

List<StaleRegionInfo> getStaleRegions() throws IOException;
```

The `setAssignmentEpoch` implementation writes directly to `hbase:meta` and updates the in-memory `RegionStateNode`, ensuring the master's state is consistent with the persisted value. The `setMetaAssignmentEpoch` implementation writes to the ZK znode and updates the meta region's `RegionStateNode`.

Both `set` methods require the caller to hold the `ADMIN` permission. The `get` methods require `ADMIN` or `CREATE` permission, consistent with existing HBCK2 access control.

Corresponding protobuf request/response messages are added to the `Hbck.proto` service definition:

```java
message GetAssignmentEpochRequest {
  required string encoded_region_name = 1;
}
message GetAssignmentEpochResponse {
  required uint64 assignment_epoch = 1;
}

message SetAssignmentEpochRequest {
  required string encoded_region_name = 1;
  required uint64 assignment_epoch = 2;
}
message SetAssignmentEpochResponse {}

message GetMetaAssignmentEpochRequest {}
message GetMetaAssignmentEpochResponse {
  required uint64 assignment_epoch = 1;
}

message SetMetaAssignmentEpochRequest {
  required uint64 assignment_epoch = 1;
}
message SetMetaAssignmentEpochResponse {}

message StaleRegionInfo {
  required string encoded_region_name = 1;
  required ServerName stale_server = 2;
  required uint64 reported_epoch = 3;
  required uint64 current_epoch = 4;
  required int32 close_attempt_count = 5;
  required uint64 first_detected_ms = 6;
  required bool scp_escalation_pending = 7;
}

message GetStaleRegionsRequest {}
message GetStaleRegionsResponse {
  repeated StaleRegionInfo stale_regions = 1;
}
```

## 6. Compatibility and Migration

### 6.1 Rolling Upgrade Safety

When an old RS reports to a new master, the heartbeat and transition reports will not include the `assignmentEpoch` protobuf field. The master MUST NOT default the missing field to 0 and compare it against the current epoch, because migrated regions have epoch >= 1 and the comparison `0 < 1` would evaluate to true, causing the master to continuously send fencing close requests to valid, older RegionServers.

Instead, the master uses the protobuf-generated `hasAssignmentEpoch()` method to check for field presence. If the field is absent, the master treats it as UNSUPPORTED and bypasses the epoch comparison entirely, falling back to the existing server-name-only comparison in `checkOnlineRegionsReport()`. This produces no regression: the behavior is identical to what exists today. In the `checkOnlineRegionsReport()` code (Section 5.3.3), this is already handled by the `reportedEpoch != null` guard: the `regionEpochs` map will not contain an entry for a region whose `RegionLoad` lacks the field, so `reportedEpoch` is `null` and the epoch comparison branch is skipped. In `reportRegionStateTransition()` (Section 5.3.6), the `hasAssignmentEpoch()` check guards the epoch validation path, allowing old RSes to pass through to the existing server-name comparison without triggering a stale epoch rejection.

When a new RS reports to an old master, the RS includes the `assignmentEpoch` field in its `RegionLoad` and `RegionStateTransition` messages. Because protobuf silently ignores unknown fields, the old master discards the epoch and processes the heartbeat and transition normally. There is no regression.

During a rolling upgrade, the cluster will contain a mix of old and new nodes. Epoch fencing is effectively disabled during this window because old RSes do not report epochs and old masters do not validate them. This is safe because the fallback is precisely the existing behavior. Once all nodes are upgraded and the master has loaded epoch values from `hbase:meta` (either from fresh assignments or from the migration backfill described below), epoch fencing activates automatically with no additional operator action.

### 6.2 Meta Migration

On the first master startup after upgrade, for any region in `hbase:meta` that lacks `info:assignmentEpoch`, the master sets it to `1`. This is a one-time migration that runs as part of the existing meta-scan during `RegionStates` initialization.

## 7. Performance Considerations

A key design goal of epoch fencing is zero impact on the read/write data path. The epoch is never consulted during client `Get`, `Put`, `Scan`, or `Delete` operations. All epoch validation occurs on control-plane paths (heartbeats, state transitions, assignment procedures) that already exist and already incur their own overhead. This section quantifies the incremental cost on each of these paths.

### 7.1 Heartbeat Serialization and Network Overhead

The epoch is a single `uint64` field added to the existing `RegionLoad` proto. In protobuf's varint encoding, a `uint64` field with a small value (e.g., epoch values under 128) consumes 1 byte for the tag and 1 byte for the value, or 2 bytes per region. For larger epoch values (under 16,384), it consumes 3 bytes. In the worst case (epoch values exceeding 2^56, which is unreachable in practice), it consumes 10 bytes.

For a representative RS hosting 1,000 regions with epoch values under 16,384, the per-heartbeat overhead is approximately 3 KB (3 bytes x 1,000 regions). The existing `RegionLoad` proto already includes dozens of fields per region (store file count, store file size, memstore size, compaction metrics, read/write request counts, and more), producing a heartbeat payload typically in the range of 100-500 KB depending on region count and the number of stores per region. The epoch adds less than 1% to this payload.

Heartbeats are sent every 3 seconds (`hbase.regionserver.msginterval`). For a cluster of 100 RSes, each sending 3 KB of additional epoch data per heartbeat, the aggregate network overhead at the master is roughly 100 KB per second. This is negligible relative to the master's total network throughput.

### 7.2 Meta Write Overhead

The epoch is persisted to `hbase:meta` only when a `TransitRegionStateProcedure` is executed, which already performs a `Put` to update the `info:server` and `info:openSeqNum` columns. The epoch is written as one additional column (`info:assignmentEpoch`) in the same row mutation. This adds 8 bytes of value plus the column qualifier overhead to an existing RPC. No additional round trip to `hbase:meta` is required.

Region assignments are infrequent relative to data operations. Even in a highly dynamic cluster performing 1,000 region moves per minute, the additional meta write volume is 1,000 Put mutations per minute, each carrying an extra ~30 bytes (column family, qualifier, timestamp, value). This is negligible compared to the meta write volume from region state transitions, which already produces far more data in the same mutations.

### 7.3 Lock Contention

The `tryLock()` pattern in `checkOnlineRegionsReport()` is preserved. When processing a heartbeat, the master iterates over the reported regions and attempts to acquire each `RegionStateNode`'s lock with `tryLock()`. If the lock is held by an in-flight TRSP or another concurrent heartbeat check, the epoch validation for that region is silently skipped and deferred to the next heartbeat 3 seconds later. This is identical to the existing behavior for server-name comparison and introduces no new contention.

The epoch comparison itself (`reportedEpoch < currentEpoch`) executes under the lock and completes in constant time. It does not extend the critical section beyond what the existing server-name comparison already requires.

### 7.4 Master CPU Overhead

Epoch validation adds one long comparison (`<`) per region per heartbeat in `checkOnlineRegionsReport()`, and one long comparison per state transition in `reportRegionStateTransition()`. For a cluster of 100 RSes, each hosting 1,000 regions, this amounts to 100,000 comparisons every 3 seconds, or roughly 33,000 per second. Each comparison is a single CPU instruction. Even accounting for the surrounding `Map.get()` to retrieve the reported epoch, the total CPU cost is on the order of microseconds per heartbeat.

The `pendingStaleCloses` map introduces `ConcurrentHashMap` operations (`computeIfAbsent`, `get`, `remove`) on the stale-close tracking path. These operations only execute when a stale epoch is actually detected, which is an exceptional condition rather than a steady-state cost. During normal operation with no stale regions, the map is empty and never accessed.

### 7.5 RegionServer Memory Overhead

Each `HRegion` gains a single `volatile long` field (`assignmentEpoch`), consuming 8 bytes per region. For an RS hosting 1,000 regions, this adds 8 KB of heap usage. For an RS hosting 10,000 regions (a high-end configuration), this adds 80 KB. Both figures are negligible relative to the RS's heap, which is typically configured in the range of 16-64 GB.

### 7.6 Master Memory Overhead

Each `RegionStateNode` gains a single `long` field (`assignmentEpoch`), consuming 8 bytes per region. For a cluster with 1,000,000 regions, this adds 8 MB of heap usage on the master. The `pendingStaleCloses` map is empty during normal operation and grows only when stale regions are detected. Each entry consumes approximately 200 bytes (the `Pair` key plus the `StaleCloseTracker` fields). Even in a pathological scenario with 10,000 simultaneously stale regions, the map consumes roughly 2 MB.

### 7.7 Data Path Independence

Epoch fencing has no involvement in the client read/write path.

The only RS code paths that touch the epoch are: (a) storing it when a region is opened (`AssignRegionHandler`), (b) including it in the heartbeat `RegionLoad` (once every 3 seconds), (c) including it in `reportRegionStateTransition` (once per state change), and (d) checking it when processing an epoch-aware `CloseRegionRequest` (an exceptional event). None of these paths are in the per-request data flow.

## 8. Testing Strategy

### 8.1 Unit Tests

Unit tests for `RegionStateNode` verify that the epoch increments correctly on each assignment, that the incremented value is persisted to `hbase:meta` in the same mutation as `info:server` and `info:openSeqNum`, and that epoch comparison correctly identifies stale values (reported epoch less than current epoch) while accepting current values.

Unit tests for `TransitRegionStateProcedure` verify that the epoch is incremented on initial assignment, on reassignment to a different RS, and on region move operations. They also verify the early-out behavior: when a TRSP resumes and discovers its epoch has been superseded, it returns `Flow.NO_MORE_STATE` without dispatching to the RS.

Unit tests for `AssignmentManager.checkOnlineRegionsReport()` verify that a heartbeat carrying a stale epoch triggers a call to `closeStaleRegion()`, that a heartbeat carrying an epoch greater than the master's current epoch logs an ERROR indicating possible state corruption, that the dedup tracker prevents redundant close RPCs within the backoff window, and that the SCP escalation fires after the configured threshold of close attempts regardless of RPC outcome.

Unit tests for `AssignmentManager.reportRegionStateTransition()` verify that a transition report with a stale epoch is rejected with `TRANSITION_REJECTED_STALE_EPOCH`, that a transition with an epoch greater than the master's current epoch logs an ERROR indicating possible state corruption, that a transition with the current epoch is accepted normally, and that a report from an old RS whose `RegionStateTransition` lacks the `assignmentEpoch` field (detected via `hasAssignmentEpoch()` returning false) bypasses the epoch comparison entirely and falls back to the existing server-name comparison.

### 8.2 Integration Tests

`testEpochFencingDoubleAssignPrevention` assigns a region to RS-A, then reassigns it to RS-B while RS-A still holds the region open. The test verifies that RS-A's next heartbeat is detected as stale by the master's epoch comparison, that the master sends an epoch-aware close RPC to RS-A, and that RS-A processes the close and stops serving the region.

`testEpochFencingMasterFailover` assigns a region with a known epoch, kills the active master, and restarts it. After the new master initializes, the test verifies that the epoch was loaded from `hbase:meta` and that heartbeat validation continues correctly: a stale RS is still detected and fenced even though the master is new.

`testEpochFencingRollingUpgrade` runs a mixed cluster with some RSes that include the epoch in their heartbeats and some that do not (simulating old RSes). The test verifies that old RSes are handled via the existing server-name-only comparison with no regression, and that new RSes benefit from epoch validation.

`testEpochFencingRSSelfFence` introduces an artificial delay in RS-A's OPENED report so that it arrives after RS-B has already opened the region and reported OPENED with a higher epoch. The test verifies that the master rejects RS-A's stale transition with `TRANSITION_REJECTED_STALE_EPOCH` and that RS-A responds by closing the region locally.

### 8.3 HBCK2 / hbase-operator-tools Tests

These tests live in the hbase-operator-tools repository alongside the existing HBCK2 integration tests. They run against a MiniHBaseCluster and exercise the new `Hbck` admin API methods and CLI commands introduced in Section 5.5.

`testGetAssignmentEpoch` assigns a region to an RS, reads the epoch via HBCK2's `getAssignmentEpoch` command, and verifies the returned value matches the `info:assignmentEpoch` column written to `hbase:meta` by the TRSP. The test then reassigns the region, calls `getAssignmentEpoch` again, and verifies the epoch has incremented.

`testSetAssignmentEpoch` manually sets a region's epoch to a value higher than the current epoch via HBCK2's `setAssignmentEpoch` command, verifies that the master's in-memory `RegionStateNode` reflects the new value, and verifies that a heartbeat from the RS carrying the old epoch is detected as stale and triggers an epoch-aware close.

`testSetAssignmentEpochReconciliation` artificially desynchronizes the master's in-memory epoch from `hbase:meta` by directly writing a higher epoch to the `info:assignmentEpoch` column (simulating the "Master epoch state desynchronized from meta" failure mode from Section 9.5). The test verifies the master logs an ERROR on the next heartbeat, then uses `setAssignmentEpoch` to update both meta and the master's in-memory state. It verifies that subsequent heartbeat validation uses the corrected epoch.

`testSetAssignmentEpochLowerValueWarning` calls `setAssignmentEpoch` with a value lower than the current persisted epoch and verifies that the command emits a warning but succeeds, allowing operators to intentionally reset an epoch if needed.

`testGetSetMetaEpoch` reads the meta region's epoch via `getMetaEpoch`, verifies it matches the value in the `/hbase/meta-assignment-epoch` ZK znode, writes a new value via `setMetaEpoch`, and verifies the ZK znode is updated. After a master restart, the test verifies the new master loads the modified epoch from ZK.

`testReportStaleRegions` creates a stale region condition by assigning a region to RS-B while RS-A still holds it open (using a delayed close injection), calls `reportStaleRegions`, and verifies the report contains the stale region with the correct stale server, reported epoch, current epoch, and close attempt count. After the stale region is successfully closed, the test calls `reportStaleRegions` again and verifies the report is empty.

`testReportStaleRegionsEmpty` calls `reportStaleRegions` on a healthy cluster with no stale regions and verifies an empty report is returned without error.

`testAssignsReportsEpoch` uses the HBCK2 `assigns` command to assign a region and verifies that the command output includes the newly assigned epoch value alongside the procedure ID.

## 9. Availability Impact Assessment

This section analyzes how epoch fencing affects cluster availability, both steady-state improvements and transient disruptions introduced by the fencing mechanism itself. The goal is to give operators a clear picture of how the design changes the availability equation so they can tune thresholds and plan rollouts accordingly.

### 9.1 Steady-State Availability Improvements

Epoch fencing's primary availability benefit is reducing the time regions spend in indeterminate states. Today, a region stuck in `FAILED_OPEN`, `FAILED_CLOSE`, or silently double-assigned can remain unavailable (or silently corrupted) until an operator notices and runs HBCK2. The detection window is bounded only by human attention and monitoring thresholds, often minutes to hours.

With epoch fencing, the detection-to-resolution timeline collapses to the heartbeat interval:

| Scenario | Without Epoch Fencing | With Epoch Fencing |
| :---- | :---- | :---- |
| Stale RS serving zombie region | Indefinite | 3-6 seconds |
| Stale RS reports OPENED | Accepted (double-assign risk) | Rejected |
| `FAILED_OPEN` region stuck | Indefinite (manual) | Auto-retry (Section 10.1.1) |
| `FAILED_CLOSE` ambiguity | Indefinite (manual) | Resolved (Section 10.1.2) |

The cumulative effect is a measurable reduction in region unavailability minutes. For a 500-RS cluster experiencing one major incident that leaves 50-100 regions in indeterminate states, the difference between hours of manual intervention and seconds of automated resolution is on the order of hundreds of region-minutes of recovered availability.

### 9.2 Transient Unavailability During Fencing Actions

Epoch fencing introduces three forms of transient unavailability. Each is brief, bounded, and affects only the fenced region, not the broader cluster.

#### 9.2.1 Write Freeze Window

When the RS receives an epoch-aware `CloseRegionRequest` (Section 5.4.6), it calls `region.setClosing()` to immediately stop accepting writes before beginning the full close sequence. This creates a write-unavailability window for clients targeting that specific region. The freeze begins the instant the RS processes the close request and ends when the region is fully re-opened on the correct RS.

In the best case (fast close, fast re-open on the new RS), this window is 1-3 seconds. In the worst case (slow flush, large memstore, slow HDFS), the close itself can take 10-30 seconds, but the region was already being served by the *wrong* RS. The alternative (no fencing) is silent data corruption, not availability. The write freeze is converting a correctness problem into a brief availability problem, which is the right trade-off.

Read availability is unaffected during this window for the *correct* RS (RS-B), which continues serving the region normally. Clients with stale region location caches pointing to the stale RS (RS-A) will experience read failures, but these clients would eventually fail anyway once the stale RS closes.

#### 9.2.2 Region Transition Gap

Between the time a stale region is closed on RS-A and the region is fully open on RS-B, clients may experience `NotServingRegionException`. This gap already exists in every region move; epoch fencing does not introduce it. However, epoch fencing may *trigger* this gap more aggressively than the current best-effort `closeRegionSilently()` because it actually succeeds in closing the stale region, whereas the current mechanism often fails to close it, masking the gap by allowing the stale RS to continue serving.

The HBase client retry logic (configured via `hbase.client.retries.number`, default 15, and `hbase.client.pause`, default 100ms) handles this gap transparently for most workloads. Clients will retry and discover the new region location via `hbase:meta` within a few retry cycles.

#### 9.2.3 Self-Fencing after Stale Transition Rejection

When `reportRegionStateTransition` rejects a stale-epoch OPENED report (Scenario B, Section 4.3), the RS closes the region locally. During the close, any in-flight requests to that region on the stale RS will fail. However, the correct RS (RS-B) is already serving the region, so properly routed clients are unaffected. Only clients with stale location caches pointing to the old RS experience transient errors.

### 9.3 SCP Escalation Blast Radius

The most significant availability risk in this design is SCP escalation (Section 5.3.5). If an RS is deemed uncooperative after exhausting close attempts, the master calls `expireServer()`, which kills the server and triggers crash recovery for **all** regions on that server, not just the stale one. A single stale region could therefore cause unavailability for hundreds of co-located regions.

This risk is mitigated by several design choices:

1. **High default threshold.** The SCP escalation threshold (default: 20 attempts with exponential backoff, roughly 8 minutes) provides substantial runway for transient issues to resolve. A GC pause, brief network blip, or temporary RPC overload will almost certainly resolve within this window.

2. **Operator-tunable.** The threshold is controlled by `hbase.assignment.stale.epoch.scp.threshold` and can be set to 0 to disable escalation entirely. Operators in environments where false-positive RS kills are more costly than stale region service (e.g., small clusters where losing one RS impacts a large fraction of regions) should increase the threshold or disable it.

3. **Precedented pattern.** SCP escalation after failed RPCs already exists in `RSProcedureDispatcher.scheduleForRetry()`. Epoch fencing adds one more trigger condition but does not introduce a new blast-radius pattern.

4. **Metrics visibility.** The `staleEpochCloseFailures` metric provides early warning. Operators can alert on this metric and intervene manually before escalation fires, if they prefer.

The following table summarizes the worst-case blast radius for each fencing action:

| Action | Scope | Duration | Blast Radius |
| :---- | :---- | :---- | :---- |
| Epoch-aware close | Single region, stale RS | 1-30 seconds | One region |
| Self-fence (transition) | Single region, stale RS | 1-5 seconds | One region |
| SCP escalation | All regions, RS | 30-120 seconds | All regions |

### 9.4 Rolling Upgrade Availability Window

During a rolling upgrade (Section 6.1), epoch fencing is effectively disabled. This means the cluster operates with exactly the same availability characteristics as today, no improvement, but also no regression. There are two specific considerations:

* **Partial-upgrade false sense of safety.** If operators assume epoch fencing is active before all nodes are upgraded, they may disable manual monitoring prematurely. The `staleEpochDetected` metric will read zero during partial upgrade, which could be misinterpreted as "no stale regions" rather than "fencing not yet active." Operators should verify that all RSes are reporting epochs (visible via the per-RS `reportedEpochCount` metric) before relying on epoch fencing for automated recovery.

* **Meta migration latency.** The one-time migration that backfills `info:assignmentEpoch` for existing regions (Section 6.2) runs during master startup. For clusters with millions of regions in `hbase:meta`, this adds to the master initialization time. However, the migration piggybacks on the existing meta scan that populates `RegionStates`. It adds one column read per region, which is negligible compared to the overall initialization cost.

### 9.5 Failure Mode Analysis

The following failure modes are specific to epoch fencing. For each, we describe the cause, the availability impact, and the mitigation that bounds the risk.

**False stale detection.** A bug in the epoch comparison logic could cause the master to conclude that a legitimately assigned region is stale and issue a close to the RS that rightfully owns it. The affected region would experience brief unavailability while it is closed and then re-opened (either on the same RS or another). This is the most consequential failure mode because it converts a healthy serving state into an unnecessary disruption. The risk is mitigated by the simplicity of the comparison itself: it is a single `<` on a monotonic counter, with no complex state machine or multi-step logic that could harbor subtle bugs. Unit and integration tests (Section 8) cover all comparison paths, including edge cases such as equal epochs and absent epochs (old RSes that predate epoch fencing). The RS provides a second layer of defense: when it receives an epoch-aware `CloseRegionRequest`, it validates the `stale_epoch` field against its own locally stored epoch before honoring the close. If the epochs do not match, the RS ignores the request.

**Master epoch state desynchronized from meta.** If a bug causes the master's in-memory epoch for a region to fall behind the value persisted in `hbase:meta` (e.g., a missed update during TRSP execution, a meta row updated by HBCK2 without the master's knowledge), an RS could report an epoch that is greater than what the master believes is current. While `reportedEpoch > currentEpoch` should be impossible in normal operation, the `checkOnlineRegionsReport()` and `reportRegionStateTransition()` methods explicitly check for this condition and log an ERROR identifying the region, the RS, and both epoch values. No automated corrective action is taken because the correct response depends on which value is authoritative. The ERROR log provides the diagnostic signal for operators to investigate whether the master's state or `hbase:meta` is the source of truth, and to reconcile them via HBCK2 if necessary.

**Epoch lost on master failover.** If the epoch fails to persist to `hbase:meta` during a TRSP, the new master after a failover would read a stale epoch and fail to detect a stale RS. This cannot happen because the epoch is written as part of the same atomic `Put` that records `info:server` and `info:openSeqNum`. If the Put fails, the entire assignment fails and the TRSP retries from the beginning, including re-incrementing the epoch. There is no scenario in the current code where the server assignment column is written successfully but the epoch column is not.

**Unbounded growth of the `pendingStaleCloses` map.** If RSes repeatedly fail to honor close requests and SCP escalation is disabled (threshold set to 0), the tracking map in the master could accumulate entries. Each entry is keyed by `(ServerName, encodedRegionName)` and consumes approximately 200 bytes. Entries are cleaned up through three paths: (a) per-region removal when the stale region disappears from the RS's heartbeat, (b) bulk removal via `purgeStaleClosesForServer()` when `ServerCrashProcedure` runs for a dead or SCP-killed RS, and (c) bulk removal when `expireServer()` is called for SCP escalation. Path (b) is critical because a crashed RS stops sending heartbeats, which means path (a) would never fire for its entries. Even in a pathological scenario with 10,000 stuck entries, the total memory consumption is roughly 2 MB, which is negligible relative to the master's heap. The `staleEpochCloseFailures` metric provides visibility into this condition, allowing operators to investigate before the map grows large.

**Epoch-aware close RPC fails due to network partition.** If a network partition prevents the master from reaching the stale RS, the close RPC fails and the stale region continues serving until the partition heals or SCP fires. The exponential backoff retries (Section 5.3.4) continue accumulating attempts toward the SCP threshold. During a partition, heartbeats from the stale RS also stop arriving at the master, so the existing ZK session expiry mechanism will trigger SCP independently if the partition persists beyond the ZooKeeper session timeout (default: 90 seconds). In practice, this means the partition scenario converges on SCP through two independent paths: epoch fencing escalation and ZK session expiry, whichever fires first.

**Clock skew affects backoff calculation.** NTP drift on the master could cause the exponential backoff between close retries to be slightly too aggressive or too conservative, sending close RPCs earlier or later than intended. This is a minor concern because the backoff calculation uses `EnvironmentEdgeManager.currentTime()`, which is monotonic within a single JVM. Cross-node clock skew is irrelevant because all backoff timing decisions are made entirely on the master; no timestamp comparison between nodes is involved.

**Pre-assignment fencing step blocks indefinitely.** If the old RS is unresponsive (e.g., hung JVM, sustained network partition) and SCP escalation is disabled (threshold set to 0), the TRSP's pre-assignment fencing step (Section 5.3.2) could block until the fencing timeout expires, leaving the region unavailable for the duration. The fencing timeout (`hbase.assignment.pre.fencing.timeout.ms`, default: 10 minutes) bounds this window. When the timeout expires, the TRSP fails the assignment and the region remains in its current state (e.g., `FAILED_CLOSE`), preserving the existing behavior of requiring operator intervention. This is a deliberate safety trade-off: unbounded blocking on a single region is preferable to silent data loss from an unsafe concurrent assignment, but the timeout ensures it does not block the procedure executor indefinitely. Operators who are comfortable with SCP escalation (the default configuration) will rarely encounter this failure mode because SCP will physically fence the unresponsive RS well before the fencing timeout expires.

### 9.6 Net Availability Assessment

**Epoch fencing is a net positive for cluster availability.** The design converts unbounded unavailability windows (regions stuck in indeterminate states until manual intervention) into bounded ones (seconds to minutes of automated resolution). The only scenario where epoch fencing *reduces* availability is a false-positive SCP escalation that kills a healthy RS due to a bug in the epoch comparison logic, a risk mitigated by the simplicity of the comparison (monotonic `<`), the two-layer validation (master-side and RS-side), and the high default escalation threshold.

For most production clusters, the availability improvement from automated fencing far outweighs the SCP escalation risk, though the calculus depends on cluster size and failure characteristics.

In the largest clusters with 500 or more RegionServers, SCP escalation for a single RS affects 0.2% or less of total cluster capacity. The regions hosted by that RS are temporarily unavailable during WAL splitting and reassignment, but the rest of the cluster continues serving normally. The risk of losing one RS is negligible relative to the benefit of automated stale region resolution across hundreds of RSes, where manual intervention at scale is slow, error-prone, and often delayed by hours.

In medium-sized clusters of around 100 RegionServers, SCP escalation removes roughly 1% of cluster capacity. This is still a modest impact for most workloads, and the automated resolution of stale regions across 100 RSes provides substantial operational value. The default SCP threshold of 20 attempts with exponential backoff (roughly 8 minutes of sustained non-cooperation) is well suited to this tier, providing ample time for transient issues to resolve before escalation fires.

In small clusters of around 20 RegionServers, SCP escalation is more impactful because a single RS represents 5% of cluster capacity. While this is not catastrophic, operators running small clusters may want to increase the SCP threshold beyond the default of 20 attempts to provide additional runway for transient failures, or disable escalation entirely by setting the threshold to 0, relying instead on the epoch-aware close path alone to fence stale regions without killing the RS.

In environments with frequent gray failures such as long GC pauses, unreliable networks, or overloaded RPC queues, the exponential backoff and high default threshold are specifically designed to avoid compounding a transient issue with a self-inflicted hard failure. Operators in these environments should monitor the `staleEpochCloseFailures` metric closely and tune the SCP threshold based on their observed false-positive rate. If close attempts routinely fail due to transient conditions but eventually succeed, the threshold should be high enough to accommodate the typical recovery time.

## 10. Future Work

### 10.1 Automated Recovery

All automated recovery paths in this section are subject to the pre-assignment blocking invariant (Section 1.2.1): the master MUST NOT dispatch an `OpenRegionRequest` to a new RS until the previous assignment holder is confirmed fenced.

#### 10.1.1 Auto-Retry FAILED_OPEN Regions

Refer to [RFC: Auto-Recovery for FAILED_OPEN State](https://docs.google.com/document/d/1qp8GCvQMvlNeXO_BLgirJWUa2Gfm4gIl9MCtzGwZ1Vw/edit?tab=t.0)

#### 10.1.2 Auto-Resolve FAILED_CLOSE Regions

Refer to [RFC: Auto-Recovery for FAILED_CLOSE State](https://docs.google.com/document/d/1oP-DUpnMvog0qlASL4JgTtZ-_wCtWp-DGNVuy3BACkQ/edit?tab=t.0)

### 10.2 Pluggable RS Fencing Interface

For cases where the RS is unreachable (network partition, hung process), epoch fencing via heartbeat is insufficient because heartbeats stop. A pluggable `RegionServerFencer` interface could provide a callout to automated deployment- specific fencing:

```java
public interface RegionServerFencer {  
  /** Returns true if the RS is confirmed dead/isolated. */  
  boolean fence(ServerName serverName) throws IOException;  
}
```

Implementations could include cloud API instance termination, IPMI power-off, or HDFS lease revocation.
