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
VERIFY_OUTPUT_PARITY=${VERIFY_OUTPUT_PARITY:-true}
PARITY_TIMEOUT_SECONDS=${PARITY_TIMEOUT_SECONDS:-20}
LOADTEST_WORKLOADS=${LOADTEST_WORKLOADS:-word-stats,json-transform}
LOADTEST_RUNTIMES=${LOADTEST_RUNTIMES:-java,java-lite,python,exec}
INVOCATION_MODE=${INVOCATION_MODE:-sync}
K6_STAGE_SEQUENCE=${K6_STAGE_SEQUENCE:-}
RESULTS_DIR_OVERRIDE=${RESULTS_DIR_OVERRIDE:-}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "loadtest"
K6_DIR="${PROJECT_ROOT}/k6"
if [[ -n "${RESULTS_DIR_OVERRIDE}" ]]; then
    RESULTS_DIR="${RESULTS_DIR_OVERRIDE}"
else
    RESULTS_DIR="${K6_DIR}/results"
fi
GRAFANA_DIR="${PROJECT_ROOT}/grafana"
SELECTED_TESTS=()
K6_STAGE_ARGS=()

show_help() {
    cat <<EOF
Usage:
  ./scripts/e2e-loadtest.sh [--help|-h]

Description:
  Run end-to-end load tests against all demo functions with k6.
  The script performs:
    1) pre-flight checks
    2) optional output parity verification across runtimes
    3) optional Grafana startup
    4) sequential k6 runs + summary report

Environment variables:
  NANOFAAS_URL            Override API base URL (default: auto from VM_NAME:30080)
  PROM_URL                Override Prometheus URL (default: auto from VM_NAME:30090)
  VM_NAME                 Multipass VM name used for auto-discovery (default: nanofaas-e2e)
  SKIP_GRAFANA            Skip local Grafana startup: true|false (default: false)
  VERIFY_OUTPUT_PARITY    Validate semantic parity before k6 tests: true|false (default: true)
  PARITY_TIMEOUT_SECONDS  Timeout per parity invocation request (default: 20)
  LOADTEST_WORKLOADS      CSV workloads: word-stats,json-transform
                          (default: word-stats,json-transform)
  LOADTEST_RUNTIMES       CSV runtimes: java,java-lite,python,exec
                          (default: java,java-lite,python,exec)
  INVOCATION_MODE         Invocation mode: sync|async (default: sync)
  K6_STAGE_SEQUENCE       Override stages CSV, e.g. 5s:3,15s:8,15s:12,5s:0
  RESULTS_DIR_OVERRIDE    Override output directory for logs and JSON results

Examples:
  ./scripts/e2e-loadtest.sh
  SKIP_GRAFANA=true ./scripts/e2e-loadtest.sh
  VERIFY_OUTPUT_PARITY=false ./scripts/e2e-loadtest.sh
  INVOCATION_MODE=async LOADTEST_RUNTIMES=java,python ./scripts/e2e-loadtest.sh
  K6_STAGE_SEQUENCE=5s:3,15s:8,15s:12,5s:0 ./scripts/e2e-loadtest.sh
  PARITY_TIMEOUT_SECONDS=40 ./scripts/e2e-loadtest.sh
  NANOFAAS_URL=http://192.168.64.2:30080 PROM_URL=http://192.168.64.2:30090 ./scripts/e2e-loadtest.sh
EOF
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            *)
                err "Unknown argument: $1"
                echo ""
                show_help
                exit 2
                ;;
        esac
        shift
    done
}

resolve_vm_ip() { e2e_resolve_nanofaas_url 30080; }

resolve_prom_url() {
    if [[ -n "${PROM_URL:-}" ]]; then
        echo "${PROM_URL}"
        return
    fi
    e2e_resolve_nanofaas_url 30090
}

array_contains() {
    local needle="$1"
    shift
    local item
    for item in "$@"; do
        if [[ "${item}" == "${needle}" ]]; then
            return 0
        fi
    done
    return 1
}

