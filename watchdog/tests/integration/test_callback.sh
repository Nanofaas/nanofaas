#!/bin/bash
#
# Integration tests for callback functionality
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

# ============================================================================
# Callback Tests
# ============================================================================

test_callback_success() {
    local test_name="callback_success"
    start_test "$test_name"

    start_callback_server "success"

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-001" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    local count=$(get_callback_count)
    assert_equals "$count" "1" "$test_name - callback received"

    stop_callback_server
    end_test "$test_name"
}

test_callback_format() {
    local test_name="callback_format"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-002" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "format test"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)

    # Verify callback structure
    assert_json_not_null "$callback" ".callback.payload.success" "$test_name - has success field"
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name - success value"

    stop_callback_server
    end_test "$test_name"
}

test_callback_error_format() {
    local test_name="callback_error_format"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-003" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=error_exit \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)

    # Verify error callback structure
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name - success false"
    assert_json_not_null "$callback" ".callback.payload.error.code" "$test_name - has error code"
    assert_json_not_null "$callback" ".callback.payload.error.message" "$test_name - has error message"

    stop_callback_server
    end_test "$test_name"
}

test_callback_url_with_execution_id() {
    local test_name="callback_url_path"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-004" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)

    # Verify callback path includes execution ID
    assert_json_contains "$callback" "exec-cb-004" "$test_name - execution ID in path"

    stop_callback_server
    end_test "$test_name"
}

test_callback_retry_on_error() {
    local test_name="callback_retry"
    start_test "$test_name"

    # Start callback server that fails first 2 attempts
    start_callback_server "fail_then_succeed"

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-005" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    # Should have retried multiple times
    local count=$(get_callback_count)

    if [ "$count" -ge 3 ]; then
        pass_test
    else
        fail_test "Expected at least 3 callback attempts, got $count"
    fi

    stop_callback_server
    end_test "$test_name"
}

test_callback_trace_id_header() {
    local test_name="callback_trace_id"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-006" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    TRACE_ID="trace-xyz-789" \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)

    # Verify trace ID is in headers
    assert_json_contains "$callback" "trace-xyz-789" "$test_name - trace ID header"

    stop_callback_server
    end_test "$test_name"
}

test_callback_always_sent_on_timeout() {
    local test_name="callback_on_timeout"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-007" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=500 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=hang \
    $WATCHDOG_BIN &
    wait $! || true

    local count=$(get_callback_count)
    local callback=$(get_last_callback)

    # Callback must be sent even on timeout
    assert_equals "$count" "1" "$test_name - callback sent"
    assert_json_contains "$callback" "TIMEOUT" "$test_name - timeout error"

    stop_callback_server
    end_test "$test_name"
}

test_callback_always_sent_on_crash() {
    local test_name="callback_on_crash"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-008" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=crash \
    $WATCHDOG_BIN &
    wait $! || true

    local count=$(get_callback_count)

    # Callback must be sent even on crash
    assert_equals "$count" "1" "$test_name - callback sent on crash"

    stop_callback_server
    end_test "$test_name"
}

test_callback_no_trace_id() {
    local test_name="callback_no_trace"
    start_test "$test_name"

    start_callback_server

    # Run without TRACE_ID
    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-009" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)

    # Should still work without trace ID
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"

    stop_callback_server
    end_test "$test_name"
}

test_callback_output_included() {
    local test_name="callback_output"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-010" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "hello world"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)

    # Output should be included in callback
    assert_json_not_null "$callback" ".callback.payload.output" "$test_name - has output"
    assert_json_contains "$callback" "HELLO WORLD" "$test_name - correct output"

    stop_callback_server
    end_test "$test_name"
}

test_callback_exhaustion_fails_watchdog() {
    local test_name="callback_exhaustion"
    start_test "$test_name"

    start_callback_server "error_503"

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-cb-011" \
    EXECUTION_MODE=STDIO \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="python3 ${FIXTURES_DIR}/stdio_handler.py" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    local watchdog_pid=$!

    local exit_code
    if wait "$watchdog_pid"; then
        exit_code=0
    else
        exit_code=$?
    fi

    assert_equals "$(get_callback_count)" "3" "$test_name - retries exhausted"
    if [ "$exit_code" -eq 0 ]; then
        fail_test "$test_name - watchdog exited successfully after callback failure"
    else
        pass_test
    fi

    stop_callback_server
    end_test "$test_name"
}

# ============================================================================
# Run all Callback tests
# ============================================================================

run_callback_tests() {
    echo "========================================"
    echo "Running Callback Tests"
    echo "========================================"

    test_callback_success
    test_callback_format
    test_callback_error_format
    test_callback_url_with_execution_id
    test_callback_retry_on_error
    test_callback_trace_id_header
    test_callback_always_sent_on_timeout
    test_callback_always_sent_on_crash
    test_callback_no_trace_id
    test_callback_output_included
    test_callback_exhaustion_fails_watchdog

    echo ""
    echo "Callback Tests: $TESTS_PASSED passed, $TESTS_FAILED failed"
}

# Run if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    run_callback_tests
fi
