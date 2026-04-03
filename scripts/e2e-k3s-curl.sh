#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh e2e run k3s-curl ...`.
exec "$(dirname "$0")/controlplane.sh" e2e run k3s-curl "$@"
