# nanofaas

Minimal, high-performance FaaS control plane and Java function runtime with pluggable managed-deployment backends, optimized for low latency and fast startup (GraalVM-ready).

## Modules

- `control-plane/` API gateway, in-memory queueing, scheduler thread, and backend-neutral dispatch core
- `control-plane-modules/` optional modules, including managed deployment providers such as `k8s-deployment-provider` and `container-deployment-provider`
- `function-sdk-go/` Go SDK for authoring NanoFaaS functions with an embedded HTTP runtime
- `function-sdk-javascript/` TypeScript/JavaScript SDK for authoring NanoFaaS functions on Node.js
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

Invoke these commands in 2 different terminals:
```bash
scripts/controlplane.sh run --profile core
./gradlew :function-runtime:bootRun
```

Use `scripts/controlplane.sh run --profile all` to start the full optional-module stack.
Use `scripts/controlplane.sh run --profile container-local -- --args=--nanofaas.deployment.default-backend=container-local`
for a no-Kubernetes managed-deployment profile.

## Control-plane tooling

Canonical tool root: `tools/controlplane/`.
Canonical shell entrypoint: `scripts/controlplane.sh`.

Use the canonical wrapper below for unified build, VM lifecycle, CLI validation, E2E, and TUI flows:

```bash
scripts/controlplane.sh build --profile core --dry-run
scripts/controlplane.sh functions list
scripts/controlplane.sh functions show-preset demo-javascript
scripts/controlplane.sh functions show-preset demo-loadtest
scripts/controlplane.sh vm up --lifecycle multipass --name nanofaas-e2e --dry-run
scripts/controlplane.sh cli-test list
scripts/controlplane.sh cli-test run vm --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript --dry-run
scripts/controlplane.sh cli-test run host-platform --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run deploy-host --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run
scripts/controlplane.sh e2e run helm-stack --dry-run
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --saved-profile demo-java --dry-run
scripts/controlplane.sh e2e all --only k3s-junit-curl --dry-run
scripts/controlplane.sh loadtest list-profiles
scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run
scripts/controlplane.sh loadtest run --saved-profile demo-java --dry-run
scripts/e2e-loadtest.sh --profile demo-java --dry-run
scripts/controlplane.sh tui
```

Use `scripts/controlplane.sh loadtest run ...` for the first-class k6 + Prometheus workflow. `scripts/e2e-loadtest.sh` remains a compatibility wrapper for the legacy Helm/Grafana/parity path and delegates to `experiments/e2e-loadtest.sh`; registry-only summary flags such as `--summary-only` belong to `scripts/e2e-loadtest-registry.sh`. Use `scripts/controlplane.sh tui` for the interactive product surface, then pick or create profiles from the `Profiles` section.

For VM-backed scenarios, provisioning is executed against the resolved VM host over SSH/Ansible rather than `localhost`. `scripts/controlplane.sh e2e all ...` plans one shared VM bootstrap block for VM-backed scenarios, reuses that session across scenarios, and tears the Multipass VM down once at the end unless `--no-cleanup-vm` is set. When `E2E_VM_LIFECYCLE=external`, the tool never attempts VM teardown.

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

When a CLI override is layered on top of a scenario file or saved profile, the tool preserves the inherited scenario metadata and shrinks payload/load selections to the chosen function subset. `helm-stack` defaults to the backend-safe `demo-loadtest` preset, and unsupported Go selections are rejected before the compatibility backend runs. For `k3s-junit-curl`, the resolved selection is forwarded into the VM via `-Dnanofaas.e2e.scenarioManifest=...`, so the executed `K8sE2eTest` consumes the same manifest shown by dry-run output.

The repository ships `tools/controlplane/profiles/demo-java.toml` as a ready-to-run example profile.
Saved profiles can also persist `cli_test.default_scenario`, so `scripts/controlplane.sh cli-test run --saved-profile demo-java --dry-run` can resolve the scenario from the profile.
`k3s-junit-curl`, `helm-stack`, and `cli-stack` are the self-bootstrapping VM-backed scenarios. When you do not pass an explicit VM request, the controlplane tool creates and configures a managed VM and installs scenario-specific software inside that VM instead of assuming host-installed Helm, kubectl, k3s, local-registry tooling, or `nanofaas-cli`.
Within `cli-test`, `cli-stack` is the canonical VM-backed CLI stack scenario: it builds the CLI in the VM, installs Helm, k3s, and the registry there, then validates function build/push/apply/invoke/enqueue/delete plus `platform install/status/uninstall`. `host-platform` remains intentionally platform-only and ignores saved function selections, while `vm` preserves the legacy in-VM CLI path and `deploy-host` iterates the full selected set on the host. Missing saved profiles or scenario files fail fast with exit code 2.

## Build images (buildpacks)

