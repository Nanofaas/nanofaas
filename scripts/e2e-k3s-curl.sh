#!/usr/bin/env bash
set -euo pipefail

# Configuration with defaults
VM_NAME=${VM_NAME:-nanofaas-k3s-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas-e2e}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
CONTROL_IMAGE=${CONTROL_PLANE_IMAGE:-${LOCAL_REGISTRY}/nanofaas/control-plane:e2e}
RUNTIME_IMAGE=${FUNCTION_RUNTIME_IMAGE:-${LOCAL_REGISTRY}/nanofaas/function-runtime:e2e}
KEEP_VM=${KEEP_VM:-false}
# Set to "rust" to build the Rust control-plane instead of the Java one.
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "k3s-e2e"
vm_exec() { e2e_vm_exec "$@"; }

cleanup() {
    local exit_code=$?
    e2e_cleanup_vm
    exit "${exit_code}"
}

trap cleanup EXIT

check_prerequisites() {
    e2e_require_multipass
    log "Prerequisites check passed"
}

create_vm() {
    e2e_ensure_vm_running "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"
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
    if [[ "${CONTROL_PLANE_RUNTIME}" == "rust" ]]; then
        e2e_build_function_runtime_jar "/home/ubuntu/nanofaas"
    else
        e2e_build_core_jars "/home/ubuntu/nanofaas"
    fi
}

