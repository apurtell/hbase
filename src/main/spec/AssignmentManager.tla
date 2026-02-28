------------------------ MODULE AssignmentManager -------------------------
(*
 * TLA+ specification of the HBase AssignmentManager.
 *
 * Models the region assignment lifecycle: state transitions, persistent
 * metadata, procedure-driven operations, RPC dispatch, RegionServer-side
 * behavior, and crash recovery.  Built iteratively per the plan in
 * TLA_ASSIGNMENT_MANAGER_PLAN.md.
 *
 * Two parallel views of region state are maintained:
 *   - regionState: volatile in-memory master state (lost on master crash)
 *   - metaTable:   persistent state in hbase:meta (survives master crash)
 * State and location fields are updated atomically (RegionStateNode lock
 * held across both writes; see plan Appendix A.8 item 2).
 *
 * Procedure state is inlined into regionState, keyed by region rather
 * than by a global procedure ID counter.  ProcedureConsistency (from
 * earlier iterations) proved at most one procedure per region, so
 * region identity is sufficient to distinguish procedures.  Procedure
 * fields (procType, procStep, targetServer, retries) model the
 * RegionStateNode.setProcedure() / unsetProcedure() discipline.
 * ASSIGN, UNASSIGN, and MOVE follow TRSP state machines:
 *   ASSIGN:   GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED -> (cleared)
 *   UNASSIGN: CLOSE -> CONFIRM_CLOSED -> (cleared)
 *   MOVE:     CLOSE -> CONFIRM_CLOSED -> GET_ASSIGN_CANDIDATE -> OPEN
 *                -> CONFIRM_OPENED -> (cleared)
 *   REOPEN:   CLOSE -> CONFIRM_CLOSED -> GET_ASSIGN_CANDIDATE -> OPEN
 *                -> CONFIRM_OPENED -> (cleared)
 * MOVE closes on the current server and opens on a different server.
 * REOPEN is identical in flow but prefers the same server (assignCandidate
 * pinned to current location) and does not require another ONLINE server.
 * If a region becomes ABNORMALLY_CLOSED (server crash), the procedure
 * converts to ASSIGN/GET_ASSIGN_CANDIDATE via the serverCrashed()
 * callback, modeling the TRSP's self-recovery path.
 *
 * Two RPC channels model the asynchronous communication:
 *   dispatchedOps:  master->RS command channel (per server)
 *   pendingReports: RS->master report channel
 * Commands and reports are matched by region (not by procedure ID).
 * Since at most one procedure can be active per region, region identity
 * provides the same discrimination as a procedure ID.
 *
 * The implementation processes reports synchronously: reportRegionStateTransition
 * is an RPC; each report is handled immediately when it arrives. There is no
 * pendingReports queue. The spec's pendingReports set models the possibility
 * of reports arriving and being processed in arbitrary order (e.g., OPENED
 * and FAILED_OPEN both in flight). The implementation's equivalent of
 * "dropping" a report is rejecting it in the handler (e.g., when
 * regionNode.getState() = ABNORMALLY_CLOSED).
 *
 * The implementation's TRSP executes as a state machine: GET_ASSIGN_CANDIDATE
 * -> OPEN -> CONFIRM_OPENED. At CONFIRM_OPENED, the procedure suspends until
 * a report arrives (OpenRegionProcedure/CloseRegionProcedure wake it via
 * reportTransition). The procedure does not advance to the next step until
 * the report is processed. Thus TRSPDispatchOpen cannot run "before"
 * TRSPHandleFailedOpen for the same retry cycle: we must receive FAILED_OPEN
 * (and process it) before we can transition to GET_ASSIGN_CANDIDATE and
 * eventually dispatch a new OPEN. The spec allows arbitrary interleaving of
 * actions across regions and between TRSP/SCP/RS, which can expose races
 * (e.g., TRSPDispatchOpen retry-to-same-server before TRSPHandleFailedOpen)
 * that the implementation's procedure serialization may prevent. The spec's
 * mitigations (clear OPEN in TRSPHandleFailedOpen, prefer OPENED) are
 * conservative guards for these model-exposed races.
 *
 * RS-side receive and complete steps are merged into single atomic
 * actions because the intermediate RS state (command consumed but not
 * yet reported) is not observable by the master and produces the same
 * crash-recovery outcome as the pre-receive state.
 *
 * Server liveness is tracked by serverState.  RS crash is decomposed
 * into a multi-step ServerCrashProcedure (SCP):
 *   1. MasterDetectCrash(s): master marks server CRASHED, starts SCP.
 *      Non-deterministically decides if the server was hosting meta.
 *   1a. SCPAssignMeta(s): if carryingMeta, reassign meta first.
 *   2. SCPGetRegions(s): snapshot regions on the crashed server.
 *   3. SCPFenceWALs(s): revoke WAL leases (prevents zombie writes).
 *   4. SCPAssignRegion(s, r): process regions one at a time, creating
 *      ASSIGN procedures or invoking TRSPServerCrashed on existing ones.
 *   5. SCPDone(s): all regions processed.
 * Separately, RSAbort(s) models the zombie RS discovering it is dead
 * and clearing its RS-side state (rsOnlineRegions, dispatchedOps).
 * RSAbort may fire at any time after MasterDetectCrash.
 *
 * RS-side actions and report consumption are guarded by server liveness.
 * Reports from crashed servers are dropped by DropStaleReport.
 * ServerRestart(s) models a process supervisor (Kubernetes, systemd)
 * restarting a crashed server after SCP completes; WF on ServerRestart
 * ensures crashed servers eventually come back online.
 *
 * When a region fails to open on an RS, the FAILED_OPEN report is
 * consumed by TRSPHandleFailedOpen, which increments the retry counter
 * and resets the procedure to GET_ASSIGN_CANDIDATE for retry on a
 * different server.  If the retry counter reaches MaxRetries,
 * TRSPGiveUpOpen transitions the region to FAILED_OPEN (persistent)
 * and clears the procedure.
 *
 * The state space is naturally finite with region-keyed procedures:
 * there are finitely many combinations of region state, procedure
 * configuration, channel contents, and server state.  When a region
 * completes a full lifecycle, the system returns to an already-visited
 * configuration.  No StateConstraint on a procedure counter is needed.
 *
 * Currently scoped to the core assign/unassign lifecycle:
 *   OFFLINE, OPENING, OPEN, CLOSING, CLOSED, FAILED_OPEN, ABNORMALLY_CLOSED
 *
 * Split/merge states (SPLITTING, SPLIT, MERGING, MERGED, SPLITTING_NEW,
 * MERGING_NEW) are deferred to a later phase.
 *
 * FAILED_CLOSE is omitted because no code path transitions into it. The RS
 * abort triggers crash detection, so close failures are resolved through
 * ABNORMALLY_CLOSED instead.
 *
 * UseLocationCheck controls whether SCPAssignRegion applies the
 * isMatchingRegionLocation() check (SCP.java L529-538).  When TRUE,
 * SCPAssignRegion skips regions whose location changed between
 * SCPGetRegions and SCPAssignRegion — matching the implementation.
 * When FALSE, every region in the SCP snapshot is processed
 * unconditionally — the correct protocol behavior.  The check is
 * a known source of bugs (HBASE-24293, HBASE-21623).
 *)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS Regions,    \* The finite set of region identifiers
         Servers
\* The finite set of regionserver identifiers
ASSUME Regions # {}
ASSUME Servers # {}

CONSTANTS None
\* Sentinel model value for "no server assigned"
ASSUME None \notin Servers

CONSTANTS MaxRetries
\* Maximum open-retry attempts before giving up (FAILED_OPEN)
ASSUME MaxRetries \in Nat /\ MaxRetries >= 0

CONSTANTS UseLocationCheck
\* BOOLEAN: when TRUE, SCPAssignRegion applies the
\* isMatchingRegionLocation() check — regions whose master-side
\* location has changed since SCPGetRegions are skipped.
\* When FALSE, every region in the SCP snapshot is processed
\* unconditionally (correct protocol behavior).
\*
\* The implementation's check is a known source of bugs:
\* HBASE-24293, HBASE-21623.  Setting FALSE models the ideal
\* protocol; setting TRUE reproduces the implementation's behavior
\* and may expose NoLostRegions violations confirming those bugs.
\*
\* Source: ServerCrashProcedure.isMatchingRegionLocation()
\*         SCP.java L498-500; called at SCP.java L529.
ASSUME UseLocationCheck \in BOOLEAN

---------------------------------------------------------------------------

(* State definitions *)
\* Core assignment lifecycle states.
\* Mirrors RegionState.State for the assign/unassign/move path.
State ==
  { "OFFLINE",
    "OPENING",
    "OPEN",
    "CLOSING",
    "CLOSED",
    "FAILED_OPEN",
    "ABNORMALLY_CLOSED"
  }

