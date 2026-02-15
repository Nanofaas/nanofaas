#!/usr/bin/env bash
set -euo pipefail

#
# E2E Load Test (registry images): VM + k3s + Helm deploy + load test
#
# Usage:
#   ./scripts/e2e-loadtest-registry.sh
#   BASE_IMAGE_TAG=v0.11.3 ./scripts/e2e-loadtest-registry.sh
#   BASE_IMAGE_TAG=v0.11.3 ARM64_TAG_SUFFIX=-arm64 ./scripts/e2e-loadtest-registry.sh
#   IMAGE_TAG_OVERRIDE=v0.11.3-arm64 ./scripts/e2e-loadtest-registry.sh
#   SKIP_GRAFANA=true ./scripts/e2e-loadtest-registry.sh
#
# Notes:
#   - Keeps the existing VM by default (KEEP_VM=true).
#   - Uses GHCR images instead of local build/push.
#   - Detects VM architecture and uses BASE_IMAGE_TAG or BASE_IMAGE_TAG+ARM64_TAG_SUFFIX.
#

VM_NAME=${VM_NAME:-nanofaas-e2e}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas}
KEEP_VM=${KEEP_VM:-true}
SKIP_GRAFANA=${SKIP_GRAFANA:-false}
BASE_IMAGE_TAG=${BASE_IMAGE_TAG:-v0.11.3}
ARM64_TAG_SUFFIX=${ARM64_TAG_SUFFIX:--arm64}
IMAGE_TAG_OVERRIDE=${IMAGE_TAG_OVERRIDE:-}
IMAGE_REPO=${IMAGE_REPO:-ghcr.io/miciav/nanofaas}
EXEC_IMAGE_PREFIX=${EXEC_IMAGE_PREFIX:-}
CURL_IMAGE=${CURL_IMAGE:-docker.io/curlimages/curl:latest}
HELM_TIMEOUT=${HELM_TIMEOUT:-10m}
EXPECTED_READY_PODS=${EXPECTED_READY_PODS:-auto}
EXPECTED_FUNCTIONS=${EXPECTED_FUNCTIONS:-8}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "loadtest-registry"
vm_exec() { e2e_vm_exec "$@"; }

RESOLVED_TAG=""
RESOLVED_EXEC_PREFIX=""

check_prerequisites() {
    e2e_require_multipass
    if ! command -v k6 &>/dev/null; then
        err "k6 is not installed. Install it from https://grafana.com/docs/k6/latest/set-up/install-k6/"
        exit 1
    fi
}

cleanup() {
    local exit_code=$?
    e2e_cleanup_vm
    exit "${exit_code}"
}
trap cleanup EXIT

create_vm() {
    e2e_ensure_vm_running "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"
}

install_k3s() {
    if [[ "$(e2e_vm_has_command k3s)" == "yes" ]]; then
        log "k3s already installed, skipping..."
        return
    fi
    e2e_install_k3s
}

install_deps() {
    if [[ "$(e2e_vm_has_command docker)" == "yes" ]] \
        && [[ "$(e2e_vm_has_command java)" == "yes" ]] \
        && [[ "$(e2e_vm_has_command helm)" == "yes" ]]; then
        log "Dependencies already present, skipping..."
        return
    fi
    e2e_install_vm_dependencies true
}

sync_project() {
    e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "/home/ubuntu/nanofaas"
}

resolve_image_tag() {
    if [[ -n "${IMAGE_TAG_OVERRIDE}" ]]; then
        RESOLVED_TAG="${IMAGE_TAG_OVERRIDE}"
        info "Using explicit IMAGE_TAG_OVERRIDE=${RESOLVED_TAG}"
        return
    fi

    local vm_arch
    vm_arch=$(vm_exec "uname -m")
    case "${vm_arch}" in
        aarch64|arm64)
            RESOLVED_TAG="${BASE_IMAGE_TAG}${ARM64_TAG_SUFFIX}"
            ;;
        *)
            RESOLVED_TAG="${BASE_IMAGE_TAG}"
            ;;
    esac

    info "VM architecture=${vm_arch}, image tag=${RESOLVED_TAG}"
}