normalize_csv_selection() {
    local raw_csv="$1"
    shift
    local allowed=("$@")
    local selected=()

    local lowered
    lowered=$(printf '%s' "${raw_csv}" | tr '[:upper:]' '[:lower:]')
    local tokens=()
    IFS=',' read -r -a tokens <<< "${lowered}"
    local token
    for token in "${tokens[@]}"; do
        token="${token//[[:space:]]/}"
        [[ -z "${token}" ]] && continue
        if ! array_contains "${token}" "${allowed[@]}"; then
            err "Invalid value '${token}' in '${raw_csv}'. Allowed: ${allowed[*]}"
            exit 2
        fi
        if ! array_contains "${token}" "${selected[@]}"; then
            selected+=("${token}")
        fi
    done

    if [[ ${#selected[@]} -eq 0 ]]; then
        err "Selection '${raw_csv}' produced no valid entries."
        exit 2
    fi

    echo "${selected[*]}"
}

build_selected_tests() {
    local allowed_workloads=("word-stats" "json-transform")
    local allowed_runtimes=("java" "java-lite" "python" "exec")
    local selected_workloads=()
    local selected_runtimes=()
    read -r -a selected_workloads <<< "$(normalize_csv_selection "${LOADTEST_WORKLOADS}" "${allowed_workloads[@]}")"
    read -r -a selected_runtimes <<< "$(normalize_csv_selection "${LOADTEST_RUNTIMES}" "${allowed_runtimes[@]}")"

    SELECTED_TESTS=()
    local workload runtime
    for workload in "${allowed_workloads[@]}"; do
        if ! array_contains "${workload}" "${selected_workloads[@]}"; then
            continue
        fi
        for runtime in "${allowed_runtimes[@]}"; do
            if ! array_contains "${runtime}" "${selected_runtimes[@]}"; then
                continue
            fi
            SELECTED_TESTS+=("${workload}-${runtime}")
        done
    done

    if [[ ${#SELECTED_TESTS[@]} -eq 0 ]]; then
        err "No tests selected from workloads='${LOADTEST_WORKLOADS}' runtimes='${LOADTEST_RUNTIMES}'"
        exit 2
    fi
}

validate_invocation_mode() {
    INVOCATION_MODE=$(printf '%s' "${INVOCATION_MODE}" | tr '[:upper:]' '[:lower:]')
    case "${INVOCATION_MODE}" in
        sync|async)
            ;;
        *)
            err "Invalid INVOCATION_MODE='${INVOCATION_MODE}'. Allowed values: sync, async"
            exit 2
            ;;
    esac
}

build_stage_args() {
    K6_STAGE_ARGS=()
    if [[ -z "${K6_STAGE_SEQUENCE}" ]]; then
        return
    fi
    local tokens=()
    IFS=',' read -r -a tokens <<< "${K6_STAGE_SEQUENCE}"
    local stage
    for stage in "${tokens[@]}"; do
        stage="${stage//[[:space:]]/}"
        [[ -z "${stage}" ]] && continue
        if [[ ! "${stage}" =~ ^[0-9]+[smhd]:[0-9]+$ ]]; then
            err "Invalid stage '${stage}' in K6_STAGE_SEQUENCE='${K6_STAGE_SEQUENCE}'. Use duration:target (example 10s:5)"
            exit 2
        fi
        K6_STAGE_ARGS+=(--stage "${stage}")
    done
}

prepare_loadtest_configuration() {
    validate_invocation_mode
    build_selected_tests
    build_stage_args
}

verify_output_parity() {
    local nanofaas_url="$1"
    local timeout_seconds="$2"

    log "Verifying output parity across runtimes..."
    python3 - "${nanofaas_url}" "${timeout_seconds}" "${PROJECT_ROOT}" << 'PYEOF'
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

base_url = sys.argv[1].rstrip("/")
timeout_s = float(sys.argv[2])
project_root = Path(sys.argv[3]).resolve()
sys.path.insert(0, str(project_root / "scripts" / "lib"))

from loadtest_output_parity import compare_case_outputs, extract_output

CASES = [
    {
        "name": "word-stats",
        "functions": ["word-stats-java", "word-stats-java-lite", "word-stats-python", "word-stats-exec"],
        "input": {
            # Deterministic ranking for topN: alpha(5), beta(4), gamma(3), delta(2), epsilon(1)
            "text": "alpha alpha alpha alpha alpha beta beta beta beta gamma gamma gamma delta delta epsilon",
            "topN": 5,
        },
    },
    {
        "name": "json-transform-count",
        "functions": ["json-transform-java", "json-transform-java-lite", "json-transform-python", "json-transform-exec"],
        "input": {
            "data": [
                {"dept": "eng", "salary": 100, "age": 30},
                {"dept": "eng", "salary": 200, "age": 35},
                {"dept": "sales", "salary": 300, "age": 28},
            ],
            "groupBy": "dept",
            "operation": "count",
        },
    },
    {
        "name": "json-transform-sum",
        "functions": ["json-transform-java", "json-transform-java-lite", "json-transform-python", "json-transform-exec"],
        "input": {
            "data": [
                {"dept": "eng", "salary": 100, "age": 30},
                {"dept": "eng", "salary": 200, "age": 35},
                {"dept": "sales", "salary": 300, "age": 28},
            ],
            "groupBy": "dept",
            "operation": "sum",
            "valueField": "salary",
        },
    },
]

def invoke(function_name, payload):
    url = f"{base_url}/v1/functions/{function_name}:invoke"
    req = urllib.request.Request(
        url,
        data=json.dumps({"input": payload}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            body = resp.read().decode("utf-8")
            status = resp.getcode()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"[parity] {function_name} returned HTTP {exc.code}: {body[:400]}")
    except Exception as exc:
        raise SystemExit(f"[parity] {function_name} request failed: {exc}")

    if status != 200:
        raise SystemExit(f"[parity] {function_name} unexpected status={status} body={body[:400]}")
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[parity] {function_name} returned non-JSON body: {exc}")
    return extract_output(parsed)

all_ok = True
for case in CASES:
    outputs = []
    for fn in case["functions"]:
        outputs.append((fn, invoke(fn, case["input"])))
    mismatches = compare_case_outputs(outputs, case_name=case["name"])
    if mismatches:
        all_ok = False
        print(f"[parity] mismatch in case '{case['name']}':")
        for fn, baseline_fn, baseline_output, function_output in mismatches:
            print(f"  - {fn} differs from baseline {baseline_fn}")
            print(f"    baseline: {json.dumps(baseline_output, sort_keys=True)[:500]}")
            print(f"    actual  : {json.dumps(function_output, sort_keys=True)[:500]}")
    else:
        print(f"[parity] ok: {case['name']}")

if not all_ok:
    raise SystemExit(1)
PYEOF
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

    if [[ "${VERIFY_OUTPUT_PARITY}" == "true" ]]; then
        verify_output_parity "${nanofaas_url}" "${PARITY_TIMEOUT_SECONDS}"
        info "Output parity check passed across runtimes"
    else
        log "VERIFY_OUTPUT_PARITY=false, skipping output parity check"
    fi
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

    local tests=("${SELECTED_TESTS[@]}")

    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         STARTING K6 LOAD TESTS"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "  Target: ${nanofaas_url}"
    log "  Invocation mode: ${INVOCATION_MODE}"
    log "  Workloads: ${LOADTEST_WORKLOADS}"
    log "  Runtimes: ${LOADTEST_RUNTIMES}"
    log "  Tests:  ${#tests[@]} functions"
    if [[ -n "${K6_STAGE_SEQUENCE}" ]]; then
        log "  Stages override: ${K6_STAGE_SEQUENCE}"
    fi
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
            "${K6_STAGE_ARGS[@]}" \
            --env "NANOFAAS_URL=${nanofaas_url}" \
            --env "INVOCATION_MODE=${INVOCATION_MODE}" \
            --summary-export="${RESULTS_DIR}/${test}.json" \
            "${script}" 2>&1 | tee "${RESULTS_DIR}/${test}.log" \
            | grep -E "█|✓|✗|http_req_duration\b|http_req_failed\b|http_reqs\b|iterations\b|default" \
            | grep -v "running (" \
            || true

        local test_end_epoch
        test_end_epoch=$(date +%s)
        printf '{"function":"%s","mode":"%s","start":%s,"end":%s}\n' \
            "${test}" "${INVOCATION_MODE}" "${test_start_epoch}" "${test_end_epoch}" >> "${windows_file}"
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
    local tests=("${SELECTED_TESTS[@]}")
    log ""
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "         PERFORMANCE REPORT (${INVOCATION_MODE^^})"
    log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log ""

    # Generate the summary table
    python3 - "${RESULTS_DIR}" "${INVOCATION_MODE}" "${tests[@]}" << 'PYEOF'
import json, os, sys, glob

results_dir = sys.argv[1]
mode = sys.argv[2]
tests = sys.argv[3:] or [
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
print(f"Analysis ({mode}):")
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
    log "         LOAD TEST COMPLETE (${INVOCATION_MODE^^})"
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
    parse_args "$@"
    prepare_loadtest_configuration
    preflight
    start_grafana
    run_tests
    generate_report
    print_summary
}

main "$@"
