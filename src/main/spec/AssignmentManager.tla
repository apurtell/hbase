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
 * MOVE is an UNASSIGN (close on current server) followed by an ASSIGN
 * (open on a new server), all within a single procedure.
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
 * RS-side receive and complete steps are merged into single atomic
 * actions because the intermediate RS state (command consumed but not
 * yet reported) is not observable by the master and produces the same
 * crash-recovery outcome as the pre-receive state.
 *
 * Server liveness is tracked by serverState.  ServerCrashAll(s) models
 * an RS crash as a per-server event: all regions on the crashed server
 * transition to ABNORMALLY_CLOSED atomically, RS-side state and pending
 * commands are cleared, and the server is marked CRASHED.  RS-side
 * actions and report consumption are guarded by server liveness.
 * Reports from crashed servers are dropped by DropStaleReport.
 * ServerRestart(s) models a process supervisor (Kubernetes, systemd)
 * restarting a crashed server; WF on ServerRestart ensures crashed
 * servers eventually come back online.
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
 *)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS
    Regions,    \* The finite set of region identifiers
    Servers     \* The finite set of regionserver identifiers

ASSUME Regions # {}
ASSUME Servers # {}

CONSTANTS
    None       \* Sentinel model value for "no server assigned"

ASSUME None \notin Servers

CONSTANTS
    MaxRetries  \* Maximum open-retry attempts before giving up (FAILED_OPEN)

ASSUME MaxRetries \in Nat /\ MaxRetries >= 0

---------------------------------------------------------------------------
(* State definitions *)

\* Core assignment lifecycle states.
\* Mirrors RegionState.State for the assign/unassign/move path.
State == { "OFFLINE",
           "OPENING",
           "OPEN",
           "CLOSING",
           "CLOSED",
           "FAILED_OPEN",
           "ABNORMALLY_CLOSED" }

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
    { <<"OFFLINE",            "OPENING">>,
      <<"OPENING",            "OPEN">>,
      <<"OPENING",            "FAILED_OPEN">>,
      <<"OPEN",               "CLOSING">>,
      <<"CLOSING",            "CLOSED">>,
      <<"CLOSED",             "OPENING">>,
      <<"CLOSED",             "OFFLINE">>,
      <<"OPEN",               "ABNORMALLY_CLOSED">>,
      <<"OPENING",            "ABNORMALLY_CLOSED">>,
      <<"CLOSING",            "ABNORMALLY_CLOSED">>,
      <<"ABNORMALLY_CLOSED",  "OPENING">>,
      <<"FAILED_OPEN",        "OPENING">> }

\* TRSP internal states used in the procStep field of regionState.
\* ASSIGN path:   GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED -> (cleared)
\* UNASSIGN path: CLOSE -> CONFIRM_CLOSED -> (cleared)
\* MOVE path:     CLOSE -> CONFIRM_CLOSED -> GET_ASSIGN_CANDIDATE -> OPEN
\*                   -> CONFIRM_OPENED -> (cleared)
TRSPState == {"GET_ASSIGN_CANDIDATE", "OPEN", "CONFIRM_OPENED",
              "CLOSE", "CONFIRM_CLOSED"}

\* Procedure types.  "NONE" means no procedure is attached.
ProcType == {"ASSIGN", "UNASSIGN", "MOVE", "NONE"}

\* RPC command types dispatched from master to RegionServer.
\* Source: RSProcedureDispatcher dispatches OpenRegionProcedure /
\*         CloseRegionProcedure via executeProcedures() RPC.
CommandType == {"OPEN", "CLOSE"}

\* Transition codes reported from RegionServer back to master.
\* Source: RegionServerStatusService.reportRegionStateTransition()
\*         with TransitionCode enum values.
ReportCode == {"OPENED", "FAILED_OPEN", "CLOSED"}

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
    \* Source: RegionStateNode.java setProcedure() L213-218,
    \*         unsetProcedure() L220-224.

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
    \* Source: RSProcedureDispatcher.java L200-367.

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

vars == <<regionState, metaTable,
          dispatchedOps, pendingReports, rsOnlineRegions, serverState>>

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == <<dispatchedOps, pendingReports>>

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == <<rsOnlineRegions>>

---------------------------------------------------------------------------
(* Type invariant *)

