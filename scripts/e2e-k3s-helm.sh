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
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_VERSION=${PROJECT_VERSION:-$(sed -n "s/^[[:space:]]*version = '\\([^']\\+\\)'.*/\\1/p" "${PROJECT_ROOT}/build.gradle" | head -n1)}
PROJECT_VERSION=${PROJECT_VERSION:-0.0.0}
TAG=${TAG:-v${PROJECT_VERSION}}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}
CONTROL_PLANE_NATIVE_BUILD=${CONTROL_PLANE_NATIVE_BUILD:-false}
CONTROL_PLANE_BUILD_ON_HOST=${CONTROL_PLANE_BUILD_ON_HOST:-false}
CONTROL_PLANE_ONLY=${CONTROL_PLANE_ONLY:-false}
HOST_REBUILD_IMAGES=${HOST_REBUILD_IMAGES:-true}
HOST_REBUILD_IMAGE_REFS=${HOST_REBUILD_IMAGE_REFS:-}
HOST_JAVA_NATIVE_IMAGE_REFS=${HOST_JAVA_NATIVE_IMAGE_REFS:-}
LOADTEST_WORKLOADS=${LOADTEST_WORKLOADS:-word-stats,json-transform}
LOADTEST_RUNTIMES=${LOADTEST_RUNTIMES:-java,java-lite,python,exec}
PROM_CONTAINER_METRICS_ENABLED=${PROM_CONTAINER_METRICS_ENABLED:-true}
PROM_CONTAINER_METRICS_MODE=${PROM_CONTAINER_METRICS_MODE:-kubelet}
PROM_CONTAINER_METRICS_KUBELET_INSECURE_SKIP_VERIFY=${PROM_CONTAINER_METRICS_KUBELET_INSECURE_SKIP_VERIFY:-true}
CONTROL_PLANE_MODULES=${CONTROL_PLANE_MODULES:-all}
CONTROL_PLANE_IMAGE_BUILDER=${CONTROL_PLANE_IMAGE_BUILDER:-}
CONTROL_PLANE_IMAGE_RUN_IMAGE=${CONTROL_PLANE_IMAGE_RUN_IMAGE:-}
CONTROL_PLANE_IMAGE_PLATFORM=${CONTROL_PLANE_IMAGE_PLATFORM:-}
CONTROL_PLANE_NATIVE_IMAGE_BUILD_ARGS=${CONTROL_PLANE_NATIVE_IMAGE_BUILD_ARGS:-}
CONTROL_PLANE_NATIVE_ACTIVE_PROCESSORS=${CONTROL_PLANE_NATIVE_ACTIVE_PROCESSORS:-}
VM_EXEC_TIMEOUT_SECONDS=${VM_EXEC_TIMEOUT_SECONDS:-900}
VM_EXEC_HEARTBEAT_SECONDS=${VM_EXEC_HEARTBEAT_SECONDS:-30}
E2E_HELM_STACK_CONTEXT_FILE=${E2E_HELM_STACK_CONTEXT_FILE:-}
E2E_HELM_STACK_FORCE_KEEP_VM=${E2E_HELM_STACK_FORCE_KEEP_VM:-false}

if [[ "${E2E_HELM_STACK_FORCE_KEEP_VM}" == "true" ]]; then
    KEEP_VM="true"
fi

if [[ "${E2E_K3S_HELM_NONINTERACTIVE:-false}" != "true" ]]; then
    echo "[e2e] Modalita interattiva obbligatoria: avvio configuratore..."
    E2E_WIZARD_CONTEXT_FILE="${E2E_HELM_STACK_CONTEXT_FILE}" \
    E2E_WIZARD_FORCE_KEEP_VM="${E2E_HELM_STACK_FORCE_KEEP_VM}" \
    exec bash "${PROJECT_ROOT}/experiments/run.sh"
fi

source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "e2e"
vm_exec() { e2e_vm_exec "$@"; }

# Rust runtime does not support JVM/native toggle: ignore native flag defensively.
if [[ "$(e2e_runtime_kind)" == "rust" && "${CONTROL_PLANE_NATIVE_BUILD}" == "true" ]]; then
    warn "CONTROL_PLANE_NATIVE_BUILD=true is ignored for rust runtime; forcing false."
    CONTROL_PLANE_NATIVE_BUILD="false"
fi

# k3s path: enforce kubelet cAdvisor scraping and avoid cAdvisor DaemonSet installation.
if [[ "${PROM_CONTAINER_METRICS_MODE}" != "kubelet" ]]; then
    warn "PROM_CONTAINER_METRICS_MODE=${PROM_CONTAINER_METRICS_MODE} is not supported in k3s flow; forcing kubelet."
    PROM_CONTAINER_METRICS_MODE="kubelet"
fi

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
HOST_CONTROL_IMAGE=""
CONTROL_PLANE_CACHE_ROOT="${PROJECT_ROOT}/experiments/.image-cache/control-plane"
CONTROL_PLANE_CACHE_DIR=""
CONTROL_PLANE_CACHE_MANIFEST=""

RESOLVED_CP_IMAGE_PLATFORM=""
RESOLVED_CP_IMAGE_BUILDER=""
RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS=""
SELECTED_WORKLOADS=()
SELECTED_RUNTIMES=()
SELECTED_DEMO_FUNCTIONS=()
DEMO_FUNCTIONS_YAML=""

detect_local_cpu_count() {
    if command -v getconf >/dev/null 2>&1; then
        getconf _NPROCESSORS_ONLN 2>/dev/null && return
    fi
    if command -v nproc >/dev/null 2>&1; then
        nproc 2>/dev/null && return
    fi
    if command -v sysctl >/dev/null 2>&1; then
        sysctl -n hw.logicalcpu 2>/dev/null && return
    fi
    echo 4
}

resolve_control_plane_native_settings_for_arch() {
    local arch=${1:-}
    local active_processors=${2:-}
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
    if [[ -z "${active_processors}" ]]; then
        active_processors="$(detect_local_cpu_count | tr -d '\r\n')"
    fi
    if [[ -n "${CONTROL_PLANE_NATIVE_ACTIVE_PROCESSORS}" ]]; then
        active_processors="${CONTROL_PLANE_NATIVE_ACTIVE_PROCESSORS}"
    fi
    if [[ ! "${active_processors}" =~ ^[0-9]+$ ]] || (( active_processors < 1 )); then
        active_processors=4
    fi
    if [[ -z "${RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS}" ]]; then
        RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS="-H:+AddAllCharsets -J-Xmx8g -J-XX:ActiveProcessorCount=${active_processors}"
    fi
}

