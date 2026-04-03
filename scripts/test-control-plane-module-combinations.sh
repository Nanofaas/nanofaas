#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

TASK="${TASK:-:control-plane:bootJar}"
DRY_RUN=false
MAX_COMBINATIONS=0
MODULES_CSV="${MODULES_CSV:-}"

usage() {
    cat <<'EOF'
Usage:
  ./scripts/test-control-plane-module-combinations.sh [options]

Runs a Gradle compile/package task for every combination of control-plane optional modules.

Options:
  --task <gradle-task>          Task to run per combination (default: :control-plane:bootJar)
  --modules <csv>               Override detected module list (e.g. async-queue,sync-queue)
  --max-combinations <n>        Run only first n combinations (0 = all)
  --dry-run                     Print commands without executing
  -h, --help                    Show this help

Environment overrides:
  TASK, MODULES_CSV, MAX_COMBINATIONS
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --task)
            TASK="${2:-}"
            shift 2
            ;;
        --modules)
            MODULES_CSV="${2:-}"
            shift 2
            ;;
        --max-combinations)
            MAX_COMBINATIONS="${2:-0}"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if ! [[ "${MAX_COMBINATIONS}" =~ ^[0-9]+$ ]]; then
    echo "--max-combinations must be a non-negative integer" >&2
    exit 2
fi

cmd=(./scripts/controlplane.sh matrix --task "${TASK}" --max-combinations "${MAX_COMBINATIONS}")

if [[ -n "${MODULES_CSV}" ]]; then
    cmd+=(--modules "${MODULES_CSV}")
fi

if [[ "${DRY_RUN}" == "true" ]]; then
    cmd+=(--dry-run)
fi

exec "${cmd[@]}"
