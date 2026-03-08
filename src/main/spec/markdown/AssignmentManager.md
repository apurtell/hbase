# AssignmentManager

**Source:** [`AssignmentManager.tla`](../AssignmentManager.tla)

Root orchestrator module for the HBase AssignmentManager TLA+ specification. Declares all 17 state variables, instantiates action sub-modules (TRSP, SCP, RegionServer, Master, ProcStore, ZK), defines 21 safety invariants, 2 action constraints, Init, Next, Fairness, and the top-level Spec.

## Design Overview

Two parallel views of region state are maintained:

- **`regionState`** — volatile in-memory master state (lost on master crash)
- **`metaTable`** — persistent state in `hbase:meta` (survives master crash)

Procedure state is inlined into `regionState`, keyed by region rather than by a global procedure ID counter. At most one procedure can be attached per region. Procedure fields (`procType`, `procStep`, `targetServer`, `retries`) model the `RegionStateNode.setProcedure()` / `unsetProcedure()` discipline.

Two RPC channels model the asynchronous communication:

- **`dispatchedOps`** — master→RS command channel (per server)
- **`pendingReports`** — RS→master report channel

Commands and reports are matched by region (not by procedure ID).

RS-side receive and complete steps are merged into single atomic actions because the intermediate RS state is not observable by the master.

Server liveness is tracked by `serverState`. RS crash is decomposed into a multi-step **ServerCrashProcedure** (SCP):

1. `MasterDetectCrash(s)` — master marks server CRASHED, starts SCP
2. `SCPAssignMeta(s)` — if `carryingMeta`, reassign meta first
3. `SCPGetRegions(s)` — snapshot regions on the crashed server
4. `SCPFenceWALs(s)` — revoke WAL leases (prevents zombie writes)
5. `SCPAssignRegion(s, r)` — process regions one at a time
6. `SCPDone(s)` — all regions processed

---

## Module Declaration

```tla
------------------------ MODULE AssignmentManager -------------------------
EXTENDS Types
```

---

## Variables

### regionState

Volatile in-memory master state per region. Record with fields: `state`, `location`, `procType`, `procStep`, `targetServer`, `retries`.

The procedure fields model `RegionStateNode.setProcedure()` / `unsetProcedure()`: at most one procedure per region. When `procType = "NONE"`, `procStep = "IDLE"`.

```tla
VARIABLE regionState
```

### metaTable

Persistent state in `hbase:meta`. Record `[state, location]` per region. Survives master crash; `regionState` does not. Procedure fields are NOT persisted to meta — procedures are recovered from `procStore` on restart.

```tla
VARIABLE metaTable
```

### dispatchedOps

Set of command records `[type, region]` pending delivery to each server. Models the master→RS command channel (`RSProcedureDispatcher`). Commands remain until consumed by RS or discarded on failure/crash.

```tla
VARIABLE dispatchedOps
```

### pendingReports

Set of report records `[server, region, code]` from RegionServers waiting to be processed. Models the RS→master report channel (`reportRegionStateTransition()` RPC).

```tla
VARIABLE pendingReports
```

### rsOnlineRegions

Set of regions currently online on each server, from the RS's own perspective. Updated by `RSOpen` (adds) and `RSClose` (removes).

```tla
VARIABLE rsOnlineRegions
```

### serverState

Liveness state per server as seen by the master: `"ONLINE"` or `"CRASHED"`.

```tla
VARIABLE serverState
```

### scpState

ServerCrashProcedure progress per server. `"NONE"` means no SCP active. Lifecycle: `GET_REGIONS` → `FENCE_WALS` → `ASSIGN` → `DONE`.

```tla
VARIABLE scpState
```

### scpRegions

SCP region snapshot per server, taken at `GET_REGIONS` time.

```tla
VARIABLE scpRegions
```

### walFenced

