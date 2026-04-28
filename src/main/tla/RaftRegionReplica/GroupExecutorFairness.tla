---- MODULE GroupExecutorFairness ----
(*
 * Formal model of a per-group two-lane GroupExecutor mailbox.
 *
 * The model captures four producers of "schedule a drain" wake-ups, the
 * cap-then-resubmit drain rule, and the per-task service cost, mirroring
 * the implementation in
 * hbase-consensus/src/main/java/org/apache/hadoop/hbase/consensus/handler/executor/GroupExecutor.java:
 *
 *   1. EnqueueControl(p): a producer enqueues a latency-sensitive
 *      ("control-class") task — vote / pre-vote / heartbeat /
 *      heartbeat-ack handlers. The implementation calls
 *      executeControl(Runnable); the task is appended to the per-group
 *      control mailbox and a single shared `scheduled` flag is CAS'd
 *      from FALSE to TRUE. If `scheduled` was already TRUE, the wake-up
 *      is silently dropped (the in-flight drain catches it).
 *
 *   2. EnqueueBulk(p): a producer enqueues a bulk-class task —
 *      AppendEntriesRequest / InstallSnapshot* / TriggerLeaderElection
 *      handlers. Goes to the bulk mailbox; same `scheduled` CAS.
 *
 *   3. BeginDrain: the drain runnable claims the latch and begins a
 *      drain pass. Single-threaded happens-before across both lanes is
 *      preserved by single-threaded drain.
 *
 *   4. The drain pass: services up to ControlBatchCap control-class
 *      tasks first (the control burst), then up to BulkBatchCap
 *      bulk-class tasks (the bulk burst), then EndDrain. After
 *      EndDrain, peek both lanes and re-arm if either is non-empty
 *      (cap-then-resubmit).
 *
 * The model intentionally abstracts:
 *   - Per-group serial happens-before across both lanes — single-
 *     threaded drain by construction.
 *   - The MultiGroupExecutor pool (we model a single drain runnable;
 *     with N concurrent drains for N groups, each per-group spec
 *     instance composes by ASSUMPTION).
 *   - Termination / dropMailbox semantics — out of scope.
 *   - Per-task per-lane wall-clock service cost variability — modeled
 *     as a constant ServiceCost per task.
 *
 * This module composes by ASSUMPTION (not INSTANCE) with
 * RaftRegionReplica.tla: the consensus spec's atomic LeaderHeartbeat
 * round assumes per-message-kind processing latency is "fast enough"
 * relative to the leader-heartbeat election timeout. The bound proven
 * here for the control lane (BoundedControlLatency) is what allows the
 * consensus spec to keep modeling LeaderHeartbeat dispatch as effectively
 * instantaneous on the receive side, even under sustained bulk-class
 * load.
 *
 * Three concrete decisions fell out of TLC iteration on this spec:
 *
 *   1. The cap-then-resubmit rule's tight latency bound is on
 *      *head-arrival* tasks only — tasks that find their lane's mailbox
 *      empty at enqueue. Tasks queued behind earlier peers in the same
 *      lane have an unbounded queue-depth-dominated wait. Without the
 *      head-arrival precondition, BoundedControlLatency is not a
 *      well-formed property at all; TLC found a 4-deep control burst
 *      counterexample at L = 3 in roughly 800ms. The implementation
 *      therefore tags tasks with a headArrival bit at enqueue time
 *      (GroupExecutor.TimedTask), and ConsensusServerMetrics splits
 *      the wait-time distributions into all-tasks and head-arrival
 *      histograms. The head-arrival p99 is the operationally
 *      meaningful signal.
 *
 *   2. The bound on the *control* lane head-arrival wait is
 *      BulkBatchCap * ServiceCost — proportional to the bulk lane's
 *      batch cap and per-task wallclock. ControlBatchCap does not
 *      enter the bound. By symmetry, the bound on the *bulk* lane
 *      head-arrival wait is ControlBatchCap * ServiceCost. The two
 *      caps protect different lanes; the implementation Javadoc
 *      (MultiGroupExecutor.DRAIN_BATCH_CAP_KEY,
 *      CONTROL_BATCH_CAP_KEY) was rewritten to reflect this
 *      asymmetry.
 *
 *   3. ServiceCost being unbounded is the single biggest hole in the
 *      bound. A single bulk task whose handler runs for seconds
 *      directly translates to seconds of head-arrival control wait.
 *      The spec models ServiceCost as a constant; the implementation
 *      assumes per-task wallclock is well under
 *      leaderHeartbeatTimeoutMillis / drainBatchCap. Long-running
 *      bulk handlers (notably InstallSnapshot application) must
 *      either chunk work or run on a separate executor outside the
 *      per-group lane. This is documented as an implication, not
 *      enforced by code.
 *)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
    Producers,         \* Set: producer thread IDs (e.g. {p1, p2})
    ControlBatchCap,   \* Nat \ {0}: max control-class tasks one drain
                       \* pass services before yielding to bulk
                       \* (default 32 in the implementation)
    BulkBatchCap,      \* Nat \ {0}: max bulk-class tasks one drain
                       \* pass services before yielding to the parent
                       \* pool (= drainBatchCap, default 64)
    ServiceCost,       \* Nat \ {0}: ticks consumed by one task's service
    MaxClock,          \* Nat: model-checking horizon (must be tall
                       \* enough that every started drain can complete;
                       \* MaxClockHonoursDrain enforces it)
    MaxEnqueues,       \* Nat: total producer enqueues allowed in one behavior
    L                  \* Nat: per-task control-lane wait bound being
                       \* verified by BoundedControlLatency