compute_sha256_12() {
    local input=${1:-}
    if command -v shasum >/dev/null 2>&1; then
        printf '%s' "${input}" | shasum -a 256 | awk '{print $1}' | cut -c1-12
        return
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "${input}" | sha256sum | awk '{print $1}' | cut -c1-12
        return
    fi
    # Fallback (worst-case) to a stable sentinel; build will still function, only reuse precision degrades.
    echo "nohashsupport"
}

resolve_host_control_image_ref() {
    local runtime_kind
    runtime_kind="$(e2e_runtime_kind)"
    local build_mode
    build_mode="$(resolve_control_plane_build_mode)"
    local modules_selector="${CONTROL_PLANE_MODULES:-none}"
    local fingerprint
    fingerprint="$(compute_sha256_12 "${runtime_kind}|${build_mode}|v${PROJECT_VERSION}|${modules_selector}")"
    if [[ "${runtime_kind}" == "java" ]]; then
        echo "nanofaas/control-plane:host-java-v${PROJECT_VERSION}-${build_mode}-${fingerprint}"
    else
        echo "nanofaas/control-plane:host-${runtime_kind}-v${PROJECT_VERSION}-${fingerprint}"
    fi
}

resolve_control_plane_build_mode() {
    local runtime_kind
    runtime_kind="$(e2e_runtime_kind)"
    if [[ "${runtime_kind}" == "rust" ]]; then
        echo "rust"
        return
    fi
    if [[ "${CONTROL_PLANE_NATIVE_BUILD}" == "true" ]]; then
        echo "native"
    else
        echo "jvm"
    fi
}

array_contains() {
    local needle="$1"
    shift
    local item
    for item in "$@"; do
        if [[ "${item}" == "${needle}" ]]; then
            return 0
        fi
    done
    return 1
}

