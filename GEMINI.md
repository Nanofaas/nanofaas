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

2.  **`function-runtime/`**: A minimal HTTP server wrapper for Java user functions.
    -   Exposes a `POST /invoke` endpoint.
    -   Executes the registered `FunctionHandler`.
    -   Propagates `X-Trace-Id` and `X-Execution-Id` headers.
    -   Supports WARM execution mode for OpenWhisk-style warm containers.

3.  **`python-runtime/`**: Python function runtime with watchdog.
    -   Supports WARM execution mode (OpenWhisk-style).
    -   Accepts `X-Execution-Id` and `X-Trace-Id` headers.
    -   Build: `python-runtime/build.sh` or `docker build`.

4.  **`common/`**: Shared library containing:
    -   Data Transfer Objects (DTOs) like `FunctionSpec`, `InvocationRequest`.
    -   Service interfaces and contracts.

## Development Workflow

### Prerequisites
- Java 21 (SDKMAN recommended)
- Docker / Container Runtime
- `kubectl` and `kind` (for K8s E2E tests)

### Build & Test Commands

| Action | Command |
| :--- | :--- |
| **Build All** | `./gradlew build` |
| **Run Tests** | `./gradlew test` |
| **Run Specific Test** | `./gradlew :control-plane:test --tests com.nanofaas.controlplane.core.QueueManagerTest` |
| **Local E2E** | `./scripts/e2e.sh` (Uses Testcontainers) |
| **Buildpack E2E** | `./scripts/e2e-buildpack.sh` |
| **K8s E2E** | `./scripts/setup-multipass-kind.sh` && `export KUBECONFIG=~/.kube/nanofaas-kind.yaml` && `./scripts/kind-build-load.sh` && `./gradlew :control-plane:test --tests com.nanofaas.controlplane.e2e.K8sE2eTest` |

### Running Locally

| Service | Command | Port |
| :--- | :--- | :--- |
| **Control Plane** | `./gradlew :control-plane:bootRun` | `:8080` (API), `:8081` (Metrics) |
| **Function Runtime** | `./gradlew :function-runtime:bootRun` | `:8080` |

### Native Builds (GraalVM)

To build native images using GraalVM:
```bash
./scripts/native-build.sh
```

### Docker / OCI Images

To build container images using Spring Boot Buildpacks:
```bash
./gradlew :control-plane:bootBuildImage :function-runtime:bootBuildImage
```

To build the Python runtime image:
```bash
cd python-runtime && ./build.sh
# or: docker build -t nanofaas/python-runtime python-runtime/
```

## Project Structure

```text
/
├── common/             # Shared DTOs and interfaces
├── control-plane/      # Main service (Gateway, Scheduler, K8s Dispatch)
│   ├── src/main/resources/application.yml  # Main config
│   └── src/test/java/  # Unit & E2E tests
├── function-runtime/   # Java function HTTP wrapper
├── python-runtime/     # Python function runtime with watchdog (WARM mode)
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
- **Function Runtime:** `function-runtime/src/main/resources/application.yml`