\* The set of valid (from, to) state transitions.
\* Derived from AssignmentManager.java expected-state arrays and
\* the actual transitionState() / setState() call sites.
\*
\* Source references:
\*   OFFLINE  -> OPENING : regionOpening()          (AM.java:2211-2215)
\*   OPENING  -> OPEN    : regionOpenedWithout...()  (AM.java:2285, STATES_EXPECTED_ON_OPEN)
\*   OPENING  -> FAILED_OPEN : regionFailedOpen()    (AM.java:2245)
\*   OPEN     -> CLOSING : regionClosing()           (AM.java:2264, STATES_EXPECTED_ON_CLOSING)
\*   CLOSING  -> CLOSED  : regionClosedWithout...()  (AM.java:2295, STATES_EXPECTED_ON_CLOSED)
\*   CLOSED   -> OPENING : regionOpening() via assign (AM.java:2211; STATES_EXPECTED_ON_ASSIGN)
\*   CLOSED   -> OFFLINE : RegionStateNode.offline() (RSN.java:132-134)
\*   OPEN     -> ABNORMALLY_CLOSED : crashed()       (AM.java:2323)
\*   OPENING  -> ABNORMALLY_CLOSED : crashed()       (AM.java:2323, server crash during open)
\*   CLOSING  -> ABNORMALLY_CLOSED : crashed()       (AM.java:2323, server crash during close)
\*   ABNORMALLY_CLOSED -> OPENING : regionOpening()  (AM.java:2211-2214, no expected states)
\*   FAILED_OPEN -> OPENING : regionOpening()        (AM.java:2211-2214, no expected states)
\*
\* The regionOpening() transition accepts ANY prior state (no expectedStates
\* parameter) because SCP may need to reassign a region from any state after
\* a crash. In the model we restrict this to the states that legitimately
\* precede an assignment: OFFLINE, CLOSED, ABNORMALLY_CLOSED, FAILED_OPEN.
ValidTransition ==
  { << "OFFLINE", "OPENING" >>,
    << "OPENING", "OPEN" >>,
    << "OPENING", "FAILED_OPEN" >>,
    << "OPEN", "CLOSING" >>,
    << "CLOSING", "CLOSED" >>,
    << "CLOSED", "OPENING" >>,
    << "CLOSED", "OFFLINE" >>,
    << "OPEN", "ABNORMALLY_CLOSED" >>,
    << "OPENING", "ABNORMALLY_CLOSED" >>,
    << "CLOSING", "ABNORMALLY_CLOSED" >>,
    << "ABNORMALLY_CLOSED", "OPENING" >>,
    << "FAILED_OPEN", "OPENING" >>
  }

\* TRSP internal states used in the procStep field of regionState.
\* ASSIGN path:   GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED -> (cleared)
\* UNASSIGN path: CLOSE -> CONFIRM_CLOSED -> (cleared)
\* MOVE path:     CLOSE -> CONFIRM_CLOSED -> GET_ASSIGN_CANDIDATE -> OPEN
\*                   -> CONFIRM_OPENED -> (cleared)
TRSPState ==
  { "GET_ASSIGN_CANDIDATE",
    "OPEN",
    "CONFIRM_OPENED",
    "CLOSE",
    "CONFIRM_CLOSED"
  }

\* Procedure types.  "NONE" means no procedure is attached.
\* REOPEN: close on current server then reopen preferring the same server
\* (assignCandidate pinning); no other ONLINE server required.
ProcType == { "ASSIGN", "UNASSIGN", "MOVE", "REOPEN", "NONE" }

\* RPC command types dispatched from master to RegionServer.
\* Source: RSProcedureDispatcher dispatches OpenRegionProcedure /
\*         CloseRegionProcedure via executeProcedures() RPC.
CommandType == { "OPEN", "CLOSE" }

\* Transition codes reported from RegionServer back to master.
\* Source: RegionServerStatusService.reportRegionStateTransition()
\*         with TransitionCode enum values.
ReportCode == { "OPENED", "FAILED_OPEN", "CLOSED" }

---------------------------------------------------------------------------

(* Variables *)
VARIABLE regionState

\* regionState[r] is a record
\*   [state        : State,
\*    location     : Servers \cup {None},
\*    procType     : ProcType,
\*    procStep     : TRSPState \cup {"IDLE"},
\*    targetServer : Servers \cup {None},
\*    retries      : 0..MaxRetries]
\* for each r in Regions.  This is volatile master in-memory state.
\*
\* The procedure fields (procType, procStep, targetServer, retries)
\* model the RegionStateNode.setProcedure() / unsetProcedure()
\* discipline: at most one procedure per region.  When procType is
\* "NONE", the region has no attached procedure and procStep is
\* "IDLE".  Procedure state is inlined (keyed by region) rather
\* than indexed by a global procedure ID counter, because at most
\* one procedure can be attached per region and region identity is
\* sufficient for report/command matching.
\*
\* Source: RegionStateNode.setProcedure(),
\*         RegionStateNode.unsetProcedure().
VARIABLE metaTable

\* metaTable[r] is a record [state : State, location : Servers \cup {None}]
\* for each r in Regions.  This is persistent state in hbase:meta.
\* Survives master crash; regionState does not.
\*
\* Procedure fields are NOT persisted to meta -- procedures are
\* master in-memory state, recovered from ProcedureStore on restart.
VARIABLE dispatchedOps

\* dispatchedOps[s] is a set of command records pending delivery to
\* server s.  Each record is:
\*   [type : CommandType, region : Regions]
\*
\* Models the master->RS command channel: the RSProcedureDispatcher
\* batches open/close commands and sends them via executeProcedures()
\* RPC.  Commands remain in the set until consumed by RS-side actions
\* or discarded on dispatch failure / server crash.
\*
\* Source: RSProcedureDispatcher.remoteDispatch(),
\*         RSProcedureDispatcher.ExecuteProceduresRemoteCall.run().
VARIABLE pendingReports

\* pendingReports is a set of report records from RegionServers
\* waiting to be processed by the master.  Each record is:
\*   [server : Servers, region : Regions, code : ReportCode]
\*
\* Models the RS->master report channel: RegionServers report
\* transition outcomes via reportRegionStateTransition() RPC.
\* Reports remain in the set until consumed by master-side actions
\* or discarded if from a crashed server.
\*
\* Source: RegionServerStatusService.reportRegionStateTransition().
VARIABLE rsOnlineRegions

\* rsOnlineRegions[s] is the set of regions currently online on
\* server s, from the RS's own perspective.  Updated atomically
\* by RSOpen (adds region) and RSClose (removes region).
\*
\* Source: HRegionServer.getRegions().
VARIABLE serverState

\* serverState[s] is the liveness state of server s as seen by the
\* master.  "ONLINE" means the server is alive and accepting commands;
\* "CRASHED" means the master has detected the server's death (ZK
\* ephemeral node expired).  Reports from CRASHED servers are rejected.
\* RS-side actions are guarded by serverState = "ONLINE".
\*
\* Source: ServerManager.ServerStateNode, ServerState.java.
\* scpState[s] tracks the ServerCrashProcedure progress for server s.
\* "NONE" means no SCP is active for this server.
\* GET_REGIONS -> FENCE_WALS -> ASSIGN -> DONE is the SCP lifecycle.
\*
\* Source: ServerCrashProcedure.executeFromState().
VARIABLE scpState

\* scpRegions[s] is the snapshot of regions taken at GET_REGIONS time.
\*
\* Source: ServerCrashProcedure.getRegionsOnCrashedServer(),
\*         AssignmentManager.getRegionsOnServer().
VARIABLE scpRegions

\* walFenced[s] is TRUE after SCP revokes WAL leases for server s.
\* Reset to FALSE on ServerRestart.  After fencing, the zombie RS
\* cannot write to its WALs; any write attempt fails with an HDFS
\* lease exception, triggering RS self-abort.
\*
\* Source: ServerCrashProcedure.executeFromState() SERVER_CRASH_SPLIT_LOGS
\*         case; MasterWalManager.splitLogs().
VARIABLE walFenced

\* locked[r] is TRUE while a region-state-mutating action holds the
\* per-region write lock for region r.  Mirrors the RegionStateNode
\* write lock acquired by TRSP.beforeExec() / TRSP.afterExec() and
\* by SCP.assignRegions().  In TLA+ each action is atomic so locked
\* is acquired and released within the same step (locked'[r]=FALSE
\* after the step).  The guard locked[r]=FALSE enforces mutual
\* exclusion between concurrent actions on the same region.
\*
\* Source: TRSP.beforeExec()/afterExec() TRSP.java L392-410;
\*         SCP.assignRegions() regionNode.lock() SCP.java L518-559.
VARIABLE locked

\* carryingMeta[s] is TRUE when server s was hosting hbase:meta at
\* the time it crashed.  Set non-deterministically by MasterDetectCrash
\* (one case per invocation: either TRUE or FALSE).  When TRUE, the
\* SCP must reassign meta before proceeding to GET_REGIONS; this is
\* modeled by the ASSIGN_META scpState.  All other SCP actions for
\* ANY server are gated on meta being available (no server in
\* ASSIGN_META state), faithfully modeling waitMetaLoaded.
\*
\* Source: SCP.executeFromState() SERVER_CRASH_START case;
\*         SCP.isCarryingMeta() SCP.java L161-195;
\*         SCP.waitMetaLoaded() SCP.java L154-157.
VARIABLE carryingMeta

vars ==
  << regionState,
     metaTable,
     dispatchedOps,
     pendingReports,
     rsOnlineRegions,
     serverState,
     scpState,
     scpRegions,
     walFenced,
     locked,
     carryingMeta
  >>

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == << dispatchedOps, pendingReports >>

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == << rsOnlineRegions >>

\* Shorthand for the SCP-related variables (used in UNCHANGED clauses).
scpVars == << scpState, scpRegions, walFenced, carryingMeta >>

---------------------------------------------------------------------------

(* Type invariant *)
TypeOK ==
  \* For every region, check all fields of its in-memory state record.
  /\ \A r \in Regions:
       \* Region lifecycle state must be one of the defined states.
       /\ regionState[r].state \in State
       \* Region location is either an assigned server or None.
       /\ regionState[r].location \in Servers \cup { None }
       \* Embedded procedure type must be a valid ProcType.
       /\ regionState[r].procType \in ProcType
       \* Procedure step is a TRSP state or IDLE (when no procedure).
       /\ regionState[r].procStep \in TRSPState \cup { "IDLE" }
       \* Procedure's target server is a server or None.
       /\ regionState[r].targetServer \in Servers \cup { None }
       \* Retry counter is bounded by MaxRetries.
       /\ regionState[r].retries \in 0 .. MaxRetries
       \* Idle procedure fields are consistent.
       /\ regionState[r].procType = "NONE" =>
            /\ regionState[r].procStep = "IDLE"
            /\ regionState[r].targetServer = None
            /\ regionState[r].retries = 0
       \* Active procedure has a valid step.
       /\ regionState[r].procType # "NONE" =>
            regionState[r].procStep \in TRSPState
  \* META table maps every region to a persistent (state, location) record.
  /\ metaTable \in [Regions -> [state:State, location:Servers \cup { None } ]]
  \* Each server has a set of dispatched operation commands (open/close).
  /\ dispatchedOps \in [Servers -> SUBSET [type:CommandType, region:Regions ]]
  \* Pending reports are a set of region-transition outcome messages.
  /\ pendingReports \subseteq [server:Servers, region:Regions, code:ReportCode ]
  \* Each server tracks its set of locally online regions.
  /\ rsOnlineRegions \in [Servers -> SUBSET Regions]
  \* Each server is either ONLINE or CRASHED.
  /\ serverState \in [Servers -> { "ONLINE", "CRASHED" }]
  \* SCP progress per server.
  /\ scpState \in
       [Servers
       ->
       { "NONE", "ASSIGN_META", "GET_REGIONS", "FENCE_WALS", "ASSIGN", "DONE" }]
  \* SCP region snapshot per server.
  /\ scpRegions \in [Servers -> SUBSET Regions]
  \* WAL fencing state per server.
  /\ walFenced \in [Servers -> BOOLEAN]
  \* Whether crashed server was hosting hbase:meta.
  /\ carryingMeta \in [Servers -> BOOLEAN]
  \* Per-region write lock for mutual exclusion.
  /\ locked \in [Regions -> BOOLEAN]