`TRUE` after SCP revokes WAL leases for a server. Reset on restart. After fencing, zombie RS cannot write — any write attempt fails with HDFS lease exception.

```tla
VARIABLE walFenced
```


### carryingMeta

`TRUE` when a crashed server was hosting `hbase:meta`. Set non-deterministically by `MasterDetectCrash`. When TRUE, SCP enters `ASSIGN_META` before `GET_REGIONS`.

```tla
VARIABLE carryingMeta
```

### serverRegions

Per-server region tracking (`ServerStateNode`). Maintained by `addRegionToServer`/`removeRegionFromServer` calls. SCP's `getRegionsOnServer()` reads from this, NOT from `regionState[r].location` — the two can desynchronize, providing the mechanism for the race where SCP snapshots a stale set.

```tla
VARIABLE serverRegions
```

### procStore

Persisted procedure record per region, or `NoProcedure`. Models `WALProcedureStore` / `RegionProcedureStore`. Survives master crash.

```tla
VARIABLE procStore
```

### masterAlive

`TRUE` when the active master JVM is running. When `FALSE`, all in-memory state is invalid. Durable state and RS-side state survive.

```tla
VARIABLE masterAlive
```

### zkNode

`TRUE` when a server has a live ZK ephemeral node. Created on RS start, deleted on ZK session expiry.

```tla
VARIABLE zkNode
```

### availableWorkers

Number of idle PEWorker threads. All procedure-step actions require `availableWorkers > 0` to execute. Non-blocking actions have net-zero effect (acquire + release within the same atomic step). Meta-writing actions when meta is unavailable may hold a worker (`UseBlockOnMetaWrite=TRUE`, decrement) or suspend and release (`UseBlockOnMetaWrite=FALSE`, no decrement).

*Source: `ProcedureExecutor.workerThreadCount`.*

```tla
VARIABLE availableWorkers
```

### suspendedOnMeta

Set of regions whose procedures have been suspended (released the PEWorker thread) while waiting for meta to become available. Default path (master/branch-3+, `UseBlockOnMetaWrite=FALSE`): models `ProcedureFutureUtil.suspendIfNecessary()` suspending the procedure and releasing the PEWorker thread when `AssignmentManager.persistToMeta()` returns a pending future.

*Source: `ProcedureFutureUtil.suspendIfNecessary()`; `RegionRemoteProcedureBase.execute()` `REPORT_SUCCEED` branch.*

```tla
VARIABLE suspendedOnMeta
```

### blockedOnMeta

Set of regions whose procedures are blocked on a synchronous meta write, holding the PEWorker thread. Branch-2.6 only (`UseBlockOnMetaWrite=TRUE`): models the synchronous `Table.put()` call in `RegionStateStore.updateRegionLocation()` blocking on an unavailable meta table.

*Source: `RegionStateStore.updateRegionLocation()` L158-240 uses synchronous `Table.put()` (L237-239) in branch-2.6.*

```tla
VARIABLE blockedOnMeta
```

### vars (tuple of all variables)

```tla
vars ==
  << regionState, metaTable, dispatchedOps, pendingReports,
     rsOnlineRegions, serverState, scpState, scpRegions,
     walFenced, carryingMeta, serverRegions,
     procStore, masterAlive, zkNode,
     availableWorkers, suspendedOnMeta, blockedOnMeta >>
```

---

## Predicates and Shorthands

### MetaIsAvailable

`TRUE` when no server is in `ASSIGN_META` scpState, meaning `hbase:meta` is online and accessible for read/write. Reuses the existing `waitMetaLoaded` guard from SCP actions.

*Source: `SCP.waitMetaLoaded()`.*

```tla
MetaIsAvailable == \A s \in Servers: scpState[s] # "ASSIGN_META"
```

### peVars

Shorthand for PEWorker pool variables (used in UNCHANGED clauses).

```tla
peVars == << availableWorkers, suspendedOnMeta, blockedOnMeta >>
```

