#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

GRADLEW="${GRADLEW:-./gradlew}"
TASK="${TASK:-:control-plane:bootJar}"
DRY_RUN=false
MAX_COMBINATIONS=0
MODULES_CSV="${MODULES_CSV:-}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${ROOT_DIR}/.gradle-local}"

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
  GRADLEW, TASK, MODULES_CSV, GRADLE_USER_HOME
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

declare -a modules=()
if [[ -n "${MODULES_CSV}" ]]; then
    IFS=',' read -r -a modules <<< "${MODULES_CSV}"
    declare -a cleaned=()
    for module in "${modules[@]}"; do
        module="${module// /}"
        if [[ -n "${module}" ]]; then
            cleaned+=("${module}")
        fi
    done
    modules=("${cleaned[@]}")
else
    while IFS= read -r module; do
        modules+=("${module}")
    done < <(
        for dir in control-plane-modules/*; do
            [[ -d "${dir}" ]] || continue
            if [[ -f "${dir}/build.gradle" || -f "${dir}/build.gradle.kts" ]]; then
                basename "${dir}"
            fi
        done | sort
    )
fi

if [[ ${#modules[@]} -eq 0 ]]; then
    echo "No optional modules found under control-plane-modules/." >&2
    exit 1
fi

if [[ ! -x "${GRADLEW}" ]]; then
    echo "Gradle wrapper not executable: ${GRADLEW}" >&2
    exit 1
fi

module_count=${#modules[@]}
total_combinations=$((1 << module_count))
if [[ "${MAX_COMBINATIONS}" -gt 0 && "${MAX_COMBINATIONS}" -lt "${total_combinations}" ]]; then
    total_to_run="${MAX_COMBINATIONS}"
else
    total_to_run="${total_combinations}"
fi

timestamp="$(date +%Y%m%d-%H%M%S)-$$"
log_dir="build/module-combo-compile-logs/${timestamp}"
mkdir -p "${log_dir}"

echo "Detected modules (${module_count}): ${modules[*]}"
echo "Task: ${TASK}"
echo "Combinations to run: ${total_to_run}/${total_combinations}"
echo "Logs: ${log_dir}"
echo ""

pass_count=0
fail_count=0
declare -a failures=()

run_index=0
for ((mask=0; mask<total_combinations; mask++)); do
    if [[ "${run_index}" -ge "${total_to_run}" ]]; then
        break
    fi
    run_index=$((run_index + 1))

    declare -a selected=()
    for ((bit=0; bit<module_count; bit++)); do
        if (( (mask >> bit) & 1 )); then
            selected+=("${modules[bit]}")
        fi
    done

    selector="none"
    if [[ ${#selected[@]} -gt 0 ]]; then
        selector="$(IFS=,; echo "${selected[*]}")"
    fi

    safe_selector="${selector//,/__}"
    log_file="${log_dir}/combo-$(printf "%03d" "${run_index}")-${safe_selector}.log"

    cmd=("${GRADLEW}" "${TASK}" ":control-plane:printSelectedControlPlaneModules" "-PcontrolPlaneModules=${selector}")
    echo "[${run_index}/${total_to_run}] selector=${selector}"

    if [[ "${DRY_RUN}" == "true" ]]; then
        echo "  DRY RUN: ${cmd[*]}"
        pass_count=$((pass_count + 1))
        continue
    fi

    if "${cmd[@]}" > "${log_file}" 2>&1; then
        echo "  PASS"
        pass_count=$((pass_count + 1))
    else
        echo "  FAIL (log: ${log_file})"
        fail_count=$((fail_count + 1))
        failures+=("${selector}|${log_file}")
    fi
done

echo ""
echo "Summary: ${pass_count} passed, ${fail_count} failed, total ${total_to_run}"

if [[ ${fail_count} -gt 0 ]]; then
    echo "Failed combinations:"
    for item in "${failures[@]}"; do
        selector="${item%%|*}"
        log_file="${item#*|}"
        echo "  - ${selector} (log: ${log_file})"
    done
    exit 1
fi
