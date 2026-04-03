#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh e2e run k8s-vm ...`.
exec "$(dirname "$0")/controlplane.sh" e2e run k8s-vm "$@"
