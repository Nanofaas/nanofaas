# Testing

This document describes the test suites available for nanofaas: unit tests,
integration tests, and end-to-end (E2E) tests.

---

## Unit Tests

All modules use JUnit 5. Test classes follow the `*Test.java` naming convention.

```bash
# Run all unit tests
./gradlew test

# Run the control-plane through the canonical wrapper
scripts/controlplane.sh test --profile core
scripts/controlplane.sh test --profile k8s -- --tests it.unimib.datai.nanofaas.controlplane.core.QueueManagerTest

# Run other modules directly
./gradlew :function-runtime:test
./gradlew :nanofaas-cli:test

# Run with coverage report (JaCoCo)
./gradlew :nanofaas-cli:test :nanofaas-cli:jacocoTestReport
# Report: nanofaas-cli/build/reports/jacoco/test/html/index.html
```

### Control-plane module matrix

Use the wrapper when you want to compile or inspect multiple optional-module combinations:

```bash
scripts/controlplane.sh matrix --task :control-plane:bootJar --max-combinations 8
scripts/controlplane.sh matrix --task :control-plane:test --modules async-queue,sync-queue --dry-run
./scripts/test-control-plane-module-combinations.sh --task :control-plane:bootJar
```

### Key testing libraries

| Module | Libraries |
|--------|-----------|
| control-plane | JUnit 5, Reactor Test, Fabric8 Mock Server, RestAssured (E2E) |
| function-runtime | JUnit 5, Spring Boot Test |
| nanofaas-cli | JUnit 5, OkHttp MockWebServer, Fabric8 Mock Client, AssertJ |
| function-sdk-go | Go `testing`, `httptest` (planned) |

### Go SDK tests

The Go function SDK lives under `function-sdk-go/` and is tested with the Go toolchain.

```bash
cd function-sdk-go
go mod tidy
go test ./...
```

### CLI unit tests

The `nanofaas-cli` module has comprehensive unit tests covering all commands:

- **Function CRUD**: `FnApplyCommandTest`, `FnGetCommandTest`, `FnListCommandTest`, `FnDeleteCommandTest`
- **Invocation**: `InvokeCommandTest`, `EnqueueCommandTest`
- **Execution status**: `ExecGetCommandTest`
- **Deploy**: `DeployCommandTest` (requires Docker for build tests)
- **Kubernetes helpers**: `K8sPodsCommandTest`, `K8sDescribeCommandTest`, `K8sLogsCommandTest`
- **Infrastructure**: `RootCommandTest`, `ConfigStoreTest`, `ControlPlaneClientTest`, `HttpJsonTest`, `BuildSpecLoaderTest`, `DockerBuildxTest`, `YamlIOTest`

HTTP interactions are tested with OkHttp `MockWebServer`. Kubernetes interactions
use `@EnableKubernetesMockClient(crud = true)` from Fabric8.

---

## E2E Tests

nanofaas provides several E2E test suites. VM-based suites can either create a
Multipass VM or target an existing local/remote VM over SSH, then deploy the
platform and run tests against the live cluster.

Canonical entrypoint for orchestration is `scripts/controlplane.sh`. Most legacy
`scripts/e2e*.sh` files are compatibility wrappers over `scripts/controlplane.sh e2e ...`; the `scripts/e2e-cli*.sh` family is now a compatibility wrapper layer over `scripts/controlplane.sh cli-test ...`; and `scripts/e2e-loadtest.sh` intentionally preserves the older Helm/Grafana/parity backend via `experiments/e2e-loadtest.sh`.

```bash
scripts/controlplane.sh e2e list
scripts/controlplane.sh vm up --lifecycle multipass --name nanofaas-e2e --dry-run
scripts/controlplane.sh cli-test list
scripts/controlplane.sh cli-test run vm --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run host-platform --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run deploy-host --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --lifecycle multipass --dry-run
scripts/controlplane.sh e2e all --only k3s-junit-curl --dry-run
scripts/controlplane.sh loadtest list-profiles
scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run
scripts/e2e-loadtest.sh --profile demo-java --dry-run
```

Behavioral notes for the repaired milestone 3 contract:

