# RegionServer

**Source:** [`RegionServer.tla`](../RegionServer.tla)

RegionServer-side actions for the HBase AssignmentManager. Contains RS-side open/close handlers (`RSOpen`, `RSFailOpen`, `RSClose`), the zombie RS abort action (`RSAbort`), RS restart (`RSRestart`), stale report cleanup (`DropStaleReport`), and the conditional `RSOpenDuplicate` action.

---

## Module Declaration

```tla
--------------------------- MODULE RegionServer -------------------------------
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

```tla
rsVars     == << rsOnlineRegions >>
scpVars    == << scpState, scpRegions, walFenced, carryingMeta >>
masterVars == << masterAlive >>
procVars   == << procStore, locked >>
serverVars == << serverState, serverRegions >>
```

---

## Actions — RS Abort (Zombie Shutdown)

### RSAbort(s)

The zombie RS discovers it is dead (via `YouAreDeadException`, ZK session expiry, or WAL fencing) and shuts down. Clears RS-side state (online regions and pending commands).

This action is non-deterministic in timing. It may fire at any time after `ZKSessionExpire`, including before or after `MasterDetectCrash` and before or after `SCPFenceWALs`. The RS discovers its own death by detecting the ZK session expiry (`zkNode[s] = FALSE` is the ground truth).

**Pre:** ZK ephemeral node is gone (RS is dead) AND still has residual RS-side state.

**Post:** `rsOnlineRegions[s]` cleared to `{}`, `dispatchedOps[s]` cleared to `{}`. Master-side state unchanged.

*Source: `HRegionServer.abort()` triggers the RS shutdown sequence, clearing online regions and stopping RPC handlers.*

```tla
RSAbort(s) ==
  /\ zkNode[s] = FALSE
  /\ rsOnlineRegions[s] # {} \/ dispatchedOps[s] # {}
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
  /\ UNCHANGED << scpVars, serverVars, procVars, masterVars,
                   regionState, metaTable, pendingReports, zkNode >>
```

---

## Actions — Stale Report Cleanup

### DropStaleReport

Drop a report from a crashed server. Models the "You are dead" rejection path in `AM.reportRegionStateTransition()`. Reports from crashed servers cannot be consumed by `TRSPConfirmOpened`, `TRSPConfirmClosed`, or `TRSPHandleFailedOpen` (server ONLINE guard), so this action cleans them up.

**Pre:** A pending report exists from a CRASHED server.

**Post:** The stale report is removed from `pendingReports`. All other state variables unchanged.

*Source: `AM.reportRegionStateTransition()` rejects reports when `serverNode` is not in ONLINE state ("You are dead" error path).*

```tla
DropStaleReport ==
    \E rpt \in pendingReports:
    /\ serverState[rpt.server] = "CRASHED"
    /\ pendingReports' = pendingReports \ { rpt }
    /\ UNCHANGED << scpVars, serverVars, procVars, rsVars,
                     masterVars, regionState, metaTable,
                     dispatchedOps, zkNode >>
```

---

## Actions — RS-Side Open Handler

### RSOpen(s, r)

RS atomically receives an OPEN command, opens the region, adds it to `rsOnlineRegions`, and reports OPENED to the master. Merges the former `RSReceiveOpen` + `RSCompleteOpen` into a single action because the intermediate state (command consumed, RS working on open) is not observable by the master and produces the same crash-recovery outcome as the pre-receive state.

**Pre:** Server is ONLINE, an OPEN command for region `r` exists in `dispatchedOps[s]`.

**Post:** Command consumed, `r` added to `rsOnlineRegions[s]`, OPENED report produced in `pendingReports`.

The `r ∉ rsOnlineRegions[s]` guard matches the implementation: `AssignRegionHandler.process()` returns without reporting OPENED if the region is already online. No `regionState` guard — the RS does not consult the master state before processing an OPEN command.

*Source: `AssignRegionHandler.process()` success path: opens the region via `HRegion.openHRegion()`, adds it to the RS's online regions, and reports OPENED via `reportRegionStateTransition()` RPC.*

```tla
RSOpen(s, r) ==
  /\ serverState[s] = "ONLINE"
  /\ zkNode[s] = TRUE
  /\ r \notin rsOnlineRegions[s]
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "OPEN"
       /\ cmd.region = r
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = @ \cup { r }]
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "OPENED" ] }
       /\ UNCHANGED << scpVars, serverVars, procVars, masterVars,
                        regionState, metaTable, zkNode >>
```

---

### RSFailOpen(s, r)

RS atomically receives an OPEN command but fails to open the region. The command is consumed and a FAILED_OPEN report is produced. The region is NOT added to `rsOnlineRegions`.

**Pre:** Server is ONLINE, an OPEN command for region `r` exists in `dispatchedOps[s]`.

**Post:** Command consumed, FAILED_OPEN report produced.

*Source: `AssignRegionHandler.process()` failure path: `AssignRegionHandler.cleanUpAndReportFailure()` reports FAILED_OPEN via `reportRegionStateTransition()` RPC; the region is NOT added to online regions.*

```tla
RSFailOpen(s, r) ==
  /\ serverState[s] = "ONLINE"
  /\ zkNode[s] = TRUE
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "OPEN"
       /\ cmd.region = r
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "FAILED_OPEN" ] }
       /\ UNCHANGED << scpVars, serverVars, procVars, rsVars,
                        masterVars, regionState, metaTable, zkNode >>
