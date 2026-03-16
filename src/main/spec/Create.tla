------------------------------- MODULE Create ---------------------------------
(*
 * CreateTable procedure actions for the HBase AssignmentManager.
 *
 * Models CreateTableProcedure: creates a new table with a single region
 * covering the full keyspace [0, MaxKey), writes meta, and spawns a
 * child ASSIGN TRSP to open the region.
 *
 * Forward-path actions:
 *   CreateTablePrepare  -- pick unused identifier, assign keyspace,
 *                          write meta, spawn child ASSIGN TRSP
 *   CreateTableDone     -- region OPEN, clear parentProc
 *
 * The parentProc[r] variable tracks the CREATE procedure's state.
 * It persists across the child TRSP lifecycle and survives master crash.
 * The child TRSP uses the normal TRSP machinery (procType/procStep).
 *
 * Source: CreateTableProcedure.java
 *         PRE_OPERATION -> WRITE_FS_LAYOUT -> ADD_TO_META -> ASSIGN_REGIONS ->
 *         UPDATE_DESC_CACHE -> POST_OPERATION.
 *         Filesystem, descriptor cache, and coprocessor operations abstracted.
 *         ADD_TO_META + ASSIGN_REGIONS collapsed into CreateTablePrepare;
 *         POST_OPERATION collapsed into CreateTableDone.
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
\* Create a new table with a single region covering [0, MaxKey).
\*
\* Picks an unused region identifier r, assigns the full keyspace,
\* writes meta as CLOSED/NoServer, spawns a child ASSIGN TRSP,
\* and sets parentProc for table-level tracking.
\*
\* Pre: master alive, PEWorker available, meta available,
\*      table t not in use (no region belongs to it),
\*      r is an unused identifier (NoRange, NoTable),
\*      TableLockFree(t).
\* Post: region r belongs to table t, has keyspace [0, MaxKey),
\*       state = CLOSED with ASSIGN TRSP spawned,
\*       parentProc = [CREATE, SPAWNED_OPEN].
\*
\* Source: CreateTableProcedure.executeFromState()
\*         ADD_TO_META + ASSIGN_REGIONS steps.
CreateTablePrepare(t, r) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* Table t must not be in use (no region belongs to it).
  /\ ~ \E r2 \in Regions: regionTable[r2] = t
  \* TableLockFree: no parentProc active on table t.
  \* (Vacuously true when no region belongs to t, but kept for
  \* completeness and consistency with Split/Merge guards.)
  /\ TableLockFree(t)
  \* r must be an unused identifier.
  /\ regionKeyRange[r] = NoRange
  /\ regionTable[r] = NoTable
  \* Assign keyspace [0, MaxKey) to the single region.
  /\ regionKeyRange' = [regionKeyRange EXCEPT ![r] = [startKey |-> 0, endKey |-> MaxKey]]
  \* Set table identity.
  /\ regionTable' = [regionTable EXCEPT ![r] = t]
  \* Write meta as CLOSED/NoServer (ADD_TO_META step).
  /\ metaTable' = [metaTable EXCEPT ![r] = [state |-> "CLOSED", location |-> NoServer]]
  \* Set in-memory state: CLOSED with child ASSIGN TRSP spawned.
  /\ regionState' =
       [regionState EXCEPT
       ![r] =
       [ state |-> "CLOSED",
         location |-> NoServer,
         procType |-> "ASSIGN",
         procStep |-> "GET_ASSIGN_CANDIDATE",
         targetServer |-> NoServer,
         retries |-> 0
       ]]
  \* Persist the child ASSIGN procedure to procStore.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  \* Set parentProc for table-level tracking.
  \* ref1/ref2 are NoRegion (single-region creation needs no cross-references).
  /\ parentProc' =
       [parentProc EXCEPT ![r] = [ type |-> "CREATE", step |-> "SPAWNED_OPEN",
                                    ref1 |-> NoRegion, ref2 |-> NoRegion ]]
  \* Everything else unchanged.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        tableEnabled,
        zkNode
     >>

\* Complete the CreateTable procedure after the region is OPEN.
\*
\* All regions of table t with parentProc.type = "CREATE" must be OPEN
\* with no active procedure (the ASSIGN TRSP has completed).
\* Clears parentProc on all such regions.
\*
\* Pre: master alive, PEWorker available, at least one region of
\*      table t has parentProc.type = "CREATE", and all such regions
\*      are OPEN with procType = "NONE".
\* Post: parentProc cleared on all regions of table t with CREATE.
\*
\* Source: CreateTableProcedure POST_OPERATION.
CreateTableDone(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* At least one region of table t has a CREATE parent procedure.
  /\ \E r \in Regions:
       /\ regionTable[r] = t
       /\ parentProc[r].type = "CREATE"
  \* All regions of table t with CREATE parentProc are OPEN and unattached.
  /\ \A r \in Regions:
       (regionTable[r] = t /\ parentProc[r].type = "CREATE") =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
  \* Clear parentProc on all regions of table t with CREATE.
  /\ parentProc' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "CREATE"
         THEN NoParentProc
         ELSE parentProc[r]]
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
        regionKeyRange,
        regionTable,
        tableEnabled,
        zkNode
     >>

============================================================================