TypeOK ==
    \* For every region, check all fields of its in-memory state record.
    /\ \A r \in Regions :
         \* Region lifecycle state must be one of the defined states.
         /\ regionState[r].state \in State
         \* Region location is either an assigned server or None.
         /\ regionState[r].location \in Servers \cup {None}
         \* Embedded procedure type must be a valid ProcType.
         /\ regionState[r].procType \in ProcType
         \* Procedure step is a TRSP state or IDLE (when no procedure).
         /\ regionState[r].procStep \in TRSPState \cup {"IDLE"}
         \* Procedure's target server is a server or None.
         /\ regionState[r].targetServer \in Servers \cup {None}
         \* Retry counter is bounded by MaxRetries.
         /\ regionState[r].retries \in 0..MaxRetries
         \* Idle procedure fields are consistent.
         /\ regionState[r].procType = "NONE" =>
              /\ regionState[r].procStep = "IDLE"
              /\ regionState[r].targetServer = None
              /\ regionState[r].retries = 0
         \* Active procedure has a valid step.
         /\ regionState[r].procType # "NONE" =>
              regionState[r].procStep \in TRSPState
    \* META table maps every region to a persistent (state, location) record.
    /\ metaTable \in [Regions -> [state : State,
                                  location : Servers \cup {None}]]
    \* Each server has a set of dispatched operation commands (open/close).
    /\ dispatchedOps \in [Servers -> SUBSET
         [type : CommandType, region : Regions]]
    \* Pending reports are a set of region-transition outcome messages.
    /\ pendingReports \subseteq
         [server : Servers, region : Regions, code : ReportCode]
    \* Each server tracks its set of locally online regions.
    /\ rsOnlineRegions \in [Servers -> SUBSET Regions]
    \* Each server is either ONLINE or CRASHED.
    /\ serverState \in [Servers -> {"ONLINE", "CRASHED"}]

---------------------------------------------------------------------------
(* Safety invariants *)

\* A region that is OPEN must have a location.
OpenImpliesLocation ==
    \A r \in Regions :
        regionState[r].state = "OPEN" => regionState[r].location # None

\* A region that is OFFLINE, CLOSED, FAILED_OPEN, or ABNORMALLY_CLOSED
\* must NOT have a location.
OfflineImpliesNoLocation ==
    \A r \in Regions :
        regionState[r].state \in {"OFFLINE", "CLOSED",
                                  "FAILED_OPEN", "ABNORMALLY_CLOSED"}
            => regionState[r].location = None

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
    \A r \in Regions :
        \/ /\ metaTable[r].state = regionState[r].state
           /\ metaTable[r].location = regionState[r].location
        \/ /\ regionState[r].state = "OFFLINE"
           /\ metaTable[r].state = "CLOSED"
           /\ regionState[r].location = None
           /\ metaTable[r].location = None

\* No double assignment: a region is never simultaneously deployed on
\* two (or more) RegionServers.  This is the foundational safety
\* property of the assignment manager.
\*
\* Source: The "at most one server per region" invariant from the
\*         HBase region assignment design.
NoDoubleAssignment ==
    \A r \in Regions :
        Cardinality({s \in Servers : r \in rsOnlineRegions[s]}) <= 1

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
\* Source: RegionStateNode.java setProcedure() L213-218,
\*         unsetProcedure() L220-224.
LockExclusivity ==
    \A r \in Regions :
        regionState[r].procType # "NONE" =>
            \/ /\ regionState[r].procType = "ASSIGN"
               /\ regionState[r].state \in {"OFFLINE", "CLOSED",
                                             "ABNORMALLY_CLOSED",
                                             "FAILED_OPEN", "OPENING"}
            \/ /\ regionState[r].procType = "UNASSIGN"
               /\ regionState[r].state \in {"OPEN", "CLOSING",
                                             "ABNORMALLY_CLOSED"}
            \/ /\ regionState[r].procType = "MOVE"
               /\ regionState[r].state \in {"OPEN", "CLOSING", "CLOSED",
                                             "ABNORMALLY_CLOSED",
                                             "FAILED_OPEN", "OPENING"}

\* When a region is stably OPEN (no procedure attached), the RS hosting
\* it must also consider it online.  This cross-checks the master's view
\* (regionState) against the RS's view (rsOnlineRegions).
\*
\* The invariant holds because TRSPConfirmOpened (which sets OPEN and
\* clears the procedure) requires an OPENED report, which is only
\* produced by RSOpen after adding the region to rsOnlineRegions.
RSMasterAgreement ==
    \A r \in Regions :
        regionState[r].state = "OPEN" /\ regionState[r].procType = "NONE"
            => r \in rsOnlineRegions[regionState[r].location]

\* Converse of RSMasterAgreement: if an RS considers a region online,
\* the master must agree on both location and lifecycle state.  Catches
\* "ghost regions" where an RS believes it hosts a region the master
\* has already reassigned or crashed.
\*
\* OPENING is included because RSOpen adds the region to
\* rsOnlineRegions before TRSPConfirmOpened transitions the master
\* state from OPENING to OPEN.  ServerCrashAll clears rsOnlineRegions
\* atomically, so crashed-server ghost entries cannot arise.
RSMasterAgreementConverse ==
    \A s \in Servers : \A r \in rsOnlineRegions[s] :
        /\ regionState[r].location = s
        /\ regionState[r].state \in {"OPENING", "OPEN", "CLOSING"}

---------------------------------------------------------------------------
(* State constraints for TLC *)

\* Limit the number of simultaneously crashed servers.  No longer
\* required in configs now that ServerRestart + WF ensures crashed
\* servers eventually come back online (preventing the all-crashed
\* deadlock).  Retained as a definition for ad-hoc bounded runs.
CrashConstraint ==
    Cardinality({s \in Servers : serverState[s] = "CRASHED"}) <= 1