---

## Module Instantiation

Each sub-module declares shared variables as `VARIABLE` parameters. `INSTANCE` without `WITH` automatically substitutes identifiers by name.

```tla
trsp   == INSTANCE TRSP
scp    == INSTANCE SCP
rs     == INSTANCE RegionServer
master == INSTANCE Master
ps     == INSTANCE ProcStore
zk     == INSTANCE ZK
```

---

## Type Invariant

### TypeOK

Validates all 17 state variables have correct types and structural consistency.

```tla
TypeOK ==
  /\ \A r \in Regions:
       /\ regionState[r].state \in State
       /\ regionState[r].location \in Servers \cup { NoServer }
       /\ regionState[r].procType \in ProcType
       /\ regionState[r].procStep \in TRSPState \cup { "IDLE" }
       /\ regionState[r].targetServer \in Servers \cup { NoServer }
       /\ regionState[r].retries \in 0 .. MaxRetries
       /\ regionState[r].procType = "NONE" =>
            /\ regionState[r].procStep = "IDLE"
            /\ regionState[r].targetServer = NoServer
            /\ regionState[r].retries = 0
       /\ regionState[r].procType # "NONE" =>
            regionState[r].procStep \in TRSPState
  /\ metaTable \in
       [Regions -> [state:State, location:Servers \cup { NoServer } ]]
  /\ dispatchedOps \in [Servers -> SUBSET [type:CommandType, region:Regions ]]
  /\ pendingReports \subseteq [server:Servers, region:Regions, code:ReportCode ]
  /\ rsOnlineRegions \in [Servers -> SUBSET Regions]
  /\ serverState \in [Servers -> { "ONLINE", "CRASHED" }]
  /\ scpState \in
       [Servers -> { "NONE", "ASSIGN_META", "GET_REGIONS", "FENCE_WALS", "ASSIGN", "DONE" }]
  /\ scpRegions \in [Servers -> SUBSET Regions]
  /\ walFenced \in [Servers -> BOOLEAN]
  /\ carryingMeta \in [Servers -> BOOLEAN]

  /\ serverRegions \in [Servers -> SUBSET Regions]
  /\ procStore \in [Regions -> ProcStoreRecord \cup { NoProcedure }]
  /\ masterAlive \in BOOLEAN
  /\ zkNode \in [Servers -> BOOLEAN]
  \* PEWorker pool: available workers bounded by MaxWorkers.
  /\ availableWorkers \in 0 .. MaxWorkers
  \* Regions suspended on meta (async, worker released).
  /\ suspendedOnMeta \subseteq Regions
  \* Regions blocked on meta (sync, worker held).
  /\ blockedOnMeta \subseteq Regions
```

---

## Safety Invariants

### OpenImpliesLocation

A region that is OPEN must have a location.

```tla
OpenImpliesLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        regionState[r].state = "OPEN" => regionState[r].location # NoServer
    )
```

### OfflineImpliesNoLocation

A region that is OFFLINE, CLOSED, FAILED_OPEN, or ABNORMALLY_CLOSED must NOT have a location.

```tla
OfflineImpliesNoLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        regionState[r].state \in
            { "OFFLINE", "CLOSED", "FAILED_OPEN", "ABNORMALLY_CLOSED" } =>
          regionState[r].location = NoServer
    )
```

### MetaConsistency

The persistent state in `hbase:meta` matches the in-memory state. One permitted divergence: `GoOffline` sets `regionState` to OFFLINE without writing to meta, so `metaTable` may retain CLOSED. Active procedure windows also permit temporary divergence.

```tla
MetaConsistency ==
  masterAlive = TRUE =>
    \A r \in Regions: \/ /\ metaTable[r].state = regionState[r].state
                         /\ metaTable[r].location = regionState[r].location
                      \/ /\ regionState[r].state = "OFFLINE"
                         /\ metaTable[r].state = "CLOSED"
                         /\ regionState[r].location = NoServer
                         /\ metaTable[r].location = NoServer
                      \/ regionState[r].procType # "NONE"
```

