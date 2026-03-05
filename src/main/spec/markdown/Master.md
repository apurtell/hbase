# Master

**Source:** [`Master.tla`](../Master.tla)

Master-side actions for the HBase AssignmentManager. Contains actions for master-driven events:

- **GoOffline** — table disable (CLOSED → OFFLINE)
- **MasterDetectCrash** — ZK expiry → server marked CRASHED, SCP started
- **MasterCrash** — master JVM crash, in-memory state lost
- **MasterRecover** — master restart, rebuild from metaTable + procStore

---

## Module Declaration

```tla
------------------------------- MODULE Master ---------------------------------
EXTENDS Types
```

## Variables

```tla
VARIABLE regionState, metaTable, dispatchedOps, pendingReports,
         rsOnlineRegions, serverState, scpState, scpRegions,
         walFenced, locked, carryingMeta, serverRegions,
         procStore, masterAlive, zkNode
```

### Variable Group Shorthands

Used in `UNCHANGED` clauses for conciseness:

```tla
rpcVars    == << dispatchedOps, pendingReports >>
rsVars     == << rsOnlineRegions >>
scpVars    == << scpState, scpRegions, walFenced, carryingMeta >>
masterVars == << masterAlive >>
procVars   == << procStore, locked >>
serverVars == << serverState, serverRegions >>
```

---

## Actions — Master-Side Events

### GoOffline(r)

Transition from CLOSED back to OFFLINE. Used by `RegionStateNode.offline()` when a region is being taken fully offline (e.g., table disable).

**Pre:** Region is CLOSED with no procedure attached, master is alive.

**Post:** `regionState` set to OFFLINE with cleared location (in-memory only — `metaTable` is NOT updated). META retains CLOSED; divergence is resolved on master restart.

*Source: `RegionStateNode.offline()` sets state to OFFLINE and clears the region location without writing to meta.*

```tla
GoOffline(r) ==
  /\ masterAlive = TRUE
  /\ regionState[r].state = "CLOSED"
  /\ regionState[r].procType = "NONE"
  /\ regionState' =
       [regionState EXCEPT ![r].state = "OFFLINE", ![r].location = NoServer]
  /\ UNCHANGED << scpVars, rpcVars, serverVars, procVars,
                   rsVars, masterVars, metaTable, zkNode >>
```

---

### MasterDetectCrash(s)

The master detects that a RegionServer has crashed. The master's `RegionServerTracker` watcher observes that the RS's ZK ephemeral node has been deleted (`zkNode[s] = FALSE`) and calls `expireServer()`.

Regions remain in their pre-crash state (OPEN, OPENING, CLOSING) with location pointing at the crashed server. The RS may still be a **zombie** (`rsOnlineRegions` not yet cleared). This creates the window where `NoDoubleAssignment` can be violated if WALs are not fenced before regions are reassigned.

**Pre:** Master is alive, server is ONLINE in master's view, ZK ephemeral node is gone (`zkNode[s] = FALSE`).

**Post:** `serverState` set to CRASHED, SCP started. Non-deterministically decides if the server was hosting `hbase:meta` — if `carryingMeta`, SCP enters `ASSIGN_META`; otherwise `GET_REGIONS`.

*Impl state: `SERVER_CRASH_START` (=1), absorbed into this action. See SCP.tla for the full enum traceability table.*

*Source: `RegionServerTracker.processAsActiveMaster()` detects child removal and calls `ServerManager.expireServer()`, which calls `moveFromOnlineToDeadServers()` and then `AM.submitServerCrash()`.*

```tla
MasterDetectCrash(s) ==
  /\ masterAlive = TRUE
  /\ serverState[s] = "ONLINE"
  /\ zkNode[s] = FALSE
  /\ serverState' = [serverState EXCEPT ![s] = "CRASHED"]
  \* Non-deterministic: crashed server may or may not have been
  \* hosting hbase:meta.
  /\ \/ /\ carryingMeta' = [carryingMeta EXCEPT ![s] = TRUE]
        /\ scpState' = [scpState EXCEPT ![s] = "ASSIGN_META"]
     \/ /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
        /\ scpState' = [scpState EXCEPT ![s] = "GET_REGIONS"]
  /\ UNCHANGED << rpcVars, procVars, rsVars, masterVars,
                   regionState, metaTable, scpRegions,
                   walFenced, serverRegions, zkNode >>
```

---

## Actions — Master Crash and Recovery

### MasterCrash

The active master JVM crashes. All in-memory state is lost. Durable state (`metaTable`, `procStore`) and RS-side state (`rsOnlineRegions`) survive. WAL fencing (`walFenced`) survives because fencing is an HDFS-level operation, not master-level.

In-memory variables (`regionState`, `serverState`, `dispatchedOps`, `pendingReports`, `scpState`, `scpRegions`, `locked`, `serverRegions`, `carryingMeta`) become stale but are left `UNCHANGED` because:

1. All invariants referencing them are gated on `masterAlive = TRUE`.
2. All actions using them require `masterAlive = TRUE` as a guard.
3. `MasterRecover` rebuilds them from durable state.
4. Mutating them here would create spurious action-constraint violations (`TransitionValid`, `SCPMonotonicity`).

The regions are still open on their RegionServers and `hbase:meta` is still valid — only the master's in-memory view vanishes.

**Pre:** `masterAlive = TRUE`.

**Post:** `masterAlive = FALSE`.

*Source: Master JVM crash — all in-memory state is lost.*

