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
