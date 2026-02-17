#!/usr/bin/env bash
set -uo pipefail

# E2E test for runtime config hot-update API.
# Starts the control-plane locally with the admin API enabled,
# then exercises GET / PATCH / validate endpoints and verifies
# that config changes take effect at runtime.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ─── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

E2E_PASS=0
E2E_FAIL=0
E2E_TESTS_RUN=()

pass() { E2E_PASS=$((E2E_PASS + 1)); E2E_TESTS_RUN+=("[PASS] $1"); echo -e "${GREEN}  PASS${NC}: $1"; }
fail() { E2E_FAIL=$((E2E_FAIL + 1)); E2E_TESTS_RUN+=("[FAIL] $1"); echo -e "${RED}  FAIL${NC}: $1 — $2"; }

API_PORT=18080
MGMT_PORT=18081
BASE_URL="http://localhost:${API_PORT}"
METRICS_URL="http://localhost:${MGMT_PORT}/actuator/prometheus"
CP_PID=""

cleanup() {
    if [[ -n "${CP_PID}" ]]; then
        echo "Stopping control-plane (PID ${CP_PID})..."
        kill "${CP_PID}" 2>/dev/null || true
        wait "${CP_PID}" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ─── Build ───────────────────────────────────────────────────────────────────
echo "Building control-plane..."
(cd "${PROJECT_ROOT}" && ./gradlew :control-plane:bootJar -x test --quiet) || { echo "Build failed"; exit 1; }

# ─── Start control-plane ─────────────────────────────────────────────────────
echo "Starting control-plane on ports ${API_PORT}/${MGMT_PORT}..."
BOOT_JAR=$(ls "${PROJECT_ROOT}/control-plane/build/libs"/*.jar | grep -v plain | sort -V | tail -1)

java -jar "${BOOT_JAR}" \
    --server.port="${API_PORT}" \
    --management.server.port="${MGMT_PORT}" \
    --nanofaas.admin.runtime-config.enabled=true \
    --sync-queue.enabled=false \
    > /tmp/e2e-runtime-config-cp.log 2>&1 &
CP_PID=$!

# Wait for health
echo "Waiting for control-plane to be ready..."
for i in $(seq 1 30); do
    if curl -sf "http://localhost:${MGMT_PORT}/actuator/health" > /dev/null 2>&1; then
        echo "Control-plane ready after ${i}s"
        break
    fi
    if [[ $i -eq 30 ]]; then
        echo -e "${RED}Control-plane failed to start. Log:${NC}"
        tail -30 /tmp/e2e-runtime-config-cp.log
        exit 1
    fi
    sleep 1
done

# ─── Tests ───────────────────────────────────────────────────────────────────

# Test 1: GET returns initial snapshot
test_get_initial_snapshot() {
    local response
    response=$(curl -sf "${BASE_URL}/v1/admin/runtime-config")
    local revision
    revision=$(echo "${response}" | python3 -c "import sys,json; print(json.load(sys.stdin)['revision'])")

    if [[ "${revision}" == "0" ]]; then
        pass "GET /runtime-config returns revision 0"
    else
        fail "GET /runtime-config returns revision 0" "got revision=${revision}"
    fi

    local rate
    rate=$(echo "${response}" | python3 -c "import sys,json; print(json.load(sys.stdin)['rateMaxPerSecond'])")
    if [[ "${rate}" -gt 0 ]]; then
        pass "GET /runtime-config has valid rateMaxPerSecond=${rate}"
    else
        fail "GET /runtime-config has valid rateMaxPerSecond" "got ${rate}"
    fi
}

# Test 2: POST /validate accepts valid patch
test_validate_valid() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/v1/admin/runtime-config/validate" \
        -H 'Content-Type: application/json' \
        -d '{"rateMaxPerSecond": 500}')
    if [[ "${status}" == "200" ]]; then
        pass "POST /validate accepts valid patch (200)"
    else
        fail "POST /validate accepts valid patch" "got HTTP ${status}"
    fi
}

# Test 3: POST /validate rejects invalid patch
test_validate_invalid() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/v1/admin/runtime-config/validate" \
        -H 'Content-Type: application/json' \
        -d '{"rateMaxPerSecond": -1}')
    if [[ "${status}" == "422" ]]; then
        pass "POST /validate rejects invalid patch (422)"
    else
        fail "POST /validate rejects invalid patch" "got HTTP ${status}"
    fi
}

# Test 4: PATCH updates rate and returns new revision
test_patch_rate() {
    local response
    response=$(curl -sf -X PATCH "${BASE_URL}/v1/admin/runtime-config" \
        -H 'Content-Type: application/json' \
        -d '{"expectedRevision": 0, "rateMaxPerSecond": 555}')

    local revision
    revision=$(echo "${response}" | python3 -c "import sys,json; print(json.load(sys.stdin)['revision'])")
    if [[ "${revision}" == "1" ]]; then
        pass "PATCH rateMaxPerSecond returns revision 1"
    else
        fail "PATCH rateMaxPerSecond returns revision 1" "got revision=${revision}"
    fi

    local eff_rate
    eff_rate=$(echo "${response}" | python3 -c "import sys,json; print(json.load(sys.stdin)['effectiveConfig']['rateMaxPerSecond'])")
    if [[ "${eff_rate}" == "555" ]]; then
        pass "PATCH rateMaxPerSecond=555 applied"
    else
        fail "PATCH rateMaxPerSecond=555 applied" "got ${eff_rate}"
    fi
}

# Test 5: GET reflects updated config
test_get_after_patch() {
    local response
    response=$(curl -sf "${BASE_URL}/v1/admin/runtime-config")
    local rate
    rate=$(echo "${response}" | python3 -c "import sys,json; print(json.load(sys.stdin)['rateMaxPerSecond'])")
    if [[ "${rate}" == "555" ]]; then
        pass "GET after PATCH shows rateMaxPerSecond=555"
    else
        fail "GET after PATCH shows rateMaxPerSecond=555" "got ${rate}"
    fi
}

# Test 6: PATCH with wrong revision returns 409
test_patch_conflict() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -X PATCH "${BASE_URL}/v1/admin/runtime-config" \
        -H 'Content-Type: application/json' \
        -d '{"expectedRevision": 999, "rateMaxPerSecond": 100}')
    if [[ "${status}" == "409" ]]; then
        pass "PATCH with wrong revision returns 409"
    else
        fail "PATCH with wrong revision returns 409" "got HTTP ${status}"
    fi
}

# Test 7: PATCH with invalid value returns 422
test_patch_invalid() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -X PATCH "${BASE_URL}/v1/admin/runtime-config" \
        -H 'Content-Type: application/json' \
        -d '{"expectedRevision": 1, "rateMaxPerSecond": -10}')
    if [[ "${status}" == "422" ]]; then
        pass "PATCH with invalid value returns 422"
    else
        fail "PATCH with invalid value returns 422" "got HTTP ${status}"
    fi
}

# Test 8: PATCH without expectedRevision returns 400
test_patch_missing_revision() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -X PATCH "${BASE_URL}/v1/admin/runtime-config" \
        -H 'Content-Type: application/json' \
        -d '{"rateMaxPerSecond": 100}')
    if [[ "${status}" == "400" ]]; then
        pass "PATCH without expectedRevision returns 400"
    else
        fail "PATCH without expectedRevision returns 400" "got HTTP ${status}"
    fi
}

# Test 9: PATCH sync queue params
test_patch_sync_queue() {
    local response
    response=$(curl -sf -X PATCH "${BASE_URL}/v1/admin/runtime-config" \
        -H 'Content-Type: application/json' \
        -d '{"expectedRevision": 1, "syncQueueAdmissionEnabled": false, "syncQueueMaxEstimatedWait": "PT10S", "syncQueueRetryAfterSeconds": 5}')

    local revision
    revision=$(echo "${response}" | python3 -c "import sys,json; print(json.load(sys.stdin)['revision'])")
    if [[ "${revision}" == "2" ]]; then
        pass "PATCH sync queue params returns revision 2"
    else
        fail "PATCH sync queue params returns revision 2" "got revision=${revision}"
    fi

    local admission
    admission=$(echo "${response}" | python3 -c "import sys,json; print(json.load(sys.stdin)['effectiveConfig']['syncQueueAdmissionEnabled'])")
    if [[ "${admission}" == "False" ]]; then
        pass "PATCH syncQueueAdmissionEnabled=false applied"
    else
        fail "PATCH syncQueueAdmissionEnabled=false applied" "got ${admission}"
    fi

    local retry
    retry=$(echo "${response}" | python3 -c "import sys,json; print(json.load(sys.stdin)['effectiveConfig']['syncQueueRetryAfterSeconds'])")
    if [[ "${retry}" == "5" ]]; then
        pass "PATCH syncQueueRetryAfterSeconds=5 applied"
    else
        fail "PATCH syncQueueRetryAfterSeconds=5 applied" "got ${retry}"
    fi
}

# Test 10: Prometheus metrics for config updates
test_prometheus_metrics() {
    local metrics
    metrics=$(curl -sf "${METRICS_URL}")

    if echo "${metrics}" | grep -q 'controlplane_runtime_config_revision'; then
        pass "Prometheus: controlplane_runtime_config_revision present"
    else
        fail "Prometheus: controlplane_runtime_config_revision present" "metric not found"
    fi

    if echo "${metrics}" | grep -q 'controlplane_runtime_config_updates_total'; then
        pass "Prometheus: controlplane_runtime_config_updates_total present"
    else
        fail "Prometheus: controlplane_runtime_config_updates_total present" "metric not found"
    fi

    local success_count
    success_count=$(echo "${metrics}" | grep 'controlplane_runtime_config_updates_total{status="success"}' | awk '{print $2}')
    if [[ -n "${success_count}" ]] && (( $(echo "${success_count} >= 2.0" | bc -l) )); then
        pass "Prometheus: success count >= 2 (got ${success_count})"
    else
        fail "Prometheus: success count >= 2" "got ${success_count:-empty}"
    fi
}

# Test 11: Sequential PATCH updates (optimistic locking works end-to-end)
test_sequential_patches() {
    # Read current revision
    local current
    current=$(curl -sf "${BASE_URL}/v1/admin/runtime-config" | python3 -c "import sys,json; print(json.load(sys.stdin)['revision'])")

    # First patch succeeds
    local status1
    status1=$(curl -s -o /dev/null -w '%{http_code}' \
        -X PATCH "${BASE_URL}/v1/admin/runtime-config" \
        -H 'Content-Type: application/json' \
        -d "{\"expectedRevision\": ${current}, \"rateMaxPerSecond\": 800}")

    # Second patch with same old revision fails
    local status2
    status2=$(curl -s -o /dev/null -w '%{http_code}' \
        -X PATCH "${BASE_URL}/v1/admin/runtime-config" \
        -H 'Content-Type: application/json' \
        -d "{\"expectedRevision\": ${current}, \"rateMaxPerSecond\": 900}")

    if [[ "${status1}" == "200" && "${status2}" == "409" ]]; then
        pass "Sequential PATCHes: first succeeds (200), second conflicts (409)"
    else
        fail "Sequential PATCHes: first succeeds, second conflicts" "got ${status1} and ${status2}"
    fi
}

# ─── Run all tests ───────────────────────────────────────────────────────────
echo ""
echo "=========================================="
echo "  Runtime Config E2E Tests"
echo "=========================================="
echo ""

test_get_initial_snapshot
test_validate_valid
test_validate_invalid
test_patch_rate
test_get_after_patch
test_patch_conflict
test_patch_invalid
test_patch_missing_revision
test_patch_sync_queue
test_prometheus_metrics
test_sequential_patches

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=========================================="
echo "  Results: ${E2E_PASS} passed, ${E2E_FAIL} failed"
echo "=========================================="
for t in "${E2E_TESTS_RUN[@]}"; do
    echo "  ${t}"
done
echo ""

if [[ ${E2E_FAIL} -gt 0 ]]; then
    exit 1
fi
