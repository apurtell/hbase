---- MODULE OutboundChannelFlush ----
(*
 * Formal model of the per-peer outbound transport flush mechanism in
 * hbase-consensus, focused on the deadline-based wakeup that bounds
 * head-of-line delivery latency in the OutboundChannel mailbox.
 *
 * The model captures four producers of "schedule a drain" wake-ups,
 * which mirror the implementation in
 * hbase-consensus/src/main/java/org/apache/hadoop/hbase/consensus/handler/transport/OutboundChannel.java:
 *
 *   1. EnqueueImmediate(p): a producer enqueues a latency-sensitive
 *      message (e.g. VoteRequest, VoteResponse, InstallSnapshot,
 *      AppendEntriesResponse). The implementation calls
 *      scheduleDrainOn(ch); the CAS on the draining latch wakes the
 *      event-loop drain runnable. If the latch is already held, the
 *      wake-up is silently dropped (the in-flight drain catches it).
 *
 *   2. EnqueueCoalescible(p): a producer enqueues a coalescible
 *      message (LeaderHeartbeat, AppendEntriesRequest,
 *      LeaderHeartbeatAck). The post-condition wakes the drain
 *      *only when* the head of the mailbox has aged past
 *      FlushDeadline ticks. This is the new mechanism the plan
 *      introduces: it is a fail-safe, not an always-on wake-up, so
 *      under healthy load the periodic tick keeps the mailbox empty
 *      and the deadline never fires.
 *
 *   3. TickFlush: the periodic batch-flush tick scheduled at
 *      hbase.consensus.log.sync.batch.ms via
 *      ScheduledExecutorService.scheduleWithFixedDelay in
 *      CoalescingTransport.start(). Modeled as nondeterministically
 *      enabled when the mailbox is non-empty and the latch is free.
 *      With SF_vars(TickFlush) under SpecLive, this gives the
 *      liveness backstop.
 *
 *   4. EndDrain tail re-arm: after one DrainOnce completes, the
 *      drain runnable peeks the head of the (now-typically-empty)
 *      mailbox and re-arms a fresh drain if a leftover is either
 *      latency-sensitive (immediate) or has aged past FlushDeadline.
 *      Today this only fires for immediate; the plan extends it
 *      to also cover stale heads.
 *
 * The model intentionally abstracts:
 *   - The Netty channel writability bit (drainOnce bails on
 *     !ch.isWritable()). Out of scope per the plan; the spec
 *     models a writable channel always.
 *   - The fragment of LogStore that runs in the same event-loop
 *     group; we treat the drain as taking DrainCost clock units
 *     of wall time, with no contention model.
 *   - The TCP-level coalescing and the protobuf encoding
 *     details. A Pending record is delivered as a single record
 *     in the `delivered` history sequence.
 *   - Per-peer FIFO across reconnects. Modeled as a single
 *     persistent mailbox; reconnect-time drains are not modeled.
 *
 * This module composes by ASSUMPTION (not INSTANCE) with
 * RaftRegionReplica.tla: the consensus spec's atomic LeaderHeartbeat
 * round assumes wire delivery is "fast enough" relative to the
 * leader-heartbeat election timeout.
 *)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
    Producers,       \* Set: producer thread IDs (e.g. {p1, p2})
    TickPeriod,      \* Nat \ {0}: clock ticks between two successive periodic
                     \* batch-flush ticks (the BATCH_MS scheduled future)
    FlushDeadline,   \* Nat \ {0}: head-age threshold (in ticks) at which the
                     \* producer-side and tail-side wake-ups fire
    DrainCost,       \* Nat \ {0}: ticks consumed by one DrainOnce
    MaxClock,        \* Nat: model-checking horizon (must be tall enough that
                     \* every started drain can complete; CONSTRAINT enforces it)
    MaxEnqueues,     \* Nat: total producer enqueues allowed in one behavior
    L                \* Nat: latency bound being verified by BoundedDeliveryLatency

