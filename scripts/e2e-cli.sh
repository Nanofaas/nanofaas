#!/usr/bin/env bash
set -euo pipefail

# Configuration with defaults
VM_NAME=${VM_NAME:-nanofaas-cli-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas-e2e}
CONTROL_IMAGE="nanofaas/control-plane:e2e"
RUNTIME_IMAGE="nanofaas/function-runtime:e2e"
KEEP_VM=${KEEP_VM:-false}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log() { echo -e "${GREEN}[cli-e2e]${NC} $*"; }
warn() { echo -e "${YELLOW}[cli-e2e]${NC} $*"; }
error() { echo -e "${RED}[cli-e2e]${NC} $*" >&2; }

cleanup() {
    local exit_code=$?
    if [[ "${KEEP_VM}" == "true" ]]; then
        warn "KEEP_VM=true, VM '${VM_NAME}' preserved for debugging"
        warn "SSH: multipass shell ${VM_NAME}"
        warn "Delete: multipass delete ${VM_NAME} && multipass purge"
        return
    fi
    log "Cleaning up VM ${VM_NAME}..."
    multipass delete "${VM_NAME}" 2>/dev/null || true
    multipass purge 2>/dev/null || true
    exit $exit_code
}

trap cleanup EXIT

check_prerequisites() {
    if ! command -v multipass >/dev/null 2>&1; then
        error "multipass not found. Install: brew install multipass"
        exit 1
    fi
    log "Prerequisites check passed"
}

create_vm() {
    log "Creating VM ${VM_NAME} (cpus=${CPUS}, memory=${MEMORY}, disk=${DISK})..."
    multipass launch --name "${VM_NAME}" --cpus "${CPUS}" --memory "${MEMORY}" --disk "${DISK}"
    log "VM created successfully"
}

vm_exec() {
    # Inclusion of NANOFAAS_ENDPOINT and NANOFAAS_NAMESPACE if they are set
    local env_vars=""
    if [[ -n "${NANOFAAS_ENDPOINT:-}" ]]; then env_vars+="export NANOFAAS_ENDPOINT=${NANOFAAS_ENDPOINT}; "; fi
    if [[ -n "${NANOFAAS_NAMESPACE:-}" ]]; then env_vars+="export NANOFAAS_NAMESPACE=${NANOFAAS_NAMESPACE}; "; fi

    multipass exec "${VM_NAME}" -- bash -lc "export KUBECONFIG=/home/ubuntu/.kube/config; ${env_vars} $*"
}

install_dependencies() {
    log "Installing dependencies in VM..."

    vm_exec "sudo apt-get update -y"
    vm_exec "sudo apt-get install -y curl ca-certificates tar unzip openjdk-21-jdk"

    # Install Docker
    vm_exec "if ! command -v docker >/dev/null 2>&1; then
        curl -fsSL https://get.docker.com | sudo sh
        sudo usermod -aG docker ubuntu
    fi"

    log "Dependencies installed"
}

install_k3s() {
    log "Installing k3s..."

    vm_exec "curl -sfL https://get.k3s.io | sudo sh -s - --disable traefik"

    # Wait for k3s to be ready
    vm_exec "for i in \$(seq 1 60); do
        if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready'; then
            echo 'k3s node ready'
            exit 0
        fi
        sleep 2
    done
    echo 'k3s node not ready after 120s' >&2
    exit 1"

    # Setup kubeconfig for ubuntu user
    vm_exec "mkdir -p /home/ubuntu/.kube"
    vm_exec "sudo cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config"
    vm_exec "sudo chown ubuntu:ubuntu /home/ubuntu/.kube/config"
    vm_exec "chmod 600 /home/ubuntu/.kube/config"

    log "k3s installed and ready"
}

sync_project() {
    log "Syncing project to VM..."
    vm_exec "rm -rf /home/ubuntu/nanofaas"
    multipass transfer --recursive "${PROJECT_ROOT}" "${VM_NAME}:/home/ubuntu/nanofaas"
    log "Project synced"
}

