#!/usr/bin/env bash
set -euo pipefail

#
# E2E Setup: Multipass VM → k3s → Helm install nanofaas → register functions → verify
#
# Usage:
#   ./scripts/e2e-k3s-helm.sh
#     Avvia il wizard interattivo (domande + riepilogo scelte).
#
# Prerequisites:
#   - multipass (https://multipass.run)
#   - Docker (for building images)
#
# After completion, run the load test:
#   ./scripts/e2e-loadtest.sh
#

VM_NAME=${VM_NAME:-nanofaas-e2e}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas}
KEEP_VM=${KEEP_VM:-true}
SKIP_BUILD=${SKIP_BUILD:-false}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
TAG=${TAG:-e2e}
CONTROL_PLANE_NATIVE_BUILD=${CONTROL_PLANE_NATIVE_BUILD:-false}
CONTROL_PLANE_BUILD_ON_HOST=${CONTROL_PLANE_BUILD_ON_HOST:-false}
CONTROL_PLANE_ONLY=${CONTROL_PLANE_ONLY:-false}
CONTROL_PLANE_MODULES=${CONTROL_PLANE_MODULES:-all}
CONTROL_PLANE_IMAGE_BUILDER=${CONTROL_PLANE_IMAGE_BUILDER:-}
CONTROL_PLANE_IMAGE_RUN_IMAGE=${CONTROL_PLANE_IMAGE_RUN_IMAGE:-}
CONTROL_PLANE_IMAGE_PLATFORM=${CONTROL_PLANE_IMAGE_PLATFORM:-}
CONTROL_PLANE_NATIVE_IMAGE_BUILD_ARGS=${CONTROL_PLANE_NATIVE_IMAGE_BUILD_ARGS:-}
VM_EXEC_TIMEOUT_SECONDS=${VM_EXEC_TIMEOUT_SECONDS:-900}
VM_EXEC_HEARTBEAT_SECONDS=${VM_EXEC_HEARTBEAT_SECONDS:-30}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ "${E2E_K3S_HELM_NONINTERACTIVE:-false}" != "true" ]]; then
    echo "[e2e] Modalita interattiva obbligatoria: avvio configuratore..."
    exec bash "${SCRIPT_DIR}/e2e-control-plane-experiment.sh"
fi

source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "e2e"
vm_exec() { e2e_vm_exec "$@"; }

# Image tags for local build
CONTROL_IMAGE="${LOCAL_REGISTRY}/nanofaas/control-plane:${TAG}"
RUNTIME_IMAGE="${LOCAL_REGISTRY}/nanofaas/function-runtime:${TAG}"
JAVA_WORD_STATS_IMAGE="${LOCAL_REGISTRY}/nanofaas/java-word-stats:${TAG}"
JAVA_JSON_TRANSFORM_IMAGE="${LOCAL_REGISTRY}/nanofaas/java-json-transform:${TAG}"
PYTHON_WORD_STATS_IMAGE="${LOCAL_REGISTRY}/nanofaas/python-word-stats:${TAG}"
PYTHON_JSON_TRANSFORM_IMAGE="${LOCAL_REGISTRY}/nanofaas/python-json-transform:${TAG}"
BASH_WORD_STATS_IMAGE="${LOCAL_REGISTRY}/nanofaas/bash-word-stats:${TAG}"
BASH_JSON_TRANSFORM_IMAGE="${LOCAL_REGISTRY}/nanofaas/bash-json-transform:${TAG}"
JAVA_LITE_WORD_STATS_IMAGE="${LOCAL_REGISTRY}/nanofaas/java-lite-word-stats:${TAG}"
JAVA_LITE_JSON_TRANSFORM_IMAGE="${LOCAL_REGISTRY}/nanofaas/java-lite-json-transform:${TAG}"
CURL_IMAGE="${LOCAL_REGISTRY}/curlimages/curl:latest"
HOST_CONTROL_IMAGE="nanofaas/control-plane:host-${TAG}"

RESOLVED_CP_IMAGE_PLATFORM=""
RESOLVED_CP_IMAGE_BUILDER=""
RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS=""

