#!/bin/bash
#
# Integration tests for Prometheus metrics endpoint in warm mode.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

WATCHDOG_PORT="${WATCHDOG_METRICS_PORT:-18082}"
WATCHDOG_PID=""

start_watchdog_warm_stdio_with_metrics() {
    FUNCTION_NAME=echo \
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

cleanup_metrics_test() {
    stop_watchdog
    sleep 0.2
}

test_metrics_exposed_and_increments() {
    start_test "metrics_exposed_and_increments"

    start_watchdog_warm_stdio_with_metrics

    # invoke once
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -H "X-Execution-Id: exec-metrics-001" \
        -d '{"input":"hello"}' || true)

    if [ "$http_code" != "200" ]; then
        cleanup_metrics_test
        fail_test "Expected 200, got $http_code"
        return 1
    fi

    local metrics
    metrics=$(curl -s "http://127.0.0.1:$WATCHDOG_PORT/metrics" || true)

    cleanup_metrics_test

    if ! echo "$metrics" | grep -q "watchdog_invocations_total"; then
        fail_test "Expected watchdog_invocations_total in /metrics"
        return 1
    fi

    if ! echo "$metrics" | grep -Fq 'watchdog_invocations_total{function="echo",mode="STDIO",success="true"} 1'; then
        fail_test "Expected invocation counter to be 1 (got: $(echo \"$metrics\" | grep \"watchdog_invocations_total\" | head -n 3))"
        return 1
    fi

    if ! echo "$metrics" | grep -Fq 'watchdog_invocations_in_flight{function="echo"} 0'; then
        fail_test "Expected in-flight gauge to be 0"
        return 1
    fi

    pass_test
}

run_metrics_tests() {
    echo ""
    echo "Running Metrics Tests"
    echo "---------------------"

    test_metrics_exposed_and_increments

    echo ""
    echo "Metrics Tests: $TESTS_PASSED passed, $TESTS_FAILED failed"
    print_summary
}