---------------------------------------------------------------------------

(* Safety invariants *)
\* A region that is OPEN must have a location.
OpenImpliesLocation ==
  \A r \in Regions:
    regionState[r].state = "OPEN" => regionState[r].location # None

\* A region that is OFFLINE, CLOSED, FAILED_OPEN, or ABNORMALLY_CLOSED
\* must NOT have a location.
OfflineImpliesNoLocation ==
  \A r \in Regions:
    regionState[r].state \in
        { "OFFLINE", "CLOSED", "FAILED_OPEN", "ABNORMALLY_CLOSED" } =>
      regionState[r].location = None

\* The persistent state in hbase:meta matches the in-memory state for
\* the fields that meta tracks (state and location).  Procedure fields
\* are master in-memory only and are not compared.
\*
\* One permitted divergence: GoOffline sets regionState to OFFLINE
\* without writing to meta, so metaTable may retain CLOSED while
\* regionState shows OFFLINE.  Both are "unassigned" states with no
\* location; the divergence is resolved on master restart when
\* in-memory state is rebuilt from meta.  See Appendix D.5.
\*
\* Becomes further non-trivial when master crash is introduced:
\* metaTable survives but regionState is lost and must be rebuilt.
MetaConsistency ==
  \A r \in Regions: \/ /\ metaTable[r].state = regionState[r].state
                       /\ metaTable[r].location = regionState[r].location
                    \/ /\ regionState[r].state = "OFFLINE"
                       /\ metaTable[r].state = "CLOSED"
                       /\ regionState[r].location = None
                       /\ metaTable[r].location = None

\* NoDoubleAssignment: HBase term of art for "at most one server per
\* region."  The precise semantics here are "no double write": a region
\* is never writable on two servers simultaneously.  A region is
\* writable on server s when r \in rsOnlineRegions[s] /\ walFenced[s] =
\* FALSE.  After SCP revokes a crashed RS's WAL leases (walFenced[s] =
\* TRUE), the zombie RS can no longer write, preventing write-side
\* split-brain.  During the zombie window (MasterDetectCrash before
\* RSAbort), rsOnlineRegions may overlap; walFenced disqualifies the
\* zombie from the writable set.
\*
\* Source: HBase region assignment design; HDFS lease recovery on
\*         crashed RS's WALs.
NoDoubleAssignment ==
  \A r \in Regions:
    Cardinality({s \in Servers:
          r \in rsOnlineRegions[s] /\ walFenced[s] = FALSE
        }) <=
      1

\* Correlates procedure type with the set of region states in which that
\* procedure may be attached.  Each TRSP type attaches before its first
\* state transition (setProcedure precedes regionOpening/regionClosing),
\* so the procedure may be present in the pre-transitional state.
\*
\* ASSIGN attaches during {OFFLINE, CLOSED, ABNORMALLY_CLOSED, FAILED_OPEN}
\*   and the region transitions through OPENING before the procedure is
\*   cleared at OPEN.
\* UNASSIGN attaches during OPEN and the region transitions through CLOSING
\*   before the procedure is cleared at CLOSED.
\* MOVE attaches during OPEN and drives the region through CLOSING, CLOSED,
\*   OPENING before being cleared at OPEN.
\* Any type may be found on an ABNORMALLY_CLOSED region if a server
\*   crash occurs while the procedure is in flight.
\*
\* Source: RegionStateNode.setProcedure(),
\*         RegionStateNode.unsetProcedure().
LockExclusivity ==
  \A r \in Regions:
    regionState[r].procType # "NONE" =>
      \/ /\ regionState[r].procType = "ASSIGN"
         /\ regionState[r].state \in
              { "OFFLINE",
                "CLOSED",
                "ABNORMALLY_CLOSED",
                "FAILED_OPEN",
                "OPENING"
              }
      \/ /\ regionState[r].procType = "UNASSIGN"
         /\ regionState[r].state \in { "OPEN", "CLOSING", "ABNORMALLY_CLOSED" }
      \/ /\ regionState[r].procType = "MOVE"
         /\ regionState[r].state \in
              { "OPEN",
                "CLOSING",
                "CLOSED",
                "ABNORMALLY_CLOSED",
                "FAILED_OPEN",
                "OPENING"
              }
      \/ /\ regionState[r].procType = "REOPEN"
         /\ regionState[r].state \in
              { "OPEN",
                "CLOSING",
                "CLOSED",
                "ABNORMALLY_CLOSED",
                "FAILED_OPEN",
                "OPENING"
              }

\* When a region is stably OPEN (no procedure attached) on an ONLINE
\* server, the RS hosting it must also consider it online.  This
\* cross-checks the master's view (regionState) against the RS's view
\* (rsOnlineRegions).
\*
\* The invariant holds because TRSPConfirmOpened (which sets OPEN and
\* clears the procedure) requires an OPENED report, which is only
\* produced by RSOpen after adding the region to rsOnlineRegions.
\*
\* CRASHED servers are exempted: during the zombie window (between
\* MasterDetectCrash and SCPAssignRegion), regionState may still show
\* OPEN while rsOnlineRegions has been cleared by RSAbort.
RSMasterAgreement ==
  \A r \in Regions:
    ( regionState[r].state = "OPEN" /\ regionState[r].procType = "NONE" /\
            regionState[r].location # None /\
          serverState[regionState[r].location] = "ONLINE"
      ) =>
      r \in rsOnlineRegions[regionState[r].location]

\* Converse of RSMasterAgreement: if an RS considers a region online,
\* the master must agree on both location and lifecycle state.  Catches
\* "ghost regions" where an RS believes it hosts a region the master
\* has already reassigned or crashed.
\*
\* OPENING is included because RSOpen adds the region to
\* rsOnlineRegions before TRSPConfirmOpened transitions the master
\* state from OPENING to OPEN.
\*
\* CRASHED servers are exempted: during the zombie window (between
\* MasterDetectCrash and RSAbort), the master has cleared the crashed
\* RS's location but rsOnlineRegions has not yet been wiped.  The
\* master no longer considers the crashed server authoritative.
RSMasterAgreementConverse ==
  \A s \in Servers:
    \A r \in rsOnlineRegions[s]:
      serverState[s] = "CRASHED" \/
        /\ regionState[r].location = s
        /\ regionState[r].state \in { "OPENING", "OPEN", "CLOSING" }

\* SCP does not reassign regions (scpState = "ASSIGN") until WAL
\* leases have been revoked (walFenced = TRUE).  Verified by
\* construction (SCP state machine: FENCE_WALS precedes ASSIGN)
\* but stated explicitly as a safety net.
ZombieFencingOrder ==
  \A s \in Servers: scpState[s] = "ASSIGN" => walFenced[s] = TRUE

\* A meta-carrying SCP must reassign meta (complete ASSIGN_META)
\* before proceeding to GET_REGIONS.  If carryingMeta[s] is TRUE,
\* the SCP should not have progressed past ASSIGN_META.  The
\* meta-online guard on SCPGetRegions/SCPFenceWALs/SCPAssignRegion
\* prevents any SCP from executing while ASSIGN_META is pending,
\* but MasterDetectCrash may set GET_REGIONS for non-meta crashes;
\* the key safety property is that the meta-carrying server itself
\* cannot skip ASSIGN_META.
\*
\* Source: SCP.waitMetaLoaded() SCP.java L154-157;
\*         SCP.executeFromState() ASSIGN_META case SCP.java L161-195.
MetaAvailableForRecovery ==
  \A s \in Servers:
    carryingMeta[s] = TRUE => scpState[s] \in { "NONE", "ASSIGN_META", "DONE" }

\* After any SCP completes, no region is stuck in ABNORMALLY_CLOSED
\* without a procedure.  Such a region is "lost" -- it will never be
\* reassigned without manual intervention.  We scope to ABNORMALLY_CLOSED
\* only: OFFLINE, CLOSED, FAILED_OPEN with no procedure are legitimate
\* quiescent states (never assigned, explicitly taken offline, or
\* failed to open); the lost-region bug is specifically ABNORMALLY_CLOSED
\* with no procedure.
NoLostRegions ==
  ( \E s \in Servers: scpState[s] = "DONE" ) =>
    \A r \in Regions:
      regionState[r].state = "ABNORMALLY_CLOSED" =>
        regionState[r].procType # "NONE"

---------------------------------------------------------------------------

(* State constraints for TLC *)
\* Limit the number of simultaneously crashed servers.  No longer
\* required in configs now that ServerRestart + WF ensures crashed
\* servers eventually come back online (preventing the all-crashed
\* deadlock).  Retained as a definition for ad-hoc bounded runs.
CrashConstraint == Cardinality({s \in Servers: serverState[s] = "CRASHED"}) <= 1

\* Symmetry reduction: regions and servers are interchangeable.
\* TLC explores one representative per equivalence class, reducing
\* the state space by up to |Regions|! * |Servers|! (36x for 3r/3s).
Symmetry == Permutations(Regions) \union Permutations(Servers)

---------------------------------------------------------------------------