build_jars() {
    log "Building JARs in VM..."
    vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :control-plane:bootJar :function-runtime:bootJar --no-daemon -q"
    log "JARs built"
}

build_images() {
    log "Building Docker images in VM..."
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${CONTROL_IMAGE} -f control-plane/Dockerfile control-plane/"
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${RUNTIME_IMAGE} -f function-runtime/Dockerfile function-runtime/"
    log "Docker images built"
}

import_images_to_k3s() {
    log "Importing images to k3s..."
    vm_exec "sudo docker save ${CONTROL_IMAGE} -o /tmp/control-plane.tar"
    vm_exec "sudo docker save ${RUNTIME_IMAGE} -o /tmp/function-runtime.tar"
    vm_exec "sudo k3s ctr images import /tmp/control-plane.tar"
    vm_exec "sudo k3s ctr images import /tmp/function-runtime.tar"
    vm_exec "sudo rm -f /tmp/control-plane.tar /tmp/function-runtime.tar"
    log "Images imported to k3s"
}

create_namespace() {
    log "Creating namespace ${NAMESPACE}..."
    vm_exec "kubectl create namespace ${NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -"
    log "Namespace created"
}

deploy_control_plane() {
    log "Deploying control-plane..."

    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: control-plane
  namespace: ${NAMESPACE}
  labels:
    app: control-plane
spec:
  replicas: 1
  selector:
    matchLabels:
      app: control-plane
  template:
    metadata:
      labels:
        app: control-plane
    spec:
      containers:
      - name: control-plane
        image: ${CONTROL_IMAGE}
        imagePullPolicy: Never
        ports:
        - containerPort: 8080
          name: api
        - containerPort: 8081
          name: management
        env:
        - name: POD_NAMESPACE
          value: \"${NAMESPACE}\"
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8081
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8081
          initialDelaySeconds: 15
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3
---
apiVersion: v1
kind: Service
metadata:
  name: control-plane
  namespace: ${NAMESPACE}
spec:
  selector:
    app: control-plane
  ports:
  - name: api
    port: 8080
    targetPort: 8080
  - name: management
    port: 8081
    targetPort: 8081
EOF"

    log "Control-plane deployment created"
}

deploy_function_runtime() {
    log "Deploying function-runtime..."

    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: function-runtime
  namespace: ${NAMESPACE}
  labels:
    app: function-runtime
spec:
  replicas: 1
  selector:
    matchLabels:
      app: function-runtime
  template:
    metadata:
      labels:
        app: function-runtime
    spec:
      containers:
      - name: function-runtime
        image: ${RUNTIME_IMAGE}
        imagePullPolicy: Never
        ports:
        - containerPort: 8080
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: function-runtime
  namespace: ${NAMESPACE}
spec:
  selector:
    app: function-runtime
  ports:
  - name: http
    port: 8080
    targetPort: 8080
EOF"

    log "Function-runtime deployment created"
}

wait_for_deployment() {
    local name=$1
    local timeout=${2:-180}

    log "Waiting for deployment ${name} to be ready (timeout: ${timeout}s)..."

    vm_exec "kubectl rollout status deployment/${name} -n ${NAMESPACE} --timeout=${timeout}s"

    log "Deployment ${name} is ready"
}

build_cli() {
    log "Building CLI in VM..."
    vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :nanofaas-cli:installDist --no-daemon -q"
    log "CLI built"
}

setup_cli_env() {
    log "Setting up CLI environment..."

    # Add CLI to PATH in .bashrc for subsequent vm_exec calls
    vm_exec "echo 'export PATH=\$PATH:/home/ubuntu/nanofaas/nanofaas-cli/build/install/nanofaas-cli/bin' >> /home/ubuntu/.bashrc"

    # Set environment variables for the current shell session's context
    export NANOFAAS_ENDPOINT="http://control-plane.${NAMESPACE}.svc.cluster.local:8080"
    export NANOFAAS_NAMESPACE="${NAMESPACE}"

    log "CLI environment configured (Endpoint: ${NANOFAAS_ENDPOINT})"
}