\* Symmetry reduction: regions and servers are interchangeable.
\* TLC explores one representative per equivalence class, reducing
\* the state space by up to |Regions|! * |Servers|! (36x for 3r/3s).
Symmetry == Permutations(Regions) \union Permutations(Servers)

---------------------------------------------------------------------------
(* Initial state *)

Init ==
    \* Every region starts OFFLINE with no server and no procedure.
    /\ regionState = [r \in Regions |->
         [state |-> "OFFLINE", location |-> None,
          procType |-> "NONE", procStep |-> "IDLE",
          targetServer |-> None, retries |-> 0]]
    \* META table mirrors the initial in-memory state: all regions OFFLINE.
    /\ metaTable = [r \in Regions |->
         [state |-> "OFFLINE", location |-> None]]
    \* No operation commands have been dispatched to any server.
    /\ dispatchedOps = [s \in Servers |-> {}]
    \* No region-transition reports are pending.
    /\ pendingReports = {}
    \* No regions are online on any RS at startup.
    /\ rsOnlineRegions = [s \in Servers |-> {}]
    \* All servers are initially ONLINE.
    /\ serverState = [s \in Servers |-> "ONLINE"]

---------------------------------------------------------------------------
(* Actions -- TRSP ASSIGN path *)

\* Create a TRSP ASSIGN procedure for a region eligible for assignment.
\* Pre: region is in a state eligible for assignment AND no procedure
\*      is currently attached.
\* Post: procedure fields set to ASSIGN/GET_ASSIGN_CANDIDATE.  Region
\*       lifecycle state is NOT changed yet -- the TRSP will drive
\*       transitions in subsequent steps.
\*
\* Source: TransitRegionStateProcedure.java queueAssign() L246-278.
TRSPCreate(r) ==
    \* Guards: region is in an assignable state and has no active procedure.
    /\ regionState[r].state \in {"OFFLINE", "CLOSED",
                                  "ABNORMALLY_CLOSED", "FAILED_OPEN"}
    /\ regionState[r].procType = "NONE"
    \* Initialize embedded ASSIGN procedure at GET_ASSIGN_CANDIDATE step.
    /\ regionState' = [regionState EXCEPT
         ![r].procType = "ASSIGN",
         ![r].procStep = "GET_ASSIGN_CANDIDATE",
         ![r].targetServer = None,
         ![r].retries = 0]
    \* META, RPC channels, RS-side state, and server liveness unchanged.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars, serverState>>

\* Choose a target server for the ASSIGN procedure.
\* Pre: TRSP is in GET_ASSIGN_CANDIDATE state, target server is ONLINE.
\* Post: targetServer set, TRSP advances to OPEN state.
\*
\* Source: TransitRegionStateProcedure.java executeFromState() L483-496,
\*         queueAssign() L246-278 (getDestinationServer).
TRSPGetCandidate(r, s) ==
    \* Guards: procedure is ASSIGN or MOVE at GET_ASSIGN_CANDIDATE, server is ONLINE.
    /\ regionState[r].procType \in {"ASSIGN", "MOVE"}
    /\ regionState[r].procStep = "GET_ASSIGN_CANDIDATE"
    /\ serverState[s] = "ONLINE"
    \* Record the chosen server and advance the procedure to the OPEN step.
    /\ regionState' = [regionState EXCEPT
         ![r].targetServer = s,
         ![r].procStep = "OPEN"]
    \* META, RPC channels, RS-side state, and server liveness unchanged.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars, serverState>>

\* Dispatch the open command to the target server via RPC.
\* Pre: TRSP is in OPEN state with a target server.
\* Post: region transitions to OPENING with the target server as
\*       location, meta updated, open command added to
\*       dispatchedOps[targetServer], TRSP advances to CONFIRM_OPENED.
\*
\* Source: TransitRegionStateProcedure.java openRegion() L293-311,
\*         RSProcedureDispatcher.java L200-367.
TRSPDispatchOpen(r) ==
    \* Guards: procedure is ASSIGN or MOVE, in OPEN step, with a target server.
    /\ regionState[r].procType \in {"ASSIGN", "MOVE"}
    /\ regionState[r].procStep = "OPEN"
    /\ regionState[r].targetServer # None
    \* Bind the target server for readability.
    /\ LET s == regionState[r].targetServer IN
       \* Transition region to OPENING, record server, advance to CONFIRM_OPENED.
       /\ regionState' = [regionState EXCEPT
            ![r].state = "OPENING",
            ![r].location = s,
            ![r].procStep = "CONFIRM_OPENED"]
       \* Persist the OPENING state and server assignment in META.
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "OPENING", location |-> s]]
       \* Enqueue an OPEN command to the target server's dispatched ops.
       /\ dispatchedOps' = [dispatchedOps EXCEPT
            ![s] = @ \cup {[type |-> "OPEN", region |-> r]}]
       \* Pending reports, RS-side state, and server liveness unchanged.
       /\ UNCHANGED <<pendingReports, rsVars, serverState>>

