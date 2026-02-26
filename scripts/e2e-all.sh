#!/usr/bin/env bash
set -euo pipefail

#
# Run all E2E test suites sequentially.
#
# Each suite creates its own VM and destroys it when done (KEEP_VM=false).
# The helm+loadtest+autoscaling group shares a single VM.
#
# Usage:
#   ./scripts/e2e-all.sh                    # Run all suites
#   ./scripts/e2e-all.sh --skip docker      # Skip Docker-based suites (e2e, buildpack)
#   ./scripts/e2e-all.sh --only k3s-curl cold-start  # Run only specific suites
#   DRY_RUN=true ./scripts/e2e-all.sh       # Print what would run without executing
#   MULTIPASS_PURGE=never ./scripts/e2e-all.sh --only helm-stack
#
# Suites (in execution order):
#   docker          - e2e.sh (local Docker containers)
#   buildpack       - e2e-buildpack.sh (buildpack images)
#   k3s-curl        - e2e-k3s-curl.sh (k3s + curl tests)
#   k8s-vm          - e2e-k8s-vm.sh (K8sE2eTest in VM)
#   cold-start      - e2e-cold-start-metrics.sh (cold start metrics)
#   cli             - e2e-cli.sh (full CLI test suite)
#   helm-stack      - e2e-k3s-helm.sh + e2e-loadtest.sh + e2e-autoscaling.sh
#   cli-host        - e2e-cli-host-platform.sh (host CLI + platform)
#   deploy-host     - e2e-cli-deploy-host.sh (deploy from host)
#
# Prerequisites:
#   - Docker
#   - multipass (for k3s/VM suites)
#   - k6 (for loadtest/autoscaling)
#
# Multipass cleanup policy (handled by shared lib):
#   - MULTIPASS_PURGE=auto   (default) purge only in CI
#   - MULTIPASS_PURGE=always always run multipass purge
#   - MULTIPASS_PURGE=never  never run multipass purge
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "e2e-all"
PROJECT_VERSION=${PROJECT_VERSION:-$(sed -n "s/^[[:space:]]*version = '\\([^']\\+\\)'.*/\\1/p" "${PROJECT_ROOT}/build.gradle" | head -n1)}
PROJECT_VERSION=${PROJECT_VERSION:-0.0.0}

DRY_RUN=${DRY_RUN:-false}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}
E2E_K3S_HELM_NONINTERACTIVE=${E2E_K3S_HELM_NONINTERACTIVE:-true}
E2E_RUNTIME_KIND=$(e2e_runtime_kind)

# ─── Suite definitions ───────────────────────────────────────────────────────
# Each entry: suite_name|description|script path
#
# The helm-stack suite keeps VM alive for loadtest+autoscaling, then cleans up.
SUITES=(
    "docker|Local Docker containers|${SCRIPT_DIR}/e2e.sh"
    "buildpack|Buildpack images|${SCRIPT_DIR}/e2e-buildpack.sh"
    "k3s-curl|k3s + curl tests|${SCRIPT_DIR}/e2e-k3s-curl.sh"
    "k8s-vm|K8sE2eTest in VM|${SCRIPT_DIR}/e2e-k8s-vm.sh"
    "cold-start|Cold start metrics|${PROJECT_ROOT}/experiments/e2e-cold-start-metrics.sh"
    "cli|Full CLI test suite|${SCRIPT_DIR}/e2e-cli.sh"
    "helm-stack|Helm + loadtest + autoscaling|HELM_STACK"
    "cli-host|Host CLI + platform|${SCRIPT_DIR}/e2e-cli-host-platform.sh"
    "deploy-host|Deploy from host|${SCRIPT_DIR}/e2e-cli-deploy-host.sh"
)

