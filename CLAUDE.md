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

# CLI E2E (full CLI against k3s, 40 tests)
./scripts/e2e-cli.sh                       # Full test with VM cleanup
KEEP_VM=true ./scripts/e2e-cli.sh          # Keep VM for debugging

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
- **PoolDispatcher** - Dispatches to warm Deployment+Service pods (supports DEPLOYMENT and POOL execution modes)
- **ExecutionState** - Tracks execution lifecycle with TTL eviction

Execution Modes:
- **DEPLOYMENT** - Default mode; routes to K8s Deployment+Service with warm containers
- **POOL** - OpenWhisk-style warm pool mode
- **LOCAL** - In-process execution for testing

Spring WebFlux (non-blocking). Ports: 8080 (API), 8081 (management/metrics).

### function-runtime/
Minimal HTTP server for Java function handlers:
- **InvokeController** - POST `/invoke` endpoint
- **HandlerRegistry** - SPI-based handler loading
- **TraceLoggingFilter** - Propagates X-Trace-Id and X-Execution-Id headers

Supports WARM mode via `X-Execution-Id` header for OpenWhisk-style execution.

Spring Web (servlet). Port: 8080.

### python-runtime/ (deprecated)
Legacy Python runtime. New Python functions should use `function-sdk-python/` instead.

### function-sdk-python/
Python function SDK providing the FastAPI-based runtime for Python handlers.

### common/
Shared contracts: `FunctionSpec`, `InvocationRequest`, `InvocationResponse`, `ExecutionStatus`, `FunctionHandler` interface.

## Request Flow

1. Client → `POST /v1/functions/{name}:invoke` (sync) or `:enqueue` (async)
2. Control plane validates, applies rate limit (1000/sec default), queues request
3. Scheduler thread picks from queue, calls dispatcher
4. PoolDispatcher forwards request to the function's Deployment/Service endpoint
5. Function pod processes request, returns result to control plane
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
