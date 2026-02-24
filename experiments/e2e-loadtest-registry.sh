#!/usr/bin/env bash
set -euo pipefail

#
# E2E Load Test (registry images): VM + k3s + Helm deploy + k6 load test
#
# End-to-end performance benchmark that provisions a Multipass VM with k3s,
# deploys nanofaas from pre-built GHCR container images, runs k6 load tests
# against all 8 demo functions (4 runtimes x 2 workloads), and produces a
# comparison summary table at the end.
#
# Runtimes tested:
#   - Java (Spring Boot SDK)     word-stats-java, json-transform-java
#   - Java (Lite SDK, no Spring) word-stats-java-lite, json-transform-java-lite
#   - Python (FastAPI SDK)       word-stats-python, json-transform-python
#   - Exec/Bash (STDIO watchdog) word-stats-exec, json-transform-exec
#
# Pipeline stages:
#   1. check_prerequisites      Verify multipass and k6 are installed
#   2. create_vm                Create or reuse a Multipass VM
#   3. install_deps             Install docker, java, helm in VM (idempotent)
#   4. install_k3s              Install k3s in VM (idempotent)
#   5. sync_project             Rsync Helm chart and k6 scripts to VM
#   6. resolve_image_tag        Detect VM arch, compute image tag (e.g. v0.12.0-arm64)
#   7. resolve_exec_image_prefix Probe registry for "exec-" or "bash-" image prefix
#   8. pull_registry_images     Pull all 9 container images + curl into k3s/containerd
#   9. helm_install_registry    Helm install nanofaas with generated values
#  10. verify_deployment        Wait for all pods to become Ready
#  11. verify_registered_functions  Poll /v1/functions until 8 functions registered
#  12. run_loadtest             Delegate to e2e-loadtest.sh (k6 runs + per-test report)
#  13. print_summary            Print side-by-side comparison tables across runtimes
#
# Usage:
#   # Default (v0.12.0, auto-detect ARM64):
#   ./scripts/e2e-loadtest-registry.sh
#
#   # Summary only from existing k6/results artifacts (no deploy/loadtest):
#   ./scripts/e2e-loadtest-registry.sh --summary-only
#
#   # Explicit version:
#   BASE_IMAGE_TAG=v0.12.0 ./scripts/e2e-loadtest-registry.sh
#
#   # Explicit full tag (skip arch detection):
#   IMAGE_TAG_OVERRIDE=v0.12.0-arm64 ./scripts/e2e-loadtest-registry.sh
#
#   # Skip Grafana dashboard:
#   SKIP_GRAFANA=true ./scripts/e2e-loadtest-registry.sh
#
#   # Custom VM resources:
#   CPUS=8 MEMORY=16G DISK=50G ./scripts/e2e-loadtest-registry.sh
#
# Environment variables:
#   VM_NAME              Multipass VM name                  (default: nanofaas-e2e)
#   CPUS                 VM CPU count                       (default: 4)
#   MEMORY               VM memory                          (default: 8G)
#   DISK                 VM disk size                       (default: 30G)
#   NAMESPACE            Kubernetes namespace               (default: nanofaas)
#   KEEP_VM              Keep VM after test (true|false)    (default: true)
#   SKIP_GRAFANA         Skip Grafana startup               (default: false)
#   BASE_IMAGE_TAG       Base image version tag             (default: v0.12.0)
#   ARM64_TAG_SUFFIX     Suffix appended on ARM64 VMs       (default: -arm64)
#   TAG_SUFFIX           Explicit image suffix override      (default: empty; e.g. arm64|amd64)
#   IMAGE_TAG_OVERRIDE   Skip arch detection, use this tag  (default: empty)
#   IMAGE_REPO           GHCR image repository prefix       (default: ghcr.io/miciav/nanofaas)
#   EXEC_IMAGE_PREFIX    Force "exec" or "bash" prefix      (default: auto-detected)
#   CURL_IMAGE           Curl image for register job        (default: docker.io/curlimages/curl:latest)
#   HELM_TIMEOUT         Helm install timeout               (default: 10m)
#   EXPECTED_READY_PODS  Expected ready pods (int|auto)     (default: auto)
#   EXPECTED_FUNCTIONS   Expected registered functions       (default: 8)
#   SUMMARY_ONLY         Skip deploy/loadtest, print summary from existing results (default: false)
#   REFRESH_SUMMARY_METRICS  In summary-only, refresh Prom/K8s inputs from VM      (default: true)
#   RESULTS_DIR_OVERRIDE Override k6 results directory path                          (default: k6/results)
#
# Prerequisites:
#   - macOS or Linux host with multipass installed
#   - k6 installed (https://grafana.com/docs/k6/latest/set-up/install-k6/)
#   - Images already pushed to GHCR for the chosen tag
#   - Docker (optional, for Grafana dashboard)
#
# Output:
#   - Per-function k6 logs and JSON summaries in k6/results/
#   - Side-by-side comparison tables per workload (word-stats, json-transform)
#   - Aggregate runtime comparison with delta% vs Java (Spring) baseline
#   - Overall winners for avg latency, throughput, and p95
#

VM_NAME=${VM_NAME:-nanofaas-e2e}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas}
KEEP_VM=${KEEP_VM:-true}
SKIP_GRAFANA=${SKIP_GRAFANA:-false}
BASE_IMAGE_TAG=${BASE_IMAGE_TAG:-v0.12.0}
ARM64_TAG_SUFFIX=${ARM64_TAG_SUFFIX:--arm64}
TAG_SUFFIX=${TAG_SUFFIX:-}
IMAGE_TAG_OVERRIDE=${IMAGE_TAG_OVERRIDE:-}
IMAGE_REPO=${IMAGE_REPO:-ghcr.io/miciav/nanofaas}
EXEC_IMAGE_PREFIX=${EXEC_IMAGE_PREFIX:-}
CURL_IMAGE=${CURL_IMAGE:-docker.io/curlimages/curl:latest}
HELM_TIMEOUT=${HELM_TIMEOUT:-10m}
EXPECTED_READY_PODS=${EXPECTED_READY_PODS:-auto}
EXPECTED_FUNCTIONS=${EXPECTED_FUNCTIONS:-8}
SUMMARY_ONLY=${SUMMARY_ONLY:-false}
REFRESH_SUMMARY_METRICS=${REFRESH_SUMMARY_METRICS:-true}
RESULTS_DIR_OVERRIDE=${RESULTS_DIR_OVERRIDE:-}
LOADTEST_WORKLOADS=${LOADTEST_WORKLOADS:-word-stats,json-transform}
LOADTEST_RUNTIMES=${LOADTEST_RUNTIMES:-java,java-lite,python,exec}
INVOCATION_MODE=${INVOCATION_MODE:-sync}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}
K6_STAGE_SEQUENCE=${K6_STAGE_SEQUENCE:-}
K6_PAYLOAD_MODE=${K6_PAYLOAD_MODE:-legacy-random}
K6_PAYLOAD_POOL_SIZE=${K6_PAYLOAD_POOL_SIZE:-5000}
INTERACTIVE=false

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${PROJECT_ROOT}/scripts/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "loadtest-registry"
vm_exec() { e2e_vm_exec "$@"; }

RESOLVED_TAG=""         # Computed in resolve_image_tag()
RESOLVED_EXEC_PREFIX="" # Computed in resolve_exec_image_prefix()
LOADTEST_START_EPOCH=0
LOADTEST_END_EPOCH=0

