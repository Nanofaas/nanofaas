# nanofaas - Gemini Context

## Project Overview

**nanoFaaS** is a minimal, high-performance FaaS (Function-as-a-Service) platform designed for Kubernetes. It focuses on low latency and fast startup times, leveraging Java 21, Spring Boot, and GraalVM native images.

### Key Goals
- **Performance First:** Minimized latency and cold-start overhead.
- **Simplicity:** Single control-plane pod (API Gateway + Queue + Scheduler).
- **Kubernetes Native:** Functions run as Kubernetes Jobs (default) or via warm pools.
- **Observability:** Prometheus metrics via Spring Actuator.

### Constraints
- **MVP Scope:** No multi-region/HA, no durable queues (in-memory only), no AuthN/AuthZ.
- **Runtime:** Java 21 toolchain.

## Architecture

The system consists of four main modules:

1.  **`control-plane/`**: The core service containing:
    -   **API Gateway:** Spring WebFlux (Netty) handling synchronous (`:invoke`) and asynchronous (`:enqueue`) requests.
    -   **Function Registry:** In-memory storage of function definitions.
    -   **Queue Manager:** Per-function bounded in-memory queues with backpressure.
    -   **Scheduler:** A single dedicated thread that dispatches work from queues to the K8s Dispatcher.
    -   **Kubernetes Dispatcher:** Creates K8s Jobs based on templates (JOB mode).
    -   **Pool Dispatcher:** Routes to warm containers for OpenWhisk-style execution (WARM mode).
    -   **Execution Store:** Tracks state of executions (Pending, Running, Succeeded, Failed).

2.  **`sdks/java/` and `services/java/warm-echo/`**: The SDK provides the reusable Java HTTP invocation runtime; warm-echo is a runnable long-running WARM example using it.
    -   Exposes a `POST /invoke` endpoint through the SDK.
    -   Is provisioned as a normal NanoFaaS function, not as shared infrastructure.

3.  **`sdks/python/`**: Python function SDK with a FastAPI runtime.
    -   Supports synchronous and asynchronous Python handlers.
    -   Accepts `X-Execution-Id` and `X-Trace-Id` headers.

4.  **`common/`**: Shared library containing:
    -   Data Transfer Objects (DTOs) like `FunctionSpec`, `InvocationRequest`.
    -   Service interfaces and contracts.

## Development Workflow

### Prerequisites
- Java 21 (SDKMAN recommended)
- Docker / Container Runtime
- [Multipass](https://multipass.run) (for K8s E2E tests on k3s)

### Build & Test Commands

| Action | Command |
| :--- | :--- |
| **Build All** | `./gradlew build` |
| **Run Tests** | `./gradlew test` |
| **Run Specific Test** | `./gradlew :control-plane:test --tests com.nanofaas.controlplane.core.QueueManagerTest` |
| **Local E2E** | `./scripts/controlplane.sh e2e run docker` (Uses Testcontainers) |
| **Buildpack E2E** | `./scripts/controlplane.sh e2e run buildpack` |
| **K8s E2E** | `./scripts/controlplane.sh e2e run k3s-junit-curl` or `./gradlew k8sE2e` |

### Running Locally

| Service | Command | Port |
| :--- | :--- | :--- |
| **Control Plane** | `./gradlew :control-plane:bootRun` | `:8080` (API), `:8081` (Metrics) |
| **Warm Echo example** | `./gradlew :services:java:warm-echo:bootRun` | `:8080` |

### Native Builds (GraalVM)

To build native images using GraalVM:
```bash
./scripts/native-build.sh
```

### Docker / OCI Images

To build container images using Spring Boot Buildpacks:
```bash
./gradlew :control-plane:bootBuildImage :services:java:warm-echo:bootBuildImage
```

## Project Structure

```text
/
├── platform/common/            # Shared DTOs and interfaces
├── platform/control-plane/     # Main service (Gateway, Scheduler, K8s Dispatch)
│   ├── src/main/resources/application.yml  # Main config
│   └── src/test/java/  # Unit & E2E tests
├── sdks/java/                   # Reusable Java invocation runtime
├── services/java/warm-echo/     # Long-running WARM example service
├── sdks/python/        # Python function SDK and FastAPI runtime
├── docs/               # Architecture and operational docs
├── k8s/                # Kubernetes manifests & templates
├── scripts/            # Helper scripts for E2E and setup
├── openapi.yaml        # Public API specification
└── build.gradle        # Root build configuration
```

## Conventions

- **Code Style:** Java 21, 4-space indentation, `com.nanofaas` package root.
- **Naming:** `PascalCase` classes, `camelCase` methods/fields, `SCREAMING_SNAKE_CASE` constants.
- **Testing:**
    -   Use JUnit 5.
    -   E2E tests live in `control-plane` and use Testcontainers/RestAssured.
    -   Mock K8s interactions using Fabric8 mock server for unit tests.
- **Commits:** Short, imperative commit messages (e.g., "Add queue backpressure").

## Key Configuration

Configuration is primarily handled in `application.yml` files:
- **Control Plane:** `control-plane/src/main/resources/application.yml`
    -   `nanofaas.defaults.timeoutMs`
    -   `nanofaas.rate.maxPerSecond`
    -   `nanofaas.k8s.namespace`
- **Warm Echo example:** `services/java/warm-echo/src/main/resources/application.yml`