(* Initial state *)
Init ==
  \* Every region starts OFFLINE with no server and no procedure.
  /\ regionState =
       [r \in Regions |->
         [ state |-> "OFFLINE",
           location |-> None,
           procType |-> "NONE",
           procStep |-> "IDLE",
           targetServer |-> None,
           retries |-> 0
         ]
       ]
  \* META table mirrors the initial in-memory state: all regions OFFLINE.
  /\ metaTable = [r \in Regions |-> [ state |-> "OFFLINE", location |-> None ]]
  \* No operation commands have been dispatched to any server.
  /\ dispatchedOps = [s \in Servers |-> {}]
  \* No region-transition reports are pending.
  /\ pendingReports = {}
  \* No regions are online on any RS at startup.
  /\ rsOnlineRegions = [s \in Servers |-> {}]
  \* All servers are initially ONLINE.
  /\ serverState = [s \in Servers |-> "ONLINE"]
  \* No SCP is active for any server.
  /\ scpState = [s \in Servers |-> "NONE"]
  \* No SCP region snapshots.
  /\ scpRegions = [s \in Servers |-> {}]
  \* No server has been WAL-fenced.
  /\ walFenced = [s \in Servers |-> FALSE]
  /\ carryingMeta = [s \in Servers |-> FALSE]
  /\ locked = [r \in Regions |-> FALSE]

---------------------------------------------------------------------------

(* Actions -- TRSP ASSIGN path *)
\* Create a TRSP ASSIGN procedure for a region eligible for assignment.
\* Pre: region is in a state eligible for assignment AND no procedure
\*      is currently attached.
\* Post: procedure fields set to ASSIGN/GET_ASSIGN_CANDIDATE.  Region
\*       lifecycle state is NOT changed yet -- the TRSP will drive
\*       transitions in subsequent steps.
\*
\* Source: TRSP.assign() creates the procedure;
\*         TRSP.queueAssign() applies the GET_ASSIGN_CANDIDATE
\*         initial state via TRSP.setInitialAndLastState().
TRSPCreate(r) ==
  \* Guards: region is in an assignable state and has no active procedure.
  /\ regionState[r].state \in
       { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED", "FAILED_OPEN" }
  /\ regionState[r].procType = "NONE"
  \* Initialize embedded ASSIGN procedure at GET_ASSIGN_CANDIDATE step.
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "ASSIGN",
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       None,
       ![r].retries =
       0]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable, rpcVars, rsVars, serverState, scpVars, locked >>

\* Choose a target server for the ASSIGN, MOVE, or REOPEN procedure.
\* Pre: TRSP is in GET_ASSIGN_CANDIDATE state, chosen server is ONLINE,
\*      and no CRASHED server still has r in its rsOnlineRegions (the
\*      zombie window must be closed: RSAbort clears the zombie RS's
\*      in-memory region set, after which a new assignment is safe).
\* Post: targetServer set, TRSP advances to OPEN state.
\*
\* The zombie-window guard (\A zombie: CRASHED => r \notin rsOnlineRegions)
\* is implementation-faithful: the implementation relies on
\* createDestinationServersList() to exclude CRASHED servers from
\* candidates and on RSAbort to close the zombie window.  Once RSAbort
\* fires, rsOnlineRegions[zombie] no longer contains r and
\* TRSPGetCandidate may safely pick a new server.
\*
\* Note: ZombieFencingOrder (scpState[s] = "ASSIGN" => walFenced[s])
\* and the walFenced guard on SCPAssignRegion remain unchanged --
\* those faithfully model SCP's own ordering constraint.
\*
\* Source: TRSP.queueAssign() -> AM.queueAssign() -> LoadBalancer;
\*         createDestinationServersList() excludes CRASHED servers;
\*         HRegionServer.abort() (RSAbort) clears rsOnlineRegions.
TRSPGetCandidate(r, s) ==
  \* Guards: procedure is ASSIGN, MOVE, or REOPEN at GET_ASSIGN_CANDIDATE;
  \* chosen server is ONLINE; no CRASHED server still holds r in
  \* rsOnlineRegions (zombie window must be closed by RSAbort first).
  \* For REOPEN, s may equal regionState[r].location (same server OK).
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "GET_ASSIGN_CANDIDATE"
  /\ serverState[s] = "ONLINE"
  /\ \A sZombie \in Servers:
       serverState[sZombie] = "CRASHED" => r \notin rsOnlineRegions[sZombie]
  \* Record the chosen server and advance the procedure to the OPEN step.
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT ![r].targetServer = s, ![r].procStep = "OPEN"]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable, rpcVars, rsVars, serverState, scpVars, locked >>

