#!/usr/bin/env bash
set -euo pipefail

#
# Run all k6 load tests sequentially against a nanofaas cluster.
#
# Usage:
#   NANOFAAS_URL=http://<VM_IP>:30080 ./k6/run-all.sh
#   K6_PAYLOAD_PROFILES=small,medium,large NANOFAAS_URL=... ./k6/run-all.sh
#   ./k6/run-all.sh --list
#
# Output: k6/results/ directory with JSON summaries per function.
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXPERIMENTS_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"

GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "${GREEN}[k6]${NC} $*"; }
info() { echo -e "${CYAN}[k6]${NC} $*"; }

FAMILIES=("word-stats" "json-transform" "roman-numeral")
RUNTIMES=("java" "java-lite" "python" "go" "javascript" "exec" "rust")

if [[ "${K6_PAYLOAD_PROFILES+x}" == "x" ]]; then
    profile_list="${K6_PAYLOAD_PROFILES}"
else
    profile_list="small"
fi

if [[ -z "${profile_list}" || "${profile_list}" == ,* || "${profile_list}" == *, || "${profile_list}" == *,,* ]]; then
    echo "ERROR: K6_PAYLOAD_PROFILES must be a comma-separated list of small, medium, or large" >&2
    exit 1
fi

IFS=',' read -r -a PROFILES <<< "${profile_list}"
for profile in "${PROFILES[@]}"; do
    case "${profile}" in
        small|medium|large) ;;
        *)
            echo "ERROR: K6_PAYLOAD_PROFILES contains unknown profile: ${profile}" >&2
            exit 1
            ;;
    esac
done

if [[ "${1:-}" == "--list" ]]; then
    for family in "${FAMILIES[@]}"; do
        for runtime in "${RUNTIMES[@]}"; do
            for profile in "${PROFILES[@]}"; do
                printf '%s\t%s\t%s\t%s\n' "${family}" "${runtime}" "${profile}" "${family}-${runtime}"
            done
        done
    done
    exit 0
elif [[ $# -ne 0 ]]; then
    echo "Usage: $0 [--list]" >&2
    exit 1
fi

NANOFAAS_URL="${NANOFAAS_URL:?Set NANOFAAS_URL to the nanofaas API endpoint (e.g. http://192.168.64.5:30080)}"
mkdir -p "${RESULTS_DIR}"
RUN_IDS=()

# Pre-flight: verify API is reachable
log "Checking nanofaas API at ${NANOFAAS_URL}..."
if ! curl -sf "${NANOFAAS_URL}/v1/functions" >/dev/null 2>&1; then
    echo "ERROR: Cannot reach ${NANOFAAS_URL}/v1/functions" >&2
    exit 1
fi
log "API reachable"
echo ""

# List registered functions
log "Registered functions:"
curl -sf "${NANOFAAS_URL}/v1/functions" | python3 -m json.tool 2>/dev/null || curl -sf "${NANOFAAS_URL}/v1/functions"
echo ""

for family in "${FAMILIES[@]}"; do
    for runtime in "${RUNTIMES[@]}"; do
        for profile in "${PROFILES[@]}"; do
            function_name="${family}-${runtime}"
            run_id="${family}-${runtime}-${profile}"
            RUN_IDS+=("${run_id}")

            log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            log "Running: ${function_name} (${profile})"
            log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            k6 run \
                --env "NANOFAAS_URL=${NANOFAAS_URL}" \
                --env "NANOFAAS_FUNCTION=${function_name}" \
                --env "NANOFAAS_FAMILY=${family}" \
                --env "K6_PAYLOAD_PROFILE=${profile}" \
                --summary-export="${RESULTS_DIR}/${run_id}.json" \
                "${SCRIPT_DIR}/function-benchmark.js" 2>&1 | tee "${RESULTS_DIR}/${run_id}.log"

            log "Results saved to ${RESULTS_DIR}/${run_id}.json"
            echo ""

            log "Cool-down 10s..."
            sleep 10
        done
    done
done

log ""
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "ALL TESTS COMPLETE"
log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log ""
log "Results directory: ${RESULTS_DIR}"
log ""

# Print summary table
log "Summary:"
printf "%-25s %10s %10s %10s %10s %10s\n" "Function" "Requests" "Failed" "p95(ms)" "p99(ms)" "Avg(ms)"
printf "%-25s %10s %10s %10s %10s %10s\n" "--------" "--------" "------" "-------" "-------" "-------"

for test in "${RUN_IDS[@]}"; do
    json="${RESULTS_DIR}/${test}.json"
    if [[ -f "${json}" ]]; then
        python3 - "${json}" "${EXPERIMENTS_ROOT}" "${test}" <<'PYEOF' 2>/dev/null || echo "${test}: parse error"
import json
import sys
from pathlib import Path

json_path = Path(sys.argv[1])
experiments_root = Path(sys.argv[2]).resolve()
test = sys.argv[3]
sys.path.insert(0, str(experiments_root / "lib"))
from k6_summary import resolve_http_req_failed_count

with json_path.open(encoding="utf-8") as f:
    d = json.load(f)
m = d.get("metrics", {})
reqs = int(m.get("http_reqs", {}).get("count", 0))
fails = resolve_http_req_failed_count(m.get("http_req_failed", {}), reqs)
dur = m.get("http_req_duration", {})
p95 = float(dur.get("p(95)", 0))
p99 = float(dur.get("p(99)", 0))
avg = float(dur.get("avg", 0))
print(f"{test:25s} {reqs:10d} {fails:10d} {p95:10.1f} {p99:10.1f} {avg:10.1f}")
PYEOF
    fi
done
