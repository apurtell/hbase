------------------------------- MODULE ZK -------------------------------------
(*
 * Minimal ZooKeeper model for the HBase AssignmentManager.
 *
 * Models ZK ephemeral nodes for RegionServer liveness.  Each live RS
 * holds an ephemeral node; when the RS process dies, ZK detects the
 * session expiry and deletes the node.  The master reads these nodes
 * to determine which servers are alive.
 *
 * ZK is the ground truth about RS liveness, independent of master
 * state.  A RS can die at any time and ZK will detect it regardless
 * of whether the master is alive or not.
 *
 * Contains one action:
 *   ZKSessionExpire(s) — RS dies, ZK deletes its ephemeral node
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
         regionKeyRange

\* Shorthand for PEWorker pool variables (used in UNCHANGED clauses).
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>

---------------------------------------------------------------------------

(* Actions -- ZK ephemeral node lifecycle *)
\* ZK detects that a RegionServer's session has expired (the RS
\* process is dead) and deletes its ephemeral node.
\*
\* This is a ZK-side action, independent of both master and RS.
\* ZK is the ground truth about RS liveness.  Any live RS can die
\* at any time — this action is non-deterministic, like MasterCrash.
\*
\* After ZKSessionExpire fires:
\*   - MasterDetectCrash (if master is alive) will observe
\*     zkNode[s] = FALSE and mark the server as CRASHED.
\*   - MasterRecover (if master restarts) will read zkNode[s] = FALSE
\*     and mark the server as CRASHED during recovery.
\*   - RSAbort will eventually clean up the zombie RS state.
\*
\* The RS process may still be a zombie briefly after ZK session
\* expiry (rsOnlineRegions is NOT cleared here — RSAbort handles
\* that).  This preserves the zombie window that makes WAL fencing
\* necessary for correctness.
\*
\* Source: ZooKeeper session timeout; ephemeral node under /hbase/rs
\*         is deleted when the RS's ZK session expires.
ZKSessionExpire(s) ==
  \* The RS has a live ZK ephemeral node.
  /\ zkNode[s] = TRUE
  \* Delete the ephemeral node. ZK now considers this RS dead.
  /\ zkNode' = [zkNode EXCEPT ![s] = FALSE]
  \* Everything else is unchanged.  The zombie RS may still hold
  \* regions in rsOnlineRegions until RSAbort fires.
  /\ UNCHANGED << regionState,
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
        peVars,
        regionKeyRange
     >>

============================================================================
