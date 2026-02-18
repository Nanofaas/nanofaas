# Control Plane Modularization Design

## Goal

Decompose the control-plane into a functional core and optional modules.
Two objectives: lightweight build (minimal core works standalone) and extensibility (new features as drop-in modules).

## Core (control-plane)

Handles sync dispatch directly (no queue). Components:

| Package | Key classes | Role |
|---------|------------|------|
| `api` | `FunctionController`, `InvocationController` (`:invoke`, `:complete`, `/executions`), `GlobalExceptionHandler` | REST endpoints |
| `config` | `HttpClientConfig`, `KubernetesClientConfig`, `VertxRuntimeHints`, properties | Spring infra |
| `dispatch` | `Dispatcher`, `DispatcherRouter`, `LocalDispatcher`, `PoolDispatcher`, `KubernetesDeploymentBuilder`, `KubernetesResourceManager` | Direct dispatch |
| `execution` | `ExecutionRecord`, `ExecutionState`, `ExecutionStore`, `IdempotencyStore` | Execution lifecycle |
| `registry` | `FunctionRegistry`, `FunctionService`, `FunctionSpecResolver`, `FunctionDefaults`, `ImageValidator` (no-op default) | Function management |
| `service` | `InvocationService` (sync direct + completion), `RateLimiter`, `Metrics` | Orchestration |
| `scheduler` | `InvocationTask` (record only) | Shared data model |

Sync `:invoke` acquires a concurrency semaphore, calls dispatcher directly, awaits result. No `QueueManager`, no `Scheduler`.

Endpoint `:enqueue` returns 501 if `async-queue` module is absent.

### New interfaces in core

| Interface | Methods | Default (core) | Module impl |
|-----------|---------|----------------|-------------|
| `InvocationEnqueuer` | `enqueue(record)`, `enabled()` | No-op, `enabled()=false` | `async-queue` delegates to `QueueManager` |
| `ScalingMetricsSource` | `queueDepth(fn)`, `inFlight(fn)`, `setEffectiveConcurrency(fn,v)`, `updateConcurrencyController(fn,mode,target)` | No-op (returns 0, ignores set) | `async-queue` delegates to `QueueManager` |

### Exceptions kept in core

- `QueueFullException` — `InvocationController` returns 429
- `SyncQueueRejectedException` + `SyncQueueRejectReason` — `InvocationController` returns 429 + `Retry-After`
- `ImageValidationException` — `GlobalExceptionHandler` maps to HTTP status

## Modules

### async-queue

Adds async queue + `:enqueue` endpoint. Also routes sync through queue when present.

| Class | Origin | Notes |
|-------|--------|-------|
| `QueueManager` | `queue.*` | Unchanged |
| `FunctionQueueState` | `queue.*` | Unchanged |
| `QueueFullException` | `queue.*` | Stays in core (controller needs it) |
| `Scheduler` | `scheduler.Scheduler` | Unchanged |
| `WorkSignaler` | `scheduler.WorkSignaler` | Unchanged |
| `AsyncQueueConfiguration` | New | `@Configuration` |
| `AsyncQueueModule` | New | SPI `ControlPlaneModule` |

Integration: implements `InvocationEnqueuer` and `ScalingMetricsSource`.

`InvocationService` behavior:
- `invokeSync()`: if `enqueuer.enabled()` -> queue path (current behavior); else -> direct dispatch with semaphore
- `invokeAsync()`: if `enqueuer.enabled()` -> enqueue + return accepted; else -> 501

Gradle dependency: `implementation project(':common')`

### sync-queue

Adds admission control and wait estimation on sync invocations. **Depends on async-queue** (reuses the queue).

| Class | Origin | Notes |
|-------|--------|-------|
| `SyncQueueService` | `sync.*` | Unchanged |
| `SyncScheduler` | `scheduler.SyncScheduler` | Unchanged |
| `SyncQueueAdmissionController` | `sync.*` | Unchanged |
| `WaitEstimator` | `sync.*` | Unchanged |
| `SyncQueueMetrics` | `sync.*` | Unchanged |
| `SyncQueueProperties` | `config.SyncQueueProperties` | Moves to module |
| `SyncQueueConfiguration` | New | `@Configuration` |
| `SyncQueueModule` | New | SPI `ControlPlaneModule` |

`SyncQueueRejectedException` + `SyncQueueRejectReason` stay in core (controller needs them for 429 + Retry-After).

Gradle dependency: `implementation project(':control-plane-modules:async-queue')`

### autoscaler

Adds K8s auto-scaling with adaptive concurrency control.

