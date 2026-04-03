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
NANOFAAS_CLI_SKIP_INSTALL_DIST=${NANOFAAS_CLI_SKIP_INSTALL_DIST:-false}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/e2e-k3s-common.sh"
source "${SCRIPT_DIR}/scenario-manifest.sh"
e2e_set_log_prefix "host-cli-e2e"
vm_exec() { e2e_vm_exec "$@"; }
REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}

KUBECONFIG_HOST=""
CLI_CONFIG=""
PUBLIC_HOST=""
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
    e2e_require_vm_access
    command -v helm >/dev/null 2>&1 || { err "helm not found on host"; exit 1; }
    command -v python3 >/dev/null 2>&1 || { err "python3 not found on host"; exit 1; }
}

create_vm() {
    e2e_ensure_vm_running "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"
}

install_vm_dependencies() {
    e2e_install_vm_dependencies
}

sync_project() {
    e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "${REMOTE_DIR}"
}

build_control_plane_image() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        log "SKIP_BUILD=true, skipping image build/push"
        return
    fi

    log "Building control-plane image in VM (runtime=${CONTROL_PLANE_RUNTIME})..."
    e2e_build_control_plane_artifacts "${REMOTE_DIR}"
    e2e_build_control_plane_image "${REMOTE_DIR}" "${CONTROL_IMAGE}"
    e2e_push_images_to_registry "${CONTROL_IMAGE}"
}

resolve_public_host() {
    PUBLIC_HOST=$(e2e_get_public_host)
    if [[ -z "${PUBLIC_HOST}" ]]; then
        error "Failed to resolve public host"
        exit 1
    fi
}

export_kubeconfig_to_host() {
    log "Exporting kubeconfig from VM to host..."
    KUBECONFIG_HOST="$(mktemp -t nanofaas-kubeconfig.XXXXXX)"
    e2e_export_kubeconfig_to_host "${VM_NAME}" "${KUBECONFIG_HOST}"
}

build_cli_on_host() {
    if [[ "${NANOFAAS_CLI_SKIP_INSTALL_DIST}" == "true" ]]; then
        log "Skipping host CLI build because NANOFAAS_CLI_SKIP_INSTALL_DIST=true"
        if [[ ! -x "${CLI_BIN}" ]]; then
            err "CLI binary not found at ${CLI_BIN}"
            exit 1
        fi
        CLI_CONFIG="$(mktemp -t nanofaas-cli-config.XXXXXX)"
        rm -f "${CLI_CONFIG}"
        return 0
    fi

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
    echo "${install_out}" | grep -q "endpoint[[:space:]]http://${PUBLIC_HOST}:30080" || { err "Unexpected endpoint in install output: ${install_out}"; exit 1; }

    local status_out
    status_out="$(cli platform status -n "${NAMESPACE}")"
    echo "${status_out}" | grep -q $'deployment\tnanofaas-control-plane\t1/1' || { err "Control-plane not ready: ${status_out}"; exit 1; }
    echo "${status_out}" | grep -q $'service\tcontrol-plane\tNodePort' || { err "Service type not NodePort: ${status_out}"; exit 1; }

    curl -fsS "http://${PUBLIC_HOST}:30081/actuator/health" >/dev/null

    cli platform uninstall --release "${RELEASE}" -n "${NAMESPACE}" >/dev/null
    if cli platform status -n "${NAMESPACE}" >/dev/null 2>&1; then
        err "platform status unexpectedly succeeded after uninstall"
        exit 1
    fi
}

main() {
    log "Scenario: host CLI executes Helm against k3s in VM"
    log "VM=${VM_NAME} NAMESPACE=${NAMESPACE} RELEASE=${RELEASE} LOCAL_REGISTRY=${LOCAL_REGISTRY} CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME}"
    if [[ -n "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        log "Selected function: $(scenario_first_function_key)"
    fi

    check_prerequisites
    if [[ "${E2E_SKIP_VM_BOOTSTRAP:-false}" != "true" ]]; then
        create_vm
        install_vm_dependencies
        e2e_install_k3s
        e2e_setup_local_registry "${LOCAL_REGISTRY}"
    fi
    sync_project
    build_control_plane_image
    resolve_public_host
    export_kubeconfig_to_host
    build_cli_on_host
    test_platform_lifecycle_from_host

    log "Host CLI platform lifecycle test: PASSED"
}

main "$@"