```

---

## Actions — RS-Side Close Handler

### RSClose(s, r)

RS atomically receives a CLOSE command, closes the region, removes it from `rsOnlineRegions`, and reports CLOSED to the master. Merges the former `RSReceiveClose` + `RSCompleteClose` into a single action (same rationale as `RSOpen`).

**Pre:** Server is ONLINE, a CLOSE command for region `r` exists in `dispatchedOps[s]`.

**Post:** Command consumed, `r` removed from `rsOnlineRegions[s]`, CLOSED report produced in `pendingReports`.

*Source: `UnassignRegionHandler.process()` success path: closes the region via `HRegion.close()`, removes it from the RS's online regions, and reports CLOSED via `reportRegionStateTransition()` RPC.*

```tla
RSClose(s, r) ==
  /\ serverState[s] = "ONLINE"
  /\ zkNode[s] = TRUE
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "CLOSE"
       /\ cmd.region = r
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       /\ rsOnlineRegions' = [t \in Servers |-> rsOnlineRegions[t] \ { r }]
       /\ pendingReports' =
            pendingReports \cup
              { [ server |-> s, region |-> r, code |-> "CLOSED" ] }
       /\ UNCHANGED << scpVars, serverVars, procVars, masterVars,
                        regionState, metaTable, zkNode >>
```

---

## Actions — RS-Side Duplicate Open Handler

### RSOpenDuplicate(s, r)

RS receives an OPEN command for a region that is already online on this server. The command is consumed without producing an OPENED report, modeling `AssignRegionHandler.process()` where the handler returns early ("region already online") without calling `reportRegionStateTransition()`.
This means the TRSP on the master side will never receive the expected OPENED report and will get stuck at `CONFIRM_OPENED`, eventually causing deadlock.

Guarded by `UseRSOpenDuplicateQuirk`: disabled by default to avoid deadlock in model checking. Enable to faithfully model this implementation quirk and generate counterexample traces.

**Pre:** `UseRSOpenDuplicateQuirk = TRUE`, server is ONLINE, region `r` is already in `rsOnlineRegions[s]`, an OPEN command for `r` exists.

**Post:** Command consumed, NO report produced, `rsOnlineRegions` unchanged.

*Source: `AssignRegionHandler.process()` L107-115.*

```tla
RSOpenDuplicate(s, r) ==
  /\ UseRSOpenDuplicateQuirk = TRUE
  /\ serverState[s] = "ONLINE"
  /\ zkNode[s] = TRUE
  /\ r \in rsOnlineRegions[s]
  /\ \E cmd \in dispatchedOps[s]:
       /\ cmd.type = "OPEN"
       /\ cmd.region = r
       /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = @ \ { cmd }]
       /\ UNCHANGED << scpVars, serverVars, procVars, rsVars,
                        masterVars, regionState, metaTable,
                        pendingReports, zkNode >>
```

---

## Actions — RS Restart

### RSRestart(s)

A process supervisor (Kubernetes, systemd, etc.) restarts a crashed RegionServer. The restarted server is empty: no regions, no pending commands, no RS-side state. Any pending reports from the previous incarnation are discarded. SCP state is reset to `"NONE"` and `walFenced` is cleared.

RS epochs are NOT modeled explicitly. Real HBase uses ServerName (`host + startcode`) to distinguish incarnations. Stale reports carry the old ServerName and are rejected. Here the same effect is achieved by atomic crash plus atomic restart (this action purges stale reports). An explicit epoch variable would only add value if crash or restart were decomposed into non-atomic multi-step sequences.

**Pre:** Server is CRASHED AND SCP for this server is complete or was never started. The guard prevents premature restart while SCP is still processing regions from the crashed server.

**Post:** `serverState` set to ONLINE, pending reports from `s` discarded, SCP state reset, `walFenced` cleared, ZK ephemeral node re-registered.

*Source: Environmental assumption: A process supervisor (Kubernetes, systemd, etc.) guarantees that crashed RegionServer processes are eventually restarted.*

```tla
RSRestart(s) ==
  /\ serverState[s] = "CRASHED"
  /\ scpState[s] \in { "DONE", "NONE" }
  /\ serverState' = [serverState EXCEPT ![s] = "ONLINE"]
  /\ pendingReports' = {rpt \in pendingReports: rpt.server # s}
  /\ dispatchedOps' = [dispatchedOps EXCEPT ![s] = {}]
  /\ rsOnlineRegions' = [rsOnlineRegions EXCEPT ![s] = {}]
  /\ scpState' = [scpState EXCEPT ![s] = "NONE"]
  /\ scpRegions' = [scpRegions EXCEPT ![s] = {}]
  /\ walFenced' = [walFenced EXCEPT ![s] = FALSE]
  /\ carryingMeta' = [carryingMeta EXCEPT ![s] = FALSE]
  /\ serverRegions' = [serverRegions EXCEPT ![s] = {}]
  \* Register a fresh ZK ephemeral node for the restarted server.
  /\ zkNode' = [zkNode EXCEPT ![s] = TRUE]
  /\ UNCHANGED << procVars, masterVars, regionState, metaTable >>
```
