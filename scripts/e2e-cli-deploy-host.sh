#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh cli-test run deploy-host ...`.
exec "$(dirname "$0")/controlplane.sh" cli-test run deploy-host "$@"
