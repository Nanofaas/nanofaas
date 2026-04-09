#!/usr/bin/env bash
set -euo pipefail

# Convenience wrapper. Prefer `scripts/controlplane.sh e2e run k3s-junit-curl ...`.
exec "$(dirname "$0")/controlplane.sh" e2e run k3s-junit-curl "$@"
