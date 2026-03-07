# Control-Plane Rust Staging Snapshot

This is the staging Rust port of the Java control-plane, implemented only under
`experiments/control-plane-staging/`.

The snapshot now aligns the live Rust path with the Java control-plane on the
highest-risk areas: invocation correctness, function lifecycle visibility,
sync-queue admission semantics, runtime-config admin APIs, and internal
autoscaler `rps` handling. It is still a staging target, not a claim of full
end-to-end parity.

## Implemented Scope

- Core HTTP API surface for:
  - function CRUD (`/v1/functions`, `/v1/functions/{name}`)
  - invoke/enqueue (`/v1/functions/{name}:invoke`, `/v1/functions/{name}:enqueue`)
  - execution lookup (`/v1/executions/{id}`)
  - internal completion (`/v1/internal/executions/{id}:complete`)
  - internal queue drain (`/v1/internal/functions/{name}:drain-once`)
  - health (`/actuator/health`)
- In-memory stores:
  - execution store with TTL/stale eviction semantics aligned to completion
  - idempotency store with atomic claim/publish semantics on live paths
- Core rate limiter + runtime-config admin snapshot/validation
  - malformed admin inputs return `400`, semantic validation errors return `422`
  - Java-style fractional ISO-8601 seconds such as `PT0.5S` are accepted
- Dispatcher parity block:
  - `Dispatcher` trait
  - `LocalDispatcher`
  - `PoolDispatcher`
  - `DispatcherRouter`
- Queue/scheduler parity block:
  - async queue scheduling with ordered ready-function dispatch
  - sync queue admission with `depth` / `est_wait` rejection semantics
  - sync invoke requests use the queue-backed live path when sync-queue support is enabled
  - bounded scheduler backoff and no ready-work stall behind blocked functions
- Internal autoscaler parity block:
  - `queue_depth`, `in_flight`, and `rps` metric handling
  - runtime-config-backed sync-queue settings
  - accepted concurrency-control config is applied to live queue state instead of being a no-op
- Execution lifecycle parity block:
  - `ExecutionRecord` transition methods (`mark_running/success/error/timeout`, retry reset)
  - legacy mutator/accessor compatibility surface
  - snapshot metadata (`dispatched_at`, cold start, init duration)
- Observability parity block:
  - sync queue wait metric exported as `sync_queue_wait_ms`
  - dispatch-rate tracking uses a dedicated hot-path lock separate from timer/counter storage

## Test Parity Map (Java intent -> Rust test)

- `FunctionControllerTest` -> `tests/function_controller_test.rs`
- `InvocationControllerTest` -> `tests/invocation_controller_test.rs`
- `ExecutionStoreEvictionTest` -> `tests/execution_store_eviction_test.rs`
- `IdempotencyStoreTest` -> `tests/idempotency_store_test.rs`
- `IdempotencyStorePutIfAbsentTest` -> `tests/idempotency_store_put_if_absent_test.rs`
- `RateLimiterTest` -> `tests/rate_limiter_test.rs`
- `PoolDispatcher*` intent -> `tests/dispatcher_router_test.rs`
- `Queue manager/scheduler` intent -> `tests/queue_manager_test.rs`, `tests/scheduler_test.rs`
- Internal completion/scheduler API intent -> `tests/scheduler_api_test.rs`
- `ExecutionRecordLegacyAccessorsTest` -> `tests/execution_record_legacy_accessors_test.rs`
- `ExecutionRecordStateTransitionTest` -> `tests/execution_record_state_transition_test.rs`
- Remaining Java tests inventory -> `tests/java_parity_generated_test.rs` (inventory only; not a green-runtime claim)

## Run

```bash
cargo test -q
```

## Prerequisites

- Rust toolchain (`cargo`, tested on stable channel)
- Docker (for containerized parity tests and rust control-plane image build)
- Java/Gradle only if you execute mixed Java E2E suites from repository root

## Repository E2E With Rust Control-Plane

From repository root (`mcFaas`), use runtime selector `CONTROL_PLANE_RUNTIME=rust`.

Build/deploy k3s stack with rust control-plane:

```bash
CONTROL_PLANE_RUNTIME=rust E2E_K3S_HELM_NONINTERACTIVE=true ./scripts/e2e-k3s-helm.sh
```

Run full runner in dry-run mode to inspect suite eligibility:

```bash
CONTROL_PLANE_RUNTIME=rust DRY_RUN=true ./scripts/e2e-all.sh
```

Run a supported subset:

```bash
CONTROL_PLANE_RUNTIME=rust ./scripts/e2e-all.sh --only k3s-curl k8s-vm cli helm-stack cli-host
```

`e2e-all.sh` marks Java-only flows as `SKIP (unsupported for runtime)` when `CONTROL_PLANE_RUNTIME=rust`.

## Dockerized E2E

The Rust staging control-plane now exposes a runnable binary (`src/main.rs`) and
a container build (`Dockerfile`). Dockerized E2E parity tests are available in:

- `tests/e2e_dockerized_flow_test.rs`
- `tests/e2e_dockerized_sdk_examples_test.rs`

Run only dockerized SDK example parity tests:

```bash
cargo test -q --test e2e_dockerized_sdk_examples_test
```

If Docker is not available, these tests are skipped.

Important: this staging snapshot still does not claim a green parity baseline.
Inventory coverage is broad and the live core now matches Java intent on the
highest-risk control-plane paths, but dockerized SDK examples may still expose
real behavioral gaps. The current known baseline failure remains the
`word-stats-java` health timeout in the dockerized SDK examples flow; the
harness should isolate that failure instead of cascading into `PoisonError`
follow-up noise.

## Remaining Gaps

- Full Kubernetes/runtime transport parity in `PoolDispatcher`
- Full E2E parity against Java campaign matrix
- Build metadata parity is still not implemented in this snapshot
- Dockerized SDK examples are not yet green end-to-end

These are the main reasons the Rust control-plane should still be treated as a
staging target rather than a drop-in replacement for the Java runtime.
