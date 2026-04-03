#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh cli-test run vm ...`.
exec "$(dirname "$0")/controlplane.sh" cli-test run vm "$@"
