------------------------------ MODULE Enable -----------------------------------
(*
 * EnableTable procedure actions for the HBase AssignmentManager.
 *
 * Models EnableTableProcedure: enables a table by opening all its
 * regions.  All regions must be in {CLOSED, OFFLINE} with no active
 * procedure (disabled-table precondition).  Spawns ASSIGN TRSPs to
 * open each region.
 *
 * Forward-path actions:
 *   EnableTablePrepare  -- acquire table lock, set tableEnabled TRUE,
 *                          spawn child ASSIGN TRSPs for all regions
 *   EnableTableDone     -- all regions OPEN, clear parentProc
 *
 * The parentProc[r] variable tracks the ENABLE procedure's state.
 * Child TRSPs use the normal TRSP machinery (procType/procStep).
 *
 * Source: EnableTableProcedure.java
 *         PREPARE -> PRE_OP -> SET_ENABLING -> MARK_ONLINE ->
 *         SET_ENABLED -> POST_OP.
 *         Calls createAssignProcedures() to open all regions.
 *         Coprocessor hooks omitted (orthogonal).
 *         Collapsed to EnableTablePrepare + EnableTableDone.
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
TableLockFree(t) == ~\E r2 \in Regions: /\ metaTable[r2].table = t
                                         /\ parentProc[r2].type # "NONE"

---------------------------------------------------------------------------

(* Actions *)
\* Enable a table by opening all its regions.
\*
\* All regions of table t must be in {CLOSED, OFFLINE} with no active
\* procedure — modeling the disabled-table precondition.  Atomically
\* spawns ASSIGN TRSPs for each region and sets tableEnabled[t] = "ENABLING".
\*
\* Pre: master alive, PEWorker available, meta available,
\*      tableEnabled[t] = "DISABLED", TableLockFree(t),
\*      at least one region belongs to t,
\*      all regions of t in {CLOSED, OFFLINE} with procType = "NONE".
\* Post: parentProc set to [ENABLE, SPAWNED_OPEN] on all regions,
\*       ASSIGN TRSPs spawned, tableEnabled[t] = "ENABLING".
\*
\* Source: EnableTableProcedure.executeFromState()
\*         PREPARE -> PRE_OP -> SET_ENABLING steps (collapsed).
EnableTablePrepare(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* Table must be currently disabled.
  /\ tableEnabled[t] = "DISABLED"
  \* TableLockFree: no parentProc active on table t.
  /\ TableLockFree(t)
  \* At least one region belongs to table t.
  /\ \E r \in Regions: metaTable[r].table = t
  \* All regions of table t must be disabled (CLOSED or OFFLINE)
  \* with no active procedure.
  /\ \A r \in Regions:
       metaTable[r].table = t =>
         /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
         /\ regionState[r].procType = "NONE"
  \* Set parentProc for table-level tracking on all regions of t
  \* and spawn child ASSIGN TRSPs.
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t
         THEN [ type |-> "ENABLE", step |-> "SPAWNED_OPEN",
                ref1 |-> NoRegion, ref2 |-> NoRegion ]
         ELSE parentProc[r]]
  \* Spawn ASSIGN TRSP on each region.
  /\ regionState' =
       [r \in Regions |->
         IF metaTable[r].table = t
         THEN [ state |-> regionState[r].state,
                location |-> NoServer,
                procType |-> "ASSIGN",
                procStep |-> "GET_ASSIGN_CANDIDATE",
                targetServer |-> NoServer,
                retries |-> 0
              ]
         ELSE regionState[r]]
  \* Persist child ASSIGN procedures to procStore.
  /\ procStore' =
       [r \in Regions |->
         IF metaTable[r].table = t
         THEN NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)
         ELSE procStore[r]]
  \* Set tableEnabled[t] = "ENABLING" (intermediate state).
  \* Source: EnableTableProcedure.SET_ENABLING_TABLE_STATE.
  /\ tableEnabled' = [tableEnabled EXCEPT ![t] = "ENABLING"]
  \* Everything else unchanged.
  /\ UNCHANGED << metaTable,
        scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        zkNode
     >>

\* Complete the EnableTable procedure after all regions are OPEN.
\*
\* All regions of table t with parentProc.type = "ENABLE" must be
\* OPEN with no active procedure (the ASSIGN TRSPs have completed).
\* Clears parentProc on all such regions.
\*
\* Pre: master alive, PEWorker available, at least one region of
\*      table t has parentProc.type = "ENABLE", and all such regions
\*      are OPEN with procType = "NONE".
\* Post: parentProc cleared on all regions of table t with ENABLE.
\*
\* Source: EnableTableProcedure POST_OP.
EnableTableDone(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* At least one region of table t has an ENABLE parent procedure.
  /\ \E r \in Regions:
       /\ metaTable[r].table = t
       /\ parentProc[r].type = "ENABLE"
  \* All regions of table t with ENABLE parentProc are OPEN and unattached.
  /\ \A r \in Regions:
       (metaTable[r].table = t /\ parentProc[r].type = "ENABLE") =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
  \* Clear parentProc on all regions of table t with ENABLE.
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "ENABLE"
         THEN NoParentProc
         ELSE parentProc[r]]
  \* Set tableEnabled[t] = "ENABLED" (final state).
  \* Source: EnableTableProcedure.SET_ENABLED_TABLE_STATE.
  /\ tableEnabled' = [tableEnabled EXCEPT ![t] = "ENABLED"]
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
        zkNode
     >>

============================================================================
