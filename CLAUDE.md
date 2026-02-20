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
./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.config.CoreDefaultsTest

# E2E tests (requires Docker)
./scripts/e2e.sh                    # Local containers
./scripts/e2e-buildpack.sh          # Buildpack images

# CLI E2E (full CLI against k3s, 47 tests)
./scripts/e2e-cli.sh                       # Full test with VM cleanup
KEEP_VM=true ./scripts/e2e-cli.sh          # Keep VM for debugging

# K3s E2E with Curl (self-contained Multipass VM)
./scripts/e2e-k3s-curl.sh              # Full test with VM cleanup
KEEP_VM=true ./scripts/e2e-k3s-curl.sh # Keep VM for debugging

# Kubernetes E2E (k3s in Multipass)
./scripts/e2e-k8s-vm.sh
# or:
./gradlew k8sE2e

# Build OCI images
./gradlew :control-plane:bootBuildImage :function-runtime:bootBuildImage

# Control-plane optional module selection
./gradlew :control-plane:bootRun -PcontrolPlaneModules=all
./gradlew :control-plane:test -PcontrolPlaneModules=all
NANOFAAS_CONTROL_PLANE_MODULES=none ./gradlew :control-plane:bootJar
# If selector is omitted: runtime/artifact tasks default to all modules, non-runtime tasks default to core-only.

# Build Python runtime image
cd python-runtime && ./build.sh  # or: docker build -t nanofaas/python-runtime python-runtime/

# Native build (GraalVM via SDKMAN)
./scripts/native-build.sh

# Experiments / load tests (see experiments/)
./experiments/e2e-loadtest.sh             # k6 load tests + Grafana (requires deployed nanofaas)
./experiments/e2e-loadtest-registry.sh    # Full VM deploy + load test from registry images
./experiments/e2e-autoscaling.sh          # Autoscaling verification
./experiments/e2e-cold-start-metrics.sh   # Cold start metrics
./experiments/e2e-runtime-config.sh       # Runtime config hot-update E2E
./experiments/run.sh                      # Interactive wizard (control-plane experiment)
# Python tests for experiments
cd experiments && python -m pytest tests/ -v
```

## Architecture Overview

nanofaas is a minimal FaaS platform for Kubernetes.

### control-plane/
Minimal core API + dispatch orchestration in a single pod. Core components:
- **FunctionRegistry** - In-memory function storage
- **InvocationService** - Sync/async invocation orchestration, retries, idempotency integration
- **PoolDispatcher** - Dispatches to warm Deployment+Service pods (supports DEPLOYMENT and POOL execution modes)
- **ExecutionStore** - Tracks execution lifecycle with TTL eviction

Core provides no-op defaults for:
- **InvocationEnqueuer**
- **ScalingMetricsSource**
- **SyncQueueGateway**
- **ImageValidator**

Optional control-plane modules (loaded via `ControlPlaneModule` SPI from `control-plane-modules/`):
- **async-queue** - Per-function queues + scheduler for async enqueue path
- **sync-queue** - Sync admission/backpressure queue
- **autoscaler** - Internal scaler and scaling metrics integration
- **runtime-config** - Hot runtime config service and admin API (`/v1/admin/runtime-config`) when `nanofaas.admin.runtime-config.enabled=true`
- **image-validator** - Kubernetes-backed image validation
- **build-metadata** - `/modules/build-metadata` diagnostics endpoint

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

1. Client -> `POST /v1/functions/{name}:invoke` (sync) or `:enqueue` (async)
2. Control plane validates, applies rate limit, creates execution state
3. Sync path: uses `sync-queue` if enabled, else `async-queue` if enabled, else dispatches inline from core
4. Async path: requires `async-queue` (otherwise API returns `501 Not Implemented`)
5. Dispatcher forwards request to runtime endpoint (LOCAL/POOL/DEPLOYMENT mode)
6. Control plane updates execution state and returns result/status

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
- In-memory state (and in-memory queues when queue modules are enabled)
- No authentication/authorization
- Performance and latency prioritized over features
- Java 21 toolchain, 4-space indentation, `com.nanofaas` package root
