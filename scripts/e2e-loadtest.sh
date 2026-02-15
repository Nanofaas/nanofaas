#!/usr/bin/env bash
set -euo pipefail

#
# E2E Load Test: Grafana + k6 load tests + performance report
#
# Usage:
#   ./scripts/e2e-loadtest.sh                 # Full run (Grafana + k6 + report)
#   SKIP_GRAFANA=true ./scripts/e2e-loadtest.sh  # Skip Grafana startup
#
# Prerequisites:
#   - nanofaas deployed (run ./scripts/e2e-k3s-helm.sh first)
#   - k6 installed (https://grafana.com/docs/k6/latest/set-up/install-k6/)
#   - Docker (for Grafana)
#

VM_NAME=${VM_NAME:-nanofaas-e2e}
SKIP_GRAFANA=${SKIP_GRAFANA:-false}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "loadtest"
K6_DIR="${PROJECT_ROOT}/k6"
RESULTS_DIR="${K6_DIR}/results"
GRAFANA_DIR="${PROJECT_ROOT}/grafana"

resolve_vm_ip() { e2e_resolve_nanofaas_url 30080; }

resolve_prom_url() {
    if [[ -n "${PROM_URL:-}" ]]; then
        echo "${PROM_URL}"
        return
    fi
    e2e_resolve_nanofaas_url 30090
}

capture_prom_snapshot() {
    local test_name="$1"
    local prom_url
    prom_url=$(resolve_prom_url)
    local out_file="${RESULTS_DIR}/prom-snapshots.jsonl"

    python3 - "${prom_url}" "${test_name}" "${out_file}" << 'PYEOF' || true
import json, sys, time, urllib.parse, urllib.request

prom_url = sys.argv[1]
function = sys.argv[2]
out_file = sys.argv[3]

def prom_query(expr):
    url = f"{prom_url}/api/v1/query?query={urllib.parse.quote(expr, safe='')}"
    with urllib.request.urlopen(url, timeout=8) as resp:
        body = json.loads(resp.read())
    return body.get("data", {}).get("result", [])

def metric_max(expr):
    try:
        rows = prom_query(expr)
    except Exception:
        return 0.0
    if not rows:
        return 0.0
    vals = []
    for row in rows:
        try:
            vals.append(float(row["value"][1]))
        except Exception:
            continue
    return max(vals) if vals else 0.0

queries = {
    "latency_p50": f'max(function_latency_ms_seconds{{function="{function}",quantile="0.5"}})',
    "latency_p95": f'max(function_latency_ms_seconds{{function="{function}",quantile="0.95"}})',
    "latency_p99": f'max(function_latency_ms_seconds{{function="{function}",quantile="0.99"}})',
    "e2e_p50": f'max(function_e2e_latency_ms_seconds{{function="{function}",quantile="0.5"}})',
    "e2e_p95": f'max(function_e2e_latency_ms_seconds{{function="{function}",quantile="0.95"}})',
    "e2e_p99": f'max(function_e2e_latency_ms_seconds{{function="{function}",quantile="0.99"}})',
    "queue_wait_p50": f'max(function_queue_wait_ms_seconds{{function="{function}",quantile="0.5"}})',
    "queue_wait_p95": f'max(function_queue_wait_ms_seconds{{function="{function}",quantile="0.95"}})',
    "init_p50": f'max(function_init_duration_ms_seconds{{function="{function}",quantile="0.5"}})',
    "init_p95": f'max(function_init_duration_ms_seconds{{function="{function}",quantile="0.95"}})',
}

payload = {
    "function": function,
    "timestamp": int(time.time()),
    "metrics": {key: metric_max(expr) for key, expr in queries.items()},
}
with open(out_file, "a", encoding="utf-8") as f:
    f.write(json.dumps(payload) + "\n")
PYEOF
}