resolve_exec_image_prefix() {
    if [[ -n "${EXEC_IMAGE_PREFIX}" ]]; then
        RESOLVED_EXEC_PREFIX="${EXEC_IMAGE_PREFIX}"
        info "Using explicit EXEC_IMAGE_PREFIX=${RESOLVED_EXEC_PREFIX}"
        return
    fi

    log "Resolving exec demo image prefix (exec|bash)..."
    local probe

    probe=$(vm_exec "if sudo k3s ctr images pull ${IMAGE_REPO}/exec-word-stats:${RESOLVED_TAG} >/dev/null 2>&1; then echo yes; else echo no; fi")
    if [[ "${probe}" == "yes" ]]; then
        RESOLVED_EXEC_PREFIX="exec"
        info "Resolved exec demo prefix: exec"
        return
    fi

    probe=$(vm_exec "if sudo k3s ctr images pull ${IMAGE_REPO}/bash-word-stats:${RESOLVED_TAG} >/dev/null 2>&1; then echo yes; else echo no; fi")
    if [[ "${probe}" == "yes" ]]; then
        RESOLVED_EXEC_PREFIX="bash"
        info "Resolved exec demo prefix: bash"
        return
    fi

    err "Cannot resolve exec demo image prefix for tag ${RESOLVED_TAG}"
    err "Set EXEC_IMAGE_PREFIX=exec or EXEC_IMAGE_PREFIX=bash and retry."
    exit 1
}

pull_registry_images() {
    local images=(
        "${IMAGE_REPO}/control-plane:${RESOLVED_TAG}"
        "${IMAGE_REPO}/java-word-stats:${RESOLVED_TAG}"
        "${IMAGE_REPO}/java-json-transform:${RESOLVED_TAG}"
        "${IMAGE_REPO}/python-word-stats:${RESOLVED_TAG}"
        "${IMAGE_REPO}/python-json-transform:${RESOLVED_TAG}"
        "${IMAGE_REPO}/${RESOLVED_EXEC_PREFIX}-word-stats:${RESOLVED_TAG}"
        "${IMAGE_REPO}/${RESOLVED_EXEC_PREFIX}-json-transform:${RESOLVED_TAG}"
        "${IMAGE_REPO}/java-lite-word-stats:${RESOLVED_TAG}"
        "${IMAGE_REPO}/java-lite-json-transform:${RESOLVED_TAG}"
        "${CURL_IMAGE}"
    )

    log "Pulling registry images in VM (k3s/containerd)..."
    for image in "${images[@]}"; do
        log "  pull ${image}"
        vm_exec "sudo k3s ctr images pull ${image}"
    done
}

dump_helm_diagnostics() {
    warn "Helm install failed. Collecting diagnostics from namespace ${NAMESPACE}..."
    vm_exec "kubectl get pods -n ${NAMESPACE} -o wide" || true
    vm_exec "kubectl get jobs -n ${NAMESPACE}" || true
    vm_exec "kubectl get deploy -n ${NAMESPACE}" || true
    vm_exec "kubectl describe deployment nanofaas-control-plane -n ${NAMESPACE}" || true
    vm_exec "kubectl get events -n ${NAMESPACE} --sort-by=.metadata.creationTimestamp | tail -n 120" || true
    vm_exec "pod=\$(kubectl get pods -n ${NAMESPACE} -l app.kubernetes.io/name=nanofaas-control-plane -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); if [ -n \"\$pod\" ]; then kubectl logs -n ${NAMESPACE} \"\$pod\" --tail=200; fi" || true
    vm_exec "kubectl logs -n ${NAMESPACE} job/nanofaas-register-demos --tail=200" || true
}