### NoDoubleAssignment

No region is writable on two servers simultaneously. A region is writable when `r ∈ rsOnlineRegions[s] ∧ walFenced[s] = FALSE`. After WAL fencing, the zombie RS can no longer write, preventing write-side split-brain.

```tla
NoDoubleAssignment ==
  \A r \in Regions:
    Cardinality({s \in Servers:
          r \in rsOnlineRegions[s] /\ walFenced[s] = FALSE
        }) <=
      1
```

### LockExclusivity

Procedure type correlates with valid region states. Each TRSP type may only be attached to regions in its expected state set (plus ABNORMALLY_CLOSED for crash scenarios, plus REPORT_SUCCEED window states).

```tla
LockExclusivity ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        regionState[r].procType # "NONE" =>
          \/ /\ regionState[r].procType = "ASSIGN"
             /\ regionState[r].state \in
                  { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED",
                    "FAILED_OPEN", "OPENING", "OPEN" }
          \/ /\ regionState[r].procType = "UNASSIGN"
             /\ regionState[r].state \in
                  { "OPEN", "CLOSING", "ABNORMALLY_CLOSED", "CLOSED" }
          \/ /\ regionState[r].procType = "MOVE"
             /\ regionState[r].state \in
                  { "OPEN", "CLOSING", "CLOSED", "ABNORMALLY_CLOSED",
                    "FAILED_OPEN", "OPENING" }
          \/ /\ regionState[r].procType = "REOPEN"
             /\ regionState[r].state \in
                  { "OPEN", "CLOSING", "CLOSED", "ABNORMALLY_CLOSED",
                    "FAILED_OPEN", "OPENING" }
    )
```

### RSMasterAgreement

When a region is stably OPEN (no procedure attached) on an ONLINE server with a live ZK node, the RS hosting it must also consider it online. CRASHED servers and servers with expired ZK nodes are exempted.

```tla
RSMasterAgreement ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        ( regionState[r].state = "OPEN" /\ regionState[r].procType = "NONE" /\
                  regionState[r].location # NoServer /\
                serverState[regionState[r].location] = "ONLINE" /\
              zkNode[regionState[r].location] = TRUE
          ) =>
          r \in rsOnlineRegions[regionState[r].location]
    )
```

### RSMasterAgreementConverse

If an RS considers a region online, the master must agree on location and lifecycle state. Catches "ghost regions." CRASHED servers and servers with expired ZK nodes are exempted.

```tla
RSMasterAgreementConverse ==
  masterAlive = TRUE =>
    \A s \in Servers:
      \A r \in rsOnlineRegions[s]:
        serverState[s] = "CRASHED" \/ zkNode[s] = FALSE \/
          /\ regionState[r].location = s
          /\ regionState[r].state \in { "OPENING", "OPEN", "CLOSING" }
```

### FencingOrder

SCP does not reassign regions until WAL leases have been revoked.

```tla
FencingOrder == \A s \in Servers: scpState[s] = "ASSIGN" => walFenced[s] = TRUE
```

### MetaAvailableForRecovery

A meta-carrying SCP must reassign meta before proceeding.

```tla
MetaAvailableForRecovery ==
  \A s \in Servers:
    carryingMeta[s] = TRUE => scpState[s] \in { "NONE", "ASSIGN_META", "DONE" }
```

### NoLostRegions

After any SCP completes, no region is stuck without a procedure and without SCP coverage.

```tla
NoLostRegions ==
  masterAlive = TRUE =>
    ( ( \E s \in Servers: scpState[s] = "DONE" ) =>
        \A r \in Regions:
          /\ ( regionState[r].state = "ABNORMALLY_CLOSED" =>
                 regionState[r].procType # "NONE"
             )
          /\ ( /\ regionState[r].state \in { "OPENING", "CLOSING" }
               /\ regionState[r].location = NoServer
               /\ regionState[r].procType = "NONE"
               => \E s \in Servers: r \in scpRegions[s] )
    )
```

