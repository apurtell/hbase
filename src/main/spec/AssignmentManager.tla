------------------------ MODULE AssignmentManager -------------------------
(*
 * TLA+ specification of the HBase AssignmentManager.
 *
 * Root orchestrator module: declares variables, instantiates action
 * modules, defines invariants, Init, Next, Fairness, and Spec.
 *
 * Action logic is factored into sub-modules:
 *   - Types: constants, type sets, state definitions
 *   - TRSP:           procedure actions (assign, unassign, move, reopen,
 *                     dispatch, confirm, failure, crash recovery)
 *   - SCP:            ServerCrashProcedure state machine
 *   - RegionServer:   RS-side handlers (open, close, abort, stale reports)
 *   - Master:         master-side actions (offline, crash detect, crash, recover)
 *   - RegionServer:   RS-side handlers (open, close, abort, stale reports,
 *                     restart, duplicate open)
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
 * TRSPReportSucceedOpen for the same retry cycle: we must receive FAILED_OPEN
 * (and process it) before we can transition to GET_ASSIGN_CANDIDATE and
 * eventually dispatch a new OPEN. The spec allows arbitrary interleaving of
 * actions across regions and between TRSP/SCP/RS, which can expose races
 * (e.g., TRSPDispatchOpen retry-to-same-server before TRSPReportSucceedOpen)
 * that the implementation's procedure serialization may prevent. The spec's
 * mitigations (clear OPEN in TRSPReportSucceedOpen, prefer OPENED) are
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
 * consumed by TRSPReportSucceedOpen (report phase), and TRSPPersistToMetaOpen
 * and resets the procedure to GET_ASSIGN_CANDIDATE for retry on a
 * different server.  If the retry counter reaches MaxRetries,
 * (persist phase, which increments the retry counter or gives up). (persistent)
 * and clears the procedure.
 *
 * The state space is naturally finite with region-keyed procedures:
 * there are finitely many combinations of region state, procedure
 * configuration, channel contents, and server state.  When a region
 * completes a full lifecycle, the system returns to an already-visited
 * configuration.  No StateConstraint on a procedure counter is needed.
 *
 * Region lifecycle states cover the full RegionState.State enum:
 *   OFFLINE, OPENING, OPEN, CLOSING, CLOSED, FAILED_OPEN, ABNORMALLY_CLOSED,
 *   SPLITTING, SPLIT, SPLITTING_NEW, MERGING, MERGED, MERGING_NEW.
 *
 * FAILED_CLOSE is omitted because no code path transitions into it. The RS
 * abort triggers crash detection, so close failures are resolved through
 * ABNORMALLY_CLOSED instead.
 *)
EXTENDS Types

---------------------------------------------------------------------------


(* Variables *)
\* regionState[r] is a record
\*   [state        : State,
\*    location     : Servers \cup {NoServer},
\*    procType     : ProcType,
\*    procStep     : TRSPState \cup {"IDLE"},
\*    targetServer : Servers \cup {NoServer},
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
VARIABLE regionState

\* metaTable[r] is a record [state : State, location : Servers \cup {NoServer}]
\* for each r in Regions.  This is persistent state in hbase:meta.
\* Survives master crash; regionState does not.
\*
\* Procedure fields are NOT persisted to meta -- procedures are
\* master in-memory state, recovered from ProcedureStore on restart.
VARIABLE metaTable

\* dispatchedOps[s] is a set of command records pending delivery to
\* server s.  Each record is:
\*   [type : CommandType, region : Regions]
\*
\* Models the master->RS command channel: the RSProcedureDispatcher
\* batches open/close commands and sends them via executeProcedures()
\* RPC.  Commands remain in the set until consumed by RS-side actions
\* or discarded on dispatch failure / server crash.
VARIABLE dispatchedOps

\* pendingReports is a set of report records from RegionServers
\* waiting to be processed by the master.  Each record is:
\*   [server : Servers, region : Regions, code : ReportCode]
\*
\* Models the RS->master report channel: RegionServers report
\* transition outcomes via reportRegionStateTransition() RPC.
\* Reports remain in the set until consumed by master-side actions
\* or discarded if from a crashed server.
VARIABLE pendingReports

\* rsOnlineRegions[s] is the set of regions currently online on
\* server s, from the RS's own perspective.  Updated atomically
\* by RSOpen (adds region) and RSClose (removes region).
VARIABLE rsOnlineRegions

\* serverState[s] is the liveness state of server s as seen by the
\* master.  "ONLINE" means the server is alive and accepting commands;
\* "CRASHED" means the master has detected the server's death (ZK
\* ephemeral node expired).  Reports from CRASHED servers are rejected.
\* RS-side actions are guarded by serverState = "ONLINE".
VARIABLE serverState

\* scpState[s] tracks the ServerCrashProcedure progress for server s.
\* "NONE" means no SCP is active for this server.
\* GET_REGIONS -> FENCE_WALS -> ASSIGN -> DONE is the SCP lifecycle.
VARIABLE scpState

\* scpRegions[s] is the snapshot of regions taken at GET_REGIONS time.
VARIABLE scpRegions

\* walFenced[s] is TRUE after SCP revokes WAL leases for server s.
\* Reset to FALSE on ServerRestart.  After fencing, the zombie RS
\* cannot write to its WALs; any write attempt fails with an HDFS
\* lease exception, triggering RS self-abort.
VARIABLE walFenced

\* carryingMeta[s] is TRUE when server s was hosting hbase:meta at
\* the time it crashed.  Set non-deterministically by MasterDetectCrash
\* (one case per invocation: either TRUE or FALSE).  When TRUE, the
\* SCP must reassign meta before proceeding to GET_REGIONS; this is
\* modeled by the ASSIGN_META scpState.  All other SCP actions for
\* ANY server are gated on meta being available (no server in
\* ASSIGN_META state), faithfully modeling waitMetaLoaded.
VARIABLE carryingMeta

\* serverRegions[s] is the set of regions tracked by server s's
\* ServerStateNode in the implementation.  This is maintained by
\* addRegionToServer/removeRegionFromServer calls in AM.regionOpening(),
\* AM.regionClosedWithoutPersistingToMeta(), AM.regionFailedOpen(), and
\* AM.regionClosedAbnormally().  SCP's getRegionsOnServer() reads from
\* this tracking, NOT from regionState[r].location.  These two can
\* desynchronize when a TRSP updates one before the other, providing
\* the mechanism for the race where SCP snapshots a stale set of
\* regions for a crashed server.
VARIABLE serverRegions

