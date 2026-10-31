# B6 dispatch / scale-to-zero correction

Implement the user-approved follow-up to 3a03d949. Keep the 60-second observed
quiet policy, but separate its duration from the scale-down cooldown. Correctness
must come from dispatch ownership, not a timer. Do not relaunch cloud experiments.

1. Reproduce both orderings with the real capacity and wake-up coordinators:
   an acquired dispatch prevents zero; an applied zero prevents a concurrent
   dispatch acquisition until the mutation completes. Other functions remain live.
2. Add a generation-scoped idle operation to FunctionCapacityRegistry, using the
   existing state and acquisition lock. Pause only dispatch acquisition; release
   all locks before provider I/O; restore in finally and notify capacity listeners.
   Preserve concurrent governor updates and replacement generations.
3. Route only internal scale-to-zero through this guard and existing wake-up
   serialization. Require positive desired replicas before reporting readiness.
   Check Kubernetes readiness freshness after replica target mutations.
4. Reuse NanoLab control-plane-log collection in B6 finalization before teardown,
   retaining collection failures alongside the original measurement result.
5. Test affected modules and scheduler interactions, independently review, then
   commit, fast-forward local main and push. Archive verification in the paper.

Scope: capacity state/registry, wake-up SPI/coordinator/gate, autoscaler,
Kubernetes readiness if needed, NanoLab B6 diagnostics and their tests/docs.
No new dependency, per-function tuning option, or weakening of B6 qualification.
Historical causality remains unproven without the failed run's control-plane log.