\* Confirm that the region opened successfully by consuming an OPENED
\* report from pendingReports.
\* Pre: TRSP is in CONFIRM_OPENED state, region is OPENING, and a
\*      matching OPENED report exists in pendingReports from an ONLINE
\*      server.
\* Post: region transitions to OPEN, procedure cleared, report consumed.
\*
\* Source: TransitRegionStateProcedure.java confirmOpened() L320-374.
TRSPConfirmOpened(r) ==
    \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, region is
    \* OPENING, and a matching OPENED report exists from an ONLINE server.
    /\ regionState[r].procType \in {"ASSIGN", "MOVE"}
    /\ regionState[r].procStep = "CONFIRM_OPENED"
    /\ regionState[r].state = "OPENING"
    /\ \E rpt \in pendingReports :
         /\ rpt.region = r
         /\ rpt.code = "OPENED"
         /\ serverState[rpt.server] = "ONLINE"
         \* Finalize region as OPEN, keep location, clear procedure fields.
         /\ regionState' = [regionState EXCEPT
              ![r] = [state |-> "OPEN",
                      location |-> regionState[r].location,
                      procType |-> "NONE", procStep |-> "IDLE",
                      targetServer |-> None, retries |-> 0]]
         \* Persist the OPEN state in META, preserving the location.
         /\ metaTable' = [metaTable EXCEPT
              ![r] = [state |-> "OPEN",
                      location |-> metaTable[r].location]]
         \* Consume the matched report from the pending set.
         /\ pendingReports' = pendingReports \ {rpt}
         \* Dispatched ops, RS-side state, and server liveness unchanged.
         /\ UNCHANGED <<dispatchedOps, rsVars, serverState>>

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
\* Source: RSProcedureDispatcher.java remoteCallFailed() L325-340.
DispatchFail(r) ==
    \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, has a
    \* target server, and the OPEN command is still in dispatchedOps.
    /\ regionState[r].procType \in {"ASSIGN", "MOVE"}
    /\ regionState[r].procStep = "CONFIRM_OPENED"
    /\ LET s == regionState[r].targetServer IN
       /\ s # None
       \* Reconstruct the dispatched command and verify it is still queued.
       /\ LET cmd == [type |-> "OPEN", region |-> r] IN
          /\ cmd \in dispatchedOps[s]
          \* Remove the undelivered command from the server's queue.
          /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
       \* Reset the procedure to GET_ASSIGN_CANDIDATE with no target server.
       /\ regionState' = [regionState EXCEPT
            ![r].procStep = "GET_ASSIGN_CANDIDATE",
            ![r].targetServer = None]
       \* META, pending reports, RS-side state, and server liveness unchanged.
       /\ UNCHANGED <<metaTable, pendingReports, rsVars, serverState>>

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
\* Source: RSProcedureDispatcher.java remoteCallFailed() L325-340.
DispatchFailClose(r) ==
    \* Guards: procedure is UNASSIGN or MOVE, in CONFIRM_CLOSED step, has a
    \* target server, and the CLOSE command is still in dispatchedOps.
    /\ regionState[r].procType \in {"UNASSIGN", "MOVE"}
    /\ regionState[r].procStep = "CONFIRM_CLOSED"
    /\ LET s == regionState[r].targetServer IN
       /\ s # None
       \* Reconstruct the dispatched command and verify it is still queued.
       /\ LET cmd == [type |-> "CLOSE", region |-> r] IN
          /\ cmd \in dispatchedOps[s]
          \* Remove the undelivered command from the server's queue.
          /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
       \* Reset the procedure to CLOSE to retry (target server kept).
       /\ regionState' = [regionState EXCEPT
            ![r].procStep = "CLOSE"]
       \* META, pending reports, RS-side state, and server liveness unchanged.
       /\ UNCHANGED <<metaTable, pendingReports, rsVars, serverState>>

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
\* Source: TransitRegionStateProcedure.java confirmOpened() L345-374.
TRSPHandleFailedOpen(r) ==
    \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, retries
    \* not exhausted, region is OPENING, and a matching FAILED_OPEN report
    \* exists from an ONLINE server.
    /\ regionState[r].procType \in {"ASSIGN", "MOVE"}
    /\ regionState[r].procStep = "CONFIRM_OPENED"
    /\ regionState[r].retries < MaxRetries
    /\ regionState[r].state = "OPENING"
    /\ \E rpt \in pendingReports :
         /\ rpt.region = r
         /\ rpt.code = "FAILED_OPEN"
         /\ serverState[rpt.server] = "ONLINE"
         \* Reset procedure to retry with a new server, increment retry count.
         /\ regionState' = [regionState EXCEPT
              ![r].procStep = "GET_ASSIGN_CANDIDATE",
              ![r].targetServer = None,
              ![r].retries = regionState[r].retries + 1]
         \* Consume the matched report from the pending set.
         /\ pendingReports' = pendingReports \ {rpt}
         \* META, dispatched ops, RS-side state, and server liveness unchanged.
         /\ UNCHANGED <<metaTable, dispatchedOps, rsVars, serverState>>

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
\* Source: TransitRegionStateProcedure.java confirmOpened() L345-374
\*         (retryCounter >= maxAttempts branch).
TRSPGiveUpOpen(r) ==
    \* Guards: procedure is ASSIGN or MOVE, in CONFIRM_OPENED step, retries
    \* exhausted, region is OPENING, and a matching FAILED_OPEN report
    \* exists from an ONLINE server.
    /\ regionState[r].procType \in {"ASSIGN", "MOVE"}
    /\ regionState[r].procStep = "CONFIRM_OPENED"
    /\ regionState[r].retries >= MaxRetries
    /\ regionState[r].state = "OPENING"
    /\ \E rpt \in pendingReports :
         /\ rpt.region = r
         /\ rpt.code = "FAILED_OPEN"
         /\ serverState[rpt.server] = "ONLINE"
         \* Move region to FAILED_OPEN, clear location, clear procedure fields.
         /\ regionState' = [regionState EXCEPT
              ![r] = [state |-> "FAILED_OPEN", location |-> None,
                      procType |-> "NONE", procStep |-> "IDLE",
                      targetServer |-> None, retries |-> 0]]
         \* Persist FAILED_OPEN state and cleared location in META.
         /\ metaTable' = [metaTable EXCEPT
              ![r] = [state |-> "FAILED_OPEN", location |-> None]]
         \* Consume the matched report from the pending set.
         /\ pendingReports' = pendingReports \ {rpt}
         \* Dispatched ops, RS-side state, and server liveness unchanged.
         /\ UNCHANGED <<dispatchedOps, rsVars, serverState>>

