# Control Plane Design (Detailed)

## Responsibilities

- HTTP API for function registry and invocation.
- In-memory function registry and execution state.
- Per-function bounded queues.
- Dedicated scheduler thread for dispatch.
- Kubernetes Job creation and lifecycle tracking.
- Prometheus metrics export.

## Runtime Model

- JVM: Java 21 (target) with Spring Boot AOT/GraalVM.
- HTTP: Spring WebFlux for non-blocking I/O.
- Threading:
  - WebFlux event loop for request handling.
  - Scheduler uses a single dedicated thread.
  - Dispatcher uses an async Kubernetes client or a bounded thread pool.

## Core Data Structures

### FunctionSpec

- name: string
- image: string
- command: optional string[]
- env: map<string,string>
- resources: cpu/memory requests and limits
- timeoutMs: per-invocation timeout
- concurrency: max in-flight per function
- queueSize: max pending per function
- maxRetries: default 3
- endpointUrl: optional pool endpoint URL
- executionMode: REMOTE | LOCAL | POOL

### Invocation

- executionId: string (UUID)
- functionName: string
- payload: JSON or binary
- metadata: map
- idempotencyKey: optional
- traceId: optional
- enqueueTime: epoch ms
- attempt: int

### ExecutionRecord

- executionId
- status: queued | running | success | error | timeout
- startedAt / finishedAt
- response payload or error
- attempts
- lastError

## Queue Manager

- One bounded queue per function.
- Data structure: ArrayBlockingQueue or ring buffer.
- Enqueue policy:
  - If full: return 429 (rate limited).
- Dequeue policy:
  - FIFO per function.
- Metrics:
  - queue depth gauge per function.
  - enqueue counter per function.

## Scheduler Algorithm (Single Thread)

- Loop at a fixed tick interval (e.g., 1-5ms) or use blocking take.
- For each function, enforce concurrency limit.
- Dispatch order: round-robin over functions with non-empty queues.
- On dispatch:
  - increment attempt
  - mark execution running
  - create Job via dispatcher
- On completion:
  - success: store output + status
  - failure: if attempts <= maxRetries, re-enqueue with minimal delay
  - failure after retries: mark error

## Retry Policy

- Default maxRetries = 3 (configurable per function).
- Retries are immediate or minimal delay to avoid latency inflation.
- Only transient errors are retried (pod failed to start, timeout).
- Client must supply idempotency to avoid duplicate side effects.

## Dispatcher (K8s Integration)

- Create Job per invocation (default) using a cached Job template.
- Set labels: function, executionId, attempt.
- Set annotations: traceId, idempotencyKey.
- Pass payload via HTTP to runtime or via mounted config map (size-limited).
- Track Job status via watch or polling; update execution record.
- Pool mode dispatches to endpointUrl (HTTP) for warm pods.

## Execution Store

- In-memory map keyed by executionId.
- TTL eviction (configurable, e.g., 15 min).
- For async calls, used by `/v1/executions/{executionId}`.

## Error Handling

- 400: invalid payload or function spec.
- 404: function not found.
- 408: timeout (sync only).
- 429: queue full or rate limit.
- 500: internal error or failed invocation.

## Metrics (Micrometer)

- function_queue_depth{function}
- function_enqueue_total{function}
- function_dispatch_total{function}
- function_success_total{function}
- function_error_total{function}
- function_retry_total{function}
- function_latency_ms{function}
- function_cold_start_ms{function}

## Configuration (Env Vars)

- DEFAULT_TIMEOUT_MS
- DEFAULT_QUEUE_SIZE
- DEFAULT_CONCURRENCY
- DEFAULT_MAX_RETRIES
- EXECUTION_TTL_MS
- SCHEDULER_TICK_MS
