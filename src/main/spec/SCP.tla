------------------------------- MODULE SCP ------------------------------------
(*
 * ServerCrashProcedure (SCP) actions for the HBase AssignmentManager.
 *
 * Contains the SCP state machine: SCPAssignMeta, SCPGetRegions,
 * SCPFenceWALs, SCPAssignRegion, and SCPDone.
 *
 * This module declares all shared variables as CONSTANT parameters.
 * The root AssignmentManager module instantiates it with
 * INSTANCE SCP WITH ... substituting the actual variables.
 *)
EXTENDS AssignmentManagerTypes

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
         locked,
         carryingMeta,
         serverRegions,
         procStore

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == << dispatchedOps, pendingReports >>

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == << rsOnlineRegions >>

---------------------------------------------------------------------------

(* Actions -- ServerCrashProcedure (SCP) state machine *)
\*
\* The implementation's ServerCrashState enum (MasterProcedure.proto)
\* has 13 values (3 deprecated).  The model abstracts these into 6
\* states that capture the assignment-relevant SCP lifecycle.  States
\* omitted from the model are pass-through cleanup/replication steps
\* that do not interact with region state, assignment, or fencing.
\*
\*   Model state      | Implementation enum(s) abstracted
\*   -----------------+--------------------------------------------------
\*   (MasterDetect-   | SERVER_CRASH_START (=1)
\*    Crash, in       |   Determines if server carries meta, sets initial
\*    ExternalEvents) |   SCP state.  Collapsed into MasterDetectCrash.
\*   "ASSIGN_META"    | SERVER_CRASH_SPLIT_META_LOGS (=10),
\*                    |   SERVER_CRASH_ASSIGN_META (=11),
\*                    |   SERVER_CRASH_DELETE_SPLIT_META_WALS_DIR (=12)
\*                    |   Meta WAL split + meta reassign + cleanup.
\*                    |   Modeled as single atomic meta reassignment.
\*   "GET_REGIONS"    | SERVER_CRASH_GET_REGIONS (=3)
\*                    |   1:1 match.
\*   "FENCE_WALS"     | SERVER_CRASH_SPLIT_LOGS (=5)
\*                    |   WAL splitting → fencing semantics only.
\*   "ASSIGN"         | SERVER_CRASH_ASSIGN (=8),
\*                    |   SERVER_CRASH_WAIT_ON_ASSIGN (=9)
\*                    |   Assign + wait collapsed into per-region
\*                    |   SCPAssignRegion + SCPDone.
\*   "DONE"           | SERVER_CRASH_CLAIM_REPLICATION_QUEUES (=14),
\*                    |   SERVER_CRASH_DELETE_SPLIT_WALS_DIR (=13),
\*                    |   SERVER_CRASH_FINISH (=100)
\*                    |   Replication queue claiming and WAL dir cleanup
\*                    |   are orthogonal to assignment; collapsed with
\*                    |   FINISH into terminal "DONE".
\*   (not modeled)    | SERVER_CRASH_PROCESS_META (=2, deprecated),
\*                    |   SERVER_CRASH_NO_SPLIT_LOGS (=4, deprecated),
\*                    |   SERVER_CRASH_HANDLE_RIT2 (=20, deprecated)
\*
\* SCP meta-reassignment step: when the crashed server was hosting
\* hbase:meta, the SCP must reassign meta before proceeding to the
\* normal crash-recovery path.  This models the ASSIGN_META sub-step
\* of the ServerCrashProcedure.  Meta reassignment is abstracted as
\* a single atomic step (the actual implementation creates a TRSP
\* for the meta region and waits for it to complete).
\*
\* Pre: scpState[s] = "ASSIGN_META", carryingMeta[s] = TRUE.
\* Post: scpState[s] = "GET_REGIONS" (meta is now online, SCP
\*       proceeds to the normal path).
\*
\* Source: SCP.executeFromState() SERVER_CRASH_SPLIT_META_LOGS
\*         and ASSIGN_META cases.
SCPAssignMeta(s) ==
  \* SCP is in the meta-recovery sub-path.
  /\ scpState[s] = "ASSIGN_META"
  \* Only servers that were hosting hbase:meta enter this path.
  /\ carryingMeta[s] = TRUE
  \* Meta reassigned (abstracted); advance to normal crash-recovery.
  /\ scpState' = [scpState EXCEPT ![s] = "GET_REGIONS"]
  \* Meta is now online; clear the carryingMeta flag.
  /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
  /\ UNCHANGED << regionState,
        metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpRegions,
        walFenced,
        locked,
        serverRegions,
        procStore
     >>

