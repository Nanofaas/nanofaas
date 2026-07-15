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
RUNTIME_PID_FILE=""
WATCHDOG_EXIT_CODE=""

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

start_watchdog_warm_http() {
    RUNTIME_PID_FILE=$(mktemp)

    WARM=true \
    WARM_PORT="$WATCHDOG_PORT" \
    EXECUTION_MODE=HTTP \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/http_server.py" \
    RUNTIME_URL="http://127.0.0.1:8081/invoke" \
    HEALTH_URL="http://127.0.0.1:8081/health" \
    RUNTIME_PID_FILE="$RUNTIME_PID_FILE" \
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
    if [ -n "$RUNTIME_PID_FILE" ]; then
        rm -f "$RUNTIME_PID_FILE"
        RUNTIME_PID_FILE=""
    fi
}

wait_for_watchdog_exit() {
    local attempts=50
    while [ "$attempts" -gt 0 ]; do
        if ! kill -0 "$WATCHDOG_PID" 2>/dev/null; then
            if wait "$WATCHDOG_PID"; then
                WATCHDOG_EXIT_CODE=0
            else
                WATCHDOG_EXIT_CODE=$?
            fi
            WATCHDOG_PID=""
            return 0
        fi
        sleep 0.1
        attempts=$((attempts - 1))
    done
    return 1
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

test_warm_http_runtime_death_exits_watchdog() {
    start_test "warm_http_runtime_death"

    start_watchdog_warm_http

    local response runtime_pid
    response=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -H "X-Execution-Id: exec-warm-http-001" \
        -d '{"input":"hello"}')
    assert_json_field "$response" ".result" "HELLO"

    runtime_pid=$(cat "$RUNTIME_PID_FILE")
    kill -TERM "$runtime_pid"

    if ! wait_for_watchdog_exit; then
        fail_test "watchdog stayed alive after its HTTP runtime exited"
        cleanup_warm_test
        return 1
    fi
    assert_not_contains "$WATCHDOG_EXIT_CODE" "0" "watchdog exits with failure"

    cleanup_warm_test
}

test_warm_http_sigterm_stops_runtime() {
    start_test "warm_http_sigterm"

    start_watchdog_warm_http

    local runtime_pid
    runtime_pid=$(cat "$RUNTIME_PID_FILE")
    kill -TERM "$WATCHDOG_PID"

    if ! wait_for_watchdog_exit; then
        fail_test "watchdog did not stop after SIGTERM"
        cleanup_warm_test
        return 1
    fi
    assert_equals "$WATCHDOG_EXIT_CODE" "0" "watchdog exits cleanly on SIGTERM"
    if kill -0 "$runtime_pid" 2>/dev/null; then
        fail_test "runtime stayed alive after watchdog SIGTERM"
    else
        pass_test
    fi

    cleanup_warm_test
}

run_warm_tests() {
    echo ""
    echo "Running WARM Tests"
    echo "------------------"

    test_warm_health_check
    test_warm_stdio_single_invocation
    test_warm_missing_execution_id_is_400
    test_warm_http_runtime_death_exits_watchdog
    test_warm_http_sigterm_stops_runtime

    echo ""
    echo "WARM Tests: $TESTS_PASSED passed, $TESTS_FAILED failed"
    print_summary
}
