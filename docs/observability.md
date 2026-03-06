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

### Autoscaler Interpretation

- `function_dispatch_total{function}` is a cumulative Prometheus counter.
- Internal autoscaling for `rps` uses the delta between successive `function_dispatch_total` samples divided by elapsed sample time. The raw cumulative counter value is not used directly as load.
- INTERNAL scaling specs accept only `queue_depth`, `in_flight`, and `rps` metric types. Unsupported metric names are rejected during function registration/spec resolution.

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