\* SCP step 1: Snapshot the set of regions assigned to the crashed
\* server.  This snapshot can go stale: between GET_REGIONS and
\* ASSIGN, concurrent TRSPs may move regions, causing
\* isMatchingRegionLocation() to skip them (Iteration 15).
\*
\* Implementation note (branch-2.6): at this step, the implementation
\* also calls AM.markRegionsAsCrashed(), which updates internal
\* bookkeeping (RIT tracking, crash timestamps) to mark the regions
\* as unavailable.  This does NOT change the RegionState.State enum
\* — the actual transition to ABNORMALLY_CLOSED happens later in
\* assignRegions() (SERVER_CRASH_ASSIGN).  The model's abstraction
\* (no region state change at GET_REGIONS, state change only at
\* SCPAssignRegion) remains valid.
\*
\* Pre: scpState[s] = "GET_REGIONS".
\* Post: scpRegions[s] = snapshot of regions with location = s,
\*       scpState advances to "FENCE_WALS".
\*
\* Source: ServerCrashProcedure.executeFromState()
\*         SERVER_CRASH_GET_REGIONS case calls
\*         ServerCrashProcedure.getRegionsOnCrashedServer()
\*         which delegates to AM.getRegionsOnServer();
\*         then AM.markRegionsAsCrashed() updates
\*         RIT tracking for each region.
SCPGetRegions(s) ==
  \* SCP is in GET_REGIONS state for this crashed server.
  /\ scpState[s] = "GET_REGIONS"
  \* Meta must be online (no server in ASSIGN_META) before SCP proceeds.
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
  \* Snapshot regions from the ServerStateNode tracking for the
  \* crashed server.  In the implementation, getRegionsOnServer()
  \* reads from the ServerStateNode's region set, NOT from
  \* regionNode.getRegionLocation().  These two can be out of sync.
  \* Source: AM.getRegionsOnServer().
  /\ scpRegions' = [scpRegions EXCEPT ![s] = serverRegions[s]]
  \* Advance SCP to the WAL fencing step.
  /\ scpState' = [scpState EXCEPT ![s] = "FENCE_WALS"]
  \* Region state, meta, RPCs, RS-side state, and WAL fencing unchanged.
  /\ UNCHANGED << regionState,
        metaTable,
        rpcVars,
        rsVars,
        serverState,
        walFenced,
        locked,
        carryingMeta,
        serverRegions,
        procStore
     >>

\* SCP step 2: Revoke WAL leases for the crashed server.  After this
\* step, the zombie RS cannot write to its WALs.  Any write attempt
\* will fail with an HDFS lease exception, triggering RS self-abort.
\* This is the fencing mechanism that prevents write-side split-brain.
\*
\* Pre: scpState[s] = "FENCE_WALS".
\* Post: walFenced[s] = TRUE, scpState advances to "ASSIGN".
\*
\* Source: ServerCrashProcedure.executeFromState()
\*         SERVER_CRASH_SPLIT_LOGS case.
SCPFenceWALs(s) ==
  \* SCP is in FENCE_WALS state for this crashed server.
  /\ scpState[s] = "FENCE_WALS"
  \* Meta must be online (no server in ASSIGN_META) before SCP proceeds.
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
  \* Revoke WAL leases — zombie RS can no longer write.
  /\ walFenced' = [walFenced EXCEPT ![s] = TRUE]
  \* Advance SCP to the region assignment step.
  /\ scpState' = [scpState EXCEPT ![s] = "ASSIGN"]
  \* Region state, meta, RPCs, RS-side state, and region snapshot unchanged.
  /\ UNCHANGED << regionState,
        metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpRegions,
        locked,
        carryingMeta,
        serverRegions,
        procStore
     >>

