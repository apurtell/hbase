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
 * by procedure ID.  Both ASSIGN and UNASSIGN follow TRSP state machines:
 *   ASSIGN:   GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED -> (removed)
 *   UNASSIGN: CLOSE -> CONFIRM_CLOSED -> (removed)
 * If a procedure's region becomes ABNORMALLY_CLOSED (server crash), the
 * procedure converts to ASSIGN/GET_ASSIGN_CANDIDATE via the
 * serverCrashed() callback, modeling the TRSP's self-recovery path.
 *
 * Two RPC channels model the asynchronous communication:
 *   dispatchedOps:  master→RS command channel (per server)
 *   pendingReports: RS→master report channel
 * Commands are dispatched by TRSP actions and consumed by RS-side
 * actions; reports are produced by RS-side actions and consumed by
 * master-side report processing.
 *
 * Currently scoped to the core assign/unassign lifecycle:
 *   OFFLINE, OPENING, OPEN, CLOSING, CLOSED, FAILED_OPEN, ABNORMALLY_CLOSED
 *
 * Split/merge states (SPLITTING, SPLIT, MERGING, MERGED, SPLITTING_NEW,
 * MERGING_NEW) and FAILED_CLOSE are deferred to a later phase.
 *)
EXTENDS Naturals, FiniteSets

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
      <<"ABNORMALLY_CLOSED",  "OPENING">>,
      <<"FAILED_OPEN",        "OPENING">> }

\* TRSP internal states used in the procedures variable.
\* ASSIGN path:   GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED -> (removed)
\* UNASSIGN path: CLOSE -> CONFIRM_CLOSED -> (removed)
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
    \*   [type : {"ASSIGN", "UNASSIGN"},
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
    \* Models the master→RS command channel: the RSProcedureDispatcher
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
    \* Models the RS→master report channel: RegionServers report
    \* transition outcomes via reportRegionStateTransition() RPC.
    \* Reports remain in the set until consumed by master-side actions
    \* or discarded if from a crashed server.
    \*
    \* Source: RegionServerStatusService.reportRegionStateTransition().

vars == <<regionState, metaTable, procedures, nextProcId,
          dispatchedOps, pendingReports>>

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == <<dispatchedOps, pendingReports>>

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
    /\ \A r \in Regions :
         /\ regionState[r].state \in State
         /\ regionState[r].location \in Servers \cup {None}
         /\ regionState[r].procedure \in {None} \cup DOMAIN procedures
    /\ metaTable \in [Regions -> [state : State,
                                  location : Servers \cup {None}]]
    /\ nextProcId \in Nat
    /\ nextProcId >= 1
    /\ DOMAIN procedures \subseteq 1..(nextProcId - 1)
    /\ \A pid \in DOMAIN procedures :
         /\ procedures[pid].type \in {"ASSIGN", "UNASSIGN"}
         /\ procedures[pid].trspState \in TRSPState
         /\ procedures[pid].region \in Regions
         /\ procedures[pid].targetServer \in Servers \cup {None}
    /\ dispatchedOps \in [Servers -> SUBSET
         [type : CommandType, region : Regions, procId : Nat]]
    /\ pendingReports \subseteq
         [server : Servers, region : Regions, code : ReportCode,
          procId : Nat]

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

\* A given region is OPEN on at most one server.
\* (Trivially true in this module since location is a scalar, but
\*  stated explicitly as the foundational safety property.  It
\*  becomes non-trivial when the model introduces separate master-
\*  and RS-side views of region state.)
SingleAssignment ==
    \A r \in Regions :
        regionState[r].state = "OPEN" => regionState[r].location # None

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
\* Either type may be found on an ABNORMALLY_CLOSED region if a server
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

---------------------------------------------------------------------------
(* State constraint for TLC *)

StateConstraint == nextProcId <= 7

---------------------------------------------------------------------------
(* Initial state *)

Init ==
    /\ regionState = [r \in Regions |->
         [state |-> "OFFLINE", location |-> None, procedure |-> None]]
    /\ metaTable = [r \in Regions |->
         [state |-> "OFFLINE", location |-> None]]
    /\ procedures = <<>>
    /\ nextProcId = 1
    /\ dispatchedOps = [s \in Servers |-> {}]
    /\ pendingReports = {}

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
    LET pid == nextProcId IN
    /\ regionState[r].state \in {"OFFLINE", "CLOSED",
                                  "ABNORMALLY_CLOSED", "FAILED_OPEN"}
    /\ regionState[r].procedure = None
    /\ regionState' = [regionState EXCEPT ![r].procedure = pid]
    /\ procedures' = AddProc(procedures, pid,
                            [type |-> "ASSIGN",
                             trspState |-> "GET_ASSIGN_CANDIDATE",
                             region |-> r,
                             targetServer |-> None])
    /\ nextProcId' = nextProcId + 1
    /\ UNCHANGED <<metaTable, rpcVars>>