---------------------------------------------------------------------------
(* Actions -- TRSP UNASSIGN path *)

\* Create a TRSP UNASSIGN procedure for an OPEN region.
\* Pre: region is OPEN with a location AND no procedure is attached.
\* Post: procedure fields set to UNASSIGN/CLOSE with targetServer
\*       pointing at the region's current server.  Region lifecycle
\*       state is NOT changed yet -- the TRSP will drive the
\*       OPEN -> CLOSING transition in the next step.
\*
\* Source: TransitRegionStateProcedure.java queueAssign() L246-278
\*         (UNASSIGN path), setProcedure() before closeRegion().
TRSPCreateUnassign(r) ==
    \* Guards: region is OPEN, has a location, and has no active procedure.
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState[r].procType = "NONE"
    \* Initialize embedded UNASSIGN procedure at CLOSE step, targeting current server.
    /\ regionState' = [regionState EXCEPT
         ![r].procType = "UNASSIGN",
         ![r].procStep = "CLOSE",
         ![r].targetServer = regionState[r].location,
         ![r].retries = 0]
    \* META, RPC channels, RS-side state, and server liveness unchanged.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars, serverState>>

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
\* Source: TransitRegionStateProcedure.java TransitionType.MOVE L160-162,
\*         queueAssign() L246-278 (MOVE path).
TRSPCreateMove(r) ==
    \* Guards: region is OPEN, has a location, has no active procedure,
    \* and at least one other ONLINE server exists as a destination.
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState[r].procType = "NONE"
    /\ \E s \in Servers : s # regionState[r].location
                           /\ serverState[s] = "ONLINE"
    \* Initialize embedded MOVE procedure at CLOSE step, targeting current server.
    /\ regionState' = [regionState EXCEPT
         ![r].procType = "MOVE",
         ![r].procStep = "CLOSE",
         ![r].targetServer = regionState[r].location,
         ![r].retries = 0]
    \* META, RPC channels, RS-side state, and server liveness unchanged.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars, serverState>>

\* Dispatch the close command to the target server via RPC.
\* Pre: UNASSIGN or MOVE procedure in CLOSE state, region is OPEN or
\*      CLOSING (CLOSING on retry after DispatchFailClose).
\* Post: region transitions to CLOSING (no-op if already CLOSING),
\*       meta updated, close command added to dispatchedOps[targetServer],
\*       TRSP advances to CONFIRM_CLOSED.
\*
\* Source: TransitRegionStateProcedure.java closeRegion() L389-407,
\*         RSProcedureDispatcher.java L200-367.
TRSPDispatchClose(r) ==
    \* Guards: procedure is UNASSIGN or MOVE, in CLOSE step, has a target
    \* server, and region is OPEN or CLOSING (CLOSING on retry).
    /\ regionState[r].procType \in {"UNASSIGN", "MOVE"}
    /\ regionState[r].procStep = "CLOSE"
    /\ regionState[r].targetServer # None
    \* Bind the target server for readability.
    /\ LET s == regionState[r].targetServer IN
       /\ regionState[r].state \in {"OPEN", "CLOSING"}
       \* Transition region to CLOSING and advance to CONFIRM_CLOSED.
       /\ regionState' = [regionState EXCEPT
            ![r].state = "CLOSING",
            ![r].procStep = "CONFIRM_CLOSED"]
       \* Persist CLOSING state in META, preserving the location.
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "CLOSING",
                    location |-> metaTable[r].location]]
       \* Enqueue a CLOSE command to the target server's dispatched ops.
       /\ dispatchedOps' = [dispatchedOps EXCEPT
            ![s] = @ \cup {[type |-> "CLOSE", region |-> r]}]
       \* Pending reports, RS-side state, and server liveness unchanged.
       /\ UNCHANGED <<pendingReports, rsVars, serverState>>

