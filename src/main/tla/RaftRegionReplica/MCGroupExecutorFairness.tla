---- MODULE MCGroupExecutorFairness ----
(*
 * Model-checking harness for GroupExecutorFairness.tla.
 *
 * Defines the concrete TLC constants for four configurations:
 *
 *   - MCGroupExecutorFairness.cfg: "base" config. Small constants
 *     (ControlBatchCap=2, BulkBatchCap=2, ServiceCost=1) so TLC can
 *     exhaustively explore the cap-then-resubmit drain rule in
 *     seconds. Verifies BoundedControlLatency at the candidate
 *     tight bound L = (ControlBatchCap + BulkBatchCap) * ServiceCost.
 *
 *   - MCGroupExecutorFairness_stress.cfg: "stress" config. ServiceCost
 *     deliberately set greater than ControlBatchCap so the
 *     ServiceCost-dominated regime (which the implementation can hit
 *     when a single per-group handler runs longer than the per-pass
 *     control cap) is actually exercised. Larger MaxEnqueues / MaxClock.
 *
 *   - MCGroupExecutorFairness_liveness.cfg: WF on every drain action;
 *     checks EventualDelivery and MailboxFairness so that bulk-lane
 *     producers cannot be starved by sustained control-lane activity
 *     and vice versa.
 *
 *   - MCGroupExecutorFairness_sim.cfg: simulation mode for longer
 *     wall-clock coverage at larger constants. Shares the safety
 *     invariants of the base config; trades exhaustiveness for breadth.
 *
 * The MC modules and configs split out base safety, stress safety,
 * liveness, and simulation following the standard pattern used in this
 * directory.
 *)
EXTENDS GroupExecutorFairness

CONSTANTS p1, p2

\* The set of producer thread IDs visible to the spec.
MC_Producers == {p1, p2}

\* Symmetry across producers; valid for safety verification but TLC
\* disables it for liveness mode automatically.
Symmetry == Permutations(MC_Producers)

====
