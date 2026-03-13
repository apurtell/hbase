# TLA+ Issue Verification Plan: Assignment Manager Bugs and Failure Patterns

**Date:** 2026-03-10

## 1. Summary

This plan describes how to model and verify the Assignment Manager issues
documented in `ASSIGNMENT_MANAGER_ISSUES.md` within the existing TLA+
specification. These require adding new variables, actions, or modules beyond
the current iteration plan, but remain within the assignment protocol domain. 

### 1.1 TRSP Timeout and Rollback — HBASE-25059

The TRSP timeout is the natural recovery mechanism that restores liveness when RS
reports are lost. Add a `TRSPTimeout(r)` action that fires when a TRSP has been
at `CONFIRM_OPENED` for "too long" (modeled non-deterministically).
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

### 1.2 RIT Chore Remediation — HBASE-27773

The RIT chore is a periodic master-side thread that detects stuck RIT regions and
kicks them.
- New action `RITChoreRemediate(r)`: non-deterministic, fires when
  `regionState[r].state ∈ {"OPENING", "CLOSING"}` and time exceeds threshold
  (modeled as non-deterministic enablement).
- Effect: cancel the current dispatch, set `forceNewPlan = TRUE`, transition
  TRSP to `GET_ASSIGN_CANDIDATE`.
- This overlaps with `TRSPTimeout` but applies at a different granularity
  (chore period vs. per-operation timeout).

### 1.3 Truncate Crash Data Loss — HBASE-26883

Requires a new `TruncateTableProcedure` parent procedure type, analogous to
`SplitTableRegionProcedure`. This is a significant extension: new
`parentProcType = "TRUNCATE"`, new child TRSP sequencing (unassign all →
delete regions → create new regions → assign all), new crash recovery for each
step.
- New module `Truncate.tla` with `TruncatePrepare`, `TruncateUnassign`,
  `TruncateDeleteMeta`, `TruncateCreateMeta`, `TruncateAssign` actions.
- Crash between `TruncateDeleteMeta` and `TruncateCreateMeta` is the bug:
  old regions deleted from meta but new regions not yet created → data loss.
- Quirk: `UseTruncateCrashQuirk` — skip the crash guard between delete and create.
- Verification: `KeyspaceCoverage` — keyspace has a hole.

### 1.4 Merged Region HBCK Race — HBASE-29692

After merge is modeled, an HBCK assign action can be added that assigns a region
that has already been merged.
- New action `HBCKAssign(r)`: non-deterministic admin action.
  Pre: `regionKeyRange[r] = NoRange` (merged/deleted) or `regionState[r].state
  = "MERGED"`. Effect: create TRSP(ASSIGN) for `r`.
- If the merged region is resurrected, it overlaps the new merged-into region.
- Verification: `KeyspaceCoverage` — overlapping keyspaces.
- Gated by `UseHBCKAssign ∈ BOOLEAN`; `FALSE` in primary configs.
