#!/usr/bin/env bash
set -euo pipefail

# E2E test: Cold Start Metrics Verification
# Deploys to k3s, invokes a function, verifies cold/warm start Prometheus metrics.

VM_NAME=${VM_NAME:-nanofaas-cold-metrics-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas-e2e}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
CONTROL_IMAGE=${CONTROL_PLANE_IMAGE:-${LOCAL_REGISTRY}/nanofaas/control-plane:e2e}
RUNTIME_IMAGE=${FUNCTION_RUNTIME_IMAGE:-${LOCAL_REGISTRY}/nanofaas/function-runtime:e2e}
KEEP_VM=${KEEP_VM:-false}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[cold-start-e2e]${NC} $*"; }
warn() { echo -e "${YELLOW}[cold-start-e2e]${NC} $*"; }
error() { echo -e "${RED}[cold-start-e2e]${NC} $*" >&2; }

TESTS_PASSED=0
TESTS_FAILED=0

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

vm_exec() {
    multipass exec "${VM_NAME}" -- bash -lc "export KUBECONFIG=/home/ubuntu/.kube/config; $*"
}

# --- Setup (reused pattern from e2e-k3s-curl.sh) ---

check_prerequisites() {
    e2e_require_multipass
    log "Prerequisites check passed"
}

create_vm() {
    log "Creating VM ${VM_NAME} (cpus=${CPUS}, memory=${MEMORY}, disk=${DISK})..."
    multipass launch --name "${VM_NAME}" --cpus "${CPUS}" --memory "${MEMORY}" --disk "${DISK}"
    log "VM created successfully"
}

install_dependencies() {
    log "Installing dependencies in VM..."
    vm_exec "sudo apt-get update -y"
    vm_exec "sudo apt-get install -y curl ca-certificates tar unzip openjdk-21-jdk"
    vm_exec "if ! command -v docker >/dev/null 2>&1; then
        curl -fsSL https://get.docker.com | sudo sh
        sudo usermod -aG docker ubuntu
    fi"
    log "Dependencies installed"
}