\* procStore[r] is the persisted procedure record for region r, or
\* NoProcedure when no procedure is persisted.  Models the
\* WALProcedureStore / RegionProcedureStore persistence layer.
\* Survives master crash -- only cleared by procedure completion or
\* explicit delete.  Updated by ProcedureExecutor.store.update()
\* after each executeFromState step (except when skipPersistence
\* is called, e.g., DispatchFail).
VARIABLE procStore

\* masterAlive is TRUE when the active master JVM is running.
\* When FALSE, all in-memory state (regionState, serverState, etc.)
\* is invalid -- the master does not exist.  Durable state (metaTable,
\* procStore) and RS-side state (rsOnlineRegions) survive.
VARIABLE masterAlive

\* zkNode[s] is TRUE when server s has a live ZK ephemeral node.
\* Created when the RS starts (RSRestart), deleted when ZK detects
\* session expiry (ZKSessionExpire).  Read by the master to determine
\* which servers are alive.
VARIABLE zkNode

\* availableWorkers is the number of idle PEWorker threads.
\* All procedure-step actions require availableWorkers > 0 to execute.
\* Non-blocking actions have net-zero effect (acquire + release within
\* the same atomic step).  Meta-writing actions when meta is unavailable
\* may hold a worker (UseBlockOnMetaWrite=TRUE, decrement) or suspend
\* and release (UseBlockOnMetaWrite=FALSE, no decrement).
VARIABLE availableWorkers

\* suspendedOnMeta is the set of regions whose procedures have been
\* suspended (released the PEWorker thread) while waiting for meta
\* to become available.  Default path (master/branch-3+,
\* UseBlockOnMetaWrite=FALSE): models ProcedureFutureUtil.
\* suspendIfNecessary() suspending the procedure and releasing the
\* PEWorker thread when AssignmentManager.persistToMeta() returns
\* a pending future.
VARIABLE suspendedOnMeta

\* blockedOnMeta is the set of regions whose procedures are blocked
\* on a synchronous meta write, holding the PEWorker thread.
\* Branch-2.6 only (UseBlockOnMetaWrite=TRUE): models the synchronous
\* Table.put() call in RegionStateStore.updateRegionLocation() blocking
\* on an unavailable meta table.
VARIABLE blockedOnMeta

\* regionKeyRange[r] is the keyspace range assigned to region r, or
\* NoRange if the region identifier is not currently in use (does not
\* exist).  A region "exists" iff regionKeyRange[r] # NoRange.
\* At Init: DeployedRegions tile [0, MaxKey) with contiguous,
\* non-overlapping ranges; all other identifiers are NoRange.
\* Split materializes daughter keyspaces at PONR;
\* merge materializes the union keyspace at PONR;
\* parent/target deletion clears to NoRange.
VARIABLE regionKeyRange

\* parentProc[r] tracks the parent procedure (split or merge) whose
\* target region is r.  NoParentProc means no parent procedure is
\* active.  During child TRSP execution (close parent, open daughters),
\* the region's procType reflects the child TRSP (UNASSIGN or ASSIGN),
\* while parentProc retains the parent's overall progress.
\* parentProc is durable (survives master crash): it models the
\* ProcedureStore persistence of the parent procedure.
\*
\* Source: SplitTableRegionProcedure / MergeTableRegionsProcedure
\*         state machines (MasterProcedure.proto).
VARIABLE parentProc

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
     carryingMeta,
     serverRegions,
     procStore,
     masterAlive,
     zkNode,
     availableWorkers,
     suspendedOnMeta,
     blockedOnMeta,
     regionKeyRange,
     parentProc
  >>

---------------------------------------------------------------------------

(* Predicates *)
\* MetaIsAvailable is TRUE when no server is in ASSIGN_META scpState,
\* meaning hbase:meta is online and accessible for read/write.
\* Reuses the existing waitMetaLoaded guard from SCP actions.
MetaIsAvailable == \A s \in Servers: scpState[s] # "ASSIGN_META"

\* A region "exists" iff its keyspace range has been assigned.
\* Unused region identifiers have regionKeyRange = NoRange.
\* All existing actions guard on this predicate so that only
\* regions with assigned keyspaces can participate in transitions.
RegionExists(r) == regionKeyRange[r] # NoRange

\* Two regions are adjacent when the first's endKey equals the
\* second's startKey.  Both must exist (have assigned keyspaces).
\* Used as a precondition for merge operations (iterations 23+).
Adjacent(r1, r2) == /\ RegionExists(r1)
                    /\ RegionExists(r2)
                    /\ regionKeyRange[r1].endKey = regionKeyRange[r2].startKey

\* Shorthand for PEWorker pool variables (used in UNCHANGED clauses).
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>

(* Module instantiation *)
\* Instantiate action modules.  Each sub-module declares its shared
\* variables as CONSTANTS with the SAME names as the variables declared
\* here.  TLA+ INSTANCE without a WITH clause automatically substitutes
\* identifiers by name, so no explicit WITH is needed.
\* Actions are then referenced as trsp!ActionName(args), etc.
trsp == INSTANCE TRSP
scp == INSTANCE SCP
rs == INSTANCE RegionServer
master == INSTANCE Master
ps == INSTANCE ProcStore
zk == INSTANCE ZK
split == INSTANCE Split

---------------------------------------------------------------------------

