#!/bin/bash
#
# Run all integration tests for the watchdog
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/test_helpers.sh"

echo "========================================"
echo "nanofaas Watchdog Integration Tests"
echo "========================================"
echo ""
echo "Watchdog version: $($WATCHDOG_BIN --version 2>&1 || echo 'unknown')"
echo "Python version: $(python3 --version)"
echo "Date: $(date)"
echo ""

# Reset counters
TESTS_PASSED=0
TESTS_FAILED=0

# Run all test suites
source "$SCRIPT_DIR/test_http_mode.sh"
run_http_tests

# Reset counters for next suite (counters are cumulative per file)
HTTP_PASSED=$TESTS_PASSED
HTTP_FAILED=$TESTS_FAILED
TESTS_PASSED=0
TESTS_FAILED=0

source "$SCRIPT_DIR/test_stdio_mode.sh"
run_stdio_tests

STDIO_PASSED=$TESTS_PASSED
STDIO_FAILED=$TESTS_FAILED
TESTS_PASSED=0
TESTS_FAILED=0

source "$SCRIPT_DIR/test_file_mode.sh"
run_file_tests

FILE_PASSED=$TESTS_PASSED
FILE_FAILED=$TESTS_FAILED
TESTS_PASSED=0
TESTS_FAILED=0

source "$SCRIPT_DIR/test_callback.sh"
run_callback_tests

CALLBACK_PASSED=$TESTS_PASSED
CALLBACK_FAILED=$TESTS_FAILED

# Calculate totals
TOTAL_PASSED=$((HTTP_PASSED + STDIO_PASSED + FILE_PASSED + CALLBACK_PASSED))
TOTAL_FAILED=$((HTTP_FAILED + STDIO_FAILED + FILE_FAILED + CALLBACK_FAILED))

# Print final summary
echo ""
echo "========================================"
echo "Final Test Summary"
echo "========================================"
echo ""
printf "%-20s %s passed, %s failed\n" "HTTP Mode:" "$HTTP_PASSED" "$HTTP_FAILED"
printf "%-20s %s passed, %s failed\n" "STDIO Mode:" "$STDIO_PASSED" "$STDIO_FAILED"
printf "%-20s %s passed, %s failed\n" "FILE Mode:" "$FILE_PASSED" "$FILE_FAILED"
printf "%-20s %s passed, %s failed\n" "Callback:" "$CALLBACK_PASSED" "$CALLBACK_FAILED"
echo "----------------------------------------"
printf "%-20s %s passed, %s failed\n" "TOTAL:" "$TOTAL_PASSED" "$TOTAL_FAILED"
echo ""

if [ "$TOTAL_FAILED" -gt 0 ]; then
    echo -e "${RED}TESTS FAILED${NC}"
    exit 1
else
    echo -e "${GREEN}ALL TESTS PASSED${NC}"
    exit 0
fi