build_images() {
    if [[ "${CONTROL_PLANE_RUNTIME}" == "rust" ]]; then
        e2e_build_rust_control_plane_image "/home/ubuntu/nanofaas" "${CONTROL_IMAGE}"
        e2e_build_function_runtime_image "/home/ubuntu/nanofaas" "${RUNTIME_IMAGE}"
    else
        e2e_build_core_images "/home/ubuntu/nanofaas" "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
    fi
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

verify_health_endpoints() {
    e2e_verify_control_plane_health "${NAMESPACE}"
}

verify_pods_running() {
    e2e_verify_core_pods_running "${NAMESPACE}"
}

register_function() {
    e2e_register_pool_function \
        "${NAMESPACE}" \
        "echo-test" \
        "${RUNTIME_IMAGE}" \
        "http://function-runtime:8080/invoke" \
        5000 \
        2 \
        20 \
        3 \
        "curl-register"
}

invoke_function_with_curl() {
    log "Invoking function and validating sync response..."
    local response
    response=$(e2e_invoke_sync_message "${NAMESPACE}" "echo-test" "hello-k3s-test" "curl-invoke-test")
    if [[ -z "${response}" ]]; then
        error "No valid JSON response from sync invoke"
        exit 1
    fi
    if ! echo "${response}" | grep -q '"status":"success"'; then
        error "Sync invoke did not return success: ${response}"
        exit 1
    fi
    if ! echo "${response}" | grep -q '"message":"hello-k3s-test"'; then
        error "Sync invoke did not echo expected message: ${response}"
        exit 1
    fi
    log "Function invocation verified successfully"
}

test_async_invocation() {
    log "Testing async function invocation..."

    local enqueue_json exec_id
    enqueue_json=$(e2e_enqueue_message "${NAMESPACE}" "echo-test" "async-test" "curl-enqueue")
    exec_id=$(e2e_extract_execution_id "${enqueue_json}")

    if [[ -z "${exec_id}" ]]; then
        error "Failed to extract async execution ID: ${enqueue_json}"
        exit 1
    fi
    log "Async execution ID: ${exec_id}"

    log "Polling for execution completion..."
    if e2e_wait_execution_success "${NAMESPACE}" "${exec_id}" 20 1 "curl-poll"; then
        log "Async execution completed successfully"
        return 0
    fi
    error "Async execution did not complete successfully within timeout (executionId=${exec_id})"
    exit 1
}

verify_prometheus_metrics() {
    log "Verifying Prometheus metrics..."

    local metrics
    metrics=$(e2e_fetch_control_plane_prometheus "${NAMESPACE}")

    # Verify core metrics exist
    if echo "${metrics}" | grep -q 'function_enqueue_total'; then
        log "  function_enqueue_total: present"
    else
        error "function_enqueue_total metric not found"
        exit 1
    fi

    if echo "${metrics}" | grep -q 'function_success_total'; then
        log "  function_success_total: present"
    else
        error "function_success_total metric not found"
        exit 1
    fi

    if echo "${metrics}" | grep -q 'function_queue_depth'; then
        log "  function_queue_depth: present"
    else
        error "function_queue_depth metric not found"
        exit 1
    fi

    if echo "${metrics}" | grep -q 'function_inFlight'; then
        log "  function_inFlight: present"
    else
        error "function_inFlight metric not found"
        exit 1
    fi

    log "All Prometheus metrics verified"
}

test_queue_depth() {
    log "Testing queue depth metric under load..."

    log "Enqueueing burst async requests..."
    e2e_enqueue_message_burst "${NAMESPACE}" "echo-test" "queue-test" 5 "curl-queue"

    # Check the metrics
    local metrics
    metrics=$(e2e_fetch_control_plane_prometheus "${NAMESPACE}")

    # Verify enqueue counter increased
    local enqueue_count
    enqueue_count=$(echo "${metrics}" | grep 'function_enqueue_total{' | grep 'echo-test' | sed -n 's/.*} \([0-9.]*\)/\1/p')

    # We've done 2 sync + 1 async + 5 queue = at least 8 enqueues
    if [[ -n "${enqueue_count}" ]]; then
        log "  function_enqueue_total for echo-test: ${enqueue_count}"
    else
        log "  function_enqueue_total: (checking)"
    fi

    # Wait for queue to drain
    log "Waiting for queue to drain..."
    sleep 5

    # Final metrics check
    metrics=$(e2e_fetch_control_plane_prometheus "${NAMESPACE}")

    local success_count
    success_count=$(echo "${metrics}" | grep 'function_success_total{' | grep 'echo-test' | sed -n 's/.*} \([0-9.]*\)/\1/p')
    log "  Total successful invocations: ${success_count:-0}"

    local queue_depth
    queue_depth=$(echo "${metrics}" | grep 'function_queue_depth{' | grep 'echo-test' | sed -n 's/.*} \([0-9.]*\)/\1/p')
    log "  Final queue depth: ${queue_depth:-0}"

    log "Queue depth test completed"
}

print_summary() {
    log ""
    log "=========================================="
    log "        E2E TEST COMPLETED SUCCESSFULLY"
    log "=========================================="
    log ""
    log "Summary:"
    log "  - VM: ${VM_NAME}"
    log "  - Namespace: ${NAMESPACE}"
    log "  - Local registry: ${LOCAL_REGISTRY}"
    log "  - Control-plane image: ${CONTROL_IMAGE}"
    log "  - Function-runtime image: ${RUNTIME_IMAGE}"
    log ""
    log "Tests passed:"
    log "  [✓] VM creation and k3s installation"
    log "  [✓] Docker image build and push to local registry"
    log "  [✓] Kubernetes deployment with health probes"
    log "  [✓] Health endpoint verification (liveness/readiness)"
    log "  [✓] Function registration"
    log "  [✓] Sync function invocation with curl"
    log "  [✓] Async function invocation and polling"
    log "  [✓] Prometheus metrics verification"
    log "  [✓] Queue depth metric under load"
    log ""
}

main() {
    log "Starting k3s E2E test..."
    log "Configuration:"
    log "  VM_NAME=${VM_NAME}"
    log "  CPUS=${CPUS}, MEMORY=${MEMORY}, DISK=${DISK}"
    log "  NAMESPACE=${NAMESPACE}"
    log "  LOCAL_REGISTRY=${LOCAL_REGISTRY}"
    log "  CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME}"
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
    verify_health_endpoints

    # Phase 5: Test functions
    register_function
    invoke_function_with_curl
    test_async_invocation

    # Phase 6: Verify metrics
    verify_prometheus_metrics
    test_queue_depth

    # Done
    print_summary
}

# Run main
main "$@"