\* Confirm that the region closed successfully by consuming a CLOSED
\* report from pendingReports.
\* Pre: UNASSIGN or MOVE procedure in CONFIRM_CLOSED state, region is
\*      CLOSING, and a matching CLOSED report exists in pendingReports
\*      from an ONLINE server.
\* Post: region transitions to CLOSED, location cleared.
\*       For UNASSIGN: procedure cleared.
\*       For MOVE: procedure kept, advanced to GET_ASSIGN_CANDIDATE
\*       with targetServer cleared so a new destination can be chosen.
\*       Report consumed from pendingReports.
\*
\* Source: TransitRegionStateProcedure.java confirmClosed() L409-446.
TRSPConfirmClosed(r) ==
    \* Guards: procedure is UNASSIGN or MOVE, in CONFIRM_CLOSED step, region
    \* is CLOSING, and a matching CLOSED report exists from an ONLINE server.
    /\ regionState[r].procType \in {"UNASSIGN", "MOVE"}
    /\ regionState[r].procStep = "CONFIRM_CLOSED"
    /\ regionState[r].state = "CLOSING"
    /\ \E rpt \in pendingReports :
         /\ rpt.region = r
         /\ rpt.code = "CLOSED"
         /\ serverState[rpt.server] = "ONLINE"
         \* Finalize region as CLOSED, clear location and target server.
         \* UNASSIGN: clear procedure to idle.
         \* MOVE: keep procedure, advance to GET_ASSIGN_CANDIDATE for re-open.
         /\ regionState' = [regionState EXCEPT
              ![r] = [state |-> "CLOSED", location |-> None,
                      procType |->
                        IF regionState[r].procType = "UNASSIGN"
                        THEN "NONE" ELSE regionState[r].procType,
                      procStep |->
                        IF regionState[r].procType = "UNASSIGN"
                        THEN "IDLE" ELSE "GET_ASSIGN_CANDIDATE",
                      targetServer |-> None,
                      retries |-> 0]]
         \* Persist CLOSED state and cleared location in META.
         /\ metaTable' = [metaTable EXCEPT
              ![r] = [state |-> "CLOSED", location |-> None]]
         \* Consume the matched report from the pending set.
         /\ pendingReports' = pendingReports \ {rpt}
         \* Dispatched ops, RS-side state, and server liveness unchanged.
         /\ UNCHANGED <<dispatchedOps, rsVars, serverState>>

---------------------------------------------------------------------------
(* Actions -- external events *)

\* Transition from CLOSED back to OFFLINE.
\* Used by RegionStateNode.offline() when a region is being taken fully
\* offline (e.g., table disable).
\* Pre: region is CLOSED with no procedure attached.
GoOffline(r) ==
    \* Guards: region is CLOSED and has no active procedure.
    /\ regionState[r].state = "CLOSED"
    /\ regionState[r].procType = "NONE"
    \* Move region to OFFLINE with cleared location (in-memory only).
    /\ regionState' = [regionState EXCEPT
         ![r].state = "OFFLINE",
         ![r].location = None]
    \* Meta is NOT updated: RegionStateNode.offline() (RSN.java L132-134)
    \* does not write to meta.  metaTable retains CLOSED; divergence is
    \* resolved on master restart.  See Appendix D.5.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars, serverState>>

\* A RegionServer crashes.  All regions on that server transition to
\* ABNORMALLY_CLOSED atomically, RS-side state and pending commands
\* are cleared, and the server is marked CRASHED.
\*
\* Pre: server is ONLINE and has at least one region assigned.
\* Post: serverState set to CRASHED, all regions with location=s
\*       transition to ABNORMALLY_CLOSED (location cleared, procedure
\*       fields preserved), RS-side state wiped, dispatchedOps cleared.
\*       Pending reports from s are retained (may have been sent
\*       before the crash) but will be dropped by DropStaleReport.
\*
\* Source: ServerManager.expireServer() L662-720,
\*         AssignmentManager.regionClosedAbnormally() L2320-2340.
ServerCrashAll(s) ==
    \* Guards: server is ONLINE and hosts at least one region.
    /\ serverState[s] = "ONLINE"
    /\ \E r \in Regions : regionState[r].location = s
    \* Mark the server as CRASHED.
    /\ serverState' = [serverState EXCEPT ![s] = "CRASHED"]
    \* All regions on this server become ABNORMALLY_CLOSED; procedure fields kept.
    /\ regionState' = [r \in Regions |->
         IF regionState[r].location = s
         THEN [state |-> "ABNORMALLY_CLOSED", location |-> None,
               procType |-> regionState[r].procType,
               procStep |-> regionState[r].procStep,
               targetServer |-> regionState[r].targetServer,
               retries |-> regionState[r].retries]
         ELSE regionState[r]]
    \* Persist ABNORMALLY_CLOSED and cleared location in META for affected regions.
    /\ metaTable' = [r \in Regions |->
         IF regionState[r].location = s
         THEN [state |-> "ABNORMALLY_CLOSED", location |-> None]
         ELSE metaTable[r]]
    \* Wipe all pending commands for the crashed server.
    /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
    \* Clear the crashed server's online region set.
    /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
    \* Pending reports retained (cleaned up later by DropStaleReport).
    /\ UNCHANGED <<pendingReports>>