| Class | Origin | Notes |
|-------|--------|-------|
| `InternalScaler` | `scaling.*` | Unchanged |
| `ScalingMetricsReader` | `scaling.*` | Uses `ScalingMetricsSource` interface from core |
| `ScalingProperties` | `scaling.*` | Moves to module |
| `ColdStartTracker` | `metrics.*` | Moves to module |
| `StaticPerPodConcurrencyController` | `scaling.*` | Unchanged |
| `AdaptivePerPodConcurrencyController` | `scaling.*` | Unchanged |
| `AdaptiveConcurrencyState` | `scaling.*` | Unchanged |
| `ConcurrencyControlMetrics` | `service.*` | Moves to module |
| `TargetLoadMetrics` | `service.*` | Moves to module |
| `AutoscalerConfiguration` | New | `@Configuration` |
| `AutoscalerModule` | New | SPI `ControlPlaneModule` |

`ScalingMetricsReader` depends on `ScalingMetricsSource` (interface in core, implemented by async-queue). If async-queue is absent, the no-op source returns zeros — autoscaler still works for CPU/memory metrics from Prometheus.

Gradle dependency: `implementation project(':common')` (no direct dep on async-queue)

### runtime-config

Adds hot parameter modification via REST API.

| Class | Origin | Notes |
|-------|--------|-------|
| `RuntimeConfigService` | `config.runtime.*` | Unchanged |
| `RuntimeConfigApplier` | `config.runtime.*` | `@Autowired(required=false)` for sync-queue deps |
| `RuntimeConfigValidator` | `config.runtime.*` | Unchanged |
| `AdminRuntimeConfigController` | `api.*` | Moves to module |
| `RuntimeConfigPatch` | `config.runtime.*` | Unchanged |
| `RuntimeConfigSnapshot` | `config.runtime.*` | Unchanged |
| `RevisionMismatchException` | `config.runtime.*` | Moves to module |
| `RuntimeConfigApplyException` | `config.runtime.*` | Moves to module |
| `RuntimeConfigConfiguration` | New | `@Configuration` |
| `RuntimeConfigModule` | New | SPI `ControlPlaneModule` |

Dependencies: core `RateLimiter` (always available), sync-queue `SyncQueueProperties` (optional via `required=false`).

Gradle dependency: `implementation project(':common')`

### image-validator

Validates container images before deployment by creating a temporary K8s pod.

| Class | Origin | Notes |
|-------|--------|-------|
| `KubernetesImageValidator` | `registry.*` | Unchanged |
| `ImageValidatorConfiguration` | New | `@Bean ImageValidator` overrides core no-op |
| `ImageValidatorModule` | New | SPI `ControlPlaneModule` |

Core provides `@Bean @ConditionalOnMissingBean ImageValidator` (no-op). Module overrides with `KubernetesImageValidator`.

Gradle dependency: `implementation project(':common')`, `implementation 'io.fabric8:kubernetes-client'`

### build-metadata (existing)

Reference module. Endpoint `GET /modules/build-metadata`.

## Dependency graph

```
common  (data model, ControlPlaneModule SPI)
  ^
  |
core (control-plane)  <-- image-validator
  ^                   <-- runtime-config
  |                   <-- build-metadata
  |
async-queue           <-- autoscaler (via ScalingMetricsSource interface)
  ^
  |
sync-queue
```

## Valid build combinations

| Configuration | Modules | Use case |
|---------------|---------|----------|
| Minimal | none | Local testing, sync-only, direct dispatch |
| Async | `async-queue` | `:enqueue` support, queue-based dispatch |
| Production base | `async-queue`, `autoscaler` | K8s with auto-scaling |
| Full production | all | All features |
| No scaling | `async-queue`, `sync-queue` | Queue with admission control, fixed replicas |

## Module conventions

- Package: `it.unimib.datai.nanofaas.modules.<module-name>`
- Bean registration: explicit `@Bean` in `@Configuration` classes (no component scanning)
- Controllers: use `@Controller` + `@ResponseBody` (not `@RestController`)
- SPI: implement `ControlPlaneModule`, register in `META-INF/services/`
- Build: apply `io.spring.dependency-management` plugin + Spring Boot BOM

## Not done (and why)

- **K8s dispatch as a module**: `KubernetesResourceManager` + `PoolDispatcher` stay in core. Extracting them would force a `DispatcherProvider` SPI, and there's no use case for a core without K8s dispatch (LOCAL mode is already a trivial fallback).
- **Metrics as a module**: `Metrics` class is tightly coupled to `InvocationService`. Extracting it would require event-based decoupling (e.g., Spring events) for minimal gain.
