# TLA+ Issue Verification Plan: Assignment Manager Bugs and Failure Patterns

**Date:** 2026-03-10

## 1. Summary

This plan describes how to model and verify the Assignment Manager issues documented in `ASSIGNMENT_MANAGER_ISSUES.md` within the existing TLA+ specification.

The issues are classified into two tiers based on amenability to TLA+ modeling:

**Tier 1 — Directly Modelable**

Bugs in assignment/procedure state machines that can be expressed as togglable guard changes, missing checks, or additional state transitions. Each gets a `Use*Quirk` toggle.

**Tier 2 — Modelable with New Spec Extensions**

Bugs requiring modest new modeling (e.g., table state, ProcedureStore bypass semantics, unbounded retry) that extend the spec beyond the current iteration plan.

## 2. Tier 1 — Directly Modelable Issues

These are bugs in the assignment/SCP/TRSP state machines that can be reproduced
by adding a `Use*Quirk` conditional to an existing action. Each entry includes:
(a) the Java source path, (b) the TLA+ action affected, (c) the invariant
expected to be violated, and (d) the spec change.

### 2.1 `UseStateLoadAssertionQuirk` — HBASE-29552

**Bug:** `RegionRemoteProcedureBase.stateLoaded()` wraps `restoreSucceedState()`
exceptions in `AssertionError`, which aborts the master. When a `CloseRegionProcedure`
has `REPORT_SUCCEED` state but the region's in-memory state (from meta) is OPENING
(not CLOSING/CLOSED), the restoration throws `UnexpectedStateException` → `AssertionError`.

```java
// RegionRemoteProcedureBase.java (stateLoaded logic)
// Wraps IOException in AssertionError — aborts master
```

**TLA+ Action:** `RestoreSucceedState` in `ProcStore.tla`

**Spec Change:**
- Add `UseStateLoadAssertionQuirk ∈ BOOLEAN` to `Types.tla`.
- In `RestoreSucceedState`, when the persisted `transitionCode = "CLOSED"` but the
  region's in-memory state (from `metaTable`) is `"OPENING"` (state mismatch):
  - If `UseStateLoadAssertionQuirk = TRUE`: crash master (`masterAlive' = FALSE`).
  - If FALSE (correct): skip restoration, leave the region as-is for SCP to handle.
- This requires a sequence: (1) master crash during a CLOSE that has recorded
  `REPORT_SUCCEED` in procStore but hasn't updated meta, (2) a concurrent OPEN updates
  meta to OPENING, (3) master recovers and hits the mismatch.

**Expected Violation:** `MasterEventuallyRecovers` liveness.

### 2.2 `UseSCPStompQuirk` — HBASE-21623

**Bug:** SCP at `SERVER_CRASH_ASSIGN` writes `ABNORMALLY_CLOSED` for a region
in meta via `RegionStateStore`, but a concurrent TRSP has already retried the
region on a *different* server and written `OPENING` with `location=newServer`.
SCP clobbers this, leaving the region stuck in OPENING forever.

```java
// ServerCrashProcedure.assignRegions() — no coordination with TRSP meta updates
// SCP writes ABNORMALLY_CLOSED overriding TRSP's OPENING on newServer
```

**TLA+ Action:** `SCPAssignRegion(s, r)` Path A in `SCP.tla`

**Spec Change:**
- Add `UseSCPStompQuirk ∈ BOOLEAN`.
- In `SCPAssignRegion` Path A (proc attached), when the region's `location` has
  already changed to a different server (i.e., `regionState[r].location ≠ s`
  but a TRSP is still attached at a further step like `CONFIRM_OPENED`):
  - If TRUE: SCP still proceeds to set `ABNORMALLY_CLOSED` and call
    `TRSPServerCrashed`, overwriting the TRSP's progress on the new server.
  - If FALSE (correct): SCP defers to the existing TRSP when it detects the
    location has changed to a live server.
- Note: This overlaps with the Iteration 16 skip path but covers the *Path A*
  case specifically — when a proc IS attached but its target has changed.

**Expected Violation:** `NoLostRegions`, region stuck in OPENING forever.

### 2.3 `UseInfiniteRetryQuirk` — HBASE-29006, HBASE-27614

**Bug(s):**
- `AssignmentManager.processAssignmentPlans()` retries assignments without bound.
- `OpenRegionProcedure.regionOpenedWithoutPersistingToMeta()` only updates seqnum
  when new > old, creating an infinite reopen loop if old > new.

```java
// AssignmentManager.java — processAssignmentPlans() catch block
// retries addToPendingAssignment() without limit

// OpenRegionProcedure.java L82 — regionOpenedWithoutPersistingToMeta()
// delegates to AM which conditionally updates seqnum only when new > old
```

