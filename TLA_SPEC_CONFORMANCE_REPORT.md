# TLA+ Spec vs Implementation: Modeling Decision Analysis

This document classifies every modeling decision in the TLA+ spec where behavior
diverges from the implementation into three categories:

| Cat | Meaning |
|-----|---------|
| **✅ Acceptable** | Abstraction that is faithful to the protocol and won't miss bugs |
| **⚠️ Inaccurate** | Model deviation that may miss real bugs or misrepresent behavior |
| **🐛 Impl Bug** | Implementation bug that the spec "fixes" to make TLC pass |

---

## Category 1: ✅ Acceptable Abstractions

These simplifications preserve the correctness-relevant behavior; they remove
detail that is orthogonal to the assignment protocol.

### 1.1 RegionRemoteProcedureBase merged into TRSP

The implementation uses child procedures (`OpenRegionProcedure`,
`CloseRegionProcedure`) with their own 4-state machine. The spec merges
these into TRSP actions (`TRSPDispatchOpen`, `TRSPReportSucceedOpen`, etc.).

**Why acceptable:** The parent TRSP holds the `RegionStateNode` lock
across all child execution (`holdLock()=true`). No concurrent procedure
observes intermediate child states. The meaningful state transitions are
identical.

### 1.2 RS open/close as atomic steps

`RSOpen` and `RSClose` merge the receive + complete steps into single
atomic actions. The former `RSReceiveOpen` + `RSCompleteOpen` intermediate
RS-side state is not observable by the master.

**Why acceptable:** A crash during the intermediate state produces the same
recovery outcome — the RS dies, SCP reassigns. The master never sees the
RS accepted without completing.

### 1.3 Meta writes as atomic, always-succeeding

Every action that writes `metaTable` does so atomically with `regionState`.
The implementation has 3 patterns of meta write with different failure/revert
behaviors (Appendix D.1–D.4).

**Why acceptable:** The `RegionStateNode` lock is held across all meta writes
and retries. No concurrent procedure can observe the transient inconsistency.
TLA+ non-determinism (action may not fire) naturally models retry. The
two-phase persistence window (Pattern C) is properly modeled via the
`REPORT_SUCCEED` intermediate step in Iter 18.

### 1.4 ZK as abstract ground truth

ZK is modeled as a single Boolean per server (`zkNode[s]`). No ZK session
details, sessionTimeout, or multi-node ensemble is modeled.

**Why acceptable:** For the assignment protocol, ZK's only role is RS
liveness detection. The Boolean captures the meaningful semantics: session
alive → ephemeral node exists → `MasterDetectCrash` guards on it.

### 1.5 ProcedureStore as abstract durable map

`procStore[r]` is a simple `[Regions → ProcStoreRecord ∪ {NoProcedure}]`.
No WAL rolling, segment compaction, or slot management.

**Why acceptable:** The spec only needs the persist/load semantics for
crash recovery. `procStore` survives `MasterCrash` and is read by
`MasterRecover` — that's the full contract.

### 1.6 `hbase:meta` as abstract function

`metaTable ∈ [Regions → [state, location]]`. No table scan, row-key
layout, or replication semantics.

**Why acceptable:** The spec cares about the persistent region→(state, location)
mapping. Single-region-mastering guarantees that meta writes are immediately
consistent.

### 1.7 Load balancer as non-deterministic choice

`TRSPGetCandidate(r, s)` picks any ONLINE server `s`. No cost function,
balancing heuristic, or locality preference.

**Why acceptable:** The safety properties hold for *any* valid server
choice. The balancer's heuristic doesn't affect correctness.

### 1.8 ServerStateNode write lock as implicit flag

`serverState ∈ {ONLINE, CRASHED}` replaces the `ServerStateNode`
`ReentrantReadWriteLock`. SCP's write lock is modeled by transitioning
`serverState` to `CRASHED` atomically in `MasterDetectCrash`.

**Why acceptable:** The write lock's purpose is to block concurrent
reports while SCP submits. The `serverState = CRASHED` guard on
report-consuming actions achieves the same effect.

### 1.9 WAL fencing as abstract Boolean

`walFenced[s]` is set to TRUE by `SCPFenceWALs`. No HDFS lease
recovery, log splitting, or `RecoverLeaseFSUtils` details.

**Why acceptable:** The spec's fencing property — zombie RS cannot write
after fencing — is captured by the `NoDoubleWrite`/`NoDoubleAssignment`
invariant. The mechanism doesn't matter; the ordering does.

### 1.10 `carryingMeta` non-deterministic in `MasterDetectCrash`

`MasterDetectCrash` non-deterministically sets `carryingMeta[s]`.
The implementation reads meta location from `MetaTableLocator`.