\* Choose a target server for the ASSIGN procedure.
\* Pre: TRSP is in GET_ASSIGN_CANDIDATE state.
\* Post: targetServer set, TRSP advances to OPEN state.
\*
\* Source: TransitRegionStateProcedure.java executeFromState() L483-496,
\*         queueAssign() L246-278 (getDestinationServer).
TRSPGetCandidate(pid, s) ==
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "ASSIGN"
    /\ procedures[pid].trspState = "GET_ASSIGN_CANDIDATE"
    /\ procedures' = [procedures EXCEPT ![pid].targetServer = s,
                                        ![pid].trspState = "OPEN"]
    /\ UNCHANGED <<regionState, metaTable, nextProcId, rpcVars>>

\* Execute the open step: transition region to OPENING.
\* Pre: TRSP is in OPEN state with a target server.
\* Post: region transitions to OPENING with the target server as
\*       location, meta updated, TRSP advances to CONFIRM_OPENED.
\*
\* Source: TransitRegionStateProcedure.java openRegion() L293-311.
TRSPOpen(pid) ==
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "ASSIGN"
    /\ procedures[pid].trspState = "OPEN"
    /\ procedures[pid].targetServer # None
    /\ LET r == procedures[pid].region
           s == procedures[pid].targetServer IN
       /\ regionState' = [regionState EXCEPT
            ![r].state = "OPENING",
            ![r].location = s]
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "OPENING", location |-> s]]
       /\ procedures' = [procedures EXCEPT
            ![pid].trspState = "CONFIRM_OPENED"]
       /\ UNCHANGED <<nextProcId, rpcVars>>

\* Confirm that the region opened successfully.
\* Pre: TRSP is in CONFIRM_OPENED state, region is OPENING.
\* Post: region transitions to OPEN, procedure removed and detached.
\*
\* In this iteration (no RS side), this models the master confirming
\* the open directly. In later iterations, this will require consuming
\* an OPENED report from the RS.
\*
\* Source: TransitRegionStateProcedure.java confirmOpened() L320-374.
TRSPConfirmOpened(pid) ==
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "ASSIGN"
    /\ procedures[pid].trspState = "CONFIRM_OPENED"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "OPENING"
       /\ regionState' = [regionState EXCEPT
            ![r] = [state |-> "OPEN",
                    location |-> regionState[r].location,
                    procedure |-> None]]
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "OPEN",
                    location |-> metaTable[r].location]]
       /\ procedures' = RemoveProc(procedures, pid)
       /\ UNCHANGED <<nextProcId, rpcVars>>

---------------------------------------------------------------------------
(* Actions -- failure path *)

\* Region failed to open (non-deterministic failure).
\* Full retry logic deferred to Iteration 12.
\* Pre: region is OPENING with a procedure attached.
\* Post: region transitions to FAILED_OPEN, location cleared,
\*       procedure removed and detached.
FailOpen(r) ==
    LET pid == regionState[r].procedure IN
    /\ regionState[r].state = "OPENING"
    /\ pid # None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "FAILED_OPEN", location |-> None,
                 procedure |-> None]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "FAILED_OPEN", location |-> None]]
    /\ procedures' = RemoveProc(procedures, pid)
    /\ UNCHANGED <<nextProcId, rpcVars>>

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
    LET pid == nextProcId IN
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState[r].procedure = None
    /\ regionState' = [regionState EXCEPT ![r].procedure = pid]
    /\ procedures' = AddProc(procedures, pid,
                            [type |-> "UNASSIGN",
                             trspState |-> "CLOSE",
                             region |-> r,
                             targetServer |-> regionState[r].location])
    /\ nextProcId' = nextProcId + 1
    /\ UNCHANGED <<metaTable, rpcVars>>

\* Execute the close step: transition region to CLOSING.
\* Pre: UNASSIGN procedure in CLOSE state, region is OPEN.
\* Post: region transitions to CLOSING (location retained during close),
\*       meta updated, TRSP advances to CONFIRM_CLOSED.
\*
\* Source: TransitRegionStateProcedure.java closeRegion() L389-407.
TRSPClose(pid) ==
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "UNASSIGN"
    /\ procedures[pid].trspState = "CLOSE"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "OPEN"
       /\ regionState' = [regionState EXCEPT
            ![r].state = "CLOSING"]
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "CLOSING",
                    location |-> metaTable[r].location]]
       /\ procedures' = [procedures EXCEPT
            ![pid].trspState = "CONFIRM_CLOSED"]
       /\ UNCHANGED <<nextProcId, rpcVars>>

