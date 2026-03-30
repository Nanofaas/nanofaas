# nanofaas

Minimal, high-performance FaaS control plane and Java function runtime designed for Kubernetes, with a focus on low latency and fast startup (GraalVM-ready).

## Modules

- `control-plane/` API gateway, in-memory queueing, scheduler thread, and Kubernetes dispatch
- `function-sdk-go/` Go SDK for authoring NanoFaaS functions with an embedded HTTP runtime
- `function-runtime/` HTTP runtime for Java function handlers
- `python-runtime/` HTTP runtime for Python function handlers
- `common/` shared DTOs and runtime contracts
- `k8s/` Kubernetes manifests and templates
- `docs/` architecture and operational docs
- `openapi.yaml` public API specification

## Requirements

- Java 21 (SDKMAN recommended)
- Docker-compatible container runtime (Docker Desktop or equivalent)
- For Kubernetes E2E: OpenSSH client and internet access to install k3s in-VM
- [Multipass](https://multipass.run) only if you want the scripts to create/manage the VM (`E2E_VM_LIFECYCLE=multipass`)

## Quickstart (local)

Invoke these commands in 2 different terminals:
```bash
./gradlew :control-plane:bootRun
```

```bash
SERVER_PORT=8082 ./gradlew :function-runtime:bootRun
```

`bootRun` includes all optional control-plane modules by default.
Use `-PcontrolPlaneModules=none` to run a core-only control plane.

## Control-plane local tooling (TUI)

Interactive local orchestrator for profile-based control-plane builds, optional test phases, and HTML report generation.

```bash
scripts/controlplane-tool.sh --help
scripts/controlplane-tool.sh --profile-name dev
scripts/controlplane-tool.sh --profile-name dev --use-saved-profile
```

Artifacts are written under:

- `tooling/profiles/<profile>.toml`
- `tooling/runs/<timestamp>-<profile>/summary.json`
- `tooling/runs/<timestamp>-<profile>/report.html`

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
cd function-sdk-go && go test ./...
```

E2E (local):
```bash
./scripts/e2e.sh
```

E2E (buildpacks):
```bash
./scripts/e2e-buildpack.sh
```

E2E (Kubernetes VM + k3s):
```bash
./gradlew k8sE2e
# or:
./scripts/e2e-k8s-vm.sh
```

VM-based E2E can also target an existing local or remote VM over SSH/SCP:

```bash
E2E_VM_LIFECYCLE=external E2E_VM_HOST=192.168.64.20 E2E_VM_USER=ubuntu ./scripts/e2e-k3s-curl.sh
E2E_VM_LIFECYCLE=external E2E_VM_HOST=ci-k3s.example.com E2E_VM_USER=dev E2E_VM_HOME=/srv/dev E2E_KUBECONFIG_SERVER=https://ci-k3s.example.com:6443 ./scripts/e2e-cli-host-platform.sh
```

Supported external-VM variables:
`E2E_VM_LIFECYCLE=external`, `E2E_VM_HOST`, `E2E_VM_USER`, `E2E_VM_HOME`, `E2E_KUBECONFIG_PATH`, `E2E_REMOTE_PROJECT_DIR`, `E2E_PUBLIC_HOST`, `E2E_KUBECONFIG_SERVER`.
SSH/SCP are always used for remote command execution and file transfer. Multipass is used only for VM lifecycle when `E2E_VM_LIFECYCLE=multipass`.

E2E/module matrix (control-plane optional modules compile):
```bash
./scripts/test-control-plane-module-combinations.sh
```

## Observability

- Prometheus metrics are exposed via Spring Actuator (`/actuator/prometheus` on the management port).

## API

- See `openapi.yaml` for request/response contracts and examples.

## Docs

- `docs/tutorial-java-function.md` — step-by-step guide to writing, building, and invoking a Java function.
- `docs/architecture.md` and `docs/quickstart.md` provide a full overview and operational notes.
- `docs/loadtest-payload-profile.md` documents payload variability modes, metrics, and validation commands for k6 load tests.
- `function-sdk-go/README.md` documents the planned Go function authoring/runtime SDK.

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
