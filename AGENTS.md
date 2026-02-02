# Repository Guidelines

## Project Structure & Module Organization

- `common/` contains shared DTOs and runtime interfaces (e.g., handler contracts used by both services).
- `control-plane/` is the API gateway + scheduler + in-memory queues + Kubernetes dispatch logic (supports JOB and WARM execution modes).
- `function-runtime/` hosts the Java function invocation HTTP server and handler registry.
- `python-runtime/` provides Python function runtime with watchdog for WARM execution mode (OpenWhisk-style).
- `docs/` holds architecture and operational documentation; `openapi.yaml` is the API spec.
- `k8s/` contains Kubernetes manifests; `scripts/` provides helper workflows.
- Tests live in `*/src/test/java` with E2E tests under `control-plane/src/test/java/.../e2e`.

## Build, Test, and Development Commands

- `./gradlew build` — compile all modules and assemble artifacts.
- `./gradlew test` — run unit/integration/E2E tests (requires container runtime; K8s E2E also requires `KUBECONFIG`).
- `./gradlew :control-plane:bootRun` — run the control plane locally.
- `./gradlew :function-runtime:bootRun` — run the function runtime locally.
- `./gradlew :control-plane:bootBuildImage` and `:function-runtime:bootBuildImage` — create buildpack images.
- `python-runtime/build.sh` or `docker build -t nanofaas/python-runtime python-runtime/` — build Python runtime image.
- `scripts/native-build.sh` — build GraalVM native binaries (uses SDKMAN).
- `scripts/e2e.sh` and `scripts/e2e-buildpack.sh` — run local E2E suites.
- `scripts/setup-multipass-kind.sh` + `scripts/kind-build-load.sh` — provision a kind cluster in Multipass and load images.

## Coding Style & Naming Conventions

- Java 21 toolchain; 4-space indentation; `com.nanofaas` package root.
- Class names `PascalCase`, methods/fields `camelCase`, constants `SCREAMING_SNAKE_CASE`.
- Configuration lives in `control-plane/src/main/resources/application.yml` and `function-runtime/src/main/resources/application.yml`.

## Testing Guidelines

- JUnit 5 is the primary framework; tests are named `*Test.java`.
- E2E tests use Testcontainers, RestAssured, and Fabric8; ensure Docker/compatible runtime is available.
- K8s E2E (`K8sE2eTest`) requires `KUBECONFIG` and reachable images; it is not skipped by default.

## Project Constraints & Requirements (FaaS MVP)

- Language: Java with Spring Boot; native image support via GraalVM build tools.
- Single control-plane pod: API gateway, in-memory queueing, and a dedicated scheduler thread.
- Function execution runs in separate Kubernetes pods (JOB mode for cold starts, WARM mode for OpenWhisk-style warm containers).
- No authentication/authorization in scope.
- Prometheus metrics exposed via Micrometer/Actuator.
- Retry default is 3 and must be user-configurable; clients handle idempotency.
- Performance and low latency take priority over feature breadth.

## Commit & Pull Request Guidelines

- No git history is present; use short, imperative commits (e.g., `Add queue backpressure`).
- PRs should include a summary, tests run, and updates to `docs/`, `openapi.yaml`, and `k8s/` when behavior changes.