resolve_control_plane_native_settings_for_arch() {
    local arch=${1:-}
    RESOLVED_CP_IMAGE_PLATFORM="${CONTROL_PLANE_IMAGE_PLATFORM:-}"
    RESOLVED_CP_IMAGE_BUILDER="${CONTROL_PLANE_IMAGE_BUILDER:-}"
    RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS="${CONTROL_PLANE_NATIVE_IMAGE_BUILD_ARGS:-}"

    if [[ -z "${RESOLVED_CP_IMAGE_PLATFORM}" ]]; then
        case "${arch}" in
            aarch64|arm64)
                RESOLVED_CP_IMAGE_PLATFORM="linux/arm64"
                ;;
            x86_64|amd64)
                RESOLVED_CP_IMAGE_PLATFORM="linux/amd64"
                ;;
        esac
    fi
    if [[ -z "${RESOLVED_CP_IMAGE_BUILDER}" && "${RESOLVED_CP_IMAGE_PLATFORM}" == "linux/arm64" ]]; then
        RESOLVED_CP_IMAGE_BUILDER="paketobuildpacks/builder-jammy-java-tiny:latest"
    fi
    if [[ -z "${RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS}" ]]; then
        local memory_upper
        memory_upper="$(printf '%s' "${MEMORY}" | tr '[:lower:]' '[:upper:]')"
        case "${memory_upper}" in
            [0-8]G|8G)
                RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS="-H:+AddAllCharsets -J-Xmx4g -J-XX:ActiveProcessorCount=2"
                ;;
            9G|10G|11G|12G)
                RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS="-H:+AddAllCharsets -J-Xmx6g -J-XX:ActiveProcessorCount=3"
                ;;
            *)
                RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS="-H:+AddAllCharsets -J-Xmx8g -J-XX:ActiveProcessorCount=4"
                ;;
        esac
    fi
}

build_control_plane_image_on_host() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        return
    fi
    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" != "true" ]]; then
        return
    fi
    if ! command -v docker >/dev/null 2>&1; then
        err "docker non trovato sul host. Avvia Docker Desktop e riprova."
        exit 1
    fi

    if [[ "${CONTROL_PLANE_NATIVE_BUILD}" == "true" ]]; then
        local host_arch
        host_arch="$(uname -m | tr -d '\r\n')"
        resolve_control_plane_native_settings_for_arch "${host_arch}"

        local native_cmd
        native_cmd="cd ${PROJECT_ROOT} && NATIVE_IMAGE_BUILD_ARGS='${RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS}' BP_OCI_SOURCE=https://github.com/miciav/nanofaas ./gradlew :control-plane:bootBuildImage -PcontrolPlaneImage=${HOST_CONTROL_IMAGE} -PcontrolPlaneModules=${CONTROL_PLANE_MODULES} --no-daemon"
        if [[ -n "${RESOLVED_CP_IMAGE_BUILDER}" ]]; then
            native_cmd="${native_cmd} -PimageBuilder=${RESOLVED_CP_IMAGE_BUILDER}"
        fi
        if [[ -n "${CONTROL_PLANE_IMAGE_RUN_IMAGE}" ]]; then
            native_cmd="${native_cmd} -PimageRunImage=${CONTROL_PLANE_IMAGE_RUN_IMAGE}"
        fi
        if [[ -n "${RESOLVED_CP_IMAGE_PLATFORM}" ]]; then
            native_cmd="${native_cmd} -PimagePlatform=${RESOLVED_CP_IMAGE_PLATFORM}"
        fi
        log "Building control-plane image on host (native, modules=${CONTROL_PLANE_MODULES}, imagePlatform=${RESOLVED_CP_IMAGE_PLATFORM:-auto}, builder=${RESOLVED_CP_IMAGE_BUILDER:-default})..."
        /bin/bash -lc "${native_cmd}"
    else
        log "Building control-plane image on host (JVM Dockerfile)..."
        (cd "${PROJECT_ROOT}" && ./gradlew :control-plane:bootJar --no-daemon -q)
        (cd "${PROJECT_ROOT}" && docker build -t "${HOST_CONTROL_IMAGE}" -f control-plane/Dockerfile control-plane/)
    fi
}