\* Dispatch the open command to the target server via RPC.
\* Pre: TRSP is in OPEN state with a target server.
\* Post: region transitions to OPENING with the target server as
\*       location, meta updated, open command added to
\*       dispatchedOps[targetServer], TRSP advances to CONFIRM_OPENED.
\*
\* Source: TRSP.openRegion() calls AM.regionOpening()
\*         to set OPENING state and meta, then creates an
\*         OpenRegionProcedure child; RSProcedureDispatcher
\*         dispatches the RPC via
\*         RSProcedureDispatcher.ExecuteProceduresRemoteCall.run().
TRSPDispatchOpen(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in OPEN step, with a target server.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "OPEN"
  /\ regionState[r].targetServer # None
  \* Bind the target server for readability.
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN \* Transition region to OPENING, record server, advance to CONFIRM_OPENED.
        /\ regionState' =
             [regionState EXCEPT
             ![r].state =
             "OPENING",
             ![r].location =
             s,
             ![r].procStep =
             "CONFIRM_OPENED"]
        \* Persist the OPENING state and server assignment in META.
        /\ metaTable' =
             [metaTable EXCEPT ![r] = [ state |-> "OPENING", location |-> s ]]
        \* Enqueue an OPEN command to the target server's dispatched ops.
        /\ dispatchedOps' =
             [dispatchedOps EXCEPT
             ![s] =
             @ \cup { [ type |-> "OPEN", region |-> r ] }]
        \* Pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << pendingReports, rsVars, serverState, scpVars, locked >>

\* Confirm that the region opened successfully by consuming an OPENED
\* report from pendingReports.
\* Pre: TRSP is in CONFIRM_OPENED state, region is OPENING, and a
\*      matching OPENED report exists in pendingReports from an ONLINE
\*      server.
\* Post: region transitions to OPEN, procedure cleared, report consumed.
\*
\* At CONFIRM_OPENED the procedure suspends until a report
\* arrives; reportTransition() wakes it. The procedure does not advance
\* until the report is processed, so OPENED/FAILED_OPEN ordering is
\* serialized per region in the implementation.
\*
\* Source: TRSP.confirmOpened() on the OPEN branch;
\*         TRSP.reportTransition() wakes the procedure;
\*         AM.regionOpenedWithoutProcedure() / AM.regionOpened()
\*         performs the OPENING -> OPEN state transition and meta
\*         update.
TRSPConfirmOpened(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, region is
  \* OPENING, and a matching OPENED report exists from an ONLINE server.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ regionState[r].state = "OPENING"
  /\ locked[r] = FALSE
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code = "OPENED"
       /\ serverState[rpt.server] = "ONLINE"
       \* Finalize region as OPEN, keep location, clear procedure fields.
       /\ regionState' =
            [regionState EXCEPT
            ![r] =
            [ state |-> "OPEN",
              location |-> regionState[r].location,
              procType |-> "NONE",
              procStep |-> "IDLE",
              targetServer |-> None,
              retries |-> 0
            ]]
       \* Persist the OPEN state in META, preserving the location.
       /\ metaTable' =
            [metaTable EXCEPT
            ![r] =
            [ state |-> "OPEN", location |-> metaTable[r].location ]]
       \* Consume the matched report from the pending set.
       /\ pendingReports' = pendingReports \ { rpt }
       \* Dispatched ops, RS-side state, and server liveness unchanged.
       /\ UNCHANGED << dispatchedOps, rsVars, serverState, scpVars, locked >>

---------------------------------------------------------------------------

(* Actions -- failure path *)
\* Open command dispatch failed (non-deterministic RPC failure).
\* The command is removed from dispatchedOps without delivery and
\* the TRSP returns to GET_ASSIGN_CANDIDATE with forceNewPlan (i.e.,
\* targetServer is cleared so a fresh candidate will be chosen).
\* The region remains OPENING with its current location; the next
\* TRSPDispatchOpen will update the location to the new server.
\*
\* Pre: ASSIGN/MOVE procedure in CONFIRM_OPENED state and the matching
\*      open command still exists in dispatchedOps (not yet consumed
\*      by an RS).
\* Post: command removed, TRSP reset to GET_ASSIGN_CANDIDATE.
\*
\* Source: RegionRemoteProcedureBase.remoteCallFailed() sets
\*         DISPATCH_FAIL state and wakes the parent TRSP;
\*         RSProcedureDispatcher.scheduleForRetry() decides
\*         whether to retry or fail the remote call.
DispatchFail(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, has a
  \* target server, and the OPEN command is still in dispatchedOps.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ s # None
        \* Reconstruct the dispatched command and verify it is still queued.
        /\ LET cmd == [ type |-> "OPEN", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              \* Remove the undelivered command from the server's queue.
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
        \* Reset the procedure to GET_ASSIGN_CANDIDATE with no target server.
        /\ regionState' =
             [regionState EXCEPT
             ![r].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r].targetServer =
             None]
        \* META, pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << metaTable,
              pendingReports,
              rsVars,
              serverState,
              scpVars,
              locked
           >>

\* Close command dispatch failed (non-deterministic RPC failure).
\* The command is removed from dispatchedOps without delivery and
\* the TRSP returns to CLOSE to retry the dispatch.  Unlike the open
\* path (DispatchFail), the targetServer is NOT cleared because the
\* close must still target the server hosting the region.
\*
\* Pre: UNASSIGN or MOVE procedure in CONFIRM_CLOSED state and the
\*      matching close command still exists in dispatchedOps.
\* Post: command removed, TRSP reset to CLOSE.
\*
\* Source: RegionRemoteProcedureBase.remoteCallFailed() sets
\*         DISPATCH_FAIL state and wakes the parent TRSP;
\*         RSProcedureDispatcher.scheduleForRetry() decides
\*         whether to retry or fail the remote call.
DispatchFailClose(r) ==
  \* Guards: procedure is UNASSIGN or MOVE, in CONFIRM_CLOSED step, has a
  \* target server, and the CLOSE command is still in dispatchedOps.
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ s # None
        \* Reconstruct the dispatched command and verify it is still queued.
        /\ LET cmd == [ type |-> "CLOSE", region |-> r ]
           IN /\ cmd \in dispatchedOps[s]
              \* Remove the undelivered command from the server's queue.
              /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
        \* Reset the procedure to CLOSE to retry (target server kept).
        /\ regionState' = [regionState EXCEPT ![r].procStep = "CLOSE"]
        \* META, pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << metaTable,
              pendingReports,
              rsVars,
              serverState,
              scpVars,
              locked
           >>

\* Handle a FAILED_OPEN report from the RS by retrying the assignment.
\* Models the non-give-up path of confirmOpened() (TRSP L345-374):
\* the procedure resets to GET_ASSIGN_CANDIDATE with forceNewPlan,
\* allowing a fresh server to be chosen.  Region state and meta are
\* NOT changed (stays OPENING); the next TRSPDispatchOpen will
\* overwrite location and state.
\*
\* Pre: ASSIGN/MOVE procedure in CONFIRM_OPENED state, retry budget not
\*      exhausted, region is OPENING, and a matching FAILED_OPEN report
\*      exists from an ONLINE server.
\* Post: report consumed, retry counter incremented, TRSP reset to
\*       GET_ASSIGN_CANDIDATE.
\*
\* Source: TRSP.confirmOpened() retry branch (retries <
\*         maxAttempts); AM.regionFailedOpen() updates
\*         bookkeeping; forceNewPlan is set to choose a new server.
TRSPHandleFailedOpen(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, retries
  \* not exhausted, region is OPENING, and a matching FAILED_OPEN report
  \* exists from an ONLINE server.
  \* Do not handle FAILED_OPEN when OPENED exists for r: the RS may have
  \* succeeded on a retry (RSOpen ran after a prior RSFailOpen); prefer OPENED.
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ regionState[r].retries < MaxRetries
  /\ locked[r] = FALSE
  /\ regionState[r].state = "OPENING"
  /\ ~\E rpt2 \in pendingReports: rpt2.region = r /\ rpt2.code = "OPENED"
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code = "FAILED_OPEN"
       /\ serverState[rpt.server] = "ONLINE"
       \* Reset procedure to retry with a new server, increment retry count.
       /\ regionState' =
            [regionState EXCEPT
            ![r].procStep =
            "GET_ASSIGN_CANDIDATE",
            ![r].targetServer =
            None,
            ![r].retries =
            regionState[r].retries + 1]
       \* Consume the matched report from the pending set.
       /\ pendingReports' = pendingReports \ { rpt }
       \* Clear any OPEN for r from dispatchedOps.  In the spec's interleaving,
       \* TRSPDispatchOpen may have run before this (retry to same server); that
       \* OPEN is now stale since we are picking a new server.  Without this,
       \* RSOpen could consume the stale OPEN and violate RSMasterAgreementConverse.
       \* The implementation's procedure ordering (CONFIRM_OPENED suspends until
       \* report) may prevent this race; the clear is a conservative mitigation.
       /\ dispatchedOps' =
            [t \in Servers |->
              {cmd \in dispatchedOps[t]: cmd.region # r \/ cmd.type # "OPEN"}
            ]
       \* META, RS-side state, and server liveness unchanged.
       /\ UNCHANGED << metaTable, rsVars, serverState, scpVars, locked >>

\* Give up on opening a region after exhausting the retry budget.
\* Consumes the FAILED_OPEN report, transitions the region to
\* FAILED_OPEN (persistent), and clears the procedure.
\*
\* Pre: ASSIGN/MOVE procedure in CONFIRM_OPENED state, retry budget
\*      exhausted, region is OPENING, and a matching FAILED_OPEN
\*      report exists from an ONLINE server.
\* Post: region becomes FAILED_OPEN with no location or procedure,
\*       meta updated, report consumed.
\*
\* Source: TRSP.confirmOpened() give-up branch (retries >=
\*         maxAttempts); AM.regionFailedOpen(regionNode, true)
\*         persists FAILED_OPEN to meta and clears location.
TRSPGiveUpOpen(r) ==
  \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, retries
  \* exhausted, region is OPENING, and a matching FAILED_OPEN report
  \* exists from an ONLINE server.
  \* Do not give up when OPENED exists for r: prefer OPENED (RS succeeded).
  /\ regionState[r].procType \in { "ASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_OPENED"
  /\ regionState[r].retries >= MaxRetries
  /\ locked[r] = FALSE
  /\ regionState[r].state = "OPENING"
  /\ ~\E rpt2 \in pendingReports: rpt2.region = r /\ rpt2.code = "OPENED"
  /\ \E rpt \in pendingReports:
       /\ rpt.region = r
       /\ rpt.code = "FAILED_OPEN"
       /\ serverState[rpt.server] = "ONLINE"
       \* Move region to FAILED_OPEN, clear location, clear procedure fields.
       /\ regionState' =
            [regionState EXCEPT
            ![r] =
            [ state |-> "FAILED_OPEN",
              location |-> None,
              procType |-> "NONE",
              procStep |-> "IDLE",
              targetServer |-> None,
              retries |-> 0
            ]]
       \* Persist FAILED_OPEN state and cleared location in META.
       /\ metaTable' =
            [metaTable EXCEPT
            ![r] =
            [ state |-> "FAILED_OPEN", location |-> None ]]
       \* Consume the matched report from the pending set.
       /\ pendingReports' = pendingReports \ { rpt }
       \* Dispatched ops, RS-side state, and server liveness unchanged.
       /\ UNCHANGED << dispatchedOps, rsVars, serverState, scpVars, locked >>

---------------------------------------------------------------------------

(* Actions -- TRSP UNASSIGN path *)
\* Create a TRSP UNASSIGN procedure for an OPEN region.
\* Pre: region is OPEN with a location AND no procedure is attached.
\* Post: procedure fields set to UNASSIGN/CLOSE with targetServer
\*       pointing at the region's current server.  Region lifecycle
\*       state is NOT changed yet -- the TRSP will drive the
\*       OPEN -> CLOSING transition in the next step.
\*
\* Source: TRSP.unassign() creates the procedure with
\*         TransitionType.UNASSIGN; TRSP.setInitialAndLastState()
\*         sets initial state to CLOSE and last state to
\*         CONFIRM_CLOSED; RegionStateNode.setProcedure()
\*         attaches it to the region.
TRSPCreateUnassign(r) ==
  \* Guards: region is OPEN, has a location, and has no active procedure.
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # None
  /\ regionState[r].procType = "NONE"
  \* Initialize embedded UNASSIGN procedure at CLOSE step, targeting current server.
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "UNASSIGN",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable, rpcVars, rsVars, serverState, scpVars, locked >>

\* Create a TRSP MOVE procedure for an OPEN region.
\* A MOVE is an UNASSIGN (close on the current server) followed by an
\* ASSIGN (open on a new server), all within a single procedure.  The
\* procedure starts in the CLOSE phase with targetServer set to the
\* region's current location.  After the close completes, targetServer
\* is cleared and TRSPGetCandidate picks a new destination.
\*
\* Pre: region is OPEN with a location AND no procedure is attached
\*      AND at least one other ONLINE server exists as a potential
\*      destination.
\* Post: MOVE procedure created in CLOSE state, attached to region.
\*
\* Source: TRSP.move() / TRSP.reopen() creates the procedure with
\*         TransitionType.MOVE; TRSP.setInitialAndLastState()
\*         sets initial state to CLOSE and last state to
\*         CONFIRM_OPENED; RegionStateNode.setProcedure()
\*         attaches it to the region.
TRSPCreateMove(r) ==
  \* Guards: region is OPEN, has a location, has no active procedure,
  \* and at least one other ONLINE server exists as a destination.
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # None
  /\ regionState[r].procType = "NONE"
  /\ \E s \in Servers: s # regionState[r].location /\ serverState[s] = "ONLINE"
  \* Initialize embedded MOVE procedure at CLOSE step, targeting current server.
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "MOVE",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable, rpcVars, rsVars, serverState, scpVars, locked >>

\* Create a TRSP REOPEN procedure for an OPEN region.
\* REOPEN follows the same flow as MOVE (CLOSE -> CONFIRM_CLOSED ->
\* GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED) but differs in that
\* assignCandidate is pre-set to the region's current server, so
\* TRSPGetCandidate may choose the same server.  There is no requirement
\* that another ONLINE server exists (contrast with TRSPCreateMove).
\*
\* Pre: region is OPEN, has a location, and has no active procedure.
\* Post: REOPEN procedure created in CLOSE state, targetServer set
\*       to the region's current location (assignCandidate pinning).
\*
\* Source: TRSP.reopen() TRSP.java L673-676;
\*         TRSP.setInitialAndLastState() TRSP.java L154-158;
\*         TRSP.queueAssign() L238-270 (retain=true when assignCandidate set).
TRSPCreateReopen(r) ==
  \* Guards: region is OPEN, has a server location, no active procedure.
  \* No requirement that another ONLINE server exists.
  /\ regionState[r].state = "OPEN"
  /\ regionState[r].location # None
  /\ regionState[r].procType = "NONE"
  \* Initialize embedded REOPEN procedure at CLOSE step, targeting the
  \* region's current server (assignCandidate pinning).
  /\ locked[r] = FALSE
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "REOPEN",
       ![r].procStep =
       "CLOSE",
       ![r].targetServer =
       regionState[r].location,
       ![r].retries =
       0]
  \* META, RPC channels, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable, rpcVars, rsVars, serverState, scpVars, locked >>

\* Dispatch the close command to the target server via RPC.
\* Pre: UNASSIGN or MOVE procedure in CLOSE state, region is OPEN or
\*      CLOSING (CLOSING on retry after DispatchFailClose).
\* Post: region transitions to CLOSING (no-op if already CLOSING),
\*       meta updated, close command added to dispatchedOps[targetServer],
\*       TRSP advances to CONFIRM_CLOSED.
\*
\* Source: TRSP.closeRegion() calls AM.regionClosing()
\*         to set CLOSING state and meta, then creates a
\*         CloseRegionProcedure child; RSProcedureDispatcher
\*         dispatches the RPC via
\*         RSProcedureDispatcher.ExecuteProceduresRemoteCall.run().
TRSPDispatchClose(r) ==
  \* Guards: procedure is UNASSIGN or MOVE, in CLOSE step, has a target
  \* server, and region is OPEN or CLOSING (CLOSING on retry).
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CLOSE"
  /\ regionState[r].targetServer # None
  \* Bind the target server for readability.
  /\ locked[r] = FALSE
  /\ LET s == regionState[r].targetServer
     IN /\ regionState[r].state \in { "OPEN", "CLOSING" }
        \* Transition region to CLOSING and advance to CONFIRM_CLOSED.
        /\ regionState' =
             [regionState EXCEPT
             ![r].state =
             "CLOSING",
             ![r].procStep =
             "CONFIRM_CLOSED"]
        \* Persist CLOSING state in META, preserving the location.
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "CLOSING", location |-> metaTable[r].location ]]
        \* Enqueue a CLOSE command to the target server's dispatched ops.
        /\ dispatchedOps' =
             [dispatchedOps EXCEPT
             ![s] =
             @ \cup { [ type |-> "CLOSE", region |-> r ] }]
        \* Pending reports, RS-side state, and server liveness unchanged.
        /\ UNCHANGED << pendingReports, rsVars, serverState, scpVars, locked >>

\* Confirm that the region closed successfully, OR handle the case
\* where the region's server crashed (ABNORMALLY_CLOSED) during close.
\*
\* Pre: UNASSIGN or MOVE procedure in CONFIRM_CLOSED state.  Either
\*      the region is CLOSING with a matching CLOSED report from the
\*      target server (Path 1), or the region is ABNORMALLY_CLOSED
\*      (Path 2, server crash during close).
\* Post (Path 1 - normal close): region transitions to CLOSED with no
\*      location.  UNASSIGN: procedure cleared to idle.  MOVE: procedure
\*      advances to GET_ASSIGN_CANDIDATE for re-open.  Report consumed,
\*      meta updated.
\* Post (Path 2 - crash during close): procedure converts to
\*      ASSIGN/GET_ASSIGN_CANDIDATE to reopen the region (needed to
\*      process recovered edits).  Stale dispatched commands for the
\*      region are cleared.
\*
\* Two paths (disjunction):
\* Path 1 (normal close): Region is CLOSING, a CLOSED report exists.
\*   For UNASSIGN: procedure cleared.
\*   For MOVE: procedure advanced to GET_ASSIGN_CANDIDATE.
\* Path 2 (crash during close): Region is ABNORMALLY_CLOSED.
\*   The procedure self-recovers by converting to ASSIGN at
\*   GET_ASSIGN_CANDIDATE.  This models the branch in confirmClosed()
\*   where the procedure detects that the region was abnormally closed
\*   and advances to re-open it, rather than waiting for
\*   TRSPServerCrashed.  The region needs to be reopened to process
\*   recovered edits before any further operations.
\*
\* Source: TRSP.confirmClosed(); Path 1 checks
\*         regionNode.isInState(CLOSED) and completes or advances
\*         to GET_ASSIGN_CANDIDATE; Path 2 detects
\*         ABNORMALLY_CLOSED and sets forceNewPlan to reopen.
\*         AM.regionClosedAbnormally() persists the
\*         ABNORMALLY_CLOSED state to meta.
TRSPConfirmClosed(r) ==
  /\ regionState[r].procType \in { "UNASSIGN", "MOVE", "REOPEN" }
  /\ regionState[r].procStep = "CONFIRM_CLOSED"
  /\ locked[r] = FALSE
  /\ \/ \* --- Path 1: Normal close (CLOSING -> CLOSED) ---
        /\ regionState[r].state = "CLOSING"
        /\ \E rpt \in pendingReports:
             /\ rpt.region = r
             /\ rpt.code = "CLOSED"
             /\ serverState[rpt.server] = "ONLINE"
             \* The report must come from the server where the CLOSE was
             \* dispatched.
             /\ rpt.server = regionState[r].targetServer
             \* Do not consume if r was reopened on that server (RSOpen ran
             \* after the CLOSED was produced); that would violate RSMasterAgreementConverse.
             /\ r \notin rsOnlineRegions[rpt.server]
             \* Finalize region as CLOSED, clear location and target server.
             \* UNASSIGN: clear procedure to idle.
             \* MOVE: keep procedure, advance to GET_ASSIGN_CANDIDATE for re-open.
             /\ regionState' =
                  [regionState EXCEPT
                  ![r] =
                  [ state |-> "CLOSED",
                    location |-> None,
                    procType |->
                      IF regionState[r].procType = "UNASSIGN"
                      THEN "NONE"
                      ELSE regionState[r].procType,
                    procStep |->
                      IF regionState[r].procType = "UNASSIGN"
                      THEN "IDLE"
                      ELSE "GET_ASSIGN_CANDIDATE",
                    targetServer |-> None,
                    retries |-> 0
                  ]]
             \* Persist CLOSED state and cleared location in META.
             /\ metaTable' =
                  [metaTable EXCEPT
                  ![r] =
                  [ state |-> "CLOSED", location |-> None ]]
             \* Consume the matched report from the pending set.
             /\ pendingReports' = pendingReports \ { rpt }
             \* Dispatched ops, RS-side state, and server liveness unchanged.
             /\ UNCHANGED << dispatchedOps,
                   rsVars,
                   serverState,
                   scpVars,
                   locked
                >>
     \/ \* --- Path 2: Crash during close (ABNORMALLY_CLOSED) ---
        \* The target server crashed while the close was in flight.
        \* The procedure self-recovers: convert to ASSIGN at
        \* GET_ASSIGN_CANDIDATE to reopen the region (needed to
        \* process recovered edits before completing the original
        \* unassign/move).
        \*
        \* We also clear any stale dispatched commands (e.g., CLOSE)
        \* for this region from all servers.  In the implementation,
        \* RegionRemoteProcedureBase.serverCrashed() transitions the
        \* stale remote procedure to SERVER_CRASH state, which
        \* effectively abandons it.  The model must do the equivalent
        \* to prevent stale commands from racing with the new ASSIGN.
        \*
        \* Source: TRSP.confirmClosed(),
        \*         RegionRemoteProcedureBase.serverCrashed().
        /\ regionState[r].state = "ABNORMALLY_CLOSED"
        /\ regionState' =
             [regionState EXCEPT
             ![r].procType =
             "ASSIGN",
             ![r].procStep =
             "GET_ASSIGN_CANDIDATE",
             ![r].targetServer =
             None,
             ![r].retries =
             0]
        \* Clear stale dispatched commands for this region.
        /\ dispatchedOps' =
             [s \in Servers |-> {cmd \in dispatchedOps[s]: cmd.region # r}
             ]
        /\ UNCHANGED << metaTable,
              pendingReports,
              rsVars,
              serverState,
              scpVars,
              locked
           >>

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
  \* Meta is NOT updated: RegionStateNode.offline() (RSN.java L132-134)
  \* does not write to meta.  metaTable retains CLOSED; divergence is
  \* resolved on master restart.  See Appendix D.5.
  /\ UNCHANGED << metaTable, rpcVars, rsVars, serverState, scpVars, locked >>

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
        locked
     >>

\* A process supervisor (Kubernetes, systemd, etc.) restarts a crashed
\* RegionServer.  The restarted server is empty: no regions, no pending
\* commands, no RS-side state.  Any pending reports from the previous
\* incarnation are discarded (in real HBase, epoch mismatch rejects
\* them).  SCP state is reset to "NONE" and walFenced is cleared.
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
  \* Region state and META unchanged.
  /\ UNCHANGED << regionState, metaTable, locked >>

---------------------------------------------------------------------------

(* Actions -- crash recovery *)
\* A procedure's region has been crashed (ABNORMALLY_CLOSED).  The
\* procedure converts itself to an ASSIGN at GET_ASSIGN_CANDIDATE,
\* modeling the TRSP serverCrashed() callback plus the closeRegion()
\* recovery branch: when closeRegion() finds the region is not in a
\* closeable state, it sets forceNewPlan=true, clears the location,
\* and jumps to GET_ASSIGN_CANDIDATE.
\*
\* This resolves the deadlock where an UNASSIGN procedure is stranded
\* on an ABNORMALLY_CLOSED region with no way to progress.  The full
\* SCP machinery (ServerCrashProcedure) orchestrates WHEN this callback
\* fires; this action models WHAT happens to the TRSP.
\*
\* Pre: region has an active procedure (any type) AND region state is
\*      ABNORMALLY_CLOSED.
\* Post: procedure converted to ASSIGN/GET_ASSIGN_CANDIDATE with
\*       cleared targetServer and reset retries.  Stale CLOSE commands
\*       for this region cleared from all servers' dispatchedOps.
\*       Stale CLOSED reports for this region dropped from
\*       pendingReports.
\*
\* Source: TRSP.serverCrashed() delegates to
\*         RegionRemoteProcedureBase.serverCrashed()
\*         if a sub-procedure is in flight, or directly calls
\*         AM.regionClosedAbnormally();
\*         TRSP.closeRegion() else-branch sets forceNewPlan
\*         and advances to GET_ASSIGN_CANDIDATE when the region
\*         is not in a closeable state.
TRSPServerCrashed(r) ==
  \* Guards: region has an active procedure and is ABNORMALLY_CLOSED.
  /\ regionState[r].procType # "NONE"
  /\ regionState[r].state = "ABNORMALLY_CLOSED"
  /\ locked[r] = FALSE
  \* Convert the procedure to ASSIGN at GET_ASSIGN_CANDIDATE, clear target.
  /\ regionState' =
       [regionState EXCEPT
       ![r].procType =
       "ASSIGN",
       ![r].procStep =
       "GET_ASSIGN_CANDIDATE",
       ![r].targetServer =
       None,
       ![r].retries =
       0]
  \* Clear any CLOSE for r from dispatchedOps.  The prior procedure (UNASSIGN
  \* or MOVE) dispatched it; we are abandoning that and doing ASSIGN instead.
  \* Without this, RSClose can consume the stale CLOSE after we reassign,
  \* violating RSMasterAgreement (OPEN in regionState but r not in rsOnlineRegions).
  /\ dispatchedOps' =
       [t \in Servers |->
         {cmd \in dispatchedOps[t]: cmd.region # r \/ cmd.type # "CLOSE"}
       ]
  \* Drop CLOSED reports for r.  They are from the abandoned UNASSIGN/MOVE;
  \* consuming them after we reassign would set CLOSED while r is in
  \* rsOnlineRegions (from RSOpen), violating RSMasterAgreementConverse.
  /\ pendingReports' =
       {pr \in pendingReports: pr.region # r \/ pr.code # "CLOSED"}
  \* META, RS-side state, and server liveness unchanged.
  /\ UNCHANGED << metaTable, rsVars, serverState, scpVars, locked >>

---------------------------------------------------------------------------

(* Actions -- RS abort (zombie shutdown) *)
\* The zombie RS discovers it is dead (via YouAreDeadException, ZK
\* session expiry, or WAL fencing) and shuts down.  Clears RS-side
\* state (online regions and pending commands).
\*
\* This action is non-deterministic in timing: it may fire at any time
\* after MasterDetectCrash, including before or after SCPFenceWALs.
\* walFenced[s] = TRUE is a sufficient but not necessary enabling
\* condition (the RS might self-detect death before WAL fencing).
\* WF on RSAbort ensures the zombie eventually shuts down.
\*
\* Pre: server is CRASHED AND still has residual RS-side state
\*      (rsOnlineRegions[s] non-empty OR dispatchedOps[s] non-empty).
\* Post: rsOnlineRegions[s] cleared to {}, dispatchedOps[s] cleared
\*       to {}.  Master-side state (regionState, metaTable,
\*       pendingReports, serverState, scpVars) unchanged.
\*
\* Source: HRegionServer.abort() triggers the RS shutdown
\*         sequence, clearing online regions and stopping RPC
\*         handlers.
RSAbort(s) ==
  \* Guards: server is CRASHED and still has residual RS-side state.
  /\ serverState[s] = "CRASHED"
  /\ rsOnlineRegions[s] # {} \/ dispatchedOps[s] # {}
  \* Purge all regions the zombie RS considers online.
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
  \* Discard all unprocessed commands queued for this RS.
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
  \* Master-side state, meta, reports, and SCP state are unaffected.
  /\ UNCHANGED << regionState,
        metaTable,
        pendingReports,
        serverState,
        scpVars,
        locked
     >>

---------------------------------------------------------------------------

(* Actions -- ServerCrashProcedure (SCP) state machine *)
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
\*         and ASSIGN_META cases; SCP.java L161-195.
SCPAssignMeta(s) ==
  /\ scpState[s] = "ASSIGN_META"
  /\ carryingMeta[s] = TRUE
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
        locked
     >>

SCPGetRegions(s) ==
  \* Guard: SCP is in GET_REGIONS state for this crashed server.
  /\ scpState[s] = "GET_REGIONS"
  \* Meta must be online (no server in ASSIGN_META) before SCP proceeds.
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
  \* Snapshot all regions currently assigned to the crashed server.
  /\ scpRegions' =
       [scpRegions EXCEPT ![s] = {r \in Regions: regionState[r].location = s}]
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
        carryingMeta
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
\*         SERVER_CRASH_SPLIT_LOGS case creates WAL splitting
\*         sub-procedures; the model abstracts this as a single
\*         fencing step.
SCPFenceWALs(s) ==
  \* Guard: SCP is in FENCE_WALS state for this crashed server.
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
        carryingMeta
     >>

\* SCP step 3: Process ONE region from the SCP's region snapshot.
\* Each invocation handles a single region and removes it from
\* scpRegions[s].  Three sub-paths:
\*
\*   Skip (location check): when UseLocationCheck is TRUE and the
\*     region's master-side location no longer matches the crashed
\*     server, SCP skips the region entirely.  This models the
\*     isMatchingRegionLocation() guard (SCP.java L529-538) and is
\*     a known source of HBASE-24293 / HBASE-21623 bugs.
\*   Path A (procedure attached): transition region to ABNORMALLY_CLOSED,
\*     clear location; existing procedure preserved for TRSPServerCrashed.
\*   Path B (no procedure): transition to ABNORMALLY_CLOSED, clear
\*     location, create fresh ASSIGN/GET_ASSIGN_CANDIDATE procedure.
\*
\* Pre: scpState[s] = "ASSIGN", r in scpRegions[s], walFenced[s] = TRUE.
\* Post: r removed from scpRegions[s], region transitioned (or skipped).
\*
\* Source: ServerCrashProcedure.assignRegions() SCP.java L512-561;
\*         isMatchingRegionLocation() SCP.java L498-500.
SCPAssignRegion(s, r) ==
  /\ scpState[s] = "ASSIGN"
  \* Meta must be online (no server in ASSIGN_META) before SCP proceeds.
  /\ \A t \in Servers: scpState[t] # "ASSIGN_META"
  /\ r \in scpRegions[s]
  /\ walFenced[s] = TRUE
  /\ locked[r] = FALSE
  /\ \/ \* --- Skip: isMatchingRegionLocation fails (SCP.java L529-538) ---
        \* Between SCPGetRegions and now, a concurrent TRSP may have moved
        \* this region to another server.  The implementation skips such
        \* regions.  This is a known source of bugs: if the concurrent TRSP
        \* subsequently fails, the region is lost (HBASE-24293).
        \* Toggle: UseLocationCheck = FALSE disables this path, modeling
        \* the correct protocol (process every region unconditionally).
        /\ UseLocationCheck = TRUE
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
              carryingMeta
           >>
     \/ \* --- Path A: TRSP already attached (SCP.java L540-544) ---
        \* Location matches (or check disabled); procedure exists.
        \* Transition region to ABNORMALLY_CLOSED; the existing
        \* TRSPServerCrashed action will convert the procedure to
        \* ASSIGN/GET_ASSIGN_CANDIDATE as a separate step.
        /\ \/ UseLocationCheck = FALSE
           \/ regionState[r].location = s
        /\ regionState[r].procType # "NONE"
        \* Clear r from rsOnlineRegions on all servers.  The region may
        \* have moved between SCPGetRegions and now; clearing everywhere
        \* prevents ghost regions.
        /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
        \* Drop stale OPENED reports for r: marking ABNORMALLY_CLOSED
        \* makes them obsolete.
        /\ pendingReports' =
             {pr \in pendingReports: pr.code # "OPENED" \/ pr.region # r}
        /\ regionState' =
             [regionState EXCEPT
             ![r].state =
             "ABNORMALLY_CLOSED",
             ![r].location =
             None]
        /\ metaTable' =
             [metaTable EXCEPT
             ![r] =
             [ state |-> "ABNORMALLY_CLOSED", location |-> None ]]
        /\ scpRegions' = [scpRegions EXCEPT ![s] = @ \ { r }]
        /\ UNCHANGED << dispatchedOps,
              serverState,
              walFenced,
              scpState,
              locked,
              carryingMeta
           >>
     \/ \* --- Path B: No TRSP attached (SCP.java L554-557) ---
        \* Location matches (or check disabled); no procedure.
        \* Transition to ABNORMALLY_CLOSED and attach a fresh
        \* ASSIGN procedure at GET_ASSIGN_CANDIDATE.
        /\ \/ UseLocationCheck = FALSE
           \/ regionState[r].location = s
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
  \* Guards: SCP is in ASSIGN state and all regions have been processed.
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
        carryingMeta
     >>

---------------------------------------------------------------------------

(* Actions -- stale report cleanup *)
\* Drop a report from a crashed server.  Models the "You are dead"
\* rejection path in reportRegionStateTransition() (AM.java L1277-1293).
\* Reports from crashed servers cannot be consumed by TRSPConfirmOpened,
\* TRSPConfirmClosed, or TRSPHandleFailedOpen (server ONLINE guard),
\* so this action cleans them up.
\*
\* Pre: a pending report exists from a CRASHED server.
\* Post: the stale report is removed from pendingReports.  All other
\*       state variables unchanged.
\*
\* Source: AM.reportRegionStateTransition() rejects
\*         reports when serverNode is not in ONLINE state
\*         ("You are dead" error path).
DropStaleReport ==
  \* Guard: a pending report exists from a CRASHED server.
    \E rpt \in pendingReports:
    /\ serverState[rpt.server] = "CRASHED"
    \* Discard the stale report.
    /\ pendingReports' = pendingReports \ { rpt }
    \* All other state variables unchanged.
    /\ UNCHANGED << regionState,
          metaTable,
          dispatchedOps,
          rsVars,
          serverState,
          scpVars,
          locked
       >>

---------------------------------------------------------------------------

(* Actions -- RS-side open handler *)
\* RS atomically receives an OPEN command, opens the region, adds it
\* to rsOnlineRegions, and reports OPENED to the master.  Merges the
\* former RSReceiveOpen + RSCompleteOpen into a single action because
\* the intermediate state (command consumed, RS working on open) is
\* not observable by the master and produces the same crash-recovery
\* outcome as the pre-receive state.
\*
\* Pre: Server is ONLINE, an OPEN command for region r exists in
\*      dispatchedOps[s].
\* Post: Command consumed, r added to rsOnlineRegions[s], OPENED
\*       report produced in pendingReports.
\*
\* Source: AssignRegionHandler.process() success path:
\*         opens the region via HRegion.openHRegion(), adds it
\*         to the RS's online regions, and reports OPENED via
\*         reportRegionStateTransition() RPC.
RSOpen(s, r) ==
  \* Guards: server is ONLINE, region is NOT already online on this
  \* server, and an OPEN command for region r exists.
  \* The r \notin rsOnlineRegions[s] guard matches the implementation:
  \* AssignRegionHandler.process() (L105-112) returns without reporting
  \* OPENED if the region is already online.
  \* The regionState[r].state = "OPENING" /\ regionState[r].location = s
  \* guard rejects stale OPEN commands: if SCPAssignRegion has already
  \* moved the region to ABNORMALLY_CLOSED (or location changed), the
  \* command is obsolete and must not add to rsOnlineRegions.
  /\ serverState[s] = "ONLINE"
  /\ r \notin rsOnlineRegions[s]
  /\ regionState[r].state = "OPENING"
  /\ regionState[r].location = s
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "OPEN"
       /\ cmd.region = r
       \* Consume the command from the server's dispatched ops queue.
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       \* Add the region to the server's set of online regions.
       /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = @ \cup { r }]
       \* Send an OPENED report to the master for procedure confirmation.
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "OPENED" ] }
       \* Master-side state and server liveness unchanged.
       /\ UNCHANGED << regionState, metaTable, serverState, scpVars, locked >>

\* RS atomically receives an OPEN command but fails to open the
\* region.  The command is consumed and a FAILED_OPEN report is
\* produced.  The region is NOT added to rsOnlineRegions.
\*
\* Pre: Server is ONLINE, an OPEN command for region r exists in
\*      dispatchedOps[s].
\* Post: Command consumed, FAILED_OPEN report produced.
\*
\* Source: AssignRegionHandler.process() failure path:
\*         AssignRegionHandler.cleanUpAndReportFailure()
\*         reports FAILED_OPEN via reportRegionStateTransition()
\*         RPC; the region is NOT added to online regions.
RSFailOpen(s, r) ==
  \* Guards: server is ONLINE and an OPEN command for region r exists.
  /\ serverState[s] = "ONLINE"
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "OPEN"
       /\ cmd.region = r
       \* Consume the command from the server's dispatched ops queue.
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       \* Send a FAILED_OPEN report to the master for error handling.
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "FAILED_OPEN" ] }
       \* Master-side state, online regions, and server liveness unchanged.
       /\ UNCHANGED << regionState,
             metaTable,
             rsOnlineRegions,
             serverState,
             scpVars,
             locked
          >>

---------------------------------------------------------------------------

(* Actions -- RS-side close handler *)
\* RS atomically receives a CLOSE command, closes the region, removes
\* it from rsOnlineRegions, and reports CLOSED to the master.  Merges
\* the former RSReceiveClose + RSCompleteClose into a single action
\* (same rationale as RSOpen -- see RS-side open handler comment).
\*
\* Pre: Server is ONLINE, a CLOSE command for region r exists in
\*      dispatchedOps[s].
\* Post: Command consumed, r removed from rsOnlineRegions[s], CLOSED
\*       report produced in pendingReports.
\*
\* Source: UnassignRegionHandler.process() success path:
\*         closes the region via HRegion.close(), removes it from
\*         the RS's online regions, and reports CLOSED via
\*         reportRegionStateTransition() RPC.
RSClose(s, r) ==
  \* Guards: server is ONLINE and a CLOSE command for region r exists.
  /\ serverState[s] = "ONLINE"
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "CLOSE"
       /\ cmd.region = r
       \* Consume the command from the server's dispatched ops queue.
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       \* Remove the region from the server's set of online regions.
       /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
       \* Send a CLOSED report to the master for procedure confirmation.
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "CLOSED" ] }
       \* Master-side state and server liveness unchanged.
       /\ UNCHANGED << regionState, metaTable, serverState, scpVars, locked >>

