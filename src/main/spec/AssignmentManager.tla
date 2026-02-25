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
 * Each region carries a `procedure` field (master in-memory only, not
 * persisted to meta) that models the RegionStateNode.setProcedure() /
 * unsetProcedure() discipline: at most one procedure can be attached to
 * a region at any time.  The `procedure` field holds the procedure ID
 * (a natural number) when a TRSP is attached, or None when idle.
 *
 * Procedures are modeled as records in the `procedures` variable, indexed
 * by procedure ID.  ASSIGN, UNASSIGN, and MOVE follow TRSP state machines:
 *   ASSIGN:   GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED -> (removed)
 *   UNASSIGN: CLOSE -> CONFIRM_CLOSED -> (removed)
 *   MOVE:     CLOSE -> CONFIRM_CLOSED -> GET_ASSIGN_CANDIDATE -> OPEN
 *                -> CONFIRM_OPENED -> (removed)
 * MOVE is an UNASSIGN (close on current server) followed by an ASSIGN
 * (open on a new server), all within a single procedure.
 * If a procedure's region becomes ABNORMALLY_CLOSED (server crash), the
 * procedure converts to ASSIGN/GET_ASSIGN_CANDIDATE via the
 * serverCrashed() callback, modeling the TRSP's self-recovery path.
 *
 * Two RPC channels model the asynchronous communication:
 *   dispatchedOps:  master->RS command channel (per server)
 *   pendingReports: RS->master report channel
 * The ASSIGN path dispatches OPEN commands to dispatchedOps and waits
 * for OPENED reports in pendingReports.  RS-side actions atomically
 * consume commands from dispatchedOps, update rsOnlineRegions, and
 * produce reports in pendingReports.  RSOpen models the success path
 * (consume command, add to rsOnlineRegions, produce OPENED report);
 * RSFailOpen models the failure path (consume command, produce
 * FAILED_OPEN report).  TRSPConfirmOpened requires consuming a matching
 * report before advancing.  The UNASSIGN path follows the same pattern:
 * TRSPDispatchClose dispatches CLOSE commands, RSClose atomically
 * consumes the command, removes from rsOnlineRegions, and produces
 * a CLOSED report consumed by TRSPConfirmClosed.  DispatchFail and
 * DispatchFailClose model non-deterministic RPC failure for each path.
 *
 * RS-side receive and complete steps are merged into single atomic
 * actions because the intermediate RS state (command consumed but not
 * yet reported) is not observable by the master and produces the same
 * crash-recovery outcome as the pre-receive state.  This eliminates
 * the rsTransitions variable, significantly reducing the state space
 * without losing safety-relevant interleavings.
 *
 * Server liveness is tracked by serverState.  ServerCrashAll(s) models
 * an RS crash as a per-server event: all regions on the crashed server
 * transition to ABNORMALLY_CLOSED atomically, RS-side state and pending
 * commands are cleared, and the server is marked CRASHED.  RS-side
 * actions and report consumption are guarded by server liveness.
 * Reports from crashed servers are dropped by DropStaleReport.
 *
 * When a region fails to open on an RS, the FAILED_OPEN report is
 * consumed by TRSPHandleFailedOpen, which resets the procedure to
 * GET_ASSIGN_CANDIDATE for retry on a different server.
 *
 * Currently scoped to the core assign/unassign lifecycle:
 *   OFFLINE, OPENING, OPEN, CLOSING, CLOSED, FAILED_OPEN, ABNORMALLY_CLOSED
 *
 * Split/merge states (SPLITTING, SPLIT, MERGING, MERGED, SPLITTING_NEW,
 * MERGING_NEW) are deferred to Phase 6 (Iterations 20-26).
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
    None       \* Sentinel model value for "no server/procedure assigned"

ASSUME None \notin Servers

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

\* TRSP internal states used in the procedures variable.
\* ASSIGN path:   GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED -> (removed)
\* UNASSIGN path: CLOSE -> CONFIRM_CLOSED -> (removed)
\* MOVE path:     CLOSE -> CONFIRM_CLOSED -> GET_ASSIGN_CANDIDATE -> OPEN
\*                   -> CONFIRM_OPENED -> (removed)
TRSPState == {"GET_ASSIGN_CANDIDATE", "OPEN", "CONFIRM_OPENED",
              "CLOSE", "CONFIRM_CLOSED"}

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
    \*   [state : State,
    \*    location : Servers \cup {None},
    \*    procedure : Nat \cup {None}]
    \* for each r in Regions.  This is volatile master in-memory state.
    \*
    \* The `procedure` field holds the procedure ID (a natural number)
    \* when a TRSP or unassign procedure is attached, or None when
    \* no procedure is attached.
    \*
    \* Source: RegionStateNode.java setProcedure() L213-218,
    \*         unsetProcedure() L220-224.

VARIABLE metaTable
    \* metaTable[r] is a record [state : State, location : Servers \cup {None}]
    \* for each r in Regions.  This is persistent state in hbase:meta.
    \* Survives master crash; regionState does not.
    \*
    \* The procedure field is NOT persisted to meta -- procedures are
    \* master in-memory state, recovered from ProcedureStore on restart.