ASSUME
    /\ ControlBatchCap \in (Nat \ {0})
    /\ BulkBatchCap \in (Nat \ {0})
    /\ ServiceCost \in (Nat \ {0})
    /\ MaxClock \in Nat
    /\ MaxEnqueues \in Nat
    /\ L \in Nat
    /\ Producers # {}

VARIABLES
    clock,                \* 0..MaxClock
    controlMailbox,       \* Seq of TaskRec (FIFO control lane)
    bulkMailbox,          \* Seq of TaskRec (FIFO bulk lane)
    delivered,            \* Seq of DeliveredRec; one entry per dequeued
                          \* task, recording the lane and the wait time
                          \* the BoundedControlLatency invariant bounds.
    scheduled,            \* BOOLEAN: shared `scheduled` flag mirroring
                          \* the AtomicBoolean in GroupExecutor
    draining,             \* BOOLEAN: set by BeginDrain, cleared by EndDrain
    drainStart,           \* 0..MaxClock: clock when current drain claimed
                          \* the latch; meaningful only while draining
    serviceUntil,         \* 0..(MaxClock + ServiceCost): clock until
                          \* which the currently-in-flight task is
                          \* occupying the drain. New service / phase /
                          \* end actions are gated on
                          \* `clock >= serviceUntil`. The upper bound
                          \* exceeds MaxClock by ServiceCost so a service
                          \* started at clock = MaxClock can still type-
                          \* check; MaxClockHonoursDrain enforces that
                          \* TLC's `clock < MaxClock` ClockTick gate
                          \* never lets the model wedge with a started-
                          \* but-unfinished drain at the horizon.
    controlProcessed,     \* 0..ControlBatchCap: control tasks done in
                          \* the current pass
    bulkProcessed,        \* 0..BulkBatchCap: bulk tasks done in the
                          \* current pass
    bulkPhaseEntered,     \* BOOLEAN: TRUE once the drain has yielded
                          \* control burst to bulk burst in the current
                          \* pass. The implementation is sequential
                          \* (drainLane(control); drainLane(bulk);), so
                          \* mid-bulk-burst control arrivals must wait
                          \* for the next pass.
    enqueuesRemaining,    \* 0..MaxEnqueues
    producerSeq           \* [Producers -> 0..MaxEnqueues]: per-producer
                          \* enqueue counter, used by NoControlInversion /
                          \* NoBulkInversion