ASSUME
    /\ TickPeriod \in (Nat \ {0})
    /\ FlushDeadline \in (Nat \ {0})
    /\ DrainCost \in (Nat \ {0})
    /\ MaxClock \in Nat
    /\ MaxEnqueues \in Nat
    /\ L \in Nat
    /\ Producers # {}

VARIABLES
    clock,                    \* 0 .. MaxClock
    mailbox,                  \* Seq of Pending records (FIFO from producer
                              \* enqueues to drain)
    delivered,                \* Seq of Delivered records (history of every
                              \* Pending that DrainOnce has flushed to the wire)
    draining,                 \* BOOLEAN: latch mirroring OutboundChannel.draining
    drainStart,               \* 0 .. MaxClock: clock when current drain claimed
                              \* the latch; meaningful only while `draining`
    drainScheduled,           \* BOOLEAN: a wake-up has been registered (CAS
                              \* succeeded) but the drain runnable has not yet
                              \* claimed the latch via BeginDrain
    forcedFlushesByDeadline,  \* Nat: history counter; bumps every time the
                              \* producer- or tail-side deadline path fires.
                              \* Mirrors the OutboundChannelStats counter.
    enqueuesRemaining,        \* 0 .. MaxEnqueues
    producerSeq               \* [Producers -> 0..MaxEnqueues]: per-producer
                              \* enqueue counter, used by NoFrameInversion

vars == << clock, mailbox, delivered, draining, drainStart, drainScheduled,
           forcedFlushesByDeadline, enqueuesRemaining, producerSeq >>

\* Type-shape of one in-flight Pending entry. Mirrors the
\* OutboundChannel.Pending Java record after the plan's edit, which adds the
\* `enqueueTimeMillis` field.
Pending == [
    enqueueTime : 0..MaxClock,
    immediate   : BOOLEAN,
    producer    : Producers,
    seq         : 1..MaxEnqueues
]

\* Type-shape of a delivered record. `deliveryTime - enqueueTime` is the
\* per-message latency that BoundedDeliveryLatency bounds.
DeliveredRec == [
    enqueueTime  : 0..MaxClock,
    deliveryTime : 0..MaxClock,
    immediate    : BOOLEAN,
    producer     : Producers,
    seq          : 1..MaxEnqueues
]

TypeOK ==
    /\ clock \in 0..MaxClock
    /\ mailbox \in Seq(Pending)
    /\ delivered \in Seq(DeliveredRec)
    /\ draining \in BOOLEAN
    /\ drainStart \in 0..MaxClock
    /\ drainScheduled \in BOOLEAN
    /\ forcedFlushesByDeadline \in Nat
    /\ enqueuesRemaining \in 0..MaxEnqueues
    /\ producerSeq \in [Producers -> 0..MaxEnqueues]

Init ==
    /\ clock = 0
    /\ mailbox = <<>>
    /\ delivered = <<>>
    /\ draining = FALSE
    /\ drainStart = 0
    /\ drainScheduled = FALSE
    /\ forcedFlushesByDeadline = 0
    /\ enqueuesRemaining = MaxEnqueues
    /\ producerSeq = [p \in Producers |-> 0]

\* ---- Helpers ----

\* Age of the head-of-mailbox entry, or 0 when the mailbox is empty.
\* This mirrors the implementation's
\*   long age = now - mailbox.peek().enqueueTimeMillis;
\* call on a non-empty mailbox.
HeadAge == IF Len(mailbox) > 0 THEN clock - mailbox[1].enqueueTime ELSE 0

\* "The head is stale per the deadline policy." Mirrors the implementation's
\*   (now - head.enqueueTimeMillis) >= flushDeadlineMillis
\* check inside enqueue() and the drain tail re-arm.
HeadStale == Len(mailbox) > 0 /\ HeadAge >= FlushDeadline

\* "The head must run on the immediate path." Mirrors `head.immediate`.
HeadImmediate == Len(mailbox) > 0 /\ mailbox[1].immediate

