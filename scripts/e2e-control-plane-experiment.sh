#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}/control-plane-experiment"
ENTRYPOINT="${PROJECT_DIR}/experiment.py"

if ! command -v uv >/dev/null 2>&1; then
    echo "uv non trovato. Installa uv e riprova." >&2
    exit 1
fi

uv run --project "${PROJECT_DIR}" "${ENTRYPOINT}" "$@"