- VM provisioning targets the resolved VM host over SSH/Ansible; dry-run plans no longer fall back to `localhost` for VM-backed playbooks.
- `scripts/controlplane.sh e2e all ...` reuses one shared VM session across VM-backed scenarios instead of looping over isolated per-scenario VM startups.
- `--no-cleanup-vm` keeps Multipass VMs available for debugging after `run` or `all`; with `E2E_VM_LIFECYCLE=external`, teardown is always skipped.
- `container-local`, `deploy-host`, `k3s-junit-curl`, `cli`, `cli-host`, and `helm-stack` route through concrete workflows instead of placeholder `echo` steps.
- `container-local` is intentionally a single-function managed-deployment verification path; multi-function presets are rejected in CLI validation before the backend runs.
- `k3s-junit-curl` consumes the full selected function set in manifest mode, so presets such as `demo-java` are exercised end-to-end instead of being reduced to the first function.

Loadtest is now a first-class workflow:

- `scripts/controlplane.sh loadtest run ...` is the canonical interface for k6 + Prometheus validation.
- saved profiles can persist `loadtest.default_load_profile`, `loadtest.metrics_gate_mode`, and `loadtest.scenario_file` or `loadtest.function_preset`.
- the dry-run output still renders one scenario manifest and one k6 plan, and the runtime path still bootstraps a tool-managed control-plane runtime when needed.
- `scripts/e2e-loadtest.sh` remains a compatibility wrapper for the legacy Helm/Grafana/parity workflow; `--profile demo-java` maps that path to the Java demo benchmark matrix, while `--summary-only` belongs to `scripts/e2e-loadtest-registry.sh`.

CLI validation is also first-class:

- `scripts/controlplane.sh cli-test list|inspect|run` is the canonical interface for `nanofaas-cli` validation.
- saved profiles can persist `cli_test.default_scenario`, so `scripts/controlplane.sh cli-test run --saved-profile demo-java --dry-run` can resolve the scenario directly from the profile.
- `k3s-junit-curl`, `helm-stack`, and `cli-stack` are self-bootstrapping VM-backed scenarios: when no VM request is provided, the tool creates and configures a managed VM and installs scenario-specific software there instead of assuming host-installed Helm, kubectl, k3s, registry tooling, or `nanofaas-cli`.
- `cli-stack` is the canonical VM-backed CLI stack scenario: it compiles the CLI in the VM, installs Helm, k3s, and the registry there, then exercises function build/push/apply/invoke/enqueue/delete plus `platform install/status/uninstall`.
- `vm` validates and executes every function in the resolved selection, and `deploy-host` iterates the same full function set when it builds, pushes, and registers host-side deploy fixtures.
- `host-platform` is a compatibility path and intentionally platform-only: saved-profile runtime and namespace defaults still apply, but function selections are ignored for that scenario.
- missing saved profiles or scenario files are reported as CLI validation failures with exit code 2.
- `scripts/e2e-cli.sh` is a compatibility wrapper over `scripts/controlplane.sh cli-test run vm`.
- `scripts/e2e-cli-host-platform.sh` is a compatibility wrapper over `scripts/controlplane.sh cli-test run host-platform`.
- `scripts/e2e-cli-deploy-host.sh` is a compatibility wrapper over `scripts/controlplane.sh cli-test run deploy-host`.

### VM lifecycle modes

- `E2E_VM_LIFECYCLE=multipass` keeps the existing default: scripts create/start/delete the VM with Multipass.
- `E2E_VM_LIFECYCLE=external` targets an existing VM; command execution and file copy still go through SSH/SCP, but the scripts do not create or delete the machine.
- VM provisioning is executed through Ansible over SSH for both lifecycle modes.
- Host-side `ansible-playbook` is used when already installed; otherwise the scripts bootstrap it idempotently under a local virtualenv and fall back to a user-site `pip` install when `venv` is unavailable.
- SSH/SCP are always used for remote command execution and file transfer.
- Multipass is used only for VM lifecycle operations when `E2E_VM_LIFECYCLE=multipass`.
- k3s defaults to the latest official release at run time unless `K3S_VERSION` is explicitly set.
- Current provisioning assumes a Debian/Ubuntu-style VM because bootstrap and package installation use `apt`.

Supported external-VM environment variables:

