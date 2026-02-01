#!/usr/bin/env bash
set -euo pipefail

# Configuration with defaults
VM_NAME=${VM_NAME:-mcfaas-k3s-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-mcfaas-e2e}
CONTROL_IMAGE="mcfaas/control-plane:e2e"
RUNTIME_IMAGE="mcfaas/function-runtime:e2e"
KEEP_VM=${KEEP_VM:-false}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log() { echo -e "${GREEN}[k3s-e2e]${NC} $*"; }
warn() { echo -e "${YELLOW}[k3s-e2e]${NC} $*"; }
error() { echo -e "${RED}[k3s-e2e]${NC} $*" >&2; }

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