---------------------------------------------------------------------------

(* Next-state relation *)
Next == \* -- ASSIGN path --
        \/ \E r \in Regions: TRSPCreate(r)
        \/ \E r \in Regions: \E s \in Servers: TRSPGetCandidate(r, s)
        \/ \E r \in Regions: TRSPDispatchOpen(r)
        \/ \E r \in Regions: TRSPConfirmOpened(r)
        \/ \E r \in Regions: TRSPHandleFailedOpen(r)
        \/ \E r \in Regions: TRSPGiveUpOpen(r)
        \/ \E r \in Regions: DispatchFail(r)
        \* -- UNASSIGN path --
        \/ \E r \in Regions: TRSPCreateUnassign(r)
        \* -- MOVE path --
        \/ \E r \in Regions: TRSPCreateMove(r)
        \* -- REOPEN path --
        \/ \E r \in Regions: TRSPCreateReopen(r)
        \/ \E r \in Regions: TRSPDispatchClose(r)
        \/ \E r \in Regions: TRSPConfirmClosed(r)
        \/ \E r \in Regions: DispatchFailClose(r)
        \* -- External events --
        \/ \E r \in Regions: GoOffline(r)
        \/ \E s \in Servers: MasterDetectCrash(s)
        \/ \E s \in Servers: ServerRestart(s)
        \* -- Crash recovery --
        \/ \E r \in Regions: TRSPServerCrashed(r)
        \* -- RS abort (zombie shutdown) --
        \/ \E s \in Servers: RSAbort(s)
        \* -- SCP state machine --
        \/ \E s \in Servers: SCPAssignMeta(s)
        \/ \E s \in Servers: SCPGetRegions(s)
        \/ \E s \in Servers: SCPFenceWALs(s)
        \/ \E s \in Servers: \E r \in Regions: SCPAssignRegion(s, r)
        \/ \E s \in Servers: SCPDone(s)
        \* -- Stale report cleanup --
        \/ DropStaleReport
        \* -- RS-side open handler --
        \/ \E s \in Servers: \E r \in Regions: RSOpen(s, r)
        \/ \E s \in Servers: \E r \in Regions: RSFailOpen(s, r)
        \* -- RS-side close handler --
        \/ \E s \in Servers: \E r \in Regions: RSClose(s, r)

