#!/usr/bin/env bash
set -euo pipefail

VM_NAME=${VM_NAME:-nanofaas-k3s-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas-e2e}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
CONTROL_IMAGE=${CONTROL_PLANE_IMAGE:-${LOCAL_REGISTRY}/nanofaas/control-plane:e2e}
RUNTIME_IMAGE=${FUNCTION_RUNTIME_IMAGE:-${LOCAL_REGISTRY}/nanofaas/function-runtime:e2e}
KEEP_VM=${KEEP_VM:-false}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/e2e-k3s-common.sh"
source "${SCRIPT_DIR}/scenario-manifest.sh"
e2e_set_log_prefix "k3s-e2e"
vm_exec() { e2e_vm_exec "$@"; }
REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}
FUNCTION_NAME=${FUNCTION_NAME:-echo-test}
FUNCTION_IMAGE=${FUNCTION_IMAGE:-${RUNTIME_IMAGE}}
FUNCTION_PAYLOAD_FILE=""

cleanup() {
    local exit_code=$?
    e2e_cleanup_vm
    exit "${exit_code}"
}

trap cleanup EXIT

resolve_function_selection() {
    if [[ -z "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        return 0
    fi

    FUNCTION_NAME=$(scenario_first_function_key)
    FUNCTION_IMAGE=$(scenario_function_image "${FUNCTION_NAME}" || echo "${RUNTIME_IMAGE}")
    FUNCTION_PAYLOAD_FILE=$(scenario_function_payload_path "${FUNCTION_NAME}" || true)
}

write_request_payload() {
    local destination=$1
    if [[ -n "${FUNCTION_PAYLOAD_FILE}" ]]; then
        scenario_write_wrapped_input "${FUNCTION_PAYLOAD_FILE}" "${destination}"
        return 0
    fi
    printf '%s\n' '{"input":{"message":"hello-k3s-test"}}' > "${destination}"
}

check_prerequisites() {
    e2e_require_vm_access
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
    e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "${REMOTE_DIR}"
}

build_jars() {
    if [[ "${CONTROL_PLANE_RUNTIME}" == "rust" ]]; then
        e2e_build_function_runtime_jar "${REMOTE_DIR}"
    else
        e2e_build_core_jars "${REMOTE_DIR}"
    fi
}

build_images() {
    if [[ "${CONTROL_PLANE_RUNTIME}" == "rust" ]]; then
        e2e_build_rust_control_plane_image "${REMOTE_DIR}" "${CONTROL_IMAGE}"
        e2e_build_function_runtime_image "${REMOTE_DIR}" "${RUNTIME_IMAGE}"
    else
        e2e_build_core_images "${REMOTE_DIR}" "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
    fi
}

build_primary_function_image() {
    if [[ -z "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        return 0
    fi

    local runtime family dockerfile
    runtime=$(scenario_function_runtime "${FUNCTION_NAME}" || true)
    family=$(scenario_function_family "${FUNCTION_NAME}" || true)

    case "${runtime}" in
        java)
            vm_exec "cd ${REMOTE_DIR} && ./gradlew :examples:java:${family}:bootBuildImage -PfunctionImage=${FUNCTION_IMAGE} --no-daemon -q"
            ;;
        java-lite)
            dockerfile="examples/java/${family}-lite/Dockerfile"
            vm_exec "cd ${REMOTE_DIR} && sudo docker build -t ${FUNCTION_IMAGE} -f ${dockerfile} ."
            ;;
        go)
            dockerfile="examples/go/${family}/Dockerfile"
            vm_exec "cd ${REMOTE_DIR} && sudo docker build -t ${FUNCTION_IMAGE} -f ${dockerfile} ."
            ;;
        python)
            dockerfile="examples/python/${family}/Dockerfile"
            vm_exec "cd ${REMOTE_DIR} && sudo docker build -t ${FUNCTION_IMAGE} -f ${dockerfile} ."
            ;;
        exec)
            dockerfile="examples/bash/${family}/Dockerfile"
            vm_exec "cd ${REMOTE_DIR} && sudo docker build -t ${FUNCTION_IMAGE} -f ${dockerfile} ."
            ;;
        *)
            error "unsupported selected function runtime '${runtime}'"
            exit 1
            ;;
    esac
}

