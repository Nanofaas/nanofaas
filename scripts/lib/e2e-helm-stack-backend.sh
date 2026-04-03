#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/scenario-manifest.sh"

VM_NAME=${VM_NAME:-nanofaas-e2e}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas}
KEEP_VM=${KEEP_VM:-true}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}

log() {
    printf '[e2e-helm-stack] %s\n' "$1"
}

log "Running Helm stack compatibility workflow"
log "VM=${VM_NAME} NAMESPACE=${NAMESPACE} CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME} KEEP_VM=${KEEP_VM}"

if [[ -n "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
    workloads=()
    runtimes=()
    mapfile -t selected_targets < <(scenario_load_targets)
    if [[ "${#selected_targets[@]}" -eq 0 ]]; then
        mapfile -t selected_targets < <(scenario_selected_functions)
    fi

    for function_key in "${selected_targets[@]}"; do
        runtime=$(scenario_function_runtime "${function_key}" || true)
        family=$(scenario_function_family "${function_key}" || true)
        if [[ "${runtime}" == "go" ]]; then
            echo "helm-stack does not support go runtime load selection" >&2
            exit 1
        fi
        workloads+=("${family}")
        runtimes+=("${runtime}")
    done

    if [[ "${#workloads[@]}" -gt 0 ]]; then
        export LOADTEST_WORKLOADS
        LOADTEST_WORKLOADS=$(printf '%s\n' "${workloads[@]}" | awk 'NF && !seen[$0]++ {print $0}' | paste -sd, -)
    fi
    if [[ "${#runtimes[@]}" -gt 0 ]]; then
        export LOADTEST_RUNTIMES
        LOADTEST_RUNTIMES=$(printf '%s\n' "${runtimes[@]}" | awk 'NF && !seen[$0]++ {print $0}' | paste -sd, -)
    fi
fi

# This compatibility backend delegates to the maintained experiment flows that
# cover Helm deployment, load generation, and autoscaling verification end-to-end.
bash "${PROJECT_ROOT}/experiments/e2e-loadtest-registry.sh"
bash "${PROJECT_ROOT}/experiments/e2e-autoscaling.sh"

log "Helm stack compatibility workflow: PASSED"
