#!/usr/bin/env bash
set -euo pipefail

#
# E2E Setup: Multipass VM → k3s → Helm install nanofaas → register functions → verify
#
# Usage:
#   ./scripts/e2e-k3s-helm.sh                  # Full setup (build + deploy)
#   SKIP_BUILD=true ./scripts/e2e-k3s-helm.sh  # Skip build if images exist
#   KEEP_VM=false ./scripts/e2e-k3s-helm.sh    # Delete VM on exit
#
# Prerequisites:
#   - multipass (https://multipass.run)
#   - Docker (for building images)
#
# After completion, run the load test:
#   ./scripts/e2e-loadtest.sh
#

VM_NAME=${VM_NAME:-nanofaas-e2e}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas}
KEEP_VM=${KEEP_VM:-true}
SKIP_BUILD=${SKIP_BUILD:-false}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
VM_EXEC_TIMEOUT_SECONDS=${VM_EXEC_TIMEOUT_SECONDS:-900}
VM_EXEC_HEARTBEAT_SECONDS=${VM_EXEC_HEARTBEAT_SECONDS:-30}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"

# Image tags for local build
TAG="e2e"
CONTROL_IMAGE="${LOCAL_REGISTRY}/nanofaas/control-plane:${TAG}"
RUNTIME_IMAGE="${LOCAL_REGISTRY}/nanofaas/function-runtime:${TAG}"
JAVA_WORD_STATS_IMAGE="${LOCAL_REGISTRY}/nanofaas/java-word-stats:${TAG}"
JAVA_JSON_TRANSFORM_IMAGE="${LOCAL_REGISTRY}/nanofaas/java-json-transform:${TAG}"
PYTHON_WORD_STATS_IMAGE="${LOCAL_REGISTRY}/nanofaas/python-word-stats:${TAG}"
PYTHON_JSON_TRANSFORM_IMAGE="${LOCAL_REGISTRY}/nanofaas/python-json-transform:${TAG}"
BASH_WORD_STATS_IMAGE="${LOCAL_REGISTRY}/nanofaas/bash-word-stats:${TAG}"
BASH_JSON_TRANSFORM_IMAGE="${LOCAL_REGISTRY}/nanofaas/bash-json-transform:${TAG}"
CURL_IMAGE="${LOCAL_REGISTRY}/curlimages/curl:latest"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${GREEN}[e2e]${NC} $*"; }
warn() { echo -e "${YELLOW}[e2e]${NC} $*"; }
info() { echo -e "${CYAN}[e2e]${NC} $*"; }
err()  { echo -e "${RED}[e2e]${NC} $*" >&2; }

check_prerequisites() {
    e2e_require_multipass
}

