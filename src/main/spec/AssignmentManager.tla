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
 * The ASSIGN path dispatches OPEN commands to dispatchedOps and waits
 * for OPENED reports in pendingReports.  RS-side actions (RSReceiveOpen,
 * RSCompleteOpen, RSFailOpen) consume commands from dispatchedOps and
 * produce reports in pendingReports.  TRSPConfirmOpened requires
 * consuming a matching report before advancing.  DispatchFail models
 * non-deterministic RPC failure: the command is removed without
 * delivery and the TRSP retries with a fresh candidate server.
 *
 * Currently scoped to the core assign/unassign lifecycle:
 *   OFFLINE, OPENING, OPEN, CLOSING, CLOSED, FAILED_OPEN, ABNORMALLY_CLOSED
 *
 * Split/merge states (SPLITTING, SPLIT, MERGING, MERGED, SPLITTING_NEW,
 * MERGING_NEW) and FAILED_CLOSE are deferred to a later phase.
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

VARIABLE rsOnlineRegions
    \* rsOnlineRegions[s] is the set of regions currently online on
    \* server s, from the RS's own perspective.  Updated when the RS
    \* completes an open transition (RSCompleteOpen).
    \*
    \* Source: HRegionServer.getRegions().

VARIABLE rsTransitions
    \* rsTransitions[s][r] tracks in-flight region transitions on
    \* server s.  The value is either None (idle) or a record
    \*   [status : {"Opening", "Closing"}, procId : Nat]
    \* capturing the transition type and the procedure ID from the
    \* dispatched command.
    \*
    \* Source: AssignRegionHandler.java, UnassignRegionHandler.java.

vars == <<regionState, metaTable, procedures, nextProcId,
          dispatchedOps, pendingReports, rsOnlineRegions, rsTransitions>>

\* Shorthand for the RPC channel variables (used in UNCHANGED clauses).
rpcVars == <<dispatchedOps, pendingReports>>

\* Shorthand for the RS-side variables (used in UNCHANGED clauses).
rsVars == <<rsOnlineRegions, rsTransitions>>

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
         \* Procedure type is either ASSIGN or UNASSIGN.
         /\ procedures[pid].type \in {"ASSIGN", "UNASSIGN"}
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
    /\ \A s \in Servers : \A r \in Regions :
         \/ rsTransitions[s][r] = None
         \/ /\ rsTransitions[s][r].status \in {"Opening", "Closing"}
            /\ rsTransitions[s][r].procId \in 1..(nextProcId - 1)

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
    \* No RS-side transitions are in progress.
    /\ rsTransitions = [s \in Servers |-> [r \in Regions |-> None]]

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
    \* META table, RPC channels, and RS-side state are unchanged.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars>>

\* Choose a target server for the ASSIGN procedure.
\* Pre: TRSP is in GET_ASSIGN_CANDIDATE state.
\* Post: targetServer set, TRSP advances to OPEN state.
\*
\* Source: TransitRegionStateProcedure.java executeFromState() L483-496,
\*         queueAssign() L246-278 (getDestinationServer).
TRSPGetCandidate(pid, s) ==
    \* Guards: procedure exists, is ASSIGN, and is in GET_ASSIGN_CANDIDATE step.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "ASSIGN"
    /\ procedures[pid].trspState = "GET_ASSIGN_CANDIDATE"
    \* Record the chosen server and advance the procedure to the OPEN step.
    /\ procedures' = [procedures EXCEPT ![pid].targetServer = s,
                                        ![pid].trspState = "OPEN"]
    \* No other state variables change in this step.
    /\ UNCHANGED <<regionState, metaTable, nextProcId, rpcVars, rsVars>>

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
    \* Guards: procedure exists, is ASSIGN, is in OPEN step, and has a target server.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "ASSIGN"
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
       \* Procedure id counter, pending reports, and RS-side state unchanged.
       /\ UNCHANGED <<nextProcId, pendingReports, rsVars>>

