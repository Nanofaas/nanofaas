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
  The selection logic and the turn bound were ported from the `Scheduler` loop
  this module used to provide, which Task 13b (issue #208) deleted once the engine
  owned selection — the per-function FIFO (`FunctionQueueState`), the queue
  (`QueueManager`) and `QueueBackedEnqueuer` remain, and are removed by whoever
  retires the facade.
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