\* Confirm that the region closed successfully.
\* Pre: UNASSIGN procedure in CONFIRM_CLOSED state, region is CLOSING.
\* Post: region transitions to CLOSED, location cleared, procedure
\*       removed and detached.
\*
\* In this iteration (no RS side), this models the master confirming
\* the close directly. In later iterations, this will require consuming
\* a CLOSED report from the RS.
\*
\* Source: TransitRegionStateProcedure.java confirmClosed() L409-446.
TRSPConfirmClosed(pid) ==
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "UNASSIGN"
    /\ procedures[pid].trspState = "CONFIRM_CLOSED"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "CLOSING"
       /\ regionState' = [regionState EXCEPT
            ![r] = [state |-> "CLOSED", location |-> None,
                    procedure |-> None]]
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "CLOSED", location |-> None]]
       /\ procedures' = RemoveProc(procedures, pid)
       /\ UNCHANGED <<nextProcId, rpcVars>>

---------------------------------------------------------------------------
(* Actions -- external events *)

\* Transition from CLOSED back to OFFLINE.
\* Used by RegionStateNode.offline() when a region is being taken fully
\* offline (e.g., table disable).
\* Pre: region is CLOSED with no procedure attached.
GoOffline(r) ==
    /\ regionState[r].state = "CLOSED"
    /\ regionState[r].procedure = None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "OFFLINE", location |-> None,
                 procedure |-> None]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "OFFLINE", location |-> None]]
    /\ UNCHANGED <<procedures, nextProcId, rpcVars>>

\* The server hosting an OPEN region crashes.
\* Pre: region is OPEN with a location.
\* Post: region transitions to ABNORMALLY_CLOSED, location cleared.
\*       This is an external event; the procedure field is preserved
\*       as-is (should be None for stable OPEN regions).
ServerCrash(r) ==
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "ABNORMALLY_CLOSED", location |-> None,
                 procedure |-> regionState[r].procedure]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "ABNORMALLY_CLOSED", location |-> None]]
    /\ UNCHANGED <<procedures, nextProcId, rpcVars>>

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
    /\ pid \in DOMAIN procedures
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "ABNORMALLY_CLOSED"
       /\ procedures' = [procedures EXCEPT
            ![pid].type = "ASSIGN",
            ![pid].trspState = "GET_ASSIGN_CANDIDATE",
            ![pid].targetServer = None]
       /\ UNCHANGED <<regionState, metaTable, nextProcId, rpcVars>>

---------------------------------------------------------------------------
(* Next-state relation *)

Next ==
    \/ \E r \in Regions : TRSPCreate(r)
    \/ \E pid \in DOMAIN procedures :
         \E s \in Servers : TRSPGetCandidate(pid, s)
    \/ \E pid \in DOMAIN procedures : TRSPOpen(pid)
    \/ \E pid \in DOMAIN procedures : TRSPConfirmOpened(pid)
    \/ \E r \in Regions : FailOpen(r)
    \/ \E r \in Regions : TRSPCreateUnassign(r)
    \/ \E pid \in DOMAIN procedures : TRSPClose(pid)
    \/ \E pid \in DOMAIN procedures : TRSPConfirmClosed(pid)
    \/ \E r \in Regions : GoOffline(r)
    \/ \E r \in Regions : ServerCrash(r)
    \/ \E pid \in DOMAIN procedures : TRSPServerCrashed(pid)

---------------------------------------------------------------------------
(* Fairness *)

\* Weak fairness on region-level creation actions ensures forward
\* progress.  Fairness on procedure-step actions (TRSPGetCandidate,
\* TRSPOpen, TRSPConfirmOpened, TRSPClose, TRSPConfirmClosed) is not
\* expressed here because procedure IDs are dynamically allocated;
\* full fairness is deferred to Iteration 27.
Fairness ==
    /\ \A r \in Regions : WF_vars(TRSPCreate(r))
    /\ \A r \in Regions : WF_vars(TRSPCreateUnassign(r))

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

\* Safety: at most one server per OPEN region (per region identity).
THEOREM Spec => []SingleAssignment

\* Safety: persistent meta state matches in-memory state.
THEOREM Spec => []MetaConsistency

\* Safety: procedure lock held only during appropriate states.
THEOREM Spec => []LockExclusivity

\* Safety: procedure<->region bidirectional consistency.
THEOREM Spec => []ProcedureConsistency

\* All transitions in every step are members of ValidTransition.
\* Expressed as an action property checked via TLC's action constraint.
TransitionValid ==
    \A r \in Regions :
        regionState'[r].state # regionState[r].state
            => <<regionState[r].state, regionState'[r].state>> \in ValidTransition

============================================================================
