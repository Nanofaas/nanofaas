#!/usr/bin/env bash
set -euo pipefail

exec "$(dirname "$0")/controlplane.sh" e2e run buildpack "$@"
