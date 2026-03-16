----------------------------- MODULE Truncate ---------------------------------
(*
 * TruncateTable procedure actions for the HBase AssignmentManager.
 *
 * Models TruncateTableProcedure: atomically deletes old regions and creates
 * new regions for the same table, clearing all data while preserving the
 * table identity.  Requires all regions of the target table to be in
 * {"CLOSED","OFFLINE"} with procType = "NONE" (disabled-table precondition).
 *
 * Forward-path actions:
 *   TruncatePrepare     -- acquire table lock, mark all regions for truncation
 *   TruncateDeleteMeta  -- delete old regions from meta, free identifiers (PONR)
 *   TruncateCreateMeta  -- pick new identifier, write meta, spawn child ASSIGN
 *   TruncateDone        -- new region OPEN, clear parentProc
 *
 * There is a crash-vulnerable window between TruncateDeleteMeta and
 * TruncateCreateMeta.  The WAL procedure store holds the new RegionInfo
 * objects. MasterRecover restores the procedure from durable parentProc
 * state and resumes.
 *
 * Source: TruncateTableProcedure.java
 *         PRE_OPERATION -> [SNAPSHOT ->] CLEAR_FS_LAYOUT -> REMOVE_FROM_META ->
 *         CREATE_FS_LAYOUT -> ADD_TO_META -> ASSIGN_REGIONS -> POST_OPERATION.
 *         Filesystem, descriptor cache, snapshot, and coprocessor operations
 *         abstracted.  PRE_OPERATION + CLEAR_FS_LAYOUT collapsed into
 *         TruncatePrepare; REMOVE_FROM_META into TruncateDeleteMeta;
 *         CREATE_FS_LAYOUT + ADD_TO_META + ASSIGN_REGIONS into
 *         TruncateCreateMeta; POST_OPERATION into TruncateDone.
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
\* Acquire exclusive table lock and mark all regions for truncation.
\*
\* All regions of table t must be in {"CLOSED","OFFLINE"} with no active
\* procedure — modeling the disabled-table precondition from the
\* implementation (TruncateTableProcedure requires the table to be disabled).
\*
\* Pre: master alive, PEWorker available, meta available,
\*      TableLockFree(t), at least one region belongs to t,
\*      all regions of t in {"CLOSED","OFFLINE"} with procType = "NONE".
\* Post: parentProc set to [TRUNCATE, COMPLETING] on all regions of t.
\*
\* Source: TruncateTableProcedure.executeFromState()
\*         PRE_OPERATION + CLEAR_FS_LAYOUT steps.
TruncatePrepare(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* TableLockFree: no parentProc active on table t.
  /\ TableLockFree(t)
  \* At least one region belongs to table t.
  /\ \E r \in Regions: metaTable[r].table = t
  \* All regions of table t must be disabled (CLOSED or OFFLINE)
  \* with no active procedure.
  /\ \A r \in Regions:
       metaTable[r].table = t => /\ regionState[r].state \in { "CLOSED", "OFFLINE" }
                             /\ regionState[r].procType = "NONE"
  \* Set parentProc for table-level tracking on all regions of t.
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t
         THEN [ type |-> "TRUNCATE",
             step |-> "COMPLETING",
             ref1 |-> NoRegion,
             ref2 |-> NoRegion
           ]
         ELSE parentProc[r]
       ]
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
        tableEnabled,
        zkNode
     >>

\* Delete old regions from meta and free identifiers (PONR).
\*
\* Atomically deletes all TRUNCATE-bearing regions of table t from meta,
\* frees their keyspaces and table identities, resets in-memory state,
\* and advances parentProc.step to "PONR" (point-of-no-return).
\*
\* After this step, old region identifiers are freed (NoRange, NoTable)
\* but parentProc records with [TRUNCATE, PONR] "float" on them.
\* TruncateCreateMeta will pick fresh identifiers and clear these.
\*
\* This is the availability-vulnerable point: table has zero regions in
\* meta until TruncateCreateMeta writes new ones.  The WAL procedure
\* store holds the procedure state for crash recovery.
\*
\* Pre: master alive, PEWorker available, at least one region of
\*      table t has parentProc = [TRUNCATE, COMPLETING].
\* Post: metaTable (state, keyRange, table), regionState cleared;
\*       parentProc.step advanced to "PONR".
\*
\* Source: TruncateTableProcedure.executeFromState()
\*         REMOVE_FROM_META step.
TruncateDeleteMeta(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* At least one region of table t has a TRUNCATE parent procedure
  \* in COMPLETING step.
  /\ \E r \in Regions: /\ metaTable[r].table = t
                       /\ parentProc[r].type = "TRUNCATE"
                       /\ parentProc[r].step = "COMPLETING"
  \* Reset metaTable for all TRUNCATE-bearing regions of t:
  \* clear state, location, keyRange, and table.
  /\ metaTable' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "TRUNCATE"
         THEN [ state |-> "OFFLINE", location |-> NoServer,
                keyRange |-> NoRange, table |-> NoTable ]
         ELSE metaTable[r]
       ]
  \* Reset in-memory state to initial unused state.
  /\ regionState' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "TRUNCATE"
         THEN [ state |-> "OFFLINE",
             location |-> NoServer,
             procType |-> "NONE",
             procStep |-> "IDLE",
             targetServer |-> NoServer,
             retries |-> 0
           ]
         ELSE regionState[r]
       ]
  \* Advance parentProc.step to "PONR" on all TRUNCATE-bearing regions.
  \* Note: the guard checks metaTable[r].table = t, which is evaluated
  \* BEFORE metaTable' is applied (TLA+ semantics).
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "TRUNCATE"
         THEN [ type |-> "TRUNCATE",
             step |-> "PONR",
             ref1 |-> NoRegion,
             ref2 |-> NoRegion
           ]
         ELSE parentProc[r]
       ]
  \* Everything else unchanged.
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        procStore,
        tableEnabled,
        zkNode
     >>

