#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "k8s-e2e-vm"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

VM_NAME=${VM_NAME:-nanofaas-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
REMOTE_DIR=${REMOTE_DIR:-/home/ubuntu/nanofaas}
NAMESPACE=${NANOFAAS_E2E_NAMESPACE:-nanofaas-e2e}
KEEP_VM=${KEEP_VM:-false}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
CONTROL_IMAGE=${CONTROL_PLANE_IMAGE:-${LOCAL_REGISTRY}/nanofaas/control-plane:e2e}
RUNTIME_IMAGE=${FUNCTION_RUNTIME_IMAGE:-${LOCAL_REGISTRY}/nanofaas/function-runtime:e2e}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}

# Note: e2e-k8s-vm.sh uses vm_exec without KUBECONFIG (the common helper sets it)
vm_exec() { e2e_vm_exec "$@"; }

cleanup() {
  local exit_code=$?
  e2e_cleanup_vm
  exit "${exit_code}"
}

trap cleanup EXIT

e2e_require_multipass

log "Starting multipass VM ${VM_NAME} (cpus=${CPUS}, memory=${MEMORY}, disk=${DISK})"
e2e_ensure_vm_running "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"

log "Installing dependencies in VM"
e2e_install_vm_dependencies

log "Syncing repository to VM"
e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "${REMOTE_DIR}"

log "Building runtime-aware control-plane artifacts in VM (runtime=${CONTROL_PLANE_RUNTIME})"
e2e_build_control_plane_artifacts "${REMOTE_DIR}"

log "Building container images in VM"
e2e_build_control_plane_image "${REMOTE_DIR}" "${CONTROL_IMAGE}"
e2e_build_function_runtime_image "${REMOTE_DIR}" "${RUNTIME_IMAGE}"

log "Installing k3s"
e2e_install_k3s

log "Setting up local registry (${LOCAL_REGISTRY})"
e2e_setup_local_registry "${LOCAL_REGISTRY}"

log "Pushing images to local registry"
e2e_push_images_to_registry "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"

log "Running K8sE2eTest in VM"
vm_exec "cd ${REMOTE_DIR} && KUBECONFIG=/home/ubuntu/.kube/config NANOFAAS_E2E_NAMESPACE=${NAMESPACE} CONTROL_PLANE_IMAGE=${CONTROL_IMAGE} FUNCTION_RUNTIME_IMAGE=${RUNTIME_IMAGE} ./gradlew -PrunE2e :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.e2e.K8sE2eTest --no-daemon"
