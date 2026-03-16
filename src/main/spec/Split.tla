------------------------------- MODULE Split ---------------------------------
(*
 * Split procedure actions for the HBase AssignmentManager.
 *
 * Models SplitTableRegionProcedure: forward path (split a region
 * into two daughters) and pre-PONR rollback (abort and reopen
 * parent).  The procedure uses the parent-child framework: it
 * spawns child TRSPs via addChildProcedure() and yields while
 * children execute.
 *
 * Forward-path actions:
 *   SplitPrepare       -- set SPLITTING, create parentProc,
 *                         spawn child UNASSIGN TRSP
 *   SplitResumeAfterClose -- resume after child TRSP completes
 *   SplitUpdateMeta    -- PONR: meta write, materialize daughters,
 *                         spawn child ASSIGN TRSPs
 *   SplitDone          -- daughters OPEN, cleanup
 *
 * Rollback action:
 *   SplitFail          -- pre-PONR failure: create fresh ASSIGN
 *                         to reopen parent, clear parentProc
 *
 * The parentProc[r] variable tracks the parent procedure's state.
 * It persists across child TRSP lifecycles and survives master crash.
 * Child TRSPs use the normal TRSP machinery (procType/procStep).
 *
 * SplitFail fires non-deterministically at the same precondition
 * as SplitResumeAfterClose (parent CLOSED, child complete).  TLC
 * explores both the success path (SplitResumeAfterClose) and the
 * failure path (SplitFail) for every reachable split state.
 *
 * Source: SplitTableRegionProcedure.rollbackState() L368-411;
 *         openParentRegion() L647-651.
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

\* Helper: does region r have an assigned keyspace?
RegionExists(r) == metaTable[r].keyRange # NoRange

\* Helper: does region r have an active parent procedure?
HasActiveParent(r) == parentProc[r].type # "NONE"

\* Helper: no exclusive-type parent procedure active on any region
\* of the same table as r.
NoTableExclusiveLock(r) ==
  LET t == metaTable[r].table
  IN t # NoTable =>
       ~ \E r2 \in Regions:
            /\ metaTable[r2].table = t
            /\ parentProc[r2].type \in TableExclusiveType

\* Helper: is meta available? (no server carrying meta is crashed)
MetaIsAvailable ==
  \A s \in Servers: carryingMeta[s] = FALSE \/ serverState[s] = "ONLINE"

---------------------------------------------------------------------------

(* Actions *)
\* Initiate a split on an OPEN region.
\* Atomically sets SPLITTING in-memory and meta, creates parentProc,
\* and spawns the child UNASSIGN TRSP (addChildProcedure).
\*
\* In the implementation, prepareSplitRegion() and
\* addChildProcedure(createUnassignProcedures()) execute within the
\* same executeFromState() call under the region lock held by
\* acquireLock() -- they are effectively atomic.
\*
\* Pre: master alive, PEWorker available, region is OPEN with a location,
\*      no procedure attached, no parent procedure in progress,
\*      keyspace width >= 2 (can be halved), and at least 2 unused
\*      region identifiers exist for daughters.
\* Post: parent state = SPLITTING, parentProc = [SPLIT, SPAWNED_CLOSE].
\*       Meta updated to SPLITTING.  Child UNASSIGN TRSP spawned.
\*
\* Source: prepareSplitRegion() L509-593 sets SPLITTING;
\*         CLOSE_PARENT_REGION: addChildProcedure(createUnassignProcedures).
SplitPrepare(r) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Region must still exist (has an assigned keyspace).
  /\ RegionExists(r)
  \* Region must be OPEN with a location.
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # NoServer
  \* No procedure attached.
  /\ regionState[r].procType = "NONE"
  \* No parent procedure in progress on this region.
  /\ ~HasActiveParent(r)
  \* Keyspace wide enough to halve.
  /\ metaTable[r].keyRange.endKey - metaTable[r].keyRange.startKey >= 2
  \* At least 2 unused identifiers available for daughters.
  /\ Cardinality({d \in Regions: metaTable[d].keyRange = NoRange}) >= 2
  \* No table-level exclusive lock on this region's table.
  /\ NoTableExclusiveLock(r)
  \* Transition parent to SPLITTING and spawn child UNASSIGN TRSP.
  /\ regionState' =
       [regionState EXCEPT
       ![r].state =
       "SPLITTING",
       ![r].procType =
       "UNASSIGN",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
  \* Update meta to SPLITTING (preserving location).
  /\ metaTable' =
       [metaTable EXCEPT
       ![r].state = "SPLITTING"]
  \* Persist the child UNASSIGN procedure to procStore.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("UNASSIGN", "CLOSE", regionState[r].location, NoTransition)]
  \* Create parent procedure record (yielding to child).
  \* ref1/ref2 are NoRegion pre-PONR (daughters not yet chosen).
  /\ parentProc' =
       [parentProc EXCEPT ![r] = [ type |-> "SPLIT", step |-> "SPAWNED_CLOSE",
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

\* Resume the split after the child UNASSIGN TRSP completes.
\*
\* When the TRSP close path finishes, the parent is CLOSED with
\* procType = NONE.  This action detects parentProc.step = SPAWNED_CLOSE
\* and re-attaches the SPLIT procedure to the parent for protection,
\* then advances to PONR.
\*
\* Pre: master alive, PEWorker available, parent is CLOSED with
\*      no procedure attached, and parentProc = [SPLIT, SPAWNED_CLOSE].
\* Post: procType = SPLIT (re-attached for mutual exclusion),
\*       procStep = IDLE, parentProc step = PONR.
\*
\* Source: checkClosedRegions() L279-283.
SplitResumeAfterClose(r) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Region must still exist (has an assigned keyspace).
  /\ RegionExists(r)
  \* Parent must be CLOSED -- the UNASSIGN child completed.
  /\ regionState[r].state = "CLOSED"
  \* No procedure attached -- child UNASSIGN cleared it.
  /\ regionState[r].procType = "NONE"
  \* Split is pending at the close phase.
  /\ parentProc[r].type = "SPLIT"
  /\ parentProc[r].step = "SPAWNED_CLOSE"
  \* Re-attach SPLIT procedure to parent for protection.
  /\ regionState' =
       [regionState EXCEPT ![r].procType = "SPLIT", ![r].procStep = "IDLE"]
  \* Persist SPLIT procedure to procStore.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("SPLIT", "IDLE", NoServer, NoTransition)]
  \* Advance parent to PONR.
  /\ parentProc' = [parentProc EXCEPT ![r].step = "PONR"]
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        metaTable,
        tableEnabled,
        zkNode
     >>

\* Point of No Return: update meta, materialize daughters, spawn
\* child ASSIGN TRSPs on daughters.
\*
\* Non-deterministically picks two unused region identifiers for
\* daughters.  Computes mid = (startKey + endKey) / 2 from the
\* parent keyspace.  Atomically:
\*   - Parent: CLOSED -> SPLIT (in-memory and meta), location cleared.
\*   - Daughter A: keyspace = [startKey, mid), state = SPLITTING_NEW
\*     (in-memory), CLOSED (meta).  procType = ASSIGN (child spawned).
\*   - Daughter B: keyspace = [mid, endKey), state = SPLITTING_NEW
\*     (in-memory), CLOSED (meta).  procType = ASSIGN (child spawned).
\*
\* Pre: master alive, PEWorker available, meta available, parent has
\*      procType = SPLIT, parentProc = [SPLIT, PONR], state = CLOSED.
\*      dA and dB are distinct unused identifiers.
\* Post: parent in SPLIT state, daughters materialized with child
\*       ASSIGN TRSPs.  parentProc step = SPAWNED_OPEN.
\*
\* Source: AssignmentManager.markRegionAsSplit() L2364-2390;
\*         RegionStateStore.splitRegion() L392-395;
\*         OPEN_CHILD_REGIONS: addChildProcedure(createAssignProcedures).
SplitUpdateMeta(r, dA, dB) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* Region must still exist (has an assigned keyspace).
  /\ RegionExists(r)
  \* The SPLIT procedure must be attached to this region.
  /\ regionState[r].procType = "SPLIT"
  \* Parent procedure has reached the point of no return.
  /\ parentProc[r].type = "SPLIT"
  /\ parentProc[r].step = "PONR"
  \* Parent region must be CLOSED before meta can be updated.
  /\ regionState[r].state = "CLOSED"
  \* dA and dB are distinct unused identifiers.
  /\ dA # dB
  /\ dA # r
  /\ dB # r
  /\ metaTable[dA].keyRange = NoRange
  /\ metaTable[dB].keyRange = NoRange
  \* Compute the midpoint of the parent's keyspace.
  /\ LET startK == metaTable[r].keyRange.startKey
         endK == metaTable[r].keyRange.endKey
         mid == ( startK + endK ) \div 2
     IN \* Update regionState: parent -> SPLIT, daughters -> SPLITTING_NEW
        \* with child ASSIGN TRSPs spawned.
        /\ regionState' =
             [regionState EXCEPT
             \* Parent: CLOSED -> SPLIT, clear location.
             ![r].state =
             "SPLIT",
             ![r].location =
             NoServer,
             \* Daughter A: SPLITTING_NEW with child ASSIGN TRSP.
             ![dA] =
             [ state |-> "SPLITTING_NEW",
               location |-> NoServer,
               procType |-> "ASSIGN",
               procStep |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> NoServer,
               retries |-> 0
             ],
             \* Daughter B: SPLITTING_NEW with child ASSIGN TRSP.
             ![dB] =
             [ state |-> "SPLITTING_NEW",
               location |-> NoServer,
               procType |-> "ASSIGN",
               procStep |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> NoServer,
               retries |-> 0
             ]]
        \* Update meta: parent -> SPLIT, daughters -> CLOSED with keyRanges
        \* and table identity.  This folds the old regionKeyRange/regionTable
        \* mutations into a single metaTable EXCEPT.
        /\ metaTable' =
             [metaTable EXCEPT
             ![r].state = "SPLIT",
             ![r].location = NoServer,
             ![dA].state = "CLOSED",
             ![dA].location = NoServer,
             ![dA].keyRange = [ startKey |-> startK, endKey |-> mid ],
             ![dA].table = metaTable[r].table,
             ![dB].state = "CLOSED",
             ![dB].location = NoServer,
             ![dB].keyRange = [ startKey |-> mid, endKey |-> endK ],
             ![dB].table = metaTable[r].table ]
        \* Persist daughter ASSIGN procedures to procStore.
        /\ procStore' =
             [procStore EXCEPT
             ![dA] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition),
             ![dB] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  \* Parent yields to daughter ASSIGNs: step -> SPAWNED_OPEN.
  \* Store daughter references for SplitDone to read back.
  /\ parentProc' = [parentProc EXCEPT ![r].step = "SPAWNED_OPEN",
                                      ![r].ref1 = dA,
                                      ![r].ref2 = dB]
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        tableEnabled,
        zkNode
     >>

\* Complete the split after both daughters are OPEN.
\*
\* Clears the parent's keyspace to NoRange (region "deleted" --
\* models the post-compaction cleanup) and clears the parent
\* procedure state.
\*
\* Pre: master alive, PEWorker available, parentProc = [SPLIT, SPAWNED_OPEN],
\*      both daughters are OPEN with procType = NONE (ASSIGN completed).
\* Post: parent keyspace cleared (NoRange), procType = NONE,
\*       parentProc = NoParentProc.  Parent stays in SPLIT state (terminal).
\*
\* Source: POST_OPERATION state (procedure completes).
SplitDone(r) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Region must still exist (has an assigned keyspace).
  /\ RegionExists(r)
  \* The SPLIT procedure must be attached to this region.
  /\ regionState[r].procType = "SPLIT"
  \* Parent procedure is waiting for daughters to open.
  /\ parentProc[r].type = "SPLIT"
  /\ parentProc[r].step = "SPAWNED_OPEN"
  \* Read daughter references stored at PONR (SplitUpdateMeta).
  /\ LET dA == parentProc[r].ref1
         dB == parentProc[r].ref2
     IN \* Daughters must have been stored.
        /\ dA # NoRegion
        /\ dB # NoRegion
        \* Both daughters must be OPEN and unattached.
        /\ regionState[dA].state = "OPEN"
        /\ regionState[dA].procType = "NONE"
        /\ regionState[dB].state = "OPEN"
        /\ regionState[dB].procType = "NONE"
        \* Clear parent procedure state.
        /\ regionState' =
             [regionState EXCEPT
             ![r].procType =
             "NONE",
             ![r].procStep =
             "IDLE",
             ![r].targetServer =
             NoServer,
             ![r].retries =
             0]
        \* Clear parent keyspace and table via metaTable (region "deleted").
        /\ metaTable' =
             [metaTable EXCEPT ![r].keyRange = NoRange,
                               ![r].table = NoTable ]
  \* Clear parent from procStore.
  /\ procStore' = [procStore EXCEPT ![r] = NoProcedure]
  \* Clear parent procedure.
  /\ parentProc' = [parentProc EXCEPT ![r] = NoParentProc]
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        tableEnabled,
        zkNode
     >>

