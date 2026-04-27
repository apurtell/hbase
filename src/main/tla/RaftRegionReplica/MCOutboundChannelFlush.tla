---- MODULE MCOutboundChannelFlush ----
(*
 * Model-checking harness for OutboundChannelFlush.tla.
 *
 * Defines the concrete TLC constants for two configurations:
 *
 *   - MCOutboundChannelFlush.cfg: "base" config. Small constants
 *     (TickPeriod=1, FlushDeadline=3, DrainCost=1) so TLC can
 *     exhaustively explore the deadline-wakeup mechanism in seconds.
 *     Verifies BoundedDeliveryLatency at the candidate tight bound.
 *
 *   - MCOutboundChannelFlush_stress.cfg: "stress" config. DrainCost
 *     deliberately set greater than FlushDeadline so the DrainCost-
 *     dominated regime (which the implementation can hit at 1000+
 *     groups when each drain encodes 8000+ protobufs) is actually
 *     exercised. Larger MaxEnqueues and MaxClock; same spec.
 *
 * The MC modules and configs intentionally mirror the existing
 * MCRaftRegionReplica_base.tla / MCRaftRegionReplica.tla split.
 *)
EXTENDS OutboundChannelFlush

CONSTANTS p1, p2

\* The set of producer thread IDs visible to the spec.
MC_Producers == {p1, p2}

\* Symmetry across producers; valid for safety verification but TLC
\* disables it for liveness mode automatically.
Symmetry == Permutations(MC_Producers)

====
