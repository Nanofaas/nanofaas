# autoscaler

Optional control-plane module: internal replica autoscaler for managed
deployments. Per-function concurrency lives in the `concurrency-control` module.

## Provides

- `InternalScaler` — polls a `WorkloadMetricsSource` provided by either the
  async-queue or sync-queue module. The source exposes queue depth, in-flight,
  effective concurrency, and dispatchable backlog; the scaler currently uses
  queue depth and in-flight to compute replica targets via
  `ScalingDecisionCalculator`, bounded by the function's `ScalingConfig`
  min/max and rate-limited by `ScalingCooldownTracker` (upscale/downscale
  cooldowns).
- RPS is derived by `ScalingMetricsReader` from the `function_dispatch_total`
  counter.
- Cold-start accounting (`ColdStartTracker`) and scaling metrics
  (`TargetLoadMetrics`, `ScalingDecisionMetrics`).

## How a decision is made

`desired` (what we already asked the backend for) and `ready` (what is actually
serving) are read from ONE replica-status snapshot, so a decision can never mix a
target that has already moved with a ready count from another pass. The
recommendation is still computed from the metric ratio against the *serving*
replicas; what changed is that it is compared against the already-requested
target rather than against `ready`.

| Situation | Action |
|---|---|
| `recommended > requested` | scale up |
| `recommended < requested` and the serving replicas already exceed the need | scale down |
| `recommended < requested`, rollout still catching up and progressing | no-op — never walk back a target we just commanded |
| `recommended < requested`, no progress for the progress window | reconcile the target down |
| `recommended == requested` | no-op |

That third row is the point: replicas that were requested but never became ready
cannot be involuntarily reduced just because they are not serving yet.

The last row is a safety valve for a rollout that will never complete (an image
that cannot pull, a node with no room). "No progress" means neither `requested`
nor `ready` moved for **60 s** — `ScalingProgressTracker.PROGRESS_WINDOW_MS`, a
compile-time constant with no configuration knob. Replica status is served from a
snapshot with a 5 s TTL and a staleness bound deliberately shorter than this
window, so a failing read path makes the scaler skip a cycle rather than mistake
a frozen observation for a stuck rollout.

Functions opt in through the `scalingConfig` block of the `FunctionSpec`
(strategy `INTERNAL`, min/max replicas, metrics such as `queue_depth`,
`in_flight`, `rps`).

## Configuration (`nanofaas.scaling.*`)

```yaml
nanofaas:
  scaling:
    poll-interval-ms: 5000
    default-min-replicas: 1
    default-max-replicas: 10
```

## Notes

- Requires either the async-queue or sync-queue module, which provides the
  workload metrics source used by the scaler.
- Replica changes are applied through the active managed deployment provider
  (k8s or container-local).
