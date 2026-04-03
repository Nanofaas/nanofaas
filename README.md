# nanofaas

Minimal, high-performance FaaS control plane and Java function runtime with pluggable managed-deployment backends, optimized for low latency and fast startup (GraalVM-ready).

## Modules

- `control-plane/` API gateway, in-memory queueing, scheduler thread, and backend-neutral dispatch core
- `control-plane-modules/` optional modules, including managed deployment providers such as `k8s-deployment-provider` and `container-deployment-provider`
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
- For Kubernetes E2E: `python3`, OpenSSH client, internet access, and a Debian/Ubuntu-style target VM
- Operational Ansible assets live under `ops/ansible/`
- [Multipass](https://multipass.run) only if you want the scripts to create/manage the VM (`E2E_VM_LIFECYCLE=multipass`)

## Quickstart (local)

```bash
scripts/control-plane-build.sh run --profile core
./gradlew :function-runtime:bootRun
```

Use `scripts/control-plane-build.sh run --profile all` to start the full optional-module stack.
Use `scripts/control-plane-build.sh run --profile container-local -- --args=--nanofaas.deployment.default-backend=container-local`
for a no-Kubernetes managed-deployment profile.

## Control-plane tooling

Canonical tool root: `tools/controlplane/`.
Canonical shell entrypoint: `scripts/controlplane.sh`.

Use the canonical wrapper below for unified build, VM lifecycle, E2E, and TUI flows:

```bash
scripts/controlplane.sh build --profile core --dry-run
scripts/controlplane.sh functions list
scripts/controlplane.sh functions show-preset demo-loadtest
scripts/controlplane.sh vm up --lifecycle multipass --name nanofaas-e2e --dry-run
scripts/controlplane.sh e2e run k8s-vm --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run helm-stack --dry-run
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
scripts/controlplane.sh e2e run k8s-vm --saved-profile demo-java --dry-run
scripts/controlplane.sh e2e all --only k3s-curl,k8s-vm --dry-run
scripts/controlplane.sh loadtest list-profiles
scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run
scripts/controlplane.sh loadtest run --saved-profile demo-java --dry-run
scripts/e2e-loadtest.sh --profile demo-java --dry-run
scripts/controlplane.sh tui --profile-name dev
```

Use `scripts/controlplane.sh loadtest run ...` for the first-class k6 + Prometheus workflow. `scripts/e2e-loadtest.sh` remains a compatibility wrapper for the legacy Helm/Grafana/parity path and delegates to `experiments/e2e-loadtest.sh`; registry-only summary flags such as `--summary-only` belong to `scripts/e2e-loadtest-registry.sh`.

For VM-backed scenarios, provisioning is executed against the resolved VM host over SSH/Ansible rather than `localhost`. `scripts/controlplane.sh e2e all ...` plans one shared VM bootstrap block for VM-backed scenarios, reuses that session across scenarios, and tears the Multipass VM down once at the end unless `--keep-vm` is set. When `E2E_VM_LIFECYCLE=external`, the tool never attempts VM teardown.

Compatibility wrappers remain available for narrower entrypoints:

```bash
scripts/control-plane-build.sh build --profile core --dry-run
scripts/control-plane-build.sh image --profile all --dry-run
scripts/control-plane-build.sh test --profile k8s -- --tests '*CoreDefaultsTest'
scripts/control-plane-build.sh inspect --profile container-local --dry-run
scripts/controlplane-tool.sh --profile-name dev
```

The canonical operational asset root for VM provisioning is `ops/ansible/`.

Artifacts are written under:

- `tools/controlplane/profiles/<profile>.toml`
- `tools/controlplane/scenarios/<scenario>.toml`
- `tools/controlplane/runs/<timestamp>-<profile>/summary.json`
- `tools/controlplane/runs/<timestamp>-<profile>/report.html`

Function/scenario selection precedence for `scripts/controlplane.sh e2e run` is:

1. explicit CLI override (`--function-preset` or `--functions`)
2. `--scenario-file`
3. `--saved-profile`

When a CLI override is layered on top of a scenario file or saved profile, the tool preserves the inherited scenario metadata and shrinks payload/load selections to the chosen function subset. `helm-stack` defaults to the backend-safe `demo-loadtest` preset, and unsupported Go selections are rejected before the compatibility backend runs. For `k8s-vm`, the resolved selection is forwarded into the VM via `-Dnanofaas.e2e.scenarioManifest=...`, so the executed `K8sE2eTest` consumes the same manifest shown by dry-run output.

The repository ships `tools/controlplane/profiles/demo-java.toml` as a ready-to-run example profile.
`pipeline-run` remains as a compatibility alias over the first-class `loadtest run` workflow.

## Build images (buildpacks)

```bash
scripts/control-plane-build.sh image --profile all -- -PcontrolPlaneImage=nanofaas/control-plane:buildpack
./gradlew :function-runtime:bootBuildImage
```

## Custom control-plane builds

Use the wrapper for the common profiles:

```bash
scripts/control-plane-build.sh jar --profile core
scripts/control-plane-build.sh run --profile container-local -- --args=--nanofaas.deployment.default-backend=container-local
scripts/control-plane-build.sh image --profile k8s -- -PcontrolPlaneImage=nanofaas/control-plane:test
scripts/control-plane-build.sh native --profile all
scripts/control-plane-build.sh test --profile core -- --tests '*CoreDefaultsTest'
scripts/control-plane-build.sh matrix --task :control-plane:bootJar --max-combinations 4 --dry-run
scripts/control-plane-build.sh inspect --profile all
```

Raw Gradle remains available for low-level/advanced workflows.

```bash
# include one module
./gradlew :control-plane:bootJar -PcontrolPlaneModules=build-metadata

# run with the local managed-deployment provider only
./gradlew :control-plane:bootRun \
  -PcontrolPlaneModules=container-deployment-provider \
  --args='--nanofaas.deployment.default-backend=container-local'

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
# or directly through the canonical orchestration wrapper:
./scripts/controlplane.sh e2e run k8s-vm
```

VM-based E2E can also target an existing local or remote VM over SSH/SCP:

```bash
E2E_VM_LIFECYCLE=external E2E_VM_HOST=192.168.64.20 E2E_VM_USER=ubuntu ./scripts/e2e-k3s-curl.sh
E2E_VM_LIFECYCLE=external E2E_VM_HOST=ci-k3s.example.com E2E_VM_USER=dev E2E_VM_HOME=/srv/dev E2E_KUBECONFIG_SERVER=https://ci-k3s.example.com:6443 ./scripts/e2e-cli-host-platform.sh
```

Supported external-VM variables:
`E2E_VM_LIFECYCLE=external`, `E2E_VM_HOST`, `E2E_VM_USER`, `E2E_VM_HOME`, `E2E_KUBECONFIG_PATH`, `E2E_REMOTE_PROJECT_DIR`, `E2E_PUBLIC_HOST`, `E2E_KUBECONFIG_SERVER`.
SSH/SCP are always used for remote command execution and file transfer. VM provisioning is driven by Ansible over SSH for both `multipass` and `external` lifecycle modes. If `ansible-playbook` is not already installed on the host, the scripts bootstrap it idempotently in a local virtualenv and fall back to a user-site `pip` install when `python3 -m venv` is unavailable. k3s defaults to the latest official release at run time; set `K3S_VERSION` only when you need to pin a specific version. Multipass is used only for VM lifecycle when `E2E_VM_LIFECYCLE=multipass`.
For wrapper-driven VM scenarios, `KEEP_VM=true` maps to the tool-level `--keep-vm` behavior: keep Multipass instances for debugging, but never delete external VMs.
Most top-level `scripts/e2e*.sh` files remain compatibility wrappers over `scripts/controlplane.sh e2e ...`; `scripts/e2e-loadtest.sh` is the intentional exception because it preserves the legacy Helm/Grafana/parity workflow via `experiments/e2e-loadtest.sh`, while `scripts/e2e-loadtest-registry.sh` owns registry-summary flows such as `--summary-only`.

E2E/module matrix (control-plane optional modules compile):
```bash
scripts/control-plane-build.sh matrix --task :control-plane:bootJar --max-combinations 4 --dry-run
./scripts/test-control-plane-module-combinations.sh
```

## Observability

- Prometheus metrics are exposed via Spring Actuator (`/actuator/prometheus` on the management port).

## API

- See `openapi.yaml` for request/response contracts and examples.

## Docs

- `docs/tutorial-java-function.md` — step-by-step guide to writing, building, and invoking a Java function.
- `docs/architecture.md` and `docs/quickstart.md` provide a full overview and operational notes.
- `docs/no-k8s-profile.md` documents the `container-local` managed-deployment profile.
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