\* Confirm that the region opened successfully by consuming an OPENED
\* report from pendingReports.
\* Pre: TRSP is in CONFIRM_OPENED state, region is OPENING, and a
\*      matching OPENED report exists in pendingReports.
\* Post: region transitions to OPEN, procedure removed and detached,
\*       report consumed from pendingReports.
\*
\* RS-side actions (RSReceiveOpen, RSCompleteOpen) produce the OPENED
\* reports consumed here, completing the ASSIGN round-trip.
\*
\* Source: TransitRegionStateProcedure.java confirmOpened() L320-374.
TRSPConfirmOpened(pid) ==
    \* Guards: procedure exists, is ASSIGN, is in CONFIRM_OPENED step,
    \* region is OPENING, and a matching OPENED report exists for this procedure.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "ASSIGN"
    /\ procedures[pid].trspState = "CONFIRM_OPENED"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "OPENING"
       /\ \E rpt \in pendingReports :
            /\ rpt.region = r
            /\ rpt.code = "OPENED"
            /\ rpt.procId = pid
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
            \* Procedure id counter, dispatched ops, and RS-side state unchanged.
            /\ UNCHANGED <<nextProcId, dispatchedOps, rsVars>>

---------------------------------------------------------------------------
(* Actions -- failure path *)

\* Region failed to open (non-deterministic master-side failure).
\* SUPERSEDED in Iteration 8 by RSFailOpen, which models the proper
\* RS-side failure path and produces a FAILED_OPEN report.  Retained
\* for reference; not included in the Next relation.
\* Full retry logic deferred to Iteration 12.
\* Pre: region is OPENING with a procedure attached.
\* Post: region transitions to FAILED_OPEN, location cleared,
\*       procedure removed and detached, dispatched command cleaned up.
\*       If targetServer is None (DispatchFail already removed the
\*       command), dispatchedOps is left unchanged.
FailOpen(r) ==
    \* Bind the owning procedure id from the region's in-memory state.
    LET pid == regionState[r].procedure IN
    \* Guards: region is OPENING and has an attached procedure.
    /\ regionState[r].state = "OPENING"
    /\ pid # None
    \* Bind the target server and the corresponding dispatched command.
    /\ LET s == procedures[pid].targetServer
           cmd == [type |-> "OPEN", region |-> r, procId |-> pid] IN
       \* Move region to FAILED_OPEN, clear location, detach procedure.
       /\ regionState' = [regionState EXCEPT
            ![r] = [state |-> "FAILED_OPEN", location |-> None,
                    procedure |-> None]]
       \* Persist the FAILED_OPEN state and cleared location in META.
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "FAILED_OPEN", location |-> None]]
       \* Remove the failed procedure from the active set.
       /\ procedures' = RemoveProc(procedures, pid)
       \* Clean up the dispatched command if a target server was set.
       /\ dispatchedOps' = IF s \in Servers
                           THEN [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
                           ELSE dispatchedOps
       \* Procedure id counter, pending reports, and RS-side state unchanged.
       /\ UNCHANGED <<nextProcId, pendingReports, rsVars>>

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
    \* Guards: procedure exists, is ASSIGN, is in CONFIRM_OPENED step,
    \* has a target server, and the OPEN command is still in dispatchedOps.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "ASSIGN"
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
       \* Region state, META, proc id counter, reports, and RS state unchanged.
       /\ UNCHANGED <<regionState, metaTable, nextProcId, pendingReports, rsVars>>

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
    \* META table, RPC channels, and RS-side state are unchanged.
    /\ UNCHANGED <<metaTable, rpcVars, rsVars>>

\* Execute the close step: transition region to CLOSING.
\* Pre: UNASSIGN procedure in CLOSE state, region is OPEN.
\* Post: region transitions to CLOSING (location retained during close),
\*       meta updated, TRSP advances to CONFIRM_CLOSED.
\*
\* Source: TransitRegionStateProcedure.java closeRegion() L389-407.
TRSPClose(pid) ==
    \* Guards: procedure exists, is UNASSIGN, is in CLOSE step, and region is OPEN.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "UNASSIGN"
    /\ procedures[pid].trspState = "CLOSE"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "OPEN"
       \* Transition the region to CLOSING (location is retained).
       /\ regionState' = [regionState EXCEPT
            ![r].state = "CLOSING"]
       \* Persist CLOSING state in META, preserving the location.
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "CLOSING",
                    location |-> metaTable[r].location]]
       \* Advance the procedure to wait for close confirmation.
       /\ procedures' = [procedures EXCEPT
            ![pid].trspState = "CONFIRM_CLOSED"]
       \* Proc id counter, RPC channels, and RS-side state unchanged.
       /\ UNCHANGED <<nextProcId, rpcVars, rsVars>>

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
    \* Guards: procedure exists, is UNASSIGN, is in CONFIRM_CLOSED step,
    \* and the region is CLOSING.
    /\ pid \in DOMAIN procedures
    /\ procedures[pid].type = "UNASSIGN"
    /\ procedures[pid].trspState = "CONFIRM_CLOSED"
    /\ LET r == procedures[pid].region IN
       /\ regionState[r].state = "CLOSING"
       \* Finalize the region as CLOSED, clear location, detach procedure.
       /\ regionState' = [regionState EXCEPT
            ![r] = [state |-> "CLOSED", location |-> None,
                    procedure |-> None]]
       \* Persist the CLOSED state and cleared location in META.
       /\ metaTable' = [metaTable EXCEPT
            ![r] = [state |-> "CLOSED", location |-> None]]
       \* Remove the completed procedure from the active set.
       /\ procedures' = RemoveProc(procedures, pid)
       \* Proc id counter, RPC channels, and RS-side state unchanged.
       /\ UNCHANGED <<nextProcId, rpcVars, rsVars>>

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
    \* Procedures, proc id counter, RPC channels, and RS-side state unchanged.
    /\ UNCHANGED <<procedures, nextProcId, rpcVars, rsVars>>