# ─── Pre-flight checks ──────────────────────────────────────────────────────
preflight() {
    log "Pre-flight checks..."

    if ! command -v k6 &>/dev/null; then
        err "k6 is not installed. Install it from https://grafana.com/docs/k6/latest/set-up/install-k6/"
        exit 1
    fi

    local nanofaas_url
    nanofaas_url=$(resolve_vm_ip)

    # Verify API reachable
    if ! curl -sf "${nanofaas_url}/v1/functions" >/dev/null 2>&1; then
        err "Cannot reach ${nanofaas_url}/v1/functions"
        err "Is nanofaas running? Run ./scripts/e2e-k3s-helm.sh first."
        exit 1
    fi

    # Verify functions are registered
    local fn_count
    fn_count=$(curl -sf "${nanofaas_url}/v1/functions" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null) || fn_count=0
    if [[ "${fn_count}" -lt 8 ]]; then
        err "Expected 8 functions, found ${fn_count}."
        exit 1
    fi

    info "API reachable at ${nanofaas_url} (${fn_count} functions registered)"
}

# ─── Start Grafana ───────────────────────────────────────────────────────────
start_grafana() {
    if [[ "${SKIP_GRAFANA}" == "true" ]]; then
        log "SKIP_GRAFANA=true, skipping..."
        return
    fi

    if ! command -v docker &>/dev/null; then
        warn "Docker not found, skipping Grafana. View metrics at Prometheus directly."
        return
    fi

    local prom_url
    prom_url=$(resolve_prom_url)

    log "Starting Grafana (datasource: ${prom_url})..."
    PROM_URL="${prom_url}" docker compose -f "${GRAFANA_DIR}/docker-compose.yml" up -d 2>&1 | tail -3

    log "Grafana available at http://localhost:3000 (admin/admin)"
    log "Dashboard: http://localhost:3000/d/nanofaas-functions"
}