VARIABLE procedures
    \* procedures is a function from a subset of Nat to procedure records.
    \* Each record is:
    \*   [type : {"ASSIGN", "UNASSIGN", "MOVE"},
    \*    trspState : TRSPState,
    \*    region : Regions,
    \*    targetServer : Servers \cup {None}]
    \*
    \* Procedures are created when a region operation begins and removed
    \* when it completes.  DOMAIN procedures is the set of active IDs.
    \*
    \* Source: TransitRegionStateProcedure.java executeFromState() L483-531.

VARIABLE nextProcId
    \* nextProcId is a natural number >= 1, monotonically increasing.
    \* Each new procedure is assigned nextProcId as its ID, then
    \* nextProcId is incremented.

VARIABLE dispatchedOps
    \* dispatchedOps[s] is a set of command records pending delivery to
    \* server s.  Each record is:
    \*   [type : CommandType, region : Regions, procId : Nat]
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
    \*   [server : Servers, region : Regions, code : ReportCode,
    \*    procId : Nat]
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

vars == <<regionState, metaTable, procedures, nextProcId,
          dispatchedOps, pendingReports, rsOnlineRegions, serverState>>

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == <<dispatchedOps, pendingReports>>

\* Shorthand for the RS-side variable (used in UNCHANGED clauses).
rsVars == <<rsOnlineRegions>>

---------------------------------------------------------------------------
(* Helper operators *)

\* Extend a function's domain by one key.
AddProc(procs, pid, rec) ==
    [p \in (DOMAIN procs) \cup {pid} |-> IF p = pid THEN rec ELSE procs[p]]

\* Restrict a function's domain by removing one key.
RemoveProc(procs, pid) ==
    [p \in (DOMAIN procs) \ {pid} |-> procs[p]]

---------------------------------------------------------------------------
(* Type invariant *)

TypeOK ==
    \* For every region, check all three fields of its in-memory state record.
    /\ \A r \in Regions :
         \* Region lifecycle state must be one of the defined states.
         /\ regionState[r].state \in State
         \* Region location is either an assigned server or None.
         /\ regionState[r].location \in Servers \cup {None}
         \* Region's owning procedure id is None or an existing procedure key.
         /\ regionState[r].procedure \in {None} \cup DOMAIN procedures
    \* META table maps every region to a persistent (state, location) record.
    /\ metaTable \in [Regions -> [state : State,
                                  location : Servers \cup {None}]]
    \* Procedure id counter is a natural number.
    /\ nextProcId \in Nat
    \* Procedure id counter is always at least 1.
    /\ nextProcId >= 1
    \* All existing procedure ids are in the range 1..(nextProcId - 1).
    /\ DOMAIN procedures \subseteq 1..(nextProcId - 1)
    \* For every active procedure, check all four fields of its record.
    /\ \A pid \in DOMAIN procedures :
         \* Procedure type is ASSIGN, UNASSIGN, or MOVE.
         /\ procedures[pid].type \in {"ASSIGN", "UNASSIGN", "MOVE"}
         \* Procedure step is one of the defined transition-procedure states.
         /\ procedures[pid].trspState \in TRSPState
         \* Procedure's target region is a valid region.
         /\ procedures[pid].region \in Regions
         \* Procedure's target server is a server or None.
         /\ procedures[pid].targetServer \in Servers \cup {None}
    \* Each server has a set of dispatched operation commands (open/close).
    /\ dispatchedOps \in [Servers -> SUBSET
         [type : CommandType, region : Regions, procId : Nat]]
    \* Pending reports are a set of region-transition outcome messages.
    /\ pendingReports \subseteq
         [server : Servers, region : Regions, code : ReportCode,
          procId : Nat]
    /\ rsOnlineRegions \in [Servers -> SUBSET Regions]
    \* Server liveness state is either ONLINE or CRASHED.
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
\* the fields that meta tracks (state and location).  The procedure
\* field is master in-memory only and is not compared.
\*
\* True by construction in this iteration because all actions update both
\* atomically.  Becomes non-trivial when master crash is introduced
\* (Iteration 19): metaTable survives but regionState is lost and must
\* be rebuilt.  Later (Iteration 22), relaxed to allow SPLITTING_NEW/
\* MERGING_NEW in memory while meta says CLOSED.
MetaConsistency ==
    \A r \in Regions :
        /\ metaTable[r].state = regionState[r].state
        /\ metaTable[r].location = regionState[r].location