\* SCP step 3: Process ONE region from the SCP's region snapshot.
\* Each invocation handles a single region and removes it from
\* scpRegions[s].  Three sub-paths:
\*
\*   Skip (location check): if the region's master-side location no
\*     longer matches the crashed server, SCP skips the region entirely.
\*     This models the isMatchingRegionLocation() guard, which the
\*     implementation always applies.
\*   Path A (procedure attached): transition region to ABNORMALLY_CLOSED,
\*     clear location; existing procedure preserved for TRSPServerCrashed.
\*   Path B (no procedure): transition to ABNORMALLY_CLOSED, clear
\*     location, create fresh ASSIGN/GET_ASSIGN_CANDIDATE procedure.
\*
\* Pre: scpState[s] = "ASSIGN", r in scpRegions[s], walFenced[s] = TRUE.
\* Post: r removed from scpRegions[s], region transitioned (or skipped).
\*
\* Source: ServerCrashProcedure.assignRegions();
\*         ServerCrashProcedure.isMatchingRegionLocation().
SCPAssignRegion(s, r) ==
  \* SCP is in ASSIGN state for this crashed server.
  /\ scpState[s] = "ASSIGN"
  \* Meta must be online (no server in ASSIGN_META) before SCP proceeds.
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
  \* Region is in this SCP's snapshot (collected at GET_REGIONS).
  /\ r \in scpRegions[s]
  \* WAL leases for the crashed server must already be revoked
  \* before reassigning any of its regions.
  /\ walFenced[s] = TRUE
  \* Region must not be locked by an in-progress procedure step
  \* (e.g., a concurrent meta write).
  /\ locked[r] = FALSE
  /\ \/ \* --- Skip: isMatchingRegionLocation fails ---
        \* Between SCPGetRegions and now, a concurrent TRSP may have moved
        \* this region to another server.  The implementation skips such
        \* regions.  If the concurrent TRSP subsequently fails, the region
        \* may be lost without manual intervention (HBASE-24293).
        /\ regionState[r].location # s
        \* Skip: only shrink the SCP snapshot; no state changes.
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
        /\ UNCHANGED << regionState,
              metaTable,
              rpcVars,
              rsOnlineRegions,
              pendingReports,
              serverState,
              walFenced,
              scpState,
              locked,
              carryingMeta,
              serverRegions,
              procStore
           >>
     \/ \* --- Path A: TRSP already attached ---
        \* Location matches; procedure exists.
        \* Transition region to ABNORMALLY_CLOSED and atomically
        \* convert the existing TRSP to ASSIGN/GET_ASSIGN_CANDIDATE.
        \* This models the implementation's serverCrashed() callback
        \* firing under the same RegionStateNode lock as the state
        \* transition — they are a single atomic step.
        \*
        \* Source: ServerCrashProcedure.assignRegions() acquires
        \*         RegionStateNode.lock(), then calls
        \*         regionNode.getProcedure().serverCrashed(env, ...);
        \*         TRSP.serverCrashed() → AM.regionClosedAbnormally()
        \*         all execute under that same lock.
        /\ regionState[r].location = s
        /\ regionState[r].procType # "NONE"
        \* Clear r from rsOnlineRegions on all servers.  The region may
        \* have moved between SCPGetRegions and now; clearing everywhere
        \* prevents ghost regions.
        /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
        \* Clear ALL stale commands for this region from every server.
        \* The procedure is being reset; any in-flight OPEN/CLOSE RPCs
        \* are obsolete.
        /\ dispatchedOps' =
             [t \in Servers |-> {cmd \in dispatchedOps[t]: cmd.region # r}
             ]
        \* Drop ALL reports for r: any OPENED/CLOSED/FAILED_OPEN
        \* reports are from the abandoned procedure and must not be
        \* consumed after reassignment.
        /\ pendingReports' = {pr \in pendingReports: pr.region # r}
        \* Atomically: mark ABNORMALLY_CLOSED, clear location,
        \* AND convert procedure to ASSIGN/GET_ASSIGN_CANDIDATE.
        /\ regionState' =
             [regionState EXCEPT
             ![r].state =
             "ABNORMALLY_CLOSED",
             ![r].location =
             None,
             ![r].procType =
             "ASSIGN",
             ![r].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r].targetServer =
             None,
             ![r].retries =
             0]
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "ABNORMALLY_CLOSED", location |-> None ]]
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
        \* Update persisted procedure: convert to ASSIGN.
        /\ procStore' =
             [procStore EXCEPT
             ![r] =
             [ type |-> "ASSIGN",
               step |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> None
             ]]
        \* ServerStateNode tracking: remove r from crashed server s.
        /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \ { r }]
        \* Server state, WAL fencing, SCP state, locks, and
        \* meta-carrying flag unchanged.
        /\ UNCHANGED << serverState,
              walFenced,
              scpState,
              locked,
              carryingMeta
           >>
     \/ \* --- Path B: No TRSP attached ---
        \* Location matches; no procedure.
        \* Transition to ABNORMALLY_CLOSED and attach a fresh
        \* ASSIGN procedure at GET_ASSIGN_CANDIDATE.
        /\ regionState[r].location = s
        /\ regionState[r].procType = "NONE"
        \* Clear r from rsOnlineRegions on all servers.
        /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
        \* Drop stale OPENED reports for r.
        /\ pendingReports' =
             {pr \in pendingReports: pr.code # "OPENED" \/ pr.region # r}
        /\ regionState' =
             [regionState EXCEPT
             ![r].state =
             "ABNORMALLY_CLOSED",
             ![r].location =
             None,
             ![r].procType =
             "ASSIGN",
             ![r].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r].targetServer =
             None,
             ![r].retries =
             0]
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "ABNORMALLY_CLOSED", location |-> None ]]
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
        \* Insert a fresh ASSIGN procedure into the store.
        /\ procStore' =
             [procStore EXCEPT
             ![r] =
             [ type |-> "ASSIGN",
               step |-> "GET_ASSIGN_CANDIDATE",
               targetServer |-> None
             ]]
        \* ServerStateNode tracking: remove r from crashed server s.
        /\ serverRegions' = [serverRegions EXCEPT ![s] = @ \ { r }]
        \* Dispatched ops, server state, WAL fencing, SCP state, locks, and
        \* meta-carrying flag unchanged.
        /\ UNCHANGED << dispatchedOps,
              serverState,
              walFenced,
              scpState,
              locked,
              carryingMeta
           >>

\* SCP step 4: All regions processed.  Mark SCP as complete.
\*
\* Pre: scpState[s] = "ASSIGN", scpRegions[s] = {} (all processed).
\* Post: scpState[s] = "DONE".
\*
\* Source: ServerCrashProcedure.executeFromState()
\*         SERVER_CRASH_FINISH case -- the procedure
\*         completes and is cleaned up by the ProcedureExecutor.
SCPDone(s) ==
  \* SCP is in ASSIGN state and all regions have been processed.
  /\ scpState[s] = "ASSIGN"
  /\ scpRegions[s] = {}
  \* Mark SCP as complete for this crashed server.
  /\ scpState' = [scpState EXCEPT ![s] = "DONE"]
  \* All other state unchanged — region reassignments already applied.
  /\ UNCHANGED << regionState,
        metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpRegions,
        walFenced,
        locked,
        carryingMeta,
        serverRegions,
        procStore
     >>

============================================================================
