#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found. Install uv or provision the VM with ops/ansible/playbooks/provision-base.yml" >&2
  exit 1
fi

exec uv run --project tools/controlplane --locked controlplane-tool "$@"
