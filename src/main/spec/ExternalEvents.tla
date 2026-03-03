--------------------------- MODULE ExternalEvents -----------------------------
(*
 * Environmental / external event actions for the HBase AssignmentManager.
 *
 * Contains actions for events external to the TRSP/SCP procedure
 * machinery: GoOffline (table disable), MasterDetectCrash (ZK expiry),
 * and ServerRestart (process supervisor).
 *
 * This module declares all shared variables as CONSTANT parameters.
 * The root AssignmentManager module instantiates it with
 * INSTANCE ExternalEvents WITH ... substituting the actual variables.
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

\* Shorthand for the SCP-related variables (used in UNCHANGED clauses).
scpVars == << scpState, scpRegions, walFenced, carryingMeta >>

---------------------------------------------------------------------------

(* Actions -- external events *)
\* Transition from CLOSED back to OFFLINE.
\* Used by RegionStateNode.offline() when a region is being taken fully
\* offline (e.g., table disable).
\* Pre: region is CLOSED with no procedure attached.
\* Post: regionState set to OFFLINE with cleared location (in-memory
\*       only -- metaTable is NOT updated).  META retains CLOSED;
\*       divergence is resolved on master restart.
\*
\* Source: RegionStateNode.offline() sets state to OFFLINE
\*         and clears the region location without writing to meta.
GoOffline(r) ==
  \* Guards: region is CLOSED and has no active procedure.
  /\ regionState[r].state = "CLOSED"
  /\ regionState[r].procType = "NONE"
  \* Move region to OFFLINE with cleared location (in-memory only).
  /\ regionState' =
       [regionState EXCEPT ![r].state = "OFFLINE", ![r].location = None]
  \* Meta is NOT updated: RegionStateNode.offline()
  \* does not write to meta.  metaTable retains CLOSED; divergence is
  \* resolved on master restart.  See Appendix D.5.
  /\ UNCHANGED << metaTable,
        rpcVars,
        rsVars,
        serverState,
        scpVars,
        locked,
        serverRegions,
        procStore
     >>

\* The master detects that a RegionServer has crashed (ZK ephemeral
\* node expired).  Marks the server as CRASHED and initiates a
\* ServerCrashProcedure (SCP).
\*
\* Regions remain in their pre-crash state (OPEN, OPENING, CLOSING)
\* with location pointing at the crashed server.  The RS is still
\* alive as a zombie. It may still be serving reads and writes. This
\* creates the window where NoDoubleAssignment can be violated if
\* WALs are not fenced before regions are reassigned.
\*
\* Pre: server is ONLINE and has at least one region assigned.
\* Post: serverState set to CRASHED, scpState set to GET_REGIONS.
\*
\* Impl state: SERVER_CRASH_START (=1), absorbed into this action.
\*   See SCP.tla header for the full enum traceability table.
\*
\* Source: ServerManager.expireServer() calls
\*         ServerManager.moveFromOnlineToDeadServers() and then
\*         AM.submitServerCrash(), which transitions the
\*         ServerStateNode to CRASHED and submits a
\*         ServerCrashProcedure.
MasterDetectCrash(s) ==
  /\ serverState[s] = "ONLINE"
  /\ \E r \in Regions: regionState[r].location = s
  /\ serverState' = [serverState EXCEPT ![s] = "CRASHED"]
  \* Non-deterministic: crashed server may or may not have been
  \* hosting hbase:meta.  If carryingMeta, SCP must reassign meta
  \* first (ASSIGN_META state); otherwise proceed to GET_REGIONS.
  /\ \/ /\ carryingMeta' = [carryingMeta EXCEPT ![s] = TRUE]
        /\ scpState' = [scpState EXCEPT ![s] = "ASSIGN_META"]
     \/ /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
        /\ scpState' = [scpState EXCEPT ![s] = "GET_REGIONS"]
  /\ UNCHANGED << regionState,
        metaTable,
        dispatchedOps,
        pendingReports,
        rsOnlineRegions,
        scpRegions,
        walFenced,
        locked,
        serverRegions,
        procStore
     >>

\* A process supervisor (Kubernetes, systemd, etc.) restarts a crashed
\* RegionServer.  The restarted server is empty: no regions, no pending
\* commands, no RS-side state.  Any pending reports from the previous
\* incarnation are discarded.  SCP state is reset to "NONE" and
\* walFenced is cleared.
\*
\* Design note -- RS epochs are NOT modeled explicitly.  Real HBase uses
\* ServerName (host + startcode) to distinguish incarnations. Stale
\* reports carry the old ServerName and are rejected.  Here the same
\* effect is achieved by atomic crash plus atomic restart (this action
\* purges stale reports).  An explicit epoch variable would only add
\* value if crash or restart were decomposed into non-atomic multi-step
\* sequences.
\*
\* Pre: server is CRASHED AND SCP for this server is complete or was
\*      never started.  The guard prevents premature restart while SCP
\*      is still processing regions from the crashed server.
\* Post: serverState set to ONLINE, pending reports from s discarded,
\*       SCP state reset, walFenced cleared.
\*
\* Source: Environmental assumption -- a process supervisor
\*         (Kubernetes, systemd, etc.) guarantees that crashed
\*         RegionServer processes are eventually restarted.
ServerRestart(s) ==
  \* Guard: server is CRASHED and SCP is complete (or never started).
  /\ serverState[s] = "CRASHED"
  /\ scpState[s] \in { "DONE", "NONE" }
  \* Bring the server back ONLINE.
  /\ serverState' = [serverState EXCEPT ![s] = "ONLINE"]
  \* Purge all stale pending reports from this server's prior incarnation.
  /\ pendingReports' = {rpt \in pendingReports: rpt.server # s}
  \* Clear stale commands for the restarting server.  The prior incarnation
  \* never received them (or crashed before consuming); the new process
  \* starts with an empty queue.  Without this, RSOpen could fire on
  \* stale OPEN commands, creating ghost regions (r in rsOnlineRegions
  \* but master has regionState ABNORMALLY_CLOSED / no location).
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
  \* Clear RS-side state for the restarting server.  The new process has
  \* no regions; the zombie's rsOnlineRegions was never cleared by RSAbort.
  \* Without this, RSMasterAgreementConverse fails: restarted server ONLINE
  \* but rsOnlineRegions[s] still has regions the master has ABNORMALLY_CLOSED.
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
  \* Reset SCP state for the restarting server.
  /\ scpState' = [scpState EXCEPT ![s] = "NONE"]
  /\ scpRegions' = [scpRegions EXCEPT ![s] = {}]
  /\ walFenced' = [walFenced EXCEPT ![s] = FALSE]
  /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
  \* Clear ServerStateNode tracking for the restarting server.
  \* In the implementation, SCP.removeServer() calls
  \* RegionStates.removeServer() which removes the ServerStateNode.
  /\ serverRegions' = [serverRegions EXCEPT ![s] = {}]
  \* Region state and META unchanged.
  /\ UNCHANGED << regionState, metaTable, locked, procStore >>

============================================================================
