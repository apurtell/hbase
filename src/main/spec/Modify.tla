------------------------------ MODULE Modify ----------------------------------
(*
 * ModifyTable procedure actions for the HBase AssignmentManager.
 *
 * Models ModifyTableProcedure (ALTER TABLE): modifies a table's
 * descriptor.  Two administrative workflows are modeled:
 *
 *   1. Non-structural modification on an ENABLED table:
 *      The table stays enabled.  Regions are reopened via a rolling
 *      restart (ReopenTableRegionsProcedure) to pick up the new
 *      descriptor.  Idle OPEN regions receive TRSP REOPEN; busy
 *      regions (mid-split, mid-merge, mid-SCP) are skipped —
 *      matching ReopenTableRegionsProcedure's
 *      regionNode.getProcedure() != null skip (L221-223).
 *
 *   2. Structural modification on a DISABLED table:
 *      The administrator disables the table first (DisableTable),
 *      then issues ALTER TABLE for structural changes (e.g., column
 *      family add/remove, coprocessor changes, region replica count),
 *      then re-enables the table (EnableTable).  Since all regions
 *      are already CLOSED/OFFLINE, no reopens are needed — the
 *      modify procedure just acquires/releases the exclusive table
 *      lock.
 *      Source: ModifyTableProcedure.preflightChecks() L119-152
 *              rejects structural changes when reopenRegions=true
 *              (enabled table); the admin must disable first.
 *
 * Structural modifications on ENABLED tables are rejected by the
 * implementation (preflightChecks L119-152).  This is modeled by
 * the non-deterministic choice of whether ModifyTablePrepare fires
 * on an enabled table — TLC explores both "admin issues non-structural
 * ALTER" (fires) and "admin chooses to disable first" (does not fire,
 * DisableTable fires instead).
 *
 * ModifyTable does NOT change tableEnabled.  The table lifecycle state
 * is managed exclusively by DisableTable/EnableTable.
 *
 * Source: ModifyTableProcedure.java
 *         executeFromState() L199-311:
 *           PREPARE -> PRE_OP -> UPDATE_DESCRIPTOR ->
 *           REOPEN_ALL_REGIONS (if enabled) -> POST_OP -> DONE.
 *         L263-268: if (isTableEnabled(env)) addChildProcedure(
 *           ReopenTableRegionsProcedure...) — reopen only when enabled.
 *         Descriptor update, FS layout, erasure coding, snapshot, and
 *         coprocessor hooks abstracted (orthogonal to assignment state).
 *         Collapsed to ModifyTablePrepare + ModifyTableDone.
 *)
EXTENDS Types

\* All shared variables are declared as VARIABLE parameters so that
\* the root module can substitute its own variables via INSTANCE.
VARIABLE regionState,
         metaTable,
         dispatchedOps,
         pendingReports,
         rsOnlineRegions,
         serverState,
         scpState,
         scpRegions,
         walFenced,
         carryingMeta,
         serverRegions,
         procStore,
         masterAlive,
         zkNode,
         availableWorkers,
         suspendedOnMeta,
         blockedOnMeta,
         parentProc,
         tableEnabled

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == << dispatchedOps, pendingReports >>

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == << rsOnlineRegions >>

\* Shorthand for the SCP-related variables (used in UNCHANGED clauses).
scpVars == << scpState, scpRegions, walFenced, carryingMeta >>

\* Shorthand for master lifecycle variables (used in UNCHANGED clauses).
masterVars == << masterAlive >>

\* Shorthand for server tracking variables (used in UNCHANGED clauses).
serverVars == << serverState, serverRegions >>

\* Shorthand for PEWorker pool variables (used in UNCHANGED clauses).
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>

\* Helper: is meta available? (no server in ASSIGN_META state)
MetaIsAvailable == \A s \in Servers: scpState[s] # "ASSIGN_META"

