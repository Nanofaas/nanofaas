#!/bin/bash
#
# Integration tests for FILE mode
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

# ============================================================================
# FILE Mode Tests
# ============================================================================

test_file_success() {
    local test_name="file_success"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-001" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/test_input.json" \
    OUTPUT_FILE="/tmp/test_output.json" \
    INVOCATION_PAYLOAD='{"input": "hello"}' \
    TEST_SCENARIO=success \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"

    stop_callback_server
    end_test "$test_name"
}

test_file_echo() {
    local test_name="file_echo"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-002" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/test_input.json" \
    OUTPUT_FILE="/tmp/test_output.json" \
    INVOCATION_PAYLOAD='{"data": "test", "value": 42}' \
    TEST_SCENARIO=echo \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"
    assert_json_contains "$callback" '"data"' "$test_name - echoed data"

    stop_callback_server
    end_test "$test_name"
}

test_file_error_exit() {
    local test_name="file_error_exit"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-003" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/test_input.json" \
    OUTPUT_FILE="/tmp/test_output.json" \
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

test_file_no_output() {
    local test_name="file_no_output"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-004" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/test_input.json" \
    OUTPUT_FILE="/tmp/test_output.json" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=no_output \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name"
    assert_json_contains "$callback" "not created" "$test_name - file not created"

    stop_callback_server
    end_test "$test_name"
}

test_file_empty_output() {
    local test_name="file_empty_output"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-005" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/test_input.json" \
    OUTPUT_FILE="/tmp/test_output.json" \
    INVOCATION_PAYLOAD='{"input": "test"}' \
    TEST_SCENARIO=empty_output \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "false" "$test_name"

    stop_callback_server
    end_test "$test_name"
}

test_file_invalid_json() {
    local test_name="file_invalid_json"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-006" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/test_input.json" \
    OUTPUT_FILE="/tmp/test_output.json" \
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

test_file_timeout() {
    local test_name="file_timeout"
    start_test "$test_name"

    start_callback_server

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-007" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=1000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/test_input.json" \
    OUTPUT_FILE="/tmp/test_output.json" \
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

test_file_custom_paths() {
    local test_name="file_custom_paths"
    start_test "$test_name"

    start_callback_server

    # Use custom file paths
    mkdir -p /tmp/custom

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-008" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/custom/in.json" \
    OUTPUT_FILE="/tmp/custom/out.json" \
    INVOCATION_PAYLOAD='{"input": "custom paths"}' \
    TEST_SCENARIO=echo \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"

    # Cleanup
    rm -rf /tmp/custom

    stop_callback_server
    end_test "$test_name"
}

test_file_large_input() {
    local test_name="file_large_input"
    start_test "$test_name"

    start_callback_server

    # Create a large payload
    local large_input=$(python3 -c 'import json; print(json.dumps({"data": "x" * 100000}))')

    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-009" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=10000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_handler.sh" \
    INPUT_FILE="/tmp/test_input.json" \
    OUTPUT_FILE="/tmp/test_output.json" \
    INVOCATION_PAYLOAD="$large_input" \
    TEST_SCENARIO=echo \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"

    stop_callback_server
    end_test "$test_name"
}

test_file_input_env_passed() {
    local test_name="file_input_env"
    start_test "$test_name"

    start_callback_server

    # Test that INPUT_FILE and OUTPUT_FILE env vars are passed to the script
    CALLBACK_URL="http://127.0.0.1:$CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="exec-file-010" \
    EXECUTION_MODE=FILE \
    TIMEOUT_MS=5000 \
    WATCHDOG_CMD="${FIXTURES_DIR}/file_env_echo.sh" \
    INPUT_FILE="/tmp/env_in.json" \
    OUTPUT_FILE="/tmp/env_out.json" \
    INVOCATION_PAYLOAD='{"test": true}' \
    $WATCHDOG_BIN &
    wait $! || true

    local callback=$(get_last_callback)
    assert_json_field "$callback" ".callback.payload.success" "true" "$test_name"
    assert_json_contains "$callback" "env_in.json" "$test_name - INPUT_FILE passed"

    stop_callback_server
    end_test "$test_name"
}

# ============================================================================
# Run all FILE tests
# ============================================================================

run_file_tests() {
    echo "========================================"
    echo "Running FILE Mode Tests"
    echo "========================================"

    test_file_success
    test_file_echo
    test_file_error_exit
    test_file_no_output
    test_file_empty_output
    test_file_invalid_json
    test_file_timeout
    test_file_custom_paths
    test_file_large_input
    test_file_input_env_passed

    echo ""
    echo "FILE Mode Tests: $TESTS_PASSED passed, $TESTS_FAILED failed"
}

# Run if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    run_file_tests
fi
