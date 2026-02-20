#!/usr/bin/env bash
set -euo pipefail

#
# Run all k6 load tests sequentially against a nanofaas cluster.
#
# Usage:
#   NANOFAAS_URL=http://<VM_IP>:30080 ./k6/run-all.sh
#
# Output: k6/results/ directory with JSON summaries per function.
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
NANOFAAS_URL="${NANOFAAS_URL:?Set NANOFAAS_URL to the nanofaas API endpoint (e.g. http://192.168.64.5:30080)}"

GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "${GREEN}[k6]${NC} $*"; }
info() { echo -e "${CYAN}[k6]${NC} $*"; }

mkdir -p "${RESULTS_DIR}"

TESTS=(
    "word-stats-java"
    "json-transform-java"
    "word-stats-python"
    "json-transform-python"
    "word-stats-exec"
    "json-transform-exec"
)

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

for test in "${TESTS[@]}"; do
    script="${SCRIPT_DIR}/${test}.js"
    if [[ ! -f "${script}" ]]; then
        log "SKIP: ${script} not found"
        continue
    fi

    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "Running: ${test}"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    k6 run \
        --env "NANOFAAS_URL=${NANOFAAS_URL}" \
        --summary-export="${RESULTS_DIR}/${test}.json" \
        "${script}" 2>&1 | tee "${RESULTS_DIR}/${test}.log"

    log "Results saved to ${RESULTS_DIR}/${test}.json"
    echo ""

    # Cool-down between tests
    log "Cool-down 10s..."
    sleep 10
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

for test in "${TESTS[@]}"; do
    json="${RESULTS_DIR}/${test}.json"
    if [[ -f "${json}" ]]; then
        python3 -c "
import json, sys
with open('${json}') as f:
    d = json.load(f)
m = d.get('metrics', {})
reqs = int(m.get('http_reqs', {}).get('count', 0))
fails = int(m.get('http_req_failed', {}).get('passes', 0))
dur = m.get('http_req_duration', {})
p95 = dur.get('p(95)', 0)
p99 = dur.get('p(99)', 0)
avg = dur.get('avg', 0)
print(f'${test:25s} {reqs:10d} {fails:10d} {p95:10.1f} {p99:10.1f} {avg:10.1f}')
" 2>/dev/null || echo "${test}: parse error"
    fi
done
