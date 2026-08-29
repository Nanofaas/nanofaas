# autoscaler

Optional control-plane module: internal replica autoscaler for managed
deployments. Per-function concurrency lives in the `concurrency-control` module.

## Provides

- `InternalScaler` — polls a `WorkloadMetricsSource` (queue depth, in-flight,
  rps; provided by either the async-queue or sync-queue module) and computes replica targets via
  `ScalingDecisionCalculator`, bounded by the function's `ScalingConfig`
  min/max and rate-limited by `ScalingCooldownTracker` (upscale/downscale
  cooldowns).
- Cold-start accounting (`ColdStartTracker`) and scaling metrics
  (`TargetLoadMetrics`, `ScalingDecisionMetrics`).

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