push_host_control_plane_image_to_registry() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        return
    fi
    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" != "true" ]]; then
        return
    fi

    local host_tar
    host_tar="$(mktemp /tmp/nanofaas-control-plane-host-image.XXXXXX.tar)"
    log "Exporting host control-plane image..."
    docker save "${HOST_CONTROL_IMAGE}" -o "${host_tar}"

    log "Copying host control-plane image to VM and pushing to ${LOCAL_REGISTRY}..."
    e2e_copy_to_vm "${host_tar}" "${VM_NAME}" "/tmp/control-plane-host-image.tar"
    rm -f "${host_tar}"

    vm_exec "sudo docker load -i /tmp/control-plane-host-image.tar"
    vm_exec "sudo docker tag ${HOST_CONTROL_IMAGE} ${CONTROL_IMAGE}"
    vm_exec "sudo docker push ${CONTROL_IMAGE}"
    vm_exec "rm -f /tmp/control-plane-host-image.tar"
    log "Host-built control-plane image pushed to registry"
}

build_control_plane_image_on_vm() {
    if [[ "${CONTROL_PLANE_NATIVE_BUILD}" == "true" ]]; then
        local module_selector="${CONTROL_PLANE_MODULES:-all}"
        local vm_arch
        vm_arch="$(vm_exec "uname -m" | tr -d '\r\n' || true)"
        resolve_control_plane_native_settings_for_arch "${vm_arch}"
        local native_cmd
        native_cmd="cd /home/ubuntu/nanofaas && NATIVE_IMAGE_BUILD_ARGS='${RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS}' BP_OCI_SOURCE=https://github.com/miciav/nanofaas ./gradlew :control-plane:bootBuildImage -PcontrolPlaneImage=${CONTROL_IMAGE} -PcontrolPlaneModules=${module_selector} --no-daemon"
        if [[ -n "${RESOLVED_CP_IMAGE_BUILDER}" ]]; then
            native_cmd="${native_cmd} -PimageBuilder=${RESOLVED_CP_IMAGE_BUILDER}"
        fi
        if [[ -n "${CONTROL_PLANE_IMAGE_RUN_IMAGE}" ]]; then
            native_cmd="${native_cmd} -PimageRunImage=${CONTROL_PLANE_IMAGE_RUN_IMAGE}"
        fi
        if [[ -n "${RESOLVED_CP_IMAGE_PLATFORM}" ]]; then
            native_cmd="${native_cmd} -PimagePlatform=${RESOLVED_CP_IMAGE_PLATFORM}"
        fi
        log "Building control-plane as native image via buildpacks (modules=${module_selector}, imagePlatform=${RESOLVED_CP_IMAGE_PLATFORM:-auto}, builder=${RESOLVED_CP_IMAGE_BUILDER:-default}, nativeArgs=${RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS})..."
        vm_exec "${native_cmd}"
        return
    fi

    log "Building control-plane image (JVM Dockerfile)..."
    vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :control-plane:bootJar --no-daemon -q"
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${CONTROL_IMAGE} -f control-plane/Dockerfile control-plane/"
}

check_prerequisites() {
    e2e_require_multipass
}

cleanup() {
    local exit_code=$?
    if [[ "${KEEP_VM:-false}" == "true" ]]; then
        local vm_ip
        vm_ip=$(e2e_get_vm_ip) || true
        e2e_cleanup_vm
        if [[ -n "${vm_ip}" ]]; then
            warn "  API:     http://${vm_ip}:30080/v1/functions"
            warn "  Metrics: http://${vm_ip}:30081/actuator/prometheus"
            warn "  Prom UI: http://${vm_ip}:30090"
        fi
    else
        e2e_cleanup_vm
    fi
    exit "${exit_code}"
}
trap cleanup EXIT

# ─── Phase 1: Create VM ─────────────────────────────────────────────────────
create_vm() {
    e2e_ensure_vm_running "${VM_NAME}" "${CPUS}" "${MEMORY}" "${DISK}"
}

# ─── Phase 2: Install k3s + dependencies ─────────────────────────────────────
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