\* No double assignment: a region is never simultaneously deployed on
\* two (or more) RegionServers.  This is the foundational safety
\* property of the assignment manager.  It is non-trivial because the
\* master and RS views of region state evolve asynchronously, and
\* server crashes create interleavings where stale RS state could
\* persist.
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
\*   removed at OPEN.
\* UNASSIGN attaches during OPEN and the region transitions through CLOSING
\*   before the procedure is removed at CLOSED.
\* MOVE attaches during OPEN and drives the region through CLOSING, CLOSED,
\*   OPENING before being removed at OPEN.  It spans the full close+assign
\*   lifecycle, so its valid states are the union of UNASSIGN and ASSIGN
\*   states plus CLOSED (where the procedure transitions from the close
\*   phase to the assign phase).
\* Any type may be found on an ABNORMALLY_CLOSED region if a server
\*   crash occurs while the procedure is in flight (crash recovery is
\*   modeled in later iterations).
\*
\* Source: RegionStateNode.java setProcedure() L213-218,
\*         unsetProcedure() L220-224.
LockExclusivity ==
    \A r \in Regions :
        LET pid == regionState[r].procedure IN
        pid # None =>
            /\ pid \in DOMAIN procedures
            /\ \/ /\ procedures[pid].type = "ASSIGN"
                  /\ regionState[r].state \in {"OFFLINE", "CLOSED",
                                                "ABNORMALLY_CLOSED",
                                                "FAILED_OPEN", "OPENING"}
               \/ /\ procedures[pid].type = "UNASSIGN"
                  /\ regionState[r].state \in {"OPEN", "CLOSING",
                                                "ABNORMALLY_CLOSED"}
               \/ /\ procedures[pid].type = "MOVE"
                  /\ regionState[r].state \in {"OPEN", "CLOSING", "CLOSED",
                                                "ABNORMALLY_CLOSED",
                                                "FAILED_OPEN", "OPENING"}

\* Bidirectional consistency between region->procedure and procedure->region.
\* Every attached procedure ID refers to an active procedure whose region
\* field points back, and every active procedure's region has that procedure
\* attached.
ProcedureConsistency ==
    /\ \A r \in Regions :
         regionState[r].procedure # None =>
            /\ regionState[r].procedure \in DOMAIN procedures
            /\ procedures[regionState[r].procedure].region = r
    /\ \A pid \in DOMAIN procedures :
         regionState[procedures[pid].region].procedure = pid

\* When a region is stably OPEN (no procedure attached), the RS hosting
\* it must also consider it online.  This cross-checks the master's view
\* (regionState) against the RS's view (rsOnlineRegions).
\*
\* The invariant holds because TRSPConfirmOpened (which sets OPEN and
\* removes the procedure) requires an OPENED report, which is only
\* produced by RSOpen after adding the region to rsOnlineRegions.
RSMasterAgreement ==
    \A r \in Regions :
        regionState[r].state = "OPEN" /\ regionState[r].procedure = None
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

StateConstraint == nextProcId <= 7

StateConstraintSmall == nextProcId <= 5

\* Deeper bound for simulation mode, where cost is proportional to
\* trace length (not state-space size).  Allows up to 14 completed
\* procedures (~5 per region with 3 regions): enough for multiple
\* full assign/unassign/crash/reassign cycles.
StateConstraintDeep == nextProcId <= 15

\* Limit the number of simultaneously crashed servers.  Used to
\* prevent cascading-crash state space explosion while still
\* exercising single-server crash paths.
CrashConstraint ==
    Cardinality({s \in Servers : serverState[s] = "CRASHED"}) <= 1

\* Symmetry reduction: regions and servers are interchangeable.
\* TLC explores one representative per equivalence class, reducing
\* the state space by up to |Regions|! * |Servers|! (36x for 3r/3s).
Symmetry == Permutations(Regions) \union Permutations(Servers)

---------------------------------------------------------------------------
(* Initial state *)

Init ==
    \* Every region starts OFFLINE with no server and no owning procedure.
    /\ regionState = [r \in Regions |->
         [state |-> "OFFLINE", location |-> None, procedure |-> None]]
    \* META table mirrors the initial in-memory state: all regions OFFLINE.
    /\ metaTable = [r \in Regions |->
         [state |-> "OFFLINE", location |-> None]]
    \* No procedures are active at startup.
    /\ procedures = <<>>
    \* First procedure id to be allocated is 1.
    /\ nextProcId = 1
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
\* Post: procedure created in GET_ASSIGN_CANDIDATE state, attached to
\*       region.  Region state is NOT changed yet -- the TRSP will
\*       drive transitions in subsequent steps.
\*
\* Source: TransitRegionStateProcedure.java queueAssign() L246-278.
TRSPCreate(r) ==
    \* Allocate the next available procedure id.
    LET pid == nextProcId IN
    \* Guards: region is in an assignable state and has no owning procedure.
    /\ regionState[r].state \in {"OFFLINE", "CLOSED",
                                  "ABNORMALLY_CLOSED", "FAILED_OPEN"}
    /\ regionState[r].procedure = None
    \* Attach the new procedure id to the region's in-memory record.
    /\ regionState' = [regionState EXCEPT ![r].procedure = pid]
    \* Create the procedure record in its initial TRSP state.
    /\ procedures' = AddProc(procedures, pid,
                            [type |-> "ASSIGN",
                             trspState |-> "GET_ASSIGN_CANDIDATE",
                             region |-> r,
                             targetServer |-> None])
    \* Advance the global procedure id counter.
    /\ nextProcId' = nextProcId + 1
    \* META table, RPC channels, RS-side state, and server state unchanged.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars, serverState>>