helm_install_registry() {
    log "Installing nanofaas via Helm using registry images..."

    vm_exec "helm uninstall nanofaas --namespace ${NAMESPACE} 2>/dev/null || true"
    vm_exec "kubectl delete namespace ${NAMESPACE} --ignore-not-found --wait=true 2>/dev/null || true"
    sleep 3

    vm_exec "cat > /tmp/e2e-values-registry.yaml << ENDVALUES
namespace:
  create: false
  name: ${NAMESPACE}

controlPlane:
  image:
    repository: ${IMAGE_REPO}/control-plane
    tag: ${RESOLVED_TAG}
    pullPolicy: IfNotPresent
  service:
    type: NodePort
    ports:
      http: 8080
      actuator: 8081
    nodePorts:
      http: 30080
      actuator: 30081
  extraEnv:
    - name: KUBERNETES_TRUST_CERTIFICATES
      value: \"true\"
    - name: SYNC_QUEUE_ADMISSION_ENABLED
      value: \"false\"

prometheus:
  create: true
  service:
    type: NodePort
    port: 9090
    nodePort: 30090
  pvc:
    enabled: false

demos:
  enabled: true
  functions:
    - name: word-stats-java
      image: ${IMAGE_REPO}/java-word-stats:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: json-transform-java
      image: ${IMAGE_REPO}/java-json-transform:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: word-stats-python
      image: ${IMAGE_REPO}/python-word-stats:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: json-transform-python
      image: ${IMAGE_REPO}/python-json-transform:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: word-stats-exec
      image: ${IMAGE_REPO}/${RESOLVED_EXEC_PREFIX}-word-stats:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      runtimeMode: STDIO
    - name: json-transform-exec
      image: ${IMAGE_REPO}/${RESOLVED_EXEC_PREFIX}-json-transform:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      runtimeMode: STDIO
    - name: word-stats-java-lite
      image: ${IMAGE_REPO}/java-lite-word-stats:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: json-transform-java-lite
      image: ${IMAGE_REPO}/java-lite-json-transform:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
  registerJob:
    image: ${CURL_IMAGE}
ENDVALUES"

    if ! vm_exec "helm upgrade --install nanofaas /home/ubuntu/nanofaas/helm/nanofaas \
        -f /tmp/e2e-values-registry.yaml \
        --namespace ${NAMESPACE} \
        --create-namespace \
        --wait --timeout ${HELM_TIMEOUT}"; then
        dump_helm_diagnostics
        exit 1
    fi
}