(* Type invariant *)
TypeOK ==
  \* For every region, check all fields of its in-memory state record.
  /\ \A r \in Regions:
       \* Region lifecycle state must be one of the defined states.
       /\ regionState[r].state \in State
       \* Region location is either an assigned server or None.
       /\ regionState[r].location \in Servers \cup { NoServer }
       \* Embedded procedure type must be a valid ProcType.
       /\ regionState[r].procType \in ProcType
       \* Procedure step is a TRSP state or IDLE.
       /\ regionState[r].procStep \in TRSPState \cup { "IDLE" }
       \* Procedure's target server is a server or None.
       /\ regionState[r].targetServer \in Servers \cup { NoServer }
       \* Retry counter is bounded by MaxRetries.
       /\ regionState[r].retries \in 0 .. MaxRetries
       \* Idle procedure fields are consistent.
       /\ regionState[r].procType = "NONE" =>
            /\ regionState[r].procStep = "IDLE"
            /\ regionState[r].targetServer = NoServer
            /\ regionState[r].retries = 0
       \* Active TRSP procedure has a TRSP step.
       /\ regionState[r].procType \in { "ASSIGN", "UNASSIGN", "MOVE", "REOPEN" } =>
            regionState[r].procStep \in TRSPState
       \* Active SPLIT procedure has IDLE step (parent lock marker).
       /\ regionState[r].procType = "SPLIT" => regionState[r].procStep = "IDLE"
  \* META table maps every region to a persistent (state, location) record.
  /\ metaTable \in
       [Regions -> [state:State, location:Servers \cup { NoServer } ]]
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
  \* Per-server region tracking (ServerStateNode).
  /\ serverRegions \in [Servers -> SUBSET Regions]
  \* Persisted procedure store: record per region or NoProcedure.
  /\ procStore \in [Regions -> ProcStoreRecord \cup { NoProcedure }]
  \* Master liveness.
  /\ masterAlive \in BOOLEAN
  \* ZK ephemeral node liveness per server.
  /\ zkNode \in [Servers -> BOOLEAN]
  \* PEWorker pool: available workers bounded by MaxWorkers.
  /\ availableWorkers \in 0 .. MaxWorkers
  \* Regions suspended on meta (async, worker released).
  /\ suspendedOnMeta \subseteq Regions
  \* Regions blocked on meta (sync, worker held).
  /\ blockedOnMeta \subseteq Regions
  \* Keyspace range: assigned range or NoRange (unused identifier).
  /\ regionKeyRange \in
         [Regions
         ->
         [startKey:0 .. MaxKey, endKey:0 .. MaxKey ] \cup { NoRange }] \* Parent procedure progress per region.
       /\
       parentProc \in [Regions -> ParentProcRecord]

---------------------------------------------------------------------------

(* Safety invariants *)
\* A region that is OPEN must have a location.
OpenImpliesLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( regionState[r].state = "OPEN" => regionState[r].location # NoServer
          )
    )

\* A region that is OFFLINE, CLOSED, FAILED_OPEN, ABNORMALLY_CLOSED,
\* SPLIT, or SPLITTING_NEW must NOT have a location.
OfflineImpliesNoLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( regionState[r].state \in
                { "OFFLINE",
                  "CLOSED",
                  "FAILED_OPEN",
                  "ABNORMALLY_CLOSED",
                  "SPLIT",
                  "SPLITTING_NEW"
                } =>
              regionState[r].location = NoServer
          )
    )

\* The persistent state in hbase:meta matches the in-memory state for
\* the fields that meta tracks (state and location).  Procedure fields
\* are master in-memory only and are not compared.
\*
\* One permitted divergence: GoOffline sets regionState to OFFLINE
\* without writing to meta, so metaTable may retain CLOSED while
\* regionState shows OFFLINE.  Both are "unassigned" states with no
\* location; the divergence is resolved on master restart when
\* in-memory state is rebuilt from meta.
\*
\* Becomes further non-trivial when master crash is introduced:
\* metaTable survives but regionState is lost and must be rebuilt.
MetaConsistency ==
  masterAlive = TRUE =>
    \A r \in Regions:
      RegionExists(r) => \/ /\ metaTable[r].state = regionState[r].state
                            /\ metaTable[r].location = regionState[r].location
                         \/ /\ regionState[r].state = "OFFLINE"
                            /\ metaTable[r].state = "CLOSED"
                            /\ regionState[r].location = NoServer
                            /\ metaTable[r].location = NoServer
                         \* Active procedure window: in-memory state may
                         \* diverge from metaTable during TRSP execution.
                         \* E.g., TRSPReportSucceedOpen sets state to OPEN
                         \* while meta still shows OPENING; TRSPPersistToMeta
                         \* retry resets to GET_ASSIGN_CANDIDATE while meta
                         \* retains OPENING.  Divergence is always resolved
                         \* when the procedure completes or is cleared.
                         \/ regionState[r].procType # "NONE"
                         \* Parent procedure window: parentProc tracks
                         \* the parent; procType/procStep temporarily
                         \* reflect child TRSPs.
                         \* split is still in progress.
                         \/ parentProc[r].type # "NONE"
                         \* SPLITTING_NEW daughters: in-memory is SPLITTING_NEW
                         \* while meta is CLOSED (Appendix C.8).
                         \/ /\ regionState[r].state = "SPLITTING_NEW"
                            /\ metaTable[r].state = "CLOSED"

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
\*   cleared at OPEN.  During the REPORT_SUCCEED window the region may
\*   briefly show OPEN or FAILED_OPEN while the ASSIGN procedure is still
\*   attached (meta has not been written yet).
\* UNASSIGN attaches during OPEN and the region transitions through CLOSING
\*   before the procedure is cleared at CLOSED.  During REPORT_SUCCEED the
\*   region may briefly show CLOSED while the UNASSIGN procedure is still
\*   attached.
\* MOVE attaches during OPEN and drives the region through CLOSING, CLOSED,
\*   OPENING before being cleared at OPEN.
\* Any type may be found on an ABNORMALLY_CLOSED region if a server
\*   crash occurs while the procedure is in flight.
\*
\* Source: RegionStateNode.setProcedure(),
\*         RegionStateNode.unsetProcedure().
LockExclusivity ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( regionState[r].procType # "NONE" =>
              \/ /\ regionState[r].procType = "ASSIGN"
                 /\ regionState[r].state \in
                      { "OFFLINE",
                        "CLOSED",
                        "ABNORMALLY_CLOSED",
                        "FAILED_OPEN",
                        "OPENING",
                        "OPEN",
                        "SPLITTING_NEW"
                      \* SPLITTING_NEW: daughters enter ASSIGN via
                      \* SplitUpdateMeta, then TRSPGetCandidate picks a
                      \* server, TRSPDispatchOpen transitions to OPENING.
                      }
              \/ /\ regionState[r].procType = "UNASSIGN"
                 /\ regionState[r].state \in
                      { "OPEN",
                        "SPLITTING",
                        "CLOSING",
                        "ABNORMALLY_CLOSED",
                        "CLOSED"
                      \* SPLITTING: split yields parent to UNASSIGN
                      \* (SplitPrepare sets procType=UNASSIGN while
                      \* parent is still in SPLITTING state).
                      }
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
              \/ /\ regionState[r].procType = "SPLIT"
                 /\ regionState[r].state \in
                      { "CLOSED",
                        "SPLIT",
                        "SPLITTING_NEW"
                      \* CLOSED: parent after close, before PONR.
                      \* SPLIT: parent after PONR.
                      \* SPLITTING_NEW: daughter lock before open yield.
                      }
          )
    )

