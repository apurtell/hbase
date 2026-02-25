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
 * a region at any time.  Actions that begin a transition (BeginOpen,
 * BeginClose) require the procedure field to be None and set it; actions
 * that complete a transition (ConfirmOpened, ConfirmClosed, FailOpen)
 * clear it back to None.
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

---------------------------------------------------------------------------
(* Variables *)

VARIABLE regionState
    \* regionState[r] is a record
    \*   [state : State,
    \*    location : Servers ∪ {None},
    \*    procedure : {None, TRUE}]
    \* for each r ∈ Regions.  This is volatile master in-memory state.
    \*
    \* The `procedure` field models RegionStateNode.setProcedure() /
    \* unsetProcedure() (RSN.java L213-224).  It is None when no
    \* procedure is attached, or TRUE when some procedure holds the
    \* region lock.  The actual procedure identity (proc ID, type,
    \* internal state) is introduced in Iteration 4.

VARIABLE metaTable
    \* metaTable[r] is a record [state : State, location : Servers ∪ {None}]
    \* for each r ∈ Regions.  This is persistent state in hbase:meta.
    \* Survives master crash; regionState does not.
    \*
    \* The procedure field is NOT persisted to meta — procedures are
    \* master in-memory state, recovered from ProcedureStore on restart.

vars == <<regionState, metaTable>>

---------------------------------------------------------------------------
(* Type invariant *)

TypeOK ==
    /\ regionState \in [Regions -> [state : State,
                                    location : Servers \cup {None},
                                    procedure : {None, TRUE}]]
    /\ metaTable \in [Regions -> [state : State,
                                  location : Servers \cup {None}]]

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

\* At most one procedure is attached to a region at any time, and a
\* procedure is only attached during transitional states (OPENING or
\* CLOSING).  The "at most one" part is trivially true by construction
\* (the procedure field is a scalar), but stated explicitly as the
\* foundational mutual exclusion property.  The state correlation is
\* a stronger check verifying that acquire/release is correctly paired
\* with state transitions.
\*
\* Becomes non-trivial in later iterations when SCP and TRSP interact
\* and procedure handoff must be correctly sequenced.  Will be extended
\* to include SPLITTING and MERGING states in Phase 6.
\*
\* Source: RegionStateNode.java setProcedure() L213-218,
\*         unsetProcedure() L220-224.
LockExclusivity ==
    \A r \in Regions :
        regionState[r].procedure # None =>
            regionState[r].state \in {"OPENING", "CLOSING"}

---------------------------------------------------------------------------
(* Initial state *)

Init ==
    /\ regionState = [r \in Regions |->
         [state |-> "OFFLINE", location |-> None, procedure |-> None]]
    /\ metaTable = [r \in Regions |->
         [state |-> "OFFLINE", location |-> None]]

---------------------------------------------------------------------------
(* Actions *)

\* --- Assign path ---

\* Begin opening a region on a chosen server.
\* Pre: region is in a state eligible for assignment AND no procedure
\*      is currently attached (the region lock is free).
\* Post: region transitions to OPENING with a target server; a
\*       procedure is attached (lock acquired).
BeginOpen(r, s) ==
    /\ regionState[r].state \in {"OFFLINE", "CLOSED",
                                  "ABNORMALLY_CLOSED", "FAILED_OPEN"}
    /\ regionState[r].procedure = None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "OPENING", location |-> s, procedure |-> TRUE]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "OPENING", location |-> s]]

\* Region successfully opened.
\* Pre: region is OPENING with a procedure attached.
\* Post: region transitions to OPEN; procedure detached (lock released).
ConfirmOpened(r) ==
    /\ regionState[r].state = "OPENING"
    /\ regionState[r].location # None
    /\ regionState[r].procedure # None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "OPEN", location |-> @.location, procedure |-> None]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "OPEN", location |-> metaTable[r].location]]

\* Region failed to open (after retries exhausted).
\* Pre: region is OPENING with a procedure attached.
\* Post: region transitions to FAILED_OPEN, location cleared;
\*       procedure detached (lock released).
FailOpen(r) ==
    /\ regionState[r].state = "OPENING"
    /\ regionState[r].procedure # None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "FAILED_OPEN", location |-> None, procedure |-> None]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "FAILED_OPEN", location |-> None]]

\* --- Unassign path ---

\* Begin closing a region.
\* Pre: region is OPEN AND no procedure is currently attached.
\* Post: region transitions to CLOSING (location retained during close);
\*       a procedure is attached (lock acquired).
BeginClose(r) ==
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState[r].procedure = None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "CLOSING", location |-> @.location, procedure |-> TRUE]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "CLOSING", location |-> metaTable[r].location]]

\* Region successfully closed.
\* Pre: region is CLOSING with a procedure attached.
\* Post: region transitions to CLOSED, location cleared;
\*       procedure detached (lock released).
ConfirmClosed(r) ==
    /\ regionState[r].state = "CLOSING"
    /\ regionState[r].procedure # None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "CLOSED", location |-> None, procedure |-> None]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "CLOSED", location |-> None]]

\* --- Transition from CLOSED back to OFFLINE ---

\* Used by RegionStateNode.offline() when a region is being taken fully
\* offline (e.g., table disable).  This is an external action that does
\* not acquire or release a procedure lock.
GoOffline(r) ==
    /\ regionState[r].state = "CLOSED"
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "OFFLINE", location |-> None,
                 procedure |-> @.procedure]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "OFFLINE", location |-> None]]

\* --- Crash path ---

\* The server hosting an OPEN region crashes.
\* Pre: region is OPEN with a location.
\* Post: region transitions to ABNORMALLY_CLOSED, location cleared.
\*       This is an external event that does not acquire or release a
\*       procedure lock; the procedure field is preserved as-is.
ServerCrash(r) ==
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "ABNORMALLY_CLOSED", location |-> None,
                 procedure |-> @.procedure]]
    /\ metaTable' = [metaTable EXCEPT
         ![r] = [state |-> "ABNORMALLY_CLOSED", location |-> None]]

---------------------------------------------------------------------------
(* Next-state relation *)

Next ==
    \E r \in Regions :
        \/ \E s \in Servers : BeginOpen(r, s)
        \/ ConfirmOpened(r)
        \/ FailOpen(r)
        \/ BeginClose(r)
        \/ ConfirmClosed(r)
        \/ GoOffline(r)
        \/ ServerCrash(r)

---------------------------------------------------------------------------
(* Fairness *)

\* Weak fairness: if a transition is continuously enabled, it eventually occurs.
\* Not strictly needed for safety checking but included for liveness exploration.
Fairness ==
    \A r \in Regions :
        /\ \A s \in Servers : WF_vars(BeginOpen(r, s))
        /\ WF_vars(ConfirmOpened(r))
        /\ WF_vars(BeginClose(r))
        /\ WF_vars(ConfirmClosed(r))

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

\* Safety: procedure lock held only during transitional states.
THEOREM Spec => []LockExclusivity

\* All transitions in every step are members of ValidTransition.
\* Expressed as an action property checked via TLC's "action constraint" or
\* by an invariant on primed variables.  For TLC, we check this as an
\* invariant on the post-state:
TransitionValid ==
    \A r \in Regions :
        regionState'[r].state # regionState[r].state
            => <<regionState[r].state, regionState'[r].state>> \in ValidTransition

============================================================================