---------------------------------------------------------------------------

(* Fairness *)
\* Weak fairness on all deterministic actions ensures forward progress.
\* Procedure-step actions, crash-recovery actions, SCP state machine
\* steps, and RS-side processing are all deterministic once enabled and
\* therefore receive WF.  Non-deterministic environmental events
\* (DispatchFail, DispatchFailClose, MasterDetectCrash, RSFailOpen,
\* GoOffline) receive no fairness -- they may occur but are not
\* required to.
Fairness ==
  \* Creation actions
  /\ \A r \in Regions: WF_vars(TRSPCreate(r))
  /\ \A r \in Regions: WF_vars(TRSPCreateUnassign(r))
  /\ \A r \in Regions: WF_vars(TRSPCreateMove(r))
  /\ \A r \in Regions: WF_vars(TRSPCreateReopen(r))
  /\ \A s \in Servers: WF_vars(ServerRestart(s))
  \* Deterministic procedure steps
  /\ \A r \in Regions: \A s \in Servers: WF_vars(TRSPGetCandidate(r, s))
  /\ \A r \in Regions: WF_vars(TRSPDispatchOpen(r))
  /\ \A r \in Regions: WF_vars(TRSPConfirmOpened(r))
  /\ \A r \in Regions: WF_vars(TRSPHandleFailedOpen(r))
  /\ \A r \in Regions: WF_vars(TRSPGiveUpOpen(r))
  /\ \A r \in Regions: WF_vars(TRSPDispatchClose(r))
  /\ \A r \in Regions: WF_vars(TRSPConfirmClosed(r))
  \* Crash recovery
  /\ \A r \in Regions: WF_vars(TRSPServerCrashed(r))
  /\ WF_vars(DropStaleReport)
  \* RS abort (zombie eventually shuts down)
  /\ \A s \in Servers: WF_vars(RSAbort(s))
  \* SCP state machine
  /\ \A s \in Servers: WF_vars(SCPAssignMeta(s))
  /\ \A s \in Servers: WF_vars(SCPGetRegions(s))
  /\ \A s \in Servers: WF_vars(SCPFenceWALs(s))
  /\ \A s \in Servers: \A r \in Regions: WF_vars(SCPAssignRegion(s, r))
  /\ \A s \in Servers: WF_vars(SCPDone(s))
  \* RS-side processing
  /\ \A s \in Servers: \A r \in Regions: WF_vars(RSOpen(s, r))
  /\ \A s \in Servers: \A r \in Regions: WF_vars(RSClose(s, r))