**TLA+ Action:** `TRSPGetCandidate` / `TRSPHandleFailedOpen` in `TRSP.tla`

**Spec Change:**
- Add `UseInfiniteRetryQuirk ∈ BOOLEAN`.
- The existing spec uses `MaxRetries` to bound open retries. If `UseInfiniteRetryQuirk
  = TRUE`, remove the `retries ≥ MaxRetries` give-up path in `TRSPGiveUpOpen` — the
  region retries forever.

**Expected Violation:** Liveness — `AssignmentProgress` property violated (region
never reaches OPEN). Also leads to workload amplification.

### 2.4 `UseInfiniteWaitQuirk` — HBASE-28192, HBASE-26287

**Bug:** `HMaster.isRegionOnline()` polls with `Integer.MAX_VALUE` retries and
60-second backoff. If the meta/namespace region is on a dead server with a stale
ZK reference, the master loops forever.

```java
// HMaster.java L1408 — isRegionOnline()
// Loop polls RegionStates.isRegionOnline() with no upper bound
```

**TLA+ Action:** `MasterRecover` in `Master.tla`

**Spec Change:**
- Add `UseInfiniteWaitQuirk ∈ BOOLEAN`.
- In `MasterRecover`, after reconstructing state from meta + procStore, add a
  blocking condition: if meta's recorded location is a CRASHED server, either:
  - If `UseInfiniteWaitQuirk = TRUE`: the master recovery action is permanently
    disabled (blocks forever) — `masterAlive` stays `FALSE` after the crash.
  - If FALSE (correct): master auto-schedules SCP for the dead server and
    proceeds with recovery.

**Expected Violation:** `MasterEventuallyRecovers` liveness. Cluster unavailable.

### 2.5 `UseNoRecoveryOnUnknownServerQuirk` — HBASE-27711, HBASE-25142

**Bug:** `AssignmentManager.checkOnlineRegionsReport()` closes regions on stale
servers but does not schedule reassignment. `CatalogJanitor` reports "Unknown
Server" indefinitely without auto-scheduling SCP.

**TLA+ Modeling:**
- Add `UseNoRecoveryOnUnknownServerQuirk ∈ BOOLEAN`.
- New action `DetectUnknownServer(r)`: when a region's location references a
  server that is CRASHED/NONE, the master detects the orphan:
  - If TRUE (buggy): region is closed silently but no TRSP is created. Region
    stays CLOSED/OFFLINE with no procedure forever.
  - If FALSE (correct): master creates a TRSP(ASSIGN) for the orphaned region.
- This action fires after SCP completes but leaves orphaned region mappings.

**Expected Violation:** `NoLostRegions`.

### 2.6 `UseMasterAbortOnMetaWriteQuirk` — HBASE-23595

**Bug:** `RegionStateStore.updateRegionLocation()` catches `IOException` and calls
`master.abort(msg, e)` — crashing the entire master when meta is temporarily
unavailable (e.g., during SCP for the meta RS).

```java
// RegionStateStore.java L231
// catch (IOException) { master.abort("TODO: Revist!!!!", e); }
```

**TLA+ Action:** All meta-writing TRSP actions in `TRSP.tla`

**Spec Change:**
- Add `UseMasterAbortOnMetaWriteQuirk ∈ BOOLEAN`.
- In the meta-blocking disjuncts (`TRSPPersistToMetaOpen`, `TRSPPersistToMetaClose`,
  etc.), when `MetaIsAvailable = FALSE`:
  - If TRUE: set `masterAlive' = FALSE` (master aborts).
  - If FALSE (already modeled): block/suspend per `UseBlockOnMetaWrite`.

**Expected Violation:** `MasterEventuallyRecovers` liveness. Cascading failure:
meta RS crash → SCP → concurrent TRSP → meta write → master abort.

### 2.7 `UseSCPNoInterruptQuirk` — HBASE-20802, HBASE-21124

**Bug:** `RemoteProcedure` has no `interruptCall()` method. RPCs to zombie RS hang
until timeout (3 minutes). `NoServerDispatchException` causes permanent stuck.

**TLA+ Modeling:**
- Add `UseSCPNoInterruptQuirk ∈ BOOLEAN`.
- In `DispatchFail(r)` and `DispatchFailClose(r)`:
  - If TRUE: when the target server is CRASHED, the dispatch failure action is
    disabled (does not fire), modeling the hang. Only a timeout (modeled as a
    separate action with lower priority) eventually resolves it.
  - If FALSE: dispatch failure fires immediately for crashed servers.

**Expected Violation:** Liveness — `AssignmentProgress` violated. Regions stuck
in OPENING/CLOSING.

### 2.8 `UseGCResurrectionQuirk` — HBASE-22631

