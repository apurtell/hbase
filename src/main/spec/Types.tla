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

\* DeployedRegions: the table regions that exist at system start.
\* They tile the full keyspace [0, MaxKey) in Init.
\* Regions \ DeployedRegions are unused identifiers available for
\* split/merge to materialize as new regions.
CONSTANTS DeployedRegions
ASSUME DeployedRegions \subseteq Regions
ASSUME DeployedRegions # {}

\* MaxKey: the keyspace is 0..(MaxKey-1).
CONSTANTS MaxKey
ASSUME MaxKey \in Nat /\ MaxKey > 0

\* NoRange: sentinel model value for unused region identifiers
\* whose keyspace has not been assigned (region does not exist).
CONSTANTS NoRange

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

\* NoRegion: sentinel model value for "no region reference" in
\* parentProc records.  Used when a region-reference field is not
\* applicable (e.g., split pre-PONR before daughters are chosen).
CONSTANTS NoRegion
ASSUME NoRegion \notin Regions

\* UseMerge: when TRUE, merge actions are enabled in Next and Fairness.
\* With both split and merge active, the state space becomes unbounded
\* (split -> daughters -> merge -> parent -> split -> ...).  Setting
\* FALSE keeps exhaustive model checking tractable (split-only).
\* Setting TRUE enables merge in simulation mode.
CONSTANTS UseMerge
ASSUME UseMerge \in BOOLEAN

\* UseRSOpenDuplicateQuirk: when TRUE, the RSOpenDuplicate action is
\* enabled, modeling AssignRegionHandler.process() L107-115 where the
\* RS silently drops OPEN requests for already-online regions without
\* reporting back.  This can cause TRSP deadlock (stuck at
\* CONFIRM_OPENED).  Default FALSE to avoid deadlock in model checking;
\* set TRUE to surface the implementation quirk and generate traces.
CONSTANTS UseRSOpenDuplicateQuirk
ASSUME UseRSOpenDuplicateQuirk \in BOOLEAN

\* UseRSCloseNotFoundQuirk: when TRUE, the RSCloseNotFound action is
\* enabled, modeling UnassignRegionHandler.process() L111-117 where the
\* RS silently drops CLOSE requests for regions that are not online
\* without reporting back.  This can cause TRSP deadlock (stuck at
\* CONFIRM_CLOSED).  Default FALSE to avoid deadlock in model checking;
\* set TRUE to surface the implementation quirk and generate traces.
CONSTANTS UseRSCloseNotFoundQuirk
ASSUME UseRSCloseNotFoundQuirk \in BOOLEAN

\* UseRestoreSucceedQuirk: when TRUE, RestoreSucceedState faithfully
\* reproduces the OpenRegionProcedure.restoreSucceedState() L128-136
\* bug where OPEN-type procedures always replay as OPENED regardless
\* of the persisted transitionCode (even FAILED_OPEN).  Default FALSE
\* so that recovery correctly checks transitionCode and branches;
\* set TRUE to demonstrate the violation and generate counterexample
\* traces.
CONSTANTS UseRestoreSucceedQuirk
ASSUME UseRestoreSucceedQuirk \in BOOLEAN

