# Observability (Detailed)

## Metrics (Prometheus)

- function_queue_depth{function}
- function_enqueue_total{function}
- function_dispatch_total{function}
- function_success_total{function}
- function_error_total{function}
- function_retry_total{function}
- function_latency_ms{function}
- function_cold_start_ms{function}
- scheduler_tick_ms
- dispatcher_k8s_latency_ms

### Sync Queue Metrics

- sync_queue_depth (global + function tag)
- sync_queue_wait_seconds (global + function tag)
- sync_queue_admitted_total
- sync_queue_rejected_total
- sync_queue_timedout_total

### Queue Contention Reading Guide

- Rising `sync_queue_depth{function}` together with flat `function_dispatch_total{function}` usually means admission is succeeding faster than dispatch slots reopen.
- A high `sync_queue_rejected_total{function}` with low depth points to estimated-wait rejection, not raw queue-capacity exhaustion.
- If `function_dispatch_total{function}` keeps growing but `function_success_total{function}` and `function_error_total{function}` lag, look at completion latency rather than scheduler fairness.
- For async queue workloads, compare queue depth against `function_inFlight{function}` and `function_effective_concurrency{function}`. Persistent depth with low in-flight implies the function is under-provisioned or slot-limited; persistent depth with high in-flight implies the runtime itself is slow.
- After the fairness changes, short bursts from colder functions should still show dispatch growth even while one hot function maintains backlog. If one function's dispatch counter starves completely while others are active, that is now a regression signal.

### Autoscaler Interpretation

- `function_dispatch_total{function}` is a cumulative Prometheus counter.
- Internal autoscaling for `rps` uses the delta between successive `function_dispatch_total` samples divided by elapsed sample time. The raw cumulative counter value is not used directly as load.
- INTERNAL scaling specs accept only `queue_depth`, `in_flight`, and `rps` metric types. Unsupported metric names are rejected during function registration/spec resolution.

### Perf Regression Coverage

- The repository includes structural hot-path regression tests instead of absolute microbenchmarks. They assert progress and allocation-sensitive behavior such as replay reuse, sync queue forward progress behind a blocked head, and async fairness between hot and cold functions.
- When tuning queueing behavior, prefer preserving those structural guarantees over chasing a fixed local timing number. Absolute timings are environment-sensitive; fairness and reuse guarantees are not.

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

## Tracing

- Propagate X-Trace-Id header from gateway to function pod.
- Optional: OpenTelemetry export in later phase.
