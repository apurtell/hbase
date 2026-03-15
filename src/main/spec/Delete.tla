------------------------------ MODULE Delete ----------------------------------
(*
 * DeleteTable procedure actions for the HBase AssignmentManager.
 *
 * Models DeleteTableProcedure: deletes all regions of a table, freeing
 * region identifiers back to the unused pool.  Requires all regions of
 * the target table to be in {"CLOSED","OFFLINE"} with procType = "NONE"
 * (modeling the disabled-table precondition).
 *
 * Forward-path actions:
 *   DeleteTablePrepare  -- acquire table lock, mark all regions for deletion
 *   DeleteTableDone     -- atomically clear meta, free identifiers, reset state
 *
 * No child TRSPs are spawned — regions are already closed.
 * The parentProc[r] variable tracks the DELETE procedure's state.
 *
 * Source: DeleteTableProcedure.java
 *         PRE_OPERATION -> CLEAR_FS_LAYOUT -> REMOVE_FROM_META ->
 *         UNASSIGN_REGIONS -> POST_OPERATION.
 *         Filesystem, descriptor cache, and coprocessor operations abstracted.
 *         REMOVE_FROM_META + POST_OPERATION collapsed into DeleteTableDone.
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
         regionTable

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
\* Acquire exclusive table lock and mark all regions for deletion.
\*
\* All regions of table t must be in {"CLOSED","OFFLINE"} with no active
\* procedure — modeling the disabled-table precondition from the
\* implementation (DeleteTableProcedure requires the table to be disabled).
\*
\* Pre: master alive, PEWorker available, meta available,
\*      TableLockFree(t), at least one region belongs to t,
\*      all regions of t in {"CLOSED","OFFLINE"} with procType = "NONE".
\* Post: parentProc set to [DELETE, COMPLETING] on all regions of t.
\*
\* Source: DeleteTableProcedure.executeFromState()
\*         PRE_OPERATION step.
DeleteTablePrepare(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* TableLockFree: no parentProc active on table t.
  /\ TableLockFree(t)
  \* At least one region belongs to table t.
  /\ \E r \in Regions: regionTable[r] = t
  \* All regions of table t must be disabled (CLOSED or OFFLINE)
  \* with no active procedure.
  /\ \A r \in Regions:
       regionTable[r] = t =>
         /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
         /\ regionState[r].procType = "NONE"
  \* Set parentProc for table-level tracking on all regions of t.
  /\ parentProc' =
       [r \in Regions |->
         IF regionTable[r] = t
         THEN [ type |-> "DELETE", step |-> "COMPLETING",
                ref1 |-> NoRegion, ref2 |-> NoRegion ]
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
        zkNode
     >>

\* Complete the DeleteTable procedure: clear meta, free identifiers, reset state.
\*
\* Atomically resets all DELETE-bearing regions of table t to the initial
\* unused state: meta cleared, keyspace freed, table identity removed,
\* in-memory state reset, parentProc cleared.  Region identifiers return
\* to the unused pool (regionKeyRange = NoRange, regionTable = NoTable).
\*
\* Pre: master alive, PEWorker available, at least one region of
\*      table t has parentProc = [DELETE, COMPLETING].
\* Post: metaTable, regionKeyRange, regionTable, regionState, parentProc
\*       all reset for DELETE-bearing regions of t.
\*
\* Source: DeleteTableProcedure.executeFromState()
\*         REMOVE_FROM_META + POST_OPERATION steps (collapsed).
DeleteTableDone(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* At least one region of table t has a DELETE parent procedure
  \* in COMPLETING step.
  /\ \E r \in Regions:
       /\ regionTable[r] = t
       /\ parentProc[r].type = "DELETE"
       /\ parentProc[r].step = "COMPLETING"
  \* Reset metaTable for all DELETE-bearing regions of t.
  /\ metaTable' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
         THEN [state |-> "OFFLINE", location |-> NoServer]
         ELSE metaTable[r]]
  \* Free keyspace: clear regionKeyRange to NoRange.
  /\ regionKeyRange' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
         THEN NoRange
         ELSE regionKeyRange[r]]
  \* Clear table identity: set regionTable to NoTable.
  /\ regionTable' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
         THEN NoTable
         ELSE regionTable[r]]
  \* Reset in-memory state to initial unused state.
  /\ regionState' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
         THEN [ state |-> "OFFLINE",
                location |-> NoServer,
                procType |-> "NONE",
                procStep |-> "IDLE",
                targetServer |-> NoServer,
                retries |-> 0
              ]
         ELSE regionState[r]]
  \* Clear parentProc on all DELETE-bearing regions.
  /\ parentProc' =
       [r \in Regions |->
         IF regionTable[r] = t /\ parentProc[r].type = "DELETE"
         THEN NoParentProc
         ELSE parentProc[r]]
  \* Everything else unchanged.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        procStore,
        zkNode
     >>

============================================================================