\* Choose a target server for the ASSIGN procedure.
\* Pre: TRSP is in GET_ASSIGN_CANDIDATE state, target server is ONLINE.
\* Post: targetServer set, TRSP advances to OPEN state.
\*
\* Source: TransitRegionStateProcedure.java executeFromState() L483-496,
\*         queueAssign() L246-278 (getDestinationServer).
TRSPGetCandidate(pid, s) ==
    \* Guards: procedure exists, is ASSIGN or MOVE, is in
    \* GET_ASSIGN_CANDIDATE step, and the chosen server is ONLINE.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type \in {"ASSIGN", "MOVE"}
    /\ procedures[pid].trspState = "GET_ASSIGN_CANDIDATE"
    /\ serverState[s] = "ONLINE"
    \* Record the chosen server and advance the procedure to the OPEN step.
    /\ procedures' = [procedures EXCEPT ![pid].targetServer = s,
                                        ![pid].trspState = "OPEN"]
    \* No other state variables change in this step.
    /\ UNCHANGED <<regionState, metaTable, nextProcId, rpcVars, rsVars,
                   serverState>>

\* Dispatch the open command to the target server via RPC.
\* Pre: TRSP is in OPEN state with a target server.
\* Post: region transitions to OPENING with the target server as
\*       location, meta updated, open command added to
\*       dispatchedOps[targetServer], TRSP advances to CONFIRM_OPENED.
\*       The procedure now waits for an OPENED report from the RS.
\*
\* Source: TransitRegionStateProcedure.java openRegion() L293-311,
\*         RSProcedureDispatcher.java L200-367.
TRSPDispatchOpen(pid) ==
    \* Guards: procedure exists, is ASSIGN or MOVE, is in OPEN step,
    \* and has a target server.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type \in {"ASSIGN", "MOVE"}
    /\ procedures[pid].trspState = "OPEN"
    /\ procedures[pid].targetServer # None
    \* Bind the procedure's region and target server for readability.
    /\ LET r == procedures[pid].region
           s == procedures[pid].targetServer IN
       \* Transition the region to OPENING and record its assigned server.
       /\ regionState' = [regionState EXCEPT
            ![r].state = "OPENING",
            ![r].location = s]
       \* Persist the OPENING state and server assignment in META.
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "OPENING", location |-> s]]
       \* Advance the procedure to wait for confirmation (CONFIRM_OPENED).
       /\ procedures' = [procedures EXCEPT
            ![pid].trspState = "CONFIRM_OPENED"]
       \* Enqueue an OPEN command to the target server's dispatched ops.
       /\ dispatchedOps' = [dispatchedOps EXCEPT
            ![s] = @ \cup {[type |-> "OPEN", region |-> r, procId |-> pid]}]
       \* Procedure id counter, pending reports, RS-side state, and
       \* server state unchanged.
       /\ UNCHANGED <<nextProcId, pendingReports, rsVars, serverState>>

\* Confirm that the region opened successfully by consuming an OPENED
\* report from pendingReports.
\* Pre: TRSP is in CONFIRM_OPENED state, region is OPENING, and a
\*      matching OPENED report exists in pendingReports from an ONLINE
\*      server.
\* Post: region transitions to OPEN, procedure removed and detached,
\*       report consumed from pendingReports.
\*
\* RSOpen produces the OPENED reports consumed here, completing the
\* ASSIGN round-trip.
\*
\* Source: TransitRegionStateProcedure.java confirmOpened() L320-374.
TRSPConfirmOpened(pid) ==
    \* Guards: procedure exists, is ASSIGN or MOVE, is in CONFIRM_OPENED
    \* step, region is OPENING, and a matching OPENED report exists for
    \* this procedure from an ONLINE server.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type \in {"ASSIGN", "MOVE"}
    /\ procedures[pid].trspState = "CONFIRM_OPENED"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "OPENING"
       /\ \E rpt \in pendingReports :
            /\ rpt.region = r
            /\ rpt.code = "OPENED"
            /\ rpt.procId = pid
            /\ serverState[rpt.server] = "ONLINE"
            \* Finalize the region as OPEN, keep its server, detach procedure.
            /\ regionState' = [regionState EXCEPT
                 ![r] = [state |-> "OPEN",
                         location |-> regionState[r].location,
                         procedure |-> None]]
            \* Persist the OPEN state in META, preserving the location.
            /\ metaTable' = [metaTable EXCEPT
                 ![r] = [state |-> "OPEN",
                         location |-> metaTable[r].location]]
            \* Remove the completed procedure from the active set.
            /\ procedures' = RemoveProc(procedures, pid)
            \* Consume the matched report from the pending set.
            /\ pendingReports' = pendingReports \ {rpt}
            \* Procedure id counter, dispatched ops, RS-side state, and
            \* server state unchanged.
            /\ UNCHANGED <<nextProcId, dispatchedOps, rsVars, serverState>>

---------------------------------------------------------------------------
(* Actions -- failure path *)