```bash
scripts/controlplane.sh image --profile all -- -PcontrolPlaneImage=nanofaas/control-plane:buildpack
./gradlew :function-runtime:bootBuildImage
```

## Custom control-plane builds

Use the wrapper for the common profiles:

```bash
scripts/controlplane.sh build --profile container-local --dry-run
scripts/controlplane.sh jar --profile core
scripts/controlplane.sh run --profile container-local -- --args=--nanofaas.deployment.default-backend=container-local
scripts/controlplane.sh image --profile k8s -- -PcontrolPlaneImage=nanofaas/control-plane:test
scripts/controlplane.sh native --profile all
scripts/controlplane.sh test --profile core -- --tests '*CoreDefaultsTest'
scripts/controlplane.sh matrix --task :control-plane:bootJar --max-combinations 4 --dry-run
scripts/controlplane.sh inspect --profile all
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
cd function-sdk-javascript && npm test
cd examples/javascript/word-stats && npm install && npm test
```

E2E (local):
```bash
./scripts/controlplane.sh e2e run docker
```

E2E (buildpacks):
```bash
./scripts/controlplane.sh e2e run buildpack
```

E2E (Kubernetes VM + k3s):
```bash
./gradlew k8sE2e
./scripts/controlplane.sh e2e run k3s-junit-curl
```

VM-based E2E can also target an existing local or remote VM over SSH/SCP:

```bash
E2E_VM_LIFECYCLE=external E2E_VM_HOST=192.168.64.20 E2E_VM_USER=ubuntu ./scripts/controlplane.sh e2e run k3s-junit-curl
E2E_VM_LIFECYCLE=external E2E_VM_HOST=ci-k3s.example.com E2E_VM_USER=dev E2E_VM_HOME=/srv/dev E2E_KUBECONFIG_SERVER=https://ci-k3s.example.com:6443 ./scripts/controlplane.sh cli-test run host-platform
```

Supported external-VM variables:
`E2E_VM_LIFECYCLE=external`, `E2E_VM_HOST`, `E2E_VM_USER`, `E2E_VM_HOME`, `E2E_KUBECONFIG_PATH`, `E2E_REMOTE_PROJECT_DIR`, `E2E_PUBLIC_HOST`, `E2E_KUBECONFIG_SERVER`.
SSH/SCP are always used for remote command execution and file transfer. VM provisioning is driven by Ansible over SSH for both `multipass` and `external` lifecycle modes. If `ansible-playbook` is not already installed on the host, the scripts bootstrap it idempotently in a local virtualenv and fall back to a user-site `pip` install when `python3 -m venv` is unavailable. k3s defaults to the latest official release at run time; set `K3S_VERSION` only when you need to pin a specific version. Multipass is used only for VM lifecycle when `E2E_VM_LIFECYCLE=multipass`.
For wrapper-driven VM scenarios, `--no-cleanup-vm` preserves the installed stack for debugging, but external VMs are never deleted by the tool.
Most top-level `scripts/e2e*.sh` files remain compatibility shims over `scripts/controlplane.sh`; `scripts/e2e-loadtest.sh` is the intentional exception because it preserves the legacy Helm/Grafana/parity workflow via `experiments/e2e-loadtest.sh`, while `scripts/e2e-loadtest-registry.sh` owns registry-summary flows such as `--summary-only`.

E2E/module matrix (control-plane optional modules compile):
```bash
scripts/controlplane.sh matrix --task :control-plane:bootJar --max-combinations 4 --dry-run
./scripts/test-control-plane-module-combinations.sh
```

## Observability

- Prometheus metrics are exposed via Spring Actuator (`/actuator/prometheus` on the management port).

## API

- See `openapi.yaml` for request/response contracts and examples.

## Docs

- `docs/tutorial-java-function.md` — step-by-step guide to writing, building, and invoking a Java function.
- `docs/tutorial-function.md` — step-by-step guide to scaffolding and deploying Java, Python, and JavaScript functions.
- `docs/architecture.md` and `docs/quickstart.md` provide a full overview and operational notes.
- `docs/no-k8s-profile.md` documents the `container-local` managed-deployment profile.
- `docs/loadtest-payload-profile.md` documents payload variability modes, metrics, and validation commands for k6 load tests.
- `function-sdk-go/README.md` documents the planned Go function authoring/runtime SDK.
- `function-sdk-javascript/README.md` documents the JavaScript function authoring/runtime SDK.

## JavaScript Scope

The JavaScript authoring workflow remains first-class under `function-sdk-javascript/`,
`examples/javascript/`, and `tools/fn-init/`.
V2 also wires JavaScript into `tools/controlplane` catalogs, saved profiles, and VM-backed
dry-run/E2E flows such as `k3s-junit-curl` and `cli-stack`.
Build and publish automation remains tracked separately in
`docs/plans/2026-04-21-v2-packaging-and-release.md`.

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
