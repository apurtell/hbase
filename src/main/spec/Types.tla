---------------------- MODULE Types ---------------------------
(*
 * Pure-definition module: constants, type sets, and state definitions
 * for the HBase AssignmentManager specification.
 *)
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS Regions,    \* The finite set of region identifiers
         Servers

\* The finite set of regionserver identifiers
ASSUME Regions # {}
ASSUME Servers # {}

\* Sentinel model value for "no server assigned"
CONSTANTS NoServer
ASSUME NoServer \notin Servers

\* Sentinel model value for "no transition code recorded".
\* Used in ProcStoreRecord.transitionCode for all procedure steps
\* except REPORT_SUCCEED.
CONSTANTS NoTransition

\* Sentinel model value for "no persisted procedure".
CONSTANTS NoProcedure

\* Maximum open-retry attempts before giving up (FAILED_OPEN)
CONSTANTS MaxRetries
ASSUME MaxRetries \in Nat /\ MaxRetries >= 0


\* UseReopen: when TRUE, TRSPCreateReopen is enabled, modeling the
\* branch-2.6 REOPEN transition type (close then reopen on the same
\* server).  master (branch-3+) does not have REOPEN, only MOVE.
\* Setting FALSE disables REOPEN, reducing the state space.
CONSTANTS UseReopen
ASSUME UseReopen \in BOOLEAN

\* UseRSOpenDuplicateQuirk: when TRUE, the RSOpenDuplicate action is
\* enabled, modeling AssignRegionHandler.process() L107-115 where the
\* RS silently drops OPEN requests for already-online regions without
\* reporting back.  This can cause TRSP deadlock (stuck at
\* CONFIRM_OPENED).  Default FALSE to avoid deadlock in model checking;
\* set TRUE to surface the implementation quirk and generate traces.
CONSTANTS UseRSOpenDuplicateQuirk
ASSUME UseRSOpenDuplicateQuirk \in BOOLEAN

\* UseRestoreSucceedQuirk: when TRUE, RestoreSucceedState faithfully
\* reproduces the OpenRegionProcedure.restoreSucceedState() L128-136
\* bug where OPEN-type procedures always replay as OPENED regardless
\* of the persisted transitionCode (even FAILED_OPEN).  Default FALSE
\* so that recovery correctly checks transitionCode and branches;
\* set TRUE to demonstrate the violation and generate counterexample
\* traces.
CONSTANTS UseRestoreSucceedQuirk
ASSUME UseRestoreSucceedQuirk \in BOOLEAN

---------------------------------------------------------------------------

(* State definitions *)
\* Core assignment lifecycle states.
\* Mirrors RegionState.State (HBase.proto / RegionState.java) for the
\* assign/unassign/move path.
\*
\*   Modeled             | Impl enum value
\*   --------------------+---------------------------------------------
\*   "OFFLINE"           | OFFLINE (=0)
\*   "OPENING"           | OPENING (=1)
\*   "OPEN"              | OPEN (=2)
\*   "CLOSING"           | CLOSING (=3)
\*   "CLOSED"            | CLOSED (=4)
\*   "FAILED_OPEN"       | FAILED_OPEN (=8)
\*   "ABNORMALLY_CLOSED" | ABNORMALLY_CLOSED (=10)
\*
\* Deferred:
\*   SPLITTING (=5), SPLIT (=6), MERGING (=9), MERGED (=11),
\*   SPLITTING_NEW (=7), MERGING_NEW (=12)
\*
\* Omitted:
\*   FAILED_CLOSE (not in proto; RS aborts on close failure,
\*   resolved through ABNORMALLY_CLOSED instead)
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
\*
\* REPORT_SUCCEED: intermediate state after RegionRemoteProcedureBase
\*   has consumed the RS report and updated in-memory state, but before
\*   the final state has been persisted to metaTable.  Exposes the
\*   crash-vulnerable window for master crash and recovery modeling.
\*
\* 1:1 match with RegionStateTransitionState enum
\* (MasterProcedure.proto).  See TRSP.tla header for the full
\* traceability table.
TRSPState ==
  { "GET_ASSIGN_CANDIDATE",
    "OPEN",
    "CONFIRM_OPENED",
    "CLOSE",
    "CONFIRM_CLOSED",
    "REPORT_SUCCEED"
  }

\* Procedure types.  "NONE" means no procedure is attached.
\* REOPEN: close on current server then reopen preferring the same server
\* (assignCandidate pinning); no other ONLINE server required.
\*
\* Maps to RegionTransitionType enum (MasterProcedure.proto):
\*   "ASSIGN"   -> ASSIGN (=1)
\*   "UNASSIGN" -> UNASSIGN (=2)
\*   "MOVE"     -> MOVE (=3)
\*   "REOPEN"   -> REOPEN (=4)
\*   "NONE"     -> (no protobuf equivalent; model-only sentinel)
ProcType == { "ASSIGN", "UNASSIGN", "MOVE", "REOPEN", "NONE" }

\* RPC command types dispatched from master to RegionServer.
\* Maps to RegionRemoteProcedureBase subclasses:
\*   "OPEN"  -> OpenRegionProcedure (dispatches via executeProcedures() RPC)
\*   "CLOSE" -> CloseRegionProcedure (dispatches via executeProcedures() RPC)
\*
\* Source: RSProcedureDispatcher dispatches OpenRegionProcedure /
\*         CloseRegionProcedure via executeProcedures() RPC.
CommandType == { "OPEN", "CLOSE" }

\* Transition codes reported from RegionServer back to master.
\* Maps to RegionServerStatusService.RegionStateTransition.TransitionCode
\* (RegionServerStatus.proto):
\*   "OPENED"      -> OPENED (=0)
\*   "FAILED_OPEN" -> FAILED_OPEN (=1)
\*   "CLOSED"      -> CLOSED (=3)
\*
\* Omitted:
\*   READY_TO_SPLIT (=4), SPLIT (=5), SPLIT_REVERTED (=7),
\*   READY_TO_MERGE (=8), MERGED (=9), MERGE_REVERTED (=10)
\*
\* Source: RegionServerStatusService.reportRegionStateTransition()
\*         with TransitionCode enum values.
ReportCode == { "OPENED", "FAILED_OPEN", "CLOSED" }

\* Type definition for persisted procedure records.
\* transitionCode records the RS report outcome when step = REPORT_SUCCEED;
\* set to NoTransition for all other steps.
ProcStoreRecord ==
  [type:ProcType \ { "NONE" },
    step:TRSPState,
    targetServer:Servers \cup { NoServer },
    transitionCode:ReportCode \cup { NoTransition }
  ]

---------------------------------------------------------------------------


(* Helpers *)
\* Constructor for procStore records.  All sites that write to procStore
\* must call this instead of constructing an inline record literal,
\* ensuring the 4-field shape is maintained in one place.
NewProcRecord(type, step, server, tc) ==
  [ type |-> type,
    step |-> step,
    targetServer |-> server,
    transitionCode |-> tc
  ]

============================================================================
