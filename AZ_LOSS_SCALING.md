# Surviving an AZ Loss at Scale

## Scope

This document evaluates three promotable-replica designs ([Promotable Consistent Replica Set](PROMOTABLE_CONSISTENT_REPLICA_SET.md), [Promotable Timeline-Consistent Replicas](PROMOTABLE_TIMELINE_CONSISTENT_REPLICAS.md), and [RAFT-Based Promotable Region Replicas](RAFT_REGION_REPLICAS.md)) under correlated failure of an entire availability zone (AZ). The working target is a 500-RegionServer cluster split across three AZs, where losing an AZ removes ~167 RegionServers and exposes hundreds of thousands of regions to simultaneous promotion. The goal is to identify where HMaster and `hbase:meta` become bottlenecks and list the options for keeping them off the critical path, even with master- and region-server-side provisioning already scaled up.

The deferred recovery model in this document is designed for *single-AZ failure*. The per design quorum/ISR/membership semantics define cluster behavior under loss of more than one AZ but are not analyzed here. The model also presumes uniform RF=3 across all tables. This statement is the operator-facing companion to the scope statement in [TUNING_FOR_RECOVERY_WITH_DEFERRAL.html §1](TUNING_FOR_RECOVERY_WITH_DEFERRAL.html#abstract).

## The Workload at AZ Loss

For the working cluster (500 RegionServers, 1,500 primary regions per RS, replica count 3, AZ-affinity placement):

| Quantity                                                    | Value          |
| ----------------------------------------------------------- | -------------- |
| Total primary regions (cluster-wide)                        | 750,000        |
| Total region replicas (RF=3)                                | 2,250,000      |
| RegionServers per AZ                                        | ~167           |
| Region replicas hosted per RS                               | ~4,500 (1,500 primary + ~3,000 secondary) |
| Primary regions hosted per AZ                               | ~250,500       |
| Secondary replicas hosted per AZ                            | ~499,500       |
| Total replicas hosted per AZ (primary + secondary)          | ~750,000       |
| Surviving RegionServers after AZ loss                       | ~333           |
| Regions to promote (primary was in dead AZ)                 | ~250,500       |
| Regions losing only a secondary (primary survives in another AZ) | ~499,500  |
| Regions whose ISR shrinks (any replica in dead AZ)          | ~750,000       |
| Replica slots suppressed during outage (deferred until AZ return) | ~750,000 |
| WAL files to fence (~3 per surviving primary on dead RSs)   | ~500–1,000     |
| `ServerCrashProcedure` instances spawned at once            | ~167           |
| RegionServers in `hbase:meta`'s replica group post-AZ-loss  | 2 of 3         |

Today's `ServerCrashProcedure` is tuned for losing a handful of RegionServers, not ~167 at once, each carrying ~1,500 primaries and ~3,000 secondary replicas. The new promotion path needs to be sized for the same correlated event. Secondary-only losses, regions whose primary survives in another AZ but whose dead-AZ replica is gone, are roughly twice as numerous as primary losses (~499,500 vs ~250,500). Re-creating those secondary slots immediately would concentrate two AZs' worth of replica-placement load onto the surviving RegionServers and HDFS at the same moment that promotion is consuming the same resources.

Of the ~750,000 suppressed slots in the table above, ~250,000 are handled in-place by primary promotion (no surviving-RS bootstrap is required: a surviving secondary takes over) and ~500,000 are secondary slots whose replacement is deferred until AZ return. Both classes avoid recovery-window bootstraps on surviving RSs.

This analysis therefore assumes the cluster runs in *Partial Recovery Deferral mode* while the AZ is offline, in which dead-AZ replica slots are *suppressed*, not replaced, until the AZ returns to service:
- **ISR** marks the ~499,500 dead-AZ entries in `info:isr` as suppressed via master-coalesced multi-row META mutations. The ~3,000 batched mutations clear inside the master's normal recovery window. Surviving primaries skip the dead-AZ replica on the commit barrier (with `min.insync.replicas` lowered for the duration of the deferral window) without competing with the promotion-time META writes.
- **Catch-up** parks ~499,500 streaming tail-source subscriptions on the surviving primaries rather than tearing them down. No catch-up re-establishment happens during the recovery window. HDFS fan-in on the surviving DataNodes is freed up for promotion-time WAL reads.
- **RAFT** marks the ~499,500 dead-AZ members in each group's local configuration view as suppressed, so the consensus layer stops sending `AppendEntries` to them, ignores any stale votes, and does not count them toward quorum. With RF=3 and one AZ suppressed, the surviving two members form full quorum (2 of 2 alive). Suppression is a configuration-bit toggle derived from the CFD's published blacklist of failed domains rather than a committed log entry per group.

The ~500,000 secondary-replacement operations and any rejoin work for the formerly-suppressed primary slots are scheduled when the CFD signals that the failed domain has returned, paced by a recovery-throttle governor and following the standard bootstrap path. Those operations land on a healthy cluster and are no longer stacked on top of the promotion storm.

The META load is the other dominant input. Every promotion writes META, every client write that observed the old primary refetches META, and META is itself a region whose group must survive the same AZ loss.

## Partial Recovery Deferral Assumption

The recovery analysis throughout this document assumes Partial Recovery Deferral mode for the duration of the AZ outage. The cluster does not attempt to re-create the ~750,000 dead-AZ replica slots while the AZ is offline. Instead the slots are marked suppressed, surviving members continue serving from a smaller in-sync (ISR) or quorum (RAFT) set, and replacement bootstraps are deferred until the AZ returns to service.

### Deferral signal

The deferral signal is the master-hosted Correlated Failure Detector's published blacklist of failed domains, observed identically by the master and every RegionServer. The CFD's design — including its trip thresholds, recovery-confirm window, persistence layer, and operator interface — lives in [CORRELATED_FAILURE_DETECTOR.md](CORRELATED_FAILURE_DETECTOR.md), which is also the canonical source for the `hbase.dead.rack.*` configuration keys cited in this document's recommended-configuration section.

### Behavior under deferral

While at least one domain is on the CFD's blacklist, all three designs apply the same five rules. Each design implements the rules in its own way (see the per-design documents):

1. **Dead-AZ replica slots are not assigned.** `SERVER_CRASH_ASSIGN`'s replica-bootstrap path consults the CFD's published blacklist and skips any slot whose target domain is on the blacklist. The skipped slots are reconciled by the per design recovery action's disengage step at AZ return; no separate master-side journal is needed.
2. **Surviving members continue serving with one fewer member.** ISR drops `min.insync.replicas` by the blacklisted-domain count for the duration. RAFT recomputes majority over non-suppressed members only (RF=3 minus one blacklisted AZ = 2 of 2 majority). Catch up does not have a commit barrier and continues unchanged on the write path.
3. **Promotion candidates are restricted to non-blacklisted domains.** Candidate selection routines in all three designs filter suppressed AZ replicas out of the candidate set before ranking. With AZ-affinity placement and RF=3, every region under single AZ deferral has two live candidates.
4. **Dead-AZ replicas do not rejoin while suppressed.** A revived RegionServer in a blacklisted domain that happens to come back early (e.g., transient network partition) is not allowed to rejoin its replica groups until the CFD removes the domain from its blacklist. This prevents flapping during a partial AZ recovery.
5. **META follows the same rules.** Multi-writer META (described under "META Under Stress") composes naturally with deferral. META's dead-AZ replica is suppressed.

### AZ return: rejoin vs replace

When the CFD removes the failed domain from its blacklist, the per design recovery action's disengage step iterates regions whose member set has any peer in the formerly blacklisted domain and decides between *rejoin* and *replace* per region:

- **Rejoin** returns the original replica to active membership. ISR moves the entry from the suppressed slot of `info:isr` to the active set. The replica resyncs through the bootstrap path. Catch up clears the dormant flag and the surviving primary resumes push from the persisted position. RAFT clears the suppressed bit in the local configuration view. The leader's next heartbeat brings the rejoined peer up to date via `AppendEntries` or `InstallSnapshot`.
- **Replace** assigns a fresh RegionServer in the formerly blacklisted domain and bootstraps a new replica from scratch.

A recovery throttle (`hbase.replica.deferred.recovery.max.in.flight`, default 1000) bounds concurrent rejoin-or-replace operations. With ~500,000 deferred secondary replacement bootstraps and a cap of 1000, the rebalance takes ~8 minutes at a 1 s per region wall clock, well outside the recovery critical window.

### Why deferral and not eager replacement with throttling

A naïve alternative is to keep the eager replacement design and rely on a throttle to spread its cost. This fails because throttling reduces peak QPS but does not move the sum of the work out of the recovery critical window. Inevitably the system overloads. Eager replacement recreates the ~499,500 secondary slots on surviving AZs, overloading those RSs and committing the cluster to a topology that the balancer will then have to undo when the AZ returns. Deferral avoids the placement churn entirely.

The rest of this document evaluates the three designs against this assumption.

## Baseline Operational Parameters

The analysis throughout this document assumes the operational parameters below are adopted as a baseline. They are the outcome of the queueing analysis using Little's Law per stage, closed-loop throughput for the procedure store, and `M/G^[N]/1` bulk-service utilization bound for sustained operation, applied to the reference workload of 500 RegionServers, 1,500 regions/RS, AZ loss = 1/3, ~250,000 region transitions, ~1.5M total procedure state transitions.

### Master-side parameters (assumed in effect)

| Parameter | Value | Role |
| --- | --- | --- |
| `hbase.master.procedure.threads` | 512 | Procedure-store closed-loop throughput at `μ_proc ≈ 25.6K w/s` |
| `hbase.master.executor.serverops.threads` | 256 | Absorbs the ~167-event SCP burst without queuing |
| `hbase.master.executor.openregion.threads` | 256 | Little's Law on inbound OPEN reports against META write rate |
| `hbase.master.executor.closeregion.threads` | 256 | Symmetric to `openregion`; avoids balancer-driven stalls |
| `hbase.master.executor.meta.openregion.threads` | 16 | META open events isolated from user-region traffic |
| `hbase.procedure.remote.dispatcher.threadpool.size` | 512 | One worker per surviving RS plus retry headroom |
| `hbase.procedure.remote.dispatcher.delay.msec` | 50 | Dispatcher dwell tax `d · t_TRSP` bounded |
| `hbase.procedure.remote.dispatcher.max.queue.size` | 256 | Batch flush size at the dispatcher |
| `hbase.procedure.store.wal.sync.wait.msec` | 10 | Procedure-store closed-loop cycle time `w + h ≈ 20 ms` |
| `hbase.regionserver.metahandler.count` (master) | 256 | Little's Law on inbound priority RPCs |
| `hbase.master.rpc.handler.count` | 400 | Headroom for non-priority master RPCs during recovery |

### RegionServer-side parameters (assumed in effect)

| Parameter | Value | Role |
| --- | --- | --- |
| `hbase.regionserver.executor.openregion.threads` | 32 | Matches master throughput at `W_open ≈ 1 s`; bounded above by NameNode capacity |
| `hbase.regionserver.executor.closeregion.threads` | 32 | Symmetric to `openregion` |
| `hbase.regionserver.executor.openpriorityregion.threads` | 8 | System-region opens (META + sysregions) isolated |
| `hbase.regionserver.handler.count` | 200 (400 on META-hosting RSs) | Inbound RPC headroom for the elevated master dispatch rate |
| `hbase.regionserver.wal.max.splitters` | 8 | Cluster-wide split parallelism `P_split = N_alive · K ≈ 2,664` |

### HDFS NameNode capacity (assumed in effect)

The active NameNode runs on `m7g.16xlarge` (64 vCPU, 256 GB DDR5, 25–30 Gbps network) with a ~200 GB JVM heap, `dfs.namenode.fslock.fair = false`, `dfs.namenode.handler.count = 128`, and `ipc.server.read.threadpool.size = 16`. JournalNodes' edit directories sit on NVMe isolated from HBase DataNode block storage. Sustained capacity for read-heavy region-open traffic is `C_NN ≈ 3 × 10^5 op/s`.

### Resulting per-stage drain rates at the baseline

The baseline yields the following per-stage drain rates and recovery bound at the reference workload (typical operating point: `W_open ≈ 1 s`, `ω = 30`, `N_wal/RS = 8`, `W_split = 5 s`):

```
μ_proc        ≈ N_proc / (w + h) = 512 / 20 ms ≈ 25.6K writes/s
T_proc        ≈ W_total / μ_proc ≈ 1.5×10⁶ / 25.6K ≈ 59 s
T_RS-open     ≈ M_TRSP · W_open / (N_alive · T_open) ≈ 250K · 1 / (333 · 32) ≈ 23 s
T_NN          ≈ M_TRSP · ω / C_NN ≈ 250K · 30 / 3×10⁵ ≈ 25 s
T_WAL         ≈ N_dead · N_wal/RS · W_split / (N_alive · K) ≈ 167 · 8 · 5 / (333 · 8) ≈ 2.5 s
T_recovery    ≈ max(T_proc, T_WAL, T_RS-open, T_NN) + T_pipe ≈ 70 s
```

## Where the Master and META Sit on the Critical Path

All three designs share the same outer skeleton:

```
SERVER_CRASH_START
  → SERVER_CRASH_GET_REGIONS               (master META scan)
  → SERVER_CRASH_RENAME_WAL_DIR            (master HDFS rename)
  → promotion phase                        (per region; design specific)
  → SERVER_CRASH_SPLIT_LOGS                (legacy fallback for non-promoted)
  → SERVER_CRASH_DELETE_SPLIT_WALS_DIR
  → SERVER_CRASH_ASSIGN                    (replacement assignment)
  → SERVER_CRASH_CLAIM_REPLICATION_QUEUES
  → SERVER_CRASH_FINISH
```

Every design needs to detect the dead primary, pick a new one, and commit the new primary's identity to META. Where they diverge is in *who* does each step and how much else happens around the META commit.

In the ISR design the master is the coordinator for the entire promotion. The master detects the dead primary through the standard heartbeat path, reads `info:isr` from META to pick a surviving candidate, dispatches a seam RPC to that candidate, performs an atomic META mutation to install it, and dispatches cutover RPCs to every other surviving ISR member. Catch-up to the dead primary's last write is implicit, since the ISR commit barrier guarantees that every surviving ISR member already holds the committed prefix. The master sits on the per-region path for every transition.

The catch-up design is similar but does more work per region. The master detects the dead primary through the same heartbeat path, but candidate selection requires a `GetReplicaTailPositions` fan-out across surviving RSs to learn each replica's current tail position before the master can choose. The chosen candidate then has to catch up by reading the dead primary's WAL from HDFS to its sealed end and replaying the tail into its own WAL, work that runs on the candidate but that the master must orchestrate and wait on. The master then performs the atomic META mutation and dispatches cutover. The master sits on the per-region path for every transition, plus one extra phase (catch-up) relative to ISR.

The RAFT design pushes the most work off the master. Detection of the dead primary is local to the consensus layer. Surviving RAFT group members notice the missing leader via their heartbeat timeout (~1.5 s) and elect a new leader among themselves. Catch-up is implicit, since the RAFT log already covers the committed prefix. The new leader sends a single `ReportLeaderElection` RPC to the master, the master performs the atomic META mutation, and surviving followers already know who the new leader is from the consensus layer, so no cutover fan-out is required. The master is on the per-region path only for the META-commit step.

The catch-up design therefore has the most per-region master work, the RAFT design has the least, and the ISR design sits in between.

In every case the bottleneck under AZ loss is the procedure framework's per-region overhead at the master plus the META region's per-row write throughput on whichever single RegionServer is currently META's primary.

## Common Bottlenecks

These hit all three designs and are the most important to mitigate.

### 1. Procedure executor parallelism

`hbase.master.procedure.threads = 512` (baseline) drains the per-region state-transition workload in roughly one minute. With each procedure thread blocking one cycle per persist at `cycle_time ≈ 20 ms`, the procedure store sustains `μ_proc ≈ 25.6K writes/s`, which clears the ~1.5M total transitions (`~250,500 TRSPs × ~6 transitions + ~167 SCPs × ~9 transitions`) in ~59 s. Any slippage in hflush latency under stress compresses effective throughput regardless of how many workers are configured.

### 2. Procedure store WAL

Every state transition writes to the procedure WAL. Procedure-store writes are batched but the WAL is a single writer and is the durability anchor for procedure replay. At ~250,000 regions × ~6 persisted states each (~6 for ISR, ~9 for catch-up, ~3 for RAFT master-mediated), the procedure store sees 0.75M–2.25M transition writes during recovery, ~1.5M for the running ISR baseline. At the baseline `μ_proc ≈ 25.6K writes/s` (512 procedure threads, `w + h ≈ 20 ms`), the workload drains in ~59 s. The initial SCP fan-out transient briefly offers ~25–50K writes/s for 5–10 s and queues. The queue empties over the subsequent ~60 s. The `M/G^[N]/1` bulk-service utilization analysis bounds the sustainable post-burst arrival rate to `ρ_max · μ_proc ≈ 16.6K w/s`.

### 3. `hbase:meta` as the cluster-wide write hotspot

`hbase:meta` is the cluster's single coordination point. Every promotion writes it once. Every client whose write target was in the dead AZ re-reads it. Today, META exists on a single RegionServer regardless of how many replicas it has (replicas are read-only). 250,000 promotion mutations through a single META RegionServer, even at 10 ms per CheckAndPut, is 2,500 seconds serialized. With pooled handlers and batched mutations the effective floor is still tens of seconds.

The ISR design also touches META on the eviction path at the moment of AZ failure. Every region with a replica in the dead AZ wants the master to remove that replica from `info:isr`. Each affected primary calls `ReportISRChange` on the master through its existing master-bound RPC stub. The master's handler validates each inbound entry against in-memory `RegionStateNode` state, accumulates accepted entries across concurrent inbound calls under a short coalescing window, and commits the batch atomically against META through `MultiRowMutationEndpoint`. The resulting META write rate is bounded by the master's coalescing window and accumulator size: ~750,000 notional change requests at a default batch size of 256 collapse to ~3,000 multi-row mutations.

META's load is examined in depth in "META Under Stress" below.

### 4. Remote procedure dispatcher

`RemoteProcedureDispatcher` batches per-RS dispatches. Each remote procedure routed to the same RS is queued in a `BufferNode` and flushed when either the dwell timer `hbase.procedure.remote.dispatcher.delay.msec = 50` (baseline) elapses or the queue reaches `hbase.procedure.remote.dispatcher.max.queue.size = 256` (baseline). The dispatcher's worker pool is sized at `hbase.procedure.remote.dispatcher.threadpool.size = 512` (baseline), giving one worker per fan-out destination plus retry headroom. This collapses up to 256 per-region RPCs into one wire RPC per RS, but the procedure-level work inside the master is not eliminated. The dispatcher is an RPC-level optimization, not a procedure-level one.

### 5. Client locator cache thrash

When ~250,500 regions change primary in seconds, every client with traffic into the dead AZ invalidates a fraction of its locator cache, refetches from META, and reissues writes. Cumulative META QPS from clients can dwarf the promotion-time META writes. Mitigation via hinted handoff is covered in Option E below.

### 6. RegionServer open executor (the per-RS bottleneck downstream of the master)

After the master persists procedure state and dispatches OPEN RPCs, each surviving RegionServer processes them on its `RS_OPEN_REGION` executor at `hbase.regionserver.executor.openregion.threads = 32` (baseline). At AZ loss, each of ~333 surviving RSs receives roughly ~750 OPEN dispatches (`M_TRSP / N_alive`). Per-region open work is HDFS-bound — tens of NameNode operations plus DataNode reads of HFile footers, indexes, and bloom filters, plus replay of any recovered edits inherited from split WALs — and the per-region wall-clock `W_open` runs from ~200 ms (healthy, well-compacted regions) to ~5 s (stressed, write-heavy, large recovered-edits volumes).

Cluster-wide RS-open throughput is `μ_open^cluster = N_alive · T_open / W_open`. At `T_open = 32` and the typical-stress `W_open ≈ 1 s`, cluster capacity is ~10K opens/s, which clears ~250,000 opens in ~25 s and matches the master's drain rate at the realistic stress point. Above `T_open = 32` the NameNode becomes the bottleneck and `W_open` self-adjusts upward to keep the NN at capacity, with no aggregate throughput gain.

### 7. HDFS NameNode op-rate ceiling

Each region open performs roughly `ω ≈ 30` NameNode operations (one `getFileInfo` plus one `getBlockLocations` per HFile, multiplied across HFiles per column family per region) plus DataNode reads. Across ~250,000 promotions the NameNode absorbs ~7.5M ops in the recovery window. The baseline NameNode (`m7g.16xlarge` per the Baseline Operational Parameters section) sustains `C_NN ≈ 3 × 10⁵ op/s` for read-heavy region-open traffic, giving

```
T_NN = M_TRSP · ω / C_NN = 250,000 · 30 / 3 × 10⁵ ≈ 25 s.
```

`T_NN` sits below the master procedure-store drain time `T_proc ≈ 59 s`, so HDFS is not the system-wide bottleneck at the baseline.

When the active NameNode is itself in the failed AZ, HDFS HA failover to the standby sits on the critical path before HBase recovery can make meaningful progress, because region opens depend on `getFileInfo` / `getBlockLocations` from a healthy NameNode. Community operational experience with production HDFS HA at comparable cluster size places the failover wall-clock at ~90–180 s, dominated by ZKFC fencing and ZK session expiry (~30 s at a standard session timeout), standby promotion and edit-log catch-up (~10–20 s for a current standby), and DataNode block-report quorum to the new active before safe-mode exit (~60–120 s, scaling with total block count). The failover-time contribution is treated separately in "Recovery Time Bounds" below.

### 8. Distributed WAL splitting

Before the regions of a dead RS can be reassigned, the master must split that RS's unarchived WAL set. Each dead RS contributes `N_wal/RS` files (workload-dependent, capped above by `hbase.regionserver.maxlogs ≥ 32`). For each, the master enumerates the splitting directory, creates one `SplitWALProcedure`, and dispatches it as a child `SplitWALRemoteProcedure` to a worker chosen by the `WorkerAssigner`. The worker runs the split on its `RS_LOG_REPLAY_OPS` executor: a sequential HDFS read of the WAL plus 3-replicated writes of per-region `recovered.edits` files. Per-WAL split service time `W_split` is HDFS-bound, ~3 s with healthy DataNodes and up to ~15 s under DataNode contention.

`hbase.regionserver.wal.max.splitters = 8` (baseline) gates two things: the per-RS slot count tracked by the `WorkerAssigner` on the master, and the core pool size of the `RS_LOG_REPLAY_OPS` executor on each RS. Cluster-wide split parallelism is therefore `P_split = N_alive · K = 333 · 8 = 2,664`, and the drain time is

```
T_WAL = N_dead · N_wal/RS · W_split / P_split = N_dead · N_wal/RS · W_split / (N_alive · K).
```

At the baseline `K = 8`:

| `N_wal/RS` | `W_split` | `T_WAL` |
| --- | --- | --- |
| 8 (typical write load)             | 5 s  | ≈ 2.5 s |
| 32 (write-heavy, near `maxlogs`)   | 10 s | ≈ 20 s  |

Each split slot does one sequential read plus a 3-replicated write at sustained ~50–100 MB/s, so eight concurrent slots remain comfortably inside the 25 Gbps NIC envelope of a Graviton3 16xlarge-class instance. `T_WAL` stays well below the master procedure-store drain time across the realistic envelope.

`W_split` is an exogenous, HDFS-bound cost that HBase cannot directly tune. It is reduced indirectly by smaller WAL roll size (more files of smaller `W_split` each, with `T_WAL` unchanged in aggregate) or by holding `N_wal/RS` down via more aggressive flush policy.

WAL splitting and region opens overlap at the cluster level: distinct SCPs run independently, so once an early dead RS finishes splitting its WALs its regions can be assigned and opened while later SCPs are still splitting. Both stages draw on the same surviving DataNodes for HDFS bandwidth, so a higher `T_WAL` inflates the effective `W_open` during the WAL-split window. `T_WAL` enters the recovery max as a parallel stage rather than additively (see "Recovery Time Bounds" below).

## Design-Specific Bottlenecks

### ISR: master-coalesced eviction at AZ failure

The ISR design records ISR membership changes durably in META through `ReportISRChange` RPCs from the acting primary to the master. The commit barrier evaluates `min.insync.replicas` against the in-memory ISR, but the in-memory copy advances only after the master returns `SUCCESS` for the change. At AZ loss, every region whose primary is in a surviving AZ but which has a secondary in the dead AZ enters this loop on the write path:

1. Next write batch's deadline expires without ack from the dead-AZ replica.
2. Primary calls `ReportISRChange` on the master via its existing master-bound RPC stub.
3. Master validates the entry against in-memory `RegionStateNode` and `(server, startCode)`, accumulates it with other concurrently inbound entries, and commits the batch atomically against META through `MultiRowMutationEndpoint` when the coalescing timer (default 20 ms) or size threshold (default 256 entries) fires.
4. Master returns `SUCCESS` for the entry.
5. Primary advances its in-memory ISR.
6. Commit barrier evaluates against the new ISR.
7. Client ack.

In healthy steady state the eviction write is rare. At AZ loss every affected primary issues an eviction `ReportISRChange` in parallel. Concurrent inbound calls land on the master's RPC handler pool and are aggregated by the handler's accumulator. The resulting META write rate is bounded by the coalescing parameters rather than by per-region arrival: ~750,000 notional eviction entries at batch size 256 collapse to ~3,000 multi-row mutations against META, drained well inside the master's normal recovery window without competing with promotion-time writes.

The per-write latency cost is bounded by `master coalesce window + master RTT + META hflush` (≈ 40 ms p50 at defaults), dominated by the coalesce window. The operator can lower the coalesce window for tighter steady-state latency or raise it to absorb larger peak batches under recovery storms.

### Catch-up: catch-up read fan-in against degraded HDFS

The catch-up design's promotion candidate reads the dead primary's WAL from HDFS. The dead primary's WAL is striped across DataNodes preferentially colocated with it in the dead AZ. With AZ-affinity HDFS block placement, the WAL bytes remain recoverable from surviving DataNodes (RF=3 means at most one of three block replicas is in any single AZ), but the surviving DataNodes are under correlated load from ~250,500 candidate readers in parallel. The NameNode also takes correlated load from block-location queries and lease-recovery RPCs.

This is the natural place for the catch-up design to fall back to legacy splitting under load: `BoundedLeaseRecovery` times out, the master routes those regions to the slow path, and the slow path inherits the same fan-in problem. The catch-up design's recovery floor under AZ loss is intrinsically higher than the ISR or RAFT designs' because reading the dead primary's WAL is on its critical path.

### RAFT: leader election storm and election-induced META storm

The RAFT design has neither an ISR membership change path nor a catch-up read. Its AZ-loss exposure is concentrated in two places:

1. **Election storm.** Every region whose RAFT leader was in the dead AZ starts an independent election simultaneously. With ~250,500 such regions, the surviving 333 RegionServers each absorb ~752 concurrent election attempts. Election uses control-lane messages on the consensus transport, which is sized exactly for this. The shared-event-loop multi-RAFT executor and the timing-wheel heartbeat coalescing keep per-message overhead low. Pre-vote and leader stickiness prevent thundering elections from leader-instability and from interfering with each other under normal partition events, but a hard AZ loss removes the prior leader entirely from every affected group, so the elections all genuinely need to fire. The RAFT design's ["Performance and Scalability" section](RAFT_REGION_REPLICAS.md#performance-and-scalability) models this load and shows it is comfortably absorbed by the per-RS consensus capacity.

2. **`ReportLeaderElection` storm at the master.** Once elected, every new leader sends one `ReportLeaderElection` RPC to the master. At ~250,500 elections completing within seconds of each other, the master receives a thundering RPC herd. The master's `LeaderChangeHandler` does very little per RPC (validate term, acquire `RegionStateNode` lock, write META, ack), but the single META write is on the per-region path and serializes through META's primary. This is the bottleneck. With ~250,500 META writes to serialize, the master-mediated path bottoms out at META's write throughput, not at consensus latency.

The RAFT design has the lightest per-region master orchestration of the three (no procedure framework at all on the happy path. The `LeaderChangeHandler` is a direct META update), but it still concentrates all promotion META writes on the single META RegionServer just like the other two designs.

#### RAFT variant: direct self-promotion via META

The RAFT design as written keeps the master as the sole writer of promotion-related META cells. An alternative variant is to let the newly elected RAFT leader write META directly, bypassing the master on the happy path:

1. RAFT election completes, new leader elected (Phase 1).
2. New leader performs a direct CheckAndPut on META, with the previous RAFT term as the CAS guard, writing `info:primaryReplicaId`, `info:server_<N>`, `info:state_<N>`, `info:seqnumDuringOpen_<N>`, and `info:raftTerm`. The CAS rule is "if the cell's RAFT term is `< new_term`, write; else fail." A duplicate or stale self-promotion is rejected by the CAS, so master-replay-style safety is preserved.
3. New leader completes Phase 3 locally and begins serving writes.
4. The master observes the META row change asynchronously via either a META change notification, a periodic META scan, or a `ReportLeaderElection` RPC the new leader sends after the META write has succeeded (now advisory rather than authoritative). The master uses this to update its in-memory `RegionStateNode`. The dead AZ slot is marked suppressed. A replacement replica is scheduled only after the CFD removes the failed domain from its blacklist.

The win is that the master is removed from the critical path entirely. ~250,500 promotion META writes are now issued by ~250,500 different RegionServers in parallel, against whatever META primary is currently elected. Master CPU, master procedure-framework throughput, and master RPC handler count all stop mattering for the AZ-loss case.

The cost is that the master's traditional role as sole arbiter of which region is primary is weakened. The RAFT term, persisted in META and CAS-guarded, takes over the role of the master's bookkeeping for the duration of the recovery window. The master remains the assignment authority for non-RAFT regions and for post-deferral replacement assignments (deferred to AZ return), but the act of installing the new primary is RegionServer-driven.

The two variants are best understood as two endpoints of a spectrum. The master-mediated variant (as written) puts every promotion on the master's critical path. The direct self-promotion variant puts no promotion on the master's critical path. The two variants are otherwise identical, and both write the same META cells in the same sequence. See Option C.2 below for the equivalent variant in the ISR design.

## Options to Mitigate

The options below are stacked from cheapest to most invasive. Realistic AZ-loss resilience needs a combination of them, not any one in isolation. They sit on top of the Baseline Operational Parameters. Pure-tuning options have been absorbed into the baseline.

### Option B. Coalesce per-region procedures into per-RS procedures

Today each promoting region gets its own per-region procedure (`ReplicaPromotionProcedure` for catch-up, equivalent per-region machinery for ISR, and `TransitRegionStateProcedure` with `PRIMARY_PROMOTED` type for the RAFT fallback path). The master persists one record per region, runs one state machine per region, and dispatches one RPC per region.

Refactor so that the unit of work is a `(dead server, candidate RS)` pair, not a region:

1. After `SERVER_CRASH_GET_REGIONS` and `BoundedLeaseRecovery`, the master scans META once for all replication-enabled regions on the dead server and assembles the per-region inputs in memory.
2. Group regions by chosen candidate RS.
3. Spawn one `BatchedReplicaPromotionProcedure` per candidate RS, carrying a list of `(region, ISR or RAFT term, openSeqNum)` tuples.
4. The remote callable on the candidate processes the whole batch. Seam work for every region in the batch in parallel on the candidate's existing worker pool, with a single bookkeeping commit on the candidate before returning.
5. The master performs a single multi-row atomic META mutation per batch (META supports `MutateRowsRequest` on a single region, which META is by definition, so per-RS batches collapse into one batched RPC against META).
6. Cutover (where it exists) fans out as one batched RPC per surviving secondary RS, carrying every region that needs cutover at that secondary.

Result: ~250,500 procedures collapse to ~167 procedures (one per surviving RS that receives at least one promotion). Procedure store load drops by three orders of magnitude. Master state transitions drop by the same factor.

Cost: the batch state machine has new error modes (partial failure: some regions in a batch succeed, others fail), and the per-region rollback path becomes more intricate. Idempotence has to hold at the granularity of an individual region inside the batch, not the batch as a whole.

This is the single largest structural master-side win available and applies equally to all three designs.

### Option C. Reduce META write volume

#### C.1 Batched META mutations

Going hand in hand with Option B, the master performs one atomic multi-row META mutation per (dead server, candidate RS) batch instead of one per region.
`Region#batchMutate`/`MutateRowsRequest` already supports the operation since META is itself one region. This collapses ~250,500 META writes into ~167, and brings META's WAL sync rate and handler contention back to a manageable range.

#### C.2 Direct self-promotion (ISR or RAFT) bypassing the master

In both the ISR and RAFT designs the master is the writer of promotion-related META cells: in the ISR design via the `SERVER_CRASH_PROMOTE_REGIONS` state's atomic mutation, in the RAFT design via the `LeaderChangeHandler`. An alternative is to let the new primary write the promotion META cells directly, bypassing the master on the promotion path:

- **RAFT direct self-promotion** is described above under "RAFT variant: direct self-promotion via META." The new RAFT leader's CheckAndPut against META carries the new RAFT term as the CAS guard. The master is informed asynchronously.
- **ISR direct self-promotion** uses an analogous structure. The surviving ISR members deterministically agree on the candidate by lowest replica ID (the rule the master would have applied anyway). The chosen candidate performs its seam append, then issues a CheckAndPut against META with the previous `info:primaryReplicaId` and `info:isr` as the CAS guard. Only one CAS can succeed. A losing candidate observes the CAS rejection and aborts its own promotion attempt. The master is informed asynchronously by the next periodic reconciliation or by an explicit notification RPC the new primary sends post-CAS. Steady-state `info:isr` writes continue to go through the master via `ReportISRChange`; only the one-shot promotion mutation is bypassed.

In both variants the master observes the META row change asynchronously and updates its in-memory `RegionStateNode`. The master remains the assignment authority for replacement-replica placement (deferred to AZ return under the suppression assumption), balancer decisions, and non-promoted regions.

The win is master-decoupling: ~250,500 promotion META writes are issued by ~250,500 different RegionServers in parallel, against whatever META primary is currently elected. The master is no longer on the per-region path at all.

The cost is that the master's role as sole arbiter of which region is primary is weakened. CAS guards on META cells take over the role the master's procedure framework plays today. Implementation effort is concentrated on:

- A robust CAS protocol for `info:primaryReplicaId` (RAFT-term guarded or ISR-membership guarded).
- A reliable way for the master to observe META row changes (poll, notification RPC, or piggyback on heartbeats).
- A back-off path on the candidate when its CAS fails (it lost the race; reread META and decide whether to do cutover or to abort).

This option pairs naturally with hinted handoff (Option E) and with the multi-writer META extension (described in "META Under Stress" below), since both client write availability and META write throughput become the binding constraints once master orchestration is out of the way.

#### C.3 Procedure-store-based promotion log instead of META

The atomic META mutation that names the new primary is the durable cross-actor signal of promotion. It does not have to land in META on the critical path if the procedure record itself is the durable source.

Risk: clients read META, not the procedure store, so any deferral of the META write is also a deferral of client cache invalidation. The master must still write to META for client locator freshness. This option is listed for completeness but is dominated by Option C.1 (batched mutations) plus Option E (hinted handoff).

### Option D. Pre-positioned candidate selection (catch-up design)

The catch-up design's `GetReplicaTailPositions` fan-out runs at promotion time. With ~333 surviving RSs and ~250,500 regions, the fan-out is bounded by RS count (one RPC per RS, batched per server) and is small in absolute count. But it adds one round-trip to the latency floor and concentrates RPC arrival at the master in a short window.

The tailer metrics from Phase 1 already include per-region last-applied sequence IDs piggybacked on the existing `regionServerReport` heartbeat. With a small extension — make those metrics authoritative enough for candidate selection within their staleness budget — the master can skip the fan-out entirely. Heartbeats arrive on a steady cadence (default 3 seconds), and at AZ failure the master already has, for every surviving RS, the most recent heartbeat's tailer state. The catch-up read on the candidate corrects any residual staleness, so correctness does not depend on heartbeat freshness.

For the ISR design the equivalent already holds: `info:isr` is read from META, which is local to the master.

For the RAFT design the equivalent is automatic: the elected leader is whoever wins the local RAFT election, and the master is told via `ReportLeaderElection`.

### Option E. Hinted handoff via extended `NotServingRegionException`

This option is design-independent and is one of the two most important options for surviving AZ loss in a cluster of this size.

Today, when a client dispatches a write to a RegionServer that is no longer hosting the primary for that region, the RS returns a `NotServingRegionException` (NSRE) with no information about *who* now hosts the region. The client invalidates its locator cache, re-reads META, and retries against whatever META reports. During AZ-loss recovery, hundreds of thousands of clients re-read META in close succession, exactly when META is also absorbing the promotion write storm. META becomes the second-order bottleneck even after master orchestration has been parallelized.

Extend NSRE with an optional hint payload:

```
NotServingRegionException {
  // existing fields ...
  optional ServerName hintedPrimaryServer;
  optional int32 hintedPrimaryReplicaId;
  optional int64 hintedRaftTerm;  // or info:seqnumDuringOpen
  optional bytes hintedAt;        // timestamp the hint was minted
}
```

Three sources of hints, each tied to a specific design's promotion mechanism:

1. **From the new primary itself.** The new primary, having just completed its own promotion, knows authoritatively where it resides. Any NSRE that *it* might generate during the transition window (e.g., for a different region whose primary it is not, or to fence pre-promotion writes against the same region) carries no hint — but it does not need one, since the new primary is accepting client writes.

2. **From the dead AZ's surviving sibling replicas.** A surviving replica on a different AZ, receiving a client write that landed on the wrong server, knows the new primary's identity for one of three reasons:
   - In the RAFT design, the surviving replica is a member of the same RAFT group and knows the elected leader directly from the consensus layer.
   - In the ISR design, the surviving replica was an ISR member and received the `ReplicaCutoverRemoteProcedure` from the master naming the new primary.
   - In the catch-up design, the surviving replica was a tail-source subscriber and received the cutover information from the master.

   In every case the surviving replica's NSRE response carries a hint pointing the client at the new primary, without the client ever touching META.

3. **From any RS that received a `LeaderChangeHandler` / `ReplicaCutoverRemoteProcedure` notification.** Any RS that has been told who the new primary is for a region can answer an NSRE for that region with a hint, even if it does not host any replica of the region. This is mostly relevant for RegionServers that participate in the gossip layer or that hold a shared notification cache.

The client side handles a hinted NSRE by routing the retry directly to the hinted server without consulting META, then validating in the background by reading META on a relaxed timeline. If the hint is stale (the server has since changed again, or the hint is malicious), the retry's response is itself an NSRE — possibly with a fresher hint — and the client repeats. The retry loop converges in O(1) hops in the common case, against O(diameter of cluster) hops in the
adversarial case. META is consulted only on the slow side of the retry loop, not on the fast side.

The freshness window matters. A hint older than the recovery cycle should not be followed. The `hintedAt` timestamp lets clients ignore stale hints. In normal operation hints are minted within seconds of promotion completing and clients act on them within seconds, so the relevant window is on the order of minutes — well-bounded by ordinary clock skew tolerance.

The benefit is concentrated on the AZ-loss tail. Cluster-wide, the extension reduces META QPS from clients during the recovery window from "every client that observed a dead-AZ primary refetches META" to "META is consulted only by clients whose hint chain was broken or stale." For AZ loss in a 500-node cluster, this is the difference between META absorbing tens of millions of locator-refetch reads in seconds and absorbing a long tail of relaxed-timeline reads in minutes.

Hinted handoff is also self-healing in the multi-AZ-loss case. A client whose first hint points it at a server that is itself dead receives the next hint from the next NSRE in the chain, and so on.

### Option F. Promotion bypass via authoritative META

In the ISR and catch-up designs, make META itself the authoritative coordination surface and let RegionServers self-promote (Option C.2 above is the most accessible form). For the RAFT design, the variant labeled "direct self-promotion" in the design discussion is the same idea expressed in RAFT terms. All three designs converge on a similar architecture under maximum optimization: master fences and watches, RegionServers promote and write META under CAS guards.

### Option G. Stagger and throttle promotion

Even with all of the above, doing ~250,500 region promotions concurrently saturates HDFS, the candidate RSs, and META. Both designs already gate per-region fast-path eligibility on `BoundedLeaseRecovery` success per file, which is naturally a staircase as files seal. A new `hbase.promotion.max.in.flight` (default 2000 in a fast cluster, 500 in a conservative one) caps the master-side concurrency of in-flight promotion procedures, with overflow yielding back to the procedure queue. Combined with Option B, this is per-batch not per-region, and the natural unit becomes "in-flight batches" not "in-flight regions".

### Option H. Discipline in SCP yield semantics

A single `ServerCrashProcedure` per dead server is the unit of server-level locking. Lock contention on the server-level exclusive lock serializes all of one dead server's regions through one procedure executor at a time, on state-transition boundaries. If `SERVER_CRASH_PROMOTE_REGIONS` is restructured to spawn its per-region or per-batch children outside its own lock scope (which is largely the case today, since children attach to per-region `RegionStateNode` locks), parallelism is preserved. The point worth checking when implementing is that the parent SCP yields aggressively between state transitions involving its many children, so that procedure-executor workers are not held idle awaiting child completion.

This is implementation discipline rather than a design change, but is called out because it directly determines whether the procedure-executor pool sized in the Baseline Operational Parameters is fully utilized.

## META Under Stress

`hbase:meta` is itself a region. Whatever replica scheme is chosen for user tables applies to META as well. This section walks through how META behaves under AZ loss and what extensions make META survive the storm.

### META under each design, no extensions

In every design, META has a primary on one RegionServer (chosen from the AZ rotation) and read-only replicas on RegionServers in the other AZs. The primary handles all write traffic. The replicas handle TIMELINE reads. Under AZ loss:

- If META's primary is in a surviving AZ: META's primary continues to serve, but its single RegionServer absorbs the entire promotion write storm plus the client locator-refetch read storm.
- If META's primary is in the dead AZ: META itself promotes, following the same rules as user regions:
  - **ISR**: master picks a surviving ISR member of META, performs seam, atomic META commit (which for META targets the `meta-region-server` znode in ZooKeeper, since META cannot write its own primary location to itself), and dispatches cutover. The whole cluster is blocked on META promotion until this completes.
  - **Catch-up**: master picks a candidate, runs catch-up against META's WAL on HDFS, performs seam, persists META's new primary to ZooKeeper, dispatches cutover. Same blocking property.
  - **RAFT**: META's surviving RAFT group elects a new leader independently. The `LeaderChangeHandler` persists META's new primary to ZooKeeper synchronously.

Either way, once META is writable on the surviving side, the META
primary RegionServer is the cluster's single most contended write
target during recovery.

### META load envelope during AZ loss

For the 500-node working cluster, in the worst recovery window:

| Source                                                  | Per cluster                              |
| ------------------------------------------------------- | ---------------------------------------- |
| Promotion META writes (one per region)                  | ~250,500                                 |
| ISR eviction META mutations (master-coalesced via `MultiRowMutationEndpoint`) | ~3,000 (ISR design only; sized by `hbase.master.isr.commit.coalesce.size`) |
| Client locator-refetch META reads (without Option E)    | tens of millions (every client × every cached dead-AZ region) |
| Master-side META scans during SCP                       | ~167 (one per dead RS, bounded)          |
| Heartbeat-piggybacked META reconciliation               | scales with RS count, bounded            |
| Replacement-replica META updates                        | 0 during recovery; ~750,000 paced after AZ return (suppression deferred) |

A single RegionServer absorbing all of this at the moment it is itself recovering from AZ loss is the single largest scalability risk in any of the three designs.

### META-side configuration

```xml
<!-- META gets handler headroom proportional to expected promotion
     RPS during recovery -->
<property>
  <name>hbase.regionserver.handler.count</name>
  <value>400</value>
</property>

<!-- Memstore headroom for the eviction/promotion write burst -->
<property>
  <name>hbase.regionserver.global.memstore.size</name>
  <value>0.5</value>
</property>

<!-- Block cache for the locator-refetch read burst -->
<property>
  <name>hfile.block.cache.size</name>
  <value>0.4</value>
</property>

<!-- Snappy compression on META WAL for higher effective WAL throughput -->
<property>
  <name>hbase.regionserver.wal.compression.type</name>
  <value>SNAPPY</value>
</property>
```

If practical, host META on a dedicated RegionServer with NVMe-backed HDFS DataNodes for its WAL pipeline. Multi-writer META (Option above) relaxes the single-RS bottleneck but still benefits from premium storage on whichever RS is currently META's primary.

## Recovery Time Bounds

The mathematical model for recovery wall-clock time is derived in [TUNING_FOR_RECOVERY_WITH_DEFERRAL.html](TUNING_FOR_RECOVERY_WITH_DEFERRAL.html), which extends [TUNING_FOR_RECOVERY.html](TUNING_FOR_RECOVERY.html) with the effects of active region replicas, the master-hosted CFD, and Partial Recovery Deferral. The numeric envelopes in this section apply that model at this document's reference workload (500 RSs, 1,500 regions/RS, AZ loss = 1/3, RF=3 with AZ-affinity placement). In practice the binding term across realistic operating points is the larger of the master procedure store drain time `T_proc` and the per-RS open executor / HDFS NameNode coupled stages `T_RS-open` / `T_NN`; the `T_pipe ≈ 10 s` SCP fan-out burst is a small additive contribution.

### Recovery-time envelope at the baseline

At the Baseline Operational Parameters (`N_proc = 512`, `w + h ≈ 20 ms` steady state / `w + h ≈ 25 ms` AZ-loss window, `T_open = 32`, `K = 8`), the master procedure store sustains `μ_proc ≈ 25.6K writes/s` at steady state and `μ_proc^AZ ≈ 20.5K writes/s` during the AZ-loss window (derated `0.80×`). Recovery completes inside the single-digit-minute envelope. The table below is evaluated at AZ-loss-window rates, with `T_RS-open` and `T_WAL` inflated by the `1.5×` surviving-DataNode read multiplier from the same section:

| Scenario | `W_open` | (ω, `C_NN`) | (`N_wal/RS`, `W_split`) | `T_proc` | `T_WAL` | `T_RS-open` | `T_NN` | Bottleneck | `T_recovery` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Light HDFS load (well-compacted regions, healthy DataNodes, typical write load) | ≈ 300 ms (1.5× of 200 ms) | (15, 300K op/s) | (8, 7.5 s) | 73 s | ≈ 3.8 s | ≈ 7.5 s | ≈ 13 s | Master procedure store | **≈ 73 s** |
| Typical AZ loss (moderate stress, moderate write load) | ≈ 1.5 s (1.5× of 1 s) | (30, 300K op/s) | (8, 7.5 s) | 73 s | ≈ 3.8 s | ≈ 35 s | ≈ 25 s | Master procedure store | **≈ 73 s** |
| Stressed (write-heavy, large recovered-edits, many HFiles per region) | ≈ 4.5–7.5 s (1.5× of 3–5 s) | (50, 300K op/s) | (32, 15 s) | 73 s | ≈ 30 s | ≈ 105–180 s | ≈ 42 s | RS open executor (DataNode-/replay-bound `W_open`) | **≈ 105–180 s** |

The master procedure store is the dominant remaining term in the light and typical cases. The per-RS open executor (which is HDFS-bound through `W_open` and pays the `1.5×` AZ-window inflation) is the bottleneck in the stressed case. Reducing the typical case further would require dynamic thread-pool sizing for the master procedure executor or reducing `W_open` in the stressed case via more compaction, smaller recovered-edits volumes, and warmer DataNode block caches.

### With NameNode failover

When the active NameNode is in the failed AZ, HDFS HA failover (90–180 s at this cluster size, dominated by DataNode block-report quorum) sits on the critical path before HBase recovery can make meaningful progress:

```
T_recovery (typical, with NN failover) = T_NN-failover + T_recovery (typical)
                                       ≈ 2.5–4.5 min.
```

The lower end corresponds to a fast HA failover followed by a procedure-store-bound HBase recovery in ~73 s. The upper end is a slow block-report quorum (~2 min) followed by a procedure-store-bound recovery in ~110 s at the Catch-up design's ceiling.

### Sensitivity to non-HBase deployment inputs

Four inputs concentrate most of the remaining recovery-time leverage. All four are properties of the deployment rather than HBase parameters, and each carries an operational precondition that must hold for the envelope above to be achievable:

| Input | Value used | Median-deployment value | Operational precondition for the optimistic value |
| --- | --- | --- | --- |
| `μ_proc` (procedure-store throughput) | 25.6K w/s | 12–18K w/s | DataNode pipelines responsive under recovery-storm load; master JVM not GC-pressured (otherwise `h` degrades from 10 ms to 20–40 ms) |
| `C_NN` (NameNode op rate) | 3 × 10^5 op/s | 1–1.5 × 10^5 op/s | NameNode on the baseline instance (`m7g.16xlarge`); no concurrent metadata-heavy foreground load |
| `ω` (NN ops per region open) | 30 | 50–80 | Regions well-compacted: a few HFiles per column family at recovery time |
| `N_wal/RS` (WAL files per dead RS) | 8 | 16–32 | Healthy flush cadence keeps unarchived WAL count near the typical operating range; otherwise approaches `maxlogs` ≈ 32 |

Re-evaluating the typical case at the median-deployment value of each input — and including NameNode failover — yields a recovery wall-clock on the order of **5–8 minutes**. This is the appropriate externally-facing figure when those four preconditions are not directly verified in the deployment. The 70 s and 2.5–4.5 min bounds are the targets that the baseline *enables*; closing the gap from the median to the target is a measurement-and-verification exercise on the inputs, not further HBase parameter tuning.

### Independence from replica-promotion design

The decomposition above applies to all three replica-promotion designs. The promotion mechanism (ISR, catch-up, RAFT) affects what happens during the per-region work (the seam append, the META commit, the cutover fan-out) and shifts the per-TRSP transition count modestly (~6 for ISR, ~9 for catch-up, ~3 for RAFT master-mediated). The per-design `t_TRSP,promote` derivation is in [TUNING_FOR_RECOVERY_WITH_DEFERRAL.html §3](TUNING_FOR_RECOVERY_WITH_DEFERRAL.html#recovery-work) and may be refined at execution-time as the per-design docs are extended. But the master procedure-store drain time, the per-RS open executor capacity, the HDFS NameNode op-rate ceiling, and the WAL-splitting parallelism are common to all three. Structural reductions like per-dead-server or per-candidate-RS batched procedures reduce `W_total` and `M_TRSP` by orders of magnitude.

### Surviving-cohort headroom

Active replicas shift data plane load from the failed AZ onto the surviving cohort at the moment of AZ loss. The surviving cohort must absorb that shift on its existing fleet. In-window scale up is too slow to help.

```
U_safe ≈ 0.44 (ISR), 0.30 (Catch-up), 0.28 (RAFT)   — before margin
```

i.e., baseline per-RS utilization should not exceed ~30% for the read-replica designs and ~40% for ISR, if the cluster is to absorb single-AZ loss without saturating. The cost-to-serve trade off is explicit: smaller safety margin → tighter sizing → less idle capacity, but tighter recovery feasibility; larger margin → safer headroom → more idle cost.

Prefer deferred autoscaling and hold scale up until the CFD's recovery window has closed. This avoids contributing to the recovery storm and provides post recovery sustained capacity.

### Closed-loop reasoning for the procedure store

The closed loop throughput of the master procedure store is `μ_proc = N_proc / (w + h)` where `w` is the procedure-store sync wait, `h` is the hflush latency, and each procedure thread blocks once per persist. With `N_proc = 512` and `w = h = 10 ms`, `μ_proc ≈ 25.6K writes/s` at steady state, which drains the ~1.5M total procedure state transitions in ~58 s. During the AZ loss window, surviving DataNode read amplification inflates `h` to ~15 ms, derating `μ_proc^AZ ≈ 20.5K writes/s` and stretching the drain to ~73 s.

## Recommended Configuration for AZ-Loss-Tolerant 500-Node Clusters

### WAL provider

Use AsyncWAL (`hbase.wal.provider = asyncfs`). Under strict one-replica-per-AZ HDFS placement an AZ loss drops one slot from every active WAL pipeline simultaneously. AsyncFSWAL's single-block-per-file design lets each surviving RegionServer roll onto a fresh width-2 pipeline within a few seconds of the AZ loss, without traversing the chained `DFSOutputStream` / `ReplaceDatanodeOnFailure` recovery path that FSHLog inherits from HDFS.

```xml
<property>
  <name>hbase.wal.provider</name>
  <value>asyncfs</value>
</property>
<property>
  <name>hbase.regionserver.async.wal.max.exclude.datanode.count</name>
  <value>256</value>
</property>
<property>
  <name>hbase.regionserver.wal.roll.on.sync.ms</name>
  <value>5000</value>
</property>
<property>
  <name>hbase.regionserver.wal.sync.timeout</name>
  <value>60000</value>
</property>
```

### Master-side

```xml
<!-- Procedure executor: scale to absorb the burst -->
<property>
  <name>hbase.master.procedure.threads</name>
  <value>512</value>
</property>
<property>
  <name>hbase.procedure.store.wal.sync.wait.msec</name>
  <value>10</value>
</property>

<!-- Server operation executor: sized for the SCP burst (~167 dead RSs) -->
<property>
  <name>hbase.master.executor.serverops.threads</name>
  <value>256</value>
</property>
<!-- Open/close region executors: sized by Little's Law against META write rate -->
<property>
  <name>hbase.master.executor.openregion.threads</name>
  <value>256</value>
</property>
<property>
  <name>hbase.master.executor.closeregion.threads</name>
  <value>256</value>
</property>
<property>
  <name>hbase.master.executor.meta.openregion.threads</name>
  <value>16</value>
</property>

<!-- Remote procedure dispatcher: one worker per surviving RS + retry headroom -->
<property>
  <name>hbase.procedure.remote.dispatcher.threadpool.size</name>
  <value>512</value>
</property>
<property>
  <name>hbase.procedure.remote.dispatcher.delay.msec</name>
  <value>50</value>
</property>
<property>
  <name>hbase.procedure.remote.dispatcher.max.queue.size</name>
  <value>256</value>
</property>

<!-- Master RPC handlers: Little's Law on inbound RPCs from RSs -->
<property>
  <name>hbase.master.rpc.handler.count</name>
  <value>400</value>
</property>
<property>
  <name>hbase.regionserver.metahandler.count</name>
  <value>256</value>
</property>

<!-- New: cap in-flight promotion procedures (or batches with Option B) -->
<property>
  <name>hbase.promotion.max.in.flight</name>
  <value>2000</value>
</property>

<!-- New: bound the per-region fast-path lease recovery -->
<property>
  <name>hbase.promotion.lease.recovery.timeout</name>
  <value>5000</value>
</property>

<!-- New: enable hinted-handoff NSRE on the server side -->
<property>
  <name>hbase.regionserver.nsre.hinted.handoff</name>
  <value>true</value>
</property>

<!-- New: enable multi-writer META during AZ-loss recovery only -->
<property>
  <name>hbase.meta.multi.writer.recovery.mode</name>
  <value>true</value>
</property>
<property>
  <name>hbase.meta.multi.writer.threshold.lost.servers</name>
  <value>100</value>
</property>
<property>
  <name>hbase.meta.multi.writer.cooldown.ms</name>
  <value>300000</value>
</property>

<!-- Partial Recovery Deferral mode — defer eager replacement of suppressed dead-AZ
     slots until the CFD signals that the failed domain has returned. Per-design
     semantics in PROMOTABLE_CONSISTENT_REPLICA_SET.md,
     PROMOTABLE_TIMELINE_CONSISTENT_REPLICAS.md, and RAFT_REGION_REPLICAS.md. -->
<!-- CFD trip thresholds and recovery-confirm window: see CORRELATED_FAILURE_DETECTOR.md
     for the canonical hbase.dead.rack.* keys and their defaults. -->
<property>
  <name>hbase.replica.deferred.recovery.max.in.flight</name>
  <value>1000</value>
</property>
<property>
  <name>hbase.replica.deferred.rejoin.freshness.ms</name>
  <value>600000</value>
</property>
<property>
  <name>hbase.replica.deferred.rejoin.host.timeout.ms</name>
  <value>300000</value>
</property>

<!-- Do not throttle RIT during failover -->
<property>
  <name>hbase.master.balancer.maxRitPercent</name>
  <value>1.0</value>
</property>
```

### RegionServers

```xml
<!-- Inbound RPC handler count: headroom for the master's higher OPEN/CLOSE rate.
     Bump to ~400 on RegionServers hosting META. -->
<property>
  <name>hbase.regionserver.handler.count</name>
  <value>200</value>
</property>

<!-- RS-side executors: matched to the master's recommended throughput and the
     NameNode saturation point at ω = 30, C_NN = 3×10^5 op/s. Above 32 the
     NameNode binds and W_open self-adjusts upward, with no throughput gain. -->
<property>
  <name>hbase.regionserver.executor.openregion.threads</name>
  <value>32</value>
</property>
<property>
  <name>hbase.regionserver.executor.closeregion.threads</name>
  <value>32</value>
</property>
<property>
  <name>hbase.regionserver.executor.openpriorityregion.threads</name>
  <value>8</value>
</property>

<!-- Distributed WAL splitting: quadruple per-RS split parallelism. Same key
     gates both the master-side WorkerAssigner slot count and the per-RS
     RS_LOG_REPLAY_OPS executor core pool size. -->
<property>
  <name>hbase.regionserver.wal.max.splitters</name>
  <value>8</value>
</property>

<!-- Honor hinted-handoff NSRE on the client side -->
<property>
  <name>hbase.client.nsre.hinted.handoff</name>
  <value>true</value>
</property>
<property>
  <name>hbase.client.nsre.hint.staleness.ms</name>
  <value>60000</value>
</property>

<!-- For the ISR design: bump min.insync.replicas headroom -->
<property>
  <name>hbase.replica.min.insync.replicas</name>
  <value>2</value>
</property>
<property>
  <name>hbase.replica.ack.timeout.ms</name>
  <value>1000</value>
</property>

<!-- For the catch-up design: cap WAL roll on compressed sources to
     bound worst-case rewind cost during catch-up -->
<property>
  <name>hbase.wal.promotable.compression.max.roll.size</name>
  <value>67108864</value>
</property>

<!-- For the RAFT design: heartbeat cadence -->
<property>
  <name>hbase.consensus.leader.heartbeat.timeout.ms</name>
  <value>1500</value>
</property>
```

### Placement and replica counts

We assume a region replica count of 3, with AZ-affinity placement constraints so no two replicas of the same region share an AZ. The Phase 1 ISR membership written to `info:isr` (or the RAFT group membership) is what makes promotion correct, but AZ-affinity placement is what makes a surviving member available after an AZ loss in the first place.

HDFS DataNode placement policy should match. WAL pipelines for any primary in AZ A should land their replicas in AZs A, B, and C (not all three in AZ A), so lease recovery after AZ loss can complete against the surviving DataNodes. This is HDFS placement policy, not HBase, but is mentioned because it is the prerequisite for everything in Phase 2 to actually work under AZ loss.

META's three replicas must be spread across the three AZs (deterministic, not load-balanced).

### HDFS NameNode capacity

The NameNode sets the cluster-wide concurrency ceiling for region opens. Tune it to sustain `C_NN ≈ 3 × 10^5 op/s` for read-heavy traffic so HDFS does not bind the system at the reference workload. At `C_NN = 3 × 10^5 op/s` and `ω = 30` ops per region open, we derive `T_NN ≈ 25 s` for the reference workload.

When the active NameNode is itself in the failed AZ, HDFS HA failover (90–180 s at this cluster size) sits on the critical path before HBase recovery can make progress.

## Per-Design Recommendations

### ISR design

The ISR design's biggest AZ-loss exposure is the promotion-time META write rate against the META region's single primary, ~250,500 promotion mutations after master-coalesced eviction has done its work.

Recommended path:

1. **Adopt per-RS batched promotion procedures.** Reduces master procedure load from per-region to per-RS.
2. **Adopt batched META mutations.** Reduces META load.
3. **Adopt hinted-handoff NSRE.** Removes the client locator-refetch read storm from META.
4. **Optionally, adopt the META multi-writer extension** if the single META RegionServer's write throughput is still the bottleneck after other work has completed.

With these in place, the promotion path's wall-clock floor is dominated by HDFS lease recovery on ~500–1,000 WAL files in parallel (single-digit seconds in healthy clusters) plus ~167 batched promotion META mutations and a handful of master-coalesced ISR-membership META mutations.

### Catch-up design

The catch-up design's biggest AZ-loss exposure is the catch-up read fan-in against degraded HDFS in the dead AZ's WALs, not the master orchestration.

Recommended path:

1. **Adopt per-RS batched promotion procedures and batched META mutations.** Same wins as for the ISR design.
2. **Adopt heartbeat-piggybacked tailer state.** Removes the `GetReplicaTailPositions` fan-out from the critical path.
3. **Adopt hinted-handoff NSRE.** Removes the client locator-refetch read storm from META.
4. **Consider deliberately routing a fraction of catch-up reads back to legacy splitting when HDFS is degraded** (e.g., a master-side fast-fail if `BoundedLeaseRecovery` `SUCCEEDED` rate drops below a threshold). Better to deliberately fall back to the splitter, which already knows how to fan out under load, than to thrash ~250,500 candidate readers against an overloaded HDFS.

The Baseline Operational Parameters (per-RS open executor at `T_open = 32`, the `m7g.16xlarge` NameNode at `C_NN ≈ 3 × 10⁵ op/s`) carry particular weight for the catch-up design, since the per-RS open executor and the NameNode are precisely the components that absorb the catch-up read fan-in against the dead AZ's WALs.

Even after these changes, the catch-up design's failover floor under AZ loss is higher than the ISR or RAFT designs', because the catch-up read against the dead AZ's WALs is intrinsic to the design and is most expensive precisely when the AZ has failed.

### RAFT design

The RAFT design's biggest AZ-loss exposure is the `ReportLeaderElection` storm at the master and the resulting META write serialization, not the consensus layer itself (which is sized for this load).

Recommended path:

1. **Adopt the direct self-promotion variant.** Removes the master from the per-region critical path entirely. ~250,500 promotion META writes are issued by ~250,500 different RegionServers in parallel.
2. **Adopt hinted-handoff NSRE.** RAFT surviving members know the new leader immediately from the consensus layer and can answer NSREs with hints with no additional protocol.
3. **Adopt the META multi-writer extension.** With direct self-promotion, the META primary is the new single point of serialization. Multi-writer META spreads the load.
4. **Keep the master-mediated path as the fallback.** For the rare case where the surviving group has lost a sufficiently fresh log, the master-side `PRIMARY_PROMOTED` TRSP path runs as in the current design and reads the dead primary's WAL to seal the promotion correctly.

The RAFT design comes out ahead under AZ loss provided the direct self-promotion variant is adopted. Without it, the master-mediated path concentrates ~250,500 META writes through the master and META primary.

### Across all three designs

**Hinted handoff** and **the META multi-writer extension** are design independent. They are the two highest-value extensions for surviving AZ loss in a cluster of this size. Either design, with those two extensions plus Options B and C.1, has master and META load below their respective binding constraints during the recovery window.

## Summary

The structural fixes layered on top of the baseline, in order of impact:

1. **Partial Recovery Deferral mode.** Operate the cluster with dead-AZ replica slots suppressed for the duration of the AZ outage; defer eager replacement until the CFD signals that the failed domain has returned.
2. **Coalesce per-region procedures into per-(dead-server, candidate-RS) batches** Three orders of magnitude reduction in master orchestration work, in all three designs.
3. **Batch META mutations alongside** Three orders of magnitude reduction in META write count.
4. **Hinted-handoff NSRE** Removes the client locator-refetch read storm from META. Independent of which design is chosen.
5. **META multi-writer recovery mode.** Once master orchestration is parallelized, META's single primary RegionServer becomes the next binding constraint. Multi-writer META spreads the write load across all META replicas during recovery.
6. **Direct self-promotion** (or the RAFT variant of the same idea). Removes the master from the critical path entirely. Most useful for the RAFT design, where the surviving RAFT group already has the authoritative election outcome locally.

Under AZ loss with the recommended extensions:

- **RAFT** has the lowest intrinsic recovery floor (consensus election plus one master RPC plus one META write, all of which can parallelize). With direct self-promotion the master is off the critical path.
- **ISR** is comparable to RAFT under AZ loss. Master-mediated, but with light per-region master work, dominated by HDFS lease recovery. The ISR-membership write path is master-coalesced and does not compete with promotion-time META throughput.
- **Catch-up** is slowest because of the catch-up read against the dead AZ's WAL on degraded HDFS.

In all three cases the **master procedure store is the dominant remaining term** at the reference workload once Options B and C.1 are applied; the per-RS open executor, HDFS NameNode, and WAL splitting all sit below the procedure-store drain time at the Baseline Operational Parameters. Reducing the typical case further would require dynamic thread-pool sizing for the master procedure executor (a future direction) or reducing `W_open` in the stressed case via more compaction, and smaller recovered-edits.
