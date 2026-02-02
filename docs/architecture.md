# System Architecture (Minimum Detail)

## Goals

- Performance first: lowest possible latency and cold-start overhead.
- Control plane in a single pod (gateway + queue + scheduler).
- Function executions in separate Kubernetes pods (Job by default, optional pool endpoint).
- Java + Spring Boot + GraalVM native images.
- Prometheus observability.

## Non-Goals

- Multi-region, multi-cluster, or HA control plane.
- Durable queue (in-memory only for MVP).
- AuthN/AuthZ (explicitly out of scope).

## High-Level Components

- Control Plane (single pod):
  - API Gateway (WebFlux)
  - Function Registry (in-memory)
  - Queue Manager (per-function bounded queues)
  - Scheduler (dedicated thread)
  - K8s Dispatcher (Job/Pod creation for JOB mode)
  - Pool Dispatcher (warm container routing for WARM mode)
  - Execution Store (in-memory)
- Function Runtimes (separate pods):
  - Java Runtime: HTTP server with SPI-based handler loading
  - Python Runtime: Watchdog-based runtime for WARM execution mode
  - Both accept `X-Execution-Id` and `X-Trace-Id` headers

## Data Flow

### Sync Invocation

1. Client calls `POST /v1/functions/{name}:invoke`.
2. Gateway validates request, resolves FunctionSpec.
3. Enqueue into function queue.
4. Scheduler dequeues and dispatches to K8s Job.
5. Control plane waits on completion future until timeout.
6. Returns output or error.

### Async Invocation

1. Client calls `POST /v1/functions/{name}:enqueue`.
2. Gateway validates and enqueues.
3. Returns 202 + executionId immediately.
4. Scheduler dispatches.
5. Client polls `/v1/executions/{executionId}`.

## Execution Modes

- **JOB mode** (default): Creates a new Kubernetes Job per invocation. Higher latency due to cold start.
- **WARM mode**: OpenWhisk-style warm containers. Reuses running pods for lower latency. Function runtimes stay alive and accept multiple invocations via `X-Execution-Id` header.

## Module Layout (Proposed)

- `control-plane/` : Spring Boot app (gateway, scheduler, dispatcher)
- `function-runtime/` : minimal Spring Boot runtime for Java user functions
- `python-runtime/` : Python runtime with watchdog for WARM execution mode
- `common/` : shared models (FunctionSpec, InvocationRequest, ErrorInfo)
- `k8s/` : manifests and templates
- `docs/` : architecture, control-plane, runtime, observability

## Primary Kubernetes Objects

- Control plane: Deployment + Service + ServiceAccount + RBAC
- Function execution (JOB mode): Job per invocation
- Function execution (WARM mode): per-function Deployment for warm pool

## Performance Strategy

- Native image for control plane and runtime.
- Pre-allocated queues and object pooling for hot paths.
- Single scheduler thread to reduce contention.
- No cross-pod coordination or distributed locks.

## Failure Model

- Control plane restart loses in-memory queue and execution state.
- Function failures can be retried up to `maxRetries` (default 3).
- Client idempotency is required for safe retries.
