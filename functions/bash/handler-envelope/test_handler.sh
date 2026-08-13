#!/usr/bin/env bash
set -euo pipefail
actual="$(printf '%s' '{"input":{"message":"body-sentinel","headers":{"x-e2e-token":"forged"}},"headers":{"x-e2e-token":"header-sentinel"}}' | "$(dirname "$0")/handler.sh")"
test "$actual" = '{"body":"body-sentinel","header":"header-sentinel"}'
