# sync-queue

Optional control-plane module: admission control and backpressure for the
**sync** invocation path (`:invoke`). Instead of dispatching inline, sync
requests go through a bounded queue with an admission check; overload turns
into fast, explicit `429`s rather than pile-ups.

## Provides

- `SyncQueueGateway` (`EngineSyncQueueGateway`, built by this module's
  configuration) — the coordinator enqueues sync tasks here when the module is
  active; queued work lives in the engine's `PendingWorkStore`.
- `SyncQueueAdmissionController` — rejects with reason `DEPTH` (queue at
  `max-depth`) or `EST_WAIT` (estimated wait above threshold, computed by
  `WaitEstimator` from a sliding throughput window).
- `SchedulingStrategy` (`SharedQueueSchedulingStrategy`) — this module's
  contribution to the composed engine: one FIFO shared across functions, with
  rotation for head-of-line fairness. Task 13b (issue #208) deleted the module's
  own `SyncScheduler` loop once the engine owned selection.

Rejections surface as `429` with `Retry-After` and `X-Queue-Reject-Reason`
headers; a task that waits longer than `max-queue-wait` completes with the
`QUEUE_TIMEOUT` error and also maps to `429`.

The offload module's pressure triggers (`depth`/`est_wait`) are exactly these
admission rejections — with both modules active, rejected sync requests can be
proxied to a remote instance instead of getting a 429.

## Configuration (`sync-queue.*`)

```yaml
sync-queue:
  enabled: true
  admission-enabled: true
  max-depth: 200
  max-estimated-wait: 2s
  max-queue-wait: 2s
  retry-after-seconds: 2
  throughput-window: 30s
  per-function-min-samples: 50
```

## Runtime activation and the scheduler

`sync-queue.enabled` gates **admission**, not the module. The composed engine
exists from the moment the module is on the classpath and keeps draining whatever
was already admitted, so flipping the flag at runtime (through `runtime-config`)
takes effect immediately in both directions:

- `enabled: false` → new sync invocations bypass the queue; work already queued,
  and its retries, still drain to completion. Nothing is stranded.
- `enabled: true` → new invocations start being admitted at once; there is no
  restart needed to start draining, which is what the flag used to require.

The engine does not poll. It parks on a monitor and is woken by anything that can
make a queued item newly dispatchable — new work, a released dispatch slot, a
raised concurrency limit, a (re)registration, a removal drain. A bounded timed
wait remains as a safety bound so queue-wait expiry and head-of-line rotation
still happen when nothing signals: ~500 ms while the queue is empty, ~50 ms while
it has work but no capacity. `stop()` interrupts the park rather than waiting it
out.

An invocation the queue terminates itself — a queue-wait timeout, or a function
removed while its work was still queued — records the invocation's end-to-end
conclusion (`function_e2e_latency_ms`) like any other terminal path.

## Notes

- Publishes the same common workload metrics as `async-queue` through the
  shared `workload-metrics` library; the two queue modules are alternatives.
- Test tip: `ControlPlaneApiTest`-style tests need `sync-queue.enabled=false` —
  with no throughput history the admission controller rejects with `est_wait`,
  causing false 429s.