vars == << clock, controlMailbox, bulkMailbox, delivered, scheduled, draining,
           drainStart, serviceUntil, controlProcessed, bulkProcessed,
           bulkPhaseEntered, enqueuesRemaining, producerSeq >>

Lanes == {"CONTROL", "BULK"}

\* Type-shape of one in-flight Pending entry. The lane field discriminates
\* control vs. bulk so that NoControlInversion / NoBulkInversion can be
\* checked per-lane. `headArrival` is TRUE iff the relevant lane's
\* mailbox was empty at the moment of enqueue; this is the natural
\* discriminator for the BoundedControlLatency invariant: tasks that
\* arrive deep in the lane's mailbox have an unbounded queue-depth-
\* dominated wait (FIFO behind earlier head-of-line tasks), so the bound
\* the cap-then-resubmit rule actually delivers — bulk-lane delay added
\* to control-lane head-of-line wait — is well-formed only on head
\* arrivals.
TaskRec == [
    enqueueTime : 0..MaxClock,
    lane        : Lanes,
    producer    : Producers,
    seq         : 1..MaxEnqueues,
    headArrival : BOOLEAN
]

\* Type-shape of a delivered record. `serviceStart - enqueueTime` is the
\* per-task wait that BoundedControlLatency (for lane = "CONTROL") and
\* the broader EventualDelivery (for both lanes) bound.
DeliveredRec == [
    enqueueTime  : 0..MaxClock,
    serviceStart : 0..MaxClock,
    lane         : Lanes,
    producer     : Producers,
    seq          : 1..MaxEnqueues,
    headArrival  : BOOLEAN
]

TypeOK ==
    /\ clock \in 0..MaxClock
    /\ controlMailbox \in Seq(TaskRec)
    /\ bulkMailbox \in Seq(TaskRec)
    /\ delivered \in Seq(DeliveredRec)
    /\ scheduled \in BOOLEAN
    /\ draining \in BOOLEAN
    /\ drainStart \in 0..MaxClock
    /\ serviceUntil \in 0..(MaxClock + ServiceCost)
    /\ controlProcessed \in 0..ControlBatchCap
    /\ bulkProcessed \in 0..BulkBatchCap
    /\ bulkPhaseEntered \in BOOLEAN
    /\ enqueuesRemaining \in 0..MaxEnqueues
    /\ producerSeq \in [Producers -> 0..MaxEnqueues]

Init ==
    /\ clock = 0
    /\ controlMailbox = <<>>
    /\ bulkMailbox = <<>>
    /\ delivered = <<>>
    /\ scheduled = FALSE
    /\ draining = FALSE
    /\ drainStart = 0
    /\ serviceUntil = 0
    /\ controlProcessed = 0
    /\ bulkProcessed = 0
    /\ bulkPhaseEntered = FALSE
    /\ enqueuesRemaining = MaxEnqueues
    /\ producerSeq = [p \in Producers |-> 0]

\* ---- Helpers ----

\* "The drain has more control-class work it can do this pass." Gated on
\* `draining` so Service*Task / EnterBulkPhase cannot fire from the
\* idle (no-latch-held) state, mirroring the implementation where the
\* drain body only runs while drainLock is held.
ControlDrainable ==
    /\ draining
    /\ ~bulkPhaseEntered
    /\ controlProcessed < ControlBatchCap
    /\ Len(controlMailbox) > 0

\* "The drain has yielded control burst to bulk burst and has more bulk
\* work it can do this pass."
BulkDrainable ==
    /\ draining
    /\ bulkPhaseEntered
    /\ bulkProcessed < BulkBatchCap
    /\ Len(bulkMailbox) > 0