**Bug:** `AssignProcedure.handleFailure()` calls `regionNode.offline()` which
clears location to null, but `undoRegionAsOpening()` is a no-op because location
is already null. The region stays in `serverMap`. When the RS restarts, SCP
resurrects a GC'd region.

**TLA+ Modeling:**
- Add `UseGCResurrectionQuirk ∈ BOOLEAN`.
- In `TRSPHandleFailedOpen(r)`: when clearing the failed open,
  - If TRUE: leave `r` in `serverRegions[s]` (buggy — don't remove from server tracking).
  - If FALSE (current correct behavior): remove `r` from `serverRegions[s]`.
- Later when `s` crashes, `SCPGetRegions` picks up the stale region from
  `serverRegions[s]` and SCP reassigns a GC'd/split region.

**Expected Violation:** `SplitMergeMutualExclusion` or `KeyspaceCoverage` — a
parent region that was already SPLIT gets resurrected as OPENING, violating
keyspace integrity.

### 2.9 `UseStaleStateQuirk` — HBASE-23958, HBASE-22703

**Bug:** After restart with RIT, `RegionStateStore.visitMeta()` creates stale
`ServerStateNode` entries for dead/restarted servers. The balancer loops
indefinitely moving regions to these stale servers.

**TLA+ Modeling:**
- Add `UseStaleStateQuirk ∈ BOOLEAN`.
- In `MasterRecover`, when reconstructing `serverState` from `metaTable`:
  - If TRUE: populate `serverState[s] = "ONLINE"` for servers referenced in
    meta but not actually alive (stale entries). This enables `TRSPCreateMove`
    to target dead servers — `TRSPGetCandidate` picks them, `TRSPDispatchOpen`
    dispatches, `DispatchFail` fires, and the loop repeats.
  - If FALSE: only populate for servers with `zkNode[s] = TRUE` (actually alive).

**Expected Violation:** Liveness — balancer loops forever. Region churns
OPEN → CLOSING → CLOSED → OPENING → FAILED_OPEN → OPENING on repeat.

### 2.10 `UsePEStarvationQuirk` — HBASE-23593, HBASE-22334

**Bug:** On heavily loaded clusters, ORP/CRP procedures are created and added to
the scheduler but never picked up because all `PEWorker` threads are consumed by
other procedures (e.g., blocked on latch waits or meta RPCs).

**TLA+ Modeling:**
- Already partially modeled via `availableWorkers` (Iteration 19).
- Add `UsePEStarvationQuirk ∈ BOOLEAN`.
- In the meta-blocking disjuncts, when `UseBlockOnMetaWrite = TRUE` AND
  `UsePEStarvationQuirk = TRUE`: additionally decrement `availableWorkers` for
  non-meta-writing procedure steps that block on RPC thread latches (model as
  an extra worker held for the duration of each `TRSPCreate` action).
- This amplifies the thread exhaustion scenario:
  `TRSPCreate` holds worker → meta write blocks another worker → ORP never
  gets a worker → deadlock.

**Expected Violation:** `NoPEWorkerDeadlock` invariant, `AssignmentProgress` liveness.

## 3. Tier 2 — Modelable with New Spec Extensions

These require adding new variables, actions, or modules beyond the current
iteration plan, but remain within the assignment protocol domain. 

### 3.1 TRSP Timeout and Rollback — HBASE-25059

**Folds into:** Iteration 27 (Liveness). Iteration 27 introduces temporal
properties and `WF_vars` fairness constraints. The TRSP timeout is the natural
recovery mechanism that restores liveness when RS reports are lost.

**What to model:** Add a `TRSPTimeout(r)` action that fires when a TRSP has been
at `CONFIRM_OPENED` for "too long" (modeled non-deterministically).

**Integration plan:**
- New constant `UseTRSPTimeout ∈ BOOLEAN`.
- New action `TRSPTimeout(r)`:
  - Pre: `procStep = "CONFIRM_OPENED"`, `procType ∈ {"ASSIGN", "MOVE", "REOPEN"}`.
  - If `UseTRSPTimeout = TRUE`: TRSP rolls back — sets `FAILED_OPEN`, marks target
    RS as suspect, selects new target, transitions to `GET_ASSIGN_CANDIDATE`.
  - If `UseTRSPTimeout = FALSE` (current behavior): action is disabled; TRSP waits
    indefinitely.
- This is the *fix* — the quirk version (FALSE) reproduces the bug where TRSP
  hangs forever. The correct version (TRUE) demonstrates that the timeout
  restores liveness.
- Add `TRSPTimeout` to `Fairness` spec: `WF_vars(TRSPTimeout(r))` when
  `UseTRSPTimeout = TRUE`, ensuring the timeout eventually fires.

**Verification:** With `UseTRSPTimeout = FALSE` and a stuck RS, TLC should find
a liveness violation for `AssignmentProgress`. With `UseTRSPTimeout = TRUE`,
liveness should be restored. Run both configs as part of Iteration 27.

### 3.2 RIT Chore Remediation — HBASE-27773

**Folds into:** Post-Iteration 29. The RIT chore is a periodic master-side
thread that detects stuck RIT regions and kicks them. This is a defense-in-depth
mechanism, not core protocol — model after the core spec is complete.

**Integration plan:**
- New action `RITChoreRemediate(r)`: non-deterministic, fires when
  `regionState[r].state ∈ {"OPENING", "CLOSING"}` and time exceeds threshold
  (modeled as non-deterministic enablement).
- Effect: cancel the current dispatch, set `forceNewPlan = TRUE`, transition
  TRSP to `GET_ASSIGN_CANDIDATE`.
- This overlaps with `TRSPTimeout` (3.1) but applies at a different granularity
  (chore period vs. per-operation timeout).

### 3.3 Split Lock Held Indefinitely — HBASE-29256

**Folds into:** Iteration 24 (crash during split/merge). When a split
procedure's parent RS crashes mid-split, the `parentProc` lock may be held
forever if pre-PONR rollback doesn't release it cleanly.

**Integration plan:**
- In Iteration 24, `MasterCrashDuringSplit` and `SplitRollback` actions
  already handle pre-PONR and post-PONR crash recovery.
- Add a `UseSplitLockTimeoutQuirk ∈ BOOLEAN`:
  - If TRUE: when `parentProc[r].step = "SPAWNED_CLOSE"` and the target RS
    crashes, the lock is NOT released — `parentProc[r]` stays non-`NONE`
    forever, preventing any new procedures on the involved regions.
  - If FALSE: crash recovery releases the lock (rollback clears `parentProc`).
- Verification: `NoLostRegions` + liveness — regions involved in the split
  are permanently locked, never reassigned.

### 3.4 Stuck InitMetaProcedure — HBASE-24924

**Folds into:** Iteration 27 (Liveness). The `MasterRecover` action currently
assumes meta becomes available eventually. If `InitMetaProcedure` is blocked on
a latch that is never released (due to racing `waitMetaLoaded`), master hangs.

**Integration plan:**
- Extend `MasterRecover` with a gate: if `metaAvailable = FALSE` and no SCP is
  pending for the meta RS, the master enters a stuck state.
- Add `UseInitMetaLatchQuirk ∈ BOOLEAN` to model the latch bug.
- Verification: `MasterEventuallyRecovers` liveness.

### 3.5 InitMetaProcedure Race — HBASE-24923

**Folds into:** Iteration 27, alongside 3.4. If meta assignment completes (via
SCP) before `InitMetaProcedure` runs, `InitMetaProcedure` sees meta as already
assigned and skips its initialization, but `postMetaHandler` was never called.

**Integration plan:**
- Add `UseInitMetaSkipQuirk ∈ BOOLEAN`:
  - If TRUE: `MasterRecover` skips `InitMeta` when meta is already OPEN,
    leaving `masterInitialized = FALSE`.
  - If FALSE: `MasterRecover` always runs `InitMeta` to completion.
- Verification: `MasterEventuallyRecovers`.

### 3.6 Truncate Crash Data Loss — HBASE-26883

**Folds into:** Post-Iteration 29. Requires a new `TruncateTableProcedure`
parent procedure type, analogous to `SplitTableRegionProcedure`. This is a
significant extension: new `parentProcType = "TRUNCATE"`, new child TRSP
sequencing (unassign all → delete regions → create new regions → assign all),
new crash recovery for each step.

**Integration plan (deferred):**
- New module `Truncate.tla` with `TruncatePrepare`, `TruncateUnassign`,
  `TruncateDeleteMeta`, `TruncateCreateMeta`, `TruncateAssign` actions.
- Crash between `TruncateDeleteMeta` and `TruncateCreateMeta` is the bug:
  old regions deleted from meta but new regions not yet created → data loss.
- Quirk: `UseTruncateCrashQuirk` — skip the crash guard between delete and create.
- Verification: `KeyspaceCoverage` — keyspace has a hole.

### 3.7 Merged Region HBCK Race — HBASE-29692

**Folds into:** Iteration 23–24 (merge). After merge is modeled, an HBCK
assign action can be added that assigns a region that has already been merged.

**Integration plan:**
- New action `HBCKAssign(r)`: non-deterministic admin action.
  Pre: `regionKeyRange[r] = NoRange` (merged/deleted) or `regionState[r].state
  = "MERGED"`. Effect: create TRSP(ASSIGN) for `r`.
- If the merged region is resurrected, it overlaps the new merged-into region.
- Verification: `KeyspaceCoverage` — overlapping keyspaces.
- Gated by `UseHBCKAssign ∈ BOOLEAN`; `FALSE` in primary configs.