\* Open command dispatch failed (non-deterministic RPC failure).
\* The command is removed from dispatchedOps without delivery and
\* the TRSP returns to GET_ASSIGN_CANDIDATE with forceNewPlan (i.e.,
\* targetServer is cleared so a fresh candidate will be chosen).
\* The region remains OPENING with its current location; the next
\* TRSPDispatchOpen will update the location to the new server.
\*
\* Pre: ASSIGN procedure in CONFIRM_OPENED state and the matching
\*      open command still exists in dispatchedOps (not yet consumed
\*      by an RS).
\* Post: command removed, TRSP reset to GET_ASSIGN_CANDIDATE.
\*
\* Source: RSProcedureDispatcher.java remoteCallFailed() L325-340.
DispatchFail(pid) ==
    \* Guards: procedure exists, is ASSIGN or MOVE, is in CONFIRM_OPENED
    \* step, has a target server, and the OPEN command is still in
    \* dispatchedOps.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type \in {"ASSIGN", "MOVE"}
    /\ procedures[pid].trspState = "CONFIRM_OPENED"
    /\ LET s == procedures[pid].targetServer
           r == procedures[pid].region IN
       /\ s # None
       \* Reconstruct the dispatched command and verify it is still queued.
       /\ LET cmd == [type |-> "OPEN", region |-> r, procId |-> pid] IN
          /\ cmd \in dispatchedOps[s]
          \* Remove the undelivered command from the server's queue.
          /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
       \* Reset the procedure to GET_ASSIGN_CANDIDATE with no target server.
       /\ procedures' = [procedures EXCEPT
            ![pid].trspState = "GET_ASSIGN_CANDIDATE",
            ![pid].targetServer = None]
       \* Region state, META, proc id counter, reports, RS state, and
       \* server state unchanged.
       /\ UNCHANGED <<regionState, metaTable, nextProcId, pendingReports,
                      rsVars, serverState>>

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
DispatchFailClose(pid) ==
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type \in {"UNASSIGN", "MOVE"}
    /\ procedures[pid].trspState = "CONFIRM_CLOSED"
    /\ LET s == procedures[pid].targetServer
           r == procedures[pid].region IN
       /\ s # None
       /\ LET cmd == [type |-> "CLOSE", region |-> r, procId |-> pid] IN
          /\ cmd \in dispatchedOps[s]
          /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
       /\ procedures' = [procedures EXCEPT
            ![pid].trspState = "CLOSE"]
       /\ UNCHANGED <<regionState, metaTable, nextProcId, pendingReports,
                      rsVars, serverState>>

\* Handle a FAILED_OPEN report from the RS by retrying the assignment.
\* Models the non-give-up path of confirmOpened() (TRSP L345-374):
\* the procedure resets to GET_ASSIGN_CANDIDATE with forceNewPlan,
\* allowing a fresh server to be chosen.  Region state and meta are
\* NOT changed (stays OPENING); the next TRSPDispatchOpen will
\* overwrite location and state.
\*
\* The give-up path (retries >= maxAttempts -> FAILED_OPEN) is
\* deferred to Iteration 12 when MaxRetries is added.
\*
\* Pre: ASSIGN procedure in CONFIRM_OPENED state, region is OPENING,
\*      and a matching FAILED_OPEN report exists from an ONLINE server.
\* Post: report consumed, TRSP reset to GET_ASSIGN_CANDIDATE.
\*
\* Source: TransitRegionStateProcedure.java confirmOpened() L345-374.
TRSPHandleFailedOpen(pid) ==
    \* Guards: procedure exists, is ASSIGN or MOVE, is in CONFIRM_OPENED
    \* step, region is OPENING, and a matching FAILED_OPEN report exists
    \* from an ONLINE server.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type \in {"ASSIGN", "MOVE"}
    /\ procedures[pid].trspState = "CONFIRM_OPENED"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "OPENING"
       /\ \E rpt \in pendingReports :
            /\ rpt.region = r
            /\ rpt.code = "FAILED_OPEN"
            /\ rpt.procId = pid
            /\ serverState[rpt.server] = "ONLINE"
            \* Reset procedure to pick a new server.
            /\ procedures' = [procedures EXCEPT
                 ![pid].trspState = "GET_ASSIGN_CANDIDATE",
                 ![pid].targetServer = None]
            \* Consume the matched report from the pending set.
            /\ pendingReports' = pendingReports \ {rpt}
            \* All other state variables unchanged.
            /\ UNCHANGED <<regionState, metaTable, nextProcId,
                           dispatchedOps, rsVars, serverState>>

---------------------------------------------------------------------------
(* Actions -- TRSP UNASSIGN path *)

