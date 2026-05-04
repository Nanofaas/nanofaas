# Control Plane Design (Detailed)

For module selection and module packaging details, see `docs/control-plane-modules.md`.

## Unified Tooling

The canonical control-plane tooling root is `tools/controlplane/`.
The canonical shell orchestration wrapper is `scripts/controlplane.sh`.

Use `scripts/controlplane.sh` for the primary control-plane build contract:

```bash
scripts/controlplane.sh building --profile container-local --dry-run
scripts/controlplane.sh jar --profile container-local
scripts/controlplane.sh jar --profile core
scripts/controlplane.sh run --profile container-local -- --args=--nanofaas.deployment.default-backend=container-local
scripts/controlplane.sh image --profile k8s -- -PcontrolPlaneImage=nanofaas/control-plane:test
scripts/controlplane.sh native --profile all
scripts/controlplane.sh test --profile core -- --tests '*CoreDefaultsTest'
scripts/controlplane.sh matrix --task :control-plane:bootJar --max-combinations 4 --dry-run
scripts/controlplane.sh inspect --profile all
```

Milestone 3 also exposes VM lifecycle and E2E orchestration through the same product surface:

```bash
scripts/controlplane.sh vm up --lifecycle multipass --name nanofaas-e2e --dry-run
scripts/controlplane.sh vm provision-base --lifecycle external --host vm.example.test --user dev --dry-run
scripts/controlplane.sh e2e list
scripts/controlplane.sh e2e run k3s-junit-curl --lifecycle multipass --dry-run
scripts/controlplane.sh e2e all --only k3s-junit-curl --dry-run
```

For VM-backed E2E plans, the tool resolves the actual SSH target for Ansible/SSH operations and no longer plans against `localhost`. `e2e all` computes one shared VM bootstrap block for VM-backed scenarios, then runs scenario-specific workflows on top of that session. `--no-cleanup-vm` preserves the installed stack and VM state for debugging; external VM lifecycle mode is never destroyed by the tool.

Operational Ansible assets are now canonical under `ops/ansible/`.

The raw `./gradlew ... -PcontrolPlaneModules=...` workflow is still supported for low-level/advanced scenarios.

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
- Java function runtimes resolve `X-Execution-Id` per request before falling back to container-level `EXECUTION_ID`, so warm containers can safely serve multiple executions without relying on ambient process identity.
- Java function runtimes deliver completion callbacks asynchronously through a bounded local dispatcher. Control-plane completion endpoints should therefore expect callbacks to arrive independently of the request/response thread that served `/invoke`.
- Optional modules add extra runtime loops where applicable (for example queue schedulers).

## Performance Notes

- `sync-queue` no longer has strict head-of-line blocking. The scheduler can skip over a blocked function and dispatch a later ready item for a different function, so one saturated function does not stall unrelated synchronous traffic.
- When sync work exists but cannot advance immediately, the sync scheduler uses bounded backoff instead of a fixed 2 ms spin loop. This reduces CPU churn under contention while still retrying quickly once slots reopen.
- The async scheduler dispatches a bounded batch per active function before re-enqueueing that function if backlog remains. This is a fairness guarantee, not a throughput cap on the whole control-plane: hot functions keep making progress, but they do not monopolize the single scheduler loop.
- Idempotent replay now claims the key before allocating and publishing a fresh execution record. Replays and stale-key contention therefore avoid speculative `ExecutionStore.put/remove` churn on the hot path.
- Completion metrics reuse a cached timer bundle per function, and completion accounting reads less synchronized execution state per result. This keeps the post-dispatch overhead smaller for very short-lived functions.

## Throughput Tuning

- `spec.concurrency` remains the main per-function throughput knob for async queueing and deployment execution modes.
- `sync-queue.max-depth` and `sync-queue.max-estimated-wait` trade off admission aggressiveness versus tail latency. Lower values reject sooner; higher values admit more work but increase wait time under saturation.
- Async queue fairness is intentionally bounded-batch, so very large single-function bursts scale best when combined with enough function concurrency or replicas rather than relying on one scheduler loop to drain the entire burst.
- If a workload shows frequent internal retries, increasing queue depth alone is usually the wrong fix; inspect dispatch errors and retry counters before raising admission limits.

## Correctness Notes

- Synchronous invoke timeouts are terminal. When `POST /v1/functions/{name}:invoke` returns `408`, the corresponding execution remains in `timeout` and late runtime callbacks do not rewrite it to `success` or `error`.
- Backpressure is consistently surfaced as `429 Too Many Requests` across both synchronous inline handling and reactive queue-backed invoke paths. Queue saturation is not reported as a generic `500`.
- `DEPLOYMENT` functions become visible only after provisioning has produced the final resolved spec, including the endpoint URL. During removal they are hidden before teardown side effects run.
- Execution retention is anchored to completion time for terminal states. Long-running executions that finish successfully are retained for the post-completion TTL instead of being cleaned up based on original creation time.
- The runtime-config admin API validates the effective configuration snapshot, not just the incoming patch. Invalid duration strings are rejected as `400`, and sync-queue patches are rejected when `syncQueueMaxEstimatedWait > syncQueueMaxQueueWait`.
