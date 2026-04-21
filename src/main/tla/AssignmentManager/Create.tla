------------------------------- MODULE Create ---------------------------------
(*
 * CreateTable procedure actions for the HBase AssignmentManager.
 *
 * Models CreateTableProcedure: creates a new table with one or more
 * regions covering the full keyspace [0, MaxKey).  The number of
 * regions depends on the cardinality of the set of currently unused
 * region identifiers: TLC non-deterministically picks any non-empty
 * subset S of unused identifiers such that MaxKey is evenly divisible
 * by |S|.  With a single unused identifier, the action creates a
 * single-region table; with multiple, it tiles the keyspace into
 * equal-width sub-ranges, one per region.  For each region, the action
 * writes meta and spawns a child ASSIGN TRSP to open the region.
 *
 * Forward-path actions:
 *   CreateTablePrepare  -- pick unused identifiers, assign keyspace,
 *                          write meta, spawn child ASSIGN TRSPs
 *   CreateTableDone     -- all regions OPEN, clear parentProc
 *
 * The parentProc[r] variable tracks the CREATE procedure's state.
 * It persists across the child TRSP lifecycle and survives master crash.
 * The child TRSPs use the normal TRSP machinery (procType/procStep).
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
\* Create a new table with one or more regions covering [0, MaxKey).
\*
\* The number of regions depends on the set S of unused region identifiers
\* provided by the caller (non-deterministically chosen by TLC via the
\* \E S \in SUBSET Regions quantifier in the Next disjunct).  When only
\* one unused identifier is available, S is a singleton and the action
\* creates a single-region table spanning [0, MaxKey).  When multiple
\* unused identifiers are available, S may contain up to all of them,
\* and the keyspace is tiled into |S| equal-width sub-ranges.
\*
\* The set S must satisfy:
\*   - S # {} (at least one region)
\*   - all elements are unused (NoRange, NoTable)
\*   - MaxKey % |S| = 0 (evenly divisible keyspace)
\*
\* For each r in S, the action atomically:
\*   - writes metaTable[r] as CLOSED/NoServer with the sub-range keyspace
\*   - sets regionState[r] with child ASSIGN TRSP
\*   - persists procStore[r]
\*   - sets parentProc[r] = [CREATE, SPAWNED_OPEN]
\*
\* Pre: master alive, PEWorker available, meta available,
\*      table t not in use (no region belongs to it),
\*      S is a non-empty set of unused identifiers,
\*      MaxKey % |S| = 0, TableLockFree(t).
\* Post: each region r in S belongs to table t, has its sub-range,
\*       state = CLOSED with ASSIGN TRSP spawned,
\*       parentProc = [CREATE, SPAWNED_OPEN].
\*
\* Source: CreateTableProcedure.executeFromState()
\*         ADD_TO_META + ASSIGN_REGIONS steps.
\*         addChildProcedure(createRoundRobinAssignProcedures(newRegions))
\*         spawns child ASSIGN TRSPs for all regions in the list.
CreateTablePrepare(t, S) ==
  LET n == Cardinality(S)
      width == MaxKey \div n
      \* Bijection from S to 0..(n-1).  CHOOSE picks an arbitrary injective
      \* function (TLC-compatible: no ordering on model values required).
      \* Same tiling pattern used in Init for DeployedRegions.
      rank == CHOOSE f \in [S -> 0 .. (n - 1)]:
                \A r1, r2 \in S: r1 # r2 => f[r1] # f[r2]
  IN
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* S must be non-empty.
  /\ S # {}
  \* MaxKey must be evenly divisible by the number of regions.
  /\ MaxKey % n = 0
  \* Table t must not be in use (no region belongs to it).
  /\ ~ \E r2 \in Regions: metaTable[r2].table = t
  \* TableLockFree: no parentProc active on table t.
  \* (Vacuously true when no region belongs to t, but kept for
  \* completeness and consistency with Split/Merge guards.)
  /\ TableLockFree(t)
  \* All elements of S must be unused identifiers.
  /\ \A r \in S: /\ metaTable[r].keyRange = NoRange
                 /\ metaTable[r].table = NoTable
  \* Assign keyspace sub-ranges, set table identity, and write meta
  \* as CLOSED/NoServer for each region in S.
  /\ metaTable' =
       [r \in Regions |->
         IF r \in S
         THEN [ state |-> "CLOSED",
                location |-> NoServer,
                keyRange |-> [ startKey |-> rank[r] * width,
                               endKey |-> (rank[r] + 1) * width ],
                table |-> t ]
         ELSE metaTable[r] ]
  \* Set in-memory state: CLOSED with child ASSIGN TRSP spawned.
  /\ regionState' =
       [r \in Regions |->
         IF r \in S
         THEN [ state |-> "CLOSED",
                location |-> NoServer,
                procType |-> "ASSIGN",
                procStep |-> "GET_ASSIGN_CANDIDATE",
                targetServer |-> NoServer,
                retries |-> 0 ]
         ELSE regionState[r] ]
  \* Persist the child ASSIGN procedure to procStore.
  /\ procStore' =
       [r \in Regions |->
         IF r \in S
         THEN NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)
         ELSE procStore[r] ]
  \* Set parentProc for table-level tracking.
  \* ref1/ref2 are NoRegion (no cross-references needed for create).
  /\ parentProc' =
       [r \in Regions |->
         IF r \in S
         THEN [ type |-> "CREATE", step |-> "SPAWNED_OPEN",
                ref1 |-> NoRegion, ref2 |-> NoRegion ]
         ELSE parentProc[r] ]
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

\* Complete the CreateTable procedure after all regions are OPEN.
\*
\* All regions of table t with parentProc.type = "CREATE" must be OPEN
\* with no active procedure (all child ASSIGN TRSPs have completed).
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
       /\ metaTable[r].table = t
       /\ parentProc[r].type = "CREATE"
  \* All regions of table t with CREATE parentProc are OPEN and unattached.
  /\ \A r \in Regions:
       (metaTable[r].table = t /\ parentProc[r].type = "CREATE") =>
         /\ regionState[r].state = "OPEN"
         /\ regionState[r].procType = "NONE"
  \* Clear parentProc on all regions of table t with CREATE.
  /\ parentProc' =
       [r \in Regions |->
         IF metaTable[r].table = t /\ parentProc[r].type = "CREATE"
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
        tableEnabled,
        zkNode
     >>

============================================================================