\* When a region is stably OPEN (no procedure attached) on an ONLINE
\* server, the RS hosting it must also consider it online.  This
\* cross-checks the master's view (regionState) against the RS's view
\* (rsOnlineRegions).
\*
\* The invariant holds because TRSPPersistToMetaOpen (which sets OPEN and
\* clears the procedure) requires an OPENED report, which is only
\* produced by RSOpen after adding the region to rsOnlineRegions.
\*
\* CRASHED servers are exempted: during the zombie window (between
\* MasterDetectCrash and SCPAssignRegion), regionState may still show
\* OPEN while rsOnlineRegions has been cleared by RSAbort.
\*
\* Servers whose ZK ephemeral node has expired (zkNode[s] = FALSE) are
\* also exempted: during the window between ZKSessionExpire and
\* MasterDetectCrash, the master still thinks the server is ONLINE but
\* the RS has already shut down.
RSMasterAgreement ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( ( regionState[r].state = "OPEN" /\ regionState[r].procType = "NONE" /\
                      regionState[r].location # NoServer /\
                    serverState[regionState[r].location] = "ONLINE" /\
                  zkNode[regionState[r].location] = TRUE
              ) =>
              r \in rsOnlineRegions[regionState[r].location]
          )
    )

\* Converse of RSMasterAgreement: if an RS considers a region online,
\* the master must agree on both location and lifecycle state.  Catches
\* "ghost regions" where an RS believes it hosts a region the master
\* has already reassigned or crashed.
\*
\* OPENING is included because RSOpen adds the region to
\* rsOnlineRegions before TRSPPersistToMetaOpen transitions the master
\* state from OPENING to OPEN.
\*
\* CRASHED servers are exempted: during the zombie window (between
\* MasterDetectCrash and RSAbort), the master has cleared the crashed
\* RS's location but rsOnlineRegions has not yet been wiped.  The
\* master no longer considers the crashed server authoritative.
\*
\* Servers with zkNode[s] = FALSE are also exempted: the RS is dead
\* (ZK session expired) but the master may not yet have processed
\* the crash notification.
RSMasterAgreementConverse ==
  masterAlive = TRUE =>
    \A s \in Servers:
      \A r \in rsOnlineRegions[s]:
        serverState[s] = "CRASHED" \/ zkNode[s] = FALSE \/
          /\ regionState[r].location = s
          /\ regionState[r].state \in
               { "OPENING", "OPEN", "CLOSING", "SPLITTING" }

\* SCP does not reassign regions (scpState = "ASSIGN") until WAL
\* leases have been revoked (walFenced = TRUE).  Verified by
\* construction (SCP state machine: FENCE_WALS precedes ASSIGN)
\* but stated explicitly as a safety net.
FencingOrder == \A s \in Servers: scpState[s] = "ASSIGN" => walFenced[s] = TRUE

\* A meta-carrying SCP must reassign meta (complete ASSIGN_META)
\* before proceeding to GET_REGIONS.  If carryingMeta[s] is TRUE,
\* the SCP should not have progressed past ASSIGN_META.  The
\* meta-online guard on SCPGetRegions/SCPFenceWALs/SCPAssignRegion
\* prevents any SCP from executing while ASSIGN_META is pending,
\* but MasterDetectCrash may set GET_REGIONS for non-meta crashes;
\* the key safety property is that the meta-carrying server itself
\* cannot skip ASSIGN_META.
\*
\* Source: SCP.waitMetaLoaded();
\*         SCP.executeFromState() ASSIGN_META case.
MetaAvailableForRecovery ==
  \A s \in Servers:
    carryingMeta[s] = TRUE => scpState[s] \in { "NONE", "ASSIGN_META", "DONE" }

\* AtMostOneCarryingMeta: hbase:meta is hosted on exactly one server,
\* so at most one crashed server can be carrying meta at any time.
\* MasterDetectCrash guards the carryingMeta=TRUE branch to enforce
\* this; the invariant is an explicit cross-check.
\*
\* Source: hbase:meta single-assignment semantics.
AtMostOneCarryingMeta ==
  Cardinality({s \in Servers: carryingMeta[s] = TRUE}) <= 1

\* After any SCP completes, no region is stuck without a procedure and
\* without an SCP that will process it.  A region is lost when it is
\* in a non-terminal state with no active procedure and no coverage
\* from any SCP snapshot.  Two cases:
\*
\*   (1) ABNORMALLY_CLOSED with no procedure: SCP should have
\*       attached an ASSIGN procedure.  If it skipped the region
\*       (e.g., due to the isMatchingRegionLocation check), the
\*       region is lost.
\*   (2) Non-terminal mid-transition (OPENING, CLOSING) with
\*       location=None, no procedure, and not in any SCP's snapshot:
\*       this region is stranded mid-transition with no active owner.
\*       This can occur when a location-check skip combined with a
\*       concurrent TRSP clearing the location leaves the region
\*       untracked.  OFFLINE is excluded: it is a legitimate quiescent
\*       state (initial state or post-GoOffline).
NoLostRegions ==
  masterAlive = TRUE =>
    ( ( \E s \in Servers: scpState[s] = "DONE" ) =>
        \A r \in Regions:
          RegionExists(r) =>
            /\ ( regionState[r].state = "ABNORMALLY_CLOSED" =>
                   regionState[r].procType # "NONE"
               )
            /\ ( ( /\ regionState[r].state \in { "OPENING", "CLOSING" }
                   /\ regionState[r].location = NoServer
                   /\ regionState[r].procType = "NONE" ) =>
                   ( \E s \in Servers: r \in scpRegions[s] )
               )
    )

\* Every active in-memory procedure has a matching persisted record,
\* and every persisted record corresponds to an active in-memory
\* procedure.  This invariant will require relaxation in iteration 18
\* when MasterCrash clears in-memory state but preserves procStore.
\*
\* Source: ProcedureExecutor lifecycle -- insert on creation,
\*         update on each step, delete on completion.
\* Intra-record correlation invariant for procStore records. Defined
\* in ProcStore.tla; always checked regardless of masterAlive.
ProcStoreConsistency == ps!ProcStoreConsistency

