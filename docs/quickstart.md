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
- E2E test on Kubernetes (requires kind + kubeconfig on host):
  - Create VM + kind and generate kubeconfig:
    - `./scripts/setup-multipass-kind.sh`
  - Build images in VM and load into kind:
    - `./scripts/kind-build-load.sh`
  - Export kubeconfig and run:
    - `export KUBECONFIG=$HOME/.kube/mcfaas-kind.yaml`
    - `./gradlew :control-plane:test --tests com.mcfaas.controlplane.e2e.K8sE2eTest`
  - The k8s E2E test will fail if `KUBECONFIG` is missing or invalid.
  - Automated (provisions kind in Multipass, loads images, runs K8sE2eTest):
    - `./gradlew k8sE2e`
    - VM and kubeconfig are deleted after the test by default (set `-Pk8sDeleteVm=false` or `-Pk8sDeleteKubeconfig=false` to keep them).
  - Fully isolated VM (k3s in Multipass, builds images in-VM, runs K8sE2eTest, cleans up):
    - `./gradlew k8sE2eVm`
    - Optional env: `VM_NAME`, `CPUS`, `MEMORY`, `DISK`, `REMOTE_DIR`, `MCFAAS_E2E_NAMESPACE`, `KEEP_VM=true`

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
  - `docker build -t mcfaas/control-plane:0.5.0 control-plane/`
  - `docker build -t mcfaas/function-runtime:0.5.0 function-runtime/`

## Register and invoke

- Register:
  - `POST /v1/functions` with a FunctionSpec
- Invoke sync:
  - `POST /v1/functions/{name}:invoke`
- Invoke async:
  - `POST /v1/functions/{name}:enqueue`
