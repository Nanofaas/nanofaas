# Observability (Detailed)

## Metrics (Prometheus)

### Metrics profiles

`nanofaas.metrics.profile` selects a cumulative metrics profile:

| Profile | Metrics enabled |
|---|---|
| `basic` | Production-essential metrics |
| `advanced` | `basic` plus histograms and detailed operational metrics |
| `soak` | `basic` plus `advanced` plus the lifecycle-owner gauges below |

Selecting `soak` also enables every `advanced` histogram. The SOAK gauges are
registered only when the effective profile is `soak`; they are absent for an
unset profile, `basic`, and `advanced`. All nine gauges are aggregate and
unlabelled, so each has cardinality `1` per process.

| Component | Metric | Unit | Authoritative owner | Settlement/release event | Cardinality |
|---|---|---|---|---|---|
| Control plane | `invocation_execution_reservations` | reservations | Global logical-execution reservations in `InvocationCapacity` | The logical execution reservation closes on terminal settlement or abandonment | `1` |
| Control plane | `invocation_canonical_input_bytes` | bytes | Global canonical-input reservations in `InvocationCapacity` | The canonical-input reservation closes with its logical execution | `1` |
| Control plane | `invocation_physical_input_copy_bytes` | bytes | Global physical input-copy reservations in `InvocationCapacity` | The physical attempt releases its input-copy reservation | `1` |
| Control plane | `execution_waiters_retained` | waiters | Retained synchronous waiters in `WaiterCapacity` | The waiter detaches on completion, cancellation, or timeout | `1` |
| Control plane | `execution_expiry_queue_depth` | tasks | The execution-expiry executor queue | The expiry task executes or is cancelled and removed | `1` |
| Control plane | `function_capacity_retired_generations` | generations | Retired and draining generations in `FunctionCapacityRegistry` | A retired generation finishes draining and is released | `1` |
| Java SDK | `runtime_active_handlers` | handlers | Reserved permits in `HandlerExecutor` | The physical handler task releases its permit in its `finally` block | `1` |
| Java SDK | `runtime_pending_callbacks` | callbacks | Pending callback reservations in `CallbackDispatcher` | The reservation closes after completion, rejection, serialization failure, cancellation, or bounded shutdown abandonment | `1` |
| Java SDK | `runtime_pending_callback_bytes` | bytes | Bytes held by pending callback reservations in `CallbackDispatcher` | The same callback reservation close releases the bytes | `1` |

The JavaScript SDK adds no SOAK-specific metrics. NanoLab uses its existing
runtime ownership gauges when that role is present.

- function_queue_depth{function}
- function_inFlight{function}
- function_effective_concurrency{function}
- function_dispatchable_backlog{function}
- function_enqueue_total{function}
- function_dispatch_total{function}
- function_success_total{function}
- function_error_total{function}
- function_retry_total{function}
- function_latency_ms{function}
- function_e2e_latency_ms{function}
- function_queue_wait_ms{function}
- function_cold_start_ms{function}
- scheduler_tick_ms
- dispatcher_k8s_latency_ms

#### What the three duration timers actually sample

These three are easy to confuse, and they deliberately do NOT have the same
sample count. All are measured on monotonic time, so a clock step between
admission and completion cannot fabricate a duration.

| Metric | Interval | Recorded for |
|---|---|---|
| `function_e2e_latency_ms` | original admission → the invocation's conclusion | every admitted invocation, **exactly once**, whatever concluded it |
| `function_latency_ms` | this attempt's dispatch → its observed completion | only attempts that produced an observed completion |
| `function_queue_wait_ms` | this attempt's enqueue → its dispatch | only attempts that were dispatched |

The end-to-end timer measures the *invocation*, so retries and the waits between
them are inside it, and the interval starts at the ORIGINAL admission — a retry
does not restart the clock. It is recorded once per invocation on every terminal
policy: normal completion, a sync caller's timeout, a queue-wait timeout, an
administrative expiry, an offloaded call concluded by the remote plane, and a
function removed while its work was still queued.

`function_latency_ms` measures the *attempt*, not the invocation, and it is
censored on purpose: an invocation that timed out or expired while its dispatch
was still in flight records no service-time sample at all, because the attempt
never produced one and a truncated value would read as a fast success. So
`function_latency_ms` has fewer samples than `function_e2e_latency_ms` under
load, and the gap is not a bug — it is the censored population. Read
`function_e2e_latency_ms` for what a caller experienced, and `function_latency_ms`
only for how long the runtime took on the attempts it finished.

`async-queue` and `sync-queue` are alternative providers of the four common
per-function workload gauges above. Dashboards, HPA rules, and autoscaling
should use those names independently of the selected queue module.

### Sync Queue Metrics

- sync_queue_depth (`function=""` for the global series, function name otherwise)
- sync_queue_wait_seconds (`function=""` for the global series, function name otherwise)
- sync_queue_admitted_total
- sync_queue_rejected_total
- sync_queue_timedout_total

### Queue Contention Reading Guide

