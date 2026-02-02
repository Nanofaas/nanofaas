#!/bin/bash
#
# Integration tests for WARM mode
#
# This test file manages its own process lifecycle and doesn't rely on
# test_helpers.sh process cleanup to avoid conflicts with warm mode's
# persistent server pattern.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Test counters (duplicated from test_helpers.sh to avoid trap conflicts)
TESTS_PASSED=0
TESTS_FAILED=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

# Default paths
TESTS_ROOT="${TESTS_ROOT:-/tests}"
FIXTURES_DIR="${FIXTURES_DIR:-${TESTS_ROOT}/fixtures}"
WATCHDOG_BIN="${WATCHDOG_BIN:-/usr/local/bin/watchdog}"

# Port utilities
wait_for_port() {
    local port="$1"
    local max_wait="${2:-30}"
    local elapsed=0

    while ! nc -z 127.0.0.1 "$port" 2>/dev/null; do
        sleep 0.1
        ((elapsed++)) || true
        if [ "$elapsed" -ge "$((max_wait * 10))" ]; then
            echo "Timeout waiting for port $port" >&2
            return 1
        fi
    done
}

print_summary() {
    echo ""
    echo "========================================"
    echo "Test Summary"
    echo "========================================"
    echo -e "Passed: ${GREEN}$TESTS_PASSED${NC}"
    echo -e "Failed: ${RED}$TESTS_FAILED${NC}"
    echo "Total:  $((TESTS_PASSED + TESTS_FAILED))"
    echo ""

    if [ "$TESTS_FAILED" -gt 0 ]; then
        return 1
    fi
    return 0
}

# Ports for warm mode testing (different from defaults to avoid conflicts)
RUNTIME_PORT="${RUNTIME_PORT:-18080}"
WATCHDOG_PORT="${WATCHDOG_PORT:-18081}"
WARM_CALLBACK_PORT="${WARM_CALLBACK_PORT:-18082}"

# PIDs for cleanup
RUNTIME_PID=""
WATCHDOG_PID=""
WARM_CALLBACK_PID=""

# ============================================================================
# Setup/Teardown
# ============================================================================

start_warm_callback_server() {
    # Start callback server using the fixtures callback_server.py
    CALLBACK_PORT="$WARM_CALLBACK_PORT" \
    CALLBACK_SCENARIO="success" \
    python3 ${FIXTURES_DIR}/callback_server.py &
    WARM_CALLBACK_PID=$!
    wait_for_port "$WARM_CALLBACK_PORT"
}

stop_warm_callback_server() {
    if [ -n "$WARM_CALLBACK_PID" ]; then
        kill "$WARM_CALLBACK_PID" 2>/dev/null || true
        # Don't wait - just kill
        WARM_CALLBACK_PID=""
    fi
}

start_mock_runtime() {
    local scenario="${1:-success}"
    PORT=$RUNTIME_PORT \
    TEST_SCENARIO="$scenario" \
    python3 ${FIXTURES_DIR}/http_server.py &
    RUNTIME_PID=$!
    wait_for_port "$RUNTIME_PORT"
}

stop_mock_runtime() {
    if [ -n "$RUNTIME_PID" ]; then
        kill "$RUNTIME_PID" 2>/dev/null || true
        wait "$RUNTIME_PID" 2>/dev/null || true
        RUNTIME_PID=""
    fi
}

start_watchdog_warm() {
    # Note: We use "sleep 99999" instead of "sleep infinity" for macOS compatibility
    # The watchdog spawns this but doesn't actually use it since we have a mock runtime
    EXECUTION_MODE=WARM \
    CALLBACK_URL="http://127.0.0.1:$WARM_CALLBACK_PORT/v1/internal/executions" \
    EXECUTION_ID="warm-ignored" \
    WARM_PORT=$WATCHDOG_PORT \
    WATCHDOG_CMD="sleep 99999" \
    RUNTIME_URL="http://127.0.0.1:$RUNTIME_PORT/invoke" \
    HEALTH_URL="http://127.0.0.1:$RUNTIME_PORT/health" \
    READY_TIMEOUT_MS=5000 \
    $WATCHDOG_BIN &
    WATCHDOG_PID=$!

    # Wait for watchdog warm server to be ready
    local max_wait=30
    local elapsed=0
    while ! curl -s -o /dev/null "http://127.0.0.1:$WATCHDOG_PORT/health" 2>/dev/null; do
        sleep 0.5
        ((elapsed++)) || true
        if [ "$elapsed" -ge "$max_wait" ]; then
            echo "Timeout waiting for watchdog warm server" >&2
            return 1
        fi
    done
}