```bash
E2E_VM_LIFECYCLE=external
E2E_VM_HOST=<ip-or-dns>
E2E_VM_USER=<ssh-user>
E2E_VM_HOME=<optional-home>
E2E_KUBECONFIG_PATH=<optional-remote-kubeconfig>
E2E_REMOTE_PROJECT_DIR=<optional-remote-repo-path>
E2E_PUBLIC_HOST=<optional-nodeport-host>
E2E_KUBECONFIG_SERVER=<optional-https-server-url>
```

Examples:

```bash
E2E_VM_LIFECYCLE=external E2E_VM_HOST=192.168.64.20 E2E_VM_USER=ubuntu ./scripts/e2e-k3s-junit-curl.sh
E2E_VM_LIFECYCLE=external E2E_VM_HOST=ci-k3s.example.com E2E_VM_USER=dev E2E_VM_HOME=/srv/dev E2E_KUBECONFIG_SERVER=https://ci-k3s.example.com:6443 ./scripts/controlplane.sh cli-test run host-platform
```

Canonical Ansible asset root: `ops/ansible/`.

### CLI E2E (`scripts/e2e-cli.sh`)

Tests the full `nanofaas` CLI against a real k3s cluster. This is the most
comprehensive CLI validation — it exercises every command end-to-end.
The canonical entrypoint is `scripts/controlplane.sh cli-test run vm`.
`./scripts/e2e-cli.sh` is a compatibility wrapper over `scripts/controlplane.sh cli-test run vm`.
When the resolved manifest selects multiple functions, the VM scenario validates the entire set instead of silently narrowing to the first entry.

