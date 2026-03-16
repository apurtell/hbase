------------------------------ MODULE Disable ----------------------------------
(*
 * DisableTable procedure actions for the HBase AssignmentManager.
 *
 * Models DisableTableProcedure: disables a table by closing all its
 * regions.  All regions must be OPEN with no active procedure (enabled-
 * table precondition).  Spawns UNASSIGN TRSPs to close each region.
 *
 * Forward-path actions:
 *   DisableTablePrepare  -- acquire table lock, set tableEnabled FALSE,
 *                           spawn child UNASSIGN TRSPs for all regions
 *   DisableTableDone     -- all regions CLOSED/OFFLINE, clear parentProc
 *
 * The parentProc[r] variable tracks the DISABLE procedure's state.
 * Child TRSPs use the normal TRSP machinery (procType/procStep).
 *
 * Source: DisableTableProcedure.java
 *         PREPARE -> PRE_OP -> SET_DISABLING -> MARK_OFFLINE ->
 *         ADD_REPLICATION_BARRIER -> SET_DISABLED -> POST_OP.
 *         Spawns CloseTableRegionsProcedure child to close all regions.
 *         Coprocessor hooks and replication barriers omitted (orthogonal).
 *         Collapsed to DisableTablePrepare + DisableTableDone.
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
         regionKeyRange,
         parentProc,
         regionTable,
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
TableLockFree(t) == ~\E r2 \in Regions: /\ regionTable[r2] = t
                                         /\ parentProc[r2].type # "NONE"

---------------------------------------------------------------------------

(* Actions *)
\* Disable a table by closing all its regions.
\*
\* All regions of table t must be OPEN with no active procedure —
\* modeling the enabled-table precondition from the implementation.
\* Atomically spawns UNASSIGN TRSPs for each region and sets
\* tableEnabled[t] = FALSE.
\*
\* Pre: master alive, PEWorker available, meta available,
\*      tableEnabled[t] = TRUE, TableLockFree(t),
\*      at least one region belongs to t,
\*      all regions of t OPEN with procType = "NONE".
\* Post: parentProc set to [DISABLE, SPAWNED_CLOSE] on all regions,
\*       UNASSIGN TRSPs spawned, tableEnabled[t] = FALSE.
\*
\* Source: DisableTableProcedure.executeFromState()
\*         PREPARE -> PRE_OP -> SET_DISABLING steps (collapsed).
DisableTablePrepare(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* Table must be currently enabled.
  /\ tableEnabled[t] = TRUE
  \* TableLockFree: no parentProc active on table t.
  /\ TableLockFree(t)
  \* At least one region belongs to table t.
  /\ \E r \in Regions: regionTable[r] = t
  \* All regions of table t must be OPEN with no active procedure.
  /\ \A r \in Regions:
       regionTable[r] = t =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
  \* Set parentProc for table-level tracking on all regions of t
  \* and spawn child UNASSIGN TRSPs.
  /\ parentProc' =
       [r \in Regions |->
         IF regionTable[r] = t
         THEN [ type |-> "DISABLE", step |-> "SPAWNED_CLOSE",
                ref1 |-> NoRegion, ref2 |-> NoRegion ]
         ELSE parentProc[r]]
  \* Spawn UNASSIGN TRSP on each region: set procType=UNASSIGN,
  \* procStep=CLOSE, targetServer=current location.
  /\ regionState' =
       [r \in Regions |->
         IF regionTable[r] = t
         THEN [ state |-> regionState[r].state,
                location |-> regionState[r].location,
                procType |-> "UNASSIGN",
                procStep |-> "CLOSE",
                targetServer |-> regionState[r].location,
                retries |-> 0
              ]
         ELSE regionState[r]]
  \* Persist child UNASSIGN procedures to procStore.
  /\ procStore' =
       [r \in Regions |->
         IF regionTable[r] = t
         THEN NewProcRecord("UNASSIGN", "CLOSE", regionState[r].location, NoTransition)
         ELSE procStore[r]]
  \* Set tableEnabled[t] = FALSE.
  /\ tableEnabled' = [tableEnabled EXCEPT ![t] = FALSE]
  \* Everything else unchanged.
  /\ UNCHANGED << metaTable,
        scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        regionKeyRange,
        regionTable,
        zkNode
     >>

\* Complete the DisableTable procedure after all regions are closed.
\*
\* All regions of table t with parentProc.type = "DISABLE" must be
\* in {CLOSED, OFFLINE} with no active procedure (the UNASSIGN TRSPs
\* have completed).  Clears parentProc on all such regions.
\*
\* Pre: master alive, PEWorker available, at least one region of
\*      table t has parentProc.type = "DISABLE", and all such regions
\*      are CLOSED/OFFLINE with procType = "NONE".
\* Post: parentProc cleared on all regions of table t with DISABLE.
\*
\* Source: DisableTableProcedure POST_OP.
DisableTableDone(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* At least one region of table t has a DISABLE parent procedure.
  /\ \E r \in Regions:
       /\ regionTable[r] = t
       /\ parentProc[r].type = "DISABLE"
  \* All regions of table t with DISABLE parentProc are closed and unattached.
  /\ \A r \in Regions:
       (regionTable[r] = t /\ parentProc[r].type = "DISABLE") =>
         /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
         /\ regionState[r].procType = "NONE"
  \* Clear parentProc on all regions of table t with DISABLE.
  /\ parentProc' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DISABLE"
         THEN NoParentProc
         ELSE parentProc[r]]
  \* Everything else unchanged (tableEnabled already FALSE).
  /\ UNCHANGED << regionState,
        metaTable,
        scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        procStore,
        regionKeyRange,
        regionTable,
        tableEnabled,
        zkNode
     >>

============================================================================
