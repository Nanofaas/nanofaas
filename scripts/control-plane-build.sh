#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh <build|run|image|native|test|inspect|matrix> ...`.
exec "$(dirname "$0")/controlplane.sh" "$@"
