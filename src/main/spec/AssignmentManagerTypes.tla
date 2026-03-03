---------------------- MODULE AssignmentManagerTypes ---------------------------
(*
 * Pure-definition module: constants, type sets, and state definitions
 * for the HBase AssignmentManager specification.
 *
 * This module contains no variables — it is included via EXTENDS by
 * all other modules in the AssignmentManager family.
 *)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS Regions,    \* The finite set of region identifiers
         Servers

\* The finite set of regionserver identifiers
ASSUME Regions # {}
ASSUME Servers # {}

\* Sentinel model value for "no server assigned"
CONSTANTS None
ASSUME None \notin Servers

\* Maximum open-retry attempts before giving up (FAILED_OPEN)
CONSTANTS MaxRetries
ASSUME MaxRetries \in Nat /\ MaxRetries >= 0


\* UseReopen: when TRUE, TRSPCreateReopen is enabled, modeling the
\* branch-2.6 REOPEN transition type (close then reopen on the same
\* server).  master (branch-3+) does not have REOPEN, only MOVE.
\* Setting FALSE disables REOPEN, reducing the state space.
CONSTANTS UseReopen
ASSUME UseReopen \in BOOLEAN

\* Sentinel model value for "no persisted procedure".
CONSTANTS NoneRecord

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
\*   OFFLINE  -> OPENING : AM.regionOpening()
\*   OPENING  -> OPEN    : AM.regionOpenedWithoutPersistingToMeta() (STATES_EXPECTED_ON_OPEN)
\*   OPENING  -> FAILED_OPEN : AM.regionFailedOpen()
\*   OPEN     -> CLOSING : AM.regionClosing() (STATES_EXPECTED_ON_CLOSING)
\*   CLOSING  -> CLOSED  : AM.regionClosedWithoutPersistingToMeta() (STATES_EXPECTED_ON_CLOSED)
\*   CLOSED   -> OPENING : AM.regionOpening() via assign (STATES_EXPECTED_ON_ASSIGN)
\*   CLOSED   -> OFFLINE : RegionStateNode.offline()
\*   OPEN     -> ABNORMALLY_CLOSED : AM.regionClosedAbnormally()
\*   OPENING  -> ABNORMALLY_CLOSED : AM.regionClosedAbnormally() (server crash during open)
\*   CLOSING  -> ABNORMALLY_CLOSED : AM.regionClosedAbnormally() (server crash during close)
\*   ABNORMALLY_CLOSED -> OPENING : AM.regionOpening() (no expected states)
\*   FAILED_OPEN -> OPENING : AM.regionOpening() (no expected states)
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

\* Type definition for persisted procedure records.
ProcStoreRecord ==
  [type:ProcType \ { "NONE" },
    step:TRSPState,
    targetServer:Servers \cup { None }
  ]

============================================================================