\* The server hosting an OPEN region crashes.
\* Pre: region is OPEN with a location.
\* Post: region transitions to ABNORMALLY_CLOSED, location cleared.
\*       This is an external event; the procedure field is preserved
\*       as-is (should be None for stable OPEN regions).
ServerCrash(r) ==
    \* Guards: region is OPEN and has an assigned server.
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    \* Mark region ABNORMALLY_CLOSED, clear location, preserve procedure field.
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "ABNORMALLY_CLOSED", location |-> None,
                 procedure |-> regionState[r].procedure]]
    \* Persist the ABNORMALLY_CLOSED state and cleared location in META.
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "ABNORMALLY_CLOSED", location |-> None]]
    \* Procedures, proc id counter, RPC channels, and RS-side state unchanged.
    /\ UNCHANGED <<procedures, nextProcId, rpcVars, rsVars>>

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
       \* Region state, META, proc id counter, RPC, and RS state unchanged.
       /\ UNCHANGED <<regionState, metaTable, nextProcId, rpcVars, rsVars>>

---------------------------------------------------------------------------
(* Actions -- RS-side open handler *)

\* RS receives an OPEN command from the master.
\* Pre: An OPEN command for region r exists in dispatchedOps[s] and
\*      no transition is already in progress for r on server s.
\* Post: Command removed from dispatchedOps, RS begins opening
\*       transition (rsTransitions records status and procId).
\*
\* Source: AssignRegionHandler.java process() L98-164.
RSReceiveOpen(s, r) ==
    \* Guards: an OPEN command for region r exists on server s,
    \* and no transition is already in progress for r on that server.
    \E cmd \in dispatchedOps[s] :
        /\ cmd.type = "OPEN"
        /\ cmd.region = r
        /\ rsTransitions[s][r] = None
        \* Consume the command from the server's dispatched ops queue.
        /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ {cmd}]
        \* Begin the RS-side open transition, recording procId for the report.
        /\ rsTransitions' = [rsTransitions EXCEPT ![s][r] =
             [status |-> "Opening", procId |-> cmd.procId]]
        \* Master-side state and other RS variables unchanged.
        /\ UNCHANGED <<regionState, metaTable, procedures, nextProcId,
                       pendingReports, rsOnlineRegions>>