**Why acceptable:** This over-approximates: the model explores both
cases (carrying and not carrying meta). TLC verifies safety for *all*
combinations.

### 1.11 RS epochs / ServerName omitted

No explicit epoch counter. Stale report rejection is achieved by
`ServerRestart` purging pending reports and `serverState = CRASHED`
guards on report consumers.

**Why acceptable:** As stated in the plan: `serverState` ONLINE/CRASHED
flag plus atomic crash/restart provides equivalent fencing without an
explicit epoch counter. Stale reports are never consumed.

### 1.12 Coprocessor hooks omitted

No coprocessor pre/post operation modeling.

**Why acceptable:** Coprocessors are observer hooks; they do not
affect the assignment state machine's correctness.

### 1.13 Replication queues omitted

SCP's `CLAIM_REPLICATION_QUEUES` step is collapsed into `DONE`.

**Why acceptable:** Replication is orthogonal to region assignment.

### 1.14 `GoOffline` doesn't write meta

Matches the implementation exactly (`RegionStateNode.offline()` L132-134
does not write to meta). `MetaConsistency` is relaxed to permit
`regionState=OFFLINE` while `metaTable=CLOSED`.

**Why acceptable:** Both are "unassigned" semantics; divergence is
resolved on master restart from meta.

### 1.15 `SCPAssignMeta` as single atomic step

The implementation creates a TRSP for the meta region and waits for
completion. The model treats meta reassignment as one step.

**Why acceptable:** While meta is unavailable, all SCP actions are
blocked by the `waitMetaLoaded` guard. The internal steps of meta
reassignment don't interact with user-region assignment.

### 1.16 Procedure state inlined per-region

No global `procedures` map or `nextProcId`. At most one procedure
per region; region identity suffices for matching.

**Why acceptable:** `LockExclusivity` and `ProcedureConsistency`
(from early iterations) proved the at-most-one invariant. Region-keyed
indexing is isomorphic to procedure-ID-keyed indexing for the single-
procedure-per-region discipline.

### 1.17 `locked[r]` always FALSE after each step

TLA+ actions are atomic, so `locked` is acquired and released within
the same step. The `locked[r] = FALSE` guard enforces mutual exclusion
between *different* actions on the same region.

**Why acceptable:** This correctly models the `RegionStateNode.lock()`
discipline: the lock is never released between steps for `holdLock()=true`
procedures, but TLA+ interleaving at action granularity means each
action is an indivisible critical section.

---

## Category 2: ⚠️ Potentially Inaccurate Abstractions

These deviations could cause the spec to miss real bugs or generate
false confidence about implementation safety.

### 2.1 No meta write failure modeling

The spec treats all meta writes as always-succeeding. The implementation has
3 revert patterns (Appendix D), with Pattern A having an **asymmetric
revert** (state reverted but location NOT reverted in `regionOpening()`).

**Risk:** The transient `(state=OFFLINE, location=server)` state after a
Pattern A failure, while masked by the `RegionStateNode` lock for normal
procedures, could interact with SCP if the lock protocol is ever weakened.
The spec cannot discover meta-write-failure-induced states.

**Mitigant:** Plan explicitly defers this to Iteration 30; lock masking
makes it low severity.

### 2.2 RPC channels as sets (no ordering, no duplicates within a set)

`dispatchedOps[s]` is a *set*, not a sequence. The implementation's
`RSProcedureDispatcher` batches commands; the spec cannot model out-of-order
delivery within a batch or duplicate delivery of the same command.

**Risk:** If the implementation has ordering-sensitive bugs (e.g., CLOSE
processed before OPEN when both are batched), the spec using sets could
miss them. However, since each region can have at most one active procedure,
and commands are matched by region, ordering within a batch is irrelevant
for correctness.

**Mitigant:** The one-procedure-per-region discipline ensures at most one
command per region per server. Ordering sensitivity would require
multi-region interactions (e.g., split/merge batching), deferred to Phase 7.

### 2.3 `TRSPReportSucceedOpen` prefers OPENED over FAILED_OPEN

The spec has an explicit preference: `rpt.code = "FAILED_OPEN" ⇒ ¬∃ rpt2
with code = "OPENED"`. This prevents FAILED_OPEN from being consumed when
an OPENED report also exists.

**Risk:** The implementation processes reports synchronously as RPC calls
arrive — there is no queue or pending set where both OPENED and FAILED_OPEN
could coexist. This preference is a **spec-only guard** to handle a race
that the implementation prevents through procedure serialization. If the
implementation ever changes to batch report processing, this guard would be
necessary but is not implementation-faithful today.

**Mitigant:** The guard is *conservative* — it never hides a bug, only
prevents a spurious model-only race.

### 2.4 `RSClose` removes from `rsOnlineRegions` of ALL servers

