#!/usr/bin/env bash
# Unit tests for roman-numeral handler.
# Run: bash tests/test_handler.sh

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HANDLER="$SCRIPT_DIR/../handler.sh"
PASS=0; FAIL=0

assert_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [ "$actual" = "$expected" ]; then
        echo "  PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc"
        echo "    expected: $expected"
        echo "    actual:   $actual"
        FAIL=$((FAIL + 1))
    fi
}

invoke() { echo "{\"input\":$1}" | bash "$HANDLER"; }

echo "=== roman-numeral handler tests ==="

assert_eq "1 → I"         "I"           "$(invoke '{"number":1}'    | jq -r .roman)"
assert_eq "4 → IV"        "IV"          "$(invoke '{"number":4}'    | jq -r .roman)"
assert_eq "9 → IX"        "IX"          "$(invoke '{"number":9}'    | jq -r .roman)"
assert_eq "40 → XL"       "XL"          "$(invoke '{"number":40}'   | jq -r .roman)"
assert_eq "42 → XLII"     "XLII"        "$(invoke '{"number":42}'   | jq -r .roman)"
assert_eq "90 → XC"       "XC"          "$(invoke '{"number":90}'   | jq -r .roman)"
assert_eq "400 → CD"      "CD"          "$(invoke '{"number":400}'  | jq -r .roman)"
assert_eq "900 → CM"      "CM"          "$(invoke '{"number":900}'  | jq -r .roman)"
assert_eq "1994 → MCMXCIV" "MCMXCIV"   "$(invoke '{"number":1994}' | jq -r .roman)"
assert_eq "2024 → MMXXIV" "MMXXIV"      "$(invoke '{"number":2024}' | jq -r .roman)"
assert_eq "3999 → MMMCMXCIX" "MMMCMXCIX" "$(invoke '{"number":3999}' | jq -r .roman)"

assert_eq "missing field → error" \
    "missing required field: number" \
    "$(invoke '{}'   | jq -r .error)"

assert_eq "out of range → error" \
    "null" \
    "$(invoke '{"number":4000}' | jq -r .roman)"

assert_eq "non-object input → error" \
    "Input must be a JSON object" \
    "$(echo '{"input":"string"}' | bash "$HANDLER" | jq -r .error)"

echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