install_k3s() {
    e2e_install_k3s
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

push_images_to_registry() {
    e2e_push_images_to_registry "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
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
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: api
        - containerPort: 8081
          name: management
        env:
        - name: POD_NAMESPACE
          value: \"${NAMESPACE}\"
        - name: SYNC_QUEUE_ENABLED
          value: \"false\"
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
        imagePullPolicy: Always
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

verify_pods_running() {
    log "Verifying all pods are running..."

    local cp_status
    cp_status=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=control-plane -o jsonpath='{.items[0].status.phase}'")
    if [[ "${cp_status}" != "Running" ]]; then
        error "control-plane pod is not Running (status: ${cp_status})"
        vm_exec "kubectl describe pod -n ${NAMESPACE} -l app=control-plane"
        exit 1
    fi
    log "  control-plane: Running"

    local fr_status
    fr_status=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=function-runtime -o jsonpath='{.items[0].status.phase}'")
    if [[ "${fr_status}" != "Running" ]]; then
        error "function-runtime pod is not Running (status: ${fr_status})"
        vm_exec "kubectl describe pod -n ${NAMESPACE} -l app=function-runtime"
        exit 1
    fi
    log "  function-runtime: Running"

    log "All pods running"
}

dump_pod_logs() {
    warn "--- control-plane logs (last 50 lines) ---"
    vm_exec "kubectl logs -n ${NAMESPACE} -l app=control-plane --tail=50" 2>/dev/null || true
    warn "--- function-runtime logs (last 50 lines) ---"
    vm_exec "kubectl logs -n ${NAMESPACE} -l app=function-runtime --tail=50" 2>/dev/null || true
}

# --- Test-specific functions ---

register_function() {
    log "Registering echo-test function in POOL mode..."

    vm_exec "kubectl run curl-register --rm -i --restart=Never --image=curlimages/curl:latest -n ${NAMESPACE} -- \
        curl -sf -X POST http://control-plane:8080/v1/functions \
        -H 'Content-Type: application/json' \
        -d '{
            \"name\": \"echo-test\",
            \"image\": \"${RUNTIME_IMAGE}\",
            \"timeoutMs\": 30000,
            \"concurrency\": 2,
            \"queueSize\": 20,
            \"maxRetries\": 3,
            \"executionMode\": \"POOL\",
            \"endpointUrl\": \"http://function-runtime:8080/invoke\"
        }'"

    log "Function registered"

    # Allow scheduler to pick up the new function
    sleep 2
}

invoke_sync() {
    local label=$1
    log "Sync invoke (${label})..."

    # Invoke and capture response. kubectl run --rm -i mixes pod deletion message
    # into stdout, so we grep for the JSON body instead of parsing HTTP codes.
    local response
    response=$(vm_exec "kubectl run curl-invoke-${label} --rm -i --restart=Never --image=curlimages/curl:latest -n ${NAMESPACE} -- \
        curl -s --max-time 35 -X POST http://control-plane:8080/v1/functions/echo-test:invoke \
        -H 'Content-Type: application/json' \
        -d '{\"input\": {\"message\": \"${label}\"}}'")

    # Extract JSON body (line containing executionId)
    local body
    body=$(echo "${response}" | grep '"executionId"' | head -1)

    if [[ -z "${body}" ]]; then
        error "  ${label}: No valid JSON response — raw output: ${response}"
        return 1
    fi

    # Check for error status
    if echo "${body}" | grep -q '"status":"error"'; then
        error "  ${label}: Error response — ${body}"
        return 1
    fi

    # Extract executionId
    local exec_id
    exec_id=$(echo "${body}" | sed -n 's/.*"executionId":"\([^"]*\)".*/\1/p')

    if [[ -z "${exec_id}" ]]; then
        warn "  Could not extract executionId from response: ${body}"
        return
    fi

    # Query execution status to get cold start details
    local status_response
    status_response=$(vm_exec "kubectl run curl-status-${label} --rm -i --restart=Never --image=curlimages/curl:latest -n ${NAMESPACE} -- \
        curl -s --max-time 10 http://control-plane:8080/v1/executions/${exec_id}" | grep '"executionId"' | head -1)

    local is_cold
    is_cold=$(echo "${status_response}" | sed -n 's/.*"coldStart":\([a-z]*\).*/\1/p')
    local init_ms
    init_ms=$(echo "${status_response}" | sed -n 's/.*"initDurationMs":\([0-9]*\).*/\1/p')

    if [[ "${is_cold}" == "true" ]]; then
        log "  ${label}: COLD START (initDurationMs=${init_ms:-N/A}ms) [executionId=${exec_id}]"
    else
        log "  ${label}: WARM START [executionId=${exec_id}]"
    fi
}

assert_metric_gte() {
    local metrics=$1
    local metric_name=$2
    local min_value=$3
    local description=$4

    local value
    value=$(echo "${metrics}" | grep "${metric_name}" | grep 'function="echo-test"' | grep -v '^#' | head -1 | awk '{print $NF}')

    if [[ -z "${value}" ]]; then
        error "FAIL: ${description} — metric '${metric_name}' not found"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi

    # Compare as floats: value >= min_value
    if awk "BEGIN {exit !(${value} >= ${min_value})}"; then
        log "  PASS: ${description} (${metric_name} = ${value}, expected >= ${min_value})"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        error "FAIL: ${description} (${metric_name} = ${value}, expected >= ${min_value})"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

assert_metric_exists() {
    local metrics=$1
    local metric_name=$2
    local description=$3

    if echo "${metrics}" | grep -q "${metric_name}"; then
        log "  PASS: ${description} (${metric_name} present)"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        error "FAIL: ${description} (${metric_name} not found)"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

assert_metric_gt() {
    local metrics=$1
    local metric_name=$2
    local min_exclusive=$3
    local description=$4

    local value
    value=$(echo "${metrics}" | grep "${metric_name}" | grep 'function="echo-test"' | grep -v '^#' | head -1 | awk '{print $NF}')

    if [[ -z "${value}" ]]; then
        error "FAIL: ${description} — metric '${metric_name}' not found"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi

    if awk "BEGIN {exit !(${value} > ${min_exclusive})}"; then
        log "  PASS: ${description} (${metric_name} = ${value}, expected > ${min_exclusive})"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        error "FAIL: ${description} (${metric_name} = ${value}, expected > ${min_exclusive})"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

verify_cold_start_metrics() {
    log "Scraping Prometheus metrics from control-plane management port..."

    local pod_name
    pod_name=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=control-plane -o jsonpath='{.items[0].metadata.name}'")

    local metrics
    metrics=$(vm_exec "kubectl exec -n ${NAMESPACE} ${pod_name} -- curl -sf http://localhost:8081/actuator/prometheus")

    log "Asserting cold start metrics..."

    # Cold start counter: at least 1 (the first invocation)
    assert_metric_gte "${metrics}" "function_cold_start_total" 1 \
        "Cold start counter >= 1" || true

    # Warm start counter: at least 1 (invocations 2-5)
    assert_metric_gte "${metrics}" "function_warm_start_total" 1 \
        "Warm start counter >= 1" || true

    # Init duration timer count: at least 1 recording
    # Micrometer Timer "function_init_duration_ms" → Prometheus "function_init_duration_ms_seconds_count"
    assert_metric_gte "${metrics}" "function_init_duration_ms_seconds_count" 1 \
        "Init duration timer count >= 1" || true

    # Init duration timer sum: should be > 0
    assert_metric_gt "${metrics}" "function_init_duration_ms_seconds_sum" 0 \
        "Init duration timer sum > 0" || true

    # Queue wait timer count: at least 1
    assert_metric_gte "${metrics}" "function_queue_wait_ms_seconds_count" 1 \
        "Queue wait timer count >= 1" || true

    # E2E latency timer count: at least 1
    assert_metric_gte "${metrics}" "function_e2e_latency_ms_seconds_count" 1 \
        "E2E latency timer count >= 1" || true

    # Backward compat: function_latency_ms timer exists
    assert_metric_exists "${metrics}" "function_latency_ms_seconds_count" \
        "Legacy latency timer exists" || true

    # Print a summary of timing values for visibility
    log ""
    log "Timing summary from Prometheus metrics:"
    local init_sum init_count queue_sum queue_count e2e_sum e2e_count

    init_sum=$(echo "${metrics}" | grep 'function_init_duration_ms_seconds_sum' | grep 'function="echo-test"' | grep -v '^#' | awk '{print $NF}')
    init_count=$(echo "${metrics}" | grep 'function_init_duration_ms_seconds_count' | grep 'function="echo-test"' | grep -v '^#' | awk '{print $NF}')
    if [[ -n "${init_sum}" && -n "${init_count}" ]]; then
        log "  Init duration:  sum=${init_sum}s  count=${init_count}"
    fi

    queue_sum=$(echo "${metrics}" | grep 'function_queue_wait_ms_seconds_sum' | grep 'function="echo-test"' | grep -v '^#' | awk '{print $NF}')
    queue_count=$(echo "${metrics}" | grep 'function_queue_wait_ms_seconds_count' | grep 'function="echo-test"' | grep -v '^#' | awk '{print $NF}')
    if [[ -n "${queue_sum}" && -n "${queue_count}" ]]; then
        log "  Queue wait:     sum=${queue_sum}s  count=${queue_count}"
    fi

    e2e_sum=$(echo "${metrics}" | grep 'function_e2e_latency_ms_seconds_sum' | grep 'function="echo-test"' | grep -v '^#' | awk '{print $NF}')
    e2e_count=$(echo "${metrics}" | grep 'function_e2e_latency_ms_seconds_count' | grep 'function="echo-test"' | grep -v '^#' | awk '{print $NF}')
    if [[ -n "${e2e_sum}" && -n "${e2e_count}" ]]; then
        log "  E2E latency:    sum=${e2e_sum}s  count=${e2e_count}"
    fi

    local cold_total warm_total
    cold_total=$(echo "${metrics}" | grep 'function_cold_start_total' | grep 'function="echo-test"' | grep -v '^#' | awk '{print $NF}')
    warm_total=$(echo "${metrics}" | grep 'function_warm_start_total' | grep 'function="echo-test"' | grep -v '^#' | awk '{print $NF}')
    log "  Cold starts: ${cold_total:-0}  Warm starts: ${warm_total:-0}"
}

print_summary() {
    log ""
    log "=========================================="
    log "  COLD START METRICS E2E TEST COMPLETE"
    log "=========================================="
    log ""
    log "  VM: ${VM_NAME}"
    log "  Namespace: ${NAMESPACE}"
    log ""
    log "  Assertions passed: ${TESTS_PASSED}"
    log "  Assertions failed: ${TESTS_FAILED}"
    log ""

    if [[ "${TESTS_FAILED}" -gt 0 ]]; then
        error "SOME ASSERTIONS FAILED"
        exit 1
    fi

    log "ALL ASSERTIONS PASSED"
}

main() {
    log "Starting Cold Start Metrics E2E test..."
    log "Configuration:"
    log "  VM_NAME=${VM_NAME}"
    log "  CPUS=${CPUS}, MEMORY=${MEMORY}, DISK=${DISK}"
    log "  NAMESPACE=${NAMESPACE}"
    log "  LOCAL_REGISTRY=${LOCAL_REGISTRY}"
    log "  KEEP_VM=${KEEP_VM}"
    log ""

    # Phase 1: Setup
    check_prerequisites
    create_vm
    install_dependencies
    install_k3s
    e2e_setup_local_registry "${LOCAL_REGISTRY}"

    # Phase 2: Build
    sync_project
    build_jars
    build_images
    push_images_to_registry

    # Phase 3: Deploy
    create_namespace
    deploy_control_plane
    deploy_function_runtime

    # Phase 4: Verify deployment
    wait_for_deployment "control-plane" 180
    wait_for_deployment "function-runtime" 120
    verify_pods_running

    # Phase 5: Invoke — 1 cold start + 4 warm starts
    register_function

    log "Invoking function 5 times (1 cold + 4 warm)..."
    invoke_sync "cold-1" || { error "First invoke failed — check control-plane and function-runtime logs"; dump_pod_logs; exit 1; }
    sleep 1
    invoke_sync "warm-2" || true
    invoke_sync "warm-3" || true
    invoke_sync "warm-4" || true
    invoke_sync "warm-5" || true

    # Allow metrics to flush
    sleep 3

    # Phase 6: Assert metrics
    verify_cold_start_metrics

    # Done
    print_summary
}

main "$@"