\* Pick a new identifier, write meta, and spawn a child ASSIGN TRSP.
\*
\* Creates a single new region covering [0, MaxKey) for table t,
\* using an unused identifier r.  Writes meta as CLOSED/NoServer,
\* spawns a child ASSIGN TRSP, and clears all old TRUNCATE/PONR
\* parentProc records (the floating records from TruncateDeleteMeta).
\*
\* Pre: master alive, PEWorker available, meta available,
\*      at least one region has parentProc = [TRUNCATE, PONR],
\*      r is an unused identifier (NoRange, NoTable, no parentProc).
\* Post: region r belongs to table t with keyspace [0, MaxKey),
\*       ASSIGN TRSP spawned, parentProc = [TRUNCATE, SPAWNED_OPEN];
\*       old TRUNCATE/PONR parentProcs cleared.
\*
\* Source: TruncateTableProcedure.executeFromState()
\*         CREATE_FS_LAYOUT + ADD_TO_META + ASSIGN_REGIONS steps.
TruncateCreateMeta(t, r) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* At least one region has a floating TRUNCATE/PONR parentProc
  \* (left by TruncateDeleteMeta).
  /\ \E r2 \in Regions: /\ parentProc[r2].type = "TRUNCATE"
                        /\ parentProc[r2].step = "PONR"
  \* Table t must have no existing regions — TruncateDeleteMeta freed
  \* all old regions to NoTable.  This binds the existentially quantified
  \* t to the table that was actually truncated, preventing the creation
  \* of new regions for an unrelated table.
  /\ ~ \E r2 \in Regions: metaTable[r2].table = t
  \* r must be an unused identifier.
  /\ metaTable[r].keyRange = NoRange
  /\ metaTable[r].table = NoTable
  /\ parentProc[r].type = "NONE"
  \* Assign keyspace [0, MaxKey), set table identity, and write meta
  \* as CLOSED/NoServer.  Folds old regionKeyRange/regionTable into
  \* a single metaTable EXCEPT.
  /\ metaTable' =
       [metaTable EXCEPT
       ![r].state = "CLOSED",
       ![r].location = NoServer,
       ![r].keyRange = [ startKey |-> 0, endKey |-> MaxKey ],
       ![r].table = t ]
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
  \* Set parentProc on the new region and clear old TRUNCATE/PONR records.
  /\ parentProc' =
       [r2 \in Regions |->
         IF r2 = r
         \* New region: set TRUNCATE/SPAWNED_OPEN.
         THEN [ type |-> "TRUNCATE",
             step |-> "SPAWNED_OPEN",
             ref1 |-> NoRegion,
             ref2 |-> NoRegion
           ]
         ELSE IF parentProc[r2].type = "TRUNCATE" /\
               parentProc[r2].step = "PONR"
           \* Old floating TRUNCATE/PONR records: clear.
           THEN NoParentProc
           ELSE parentProc[r2]
       ]
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

\* Complete the TruncateTable procedure after the new region is OPEN.
\*
\* All regions of table t with parentProc.type = "TRUNCATE" must be OPEN
\* with no active procedure (the ASSIGN TRSP has completed).
\* Clears parentProc on all such regions.
\*
\* Pre: master alive, PEWorker available, at least one region of
\*      table t has parentProc.type = "TRUNCATE", and all such regions
\*      are OPEN with procType = "NONE".
\* Post: parentProc cleared on all regions of table t with TRUNCATE.
\*
\* Source: TruncateTableProcedure POST_OPERATION.
TruncateDone(t) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* At least one region of table t has a TRUNCATE parent procedure.
  /\ \E r \in Regions: /\ metaTable[r].table = t
                       /\ parentProc[r].type = "TRUNCATE"
  \* All regions of table t with TRUNCATE parentProc are OPEN and unattached.
  /\ \A r \in Regions:
       ( metaTable[r].table = t /\ parentProc[r].type = "TRUNCATE" ) =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
  \* Clear parentProc on all regions of table t with TRUNCATE.
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "TRUNCATE"
         THEN NoParentProc
         ELSE parentProc[r]
       ]
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
        tableEnabled,
        zkNode
     >>

============================================================================
