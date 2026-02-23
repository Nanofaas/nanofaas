# Control-Plane Rust M3 (Staging Only)

This is the M3 Rust port of the Java control-plane, implemented only under
`experiments/control-plane-staging/`.

## M3 Scope

- Core HTTP API surface for:
  - function CRUD (`/v1/functions`, `/v1/functions/{name}`)
  - invoke/enqueue (`/v1/functions/{name}:invoke`, `/v1/functions/{name}:enqueue`)
  - execution lookup (`/v1/executions/{id}`)
  - internal completion (`/v1/internal/executions/{id}:complete`)
  - internal queue drain (`/v1/internal/functions/{name}:drain-once`)
  - health (`/actuator/health`)
- In-memory stores:
  - execution store with TTL/stale eviction semantics
  - idempotency store with `put_if_absent` semantics
- Core rate limiter window logic
- Dispatcher parity skeleton:
  - `Dispatcher` trait
  - `LocalDispatcher`
  - `PoolDispatcher`
  - `DispatcherRouter`
- Queue/scheduler parity skeleton:
  - `QueueManager` + overflow behavior
  - `Scheduler::tick_once` dispatch loop
- Execution lifecycle parity block:
  - `ExecutionRecord` transition methods (`mark_running/success/error/timeout`, retry reset)
  - legacy mutator/accessor compatibility surface
  - snapshot metadata (`dispatched_at`, cold start, init duration)

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
- Remaining Java tests inventory -> `tests/java_parity_generated_test.rs` (ignored placeholders)

## Run

```bash
cargo test -q
```

## Dockerized E2E

The Rust staging control-plane now exposes a runnable binary (`src/main.rs`) and
a container build (`Dockerfile`). Dockerized E2E parity tests are available in:

- `tests/e2e_dockerized_flow_test.rs`

Run only dockerized flow tests:

```bash
cargo test -q --test e2e_dockerized_flow_test
```

If Docker is not available, these tests are skipped.

## Out of Scope after M3

- Full Kubernetes/runtime transport parity in `PoolDispatcher`
- Async/sync queue admission/backpressure algorithms 1:1
- Autoscaler module parity
- Full E2E parity against Java campaign matrix

These are planned for subsequent milestones while keeping this version inside
the staging area.