\* Pre-PONR rollback: abort the split and create a fresh ASSIGN
\* TRSP to reopen the parent region.
\*
\* Fires non-deterministically at the same precondition as
\* SplitResumeAfterClose (parent CLOSED, child UNASSIGN complete,
\* parentProc = [SPLIT, SPAWNED_CLOSE]).  The non-deterministic
\* choice between SplitResumeAfterClose and SplitFail models the
\* success/failure decision: TLC explores both branches.
\*
\* The rollback:
\*   1. Creates a fresh ASSIGN TRSP on the parent (GET_ASSIGN_CANDIDATE)
\*      to reopen it.  Matches openParentRegion() -> assignRegionForRollback().
\*   2. Clears parentProc to NoParentProc (split is terminated).
\*   3. Reverts meta from SPLITTING to CLOSED (parent's actual state;
\*      the ASSIGN TRSP will update meta to OPENING -> OPEN later).
\*   4. Persists the new ASSIGN procedure to procStore.
\*
\* No daughters are created pre-PONR (SplitAtomicity invariant), so
\* no daughter cleanup is needed.  metaTable keyRange is unchanged
\* (parent keeps its keyspace).
\*
\* Pre: master alive, PEWorker available, region exists,
\*      parent CLOSED, no procedure attached, parentProc = SPAWNED_CLOSE.
\* Post: ASSIGN TRSP spawned, parentProc cleared, meta reverted.
\*
\* Source: rollbackState() CHECK_CLOSED_REGIONS case L386-388;
\*         openParentRegion() L647-651.
SplitFail(r) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Region must still exist (has an assigned keyspace).
  /\ RegionExists(r)
  \* Parent must be CLOSED -- the child UNASSIGN completed.
  /\ regionState[r].state = "CLOSED"
  \* No procedure attached -- child UNASSIGN cleared it.
  /\ regionState[r].procType = "NONE"
  \* Split is pending at the close phase (pre-PONR).
  /\ parentProc[r].type = "SPLIT"
  /\ parentProc[r].step = "SPAWNED_CLOSE"
  \* Create fresh ASSIGN TRSP to reopen the parent.
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "ASSIGN",
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       NoServer,
       ![r].retries =
       0]
  \* Persist the new ASSIGN procedure to procStore.
  /\ procStore' =
       [procStore EXCEPT
       ![r] =
       NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  \* Clear parent procedure -- split is terminated.
  /\ parentProc' = [parentProc EXCEPT ![r] = NoParentProc]
  \* Revert meta from SPLITTING to CLOSED (parent's actual state).
  /\ metaTable' =
       [metaTable EXCEPT ![r].state = "CLOSED", ![r].location = NoServer ]
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

============================================================================
