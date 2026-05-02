#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh cli-test run host-platform ...`.
exec "$(dirname "$0")/controlplane.sh" cli-test run host-platform "$@"
