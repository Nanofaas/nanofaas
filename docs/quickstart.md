# Quickstart

## Build

- Build all modules:
  - `./gradlew build`
- Native build (GraalVM via SDKMAN):
  - `./scripts/native-build.sh`
- E2E test (requires Docker + SDKMAN JDK):
  - `./scripts/e2e.sh`
- If you use a Docker-compatible runtime (e.g., podman/colima), export:
  - `CONTAINER_HOST=unix:///path/to/socket`
  - `CONTAINER_TLS_VERIFY=0` (optional)
  - `CONTAINER_CERT_PATH=/path/to/certs` (optional)
- E2E buildpack test (builds minimal images with Spring Boot buildpacks):
  - `./scripts/e2e-buildpack.sh`
  - Requires Docker (buildpacks run with `bootBuildImage`).
- E2E test on Kubernetes (k3s in Multipass):
  - Fully automated:
    - `./gradlew k8sE2e`
  - Direct script entrypoint:
    - `./scripts/e2e-k8s-vm.sh`
  - Optional env for sizing/debug:
    - `VM_NAME`, `CPUS`, `MEMORY`, `DISK`, `REMOTE_DIR`, `NANOFAAS_E2E_NAMESPACE`, `KEEP_VM=true`
  - K8sE2eTest also verifies sync queue backpressure (429 + headers + sync_queue_* metrics).
  - The k8s E2E test will fail if `KUBECONFIG` is missing or invalid.
  - Alias task:
    - `./gradlew k8sE2eVm`

## Run control plane locally

- `scripts/control-plane-build.sh run --profile core`
- Control plane API on `http://localhost:8080`
- Metrics on `http://localhost:8081/actuator/prometheus`

## Run control-plane tooling wizard locally

- Canonical tool root:
  - `tools/controlplane/`
- Use the unified non-interactive wrapper for control-plane Gradle actions:
  - `scripts/control-plane-build.sh jar --profile core --dry-run`
  - `scripts/control-plane-build.sh image --profile all --dry-run`
  - `scripts/control-plane-build.sh native --profile all --dry-run`
  - `scripts/control-plane-build.sh test --profile k8s --dry-run`
  - `scripts/control-plane-build.sh matrix --task :control-plane:bootJar --max-combinations 4 --dry-run`
  - `scripts/control-plane-build.sh inspect --profile container-local --dry-run`

- Open the interactive wizard and save a reusable profile:
  - `scripts/controlplane-tool.sh --profile-name dev`
- Re-run with an existing profile:
  - `scripts/controlplane-tool.sh --profile-name dev --use-saved-profile`
- Exit codes:
  - `0` when run final status is `passed`
  - `1` when run final status is `failed`
  - `2` when profile loading/validation fails
- Prometheus metrics source is now zero-config:
  - The wizard does not ask for a Prometheus URL.
  - The metrics flow auto-registers a deterministic local fixture function (`tool-metrics-echo`) before k6.
  - The metrics flow also registers/verifies demo deployment function (`demo-word-stats-deployment`, `executionMode=DEPLOYMENT`).
  - The metrics flow auto-starts a mock Kubernetes API backend.
  - The metrics flow auto-starts a tool-managed control-plane runtime wired to the mock backend.
  - The metrics step reuses a reachable Prometheus endpoint when available.
  - If no endpoint is reachable, the tool auto-pulls `prom/prometheus` (if needed) and starts a local Docker container.
  - Default metric gating is scenario-compatible; strict full-gate can be enabled in profile with `metrics.strict_required = true`.
- Artifacts:
  - `tools/controlplane/profiles/<profile>.toml`
  - `tools/controlplane/runs/<timestamp>-<profile>/summary.json`
  - `tools/controlplane/runs/<timestamp>-<profile>/report.html`

## Run function runtime locally

- `./gradlew :function-runtime:bootRun`
- Invoke with: `POST http://localhost:8080/invoke`

## Deploy to Kubernetes

- Apply base manifests:
  - `kubectl apply -f k8s/namespace.yaml`
  - `kubectl apply -f k8s/serviceaccount.yaml`
  - `kubectl apply -f k8s/rbac.yaml`
  - `kubectl apply -f k8s/control-plane-deployment.yaml`
  - `kubectl apply -f k8s/control-plane-service.yaml`

- Build and push images:
  - `docker build -t nanofaas/control-plane:0.9.2 control-plane/`
  - `docker build -t nanofaas/function-runtime:0.9.2 function-runtime/`

## Register and invoke

- Register:
  - `POST /v1/functions` with a FunctionSpec
- Invoke sync:
  - `POST /v1/functions/{name}:invoke`
- Invoke async:
  - `POST /v1/functions/{name}:enqueue`
