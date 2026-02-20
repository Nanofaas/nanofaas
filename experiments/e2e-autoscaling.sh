#!/usr/bin/env bash
set -euo pipefail

#
# E2E Autoscaling Test: verifies InternalScaler scales up under load
# and scales back down to 0 when load stops.
#
# Usage:
#   ./scripts/e2e-autoscaling.sh
#
# Prerequisites:
#   - nanofaas deployed via ./scripts/e2e-k3s-helm.sh (VM running)
#   - k6 installed (https://grafana.com/docs/k6/latest/set-up/install-k6/)
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${PROJECT_ROOT}/scripts/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "autoscale"
e2e_test_init
K6_DIR="${PROJECT_ROOT}/experiments/k6"
FUNCTION_NAME=${FUNCTION_NAME:-word-stats-java}
NAMESPACE=${NAMESPACE:-nanofaas}

# Auto-detect VM name
VM_NAME=$(e2e_auto_detect_vm)

vm_exec() { e2e_vm_exec "$@"; }
pass() { e2e_pass "$@"; }
fail() { e2e_fail "$@"; }

resolve_vm_ip() { e2e_resolve_nanofaas_url 30080; }

# Deployment name: nanofaas prefixes function deployments with "fn-"
DEPLOY_NAME="fn-${FUNCTION_NAME}"

get_replicas() { e2e_get_ready_replicas "${NAMESPACE}" "$1"; }
get_desired_replicas() { e2e_get_desired_replicas "${NAMESPACE}" "$1"; }

# ─── Pre-flight checks ──────────────────────────────────────────────────────
preflight() {
    log "Pre-flight checks..."

    if ! command -v k6 &>/dev/null; then
        err "k6 is not installed. Install from https://grafana.com/docs/k6/latest/set-up/install-k6/"
        exit 1
    fi

    local nanofaas_url
    nanofaas_url=$(resolve_vm_ip)

    if ! curl -sf "${nanofaas_url}/v1/functions" >/dev/null 2>&1; then
        err "Cannot reach ${nanofaas_url}/v1/functions"
        err "Is nanofaas running? Run ./scripts/e2e-k3s-helm.sh first."
        exit 1
    fi

    info "API reachable at ${nanofaas_url}"
}

# ─── Phase A: Register function with scaling config ──────────────────────────
phase_a_register() {
    local nanofaas_url
    nanofaas_url=$(resolve_vm_ip)

    log ""
    log "━━━ Phase A: Register function with scaling config ━━━"

    # Delete existing function (ignore errors)
    info "Deleting existing ${FUNCTION_NAME} (if any)..."
    curl -sf -X DELETE "${nanofaas_url}/v1/functions/${FUNCTION_NAME}" >/dev/null 2>&1 || true
    sleep 2

    info "Registering ${FUNCTION_NAME} with INTERNAL scaling (minReplicas=0, maxReplicas=5)..."
    local http_code
    http_code=$(curl -sf -o /dev/null -w '%{http_code}' -X POST \
        "${nanofaas_url}/v1/functions" \
        -H 'Content-Type: application/json' \
        -d '{
            "name": "'"${FUNCTION_NAME}"'",
            "image": "localhost:5000/nanofaas/java-word-stats:e2e",
            "timeoutMs": 30000,
            "concurrency": 4,
            "queueSize": 100,
            "maxRetries": 3,
            "executionMode": "DEPLOYMENT",
            "scalingConfig": {
                "strategy": "INTERNAL",
                "minReplicas": 0,
                "maxReplicas": 5,
                "metrics": [{"type": "in_flight", "target": "2"}]
            }
        }')

    if [[ "${http_code}" == "200" || "${http_code}" == "201" ]]; then
        pass "Function registered (HTTP ${http_code})"
    else
        fail "Function registration failed (HTTP ${http_code})"
        return 1
    fi

    # Wait for initial deployment to be created
    info "Waiting for deployment to be created..."
    local i
    for i in $(seq 1 30); do
        if vm_exec "kubectl get deployment ${DEPLOY_NAME} -n ${NAMESPACE} >/dev/null 2>&1"; then
            pass "Deployment created"
            return 0
        fi
        sleep 2
    done
    fail "Deployment not created after 60s"
    return 1
}

# ─── Phase B: Verify baseline replicas ──────────────────────────────────────
phase_b_baseline() {
    log ""
    log "━━━ Phase B: Verify baseline replicas ━━━"

    # Wait for deployment to settle
    info "Waiting for deployment to settle (up to 60s)..."
    local i replicas
    for i in $(seq 1 12); do
        replicas=$(get_replicas "${DEPLOY_NAME}")
        info "  Ready replicas: ${replicas}"
        if [[ "${replicas}" -ge 1 ]]; then
            break
        fi
        sleep 5
    done

    local desired
    desired=$(get_desired_replicas "${DEPLOY_NAME}")
    info "Baseline: desired=${desired}, ready=${replicas}"
    pass "Baseline recorded (desired=${desired}, ready=${replicas})"
}