\* ---- Actions ----

\* Wall-clock advances + integrated periodic flush tick.
\*
\* The implementation schedules the batch-flush tick via
\*   eventLoopGroup.next().scheduleWithFixedDelay(this::tick, BATCH_MS, ...)
\* so it fires at deterministic clock multiples of TickPeriod. We fold
\* that into ClockTick: every clock advance into a clock' that is a
\* multiple of TickPeriod attempts to set drainScheduled exactly when
\* the implementation's tick body would call scheduleDrainOn (mailbox
\* non-empty AND latch free). The CAS-fails-when-draining behavior is
\* captured by the `~draining` guard on `shouldTick`: a tick that lands
\* during a drain is silently dropped, just like in the implementation.
\*
\* The two negative preconditions reflect implementation invariants:
\*
\*   (a) `~drainScheduled` — scheduleDrainOn's CAS-success synchronously
\*       queues the drain runnable on the event loop, so no wall-clock
\*       time observably elapses between "wake-up registered" and "drain
\*       claimed". Event-loop dispatch latency is folded into DrainCost.
\*
\*   (b) `~(draining /\ clock >= drainStart + DrainCost)` — once a drain
\*       has consumed its DrainCost work, the runnable proceeds straight
\*       to releasing the latch; wall-clock time does not advance past
\*       the EndDrain boundary while EndDrain is enabled but pending.
\*       Without this guard TLC interleaves ClockTick over EndDrain and
\*       inflates BoundedDeliveryLatency by an arbitrary amount.
ClockTick ==
    /\ clock < MaxClock
    /\ ~drainScheduled
    /\ ~(draining /\ clock >= drainStart + DrainCost)
    /\ clock' = clock + 1
    /\ LET shouldTick == (clock' % TickPeriod = 0)
                         /\ Len(mailbox) > 0
                         /\ ~draining
       IN drainScheduled' = (drainScheduled \/ shouldTick)
    /\ UNCHANGED << mailbox, delivered, draining, drainStart,
                    forcedFlushesByDeadline, enqueuesRemaining, producerSeq >>

\* Producer enqueues a coalescible message (heartbeat, append, ack).
\*
\* The post-enqueue deadline check fires the wake-up iff the latch is free
\* (mirrors scheduleDrainOn's CAS on `draining`) AND the head's age has
\* exceeded FlushDeadline. In that case `drainScheduled` becomes TRUE so a
\* subsequent BeginDrain is enabled. forcedFlushesByDeadline is bumped so
\* that any TLC counterexample trace explains *why* the wake-up fired.
\*
\* When draining is TRUE the wake-up is silently dropped: the lost-wakeup-
\* safe tail re-arm in EndDrain plus the in-flight drain itself (which
\* polls the entire mailbox unbounded) close the window.
EnqueueCoalescible(p) ==
    /\ enqueuesRemaining > 0
    /\ LET newP    == [enqueueTime |-> clock, immediate |-> FALSE,
                       producer    |-> p,
                       seq         |-> producerSeq[p] + 1]
           mailbox2 == Append(mailbox, newP)
           \* The implementation peeks AFTER offering, so the head it sees may
           \* be the one we just appended (when mailbox was empty).
           head2          == mailbox2[1]
           ageAfterAppend == clock - head2.enqueueTime
           fire           == ~draining /\ ageAfterAppend >= FlushDeadline
       IN /\ mailbox' = mailbox2
          /\ producerSeq' = [producerSeq EXCEPT ![p] = producerSeq[p] + 1]
          /\ enqueuesRemaining' = enqueuesRemaining - 1
          /\ drainScheduled' = (drainScheduled \/ fire)
          /\ forcedFlushesByDeadline' = IF fire
                                        THEN forcedFlushesByDeadline + 1
                                        ELSE forcedFlushesByDeadline
          /\ UNCHANGED << clock, delivered, draining, drainStart >>

\* Producer enqueues an immediate message (vote request/response, install
\* snapshot, append-entries response, error). The implementation always
\* tries to wake; the CAS fails silently when the latch is held.
EnqueueImmediate(p) ==
    /\ enqueuesRemaining > 0
    /\ LET newP == [enqueueTime |-> clock, immediate |-> TRUE,
                    producer    |-> p,
                    seq         |-> producerSeq[p] + 1]
       IN /\ mailbox' = Append(mailbox, newP)
          /\ producerSeq' = [producerSeq EXCEPT ![p] = producerSeq[p] + 1]
          /\ enqueuesRemaining' = enqueuesRemaining - 1
          /\ drainScheduled' = (drainScheduled \/ ~draining)
          /\ UNCHANGED << clock, delivered, draining, drainStart,
                          forcedFlushesByDeadline >>

\* The periodic batch-flush tick is folded into ClockTick (see above). It
\* used to be a separate may-fire action; counterexample #2 in the plan
\* showed that a may-fire model lets TLC defer the tick arbitrarily,
\* producing unrealistic latency upper bounds. Folding it into ClockTick
\* makes the tick deterministic on its periodic schedule, faithfully
\* mirroring scheduleWithFixedDelay.

\* Begin drain: the runnable claims the latch on the event loop. Mirrors
\*   draining.compareAndSet(false, true)  // succeeds
\*   ch.eventLoop().execute(() -> drainOnce(ch));
BeginDrain ==
    /\ drainScheduled
    /\ ~draining
    /\ Len(mailbox) > 0
    /\ draining' = TRUE
    /\ drainStart' = clock
    /\ drainScheduled' = FALSE
    /\ UNCHANGED << clock, mailbox, delivered, forcedFlushesByDeadline,
                    enqueuesRemaining, producerSeq >>

\* End drain: after DrainCost clock ticks, atomically deliver every entry
\* the mailbox holds at the moment of completion. The unbounded poll-loop
\* in OutboundChannel.drainOnce ("while ((p = mailbox.relaxedPoll()) !=
\* null)") absorbs producer enqueues that landed during the in-flight
\* drain, so the model does too: mailbox' = <<>>.
\*
\* Tail re-arm semantics: the implementation peeks the post-drain head and
\* re-arms iff (head.immediate || age >= flushDeadlineMillis). With
\* mailbox' = <<>> the re-arm is vacuous in the unbounded-poll model. The
\* `drainScheduled' = FALSE` clearing here documents that. If a future
\* edit ever makes drainOnce a bounded-batch drain (so post-drain mailbox
\* may be non-empty), this action must be split and the tail re-arm must
\* be re-introduced; the spec test NoFrameInversion would catch most
\* mistakes but the tight latency bound would change too.
EndDrain ==
    /\ draining
    /\ clock >= drainStart + DrainCost
    /\ LET delivers == [i \in 1..Len(mailbox) |->
                          [enqueueTime  |-> mailbox[i].enqueueTime,
                           deliveryTime |-> clock,
                           immediate    |-> mailbox[i].immediate,
                           producer     |-> mailbox[i].producer,
                           seq          |-> mailbox[i].seq]]
       IN delivered' = delivered \o delivers
    /\ mailbox' = <<>>
    /\ draining' = FALSE
    /\ drainStart' = 0
    /\ drainScheduled' = FALSE
    /\ UNCHANGED << clock, forcedFlushesByDeadline, enqueuesRemaining,
                    producerSeq >>

Next ==
    \/ ClockTick
    \/ \E p \in Producers : EnqueueCoalescible(p)
    \/ \E p \in Producers : EnqueueImmediate(p)
    \/ BeginDrain
    \/ EndDrain

\* ---- Top-level safety spec (no fairness; for safety verification) ----

Spec == Init /\ [][Next]_vars

\* ---- Top-level liveness spec (with fairness) ----
\*
\* WF on ClockTick: clock eventually advances. Since the periodic tick
\*   is folded into ClockTick, this also gives "ticks fire infinitely
\*   often" for free, covering both the tick-driven happy path and the
\*   deadline-driven recovery path under EventualDelivery.
\* WF on BeginDrain: when scheduled, the drain eventually starts.
\* WF on EndDrain: when started, the drain eventually finishes.
LivenessFairness ==
    /\ WF_vars(ClockTick)
    /\ WF_vars(BeginDrain)
    /\ WF_vars(EndDrain)

SpecLive == Spec /\ LivenessFairness

\* ---- Safety invariants ----

\* The headline property the spec exists to verify. L is a CONSTANT so the
\* MC harness can tune it from counterexample feedback without changing
\* the spec. The empirically-tight value of L found by TLC determines the
\* default hbase.consensus.transport.flush.deadline.ms in TransportConfig
\* and the leaderHeartbeatLag.p99 invariant in TestConsensusServerScale.
BoundedDeliveryLatency ==
    \A i \in DOMAIN delivered :
        delivered[i].deliveryTime - delivered[i].enqueueTime <= L

\* Per-producer FIFO: catches any drain-bucket reordering bug.
NoFrameInversion ==
    \A i, j \in DOMAIN delivered :
        (i < j /\ delivered[i].producer = delivered[j].producer)
            => delivered[i].seq < delivered[j].seq

\* Sanity: no message is delivered out of thin air.
NoSpontaneousFrames ==
    Len(delivered) <= MaxEnqueues - enqueuesRemaining

\* The drain latch and the start-time agree about whether a drain is in flight.
\* When the latch is free we conventionally pin drainStart to 0; when it is
\* held, drainStart is the clock at which BeginDrain claimed the latch and
\* must be in the past (so EndDrain can become enabled at drainStart + DrainCost).
DrainLatchConsistent ==
    /\ draining => drainStart <= clock
    /\ ~draining => drainStart = 0

\* The mailbox is FIFO by enqueue time. The MPSC queue in the implementation
\* preserves insertion order; this asserts the model does too.
MailboxFifoOK ==
    \A i, j \in DOMAIN mailbox :
        i <= j => mailbox[i].enqueueTime <= mailbox[j].enqueueTime

\* ---- Liveness property ----

\* Every message that reaches the mailbox is eventually delivered.
\* Equivalently, the mailbox eventually empties whenever it becomes
\* non-empty. With the MaxEnqueues bound and the fairness conditions
\* above, this is decidable for TLC.
EventualDelivery ==
    (Len(mailbox) > 0) ~> (Len(mailbox) = 0)

\* ---- State-space CONSTRAINTS ----
\*
\* MaxClock has to be tall enough that every drain that BeginDrain claims
\* can finish via EndDrain inside the model horizon, otherwise TLC will
\* report a spurious EventualDelivery violation. We could enforce this
\* purely with a bigger MaxClock, but a CONSTRAINT keeps TLC honest by
\* pruning behaviors that step over the horizon mid-drain rather than
\* admitting them and reporting a confusing trace.
MaxClockHonoursDrain ==
    \/ ~draining
    \/ drainStart + DrainCost <= MaxClock

\* For liveness verification: don't let producers enqueue so close to
\* MaxClock that the model horizon prevents the inevitable
\* (next-tick + drain) cycle from completing. Pure model-checking
\* artifact -- in the implementation MaxClock is unbounded. Without
\* this, EventualDelivery reports spurious counterexamples whose
\* "Stuttering" frame is just "clock at MaxClock with mailbox
\* non-empty and no further enqueues possible".
\*
\* The bound (TickPeriod + DrainCost) is the worst-case latency the
\* base-config TLC run proved tight, so the constraint is exactly the
\* claim "any enqueue admitted by the model can be drained inside the
\* horizon".
EnqueueHonoursHorizon ==
    \/ enqueuesRemaining = 0
    \/ clock + TickPeriod + DrainCost <= MaxClock

====
