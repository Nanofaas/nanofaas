#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh loadtest run ...` for the canonical loadtest surface.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEGACY_SCRIPT="${ROOT_DIR}/experiments/e2e-loadtest.sh"
LEGACY_SCRIPT_DISPLAY="experiments/e2e-loadtest.sh"
REGISTRY_SCRIPT_DISPLAY="scripts/e2e-loadtest-registry.sh"

dry_run=false
profile_name=""
env_overrides=()

map_profile() {
  case "$1" in
    demo-java)
      env_overrides+=(
        "LOADTEST_WORKLOADS=word-stats,json-transform"
        "LOADTEST_RUNTIMES=java"
        "CONTROL_PLANE_RUNTIME=java"
      )
      ;;
    *)
      echo "Unsupported --profile '$1'. Supported values: demo-java." >&2
      exit 2
      ;;
  esac
}

while (($#)); do
  case "$1" in
    -h|--help)
      exec "${LEGACY_SCRIPT}" --help
      ;;
    --profile)
      if (($# < 2)); then
        echo "--profile requires a value" >&2
        exit 2
      fi
      profile_name="$2"
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --summary-only|--no-refresh-summary-metrics|--interactive)
      echo "Option '$1' belongs to ${REGISTRY_SCRIPT_DISPLAY}, not ${LEGACY_SCRIPT_DISPLAY}." >&2
      exit 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Use --help to inspect the legacy Helm/Grafana/parity workflow." >&2
      exit 2
      ;;
  esac
done

if [[ -n "${profile_name}" ]]; then
  map_profile "${profile_name}"
fi

if [[ "${dry_run}" == "true" ]]; then
  echo "Compatibility wrapper backend: ${LEGACY_SCRIPT_DISPLAY}"
  if [[ -n "${profile_name}" ]]; then
    echo "Profile mapping: ${profile_name}"
  fi
  if [[ ${#env_overrides[@]} -gt 0 ]]; then
    echo "Environment overrides:"
    for item in "${env_overrides[@]}"; do
      echo "  ${item}"
    done
  fi
  echo "Command: ${LEGACY_SCRIPT_DISPLAY}"
  exit 0
fi

if [[ ${#env_overrides[@]} -gt 0 ]]; then
  exec env "${env_overrides[@]}" "${LEGACY_SCRIPT}"
fi

exec "${LEGACY_SCRIPT}"