# ─── Phase 3: Build and push images to local registry ───────────────────────
sync_and_build() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        log "SKIP_BUILD=true, skipping build..."
        return
    fi

    e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "/home/ubuntu/nanofaas"

    if [[ "${CONTROL_PLANE_ONLY}" == "true" ]]; then
        log "Control-plane-only mode enabled: building only the control-plane image."
        if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" == "true" ]]; then
            log "Skipping in-VM control-plane build (host-built image already pushed to registry)."
        else
            build_control_plane_image_on_vm
        fi
        log "Control-plane-only mode: skipping function-runtime and demo image builds."
        return
    fi

    log "Building JARs and distributions..."
    if [[ "${CONTROL_PLANE_NATIVE_BUILD}" == "true" || "${CONTROL_PLANE_BUILD_ON_HOST}" == "true" ]]; then
        # Native control-plane image is built via bootBuildImage below with explicit module selector.
        vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :function-runtime:bootJar :examples:java:word-stats:bootJar :examples:java:json-transform:bootJar --no-daemon -q"
    else
        vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :control-plane:bootJar :function-runtime:bootJar :examples:java:word-stats:bootJar :examples:java:json-transform:bootJar --no-daemon -q"
    fi
    log "JARs built"

    log "Building Docker images..."
    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" == "true" ]]; then
        log "Skipping in-VM control-plane build (using host-built image already pushed to registry)."
        log "Building function-runtime Docker image..."
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${RUNTIME_IMAGE} -f function-runtime/Dockerfile function-runtime/"
    elif [[ "${CONTROL_PLANE_NATIVE_BUILD}" == "true" ]]; then
        build_control_plane_image_on_vm
        log "Building function-runtime Docker image..."
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${RUNTIME_IMAGE} -f function-runtime/Dockerfile function-runtime/"
    else
        e2e_build_core_images "/home/ubuntu/nanofaas" "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
    fi

    # Java demo images
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_WORD_STATS_IMAGE} -f examples/java/word-stats/Dockerfile examples/java/word-stats/"
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_JSON_TRANSFORM_IMAGE} -f examples/java/json-transform/Dockerfile examples/java/json-transform/"

    # Python demo images (need repo root context for function-sdk-python)
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${PYTHON_WORD_STATS_IMAGE} -f examples/python/word-stats/Dockerfile ."
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${PYTHON_JSON_TRANSFORM_IMAGE} -f examples/python/json-transform/Dockerfile ."

    # Bash demo images (need repo root for watchdog build)
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${BASH_WORD_STATS_IMAGE} -f examples/bash/word-stats/Dockerfile ."
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${BASH_JSON_TRANSFORM_IMAGE} -f examples/bash/json-transform/Dockerfile ."

    # Java lite demo images (native compilation via multi-stage, needs repo root context)
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_LITE_WORD_STATS_IMAGE} -f examples/java/word-stats-lite/Dockerfile ."
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_LITE_JSON_TRANSFORM_IMAGE} -f examples/java/json-transform-lite/Dockerfile ."

    log "All images built"
}

push_images() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        log "SKIP_BUILD=true, skipping image push..."
        return
    fi

    log "Pushing images to local registry ${LOCAL_REGISTRY}..."

    local images=()
    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" != "true" ]]; then
        images+=("${CONTROL_IMAGE}")
    fi
    if [[ "${CONTROL_PLANE_ONLY}" != "true" ]]; then
        images+=(
            "${RUNTIME_IMAGE}"
            "${JAVA_WORD_STATS_IMAGE}"
            "${JAVA_JSON_TRANSFORM_IMAGE}"
            "${PYTHON_WORD_STATS_IMAGE}"
            "${PYTHON_JSON_TRANSFORM_IMAGE}"
            "${BASH_WORD_STATS_IMAGE}"
            "${BASH_JSON_TRANSFORM_IMAGE}"
            "${JAVA_LITE_WORD_STATS_IMAGE}"
            "${JAVA_LITE_JSON_TRANSFORM_IMAGE}"
        )
    fi

    if [[ "${#images[@]}" -gt 0 ]]; then
        e2e_push_images_to_registry "${images[@]}"
    else
        log "No in-VM images to push (control-plane image already provided by host build)."
    fi

    if [[ "${CONTROL_PLANE_ONLY}" != "true" ]]; then
        # Mirror curl image into local registry for the Helm registration job.
        info "Mirroring curlimages/curl into local registry..."
        vm_exec "sudo docker pull curlimages/curl:latest"
        vm_exec "sudo docker tag curlimages/curl:latest ${CURL_IMAGE}"
        vm_exec "sudo docker push ${CURL_IMAGE}"
    else
        log "Control-plane-only mode: skipping curl image mirroring for demo registration job."
    fi

    log "All images pushed"
}