\* A process supervisor (Kubernetes, systemd, etc.) restarts a crashed
\* RegionServer.  The restarted server is empty: no regions, no pending
\* commands, no RS-side state (all cleared atomically by ServerCrashAll
\* at crash time).  Any pending reports from the previous incarnation
\* are discarded (in real HBase, epoch mismatch rejects them).
\*
\* Design note -- RS epochs are NOT modeled explicitly.  Real HBase uses
\* ServerName (host + startcode) to distinguish incarnations. Stale
\* reports carry the old ServerName and are rejected.  Here the same
\* effect is achieved by atomic crash (ServerCrashAll clears RS state)
\* plus atomic restart (this action purges stale reports).  An explicit
\* epoch variable would only add value if crash or restart were
\* decomposed into non-atomic multi-step sequences.
\*
\* Pre: server is CRASHED.
\* Post: serverState set to ONLINE, pending reports from s discarded.
\*
\* Source: Environmental assumption -- process supervisor guarantees
\*         crashed processes are eventually restarted.
ServerRestart(s) ==
    \* Guard: server is CRASHED.
    /\ serverState[s] = "CRASHED"
    \* Bring the server back ONLINE.
    /\ serverState' = [serverState EXCEPT ![s] = "ONLINE"]
    \* Purge all stale pending reports from this server's prior incarnation.
    /\ pendingReports' = {rpt \in pendingReports : rpt.server # s}
    \* Region state, META, dispatched ops, and RS-side state unchanged.
    /\ UNCHANGED <<regionState, metaTable, dispatchedOps, rsVars>>

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
\* Source: TransitRegionStateProcedure.java serverCrashed() L566-586,
\*         closeRegion() L389-407 (else branch).
TRSPServerCrashed(r) ==
    \* Guards: region has an active procedure and is ABNORMALLY_CLOSED.
    /\ regionState[r].procType # "NONE"
    /\ regionState[r].state = "ABNORMALLY_CLOSED"
    \* Convert the procedure to ASSIGN at GET_ASSIGN_CANDIDATE, clear target.
    /\ regionState' = [regionState EXCEPT
         ![r].procType = "ASSIGN",
         ![r].procStep = "GET_ASSIGN_CANDIDATE",
         ![r].targetServer = None,
         ![r].retries = 0]
    \* META, RPC channels, RS-side state, and server liveness unchanged.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars, serverState>>

---------------------------------------------------------------------------
(* Actions -- stale report cleanup *)

\* Drop a report from a crashed server.  Models the "You are dead"
\* rejection path in reportRegionStateTransition() (AM.java L1277-1293).
\* Reports from crashed servers cannot be consumed by TRSPConfirmOpened,
\* TRSPConfirmClosed, or TRSPHandleFailedOpen (server ONLINE guard),
\* so this action cleans them up.
DropStaleReport ==
    \* Guard: a pending report exists from a CRASHED server.
    \E rpt \in pendingReports :
        /\ serverState[rpt.server] = "CRASHED"
        \* Discard the stale report.
        /\ pendingReports' = pendingReports \ {rpt}
        \* All other state variables unchanged.
        /\ UNCHANGED <<regionState, metaTable,
                       dispatchedOps, rsVars, serverState>>

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
\* Source: AssignRegionHandler.java process() L98-164 (success path).
RSOpen(s, r) ==
    \* Guards: server is ONLINE and an OPEN command for region r exists.
    /\ serverState[s] = "ONLINE"
    /\ \E cmd \in dispatchedOps[s] :
        /\ cmd.type = "OPEN"
        /\ cmd.region = r
        \* Consume the command from the server's dispatched ops queue.
        /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
        \* Add the region to the server's set of online regions.
        /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = @ \cup {r}]
        \* Send an OPENED report to the master for procedure confirmation.
        /\ pendingReports' = pendingReports \cup
             {[server |-> s, region |-> r, code |-> "OPENED"]}
        \* Master-side state and server liveness unchanged.
        /\ UNCHANGED <<regionState, metaTable, serverState>>