normalize_csv_selection() {
    local raw_csv="$1"
    shift
    local allowed=("$@")
    local selected=()
    local lowered
    lowered=$(printf '%s' "${raw_csv}" | tr '[:upper:]' '[:lower:]')
    local tokens=()
    IFS=',' read -r -a tokens <<< "${lowered}"
    local token
    for token in ${tokens[@]+"${tokens[@]}"}; do
        token="${token//[[:space:]]/}"
        [[ -z "${token}" ]] && continue
        if ! array_contains "${token}" "${allowed[@]}"; then
            err "Invalid value '${token}' in '${raw_csv}'. Allowed: ${allowed[*]}"
            exit 2
        fi
        if ! array_contains "${token}" ${selected[@]+"${selected[@]}"}; then
            selected+=("${token}")
        fi
    done
    if [[ ${#selected[@]} -eq 0 ]]; then
        err "Selection '${raw_csv}' produced no valid entries."
        exit 2
    fi
    echo "${selected[*]}"
}

demo_function_name() {
    local workload="$1"
    local runtime="$2"
    case "${runtime}" in
        java|java-lite|python|exec)
            echo "${workload}-${runtime}"
            ;;
        *)
            err "Unknown runtime '${runtime}'"
            exit 2
            ;;
    esac
}

host_image_exists() {
    local image_ref=${1:?image_ref is required}
    docker image inspect "${image_ref}" >/dev/null 2>&1
}

host_image_id() {
    local image_ref=${1:?image_ref is required}
    docker image inspect --format='{{.Id}}' "${image_ref}" 2>/dev/null | tr -d '\r\n'
}

host_latest_image_for_repository() {
    local repo=${1:?repo is required}
    local candidate=""
    while IFS= read -r candidate; do
        [[ -z "${candidate}" ]] && continue
        [[ "${candidate}" == *":<none>" ]] && continue
        if host_image_exists "${candidate}"; then
            echo "${candidate}"
            return 0
        fi
    done < <(docker image ls "${repo}" --format '{{.Repository}}:{{.Tag}}' 2>/dev/null || true)
    return 1
}

ensure_host_image_available_from_local_cache() {
    local target_ref=${1:?target_ref is required}
    if host_image_exists "${target_ref}"; then
        return 0
    fi
    local repo
    repo="${target_ref%:*}"
    local source_ref
    source_ref="$(host_latest_image_for_repository "${repo}" || true)"
    if [[ -z "${source_ref}" ]]; then
        return 1
    fi
    if [[ "${source_ref}" == "${target_ref}" ]]; then
        return 1
    fi
    log "Host image '${target_ref}' missing; retagging from '${source_ref}'."
    docker tag "${source_ref}" "${target_ref}"
    host_image_exists "${target_ref}"
}

csv_contains_exact() {
    local csv="${1:-}"
    local needle="${2:-}"
    [[ -z "${csv}" || -z "${needle}" ]] && return 1
    local item
    IFS=',' read -r -a items <<< "${csv}"
    for item in "${items[@]}"; do
        if [[ "${item}" == "${needle}" ]]; then
            return 0
        fi
    done
    return 1
}

should_rebuild_host_image() {
    local image_ref=${1:?image_ref is required}
    if [[ -n "${HOST_REBUILD_IMAGE_REFS}" ]]; then
        csv_contains_exact "${HOST_REBUILD_IMAGE_REFS}" "${image_ref}"
        return $?
    fi
    [[ "${HOST_REBUILD_IMAGES}" == "true" ]]
}

should_build_java_image_native() {
    local image_ref=${1:?image_ref is required}
    csv_contains_exact "${HOST_JAVA_NATIVE_IMAGE_REFS}" "${image_ref}"
}

resolve_control_plane_compatible_image_ref() {
    if [[ ! -f "${CONTROL_PLANE_CACHE_MANIFEST}" ]]; then
        return 1
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        return 1
    fi

    local runtime_kind
    runtime_kind="$(e2e_runtime_kind)"
    local build_mode
    build_mode="$(resolve_control_plane_build_mode)"
    local modules_selector="${CONTROL_PLANE_MODULES:-none}"
    python3 - "${CONTROL_PLANE_CACHE_MANIFEST}" "${runtime_kind}" "${build_mode}" "${modules_selector}" <<'PYEOF'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
expected_runtime = sys.argv[2]
expected_mode = sys.argv[3]
expected_selector = sys.argv[4]

try:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
except Exception:
    raise SystemExit(1)

manifest_modules = payload.get("selected_modules")
if not isinstance(manifest_modules, list):
    raise SystemExit(1)
expected_modules = [] if expected_selector in {"", "none"} else [item for item in expected_selector.split(",") if item]
if sorted(str(item).strip() for item in manifest_modules if str(item).strip()) != sorted(expected_modules):
    raise SystemExit(1)
if payload.get("runtime_kind") != expected_runtime:
    raise SystemExit(1)
if payload.get("build_mode") != expected_mode:
    raise SystemExit(1)
image_ref = payload.get("image_ref")
if not isinstance(image_ref, str) or not image_ref.strip():
    raise SystemExit(1)
print(image_ref.strip())
PYEOF
}

resolve_control_plane_cache_dir() {
    local runtime_kind
    runtime_kind="$(e2e_runtime_kind)"
    local build_mode
    build_mode="$(resolve_control_plane_build_mode)"
    local modules_selector="${CONTROL_PLANE_MODULES:-none}"
    local modules_hash
    modules_hash="$(compute_sha256_12 "${runtime_kind}|${build_mode}|v${PROJECT_VERSION}|${modules_selector}")"
    echo "${CONTROL_PLANE_CACHE_ROOT}/${runtime_kind}/${build_mode}/${modules_hash}"
}

control_plane_cache_manifest_is_valid() {
    if [[ ! -f "${CONTROL_PLANE_CACHE_MANIFEST}" ]]; then
        return 1
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        warn "python3 not found: cannot validate control-plane cache manifest."
        return 1
    fi
    local image_id
    image_id="$(host_image_id "${HOST_CONTROL_IMAGE}")"
    if [[ -z "${image_id}" ]]; then
        return 1
    fi

    local build_mode
    build_mode="$(resolve_control_plane_build_mode)"
    local runtime_kind
    runtime_kind="$(e2e_runtime_kind)"
    local modules_selector="${CONTROL_PLANE_MODULES:-none}"

    python3 - "${CONTROL_PLANE_CACHE_MANIFEST}" "${HOST_CONTROL_IMAGE}" "${image_id}" "${runtime_kind}" "${build_mode}" "${modules_selector}" <<'PYEOF'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
expected_ref = sys.argv[2]
expected_id = sys.argv[3]
expected_runtime = sys.argv[4]
expected_mode = sys.argv[5]
expected_selector = sys.argv[6]

try:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
except Exception:
    raise SystemExit(1)

expected_modules = [] if expected_selector in {"", "none"} else [item for item in expected_selector.split(",") if item]
manifest_modules = payload.get("selected_modules")
if not isinstance(manifest_modules, list):
    raise SystemExit(1)

if payload.get("runtime_kind") != expected_runtime:
    raise SystemExit(1)
if payload.get("build_mode") != expected_mode:
    raise SystemExit(1)
if payload.get("image_ref") != expected_ref:
    raise SystemExit(1)
if payload.get("image_id") != expected_id:
    raise SystemExit(1)
if sorted(str(item).strip() for item in manifest_modules if str(item).strip()) != sorted(expected_modules):
    raise SystemExit(1)
PYEOF
}

write_control_plane_cache_manifest() {
    if ! command -v python3 >/dev/null 2>&1; then
        warn "python3 not found: skipping control-plane cache manifest write."
        return
    fi
    local image_id
    image_id="$(host_image_id "${HOST_CONTROL_IMAGE}")"
    if [[ -z "${image_id}" ]]; then
        warn "Cannot determine control-plane image id for '${HOST_CONTROL_IMAGE}', skipping cache manifest write."
        return
    fi
    local build_mode
    build_mode="$(resolve_control_plane_build_mode)"
    local runtime_kind
    runtime_kind="$(e2e_runtime_kind)"
    local modules_selector="${CONTROL_PLANE_MODULES:-none}"

    mkdir -p "${CONTROL_PLANE_CACHE_DIR}"
    python3 - "${CONTROL_PLANE_CACHE_MANIFEST}" "${HOST_CONTROL_IMAGE}" "${image_id}" "${runtime_kind}" "${build_mode}" "${modules_selector}" <<'PYEOF'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

manifest_path = Path(sys.argv[1])
image_ref = sys.argv[2]
image_id = sys.argv[3]
runtime_kind = sys.argv[4]
build_mode = sys.argv[5]
modules_selector = sys.argv[6]

selected_modules = [] if modules_selector in {"", "none"} else [item for item in modules_selector.split(",") if item]
payload = {
    "runtime_kind": runtime_kind,
    "build_mode": build_mode,
    "selected_modules": sorted(selected_modules),
    "image_ref": image_ref,
    "image_id": image_id,
    "built_at": datetime.now(timezone.utc).isoformat(),
}
manifest_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PYEOF
}

should_include_demo() {
    local workload="$1"
    local runtime="$2"
    if [[ "${CONTROL_PLANE_ONLY}" == "true" ]]; then
        return 1
    fi
    array_contains "${workload}" ${SELECTED_WORKLOADS[@]+"${SELECTED_WORKLOADS[@]}"} || return 1
    array_contains "${runtime}" ${SELECTED_RUNTIMES[@]+"${SELECTED_RUNTIMES[@]}"} || return 1
    return 0
}

resolve_selected_demo_targets() {
    local allowed_workloads=("word-stats" "json-transform")
    local allowed_runtimes=("java" "java-lite" "python" "exec")
    read -r -a SELECTED_WORKLOADS <<< "$(normalize_csv_selection "${LOADTEST_WORKLOADS}" "${allowed_workloads[@]}")"
    read -r -a SELECTED_RUNTIMES <<< "$(normalize_csv_selection "${LOADTEST_RUNTIMES}" "${allowed_runtimes[@]}")"
    SELECTED_DEMO_FUNCTIONS=()
    local workload runtime
    for workload in "${SELECTED_WORKLOADS[@]}"; do
        for runtime in "${SELECTED_RUNTIMES[@]}"; do
            SELECTED_DEMO_FUNCTIONS+=("$(demo_function_name "${workload}" "${runtime}")")
        done
    done
    if [[ "${CONTROL_PLANE_ONLY}" != "true" && ${#SELECTED_DEMO_FUNCTIONS[@]} -eq 0 ]]; then
        err "No demo functions selected while CONTROL_PLANE_ONLY=false."
        exit 2
    fi
}

append_demo_function_yaml() {
    local name="$1"
    local image="$2"
    local runtime_mode="${3:-}"
    DEMO_FUNCTIONS_YAML+="    - name: ${name}"$'\n'
    DEMO_FUNCTIONS_YAML+="      image: ${image}"$'\n'
    DEMO_FUNCTIONS_YAML+="      timeoutMs: 30000"$'\n'
    DEMO_FUNCTIONS_YAML+="      concurrency: 8"$'\n'
    DEMO_FUNCTIONS_YAML+="      queueSize: 1000"$'\n'
    DEMO_FUNCTIONS_YAML+="      maxRetries: 3"$'\n'
    DEMO_FUNCTIONS_YAML+="      executionMode: DEPLOYMENT"$'\n'
    if [[ -n "${runtime_mode}" ]]; then
        DEMO_FUNCTIONS_YAML+="      runtimeMode: ${runtime_mode}"$'\n'
    fi
}

build_demo_functions_yaml() {
    DEMO_FUNCTIONS_YAML=""
    if should_include_demo "word-stats" "java"; then
        append_demo_function_yaml "word-stats-java" "${LOCAL_REGISTRY}/nanofaas/java-word-stats:${TAG}"
    fi
    if should_include_demo "json-transform" "java"; then
        append_demo_function_yaml "json-transform-java" "${LOCAL_REGISTRY}/nanofaas/java-json-transform:${TAG}"
    fi
    if should_include_demo "word-stats" "python"; then
        append_demo_function_yaml "word-stats-python" "${LOCAL_REGISTRY}/nanofaas/python-word-stats:${TAG}"
    fi
    if should_include_demo "json-transform" "python"; then
        append_demo_function_yaml "json-transform-python" "${LOCAL_REGISTRY}/nanofaas/python-json-transform:${TAG}"
    fi
    if should_include_demo "word-stats" "exec"; then
        append_demo_function_yaml "word-stats-exec" "${LOCAL_REGISTRY}/nanofaas/bash-word-stats:${TAG}" "STDIO"
    fi
    if should_include_demo "json-transform" "exec"; then
        append_demo_function_yaml "json-transform-exec" "${LOCAL_REGISTRY}/nanofaas/bash-json-transform:${TAG}" "STDIO"
    fi
    if should_include_demo "word-stats" "java-lite"; then
        append_demo_function_yaml "word-stats-java-lite" "${LOCAL_REGISTRY}/nanofaas/java-lite-word-stats:${TAG}"
    fi
    if should_include_demo "json-transform" "java-lite"; then
        append_demo_function_yaml "json-transform-java-lite" "${LOCAL_REGISTRY}/nanofaas/java-lite-json-transform:${TAG}"
    fi
    if [[ -z "${DEMO_FUNCTIONS_YAML}" ]]; then
        DEMO_FUNCTIONS_YAML="    []"$'\n'
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
    if ! should_rebuild_host_image "${HOST_CONTROL_IMAGE}"; then
        if host_image_exists "${HOST_CONTROL_IMAGE}"; then
            if control_plane_cache_manifest_is_valid; then
                log "Reusing existing host-built control-plane image: ${HOST_CONTROL_IMAGE}"
                return
            fi
            log "Reusing existing host-built control-plane image and refreshing cache manifest: ${HOST_CONTROL_IMAGE}"
            write_control_plane_cache_manifest
            return
        fi

        local compatible_source_ref=""
        compatible_source_ref="$(resolve_control_plane_compatible_image_ref || true)"
        if [[ -n "${compatible_source_ref}" ]] && host_image_exists "${compatible_source_ref}"; then
            if [[ "${compatible_source_ref}" != "${HOST_CONTROL_IMAGE}" ]]; then
                log "Host control-plane image '${HOST_CONTROL_IMAGE}' missing; retagging from compatible cached image '${compatible_source_ref}'."
                docker tag "${compatible_source_ref}" "${HOST_CONTROL_IMAGE}"
            fi
            write_control_plane_cache_manifest
            log "Reusing existing host-built control-plane image: ${HOST_CONTROL_IMAGE}"
            return
        fi
        warn "Control-plane image '${HOST_CONTROL_IMAGE}' requested from cache but missing; falling back to host build."
    fi

    if [[ "${CONTROL_PLANE_NATIVE_BUILD}" == "true" ]]; then
        local host_arch
        local host_cpu_count
        host_arch="$(uname -m | tr -d '\r\n')"
        host_cpu_count="$(detect_local_cpu_count | tr -d '\r\n')"
        resolve_control_plane_native_settings_for_arch "${host_arch}" "${host_cpu_count}"

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
    elif [[ "$(e2e_runtime_kind)" == "rust" ]]; then
        local rust_cp_dir="${CONTROL_PLANE_RUST_DIR:-experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159/snapshot/control-plane-rust}"
        log "Building control-plane image on host (Rust Dockerfile)..."
        (cd "${PROJECT_ROOT}" && docker build -t "${HOST_CONTROL_IMAGE}" -f "${rust_cp_dir}/Dockerfile" "${rust_cp_dir}/")
    else
        log "Building control-plane image on host (JVM Dockerfile)..."
        (cd "${PROJECT_ROOT}" && ./gradlew :control-plane:bootJar -PcontrolPlaneModules="${CONTROL_PLANE_MODULES}" --no-daemon -q)
        (cd "${PROJECT_ROOT}" && docker build -t "${HOST_CONTROL_IMAGE}" -f control-plane/Dockerfile control-plane/)
    fi
    write_control_plane_cache_manifest
}

build_non_control_plane_images_on_host() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        return
    fi
    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" != "true" ]]; then
        return
    fi
    if [[ "${CONTROL_PLANE_ONLY}" == "true" ]]; then
        return
    fi
    if ! command -v docker >/dev/null 2>&1; then
        err "docker non trovato sul host. Avvia Docker Desktop e riprova."
        exit 1
    fi
    local need_runtime_image=false
    local need_java_word_stats=false
    local need_java_json_transform=false
    local need_python_word_stats=false
    local need_python_json_transform=false
    local need_bash_word_stats=false
    local need_bash_json_transform=false
    local need_java_lite_word_stats=false
    local need_java_lite_json_transform=false
    local java_word_stats_mode="jvm"
    local java_json_transform_mode="jvm"

    if should_rebuild_host_image "${RUNTIME_IMAGE}" || !ensure_host_image_available_from_local_cache "${RUNTIME_IMAGE}"; then
        need_runtime_image=true
    fi

    if should_include_demo "word-stats" "java"; then
        if should_build_java_image_native "${JAVA_WORD_STATS_IMAGE}"; then
            java_word_stats_mode="native"
        fi
        if should_rebuild_host_image "${JAVA_WORD_STATS_IMAGE}" || !ensure_host_image_available_from_local_cache "${JAVA_WORD_STATS_IMAGE}"; then
            need_java_word_stats=true
        fi
    fi

    if should_include_demo "json-transform" "java"; then
        if should_build_java_image_native "${JAVA_JSON_TRANSFORM_IMAGE}"; then
            java_json_transform_mode="native"
        fi
        if should_rebuild_host_image "${JAVA_JSON_TRANSFORM_IMAGE}" || !ensure_host_image_available_from_local_cache "${JAVA_JSON_TRANSFORM_IMAGE}"; then
            need_java_json_transform=true
        fi
    fi

    if should_include_demo "word-stats" "python"; then
        if should_rebuild_host_image "${PYTHON_WORD_STATS_IMAGE}" || !ensure_host_image_available_from_local_cache "${PYTHON_WORD_STATS_IMAGE}"; then
            need_python_word_stats=true
        fi
    fi

    if should_include_demo "json-transform" "python"; then
        if should_rebuild_host_image "${PYTHON_JSON_TRANSFORM_IMAGE}" || !ensure_host_image_available_from_local_cache "${PYTHON_JSON_TRANSFORM_IMAGE}"; then
            need_python_json_transform=true
        fi
    fi

    if should_include_demo "word-stats" "exec"; then
        if should_rebuild_host_image "${BASH_WORD_STATS_IMAGE}" || !ensure_host_image_available_from_local_cache "${BASH_WORD_STATS_IMAGE}"; then
            need_bash_word_stats=true
        fi
    fi

    if should_include_demo "json-transform" "exec"; then
        if should_rebuild_host_image "${BASH_JSON_TRANSFORM_IMAGE}" || !ensure_host_image_available_from_local_cache "${BASH_JSON_TRANSFORM_IMAGE}"; then
            need_bash_json_transform=true
        fi
    fi

    if should_include_demo "word-stats" "java-lite"; then
        if should_rebuild_host_image "${JAVA_LITE_WORD_STATS_IMAGE}" || !ensure_host_image_available_from_local_cache "${JAVA_LITE_WORD_STATS_IMAGE}"; then
            need_java_lite_word_stats=true
        fi
    fi

    if should_include_demo "json-transform" "java-lite"; then
        if should_rebuild_host_image "${JAVA_LITE_JSON_TRANSFORM_IMAGE}" || !ensure_host_image_available_from_local_cache "${JAVA_LITE_JSON_TRANSFORM_IMAGE}"; then
            need_java_lite_json_transform=true
        fi
    fi

    if [[ "${need_runtime_image}" == "false" \
        && "${need_java_word_stats}" == "false" \
        && "${need_java_json_transform}" == "false" \
        && "${need_python_word_stats}" == "false" \
        && "${need_python_json_transform}" == "false" \
        && "${need_bash_word_stats}" == "false" \
        && "${need_bash_json_transform}" == "false" \
        && "${need_java_lite_word_stats}" == "false" \
        && "${need_java_lite_json_transform}" == "false" ]]; then
        log "Reusing existing host-built function/runtime/demo images."
        return
    fi

    local gradle_tasks=()
    if [[ "${need_runtime_image}" == "true" ]]; then
        gradle_tasks+=(":function-runtime:bootJar")
    fi
    if [[ "${need_java_word_stats}" == "true" && "${java_word_stats_mode}" != "native" ]]; then
        gradle_tasks+=(":examples:java:word-stats:bootJar")
    fi
    if [[ "${need_java_json_transform}" == "true" && "${java_json_transform_mode}" != "native" ]]; then
        gradle_tasks+=(":examples:java:json-transform:bootJar")
    fi

    if [[ "${#gradle_tasks[@]}" -gt 0 ]]; then
        log "Building function/runtime JARs on host..."
        (cd "${PROJECT_ROOT}" && ./gradlew "${gradle_tasks[@]}" --no-daemon -q)
    fi

    if [[ "${need_runtime_image}" == "true" ]]; then
        log "Building image on host: function-runtime"
        (cd "${PROJECT_ROOT}" && docker build -t "${RUNTIME_IMAGE}" -f function-runtime/Dockerfile function-runtime/)
    fi

    if [[ "${need_java_word_stats}" == "true" ]]; then
        if [[ "${java_word_stats_mode}" == "native" ]]; then
            log "Building image on host: word-stats-java (native)"
            (cd "${PROJECT_ROOT}" && docker build -t "${JAVA_WORD_STATS_IMAGE}" -f examples/java/word-stats-lite/Dockerfile .)
        else
            log "Building image on host: word-stats-java (jvm)"
            (cd "${PROJECT_ROOT}" && docker build -t "${JAVA_WORD_STATS_IMAGE}" -f examples/java/word-stats/Dockerfile examples/java/word-stats/)
        fi
    fi

    if [[ "${need_java_json_transform}" == "true" ]]; then
        if [[ "${java_json_transform_mode}" == "native" ]]; then
            log "Building image on host: json-transform-java (native)"
            (cd "${PROJECT_ROOT}" && docker build -t "${JAVA_JSON_TRANSFORM_IMAGE}" -f examples/java/json-transform-lite/Dockerfile .)
        else
            log "Building image on host: json-transform-java (jvm)"
            (cd "${PROJECT_ROOT}" && docker build -t "${JAVA_JSON_TRANSFORM_IMAGE}" -f examples/java/json-transform/Dockerfile examples/java/json-transform/)
        fi
    fi

    if [[ "${need_python_word_stats}" == "true" ]]; then
        log "Building image on host: word-stats-python"
        (cd "${PROJECT_ROOT}" && docker build -t "${PYTHON_WORD_STATS_IMAGE}" -f examples/python/word-stats/Dockerfile .)
    fi
    if [[ "${need_python_json_transform}" == "true" ]]; then
        log "Building image on host: json-transform-python"
        (cd "${PROJECT_ROOT}" && docker build -t "${PYTHON_JSON_TRANSFORM_IMAGE}" -f examples/python/json-transform/Dockerfile .)
    fi
    if [[ "${need_bash_word_stats}" == "true" ]]; then
        log "Building image on host: word-stats-exec"
        (cd "${PROJECT_ROOT}" && docker build -t "${BASH_WORD_STATS_IMAGE}" -f examples/bash/word-stats/Dockerfile .)
    fi
    if [[ "${need_bash_json_transform}" == "true" ]]; then
        log "Building image on host: json-transform-exec"
        (cd "${PROJECT_ROOT}" && docker build -t "${BASH_JSON_TRANSFORM_IMAGE}" -f examples/bash/json-transform/Dockerfile .)
    fi
    if [[ "${need_java_lite_word_stats}" == "true" ]]; then
        log "Building image on host: word-stats-java-lite"
        (cd "${PROJECT_ROOT}" && docker build -t "${JAVA_LITE_WORD_STATS_IMAGE}" -f examples/java/word-stats-lite/Dockerfile .)
    fi
    if [[ "${need_java_lite_json_transform}" == "true" ]]; then
        log "Building image on host: json-transform-java-lite"
        (cd "${PROJECT_ROOT}" && docker build -t "${JAVA_LITE_JSON_TRANSFORM_IMAGE}" -f examples/java/json-transform-lite/Dockerfile .)
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
    host_tar="$(e2e_mktemp_file "nanofaas-control-plane-host-image" ".tar")"
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

push_host_non_control_plane_images_to_registry() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        return
    fi
    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" != "true" ]]; then
        return
    fi
    if [[ "${CONTROL_PLANE_ONLY}" == "true" ]]; then
        return
    fi

    local images=("${RUNTIME_IMAGE}")
    if should_include_demo "word-stats" "java"; then images+=("${JAVA_WORD_STATS_IMAGE}"); fi
    if should_include_demo "json-transform" "java"; then images+=("${JAVA_JSON_TRANSFORM_IMAGE}"); fi
    if should_include_demo "word-stats" "python"; then images+=("${PYTHON_WORD_STATS_IMAGE}"); fi
    if should_include_demo "json-transform" "python"; then images+=("${PYTHON_JSON_TRANSFORM_IMAGE}"); fi
    if should_include_demo "word-stats" "exec"; then images+=("${BASH_WORD_STATS_IMAGE}"); fi
    if should_include_demo "json-transform" "exec"; then images+=("${BASH_JSON_TRANSFORM_IMAGE}"); fi
    if should_include_demo "word-stats" "java-lite"; then images+=("${JAVA_LITE_WORD_STATS_IMAGE}"); fi
    if should_include_demo "json-transform" "java-lite"; then images+=("${JAVA_LITE_JSON_TRANSFORM_IMAGE}"); fi

    local host_tar
    host_tar="$(e2e_mktemp_file "nanofaas-host-function-images" ".tar")"
    log "Exporting host function/demo images..."
    docker save "${images[@]}" -o "${host_tar}"

    log "Copying host function/demo images to VM and pushing to ${LOCAL_REGISTRY}..."
    e2e_copy_to_vm "${host_tar}" "${VM_NAME}" "/tmp/host-function-images.tar"
    rm -f "${host_tar}"

    vm_exec "sudo docker load -i /tmp/host-function-images.tar"
    local image
    for image in "${images[@]}"; do
        vm_exec "sudo docker push ${image}"
    done
    vm_exec "rm -f /tmp/host-function-images.tar"
    log "Host-built function/demo images pushed to registry"
}

build_control_plane_image_on_vm() {
    if [[ "${CONTROL_PLANE_NATIVE_BUILD}" == "true" ]]; then
        local module_selector="${CONTROL_PLANE_MODULES:-all}"
        local vm_arch
        local vm_cpu_count
        vm_arch="$(vm_exec "uname -m" | tr -d '\r\n' || true)"
        vm_cpu_count="$(vm_exec "getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo ${CPUS}" | tr -d '\r\n' || true)"
        resolve_control_plane_native_settings_for_arch "${vm_arch}" "${vm_cpu_count}"
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

    if [[ "$(e2e_runtime_kind)" == "rust" ]]; then
        log "Building control-plane image (runtime=rust)..."
        e2e_build_control_plane_image "/home/ubuntu/nanofaas" "${CONTROL_IMAGE}"
        return
    fi

    log "Building control-plane image (JVM Dockerfile)..."
    vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :control-plane:bootJar -PcontrolPlaneModules=${CONTROL_PLANE_MODULES} --no-daemon -q"
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

    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" == "true" ]]; then
        log "Using host-built function-runtime/demo images; skipping in-VM image builds."
        return
    fi

    log "Building JARs and distributions..."
    if [[ "${CONTROL_PLANE_NATIVE_BUILD}" == "true" || "${CONTROL_PLANE_BUILD_ON_HOST}" == "true" ]]; then
        # Native control-plane image is built via bootBuildImage below with explicit module selector.
        vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :function-runtime:bootJar :examples:java:word-stats:bootJar :examples:java:json-transform:bootJar --no-daemon -q"
    elif [[ "$(e2e_runtime_kind)" == "rust" ]]; then
        e2e_build_control_plane_artifacts "/home/ubuntu/nanofaas"
        vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :examples:java:word-stats:bootJar :examples:java:json-transform:bootJar --no-daemon -q"
    else
        vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :control-plane:bootJar :function-runtime:bootJar :examples:java:word-stats:bootJar :examples:java:json-transform:bootJar -PcontrolPlaneModules=${CONTROL_PLANE_MODULES} --no-daemon -q"
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
    elif [[ "$(e2e_runtime_kind)" == "rust" ]]; then
        build_control_plane_image_on_vm
        log "Building function-runtime Docker image..."
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${RUNTIME_IMAGE} -f function-runtime/Dockerfile function-runtime/"
    else
        e2e_build_core_images "/home/ubuntu/nanofaas" "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
    fi

    # Java demo images
    if should_include_demo "word-stats" "java"; then
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_WORD_STATS_IMAGE} -f examples/java/word-stats/Dockerfile examples/java/word-stats/"
    fi
    if should_include_demo "json-transform" "java"; then
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_JSON_TRANSFORM_IMAGE} -f examples/java/json-transform/Dockerfile examples/java/json-transform/"
    fi

    # Python demo images (need repo root context for function-sdk-python)
    if should_include_demo "word-stats" "python"; then
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${PYTHON_WORD_STATS_IMAGE} -f examples/python/word-stats/Dockerfile ."
    fi
    if should_include_demo "json-transform" "python"; then
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${PYTHON_JSON_TRANSFORM_IMAGE} -f examples/python/json-transform/Dockerfile ."
    fi

    # Bash demo images (need repo root for watchdog build)
    if should_include_demo "word-stats" "exec"; then
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${BASH_WORD_STATS_IMAGE} -f examples/bash/word-stats/Dockerfile ."
    fi
    if should_include_demo "json-transform" "exec"; then
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${BASH_JSON_TRANSFORM_IMAGE} -f examples/bash/json-transform/Dockerfile ."
    fi

    # Java lite demo images (native compilation via multi-stage, needs repo root context)
    if should_include_demo "word-stats" "java-lite"; then
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_LITE_WORD_STATS_IMAGE} -f examples/java/word-stats-lite/Dockerfile ."
    fi
    if should_include_demo "json-transform" "java-lite"; then
        vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${JAVA_LITE_JSON_TRANSFORM_IMAGE} -f examples/java/json-transform-lite/Dockerfile ."
    fi

    log "All images built"
}

