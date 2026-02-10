#!/bin/bash
#
# Integration tests for warm DEPLOYMENT mode (WARM=true).
#
# Expected behavior:
# - watchdog exposes HTTP server on WARM_PORT
# - each POST /invoke runs WATCHDOG_CMD according to EXECUTION_MODE (STDIO/FILE/HTTP)
# - execution id is provided via X-Execution-Id header (required)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

# Ports for warm mode testing (different from defaults to avoid conflicts)
WATCHDOG_PORT="${WATCHDOG_PORT:-18081}"

# PIDs for cleanup
WATCHDOG_PID=""

start_watchdog_warm_stdio() {
    # Start watchdog in warm mode, stdio execution. Use fixture handler.
    WARM=true \
    WARM_PORT="$WATCHDOG_PORT" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    $WATCHDOG_BIN >/dev/null 2>&1 &
    WATCHDOG_PID=$!

    wait_for_port "$WATCHDOG_PORT"
}

stop_watchdog() {
    if [ -n "$WATCHDOG_PID" ]; then
        kill "$WATCHDOG_PID" 2>/dev/null || true
        wait "$WATCHDOG_PID" 2>/dev/null || true
        WATCHDOG_PID=""
    fi
}

cleanup_warm_test() {
    stop_watchdog
    sleep 0.2
}

test_warm_health_check() {
    start_test "warm_health_check"

    start_watchdog_warm_stdio

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$WATCHDOG_PORT/health" || true)

    cleanup_warm_test

    assert_equals "$http_code" "200"
}

test_warm_stdio_single_invocation() {
    start_test "warm_stdio_single_invocation"

    start_watchdog_warm_stdio

    local http_code body
    body=$(curl -s -w "\n%{http_code}" -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -H "X-Execution-Id: exec-warm-001" \
        -d '{"input":"hello"}' || true)

    http_code="${body##*$'\n'}"
    body="${body%$'\n'*}"

    cleanup_warm_test

    if [ "$http_code" != "200" ]; then
        fail_test "Expected 200, got $http_code (body: $body)"
        return 1
    fi

    assert_json_field "$body" ".output" "HELLO"
}

test_warm_missing_execution_id_is_400() {
    start_test "warm_missing_execution_id_is_400"

    start_watchdog_warm_stdio

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{"input":"hello"}' || true)

    cleanup_warm_test

    assert_equals "$http_code" "400"
}

run_warm_tests() {
    echo ""
    echo "Running WARM Tests"
    echo "------------------"

    test_warm_health_check
    test_warm_stdio_single_invocation
    test_warm_missing_execution_id_is_400

    echo ""
    echo "WARM Tests: $TESTS_PASSED passed, $TESTS_FAILED failed"
    print_summary
}
