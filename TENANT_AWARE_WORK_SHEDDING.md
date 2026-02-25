# RFC: Tenant-Aware Fair-Share Work Shedding

**Updated:** February 2026
**Version:** 1.5
**Status:** Draft

## Summary

This RFC proposes *tenant-aware fair-share work shedding*. Under overload, the server today drops requests indiscriminately. The proposal introduces a pluggable `DropPolicy` interface that separates the drop decision from CoDel's overload detection, and ships a `TenantFairShareDropPolicy` implementation that uses *probabilistic shedding* to enforce max-min fairness. A background `TenantDecayChore` snapshots per-tenant demand and per-queue-type measured capacity, then runs a weighted max-min fair water-filling algorithm to compute an *admission limit* for each tenant in each queue type. The water-filling distributes all available capacity to active tenants in proportion to their configured weights, with unused capacity from under-demanding tenants flowing to over-demanding tenants, mathematically guaranteeing full capacity utilization. When CoDel signals overload, the policy drops requests from tenants whose demand exceeds their admission limit, smoothly trimming only the excess traffic. The system maintains a stable, work-conserving equilibrium. Because the drop decision is independent of queue delay, CoDel's Adaptive LIFO dequeue strategy remains fully operational, preserving the "serve the freshest request first" optimization that maximizes goodput under heavy load. Demand is measured at arrival, so that every arriving request is counted regardless of whether it is later dropped by deadline expiry, CoDel, or the drop policy. This prevents noisy neighbors from evading demand measurement by causing queue delays that expire their own requests before dequeue. Capacity is measured at dequeue, where only admitted requests contribute to the throughput signal. The mechanism is O(1) per request and zero-allocation in steady state on both the dispatch and handler paths. The O(N log N) water-filling computation runs on a dedicated background thread. Tenant identity is resolved by a pluggable `TenantIdentityMapper` interface, with built-in implementations for request attributes, row key parsing (supporting Apache Phoenix composite keys), table name/namespace patterns, and mTLS certificates. Tenant shares are managed dynamically via the `hbase:quota` system table and new shell commands, scaling to several hundred thousand tenants with ~240-360 bytes of RegionServer memory per tenant, depending on whether optional request counting is enabled. Tenant activity is tracked per queue type, ensuring that a tenant's demand in one queue type does not influence shedding decisions in another. The `DropPolicy` interface also opens the door for future policy implementations without further modification to the queue class. The change composes with the existing read/write/scan queue split, requires no protocol changes, and degrades gracefully to unmodified CoDel when disabled.

## Motivation

HBase implements CoDel-based active queue management in its RPC server, which detects sustained overload via sojourn-time tracking and sheds stale requests. However, CoDel is tenant-blind. When overload triggers shedding, requests are dropped indiscriminately based solely on queue delay. A "noisy neighbor" tenant consuming the vast majority of server throughput causes drops to be distributed across all tenants proportionally, punishing well-behaved tenants whose request rates are modest and well within the server's capacity to serve. Multi-tenant deployments require that work shedding be *tenant-aware*. Tenants operating at or below their fair share of resources must not be unduly affected by the overload caused by others. Additionally, some tenants may warrant preferential treatment (higher share, more protection from shedding) or deprioritized treatment (lower share, shed more aggressively) based on business criticality or service-level agreements.

A natural first instinct is to solve this with reservations, but they are a poor fit for several reasons. First, reservations require an a-priori estimate of the server's total capacity, which varies with hardware generation, workload mix, JVM tuning, and even time of day. A fixed partition is either too generous, wasting resources when the reserving tenant is idle, or too conservative, shedding requests that the server could easily handle. Second, reserved but unused capacity is stranded. If a tenant holding 30% of handler slots is momentarily quiet, those slots sit idle while other tenants' requests queue behind a fully-occupied 70%. In a bursty, latency-sensitive system like HBase, stranded capacity directly translates to unnecessary latency and unnecessary drops. Third, the operational burden scales with the number of tenants. Onboarding new tenants may require capacity replanning, and every hardware change or cluster expansion requires re-partitioning.

What we want instead is *fair sharing*. All of the server's resources remain in a single shared pool, and every tenant can burst to use whatever capacity is available at any given instant. Only when aggregate demand exceeds supply, the overload condition that CoDel already detects, does the system intervene, and it does so by shedding work in proportion to how far each tenant exceeds its configured fair share. A tenant whose neighbor is idle benefits from the full capacity of the server. When the neighbor returns, the system smoothly rebalances by shedding the over-share tenant's excess traffic. No capacity is ever stranded, no a-priori sizing is needed, and the system adapts automatically to hardware changes, workload shifts, and tenant population growth.

## Background

CoDel was originally designed by Nichols and Jacobson as an active queue management algorithm for network routers, documented in RFC 8289. Its key insight is that queue length is a poor signal of congestion, a burst of packets can temporarily fill a buffer without indicating sustained overload. The better signal is the *sojourn time*: how long a packet waits in the queue before being forwarded. CoDel tracks the minimum sojourn time over a sliding interval (default 100 ms). If the minimum stays above a target delay for an entire interval, the queue is judged to be in a state of sustained congestion and the algorithm begins dropping packets. The dropping rate accelerates following a control law derived from an inverse square-root function, which ensures that throughput decreases linearly rather than collapsing.

Facebook adapted the CoDel concept for server-side RPC queues, as described in Ben Maurer's *Fail at Scale* paper (ACM Queue 2015). Instead of dropping packets on a wire, the server drops RPC requests from a job queue and responds to the client with an error that signals overload. The target delay is raised significantly (100 ms is typical for server-side queues vs. 5 ms for network links). Facebook also combined CoDel with an Adaptive LIFO strategy. Under high queue utilization, the dequeue order flips from FIFO to LIFO so that the freshest requests, the ones most likely to still be within their client-side timeout window, are served first. Together, these two mechanisms allow the server to shed stale work while maximizing the fraction of responses that arrive in time to be useful.

HBase's `AdaptiveLifoCoDelCallQueue` implementation is a faithful adaptation of the Facebook model. Each `ServerCall` records its `receiveTime` at construction, and `needToDrop` computes call delay at dequeue time. Minimum delay is tracked per interval using a `volatile long` reset by at most one thread per interval via an `AtomicBoolean` guard, and the overloaded state transitions cleanly at interval boundaries, entering when the observed minimum delay exceeds `codelTargetDelay` for a full interval, exiting otherwise. Any call whose sojourn time exceeds `2 x codelTargetDelay` while overloaded is dropped. When queue utilization exceeds `lifoThreshold` (default 0.8), dequeue flips from `takeFirst()` to `takeLast()`, serving the freshest requests first. `CallRunner.drop()` sends a `CallDroppedException` (`serverOverloaded = true`) back to the client, which enters its overloaded-server retry path with a separately configurable pause (`hbase.client.pause.server.overloaded`), effectively moving the retry backlog from the server to the client side.

Two complementary safety nets exist outside of CoDel itself. Before a call enters the queue, `ServerRpcConnection.processRequest()` checks cumulative queued byte size against `hbase.ipc.server.max.callqueue.size` and `BalancedQueueRpcExecutor.dispatch()` checks individual queue length, rejecting immediately with `CallQueueTooBigException` if either limit is exceeded. After dequeue but before processing, `CallRunner.run()` compares the current time against the call's deadline (`receiveTime + clientTimeout`) and silently drops calls that have already exceeded it, catching requests that survived the CoDel check but aged out while waiting for a handler thread. All CoDel parameters are dynamically reloadable via `AdaptiveLifoCoDelCallQueue.updateTunables()`, so operators can adjust shedding behavior in a running cluster without a restart.

## Gaps and Limitations

Despite that solid foundation, there are gaps between what the current CoDel implementation provides and what a comprehensive work-shedding strategy requires. These gaps fall into three categories: server-side architectural limitations, client-side behavioral issues, and observability shortfalls.

### Server-Side Gaps

**CoDel applies only to the general call queue.** The `SimpleRpcScheduler` creates separate executors for priority calls (meta operations, admin RPCs), replication, meta-transition, and bulk-load traffic. All of these executors are hardcoded to use plain FIFO queues regardless of the `hbase.ipc.server.callqueue.type` setting. If any of these specialized queues develops a standing backlog - a replication storm, for example, or a burst of meta operations - there is no shedding mechanism to prevent handler-thread starvation within that queue. The priority queue is especially concerning because it carries meta operations that are critical to cluster health, yet it has no protection against overload beyond its configured capacity limit.

**CoDel does not use the client-provided deadline.** The client sends its RPC timeout in the `RequestHeader`, and the server faithfully computes a deadline (`receiveTime + timeout`) in `ServerCall`. However, the `needToDrop` method in `AdaptiveLifoCoDelCallQueue` never consults this value. It makes its drop decision solely on the basis of its own sojourn-time threshold (`2 x codelTargetDelay`, which defaults to 200 ms). This means the algorithm can make suboptimal decisions in both directions.

**Per-queue CoDel state is independent.** When multiple call queues are configured via `hbase.ipc.server.callqueue.handler.factor`, each `AdaptiveLifoCoDelCallQueue` instance maintains its own `minDelay`, `intervalTime`, and `isOverloaded` state. One queue may be overloaded while its neighbors are not. There is no global overload signal that aggregates across all queues, which can lead to inconsistent behavior.

**Drops happen only at dequeue time.** The CoDel check runs inside `take()` and `poll()`, which are called by handler threads. If all handler threads are busy, no dequeues happen and no drops occur. The queue fills silently until it hits the capacity limit, at which point new calls are rejected with `CallQueueTooBigException`, a different code path that does not carry the `serverOverloaded` flag and is therefore not subject to the overloaded-server backoff configuration on the client. Enqueue-time shedding based on queue depth or estimated wait time could address this gap.

### Client-Side Gaps

**Per-server concurrency limiting is effectively disabled.** The client exposes `hbase.client.perserver.requests.threshold`, but its default value is `Integer.MAX_VALUE`, which means it is not enforced. During a shedding episode, a single client may have many concurrent in-flight RPCs to the same overloaded RegionServer, each of which consumes a slot in the server's call queue. A reasonable per-server concurrency limit would provide cooperative admission control on the client side, reducing the volume of work the server needs to shed.

**No circuit-breaker pattern.** The client retries each operation independently up to `hbase.client.retries.number` (default 15) times. Use of the circuit breaker pattern here would allow the client to fail fast for a cooldown period, giving the server time to drain its queue, and then probe with a single request before resuming full traffic.

**`CallQueueTooBigException` bypasses the overloaded-server backoff path.** When the queue is full and a call is rejected before entering the CoDel queue, the client receives a `CallQueueTooBigException`. This exception does not carry the `serverOverloaded` flag (it extends from a different branch of the exception hierarchy), so the client retries with the standard `hbase.client.pause` backoff rather than the potentially higher `hbase.client.pause.server.overloaded` value. This means the most severe overload condition paradoxically triggers less aggressive client-side backoff than the moderate overload condition.

### Observability Gaps

**The `numLifoModeSwitches` metric is misleading.** Despite its name, this counter is incremented on every individual LIFO dequeue, not once per transition into LIFO mode. Under sustained overload with the queue above the 80% threshold, this counter will increment at the rate of handler dequeues, not at the rate of mode switches. Operators who interpret it as a mode-switch count will overestimate the frequency of state transitions.

**No metric for calls dropped by the deadline check.** The `CallRunner.run()` deadline check increments `callTimedOut`, but this metric is not prominently surfaced alongside the CoDel drop metrics. Operators monitoring `numGeneralCallsDropped` may miss a significant source of wasted work that occurs after the CoDel check passes.

## Proposed Changes

### Quick Wins

**`CallQueueTooBigException` to carry the `serverOverloaded` flag.** A small code change to ensure the client uses the overloaded-server backoff path for queue-full rejections, not just CoDel drops. This eliminates the paradox where the most severe overload condition triggers the least aggressive backoff.

**Fix the `numLifoModeSwitches` metric.** Despite its name, this counter is incremented on every individual LIFO dequeue, not once per transition into LIFO mode. Under sustained overload it increments at the rate of handler dequeues, leading operators to dramatically overestimate the frequency of state transitions. Change it to track actual FIFO -> LIFO and LIFO -> FIFO transitions.

**Surface the `callTimedOut` metric alongside CoDel drop metrics.** The `CallRunner.run()` deadline check increments `callTimedOut` when it drops a call that has already exceeded its client-provided deadline, but this metric is not prominently surfaced alongside `numGeneralCallsDropped`. Operators monitoring CoDel drops may miss a significant source of wasted work that occurs *after* the CoDel check passes. Ensure `callTimedOut` is included in the same dashboard context as the CoDel metrics.

**Integrate client deadline into the CoDel drop decision.** The client already sends its RPC timeout in the `RequestHeader`, and the server computes a deadline (`receiveTime + timeout`) in `ServerCall`, yet `needToDrop` never consults it. Modify `needToDrop` (or add a complementary check) to consult `call.getDeadline()`. A call that has already passed its deadline, or whose remaining time is less than some configurable fraction of the expected processing time, should be dropped regardless of the CoDel overload state. This would eliminate the two suboptimal cases described above, dropping calls that still have ample client-side budget, and processing calls that have already expired, and would subsume the current deadline check in `CallRunner.run()`, freeing the handler-thread dequeue cycle.

**Extend CoDel to the priority queue.** Meta operations are critical to cluster health, and a standing backlog in the priority queue can delay region assignments, splits, and moves. Applying CoDel (or a similar mechanism) to the priority queue would prevent these operations from suffering unbounded queue delay. This requires careful tuning because meta operations have very different latency characteristics than general RPCs.

**Set a finite default for `hbase.client.perserver.requests.threshold`.** A reasonable default (e.g. 256 or configurable as a function of handler count) would provide cooperative client-side admission control out of the box.

