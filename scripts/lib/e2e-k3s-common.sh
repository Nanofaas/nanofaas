#!/usr/bin/env bash

# Shared helpers for k3s-based E2E scripts.
# Callers are expected to define:
#   - log/warn/error functions (optional)
#   - vm_exec() for remote command execution where required

e2e_log() {
    if declare -F log >/dev/null 2>&1; then
        log "$@"
    else
        echo "[e2e] $*"
    fi
}

e2e_error() {
    if declare -F error >/dev/null 2>&1; then
        error "$@"
    else
        echo "[e2e] $*" >&2
    fi
}

e2e_require_multipass() {
    if ! command -v multipass >/dev/null 2>&1; then
        e2e_error "multipass not found. Install: brew install multipass"
        return 1
    fi
}

e2e_install_k3s() {
    if ! declare -F vm_exec >/dev/null 2>&1; then
        e2e_error "vm_exec function not defined by caller"
        return 1
    fi

    e2e_log "Installing k3s..."
    vm_exec "curl -sfL https://get.k3s.io | sudo sh -s - --disable traefik"

    vm_exec "for i in \$(seq 1 60); do
        if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready'; then
            echo 'k3s node ready'
            exit 0
        fi
        sleep 2
    done
    echo 'k3s node not ready after 120s' >&2
    exit 1"

    vm_exec "mkdir -p /home/ubuntu/.kube"
    vm_exec "sudo cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config"
    vm_exec "sudo chown ubuntu:ubuntu /home/ubuntu/.kube/config"
    vm_exec "chmod 600 /home/ubuntu/.kube/config"

    e2e_log "k3s installed and ready"
}

e2e_setup_local_registry() {
    if ! declare -F vm_exec >/dev/null 2>&1; then
        e2e_error "vm_exec function not defined by caller"
        return 1
    fi

    local registry=${1:-localhost:5000}
    local host=${registry%:*}
    local port=${registry##*:}
    local container_name=${2:-nanofaas-e2e-registry}

    if [[ -z "${host}" || -z "${port}" || "${host}" == "${registry}" ]]; then
        e2e_error "Registry must include host:port (got '${registry}')"
        return 1
    fi

    e2e_log "Starting local registry ${registry}..."
    vm_exec "sudo docker rm -f ${container_name} >/dev/null 2>&1 || true"
    vm_exec "sudo docker run -d --restart unless-stopped --name ${container_name} -p ${port}:5000 registry:2 >/dev/null"

    vm_exec "for i in \$(seq 1 30); do
        if curl -fsS http://${registry}/v2/ >/dev/null 2>&1; then
            echo 'registry ready'
            exit 0
        fi
        sleep 1
    done
    echo 'registry not ready after 30s' >&2
    exit 1"

    e2e_log "Configuring k3s to pull from ${registry}..."
    vm_exec "cat <<EOF | sudo tee /etc/rancher/k3s/registries.yaml >/dev/null
mirrors:
  \"${registry}\":
    endpoint:
      - \"http://${registry}\"
configs:
  \"${registry}\":
    tls:
      insecure_skip_verify: true
EOF"
    vm_exec "sudo systemctl restart k3s"
    vm_exec "for i in \$(seq 1 60); do
        if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready'; then
            echo 'k3s node ready'
            exit 0
        fi
        sleep 2
    done
    echo 'k3s node not ready after k3s restart' >&2
    exit 1"

    e2e_log "Local registry configured for k3s"
}

e2e_push_images_to_registry() {
    if ! declare -F vm_exec >/dev/null 2>&1; then
        e2e_error "vm_exec function not defined by caller"
        return 1
    fi
    if [[ "$#" -eq 0 ]]; then
        e2e_error "e2e_push_images_to_registry requires at least one image"
        return 1
    fi

    local img
    for img in "$@"; do
        vm_exec "sudo docker push ${img}"
    done
    e2e_log "Pushed images to registry"
}

e2e_import_images_to_k3s() {
    if ! declare -F vm_exec >/dev/null 2>&1; then
        e2e_error "vm_exec function not defined by caller"
        return 1
    fi
    if [[ "$#" -eq 0 ]]; then
        e2e_error "e2e_import_images_to_k3s requires at least one image"
        return 1
    fi

    e2e_log "Importing images to k3s..."
    local img
    for img in "$@"; do
        local tarname
        tarname=$(echo "${img}" | tr '/:' '_')
        vm_exec "sudo docker save ${img} -o /tmp/${tarname}.tar"
        vm_exec "sudo k3s ctr images import /tmp/${tarname}.tar"
        vm_exec "sudo rm -f /tmp/${tarname}.tar"
    done
    e2e_log "Images imported to k3s"
}