\* Helper: no parentProc of any type active on any region of table t.
TableLockFree(t) ==
  ~\E r2 \in Regions: /\ metaTable[r2].table = t /\ parentProc[r2].type # "NONE"

---------------------------------------------------------------------------

(* Actions *)
\* Modify a table's descriptor.
\*
\* Two disjuncts model the two administrative workflows:
\*
\*   Disjunct 1 — Non-structural modification (ENABLED table):
\*     Regions that are OPEN with no active procedure receive a TRSP
\*     REOPEN (rolling restart).  Busy/non-OPEN regions are skipped.
\*     The non-deterministic choice of whether this disjunct fires
\*     models the administrator issuing a non-structural ALTER TABLE;
\*     structural changes on enabled tables are rejected by the
\*     implementation (preflightChecks L119-152).
\*
\*   Disjunct 2 — Structural modification (DISABLED table):
\*     All regions are CLOSED/OFFLINE with no active procedure
\*     (disabled-table precondition).  No reopens needed — the modify
\*     acquires/releases the exclusive lock.  Regions are marked
\*     COMPLETING (immediately done).
\*     Expected admin sequence: DisableTable -> ModifyTable -> EnableTable.
\*
\* Pre: master alive, PEWorker available, meta available, UseModify,
\*      TableLockFree(t), at least one region belongs to t,
\*      tableEnabled[t] in {"ENABLED", "DISABLED"}.
\* Post: parentProc set on all regions of t.  tableEnabled unchanged.
\*
\* Source: ModifyTableProcedure.executeFromState() L199-311,
\*         ReopenTableRegionsProcedure.executeFromState() L204-233.
ModifyTablePrepare(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* ModifyTable feature must be enabled.
  /\ UseModify = TRUE
  \* TableLockFree: no parentProc active on table t.
  /\ TableLockFree(t)
  \* At least one region belongs to table t.
  /\ \E r \in Regions: metaTable[r].table = t
  /\ \/ \* --- Disjunct 1: Non-structural modification (ENABLED table) ---
        \* Table must be enabled.
        \* Source: ModifyTableProcedure.executeFromState() L264:
        \*         if (isTableEnabled(env)) -> spawns ReopenTableRegionsProcedure.
        /\ tableEnabled[t] = "ENABLED"
        \* Set parentProc for table-level tracking on all regions of t.
        \* Idle OPEN regions: SPAWNED_OPEN (will be reopened).
        \* Others (busy or non-OPEN): COMPLETING (skipped).
        /\ parentProc' =
             [r \in Regions |->
               IF metaTable[r].table = t
               THEN IF /\ regionState[r].state = "OPEN"
                       /\ regionState[r].procType = "NONE"
                 \* Idle OPEN region: target for REOPEN.
                 THEN [ type |-> "MODIFY",
                     step |-> "SPAWNED_OPEN",
                     ref1 |-> NoRegion,
                     ref2 |-> NoRegion
                   ]
                 \* Busy or non-OPEN region: skip.
                 \* Source: ReopenTableRegionsProcedure L221-223
                 \*         regionNode.getProcedure() != null -> skip.
                 ELSE [ type |-> "MODIFY",
                     step |-> "COMPLETING",
                     ref1 |-> NoRegion,
                     ref2 |-> NoRegion
                   ]
               ELSE parentProc[r]
             ]
        \* Spawn TRSP REOPEN on each idle OPEN region of table t:
        \* set procType=REOPEN, procStep=CLOSE, targetServer=current location.
        \* Busy or non-OPEN regions are left unchanged.
        /\ regionState' =
             [r \in Regions |->
               IF /\ metaTable[r].table = t
                  /\ regionState[r].state = "OPEN"
                  /\ regionState[r].procType = "NONE"
               THEN [ state |-> regionState[r].state,
                   location |-> regionState[r].location,
                   procType |-> "REOPEN",
                   procStep |-> "CLOSE",
                   targetServer |-> regionState[r].location,
                   retries |-> 0
                 ]
               ELSE regionState[r]
             ]
        \* Persist child REOPEN procedures to procStore for idle OPEN regions.
        /\ procStore' =
             [r \in Regions |->
               IF /\ metaTable[r].table = t
                  /\ regionState[r].state = "OPEN"
                  /\ regionState[r].procType = "NONE"
               THEN NewProcRecord("REOPEN", "CLOSE", regionState[r].location, NoTransition)
               ELSE procStore[r]
             ]
     \/ \* --- Disjunct 2: Structural modification (DISABLED table) ---
        \* Table must be disabled.  All regions are CLOSED/OFFLINE.
        \* The administrator has already run DisableTable; after this
        \* modify completes, the administrator will run EnableTable.
        \* Source: ModifyTableProcedure.preflightChecks() L119-152
        \*         rejects structural changes when reopenRegions=true;
        \*         ModifyTableProcedure.executeFromState() L264:
        \*         if (!isTableEnabled(env)) -> skips reopen entirely.
        /\ tableEnabled[t] = "DISABLED"
        \* All regions of table t must be disabled (CLOSED/OFFLINE)
        \* with no active procedure.
        /\ \A r \in Regions:
             metaTable[r].table = t =>
               /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
               /\ regionState[r].procType = "NONE"
        \* All regions marked COMPLETING (no reopens needed).
        /\ parentProc' =
             [r \in Regions |->
               IF metaTable[r].table = t
               THEN [ type |-> "MODIFY",
                   step |-> "COMPLETING",
                   ref1 |-> NoRegion,
                   ref2 |-> NoRegion
                 ]
               ELSE parentProc[r]
             ]
        \* No TRSP spawned — regions stay as-is.
        /\ UNCHANGED << regionState, procStore >>
  \* tableEnabled unchanged — ModifyTable does not alter table state.
  \* Everything else unchanged.
  /\ UNCHANGED << metaTable,
        scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        zkNode,
        tableEnabled
     >>

\* Complete the ModifyTable procedure after all reopens complete.
\*
\* All regions of table t with parentProc.type = "MODIFY" and
\* step = "SPAWNED_OPEN" must be OPEN with no active procedure
\* (the REOPEN TRSPs have completed).  Regions with step =
\* "COMPLETING" were skipped (or structural modify on disabled table)
\* and don't block completion.
\* Clears parentProc on all MODIFY-tagged regions of table t.
\*
\* Pre: master alive, PEWorker available, at least one region of
\*      table t has parentProc.type = "MODIFY", and all SPAWNED_OPEN
\*      regions are OPEN with procType = "NONE".
\* Post: parentProc cleared on all regions of table t with MODIFY.
\*       tableEnabled unchanged.
\*
\* Source: ReopenTableRegionsProcedure.executeFromState() L245-246
\*         (empty regions -> NO_MORE_STATE);
\*         ModifyTableProcedure.executeFromState() L254-261
\*         (!reopenRegions -> NO_MORE_STATE after POST_OPERATION).
ModifyTableDone(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* At least one region of table t has a MODIFY parent procedure.
  /\ \E r \in Regions: /\ metaTable[r].table = t
                       /\ parentProc[r].type = "MODIFY"
  \* All regions of table t with MODIFY/SPAWNED_OPEN are OPEN and idle.
  \* Regions with MODIFY/COMPLETING are skipped — no check needed.
  /\ \A r \in Regions:
       ( metaTable[r].table = t /\ parentProc[r].type = "MODIFY" /\
             parentProc[r].step = "SPAWNED_OPEN"
         ) =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
  \* Clear parentProc on all regions of table t with MODIFY.
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "MODIFY"
         THEN NoParentProc
         ELSE parentProc[r]
       ]
  \* tableEnabled unchanged — ModifyTable does not alter table state.
  \* Everything else unchanged.
  /\ UNCHANGED << regionState,
        metaTable,
        scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        procStore,
        zkNode,
        tableEnabled
     >>

============================================================================
