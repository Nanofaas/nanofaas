# nanofaas

Minimal, high-performance FaaS control plane and Java function runtime designed for Kubernetes, with a focus on low latency and fast startup (GraalVM-ready).

## Modules

- `control-plane/` API gateway, in-memory queueing, scheduler thread, and Kubernetes dispatch
- `function-runtime/` HTTP runtime for Java function handlers
- `python-runtime/` HTTP runtime for Python function handlers
- `common/` shared DTOs and runtime contracts
- `k8s/` Kubernetes manifests and templates
- `docs/` architecture and operational docs
- `openapi.yaml` public API specification

## Requirements

- Java 21 (SDKMAN recommended)
- Docker-compatible container runtime (Docker Desktop or equivalent)
- For Kubernetes E2E: `kubectl`, `kind`, and a reachable cluster

## Quickstart (local)

```bash
./gradlew :control-plane:bootRun
./gradlew :function-runtime:bootRun
```

## Build images (buildpacks)

```bash
./gradlew :control-plane:bootBuildImage :function-runtime:bootBuildImage
```

## Native build (GraalVM)

```bash
./scripts/native-build.sh
```

## Tests

```bash
./gradlew test
```

E2E (local):
```bash
./scripts/e2e.sh
```

E2E (buildpacks):
```bash
./scripts/e2e-buildpack.sh
```

E2E (Kubernetes via Multipass + kind):
```bash
./scripts/setup-multipass-kind.sh
export KUBECONFIG=~/.kube/nanofaas-kind.yaml
./scripts/kind-build-load.sh
./gradlew :control-plane:test --tests com.nanofaas.controlplane.e2e.K8sE2eTest
```

## Observability

- Prometheus metrics are exposed via Spring Actuator (`/actuator/prometheus` on the management port).

## API

- See `openapi.yaml` for request/response contracts and examples.

## Docs

- `docs/architecture.md` and `docs/quickstart.md` provide a full overview and operational notes.