---------------------------------------------------------------------------

(* Specification *)
Spec == Init /\ [][Next]_vars /\ Fairness

---------------------------------------------------------------------------

(* Theorems / properties to check *)
        \* Safety: every step preserves the type invariant.
        THEOREM Spec => []TypeOK

\* Safety: OPEN regions always have a location.
        THEOREM Spec => []OpenImpliesLocation

\* Safety: offline-like regions never have a location.
        THEOREM Spec => []OfflineImpliesNoLocation

\* Safety: no double assignment (region writable on at most one server).
        THEOREM Spec => []NoDoubleAssignment

\* Safety: SCP does not reassign until WAL leases are revoked.
        THEOREM Spec => []ZombieFencingOrder

\* Safety: after SCP completes, no region is lost (stuck without a procedure).
        THEOREM Spec => []NoLostRegions

\* Safety: persistent meta state matches in-memory state.
        THEOREM Spec => []MetaConsistency

\* Safety: procedure lock held only during appropriate states.
        THEOREM Spec => []LockExclusivity

\* Safety: stably OPEN region is online on its RS.
        THEOREM Spec => []RSMasterAgreement

\* Safety: RS-online region is acknowledged by master.
        THEOREM Spec => []RSMasterAgreementConverse

\* All transitions in every step are members of ValidTransition.
\* Expressed as an action property checked via TLC's action constraint.
TransitionValid ==
  \A r \in Regions:
    regionState'[r].state # regionState[r].state =>
      << regionState[r].state, regionState'[r].state >> \in ValidTransition

============================================================================