### ProcStoreConsistency

Intra-record correlation invariant for persisted procedure records. Defined in `ProcStore.tla`; checked regardless of `masterAlive`.

```tla
ProcStoreConsistency == ps!ProcStoreConsistency
```

### ProcStoreBijection

Active in-memory procedures ↔ persisted records. Gated on `masterAlive`.

```tla
ProcStoreBijection == ps!ProcStoreBijection
```

### ProcStepConsistency

TRSP `procStep` must correlate with the region's lifecycle state.

```tla
ProcStepConsistency ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        regionState[r].procType # "NONE" =>
          /\ ( regionState[r].procStep = "GET_ASSIGN_CANDIDATE" =>
                 regionState[r].state \in
                   { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED",
                     "FAILED_OPEN", "OPENING" }
             )
          /\ ( regionState[r].procStep = "OPEN" =>
                 regionState[r].state \in
                   { "OFFLINE", "CLOSED", "ABNORMALLY_CLOSED",
                     "FAILED_OPEN", "OPENING" }
             )
          /\ ( regionState[r].procStep = "CONFIRM_OPENED" =>
                 regionState[r].state = "OPENING"
             )
          /\ ( regionState[r].procStep = "CLOSE" =>
                 regionState[r].state \in
                   { "OPEN", "CLOSING", "ABNORMALLY_CLOSED" }
             )
          /\ ( regionState[r].procStep = "CONFIRM_CLOSED" =>
                 regionState[r].state \in { "CLOSING", "ABNORMALLY_CLOSED" }
             )
          /\ ( regionState[r].procStep = "REPORT_SUCCEED" =>
                 regionState[r].state \in
                   { "OPEN", "OPENING", "FAILED_OPEN", "CLOSED" }
             )
    )
```

### TargetServerConsistency

`targetServer` presence/absence correlates with `procStep`. At `GET_ASSIGN_CANDIDATE`, no candidate has been chosen. At `OPEN`, `CONFIRM_OPENED`, `CONFIRM_CLOSED`, and `REPORT_SUCCEED`, a target must exist.

```tla
TargetServerConsistency ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        regionState[r].procType # "NONE" =>
          /\ ( regionState[r].procStep = "GET_ASSIGN_CANDIDATE" =>
                 regionState[r].targetServer = NoServer
             )
          /\ ( regionState[r].procStep \in
                   { "OPEN", "CONFIRM_OPENED", "CONFIRM_CLOSED",
                     "REPORT_SUCCEED" } =>
                 regionState[r].targetServer # NoServer
             )
    )
```

### OpeningImpliesLocation

A region in OPENING state always has a non-None location.

```tla
OpeningImpliesLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        regionState[r].state = "OPENING" => regionState[r].location # NoServer
    )
```

### ClosingImpliesLocation

A region in CLOSING state always has a non-None location.

```tla
ClosingImpliesLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        regionState[r].state = "CLOSING" => regionState[r].location # NoServer
    )
```

### ServerRegionsTrackLocation

For a region with a known location, no active procedure, and whose server is not CRASHED, `serverRegions` tracking must include it.

```tla
ServerRegionsTrackLocation ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        ( /\ regionState[r].location # NoServer
          /\ regionState[r].procType = "NONE"
          /\ serverState[regionState[r].location] # "CRASHED" ) =>
          r \in serverRegions[regionState[r].location]
    )
```

### DispatchCorrespondance

Every dispatched command corresponds to an active procedure or the target server is CRASHED.

```tla
DispatchCorrespondance ==
  masterAlive = TRUE =>
    \A s \in Servers:
      \A cmd \in dispatchedOps[s]: \/ regionState[cmd.region].procType # "NONE"
                                   \/ serverState[s] = "CRASHED"
```