show_help() {
    cat <<'EOF'
Usage:
  ./scripts/e2e-loadtest-registry.sh [--interactive] [--summary-only] [--no-refresh-summary-metrics] [--help|-h]

Modes:
  default:
    Provision VM + deploy nanofaas + run load tests + print summary.
  --summary-only:
    Skip provisioning/deploy/loadtest and render final tables from existing artifacts.

Options:
  --interactive                  Open interactive configurator (uv + questionary).
  --summary-only                 Render only the final comparison summary.
  --no-refresh-summary-metrics   In --summary-only mode, do not query VM/Prometheus/Kubernetes.
  --help, -h                     Show this help.

Useful env vars:
  VM_NAME, CPUS, MEMORY, DISK, NAMESPACE, KEEP_VM
  SKIP_GRAFANA, BASE_IMAGE_TAG, ARM64_TAG_SUFFIX, IMAGE_TAG_OVERRIDE
  TAG_SUFFIX
  IMAGE_REPO, EXEC_IMAGE_PREFIX, CURL_IMAGE, HELM_TIMEOUT
  EXPECTED_READY_PODS, EXPECTED_FUNCTIONS
  LOADTEST_WORKLOADS, LOADTEST_RUNTIMES, INVOCATION_MODE, K6_STAGE_SEQUENCE
  K6_PAYLOAD_MODE, K6_PAYLOAD_POOL_SIZE
  SUMMARY_ONLY, REFRESH_SUMMARY_METRICS, RESULTS_DIR_OVERRIDE

Examples:
  ./scripts/e2e-loadtest-registry.sh --interactive
  ./scripts/e2e-loadtest-registry.sh
  BASE_IMAGE_TAG=v0.13.0 ARM64_TAG_SUFFIX=-arm64 ./scripts/e2e-loadtest-registry.sh
  ./scripts/e2e-loadtest-registry.sh --summary-only
  ./scripts/e2e-loadtest-registry.sh --summary-only --no-refresh-summary-metrics
  INVOCATION_MODE=async LOADTEST_RUNTIMES=java,python ./scripts/e2e-loadtest-registry.sh
  K6_PAYLOAD_MODE=pool-sequential K6_PAYLOAD_POOL_SIZE=5000 ./scripts/e2e-loadtest-registry.sh
  BASE_IMAGE_TAG=v0.13.0 TAG_SUFFIX=amd64 ./scripts/e2e-loadtest-registry.sh
  RESULTS_DIR_OVERRIDE=/tmp/k6-results ./scripts/e2e-loadtest-registry.sh --summary-only --no-refresh-summary-metrics
EOF
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            --summary-only)
                SUMMARY_ONLY=true
                ;;
            --no-refresh-summary-metrics)
                REFRESH_SUMMARY_METRICS=false
                ;;
            --interactive)
                INTERACTIVE=true
                ;;
            *)
                err "Unknown argument: $1"
                echo ""
                show_help
                exit 2
                ;;
        esac
        shift
    done
}

launch_interactive_configurator() {
    if ! command -v uv &>/dev/null; then
        err "uv is required for --interactive mode. Install uv and retry."
        exit 1
    fi
    uv run "${SCRIPT_DIR}/e2e-loadtest-registry-interactive.py"
}

get_results_dir() {
    if [[ -n "${RESULTS_DIR_OVERRIDE}" ]]; then
        echo "${RESULTS_DIR_OVERRIDE}"
        return
    fi
    echo "${PROJECT_ROOT}/experiments/k6/results"
}

build_run_results_dir() {
    local base_results_dir
    base_results_dir="$(get_results_dir)"
    local run_id
    run_id="${LOADTEST_RUN_ID_OVERRIDE:-$(date +%Y%m%d-%H%M%S)}"
    echo "${base_results_dir}/run-${run_id}-${INVOCATION_MODE}"
}

derive_loadtest_window_from_results() {
    if [[ "${LOADTEST_START_EPOCH}" -gt 0 && "${LOADTEST_END_EPOCH}" -gt 0 ]]; then
        return
    fi

    local windows_file
    windows_file="$(get_results_dir)/test-windows.jsonl"
    if [[ ! -f "${windows_file}" ]]; then
        return
    fi

    local bounds
    bounds=$(python3 - "${windows_file}" <<'PYEOF'
import json, sys
from pathlib import Path

path = Path(sys.argv[1])
starts = []
ends = []
for line in path.read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if not line:
        continue
    try:
        item = json.loads(line)
    except Exception:
        continue
    s = item.get("start", 0)
    e = item.get("end", 0)
    if isinstance(s, int) and s > 0:
        starts.append(s)
    if isinstance(e, int) and e > 0:
        ends.append(e)

if starts and ends:
    print(f"{min(starts)} {max(ends)}")
PYEOF
) || true

    if [[ -n "${bounds}" ]]; then
        local start_epoch end_epoch
        read -r start_epoch end_epoch <<< "${bounds}"
        if [[ -n "${start_epoch}" && -n "${end_epoch}" ]]; then
            LOADTEST_START_EPOCH="${start_epoch}"
            LOADTEST_END_EPOCH="${end_epoch}"
        fi
    fi
}

# ─── Stage 1: Pre-flight checks ───────────────────────────────────────────────
check_prerequisites() {
    e2e_require_multipass
    if ! command -v k6 &>/dev/null; then
        err "k6 is not installed. Install it from https://grafana.com/docs/k6/latest/set-up/install-k6/"
        exit 1
    fi
}

cleanup() {
    local exit_code=$?
    if [[ "${SUMMARY_ONLY}" != "true" ]]; then
        e2e_cleanup_vm
    fi
    exit "${exit_code}"
}

# ─── Stage 2: VM provisioning ──────────────────────────────────────────────────
create_vm() {
    e2e_ensure_vm_running "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"
}

# ─── Stage 4: k3s installation ─────────────────────────────────────────────────
install_k3s() {
    if [[ "$(e2e_vm_has_command k3s)" == "yes" ]]; then
        log "k3s already installed, skipping..."
        return
    fi
    e2e_install_k3s
}

# ─── Stage 3: VM dependencies (docker, java, helm) ────────────────────────────
install_deps() {
    if [[ "$(e2e_vm_has_command docker)" == "yes" ]] \
        && [[ "$(e2e_vm_has_command java)" == "yes" ]] \
        && [[ "$(e2e_vm_has_command helm)" == "yes" ]]; then
        log "Dependencies already present, skipping..."
        return
    fi
    e2e_install_vm_dependencies true
}

# ─── Stage 5: Sync Helm chart and k6 scripts to VM ────────────────────────────
sync_project() {
    e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "/home/ubuntu/nanofaas"
}

