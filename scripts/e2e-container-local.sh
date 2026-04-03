#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh e2e run container-local ...`.
exec "$(dirname "$0")/controlplane.sh" e2e run container-local "$@"
