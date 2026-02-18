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
- For Kubernetes E2E: [Multipass](https://multipass.run) and internet access to install k3s in-VM

## Quickstart (local)

```bash
./gradlew :control-plane:bootRun
./gradlew :function-runtime:bootRun
```

`bootRun` includes all optional control-plane modules by default.
Use `-PcontrolPlaneModules=none` to run a core-only control plane.

## Build images (buildpacks)

```bash
./gradlew :control-plane:bootBuildImage :function-runtime:bootBuildImage
```

## Custom control-plane builds

You can build a custom control plane by selecting optional modules at compile time.

```bash
# include one module
./gradlew :control-plane:bootJar -PcontrolPlaneModules=build-metadata

# include all modules found under control-plane-modules/
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all

# include no optional modules (core only)
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none

# inspect which modules are included in the current build
./gradlew :control-plane:printSelectedControlPlaneModules -PcontrolPlaneModules=build-metadata
```

You can also use `NANOFAAS_CONTROL_PLANE_MODULES` instead of `-PcontrolPlaneModules`.
Module authoring details are in `docs/control-plane-modules.md`.

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

E2E (Kubernetes via Multipass + k3s):
```bash
./gradlew k8sE2e
# or:
./scripts/e2e-k8s-vm.sh
```

## Observability

- Prometheus metrics are exposed via Spring Actuator (`/actuator/prometheus` on the management port).

## API

- See `openapi.yaml` for request/response contracts and examples.

## Docs

- `docs/architecture.md` and `docs/quickstart.md` provide a full overview and operational notes.
- `docs/loadtest-payload-profile.md` documents payload variability modes, metrics, and validation commands for k6 load tests.

## nanofaas-cli (CLI)

Standalone CLI (GraalVM native) under the `nanofaas-cli/` subproject.

Build a native executable (requires GraalVM):

```bash
./gradlew :nanofaas-cli:nativeCompile
./nanofaas-cli/build/native/nativeCompile/nanofaas-cli --help
```

Run on the JVM:

```bash
./gradlew :nanofaas-cli:run --args="--help"
```

Command reference: `docs/nanofaas-cli.md`.
