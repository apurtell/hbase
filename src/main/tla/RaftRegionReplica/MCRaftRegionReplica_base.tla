---- MODULE MCRaftRegionReplica_base ----
(*
 * Shared model-checking constants for all RaftRegionReplica MC
 * configurations.  Each MC module EXTENDS this alongside its
 * spec module (RaftRegionReplica, MultiGroupRaftRegionReplica, etc.)
 * and defines only the per-config overrides.
 *)
EXTENDS TLC

CONSTANTS m1, m2, m3, NoVote

MC_Members == {m1, m2, m3}
MC_None == NoVote
MC_LeaderLeaseDuration == 1

Symmetry == Permutations(MC_Members)

====