cleanup_stale_multipass_exec() {
    local stale_pids
    stale_pids=$(ps -axo pid=,ppid=,command= | awk -v vm="${VM_NAME}" '
        $2 == 1 && $0 ~ ("multipass exec " vm " --") { print $1 }
    ')

    if [[ -z "${stale_pids}" ]]; then
        return
    fi

    local stale_list
    stale_list=$(echo "${stale_pids}" | tr '\n' ' ' | sed 's/[[:space:]]\+$//')
    warn "Cleaning stale multipass exec processes: ${stale_list}"
    # shellcheck disable=SC2086
    kill ${stale_pids} 2>/dev/null || true
    sleep 1
}

get_vm_ip() {
    multipass info "${VM_NAME}" --format csv 2>/dev/null | tail -1 | cut -d, -f3
}

cleanup() {
    local exit_code=$?
    if [[ "${KEEP_VM}" == "true" ]]; then
        local vm_ip
        vm_ip=$(get_vm_ip) || true
        warn "KEEP_VM=true — VM '${VM_NAME}' preserved"
        warn "  SSH:    multipass shell ${VM_NAME}"
        warn "  Delete: multipass delete ${VM_NAME} && multipass purge"
        if [[ -n "${vm_ip}" ]]; then
            warn "  API:    http://${vm_ip}:30080/v1/functions"
            warn "  Metrics: http://${vm_ip}:30081/actuator/prometheus"
            warn "  Prom UI: http://${vm_ip}:30090"
        fi
        return
    fi
    log "Cleaning up VM ${VM_NAME}..."
    multipass delete "${VM_NAME}" 2>/dev/null || true
    multipass purge 2>/dev/null || true
    exit $exit_code
}
trap cleanup EXIT

vm_exec() {
    local remote_cmd="$*"
    local heartbeat_pid=""
    local rc=0

    if [[ "${VM_EXEC_HEARTBEAT_SECONDS}" -gt 0 ]]; then
        (
            while true; do
                sleep "${VM_EXEC_HEARTBEAT_SECONDS}"
                info "vm_exec still running: ${remote_cmd:0:120}"
            done
        ) &
        heartbeat_pid=$!
    fi

    set +e
    if [[ "${VM_EXEC_TIMEOUT_SECONDS}" -gt 0 ]]; then
        if command -v gtimeout >/dev/null 2>&1; then
            gtimeout "${VM_EXEC_TIMEOUT_SECONDS}" multipass exec "${VM_NAME}" -- bash -lc "export KUBECONFIG=/home/ubuntu/.kube/config; ${remote_cmd}"
            rc=$?
        elif command -v timeout >/dev/null 2>&1; then
            timeout "${VM_EXEC_TIMEOUT_SECONDS}" multipass exec "${VM_NAME}" -- bash -lc "export KUBECONFIG=/home/ubuntu/.kube/config; ${remote_cmd}"
            rc=$?
        else
            multipass exec "${VM_NAME}" -- bash -lc "export KUBECONFIG=/home/ubuntu/.kube/config; ${remote_cmd}"
            rc=$?
        fi
    else
        multipass exec "${VM_NAME}" -- bash -lc "export KUBECONFIG=/home/ubuntu/.kube/config; ${remote_cmd}"
        rc=$?
    fi
    set -e

    if [[ -n "${heartbeat_pid}" ]]; then
        kill "${heartbeat_pid}" >/dev/null 2>&1 || true
        wait "${heartbeat_pid}" 2>/dev/null || true
    fi

    if [[ "${rc}" -eq 124 ]]; then
        err "vm_exec timed out after ${VM_EXEC_TIMEOUT_SECONDS}s: ${remote_cmd}"
    fi
    return "${rc}"
}

# ─── Phase 1: Create VM ─────────────────────────────────────────────────────
create_vm() {
    if multipass info "${VM_NAME}" &>/dev/null; then
        local state
        state=$(multipass info "${VM_NAME}" --format csv | tail -1 | cut -d, -f2)
        if [[ "${state}" == "Running" ]]; then
            log "VM '${VM_NAME}' already running, reusing..."
            return
        else
            log "VM '${VM_NAME}' exists but in state '${state}', starting..."
            multipass start "${VM_NAME}"
            return
        fi
    fi

    log "Creating VM ${VM_NAME} (cpus=${CPUS}, mem=${MEMORY}, disk=${DISK})..."
    multipass launch --name "${VM_NAME}" --cpus "${CPUS}" --memory "${MEMORY}" --disk "${DISK}"
    log "VM created"
}

# ─── Phase 2: Install k3s + dependencies ─────────────────────────────────────
install_k3s() {
    if vm_exec "command -v k3s" &>/dev/null; then
        log "k3s already installed, skipping..."
        return
    fi

    log "Installing k3s..."
    vm_exec "curl -sfL https://get.k3s.io | sudo sh -s - --disable traefik"

    # Wait for node Ready
    vm_exec 'for i in $(seq 1 60); do
        if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q " Ready"; then
            exit 0
        fi
        sleep 2
    done
    echo "k3s not ready" >&2; exit 1'

    # Setup kubeconfig
    vm_exec "mkdir -p /home/ubuntu/.kube"
    vm_exec "sudo cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config"
    vm_exec "sudo chown ubuntu:ubuntu /home/ubuntu/.kube/config"
    vm_exec "chmod 600 /home/ubuntu/.kube/config"

    log "k3s installed and ready"
}

install_deps() {
    if vm_exec "command -v docker" &>/dev/null && vm_exec "command -v java" &>/dev/null && vm_exec "command -v helm" &>/dev/null; then
        log "Dependencies already present, skipping..."
        return
    fi

    log "Installing dependencies (Docker, JDK 21, Helm)..."
    vm_exec "sudo apt-get update -y -qq"
    vm_exec "sudo apt-get install -y -qq curl ca-certificates tar unzip openjdk-21-jdk"

    # Docker
    vm_exec 'if ! command -v docker >/dev/null 2>&1; then
        curl -fsSL https://get.docker.com | sudo sh
        sudo usermod -aG docker ubuntu
    fi'

    # Helm
    vm_exec 'if ! command -v helm >/dev/null 2>&1; then
        curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
    fi'

    log "Dependencies installed"
}

# ─── Phase 3: Build and push images to local registry ───────────────────────
sync_and_build() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        log "SKIP_BUILD=true, skipping build..."
        return
    fi

    log "Syncing project to VM..."
    vm_exec "rm -rf /home/ubuntu/nanofaas"
    multipass transfer --recursive "${PROJECT_ROOT}" "${VM_NAME}:/home/ubuntu/nanofaas"
    log "Project synced"

    log "Building JARs..."
    vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :control-plane:bootJar :function-runtime:bootJar :examples:java:word-stats:bootJar :examples:java:json-transform:bootJar --no-daemon -q"
    log "JARs built"

    log "Building Docker images..."

    # Core images
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${CONTROL_IMAGE} -f control-plane/Dockerfile control-plane/"
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${RUNTIME_IMAGE} -f function-runtime/Dockerfile function-runtime/"

    # Java demo images
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_WORD_STATS_IMAGE} -f examples/java/word-stats/Dockerfile examples/java/word-stats/"
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_JSON_TRANSFORM_IMAGE} -f examples/java/json-transform/Dockerfile examples/java/json-transform/"

    # Python demo images (need repo root context for function-sdk-python)
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${PYTHON_WORD_STATS_IMAGE} -f examples/python/word-stats/Dockerfile ."
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${PYTHON_JSON_TRANSFORM_IMAGE} -f examples/python/json-transform/Dockerfile ."

    # Bash demo images (need repo root for watchdog build)
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${BASH_WORD_STATS_IMAGE} -f examples/bash/word-stats/Dockerfile ."
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${BASH_JSON_TRANSFORM_IMAGE} -f examples/bash/json-transform/Dockerfile ."

    log "All images built"
}

