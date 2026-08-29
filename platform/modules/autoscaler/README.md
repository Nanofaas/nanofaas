# autoscaler

Optional control-plane module: internal replica autoscaler for managed
deployments. Per-function concurrency lives in the `concurrency-control` module.

## Provides

- `InternalScaler` — polls a `WorkloadMetricsSource` (queue depth, in-flight,
  rps; provided by the async-queue module) and computes replica targets via
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

- Needs a metrics source: without the async-queue module the core no-op
  no queue provider provides no signal and the scaler stays idle.
- Replica changes are applied through the active managed deployment provider
  (k8s or container-local).
