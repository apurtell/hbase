------------------------------- MODULE Merge ---------------------------------
(*
 * Merge procedure actions for the HBase AssignmentManager.
 *
 * Models MergeTableRegionsProcedure: forward path (merge two adjacent
 * regions into one) and pre-PONR rollback (abort and reopen targets).
 * The procedure uses the parent-child framework: it spawns child
 * TRSPs via addChildProcedure() and yields while children execute.
 *
 * Forward-path actions:
 *   MergePrepare       -- set MERGING, create parentProc on both targets,
 *                         spawn child UNASSIGN TRSPs
 *   MergeCheckClosed   -- resume after child TRSPs complete
 *   MergeUpdateMeta    -- PONR: meta write, materialize merged region,
 *                         spawn child ASSIGN TRSP
 *   MergeDone          -- merged region OPEN, cleanup
 *
 * Rollback action:
 *   MergeFail          -- pre-PONR failure: create fresh ASSIGNs
 *                         to reopen targets, clear parentProc
 *
 * The parentProc[r] variable tracks the parent procedure's state on
 * BOTH target regions.  It persists across child TRSP lifecycles and
 * survives master crash.  The ref1 field cross-references the other
 * target (peer); ref2 references the merged region identifier.
 *
 * MergeFail fires non-deterministically at the same precondition
 * as MergeCheckClosed (both targets CLOSED, children complete).
 * TLC explores both the success path (MergeCheckClosed) and the
 * failure path (MergeFail) for every reachable merge state.
 *
 * Source: MergeTableRegionsProcedure.executeFromState() L189-255;
 *         MergeTableRegionsProcedure.rollbackState() L266-310.
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

\* Helper: are two regions adjacent (r1's endKey = r2's startKey)?
Adjacent(r1, r2) == /\ RegionExists(r1)
                    /\ RegionExists(r2)
                    /\ metaTable[r1].keyRange.endKey = metaTable[r2].keyRange.startKey

\* Helper: is meta available? (no server carrying meta is crashed)
MetaIsAvailable ==
  \A s \in Servers: carryingMeta[s] = FALSE \/ serverState[s] = "ONLINE"

---------------------------------------------------------------------------

(* Actions *)
\* Initiate a merge on two adjacent OPEN regions.
\* Atomically sets both targets to MERGING in-memory and meta, creates
\* parentProc on both (with cross-references), and spawns child UNASSIGN
\* TRSPs on both targets.
\*
\* In the implementation, prepareMergeRegion() and
\* addChildProcedure(createUnassignProcedures()) execute within the
\* same executeFromState() call under region locks -- effectively atomic.
\*
\* Pre: master alive, PEWorker available, UseMerge = TRUE,
\*      r1 and r2 are OPEN with locations, no procedures attached,
\*      no parent procedures in progress, Adjacent(r1, r2),
\*      m is an unused identifier (metaTable[m].keyRange = NoRange).
\* Post: both targets state = MERGING, parentProc = [MERGE, SPAWNED_CLOSE,
\*       ref1=peer, ref2=m].  Child UNASSIGN TRSPs spawned on both.
\*
\* Source: prepareMergeRegion() sets MERGING;
\*         CLOSE_REGIONS: addChildProcedure(createUnassignProcedures).
MergePrepare(r1, r2, m) ==
  \* Merge feature must be enabled.
  /\ UseMerge = TRUE
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Both regions must exist (have assigned keyspaces).
  /\ RegionExists(r1)
  /\ RegionExists(r2)
  \* Both must be OPEN with locations.
  /\ regionState[r1].state = "OPEN"
  /\ regionState[r1].location # NoServer
  /\ regionState[r2].state = "OPEN"
  /\ regionState[r2].location # NoServer
  \* No procedures attached to either target.
  /\ regionState[r1].procType = "NONE"
  /\ regionState[r2].procType = "NONE"
  \* No parent procedures in progress on either target.
  /\ ~HasActiveParent(r1)
  /\ ~HasActiveParent(r2)
  \* Regions must be adjacent.
  /\ Adjacent(r1, r2)
  \* All three identifiers must be distinct.
  /\ r1 # r2
  /\ r1 # m
  /\ r2 # m
  \* m must be an unused identifier.
  /\ metaTable[m].keyRange = NoRange
  \* No parent procedure on m either.
  /\ ~HasActiveParent(m)
  \* No table-level exclusive lock on this region's table.
  /\ NoTableExclusiveLock(r1)
  \* Transition both targets to MERGING and spawn child UNASSIGN TRSPs.
  /\ regionState' =
       [regionState EXCEPT
       \* r1: MERGING with child UNASSIGN TRSP.
       ![r1].state =
       "MERGING",
       ![r1].procType =
       "UNASSIGN",
       ![r1].procStep =
       "CLOSE",
       ![r1].targetServer =
       regionState[r1].location,
       ![r1].retries =
       0,
       \* r2: MERGING with child UNASSIGN TRSP.
       ![r2].state =
       "MERGING",
       ![r2].procType =
       "UNASSIGN",
       ![r2].procStep =
       "CLOSE",
       ![r2].targetServer =
       regionState[r2].location,
       ![r2].retries =
       0]
  \* Update meta to MERGING (preserving locations).
  /\ metaTable' =
       [metaTable EXCEPT
       ![r1].state = "MERGING",
       ![r2].state = "MERGING" ]
  \* Persist child UNASSIGN procedures to procStore.
  /\ procStore' =
       [procStore EXCEPT
       ![r1] =
       NewProcRecord("UNASSIGN", "CLOSE", regionState[r1].location, NoTransition),
       ![r2] =
       NewProcRecord("UNASSIGN", "CLOSE", regionState[r2].location, NoTransition)]
  \* Create parent procedure records on both targets with cross-references.
  \* ref1 = peer (other target), ref2 = merged region identifier.
  /\ parentProc' =
       [parentProc EXCEPT
       ![r1] = [ type |-> "MERGE", step |-> "SPAWNED_CLOSE",
                 ref1 |-> r2, ref2 |-> m ],
       ![r2] = [ type |-> "MERGE", step |-> "SPAWNED_CLOSE",
                 ref1 |-> r1, ref2 |-> m ]]
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

\* Resume the merge after both child UNASSIGN TRSPs complete.
\*
\* When both TRSP close paths finish, r1 and r2 are CLOSED with
\* procType = NONE.  This action detects parentProc.step = SPAWNED_CLOSE
\* on r1 (the primary target) and re-attaches the MERGE procedure
\* lock, then advances to PONR.
\*
\* Pre: master alive, PEWorker available, r1 is CLOSED with no procedure,
\*      r2 (= parentProc[r1].ref1) is CLOSED with no procedure,
\*      both parentProc = [MERGE, SPAWNED_CLOSE].
\* Post: r1 procType = MERGE (re-attached), parentProc[r1].step = PONR.
\*
\* Source: checkClosedRegions() L279-283.
MergeCheckClosed(r1) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* r1 must still exist.
  /\ RegionExists(r1)
  \* r1 must be CLOSED -- the UNASSIGN child completed.
  /\ regionState[r1].state = "CLOSED"
  \* No procedure attached -- child UNASSIGN cleared it.
  /\ regionState[r1].procType = "NONE"
  \* Merge is pending at the close phase on r1.
  /\ parentProc[r1].type = "MERGE"
  /\ parentProc[r1].step = "SPAWNED_CLOSE"
  \* Read peer from parentProc.
  /\ LET r2 == parentProc[r1].ref1
     IN \* r2 must also be CLOSED with no procedure.
        /\ regionState[r2].state = "CLOSED"
        /\ regionState[r2].procType = "NONE"
        \* r2's parentProc must also be at SPAWNED_CLOSE.
        /\ parentProc[r2].type = "MERGE"
        /\ parentProc[r2].step = "SPAWNED_CLOSE"
  \* Re-attach MERGE procedure to r1 for protection.
  /\ regionState' =
       [regionState EXCEPT ![r1].procType = "MERGE", ![r1].procStep = "IDLE"]
  \* Persist MERGE procedure to procStore.
  /\ procStore' =
       [procStore EXCEPT
       ![r1] =
       NewProcRecord("MERGE", "IDLE", NoServer, NoTransition)]
  \* Advance r1 to PONR.
  /\ parentProc' = [parentProc EXCEPT ![r1].step = "PONR"]
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

\* Point of No Return: update meta, materialize merged region, spawn
\* child ASSIGN TRSP on merged region.
\*
\* Atomically:
\*   - r1, r2: CLOSED -> MERGED (in-memory and meta). Terminal state.
\*   - m: keyspace = [r1.startKey, r2.endKey), state = MERGING_NEW
\*     (in-memory), CLOSED (meta).  procType = ASSIGN (child spawned).
\*
\* Pre: master alive, PEWorker available, meta available, r1 has
\*      procType = MERGE, parentProc[r1] = [MERGE, PONR, ref1=r2, ref2=m].
\* Post: targets in MERGED state, merged region materialized with
\*       child ASSIGN TRSP.  parentProc[r1].step = SPAWNED_OPEN.
\*
\* Source: AssignmentManager.markRegionAsMerged();
\*         RegionStateStore.mergeRegions();
\*         OPEN_MERGED: addChildProcedure(createAssignProcedures).
MergeUpdateMeta(r1) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* Meta region must be accessible (not on a crashed server).
  /\ MetaIsAvailable
  \* r1 must still exist.
  /\ RegionExists(r1)
  \* The MERGE procedure must be attached to r1.
  /\ regionState[r1].procType = "MERGE"
  \* Parent procedure has reached the point of no return.
  /\ parentProc[r1].type = "MERGE"
  /\ parentProc[r1].step = "PONR"
  \* r1 must be CLOSED before meta can be updated.
  /\ regionState[r1].state = "CLOSED"
  \* Read peer and merged region from parentProc.
  /\ LET r2 == parentProc[r1].ref1
         m == parentProc[r1].ref2
         startK == metaTable[r1].keyRange.startKey
         endK == metaTable[r2].keyRange.endKey
     IN \* r2 must also be CLOSED.
        /\ regionState[r2].state = "CLOSED"
        \* m must still be unused.
        /\ metaTable[m].keyRange = NoRange
        \* Update regionState: targets -> MERGED, merged -> MERGING_NEW
        \* with child ASSIGN TRSP spawned.
        /\ regionState' =
             [regionState EXCEPT
             \* r1: CLOSED -> MERGED, clear location and procedure.
             ![r1].state =
             "MERGED",
             ![r1].location =
             NoServer,
             ![r1].procType =
             "NONE",
             ![r1].procStep =
             "IDLE",
             ![r1].targetServer =
             NoServer,
             ![r1].retries =
             0,
             \* r2: CLOSED -> MERGED, clear location.
             ![r2].state =
             "MERGED",
             ![r2].location =
             NoServer,
             \* m: MERGING_NEW with child ASSIGN TRSP.
             ![m] =
             [ state |-> "MERGING_NEW",
               location |-> NoServer,
               procType |-> "ASSIGN",
               procStep |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> NoServer,
               retries |-> 0
             ]]
        \* Update meta: targets -> MERGED, merged -> CLOSED with keyRange
        \* and table identity.  Folds old regionKeyRange/regionTable
        \* mutations into a single metaTable EXCEPT.
        /\ metaTable' =
             [metaTable EXCEPT
             ![r1].state = "MERGED",
             ![r1].location = NoServer,
             ![r2].state = "MERGED",
             ![r2].location = NoServer,
             ![m].state = "CLOSED",
             ![m].location = NoServer,
             ![m].keyRange = [ startKey |-> startK, endKey |-> endK ],
             ![m].table = metaTable[r1].table ]
        \* Persist merged region ASSIGN procedure to procStore.
        \* Clear r1's procStore (MERGE lock was removed above).
        /\ procStore' =
             [procStore EXCEPT
             ![r1] =
             NoProcedure,
             ![m] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
  \* Parent advances to SPAWNED_OPEN (yielding to merged ASSIGN).
  /\ parentProc' = [parentProc EXCEPT ![r1].step = "SPAWNED_OPEN"]
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        tableEnabled,
        zkNode
     >>

\* Complete the merge after the merged region is OPEN.
\*
\* Clears the targets' keyspaces to NoRange (regions "deleted" --
\* models the post-compaction cleanup) and clears all parent
\* procedure state.
\*
\* Pre: master alive, PEWorker available, parentProc[r1] = [MERGE, SPAWNED_OPEN],
\*      merged region m is OPEN with procType = NONE (ASSIGN completed).
\* Post: target keyspaces cleared (NoRange), parentProc cleared on both.
\*       Targets stay in MERGED state (terminal).
\*
\* Source: POST_OPERATION state (procedure completes).
MergeDone(r1) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* r1 must still exist (has an assigned keyspace).
  /\ RegionExists(r1)
  \* Parent procedure is waiting for merged region to open.
  /\ parentProc[r1].type = "MERGE"
  /\ parentProc[r1].step = "SPAWNED_OPEN"
  \* Read peer and merged region from parentProc.
  /\ LET r2 == parentProc[r1].ref1
         m == parentProc[r1].ref2
     IN \* Merged region must be OPEN and unattached.
        /\ regionState[m].state = "OPEN"
        /\ regionState[m].procType = "NONE"
        \* Clear target keyspaces and table via metaTable (regions "deleted").
        /\ metaTable' =
             [metaTable EXCEPT
             ![r1].keyRange = NoRange,
             ![r1].table = NoTable,
             ![r2].keyRange = NoRange,
             ![r2].table = NoTable ]
  \* No procStore change needed (r1 already cleared at MergeUpdateMeta).
  /\ UNCHANGED << procStore, tableEnabled >>
  \* Clear parent procedure on both targets.
  /\ parentProc' =
       [parentProc EXCEPT
       ![r1] = NoParentProc,
       ![parentProc[r1].ref1] = NoParentProc]
  \* regionState unchanged (targets already MERGED, m already OPEN).
  /\ UNCHANGED << scpVars,
        rpcVars,
        serverVars,
        rsVars,
        masterVars,
        peVars,
        regionState,
        tableEnabled,
        zkNode
     >>

\* Pre-PONR rollback: abort the merge and create fresh ASSIGN
\* TRSPs to reopen both target regions.
\*
\* Fires non-deterministically at the same precondition as
\* MergeCheckClosed (both targets CLOSED, child UNASSIGNs complete,
\* parentProc = [MERGE, SPAWNED_CLOSE]).  The non-deterministic
\* choice between MergeCheckClosed and MergeFail models the
\* success/failure decision: TLC explores both branches.
\*
\* The rollback:
\*   1. Creates fresh ASSIGN TRSPs on both targets (GET_ASSIGN_CANDIDATE)
\*      to reopen them.
\*   2. Clears parentProc on both targets to NoParentProc.
\*   3. Reverts meta from MERGING to CLOSED (targets' actual state;
\*      the ASSIGN TRSPs will update meta to OPENING -> OPEN later).
\*   4. Persists the new ASSIGN procedures to procStore.
\*
\* No merged region was created pre-PONR (MergeAtomicity invariant),
\* so no merged region cleanup is needed.  metaTable keyRange unchanged
\* (targets keep their keyspaces, m stays NoRange).
\*
\* Pre: master alive, PEWorker available, r1 and r2 exist, both CLOSED,
\*      no procedure attached, parentProc = SPAWNED_CLOSE.
\* Post: ASSIGN TRSPs spawned on both, parentProc cleared, meta reverted.
\*
\* Source: rollbackState() L266-310;
\*         openParentRegion() analog for merge targets.
MergeFail(r1) ==
  \* Master must be alive to execute the procedure.
  /\ masterAlive = TRUE
  \* PEWorker thread available to execute this step.
  /\ availableWorkers > 0
  \* r1 must still exist.
  /\ RegionExists(r1)
  \* r1 must be CLOSED -- the child UNASSIGN completed.
  /\ regionState[r1].state = "CLOSED"
  \* No procedure attached -- child UNASSIGN cleared it.
  /\ regionState[r1].procType = "NONE"
  \* Merge is pending at the close phase (pre-PONR).
  /\ parentProc[r1].type = "MERGE"
  /\ parentProc[r1].step = "SPAWNED_CLOSE"
  \* Read peer from parentProc.
  /\ LET r2 == parentProc[r1].ref1
     IN \* r2 must also be CLOSED with no procedure.
        /\ regionState[r2].state = "CLOSED"
        /\ regionState[r2].procType = "NONE"
        \* r2's parentProc must also be at SPAWNED_CLOSE.
        /\ parentProc[r2].type = "MERGE"
        /\ parentProc[r2].step = "SPAWNED_CLOSE"
        \* Create fresh ASSIGN TRSPs to reopen both targets.
        /\ regionState' =
             [regionState EXCEPT
             ![r1].procType =
             "ASSIGN",
             ![r1].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r1].targetServer =
             NoServer,
             ![r1].retries =
             0,
             ![r2].procType =
             "ASSIGN",
             ![r2].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r2].targetServer =
             NoServer,
             ![r2].retries =
             0]
        \* Persist the new ASSIGN procedures to procStore.
        /\ procStore' =
             [procStore EXCEPT
             ![r1] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition),
             ![r2] =
             NewProcRecord("ASSIGN", "GET_ASSIGN_CANDIDATE", NoServer, NoTransition)]
        \* Revert meta from MERGING to CLOSED (targets' actual state).
        /\ metaTable' =
             [metaTable EXCEPT
             ![r1].state = "CLOSED", ![r1].location = NoServer,
             ![r2].state = "CLOSED", ![r2].location = NoServer ]
  \* Clear parent procedure on both targets -- merge is terminated.
  /\ parentProc' =
       [parentProc EXCEPT
       ![r1] = NoParentProc,
       ![parentProc[r1].ref1] = NoParentProc]
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