\* MaxWorkers: ProcedureExecutor worker thread pool size.
\* All procedure-step actions require an available worker to execute.
\* Non-blocking actions acquire and release within the same atomic step
\* (net-zero effect).  Meta-writing actions when meta is unavailable
\* may hold a worker (UseBlockOnMetaWrite=TRUE) or suspend and release
\* (UseBlockOnMetaWrite=FALSE).
\*
\* Source: ProcedureExecutor.workerThreadCount;
\*         hbase.procedure.threads (conf, default=# CPUs / 4).
CONSTANTS MaxWorkers
ASSUME MaxWorkers \in Nat /\ MaxWorkers > 0

\* UseBlockOnMetaWrite: when FALSE (default, master/branch-3+),
\* RegionStateStore.updateRegionLocation() returns CompletableFuture<Void>
\* via AsyncTable.put() and the calling procedure suspends via
\* ProcedureFutureUtil.suspendIfNecessary(), releasing the PEWorker thread.
\* When TRUE (branch-2.6), RegionStateStore uses synchronous Table.put(),
\* blocking the PEWorker thread until the RPC completes.
\*
\* Source: master/branch-3+: RegionStateStore.updateRegionLocation()
\*         via AsyncTable.put(); ProcedureFutureUtil.suspendIfNecessary().
\*         branch-2.6: RegionStateStore.updateRegionLocation() L158-240
\*         uses synchronous Table.put() (L237-239).
CONSTANTS UseBlockOnMetaWrite
ASSUME UseBlockOnMetaWrite \in BOOLEAN

\* UseUnknownServerQuirk: when TRUE, DetectUnknownServer silently
\* closes the orphaned region without creating a TRSP(ASSIGN),
\* modeling the checkOnlineRegionsReport() gap (AM.java L1496-1546)
\* where regions on Unknown Servers are closed but never reassigned.
\* Default FALSE: master creates a TRSP(ASSIGN) for the orphan.
\*
\* Source: AM.checkOnlineRegionsReport() L1496-1546,
\*         AM.closeRegionSilently() L1482-1490,
\*         CatalogJanitorReport L50-54 (TODO: auto-fix),
\*         HBCKServerCrashProcedure L40-185 (manual fix).
CONSTANTS UseUnknownServerQuirk
ASSUME UseUnknownServerQuirk \in BOOLEAN

\* UseMasterAbortOnMetaWriteQuirk: when TRUE, models 
\* where RegionStateStore.updateRegionLocation() catches IOException
\* and calls master.abort(msg, e), crashing the entire master when
\* meta is temporarily unavailable (e.g., during SCP for the meta RS).
\* When FALSE (default), the procedure suspends or blocks per
\* UseBlockOnMetaWrite.
\*
\* Source: RegionStateStore.updateRegionLocation() L231-250,
\*         private updateRegionLocation(RegionInfo, State, Put) catch
\*         block calls master.abort() on IOException.
CONSTANTS UseMasterAbortOnMetaWriteQuirk
ASSUME UseMasterAbortOnMetaWriteQuirk \in BOOLEAN

---------------------------------------------------------------------------

(* State definitions *)
\* Region lifecycle states.
\*
\*   Modeled             | Impl enum value
\*   --------------------+---------------------------------------------
\*   "OFFLINE"           | OFFLINE (=0)
\*   "OPENING"           | OPENING (=1)
\*   "OPEN"              | OPEN (=2)
\*   "CLOSING"           | CLOSING (=3)
\*   "CLOSED"            | CLOSED (=4)
\*   "SPLITTING"         | SPLITTING (=5)
\*   "SPLIT"             | SPLIT (=6)
\*   "SPLITTING_NEW"     | SPLITTING_NEW (=7)
\*   "FAILED_OPEN"       | FAILED_OPEN (=8)
\*   "MERGING"           | MERGING (=9)
\*   "ABNORMALLY_CLOSED" | ABNORMALLY_CLOSED (=10)
\*   "MERGED"            | MERGED (=11)
\*   "MERGING_NEW"       | MERGING_NEW (=12)
State ==
  { "OFFLINE",
    "OPENING",
    "OPEN",
    "CLOSING",
    "CLOSED",
    "SPLITTING",
    "SPLIT",
    "SPLITTING_NEW",
    "FAILED_OPEN",
    "MERGING",
    "ABNORMALLY_CLOSED",
    "MERGED",
    "MERGING_NEW"
  }

\* The set of valid (from, to) state transitions.
ValidTransition ==
  { \* --- Core assign/unassign/move ---
    << "OFFLINE", "OPENING" >>,
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
    << "FAILED_OPEN", "OPENING" >>,
    \* --- Split-specific ---
    << "OPEN", "SPLITTING" >>,
    << "SPLITTING", "CLOSING" >>,
    << "SPLITTING", "OPEN" >>,
    << "CLOSED", "SPLIT" >>,
    << "SPLITTING_NEW", "OPENING" >>,
    \* --- Merge-specific ---
    << "OPEN", "MERGING" >>,
    << "MERGING", "CLOSING" >>,
    << "MERGING", "OPEN" >>,
    << "CLOSED", "MERGED" >>,
    << "MERGING_NEW", "OPENING" >>
  }

\* TRSP internal states used in the procStep field of regionState.
\* ASSIGN path:   GET_ASSIGN_CANDIDATE -> OPEN -> CONFIRM_OPENED -> (cleared)
\* UNASSIGN path: CLOSE -> CONFIRM_CLOSED -> (cleared)
\* MOVE path:     CLOSE -> CONFIRM_CLOSED -> GET_ASSIGN_CANDIDATE -> OPEN
\*                   -> CONFIRM_OPENED -> (cleared)
\* REPORT_SUCCEED: intermediate state
\*
\* Matches the RegionStateTransitionState enum (MasterProcedure.proto).
\* See TRSP.tla header for the full traceability table.
TRSPState ==
  { "GET_ASSIGN_CANDIDATE",
    "OPEN",
    "CONFIRM_OPENED",
    "CLOSE",
    "CONFIRM_CLOSED",
    "REPORT_SUCCEED"
  }

\* Parent procedure step states.  These track the progress of a
\* parent procedure (SplitTableRegionProcedure, future merge) that
\* owns one or more child TRSPs via addChildProcedure().
\*
\* The parent yields by spawning child TRSPs (SPAWNED_CLOSE,
\* SPAWNED_OPEN) and resumes when all children complete.
\* parentProc[r] persists across the child TRSP lifecycle.
\*
\* Maps to SplitTableRegionState enum (MasterProcedure.proto),
\* collapsed from 11 states to 5 (filesystem + coprocessor ops
\* abstracted).  Generalizes to MergeTableRegionsState later.
\* PREPARE: PREPARE -> PRE_OP (set SPLITTING/MERGING)
\* SPAWNED_CLOSE: CLOSE_PARENT: addChildProcedure(unassign)
\* PONR: CHECK_CLOSED -> CREATE_DAUGHTERS -> WRITE_SEQ
\*   -> PRE_BEFORE_META -> UPDATE_META (PONR)
\* SPAWNED_OPEN:OPEN_CHILD_REGIONS: addChildProcedure(assign)
\* COMPLETING: POST_OPERATION
ParentProcStep ==
  { "SPAWNED_CLOSE", \* CLOSE_PARENT: addChildProcedure(unassign)
    "PONR", \* CHECK_CLOSED -> CREATE_DAUGHTERS -> WRITE_SEQ
    \*   -> PRE_BEFORE_META -> UPDATE_META (PONR)
    "SPAWNED_OPEN", \* OPEN_CHILD_REGIONS: addChildProcedure(assign)
    "COMPLETING" \* POST_OPERATION
  }

\* Parent procedure types.  "NONE" means no parent procedure is
\* attached.  Extensible: "MERGE" will be added in a future iteration.
ParentProcType == { "SPLIT", "MERGE" }

\* Sentinel: no parent procedure attached.
NoParentProc ==
  [ type |-> "NONE", step |-> "NONE", ref1 |-> NoRegion, ref2 |-> NoRegion ]

\* Type definition for parentProc records (used in TypeOK).
\*
\* ref1 and ref2 store region-identifier references that the parent
\* procedure needs across steps, modeling persisted procedure fields:
\*   Split: ref1 = daughter A, ref2 = daughter B
\*          (set at PONR when daughters are chosen; NoRegion before)
\*   Merge: ref1 = other target region, ref2 = merged region
\*          (set at MergePrepare; cross-referenced on both targets)
\*
\* Source: SplitTableRegionProcedure.daughterOneRI/daughterTwoRI;
\*         MergeTableRegionsProcedure.regionsToMerge[]/mergedRegion.
ParentProcRecord ==
  [type:ParentProcType \cup { "NONE" },
    step:ParentProcStep \cup { "NONE" },
    ref1:Regions \cup { NoRegion },
    ref2:Regions \cup { NoRegion }
  ]

\* Procedure types.  "NONE" means no procedure is attached.
\* REOPEN: close on current server then reopen preferring the same server
\* (assignCandidate pinning); no other ONLINE server required.
\*
\* Maps to RegionTransitionType enum (MasterProcedure.proto).
ProcType == { "ASSIGN", "UNASSIGN", "MOVE", "REOPEN", "SPLIT", "MERGE", "NONE" }

\* RPC command types dispatched from master to RegionServer.
\* Maps to RegionRemoteProcedureBase subclasses.
CommandType == { "OPEN", "CLOSE" }

\* Transition codes reported from RegionServer back to master.
\* Maps to RegionServerStatusService.RegionStateTransition.TransitionCode
ReportCode == { "OPENED", "FAILED_OPEN", "CLOSED" }

\* Type definition for persisted procedure records.
\* transitionCode records the RS report outcome when step = REPORT_SUCCEED;
\* set to NoTransition for all other steps.
ProcStoreRecord ==
  [type:ProcType \ { "NONE" },
    step:TRSPState \cup { "IDLE" },
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