push_images() {
    if [[ "${SKIP_BUILD}" == "true" ]]; then
        log "SKIP_BUILD=true, skipping image push..."
        return
    fi

    log "Pushing images to local registry ${LOCAL_REGISTRY}..."

    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" == "true" ]]; then
        log "Skipping in-VM image push (host-built images already pushed to registry)."
    else
    local images=()
    images+=("${CONTROL_IMAGE}")
    if [[ "${CONTROL_PLANE_ONLY}" != "true" ]]; then
        images+=("${RUNTIME_IMAGE}")
        if should_include_demo "word-stats" "java"; then images+=("${JAVA_WORD_STATS_IMAGE}"); fi
        if should_include_demo "json-transform" "java"; then images+=("${JAVA_JSON_TRANSFORM_IMAGE}"); fi
        if should_include_demo "word-stats" "python"; then images+=("${PYTHON_WORD_STATS_IMAGE}"); fi
        if should_include_demo "json-transform" "python"; then images+=("${PYTHON_JSON_TRANSFORM_IMAGE}"); fi
        if should_include_demo "word-stats" "exec"; then images+=("${BASH_WORD_STATS_IMAGE}"); fi
        if should_include_demo "json-transform" "exec"; then images+=("${BASH_JSON_TRANSFORM_IMAGE}"); fi
        if should_include_demo "word-stats" "java-lite"; then images+=("${JAVA_LITE_WORD_STATS_IMAGE}"); fi
        if should_include_demo "json-transform" "java-lite"; then images+=("${JAVA_LITE_JSON_TRANSFORM_IMAGE}"); fi
    fi

    if [[ "${#images[@]}" -gt 0 ]]; then
        e2e_push_images_to_registry "${images[@]}"
    else
        log "No in-VM images to push."
    fi
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
    local sync_invoke_queue_enabled="false"
    if [[ "$(e2e_runtime_kind)" == "rust" ]]; then
        sync_invoke_queue_enabled="true"
    fi
    if [[ "${CONTROL_PLANE_ONLY}" == "true" ]]; then
        demos_enabled="false"
        log "Control-plane-only mode: disabling demo function deployment in Helm values."
    fi
    build_demo_functions_yaml
    if [[ "${demos_enabled}" == "true" && -z "${DEMO_FUNCTIONS_YAML}" ]]; then
        err "No demo functions selected for deployment."
        exit 2
    fi

    # Uninstall previous release if present (clean state)
    vm_exec "helm uninstall nanofaas --namespace ${NAMESPACE} 2>/dev/null || true"
    vm_exec "kubectl delete namespace ${NAMESPACE} --ignore-not-found --wait=true 2>/dev/null || true"
    sleep 3

    # Create values override for local images
    vm_exec "cat > /tmp/e2e-values.yaml << ENDVALUES
namespace:
  create: false
  name: ${NAMESPACE}

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
    - name: NANOFAAS_SYNC_INVOKE_QUEUE_ENABLED
      value: \"${sync_invoke_queue_enabled}\"
    - name: NANOFAAS_INTERNAL_SCALER_POLL_INTERVAL_MS
      value: \"500\"

prometheus:
  create: true
  containerMetrics:
    enabled: ${PROM_CONTAINER_METRICS_ENABLED}
    mode: ${PROM_CONTAINER_METRICS_MODE}
    kubelet:
      insecureSkipVerify: ${PROM_CONTAINER_METRICS_KUBELET_INSECURE_SKIP_VERIFY}
  service:
    type: NodePort
    port: 9090
    nodePort: 30090
  pvc:
    enabled: false

demos:
  enabled: ${demos_enabled}
  functions:
${DEMO_FUNCTIONS_YAML}
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
        local expected_ready_pods
        expected_ready_pods=$((2 + ${#SELECTED_DEMO_FUNCTIONS[@]}))
        # Wait for base pods + selected demo function pods.
        log "Waiting for demo function pods..."
        vm_exec "for i in \$(seq 1 90); do
            ready=\$(kubectl get pods -n ${NAMESPACE} --no-headers 2>/dev/null | grep -c '1/1.*Running' || echo 0)
            total=\$(kubectl get pods -n ${NAMESPACE} --no-headers 2>/dev/null | grep -cv Completed 2>/dev/null || echo 0)
            echo \"  pods: \${ready}/\${total} Running\"
            if [ \"\${ready}\" -ge ${expected_ready_pods} ]; then exit 0; fi
            sleep 5
        done
        echo \"Not all pods ready\" >&2
        kubectl get pods -n ${NAMESPACE}
        exit 1"

        # Show pods
        vm_exec "kubectl get pods -n ${NAMESPACE}"

        # List registered functions
        log "Registered functions:"
        vm_exec "curl -sf http://localhost:30080/v1/functions"

        # Smoke-test: invoke each function
        log "Smoke-testing each function..."
        local ws_payload='{"input":{"text":"hello world test"}}'
        local jt_payload='{"input":{"data":[{"dept":"eng","salary":80000},{"dept":"sales","salary":70000}],"groupBy":"dept","operation":"count"}}'
        local workload runtime payload fn code
        for workload in "${SELECTED_WORKLOADS[@]}"; do
            if [[ "${workload}" == "word-stats" ]]; then
                payload="${ws_payload}"
            else
                payload="${jt_payload}"
            fi
            for runtime in "${SELECTED_RUNTIMES[@]}"; do
                if ! should_include_demo "${workload}" "${runtime}"; then
                    continue
                fi
                fn="$(demo_function_name "${workload}" "${runtime}")"
                code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://${vm_ip}:30080/v1/functions/${fn}:invoke" \
                    -H "Content-Type: application/json" -d "${payload}" --max-time 30) || code="000"
                if [[ "${code}" == "200" ]]; then
                    info "  ${fn}: OK (${code})"
                else
                    err "  ${fn}: FAIL (${code})"
                    all_ok=false
                fi
            done
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
    if [[ "${KEEP_VM:-false}" != "true" ]]; then
        log "VM cleanup is enabled (KEEP_VM=false): the VM will be deleted at script exit."
        log "To run load tests later, re-run with KEEP_VM=true."
    elif [[ "${CONTROL_PLANE_ONLY}" == "true" ]]; then
        log "Next step — register functions before running load tests:"
        log "  1) Register your functions via API/Helm"
        log "  2) Run: ./experiments/e2e-loadtest.sh"
    else
        log "Next step — run the load test:"
        log "  ./experiments/e2e-loadtest.sh"
    fi
    log ""
}

write_helm_stack_context() {
    if [[ -z "${E2E_HELM_STACK_CONTEXT_FILE}" ]]; then
        return
    fi
    mkdir -p "$(dirname "${E2E_HELM_STACK_CONTEXT_FILE}")"
    cat > "${E2E_HELM_STACK_CONTEXT_FILE}" <<EOF
VM_NAME=${VM_NAME}
TAG=${TAG}
CONTROL_PLANE_RUNTIME=$(e2e_runtime_kind)
EOF
    log "Helm stack context written: ${E2E_HELM_STACK_CONTEXT_FILE}"
}

main() {
    log "Starting nanofaas E2E setup..."
    log "  VM=${VM_NAME} CPUS=${CPUS} MEM=${MEMORY} DISK=${DISK}"
    log "  NAMESPACE=${NAMESPACE} LOCAL_REGISTRY=${LOCAL_REGISTRY} SKIP_BUILD=${SKIP_BUILD}"
    log "  CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME}"
    log "  TAG=${TAG} CONTROL_PLANE_NATIVE_BUILD=${CONTROL_PLANE_NATIVE_BUILD} CONTROL_PLANE_BUILD_ON_HOST=${CONTROL_PLANE_BUILD_ON_HOST} CONTROL_PLANE_ONLY=${CONTROL_PLANE_ONLY} HOST_REBUILD_IMAGES=${HOST_REBUILD_IMAGES} CONTROL_PLANE_MODULES=${CONTROL_PLANE_MODULES}"
    log "  HOST_REBUILD_IMAGE_REFS=${HOST_REBUILD_IMAGE_REFS:-<none>} HOST_JAVA_NATIVE_IMAGE_REFS=${HOST_JAVA_NATIVE_IMAGE_REFS:-<none>}"
    log "  VM_EXEC_TIMEOUT_SECONDS=${VM_EXEC_TIMEOUT_SECONDS} VM_EXEC_HEARTBEAT_SECONDS=${VM_EXEC_HEARTBEAT_SECONDS}"
    if [[ "${CONTROL_PLANE_BUILD_ON_HOST}" == "true" && "${CONTROL_PLANE_ONLY}" != "true" ]]; then
        log "  Build strategy: control-plane/function-runtime/demo images on host"
    elif [[ "${CONTROL_PLANE_BUILD_ON_HOST}" == "true" ]]; then
        log "  Build strategy: control-plane image on host; runtime/demo image build disabled"
    fi
    log ""

    check_prerequisites
    resolve_selected_demo_targets
    HOST_CONTROL_IMAGE="$(resolve_host_control_image_ref)"
    CONTROL_PLANE_CACHE_DIR="$(resolve_control_plane_cache_dir)"
    CONTROL_PLANE_CACHE_MANIFEST="${CONTROL_PLANE_CACHE_DIR}/manifest.json"
    if [[ "${CONTROL_PLANE_ONLY}" != "true" ]]; then
        local selected_demo_label
        selected_demo_label=$(IFS=,; echo "${SELECTED_DEMO_FUNCTIONS[*]}")
        log "  Selected demo functions: ${selected_demo_label}"
    fi
    build_control_plane_image_on_host
    build_non_control_plane_images_on_host
    create_vm
    install_deps
    install_k3s
    e2e_setup_local_registry "${LOCAL_REGISTRY}"
    push_host_control_plane_image_to_registry
    push_host_non_control_plane_images_to_registry
    sync_and_build
    push_images
    helm_install
    verify
    write_helm_stack_context
    print_summary
}

main "$@"
