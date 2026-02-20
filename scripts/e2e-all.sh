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

DRY_RUN=${DRY_RUN:-false}

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

# ─── Execution ───────────────────────────────────────────────────────────────
PASSED_SUITES=()
FAILED_SUITES=()
SKIPPED_SUITES=()
TOTAL_START=$(date +%s)

run_suite() {
    local name=$1 desc=$2 cmd=$3
    local start elapsed

    if ! should_run "${name}"; then
        SKIPPED_SUITES+=("${name}")
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

    if ! should_run "${name}"; then
        SKIPPED_SUITES+=("${name}")
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
    local suite_ok=true
    start=$(date +%s)

    # Phase 1: Helm setup (VM kept alive)
    log "Phase 1/3: Helm setup (VM=${helm_vm_name})..."
    if ! KEEP_VM=true VM_NAME="${helm_vm_name}" "${SCRIPT_DIR}/e2e-k3s-helm.sh"; then
        error "Helm setup failed"
        suite_ok=false
    fi

    # Phase 2: Load test (reuses helm VM)
    if [[ "${suite_ok}" == "true" ]]; then
        log "Phase 2/3: Load test..."
        if ! VM_NAME="${helm_vm_name}" "${PROJECT_ROOT}/experiments/e2e-loadtest.sh"; then
            error "Load test failed"
            suite_ok=false
        fi
    fi

    # Phase 3: Autoscaling test (reuses helm VM)
    if [[ "${suite_ok}" == "true" ]]; then
        log "Phase 3/3: Autoscaling test..."
        if ! VM_NAME="${helm_vm_name}" "${PROJECT_ROOT}/experiments/e2e-autoscaling.sh"; then
            error "Autoscaling test failed"
            suite_ok=false
        fi
    fi

    # Cleanup the shared VM
    log "Cleaning up helm-stack VM ${helm_vm_name}..."
    KEEP_VM=false VM_NAME="${helm_vm_name}" e2e_cleanup_vm

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
        for s in "${SKIPPED_SUITES[@]}"; do warn "    [SKIP] ${s}"; done
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
