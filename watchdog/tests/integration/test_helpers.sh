#!/bin/bash
#
# Test helper functions
#

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0
CURRENT_TEST=""

# Callback server
CALLBACK_PORT="${CALLBACK_PORT:-9999}"
CALLBACK_PID=""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Default paths (override in local runs)
TESTS_ROOT="${TESTS_ROOT:-/tests}"
FIXTURES_DIR="${FIXTURES_DIR:-${TESTS_ROOT}/fixtures}"
WATCHDOG_BIN="${WATCHDOG_BIN:-/usr/local/bin/watchdog}"

# ============================================================================
# Test Framework
# ============================================================================

start_test() {
    local name="$1"
    CURRENT_TEST="$name"
    echo -n "  Testing $name... "
}

end_test() {
    local name="$1"
    # Cleanup any leftover processes
    pkill -f "http_server.py" 2>/dev/null || true
    pkill -f "stdio_handler.py" 2>/dev/null || true
    pkill -f "file_handler.sh" 2>/dev/null || true
    sleep 0.1
}

pass_test() {
    echo -e "${GREEN}PASS${NC}"
    ((TESTS_PASSED++)) || true
}

fail_test() {
    local reason="${1:-}"
    echo -e "${RED}FAIL${NC}"
    if [ -n "$reason" ]; then
        echo -e "    ${RED}Reason: $reason${NC}"
    fi
    ((TESTS_FAILED++)) || true
}

# ============================================================================
# Callback Server Management
# ============================================================================

start_callback_server() {
    local scenario="${1:-success}"

    # Kill any existing callback server
    stop_callback_server 2>/dev/null || true

    CALLBACK_SCENARIO="$scenario" \
    CALLBACK_PORT="$CALLBACK_PORT" \
    python3 ${FIXTURES_DIR}/callback_server.py &
    CALLBACK_PID=$!

    # Wait for server to be ready
    wait_for_port "$CALLBACK_PORT"

    # Clear any previous callbacks
    curl -s "http://127.0.0.1:$CALLBACK_PORT/callbacks/clear" > /dev/null
}

stop_callback_server() {
    if [ -n "$CALLBACK_PID" ]; then
        kill "$CALLBACK_PID" 2>/dev/null || true
        wait "$CALLBACK_PID" 2>/dev/null || true
        CALLBACK_PID=""
    fi
    # Also kill by port
    pkill -f "callback_server.py" 2>/dev/null || true
}

get_callbacks() {
    curl -s "http://127.0.0.1:$CALLBACK_PORT/callbacks"
}

get_last_callback() {
    curl -s "http://127.0.0.1:$CALLBACK_PORT/callbacks/last"
}

get_callback_count() {
    curl -s "http://127.0.0.1:$CALLBACK_PORT/callbacks/count" | jq -r '.count'
}

# ============================================================================
# Port Utilities
# ============================================================================

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

# ============================================================================
# Assertions
# ============================================================================

assert_equals() {
    local actual="$1"
    local expected="$2"
    local test_name="${3:-$CURRENT_TEST}"

    if [ "$actual" = "$expected" ]; then
        pass_test
        return 0
    else
        fail_test "Expected '$expected', got '$actual'"
        return 1
    fi
}

assert_contains() {
    local haystack="$1"
    local needle="$2"
    local test_name="${3:-$CURRENT_TEST}"

    if echo "$haystack" | grep -q "$needle"; then
        pass_test
        return 0
    else
        fail_test "Expected to contain '$needle'"
        return 1
    fi
}

assert_not_contains() {
    local haystack="$1"
    local needle="$2"
    local test_name="${3:-$CURRENT_TEST}"

    if ! echo "$haystack" | grep -q "$needle"; then
        pass_test
        return 0
    else
        fail_test "Expected NOT to contain '$needle'"
        return 1
    fi
}

assert_json_field() {
    local json="$1"
    local path="$2"
    local expected="$3"
    local test_name="${4:-$CURRENT_TEST}"

    local actual=$(echo "$json" | jq -r "$path")

    if [ "$actual" = "$expected" ]; then
        pass_test
        return 0
    else
        fail_test "JSON $path: expected '$expected', got '$actual'"
        return 1
    fi
}

assert_json_contains() {
    local json="$1"
    local needle="$2"
    local test_name="${3:-$CURRENT_TEST}"

    if echo "$json" | grep -q "$needle"; then
        pass_test
        return 0
    else
        fail_test "JSON expected to contain '$needle'"
        return 1
    fi
}

assert_json_not_null() {
    local json="$1"
    local path="$2"
    local test_name="${3:-$CURRENT_TEST}"

    local actual=$(echo "$json" | jq -r "$path")

    if [ "$actual" != "null" ] && [ -n "$actual" ]; then
        pass_test
        return 0
    else
        fail_test "JSON $path: expected non-null value"
        return 1
    fi
}

# ============================================================================
# Summary
# ============================================================================

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

# Cleanup on exit
cleanup() {
    stop_callback_server
    pkill -f "http_server.py" 2>/dev/null || true
    pkill -f "stdio_handler.py" 2>/dev/null || true
    pkill -f "file_handler.sh" 2>/dev/null || true
}

trap cleanup EXIT