- Rising `sync_queue_depth{function}` together with flat `function_dispatch_total{function}` usually means admission is succeeding faster than dispatch slots reopen.
- A high `sync_queue_rejected_total{function}` with low depth points to estimated-wait rejection, not raw queue-capacity exhaustion.
- If `function_dispatch_total{function}` keeps growing but `function_success_total{function}` and `function_error_total{function}` lag, look at completion latency rather than scheduler fairness.
- Compare queue depth against `function_inFlight{function}` and `function_effective_concurrency{function}` for either queue provider. Persistent depth with low in-flight implies the function is under-provisioned or slot-limited; persistent depth with high in-flight implies the runtime itself is slow.
- After the fairness changes, short bursts from colder functions should still show dispatch growth even while one hot function maintains backlog. If one function's dispatch counter starves completely while others are active, that is now a regression signal.

### Autoscaler Interpretation

- `function_dispatch_total{function}` is a cumulative Prometheus counter.
- Internal autoscaling for `rps` uses the delta between successive `function_dispatch_total` samples divided by elapsed sample time. The raw cumulative counter value is not used directly as load.
- INTERNAL scaling specs accept only `queue_depth`, `in_flight`, and `rps` metric types. Unsupported metric names are rejected during function registration/spec resolution.

### Perf Regression Coverage

- The repository includes structural hot-path regression tests instead of absolute microbenchmarks. They assert progress and allocation-sensitive behavior such as replay reuse, sync queue forward progress behind a blocked head, and async fairness between hot and cold functions.
- When tuning queueing behavior, prefer preserving those structural guarantees over chasing a fixed local timing number. Absolute timings are environment-sensitive; fairness and reuse guarantees are not.
- The Go function SDK exposes its own Prometheus endpoint at `/metrics`, including runtime-side counters for invocations, handler duration, cold starts, and dropped async callbacks.

## Example PromQL queries

Metrics are exposed at `/actuator/prometheus` on port 8081. The bundled
Prometheus (installed by the Helm chart, see `deploy/helm/nanofaas/README.md`)
discovers them via service annotations; without the chart, point Prometheus at
the actuator endpoint directly.

```promql
# Error rate per function (percent)
rate(function_error_total{function="word-stats"}[5m])
  / (rate(function_success_total{function="word-stats"}[5m])
     + rate(function_error_total{function="word-stats"}[5m])) * 100

# Invocation latency percentiles (requires histogram in the runtime, p95)
histogram_quantile(0.95, sum by (le) (rate(function_latency_ms_bucket{function="word-stats"}[5m])))

# Cold start: average duration of the first-invocation initialization
rate(function_cold_start_ms_sum{function="word-stats"}[1h]) / rate(function_cold_start_ms_count{function="word-stats"}[1h])

# Queue backlog: pending async work per function
function_queue_depth{function!=""}

# Sync admission pressure: rejection ratio by reason
rate(sync_queue_rejected_total{function="word-stats"}[5m]) / rate(sync_queue_admitted_total{function="word-stats"}[5m])

# Autoscaling: effective load per replica (the autoscaler's rps signal)
rate(function_dispatch_total{function="word-stats"}[1m]) / max by (function) (kube_deployment_status_replicas{deployment="fn-word-stats"})

# Offload activity (offload module)
rate(nanofaas_offload_total[5m])
```

See the queue-contention and autoscaler interpretation notes above before
acting on a query result: a high rejection ratio with low depth means
estimated-wait rejection, not capacity exhaustion.

## Health

- /actuator/health/liveness
- /actuator/health/readiness

## Logging

- Structured logs with:
  - executionId
  - functionName
  - traceId
  - attempt
  - status
- In the Java function runtime, `executionId` in logs comes from `X-Execution-Id` first and falls back to configured `EXECUTION_ID` only when the header is missing or blank.
- In the Go function runtime, request-scoped logging resolves `executionId` and `traceId` from request headers first and falls back to `EXECUTION_ID` / `TRACE_ID` only for cold-mode compatibility.
- The runtime does not synthesize placeholder execution IDs for logging. If neither the request header nor `EXECUTION_ID` is available, MDC stays empty for `executionId`.

## Tracing

- Propagate X-Trace-Id header from gateway to function pod.
- The Java function runtime forwards `X-Trace-Id` from `/invoke` to the async completion callback. If the request header is absent, callback delivery falls back to configured `TRACE_ID`.
- The Go function runtime mirrors the same behavior: `X-Trace-Id` is propagated from `/invoke` into the async completion callback, with environment fallback only when headers are absent.
- Callback delivery from the Java function runtime is asynchronous and bounded. `/invoke` returns without waiting for callback completion; when the dispatcher is saturated, the callback is dropped and the runtime logs a warning.
- Callback delivery from the Go function runtime is also asynchronous and bounded. When the callback queue is saturated the invocation still returns, and the runtime increments a callback-drop metric.
- Callback retries in the Java function runtime are limited to retryable failures only: network/transport errors, HTTP `408`, HTTP `429`, and `5xx` responses. Other `4xx` callback responses are treated as permanent failures and are not retried.
- Successful `/invoke` responses from the Java function runtime can carry `X-Cold-Start: true` and `X-Init-Duration-Ms` only for the first invocation attempt handled by that runtime process.
- Successful `/invoke` responses from the Go function runtime also expose `X-Cold-Start: true` and `X-Init-Duration-Ms` only on the first handled invocation of that process.
- Optional: OpenTelemetry export in later phase.
