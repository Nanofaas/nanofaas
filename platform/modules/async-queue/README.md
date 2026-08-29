# async-queue

Optional control-plane module: per-function in-memory queues plus the
scheduler loop that drains them. It is the backbone of the async path —
without this module `POST /v1/functions/{name}:enqueue` returns
`501 Not Implemented`.

## Provides

- `InvocationEnqueuer` (`QueueBackedEnqueuer`) — core SPI implementation used
  by both the async path and, when the sync-queue module is absent, the sync
  path's queued dispatch.
- `Scheduler` — background loop that pulls tasks from `QueueManager` and
  dispatches them while respecting per-function concurrency
  (`FunctionQueueState.tryAcquireSlot`, CAS-based).
- `WorkloadMetricsSource` (`AsyncQueueWorkloadMetricsSource`) — exposes queue
  depth, in-flight, effective concurrency, and dispatchable backlog. The
  autoscaler currently consumes queue depth and in-flight; RPS is derived by
  `ScalingMetricsReader` from the `function_dispatch_total` counter.

## Configuration

No dedicated prefix: queue size and concurrency come from the function spec,
with fallbacks from `nanofaas.defaults.queueSize` (100) and
`nanofaas.defaults.concurrency` (4).

## Notes

- Queues are in-memory: a control-plane restart drops queued invocations
  (project constraint, single pod / no HA).
- A full queue rejects with `QueueFullException` → HTTP 429.