**Adaptive target delay.** Instead of a fixed `codelTargetDelay`, automatically adjust the target based on observed steady-state latency percentiles. A server that normally processes RPCs in 5 ms should not tolerate 100 ms of queue delay before activating shedding; conversely, a server with inherently slow operations may need a higher target to avoid false positives.

### Client-Side Changes

**Implement a circuit breaker on the client** that trips after a configurable number of consecutive `CallDroppedException` responses from the same server. While tripped, the client fails fast (or routes to a replica if available) and periodically sends a single probe request. When the probe succeeds, the circuit closes and normal traffic resumes. This pattern is standard in service-mesh frameworks (Envoy, Hystrix, resilience4j) and would significantly reduce the load on a recovering server.

### Server-Side Changes

**Tenant fair-share work shedding.**

CoDel remains responsible for overload detection. A new `DropPolicy` interface formalizes the drop-decision extension point. The queue's `needToDrop()` method delegates to a pluggable policy that receives the CoDel overload state, the call's sojourn time, and the queue's type, and returns a drop verdict. The shipped `TenantFairShareDropPolicy` uses probabilistic shedding driven by max-min fair admission limits. A background `TenantDecayChore` periodically snapshots per-tenant demand and per-queue-type measured capacity, then runs a weighted max-min fair water-filling algorithm to produce an admission limit for each tenant in each queue type. The water-filling guarantees that the sum of all admission limits equals the capacity whenever total demand exceeds capacity, so the system is provably work-conserving. No handler throughput is ever stranded. When CoDel signals overload, the policy drops requests from tenants whose demand exceeds their admission limit with a probability equal to the ratio of the admission limit to the tenant's demand. This produces smooth, proportional shedding that converges on max-min fair targets. Tenants at or below their admission limit are never probabilistically shed. Because the drop decision is independent of queue delay, CoDel's Adaptive LIFO dequeue strategy remains fully operational. No new queues, no new handler threads, no protocol changes, and no a-priori capacity sizing are required. The interface also accommodates potential future policy implementations without requiring further modification to the queue class.

Every incoming RPC is mapped to a tenant identifier by a pluggable `TenantIdentityMapper` and this proposal includes built-in implementations that cover the most common deployment patterns:

* extracting tenant from a request attribute set by the client or a proxy layer;

* parsing tenant from the leading bytes of the HBase row key (for Apache Phoenix multi-tenant tables, where the tenant ID is the first primary-key component and therefore the row key prefix);

* deriving tenant from the target table's namespace or name;

* and deriving tenant from the mTLS client certificate.

Deployments with bespoke key layouts can plug in a custom implementation. RPCs that cannot be attributed to any tenant are assigned to a synthetic `__default__` tenant.

A shared `TenantTracker` maintains lightweight per-tenant, per-queue-type cost counters (payload size via `call.getSize()`) using `LongAdder` for lock-free, zero-allocation updates. Demand is recorded on the dispatch (enqueue) path via `recordDemand()`, measuring each tenant's *arrival rate* before the call enters the queue. This ensures that every arriving request is counted as demand regardless of whether it is later dropped by deadline expiry, CoDel, or the drop policy, preventing noisy neighbors from evading demand measurement. Capacity is recorded on the dequeue path via `recordAdmitted()`, measuring the server's actual processing throughput. Counters are scoped per queue type (read, write, scan), so each queue measures demand and capacity against its own resource pool. Cost-based demand drives the admission limit computation in the background chore, ensuring that heavy operations register proportionally higher cost than lightweight lookups. The tracker also optionally maintains per-tenant, per-queue-type request counts for observability and operational diagnostics. The `TenantDecayChore` periodically snapshots these counters, resets them, runs the weighted max-min fair water-filling algorithm to compute admission limits, and evicts idle tenants. Handler threads read only the pre-computed admission limits and demand snapshots via O(1) reads. Per-tenant memory is ~240 bytes with cost tracking only, or ~360 bytes with request counting enabled covering all three queue types. At 300,000 active tenants the total footprint is ~72-108 MB depending on configuration.

Each tenant is assigned a *share* (a configurable weight, managed dynamically via the `hbase:quota` table and shell commands). The `TenantDecayChore` converts these weights into per-tenant, per-queue-type admission limits via weighted max-min fair water-filling (see the Background Decay section). When CoDel signals overload, the drop decision reads the tenant's precomputed admission limit and its demand from the most recent snapshot. If the demand exceeds the admission limit, requests are dropped with probability `1 - (admissionLimit / tenantDemand)`, smoothly trimming only the excess traffic. A tenant at or below its admission limit faces zero additional drop probability. Because the water-filling guarantees that all admission limits sum to the full measured capacity, no handler throughput is stranded. Unused capacity from under-demanding tenants flows to over-demanding tenants in proportion to their weights. As a safety net, the standard CoDel threshold check (`callDelay > baseThreshold`) remains as a fallback for symmetric overload where all tenants equally exceed their share. When tenant-awareness is not enabled, the policy is absent and behavior is identical to today's unmodified CoDel.

The remainder of this section details the design principles, component interfaces, integration points, configuration, and phasing.

#### Design Principles

**CoDel detects, the policy decides who.** CoDel's interval/minDelay/isOverloaded state machine tells us that the server is overloaded. A formal `DropPolicy` interface decides which requests to drop once overload is signaled. `AdaptiveLifoCoDelCallQueue` delegates the drop decision to this interface, keeping the adaptive, self-tuning nature of CoDel intact while giving us a clean, stable extension point that can host entirely different shedding strategies without modifying the queue class.

**Work-conserving max-min fair shedding.** This proposal uses a probabilistic drop model driven by per-tenant *admission limits* computed via a weighted max-min fair water-filling algorithm. The computation runs in the `TenantDecayChore` (every 100 ms), not on the handler hot path. For each queue type, the chore takes the measured processing capacity and the observed per-tenant demands, and runs water-filling: tenants are sorted by normalized demand (demand / weight), and capacity is distributed in weight-proportional increments, with each tenant receiving the minimum of their demand and their weighted share of the remaining capacity. The result is a per-tenant, per-queue-type *admission limit*, an absolute cost value representing the maximum throughput each tenant should consume. Crucially, `sum(admissionLimits) = capacity` whenever aggregate demand exceeds capacity, mathematically guaranteeing that the system is *work-conserving*: unused capacity from under-demanding tenants flows to over-demanding tenants in proportion to their weights, and no handler throughput is ever stranded.

On the hot path, the drop probability for a tenant exceeding its admission limit is:

`dropProbability = 1 - (admissionLimit / tenantDemand)`

Because `admissionLimit` is an absolute cost value precomputed by the chore (not a fraction derived from instantaneous measurements), the formula does not create a feedback loop between drop decisions and capacity measurements. The system converges to a stable equilibrium where each tenant's admitted traffic matches its admission limit. Admission limits are computed over *demand-active* tenants only (those with non-zero demand in any queue type in the current snapshot), so an idle tenant's capacity entitlement is immediately redistributed to active tenants without waiting for eviction. The model produces smooth, proportional, capacity-aware shedding that converges on max-min fair targets. When tenant-awareness is not configured, the policy is absent and behavior is identical to today's CoDel.