\* Create a TRSP UNASSIGN procedure for an OPEN region.
\* Pre: region is OPEN with a location AND no procedure is attached.
\* Post: procedure created in CLOSE state, attached to region.
\*       Region state is NOT changed yet -- the TRSP will drive the
\*       OPEN -> CLOSING transition in the next step.
\*
\* Source: TransitRegionStateProcedure.java queueAssign() L246-278
\*         (UNASSIGN path), setProcedure() before closeRegion().
TRSPCreateUnassign(r) ==
    \* Allocate the next available procedure id.
    LET pid == nextProcId IN
    \* Guards: region is OPEN, has a location, and has no owning procedure.
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState[r].procedure = None
    \* Attach the new procedure id to the region's in-memory record.
    /\ regionState' = [regionState EXCEPT ![r].procedure = pid]
    \* Create an UNASSIGN procedure targeting the region's current server.
    /\ procedures' = AddProc(procedures, pid,
                            [type |-> "UNASSIGN",
                             trspState |-> "CLOSE",
                             region |-> r,
                             targetServer |-> regionState[r].location])
    \* Advance the global procedure id counter.
    /\ nextProcId' = nextProcId + 1
    \* META table, RPC channels, RS-side state, and server state unchanged.
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
\*      destination.  The balancer would never generate a move when
\*      only the hosting server is available (HMaster.move() L2406).
\* Post: MOVE procedure created in CLOSE state, attached to region.
\*       Region state is NOT changed yet -- the TRSP will drive the
\*       OPEN -> CLOSING transition via TRSPDispatchClose.
\*
\* Source: TransitRegionStateProcedure.java TransitionType.MOVE L160-162,
\*         queueAssign() L246-278 (MOVE path).
TRSPCreateMove(r) ==
    LET pid == nextProcId IN
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState[r].procedure = None
    /\ \E s \in Servers : s # regionState[r].location
                           /\ serverState[s] = "ONLINE"
    /\ regionState' = [regionState EXCEPT ![r].procedure = pid]
    /\ procedures' = AddProc(procedures, pid,
                            [type |-> "MOVE",
                             trspState |-> "CLOSE",
                             region |-> r,
                             targetServer |-> regionState[r].location])
    /\ nextProcId' = nextProcId + 1
    /\ UNCHANGED <<metaTable, rpcVars, rsVars, serverState>>

\* Dispatch the close command to the target server via RPC.
\* Pre: UNASSIGN or MOVE procedure in CLOSE state, region is OPEN or CLOSING
\*      (CLOSING on retry after DispatchFailClose).
\* Post: region transitions to CLOSING (no-op if already CLOSING),
\*       meta updated, close command added to dispatchedOps[targetServer],
\*       TRSP advances to CONFIRM_CLOSED.
\*
\* Source: TransitRegionStateProcedure.java closeRegion() L389-407,
\*         RSProcedureDispatcher.java L200-367.
TRSPDispatchClose(pid) ==
    \* Guards: procedure exists, is UNASSIGN or MOVE, is in CLOSE step,
    \* has a target server, and the region is OPEN or CLOSING (CLOSING on retry).
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type \in {"UNASSIGN", "MOVE"}
    /\ procedures[pid].trspState = "CLOSE"
    /\ procedures[pid].targetServer # None
    \* Bind the procedure's region and target server for readability.
    /\ LET r == procedures[pid].region
           s == procedures[pid].targetServer IN
       /\ regionState[r].state \in {"OPEN", "CLOSING"}
       \* Transition region to CLOSING (no-op if already CLOSING on retry).
       /\ regionState' = [regionState EXCEPT
            ![r].state = "CLOSING"]
       \* Persist CLOSING state in META, preserving the location.
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "CLOSING",
                    location |-> metaTable[r].location]]
       \* Advance the procedure to wait for close confirmation.
       /\ procedures' = [procedures EXCEPT
            ![pid].trspState = "CONFIRM_CLOSED"]
       \* Enqueue a CLOSE command to the target server's dispatched ops.
       /\ dispatchedOps' = [dispatchedOps EXCEPT
            ![s] = @ \cup {[type |-> "CLOSE", region |-> r, procId |-> pid]}]
       \* Proc id counter, pending reports, RS-side state, and server
       \* state unchanged.
       /\ UNCHANGED <<nextProcId, pendingReports, rsVars, serverState>>