# ─── Parse arguments ─────────────────────────────────────────────────────────
SKIP_SUITES=()
ONLY_SUITES=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip)
            shift
            while [[ $# -gt 0 && ! "$1" == --* ]]; do
                SKIP_SUITES+=("$1"); shift
            done
            ;;
        --only)
            shift
            while [[ $# -gt 0 && ! "$1" == --* ]]; do
                ONLY_SUITES+=("$1"); shift
            done
            ;;
        -h|--help)
            # Print header comment as help
            sed -n '3,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
            exit 0
            ;;
        *)
            error "Unknown argument: $1"
            exit 1
            ;;
    esac
done

should_run() {
    local name=$1
    if [[ ${#ONLY_SUITES[@]} -gt 0 ]]; then
        local s
        for s in "${ONLY_SUITES[@]}"; do
            [[ "$s" == "$name" ]] && return 0
        done
        return 1
    fi
    if [[ ${#SKIP_SUITES[@]} -gt 0 ]]; then
        local s
        for s in "${SKIP_SUITES[@]}"; do
            [[ "$s" == "$name" ]] && return 1
        done
    fi
    return 0
}

suite_runtime_skip_reason() {
    local name=$1
    if [[ "${E2E_RUNTIME_KIND}" != "rust" ]]; then
        return 1
    fi

    case "${name}" in
        docker)
            echo "rust runtime skip: suite uses Java-only local Docker flow."
            ;;
        buildpack)
            echo "rust runtime skip: suite uses Java buildpack E2E flow."
            ;;
        cold-start)
            echo "rust runtime skip: cold-start metrics suite is not parameterized for rust runtime."
            ;;
        deploy-host)
            echo "rust runtime skip: host deploy suite is not yet runtime-parameterized."
            ;;
        *)
            return 1
            ;;
    esac
}

read_context_value() {
    local context_file=$1
    local key=$2
    if [[ ! -s "${context_file}" ]]; then
        return 0
    fi
    sed -n "s/^${key}=//p" "${context_file}" | tail -n1
}

# ─── Execution ───────────────────────────────────────────────────────────────
PASSED_SUITES=()
FAILED_SUITES=()
SKIPPED_SUITES=()
SKIPPED_SUITE_NOTES=()
TOTAL_START=$(date +%s)

run_suite() {
    local name=$1 desc=$2 cmd=$3
    local start elapsed
    local runtime_skip_reason=""

    runtime_skip_reason=$(suite_runtime_skip_reason "${name}" || true)
    if [[ -n "${runtime_skip_reason}" ]]; then
        SKIPPED_SUITES+=("${name}")
        SKIPPED_SUITE_NOTES+=("unsupported for runtime")
        warn "SKIP: ${name} (${desc}) - ${runtime_skip_reason}"
        return 0
    fi

    if ! should_run "${name}"; then
        SKIPPED_SUITES+=("${name}")
        SKIPPED_SUITE_NOTES+=("filtered by --skip/--only")
        info "SKIP: ${name} (${desc})"
        return 0
    fi

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "  Suite: ${name} — ${desc}"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""

    if [[ "${DRY_RUN}" == "true" ]]; then
        info "[DRY RUN] Would execute: ${cmd}"
        PASSED_SUITES+=("${name}")
        return 0
    fi

    start=$(date +%s)
    if "${cmd}"; then
        elapsed=$(( $(date +%s) - start ))
        log "PASSED: ${name} (${elapsed}s)"
        PASSED_SUITES+=("${name}")
    else
        elapsed=$(( $(date +%s) - start ))
        error "FAILED: ${name} (${elapsed}s)"
        FAILED_SUITES+=("${name}")
    fi
}

run_helm_stack() {
    local name="helm-stack" desc="Helm + loadtest + autoscaling"
    local runtime_skip_reason=""

    runtime_skip_reason=$(suite_runtime_skip_reason "${name}" || true)
    if [[ -n "${runtime_skip_reason}" ]]; then
        SKIPPED_SUITES+=("${name}")
        SKIPPED_SUITE_NOTES+=("unsupported for runtime")
        warn "SKIP: ${name} (${desc}) - ${runtime_skip_reason}"
        return 0
    fi

    if ! should_run "${name}"; then
        SKIPPED_SUITES+=("${name}")
        SKIPPED_SUITE_NOTES+=("filtered by --skip/--only")
        info "SKIP: ${name} (${desc})"
        return 0
    fi

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "  Suite: ${name} — ${desc}"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""

    if [[ "${DRY_RUN}" == "true" ]]; then
        info "[DRY RUN] Would execute: e2e-k3s-helm.sh, e2e-loadtest.sh, e2e-autoscaling.sh"
        PASSED_SUITES+=("${name}")
        return 0
    fi

    local start elapsed
    local helm_vm_name="nanofaas-e2e-all-$(date +%s)"
    local helm_tag="${TAG:-v${PROJECT_VERSION}}"
    local helm_runtime="${CONTROL_PLANE_RUNTIME}"
    local helm_run_loadtest="true"
    local helm_skip_grafana=""
    local helm_loadtest_workloads=""
    local helm_loadtest_runtimes=""
    local helm_invocation_mode=""
    local helm_stage_sequence=""
    local helm_payload_mode=""
    local helm_payload_pool_size=""
    local helm_context_file
    helm_context_file="$(e2e_mktemp_file "nanofaas-helm-stack-context" ".env")"
    local suite_ok=true
    start=$(date +%s)

    # Phase 1: Helm setup (VM kept alive)
    log "Phase 1/3: Helm setup (VM=${helm_vm_name})..."
    if ! KEEP_VM=true \
        VM_NAME="${helm_vm_name}" \
        CONTROL_PLANE_RUNTIME="${CONTROL_PLANE_RUNTIME}" \
        E2E_HELM_STACK_CONTEXT_FILE="${helm_context_file}" \
        E2E_HELM_STACK_FORCE_KEEP_VM=true \
        E2E_WIZARD_FORCE_RUN_LOADTEST=false \
        E2E_WIZARD_CAPTURE_LOADTEST_CONFIG=true \
        E2E_WIZARD_DEFER_LOADTEST_EXECUTION=true \
        E2E_K3S_HELM_NONINTERACTIVE="${E2E_K3S_HELM_NONINTERACTIVE}" \
        "${SCRIPT_DIR}/e2e-k3s-helm.sh"; then
        error "Helm setup failed"
        suite_ok=false
    elif [[ -s "${helm_context_file}" ]]; then
        local context_vm context_tag context_runtime context_run_loadtest context_skip_grafana
        local context_loadtest_workloads context_loadtest_runtimes context_invocation_mode
        local context_stage_sequence context_payload_mode context_payload_pool_size
        context_vm="$(read_context_value "${helm_context_file}" "VM_NAME" || true)"
        context_tag="$(read_context_value "${helm_context_file}" "TAG" || true)"
        context_runtime="$(read_context_value "${helm_context_file}" "CONTROL_PLANE_RUNTIME" || true)"
        context_run_loadtest="$(read_context_value "${helm_context_file}" "RUN_LOADTEST" || true)"
        context_skip_grafana="$(read_context_value "${helm_context_file}" "SKIP_GRAFANA" || true)"
        context_loadtest_workloads="$(read_context_value "${helm_context_file}" "LOADTEST_WORKLOADS" || true)"
        context_loadtest_runtimes="$(read_context_value "${helm_context_file}" "LOADTEST_RUNTIMES" || true)"
        context_invocation_mode="$(read_context_value "${helm_context_file}" "INVOCATION_MODE" || true)"
        context_stage_sequence="$(read_context_value "${helm_context_file}" "K6_STAGE_SEQUENCE" || true)"
        context_payload_mode="$(read_context_value "${helm_context_file}" "K6_PAYLOAD_MODE" || true)"
        context_payload_pool_size="$(read_context_value "${helm_context_file}" "K6_PAYLOAD_POOL_SIZE" || true)"
        if [[ -n "${context_vm}" ]]; then
            helm_vm_name="${context_vm}"
        fi
        if [[ -n "${context_tag}" ]]; then
            helm_tag="${context_tag}"
        fi
        if [[ -n "${context_runtime}" ]]; then
            helm_runtime="${context_runtime}"
        fi
        if [[ -n "${context_run_loadtest}" ]]; then
            helm_run_loadtest="${context_run_loadtest}"
        fi
        helm_skip_grafana="${context_skip_grafana}"
        helm_loadtest_workloads="${context_loadtest_workloads}"
        helm_loadtest_runtimes="${context_loadtest_runtimes}"
        helm_invocation_mode="${context_invocation_mode}"
        helm_stage_sequence="${context_stage_sequence}"
        helm_payload_mode="${context_payload_mode}"
        helm_payload_pool_size="${context_payload_pool_size}"
        log "Phase 1 context: VM=${helm_vm_name} tag=${helm_tag} runtime=${helm_runtime}"
    fi

    # Phase 2: Load test (reuses helm VM)
    if [[ "${suite_ok}" == "true" ]]; then
        if [[ "${helm_run_loadtest}" != "true" ]]; then
            warn "Phase 2/3: Load test skipped by wizard configuration (RUN_LOADTEST=${helm_run_loadtest})."
        else
            local loadtest_mode
            local loadtest_modes=("sync")
            loadtest_mode="$(printf '%s' "${helm_invocation_mode}" | tr '[:upper:]' '[:lower:]')"
            case "${loadtest_mode}" in
                both)
                    loadtest_modes=("sync" "async")
                    ;;
                sync|async)
                    loadtest_modes=("${loadtest_mode}")
                    ;;
                "")
                    loadtest_modes=("sync")
                    ;;
                *)
                    warn "Unknown invocation mode '${helm_invocation_mode}' from context, defaulting to sync."
                    loadtest_modes=("sync")
                    ;;
            esac

            local mode
            for mode in "${loadtest_modes[@]}"; do
                log "Phase 2/3: Load test (${mode})..."
                if ! VM_NAME="${helm_vm_name}" \
                    CONTROL_PLANE_RUNTIME="${helm_runtime}" \
                    SKIP_GRAFANA="${helm_skip_grafana}" \
                    LOADTEST_WORKLOADS="${helm_loadtest_workloads}" \
                    LOADTEST_RUNTIMES="${helm_loadtest_runtimes}" \
                    INVOCATION_MODE="${mode}" \
                    K6_STAGE_SEQUENCE="${helm_stage_sequence}" \
                    K6_PAYLOAD_MODE="${helm_payload_mode}" \
                    K6_PAYLOAD_POOL_SIZE="${helm_payload_pool_size}" \
                    "${PROJECT_ROOT}/experiments/e2e-loadtest.sh"; then
                    error "Load test failed (${mode})"
                    suite_ok=false
                    break
                fi
            done
        fi
    fi

    # Phase 3: Autoscaling test (reuses helm VM)
    if [[ "${suite_ok}" == "true" ]]; then
        log "Phase 3/3: Autoscaling test..."
        if ! VM_NAME="${helm_vm_name}" \
            CONTROL_PLANE_RUNTIME="${helm_runtime}" \
            FUNCTION_IMAGE_TAG="${helm_tag}" \
            "${PROJECT_ROOT}/experiments/e2e-autoscaling.sh"; then
            error "Autoscaling test failed"
            suite_ok=false
        fi
    fi

    # Cleanup the shared VM
    log "Cleaning up helm-stack VM ${helm_vm_name}..."
    KEEP_VM=false VM_NAME="${helm_vm_name}" e2e_cleanup_vm
    rm -f "${helm_context_file}"

    elapsed=$(( $(date +%s) - start ))
    if [[ "${suite_ok}" == "true" ]]; then
        log "PASSED: ${name} (${elapsed}s)"
        PASSED_SUITES+=("${name}")
    else
        error "FAILED: ${name} (${elapsed}s)"
        FAILED_SUITES+=("${name}")
    fi
}

