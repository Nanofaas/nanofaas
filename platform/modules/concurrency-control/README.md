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
- `BudgetedConcurrencyController` — per-function latency SLO served out of a
  concurrency budget shared by every function. See below.

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
    # Total in-flight invocations across every BUDGETED function. A platform
    # capacity statement: inferred from load it would grow under load and shrink
    # when idle, which is the opposite of what a budget is for. Unset, it scales
    # with the cores the control plane can see.
    total-budget: 64
```

Per-function knobs (`targetInFlightPerPod`, `min`/`maxTargetInFlightPerPod`,
`upscale`/`downscaleCooldownMs`, `high`/`lowLoadThreshold`) live in the
`FunctionSpec` and are defaulted by `FunctionSpecResolver`.

## BUDGETED: an SLO, and a budget to pay for it

The adaptive mode asks each function to find its own limit against a resource it
shares with every other function, which is a decision no function has the
information to make. Measured on two functions and one control plane: one
function's arrival moved its neighbour's limit while the neighbour's own load
was unchanged, and which of the two moved varied between runs.

BUDGETED splits that into a question each party can answer.

**What the function needs** — the gradient from Netflix's `concurrency-limits`,
with the function's SLO as the target rather than its own best-ever latency:

```
gradient = clamp(targetLatencyMs / observedLatencyMs, 0.5, 1.0)
desired  = limit x gradient + sqrt(limit)
```

Against a fixed target the gradient is 1 while the function is inside its SLO
and below 1 only when the promise is being broken — unlike a self-measured
minimum, which has no interior optimum to find because service time rises with
concurrency on any shared resource, so the limit walks to its floor. The
`sqrt(limit)` term is deliberate slack: a limit sized exactly to the arrival
rate leaves nothing for a burst, and the next arrival above the mean is what
gets rejected.

**What the platform can give** — weighted max-min fairness over
`nanofaas.concurrency-control.total-budget`, the allocation used for link
bandwidth and the ancestor of DRF in cluster schedulers. Everyone gets their ask
if the budget covers it; otherwise each is held to its weighted share and
whatever a modest function leaves unclaimed is redistributed to those still
short. A small function is never cut to pay for a large one, and nothing is
idled while anyone is still short.

The sum of the grants cannot exceed the budget, so contention is prevented by
construction rather than reacted to afterwards, and functions no longer discover
each other through interference.

## The configured limit is a ceiling

`FunctionQueueState.setEffectiveConcurrency` clamps to the spec's `concurrency`,
so the governor can only ever throttle *below* it — it cannot grant a function
more parallelism than it was registered with. Raise the ceiling at runtime,
without re-registering and without touching the deployment:

```bash
curl -X PATCH localhost:8080/v1/functions/echo \
  -H 'Content-Type: application/json' \
  -d '{"concurrency": 16}'
```

The same endpoint accepts `timeoutMs`, `maxRetries` and a replacement
`concurrencyControl` block. Immutable fields (`image`, `executionMode`, …) are
rejected with 400 rather than silently ignored.

## Notes

- Independent of the autoscaler: concurrency per replica is orthogonal to how
  many replicas exist, so functions with `ScalingStrategy.NONE` or an external
  HPA are governed too.
- Needs an enforcing queue: without the async-queue module the core no-op
  `ScalingMetricsSource` swallows the decision and nothing is limited.
- `function_effective_concurrency`, `function_target_inflight_per_pod` and
  `function_concurrency_controller_mode` are only exported under
  `nanofaas.metrics.profile=advanced`.