**Cost-weighted tracking with optional request counting.** The `TenantTracker` maintains a cost counter per tenant per queue type, the payload size via `call.getSize()`. The water-filling algorithm uses per-queue-type cost-based demand as input, ensuring that heavy-resource operations register proportionally higher cost than lighter weight operations. Without cost-weighting, a tenant saturating the CPU and I/O with a few massive requests would appear "fair" while a well-behaved tenant issuing many lightweight requests would be falsely flagged as exceeding its share. Optionally (and recommended), the tracker also maintains a per-tenant request count. Request counting is not used by the drop probability computation. It exists for two reasons: observability (request rate is valuable for diagnosing whether a tenant's cost footprint is driven by a few heavy requests or many lightweight ones) and future policy flexibility. A count-based or blended-share policy can be added without modifying the tracker.

**Scale to several hundred thousand tenants.** Per-tenant state must be trivially small, lazily created, and periodically evicted when idle by a background chore. Request-to-tenant mappers can optionally map individual identities into "tenants" that function more like quality-of-service classes, as appropriate for the use case, to minimize the total number of shares tracked. The configuration model supports a *default policy*, providing an equal share of capacity for all tenants not explicitly configured, so that operators need only configure the tenants that require special treatment.

**Compose functionality, don't complexify.** `RWQueueRpcExecutor` already splits traffic into read/write/scan queues. The shedding policy operates within each queue independently. It does not create new queues or interact with the queue-type split.

**Per-queue-type tracking prevents cross-queue pollution.** A single `TenantTracker` is shared across all queues, but it tracks tenant cost and capacity *per queue type* (read, write, scan). The water-filling algorithm runs independently for each queue type, so admission limits are computed against the specific resource pool experiencing congestion, not against a global aggregate. Weights remain global: a tenant's configured weight determines its entitlement uniformly across all queue types. Only the measurement side (demand and capacity) and the resulting admission limits are scoped per queue type. The `QueueType` is threaded from `RWQueueRpcExecutor` through `DropPolicy.recordDemand()` on the dispatch path and `DropPolicy.shouldDrop()` on the dequeue path, so the policy always records demand and reads the tenant's admission limit for the correct queue type.

#### Alternatives Considered

Several alternative approaches were evaluated before settling on a pluggable `DropPolicy` with probabilistic shedding. Each was rejected for specific reasons, though the analysis informed the final design.

**Per-tenant sub-queues (Weighted Fair Queuing / Deficit Round Robin).** The networking world's standard answer to multi-tenant fairness is WFQ or DRR, which give each tenant a virtual sub-queue and schedule dequeues proportionally to weights. This would provide true *fair scheduling* (controlling service order), not just *fair shedding* (controlling what to drop). However, it is a poor fit for HBase. With hundreds of thousands of tenants, most sub-queues are empty at any instant, and the scheduling overhead of maintaining a run-list adds latency to every dequeue, not just overloaded ones. Per-tenant sub-queues do not compose with the existing read/write/scan queue split without creating a combinatorial explosion of sub-queues. The approach requires a completely new queue implementation with a much larger blast radius than evolving the existing class.

**Pre-admission rate limiting at dispatch.** Instead of modifying CoDel, one could reject over-share tenants before they enter the queue, at the `BalancedQueueRpcExecutor.dispatch()` or `ServerRpcConnection.processRequest()` level. This would prevent over-share requests from consuming queue space at all. However, pre-admission *drop decisions* at dispatch require knowing the server's capacity in advance to set per-tenant rates, which is exactly the reservation/partitioning problem the Motivation section argues against. CoDel's core strength is that it discovers overload empirically via sojourn time. Pre-admission drop decisions would lose that self-tuning property. Note that this proposal *does* record demand at the dispatch site (via `DropPolicy.recordDemand()`), but this is demand *measurement*, not a drop decision. The distinction is critical: measurement at dispatch captures true arrival rate for the water-filling algorithm, while the drop decision remains at dequeue time, driven by CoDel's empirically-discovered overload signal.

**Delay-threshold modulation.** An earlier iteration of this design computed a per-tenant *threshold factor* (`fairShare / actualShare`, bounded by floor and ceiling parameters) and multiplied it against CoDel's base drop threshold: `effectiveThreshold = baseThreshold x tenantFactor`. A request was dropped when `callDelay > effectiveThreshold`. A probabilistic drop formula avoids the mathematical issues with that former approach: it is delay-independent (preserving LIFO), produces smooth shedding, and is naturally bounded between 0.0 and 1.0 without manual clamping.

**Direct fractional shedding (death spiral of stranded capacity).** An earlier iteration of this design computed the drop probability directly on the hot path using `dropProbability = 1 - (fairShare / loadFraction)`, where `loadFraction = tenantDemand / measuredCapacity` and `measuredCapacity = snapshotTotalAdmittedCost`. This is mathematically equivalent to capping each over-share tenant's admitted traffic to exactly `fairShare × measuredCapacity`. The formula creates a destructive feedback loop when any tenant demands less than its entitled share. Unused capacity vanishes from the `measuredCapacity` measurement in the next window, which further reduces every over-share tenant's cap, which further reduces measured capacity. The contraction continues window-by-window until the system converges to a fixed point far below the server's true capability. The system fails to be work-conserving. The max-min fair water-filling algorithm eliminates this failure mode.

#### Components

##### QueueType

An enum identifying the queue type. `RWQueueRpcExecutor` assigns a `QueueType` to each `AdaptiveLifoCoDelCallQueue` at construction time. The queue type is used in two places: on the dispatch path, `RpcExecutor` passes it to `DropPolicy.recordDemand()` so that demand is recorded against the correct resource pool. On the dequeue path, the queue passes it to `DropPolicy.shouldDrop()` so that the drop policy reads the correct per-queue-type admission limit for the specific resource pool (read, write, or scan) experiencing congestion.

```java
@InterfaceAudience.Private
public enum QueueType {
  READ, WRITE, SCAN
}
```

##### DropPolicy

The top-level extension point. The interface has two hot-path methods: `recordDemand()`, called from the dispatch (enqueue) path to record the tenant's demand before the call enters the queue, and `shouldDrop()`, called from handler threads at dequeue time after the CoDel interval state machine has run, which receives the information CoDel has already computed along with the queue type and returns a drop verdict. Separating demand recording from the drop decision ensures that demand measures arrival rate (every request counted) while the drop decision remains driven by CoDel's empirically-discovered overload signal.

```java
@InterfaceAudience.Public
public interface DropPolicy {
  /**
   * Record the demand of a call at enqueue time. Called from the dispatch
   * path (RpcExecutor thread) before the call enters the queue, so that
   * demand is measured by arrival rate regardless of whether the call is
   * later dropped by deadline expiry, CoDel, or the drop policy.
   * Implementations must be O(1), lock-free, and allocation-free in
   * steady state.
   *
   * @param call      the RPC call being enqueued
   * @param queueType the queue type the call is being dispatched to
   */
  default void recordDemand(RpcCall call, QueueType queueType) {}

  /**
   * Decide whether a call should be dropped.
   * Called from handler threads at dequeue time; implementations must be
   * O(1), lock-free, and allocation-free in steady state.
   *
   * @param call          the RPC call being evaluated
   * @param callDelay     sojourn time in milliseconds
   * @param isOverloaded  true if CoDel has detected sustained overload
   * @param baseThreshold the base drop threshold (2 x codelTargetDelay)
   * @param queueType     the queue type (READ, WRITE, or SCAN) of the
   *                      dequeuing queue, used to scope admission limit and
   *                      capacity measurements to the correct resource pool
   * @return true if the call should be dropped
   */
  boolean shouldDrop(RpcCall call, long callDelay, boolean isOverloaded,
    long baseThreshold, QueueType queueType);

  /**
   * Called once during RpcExecutor initialization.
   */
  default void initialize(Configuration conf) {}

  /**
   * Called on configuration reload to pick up changed tunables.
   */
  default void onConfigurationChange(Configuration conf) {}
}
```

When no `DropPolicy` is configured (the default), `AdaptiveLifoCoDelCallQueue` uses a trivial inline default that preserves today's behavior:

```java
return isOverloaded && callDelay > baseThreshold;
```

##### TenantFairShareDropPolicy

The tenant-aware implementation of `DropPolicy`. It owns the `TenantTracker`, `TenantIdentityMapper`, and `TenantShareCache` references, encapsulating all tenant-fair-share logic in a single class. `recordDemand()` records the tenant's arrival cost on the dispatch path; `shouldDrop()` makes the drop decision at dequeue time using precomputed snapshot data. The queue class and executor know nothing about tenants, shares, or fairness. They only know that they have an optional `DropPolicy` to consult and a `QueueType` to pass.

```java
@InterfaceAudience.Private
public class TenantFairShareDropPolicy implements DropPolicy {
  private final TenantTracker tracker;

  @Override
  public void recordDemand(RpcCall call, QueueType queueType) {
    tracker.recordDemand(call, call.getSize(), queueType);
  }

  @Override
  public boolean shouldDrop(RpcCall call, long callDelay,
      boolean isOverloaded, long baseThreshold, QueueType queueType) {
    long cost = call.getSize();
    TenantStats stats = tracker.getStats(call);
    if (!isOverloaded) {
      tracker.recordAdmitted(cost, queueType);
      return false;
    }
    long tenantDemand = stats.getSnapshotCost(queueType);
    long admissionLimit = stats.getAdmissionLimit(queueType);
    if (tenantDemand > admissionLimit) {
      if (admissionLimit <= 0) {
        return true;  // zero allocation with positive demand: shed all
      }
      double dropProb = 1.0 - ((double) admissionLimit / tenantDemand);
      if (ThreadLocalRandom.current().nextDouble() < dropProb) {
        return true;
      }
    }
    boolean fallbackDrop = callDelay > baseThreshold;
    if (!fallbackDrop) {
      tracker.recordAdmitted(cost, queueType);
    }
    return fallbackDrop;
  }
}
```

Demand tracking and the drop decision are intentionally separated across the two halves of the request lifecycle. `recordDemand()` is called on the dispatch (enqueue) path, before the call enters the queue, incrementing the tenant's per-queue-type cost counter to measure *arrival rate*. This ensures that every request is counted as demand regardless of whether it is later dropped by deadline expiry, CoDel, or the drop policy, preventing noisy neighbors from evading demand measurement by causing queue delays that expire their own requests before dequeue. `shouldDrop()` is called at dequeue time by handler threads. It reads the tenant's `TenantStats` via `tracker.getStats(call)` and consults two precomputed values from the most recent `TenantDecayChore` snapshot: the tenant's `snapshotCost` (demand) and its `admissionLimit`, both scoped to the queue type. `recordAdmitted()` increments the per-queue-type admitted-cost counter only for requests that pass the drop decision, measuring the server's actual processing throughput (capacity). The `admissionLimit` is computed by the chore's weighted max-min fair water-filling algorithm (see the Background Decay section), which distributes all available capacity in proportion to tenant weights with unused capacity from under-demanding tenants flowing to over-demanding tenants. A tenant flooding the read queue will see a reduced admission limit only in the read queue. Their occasional write request, evaluated against the write queue's per-queue-type admission limit, will be within its limit and face no probabilistic shedding.

Configuration:

`hbase.ipc.server.callqueue.codel.shedding.policy.class =`  
    `org.apache.hadoop.hbase.ipc.TenantFairShareDropPolicy`

When this property is not set, no policy is instantiated, the policy reference is null, and `needToDrop()` falls through to the vanilla CoDel comparison. The existing `hbase.ipc.server.callqueue.codel.tenant.enabled` flag is retained as a convenience alias: setting it to `true` is equivalent to setting the policy class to `TenantFairShareDropPolicy`.

##### TenantTracker

Maintains lightweight per-tenant accounting, scoped per queue type. Cost tracking (payload size) is mandatory and provides the demand inputs for the water-filling algorithm. Request counting is optional (controlled by `hbase.ipc.server.shedding.tenant.track.request.count`, default `true`) and recommended for observability. All counters are maintained separately for each `QueueType`, so that demand measurements and admission limits reflect the specific resource pool, preventing cross-queue pollution.

```java
@InterfaceAudience.Private
public class TenantTracker {
  private static final int NUM_QUEUE_TYPES = QueueType.values().length;
  private final ConcurrentHashMap<String, TenantStats> tenants;
  private final boolean trackRequestCount;
  private final LongAdder[] totalAdmittedCost =
      new LongAdder[NUM_QUEUE_TYPES];
  private final LongAdder[] totalRequests =
      new LongAdder[NUM_QUEUE_TYPES];
  private final AtomicLongArray snapshotTotalAdmittedCost =
      new AtomicLongArray(NUM_QUEUE_TYPES);
  private final AtomicLongArray snapshotTotalRequests =
      new AtomicLongArray(NUM_QUEUE_TYPES);

  static class TenantStats {
    final LongAdder[] cost = new LongAdder[NUM_QUEUE_TYPES];
    final LongAdder[] requests; // allocated when enabled
    final AtomicLongArray snapshotCost =
        new AtomicLongArray(NUM_QUEUE_TYPES);
    final AtomicLongArray snapshotRequests;
    final AtomicLongArray admissionLimit =
        new AtomicLongArray(NUM_QUEUE_TYPES);

    long getSnapshotCost(QueueType qt) {
      return snapshotCost.get(qt.ordinal());
    }

    long getAdmissionLimit(QueueType qt) {
      return admissionLimit.get(qt.ordinal());
    }
  }

  void recordDemand(RpcCall call, long cost, QueueType queueType) {
    String tenantId = identityMapper.getTenantId(call);
    TenantStats stats = tenants.computeIfAbsent(tenantId,
        k -> new TenantStats(trackRequestCount));
    stats.cost[queueType.ordinal()].add(cost);
    if (trackRequestCount) {
      stats.requests[queueType.ordinal()].increment();
      totalRequests[queueType.ordinal()].increment();
    }
  }

  TenantStats getStats(RpcCall call) {
    String tenantId = identityMapper.getTenantId(call);
    return tenants.computeIfAbsent(tenantId,
        k -> new TenantStats(trackRequestCount));
  }

  void recordAdmitted(long cost, QueueType queueType) {
    totalAdmittedCost[queueType.ordinal()].add(cost);
  }

  long getSnapshotTotalAdmittedCost(QueueType queueType) {
    return snapshotTotalAdmittedCost.get(queueType.ordinal());
  }
}
```

The tracker is a singleton shared across all `AdaptiveLifoCoDelCallQueue` instances within an `RpcExecutor`, accessible via the shared `DropPolicy` reference. Demand and capacity are tracked on separate paths, reflecting their distinct semantics. On every dispatch (enqueue), `recordDemand(call, cost, queueType)` increments the tenant's per-tenant cost counter for the given queue type (by `call.getSize()`), recording the tenant's *demand* as *arrival rate* in that specific resource pool. Because demand is recorded before the call enters the queue, every arriving request is counted regardless of whether it is later dropped by deadline expiry, CoDel, or the drop policy. This prevents a noisy neighbor from evading demand measurement: even if a tenant's requests age out in the queue and are dropped by the early deadline check in `needToDrop()`, their arrival cost has already been recorded, and the water-filling algorithm sees their true demand. When request counting is enabled, `recordDemand()` also increments the per-tenant and global request counters for the queue type. At dequeue time, `getStats(call)` retrieves the tenant's `TenantStats` reference so that `shouldDrop()` can read the precomputed snapshot values for the drop decision. Separately, `recordAdmitted(cost, queueType)` increments the per-queue-type `totalAdmittedCost`, called only for requests that are not dropped, measuring the server's actual processing throughput. When a specific queue is saturated, that queue type's `snapshotTotalAdmittedCost` converges to the true processing throughput of handlers serving that queue, a self-calibrating capacity measurement that requires no a-priori sizing. Because the tracker maintains separate counters per queue type, a tenant's read demand does not inflate their apparent load in the write queue or vice versa. The `admissionLimit` field is written by the `TenantDecayChore` after each water-filling computation and read by handler threads via O(1) reads.

The drop probability is computed from the precomputed per-queue-type admission limits, comparing each tenant's demand in a specific resource pool against its allocated share of that pool's capacity. The water-filling algorithm run by the `TenantDecayChore` ensures that the shedding decision reflects who is consuming more than their max-min fair allocation of a specific queue type's resources, and that all capacity is distributed. No throughput is stranded. A tenant saturating the read queue with a few massive MultiGets registers proportional cost in the read queue only. Their write traffic is evaluated independently against the write queue's admission limits. When request counting is enabled (the recommended default), the introspection servlet exposes both `snapshotCost` and `snapshotRequests` per tenant per queue type, giving operators visibility into both the resource footprint and the request frequency of each tenant in each resource pool, valuable for diagnosing whether a tenant's cost is driven by a few heavy requests or many lightweight ones and whether the load is concentrated in a specific queue type.

##### TenantIdentityMapper

How a request is mapped to a tenant is pluggable. Different deployments derive tenant identity from different sources: a request attribute set by a proxy layer, the row key bytes of the request (critical for Apache Phoenix), a table name or namespace pattern, or an mTLS certificate. The `TenantIdentityMapper` interface decouples the `TenantTracker` from any single identity strategy.

```java
@InterfaceAudience.Private
public interface TenantIdentityMapper {
  /**
   * Extract a tenant identifier from the given RPC call.
   * Implementations should be O(1), allocation-free in steady state,
   * and thread-safe (called concurrently from handler threads).
   * @return tenant identifier, or null if this extractor cannot determine
   *         the tenant from the given call
   */
  String getTenantId(RpcCall call);

  /**
   * Called once during RegionServer startup with the active Configuration.
   * Implementations may read configuration keys to parameterize their
   * extraction logic (e.g. attribute key name, row key prefix length,
   * table name regex).
   */
  default void initialize(Configuration conf) {}
}
```

The `TenantTracker` delegates identity resolution to its configured `TenantIdentityMapper`. If the extractor returns `null`, the call is assigned to the synthetic `__default__` tenant.

##### Provided TenantIdentityMapper implementations

###### `RequestAttributeTenantIdentityMapper`

Reads the tenant ID from a request attribute (default key: `hbase.rpc.tenant`), exactly as `QuotaCache.getQuotaUserName()` resolves the quota user from a request attribute today (HBASE-27784). The client sets the attribute via `TableBuilder.setRequestAttribute()`. This is the simplest mechanism and requires client cooperation. HubSpot demonstrated this pattern at scale in production: their proxy microservices pass the originating caller identity as a request attribute, and the quota system resolves per-caller throttles despite the traffic arriving from a shared proxy user (see "Using HBase Quotas to Share Resources at Scale", HubSpot Engineering Blog, Oct 2024). Falls back to the connection attribute if no request attribute is set.

###### `RowKeyTenantIdentityMapper`

Extracts the tenant ID from the row key bytes of the request. This is especially important for Apache Phoenix, which constructs HBase row keys as a concatenation of primary key components of the relational schema. In a multi-tenant Phoenix deployment, the tenant ID is typically the first component of the composite primary key and therefore occupies the leading bytes of the HBase row key.

The extractor inspects the deserialized request protobuf (which has already been parsed by the RPC layer before `needToDrop()` is called) and reads the row key from the appropriate field: `GetRequest.getGet().getRow()` for Gets, `MutateRequest.getMutation().getRow()` for Mutations, the first action's row for `MultiRequest`, and the start row for `ScanRequest`. Two extraction modes are supported:

| Mode | Configuration | Behavior |
| :---- | :---- | :---- |
| `prefix_length` | `...rowkey.prefix.length = 16` | First N bytes of the row key, decoded as UTF-8 |
| `delimiter` | `...rowkey.delimiter = 0` | Bytes before the first occurrence of the delimiter byte (e.g. `0x00`, which Phoenix uses as the separator between VARCHAR PK components) |

For Phoenix, a table with schema `CREATE TABLE metrics (tenant_id VARCHAR, ts TIMESTAMP, ...) MULTI_TENANT` produces row keys where the tenant ID occupies the leading bytes followed by a `0x00` separator. Configuring `delimiter` mode with `0x00` extracts the tenant ID directly from the row key.

For deployments needing more complex row key parsing (e.g., composite formats where the tenant ID is not a simple prefix), operators can provide a custom `TenantIdentityMapper` implementation class.

###### `TableNameTenantIdentityMapper`

Derives the tenant ID from the target table's namespace or name. In deployments that use a namespace-per-tenant model (e.g., `tenant_42:events`, `tenant_43:events`), the namespace *is* the tenant identity. In deployments that encode tenant ID in the table name (e.g., `events_tenant_42`), a configurable regex with a capture group extracts it. The table name is available on the `RpcCall` via the request protobuf's `RegionSpecifier`.

###### `CertificateTenantIdentityMapper`

Extracts tenant ID from the mTLS client certificate's Common Name or Subject Alternative Name. The client's TLS certificate chain is exposed on `RpcCallContext` via `getClientCertificateChain()` (HBASE-28317), and a new `RpcObserver` coprocessor hook (HBASE-28952) allows authorization and identity extraction based on the certificate at connection time. In mTLS deployments, the certificate identity can serve as the tenant identifier without requiring the client to set an explicit request attribute.

Operators select the extractor appropriate for their deployment by setting the extractor class:

`hbase.ipc.server.shedding.tenant.extractor.class =`  
    `org.apache.hadoop.hbase.ipc.RowKeyTenantIdentityMapper`

If no identity source yields a tenant, the call is assigned to the synthetic `__default__` tenant with share 1.0.

#### Background Decay and Eviction Chore

`TenantTracker` registers a dedicated `ScheduledChore` named `TenantDecayChore` that runs every 100 ms (configurable via `hbase.ipc.server.shedding.tenant.decay.interval`). On each run, the chore performs three phases:

**Phase 1: Snapshot and reset.** The chore snapshots every active tenant's per-queue-type `LongAdder` cost counters (and request counters, when enabled) into the respective `AtomicLongArray` snapshot arrays, then resets the live counters. For each queue type, the chore also snapshots and resets the per-queue-type `totalAdmittedCost` adder (the capacity measurement for that resource pool) and the per-queue-type `totalRequests` adder (when enabled). Handler threads read only the pre-computed snapshots via O(1) `AtomicLongArray.get()` reads, which provide per-element volatile semantics under the Java Memory Model, and never call `sum()` themselves. This keeps the handler hot path entirely wait-free.

**Phase 2: Compute admission limits via weighted max-min fair water-filling.** For each queue type independently, the chore runs a water-filling algorithm that distributes the measured capacity (`snapshotTotalAdmittedCost[queueType]`) among demand-active tenants in proportion to their configured weights. The algorithm guarantees that `sum(admissionLimits) = capacity` whenever total demand exceeds capacity, so the system is provably work-conserving — no handler throughput is ever stranded. Unused capacity from under-demanding tenants flows to over-demanding tenants in proportion to their weights.

```java
void computeAdmissionLimits(QueueType qt) {
  long capacity = snapshotTotalAdmittedCost.get(qt.ordinal());
  if (capacity <= 0) return;

  List<TenantAlloc> active = new ArrayList<>();
  double totalWeight = 0;
  for (Map.Entry<String, TenantStats> e : tenants.entrySet()) {
    long demand = e.getValue().snapshotCost.get(qt.ordinal());
    if (demand > 0) {
      double weight = shareCache.getShare(e.getKey());
      active.add(new TenantAlloc(e.getValue(), demand, weight));
      totalWeight += weight;
    }
  }

  // Sort by normalized demand (demand / weight) ascending.
  // Tenants with the smallest normalized demand are satisfied first;
  // their unused capacity flows to heavier consumers.
  active.sort(Comparator.comparingDouble(
      t -> (double) t.demand / t.weight));

  long remaining = capacity;
  double remainingWeight = totalWeight;
  int last = active.size() - 1;
  for (int i = 0; i <= last; i++) {
    TenantAlloc t = active.get(i);
    long weightedShare = (i < last)
        ? Math.round(remaining * (t.weight / remainingWeight))
        : remaining;
    long limit = Math.min(t.demand, weightedShare);
    t.stats.admissionLimit.set(qt.ordinal(), limit);
    remaining -= limit;
    remainingWeight -= t.weight;
  }
}
```

The algorithm processes tenants in ascending order of normalized demand (`demand / weight`). For each tenant, it computes the tenant's weighted share of the remaining capacity using `Math.round()` (not truncation) so that fractional shares do not strand capacity when many tenants have small weighted shares. The admission limit is `min(demand, weightedShare)`. When a tenant's demand is below its weighted share, the surplus capacity remains in the pool and is distributed to subsequent (heavier) tenants. The last tenant in the sorted order receives the full remaining capacity to ensure we do not leak capacity out of the allocation. This is the standard progressive filling procedure for weighted max-min fairness, completing in O(N log N) time dominated by the sort.

**Phase 3: Eviction.** Every 60 seconds, the chore performs a full eviction sweep, iterating the tenant map and removing entries where all per-queue-type live counters and snapshot counters are zero (i.e., the tenant has had no activity on any queue type). At several hundred thousand tenants, this sweep takes ~10-30 ms. The eviction cycle is staggered by jittering the multiplier (+/-20%) per RegionServer to avoid synchronized sweeps across the cluster.

The per-queue-type structure means the chore iterates `3 x activeTenants` counters (one per queue type per tenant) for snapshotting, plus runs the water-filling algorithm independently for each of the three queue types. The inner loop is a simple `sum()` + `reset()` on each `LongAdder`, and the constant factor of 3 is negligible relative to the cache-friendly sequential access pattern. The water-filling sort adds O(N log N) per queue type, but at 100,000 tenants this is ~2-5 ms, well within the 100 ms chore interval.

#### Fair-share Configuration

Each tenant's entitlement is expressed as a *weight* (called a *share* in the configuration interface). A tenant with weight 2 is entitled to twice the throughput of a tenant with weight 1. Weights are dimensionless — only their ratios matter.

The `TenantDecayChore` converts these weights into per-tenant, per-queue-type *admission limits* via the weighted max-min fair water-filling algorithm described in the Background Decay section above. A tenant is *demand-active* if any of its per-queue-type `snapshotCost` values is non-zero in the current measurement window. When a tenant stops sending traffic on all queue types, it is excluded from the water-filling computation at the next snapshot boundary (within 100 ms), and its capacity entitlement is redistributed to the remaining active tenants in proportion to their weights. This ensures that an idle tenant's capacity is immediately available to its neighbors, eliminating stranded capacity without waiting for the 60-second eviction sweep. Weights are global (not per-queue-type): a tenant's configured weight determines its entitlement uniformly across all queue types. The per-queue-type scoping applies to the *admission limit* (the output of water-filling, reflecting both weights and actual demands), not to the *weight* itself. This is intentional. A tenant entitled to 25% of the server's resources should be entitled to 25% of the read queue, 25% of the write queue, and 25% of the scan queue, but if the tenant demands less than 25% in one queue type, the surplus flows to other tenants rather than being stranded.

Tenant shares must be managed dynamically. The design follows the existing quota subsystem's pattern for dynamic configuration, adjustment, and management. Shares are stored in an HBase system table and administered through a shell command or administrative API, with each RegionServer caching them locally and refreshing periodically.

##### Storage

Tenant shares are stored in the existing `hbase:quota` table, adding a new row-key prefix `s.<tenantId>` (for "share") alongside the existing `u.` / `t.` / `n.` / `r.` prefixes. A single column `q:s` holds the share as a double. The default share (applied to any tenant with no explicit row) is stored in a well-known row `s.__default__`. This reuses the quota table's existing infrastructure. No new system table, no new column family, and no schema migration is required.

##### Administration

A new shell command and corresponding Admin RPC:

`hbase> set_tenant_share 'tenant-42', 2.5`  
`hbase> set_tenant_share '__default__', 1.0`  
`hbase> unset_tenant_share 'tenant-42'`  
`hbase> list_tenant_shares`

These mirror the existing `set_quota` and `list_quotas` commands. The Master's `MasterQuotaManager` handles the write path, validating and persisting to `hbase:quota`. At O(300,000) tenants this is 300,000 rows in a system table, well within HBase's comfort zone.

##### Cache and Refresh

Each RegionServer maintains a `TenantShareCache,` refreshed by a `ScheduledChore`, following the same pattern as `QuotaCache` and its `QuotaRefresherChore`. The refresh period should match the quota cache refresh period (a reasonable starting point is 30 seconds, configurable via `hbase.ipc.server.shedding.tenant.share.refresh.period`). The chore interval must incorporate randomized jitter (+/-20%) to evenly distribute read pressure across the RegionServer hosting the `hbase:quota` region. Without jitter, 1,000 RegionServers refreshing simultaneously every 30 seconds will cause a thundering herd of scan requests. On each refresh the chore scans the `s.` prefix of `hbase:quota` and replaces the map atomically. The `TenantDecayChore` reads from this cache when computing admission limits via the water-filling algorithm. Between refreshes, a `ConfigurationObserver.onConfigurationChange()` callback can trigger an immediate re-scan, so that an operator who changes a share and reloads config sees the effect without waiting for the next chore cycle.

##### Bulk Management

For large tenant populations, the shell commands support file-based bulk import:

`hbase> set_tenant_shares_from_file '/path/to/shares.csv'`

where the CSV is `tenantId,share` pairs. This avoids the need for per-tenant interactive commands.

Only tenants needing non-default treatment require explicit entries. Tenants with no row in the table receive the `__default__` share (1.0 if not explicitly set).

##### Practical Weight Management

Weights (called "shares" in the configuration interface) are dimensionless. Their absolute values do not matter, only their ratios. A weight of 10 is not "ten requests per second"; it means "ten times the entitlement of a tenant with weight 1." This relative model avoids any dependency on absolute server capacity, which varies with hardware, workload mix, and time of day. The `TenantDecayChore` converts these weights into absolute admission limits via the water-filling algorithm, which automatically accounts for the actual measured capacity and the demands of all active tenants.

Consider an example in which a SaaS platform operates three service tiers on a shared HBase cluster. The operator configures shares to reflect the contractual priority of each tier:

`hbase> set_tenant_share '__default__', 1.0`  
`hbase> set_tenant_share 'platinum-acme-corp', 10.0`  
`hbase> set_tenant_share 'platinum-globex', 10.0`  
`hbase> set_tenant_share 'gold-initech', 5.0`  
`hbase> set_tenant_share 'gold-umbrella', 5.0`  
`hbase> set_tenant_share 'bronze-hooli', 1.0`

All tenants not explicitly configured receive the `__default__` share of 1.0, equivalent to the Bronze tier. The resulting weight fractions depend on which tenants are active on a given RegionServer. If all six tenants above are active simultaneously, the total weight is 32:

| Tier | Tenant | weight | weight fraction | Entitlement |
| :---- | :---- | :---- | :---- | :---- |
| Platinum | acme-corp | 10 | 31.25% | ~2x a Gold tenant |
| Platinum | globex | 10 | 31.25% | ~2x a Gold tenant |
| Gold | initech | 5 | 15.625% | ~5x a Bronze tenant |
| Gold | umbrella | 5 | 15.625% | ~5x a Bronze tenant |
| Bronze | hooli | 1 | 3.125% | Baseline |
| Bronze | (default) | 1 | 3.125% | Baseline |

Suppose CoDel detects sustained overload on the read queue with measured capacity of 1,000 cost units. `hooli` (Bronze, weight 1) is demanding 400 cost units (40% of capacity) while its weighted entitlement is only 3.125%. Meanwhile `acme-corp` (Platinum, weight 10) is demanding 100 cost units (10% of capacity), well within its 31.25% entitlement, and the other four tenants each demand 125 cost units.

The `TenantDecayChore` runs the water-filling algorithm: tenants are sorted by normalized demand (demand / weight). Hooli (400/1 = 400) has the highest normalized demand and is processed last. Under-demanding tenants (acme-corp at 100/10 = 10, etc.) receive their full demand, and their unused capacity flows to hooli. In this scenario total demand equals capacity (1,000), so the five under-demanding tenants receive their full demand (100 + 125×4 = 600) and hooli receives the remaining 400 — exactly its demand — so it is at its admission limit.

| Tenant | demand | admissionLimit | dropProbability | Client experience |
| :---- | :---- | :---- | :---- | :---- |
| acme-corp (Platinum) | 100 | 100 | **0.0** | No probabilistic shedding. May still experience CoDel baseline shedding if queue delays are severe. |
| initech (Gold) | 125 | 125 | **0.0** | No probabilistic shedding. Same CoDel baseline exposure. |
| hooli (Bronze) | 400 | 400 | **0.0** | At admission limit; no probabilistic shedding. If hooli demanded more (e.g. 800), it would be capped and face drop probability 1 − (400/800) = 0.5, scaling with over-demand. |

The Platinum and Gold tenants face zero probabilistic shedding. In this balanced scenario hooli is also at its limit because unused capacity from the others flowed to it. The critical point is proportionality: if hooli had demanded more than the 400 units that flowed to it, it would face probabilistic shedding (e.g. demand 800 → limit 400 → 50% dropped), while under-limit tenants would still see none. Because the water-filling produces admission limits that sum to the full capacity, all handler throughput is utilized and no capacity is stranded. Handler threads drain the queue fast enough that delays stay below the CoDel base threshold, so under-limit tenants see no drops at all. If hooli were flooding at 100x its entitlement, the drop rate would scale correspondingly, keeping the queue draining even under extreme load.

During an incident, an operator can instantly elevate a tenant's protection without restarting any servers:

`hbase> set_tenant_share 'bronze-hooli', 10.0`

The `TenantShareCache` picks up the change within 30 seconds. Hooli's weight increases from 1 to 10, which causes the next water-filling computation to allocate a much larger admission limit. If the tenant's demand still exceeds the new admission limit, shedding continues but at a reduced rate. If the new limit exceeds its actual demand, shedding stops entirely.

Revoking special treatment is equally straightforward:

`hbase> unset_tenant_share 'bronze-hooli'`

Hooli reverts to the `__default__` share (1.0), and its shedding behavior is recalculated accordingly.

#### Drop Probability Computation

The drop probability for a tenant is derived from its precomputed admission limit, which is calculated by the `TenantDecayChore` using the weighted max-min fair water-filling algorithm (see the Background Decay section above). The hot path reads the admission limit and the tenant's demand from the most recent snapshot:

```java
long tenantDemand = stats.getSnapshotCost(queueType);
long admissionLimit = stats.getAdmissionLimit(queueType);
double dropProbability;
if (tenantDemand <= admissionLimit) {
  dropProbability = 0.0;  // at or below admission limit; no probabilistic shedding
} else if (admissionLimit <= 0) {
  dropProbability = 1.0;  // zero allocation with positive demand: shed all
} else {
  dropProbability = 1.0 - ((double) admissionLimit / tenantDemand);
}
```

Here `admissionLimit` is the tenant's per-queue-type allocation computed by the background chore's water-filling algorithm. It represents the maximum cost this tenant should consume from this queue type's capacity in the current window, accounting for both the tenant's configured weight and the unused capacity left over by under-demanding tenants. `tenantDemand` is the tenant's `snapshotCost[queueType]` (its demand in this queue type). Both values are scoped to the queue type of the dequeuing queue, so a tenant's read demand is never evaluated against write capacity (or vice versa).

The water-filling algorithm guarantees that `sum(admissionLimit_t for all active tenants t) = capacity` whenever total demand exceeds capacity. This property ensures the system is work-conserving. Every unit of handler throughput is allocated to some tenant. No capacity is stranded, even when some tenants demand less than their weighted share. Unused capacity flows to over-demanding tenants in proportion to their weights.

The formula `1 - (admissionLimit / tenantDemand)` is naturally bounded between 0.0 and 1.0 when `tenantDemand > admissionLimit > 0`, requiring no manual floor or ceiling parameters. When `tenantDemand` equals `admissionLimit`, the probability is exactly 0.0. When `admissionLimit <= 0` but `tenantDemand > 0` (e.g., a tenant received zero allocation due to extreme weight skew or the first window before the chore runs), the drop probability is 1.0: all of that tenant's requests in the queue type are probabilistically shed. As `tenantDemand` grows large relative to `admissionLimit`, the probability approaches 1.0. A tenant that was idle in the previous window has `tenantDemand = 0`, which is <= `admissionLimit`, so it faces zero probabilistic drop probability. When the server itself is idle (`capacity == 0`), no shedding occurs because `isOverloaded` is false. The standard CoDel threshold check (`callDelay > baseThreshold`) remains as a fallback for symmetric overload and for the brief convergence period (~200 ms) when the capacity measurement stabilizes after a fresh overload transition.

The following scenarios illustrate the admission limit computation and the resulting drop probabilities for a single queue type. All demand measurements and capacity are per-queue-type (e.g., within the read queue). Consider a RegionServer serving three tenants with the following configured weights, and a per-queue-type capacity of 1,000 cost units per window:

| Tenant | weight_t | weight fraction |
| :---- | :---- | :---- |
| A | 2 | 2/4 = **50%** |
| B | 1 | 1/4 = **25%** |
| C | 1 | 1/4 = **25%** |

**Scenario 1: All tenants at their weighted share**

All three tenants are active. Tenant A demands 500, B demands 250, C demands 250 (total demand = 1,000 = capacity).

Water-filling: all tenants demand exactly their weighted share. Each receives their full demand as their admission limit.

`admissionLimit_A = 500, tenantDemand_A = 500`  
`dropProbability_A = 0.0`

No probabilistic shedding occurs. The tenant's requests are still subject to the standard CoDel threshold check (`callDelay > baseThreshold`), identical to vanilla CoDel behavior. If queue delays are severe enough, some requests may still be dropped by this baseline mechanism.

**Scenario 2: Tenant A as noisy neighbor, B under-limit**

Tenant A demands 2,000. Tenant B demands 100. Tenant C demands 300.

Water-filling (capacity = 1,000, sort by normalized demand = demand / weight):

1. B (demand=100, weight=1, norm=100): weightedShare = 1000 × (1/4) = 250. limit = min(100, 250) = **100**. remaining = 900, remainingWeight = 3.
2. C (demand=300, weight=1, norm=300): weightedShare = 900 × (1/3) = 300. limit = min(300, 300) = **300**. remaining = 600, remainingWeight = 2.
3. A (demand=2000, weight=2, norm=1000): weightedShare = remaining = 600. limit = min(2000, 600) = **600**. remaining = 0.

Total allocated: 100 + 300 + 600 = 1,000. Full capacity utilization.

`dropProbability_A = 1.0 - (600 / 2000) = 0.70`

70% of Tenant A's requests in this queue type are probabilistically shed. Note that A receives an admission limit of more than its 50% weighted share because B's unused capacity flows to the remaining tenants in proportion to their weights. This is the work-conserving property of max-min fairness. No capacity is stranded. From Tenant A's perspective, roughly 70% of its RPCs return `CallDroppedException` instead of a result. The client enters its overloaded-server retry path, backing off with `hbase.client.pause.server.overloaded` delays. This smoothly trims the excess, converging Tenant A's effective throughput toward its 600-unit admission limit.

**Scenario 3: Tenant B is well-behaved**

While Tenant A floods this queue type, Tenant B is demanding only 100 cost units, well below its admission limit of 100 (set by the water-filling above, which allocates exactly B's demand since it is under its weighted share).

`tenantDemand_B (100) <= admissionLimit_B (100)`  
`dropProbability_B = 0.0`

Tenant B faces zero probabilistic drop probability. The tenant-fair mechanism does not target it. Because the water-filling produces aggressive shedding of Tenant A's traffic (70% dropped), handler threads drain the queue fast enough that delays stay well below the CoDel base threshold. From Tenant B's perspective, the overload caused by Tenant A is largely invisible. Under Adaptive LIFO, Tenant B's freshest requests are dequeued first and served immediately, providing the best possible latency.

**Scenario 4: Tenant C is idle**

Tenant C sent no requests in any queue type in the previous measurement window. C is not demand-active and is excluded from the water-filling computation. Tenant A demands 2,000. Tenant B demands 100.

Water-filling (capacity = 1,000, active tenants A(weight=2) and B(weight=1)):

1. B (demand=100, weight=1, norm=100): weightedShare = 1000 × (1/3) = 333. limit = min(100, 333) = **100**. remaining = 900, remainingWeight = 2.
2. A (demand=2000, weight=2, norm=1000): weightedShare = remaining = 900. limit = min(2000, 900) = **900**. remaining = 0.

Total allocated: 100 + 900 = 1,000. Full capacity utilization.

`dropProbability_A = 1.0 - (900 / 2000) = 0.55`

Tenant A's admission limit increases from 600 (when C was active) to 900, reflecting the redistribution of C's idle capacity. A's drop probability decreases from 70% to 55%. No capacity is stranded. If Tenant C begins sending requests, it will be included in the water-filling computation at the next snapshot boundary (within 100 ms), and A's admission limit will decrease accordingly.

#### Scaling to Large Tenant Populations

In a deployment with hundreds or thousands of tenants, individual admission limits are correspondingly smaller. For example, with 100 equal-weight tenants and a queue type processing 1,000 cost units per window, each tenant's baseline weighted share is 10 cost units. The water-filling handles this gracefully:

| Tenant | weight | demand | admissionLimit | dropProbability | Interpretation |
| :---- | :---- | :---- | :---- | :---- | :---- |
| X (noisy) | 1 | 20,000 | 19 | 1 - (19/20000) = **0.999** | 99.9% of requests shed |
| Y (normal) | 1 | 10 | 10 | **0.0** (at limit) | No probabilistic shedding |
| Z (light) | 1 | 1 | 1 | **0.0** (below limit) | No probabilistic shedding |

Water-filling with 100 equal-weight tenants and capacity 1,000: Z (demand 1) gets limit 1; Y and the other 97 tenants at demand 10 each get limit 10; the remaining capacity (19 units) goes to X. So X's admission limit is 19, not the baseline 10, because Z's unused 9 units and rounding surplus flow to the heaviest consumer. Tenant X, demanding 20,000 cost units against an admission limit of 19, sees 99.9% of its requests probabilistically shed, aggressive enough that handler threads can dequeue X's requests almost instantly, preventing queue buildup. Tenant Y, demanding exactly its admission limit, and Tenant Z, demanding one-tenth of its limit, both face zero probabilistic drop probability. They remain subject to the standard CoDel baseline check, but the aggressive shedding of Tenant X's excess ensures the queue drains faster than it fills, preventing the indiscriminate `CallQueueTooBigException` rejections that would punish under-limit tenants. Z's unused capacity (9 of its 10-unit allocation) flows to X and other over-demanding tenants through the water-filling, ensuring no throughput is stranded.

#### Feedback-Loop Dynamics

The admission limits are computed from cost and capacity snapshots taken during the previous decay window (100 ms ago by default). This creates a feedback loop with a one-window delay. Shedding decisions are based on slightly stale demand measurements. Because the water-filling guarantees that `sum(admissionLimit_t) = capacity` whenever total demand exceeds capacity, the total admitted traffic in each window converges to the full measured capacity. In the next window, `snapshotTotalAdmittedCost` reflects this full utilization, providing a stable capacity input for the next round of water-filling.

Oscillation is further dampened by three factors. First, the 100 ms decay window captures a statistically meaningful sample of requests at any non-trivial load. At 10,000 req/s per RegionServer (a modest load), each window contains ~1,000 requests, producing stable demand estimates with low variance. Second, the probabilistic formula is self-stabilizing. When shedding reduces a tenant's demand, its admission limit in the next water-filling round may increase, because it consumes less, leaving more capacity for redistribution, producing smooth convergence rather than overshoot. Third, the shedding policy only drops excess traffic. It does not admit extra traffic in compensation. When an over-limit tenant's requests are shed, its demand decreases and its drop probability decreases, but this simply means fewer of its requests are shed, not that it receives a burst of preferential treatment.

The per-queue-type capacity measurement exhibits a brief convergence lag at overload onset. During the first CoDel interval after overload is detected, the capacity snapshot still reflects the previous non-overloaded window where all traffic was admitted, so it may overstate the true handler throughput. The water-filling uses this initial (slightly high) capacity estimate, which causes slightly more traffic to be admitted than the handlers can process, but CoDel's sojourn-time tracking detects this and the capacity measurement self-corrects in the next window. By the second window, the capacity snapshot reflects the handlers' actual processing throughput under load for that specific queue type, and the water-filling becomes fully calibrated. Because capacity is measured per queue type, this convergence occurs independently in each resource pool. A read queue entering overload does not affect the write queue's capacity measurement. This brief transient does not cause queue overflow in practice: the CoDel base threshold check provides a safety net during convergence.

When tenant tracking is disabled (the default configuration), no `DropPolicy` is configured and behavior is identical to today's unmodified CoDel.

#### Drop Decision

Today's `needToDrop()` in `AdaptiveLifoCoDelCallQueue`:

```java
return isOverloaded.get() && callDelay > 2 * codelTargetDelay;
```

Proposed replacement with `DropPolicy` delegation:

```java
private final QueueType queueType;  // assigned at construction

private boolean needToDrop(CallRunner callRunner) {
  long now = EnvironmentEdgeManager.currentTime();
  RpcCall call = callRunner.getRpcCall();
  long callDelay = now - call.getReceiveTime();
  if (call.getDeadline() > 0 && call.getDeadline() <= now) {
    return true;
  }
  // --- CoDel interval state machine (unchanged) ---
  long localMinDelay = this.minDelay;
  if (now > intervalTime && !resetDelay.get()
      && !resetDelay.getAndSet(true)) {
    intervalTime = now + codelInterval;
    isOverloaded.set(localMinDelay > codelTargetDelay);
  }
  if (resetDelay.get() && resetDelay.getAndSet(false)) {
    minDelay = callDelay;
    return false;
  } else if (callDelay < localMinDelay) {
    minDelay = callDelay;
  }
  // --- Delegate to DropPolicy ---
  boolean overloaded = isOverloaded.get();
  long baseThreshold = 2 * codelTargetDelay;
  if (dropPolicy != null) {
    return dropPolicy.shouldDrop(call, callDelay,
        overloaded, baseThreshold, queueType);
  }
  return overloaded && callDelay > baseThreshold;
}
```

The `queueType` field is set at construction by `RpcExecutor`, which assigns `QueueType.READ`, `QueueType.WRITE`, or `QueueType.SCAN` based on the queue's position in the `RWQueueRpcExecutor` queue list. When `DropPolicy` is null (the default), the final line reproduces today's CoDel behavior exactly. When a `DropPolicy` is configured, the policy receives the CoDel state and the queue type, and makes the decision. The `TenantFairShareDropPolicy` implementation (shown in the Components section above) uses the `queueType` to read the correct per-queue-type admission limit and demand snapshot, preventing cross-queue pollution. Because the probabilistic drop decision is independent of `callDelay`, the queue's Adaptive LIFO dequeue strategy remains fully effective. Note that the early deadline check (line 4) safely short-circuits before `shouldDrop()` is called. The tenant's demand has already been recorded at dispatch time via `DropPolicy.recordDemand()`, so requests that expire in the queue are still counted as demand. This prevents a noisy neighbor from evading demand measurement by causing queue delays that expire their own requests before dequeue.

This decomposition keeps `AdaptiveLifoCoDelCallQueue` focused on CoDel's interval state machine, min-delay tracking, Adaptive LIFO, and queue mechanics and delegates the *who-to-shed* question to a pluggable policy that can be evolved, replaced, or extended independently.

#### Integration with Existing Code

The change is confined to the `ipc` package. No changes to `SimpleRpcScheduler`, the protobuf definitions, or the quota subsystem are required. `RpcExecutor` requires two small changes: it must instantiate the `DropPolicy` based on configuration and pass it (along with the `QueueType`) to the queue constructor, and it must call `DropPolicy.recordDemand()` on the dispatch path before enqueuing each call. `RWQueueRpcExecutor` requires a minor change to assign `QueueType.WRITE`, `QueueType.READ`, or `QueueType.SCAN` to each queue group when calling `initializeQueues()`.

##### Queue class

`AdaptiveLifoCoDelCallQueue` gains two new optional constructor parameters: a `DropPolicy` reference and a `QueueType`. When the policy is null, `needToDrop()` falls through to the vanilla CoDel comparison (today's behavior). When non-null, `needToDrop()` delegates to `DropPolicy.shouldDrop()`, passing the queue's `QueueType`, as shown in the Modified Drop Decision section. `RpcExecutor` is responsible for instantiating the correct `DropPolicy` implementation based on configuration:

`hbase.ipc.server.callqueue.codel.shedding.policy.class = <none>`  
`hbase.ipc.server.callqueue.codel.tenant.enabled = false`

Setting `codel.tenant.enabled = true` is a convenience alias that sets the policy class to `TenantFairShareDropPolicy`. Both default to off, making this a zero-risk change for existing deployments. The `DropPolicy` instance is shared across all `AdaptiveLifoCoDelCallQueue` instances within an `RpcExecutor`. `RWQueueRpcExecutor` assigns `QueueType.WRITE` to its write queues, `QueueType.READ` to its read queues, and `QueueType.SCAN` to its scan queues. When the non-splitting `BalancedQueueRpcExecutor` is used (no read/write/scan split), all queues receive `QueueType.READ` as the default, collapsing the per-queue-type tracking to a single pool (functionally equivalent to global tracking). The policy's `onConfigurationChange()` method is called by `RpcExecutor.onConfigurationChange()` alongside the existing `updateTunables()` calls.

##### Dispatch-path demand recording

Demand must measure *arrival rate*, not *processing rate*. Recording demand at dequeue time (inside `shouldDrop()`) would allow a noisy neighbor to evade demand measurement: if a tenant's burst causes massive queue delays, many of their requests expire before being dequeued and are dropped by the early deadline check in `needToDrop()` — before `shouldDrop()` is ever called. These expired requests would skip demand recording entirely, causing the water-filling algorithm to see drastically lower demand than the tenant's true arrival rate, under-computing their admission limit and weakening the shedding response.

To close this gap, demand is recorded on the dispatch (enqueue) path. `RpcExecutor.dispatch()` calls `dropPolicy.recordDemand(call, queueType)` strictly *before* the call is offered to the queue and before the `CallQueueTooBigException` bounds check. This ensures that every arriving request — including those that are immediately rejected because the queue is full, those that later expire in the queue, and those that are dropped by CoDel or the drop policy — is counted as demand. The water-filling algorithm thus sees the tenant's true arrival rate, and the admission limit correctly reflects the tenant's actual load on the system.

The `QueueType` is known at dispatch time because `RWQueueRpcExecutor` has already selected the target queue (read, write, or scan) based on the call type before calling `dispatch()`. When the non-splitting `BalancedQueueRpcExecutor` is used, all calls are dispatched with `QueueType.READ` as the default.

Capacity remains measured at dequeue time via `recordAdmitted()`, called only for requests that pass the drop decision and will be processed by a handler thread. This separation — demand on the enqueue path, capacity on the dequeue path — ensures that the water-filling inputs are semantically correct: demand reflects what tenants *want*, capacity reflects what the server *can process*.

##### Tenant Identity Plumbing

The `TenantFairShareDropPolicy` resolves tenant identity by delegating to its configured `TenantIdentityMapper`. The extractor is instantiated once during policy initialization based on the `hbase.ipc.server.shedding.tenant.extractor.class` configuration property and shared across all queue instances via the shared policy reference.

All information the built-in extractors need is already available on the `RpcCall` at dispatch time (and therefore also at dequeue time). The protobuf is fully deserialized by `ServerRpcConnection` before `RpcExecutor.dispatch()` is called, so tenant identity resolution at the dispatch site performs only field reads, not deserialization:

* **Request/connection attributes** flow from `TableBuilder.setRequestAttribute()` into `RequestHeader`, deserialized lazily in `ServerCall.getRequestAttribute(key)`. No new protocol fields are needed. This is the mechanism used by the `RequestAttributeTenantIdentityMapper`.

* **Row key bytes** are available from the deserialized request protobuf (e.g., `GetRequest.getGet().getRow()`, `MutateRequest.getMutation().getRow()`). The protobuf is fully deserialized by `ServerRpcConnection` before the call enters the queue, so the `RowKeyTenantIdentityMapper` performs only a field read and byte-array slice, not deserialization.

* **Table name and namespace** are available from the `RegionSpecifier` in the request protobuf, used by the `TableNameTenantIdentityMapper`.

* **The mTLS certificate chain** is available via `RpcCallContext.getClientCertificateChain()` (HBASE-28317), used by the `CertificateTenantIdentityMapper`.

Clients that do not produce an identifiable tenant are assigned to the `__default__` tenant with share 1.0.

##### Configuration reloads

`RpcExecutor.onConfigurationChange()` already iterates over its queues and calls `updateTunables()` on each `AdaptiveLifoCoDelCallQueue`. It now also calls `DropPolicy.onConfigurationChange()` if a policy is configured. The `TenantFairShareDropPolicy` uses this callback to pick up changed tunables. Tenant shares are managed separately: the `TenantShareCache` refreshes from `hbase:quota` on its own `ScheduledChore` cycle and can be triggered immediately via `onConfigurationChange()`. The `TenantTracker` reads the share cache by reference, so share updates are visible to the next drop decision without restarting any queues.

##### Adaptive LIFO compatibility

HBase's RPC queues normally switch from FIFO to LIFO when occupancy exceeds `lifoThreshold` (default 0.8). LIFO helps under overload by serving the freshest requests first, maximizing the fraction that are still within the client's timeout window. This is arguably the "secret sauce" of server-side CoDel.

Tenant-fair probabilistic shedding is fully compatible with Adaptive LIFO. Because the drop probability is computed from the tenant's precomputed admission limit (`1 - admissionLimit / tenantDemand`), which is independent of `callDelay`, the decision is equally valid regardless of whether the dequeued request is fresh (LIFO) or aged (FIFO).

No changes to the `lifoThreshold` configuration or the LIFO/FIFO switching logic are required when tenant-aware shedding is enabled.

#### Metrics

A new gauge is exposed via the existing `MetricsHBaseServer`:

`tenantActiveShedding` (gauge: number of tenants currently above their admission limit while overloaded)

#### Introspection Servlet

The `tenantActiveShedding` gauge tells operators that tenant-fair shedding is active. Per-tenant metrics are intentionally excluded from the Hadoop metrics2 pipeline to avoid cardinality explosions at scale. Operators still need per-tenant visibility, but on an on-demand basis. A new introspection servlet tells them who is being shed, why, and whether the configuration is behaving as intended. It is the primary tool for validating the subsystem in production.

The servlet is registered at `/codel` on the RegionServer's info server. It is implemented as a standard `HttpServlet`, which restricts access to the same SPNEGO/Kerberos authentication and IP-based ACL that protect the existing admin endpoints. No new authentication mechanism is required.

The servlet produces JSON by default (`Accept: application/json`), with an optional `?format=text` (or `Accept: text/plain`) query parameter for human-readable plain-text output suitable for quick checks with `curl`. The JSON response is a single object with two top-level sections:

```json
{
  "timestamp": 1740000000000,
  "codelOverloaded": true,
  "tenantSheddingEnabled": true,
  "activeTenants": 1247,
  "tenantsAboveAdmissionLimit": 3,
  "baseThresholdMs": 200,
  "adaptiveLifoEnabled": true,
  "extractorClass": "org.apache.hadoop.hbase.ipc.RowKeyTenantIdentityMapper",
  "capacity": {
    "read": 125400000,
    "write": 34200000,
    "scan": 89100000
  },
  "shareCache": {
    "lastRefreshTimestamp": 1739999970000,
    "lastRefreshDurationMs": 12,
    "configuredTenants": 5,
    "defaultShare": 1.0
  },
  "decayChore": {
    "intervalMs": 100,
    "lastRunTimestamp": 1739999999900,
    "lastRunDurationMs": 3,
    "evictionIntervalMs": 60000,
    "lastEvictionTimestamp": 1739999940000,
    "lastEvictionRemovedCount": 42
  },
  "tenants": [
    {
      "tenantId": "tenant-42",
      "configuredWeight": 2.5,
      "byQueueType": {
        "read":  { "snapshotCost": 71230000, "snapshotRequests": 7123,
                    "admissionLimit": 41800000, "dropProbability": 0.414 },
        "write": { "snapshotCost": 12000000, "snapshotRequests": 1200,
                    "admissionLimit": 12000000, "dropProbability": 0.0 },
        "scan":  { "snapshotCost": 6000000, "snapshotRequests": 600,
                    "admissionLimit": 6000000, "dropProbability": 0.0 }
      },
      "aboveAdmissionLimit": true
    },
    {
      "tenantId": "tenant-7",
      "configuredWeight": 1.0,
      "byQueueType": {
        "read":  { "snapshotCost": 3120000, "snapshotRequests": 312,
                    "admissionLimit": 3120000, "dropProbability": 0.0 },
        "write": { "snapshotCost": 2000000, "snapshotRequests": 200,
                    "admissionLimit": 2000000, "dropProbability": 0.0 },
        "scan":  { "snapshotCost": 0, "snapshotRequests": 0,
                    "admissionLimit": 0, "dropProbability": 0.0 }
      },
      "aboveAdmissionLimit": false
    }
  ]
}
```

The top-level `capacity` object shows `snapshotTotalAdmittedCost` for each queue type, giving operators immediate visibility into the measured throughput of each resource pool. Each tenant's `byQueueType` object breaks down `snapshotCost`, `snapshotRequests`, `admissionLimit`, and `dropProbability` per queue type, making cross-queue pollution and admission limit violations immediately diagnosable. The `aboveAdmissionLimit` flag is `true` if the tenant's demand exceeds its admission limit in *any* queue type. The `tenants` array is sorted by the maximum ratio of `snapshotCost / admissionLimit` across all queue types descending, the most over-limit tenants first. The `snapshotRequests` fields are present only when request counting is enabled (the recommended default); they are omitted from the response when tracking is disabled. By default only the top 50 tenants are returned to keep the response size bounded. A `?limit=N` query parameter overrides this. A `?tenant=<id>` parameter filters to a single tenant, useful for investigating a specific complaint. An `?aboveAdmissionLimit=true` parameter returns only tenants currently exceeding their admission limit in at least one queue type. A `?queueType=read` parameter filters the sort and `aboveAdmissionLimit` evaluation to a single queue type.

The servlet reads directly from the in-memory `TenantTracker`. No table scans, no file I/O, and no RPC calls are needed to serve the response. The data reflects the most recent `TenantDecayChore` snapshot. The servlet acquires no locks so it cannot block handler threads or the decay chore. Computing the response requires iterating the active tenant map to read each tenant's snapshot counters and share. At 100,000 active tenants, this might take ~10-50 ms and produce a ~10 MB JSON response (with `?limit=1000`). With the default limit of 50 tenants, serialization is sub-millisecond regardless of population size because the sort uses a partial (top-K) selection rather than a full sort.

If tenant-aware shedding is disabled, the endpoint is not registered and returns 404. This prevents confusion when the feature is off.

Operators might use this servlet as follows:

**Validating extractor configuration.** After deploying a new `TenantIdentityMapper` configuration, the operator queries the servlet. If most traffic lands in `__default__`, the extractor is not resolving tenant identity correctly. The `extractorClass` field in the response confirms which extractor is active.

**Investigating a tenant complaint.** An operator receives a report that "tenant-42 is experiencing elevated error rates." They query `/codel?tenant=tenant-42` and check whether `aboveAdmissionLimit` is true and what the `dropProbability` is. A `dropProbability` of 0.80 tells the operator immediately that the system is shedding 80% of that tenant's traffic. If tenant-42 is above its admission limit, the shedding is working as designed. If tenant-42 is below its admission limit but still being shed (via the standard CoDel threshold fallback), the `dropProbability` of 0.0 confirms that the issue is symmetric overload, not tenant-specific shedding.

**Automated alerting.** A monitoring sidecar can poll `/codel?aboveAdmissionLimit=true&limit=10` every 30 seconds and feed the results into a log aggregation system or alerting pipeline. Because the polling frequency is controlled by the operator (not by the metrics scrape interval), the load is predictable and bounded.

**Capacity planning.** Periodic snapshots of the full tenant list (e.g., daily via a cron job querying `/codel?limit=1000`) provide historical data on tenant traffic distribution, which feeds into capacity planning models for cluster sizing and share rebalancing.

#### Scaling

Per-tenant state: three `LongAdder` instances (cost, one per queue type) \+ one `AtomicLongArray` (cost snapshots, length 3) \+ one `AtomicLongArray` (admission limits, length 3) \+ map entry overhead ~ 240 bytes with cost tracking only. With the recommended request counting enabled, add three `LongAdder` instances (requests) \+ one `AtomicLongArray` (requests snapshots, length 3) ~ 360 bytes total. At 100,000 tenants: ~24-36 MB. At 300,000 tenants: ~72-108 MB. All creation is lazy (via `computeIfAbsent`). Eviction of idle tenants is performed by the `TenantDecayChore` background thread. The per-queue-type structure increases per-tenant memory by a constant factor of 3 relative to global tracking, but the total footprint remains modest relative to typical RegionServer heap sizes (8-32 GB). In practice, many tenants will have zero traffic on one or two queue types (e.g., a read-only analytics pipeline has no write demand), and their per-queue-type counters simply remain at zero with negligible overhead.

Per-request overhead is split across two paths. On the **dispatch (enqueue) path**, `recordDemand()` performs: one `TenantIdentityMapper.getTenantId()` call (~10-40 ns depending on the implementation), one `ConcurrentHashMap.computeIfAbsent()` (which degrades to a simple `get()` for previously-seen tenants, ~20-40 ns), one `LongAdder` increment for per-tenant demand tracking (`cost[queueType]`; ~10-20 ns), plus two additional `LongAdder` increments when request counting is enabled (per-tenant `requests[queueType]` and global `totalRequests[queueType]`). Estimated dispatch-path overhead: ~40-100 ns. On the **handler (dequeue) path**, `shouldDrop()` performs: one `call.getSize()` call (~5 ns), one `TenantIdentityMapper.getTenantId()` call (~10-40 ns, resolving the same tenant identity a second time; this can be eliminated by caching the resolved tenant ID on the `ServerCall` at dispatch time), one `ConcurrentHashMap.get()` (~20-40 ns), two `AtomicLongArray.get()` snapshot reads (demand and admission limit, indexed by queue type), a division for the drop probability computation (~5-10 ns), and a `ThreadLocalRandom.nextDouble()` call (~5-10 ns, zero-allocation, only when the tenant exceeds its admission limit). For non-dropped requests, one `LongAdder` increment for per-queue-type capacity tracking (`totalAdmittedCost[queueType]`; ~10-20 ns). Estimated dequeue-path overhead: ~55-125 ns. Combined per-request total: ~95-225 ns in the worst case (without tenant ID caching), or ~65-165 ns with tenant ID caching. All O(1), lock-free, and zero-allocation for known tenants. The per-queue-type array indexing adds negligible cost (~1-2 ns per access, inlined by the JIT). The first request from a previously-unseen tenant allocates one `TenantStats` object (including its per-queue-type counter arrays) via `computeIfAbsent`.

Background chore overhead: the `TenantDecayChore` iterates all active tenants every 100 ms to snapshot and reset cost counters (and request counters, when enabled), then runs the weighted max-min fair water-filling algorithm independently for each of the three queue types. The snapshotting phase is O(N) per queue type; the water-filling phase is O(N log N) per queue type, dominated by the sort. At 100,000 active tenants the total chore cycle takes ~10-30 ms depending on configuration; at 300,000 tenants ~30-80 ms. This runs on a dedicated background thread and does not affect handler-thread latency. The water-filling allocates an `ArrayList` per queue type per cycle; at 100,000 tenants this is ~2-4 MB of short-lived garbage per cycle, collected in the next minor GC. The eviction sweep (every ~60 seconds) adds a similar cost but is amortized over a much longer interval.

Configuration: one default share entry covers all unconfigured tenants. Explicit entries needed only for tenants requiring differentiated treatment. Shares refreshed from `hbase:quota` on a 30-second chore cycle.

#### Relationship to Other Future-Work Items

This proposal composes cleanly with the other future-work items described elsewhere in this document:

* **Handler-thread saturation as a shedding signal.** Could feed an additional `isOverloaded` signal into the CoDel state machine, orthogonal to the tenant probabilistic shedding.

* **Adaptive target delay.** Adjusts `codelTargetDelay` (and therefore `baseThreshold`), complementing the probabilistic tenant shedding which operates independently of the delay threshold.

* **End-to-end deadline propagation.** The client-provided deadline is an additional drop criterion checked *before* the CoDel threshold test, orthogonal to tenant fairness.

* **Quota subsystem.** Quotas operate post-dequeue in `RSRpcServices` and are complementary: quotas enforce hard rate limits per user/table/namespace; the shedding policy handles overload-time fairness. The two do not interfere.

## Availability Impact Assessment

The proposal's dominant effect on availability is positive: it transforms CoDel's indiscriminate shedding into a policy-aware system that protects important tenants while shedding noisy neighbors. The primary risks (misattribution, staleness) are mitigated by conservative defaults, opt-in enablement, runtime-disablable feature flag, and monitoring. The failure modes degrade gracefully to today's unmodified CoDel rather than introducing new catastrophic states.

### Failure Modes and Mitigations

*Incorrect tenant identity causes unfair shedding*

If the configured `TenantIdentityMapper` cannot resolve a tenant (e.g., clients do not set the request attribute, or the row key format does not match the extractor's configuration), traffic lands in the `__default__` tenant bucket. A large volume of unattributed traffic inflates `__default__`'s demand, causing that synthetic tenant to exceed its admission limit and be shed aggressively, penalizing well-behaved clients who simply did not opt in.  
**Mitigation:** Tenant tracking is opt-in. It is only enabled when explicitly configured. The `TenantIdentityMapper` plugin interface supports multiple strategies: operators select the extractor that matches their deployment (request attributes for proxy-based architectures, row key parsing for Phoenix multi-tenant tables, table name/namespace patterns, mTLS certificates). In the worst case, disabling tenant tracking at runtime (`codel.tenant.enabled = false` via config reload) instantly reverts to unmodified CoDel.

*Stale tenant share cache causes prolonged unfairness*

The `TenantShareCache` refreshes every 30 seconds from `hbase:quota`. If the Master is unavailable or the `hbase:quota` table is itself under load, the cache retains its last-known state. A share that should have been updated (e.g., an emergency increase for a critical tenant) could be delayed by up to one full refresh period.  
**Mitigation:** The refresh period is configurable and can be shortened. The `onConfigurationChange()` callback provides an immediate refresh path. The stale cache is a *conservative* failure: it retains the previously-known shares rather than reverting to defaults, so the system continues operating with the last-good configuration. This is the same staleness model the existing quota subsystem already tolerates in production (HubSpot runs 30-second refresh at >25 M req/s).

*`TenantTracker` memory growth with many transient tenants*

If tenant IDs are high-cardinality (e.g., derived from end-user session IDs rather than application identities, or from a `RowKeyTenantIdentityMapper` misconfigured to extract a high-cardinality field), the `ConcurrentHashMap` could grow to hundreds of thousands or millions of entries.  
**Mitigation:** The `TenantDecayChore` performs periodic eviction of idle tenants (zero requests in both current and previous windows) on its background thread, never inline on handler threads. A tenant becomes eligible for eviction after two decay intervals (200 ms at default settings), when both its live and snapshot counters reach zero, but the entry is not actually removed until the next eviction sweep (every 60 seconds by default). This bounds the map to the number of distinct tenants that sent at least one request within the eviction interval (~60 seconds at default settings). The operator must choose tenant IDs at the right granularity - application or service identity, not end-user identity - and verify that the chosen `TenantIdentityMapper` produces IDs at the intended cardinality.

### Availability Improvements

Without tenant-fair shedding, CoDel drops requests indiscriminately once the sojourn threshold is breached. A "noisy neighbor" tenant consuming 80% of a queue type's throughput causes 80% of the drops in that queue to be borne by *all* tenants proportionally. With tenant-fair shedding, the over-limit tenant's requests are probabilistically shed in proportion to how far their demand exceeds their max-min fair admission limit, while under-limit tenants face zero probabilistic drop probability. The admission limits are computed by the `TenantDecayChore` using a weighted max-min fair water-filling algorithm that distributes all available capacity to active tenants in proportion to their weights. The system is provably work-conserving. No handler throughput is stranded, and unused capacity from under-demanding tenants flows to over-demanding tenants. A tenant flooding at 100x its admission limit sees a ~99% drop rate, ensuring that handler threads can dequeue fast enough to prevent queue filling and the indiscriminate `CallQueueTooBigException` rejections that would punish under-share tenants. Under-share tenants remain subject to the standard CoDel baseline check (`callDelay > baseThreshold`), but in typical noisy-neighbor scenarios the aggressive shedding of over-limit traffic drains the queue fast enough that delays stay below the base threshold, effectively shielding under-share tenants entirely.

Because shedding is cost-weighted (tracking payload size via `call.getSize()`), heavy-resource operations like large MultiGets and wide scans register proportional cost. A tenant saturating server resources with a few massive requests is correctly identified as consuming more than its admission limit, while a well-behaved tenant issuing many lightweight Gets is not falsely penalized. This eliminates the "payload skew" vulnerability that request-count-based tracking would create.

Unlike static rate limiters or HTB, this proposal requires no a-priori estimation of server capacity. The per-queue-type capacity measurement (`snapshotTotalAdmittedCost[queueType]`) is self-calibrating: it emerges naturally from the handlers' observed throughput for each resource pool, adapting automatically to hardware changes, workload shifts, queue configuration changes, and cluster expansions without reconfiguration, reducing operational risk. Because capacity is measured per queue type, each resource pool (read, write, scan) tracks its own throughput independently, preventing cross-queue pollution where a tenant's behavior in one queue type distorts their treatment in another.

The admission-limit formula `P = 1 - (admissionLimit / tenantDemand)` produces a mathematically smooth spectrum of shedding intensity that scales with the overload factor. Because admission limits are computed via water-filling rather than simple fractional math, the system avoids the "death spiral" of stranded capacity that afflicts direct fractional shedding formulas (see Alternatives Considered). Under severe or symmetric overload, all tenants may experience some CoDel baseline drops, but the shedding remains proportional: over-limit tenants always absorb more than under-limit tenants.

`codel.tenant.enabled` defaults to `false`. Enabling CoDel alone does not activate any new shedding logic. The feature can be enabled, observed in production via metrics, and disabled via configuration reload without restart. This allows incremental rollout with full rollback capability.

## Performance Impact Analysis

Under overload, the proposal yields a net performance *improvement* by shedding over-limit tenants first and shortening overload episodes. The steady-state overhead of ~95-225 ns per request (\<0.1% of typical RPC latency), split across dispatch and handler paths, is the cost of admission. With tenant ID caching on the `ServerCall` (eliminating the redundant identity resolution at dequeue), this drops to ~65-165 ns. The dominant cost at extreme scale (several hundred thousand tenants) is the `TenantDecayChore` cycle (snapshotting + water-filling + eviction), which runs on a dedicated background thread and is bounded at ~30-80 ms per 100 ms interval at 300,000 tenants (depending on whether optional request counting is enabled) — never touching the handler hot path. Memory overhead scales linearly (~240 bytes per tenant with cost tracking only, ~360 bytes with request counting enabled, covering all three queue types plus admission limits). GC and I/O impacts are negligible at all projected scales.

### Per-Request Overhead

The existing `needToDrop()` executes on every `take()`, inside the handler thread, while holding no locks. It performs: one `EnvironmentEdgeManager.currentTime()` call, one subtraction for `callDelay`, two `volatile` reads (`minDelay`, `intervalTime`), conditional `AtomicBoolean` CAS operations for the interval reset, one `volatile` read of `isOverloaded`, and one comparison. Total: ~5-8 operations, all register-level or L1-cache resident, well under 100 ns on modern hardware.

The added work per request is split across two paths. On the dispatch (enqueue) path, `DropPolicy.recordDemand()` dispatches to `TenantFairShareDropPolicy.recordDemand()`, which calls `call.getSize()` (~5 ns), then `TenantIdentityMapper.getTenantId()`: ~10-15 ns for the `RequestAttributeTenantIdentityMapper` (one hash-map lookup on request attributes), ~20-40 ns for the `RowKeyTenantIdentityMapper` (one protobuf field read \+ byte-array scan for delimiter or slice for prefix), or ~15-25 ns for the `TableNameTenantIdentityMapper` (one field read \+ optional regex match, cached). A `ConcurrentHashMap.computeIfAbsent()` (~20-40 ns, degrades to `get()` for known tenants) retrieves or creates the `TenantStats` reference. One mandatory `LongAdder` increment follows: per-tenant `cost[queueType]` (`LongAdder.add(cost)`, ~10-20 ns). When request counting is enabled, two additional increments: per-tenant `requests[queueType]` and global `totalRequests[queueType]`. Estimated dispatch-path overhead: ~40-100 ns. On the handler (dequeue) path, `DropPolicy.shouldDrop()` calls `call.getSize()` (~5 ns), resolves the tenant identity again via `TenantIdentityMapper.getTenantId()` (~10-40 ns; eliminable by caching the resolved tenant ID on the `ServerCall` at dispatch time), and retrieves the `TenantStats` via `ConcurrentHashMap.get()` (~20-40 ns). The drop probability computation reads two `AtomicLongArray.get()` snapshot values (demand and admission limit, both indexed by queue type) and computes a division (~5-10 ns). When the tenant exceeds its admission limit, `ThreadLocalRandom.current().nextDouble()` generates the random value (~5-10 ns, zero-allocation). For non-dropped requests, one `LongAdder` increment records the per-queue-type admitted cost (~10-20 ns). The per-queue-type array indexing adds only an `ordinal()` call (~1-2 ns, inlined by the JIT) per counter access, with no additional memory indirection since the arrays are small fixed-size (length 3) and reside in the same cache line as the `TenantStats` object header. Estimated dequeue-path overhead: ~55-125 ns. Combined per-request total: ~95-225 ns without tenant ID caching, ~65-165 ns with caching. For comparison, a single HBase `Get` RPC that hits the block cache typically takes 200-500 us end to end; the shedding overhead is <0.1% of request latency. At 100,000 req/s per RegionServer, the aggregate CPU cost is ~9.5-22.5 ms/s of wall time, or roughly 1-2% of a single core.

`LongAdder` is specifically designed for high-frequency concurrent updates. It stripes across multiple cells and only contends during `sum()`. The proposal never calls `sum()` on either hot path. It reads only the pre-computed snapshots via `AtomicLongArray.get()`, which provides per-element volatile semantics. Demand recording (`recordDemand()` on the dispatch path) and the drop decision (`shouldDrop()` on the handler path) write to different `LongAdder` instances (demand counters vs. admitted-cost counters) and do not contend with each other beyond the `ConcurrentHashMap.get()`, which is lock-free for existing keys. `ThreadLocalRandom` is per-thread and requires no synchronization. Under high thread counts (e.g., 100+ handler threads), `LongAdder` contention may add an additional ~10-20 ns due to cell striping, but this remains far below the threshold of measurability against RPC-level latency. Note that the dispatch path (`RpcExecutor.dispatch()`) is typically called from a Netty I/O thread or the `ServerRpcConnection` reader thread, not from handler threads, so demand recording and capacity recording contend on different `LongAdder` stripe sets.

### Background Chore Overhead

The `TenantDecayChore` performs three phases per cycle: (1) snapshot and reset all active tenants' per-queue-type cost counters (and request counters, when enabled), O(3 × active tenants) counter operations; (2) run the weighted max-min fair water-filling algorithm independently for each of the three queue types, O(N log N) per queue type, dominated by the sort; and (3) periodic eviction of idle tenants (every ~60 seconds). The chore runs on a dedicated background thread. At 1,000 active tenants, the full cycle takes ~200-500 us, negligible relative to the 100 ms interval. At 100,000 active tenants, snapshotting takes ~10-20 ms and water-filling adds ~2-5 ms for the sort — total ~12-25 ms. At 300,000 active tenants, ~30-80 ms total. Even at 300,000 tenants the chore completes within its 100 ms interval. If chore duration approaches the interval at extreme scale, the interval can be lengthened (at the cost of slightly staler snapshots and admission limits). The eviction sweep adds a comparable cost, amortized over the longer eviction period. Handler threads never call `sum()`, `reset()`, sort, or iterate the tenant map.

### Performance Under Overload

The overhead analysis above applies to the *non-overloaded* path, where all requests pass through `needToDrop()` and are served. Under overload the performance impact is dominated by *what is shed*, not by the shedding decision's CPU cost.

Without fairness, an over-limit tenant's requests may be admitted, consume handler time, and still time out on the client side because the server is too slow. The shedding policy drops these requests at dequeue time, before they consume any handler or I/O resources, so the resources are available for tenants whose requests can still succeed. This reduces the total CPU and I/O spent on doomed requests.

By shedding over-limit requests first, the server drains its queue faster and exits the overloaded state sooner. The duration of the overload episode is shortened, reducing the total number of requests affected. This is a second-order performance benefit that is difficult to quantify analytically but is consistently observed in CoDel-based systems that employ selective shedding.

### Performance When Disabled

When no `DropPolicy` is configured (the default), the new code path is guarded by an `if (DropPolicy != null)` null check. This is trivially branch-predicted and adds \<1 ns per request. The disabled configuration is performance-identical to today's CoDel within measurement error.

### Resource Overhead

Per the Scaling section: ~240 bytes per active tenant with cost tracking only (3 `LongAdder` instances + 1 `AtomicLongArray` for cost snapshots + 1 `AtomicLongArray` for admission limits, each length 3), ~360 bytes with the recommended request counting enabled (adding 3 more `LongAdder` instances + 1 `AtomicLongArray` for request snapshots). At 100 tenants: ~24-36 KB. At 100,000 tenants: ~24-36 MB. At 300,000 tenants: ~72-108 MB. The `TenantShareCache` (a `ConcurrentHashMap<String, Double>`) adds ~64 bytes per configured tenant, which is negligible since only explicitly-configured tenants have entries (most tenants use the default share). The per-queue-type global counters (`totalAdmittedCost` and `totalRequests` arrays) add a fixed 6 `LongAdder` instances plus 2 `AtomicLongArray` instances regardless of tenant count. The `TenantIdentityMapper` instances are stateless or hold only configuration-derived state (e.g., a compiled regex pattern, a delimiter byte). At the more typical 100,000-tenant scale, total memory for the feature is ~24-36 MB (with request counting enabled). At 300,000 tenants: ~72-108 MB depending on configuration, manageable relative to RegionServer heap sizes (typically 8-32 GB). The 3x memory increase from per-queue-type tracking (compared to global tracking) is the cost of preventing cross-queue pollution. In practice, the increase is partially offset by the fact that many tenants have zero traffic on one or two queue types, and their idle `LongAdder` instances occupy only the base object overhead without stripe expansion.

In steady state (all tenants previously seen), the hot path performs zero allocations. The only allocations are: one `TenantStats` object per newly-seen tenant (via `computeIfAbsent`), which includes its three per-queue-type `LongAdder` instances for cost, one `AtomicLongArray` for cost snapshots, one `AtomicLongArray` for admission limits (and three more `LongAdder` instances plus one `AtomicLongArray` for request snapshots, when enabled), amortized over the tenant's lifetime; periodic `TenantShareCache` refresh replaces the map reference, and the old map becomes garbage. At 30-second intervals with O(100) configured entries, this is ~10 KB of garbage per minute. The `TenantDecayChore` allocates an `ArrayList` per queue type per cycle for the water-filling sort; at 100,000 tenants this is ~2-4 MB of short-lived garbage per cycle (every 100 ms), collected in the next minor GC. This allocation is confined to the background thread and does not affect the handler hot path. The eviction sweep removes entries from the `ConcurrentHashMap` but does not allocate new objects; the evicted `TenantStats` instances (along with their per-queue-type counter arrays) become garbage, collected during the next minor GC.

At typical tenant populations (≤100,000), the water-filling allocation adds negligible GC pressure (~20-40 MB/s of short-lived garbage, well within the young generation's capacity). At 300,000 tenants, this increases to ~60-120 MB/s, which may be noticeable but remains bounded and predictable. If GC pressure is a concern at extreme scale, the water-filling sort can be performed in-place using an array pool, eliminating the per-cycle allocation entirely.

The `TenantShareCache` refresh performs a `Scan` on the `s.` prefix of `hbase:quota` every 30 seconds. With O(100) share entries, this scan returns a few KB of data - a single RPC to the RegionServer hosting the `hbase:quota` region. At O(100,000) entries, the scan returns ~5-10 MB, which may take 50-200 ms depending on the hosting RegionServer's load. At several hundred thousand entries, ~15-30 MB. This is a background operation that does not block the RPC hot path. The refresh schedule already incorporates randomized jitter (+/-20%) to prevent thundering-herd scans across RegionServers. If the scan latency is a concern at extreme scale, the cache can additionally be partitioned (e.g., by tenant-ID prefix) to parallelize the refresh across multiple RPCs.

The tenant identity extractor and tenant tracker add no network I/O.

## Operational Guidance and SLA Considerations

### How Tenants Experience Work Shedding

From a tenant's perspective, work shedding manifests as an increase in error rates. When a tenant's requests are shed, the HBase client receives a `CallDroppedException` with the `serverOverloaded` flag set. The client's standard retry logic engages automatically, pausing for `hbase.client.pause.server.overloaded` (default 500 ms) before retrying. Applications observe this as elevated error rates, increased tail latency, and reduced effective throughput.

Tenants at or below their admission limit face zero *probabilistic* drops. The tenant-fair shedding mechanism does not target them. In typical noisy-neighbor scenarios, where one or a few tenants cause the overload, the aggressive probabilistic shedding of over-limit traffic is sufficient to drain the queue and keep delays below the CoDel base threshold. Under-limit tenants experience no drops at all in these cases. However, where aggregate demand is so high that queue delays exceed the CoDel base threshold even after over-limit tenants are probabilistically shed, all tenants, including under-limit tenants, may experience some baseline CoDel shedding. The key is that this shedding is still proportional: over-limit tenants face *both* probabilistic shedding *and* baseline CoDel shedding, while under-limit tenants face *only* the baseline. The over-limit tenant always absorbs a larger fraction of the total drops.

### Recommended Share Configuration Patterns

**Equal-weight baseline.** The simplest deployment uses a single `__default__` share of 1.0 and no explicit tenant entries. All tenants receive equal protection. Under overload, the heaviest consumer (by cost) is shed first. This requires no per-tenant configuration and is a good starting point.

`hbase> set_tenant_share '__default__', 1.0`

**Tiered service model.** Assign weights proportional to each tier's business criticality. A common pattern:

| Tier | Weight | Rationale |
| :---- | :---- | :---- |
| Platinum | 10 | Mission-critical production traffic. Last to be shed. |
| Gold | 5 | Important but can tolerate brief degradation. |
| Silver | 2 | Standard production traffic. |
| Bronze / Default | 1 | Best-effort, batch, or development traffic. First to be shed. |

`hbase> set_tenant_share '__default__', 1.0`  
`hbase> set_tenant_share 'payments-service', 10.0`  
`hbase> set_tenant_share 'user-profile-service', 5.0`  
`hbase> set_tenant_share 'analytics-pipeline', 1.0`  
`hbase> set_tenant_share 'dev-test-env', 1.0`

Under overload, if `analytics-pipeline` is responsible for the load spike, it absorbs nearly all of the shedding. `payments-service` and `user-profile-service` continue operating at normal latency. If the overload is so severe that even Platinum tenants exceed their admission limits, the system sheds proportionally, but Platinum tenants' larger weights mean they receive much larger admission limits and must consume a much larger fraction of server resources before any of their requests are shed.

**Revenue-proportional weights.** For deployments where tenant revenue directly correlates with service priority, weights can be set proportional to monthly revenue (or any other continuous metric). A tenant paying 10x more than another receives 10x the weight. This produces a natural alignment between what the customer pays and what protection they receive during overload.

**Overcommitment and "burst credits."** Because weights are relative and only matter during overload, the system naturally supports overcommitment. Ten tenants with weight 10 each behave identically to ten tenants with weight 1 each. Only the ratios matter. During normal operation (no CoDel overload), all tenants can burst to 100% of the server. Weights only become relevant when aggregate demand exceeds supply.

### SLA Documentation

The work-shedding subsystem enables operators to offer differentiated service behaviors that were previously impossible with indiscriminate CoDel. The following is a recommended framework for communicating shedding behavior in SLA documentation.

#### What to Communicate to Customers

**"Your error rate may increase during cluster overload, but your weight determines how much."** This is the key message. Customers should understand that under normal operation, all tenants receive identical performance. The server's full capacity is available to every tenant. There are no reservations, no throttling, and no artificial limits. During overload conditions, a tenant demanding more than its max-min fair admission limit sees requests shed in proportion to how far its demand exceeds the limit. The system does not "cut off" any tenant — it gradually reduces throughput to converge on the tenant's admission limit, which reflects both the tenant's configured weight and the unused capacity from under-demanding neighbors.

**The standard client already handles shedding automatically.** The HBase client recognizes `CallDroppedException` as a transient overload signal and retries with configurable backoff. Applications do not need to implement custom retry logic. However, applications should set reasonable operation timeouts and be designed to tolerate transient latency increases.

**Share changes take effect without downtime.** If a customer's share should change, the operator updates the share, and the new protection level takes effect within 30 seconds across the cluster. No server restarts are required.

#### Recommended Practices

**Monitor tenant-level shedding via the introspection servlet.** Operators should set up periodic polling of `/codel?aboveAdmissionLimit=true` to detect when tenants are being actively shed. This provides a conveniently rolled up analysis of overload episodes and identifies which tenants are driving them.

**Use quotas for hard limits, weights for fair shedding.** The HBase quota subsystem can separately and independently enforce hard rate limits that apply regardless of server load. Weights govern behavior *only* during overload. For tenants that must never exceed a specific request rate, use quotas, which are out of scope of this proposal. For tenants that should be deprioritized during overload but otherwise unrestricted, use weights, the central contribution of this proposal.

**Document the `__default__` share.** New tenants that are onboarded without an explicit share entry receive the `__default__` share. Ensure that SLA documentation clearly states what the default tier provides and that customers who require higher protection must be explicitly configured.

## References

* [HBASE-15136: Explore different queuing behaviors while busy](https://issues.apache.org/jira/browse/HBASE-15136)

* [Fail at Scale (Ben Maurer, ACM Queue 2015)](https://queue.acm.org/detail.cfm?id=2839461)

* [Controlling Queue Delay (Nichols & Jacobson, ACM Queue 2012)](https://queue.acm.org/detail.cfm?id=2209336)

* [RFC 8289: CoDel Active Queue Management (IETF)](https://datatracker.ietf.org/doc/html/rfc8289)

* [Wangle CoDel implementation (Facebook)](https://github.com/facebook/wangle/blob/master/wangle/concurrent/Codel.cpp)

* [Using Load Shedding to Avoid Overload (AWS Builder's Library)](https://aws.amazon.com/builders-library/using-load-shedding-to-avoid-overload/)

* [Fairness in Multi-Tenant Systems (AWS Builder's Library)](https://aws.amazon.com/builders-library/fairness-in-multi-tenant-systems/)

* [Aequitas: Admission Control for Latency-Critical RPCs in Datacenters (Google Research)](https://research.google/pubs/aequitas-admission-control-for-latency-critical-rpcs-in-datacenters/)

* [Pisces: Scalable and Efficient Network-Aware Scheduling in Shared Storage (USENIX OSDI 2012)](https://www.usenix.org/system/files/conference/osdi12/osdi12-final-215.pdf)

* [Linux tc-htb: Hierarchical Token Bucket](https://man7.org/linux/man-pages/man8/tc-htb.8.html)

* [Using HBase Quotas to Share Resources at Scale (HubSpot Engineering Blog, Oct 2024)](https://product.hubspot.com/blog/hbase-share-resources)

* [HBASE-27657: Connection and Request Attributes](https://issues.apache.org/jira/browse/HBASE-27657)

* [HBASE-27784: Support quota user overrides](https://issues.apache.org/jira/browse/HBASE-27784)

* [HBASE-27800: Add support for default user quotas](https://issues.apache.org/jira/browse/HBASE-27800)

* [HBASE-28317: Expose client TLS certificate on RpcCallContext](https://issues.apache.org/jira/browse/HBASE-28317)

* [HBASE-28952: Add coprocessor hook to authorize user based on client SSL certificate chain](https://issues.apache.org/jira/browse/HBASE-28952)
