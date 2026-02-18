# Control Plane Design (Detailed)

For module selection and module packaging details, see `docs/control-plane-modules.md`.

## Architecture Summary

- The control-plane is a minimal core plus optional modules from `control-plane-modules/`.
- Optional module configs are loaded through the `ControlPlaneModule` SPI (`ServiceLoader`), then imported during bootstrap.
- Core registers no-op defaults for `InvocationEnqueuer`, `ScalingMetricsSource`, `SyncQueueGateway`, and `ImageValidator`, so the app can run without optional modules.

## Core Responsibilities (Always Included)

- HTTP API for function registration, invocation, and execution status.
- In-memory function registry and execution state store.
- Invocation dispatch routing for `LOCAL`, `POOL`, and `DEPLOYMENT` execution modes.
- Rate limiting, idempotency tracking, retry handling, and Micrometer metrics.

## Optional Module Capabilities

- `async-queue`: per-function async queue + scheduler, queue-backed enqueue/dispatch flow, scaling metrics source.
- `sync-queue`: sync queue admission/backpressure with estimated-wait checks and retry-after behavior.
- `autoscaler`: internal scaler and related metrics/readers that consume scaling signals and tune concurrency/replicas.
- `runtime-config`: hot runtime configuration service (rate limit + sync-queue runtime knobs), optional admin API when `nanofaas.admin.runtime-config.enabled=true`.
- `image-validator`: proactive Kubernetes image pull validation during function registration.
- `build-metadata`: module diagnostics endpoint (`GET /modules/build-metadata`).

## Invocation Behavior By Module Set

- Sync invoke (`POST /v1/functions/{name}:invoke`):
  - with `sync-queue`: request enters sync admission queue.
  - else with `async-queue`: request is enqueued through async queueing path and awaited.
  - with core-only: request is dispatched inline (no queue module required).
- Async invoke (`POST /v1/functions/{name}:enqueue`):
  - requires `async-queue`.
  - returns `501 Not Implemented` when async queueing is not present.

## Build-Time Module Selection

Use either selector input:

- `-PcontrolPlaneModules=<csv>`
- `NANOFAAS_CONTROL_PLANE_MODULES=<csv>`

Special values:

- `all`: include all optional modules.
- `none`: include no optional modules (core-only).

When selector is omitted:

- Runtime/artifact tasks (`bootRun`, `bootJar`, `bootBuildImage`, `build`, `assemble`) default to `all`.
- Non-runtime tasks (for example `:control-plane:test`) default to core-only.

## Runtime Model

- Spring WebFlux handles HTTP I/O.
- Dispatch completion is asynchronous via dispatcher futures/callbacks.
- Optional modules add extra runtime loops where applicable (for example queue schedulers).