# ─── Phase C: Apply load and verify scale-up ────────────────────────────────
phase_c_load_and_scaleup() {
    local nanofaas_url
    nanofaas_url=$(resolve_vm_ip)

    log ""
    log "━━━ Phase C: Apply load and verify scale-up ━━━"

    info "Starting k6 load test in background..."
    k6 run \
        --env "NANOFAAS_URL=${nanofaas_url}" \
        --env "FUNCTION_NAME=${FUNCTION_NAME}" \
        "${K6_DIR}/autoscaling.js" \
        > /tmp/k6-autoscaling.log 2>&1 &
    local k6_pid=$!
    info "k6 PID: ${k6_pid}"

    local max_replicas=0
    local scaled_up=false
    local poll_count=0
    local max_polls=24  # 24 * 5s = 120s

    info "Polling replicas every 5s for up to 120s..."
    while [[ ${poll_count} -lt ${max_polls} ]]; do
        sleep 5
        ((poll_count++))

        local replicas desired
        replicas=$(get_replicas "${DEPLOY_NAME}")
        desired=$(get_desired_replicas "${DEPLOY_NAME}")

        if [[ ${desired} -gt ${max_replicas} ]]; then
            max_replicas=${desired}
        fi
        if [[ ${replicas} -gt ${max_replicas} ]]; then
            max_replicas=${replicas}
        fi

        info "  [${poll_count}/${max_polls}] desired=${desired} ready=${replicas} max_seen=${max_replicas}"

        if [[ ${max_replicas} -gt 1 ]]; then
            scaled_up=true
        fi

        # If k6 finished, stop polling early
        if ! kill -0 "${k6_pid}" 2>/dev/null; then
            info "k6 finished"
            break
        fi
    done

    # Wait for k6 to finish if still running
    if kill -0 "${k6_pid}" 2>/dev/null; then
        info "Waiting for k6 to finish..."
        wait "${k6_pid}" || true
    fi

    if [[ "${scaled_up}" == "true" ]]; then
        pass "Scale-up observed: max replicas = ${max_replicas}"
    else
        fail "Scale-up NOT observed: max replicas stayed at ${max_replicas}"
    fi

    MAX_REPLICAS_OBSERVED=${max_replicas}
}

# ─── Phase D: Wait for scale-down to zero ───────────────────────────────────
phase_d_scaledown() {
    log ""
    log "━━━ Phase D: Wait for scale-down to zero ━━━"

    info "Waiting 90s for scale-down cooldown (60s cooldown + 30s margin)..."
    sleep 90

    local scaled_down=false
    local poll_count=0
    local max_polls=24  # 24 * 5s = 120s

    info "Polling replicas every 5s for up to 120s..."
    while [[ ${poll_count} -lt ${max_polls} ]]; do
        local desired
        desired=$(get_desired_replicas "${DEPLOY_NAME}")

        info "  [${poll_count}/${max_polls}] desired=${desired}"

        if [[ "${desired}" == "0" ]]; then
            scaled_down=true
            break
        fi

        sleep 5
        ((poll_count++))
    done

    if [[ "${scaled_down}" == "true" ]]; then
        pass "Scale-down to 0 verified (desired replicas = 0)"
    else
        local final_desired
        final_desired=$(get_desired_replicas "${DEPLOY_NAME}")
        fail "Scale-down to 0 NOT observed: desired replicas = ${final_desired}"
    fi
}

# ─── Phase E: Report ────────────────────────────────────────────────────────
phase_e_report() {
    local final_desired final_ready
    final_desired=$(get_desired_replicas "${DEPLOY_NAME}")
    final_ready=$(get_replicas "${DEPLOY_NAME}")

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         AUTOSCALING TEST REPORT"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""
    log "  Function:         ${FUNCTION_NAME}"
    log "  Max replicas:     ${MAX_REPLICAS_OBSERVED:-0}"
    log "  Final desired:    ${final_desired}"
    log "  Final ready:      ${final_ready}"
    log ""
    log "  Passed: ${E2E_PASS}"
    log "  Failed: ${E2E_FAIL}"
    log ""

    if [[ "${E2E_FAIL}" -gt 0 ]]; then
        err "AUTOSCALING TEST FAILED"
        # Dump logs for debugging
        info "Control-plane logs (last 30 lines):"
        vm_exec "kubectl logs -n ${NAMESPACE} -l app=control-plane --tail=30" 2>/dev/null || true
        info "k6 output (last 20 lines):"
        tail -20 /tmp/k6-autoscaling.log 2>/dev/null || true
        exit 1
    else
        log "AUTOSCALING TEST PASSED"
    fi
}

# ─── Main ────────────────────────────────────────────────────────────────────
MAX_REPLICAS_OBSERVED=0

main() {
    preflight
    phase_a_register
    phase_b_baseline
    phase_c_load_and_scaleup
    phase_d_scaledown
    phase_e_report
}

main "$@"