# ─── Phase 4: Helm install ──────────────────────────────────────────────────
helm_install() {
    log "Installing nanofaas via Helm..."
    local demos_enabled="true"
    if [[ "${CONTROL_PLANE_ONLY}" == "true" ]]; then
        demos_enabled="false"
        log "Control-plane-only mode: disabling demo function deployment in Helm values."
    fi

    # Uninstall previous release if present (clean state)
    vm_exec "helm uninstall nanofaas --namespace ${NAMESPACE} 2>/dev/null || true"
    vm_exec "kubectl delete namespace ${NAMESPACE} --ignore-not-found --wait=true 2>/dev/null || true"
    sleep 3

    # Create values override for local images
    vm_exec "cat > /tmp/e2e-values.yaml << ENDVALUES
namespace:
  create: false
  name: nanofaas

controlPlane:
  image:
    repository: ${LOCAL_REGISTRY}/nanofaas/control-plane
    tag: ${TAG}
    pullPolicy: Always
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
  enabled: ${demos_enabled}
  functions:
    - name: word-stats-java
      image: ${LOCAL_REGISTRY}/nanofaas/java-word-stats:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: json-transform-java
      image: ${LOCAL_REGISTRY}/nanofaas/java-json-transform:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: word-stats-python
      image: ${LOCAL_REGISTRY}/nanofaas/python-word-stats:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: json-transform-python
      image: ${LOCAL_REGISTRY}/nanofaas/python-json-transform:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: word-stats-exec
      image: ${LOCAL_REGISTRY}/nanofaas/bash-word-stats:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      runtimeMode: STDIO
    - name: json-transform-exec
      image: ${LOCAL_REGISTRY}/nanofaas/bash-json-transform:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
      runtimeMode: STDIO
    - name: word-stats-java-lite
      image: ${LOCAL_REGISTRY}/nanofaas/java-lite-word-stats:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
    - name: json-transform-java-lite
      image: ${LOCAL_REGISTRY}/nanofaas/java-lite-json-transform:${TAG}
      timeoutMs: 30000
      concurrency: 4
      queueSize: 100
      maxRetries: 3
      executionMode: DEPLOYMENT
  registerJob:
    image: ${CURL_IMAGE}
ENDVALUES"

    # Install with --create-namespace (namespace.create=false avoids the duplicate error)
    vm_exec "helm upgrade --install nanofaas /home/ubuntu/nanofaas/helm/nanofaas \
        -f /tmp/e2e-values.yaml \
        --namespace ${NAMESPACE} \
        --create-namespace \
        --wait --timeout 5m"

    log "Helm install complete"
}