```tla
MasterCrash ==
  /\ masterAlive = TRUE
  /\ masterAlive' = FALSE
  \* In-memory master state becomes stale (gated on masterAlive).
  /\ UNCHANGED << regionState, serverState, dispatchedOps,
                   pendingReports, scpState, scpRegions,
                   locked, serverRegions, carryingMeta >>
  \* Durable state survives.
  /\ UNCHANGED << metaTable, procStore >>
  \* RS-side state survives.
  /\ UNCHANGED rsOnlineRegions
  \* WAL fencing survives (HDFS-level).
  /\ UNCHANGED walFenced
  \* ZK ephemeral nodes survive (external to master).
  /\ UNCHANGED zkNode
```

---

### MasterRecover

The master recovers after a crash. Rebuilds in-memory state from durable storage (`metaTable` and `procStore`).

This is modeled as a single atomic action because no external interactions happen between the recovery sub-steps (meta scan, procedure reload, `restoreSucceedState`).

**Recovery steps:**

1. **Rebuild `regionState` from `metaTable`** — state and location only; procedure fields initially NONE/IDLE.
2. **Reload procedures from `procStore`** — for each region with a persisted procedure, set `procType`/`procStep`/`targetServer` from the record.
3. **Apply `restoreSucceedState`** for procedures at `REPORT_SUCCEED` — replays the in-memory state that was updated (RS report consumed) but not yet persisted to `metaTable` before the crash.
4. **Set `masterAlive = TRUE`.**

**Pre:** `masterAlive = FALSE`.

**Post:** `masterAlive = TRUE`, `regionState` rebuilt, procedures reattached.

*Source: `HMaster.finishActiveMasterInitialization()` → `ProcedureExecutor.start()` → recovery of `WALProcedureStore` → `restoreSucceedState()` callbacks.*

```tla
MasterRecover ==
  /\ masterAlive = FALSE
  /\ regionState' =
       [r \in Regions |->
         LET metaRec == metaTable[r]
             procRec == procStore[r]
         IN \* Step 1: Base state from metaTable.
             IF procRec = NoProcedure
             THEN \* No procedure — just restore from meta.
               [ state |-> metaRec.state,
                 location |-> metaRec.location,
                 procType |-> "NONE",
                 procStep |-> "IDLE",
                 targetServer |-> NoServer,
                 retries |-> 0
               ]
             ELSE \* Step 2: Procedure exists — attach it.
               LET baseState == metaRec.state
                   baseLoc == metaRec.location
               IN \* Step 3: If procedure was at REPORT_SUCCEED,
                   \* apply restoreSucceedState.
                   IF procRec.step = "REPORT_SUCCEED"
                   THEN LET restored ==
                              IF procRec.transitionCode = "CLOSED"
                              THEN [ state |-> "CLOSED", location |-> NoServer ]
                              ELSE \* Open-path (OPENED or FAILED_OPEN).
                                IF UseRestoreSucceedQuirk
                                THEN [ state |-> "OPEN",
                                       location |-> procRec.targetServer ]
                                ELSE \* Correct: check transitionCode.
                                  IF procRec.transitionCode = "OPENED"
                                  THEN [ state |-> "OPEN",
                                         location |-> procRec.targetServer ]
                                  ELSE [ state |-> "FAILED_OPEN",
                                         location |-> NoServer ]
                     IN [ state |-> restored.state,
                           location |-> restored.location,
                           procType |-> procRec.type,
                           procStep |-> procRec.step,
                           targetServer |-> procRec.targetServer,
                           retries |-> 0
                         ]
                   ELSE \* Procedure not at REPORT_SUCCEED — just re-attach.
                     [ state |-> baseState,
                       location |-> baseLoc,
                       procType |-> procRec.type,
                       procStep |-> procRec.step,
                       targetServer |-> procRec.targetServer,
                       retries |-> 0
                     ]
       ]
```

#### Server State Recovery

Read ZK ephemeral nodes to determine server liveness. On startup the master connects to ZK and gets the list of live RegionServers (via ephemeral nodes). Any server whose ephemeral node is missing is marked CRASHED and gets a fresh SCP.

*Source: `HMaster.finishActiveMasterInitialization()` → `RegionServerTracker.upgrade()` → `ServerManager.findDeadServersAndProcess()`*

```tla
  \* (continuation of MasterRecover)
  /\ serverState' =
       [s \in Servers |-> IF zkNode[s] = FALSE THEN "CRASHED" ELSE "ONLINE"]
  /\ scpState' =
       [s \in Servers |-> IF zkNode[s] = FALSE THEN "GET_REGIONS" ELSE "NONE"]
  /\ scpRegions' = [s \in Servers |-> {}]
  /\ carryingMeta' = [s \in Servers |-> FALSE]
  \* Clear in-flight RPCs and reports (stale from pre-crash master).
  /\ dispatchedOps' = [s \in Servers |-> {}]
  /\ pendingReports' = {}
  \* Reset locks.
  /\ locked' = [r \in Regions |-> FALSE]
  \* Rebuild serverRegions from recovered regionState.
  /\ serverRegions' =
       [s \in Servers |->
         {r \in Regions:
           LET metaRec == metaTable[r]
               procRec == procStore[r]
           IN IF procRec # NoProcedure /\ procRec.step = "REPORT_SUCCEED"
               THEN \* Use restored location
                 IF procRec.transitionCode = "CLOSED"
                 THEN FALSE
                 ELSE IF UseRestoreSucceedQuirk
                   THEN procRec.targetServer = s
                   ELSE IF procRec.transitionCode = "OPENED"
                     THEN procRec.targetServer = s
                     ELSE FALSE
               ELSE metaRec.location = s
         }
       ]
  /\ masterAlive' = TRUE
  /\ UNCHANGED << metaTable, procStore, rsOnlineRegions, walFenced, zkNode >>
```
