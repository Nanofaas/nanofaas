# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Build all modules
./gradlew build

# Run locally
./gradlew :control-plane:bootRun    # API on :8080, metrics on :8081
./gradlew :function-runtime:bootRun # Handler on :8080

# Run all tests
./gradlew test

# Run a single test class
./gradlew :control-plane:test --tests com.nanofaas.controlplane.core.QueueManagerTest

# E2E tests (requires Docker)
./scripts/e2e.sh                    # Local containers
./scripts/e2e-buildpack.sh          # Buildpack images

# K3s E2E with Curl (self-contained Multipass VM)
./scripts/e2e-k3s-curl.sh              # Full test with VM cleanup
KEEP_VM=true ./scripts/e2e-k3s-curl.sh # Keep VM for debugging

# Kubernetes E2E (requires kind cluster)
./scripts/setup-multipass-kind.sh
export KUBECONFIG=~/.kube/nanofaas-kind.yaml
./scripts/kind-build-load.sh
./gradlew :control-plane:test --tests com.nanofaas.controlplane.e2e.K8sE2eTest

# Build OCI images
./gradlew :control-plane:bootBuildImage :function-runtime:bootBuildImage

# Build Python runtime image
cd python-runtime && ./build.sh  # or: docker build -t nanofaas/python-runtime python-runtime/

# Native build (GraalVM via SDKMAN)
./scripts/native-build.sh
```

## Architecture Overview

nanofaas is a minimal FaaS platform for Kubernetes with four modules:

### control-plane/
API gateway + scheduler + dispatcher in a single pod. Key components:
- **FunctionRegistry** - In-memory function storage
- **QueueManager** - Per-function bounded queues with backpressure (default 100 items)
- **Scheduler** - Single dedicated thread dispatching from all queues
- **KubernetesDispatcher** - Creates K8s Jobs for function execution
- **PoolDispatcher** - Optional warm pool mode for faster invocation (supports WARM execution mode)
- **ExecutionState** - Tracks execution lifecycle with TTL eviction

Execution Modes:
- **JOB** - Default mode; creates a new K8s Job per invocation
- **WARM** - OpenWhisk-style warm containers; reuses running pods for lower latency

Spring WebFlux (non-blocking). Ports: 8080 (API), 8081 (management/metrics).

### function-runtime/
Minimal HTTP server for Java function handlers:
- **InvokeController** - POST `/invoke` endpoint
- **HandlerRegistry** - SPI-based handler loading
- **TraceLoggingFilter** - Propagates X-Trace-Id and X-Execution-Id headers

Supports WARM mode via `X-Execution-Id` header for OpenWhisk-style execution.

Spring Web (servlet). Port: 8080.

### python-runtime/
Python function runtime with watchdog for WARM execution mode:
- Loads user functions dynamically
- Supports OpenWhisk-style warm containers
- Accepts `X-Execution-Id` and `X-Trace-Id` headers

Build: `python-runtime/build.sh` or `docker build -t nanofaas/python-runtime python-runtime/`

### common/
Shared contracts: `FunctionSpec`, `InvocationRequest`, `InvocationResponse`, `ExecutionStatus`, `FunctionHandler` interface.

## Request Flow

1. Client → `POST /v1/functions/{name}:invoke` (sync) or `:enqueue` (async)
2. Control plane validates, applies rate limit (1000/sec default), queues request
3. Scheduler thread picks from queue, calls dispatcher
4. KubernetesDispatcher creates Job using template from `k8s/function-job-template.yaml`
5. Job pod runs function-runtime, calls handler, POSTs result back to `/v1/internal/executions`
6. Control plane updates ExecutionState, returns result to client

## Key Configuration

`control-plane/src/main/resources/application.yml`:
- `nanofaas.defaults.timeoutMs` (30000), `concurrency` (4), `queueSize` (100), `maxRetries` (3)
- `nanofaas.rate.maxPerSecond` (1000)
- `nanofaas.k8s.namespace`, `callbackUrl`

## Testing

- JUnit 5, tests named `*Test.java`
- E2E uses Testcontainers + RestAssured
- K8s E2E requires `KUBECONFIG` environment variable
- Fabric8 mock server for K8s unit tests

## Project Constraints

- Single control-plane pod (no HA/distributed mode)
- In-memory queues (no durability)
- No authentication/authorization
- Performance and latency prioritized over features
- Java 21 toolchain, 4-space indentation, `com.nanofaas` package root