\* Bijection: active in-memory procedures <=> persisted records.
\* Gated on masterAlive (in-memory state doesn't exist when master is down).
ProcStoreBijection == ps!ProcStoreBijection

\* TRSP procStep must correlate with the region's lifecycle state.
\*
\*   GET_ASSIGN_CANDIDATE: region is idle or mid-transition
\*     (OFFLINE, CLOSED, ABNORMALLY_CLOSED, FAILED_OPEN, or OPENING
\*     if DispatchFail reset the step while the region was still OPENING).
\*   OPEN: target chosen, region not yet transitioned to OPENING
\*     (same set as GET_ASSIGN_CANDIDATE -- TRSPDispatchOpen will move it).
\*   CONFIRM_OPENED: region is OPENING (TRSPDispatchOpen transitioned it).
\*   CLOSE: region is OPEN or CLOSING (CLOSING on retry after DispatchFailClose),
\*     or ABNORMALLY_CLOSED (TRSPConfirmClosed Path 2 re-routes to close).
\*   CONFIRM_CLOSED: region is CLOSING or ABNORMALLY_CLOSED.
\*
\* Source: TRSP.executeFromState() action guards.
ProcStepConsistency ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( regionState[r].procType \in
                { "ASSIGN", "UNASSIGN", "MOVE", "REOPEN" } =>
              /\ ( regionState[r].procStep = "GET_ASSIGN_CANDIDATE" =>
                     regionState[r].state \in
                       { "OFFLINE",
                         "CLOSED",
                         "ABNORMALLY_CLOSED",
                         "FAILED_OPEN",
                         "OPENING",
                         "SPLITTING_NEW"
                       \* SPLITTING_NEW: daughter in ASSIGN at
                       \* GET_ASSIGN_CANDIDATE (SplitUpdateMeta).
                       }
                 )
              /\ ( regionState[r].procStep = "OPEN" =>
                     regionState[r].state \in
                       { "OFFLINE",
                         "CLOSED",
                         "ABNORMALLY_CLOSED",
                         "FAILED_OPEN",
                         "OPENING",
                         "SPLITTING_NEW"
                       }
                 )
              /\ ( regionState[r].procStep = "CONFIRM_OPENED" =>
                     regionState[r].state = "OPENING"
                 )
              /\ ( regionState[r].procStep = "CLOSE" =>
                     regionState[r].state \in
                       { "OPEN", "SPLITTING", "CLOSING", "ABNORMALLY_CLOSED" }
                 \* SPLITTING: split-yielded UNASSIGN starts from SPLITTING.
                 )
              /\ ( regionState[r].procStep = "CONFIRM_CLOSED" =>
                     regionState[r].state \in { "CLOSING", "ABNORMALLY_CLOSED" }
                 )
              /\ ( regionState[r].procStep = "REPORT_SUCCEED" =>
                     regionState[r].state \in
                       { "OPEN", "OPENING", "FAILED_OPEN", "CLOSED" }
                 )
          )
    )

\* targetServer presence/absence correlates with procStep.
\* At GET_ASSIGN_CANDIDATE, no candidate has been chosen yet.
\* At OPEN, CONFIRM_OPENED, and CONFIRM_CLOSED, a target must exist.
\*
\* Source: TRSPGetCandidate sets targetServer;
\*         TRSPCreate/TRSPPersistToMetaOpen/TRSPServerCrashed clear it.
TargetServerConsistency ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( regionState[r].procType \in
                { "ASSIGN", "UNASSIGN", "MOVE", "REOPEN" } =>
              /\ ( regionState[r].procStep = "GET_ASSIGN_CANDIDATE" =>
                     regionState[r].targetServer = NoServer
                 )
              /\ ( regionState[r].procStep \in
                       { "OPEN",
                         "CONFIRM_OPENED",
                         "CONFIRM_CLOSED",
                         "REPORT_SUCCEED"
                       } =>
                     regionState[r].targetServer # NoServer
                 )
          )
    )

\* A region in OPENING state always has a non-None location.
\* TRSPDispatchOpen atomically sets state=OPENING and location=targetServer.
\*
\* Source: TRSPDispatchOpen -- the only action that creates OPENING state.
OpeningImpliesLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( regionState[r].state = "OPENING" =>
              regionState[r].location # NoServer
          )
    )

\* A region in CLOSING state always has a non-None location.
\* TRSPDispatchClose transitions to CLOSING while preserving the existing
\* location; clearing the location only happens at TRSPConfirmClosed Path 1
\* (CLOSING -> CLOSED) or SCPAssignRegion (CLOSING -> ABNORMALLY_CLOSED).
\*
\* Source: TRSPDispatchClose -- preserves location on CLOSING transition.
ClosingImpliesLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( regionState[r].state = "CLOSING" =>
              regionState[r].location # NoServer
          )
    )

\* For a region that has a known location, no active procedure, and whose
\* server is not CRASHED, the serverRegions tracking must include it.
\* This validates the independent serverRegions variable (which SCP reads
\* for its snapshot) against the authoritative regionState.location.
\*
\* Active procedures are exempt because during TRSP execution,
\* serverRegions and location may temporarily desynchronize
\* (regionOpening adds to new server without removing from old).
\*
\* Source: AM.regionOpening(), AM.regionClosedWithoutPersisting(),
\*         AM.regionFailedOpen(), AM.regionClosedAbnormally().
ServerRegionsTrackLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( ( /\ regionState[r].location # NoServer
              /\ regionState[r].procType = "NONE"
              /\ serverState[regionState[r].location] # "CRASHED" ) =>
              r \in serverRegions[regionState[r].location]
          )
    )

\* Every dispatched command for a region corresponds to an active
\* procedure on that region, or the target server is CRASHED (stale
\* commands awaiting cleanup by RSAbort/ServerRestart).  Catches
\* orphaned commands that could cause ghost opens/closes.
\*
\* Source: TRSP dispatch actions produce commands only when a procedure
\*         is active; RS consume actions and RSAbort/ServerRestart clean up.
DispatchCorrespondance ==
  masterAlive = TRUE =>
    \A s \in Servers:
      \A cmd \in dispatchedOps[s]: \/ regionState[cmd.region].procType # "NONE"
                                   \/ serverState[s] = "CRASHED"

\* A procedure-bearing region must never be in OFFLINE state.
\* OFFLINE is a quiescent state entered only by GoOffline (which
\* requires procType = NONE).  TRSPCreate may attach to OFFLINE
\* but it does not SET the region to OFFLINE--it attaches ASSIGN
\* at GET_ASSIGN_CANDIDATE while the region stays OFFLINE until
\* TRSPDispatchOpen transitions it to OPENING.  Wait--that means
\* a region CAN be OFFLINE with an ASSIGN procedure attached
\* (between TRSPCreate and TRSPDispatchOpen).  This is correct
\* behavior; the invariant below accounts for it.
\*
\* Source: RegionStateNode.offline(); TRSPCreate action guards.
NoOrphanedProcedures ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        RegionExists(r) =>
          ( ( regionState[r].state = "OFFLINE" /\
                  regionState[r].procType # "NONE"
              ) =>
              regionState[r].procType = "ASSIGN"
          )
    )

