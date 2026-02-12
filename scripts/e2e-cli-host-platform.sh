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
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'
log() { echo -e "${GREEN}[host-cli-e2e]${NC} $*"; }
warn() { echo -e "${YELLOW}[host-cli-e2e]${NC} $*"; }
err() { echo -e "${RED}[host-cli-e2e]${NC} $*" >&2; }

KUBECONFIG_HOST=""
CLI_CONFIG=""
VM_IP=""
CLI_BIN="${PROJECT_ROOT}/nanofaas-cli/build/install/nanofaas-cli/bin/nanofaas"

cleanup() {
    local exit_code=$?

    if [[ -n "${CLI_CONFIG}" && -f "${CLI_CONFIG}" ]]; then
        rm -f "${CLI_CONFIG}" || true
    fi
    if [[ -n "${KUBECONFIG_HOST}" && -f "${KUBECONFIG_HOST}" ]]; then
        rm -f "${KUBECONFIG_HOST}" || true
    fi

    if [[ "${KEEP_VM}" == "true" ]]; then
        warn "KEEP_VM=true, VM '${VM_NAME}' preserved"
        warn "  SSH: multipass shell ${VM_NAME}"
        warn "  Delete: multipass delete ${VM_NAME} && multipass purge"
        return
    fi

    log "Cleaning up VM ${VM_NAME}..."
    multipass delete "${VM_NAME}" >/dev/null 2>&1 || true
    multipass purge >/dev/null 2>&1 || true
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
    if multipass info "${VM_NAME}" >/dev/null 2>&1; then
        log "VM ${VM_NAME} already exists, reusing"
        multipass start "${VM_NAME}" >/dev/null 2>&1 || true
        return
    fi
    log "Creating VM ${VM_NAME} (cpus=${CPUS}, memory=${MEMORY}, disk=${DISK})..."
    multipass launch --name "${VM_NAME}" --cpus "${CPUS}" --memory "${MEMORY}" --disk "${DISK}"
}

vm_exec() {
    multipass exec "${VM_NAME}" -- bash -lc "export KUBECONFIG=/home/ubuntu/.kube/config; $*"
}

install_vm_dependencies() {
    log "Installing dependencies in VM..."
    vm_exec "sudo apt-get update -y"
    vm_exec "sudo apt-get install -y curl ca-certificates tar unzip openjdk-21-jdk"
    vm_exec "if ! command -v docker >/dev/null 2>&1; then curl -fsSL https://get.docker.com | sudo sh; fi"
    vm_exec "sudo usermod -aG docker ubuntu"
}

sync_project() {
    log "Syncing project to VM..."
    vm_exec "rm -rf /home/ubuntu/nanofaas"
    multipass transfer --recursive "${PROJECT_ROOT}" "${VM_NAME}:/home/ubuntu/nanofaas"
}

build_control_plane_image() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        log "SKIP_BUILD=true, skipping image build/push"
        return
    fi

    log "Building control-plane image in VM..."
    vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :control-plane:bootJar --no-daemon -q"
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${CONTROL_IMAGE} -f control-plane/Dockerfile control-plane/"
    e2e_push_images_to_registry "${CONTROL_IMAGE}"
}

extract_vm_ip() {
    VM_IP=$(multipass info "${VM_NAME}" --format csv | tail -1 | cut -d, -f3)
    if [[ -z "${VM_IP}" ]]; then
        err "Failed to resolve VM IP"
        exit 1
    fi
}

export_kubeconfig_to_host() {
    log "Exporting kubeconfig from VM to host..."
    KUBECONFIG_HOST="$(mktemp -t nanofaas-kubeconfig.XXXXXX)"
    multipass transfer "${VM_NAME}:/home/ubuntu/.kube/config" "${KUBECONFIG_HOST}"

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
    log "VM=${VM_NAME} NAMESPACE=${NAMESPACE} RELEASE=${RELEASE} LOCAL_REGISTRY=${LOCAL_REGISTRY}"

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
