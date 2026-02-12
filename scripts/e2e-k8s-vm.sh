#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"

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

log() {
  echo "[k8s-e2e-vm] $*"
}

cleanup() {
  if [[ "${KEEP_VM}" == "true" ]]; then
    log "KEEP_VM=true, skipping VM delete."
    return
  fi
  log "Deleting VM ${VM_NAME}"
  multipass delete "${VM_NAME}" >/dev/null 2>&1 || true
  multipass purge >/dev/null 2>&1 || true
}

trap cleanup EXIT

e2e_require_multipass

if multipass list | awk '{print $1}' | grep -q "^${VM_NAME}$"; then
  echo "VM ${VM_NAME} already exists. Choose a different VM_NAME." >&2
  exit 1
fi

log "Starting multipass VM ${VM_NAME} (cpus=${CPUS}, memory=${MEMORY}, disk=${DISK})"
multipass launch --name "${VM_NAME}" --cpus "${CPUS}" --memory "${MEMORY}" --disk "${DISK}"

vm_exec() {
  multipass exec "${VM_NAME}" -- bash -lc "$*"
}

log "Installing dependencies in VM"
vm_exec "sudo apt-get update -y"
vm_exec "sudo apt-get install -y curl ca-certificates tar unzip"
vm_exec "if ! command -v docker >/dev/null 2>&1; then curl -fsSL https://get.docker.com | sudo sh; fi"
vm_exec "sudo usermod -aG docker ubuntu"
vm_exec "sudo apt-get install -y openjdk-21-jdk"

log "Syncing repository to VM"
vm_exec "rm -rf ${REMOTE_DIR}"
multipass transfer --recursive . "${VM_NAME}:${REMOTE_DIR}"

log "Building JARs in VM"
vm_exec "cd ${REMOTE_DIR} && ./gradlew :control-plane:bootJar :function-runtime:bootJar --no-daemon"

log "Building container images in VM"
vm_exec "cd ${REMOTE_DIR} && sudo docker build -t ${CONTROL_IMAGE} control-plane/"
vm_exec "cd ${REMOTE_DIR} && sudo docker build -t ${RUNTIME_IMAGE} function-runtime/"

log "Installing k3s"
e2e_install_k3s

log "Setting up local registry (${LOCAL_REGISTRY})"
e2e_setup_local_registry "${LOCAL_REGISTRY}"

log "Pushing images to local registry"
e2e_push_images_to_registry "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"

log "Running K8sE2eTest in VM"
vm_exec "cd ${REMOTE_DIR} && KUBECONFIG=/home/ubuntu/.kube/config NANOFAAS_E2E_NAMESPACE=${NAMESPACE} CONTROL_PLANE_IMAGE=${CONTROL_IMAGE} FUNCTION_RUNTIME_IMAGE=${RUNTIME_IMAGE} ./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.e2e.K8sE2eTest --no-daemon"