\* NoPEWorkerDeadlock: When the master is alive and all PEWorkers are
\* consumed while meta is unavailable, there must exist at least one
\* active procedure that is neither suspended nor blocked on meta.
\* If this invariant fails, all workers are tied up on meta writes and
\* no progress can be made -- a thread-pool exhaustion deadlock.
\*
\* With UseBlockOnMetaWrite = FALSE (default), this should always hold
\* because async suspension releases the PEWorker immediately.
\* With UseBlockOnMetaWrite = TRUE (branch-2.6), violations are EXPECTED
\* and represent genuine deadlock scenarios.
NoPEWorkerDeadlock ==
  masterAlive = TRUE =>
    ( ( availableWorkers = 0 /\ ~MetaIsAvailable ) =>
        \E r \in Regions: /\ regionState[r].procType # "NONE"
                          /\ r \notin blockedOnMeta
                          /\ r \notin suspendedOnMeta
    )

\* Action constraint: SCP state machine transitions are strictly monotonic.
\* The SCP never moves backward; only forward along the defined sequence,
\* plus the DONE->NONE reset on ServerRestart.
\*
\* Gated on masterAlive in both current and next state: during master
\* crash/recovery, scpState is reset from stale values (MasterCrash
\* preserves them as UNCHANGED; MasterRecover resets based on ZK
\* re-discovery), which are not normal SCP forward-progress transitions.
\*
\* Source: SCP state machine actions in SCP.tla, ServerRestart in
\*         Master.tla.
SCPMonotonicity ==
  ( masterAlive /\ masterAlive' ) =>
    \A s \in Servers:
      scpState'[s] # scpState[s] =>
        << scpState[s], scpState'[s] >> \in
          { << "NONE", "ASSIGN_META" >>,
            << "NONE", "GET_REGIONS" >>,
            << "ASSIGN_META", "GET_REGIONS" >>,
            << "GET_REGIONS", "FENCE_WALS" >>,
            << "FENCE_WALS", "ASSIGN" >>,
            << "ASSIGN", "DONE" >>,
            << "DONE", "NONE" >>
          }


\* KeyspaceCoverage: every key in [0, MaxKey) is covered by exactly
\* one live region.  A region covers a key if:
\*   1. It exists (regionKeyRange # NoRange)
\*   2. The key falls within its range [startKey, endKey)
\*   3. It is not in a terminal split/merge state (SPLIT, MERGED)
\*
\* SPLITTING_NEW and MERGING_NEW regions ARE counted as covering
\* their keyspaces -- they have been materialized at PONR and will
\* become OPEN when their child TRSP completes.
\*
\* During a split: the parent's keyspace is preserved until SplitDone
\* clears it to NoRange.  At PONR, the parent transitions to SPLIT
\* state (excluded from coverage), and daughters are materialized in
\* SPLITTING_NEW (included in coverage).  So coverage is maintained
\* across the PONR boundary.
KeyspaceCoverage ==
  \A k \in 0 .. ( MaxKey - 1 ):
    Cardinality({r \in Regions:
          /\ RegionExists(r)
          /\ regionKeyRange[r].startKey <= k
          /\ k < regionKeyRange[r].endKey
          /\ regionState[r].state \notin { "SPLIT", "MERGED" }
        }) =
      1

\* SplitMergeMutualExclusion: per-region mutual exclusion.
\*   1. A daughter region (SPLITTING_NEW) cannot be a parent
\*      procedure target (parentProc is for parents; daughters
\*      are protected by SplitPrepare's state=OPEN guard).
\*   2. No region has concurrent split and merge (trivially true:
\*      no merge actions in this iteration).
\*
\* Multiple disjoint splits CAN occur in parallel (faithful to
\* the implementation).  The SplitMergeConstraint state constraint
\* bounds total concurrent splits for TLC tractability.
SplitMergeMutualExclusion ==
  masterAlive = TRUE =>
    \A r \in Regions:
      \* A daughter cannot also be tracked as a parent procedure target.
            regionState[r].state = "SPLITTING_NEW" =>
        parentProc[r] = NoParentProc

\* SplitAtomicity: pre-PONR, daughters of this parent are not yet
\* materialized.  If the split parent is in SPAWNED_CLOSE phase,
\* no SPLITTING_NEW daughters whose keyspace is carved from this
\* parent's range should exist.  Scoped per-parent so that concurrent
\* splits on disjoint regions do not falsely trigger the invariant.
SplitAtomicity ==
  masterAlive = TRUE =>
    \A r \in Regions:
      parentProc[r].step = "SPAWNED_CLOSE" =>
        LET startK == regionKeyRange[r].startKey
            endK == regionKeyRange[r].endKey
            mid == ( startK + endK ) \div 2
        IN ~\E d \in Regions:
              /\ regionState[d].state = "SPLITTING_NEW"
              /\ RegionExists(d)
              /\ \/ regionKeyRange[d] = [ startKey |-> startK, endKey |-> mid ]
                 \/ regionKeyRange[d] = [ startKey |-> mid, endKey |-> endK ]

\* NoOrphanedDaughters: a region in SPLITTING_NEW state always has
\* an ASSIGN procedure (child TRSP from SplitUpdateMeta).
NoOrphanedDaughters ==
  masterAlive = TRUE =>
    \A r \in Regions:
      ( RegionExists(r) /\ regionState[r].state = "SPLITTING_NEW" ) =>
        regionState[r].procType = "ASSIGN"

\* SplitCompleteness: after a split completes (parent is SPLIT with
\* NoRange, meaning SplitDone has fired), the daughters' keyspaces
\* exist and are correct.  This is a post-condition check: if the
\* parent has been cleaned up, the daughters should be OPEN.
\* Gated on no active SCP (SCP may disrupt daughter assignments).
SplitCompleteness ==
  masterAlive = TRUE =>
    ( ( \A s \in Servers: scpState[s] = "NONE" ) =>
        \A r \in Regions:
          ( regionState[r].state = "SPLIT" /\ ~RegionExists(r) ) =>
            parentProc[r] = NoParentProc
    )

(* State constraints for TLC *)
\* Symmetry reduction: only unused region identifiers are interchangeable
\* (deployed regions have distinct keyspaces).  Servers remain
\* interchangeable.  With 2 unused identifiers this provides up to
\* 2x reduction.
Symmetry == Permutations(Regions \ DeployedRegions) \union Permutations(Servers)

\* State constraint: bound concurrent split/merge procedures.
\* Limits to at most 1 concurrent split to keep the state space
\* tractable for TLC.  Multiple disjoint splits are permitted by
\* the specification (faithful to implementation); this constraint
\* is purely a model-checking optimization.  With 2 deployed + 2
\* unused regions, at most 1 split is physically possible anyway
\* (each split consumes 2 unused identifiers).
SplitMergeConstraint ==
  Cardinality({r \in Regions: parentProc[r] # NoParentProc}) <= 1

---------------------------------------------------------------------------

(* Initial state *)
Init ==
  \* DeployedRegions start OFFLINE with assigned keyspaces;
  \* unused identifiers start OFFLINE with NoRange.
  /\ regionState =
       [r \in Regions |->
         [ state |-> "OFFLINE",
           location |-> NoServer,
           procType |-> "NONE",
           procStep |-> "IDLE",
           targetServer |-> NoServer,
           retries |-> 0
         ]
       ]
  \* META table mirrors the initial in-memory state: all regions OFFLINE.
  /\ metaTable =
       [r \in Regions |-> [ state |-> "OFFLINE", location |-> NoServer ]
       ]
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
  /\ serverRegions = [s \in Servers |-> {}]
  \* No procedures are persisted initially.
  /\ procStore = [r \in Regions |-> NoProcedure]
  \* Master starts alive.
  /\ masterAlive = TRUE
  \* All servers have live ZK ephemeral nodes at startup.
  /\ zkNode = [s \in Servers |-> TRUE]
  \* All PEWorker threads are available at startup.
  /\ availableWorkers = MaxWorkers
  \* No procedures are suspended or blocked on meta.
  /\ suspendedOnMeta = {}
  /\ blockedOnMeta = {}
  \* Keyspace tiling: DeployedRegions tile [0, MaxKey) evenly.
  \* Unused identifiers get NoRange.
  \* The tiling assigns contiguous, equal-width sub-ranges to deployed
  \* regions.  Each deployed region r gets [rank * width, (rank+1) * width)
  \* where rank is its position in an arbitrary bijection and
  \* width = MaxKey div |DeployedRegions|.
  /\ LET n == Cardinality(DeployedRegions)
         width == MaxKey \div n
         \* Bijection from DeployedRegions to 0..(n-1).  CHOOSE picks
         \* an arbitrary injective function (TLC-compatible: no ordering
         \* on model values required).
         rank ==
           CHOOSE f \in [DeployedRegions -> 0 .. ( n - 1 )]:
             \A r1, r2 \in DeployedRegions: r1 # r2 => f[r1] # f[r2]
     IN regionKeyRange =
           [r \in Regions |->
             IF r \in DeployedRegions
             THEN [ startKey |-> rank[r] * width,
                 endKey |-> ( rank[r] + 1 ) * width
               ]
             ELSE NoRange
           ]
  \* No parent procedures are in progress.
  /\ parentProc = [r \in Regions |-> NoParentProc]

---------------------------------------------------------------------------

(* Next-state relation *)
Next ==
  \* -- ASSIGN path --
  \/ \E r \in Regions: trsp!TRSPCreate(r)
  \/ \E r \in Regions: \E s \in Servers: trsp!TRSPGetCandidate(r, s)
  \/ \E r \in Regions: trsp!TRSPDispatchOpen(r)
  \/ \E r \in Regions: trsp!TRSPReportSucceedOpen(r)
  \/ \E r \in Regions: trsp!TRSPPersistToMetaOpen(r)
  \/ \E r \in Regions: trsp!DispatchFail(r)
  \* -- UNASSIGN path --
  \/ \E r \in Regions: trsp!TRSPCreateUnassign(r)
  \* -- MOVE path --
  \/ \E r \in Regions: trsp!TRSPCreateMove(r)
  \* -- REOPEN path (branch-2.6 only, controlled by UseReopen) --
  \/ \E r \in Regions: trsp!TRSPCreateReopen(r)
  \/ \E r \in Regions: trsp!TRSPDispatchClose(r)
  \/ \E r \in Regions: trsp!TRSPReportSucceedClose(r)
  \/ \E r \in Regions: trsp!TRSPPersistToMetaClose(r)
  \/ \E r \in Regions: trsp!TRSPConfirmClosedCrash(r)
  \/ \E r \in Regions: trsp!DispatchFailClose(r)
  \* -- External events --
  \/ \E r \in Regions: master!GoOffline(r)
  \/ \E s \in Servers: master!MasterDetectCrash(s)
  \/ \E s \in Servers: rs!RSRestart(s)
  \* -- Crash recovery --
  \/ \E r \in Regions: trsp!TRSPServerCrashed(r)
  \* -- PEWorker meta-resume --
  \/ \E r \in Regions: trsp!ResumeFromMeta(r)
  \* -- RS abort (zombie shutdown) --
  \/ \E s \in Servers: rs!RSAbort(s)
  \* -- SCP state machine --
  \/ \E s \in Servers: scp!SCPAssignMeta(s)
  \/ \E s \in Servers: scp!SCPGetRegions(s)
  \/ \E s \in Servers: scp!SCPFenceWALs(s)
  \/ \E s \in Servers: \E r \in Regions: scp!SCPAssignRegion(s, r)
  \/ \E s \in Servers: scp!SCPDone(s)
  \* -- Stale report cleanup --
  \/ rs!DropStaleReport
  \* -- RS-side open handler --
  \/ \E s \in Servers: \E r \in Regions: rs!RSOpen(s, r)
  \/ \E s \in Servers: \E r \in Regions: rs!RSFailOpen(s, r)
  \* -- RS-side close handler --
  \/ \E s \in Servers: \E r \in Regions: rs!RSClose(s, r)
  \* -- RS-side duplicate open handler (conditional) --
  \/ \E s \in Servers: \E r \in Regions: rs!RSOpenDuplicate(s, r)
  \* -- Master crash and recovery --
  \/ master!MasterCrash
  \/ master!MasterRecover
  \* -- ZK session expiry --
  \/ \E s \in Servers: zk!ZKSessionExpire(s)
  \* -- Split forward path --
  \/ \E r \in Regions: split!SplitPrepare(r)
  \/ \E r \in Regions: split!SplitResumeAfterClose(r)
  \/ \E r \in Regions: \E dA, dB \in Regions: split!SplitUpdateMeta(r, dA, dB)
  \/ \E r \in Regions: split!SplitDone(r)

---------------------------------------------------------------------------

(* Fairness *)
\* Weak fairness on deterministic actions ensures forward progress.
\* Procedure-step actions, crash-recovery actions, SCP state machine
\* steps, and RS-side processing are all deterministic once enabled and
\* therefore receive WF.  Non-deterministic environmental events
\* (DispatchFail, DispatchFailClose, MasterDetectCrash, RSFailOpen,
\* GoOffline) receive no fairness -- they may occur but are not
\* required to.
Fairness ==
  \* Procedure invocations
  \* Note: No WF on TRSPCreate: In the implementation, no automatic
  \* process creates ASSIGN procedures for lost regions.
  /\ \A r \in Regions: WF_vars(trsp!TRSPCreateUnassign(r))
  /\ \A r \in Regions: WF_vars(trsp!TRSPCreateMove(r))
  /\ ( UseReopen => \A r \in Regions: WF_vars(trsp!TRSPCreateReopen(r)) )
  /\ \A s \in Servers: WF_vars(rs!RSRestart(s))
  \* Deterministic procedure steps
  /\ \A r \in Regions: \A s \in Servers: WF_vars(trsp!TRSPGetCandidate(r, s))
  /\ \A r \in Regions: WF_vars(trsp!TRSPDispatchOpen(r))
  /\ \A r \in Regions: WF_vars(trsp!TRSPReportSucceedOpen(r))
  /\ \A r \in Regions: WF_vars(trsp!TRSPPersistToMetaOpen(r))
  /\ \A r \in Regions: WF_vars(trsp!TRSPDispatchClose(r))
  /\ \A r \in Regions: WF_vars(trsp!TRSPReportSucceedClose(r))
  /\ \A r \in Regions: WF_vars(trsp!TRSPPersistToMetaClose(r))
  /\ \A r \in Regions: WF_vars(trsp!TRSPConfirmClosedCrash(r))
  \* Crash recovery
  /\ \A r \in Regions: WF_vars(trsp!TRSPServerCrashed(r))
  \* PEWorker meta-resume
  /\ \A r \in Regions: WF_vars(trsp!ResumeFromMeta(r))
  /\ WF_vars(rs!DropStaleReport)
  \* RS abort (zombie eventually shuts down)
  /\ \A s \in Servers: WF_vars(rs!RSAbort(s))
  \* Master eventually recovers after crash.
  /\ WF_vars(master!MasterRecover)
  \* ZK session expiry (eventually cleans up dead server nodes).
  /\ \A s \in Servers: WF_vars(zk!ZKSessionExpire(s))
  \* No fairness on MasterCrash (non-deterministic environmental event).
  \* No fairness on RSOpenDuplicate (fires non-deterministically; conditional).
  \* SCP state machine
  /\ \A s \in Servers: WF_vars(scp!SCPAssignMeta(s))
  /\ \A s \in Servers: WF_vars(scp!SCPGetRegions(s))
  /\ \A s \in Servers: WF_vars(scp!SCPFenceWALs(s))
  /\ \A s \in Servers: \A r \in Regions: WF_vars(scp!SCPAssignRegion(s, r))
  /\ \A s \in Servers: WF_vars(scp!SCPDone(s))
  \* RS-side processing
  /\ \A s \in Servers: \A r \in Regions: WF_vars(rs!RSOpen(s, r))
  /\ \A s \in Servers: \A r \in Regions: WF_vars(rs!RSClose(s, r))
  \* Split forward path (deterministic steps; no WF on SplitPrepare)
  /\ \A r \in Regions: WF_vars(split!SplitResumeAfterClose(r))
  /\ \A r \in Regions:
       \A dA, dB \in Regions: WF_vars(split!SplitUpdateMeta(r, dA, dB))
  /\ \A r \in Regions: WF_vars(split!SplitDone(r))

---------------------------------------------------------------------------

(* Specification *)
Spec == Init /\ [][Next]_vars /\ Fairness

\* Liveness: when meta becomes unavailable (a server enters ASSIGN_META),
\* the SCP eventually completes meta assignment and MetaIsAvailable
\* becomes TRUE again.  This ensures suspended/blocked procedures
\* are eventually able to resume.
\*
\* Source: The SCP state machine has WF on all steps including
\*         SCPAssignMeta, so meta assignment always completes.
MetaEventuallyAssigned ==
  \A s \in Servers: scpState[s] = "ASSIGN_META" ~> MetaIsAvailable

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
        THEOREM Spec => []FencingOrder

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

\* Safety: procStep correlates with region lifecycle state.
        THEOREM Spec => []ProcStepConsistency

\* Safety: targetServer presence correlates with procStep.
        THEOREM Spec => []TargetServerConsistency

\* Safety: OPENING region always has a location.
        THEOREM Spec => []OpeningImpliesLocation

\* Safety: CLOSING region always has a location.
        THEOREM Spec => []ClosingImpliesLocation

\* Safety: serverRegions tracks location for stable regions.
        THEOREM Spec => []ServerRegionsTrackLocation

\* Safety: dispatched commands have corresponding procedures.
        THEOREM Spec => []DispatchCorrespondance

\* Safety: persisted procedure records are internally consistent.
        THEOREM Spec => []ProcStoreConsistency

\* Safety: in-memory procedures match persisted records (when master alive).
        THEOREM Spec => []ProcStoreBijection

\* Safety: OFFLINE procedure-bearing regions are ASSIGN only.
        THEOREM Spec => []NoOrphanedProcedures

\* Safety: live regions' keyspaces cover [0, MaxKey) with no gaps or overlaps.
        THEOREM Spec => []KeyspaceCoverage

\* Safety: at most one split/merge procedure at a time.
        THEOREM Spec => []SplitMergeMutualExclusion

\* Safety: pre-PONR daughters not materialized.
        THEOREM Spec => []SplitAtomicity

\* Safety: SPLITTING_NEW daughters always have a procedure.
        THEOREM Spec => []NoOrphanedDaughters

\* Safety: completed split has cleaned-up parent.
        THEOREM Spec => []SplitCompleteness

\* Safety: at most one server carrying meta.
        THEOREM Spec => []AtMostOneCarryingMeta

\* All transitions in every step are members of ValidTransition.
\* Expressed as an action property checked via TLC's action constraint.
\* Gated on masterAlive: MasterRecover rebuilds regionState from
\* durable storage, which is a state reconstruction, not a normal
\* state machine transition.
TransitionValid ==
  ( masterAlive /\ masterAlive' ) =>
    \A r \in Regions:
      RegionExists(r) =>
        ( regionState'[r].state # regionState[r].state =>
            << regionState[r].state, regionState'[r].state >> \in
              ValidTransition
        )

============================================================================