\* The drain pass is finished iff the bulk phase has ended (either
\* exhausted bulk lane or cap reached). EndDrain is only enabled after
\* bulkPhaseEntered=TRUE so the "control then bulk" ordering is enforced.
DrainFinished ==
    /\ draining
    /\ bulkPhaseEntered
    /\ \/ bulkProcessed >= BulkBatchCap
       \/ Len(bulkMailbox) = 0

\* Phase transition is enabled when the drain pass is past the control
\* burst (cap reached or control empty) and bulkPhaseEntered is still
\* FALSE. This is the hidden control flow at the
\* `drainLane(control); drainLane(bulk);` boundary in the implementation.
PhaseTransitionEnabled ==
    /\ draining
    /\ ~bulkPhaseEntered
    /\ \/ controlProcessed >= ControlBatchCap
       \/ Len(controlMailbox) = 0

\* Any drain-internal action (service control, service bulk, phase
\* transition, end drain) is enabled. Used as a ClockTick gate so that
\* TLC does not interleave clock advances over instantaneous drain
\* transitions and inflate apparent latencies.
DrainInternalEnabled ==
    /\ draining
    /\ clock >= serviceUntil
    /\ \/ ControlDrainable
       \/ BulkDrainable
       \/ PhaseTransitionEnabled
       \/ DrainFinished

\* ---- Actions ----

\* Wall-clock advances. Disabled when an instantaneous action is enabled
\* (BeginDrain, any drain-internal transition); this keeps TLC from
\* interleaving ClockTick over them and inflating BoundedControlLatency
\* by an arbitrary amount.
ClockTick ==
    /\ clock < MaxClock
    /\ ~(scheduled /\ ~draining)
    /\ ~DrainInternalEnabled
    /\ clock' = clock + 1
    /\ UNCHANGED << controlMailbox, bulkMailbox, delivered, scheduled,
                    draining, drainStart, serviceUntil, controlProcessed,
                    bulkProcessed, bulkPhaseEntered, enqueuesRemaining,
                    producerSeq >>

\* Producer enqueues a control-class task. CAS the shared `scheduled`
\* flag from FALSE to TRUE; if it was already TRUE the wake-up is
\* silently dropped (the in-flight drain catches it on the EndDrain
\* peek-and-rearm path). The lost-wakeup-safe Phase 2 invariant is
\* preserved because the drain re-arms `scheduled` only after observing
\* both lanes empty.
EnqueueControl(p) ==
    /\ enqueuesRemaining > 0
    /\ LET newP == [enqueueTime |-> clock, lane |-> "CONTROL",
                    producer    |-> p,
                    seq         |-> producerSeq[p] + 1,
                    headArrival |-> Len(controlMailbox) = 0]
       IN /\ controlMailbox' = Append(controlMailbox, newP)
          /\ producerSeq' = [producerSeq EXCEPT ![p] = producerSeq[p] + 1]
          /\ enqueuesRemaining' = enqueuesRemaining - 1
          /\ scheduled' = TRUE
          /\ UNCHANGED << clock, bulkMailbox, delivered, draining, drainStart,
                          serviceUntil, controlProcessed, bulkProcessed,
                          bulkPhaseEntered >>

\* Producer enqueues a bulk-class task; symmetric to EnqueueControl.
EnqueueBulk(p) ==
    /\ enqueuesRemaining > 0
    /\ LET newP == [enqueueTime |-> clock, lane |-> "BULK",
                    producer    |-> p,
                    seq         |-> producerSeq[p] + 1,
                    headArrival |-> Len(bulkMailbox) = 0]
       IN /\ bulkMailbox' = Append(bulkMailbox, newP)
          /\ producerSeq' = [producerSeq EXCEPT ![p] = producerSeq[p] + 1]
          /\ enqueuesRemaining' = enqueuesRemaining - 1
          /\ scheduled' = TRUE
          /\ UNCHANGED << clock, controlMailbox, delivered, draining, drainStart,
                          serviceUntil, controlProcessed, bulkProcessed,
                          bulkPhaseEntered >>

