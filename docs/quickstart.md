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

- `./gradlew :control-plane:bootRun`
- Control plane API on `http://localhost:8080`
- Metrics on `http://localhost:8081/actuator/prometheus`

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