stop_watchdog() {
    if [ -n "$WATCHDOG_PID" ]; then
        kill "$WATCHDOG_PID" 2>/dev/null || true
        wait "$WATCHDOG_PID" 2>/dev/null || true
        WATCHDOG_PID=""
    fi
    # Also kill any spawned sleep process
    pkill -f "sleep 99999" 2>/dev/null || true
}

cleanup_warm_test() {
    stop_watchdog
    stop_mock_runtime
    stop_warm_callback_server
    # Small delay to ensure ports are freed
    sleep 0.3
}

# ============================================================================
# Warm Mode Tests
# ============================================================================

test_warm_health_check() {
    local test_name="warm_health_check"
    echo -n "  Testing $test_name... "

    start_warm_callback_server
    start_mock_runtime "success"
    start_watchdog_warm

    # Test health endpoint
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$WATCHDOG_PORT/health")

    cleanup_warm_test

    if [ "$http_code" = "200" ]; then
        echo -e "${GREEN}PASS${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "    Expected 200, got $http_code"
        ((TESTS_FAILED++))
    fi
}

test_warm_single_invocation() {
    local test_name="warm_single_invocation"
    echo -n "  Testing $test_name... "

    start_warm_callback_server
    start_mock_runtime "success"
    start_watchdog_warm

    # Clear any previous callbacks
    curl -s "http://127.0.0.1:$WARM_CALLBACK_PORT/callbacks/clear" > /dev/null

    # Send invocation
    local response=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-warm-001",
            "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions",
            "payload": {"input": "hello"}
        }')

    cleanup_warm_test

    local success=$(echo "$response" | jq -r '.success')
    if [ "$success" = "true" ]; then
        echo -e "${GREEN}PASS${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "    Expected success=true, got: $response"
        ((TESTS_FAILED++))
    fi
}

test_warm_multiple_invocations() {
    local test_name="warm_multiple_invocations"
    echo -n "  Testing $test_name... "

    start_warm_callback_server
    start_mock_runtime "success"
    start_watchdog_warm

    # Clear callbacks
    curl -s "http://127.0.0.1:$WARM_CALLBACK_PORT/callbacks/clear" > /dev/null

    # First invocation
    local response1=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-warm-multi-001",
            "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions",
            "payload": {"input": "first"}
        }')

    # Second invocation (same warm container - key test!)
    local response2=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-warm-multi-002",
            "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions",
            "payload": {"input": "second"}
        }')

    # Third invocation
    local response3=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-warm-multi-003",
            "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions",
            "payload": {"input": "third"}
        }')

    # Get callback count
    local callback_count=$(curl -s "http://127.0.0.1:$WARM_CALLBACK_PORT/callbacks/count" | jq -r '.count')

    cleanup_warm_test

    # All three should succeed
    local s1=$(echo "$response1" | jq -r '.success')
    local s2=$(echo "$response2" | jq -r '.success')
    local s3=$(echo "$response3" | jq -r '.success')

    if [ "$s1" = "true" ] && [ "$s2" = "true" ] && [ "$s3" = "true" ] && [ "$callback_count" = "3" ]; then
        echo -e "${GREEN}PASS${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "    Expected 3 successful invocations and 3 callbacks"
        echo "    Got: s1=$s1, s2=$s2, s3=$s3, callbacks=$callback_count"
        ((TESTS_FAILED++))
    fi
}

test_warm_echo_payload() {
    local test_name="warm_echo_payload"
    echo -n "  Testing $test_name... "

    start_warm_callback_server
    start_mock_runtime "echo"
    start_watchdog_warm

    # Send invocation with specific payload
    local response=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-warm-echo",
            "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions",
            "payload": {"test": "data", "number": 42}
        }')

    cleanup_warm_test

    # Should echo back the payload
    if echo "$response" | grep -q '"test"'; then
        echo -e "${GREEN}PASS${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "    Expected echoed payload, got: $response"
        ((TESTS_FAILED++))
    fi
}

