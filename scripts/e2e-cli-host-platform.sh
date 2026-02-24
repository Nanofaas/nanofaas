#!/usr/bin/env bash
set -euo pipefail

VM_NAME=${VM_NAME:-nanofaas-host-cli-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas-host-cli-e2e}
RELEASE=${RELEASE:-nanofaas-host-cli-e2e}
KEEP_VM=${KEEP_VM:-false}
SKIP_BUILD=${SKIP_BUILD:-false}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
TAG=${TAG:-e2e}
CONTROL_IMAGE=${CONTROL_PLANE_IMAGE:-${LOCAL_REGISTRY}/nanofaas/control-plane:${TAG}}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "host-cli-e2e"
vm_exec() { e2e_vm_exec "$@"; }

KUBECONFIG_HOST=""
CLI_CONFIG=""
VM_IP=""
CLI_BIN="${PROJECT_ROOT}/nanofaas-cli/build/install/nanofaas-cli/bin/nanofaas"

cleanup() {
    local exit_code=$?
    [[ -n "${CLI_CONFIG}" && -f "${CLI_CONFIG}" ]] && rm -f "${CLI_CONFIG}" || true
    [[ -n "${KUBECONFIG_HOST}" && -f "${KUBECONFIG_HOST}" ]] && rm -f "${KUBECONFIG_HOST}" || true
    e2e_cleanup_vm
    exit "${exit_code}"
}
trap cleanup EXIT

check_prerequisites() {
    e2e_require_multipass
    command -v helm >/dev/null 2>&1 || { err "helm not found on host"; exit 1; }
    command -v python3 >/dev/null 2>&1 || { err "python3 not found on host"; exit 1; }
    [[ -f "${PROJECT_ROOT}/nanofaas-cli/build/install/nanofaas-cli/bin/nanofaas" ]] || true
}

create_vm() {
    e2e_ensure_vm_running "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"
}

install_vm_dependencies() {
    e2e_install_vm_dependencies
}

sync_project() {
    e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "/home/ubuntu/nanofaas"
}

build_control_plane_image() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        log "SKIP_BUILD=true, skipping image build/push"
        return
    fi

    log "Building control-plane image in VM (runtime=${CONTROL_PLANE_RUNTIME})..."
    e2e_build_control_plane_artifacts "/home/ubuntu/nanofaas"
    e2e_build_control_plane_image "/home/ubuntu/nanofaas" "${CONTROL_IMAGE}"
    e2e_push_images_to_registry "${CONTROL_IMAGE}"
}

extract_vm_ip() {
    VM_IP=$(e2e_get_vm_ip)
    if [[ -z "${VM_IP}" ]]; then
        error "Failed to resolve VM IP"
        exit 1
    fi
}

export_kubeconfig_to_host() {
    log "Exporting kubeconfig from VM to host..."
    KUBECONFIG_HOST="$(mktemp -t nanofaas-kubeconfig.XXXXXX)"
    e2e_copy_from_vm "${VM_NAME}" "/home/ubuntu/.kube/config" "${KUBECONFIG_HOST}"

    python3 - "${KUBECONFIG_HOST}" "${VM_IP}" <<'PY'
import pathlib
import sys

cfg_path = pathlib.Path(sys.argv[1])
vm_ip = sys.argv[2]
text = cfg_path.read_text()
text = text.replace("https://127.0.0.1:6443", f"https://{vm_ip}:6443")
text = text.replace("https://localhost:6443", f"https://{vm_ip}:6443")
cfg_path.write_text(text)
PY
}

build_cli_on_host() {
    log "Building nanofaas-cli on host..."
    (cd "${PROJECT_ROOT}" && ./gradlew :nanofaas-cli:installDist --no-daemon -q)
    if [[ ! -x "${CLI_BIN}" ]]; then
        err "CLI binary not found at ${CLI_BIN}"
        exit 1
    fi
    CLI_CONFIG="$(mktemp -t nanofaas-cli-config.XXXXXX)"
    rm -f "${CLI_CONFIG}"
}

cli() {
    KUBECONFIG="${KUBECONFIG_HOST}" "${CLI_BIN}" --config "${CLI_CONFIG}" "$@"
}

test_platform_lifecycle_from_host() {
    log "Running platform lifecycle from host CLI..."

    local install_out
    install_out="$(cli platform install \
        --release "${RELEASE}" \
        -n "${NAMESPACE}" \
        --chart "${PROJECT_ROOT}/helm/nanofaas" \
        --control-plane-repository "${CONTROL_IMAGE%:*}" \
        --control-plane-tag "${CONTROL_IMAGE##*:}" \
        --control-plane-pull-policy Always \
        --demos-enabled=false)"

    echo "${install_out}" | grep -q $'release\t'"${RELEASE}" || { err "Missing release in install output"; exit 1; }
    echo "${install_out}" | grep -q $'namespace\t'"${NAMESPACE}" || { err "Missing namespace in install output"; exit 1; }
    echo "${install_out}" | grep -q "endpoint[[:space:]]http://${VM_IP}:30080" || { err "Unexpected endpoint in install output: ${install_out}"; exit 1; }

    local status_out
    status_out="$(cli platform status -n "${NAMESPACE}")"
    echo "${status_out}" | grep -q $'deployment\tnanofaas-control-plane\t1/1' || { err "Control-plane not ready: ${status_out}"; exit 1; }
    echo "${status_out}" | grep -q $'service\tcontrol-plane\tNodePort' || { err "Service type not NodePort: ${status_out}"; exit 1; }

    curl -fsS "http://${VM_IP}:30081/actuator/health" >/dev/null

    cli platform uninstall --release "${RELEASE}" -n "${NAMESPACE}" >/dev/null
    if cli platform status -n "${NAMESPACE}" >/dev/null 2>&1; then
        err "platform status unexpectedly succeeded after uninstall"
        exit 1
    fi
}

main() {
    log "Scenario: host CLI executes Helm against k3s in VM"
    log "VM=${VM_NAME} NAMESPACE=${NAMESPACE} RELEASE=${RELEASE} LOCAL_REGISTRY=${LOCAL_REGISTRY} CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME}"

    check_prerequisites
    create_vm
    install_vm_dependencies
    e2e_install_k3s
    e2e_setup_local_registry "${LOCAL_REGISTRY}"
    sync_project
    build_control_plane_image
    extract_vm_ip
    export_kubeconfig_to_host
    build_cli_on_host
    test_platform_lifecycle_from_host

    log "Host CLI platform lifecycle test: PASSED"
}

main "$@"