\* Begin drain: the drain runnable claims the latch on the parent pool.
\* Mirrors the implementation:
\*     scheduled.set(true)  -- already TRUE; producer or prior EndDrain
\*     parent.submitDrain(drainRunnable);  -- runs drain() body
\*     synchronized (drainLock) { ... }
BeginDrain ==
    /\ scheduled
    /\ ~draining
    /\ \/ Len(controlMailbox) > 0
       \/ Len(bulkMailbox) > 0
    /\ draining' = TRUE
    /\ drainStart' = clock
    /\ serviceUntil' = clock
    /\ controlProcessed' = 0
    /\ bulkProcessed' = 0
    /\ bulkPhaseEntered' = FALSE
    /\ scheduled' = FALSE
    /\ UNCHANGED << clock, controlMailbox, bulkMailbox, delivered,
                    enqueuesRemaining, producerSeq >>

\* Service one control-class task. Pops the head of the control mailbox,
\* records its wait, and sets serviceUntil so subsequent service / phase /
\* end actions are gated on clock catching up.
ServiceControlTask ==
    /\ ControlDrainable
    /\ clock >= serviceUntil
    /\ LET head == controlMailbox[1]
           rec  == [enqueueTime  |-> head.enqueueTime,
                    serviceStart |-> clock,
                    lane         |-> head.lane,
                    producer     |-> head.producer,
                    seq          |-> head.seq,
                    headArrival  |-> head.headArrival]
       IN /\ controlMailbox' = Tail(controlMailbox)
          /\ delivered' = Append(delivered, rec)
          /\ controlProcessed' = controlProcessed + 1
          /\ serviceUntil' = clock + ServiceCost
          /\ UNCHANGED << clock, bulkMailbox, scheduled, draining, drainStart,
                          bulkProcessed, bulkPhaseEntered, enqueuesRemaining,
                          producerSeq >>

\* The control burst has finished (cap or empty). The implementation
\* sequentially calls drainLane(bulk, ...) at this point. We model that
\* hand-off as an instantaneous bulkPhaseEntered=TRUE transition. New
\* control arrivals after this point have to wait for the next drain
\* pass.
EnterBulkPhase ==
    /\ PhaseTransitionEnabled
    /\ clock >= serviceUntil
    /\ bulkPhaseEntered' = TRUE
    /\ UNCHANGED << clock, controlMailbox, bulkMailbox, delivered, scheduled,
                    draining, drainStart, serviceUntil, controlProcessed,
                    bulkProcessed, enqueuesRemaining, producerSeq >>

\* Service one bulk-class task. Symmetric to ServiceControlTask but on
\* the bulk lane.
ServiceBulkTask ==
    /\ BulkDrainable
    /\ clock >= serviceUntil
    /\ LET head == bulkMailbox[1]
           rec  == [enqueueTime  |-> head.enqueueTime,
                    serviceStart |-> clock,
                    lane         |-> head.lane,
                    producer     |-> head.producer,
                    seq          |-> head.seq,
                    headArrival  |-> head.headArrival]
       IN /\ bulkMailbox' = Tail(bulkMailbox)
          /\ delivered' = Append(delivered, rec)
          /\ bulkProcessed' = bulkProcessed + 1
          /\ serviceUntil' = clock + ServiceCost
          /\ UNCHANGED << clock, controlMailbox, scheduled, draining, drainStart,
                          controlProcessed, bulkPhaseEntered,
                          enqueuesRemaining, producerSeq >>