\* Confirm that the region closed successfully by consuming a CLOSED
\* report from pendingReports.
\* Pre: UNASSIGN or MOVE procedure in CONFIRM_CLOSED state, region is
\*      CLOSING, and a matching CLOSED report exists in pendingReports
\*      from an ONLINE server.
\* Post: region transitions to CLOSED, location cleared.
\*       For UNASSIGN: procedure removed and detached.
\*       For MOVE: procedure kept, advanced to GET_ASSIGN_CANDIDATE
\*       with targetServer cleared so a new destination can be chosen.
\*       Report consumed from pendingReports.
\*
\* RSClose produces the CLOSED reports consumed here.  For UNASSIGN
\* this completes the round-trip; for MOVE the procedure continues
\* into the assign phase.
\*
\* Source: TransitRegionStateProcedure.java confirmClosed() L409-446.
TRSPConfirmClosed(pid) ==
    \* Guards: procedure exists, is UNASSIGN or MOVE, is in
    \* CONFIRM_CLOSED step, region is CLOSING, and a matching CLOSED
    \* report exists for this procedure from an ONLINE server.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type \in {"UNASSIGN", "MOVE"}
    /\ procedures[pid].trspState = "CONFIRM_CLOSED"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "CLOSING"
       /\ \E rpt \in pendingReports :
            /\ rpt.region = r
            /\ rpt.code = "CLOSED"
            /\ rpt.procId = pid
            /\ serverState[rpt.server] = "ONLINE"
            \* Region becomes CLOSED with location cleared.
            \* UNASSIGN: detach procedure.  MOVE: keep procedure attached.
            /\ regionState' = [regionState EXCEPT
                 ![r] = [state |-> "CLOSED", location |-> None,
                         procedure |->
                           IF procedures[pid].type = "UNASSIGN"
                           THEN None ELSE pid]]
            \* Persist the CLOSED state and cleared location in META.
            /\ metaTable' = [metaTable EXCEPT
                 ![r] = [state |-> "CLOSED", location |-> None]]
            \* UNASSIGN: remove completed procedure.
            \* MOVE: advance to GET_ASSIGN_CANDIDATE, clear targetServer.
            /\ procedures' =
                 IF procedures[pid].type = "UNASSIGN"
                 THEN RemoveProc(procedures, pid)
                 ELSE [procedures EXCEPT
                         ![pid].trspState = "GET_ASSIGN_CANDIDATE",
                         ![pid].targetServer = None]
            \* Consume the matched report from the pending set.
            /\ pendingReports' = pendingReports \ {rpt}
            \* Proc id counter, dispatched ops, RS-side state, and server
            \* state unchanged.
            /\ UNCHANGED <<nextProcId, dispatchedOps, rsVars, serverState>>

---------------------------------------------------------------------------
(* Actions -- external events *)

\* Transition from CLOSED back to OFFLINE.
\* Used by RegionStateNode.offline() when a region is being taken fully
\* offline (e.g., table disable).
\* Pre: region is CLOSED with no procedure attached.
GoOffline(r) ==
    \* Guards: region is CLOSED and has no owning procedure.
    /\ regionState[r].state = "CLOSED"
    /\ regionState[r].procedure = None
    \* Move region to OFFLINE with no location or procedure.
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "OFFLINE", location |-> None,
                 procedure |-> None]]
    \* Persist the OFFLINE state and cleared location in META.
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "OFFLINE", location |-> None]]
    \* Procedures, proc id counter, RPC channels, RS-side state, and
    \* server state unchanged.
    /\ UNCHANGED <<procedures, nextProcId, rpcVars, rsVars, serverState>>

\* A RegionServer crashes.  All regions on that server transition to
\* ABNORMALLY_CLOSED atomically, RS-side state and pending commands
\* are cleared, and the server is marked CRASHED.
\*
\* Pre: server is ONLINE and has at least one region assigned.
\* Post: serverState set to CRASHED, all regions with location=s
\*       transition to ABNORMALLY_CLOSED (location cleared, procedure
\*       preserved), RS-side state wiped, dispatchedOps cleared.
\*       Pending reports from s are retained (may have been sent
\*       before the crash) but will be dropped by DropStaleReport.
\*
\* Source: ServerManager.expireServer() L662-720,
\*         AssignmentManager.regionClosedAbnormally() L2320-2340.
ServerCrashAll(s) ==
    \* Guards: server is ONLINE and has at least one region.
    /\ serverState[s] = "ONLINE"
    /\ \E r \in Regions : regionState[r].location = s
    \* Mark the server as CRASHED.
    /\ serverState' = [serverState EXCEPT ![s] = "CRASHED"]
    \* All regions located on s transition to ABNORMALLY_CLOSED.
    /\ regionState' = [r \in Regions |->
         IF regionState[r].location = s
         THEN [state |-> "ABNORMALLY_CLOSED", location |-> None,
               procedure |-> regionState[r].procedure]
         ELSE regionState[r]]
    \* Persist ABNORMALLY_CLOSED in META for affected regions.
    /\ metaTable' = [r \in Regions |->
         IF regionState[r].location = s
         THEN [state |-> "ABNORMALLY_CLOSED", location |-> None]
         ELSE metaTable[r]]
    \* Clear all pending commands for the crashed server.
    /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
    \* Clear RS-side state for the crashed server.
    /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
    \* Procedures and proc id counter unchanged; reports retained.
    /\ UNCHANGED <<procedures, nextProcId, pendingReports>>

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
\* SCP machinery (Iterations 14-16) orchestrates WHEN this callback
\* fires; this action models WHAT happens to the TRSP.
\*
\* Source: TransitRegionStateProcedure.java serverCrashed() L566-586,
\*         closeRegion() L389-407 (else branch).
TRSPServerCrashed(pid) ==
    \* Guards: procedure exists and its region is ABNORMALLY_CLOSED.
    /\ pid \in DOMAIN procedures
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "ABNORMALLY_CLOSED"
       \* Convert the procedure to ASSIGN at GET_ASSIGN_CANDIDATE, clear target.
       /\ procedures' = [procedures EXCEPT
            ![pid].type = "ASSIGN",
            ![pid].trspState = "GET_ASSIGN_CANDIDATE",
            ![pid].targetServer = None]
       \* Region state, META, proc id counter, RPC, RS state, and server
       \* state unchanged.
       /\ UNCHANGED <<regionState, metaTable, nextProcId, rpcVars, rsVars,
                      serverState>>

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
        /\ UNCHANGED <<regionState, metaTable, procedures, nextProcId,
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
             {[server |-> s, region |-> r, code |-> "OPENED",
               procId |-> cmd.procId]}
        \* Master-side state and server liveness unchanged.
        /\ UNCHANGED <<regionState, metaTable, procedures, nextProcId,
                       serverState>>

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
             {[server |-> s, region |-> r, code |-> "FAILED_OPEN",
               procId |-> cmd.procId]}
        \* Master-side state, online regions, and server liveness unchanged.
        /\ UNCHANGED <<regionState, metaTable, procedures, nextProcId,
                       rsOnlineRegions, serverState>>