# ─── Phase 5: Wait for functions and verify ──────────────────────────────────
verify() {
    log "Verifying deployment..."

    # Wait for control-plane
    vm_exec "kubectl rollout status deployment/nanofaas-control-plane -n ${NAMESPACE} --timeout=180s"
    log "  control-plane: Ready"

    local all_ok=true
    local vm_ip
    vm_ip=$(e2e_get_vm_ip)

    if [[ "${CONTROL_PLANE_ONLY}" != "true" ]]; then
        # Wait for demo function pods (10 total: 1 control-plane + 1 prometheus + 8 functions)
        log "Waiting for demo function pods..."
        vm_exec 'for i in $(seq 1 90); do
            ready=$(kubectl get pods -n nanofaas --no-headers 2>/dev/null | grep -c "1/1.*Running" || echo 0)
            total=$(kubectl get pods -n nanofaas --no-headers 2>/dev/null | grep -cv Completed 2>/dev/null || echo 0)
            echo "  pods: ${ready}/${total} Running"
            if [ "${ready}" -ge 10 ]; then exit 0; fi
            sleep 5
        done
        echo "Not all pods ready" >&2
        kubectl get pods -n nanofaas
        exit 1'

        # Show pods
        vm_exec "kubectl get pods -n ${NAMESPACE}"

        # List registered functions
        log "Registered functions:"
        vm_exec "curl -sf http://localhost:30080/v1/functions"

        # Smoke-test: invoke each function
        log "Smoke-testing each function..."
        local ws_payload='{"input":{"text":"hello world test"}}'
        local jt_payload='{"input":{"data":[{"dept":"eng","salary":80000},{"dept":"sales","salary":70000}],"groupBy":"dept","operation":"count"}}'
        for fn in word-stats-java word-stats-python word-stats-exec word-stats-java-lite; do
            local code
            code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://${vm_ip}:30080/v1/functions/${fn}:invoke" \
                -H "Content-Type: application/json" -d "${ws_payload}" --max-time 30) || code="000"
            if [[ "${code}" == "200" ]]; then
                info "  ${fn}: OK (${code})"
            else
                err "  ${fn}: FAIL (${code})"
                all_ok=false
            fi
        done

        for fn in json-transform-java json-transform-python json-transform-exec json-transform-java-lite; do
            local code
            code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://${vm_ip}:30080/v1/functions/${fn}:invoke" \
                -H "Content-Type: application/json" -d "${jt_payload}" --max-time 30) || code="000"
            if [[ "${code}" == "200" ]]; then
                info "  ${fn}: OK (${code})"
            else
                err "  ${fn}: FAIL (${code})"
                all_ok=false
            fi
        done
    else
        log "Control-plane-only mode: skipping demo pod readiness and function smoke tests."
        vm_exec "kubectl get pods -n ${NAMESPACE}"
        log "Control-plane API function list (expected empty or user-managed):"
        vm_exec "curl -sf http://localhost:30080/v1/functions"
    fi

    # Prometheus check
    local prom_code
    prom_code=$(curl -s -o /dev/null -w "%{http_code}" "http://${vm_ip}:30090/api/v1/query?query=up" --max-time 5) || prom_code="000"
    if [[ "${prom_code}" == "200" ]]; then
        info "  prometheus: OK"
    else
        err "  prometheus: FAIL (${prom_code})"
        all_ok=false
    fi

    if [[ "${all_ok}" == "true" ]]; then
        log "All verifications passed"
    else
        err "Some verifications failed"
        exit 1
    fi
}

print_summary() {
    local vm_ip
    vm_ip=$(e2e_get_vm_ip) || vm_ip="<VM_IP>"

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         NANOFAAS E2E SETUP COMPLETE"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""
    log "VM: ${VM_NAME} (${vm_ip})"
    log ""
    log "Endpoints:"
    log "  API:        http://${vm_ip}:30080/v1/functions"
    log "  Metrics:    http://${vm_ip}:30081/actuator/prometheus"
    log "  Prometheus: http://${vm_ip}:30090"
    log ""
    if [[ "${CONTROL_PLANE_ONLY}" == "true" ]]; then
        log "Next step — register functions before running load tests:"
        log "  1) Register your functions via API/Helm"
        log "  2) Run: ./scripts/e2e-loadtest.sh"
    else
        log "Next step — run the load test:"
        log "  ./scripts/e2e-loadtest.sh"
    fi
    log ""
}

main() {
    log "Starting nanofaas E2E setup..."
    log "  VM=${VM_NAME} CPUS=${CPUS} MEM=${MEMORY} DISK=${DISK}"
    log "  NAMESPACE=${NAMESPACE} LOCAL_REGISTRY=${LOCAL_REGISTRY} SKIP_BUILD=${SKIP_BUILD}"
    log "  TAG=${TAG} CONTROL_PLANE_NATIVE_BUILD=${CONTROL_PLANE_NATIVE_BUILD} CONTROL_PLANE_BUILD_ON_HOST=${CONTROL_PLANE_BUILD_ON_HOST} CONTROL_PLANE_ONLY=${CONTROL_PLANE_ONLY} CONTROL_PLANE_MODULES=${CONTROL_PLANE_MODULES}"
    log "  VM_EXEC_TIMEOUT_SECONDS=${VM_EXEC_TIMEOUT_SECONDS} VM_EXEC_HEARTBEAT_SECONDS=${VM_EXEC_HEARTBEAT_SECONDS}"
    log ""

    check_prerequisites
    build_control_plane_image_on_host
    create_vm
    install_deps
    install_k3s
    e2e_setup_local_registry "${LOCAL_REGISTRY}"
    push_host_control_plane_image_to_registry
    sync_and_build
    push_images
    helm_install
    verify
    print_summary
}

main "$@"