\* End drain: caps reached or both lanes drained. Releases the latch and
\* peeks both lanes; if either is non-empty, re-arms `scheduled` so the
\* parent pool will re-enter BeginDrain on the next round. This is the
\* lost-wakeup-safe `peek()-then-CAS` tail in the Java implementation.
EndDrain ==
    /\ DrainFinished
    /\ clock >= serviceUntil
    /\ draining' = FALSE
    /\ drainStart' = 0
    /\ controlProcessed' = 0
    /\ bulkProcessed' = 0
    /\ bulkPhaseEntered' = FALSE
    /\ scheduled' = (Len(controlMailbox) > 0 \/ Len(bulkMailbox) > 0)
    /\ UNCHANGED << clock, controlMailbox, bulkMailbox, delivered, serviceUntil,
                    enqueuesRemaining, producerSeq >>

Next ==
    \/ ClockTick
    \/ \E p \in Producers : EnqueueControl(p)
    \/ \E p \in Producers : EnqueueBulk(p)
    \/ BeginDrain
    \/ ServiceControlTask
    \/ EnterBulkPhase
    \/ ServiceBulkTask
    \/ EndDrain

\* ---- Top-level safety spec (no fairness; for safety verification) ----

Spec == Init /\ [][Next]_vars

\* ---- Top-level liveness spec (with fairness) ----
\*
\* WF on ClockTick: clock eventually advances under bounded enqueues.
\* WF on BeginDrain / EndDrain: when scheduled / when finished, the drain
\*   eventually starts / finishes. The intermediate Service* and
\*   EnterBulkPhase actions are continuously enabled while the drain is
\*   in progress, so weak fairness on them is implied by SF on EndDrain
\*   (which they precede by transitivity); we explicitly add WF for them
\*   so TLC's liveness check terminates without deeper proof.
LivenessFairness ==
    /\ WF_vars(ClockTick)
    /\ WF_vars(BeginDrain)
    /\ WF_vars(ServiceControlTask)
    /\ WF_vars(EnterBulkPhase)
    /\ WF_vars(ServiceBulkTask)
    /\ WF_vars(EndDrain)

SpecLive == Spec /\ LivenessFairness

\* ---- Safety invariants ----

\* The headline property the spec exists to verify. L is a CONSTANT so
\* the MC harness can tune it from counterexample feedback. The
\* empirically-tight value of L found by TLC informs the default
\* hbase.consensus.executor.control.batch.cap.
\*
\* The bound is on *head-arrival* control tasks: tasks that find an
\* empty control mailbox at enqueue. These are the tasks whose wait is
\* set entirely by the bulk-lane back-pressure of the cap-then-resubmit
\* drain rule. Tasks queued behind earlier head-of-line control tasks
\* have a wait that is dominated by FIFO queueing delay (k * ServiceCost
\* for k tasks ahead), which is unbounded by control-mailbox depth and
\* not what the cap-then-resubmit rule is designed to bound. In the
\* implementation, a sustained control-lane backlog of more than a few
\* tasks is itself a pathology — heartbeats arrive at known intervals
\* per group, vote bursts are short — so the operationally-relevant
\* invariant is the head-arrival bound the spec verifies here.
\*
\* The worst-case head-arrival wait covered by L is: a head arrival in
\* an empty control mailbox lands while the drain is mid-bulk-burst.
\* The drain finishes the in-flight bulk task (up to ServiceCost) and
\* up to (BulkBatchCap-1) more bulk tasks before EndDrain (instant),
\* BeginDrain (instant), and ServiceControlTask serves the head. So
\* L = BulkBatchCap * ServiceCost is the tight bound. This empirically-
\* tight value found by TLC informs the default
\* hbase.consensus.executor.control.batch.cap.
BoundedControlLatency ==
    \A i \in DOMAIN delivered :
        ( delivered[i].lane = "CONTROL"
          /\ delivered[i].headArrival )
            => delivered[i].serviceStart - delivered[i].enqueueTime <= L

\* Per-lane FIFO: catches any drain-bucket reordering bug. Each producer
\* p observes a strictly-increasing seq within delivered entries of the
\* same (producer, lane).
NoControlInversion ==
    \A i, j \in DOMAIN delivered :
        ( i < j
          /\ delivered[i].producer = delivered[j].producer
          /\ delivered[i].lane = "CONTROL"
          /\ delivered[j].lane = "CONTROL" )
            => delivered[i].seq < delivered[j].seq

