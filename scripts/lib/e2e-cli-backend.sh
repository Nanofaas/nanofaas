#!/usr/bin/env bash
set -euo pipefail

VM_NAME=${VM_NAME:-nanofaas-cli-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas-e2e}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
CONTROL_IMAGE=${CONTROL_PLANE_IMAGE:-${LOCAL_REGISTRY}/nanofaas/control-plane:e2e}
RUNTIME_IMAGE=${FUNCTION_RUNTIME_IMAGE:-${LOCAL_REGISTRY}/nanofaas/function-runtime:e2e}
KEEP_VM=${KEEP_VM:-false}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}
CONTROL_IMAGE_REPOSITORY=${CONTROL_IMAGE%:*}
CONTROL_IMAGE_TAG=${CONTROL_IMAGE##*:}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/e2e-k3s-common.sh"
source "${SCRIPT_DIR}/scenario-manifest.sh"
e2e_set_log_prefix "cli-e2e"
e2e_test_init
REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}
CLI_BIN_DIR="${REMOTE_DIR}/nanofaas-cli/build/install/nanofaas-cli/bin"
REMOTE_HELM_DIR="${REMOTE_DIR}/helm/nanofaas"

NANOFAAS_ENDPOINT=""
LAST_EXEC_ID=""
FUNCTION_NAME=${FUNCTION_NAME:-echo-test}
FUNCTION_IMAGE=${FUNCTION_IMAGE:-${RUNTIME_IMAGE}}
FUNCTION_PAYLOAD_FILE=""

cleanup() {
    local exit_code=$?
    e2e_cleanup_vm
    exit "${exit_code}"
}
trap cleanup EXIT

pass() {
    e2e_pass "$1"
}

fail_now() {
    e2e_fail "$1"
    exit 1
}

vm_exec() {
    local env_vars="export PATH=\$PATH:${CLI_BIN_DIR}; "
    if [[ -n "${NANOFAAS_ENDPOINT}" ]]; then
        env_vars+="export NANOFAAS_ENDPOINT=${NANOFAAS_ENDPOINT}; "
    fi
    env_vars+="export NANOFAAS_NAMESPACE=${NAMESPACE}; "
    e2e_vm_exec "${env_vars}$*"
}

