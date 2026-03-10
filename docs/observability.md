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
- The Go function SDK exposes its own Prometheus endpoint at `/metrics`, including runtime-side counters for invocations, handler duration, cold starts, and dropped async callbacks.

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
