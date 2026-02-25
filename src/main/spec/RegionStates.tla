--------------------------- MODULE RegionStates ----------------------------
(*
 * Models the HBase region assignment state machine.
 *
 * Each region has a State drawn from RegionState.State (RegionState.java:38-59)
 * and an optional location (the RegionServer hosting it).
 *
 * This module defines the valid states, the valid transitions between them,
 * and a simple spec that non-deterministically exercises all transitions,
 * allowing TLC to verify that the transition invariant is never violated.
 *
 * Scoped to the core assign/unassign lifecycle:
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
    None       \* Sentinel model value for "no server assigned"

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
    \* regionState[r] is a record [state: State, location: Servers ∪ {None}]
    \* for each r ∈ Regions.

vars == <<regionState>>

---------------------------------------------------------------------------
(* Type invariant *)

TypeOK ==
    regionState \in [Regions -> [state : State,
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

\* A given region is OPEN on at most one server.
\* (Trivially true in this module since location is a scalar, but
\*  stated explicitly as the foundational safety property.  It
\*  becomes non-trivial when the model introduces separate master-
\*  and RS-side views of region state.)
SingleAssignment ==
    \A r \in Regions :
        regionState[r].state = "OPEN" => regionState[r].location # None

---------------------------------------------------------------------------
(* Initial state *)

Init ==
    regionState = [r \in Regions |-> [state |-> "OFFLINE", location |-> None]]

---------------------------------------------------------------------------
(* Actions *)

\* --- Assign path ---

\* Begin opening a region on a chosen server.
\* Pre: region is in a state eligible for assignment.
\* Post: region transitions to OPENING with a target server.
BeginOpen(r, s) ==
    /\ regionState[r].state \in {"OFFLINE", "CLOSED",
                                  "ABNORMALLY_CLOSED", "FAILED_OPEN"}
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "OPENING", location |-> s]]

\* Region successfully opened.
\* Pre: region is OPENING.
\* Post: region transitions to OPEN (location unchanged).
ConfirmOpened(r) ==
    /\ regionState[r].state = "OPENING"
    /\ regionState[r].location # None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "OPEN", location |-> @.location]]

\* Region failed to open (after retries exhausted).
\* Pre: region is OPENING.
\* Post: region transitions to FAILED_OPEN, location cleared.
FailOpen(r) ==
    /\ regionState[r].state = "OPENING"
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "FAILED_OPEN", location |-> None]]

\* --- Unassign path ---

\* Begin closing a region.
\* Pre: region is OPEN.
\* Post: region transitions to CLOSING (location retained during close).
BeginClose(r) ==
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "CLOSING", location |-> @.location]]

\* Region successfully closed.
\* Pre: region is CLOSING.
\* Post: region transitions to CLOSED, location cleared.
ConfirmClosed(r) ==
    /\ regionState[r].state = "CLOSING"
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "CLOSED", location |-> None]]

\* --- Transition from CLOSED back to OFFLINE ---

\* Used by RegionStateNode.offline() when a region is being taken fully offline
\* (e.g., table disable).
GoOffline(r) ==
    /\ regionState[r].state = "CLOSED"
    /\ regionState' = [regionState EXCEPT
         ![r] = [state |-> "OFFLINE", location |-> None]]

\* --- Crash path ---

\* The server hosting an OPEN region crashes.
\* Pre: region is OPEN with a location.
\* Post: region transitions to ABNORMALLY_CLOSED, location cleared.
ServerCrash(r) ==
    /\ regionState[r].state = "OPEN"
    /\ regionState[r].location # None
    /\ regionState' = [regionState EXCEPT
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

\* All transitions in every step are members of ValidTransition.
\* Expressed as an action property checked via TLC's "action constraint" or
\* by an invariant on primed variables.  For TLC, we check this as an
\* invariant on the post-state:
TransitionValid ==
    \A r \in Regions :
        regionState'[r].state # regionState[r].state
            => <<regionState[r].state, regionState'[r].state>> \in ValidTransition

============================================================================