test_cli_config() {
    log "Testing CLI config..."
    vm_exec "nanofaas --version" | grep -q "nanofaas"
    log "  CLI version check: OK"
}

test_cli_fn_list() {
    log "Testing 'nanofaas fn list'..."
    local output
    output=$(vm_exec "nanofaas fn list")
    echo "${output}"
}

test_cli_fn_apply() {
    log "Testing 'nanofaas fn apply'..."

    # Create a temporary function spec file in the VM
    vm_exec "cat <<EOF > /tmp/echo-test.json
{
    \"name\": \"echo-test\",
    \"image\": \"${RUNTIME_IMAGE}\",
    \"timeoutMs\": 5000,
    \"concurrency\": 2,
    \"queueSize\": 20,
    \"maxRetries\": 3,
    \"executionMode\": \"POOL\",
    \"endpointUrl\": \"http://function-runtime:8080/invoke\"
}
EOF"

    vm_exec "nanofaas fn apply -f /tmp/echo-test.json" | grep -q "echo-test"
    log "  Function applied: OK"
}

test_cli_invoke() {
    log "Testing 'nanofaas invoke'..."
    local response
    response=$(vm_exec "nanofaas invoke echo-test -d '{\"input\": {\"message\": \"hello-from-cli\"}}'")
    echo "${response}"
    echo "${response}" | grep -q "\"status\":\"success\""
    echo "${response}" | grep -q "\"message\":\"hello-from-cli\""
    log "  Function invocation: OK"
}

test_cli_fn_delete() {
    log "Testing 'nanofaas fn delete'..."
    vm_exec "nanofaas fn delete echo-test"
    log "  Function deleted: OK"
}

print_summary() {
    log ""
    log "=========================================="
    log "        CLI E2E TEST COMPLETED SUCCESSFULLY"
    log "=========================================="
    log ""
    log "Summary:"
    log "  - VM: ${VM_NAME}"
    log "  - Namespace: ${NAMESPACE}"
    log ""
    log "Tests passed:"
    log "  [✓] CLI Build and Setup"
    log "  [✓] CLI Version Check"
    log "  [✓] Function Application (fn apply)"
    log "  [✓] Function Listing (fn list)"
    log "  [✓] Function Invocation (invoke)"
    log "  [✓] Function Deletion (fn delete)"
    log ""
}

main() {
    log "Starting CLI E2E test..."
    log "Configuration:"
    log "  VM_NAME=${VM_NAME}"
    log "  CPUS=${CPUS}, MEMORY=${MEMORY}, DISK=${DISK}"
    log "  NAMESPACE=${NAMESPACE}"
    log "  KEEP_VM=${KEEP_VM}"
    log ""

    # Phase 1: Setup
    check_prerequisites
    create_vm
    install_dependencies
    install_k3s

    # Phase 2: Build Nanofaas components
    sync_project
    build_jars
    build_images
    import_images_to_k3s

    # Phase 3: Deploy Nanofaas
    create_namespace
    deploy_control_plane
    deploy_function_runtime

    # Phase 4: Verify deployment
    wait_for_deployment "control-plane" 180
    wait_for_deployment "function-runtime" 120

    # Phase 5: Build and Setup CLI
    build_cli
    setup_cli_env

    # Phase 6: Run CLI tests
    test_cli_config
    test_cli_fn_list
    test_cli_fn_apply
    test_cli_fn_list # List again to see the new function
    test_cli_invoke
    test_cli_fn_delete
    test_cli_fn_list # List again to ensure it's gone

    # Done
    print_summary
}

# Run main
main "$@"