### NoOrphanedProcedures

OFFLINE procedure-bearing regions must be ASSIGN only.

```tla
NoOrphanedProcedures ==
  masterAlive = TRUE =>
    ( \A r \in Regions:
        ( regionState[r].state = "OFFLINE" /\ regionState[r].procType # "NONE" ) =>
          regionState[r].procType = "ASSIGN"
    )
```

### NoPEWorkerDeadlock

When the master is alive and all PEWorkers are consumed while meta is unavailable, there must exist at least one active procedure that is neither suspended nor blocked on meta. If this invariant fails, all workers are tied up on meta writes and no progress can be made — a thread-pool exhaustion deadlock.

With `UseBlockOnMetaWrite = FALSE` (default), this should always hold because async suspension releases the PEWorker immediately. With `UseBlockOnMetaWrite = TRUE` (branch-2.6), violations are **expected** and represent genuine deadlock scenarios.

```tla
NoPEWorkerDeadlock ==
  masterAlive = TRUE =>
    ( ( availableWorkers = 0 /\ ~MetaIsAvailable ) =>
        \E r \in Regions: /\ regionState[r].procType # "NONE"
                          /\ r \notin blockedOnMeta
                          /\ r \notin suspendedOnMeta
    )
```

---

## Action Constraints

### SCPMonotonicity

SCP state machine transitions are strictly monotonic. Gated on `masterAlive` in both current and next state.

```tla
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
```

### TransitionValid

All region state transitions are members of `ValidTransition`. Gated on `masterAlive`.

```tla
TransitionValid ==
  ( masterAlive /\ masterAlive' ) =>
    \A r \in Regions:
      regionState'[r].state # regionState[r].state =>
        << regionState[r].state, regionState'[r].state >> \in ValidTransition
```

---

## State Constraints

### Symmetry

Symmetry reduction: regions and servers are interchangeable. TLC explores one representative per equivalence class, reducing the state space by up to `|Regions|! × |Servers|!` (36× for 3r/3s).

```tla
Symmetry == Permutations(Regions) \union Permutations(Servers)
```

---

## Initial State

Every region starts OFFLINE with no server and no procedure. All servers are ONLINE. Master starts alive.

```tla
Init ==
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
  /\ metaTable =
       [r \in Regions |-> [ state |-> "OFFLINE", location |-> NoServer ]]
  /\ dispatchedOps = [s \in Servers |-> {}]
  /\ pendingReports = {}
  /\ rsOnlineRegions = [s \in Servers |-> {}]
  /\ serverState = [s \in Servers |-> "ONLINE"]
  /\ scpState = [s \in Servers |-> "NONE"]
  /\ scpRegions = [s \in Servers |-> {}]
  /\ walFenced = [s \in Servers |-> FALSE]
  /\ carryingMeta = [s \in Servers |-> FALSE]

  /\ serverRegions = [s \in Servers |-> {}]
  /\ procStore = [r \in Regions |-> NoProcedure]
  /\ masterAlive = TRUE
  /\ zkNode = [s \in Servers |-> TRUE]
  \* All PEWorker threads are available at startup.
  /\ availableWorkers = MaxWorkers
  \* No procedures are suspended or blocked on meta.
  /\ suspendedOnMeta = {}
  /\ blockedOnMeta = {}
```

---

## Next-State Relation

The next-state relation defines every possible atomic step the system can take from any reachable state. It is the disjunction (`\/`) of all individual actions — assign, unassign, move, reopen, server crash recovery, RS-side handlers, master crash/recovery, and ZK session expiry. In each model-checking step, TLC explores every disjunct whose precondition is satisfied, effectively enumerating all legal transitions of the state machine.