verify_deployment() {
    log "Verifying deployment..."
    vm_exec "kubectl rollout status deployment/nanofaas-control-plane -n ${NAMESPACE} --timeout=180s"

    vm_exec "for i in \$(seq 1 90); do
        snapshot=\$(kubectl get pods -n ${NAMESPACE} --no-headers 2>/dev/null || true)
        if [ -z \"\${snapshot}\" ]; then
            echo \"  pods ready: 0/0 (waiting for pod creation)\"
            sleep 5
            continue
        fi

        total=\$(echo \"\${snapshot}\" | awk '\$3 != \"Completed\" {c++} END {print c+0}')
        ready=\$(echo \"\${snapshot}\" | awk '\$3 == \"Running\" {split(\$2,a,\"/\"); if (a[1] == a[2]) c++} END {print c+0}')
        not_ready=\$((total - ready))
        deploy_snapshot=\$(kubectl get deploy -n ${NAMESPACE} --no-headers 2>/dev/null || true)
        expected_mode=\"${EXPECTED_READY_PODS}\"
        if [ \"\${expected_mode}\" = \"auto\" ]; then
            expected=\$(echo \"\${deploy_snapshot}\" | awk 'NF >= 2 {split(\$2,a,\"/\"); desired=a[2]+0; if (desired > 0) s += desired} END {print s+0}')
        else
            expected=\"\${expected_mode}\"
        fi
        case \"\${expected}\" in
            ''|*[!0-9]*)
                expected=0
                ;;
        esac
        zero_replica=\$(echo \"\${deploy_snapshot}\" | awk 'NF >= 2 {split(\$2,a,\"/\"); if ((a[2]+0) == 0) c++} END {print c+0}')

        echo \"  pods ready: \${ready}/\${total} (not-ready: \${not_ready}, expected: \${expected}, zero-replica deploys: \${zero_replica})\"
        if [ -n \"\${deploy_snapshot}\" ]; then
            echo \"\${deploy_snapshot}\" | awk 'NF >= 2 {printf \"    DEPLOY %-28s READY=%-7s UP-TO-DATE=%-4s AVAILABLE=%-4s\\n\", \$1, \$2, \$3, \$4}'
        fi
        echo \"\${snapshot}\" | awk '\$3 != \"Completed\" {
            split(\$2,a,\"/\");
            state=(\$3 == \"Running\" && a[1] == a[2]) ? \"READY\" : \"NOT_READY\";
            printf \"    %-9s %-12s %-8s %s\\n\", state, \$3, \$2, \$1
        }'

        if [ \"\${ready}\" -ge \"\${expected}\" ]; then exit 0; fi
        sleep 5
    done
    echo \"Not all expected pods became ready\" >&2
    kubectl get pods -n ${NAMESPACE} -o wide
    exit 1"

    vm_exec "kubectl get pods -n ${NAMESPACE}"
}

verify_registered_functions() {
    log "Verifying function registration..."
    vm_exec "for i in \$(seq 1 60); do
        count=\$(curl -sf http://127.0.0.1:30080/v1/functions \
            | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)
        echo \"  functions: \${count}/${EXPECTED_FUNCTIONS}\"
        if [ \"\${count}\" -ge ${EXPECTED_FUNCTIONS} ]; then
            exit 0
        fi
        sleep 2
    done
    echo \"Expected at least ${EXPECTED_FUNCTIONS} functions but registration did not complete\" >&2
    echo \"Current /v1/functions response:\"
    curl -s http://127.0.0.1:30080/v1/functions || true
    echo
    echo \"Control-plane logs (tail):\" >&2
    pod=\$(kubectl get pods -n ${NAMESPACE} -o name | grep control-plane | head -1)
    if [ -n \"\${pod}\" ]; then
        kubectl logs -n ${NAMESPACE} \"\${pod}\" --tail=120 || true
    fi
    exit 1"
}

run_loadtest() {
    log "Starting load test suite (e2e-loadtest.sh)..."
    VM_NAME="${VM_NAME}" \
    SKIP_GRAFANA="${SKIP_GRAFANA}" \
    "${SCRIPT_DIR}/e2e-loadtest.sh"
}

print_summary() {
    local vm_ip
    vm_ip=$(e2e_get_vm_ip) || vm_ip="<VM_IP>"

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "      REGISTRY-BASED LOAD TEST COMPLETE"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""
    log "VM: ${VM_NAME} (${vm_ip})"
    log "Image repo: ${IMAGE_REPO}"
    log "Image tag:  ${RESOLVED_TAG}"
    log "Exec image prefix: ${RESOLVED_EXEC_PREFIX}"
    log ""
    log "API:        http://${vm_ip}:30080/v1/functions"
    log "Prometheus: http://${vm_ip}:30090"
    log ""
}

main() {
    log "Starting registry-based E2E load test..."
    log "  VM=${VM_NAME} CPUS=${CPUS} MEM=${MEMORY} DISK=${DISK} KEEP_VM=${KEEP_VM}"
    log "  IMAGE_REPO=${IMAGE_REPO} BASE_IMAGE_TAG=${BASE_IMAGE_TAG} ARM64_TAG_SUFFIX=${ARM64_TAG_SUFFIX}"
    log "  CURL_IMAGE=${CURL_IMAGE}"
    log "  HELM_TIMEOUT=${HELM_TIMEOUT}"
    log ""

    check_prerequisites
    create_vm
    install_deps
    install_k3s
    sync_project
    resolve_image_tag
    resolve_exec_image_prefix
    pull_registry_images
    helm_install_registry
    verify_deployment
    verify_registered_functions
    run_loadtest
    print_summary
}

main "$@"
