#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh loadtest run ...` for the canonical loadtest surface.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

dry_run=false
profile_args=()

while (($#)); do
  case "$1" in
    -h|--help)
      exec "${SCRIPT_DIR}/controlplane.sh" loadtest run --help
      ;;
    --profile)
      if (($# < 2)); then
        echo "--profile requires a value" >&2
        exit 2
      fi
      case "$2" in
        demo-java) profile_args=(--saved-profile demo-java) ;;
        *)
          echo "Unsupported --profile '$2'. Supported values: demo-java." >&2
          exit 2
          ;;
      esac
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --summary-only|--no-refresh-summary-metrics|--interactive)
      echo "Option '$1' is no longer supported by this wrapper." >&2
      exit 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Use --help to inspect the loadtest workflow." >&2
      exit 2
      ;;
  esac
done

if [[ "${dry_run}" == "true" ]]; then
  echo "Compatibility wrapper: scripts/controlplane.sh loadtest run"
  if [[ ${#profile_args[@]} -gt 0 ]]; then
    echo "Profile args: ${profile_args[*]}"
  fi
  exit 0
fi

exec "${SCRIPT_DIR}/controlplane.sh" loadtest run "${profile_args[@]}"