test_warm_runtime_error() {
    local test_name="warm_runtime_error"
    echo -n "  Testing $test_name... "

    start_warm_callback_server
    start_mock_runtime "error_500"
    start_watchdog_warm

    local response=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-warm-error",
            "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions",
            "payload": {"input": "test"}
        }')

    cleanup_warm_test

    local success=$(echo "$response" | jq -r '.success')
    if [ "$success" = "false" ]; then
        echo -e "${GREEN}PASS${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "    Expected success=false, got: $response"
        ((TESTS_FAILED++))
    fi
}

test_warm_trace_id_propagation() {
    local test_name="warm_trace_id"
    echo -n "  Testing $test_name... "

    start_warm_callback_server
    start_mock_runtime "success"
    start_watchdog_warm

    # Clear callbacks
    curl -s "http://127.0.0.1:$WARM_CALLBACK_PORT/callbacks/clear" > /dev/null

    local response=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-warm-trace",
            "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions",
            "trace_id": "trace-warm-xyz-789",
            "payload": {"input": "trace"}
        }')

    # Check callback received trace ID
    local callback=$(curl -s "http://127.0.0.1:$WARM_CALLBACK_PORT/callbacks/last")

    cleanup_warm_test

    # Callback should have trace ID in headers
    if echo "$callback" | grep -q "trace-warm-xyz-789"; then
        echo -e "${GREEN}PASS${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "    Expected trace ID in callback, got: $callback"
        ((TESTS_FAILED++))
    fi
}

test_warm_timeout() {
    local test_name="warm_timeout"
    echo -n "  Testing $test_name... "

    start_warm_callback_server
    start_mock_runtime "hang"
    start_watchdog_warm

    # Send invocation with short timeout
    local response=$(curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{
            "execution_id": "exec-warm-timeout",
            "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions",
            "payload": {"input": "test"},
            "timeout_ms": 1000
        }')

    cleanup_warm_test

    local success=$(echo "$response" | jq -r '.success')
    if [ "$success" = "false" ] && echo "$response" | grep -q "TIMEOUT"; then
        echo -e "${GREEN}PASS${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "    Expected timeout error, got: $response"
        ((TESTS_FAILED++))
    fi
}

test_warm_container_persists() {
    local test_name="warm_container_persists"
    echo -n "  Testing $test_name... "

    start_warm_callback_server
    start_mock_runtime "success"
    start_watchdog_warm

    # Clear callbacks
    curl -s "http://127.0.0.1:$WARM_CALLBACK_PORT/callbacks/clear" > /dev/null

    # Get initial health (confirm watchdog running)
    local health1=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$WATCHDOG_PORT/health")

    # First invocation
    curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{"execution_id": "exec-persist-1", "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions", "payload": {}}' > /dev/null

    # Health still good after invocation (container didn't exit)
    local health2=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$WATCHDOG_PORT/health")

    # Second invocation
    curl -s -X POST "http://127.0.0.1:$WATCHDOG_PORT/invoke" \
        -H "Content-Type: application/json" \
        -d '{"execution_id": "exec-persist-2", "callback_url": "http://127.0.0.1:'"$WARM_CALLBACK_PORT"'/v1/internal/executions", "payload": {}}' > /dev/null

    # Health still good after second invocation
    local health3=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$WATCHDOG_PORT/health")

    cleanup_warm_test

    if [ "$health1" = "200" ] && [ "$health2" = "200" ] && [ "$health3" = "200" ]; then
        echo -e "${GREEN}PASS${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        echo "    Watchdog should persist between invocations"
        echo "    Health checks: $health1, $health2, $health3"
        ((TESTS_FAILED++))
    fi
}

# ============================================================================
# Run all Warm Mode tests
# ============================================================================

run_warm_tests() {
    echo "========================================"
    echo "Running WARM Mode Tests"
    echo "========================================"

    test_warm_health_check
    test_warm_single_invocation
    test_warm_multiple_invocations
    test_warm_echo_payload
    test_warm_runtime_error
    test_warm_trace_id_propagation
    test_warm_timeout
    test_warm_container_persists

    echo ""
    echo "WARM Mode Tests: $TESTS_PASSED passed, $TESTS_FAILED failed"
}

# Run if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # Cleanup on exit
    trap cleanup_warm_test EXIT

    run_warm_tests
    print_summary
fi
