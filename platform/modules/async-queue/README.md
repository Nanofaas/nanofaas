# async-queue

Optional control-plane module: the per-function selection policy of the async
path. Without this module `POST /v1/functions/{name}:enqueue` returns
`501 Not Implemented`.

## Provides

- `SchedulingStrategy` (`PerFunctionSchedulingStrategy`) — this module's
  contribution to the composed engine: one FIFO of tickets per function, visited
  round-robin, with a bounded number of consecutive dispatches per function turn.
  The composed `SchedulerConfiguration` builds ONE `SchedulerEngine` around it and
  the sync-queue module's strategy; this module registers no worker of its own.
  Queued work lives in the engine's `PendingWorkStore`; admission goes through
  the core's `EngineInvocationEnqueuer`. Queue depth, in-flight and backlog
  readings come from the core's `EngineWorkloadMetricsSource`.

## Configuration

No dedicated prefix: queue size and concurrency come from the function spec,
with fallbacks from `nanofaas.defaults.queueSize` (100) and
`nanofaas.defaults.concurrency` (4).

## Notes

- Queues are in-memory: a control-plane restart drops queued invocations
  (project constraint, single pod / no HA).
- A full queue rejects with `QueueFullException` → HTTP 429.
