# Sync Queue Backpressure Design (Control Plane)

Date: 2026-02-01

## Summary

Add a dedicated in-memory sync queue in the control-plane to apply explicit backpressure and expose queue metrics for synchronous requests. The queue is separate from async to avoid interference and to make metrics and admission control deterministic.

## Goals

- Provide an explicit sync queue with measurable depth and wait times.
- Enforce backpressure for synchronous requests with immediate 429 responses.
- Support both admission control (estimated wait) and runtime queue timeout.
- Expose global and per-function sync queue metrics via Micrometer.
- Keep configuration global in `application.yml` (no API changes).

## Non-Goals

- Persistent or distributed queues.
- Per-tenant config or overrides.
- Changes to async queue semantics.

## Architecture

Introduce a `SyncQueueService` in the control-plane with:

- Dedicated in-memory queue for sync requests.
- Admission controller enforcing `maxDepth` and `maxEstimatedWait`.
- Wait estimator using hybrid throughput (per-function if enough samples, else global).
- Scheduler that dequeues sync items and dispatches to the runtime.
- Micrometer metrics (global + per-function).

The HTTP controller routes synchronous requests through the sync queue instead of direct dispatch.

## Components

- `SyncQueueService`
  - `enqueue(...)` returns a future for the HTTP response.
  - Applies admission rules and updates metrics.
- `AdmissionController`
  - Rejects on `maxDepth`.
  - If enabled, rejects when `estimatedWait > maxEstimatedWait`.
  - Returns 429 with `Retry-After`.
- `WaitEstimator`
  - Sliding window throughput for global and per-function.
  - Hybrid policy: per-function when `samples >= perFunctionMinSamples`, else global.
  - Estimated wait = `queueDepth / throughput` with clamping.
- `SyncQueueScheduler`
  - Dequeues sync items and dispatches them.
  - Recommended: dedicated sync scheduler thread for simplicity.
- `SyncQueueMetrics`
  - Exposes gauges/counters/summaries.

## Data Flow

1) Sync request arrives at control-plane.
2) `AdmissionController` checks:
   - `maxDepth`
   - (if enabled) `maxEstimatedWait` based on hybrid throughput
3) If rejected: respond 429 + `Retry-After`, increment counters.
4) If accepted: enqueue item with `enqueueTime` and response future.
5) Scheduler dequeues item and dispatches to runtime.
6) If item exceeds `maxQueueWait`, cancel and respond 429.
7) On completion: respond to client, update throughput stats and metrics.

## HTTP Semantics

- Backpressure response: **429** with `Retry-After`.
- Optional header for observability:
  - `X-Queue-Reject-Reason: depth | est_wait | timeout`

## Configuration (Global Only)

Example `application.yml` block (defaults below are recommended starting points):

syncQueue:
  enabled: true
  admissionEnabled: true
  maxDepth: 200
  maxEstimatedWait: 2s
  maxQueueWait: 2s
  retryAfterSeconds: 2
  throughputWindow: 30s
  perFunctionMinSamples: 50
  scheduler:
    policy: SYNC_ONLY

Guideline: set `maxDepth` roughly to `maxEstimatedWait * minExpectedThroughput`. The above default assumes 100 RPS as a conservative baseline; tune per environment.

## Metrics

Global metrics:

- `sync_queue_depth` (gauge)
- `sync_queue_wait_seconds_avg` (summary)
- `sync_queue_est_wait_seconds` (gauge/summary)
- `sync_queue_admitted_total` (counter)
- `sync_queue_rejected_total` (counter)
- `sync_queue_timedout_total` (counter)
- `sync_queue_exec_started_total` (counter)
- `sync_queue_exec_completed_total` (counter)

Per-function metrics (tag `function`):

- `sync_queue_depth`
- `sync_queue_wait_seconds_avg`
- `sync_queue_est_wait_seconds`
- `sync_queue_rejected_total`
- `sync_queue_timedout_total`

## Edge Cases and Concurrency

- If throughput is near zero and admission is enabled, treat estimated wait as infinite and reject.
- Ensure timeout vs dequeue race safety (atomic claim flag or removal tracking).
- A timed-out item must not be dispatched.

## Testing

Unit tests:
- Admission controller (depth, est wait, admission disabled).
- Hybrid estimator fallback and window logic.
- Queue timeout behavior.

E2E:
- Sync queue full => 429 + Retry-After.
- Admission disabled => allow enqueue until `maxDepth`, then 429.
- Metrics increment and per-function tags.

## Open Questions

- Should timeout-in-queue use 429 or a different status (keep 429 for consistency)?
- Do we want an optional mixed scheduler policy (e.g., N sync : 1 async) in the MVP?