NoBulkInversion ==
    \A i, j \in DOMAIN delivered :
        ( i < j
          /\ delivered[i].producer = delivered[j].producer
          /\ delivered[i].lane = "BULK"
          /\ delivered[j].lane = "BULK" )
            => delivered[i].seq < delivered[j].seq

\* Sanity: no task is delivered out of thin air.
NoSpontaneousTasks ==
    Len(delivered) <= MaxEnqueues - enqueuesRemaining

\* The drain latch and the start-time agree about whether a drain is in
\* flight. When the latch is free we conventionally pin drainStart to 0;
\* when held, drainStart is the clock at which BeginDrain claimed the
\* latch and must be in the past.
DrainLatchConsistent ==
    /\ draining => drainStart <= clock
    /\ ~draining => drainStart = 0
    /\ ~draining => controlProcessed = 0
    /\ ~draining => bulkProcessed = 0
    /\ ~draining => ~bulkPhaseEntered

\* Each lane's mailbox is FIFO by enqueue time. The MPSC queue in the
\* implementation preserves insertion order; this asserts the model
\* does too.
MailboxFifoOK ==
    /\ \A i, j \in DOMAIN controlMailbox :
        i <= j => controlMailbox[i].enqueueTime <= controlMailbox[j].enqueueTime
    /\ \A i, j \in DOMAIN bulkMailbox :
        i <= j => bulkMailbox[i].enqueueTime <= bulkMailbox[j].enqueueTime

\* Per-pass caps are honored by the drain rule: the drain never
\* services more than ControlBatchCap control or BulkBatchCap bulk
\* tasks before yielding (EndDrain).
PerPassCapsHonored ==
    /\ controlProcessed <= ControlBatchCap
    /\ bulkProcessed <= BulkBatchCap

\* ---- Liveness properties ----

\* Every enqueued task is eventually delivered (= dequeued from its
\* mailbox and recorded). Equivalently, both mailboxes eventually empty
\* whenever either becomes non-empty. With the MaxEnqueues bound and
\* the fairness conditions above, this is decidable for TLC.
EventualDelivery ==
    (Len(controlMailbox) > 0 \/ Len(bulkMailbox) > 0)
        ~> (Len(controlMailbox) = 0 /\ Len(bulkMailbox) = 0)

\* MailboxFairness: the bulk lane is not starved by sustained control-
\* producer activity. Specifically: any task currently in the bulk
\* mailbox is eventually dequeued. The cap-then-resubmit drain rule
\* is what makes this true even if the control producer never stops
\* — the drain will hit the ControlBatchCap and yield to the bulk
\* burst on every pass.
MailboxFairness ==
    (Len(bulkMailbox) > 0) ~> (Len(bulkMailbox) = 0)

\* ---- State-space CONSTRAINTS ----
\*
\* Keep MaxClock tall enough that
\* every started drain can finish in-horizon, and keep producers from
\* enqueuing so close to MaxClock that EventualDelivery reports a
\* spurious counterexample whose stuttering frame is just "clock at
\* MaxClock with mailbox non-empty and no further enqueues possible".

MaxClockHonoursDrain ==
    \/ ~draining
    \/ serviceUntil <= MaxClock

\* Worst-case wait from enqueue to delivery: enqueue at clock=t, drain
\* mid-bulk-burst, full control burst on next pass, plus the in-flight
\* task. (ControlBatchCap + BulkBatchCap + 1) * ServiceCost is a safe
\* upper bound; we use it to gate fresh enqueues so the model horizon
\* always admits at least one drain cycle past every enqueue.
EnqueueHonoursHorizon ==
    \/ enqueuesRemaining = 0
    \/ clock + (ControlBatchCap + BulkBatchCap + 1) * ServiceCost <= MaxClock

====