push_images() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        log "SKIP_BUILD=true, skipping image push..."
        return
    fi

    log "Pushing images to local registry ${LOCAL_REGISTRY}..."

    local images=(
        "${CONTROL_IMAGE}"
        "${RUNTIME_IMAGE}"
        "${JAVA_WORD_STATS_IMAGE}"
        "${JAVA_JSON_TRANSFORM_IMAGE}"
        "${PYTHON_WORD_STATS_IMAGE}"
        "${PYTHON_JSON_TRANSFORM_IMAGE}"
        "${BASH_WORD_STATS_IMAGE}"
        "${BASH_JSON_TRANSFORM_IMAGE}"
    )

    for img in "${images[@]}"; do
        vm_exec "sudo docker push ${img}"
        info "  pushed: ${img}"
    done

    # Mirror curl image into local registry for the Helm registration job.
    info "Mirroring curlimages/curl into local registry..."
    vm_exec "sudo docker pull curlimages/curl:latest"
    vm_exec "sudo docker tag curlimages/curl:latest ${CURL_IMAGE}"
    vm_exec "sudo docker push ${CURL_IMAGE}"

    log "All images pushed"
}

# ─── Phase 4: Helm install ──────────────────────────────────────────────────
helm_install() {
    log "Installing nanofaas via Helm..."

    # Uninstall previous release if present (clean state)
    vm_exec "helm uninstall nanofaas --namespace ${NAMESPACE} 2>/dev/null || true"
    vm_exec "kubectl delete namespace ${NAMESPACE} --ignore-not-found --wait=true 2>/dev/null || true"
    sleep 3

    # Create values override for local images
    vm_exec "cat > /tmp/e2e-values.yaml << ENDVALUES
namespace:
  create: false
  name: nanofaas

controlPlane:
  image:
    repository: ${LOCAL_REGISTRY}/nanofaas/control-plane
    tag: ${TAG}
    pullPolicy: Always
  service:
    type: NodePort
    ports:
      http: 8080
      actuator: 8081
    nodePorts:
      http: 30080
      actuator: 30081
  extraEnv:
    - name: KUBERNETES_TRUST_CERTIFICATES
      value: \"true\"
    - name: SYNC_QUEUE_ADMISSION_ENABLED
      value: \"false\"

prometheus:
  create: true
  service:
    type: NodePort
    port: 9090
    nodePort: 30090
  pvc:
    enabled: false

demos:
  enabled: true
  functions:
    - name: word-stats-java
      image: ${LOCAL_REGISTRY}/nanofaas/java-word-stats:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: json-transform-java
      image: ${LOCAL_REGISTRY}/nanofaas/java-json-transform:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: word-stats-python
      image: ${LOCAL_REGISTRY}/nanofaas/python-word-stats:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: json-transform-python
      image: ${LOCAL_REGISTRY}/nanofaas/python-json-transform:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: word-stats-exec
      image: ${LOCAL_REGISTRY}/nanofaas/bash-word-stats:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      runtimeMode: STDIO
    - name: json-transform-exec
      image: ${LOCAL_REGISTRY}/nanofaas/bash-json-transform:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      runtimeMode: STDIO
  registerJob:
    image: ${CURL_IMAGE}
ENDVALUES"

    # Install with --create-namespace (namespace.create=false avoids the duplicate error)
    vm_exec "helm upgrade --install nanofaas /home/ubuntu/nanofaas/helm/nanofaas \
        -f /tmp/e2e-values.yaml \
        --namespace ${NAMESPACE} \
        --create-namespace \
        --wait --timeout 5m"

    log "Helm install complete"
}

