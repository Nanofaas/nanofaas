# concurrency-control

Optional control-plane module: per-function concurrency governor. Decides how
many invocations a function may run in parallel; the enforcement itself stays in
the queue module's hot path.

## Provides

- `ConcurrencyGovernor` — `SmartLifecycle` tick loop over the function registry.
  For each function it reads the ready replica count from the active managed
  deployment backend (1 when the function is not managed) and the function's
  service-time timer, then hands both to the coordinator.
- `ConcurrencyControlCoordinator` — picks the controller for the function's
  `concurrencyControl.mode` and publishes the result through
  `ScalingMetricsSource.setEffectiveConcurrency` / `updateConcurrencyController`.
- `StaticPerPodConcurrencyController` — `min(readyReplicas × targetInFlightPerPod, concurrency)`.
- `AdaptivePerPodConcurrencyController` — latency-gradient (TCP-Vegas style)
  hill climber over the per-replica target.

Functions opt in through `scalingConfig.concurrencyControl` in the
`FunctionSpec`; mode `FIXED` (the default) leaves the configured `concurrency`
untouched.

## The adaptive signal

Each tick the controller diffs the function's `function_latency_ms` timer to get
the mean service time of the interval, and compares it against the best mean it
has seen for that function:

```
degradation = (intervalMean - baseline) / intervalMean      // in [0, 1)
degradation >= highLoadThreshold  ->  target -= 1           // honours downscaleCooldownMs
degradation <= lowLoadThreshold   ->  target += 1           // honours upscaleCooldownMs
```

An interval with no completed invocation moves nothing. The baseline creeps
upwards by 0.2% per tick so a single fast interval cannot pin it forever.

Queue depth and utilisation are deliberately *not* the signal: a function
running at full utilisation with an empty queue is healthy, not saturated, so
neither can locate the point where extra concurrency starts costing throughput.
Rising service time at constant work is that point.

## Configuration (`nanofaas.concurrency-control.*`)

```yaml
nanofaas:
  concurrency-control:
    poll-interval-ms: 5000
    default-target-in-flight-per-pod: 2
```

Per-function knobs (`targetInFlightPerPod`, `min`/`maxTargetInFlightPerPod`,
`upscale`/`downscaleCooldownMs`, `high`/`lowLoadThreshold`) live in the
`FunctionSpec` and are defaulted by `FunctionSpecResolver`.

## Notes

- Independent of the autoscaler: concurrency per replica is orthogonal to how
  many replicas exist, so functions with `ScalingStrategy.NONE` or an external
  HPA are governed too.
- Needs an enforcing queue: without the async-queue module the core no-op
  `ScalingMetricsSource` swallows the decision and nothing is limited.
- `function_effective_concurrency`, `function_target_inflight_per_pod` and
  `function_concurrency_controller_mode` are only exported under
  `nanofaas.metrics.profile=advanced`.