\* RS atomically receives an OPEN command but fails to open the
\* region.  The command is consumed and a FAILED_OPEN report is
\* produced.  The region is NOT added to rsOnlineRegions.
\*
\* Pre: Server is ONLINE, an OPEN command for region r exists in
\*      dispatchedOps[s].
\* Post: Command consumed, FAILED_OPEN report produced.
\*
\* Source: AssignRegionHandler.java process() L98-164 (failure path).
RSFailOpen(s, r) ==
    \* Guards: server is ONLINE and an OPEN command for region r exists.
    /\ serverState[s] = "ONLINE"
    /\ \E cmd \in dispatchedOps[s] :
        /\ cmd.type = "OPEN"
        /\ cmd.region = r
        \* Consume the command from the server's dispatched ops queue.
        /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
        \* Send a FAILED_OPEN report to the master for error handling.
        /\ pendingReports' = pendingReports \cup
             {[server |-> s, region |-> r, code |-> "FAILED_OPEN"]}
        \* Master-side state, online regions, and server liveness unchanged.
        /\ UNCHANGED <<regionState, metaTable, rsOnlineRegions, serverState>>

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
\* Source: UnassignRegionHandler.java process() L92-158 (success path).
RSClose(s, r) ==
    \* Guards: server is ONLINE and a CLOSE command for region r exists.
    /\ serverState[s] = "ONLINE"
    /\ \E cmd \in dispatchedOps[s] :
        /\ cmd.type = "CLOSE"
        /\ cmd.region = r
        \* Consume the command from the server's dispatched ops queue.
        /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
        \* Remove the region from the server's set of online regions.
        /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = @ \ {r}]
        \* Send a CLOSED report to the master for procedure confirmation.
        /\ pendingReports' = pendingReports \cup
             {[server |-> s, region |-> r, code |-> "CLOSED"]}
        \* Master-side state and server liveness unchanged.
        /\ UNCHANGED <<regionState, metaTable, serverState>>

---------------------------------------------------------------------------
(* Next-state relation *)

Next ==
    \* -- ASSIGN path --
    \/ \E r \in Regions : TRSPCreate(r)
    \/ \E r \in Regions :
         \E s \in Servers : TRSPGetCandidate(r, s)
    \/ \E r \in Regions : TRSPDispatchOpen(r)
    \/ \E r \in Regions : TRSPConfirmOpened(r)
    \/ \E r \in Regions : TRSPHandleFailedOpen(r)
    \/ \E r \in Regions : TRSPGiveUpOpen(r)
    \/ \E r \in Regions : DispatchFail(r)
    \* -- UNASSIGN path --
    \/ \E r \in Regions : TRSPCreateUnassign(r)
    \* -- MOVE path --
    \/ \E r \in Regions : TRSPCreateMove(r)
    \/ \E r \in Regions : TRSPDispatchClose(r)
    \/ \E r \in Regions : TRSPConfirmClosed(r)
    \/ \E r \in Regions : DispatchFailClose(r)
    \* -- External events --
    \/ \E r \in Regions : GoOffline(r)
    \/ \E s \in Servers : ServerCrashAll(s)
    \/ \E s \in Servers : ServerRestart(s)
    \* -- Crash recovery --
    \/ \E r \in Regions : TRSPServerCrashed(r)
    \* -- Stale report cleanup --
    \/ DropStaleReport
    \* -- RS-side open handler --
    \/ \E s \in Servers : \E r \in Regions : RSOpen(s, r)
    \/ \E s \in Servers : \E r \in Regions : RSFailOpen(s, r)
    \* -- RS-side close handler --
    \/ \E s \in Servers : \E r \in Regions : RSClose(s, r)

---------------------------------------------------------------------------
(* Fairness *)

\* Weak fairness on all deterministic actions ensures forward progress.
\* Procedure-step actions, crash-recovery actions, and RS-side
\* processing are all deterministic once enabled and therefore receive
\* WF.  Non-deterministic environmental events (DispatchFail,
\* DispatchFailClose, ServerCrashAll, RSFailOpen, GoOffline) receive
\* no fairness -- they may occur but are not required to.
Fairness ==
    \* Creation actions
    /\ \A r \in Regions : WF_vars(TRSPCreate(r))
    /\ \A r \in Regions : WF_vars(TRSPCreateUnassign(r))
    /\ \A r \in Regions : WF_vars(TRSPCreateMove(r))
    /\ \A s \in Servers : WF_vars(ServerRestart(s))
    \* Deterministic procedure steps
    /\ \A r \in Regions :
         \A s \in Servers : WF_vars(TRSPGetCandidate(r, s))
    /\ \A r \in Regions : WF_vars(TRSPDispatchOpen(r))
    /\ \A r \in Regions : WF_vars(TRSPConfirmOpened(r))
    /\ \A r \in Regions : WF_vars(TRSPHandleFailedOpen(r))
    /\ \A r \in Regions : WF_vars(TRSPGiveUpOpen(r))
    /\ \A r \in Regions : WF_vars(TRSPDispatchClose(r))
    /\ \A r \in Regions : WF_vars(TRSPConfirmClosed(r))
    \* Crash recovery
    /\ \A r \in Regions : WF_vars(TRSPServerCrashed(r))
    /\ WF_vars(DropStaleReport)
    \* RS-side processing
    /\ \A s \in Servers :
         \A r \in Regions : WF_vars(RSOpen(s, r))
    /\ \A s \in Servers :
         \A r \in Regions : WF_vars(RSClose(s, r))

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

\* Safety: no double assignment (region deployed on at most one server).
THEOREM Spec => []NoDoubleAssignment

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
    \A r \in Regions :
        regionState'[r].state # regionState[r].state
            => <<regionState[r].state, regionState'[r].state>> \in ValidTransition

============================================================================