# ─── Phase 5: Wait for functions and verify ──────────────────────────────────
verify() {
    log "Verifying deployment..."

    # Wait for control-plane
    vm_exec "kubectl rollout status deployment/nanofaas-control-plane -n ${NAMESPACE} --timeout=180s"
    log "  control-plane: Ready"

    # Wait for demo function pods (8 total: 1 control-plane + 1 prometheus + 6 functions)
    log "Waiting for demo function pods..."
    vm_exec 'for i in $(seq 1 90); do
        ready=$(kubectl get pods -n nanofaas --no-headers 2>/dev/null | grep -c "1/1.*Running" || echo 0)
        total=$(kubectl get pods -n nanofaas --no-headers 2>/dev/null | grep -cv Completed 2>/dev/null || echo 0)
        echo "  pods: ${ready}/${total} Running"
        if [ "${ready}" -ge 8 ]; then exit 0; fi
        sleep 5
    done
    echo "Not all pods ready" >&2
    kubectl get pods -n nanofaas
    exit 1'

    # Show pods
    vm_exec "kubectl get pods -n ${NAMESPACE}"

    # List registered functions
    log "Registered functions:"
    vm_exec "curl -sf http://localhost:30080/v1/functions"

    # Smoke-test: invoke each function
    log "Smoke-testing each function..."
    local vm_ip
    vm_ip=$(get_vm_ip)

    local ws_payload='{"input":{"text":"hello world test"}}'
    local jt_payload='{"input":{"data":[{"dept":"eng","salary":80000},{"dept":"sales","salary":70000}],"groupBy":"dept","operation":"count"}}'

    local all_ok=true
    for fn in word-stats-java word-stats-python word-stats-exec; do
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://${vm_ip}:30080/v1/functions/${fn}:invoke" \
            -H "Content-Type: application/json" -d "${ws_payload}" --max-time 30) || code="000"
        if [[ "${code}" == "200" ]]; then
            info "  ${fn}: OK (${code})"
        else
            err "  ${fn}: FAIL (${code})"
            all_ok=false
        fi
    done

    for fn in json-transform-java json-transform-python json-transform-exec; do
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://${vm_ip}:30080/v1/functions/${fn}:invoke" \
            -H "Content-Type: application/json" -d "${jt_payload}" --max-time 30) || code="000"
        if [[ "${code}" == "200" ]]; then
            info "  ${fn}: OK (${code})"
        else
            err "  ${fn}: FAIL (${code})"
            all_ok=false
        fi
    done

    # Prometheus check
    local prom_code
    prom_code=$(curl -s -o /dev/null -w "%{http_code}" "http://${vm_ip}:30090/api/v1/query?query=up" --max-time 5) || prom_code="000"
    if [[ "${prom_code}" == "200" ]]; then
        info "  prometheus: OK"
    else
        err "  prometheus: FAIL (${prom_code})"
        all_ok=false
    fi

    if [[ "${all_ok}" == "true" ]]; then
        log "All verifications passed"
    else
        err "Some verifications failed"
        exit 1
    fi
}

print_summary() {
    local vm_ip
    vm_ip=$(get_vm_ip) || vm_ip="<VM_IP>"

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         NANOFAAS E2E SETUP COMPLETE"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""
    log "VM: ${VM_NAME} (${vm_ip})"
    log ""
    log "Endpoints:"
    log "  API:        http://${vm_ip}:30080/v1/functions"
    log "  Metrics:    http://${vm_ip}:30081/actuator/prometheus"
    log "  Prometheus: http://${vm_ip}:30090"
    log ""
    log "Next step — run the load test:"
    log "  ./scripts/e2e-loadtest.sh"
    log ""
}

main() {
    log "Starting nanofaas E2E setup..."
    log "  VM=${VM_NAME} CPUS=${CPUS} MEM=${MEMORY} DISK=${DISK}"
    log "  NAMESPACE=${NAMESPACE} LOCAL_REGISTRY=${LOCAL_REGISTRY} SKIP_BUILD=${SKIP_BUILD}"
    log "  VM_EXEC_TIMEOUT_SECONDS=${VM_EXEC_TIMEOUT_SECONDS} VM_EXEC_HEARTBEAT_SECONDS=${VM_EXEC_HEARTBEAT_SECONDS}"
    log ""

    check_prerequisites
    cleanup_stale_multipass_exec
    create_vm
    install_deps
    install_k3s
    e2e_setup_local_registry "${LOCAL_REGISTRY}"
    sync_and_build
    push_images
    helm_install
    verify
    print_summary
}

main "$@"