```tla
Next == \* -- ASSIGN path --
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
        \* -- REOPEN path --
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
        \* -- RS abort --
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
        \* -- RS-side duplicate open handler --
        \/ \E s \in Servers: \E r \in Regions: rs!RSOpenDuplicate(s, r)
        \* -- Master crash and recovery --
        \/ master!MasterCrash
        \/ master!MasterRecover
        \* -- ZK session expiry --
        \/ \E s \in Servers: zk!ZKSessionExpire(s)
```

---

## Fairness

Weak fairness on deterministic actions ensures forward progress. Non-deterministic environmental events (`DispatchFail`, `DispatchFailClose`, `MasterDetectCrash`, `RSFailOpen`, `GoOffline`) receive no fairness — they may occur but are not required to.

```tla
Fairness ==
  \* Procedure invocations
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
  /\ \A s \in Servers: WF_vars(rs!RSAbort(s))
  /\ WF_vars(master!MasterRecover)
  /\ \A s \in Servers: WF_vars(zk!ZKSessionExpire(s))
  \* SCP state machine
  /\ \A s \in Servers: WF_vars(scp!SCPAssignMeta(s))
  /\ \A s \in Servers: WF_vars(scp!SCPGetRegions(s))
  /\ \A s \in Servers: WF_vars(scp!SCPFenceWALs(s))
  /\ \A s \in Servers: \A r \in Regions: WF_vars(scp!SCPAssignRegion(s, r))
  /\ \A s \in Servers: WF_vars(scp!SCPDone(s))
  \* RS-side processing
  /\ \A s \in Servers: \A r \in Regions: WF_vars(rs!RSOpen(s, r))
  /\ \A s \in Servers: \A r \in Regions: WF_vars(rs!RSClose(s, r))
```

---

## Specification

The top-level specification is the conjunction of three parts: (1) the system starts in `Init`, (2) every step either satisfies `Next` or leaves all variables unchanged (a "stuttering" step, written `[][Next]_vars`), and (3) the `Fairness` conditions guarantee that enabled actions eventually execute. Together, these define the full set of legal system behaviors.

```tla
Spec == Init /\ [][Next]_vars /\ Fairness
```

---

## Liveness

### MetaEventuallyAssigned

When meta becomes unavailable (a server enters `ASSIGN_META`), the SCP eventually completes meta assignment and `MetaIsAvailable` becomes TRUE again. This ensures suspended/blocked procedures are eventually able to resume.

*Source: The SCP state machine has WF on all steps including `SCPAssignMeta`, so meta assignment always completes.*

```tla
MetaEventuallyAssigned ==
  \A s \in Servers: scpState[s] = "ASSIGN_META" ~> MetaIsAvailable
```

---

## Theorems

Each theorem declares that a safety invariant holds in every reachable state of the system (`[]` is the "always" temporal operator). The model checker verifies these by exhaustively exploring all reachable states and confirming that no state violates any listed property.

```tla
THEOREM Spec => []TypeOK
THEOREM Spec => []OpenImpliesLocation
THEOREM Spec => []OfflineImpliesNoLocation
THEOREM Spec => []NoDoubleAssignment
THEOREM Spec => []FencingOrder
THEOREM Spec => []NoLostRegions
THEOREM Spec => []MetaConsistency
THEOREM Spec => []LockExclusivity
THEOREM Spec => []RSMasterAgreement
THEOREM Spec => []RSMasterAgreementConverse
THEOREM Spec => []ProcStepConsistency
THEOREM Spec => []TargetServerConsistency
THEOREM Spec => []OpeningImpliesLocation
THEOREM Spec => []ClosingImpliesLocation
THEOREM Spec => []ServerRegionsTrackLocation
THEOREM Spec => []DispatchCorrespondance
THEOREM Spec => []ProcStoreConsistency
THEOREM Spec => []ProcStoreBijection
THEOREM Spec => []NoOrphanedProcedures
THEOREM Spec => []NoPEWorkerDeadlock
```
