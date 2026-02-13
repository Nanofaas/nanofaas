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
    e2e_create_vm "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"
}

install_dependencies() {
    e2e_install_vm_dependencies
}

install_k3s() {
    e2e_install_k3s
}

sync_project() {
    e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "/home/ubuntu/nanofaas"
}

build_jars() {
    e2e_build_core_jars "/home/ubuntu/nanofaas"
}

build_images() {
    e2e_build_core_images "/home/ubuntu/nanofaas" "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
}

push_images_to_registry() {
    e2e_push_images_to_registry "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
}

create_namespace() {
    e2e_create_namespace "${NAMESPACE}"
}

deploy_control_plane() {
    e2e_deploy_control_plane "${NAMESPACE}" "${CONTROL_IMAGE}" "${NAMESPACE}" "false"
}

deploy_function_runtime() {
    e2e_deploy_function_runtime "${NAMESPACE}" "${RUNTIME_IMAGE}"
}

wait_for_deployment() {
    local name=$1
    local timeout=${2:-180}
    e2e_wait_for_deployment "${NAMESPACE}" "${name}" "${timeout}"
}

verify_pods_running() {
    e2e_verify_core_pods_running "${NAMESPACE}"
}

dump_pod_logs() {
    e2e_dump_core_pod_logs "${NAMESPACE}" 50
}

# --- Test-specific functions ---

register_function() {
    e2e_register_pool_function \
        "${NAMESPACE}" \
        "echo-test" \
        "${RUNTIME_IMAGE}" \
        "http://function-runtime:8080/invoke" \
        30000 \
        2 \
        20 \
        3 \
        "curl-register"

    # Allow scheduler to pick up the new function
    sleep 2
}

invoke_sync() {
    local label=$1
    log "Sync invoke (${label})..."

    local body
    body=$(e2e_invoke_sync_message "${NAMESPACE}" "echo-test" "${label}" "curl-invoke-${label}")

    if [[ -z "${body}" ]]; then
        error "  ${label}: No valid JSON response"
        return 1
    fi

    # Check for error status
    if echo "${body}" | grep -q '"status":"error"'; then
        error "  ${label}: Error response — ${body}"
        return 1
    fi

    # Extract executionId
    local exec_id
    exec_id=$(e2e_extract_execution_id "${body}")

    if [[ -z "${exec_id}" ]]; then
        warn "  Could not extract executionId from response: ${body}"
        return
    fi

    local status_response
    status_response=$(e2e_fetch_execution "${NAMESPACE}" "${exec_id}" "curl-status-${label}")

    local is_cold
    is_cold=$(e2e_extract_bool_field "${status_response}" "coldStart")
    local init_ms
    init_ms=$(e2e_extract_numeric_field "${status_response}" "initDurationMs")

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

    local metrics
    metrics=$(e2e_fetch_control_plane_prometheus "${NAMESPACE}")

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