resolve_function_selection() {
    if [[ -z "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        return 0
    fi

    FUNCTION_NAME=$(scenario_first_function_key)
    FUNCTION_IMAGE=$(scenario_function_image "${FUNCTION_NAME}" || echo "${RUNTIME_IMAGE}")
    FUNCTION_PAYLOAD_FILE=$(scenario_function_payload_path "${FUNCTION_NAME}" || true)
}

write_cli_request() {
    local destination=$1
    if [[ -n "${FUNCTION_PAYLOAD_FILE}" ]]; then
        scenario_write_wrapped_input "${FUNCTION_PAYLOAD_FILE}" "${destination}"
        return 0
    fi
    printf '%s\n' '{"input":{"message":"hello-from-cli"}}' > "${destination}"
}

check_prerequisites() {
    e2e_require_vm_access
    log "Prerequisites check passed"
}

create_vm() {
    e2e_ensure_vm_running "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"
}

install_dependencies() {
    e2e_install_vm_dependencies true
}

install_k3s() {
    e2e_install_k3s
}

sync_project() {
    e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "${REMOTE_DIR}"
}

build_artifacts() {
    e2e_build_control_plane_artifacts "${REMOTE_DIR}"
    e2e_build_control_plane_image "${REMOTE_DIR}" "${CONTROL_IMAGE}"
    e2e_build_function_runtime_image "${REMOTE_DIR}" "${RUNTIME_IMAGE}"
    e2e_push_images_to_registry "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
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
            fail_now "unsupported selected function runtime '${runtime}'"
            ;;
    esac
    vm_exec "sudo docker push ${FUNCTION_IMAGE}"
}

deploy_platform_primitives() {
    e2e_create_namespace "${NAMESPACE}"
    e2e_deploy_control_plane "${NAMESPACE}" "${CONTROL_IMAGE}" "${NAMESPACE}" "false"
    e2e_deploy_function_runtime "${NAMESPACE}" "${RUNTIME_IMAGE}"
    e2e_wait_for_deployment "${NAMESPACE}" "control-plane" 180
    e2e_wait_for_deployment "${NAMESPACE}" "function-runtime" 120
    e2e_verify_core_pods_running "${NAMESPACE}"
    e2e_verify_control_plane_health "${NAMESPACE}"
}

build_cli() {
    log "Building CLI in VM..."
    vm_exec "cd ${REMOTE_DIR} && ./gradlew :nanofaas-cli:installDist --no-daemon -q"
}

setup_cli_env() {
    NANOFAAS_ENDPOINT="$(vm_exec "kubectl get svc control-plane -n ${NAMESPACE} -o jsonpath='{.spec.clusterIP}'")"
    if [[ -z "${NANOFAAS_ENDPOINT}" ]]; then
        fail_now "failed to resolve control-plane ClusterIP"
    fi
    NANOFAAS_ENDPOINT="http://${NANOFAAS_ENDPOINT}:8080"
    log "CLI endpoint=${NANOFAAS_ENDPOINT}"
}

deploy_echo_deployment_mode() {
    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fn-echo-deploy
  namespace: ${NAMESPACE}
  labels:
    function: echo-deploy
spec:
  replicas: 1
  selector:
    matchLabels:
      function: echo-deploy
  template:
    metadata:
      labels:
        function: echo-deploy
    spec:
      containers:
      - name: function
        image: ${RUNTIME_IMAGE}
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: fn-echo-deploy
  namespace: ${NAMESPACE}
spec:
  selector:
    function: echo-deploy
  ports:
  - port: 8080
    targetPort: 8080
EOF"
    e2e_wait_for_deployment "${NAMESPACE}" "fn-echo-deploy" 120

    local svc_ip
    svc_ip=$(vm_exec "kubectl get svc fn-echo-deploy -n ${NAMESPACE} -o jsonpath='{.spec.clusterIP}'")
    vm_exec "cat <<EOF > /tmp/echo-deploy.json
{
  \"name\": \"echo-deploy\",
  \"image\": \"${RUNTIME_IMAGE}\",
  \"timeoutMs\": 5000,
  \"concurrency\": 2,
  \"queueSize\": 20,
  \"maxRetries\": 3,
  \"executionMode\": \"DEPLOYMENT\",
  \"endpointUrl\": \"http://${svc_ip}:8080/invoke\"
}
EOF"
    vm_exec "nanofaas fn apply -f /tmp/echo-deploy.json" >/dev/null
}

test_cli_help() {
    local help_out
    help_out=$(vm_exec "nanofaas --help")
    echo "${help_out}" | grep -q "Usage: nanofaas" || fail_now "CLI help output missing usage"
    pass "CLI help"
}

test_cli_function_flow() {
    vm_exec "cat <<EOF > /tmp/${FUNCTION_NAME}.json
{
  \"name\": \"${FUNCTION_NAME}\",
  \"image\": \"${FUNCTION_IMAGE}\",
  \"timeoutMs\": 5000,
  \"concurrency\": 2,
  \"queueSize\": 20,
  \"maxRetries\": 3,
  \"executionMode\": \"DEPLOYMENT\"
}
EOF"

    vm_exec "nanofaas fn apply -f /tmp/${FUNCTION_NAME}.json" >/dev/null || fail_now "fn apply failed"
    vm_exec "nanofaas fn list" | grep -q "${FUNCTION_NAME}" || fail_now "fn list missing ${FUNCTION_NAME}"
    vm_exec "nanofaas fn get ${FUNCTION_NAME}" | grep -q "${FUNCTION_NAME}" || fail_now "fn get missing ${FUNCTION_NAME}"

    local invoke_response request_file
    request_file="/tmp/${FUNCTION_NAME}-invoke.json"
    write_cli_request "${request_file}"
    invoke_response=$(vm_exec "nanofaas invoke ${FUNCTION_NAME} -d '$(cat "${request_file}")'")
    echo "${invoke_response}" | grep -q '"status":"success"' || fail_now "invoke did not succeed"
    if [[ -z "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        echo "${invoke_response}" | grep -q '"message":"hello-from-cli"' || fail_now "invoke did not echo payload"
    fi

    local enqueue_response
    enqueue_response=$(vm_exec "nanofaas enqueue ${FUNCTION_NAME} -d '$(cat "${request_file}")'")
    LAST_EXEC_ID=$(printf '%s\n' "${enqueue_response}" | grep -o '"executionId":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    [[ -n "${LAST_EXEC_ID}" ]] || fail_now "enqueue did not return executionId"
    vm_exec "nanofaas exec get ${LAST_EXEC_ID}" | grep -q "${LAST_EXEC_ID}" || fail_now "exec get missing executionId"

    vm_exec "nanofaas fn delete ${FUNCTION_NAME}" >/dev/null || fail_now "fn delete failed"
    if vm_exec "nanofaas fn list" | grep -q "${FUNCTION_NAME}"; then
        fail_now "${FUNCTION_NAME} still present after delete"
    fi

    pass "CLI function lifecycle"
}

test_cli_k8s_commands() {
    if [[ -n "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        vm_exec "nanofaas k8s pods ${FUNCTION_NAME}" | grep -q "${FUNCTION_NAME}" || fail_now "k8s pods missing ${FUNCTION_NAME}"
        pass "CLI k8s commands"
        return 0
    fi
    deploy_echo_deployment_mode
    vm_exec "nanofaas k8s pods echo-deploy" | grep -q "echo-deploy" || fail_now "k8s pods missing echo-deploy"
    pass "CLI k8s commands"
}

test_cli_platform_lifecycle() {
    local release="nanofaas-platform-e2e"
    local ns="${NAMESPACE}-platform"

    vm_exec "helm uninstall ${release} -n ${ns} >/dev/null 2>&1 || true"
    vm_exec "kubectl delete namespace ${ns} --ignore-not-found=true --wait=true >/dev/null 2>&1 || true"

    local install_output
    install_output=$(vm_exec "nanofaas platform install --release ${release} -n ${ns} --chart \"${REMOTE_HELM_DIR}\" --control-plane-repository ${CONTROL_IMAGE_REPOSITORY} --control-plane-tag ${CONTROL_IMAGE_TAG} --control-plane-pull-policy Always --demos-enabled=false" 2>&1) || fail_now "platform install failed"
    echo "${install_output}" | grep -q "endpoint" || fail_now "platform install did not print endpoint"

    local status_output
    status_output=$(vm_exec "nanofaas platform status -n ${ns}" 2>&1) || fail_now "platform status failed"
    echo "${status_output}" | grep -q $'deployment\tnanofaas-control-plane\t1/1' || fail_now "platform status missing ready deployment"
    echo "${status_output}" | grep -q $'service\tcontrol-plane\tNodePort' || fail_now "platform status missing NodePort service"

    vm_exec "nanofaas platform uninstall --release ${release} -n ${ns}" >/dev/null || fail_now "platform uninstall failed"
    if vm_exec "nanofaas platform status -n ${ns}" >/dev/null 2>&1; then
        fail_now "platform status unexpectedly succeeded after uninstall"
    fi
    vm_exec "kubectl delete namespace ${ns} --ignore-not-found=true --wait=true >/dev/null 2>&1 || true"
    pass "CLI platform lifecycle"
}

print_summary() {
    log "CLI compatibility workflow completed"
    printf '%s\n' "${E2E_TESTS_RUN[@]}"
}

main() {
    log "Starting CLI compatibility workflow"
    resolve_function_selection
    check_prerequisites
    if [[ "${E2E_SKIP_VM_BOOTSTRAP:-false}" != "true" ]]; then
        create_vm
        install_dependencies
        install_k3s
        e2e_setup_local_registry "${LOCAL_REGISTRY}"
    fi
    sync_project
    build_artifacts
    build_primary_function_image
    deploy_platform_primitives
    build_cli
    setup_cli_env
    test_cli_help
    test_cli_function_flow
    test_cli_k8s_commands
    test_cli_platform_lifecycle
    print_summary
}

main "$@"