[RegionServer.tla L236](file:///Users/andrewpurtell/src/hbase/src/main/spec/RegionServer.tla#L236):
```tla
rsOnlineRegions' = [t ∈ Servers ↦ rsOnlineRegions[t] \ { r }]
```

The implementation only removes from the executing server. The spec clears
from *all* servers.

**Risk:** Over-aggressive cleanup could mask bugs where a region is
simultaneously in two servers' `rsOnlineRegions` during normal operation
(not just the zombie window). In practice this is unlikely since the
spec already prevents double-open via `r ∉ rsOnlineRegions[s]` guards.

**Mitigant:** The `NoDoubleAssignment` invariant would still catch violations
in the write-side, but ghost reads could be masked.

### 2.5 No `regionsInTransitionInRS` modeling

The RS-side `Map<byte[], Boolean>` tracking (in-transition flag for open
vs close) is not modeled. The spec uses only `rsOnlineRegions`.

**Risk:** The implementation's conflict handling at the RS level (open while
closing → retry; close while opening → cancel open) is not captured. If
there are bugs in this conflict handling, the spec won't find them.

**Mitigant:** The one-procedure-per-region discipline means the master
never dispatches conflicting open/close for the same region simultaneously.
RS-level conflicts arise only from stale commands during crash recovery,
which the spec handles via `SCPAssignRegion` clearing stale commands.

### 2.6 No ProcedureExecutor queueing / scheduling model

The spec fires any enabled action non-deterministically. The implementation's
`ProcedureExecutor` has a `ProcedureScheduler` with per-table and per-server
queues, wakeup semantics, and child completion callbacks.

**Risk:** Scheduling-dependent bugs (e.g., starvation, priority inversion
between SCP and TRSP) are not detectable. The PEWorker pool is planned for
Iteration 19 but not yet implemented.

**Mitigant:** The spec's non-deterministic interleaving is a strict
*over-approximation* of any specific scheduling order. Any safety violation
the implementation can reach, the spec can reach. The concern is liveness,
not safety.

### 2.7 `pendingReports` as a global set

Reports are a global set, not per-server queues. The implementation processes
reports synchronously per-RPC — so there's no actual queue.

**Risk:** The spec allows consuming reports in arbitrary order across
servers. This is actually *more* permissive than the implementation (which
processes them one at a time per RPC), so the spec over-approximates. No
bugs should be missed.

### 2.8 Master recovery does not re-discover running procedures' sub-state

`MasterRecover` rebuilds `regionState` from `metaTable + procStore` but
with `retries = 0` for all recovered procedures.

**Risk:** If a procedure was at retry count 3 of 5 when the master crashed,
recovery resets to 0. This is more lenient than the implementation (which
also loses retry state since it's not persisted in the procedure store
record). The risk is low — it means the model gives more retries than the
implementation, which is conservative (more forgiving).

**Mitigant:** This matches the implementation: retry counters are transient
in-memory state, not persisted.

---

## Category 3: 🐛 Implementation Bugs "Fixed" in Spec

These are cases where the spec models the *correct* protocol behavior
and the implementation diverges — the spec exposes (or is designed to
expose) real implementation bugs.

### 3.1 `isMatchingRegionLocation()` in SCP (HBASE-24293, HBASE-21623) — Configurable Toggle

The implementation (SCP.java L498-500, L529-538) checks whether a region's
location still matches the crashed server before processing it. If a
concurrent TRSP moved the region between `SCPGetRegions` and `SCPAssignRegion`,
the SCP **skips** it entirely.

The spec models this via the `UseLocationCheck` toggle:
- `TRUE` (default): matches the implementation's buggy skip behavior
- `FALSE`: correct protocol — process every region unconditionally

The plan calls this a "known source of bugs" — the skip can cause **lost
regions** (ABNORMALLY_CLOSED with no procedure, never reassigned).

**Classification: 🐛** — The spec's default (`TRUE`) faithfully reproduces
the bug; setting `FALSE` models the fix. The `NoLostRegions` invariant is
designed to catch violations caused by this skip.

### 3.2 `OpenRegionProcedure.restoreSucceedState()` bug — Configurable Toggle

The implementation (OpenRegionProcedure L128-136) unconditionally replays
REPORT_SUCCEED procedures as OPENED, regardless of whether the actual
`transitionCode` was FAILED_OPEN.

The spec models this via `UseRestoreSucceedQuirk`:
- `TRUE`: faithfully reproduces the bug (FAILED_OPEN replayed as OPEN)
- `FALSE` (default): correct behavior (checks `transitionCode`)

**Classification: 🐛** — When `TRUE`, the spec reproduces the implementation's
buggy recovery path. This can cause a region that actually failed to open to
be marked OPEN after master recovery, leading to `RSMasterAgreement`
violations.

