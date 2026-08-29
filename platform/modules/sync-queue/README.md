# sync-queue

Optional control-plane module: admission control and backpressure for the
**sync** invocation path (`:invoke`). Instead of dispatching inline, sync
requests go through a bounded queue with an admission check; overload turns
into fast, explicit `429`s rather than pile-ups.

## Provides

- `SyncQueueGateway` (`SyncQueueService`) — core SPI implementation; the
  coordinator enqueues sync tasks here when the module is active.
- `SyncQueueAdmissionController` — rejects with reason `DEPTH` (queue at
  `max-depth`) or `EST_WAIT` (estimated wait above threshold, computed by
  `WaitEstimator` from a sliding throughput window).
- `SyncScheduler` — drains the queue and dispatches.

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

## Notes

- Publishes the same common workload metrics as `async-queue` through the
  shared `workload-metrics` library; the two queue modules are alternatives.
- Test tip: `ControlPlaneApiTest`-style tests need `sync-queue.enabled=false` —
  with no throughput history the admission controller rejects with `est_wait`,
  causing false 429s.