# ─── Main ────────────────────────────────────────────────────────────────────
main() {
    log "nanofaas E2E test runner"
    log "Runtime=${E2E_RUNTIME_KIND} (CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME})"
    log ""

    if [[ ${#ONLY_SUITES[@]} -gt 0 ]]; then
        log "Running only: ${ONLY_SUITES[*]}"
    elif [[ ${#SKIP_SUITES[@]} -gt 0 ]]; then
        log "Skipping: ${SKIP_SUITES[*]}"
    fi

    for entry in "${SUITES[@]}"; do
        IFS='|' read -r name desc cmd <<< "${entry}"
        if [[ "${cmd}" == "HELM_STACK" ]]; then
            run_helm_stack
        else
            run_suite "${name}" "${desc}" "${cmd}"
        fi
    done

    # ─── Final report ────────────────────────────────────────────────────
    local total_elapsed=$(( $(date +%s) - TOTAL_START ))
    local total_min=$(( total_elapsed / 60 ))
    local total_sec=$(( total_elapsed % 60 ))

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         E2E TEST RUNNER REPORT"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""
    log "  Total time: ${total_min}m ${total_sec}s"
    log ""

    if [[ ${#PASSED_SUITES[@]} -gt 0 ]]; then
        log "  Passed (${#PASSED_SUITES[@]}):"
        for s in "${PASSED_SUITES[@]}"; do log "    [PASS] ${s}"; done
    fi

    if [[ ${#SKIPPED_SUITES[@]} -gt 0 ]]; then
        warn "  Skipped (${#SKIPPED_SUITES[@]}):"
        local idx s note
        for idx in "${!SKIPPED_SUITES[@]}"; do
            s="${SKIPPED_SUITES[$idx]}"
            note="${SKIPPED_SUITE_NOTES[$idx]:-skip}"
            warn "    [SKIP] ${s} (${note})"
        done
    fi

    if [[ ${#FAILED_SUITES[@]} -gt 0 ]]; then
        error "  Failed (${#FAILED_SUITES[@]}):"
        for s in "${FAILED_SUITES[@]}"; do error "    [FAIL] ${s}"; done
        log ""
        error "SOME SUITES FAILED"
        exit 1
    fi

    log ""
    log "ALL SUITES PASSED"
}

main "$@"