---------------------------------------------------------------------------
(* Actions -- RS-side close handler *)

\* RS atomically receives a CLOSE command, closes the region, removes
\* it from rsOnlineRegions, and reports CLOSED to the master.  Merges
\* the former RSReceiveClose + RSCompleteClose into a single action
\* (same rationale as RSOpen — see RS-side open handler comment).
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
             {[server |-> s, region |-> r, code |-> "CLOSED",
               procId |-> cmd.procId]}
        \* Master-side state and server liveness unchanged.
        /\ UNCHANGED <<regionState, metaTable, procedures, nextProcId,
                       serverState>>

---------------------------------------------------------------------------
(* Next-state relation *)

Next ==
    \* -- ASSIGN path --
    \* Start an ASSIGN procedure for an eligible region.
    \/ \E r \in Regions : TRSPCreate(r)
    \* Pick a target server for an ASSIGN procedure.
    \/ \E pid \in DOMAIN procedures :
         \E s \in Servers : TRSPGetCandidate(pid, s)
    \* Dispatch an OPEN command to the target server.
    \/ \E pid \in DOMAIN procedures : TRSPDispatchOpen(pid)
    \* Confirm a successful open via an OPENED report.
    \/ \E pid \in DOMAIN procedures : TRSPConfirmOpened(pid)
    \* Handle a failed open by retrying with a new server.
    \/ \E pid \in DOMAIN procedures : TRSPHandleFailedOpen(pid)
    \* Handle open RPC dispatch failure; retry with a new candidate.
    \/ \E pid \in DOMAIN procedures : DispatchFail(pid)
    \* -- UNASSIGN path --
    \* Start an UNASSIGN procedure for an OPEN region.
    \/ \E r \in Regions : TRSPCreateUnassign(r)
    \* -- MOVE path --
    \* Start a MOVE procedure for an OPEN region.
    \/ \E r \in Regions : TRSPCreateMove(r)
    \* Dispatch a CLOSE command to the target server.
    \/ \E pid \in DOMAIN procedures : TRSPDispatchClose(pid)
    \* Confirm a successful close via a CLOSED report.
    \/ \E pid \in DOMAIN procedures : TRSPConfirmClosed(pid)
    \* Handle close RPC dispatch failure; retry.
    \/ \E pid \in DOMAIN procedures : DispatchFailClose(pid)
    \* -- External events --
    \* Transition a CLOSED region to OFFLINE (e.g. table disable).
    \/ \E r \in Regions : GoOffline(r)
    \* Crash an ONLINE server with at least one region.
    \/ \E s \in Servers : ServerCrashAll(s)
    \* -- Crash recovery --
    \* Convert a stranded procedure on an ABNORMALLY_CLOSED region to ASSIGN.
    \/ \E pid \in DOMAIN procedures : TRSPServerCrashed(pid)
    \* -- Stale report cleanup --
    \* Drop a report from a crashed server.
    \/ DropStaleReport
    \* -- RS-side open handler --
    \* RS atomically receives, opens, and reports OPENED.
    \/ \E s \in Servers : \E r \in Regions : RSOpen(s, r)
    \* RS atomically receives but fails to open; reports FAILED_OPEN.
    \/ \E s \in Servers : \E r \in Regions : RSFailOpen(s, r)
    \* -- RS-side close handler --
    \* RS atomically receives, closes, and reports CLOSED.
    \/ \E s \in Servers : \E r \in Regions : RSClose(s, r)

---------------------------------------------------------------------------
(* Fairness *)

\* Weak fairness on region-level creation actions ensures forward
\* progress.  Fairness on procedure-step actions (TRSPGetCandidate,
\* TRSPDispatchOpen, TRSPConfirmOpened, TRSPClose, TRSPConfirmClosed)
\* is not expressed here because procedure IDs are dynamically
\* allocated; full fairness is deferred to Iteration 27.
Fairness ==
    /\ \A r \in Regions : WF_vars(TRSPCreate(r))
    /\ \A r \in Regions : WF_vars(TRSPCreateUnassign(r))
    /\ \A r \in Regions : WF_vars(TRSPCreateMove(r))

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

\* Safety: procedure<->region bidirectional consistency.
THEOREM Spec => []ProcedureConsistency

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