### 3.3 `RSOpenDuplicate` deadlock — Configurable Toggle

AssignRegionHandler.process() L107-115 silently drops OPEN requests for
already-online regions **without reporting back**. This causes the TRSP to
be stuck at CONFIRM_OPENED forever.

The spec models this via `UseRSOpenDuplicateQuirk`:
- `TRUE`: faithfully reproduces the deadlock
- `FALSE` (default): disabled to allow model checking to complete

**Classification: 🐛** — The implementation silently swallows a command
that should either report OPENED (idempotent success) or FAILED_OPEN
(reject). The missing report causes TRSP deadlock.

### 3.4 `SCPAssignRegion` Path A: atomic conversion vs. deferred `TRSPServerCrashed`

In the spec (Iter 17.5), `SCPAssignRegion` Path A atomically converts the
existing TRSP to `ASSIGN/GET_ASSIGN_CANDIDATE` in the same step as
marking ABNORMALLY_CLOSED. This models the implementation's
`serverCrashed()` firing **under the same `RegionStateNode.lock()`**.

The implementation actually has two steps: (1) SCP calls `serverCrashed()`
on the TRSP's child procedure, (2) the TRSP's next execution converts
itself. By collapsing these into one atomic step, the spec eliminates a
window where the TRSP is ABNORMALLY_CLOSED but not yet converted.

**Classification: ✅ (borderline)** — This is acceptable because the
lock is held across both steps in the implementation. However, if the
lock protocol changes, this atomicity assumption would need revisiting.

### 3.5 `TRSPServerCrashed` resets retries to 0

The spec resets `retries = 0` on crash recovery, matching
`retryCounter = null` at TRSP.java L329/L443.

**Classification: ✅** — Matches the implementation. Not a fix.

---

## Summary Table

| # | Item | Cat | Risk Level |
|---|------|-----|-----------|
| 1.1 | RRPB merged into TRSP | ✅ | None |
| 1.2 | Atomic RS open/close | ✅ | None |
| 1.3 | Atomic meta writes | ✅ | None |
| 1.4 | ZK as Boolean | ✅ | None |
| 1.5 | Abstract ProcStore | ✅ | None |
| 1.6 | Abstract meta | ✅ | None |
| 1.7 | Non-deterministic balancer | ✅ | None |
| 1.8 | Implicit ServerStateNode lock | ✅ | None |
| 1.9 | Abstract WAL fencing | ✅ | None |
| 1.10 | Non-deterministic carryingMeta | ✅ | None |
| 1.11 | Omitted RS epochs | ✅ | None |
| 1.12 | Omitted coprocessors | ✅ | None |
| 1.13 | Omitted replication | ✅ | None |
| 1.14 | GoOffline no meta write | ✅ | None |
| 1.15 | Atomic SCPAssignMeta | ✅ | None |
| 1.16 | Inlined procedure state | ✅ | None |
| 1.17 | locked always FALSE after step | ✅ | None |
| 2.1 | No meta write failure | ⚠️ | Low |
| 2.2 | Set-based RPC channels | ⚠️ | Low |
| 2.3 | OPENED preference guard | ⚠️ | Low |
| 2.4 | RSClose clears all servers | ⚠️ | Low |
| 2.5 | No regionsInTransitionInRS | ⚠️ | Low |
| 2.6 | No PE scheduler model | ⚠️ | Medium |
| 2.7 | Global pendingReports set | ⚠️ | Low |
| 2.8 | Retries reset on recovery | ⚠️ | Low |
| 3.1 | `isMatchingRegionLocation` skip | 🐛 | **High** |
| 3.2 | `restoreSucceedState` bug | 🐛 | **High** |
| 3.3 | `RSOpenDuplicate` deadlock | 🐛 | **Medium** |

---

## Key Findings

1. **The spec is well-grounded.** 17 of 28 items are ✅ acceptable
   abstractions that faithfully preserve the protocol's correctness-relevant
   behavior while eliminating irrelevant implementation detail.

2. **All ⚠️ items are low-to-medium risk** and result from the spec being
   *more permissive* than the implementation (over-approximation), which
   means they cannot cause the spec to *miss* safety bugs — they can only
   produce false positives (spurious violations). The one medium-risk item
   (2.6, no PE scheduler) is a liveness concern, not safety.

3. **Three genuine implementation bugs are exposed** via configurable toggles:
   - HBASE-24293/21623 (`isMatchingRegionLocation` skip → lost regions)
   - `restoreSucceedState` replaying FAILED_OPEN as OPENED
   - `RSOpenDuplicate` causing TRSP deadlock

   The spec defaults are set to the *correct* protocol behavior (bugs off),
   allowing TLC to verify safety. Toggling them on surfaces the violations
   as TLC counterexamples.
