# autoscaler

Optional control-plane module: internal replica autoscaler and per-pod
concurrency control for managed deployments.

## Provides

- `InternalScaler` — polls a `ScalingMetricsSource` (queue depth, in-flight,
  rps; provided by the async-queue module) and computes replica targets via
  `ScalingDecisionCalculator`, bounded by the function's `ScalingConfig`
  min/max and rate-limited by `ScalingCooldownTracker` (upscale/downscale
  cooldowns).
- Per-pod concurrency controllers: `StaticPerPodConcurrencyController` and
  `AdaptivePerPodConcurrencyController` (with `AdaptiveConcurrencyState` and
  `ColdStartTracker`), coordinated by `ConcurrencyControlCoordinator`.
- Scaling and concurrency metrics (`TargetLoadMetrics`,
  `ConcurrencyControlMetrics`).

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

- Needs a metrics source: without the async-queue module the core no-op
  `ScalingMetricsSource` provides no signal and the scaler stays idle.
- Replica changes are applied through the active managed deployment provider
  (k8s or container-local).