push_images_to_registry() {
    e2e_push_images_to_registry "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
    if [[ -n "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        e2e_push_images_to_registry "${FUNCTION_IMAGE}"
    fi
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
    if [[ -n "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        local payload
        payload="{\"name\":\"${FUNCTION_NAME}\",\"image\":\"${FUNCTION_IMAGE}\",\"timeoutMs\":5000,\"concurrency\":2,\"queueSize\":20,\"maxRetries\":3,\"executionMode\":\"DEPLOYMENT\"}"
        e2e_kubectl_curl_control_plane "${NAMESPACE}" "curl-register" "POST" "/v1/functions" "${payload}" "20" >/dev/null
        return 0
    fi

    e2e_register_pool_function \
        "${NAMESPACE}" \
        "${FUNCTION_NAME}" \
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
    local response request_file
    request_file=$(mktemp -t nanofaas-k3s-request.XXXXXX.json)
    write_request_payload "${request_file}"
    if [[ -n "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        response=$(e2e_kubectl_curl_control_plane "${NAMESPACE}" "curl-invoke-test" "POST" "/v1/functions/${FUNCTION_NAME}:invoke" "$(tr -d '\n' < "${request_file}")" "20")
    else
        response=$(e2e_invoke_sync_message "${NAMESPACE}" "${FUNCTION_NAME}" "hello-k3s-test" "curl-invoke-test")
    fi
    rm -f "${request_file}"
    if [[ -z "${response}" ]]; then
        error "No valid JSON response from sync invoke"
        exit 1
    fi
    if ! echo "${response}" | grep -q '"status":"success"'; then
        error "Sync invoke did not return success: ${response}"
        exit 1
    fi
    if [[ -z "${NANOFAAS_SCENARIO_PATH:-}" ]] && ! echo "${response}" | grep -q '"message":"hello-k3s-test"'; then
        error "Sync invoke did not echo expected message: ${response}"
        exit 1
    fi
    log "Function invocation verified successfully"
}

test_async_invocation() {
    log "Testing async function invocation..."

    local enqueue_json exec_id request_file
    request_file=$(mktemp -t nanofaas-k3s-enqueue.XXXXXX.json)
    write_request_payload "${request_file}"
    if [[ -n "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        enqueue_json=$(e2e_kubectl_curl_control_plane "${NAMESPACE}" "curl-enqueue" "POST" "/v1/functions/${FUNCTION_NAME}:enqueue" "$(tr -d '\n' < "${request_file}")" "20")
    else
        enqueue_json=$(e2e_enqueue_message "${NAMESPACE}" "${FUNCTION_NAME}" "async-test" "curl-enqueue")
    fi
    rm -f "${request_file}"
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

    if ! echo "${metrics}" | grep -q 'function_enqueue_total'; then
        error "function_enqueue_total metric not found"
        exit 1
    fi
    if ! echo "${metrics}" | grep -q 'function_success_total'; then
        error "function_success_total metric not found"
        exit 1
    fi
    if ! echo "${metrics}" | grep -q 'function_queue_depth'; then
        error "function_queue_depth metric not found"
        exit 1
    fi
    if ! echo "${metrics}" | grep -q 'function_inFlight'; then
        error "function_inFlight metric not found"
        exit 1
    fi

    log "All Prometheus metrics verified"
}

test_queue_depth() {
    log "Testing queue depth metric under load..."
    e2e_enqueue_message_burst "${NAMESPACE}" "${FUNCTION_NAME}" "queue-test" 5 "curl-queue"
    sleep 5
    e2e_fetch_control_plane_prometheus "${NAMESPACE}" >/dev/null
    log "Queue depth test completed"
}

print_summary() {
    log "k3s curl workflow completed"
    log "  VM=${VM_NAME}"
    log "  Namespace=${NAMESPACE}"
    log "  Registry=${LOCAL_REGISTRY}"
}

main() {
    log "Starting k3s E2E test..."
    log "  VM_NAME=${VM_NAME}"
    log "  NAMESPACE=${NAMESPACE}"
    log "  LOCAL_REGISTRY=${LOCAL_REGISTRY}"
    log "  CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME}"
    log "  KEEP_VM=${KEEP_VM}"

    resolve_function_selection
    check_prerequisites
    if [[ "${E2E_SKIP_VM_BOOTSTRAP:-false}" != "true" ]]; then
        create_vm
        install_dependencies
        install_k3s
        e2e_setup_local_registry "${LOCAL_REGISTRY}"
    fi

    sync_project
    build_jars
    build_images
    build_primary_function_image
    push_images_to_registry
    create_namespace
    deploy_control_plane
    deploy_function_runtime
    wait_for_deployment "control-plane" 180
    wait_for_deployment "function-runtime" 120
    verify_pods_running
    verify_health_endpoints
    register_function
    invoke_function_with_curl
    test_async_invocation
    verify_prometheus_metrics
    test_queue_depth
    print_summary
}

main "$@"