# ─── Run k6 tests ───────────────────────────────────────────────────────────
run_tests() {
    local nanofaas_url
    nanofaas_url=$(resolve_vm_ip)

    mkdir -p "${RESULTS_DIR}"
    local windows_file="${RESULTS_DIR}/test-windows.jsonl"
    local prom_snapshots_file="${RESULTS_DIR}/prom-snapshots.jsonl"
    : > "${windows_file}"
    : > "${prom_snapshots_file}"

    local tests=(
        "word-stats-java"
        "json-transform-java"
        "word-stats-python"
        "json-transform-python"
        "word-stats-exec"
        "json-transform-exec"
        "word-stats-java-lite"
        "json-transform-java-lite"
    )

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         STARTING K6 LOAD TESTS"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "  Target: ${nanofaas_url}"
    log "  Tests:  ${#tests[@]} functions"
    log ""

    local last_index=$(( ${#tests[@]} - 1 ))
    local idx=0
    for test in "${tests[@]}"; do
        local script="${K6_DIR}/${test}.js"
        if [[ ! -f "${script}" ]]; then
            warn "SKIP: ${script} not found"
            continue
        fi

        log "━━━ ${test} ━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        local test_start_epoch
        test_start_epoch=$(date +%s)

        # Allow k6 to exit non-zero when thresholds are crossed (report handles it)
        k6 run \
            --env "NANOFAAS_URL=${nanofaas_url}" \
            --summary-export="${RESULTS_DIR}/${test}.json" \
            "${script}" 2>&1 | tee "${RESULTS_DIR}/${test}.log" \
            | grep -E "█|✓|✗|http_req_duration\b|http_req_failed\b|http_reqs\b|iterations\b|default" \
            | grep -v "running (" \
            || true

        local test_end_epoch
        test_end_epoch=$(date +%s)
        printf '{"function":"%s","start":%s,"end":%s}\n' \
            "${test}" "${test_start_epoch}" "${test_end_epoch}" >> "${windows_file}"
        capture_prom_snapshot "${test}"

        log "Results saved: ${RESULTS_DIR}/${test}.json"
        echo ""

        # Cool-down between tests
        if [[ ${idx} -lt ${last_index} ]]; then
            info "Cool-down 10s..."
            sleep 10
        fi
        ((idx++))
    done
}

# ─── Generate report ────────────────────────────────────────────────────────
generate_report() {
    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         PERFORMANCE REPORT"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""

    # Generate the summary table
    python3 - "${RESULTS_DIR}" << 'PYEOF'
import json, os, sys, glob

results_dir = sys.argv[1]
tests = [
    "word-stats-java", "json-transform-java",
    "word-stats-python", "json-transform-python",
    "word-stats-exec", "json-transform-exec",
    "word-stats-java-lite", "json-transform-java-lite",
]

rows = []
for test in tests:
    path = os.path.join(results_dir, f"{test}.json")
    if not os.path.exists(path):
        continue
    with open(path) as f:
        d = json.load(f)
    m = d.get("metrics", {})
    reqs = int(m.get("http_reqs", {}).get("count", 0))
    fails = int(m.get("http_req_failed", {}).get("passes", 0))
    dur = m.get("http_req_duration", {})
    avg = dur.get("avg", 0)
    p90 = dur.get("p(90)", 0)
    p95 = dur.get("p(95)", 0)
    med = dur.get("med", 0)
    mx = dur.get("max", 0)
    iters = m.get("iterations", {})
    rate = float(iters.get("rate", 0))
    fail_pct = (fails / max(1, reqs)) * 100

    rows.append((test, reqs, fail_pct, avg, med, p90, p95, mx, rate))

# Header
hdr = f"{'Function':<28s} {'Reqs':>7s} {'Fail%':>7s} {'Avg(ms)':>9s} {'Med(ms)':>9s} {'p90(ms)':>9s} {'p95(ms)':>9s} {'Req/s':>8s}"
sep = "-" * len(hdr)
print(hdr)
print(sep)
for name, reqs, fail_pct, avg, med, p90, p95, mx, rate in rows:
    print(f"{name:<28s} {reqs:>7d} {fail_pct:>6.1f}% {avg:>8.1f} {med:>8.1f} {p90:>8.1f} {p95:>8.1f} {rate:>8.1f}")
print(sep)

# Analysis
print()
print("Analysis:")
if rows:
    best = min(rows, key=lambda r: r[3])  # lowest avg
    worst = max(rows, key=lambda r: r[3])  # highest avg
    fastest = max(rows, key=lambda r: r[8])  # highest rps
    print(f"  Fastest avg latency:  {best[0]} ({best[3]:.1f}ms)")
    print(f"  Slowest avg latency:  {worst[0]} ({worst[3]:.1f}ms)")
    print(f"  Highest throughput:   {fastest[0]} ({fastest[8]:.1f} req/s)")

    # Group by runtime
    runtimes = {"Java": [], "Java-Lite": [], "Python": [], "Bash": []}
    for r in rows:
        if "java-lite" in r[0]: runtimes["Java-Lite"].append(r)
        elif "java" in r[0]: runtimes["Java"].append(r)
        elif "python" in r[0]: runtimes["Python"].append(r)
        elif "exec" in r[0]: runtimes["Bash"].append(r)

    print()
    print("  By runtime:")
    for rt, entries in runtimes.items():
        if entries:
            avg_lat = sum(e[3] for e in entries) / len(entries)
            avg_rps = sum(e[8] for e in entries) / len(entries)
            avg_fail = sum(e[2] for e in entries) / len(entries)
            print(f"    {rt:8s}  avg={avg_lat:7.1f}ms  rps={avg_rps:6.1f}  fail={avg_fail:.1f}%")
PYEOF

    # Save report to file
    log ""
    log "Full logs:    ${RESULTS_DIR}/*.log"
    log "JSON results: ${RESULTS_DIR}/*.json"
}

# ─── Print final summary ────────────────────────────────────────────────────
print_summary() {
    local prom_url
    prom_url=$(resolve_prom_url)

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         LOAD TEST COMPLETE"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""
    if [[ "${SKIP_GRAFANA}" != "true" ]]; then
        log "Grafana:    http://localhost:3000  (admin/admin)"
        log "Dashboard:  http://localhost:3000/d/nanofaas-functions"
    fi
    log "Prometheus: ${prom_url}"
    log "Results:    ${RESULTS_DIR}/"
    log ""
    log "To stop Grafana:"
    log "  docker compose -f grafana/docker-compose.yml down"
    log ""
    log "To tear down the VM:"
    log "  multipass delete ${VM_NAME}"
    log "  multipass purge   # optional: clean all deleted VMs"
    log ""
}

main() {
    preflight
    start_grafana
    run_tests
    generate_report
    print_summary
}

main "$@"