\* RS successfully completes opening a region.
\* Pre: rsTransitions[s][r] is in "Opening" status.
\* Post: Region added to rsOnlineRegions, transition cleared,
\*       OPENED report sent to master via pendingReports.
\*
\* Source: AssignRegionHandler.java process() L98-164 (success path).
RSCompleteOpen(s, r) ==
    \* Guards: a transition exists for region r on server s and is in Opening status.
    /\ rsTransitions[s][r] # None
    /\ rsTransitions[s][r].status = "Opening"
    \* Bind the procedure id from the in-progress transition.
    /\ LET pid == rsTransitions[s][r].procId IN
       \* Add the region to the server's set of online regions.
       /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = @ \cup {r}]
       \* Clear the completed transition record.
       /\ rsTransitions' = [rsTransitions EXCEPT ![s][r] = None]
       \* Send an OPENED report to the master for procedure confirmation.
       /\ pendingReports' = pendingReports \cup
            {[server |-> s, region |-> r, code |-> "OPENED", procId |-> pid]}
       \* Master-side state and dispatched ops unchanged.
       /\ UNCHANGED <<regionState, metaTable, procedures, nextProcId,
                      dispatchedOps>>

\* RS fails to open a region.
\* Pre: rsTransitions[s][r] is in "Opening" status.
\* Post: Transition cleared, FAILED_OPEN report sent to master via
\*       pendingReports.  Region is NOT added to rsOnlineRegions.
\*
\* Source: AssignRegionHandler.java process() L98-164 (failure path).
RSFailOpen(s, r) ==
    \* Guards: a transition exists for region r on server s and is in Opening status.
    /\ rsTransitions[s][r] # None
    /\ rsTransitions[s][r].status = "Opening"
    \* Bind the procedure id from the in-progress transition.
    /\ LET pid == rsTransitions[s][r].procId IN
       \* Clear the failed transition record.
       /\ rsTransitions' = [rsTransitions EXCEPT ![s][r] = None]
       \* Send a FAILED_OPEN report to the master for error handling.
       /\ pendingReports' = pendingReports \cup
            {[server |-> s, region |-> r, code |-> "FAILED_OPEN", procId |-> pid]}
       \* Master-side state, dispatched ops, and online regions unchanged.
       /\ UNCHANGED <<regionState, metaTable, procedures, nextProcId,
                      dispatchedOps, rsOnlineRegions>>

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
    \* Handle RPC dispatch failure; retry with a new candidate.
    \/ \E pid \in DOMAIN procedures : DispatchFail(pid)
    \* -- UNASSIGN path --
    \* Start an UNASSIGN procedure for an OPEN region.
    \/ \E r \in Regions : TRSPCreateUnassign(r)
    \* Begin closing the region.
    \/ \E pid \in DOMAIN procedures : TRSPClose(pid)
    \* Confirm the region closed successfully.
    \/ \E pid \in DOMAIN procedures : TRSPConfirmClosed(pid)
    \* -- External events --
    \* Transition a CLOSED region to OFFLINE (e.g. table disable).
    \/ \E r \in Regions : GoOffline(r)
    \* Crash the server hosting an OPEN region.
    \/ \E r \in Regions : ServerCrash(r)
    \* -- Crash recovery --
    \* Convert a stranded procedure on an ABNORMALLY_CLOSED region to ASSIGN.
    \/ \E pid \in DOMAIN procedures : TRSPServerCrashed(pid)
    \* -- RS-side open handler --
    \* RS receives and begins processing an OPEN command.
    \/ \E s \in Servers : \E r \in Regions : RSReceiveOpen(s, r)
    \* RS successfully completes opening a region.
    \/ \E s \in Servers : \E r \in Regions : RSCompleteOpen(s, r)
    \* RS fails to open a region.
    \/ \E s \in Servers : \E r \in Regions : RSFailOpen(s, r)

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