**Prerequisites:**
- `python3` on the host
- OpenSSH client and an SSH public key at `~/.ssh/id_rsa.pub` or `~/.ssh/id_ed25519.pub`
- [Multipass](https://multipass.run) (`brew install multipass`) only if `E2E_VM_LIFECYCLE=multipass`
- Internet connection (downloads k3s, Docker, JDK 21, Helm and/or Ansible dependencies)

**Run:**

```bash
# Canonical path
scripts/controlplane.sh cli-test run vm --saved-profile demo-java --dry-run

# Full run (creates VM, tests, cleans up)
./scripts/e2e-cli.sh

# Keep VM for debugging
./scripts/e2e-cli.sh --no-cleanup-vm
```

**What it does:**

1. Creates a Multipass VM (4 CPU, 8 GB RAM, 30 GB disk) when `E2E_VM_LIFECYCLE=multipass`, or reuses the external VM over SSH
2. Runs Ansible provisioning for Docker, JDK 21, optional Helm, and k3s
3. Syncs the project and builds JARs + Docker images
4. Deploys control-plane and function-runtime on k3s
5. Builds the CLI distribution (`installDist`)
6. Runs **47 tests** across all CLI commands:

| Phase | Tests | What is verified |
|-------|-------|-----------------|
| Health | 5 | Pod status, `/actuator/health`, liveness, readiness |
| Config | 2 | CLI binary exists, `--help` output, `--endpoint` flag |
| Function CRUD | 8 | `fn apply`, `fn list`, `fn get`, `fn delete`, idempotency, negative cases |
| Sync invoke | 5 | Inline JSON, `@file` data, custom headers, invalid JSON, nonexistent function |
| Async enqueue | 2 | Returns `executionId`, custom headers |
| Execution status | 3 | `exec get`, ID matching, `--watch` polling |
| Kubernetes | 8 | `k8s pods`, `k8s describe`, `k8s logs`, `--container` flag, negative cases |
| Platform | 7 | `platform install/status/uninstall`, NodePort endpoint, post-uninstall failure |
| Cleanup | 3 | Delete function, idempotent delete, verify empty list |

**Configuration:**

| Variable | Default | Description |
|----------|---------|-------------|
| `VM_NAME` | `nanofaas-cli-e2e-<timestamp>` | VM name when `E2E_VM_LIFECYCLE=multipass` |
| `CPUS` | `4` | VM CPU count |
| `MEMORY` | `8G` | VM memory |
| `DISK` | `30G` | VM disk size |
| `NAMESPACE` | `nanofaas-e2e` | Kubernetes namespace |
| `--no-cleanup-vm` | disabled | Keep VM after script exits |
| `K3S_VERSION` | latest official release | Optional k3s pin; otherwise resolved dynamically at run time |

**Typical duration:** ~10 minutes (mostly VM setup and Gradle build).

### CLI Stack E2E

Dedicated VM-backed CLI evaluation where the VM itself becomes the full test surface:
cli-stack is the canonical VM-backed CLI stack scenario.
- compiles `nanofaas-cli` inside the VM
- installs Helm inside the VM
- installs k3s inside the VM
- installs and wires a local registry inside the VM for k3s
- validates CLI-driven `platform install/status/uninstall`
- validates selected function image build/push/apply/list/invoke/enqueue/delete through the CLI

The canonical entrypoint is `scripts/controlplane.sh cli-test run cli-stack`.

```bash
scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-java --dry-run
```

**Debugging a failure:**

```bash
# Run with preserved VM
./scripts/e2e-cli.sh --no-cleanup-vm

# SSH into the VM
ssh <user>@<vm-host>

# Check pods
kubectl get pods -n nanofaas-e2e

# Check control-plane logs
kubectl logs -l app=control-plane -n nanofaas-e2e --tail=50

# Run a CLI command manually
export KUBECONFIG=<remote-kubeconfig-path>
export PATH=$PATH:<remote-project-dir>/nanofaas-cli/build/install/nanofaas-cli/bin
export NANOFAAS_ENDPOINT=http://$(kubectl get svc control-plane -n nanofaas-e2e -o jsonpath='{.spec.clusterIP}'):8080
export NANOFAAS_NAMESPACE=nanofaas-e2e
nanofaas fn list

# Clean up when done
exit
if [[ "${E2E_VM_LIFECYCLE:-multipass}" == "multipass" ]]; then
  multipass delete <vm-name> && multipass purge
fi
```

### K3s JUnit Curl E2E (`scripts/e2e-k3s-junit-curl.sh`)

Deploys the shared Helm stack on k3s, runs the curl-based API checks, then runs `K8sE2eTest` against the same installation.
The script is a wrapper over `scripts/controlplane.sh e2e run k3s-junit-curl`.

```bash
./scripts/e2e-k3s-junit-curl.sh
./scripts/e2e-k3s-junit-curl.sh --no-cleanup-vm
```

### Docker E2E (`scripts/e2e.sh`)

Runs control-plane + function-runtime in local Docker containers using
Testcontainers and RestAssured. No Kubernetes needed.
The script is a wrapper over `scripts/controlplane.sh e2e run docker`.

```bash
./scripts/e2e.sh
```

The SDK parity suite `SdkExamplesE2eTest` now covers Java Spring SDK, Java lite SDK,
and Go SDK example containers against the same control-plane flows.

### Buildpack E2E (`scripts/e2e-buildpack.sh`)

Same as Docker E2E but builds images using Cloud Native Buildpacks instead
of Dockerfiles.
The script is a wrapper over `scripts/controlplane.sh e2e run buildpack`.

```bash
./scripts/e2e-buildpack.sh
```

### Kubernetes E2E (JUnit on k3s)

JUnit-based E2E now runs as the second verifier inside `k3s-junit-curl`, against a stack that was already installed by the Python runner.

```bash
scripts/controlplane.sh e2e run k3s-junit-curl
./scripts/e2e-k3s-junit-curl.sh
./gradlew k8sE2e
```

### Host CLI Platform E2E (`scripts/e2e-cli-host-platform.sh`)

End-to-end scenario where `nanofaas-cli` runs on the host machine and executes
`platform install/status/uninstall` (Helm local to host) against a k3s cluster
running in a VM.
The canonical entrypoint is `scripts/controlplane.sh cli-test run host-platform`.
`./scripts/e2e-cli-host-platform.sh` is a compatibility wrapper over `scripts/controlplane.sh cli-test run host-platform`.
This scenario is intentionally platform-only, so saved profiles can choose `host-platform` as the default scenario while function selections remain ignored.

```bash
scripts/controlplane.sh cli-test run host-platform --saved-profile demo-java --dry-run
./scripts/e2e-cli-host-platform.sh
```

### Host CLI Deploy E2E (`scripts/e2e-cli-deploy-host.sh`)

Host-only validation of `nanofaas deploy` without Multipass/VM:
- starts a local Docker registry container
- runs a fake control-plane HTTP endpoint on host
- executes CLI `deploy` (docker buildx + push + register)
- verifies pushed tag exists in registry and `POST /v1/functions` payload
The canonical entrypoint is `scripts/controlplane.sh cli-test run deploy-host`.
`./scripts/e2e-cli-deploy-host.sh` is a compatibility wrapper over `scripts/controlplane.sh cli-test run deploy-host`.
Preset-backed and scenario-backed deploy runs iterate the full resolved function set instead of requiring a single function.

```bash
scripts/controlplane.sh cli-test run deploy-host --function-preset demo-java --dry-run
./scripts/e2e-cli-deploy-host.sh
```

### Load Testing (`scripts/e2e-k3s-helm.sh` + `scripts/e2e-loadtest.sh`)

Full Helm-based deployment with k6 load testing and Grafana dashboard.
See [docs/e2e-tutorial.md](e2e-tutorial.md) for a detailed walkthrough.
`scripts/e2e-k3s-helm.sh` is a wrapper over `scripts/controlplane.sh e2e run helm-stack`.
`scripts/e2e-loadtest.sh` is a compatibility wrapper over `experiments/e2e-loadtest.sh`, not over `scripts/controlplane.sh loadtest run`.

```bash
./scripts/e2e-k3s-helm.sh     # deploy nanofaas with Helm
./scripts/e2e-loadtest.sh     # run k6 load tests + Grafana
./scripts/e2e-loadtest.sh --profile demo-java --dry-run
./scripts/e2e-loadtest.sh --help
./scripts/e2e-loadtest-registry.sh --summary-only --no-refresh-summary-metrics
```

Key load-test parameters:
- `SKIP_GRAFANA=true` to skip local Grafana startup
- `VERIFY_OUTPUT_PARITY=false` to skip pre-load runtime output parity checks
- `PARITY_TIMEOUT_SECONDS=<n>` to tune parity request timeout
- `NANOFAAS_URL` and `PROM_URL` to override auto-discovered endpoints
- `K6_PAYLOAD_MODE=pool-sequential|pool-random|legacy-random` for payload model
- `K6_PAYLOAD_POOL_SIZE=<n>` for pool size in pool modes
- `./scripts/e2e-loadtest-registry.sh --summary-only` to regenerate Section 1..9 from existing `k6/results`
- `./scripts/e2e-loadtest.sh --profile demo-java --dry-run` to inspect the legacy backend mapping for the Java-only benchmark slice
- `PROM_CONTAINER_METRICS_ENABLED=true` to enable institutional container CPU/RAM metrics in bundled Prometheus
- `PROM_CONTAINER_METRICS_MODE=kubelet|daemonset` (`kubelet` recommended on k3s)
- `PROM_CONTAINER_METRICS_KUBELET_INSECURE_SKIP_VERIFY=true|false` for kubelet TLS policy in dev/staging

Payload-specific tests and metrics:

```bash
# Pure JS unit tests for payload model logic
node --test k6/tests/payload-model.test.mjs

# Pytest bridge that executes the Node test
uv run pytest scripts/tests/test_k6_payload_model_js.py -q

# Full scripts suite (includes registry summary payload section assertions)
uv run pytest scripts/tests -q
```

### Control-plane local tooling tests (Python)

The local TUI/build orchestration tool (`controlplane-tool`, project path `tools/controlplane`) is validated with `pytest`.

```bash
# Run all tooling tests
uv run --project tools/controlplane pytest tools/controlplane/tests -v

# Focused tests
uv run --project tools/controlplane pytest tools/controlplane/tests/test_pipeline.py -v
uv run --project tools/controlplane pytest tools/controlplane/tests/test_report.py -v
uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_commands.py -v
```

The E2E orchestration surface now exposes a typed function catalog plus reusable scenario specs:

```bash
scripts/controlplane.sh functions list
scripts/controlplane.sh functions show-preset demo-java
scripts/controlplane.sh functions show-preset demo-loadtest
scripts/controlplane.sh cli-test list
scripts/controlplane.sh cli-test run vm --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run host-platform --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run deploy-host --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run helm-stack --dry-run
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --functions word-stats-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --saved-profile demo-java --dry-run
```

Scenario specs live under `tools/controlplane/scenarios/`.
Saved profile defaults live under `tools/controlplane/profiles/`.
Selection precedence is explicit CLI override first, then scenario file, then saved profile defaults.

Additional selection semantics:

- `helm-stack` built-in defaults resolve the `demo-loadtest` preset, so dry-run and live plans exclude unsupported Go functions.
- explicit CLI selection on top of a scenario file or saved profile preserves inherited payloads, namespace, and `load.profile`, then narrows `load.targets` to the selected subset.
- `k3s-junit-curl` now passes `-Dnanofaas.e2e.scenarioManifest=...` to the remote `K8sE2eTest`, so the executed VM workflow consumes the same manifest rendered by the dry-run plan.

For metric time-series collection during a tooling run, the tool now auto-manages Prometheus:
- no interactive URL prompt is shown in the wizard,
- if an endpoint is already reachable it is reused,
- otherwise `prom/prometheus` is pulled (if missing) and started as a local Docker container for the run.
- for metrics/k6 runs, a local mock Kubernetes API backend is auto-started,
- for metrics/k6 runs, a tool-managed control-plane runtime is auto-started and wired to that mock backend,
- before k6, a deterministic fixture function (`tool-metrics-echo`, `executionMode=LOCAL`) is ensured and warm-up invoked.
- before k6, demo deployment function (`demo-word-stats-deployment`, `executionMode=DEPLOYMENT`) is ensured and verified via API lookup.
- default gate checks scenario-compatible core metrics; enable strict full gate with `metrics.strict_required = true`.

Optional override for an existing endpoint:

```bash
export NANOFAAS_TOOL_PROMETHEUS_URL=http://127.0.0.1:9090
scripts/controlplane.sh tui --profile-name dev --use-saved-profile
```

The metrics step invokes k6 with control-plane base URL semantics:

```bash
NANOFAAS_URL=http://localhost:8080
```

Additional reference:
- [docs/loadtest-payload-profile.md](loadtest-payload-profile.md)

### Control-plane module matrix (`scripts/test-control-plane-module-combinations.sh`)

Compiles the control-plane for every combination of optional modules
(`-PcontrolPlaneModules=<csv|none>`), with per-combination logs.

```bash
# Run full matrix (all combinations)
./scripts/test-control-plane-module-combinations.sh

# Quick check
./scripts/test-control-plane-module-combinations.sh --max-combinations 10
```

---

## Test Coverage

Coverage is tracked with JaCoCo. Current targets for `nanofaas-cli`:

| Metric | Target | Current |
|--------|--------|---------|
| Instruction | > 90% | 92% |
| Branch | > 75% | 80% |

Generate the report:

```bash
./gradlew :nanofaas-cli:test :nanofaas-cli:jacocoTestReport
open nanofaas-cli/build/reports/jacoco/test/html/index.html
```

---

## Summary

| Suite | Scope | Requires | Command |
|-------|-------|----------|---------|
| Unit tests | All modules | JDK 21 | `./gradlew test` |
| CLI E2E | Legacy in-VM CLI validation path | SSH key; Multipass optional for managed VM lifecycle | `./scripts/e2e-cli.sh` |
| CLI Stack E2E | Canonical VM-backed CLI stack validation | SSH key; Multipass optional for managed VM lifecycle | `scripts/controlplane.sh cli-test run cli-stack` |
| Host CLI Platform E2E | Host CLI + Helm lifecycle on k3s VM | SSH key + Helm on host; Multipass optional | `./scripts/e2e-cli-host-platform.sh` |
| Host CLI Deploy E2E | Host-only deploy build+push+register | Docker + Python 3 | `./scripts/e2e-cli-deploy-host.sh` |
| K3s JUnit Curl E2E | Shared Helm deploy + curl API checks + `K8sE2eTest` | SSH; Multipass optional for managed VM lifecycle | `./scripts/e2e-k3s-junit-curl.sh` |
| Docker E2E | Core flow | Docker | `./scripts/e2e.sh` |
| Buildpack E2E | Core flow (buildpack) | Docker | `./scripts/e2e-buildpack.sh` |
| K8s E2E (JUnit) | Control-plane on k3s | SSH; Multipass optional for managed VM lifecycle | `./scripts/e2e-k3s-junit-curl.sh` or `./gradlew k8sE2e` |
| Load test | Performance | SSH + k6; Multipass optional for managed VM lifecycle | `./scripts/e2e-k3s-helm.sh && ./scripts/e2e-loadtest.sh` |
| Control-plane module matrix | Compile-time module compatibility | JDK 21 | `./scripts/test-control-plane-module-combinations.sh` |
