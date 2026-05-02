#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh tui ...`.
exec "$(dirname "$0")/controlplane.sh" tui "$@"