# ─── Stage 6: Resolve image tag from VM architecture ──────────────────────────
# On ARM64 VMs, appends ARM64_TAG_SUFFIX to BASE_IMAGE_TAG (e.g. v0.12.0-arm64).
# Can be bypassed entirely with IMAGE_TAG_OVERRIDE.
resolve_image_tag() {
    if [[ -n "${IMAGE_TAG_OVERRIDE}" ]]; then
        RESOLVED_TAG="${IMAGE_TAG_OVERRIDE}"
        info "Using explicit IMAGE_TAG_OVERRIDE=${RESOLVED_TAG}"
        return
    fi

    if [[ -n "${TAG_SUFFIX}" ]]; then
        local suffix="${TAG_SUFFIX#-}"
        suffix=$(printf '%s' "${suffix}" | tr '[:upper:]' '[:lower:]')
        if [[ -z "${suffix}" || "${suffix}" == "none" ]]; then
            RESOLVED_TAG="${BASE_IMAGE_TAG}"
            info "Using BASE_IMAGE_TAG without suffix: ${RESOLVED_TAG}"
        else
            RESOLVED_TAG="${BASE_IMAGE_TAG}-${suffix}"
            info "Using explicit TAG_SUFFIX=${suffix}, image tag=${RESOLVED_TAG}"
        fi
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

# ─── Stage 7: Resolve exec/bash image prefix ──────────────────────────────────
# Older releases used "bash-word-stats", newer ones use "exec-word-stats".
# Probes the registry to find which prefix exists for the resolved tag.
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

# ─── Stage 8: Pull all container images into k3s/containerd ───────────────────
# Pulls 9 function/infra images + curl (for the register job).
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

# Collect pod/event/log diagnostics when Helm install fails.
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

# ─── Stage 9: Helm install with generated values ──────────────────────────────
# Generates a temporary values file with all 8 demo functions pointing to
# the resolved GHCR image tags, then runs helm upgrade --install.
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
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: \"1\"
      memory: 2Gi
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
      scalingConfig:
        strategy: INTERNAL
        minReplicas: 1
        maxReplicas: 5
        metrics:
          - type: in_flight
            target: \"2\"
    - name: json-transform-java
      image: ${IMAGE_REPO}/java-json-transform:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      scalingConfig:
        strategy: INTERNAL
        minReplicas: 1
        maxReplicas: 5
        metrics:
          - type: in_flight
            target: \"2\"
    - name: word-stats-python
      image: ${IMAGE_REPO}/python-word-stats:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      scalingConfig:
        strategy: INTERNAL
        minReplicas: 1
        maxReplicas: 5
        metrics:
          - type: in_flight
            target: \"2\"
    - name: json-transform-python
      image: ${IMAGE_REPO}/python-json-transform:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      scalingConfig:
        strategy: INTERNAL
        minReplicas: 1
        maxReplicas: 5
        metrics:
          - type: in_flight
            target: \"2\"
    - name: word-stats-exec
      image: ${IMAGE_REPO}/${RESOLVED_EXEC_PREFIX}-word-stats:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      runtimeMode: STDIO
      scalingConfig:
        strategy: INTERNAL
        minReplicas: 1
        maxReplicas: 5
        metrics:
          - type: in_flight
            target: \"2\"
    - name: json-transform-exec
      image: ${IMAGE_REPO}/${RESOLVED_EXEC_PREFIX}-json-transform:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      runtimeMode: STDIO
      scalingConfig:
        strategy: INTERNAL
        minReplicas: 1
        maxReplicas: 5
        metrics:
          - type: in_flight
            target: \"2\"
    - name: word-stats-java-lite
      image: ${IMAGE_REPO}/java-lite-word-stats:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      scalingConfig:
        strategy: INTERNAL
        minReplicas: 1
        maxReplicas: 5
        metrics:
          - type: in_flight
            target: \"2\"
    - name: json-transform-java-lite
      image: ${IMAGE_REPO}/java-lite-json-transform:${RESOLVED_TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      scalingConfig:
        strategy: INTERNAL
        minReplicas: 1
        maxReplicas: 5
        metrics:
          - type: in_flight
            target: \"2\"
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

# ─── Stage 10: Wait for all pods to become Ready ──────────────────────────────
# Polls pod status every 5s for up to 7.5 minutes. In "auto" mode, computes
# the expected pod count from the sum of desired replicas across all Deployments.
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

# ─── Stage 11: Verify function registration ───────────────────────────────────
# Polls GET /v1/functions every 2s for up to 2 minutes until EXPECTED_FUNCTIONS
# are registered. The register job runs as a Kubernetes Job after Helm install.
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

# ─── Stage 12: Run k6 load tests ──────────────────────────────────────────────
# Delegates to e2e-loadtest.sh which runs k6 against all 8 functions sequentially
# with 10s cool-down between each test. Results saved to k6/results/.
run_loadtest() {
    log "Starting load test suite (e2e-loadtest.sh)..."
    local results_dir
    results_dir="$(build_run_results_dir)"
    RESULTS_DIR_OVERRIDE="${results_dir}"
    mkdir -p "${results_dir}"
    log "Run results directory: ${results_dir}"

    local cp_samples_file="${results_dir}/control-plane-top-samples.txt"
    : > "${cp_samples_file}"

    LOADTEST_START_EPOCH=$(date +%s)
    (
        while true; do
            sample=$(vm_exec "sudo kubectl top pods -n ${NAMESPACE} -l app=nanofaas-control-plane --no-headers 2>/dev/null | head -n 1" || true)
            if [[ -n "${sample}" ]]; then
                echo "$(date +%s) ${sample}" >> "${cp_samples_file}"
            fi
            sleep 5
        done
    ) &
    local sampler_pid=$!

    local rc=0
    VM_NAME="${VM_NAME}" \
    SKIP_GRAFANA="${SKIP_GRAFANA}" \
    CONTROL_PLANE_RUNTIME="${CONTROL_PLANE_RUNTIME}" \
    LOADTEST_WORKLOADS="${LOADTEST_WORKLOADS}" \
    LOADTEST_RUNTIMES="${LOADTEST_RUNTIMES}" \
    INVOCATION_MODE="${INVOCATION_MODE}" \
    K6_STAGE_SEQUENCE="${K6_STAGE_SEQUENCE}" \
    K6_PAYLOAD_MODE="${K6_PAYLOAD_MODE}" \
    K6_PAYLOAD_POOL_SIZE="${K6_PAYLOAD_POOL_SIZE}" \
    RESULTS_DIR_OVERRIDE="${results_dir}" \
    "${SCRIPT_DIR}/e2e-loadtest.sh" || rc=$?

    LOADTEST_END_EPOCH=$(date +%s)
    kill "${sampler_pid}" 2>/dev/null || true
    wait "${sampler_pid}" 2>/dev/null || true

    if [[ ${rc} -ne 0 ]]; then
        return ${rc}
    fi
}

guard_runtime_support() {
    local runtime_kind
    runtime_kind="$(e2e_runtime_kind)"
    if [[ "${runtime_kind}" == "rust" ]]; then
        warn "SKIP: e2e-loadtest-registry is Java-image specific and is not supported with CONTROL_PLANE_RUNTIME=rust."
        exit 0
    fi
}

# ─── Stage 13: Comparison summary tables ───────────────────────────────────────
# Reads k6 JSON summaries and Prometheus metrics, then prints:
#   1. Per-workload table (word-stats, json-transform) comparing all 4 runtimes
#   2. Delta% vs Java (Spring) baseline for each alternative runtime
#   3. Aggregate runtime comparison (averages across both workloads)
#   4. Control-plane metrics: cold/warm starts, init duration, queue wait, e2e latency
#   5. Error breakdown: timeouts, rejections, retries per function
#   6. Overall winners
print_summary() {
    local results_dir
    results_dir="$(get_results_dir)"
    mkdir -p "${results_dir}"

    local prom_dump="${results_dir}/prometheus-dump.json"
    local k8s_dump="${results_dir}/k8s-resources.json"
    local vm_ip="<VM_IP>"
    local should_refresh="${REFRESH_SUMMARY_METRICS}"

    if [[ "${should_refresh}" == "true" ]]; then
        vm_ip=$(e2e_get_vm_ip || true)
        if [[ -z "${vm_ip}" ]]; then
            warn "Cannot determine VM IP; using cached summary inputs from ${results_dir}"
            should_refresh="false"
            vm_ip="<VM_IP>"
        fi
    fi

    if [[ "${should_refresh}" == "true" ]]; then
        local prom_url="http://${vm_ip}:30090"
        log "Collecting Prometheus metrics..."
python3 - "${prom_url}" "${prom_dump}" << 'PROM_DUMP'
import json, sys, urllib.request, urllib.error, urllib.parse
from pathlib import Path

prom_url = sys.argv[1]
out_path = sys.argv[2]
project_root = Path(out_path).resolve().parents[3]
sys.path.insert(0, str(project_root / "experiments" / "lib"))

from loadtest_registry_metrics import build_prom_queries

def prom_query(expr):
    """Execute a PromQL instant query and return the result list."""
    url = f"{prom_url}/api/v1/query?query={urllib.parse.quote(expr, safe='')}"
    with urllib.request.urlopen(url, timeout=10) as resp:
        body = json.loads(resp.read())
    return body.get("data", {}).get("result", [])

# ── Diagnostic: check connectivity and discover available metrics ──
try:
    targets = prom_query("up")
    up_count = sum(1 for t in targets if t["value"][1] == "1")
    print(f"  Prometheus reachable: {len(targets)} targets ({up_count} up)")
except Exception as e:
    print(f"  ERROR: Prometheus not reachable at {prom_url}: {e}", file=sys.stderr)

# Discover all function_* metric names
try:
    url = f"{prom_url}/api/v1/label/__name__/values"
    with urllib.request.urlopen(url, timeout=10) as resp:
        all_names = json.loads(resp.read()).get("data", [])
    fn_names = sorted(n for n in all_names if n.startswith("function_"))
    print(f"  Available function_* metrics ({len(fn_names)}): {', '.join(fn_names[:20])}")
    if len(fn_names) > 20:
        print(f"    ... and {len(fn_names) - 20} more")
except Exception as e:
    print(f"  warn: could not list metric names: {e}", file=sys.stderr)
    fn_names = []

# ── Build query map, adapting to discovered metric names ──
# Micrometer Timer "function_latency_ms" exports as "function_latency_ms_seconds" in Prometheus.
# But if the actual names differ, try to discover them.
def find_metric(base, candidates):
    """Return the first candidate that exists in Prometheus, or the base name."""
    for c in candidates:
        if c in fn_names:
            return c
    return base

lat_base = find_metric("function_latency_ms_seconds", [
    "function_latency_ms_seconds", "function_latency_ms",
])
e2e_base = find_metric("function_e2e_latency_ms_seconds", [
    "function_e2e_latency_ms_seconds", "function_e2e_latency_ms",
])
qw_base = find_metric("function_queue_wait_ms_seconds", [
    "function_queue_wait_ms_seconds", "function_queue_wait_ms",
])
init_base = find_metric("function_init_duration_ms_seconds", [
    "function_init_duration_ms_seconds", "function_init_duration_ms",
])

queries = build_prom_queries(lat_base, e2e_base, qw_base, init_base)

data = {}
query_hits = 0
for key, expr in queries.items():
    try:
        results = prom_query(expr)
        if results:
            query_hits += 1
        for r in results:
            fn = r.get("metric", {}).get("function", "_global_")
            val = float(r["value"][1])
            data.setdefault(fn, {})[key] = val
    except Exception as e:
        print(f"  warn: query '{key}' ({expr}) failed: {e}", file=sys.stderr)

with open(out_path, "w") as f:
    json.dump(data, f, indent=2)
print(f"  Prometheus metrics collected for {len(data)} functions ({query_hits}/{len(queries)} queries returned data)")
PROM_DUMP

        # Collect Kubernetes pod/resource info from the VM
        log "Collecting Kubernetes resource metrics..."
        vm_exec "sudo kubectl top pods -n ${NAMESPACE} --no-headers 2>/dev/null || true" > "${results_dir}/kubectl-top-pods.txt" 2>/dev/null || true
        vm_exec "sudo kubectl get pods -n ${NAMESPACE} -o json 2>/dev/null" > "${results_dir}/kubectl-pods.json" 2>/dev/null || true
        vm_exec "free -b 2>/dev/null" > "${results_dir}/vm-memory.txt" 2>/dev/null || true

        python3 - "${results_dir}" "${k8s_dump}" << 'K8S_DUMP'
import json, os, sys, re

results_dir = sys.argv[1]
out_path = sys.argv[2]
data = {"pods": {}, "vm_memory": {}}

# Parse kubectl top pods (CPU and memory per pod)
top_path = os.path.join(results_dir, "kubectl-top-pods.txt")
if os.path.exists(top_path):
    with open(top_path) as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 3:
                pod_name = parts[0]
                cpu = parts[1]   # e.g. "25m"
                mem = parts[2]   # e.g. "128Mi"
                # Extract function name from pod (fn-<name>-<hash>-<hash>)
                m = re.match(r'^fn-(.+?)-[a-f0-9]+-[a-z0-9]+$', pod_name)
                if m:
                    fn = m.group(1)
                    # Parse memory
                    mem_bytes = 0
                    mm = re.match(r'^(\d+)([KMGi]+)$', mem)
                    if mm:
                        val = int(mm.group(1))
                        unit = mm.group(2)
                        if unit == "Ki": mem_bytes = val * 1024
                        elif unit == "Mi": mem_bytes = val * 1024 * 1024
                        elif unit == "Gi": mem_bytes = val * 1024 * 1024 * 1024
                        else: mem_bytes = val
                    # Parse CPU
                    cpu_milli = 0
                    cm = re.match(r'^(\d+)m$', cpu)
                    if cm:
                        cpu_milli = int(cm.group(1))
                    elif cpu.replace('.', '', 1).isdigit():
                        cpu_milli = int(float(cpu) * 1000)
                    data["pods"].setdefault(fn, []).append({
                        "pod": pod_name, "cpu_milli": cpu_milli,
                        "mem_bytes": mem_bytes, "mem_display": mem
                    })

# Parse kubectl get pods JSON for replica counts and restart counts
pods_path = os.path.join(results_dir, "kubectl-pods.json")
if os.path.exists(pods_path):
    try:
        with open(pods_path) as f:
            pods_json = json.load(f)
        for item in pods_json.get("items", []):
            pod_name = item.get("metadata", {}).get("name", "")
            m = re.match(r'^fn-(.+?)-[a-f0-9]+-[a-z0-9]+$', pod_name)
            if m:
                fn = m.group(1)
                restarts = sum(
                    cs.get("restartCount", 0)
                    for cs in item.get("status", {}).get("containerStatuses", [])
                )
                for pod_entry in data["pods"].get(fn, []):
                    if pod_entry["pod"] == pod_name:
                        pod_entry["restarts"] = restarts
    except Exception:
        pass

# Parse VM memory
mem_path = os.path.join(results_dir, "vm-memory.txt")
if os.path.exists(mem_path):
    with open(mem_path) as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 7 and parts[0] == "Mem:":
                data["vm_memory"] = {
                    "total": int(parts[1]),
                    "used": int(parts[2]),
                    "free": int(parts[3]),
                    "available": int(parts[6]) if len(parts) > 6 else 0,
                }

with open(out_path, "w") as f:
    json.dump(data, f)
fn_count = len(data.get("pods", {}))
print(f"  Kubernetes resource metrics collected for {fn_count} functions")
K8S_DUMP
    else
        log "REFRESH_SUMMARY_METRICS=false, using cached summary inputs from ${results_dir}"
    fi

    local tag_display="${RESOLVED_TAG:-${IMAGE_TAG_OVERRIDE:-${BASE_IMAGE_TAG}}}"
    local exec_prefix_display="${RESOLVED_EXEC_PREFIX:-${EXEC_IMAGE_PREFIX:-auto}}"

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "                              REGISTRY-BASED LOAD TEST — COMPARISON SUMMARY"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""
    log "VM: ${VM_NAME} (${vm_ip})  |  Image: ${IMAGE_REPO}:${tag_display}  |  Exec prefix: ${exec_prefix_display}"
    log ""

    python3 - "${results_dir}" "${prom_dump}" "${k8s_dump}" "${LOADTEST_START_EPOCH}" "${LOADTEST_END_EPOCH}" "${PROJECT_ROOT}" << 'PYEOF'
import json, os, sys
from pathlib import Path

project_root = Path(sys.argv[6]).resolve() if len(sys.argv) > 6 else Path(sys.argv[1]).resolve().parents[2]
sys.path.insert(0, str(project_root / "experiments" / "lib"))
from loadtest_registry_metrics import (
    merge_prom_with_snapshots,
    summarize_control_plane_samples,
    summarize_control_plane_samples_by_windows,
)

results_dir = sys.argv[1]
prom_path = sys.argv[2]
k8s_path = sys.argv[3]
loadtest_start = int(sys.argv[4]) if len(sys.argv) > 4 else 0
loadtest_end = int(sys.argv[5]) if len(sys.argv) > 5 else 0
payload_mode = os.environ.get("K6_PAYLOAD_MODE", "legacy-random").strip().lower()
try:
    payload_pool_size = max(1, int(os.environ.get("K6_PAYLOAD_POOL_SIZE", "5000")))
except ValueError:
    payload_pool_size = 5000

# Load Prometheus dump
prom = {}
if os.path.exists(prom_path):
    with open(prom_path) as f:
        prom = json.load(f)

# Merge per-test Prometheus snapshots to fill function-level percentile gaps.
snapshots = []
snap_path = os.path.join(results_dir, "prom-snapshots.jsonl")
if os.path.exists(snap_path):
    with open(snap_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                snapshots.append(json.loads(line))
            except Exception:
                continue
prom = merge_prom_with_snapshots(prom, snapshots)

# Load Kubernetes resource dump
k8s = {}
if os.path.exists(k8s_path):
    with open(k8s_path) as f:
        k8s = json.load(f)

# Function name mappings
ALL_FUNCTIONS = [
    "word-stats-java", "word-stats-java-lite", "word-stats-python", "word-stats-exec",
    "json-transform-java", "json-transform-java-lite", "json-transform-python", "json-transform-exec",
]

SELECTED_FUNCTIONS = [
    fn for fn in ALL_FUNCTIONS if os.path.exists(os.path.join(results_dir, f"{fn}.json"))
]
if not SELECTED_FUNCTIONS:
    SELECTED_FUNCTIONS = list(ALL_FUNCTIONS)

pairs = [
    ("word-stats",      ["word-stats-java", "word-stats-java-lite", "word-stats-python", "word-stats-exec"]),
    ("json-transform",  ["json-transform-java", "json-transform-java-lite", "json-transform-python", "json-transform-exec"]),
]

runtime_label = {
    "word-stats-java": "Java (Spring)",  "word-stats-java-lite": "Java (Lite)",
    "word-stats-python": "Python",       "word-stats-exec": "Exec/Bash",
    "json-transform-java": "Java (Spring)",  "json-transform-java-lite": "Java (Lite)",
    "json-transform-python": "Python",       "json-transform-exec": "Exec/Bash",
}

def load_result(name):
    path = os.path.join(results_dir, f"{name}.json")
    if not os.path.exists(path):
        return None
    with open(path) as f:
        d = json.load(f)
    m = d.get("metrics", {})
    reqs = int(m.get("http_reqs", {}).get("count", 0))
    fails = int(m.get("http_req_failed", {}).get("passes", 0))
    dur = m.get("http_req_duration", {})
    iters = m.get("iterations", {})
    payload_size = m.get("payload_size_bytes", {})
    iterations_count = int(iters.get("count", reqs))
    if iterations_count <= 0:
        iterations_count = reqs
    return {
        "reqs": reqs,
        "fail_pct": (fails / max(1, reqs)) * 100,
        "avg": dur.get("avg", 0),
        "med": dur.get("med", 0),
        "p90": dur.get("p(90)", 0),
        "p95": dur.get("p(95)", 0),
        "max": dur.get("max", 0),
        "rps": float(iters.get("rate", 0)),
        "iterations": iterations_count,
        "payload_avg_b": float(payload_size.get("avg", 0.0)),
        "payload_q1_b": float(payload_size.get("p(25)", 0.0)),
        "payload_q2_b": float(payload_size.get("med", 0.0)),
        "payload_q3_b": float(payload_size.get("p(75)", 0.0)),
    }

def pget(fn, key, default=0.0):
    """Get a Prometheus metric value for a function."""
    return prom.get(fn, {}).get(key, default)

def fmt_ms(seconds):
    """Format seconds as ms string, or '-' if zero/missing."""
    if seconds == 0.0:
        return "-"
    return f"{seconds * 1000:.1f}"

def fmt_avg_ms(sum_seconds, count):
    if count <= 0:
        return "-"
    return f"{(sum_seconds / count) * 1000:.1f}"

def fmt_num(value, digits=1):
    if value is None:
        return "-"
    return f"{value:.{digits}f}"

def estimate_unique_payloads(iterations, mode, pool_size):
    if iterations <= 0:
        return 0.0
    if mode == "pool-sequential":
        return float(min(iterations, pool_size))
    if mode == "pool-random":
        # Expected distinct values after k samples from uniform pool of size N.
        return float(pool_size) * (1.0 - (1.0 - 1.0 / float(pool_size)) ** float(iterations))
    return None

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 1: k6 CLIENT-SIDE LATENCY (per workload)
# ════════════════════════════════════════════════════════════════════════════════
print("=" * 105)
print(f"{'SECTION 1: CLIENT-SIDE LATENCY (k6)':^105s}")
print("=" * 105)
print()

for fn_group, variants in pairs:
    rows = []
    for v in variants:
        r = load_result(v)
        if r:
            rows.append((runtime_label.get(v, v), r))
    if not rows:
        continue

    print(f"┌─────────────────────────────────────────────────────────────────────────────────────────┐")
    print(f"│  {fn_group.upper():^85s} │")
    print(f"├───────────────┬────────┬────────┬──────────┬──────────┬──────────┬──────────┬──────────┤")
    print(f"│ {'Runtime':<13s} │ {'Reqs':>6s} │ {'Fail%':>6s} │ {'Avg(ms)':>8s} │ {'Med(ms)':>8s} │ {'p90(ms)':>8s} │ {'p95(ms)':>8s} │ {'Req/s':>8s} │")
    print(f"├───────────────┼────────┼────────┼──────────┼──────────┼──────────┼──────────┼──────────┤")
    for label, r in rows:
        print(f"│ {label:<13s} │ {r['reqs']:>6d} │ {r['fail_pct']:>5.1f}% │ {r['avg']:>8.1f} │ {r['med']:>8.1f} │ {r['p90']:>8.1f} │ {r['p95']:>8.1f} │ {r['rps']:>8.1f} │")
    print(f"└───────────────┴────────┴────────┴──────────┴──────────┴──────────┴──────────┴──────────┘")

    spring = next((r for l, r in rows if "Spring" in l), None)
    if spring and spring["avg"] > 0:
        print(f"  Δ vs Java (Spring):")
        for label, r in rows:
            if "Spring" in label:
                continue
            da = ((r["avg"] - spring["avg"]) / spring["avg"]) * 100
            dr = ((r["rps"] - spring["rps"]) / max(0.01, spring["rps"])) * 100
            print(f"    {label:<13s}  avg: {'+' if da >= 0 else ''}{da:.1f}%   rps: {'+' if dr >= 0 else ''}{dr:.1f}%")
    print()

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 2: CONTROL-PLANE LATENCY (Prometheus)
# ════════════════════════════════════════════════════════════════════════════════
print("=" * 105)
print(f"{'SECTION 2: CONTROL-PLANE LATENCY (Prometheus)':^105s}")
print("=" * 105)
print()
print("  Latency measured inside the control-plane (excludes network hop from k6).")
print("  Timers: function_latency_ms (dispatch→response), function_e2e_latency_ms (enqueue→response).")
if not any(pget(fn, "latency_p50") > 0 for fn in SELECTED_FUNCTIONS):
    print("  Note: percentile series are zero in this run; Avg(ms) from sum/count is the reliable value.")
print()

header =  f"│ {'Function':<28s} │ {'Lat p50':>8s} │ {'Lat p95':>8s} │ {'Lat p99':>8s} │ {'E2E p50':>8s} │ {'E2E p95':>8s} │ {'E2E p99':>8s} │ {'Avg(ms)':>8s} │"
sep_top = f"┌{'─'*30}┬{'─'*10}┬{'─'*10}┬{'─'*10}┬{'─'*10}┬{'─'*10}┬{'─'*10}┬{'─'*10}┐"
sep_mid = f"├{'─'*30}┼{'─'*10}┼{'─'*10}┼{'─'*10}┼{'─'*10}┼{'─'*10}┼{'─'*10}┼{'─'*10}┤"
sep_bot = f"└{'─'*30}┴{'─'*10}┴{'─'*10}┴{'─'*10}┴{'─'*10}┴{'─'*10}┴{'─'*10}┴{'─'*10}┘"

print(sep_top)
print(header)
print(sep_mid)
for fn in SELECTED_FUNCTIONS:
    lp50 = fmt_ms(pget(fn, "latency_p50"))
    lp95 = fmt_ms(pget(fn, "latency_p95"))
    lp99 = fmt_ms(pget(fn, "latency_p99"))
    ep50 = fmt_ms(pget(fn, "e2e_p50"))
    ep95 = fmt_ms(pget(fn, "e2e_p95"))
    ep99 = fmt_ms(pget(fn, "e2e_p99"))
    cnt = pget(fn, "latency_count")
    sm  = pget(fn, "latency_sum")
    avg = fmt_avg_ms(sm, cnt)
    print(f"│ {fn:<28s} │ {lp50:>8s} │ {lp95:>8s} │ {lp99:>8s} │ {ep50:>8s} │ {ep95:>8s} │ {ep99:>8s} │ {avg:>8s} │")
print(sep_bot)
print()

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 3: COLD START & INIT DURATION
# ════════════════════════════════════════════════════════════════════════════════
print("=" * 105)
print(f"{'SECTION 3: COLD START & INIT DURATION':^105s}")
print("=" * 105)
print()

h  = f"│ {'Function':<28s} │ {'Cold':>6s} │ {'Warm':>6s} │ {'Total':>6s} │ {'Cold%':>7s} │ {'Init p50':>9s} │ {'Init p95':>9s} │"
s1 = f"┌{'─'*30}┬{'─'*8}┬{'─'*8}┬{'─'*8}┬{'─'*9}┬{'─'*11}┬{'─'*11}┐"
s2 = f"├{'─'*30}┼{'─'*8}┼{'─'*8}┼{'─'*8}┼{'─'*9}┼{'─'*11}┼{'─'*11}┤"
s3 = f"└{'─'*30}┴{'─'*8}┴{'─'*8}┴{'─'*8}┴{'─'*9}┴{'─'*11}┴{'─'*11}┘"

print(s1)
print(h)
print(s2)
for fn in SELECTED_FUNCTIONS:
    cold = int(pget(fn, "cold_start"))
    warm = int(pget(fn, "warm_start"))
    total = cold + warm
    cold_pct = f"{cold / total * 100:.1f}%" if total > 0 else "-"
    ip50 = fmt_ms(pget(fn, "init_p50"))
    ip95 = fmt_ms(pget(fn, "init_p95"))
    print(f"│ {fn:<28s} │ {cold:>6d} │ {warm:>6d} │ {total:>6d} │ {cold_pct:>7s} │ {ip50:>9s} │ {ip95:>9s} │")
print(s3)
print()

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 4: QUEUE WAIT TIME
# ════════════════════════════════════════════════════════════════════════════════
print("=" * 105)
print(f"{'SECTION 4: QUEUE WAIT TIME':^105s}")
print("=" * 105)
print()
print("  Time between enqueue and dispatch (function_queue_wait_ms).")
if not any(pget(fn, "queue_wait_p50") > 0 for fn in SELECTED_FUNCTIONS):
    print("  Note: queue quantiles are zero in this run; QWait avg is derived from sum/count.")
print()

h  = f"│ {'Function':<28s} │ {'QWait avg':>10s} │ {'QWait p50':>10s} │ {'QWait p95':>10s} │ {'QDepth':>8s} │ {'InFlight':>8s} │"
s1 = f"┌{'─'*30}┬{'─'*12}┬{'─'*12}┬{'─'*12}┬{'─'*10}┬{'─'*10}┐"
s2 = f"├{'─'*30}┼{'─'*12}┼{'─'*12}┼{'─'*12}┼{'─'*10}┼{'─'*10}┤"
s3 = f"└{'─'*30}┴{'─'*12}┴{'─'*12}┴{'─'*12}┴{'─'*10}┴{'─'*10}┘"

print(s1)
print(h)
print(s2)
for fn in SELECTED_FUNCTIONS:
    qcnt = pget(fn, "queue_wait_count")
    qsum = pget(fn, "queue_wait_sum")
    qavg = fmt_avg_ms(qsum, qcnt)
    qp50 = fmt_ms(pget(fn, "queue_wait_p50"))
    qp95 = fmt_ms(pget(fn, "queue_wait_p95"))
    qdepth = int(pget(fn, "queue_depth"))
    inflight = int(pget(fn, "in_flight"))
    print(f"│ {fn:<28s} │ {qavg:>10s} │ {qp50:>10s} │ {qp95:>10s} │ {qdepth:>8d} │ {inflight:>8d} │")
print(s3)
print()

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 5: ERROR BREAKDOWN
# ════════════════════════════════════════════════════════════════════════════════
print("=" * 105)
print(f"{'SECTION 5: ERROR BREAKDOWN (control-plane counters)':^105s}")
print("=" * 105)
print()

h  = f"│ {'Function':<28s} │ {'Enqueue':>8s} │ {'Dispatch':>8s} │ {'Success':>8s} │ {'Error':>7s} │ {'Timeout':>8s} │ {'Reject':>7s} │ {'Retry':>7s} │"
s1 = f"┌{'─'*30}┬{'─'*10}┬{'─'*10}┬{'─'*10}┬{'─'*9}┬{'─'*10}┬{'─'*9}┬{'─'*9}┐"
s2 = f"├{'─'*30}┼{'─'*10}┼{'─'*10}┼{'─'*10}┼{'─'*9}┼{'─'*10}┼{'─'*9}┼{'─'*9}┤"
s3 = f"└{'─'*30}┴{'─'*10}┴{'─'*10}┴{'─'*10}┴{'─'*9}┴{'─'*10}┴{'─'*9}┴{'─'*9}┘"

print(s1)
print(h)
print(s2)
for fn in SELECTED_FUNCTIONS:
    enq  = int(pget(fn, "enqueue"))
    disp = int(pget(fn, "dispatch"))
    succ = int(pget(fn, "success"))
    err  = int(pget(fn, "error"))
    tout = int(pget(fn, "timeout"))
    rej  = int(pget(fn, "rejected"))
    ret  = int(pget(fn, "retry"))
    print(f"│ {fn:<28s} │ {enq:>8d} │ {disp:>8d} │ {succ:>8d} │ {err:>7d} │ {tout:>8d} │ {rej:>7d} │ {ret:>7d} │")
print(s3)
print()

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 6: POD RESOURCES & VM MEMORY
# ════════════════════════════════════════════════════════════════════════════════
print("=" * 105)
print(f"{'SECTION 6: POD RESOURCES & VM MEMORY':^105s}")
print("=" * 105)
print()

def fmt_mem(b):
    if b == 0: return "-"
    if b >= 1024**3: return f"{b / 1024**3:.1f}Gi"
    if b >= 1024**2: return f"{b / 1024**2:.0f}Mi"
    if b >= 1024: return f"{b / 1024:.0f}Ki"
    return f"{b}B"

# VM memory summary
vm_mem = k8s.get("vm_memory", {})
if vm_mem:
    total = vm_mem.get("total", 0)
    used = vm_mem.get("used", 0)
    available = vm_mem.get("available", 0)
    pct = used / total * 100 if total > 0 else 0
    print(f"  VM Memory: {fmt_mem(total)} total, {fmt_mem(used)} used ({pct:.0f}%), {fmt_mem(available)} available")
    print()

# Pod resource table
k8s_pods = k8s.get("pods", {})
if k8s_pods:
    h  = f"│ {'Function':<28s} │ {'Pods':>5s} │ {'CPU(m)':>7s} │ {'RAM':>9s} │ {'RAM/pod':>9s} │ {'Restarts':>8s} │"
    s1 = f"┌{'─'*30}┬{'─'*7}┬{'─'*9}┬{'─'*11}┬{'─'*11}┬{'─'*10}┐"
    s2 = f"├{'─'*30}┼{'─'*7}┼{'─'*9}┼{'─'*11}┼{'─'*11}┼{'─'*10}┤"
    s3 = f"└{'─'*30}┴{'─'*7}┴{'─'*9}┴{'─'*11}┴{'─'*11}┴{'─'*10}┘"

    print(s1)
    print(h)
    print(s2)
    total_cpu = 0
    total_mem = 0
    total_pods = 0
    total_restarts = 0
    for fn in SELECTED_FUNCTIONS:
        pods = k8s_pods.get(fn, [])
        n = len(pods)
        cpu = sum(p.get("cpu_milli", 0) for p in pods)
        mem = sum(p.get("mem_bytes", 0) for p in pods)
        restarts = sum(p.get("restarts", 0) for p in pods)
        mem_per_pod = fmt_mem(mem // n) if n > 0 else "-"
        total_cpu += cpu; total_mem += mem; total_pods += n; total_restarts += restarts
        print(f"│ {fn:<28s} │ {n:>5d} │ {cpu:>7d} │ {fmt_mem(mem):>9s} │ {mem_per_pod:>9s} │ {restarts:>8d} │")
    print(s2)
    print(f"│ {'TOTAL':<28s} │ {total_pods:>5d} │ {total_cpu:>7d} │ {fmt_mem(total_mem):>9s} │ {'':>9s} │ {total_restarts:>8d} │")
    print(s3)

    # Aggregate by runtime
    print()
    print(f"  Per-runtime averages:")
    rt_pods = {}
    for fn in SELECTED_FUNCTIONS:
        label = runtime_label.get(fn, fn)
        pods = k8s_pods.get(fn, [])
        n = len(pods)
        cpu = sum(p.get("cpu_milli", 0) for p in pods)
        mem = sum(p.get("mem_bytes", 0) for p in pods)
        rt_pods.setdefault(label, []).append({"pods": n, "cpu": cpu, "mem": mem})
    for rt in ["Java (Spring)", "Java (Lite)", "Python", "Exec/Bash"]:
        entries = rt_pods.get(rt, [])
        if not entries: continue
        n = len(entries)
        avg_pods = sum(e["pods"] for e in entries) / n
        avg_cpu = sum(e["cpu"] for e in entries) / n
        avg_mem = sum(e["mem"] for e in entries) / n
        avg_mem_per_pod = avg_mem / avg_pods if avg_pods > 0 else 0
        print(f"    {rt:<13s}  pods: {avg_pods:.0f}  cpu: {avg_cpu:.0f}m  ram: {fmt_mem(int(avg_mem))}  ram/pod: {fmt_mem(int(avg_mem_per_pod))}")
else:
    print("  (kubectl top pods not available — is metrics-server installed?)")
print()

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 7: AGGREGATE RUNTIME COMPARISON
# ════════════════════════════════════════════════════════════════════════════════
print("=" * 105)
print(f"{'SECTION 7: AGGREGATE RUNTIME COMPARISON':^105s}")
print("=" * 105)
print()

runtimes = {"Java (Spring)": [], "Java (Lite)": [], "Python": [], "Exec/Bash": []}
all_rows = []
for _, variants in pairs:
    for v in variants:
        r = load_result(v)
        if r:
            label = runtime_label.get(v, v)
            runtimes.setdefault(label, []).append(r)
            all_rows.append((v, label, r))

# k6 aggregate
print(f"  Client-side (k6):")
print(f"  ┌───────────────┬──────────┬──────────┬──────────┬──────────┬────────┐")
print(f"  │ {'Runtime':<13s} │ {'Avg(ms)':>8s} │ {'Med(ms)':>8s} │ {'p95(ms)':>8s} │ {'Req/s':>8s} │ {'Fail%':>6s} │")
print(f"  ├───────────────┼──────────┼──────────┼──────────┼──────────┼────────┤")
for rt in ["Java (Spring)", "Java (Lite)", "Python", "Exec/Bash"]:
    entries = runtimes.get(rt, [])
    if not entries:
        continue
    n = len(entries)
    avg = sum(e["avg"] for e in entries) / n
    med = sum(e["med"] for e in entries) / n
    p95 = sum(e["p95"] for e in entries) / n
    rps = sum(e["rps"] for e in entries) / n
    fail = sum(e["fail_pct"] for e in entries) / n
    print(f"  │ {rt:<13s} │ {avg:>8.1f} │ {med:>8.1f} │ {p95:>8.1f} │ {rps:>8.1f} │ {fail:>5.1f}% │")
print(f"  └───────────────┴──────────┴──────────┴──────────┴──────────┴────────┘")
print()

# Prometheus aggregate
prom_runtimes = {"Java (Spring)": [], "Java (Lite)": [], "Python": [], "Exec/Bash": []}
for fn in SELECTED_FUNCTIONS:
    label = runtime_label.get(fn, fn)
    cnt = pget(fn, "latency_count")
    sm = pget(fn, "latency_sum")
    if cnt > 0:
        prom_runtimes.setdefault(label, []).append({
            "avg_s": sm / cnt,
            "p50_s": pget(fn, "latency_p50"),
            "p95_s": pget(fn, "latency_p95"),
            "e2e_p50_s": pget(fn, "e2e_p50"),
            "e2e_p95_s": pget(fn, "e2e_p95"),
            "cold": int(pget(fn, "cold_start")),
            "warm": int(pget(fn, "warm_start")),
        })

print(f"  Server-side (Prometheus, control-plane):")
print(f"  ┌───────────────┬──────────┬──────────┬──────────┬──────────┬──────────┬───────┬───────┐")
print(f"  │ {'Runtime':<13s} │ {'Avg(ms)':>8s} │ {'Lat p50':>8s} │ {'Lat p95':>8s} │ {'E2E p50':>8s} │ {'E2E p95':>8s} │ {'Cold':>5s} │ {'Warm':>5s} │")
print(f"  ├───────────────┼──────────┼──────────┼──────────┼──────────┼──────────┼───────┼───────┤")
for rt in ["Java (Spring)", "Java (Lite)", "Python", "Exec/Bash"]:
    entries = prom_runtimes.get(rt, [])
    if not entries:
        continue
    n = len(entries)
    avg_ms = sum(e["avg_s"] for e in entries) / n * 1000
    p50 = fmt_ms(sum(e["p50_s"] for e in entries) / n)
    p95 = fmt_ms(sum(e["p95_s"] for e in entries) / n)
    ep50 = fmt_ms(sum(e["e2e_p50_s"] for e in entries) / n)
    ep95 = fmt_ms(sum(e["e2e_p95_s"] for e in entries) / n)
    cold = sum(e["cold"] for e in entries)
    warm = sum(e["warm"] for e in entries)
    print(f"  │ {rt:<13s} │ {avg_ms:>8.1f} │ {p50:>8s} │ {p95:>8s} │ {ep50:>8s} │ {ep95:>8s} │ {cold:>5d} │ {warm:>5d} │")
print(f"  └───────────────┴──────────┴──────────┴──────────┴──────────┴──────────┴───────┴───────┘")
print()

# ════════════════════════════════════════════════════════════════════════════════
# WINNERS
# ════════════════════════════════════════════════════════════════════════════════
if all_rows:
    best_avg = min(all_rows, key=lambda x: x[2]["avg"])
    best_rps = max(all_rows, key=lambda x: x[2]["rps"])
    best_p95 = min(all_rows, key=lambda x: x[2]["p95"])
    print("  🏆 WINNERS (client-side k6):")
    print(f"     Lowest avg latency:  {best_avg[0]} [{best_avg[1]}] — {best_avg[2]['avg']:.1f}ms")
    print(f"     Highest throughput:  {best_rps[0]} [{best_rps[1]}] — {best_rps[2]['rps']:.1f} req/s")
    print(f"     Best p95 latency:    {best_p95[0]} [{best_p95[1]}] — {best_p95[2]['p95']:.1f}ms")

# Prometheus-based winners (by avg server-side latency)
prom_rows = []
for fn in SELECTED_FUNCTIONS:
    cnt = pget(fn, "latency_count")
    sm = pget(fn, "latency_sum")
    if cnt > 0:
        prom_rows.append((fn, runtime_label.get(fn, fn), sm / cnt * 1000))
if prom_rows:
    best_srv = min(prom_rows, key=lambda x: x[2])
    print()
    print(f"  🏆 WINNERS (server-side Prometheus):")
    print(f"     Lowest avg latency:  {best_srv[0]} [{best_srv[1]}] — {best_srv[2]:.1f}ms")

    # Fastest cold start
    init_rows = [(fn, runtime_label.get(fn, fn), pget(fn, "init_p50"))
                 for fn in SELECTED_FUNCTIONS if pget(fn, "init_p50") > 0]
    if init_rows:
        best_init = min(init_rows, key=lambda x: x[2])
        print(f"     Fastest cold start:  {best_init[0]} [{best_init[1]}] — {best_init[2]*1000:.1f}ms (init p50)")

    # Lowest queue wait (avg from sum/count)
    qw_rows = []
    for fn in SELECTED_FUNCTIONS:
        qcnt = pget(fn, "queue_wait_count")
        qsum = pget(fn, "queue_wait_sum")
        if qcnt > 0:
            qw_rows.append((fn, runtime_label.get(fn, fn), (qsum / qcnt) * 1000))
    if qw_rows:
        best_qw = min(qw_rows, key=lambda x: x[2])
        print(f"     Lowest queue wait:   {best_qw[0]} [{best_qw[1]}] — {best_qw[2]:.1f}ms (queue avg)")

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 8: CONTROL-PLANE RESOURCE PROFILE (during test run)
# ════════════════════════════════════════════════════════════════════════════════
print()
print("=" * 105)
print(f"{'SECTION 8: CONTROL-PLANE RESOURCE PROFILE':^105s}")
print("=" * 105)
print()

samples_path = os.path.join(results_dir, "control-plane-top-samples.txt")
sample_lines = []
if os.path.exists(samples_path):
    with open(samples_path) as f:
        sample_lines = [line.strip() for line in f if line.strip()]

cp_stats = summarize_control_plane_samples(sample_lines)
duration_s = (loadtest_end - loadtest_start) if loadtest_end > loadtest_start else 0

if cp_stats["samples"] == 0:
    print("  No control-plane resource samples collected (metrics-server might be unavailable).")
else:
    print(f"  Window: {duration_s}s, samples: {int(cp_stats['samples'])}")
    print(f"  ┌─────────────────┬──────────┬──────────┬──────────┐")
    print(f"  │ {'Metric':<15s} │ {'Avg':>8s} │ {'p95':>8s} │ {'Max':>8s} │")
    print(f"  ├─────────────────┼──────────┼──────────┼──────────┤")
    print(f"  │ {'CPU (m)':<15s} │ {cp_stats['cpu_avg_m']:>8.1f} │ {cp_stats['cpu_p95_m']:>8.1f} │ {cp_stats['cpu_max_m']:>8.1f} │")
    print(f"  │ {'RAM (Mi)':<15s} │ {cp_stats['mem_avg_bytes']/1024/1024:>8.1f} │ {cp_stats['mem_p95_bytes']/1024/1024:>8.1f} │ {cp_stats['mem_max_bytes']/1024/1024:>8.1f} │")
    print(f"  └─────────────────┴──────────┴──────────┴──────────┘")

    windows = []
    windows_path = os.path.join(results_dir, "test-windows.jsonl")
    if os.path.exists(windows_path):
        with open(windows_path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    windows.append(json.loads(line))
                except Exception:
                    continue

    by_function = summarize_control_plane_samples_by_windows(sample_lines, windows)
    if by_function:
        print()
        print("  Per-function experiment profile:")
        print(f"  ┌──────────────────────────────┬─────────┬─────────┬─────────┬──────────┬──────────┬──────────┐")
        print(f"  │ {'Function':<28s} │ {'CPU a':>7s} │ {'CPU95':>7s} │ {'CPU m':>7s} │ {'RAM a':>8s} │ {'RAM95':>8s} │ {'RAM m':>8s} │")
        print(f"  ├──────────────────────────────┼─────────┼─────────┼─────────┼──────────┼──────────┼──────────┤")
        for fn in SELECTED_FUNCTIONS:
            s = by_function.get(fn, {})
            if not s or s.get("samples", 0) == 0:
                print(f"  │ {fn:<28s} │ {'-':>7s} │ {'-':>7s} │ {'-':>7s} │ {'-':>8s} │ {'-':>8s} │ {'-':>8s} │")
                continue
            ram_avg = s["mem_avg_bytes"] / 1024 / 1024
            ram_p95 = s["mem_p95_bytes"] / 1024 / 1024
            ram_max = s["mem_max_bytes"] / 1024 / 1024
            print(
                f"  │ {fn:<28s} │ {s['cpu_avg_m']:>7.1f} │ {s['cpu_p95_m']:>7.1f} │ {s['cpu_max_m']:>7.1f} │ "
                f"{ram_avg:>8.1f} │ {ram_p95:>8.1f} │ {ram_max:>8.1f} │"
            )
        print(f"  └──────────────────────────────┴─────────┴─────────┴─────────┴──────────┴──────────┴──────────┘")

# ════════════════════════════════════════════════════════════════════════════════
# SECTION 9: PAYLOAD PROFILE (k6 INPUT MIX)
# ════════════════════════════════════════════════════════════════════════════════
print()
print("=" * 105)
print(f"{'SECTION 9: PAYLOAD PROFILE (k6 INPUT MIX)':^105s}")
print("=" * 105)
print()
print("  Payload bytes from k6 custom trend metric `payload_size_bytes` (Avg/Q1/Q2/Q3).")

table_rows = []
for fn in SELECTED_FUNCTIONS:
    r = load_result(fn)
    if not r:
        continue
    iterations = int(r.get("iterations", 0))
    unique_payloads = estimate_unique_payloads(iterations, payload_mode, payload_pool_size)
    if unique_payloads is None:
        coverage_pct = None
        reuse_factor = None
        collisions = None
        unique_display = "-"
    else:
        coverage_pct = (unique_payloads / float(payload_pool_size)) * 100.0
        reuse_factor = (float(iterations) / unique_payloads) if unique_payloads > 0 else None
        collisions = max(0.0, float(iterations) - unique_payloads)
        unique_display = fmt_num(unique_payloads, 0 if payload_mode == "pool-sequential" else 1)

    table_rows.append({
        "function": fn,
        "iterations": iterations,
        "unique": unique_display,
        "coverage": fmt_num(coverage_pct, 1) if coverage_pct is not None else "-",
        "reuse": fmt_num(reuse_factor, 2) if reuse_factor is not None else "-",
        "collisions": fmt_num(collisions, 1) if collisions is not None else "-",
        "payload_avg_b": fmt_num(r.get("payload_avg_b", 0.0), 1),
        "payload_q1_b": fmt_num(r.get("payload_q1_b", 0.0), 1),
        "payload_q2_b": fmt_num(r.get("payload_q2_b", 0.0), 1),
        "payload_q3_b": fmt_num(r.get("payload_q3_b", 0.0), 1),
    })

print(f"  Mode: {payload_mode} | Pool size: {payload_pool_size}")
print(f"  ┌──────────────────────────────┬───────┬────────┬──────────┬────────┬────────────┬──────────┬──────────┬──────────┬──────────┐")
print(f"  │ {'Function':<28s} │ {'Iter':>5s} │ {'Unique':>6s} │ {'Cover%':>8s} │ {'Reuse':>6s} │ {'Collisions':>10s} │ {'Avg(B)':>8s} │ {'Q1(B)':>8s} │ {'Q2(B)':>8s} │ {'Q3(B)':>8s} │")
print(f"  ├──────────────────────────────┼───────┼────────┼──────────┼────────┼────────────┼──────────┼──────────┼──────────┼──────────┤")
if not table_rows:
    print(f"  │ {'(no k6 summaries found)':<28s} │ {'-':>5s} │ {'-':>6s} │ {'-':>8s} │ {'-':>6s} │ {'-':>10s} │ {'-':>8s} │ {'-':>8s} │ {'-':>8s} │ {'-':>8s} │")
else:
    for row in table_rows:
        coverage_disp = f"{row['coverage']}%" if row["coverage"] != "-" else "-"
        print(
            f"  │ {row['function']:<28s} │ {row['iterations']:>5d} │ {row['unique']:>6s} │ {coverage_disp:>8s} │ "
            f"{row['reuse']:>6s} │ {row['collisions']:>10s} │ {row['payload_avg_b']:>8s} │ {row['payload_q1_b']:>8s} │ "
            f"{row['payload_q2_b']:>8s} │ {row['payload_q3_b']:>8s} │"
        )
print(f"  └──────────────────────────────┴───────┴────────┴──────────┴────────┴────────────┴──────────┴──────────┴──────────┴──────────┘")
PYEOF

    log ""
    log "API:        http://${vm_ip}:30080/v1/functions"
    log "Prometheus: http://${vm_ip}:30090"
    log "Results:    ${results_dir}/"
    log ""
}

# ─── Main ──────────────────────────────────────────────────────────────────────
main() {
    parse_args "$@"
    if [[ "${INTERACTIVE}" == "true" ]]; then
        launch_interactive_configurator
        return
    fi
    log "Starting registry-based E2E load test..."
    log "  VM=${VM_NAME} CPUS=${CPUS} MEM=${MEMORY} DISK=${DISK} KEEP_VM=${KEEP_VM}"
    log "  IMAGE_REPO=${IMAGE_REPO} BASE_IMAGE_TAG=${BASE_IMAGE_TAG} ARM64_TAG_SUFFIX=${ARM64_TAG_SUFFIX}"
    log "  TAG_SUFFIX=${TAG_SUFFIX:-<auto>}"
    log "  CURL_IMAGE=${CURL_IMAGE}"
    log "  HELM_TIMEOUT=${HELM_TIMEOUT}"
    log "  LOADTEST_WORKLOADS=${LOADTEST_WORKLOADS} LOADTEST_RUNTIMES=${LOADTEST_RUNTIMES}"
    log "  CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME} (kind=$(e2e_runtime_kind))"
    log "  INVOCATION_MODE=${INVOCATION_MODE} K6_STAGE_SEQUENCE=${K6_STAGE_SEQUENCE:-<default>}"
    log "  K6_PAYLOAD_MODE=${K6_PAYLOAD_MODE} K6_PAYLOAD_POOL_SIZE=${K6_PAYLOAD_POOL_SIZE}"
    log "  SUMMARY_ONLY=${SUMMARY_ONLY} REFRESH_SUMMARY_METRICS=${REFRESH_SUMMARY_METRICS}"
    log ""

    if [[ "${SUMMARY_ONLY}" == "true" ]]; then
        log "Summary-only mode: skipping VM provisioning, deployment, and load generation."
        derive_loadtest_window_from_results
        print_summary
        return
    fi

    guard_runtime_support

    trap cleanup EXIT

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

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
