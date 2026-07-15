#!/bin/bash
#
# Integration tests for STDIO mode
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

# ============================================================================
# STDIO Mode Tests
# ============================================================================

test_stdio_success() {
    local test_name="stdio_success"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-001" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "hello world"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"
    assert_json_contains "$callback" "HELLO WORLD" "$test_name - uppercase"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_echo() {
    local test_name="stdio_echo"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-002" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"key": "value", "number": 123}' \
    TEST_SCENARIO=echo \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"
    assert_json_contains "$callback" '"key"' "$test_name - echoed key"
    assert_json_contains "$callback" '"number"' "$test_name - echoed number"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_error_exit() {
    local test_name="stdio_error_exit"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-003" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=error_exit \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name"
    assert_json_contains "$callback" "FUNCTION_ERROR" "$test_name - error code"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_exit_code_42() {
    local test_name="stdio_exit_42"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-004" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=error_exit_42 \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name"
    assert_json_contains "$callback" "42" "$test_name - exit code in message"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_timeout() {
    local test_name="stdio_timeout"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-005" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=1000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=hang \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name"
    assert_json_contains "$callback" "TIMEOUT" "$test_name - timeout error"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_timeout_terminates_process_group() {
    local test_name="stdio_timeout_terminates_process_group"
    start_test "$test_name"

    local handler_pid_file child_pid_file
    handler_pid_file=$(mktemp)
    child_pid_file=$(mktemp)
    rm -f "$handler_pid_file" "$child_pid_file"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-process-group" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=500 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=spawn_child_hang \
    HANDLER_PID_FILE="$handler_pid_file" \
    CHILD_PID_FILE="$child_pid_file" \
    $WATCHDOG_BIN &
    wait $! || true

    local handler_pid child_pid
    handler_pid=$(cat "$handler_pid_file")
    child_pid=$(cat "$child_pid_file")

    if kill -0 "$handler_pid" 2>/dev/null || kill -0 "$child_pid" 2>/dev/null; then
        fail_test "timed-out handler process group is still running"
    else
        pass_test
    fi

    rm -f "$handler_pid_file" "$child_pid_file"
    stop_callback_server
    end_test "$test_name"
}

test_stdio_invalid_json() {
    local test_name="stdio_invalid_json"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-006" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=invalid_json \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name"
    assert_json_contains "$callback" "Invalid JSON" "$test_name - parse error"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_empty_output() {
    local test_name="stdio_empty_output"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-007" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=empty_output \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_large_output() {
    local test_name="stdio_large_output"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-008" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=10000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=large_output \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_with_stderr() {
    local test_name="stdio_stderr"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-009" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=stderr_output \
    $WATCHDOG_BIN &
    wait $! || true

    # Should succeed even with stderr output
    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_command_not_found() {
    local test_name="stdio_command_not_found"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-010" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="/nonexistent/command" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name"
    assert_json_contains "$callback" "FUNCTION_ERROR" "$test_name - spawn error"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_null_payload() {
    local test_name="stdio_null_payload"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-011" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='null' \
    TEST_SCENARIO=echo \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"

    stop_callback_server
    end_test "$test_name"
}

test_stdio_complex_json() {
    local test_name="stdio_complex_json"
    start_test "$test_name"

    start_callback_server

    local complex_payload='{"array": [1,2,3], "nested": {"key": "value"}, "unicode": "\u00e9\u00e8"}'

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-stdio-012" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD="$complex_payload" \
    TEST_SCENARIO=echo \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"
    assert_json_contains "$callback" '"array"' "$test_name - array preserved"
    assert_json_contains "$callback" '"nested"' "$test_name - nested preserved"

    stop_callback_server
    end_test "$test_name"
}

# ============================================================================
# Run all STDIO tests
# ============================================================================

run_stdio_tests() {
    echo "========================================"
    echo "Running STDIO Mode Tests"
    echo "========================================"

    test_stdio_success
    test_stdio_echo
    test_stdio_error_exit
    test_stdio_exit_code_42
    test_stdio_timeout
    test_stdio_timeout_terminates_process_group
    test_stdio_invalid_json
    test_stdio_empty_output
    test_stdio_large_output
    test_stdio_with_stderr
    test_stdio_command_not_found
    test_stdio_null_payload
    test_stdio_complex_json

    echo ""
    echo "STDIO Mode Tests: $TESTS_PASSED passed, $TESTS_FAILED failed"
}

# Run if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    run_stdio_tests
fi
