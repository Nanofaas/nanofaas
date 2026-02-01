#!/usr/bin/env bash
set -euo pipefail

VM_NAME=${VM_NAME:-mcfaas-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
REMOTE_DIR=${REMOTE_DIR:-/home/ubuntu/mcFaas}
NAMESPACE=${MCFAAS_E2E_NAMESPACE:-mcfaas-e2e}
KEEP_VM=${KEEP_VM:-false}

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

if ! command -v multipass >/dev/null 2>&1; then
  echo "multipass not found. Install it first." >&2
  exit 1
fi

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
vm_exec "sudo apt-get install -y openjdk-17-jdk"

log "Syncing repository to VM"
vm_exec "rm -rf ${REMOTE_DIR}"
multipass transfer --recursive . "${VM_NAME}:${REMOTE_DIR}"

log "Building JARs in VM"
vm_exec "cd ${REMOTE_DIR} && ./gradlew :control-plane:bootJar :function-runtime:bootJar --no-daemon"

log "Building container images in VM"
vm_exec "cd ${REMOTE_DIR} && sudo docker build -t mcfaas/control-plane:0.5.0 control-plane/"
vm_exec "cd ${REMOTE_DIR} && sudo docker build -t mcfaas/function-runtime:0.5.0 function-runtime/"

log "Installing k3s"
vm_exec "curl -sfL https://get.k3s.io | sudo sh -s - --disable traefik"
vm_exec "for i in \$(seq 1 60); do if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready'; then exit 0; fi; sleep 5; done; echo 'k3s node not ready' >&2; exit 1"
vm_exec "mkdir -p /home/ubuntu/.kube"
vm_exec "sudo cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config"
vm_exec "sudo chown ubuntu:ubuntu /home/ubuntu/.kube/config"

log "Importing images into k3s"
vm_exec "sudo docker save mcfaas/control-plane:0.5.0 -o /tmp/control-plane.tar"
vm_exec "sudo docker save mcfaas/function-runtime:0.5.0 -o /tmp/function-runtime.tar"
vm_exec "sudo k3s ctr images import /tmp/control-plane.tar"
vm_exec "sudo k3s ctr images import /tmp/function-runtime.tar"
vm_exec "sudo rm -f /tmp/control-plane.tar /tmp/function-runtime.tar"

log "Running K8sE2eTest in VM"
vm_exec "cd ${REMOTE_DIR} && KUBECONFIG=/home/ubuntu/.kube/config MCFAAS_E2E_NAMESPACE=${NAMESPACE} ./gradlew :control-plane:test --tests com.mcfaas.controlplane.e2e.K8sE2eTest --no-daemon"
