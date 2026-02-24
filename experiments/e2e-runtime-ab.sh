#!/usr/bin/env bash
set -euo pipefail

#
# Runtime A/B runner for control-plane comparison.
# Deploys two control-plane runtimes sequentially (baseline and candidate),
# runs the same loadtest selection, and writes a comparison report.
#
# Usage:
#   ./experiments/e2e-runtime-ab.sh
#
# Key env vars:
#   BASELINE_RUNTIME                 (default: java)
#   CANDIDATE_RUNTIME                (default: rust)
#   VM_NAME/CPUS/MEMORY/DISK         VM settings
#   NAMESPACE                        Kubernetes namespace (default: nanofaas)
#   LOADTEST_WORKLOADS               (default: word-stats,json-transform)
#   LOADTEST_RUNTIMES                (default: java,java-lite,python,exec)
#   INVOCATION_MODE                  (default: sync)
#   K6_STAGE_SEQUENCE                (default: loadtest defaults)
#   K6_PAYLOAD_MODE                  (default: legacy-random)
#   K6_PAYLOAD_POOL_SIZE             (default: 5000)
#   SKIP_GRAFANA                     (default: true)
#   VERIFY_OUTPUT_PARITY             (default: false)
#   HOST_REBUILD_IMAGES              (default: false)
#   CONTROL_PLANE_NATIVE_BUILD       (default: false)
#   CONTROL_PLANE_MODULES            (default: all)
#   CONTROL_PLANE_RUST_DIR           (optional)
#   KEEP_VM_AFTER                    Keep VM after run (default: false)
#   RESULTS_BASE_DIR                 Output root (default: experiments/k6/results/runtime-ab-<ts>)
#

VM_NAME=${VM_NAME:-nanofaas-e2e}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas}
BASELINE_RUNTIME=${BASELINE_RUNTIME:-java}
CANDIDATE_RUNTIME=${CANDIDATE_RUNTIME:-rust}
CONTROL_PLANE_NATIVE_BUILD=${CONTROL_PLANE_NATIVE_BUILD:-false}
CONTROL_PLANE_MODULES=${CONTROL_PLANE_MODULES:-all}
HOST_REBUILD_IMAGES=${HOST_REBUILD_IMAGES:-false}
LOADTEST_WORKLOADS=${LOADTEST_WORKLOADS:-word-stats,json-transform}
LOADTEST_RUNTIMES=${LOADTEST_RUNTIMES:-java,java-lite,python,exec}
INVOCATION_MODE=${INVOCATION_MODE:-sync}
K6_STAGE_SEQUENCE=${K6_STAGE_SEQUENCE:-}
K6_PAYLOAD_MODE=${K6_PAYLOAD_MODE:-legacy-random}
K6_PAYLOAD_POOL_SIZE=${K6_PAYLOAD_POOL_SIZE:-5000}
SKIP_GRAFANA=${SKIP_GRAFANA:-true}
VERIFY_OUTPUT_PARITY=${VERIFY_OUTPUT_PARITY:-false}
SAMPLE_INTERVAL_SECONDS=${SAMPLE_INTERVAL_SECONDS:-5}
TAG_PREFIX=${TAG_PREFIX:-runtime-ab}
KEEP_VM_AFTER=${KEEP_VM_AFTER:-false}
RUN_BASELINE=${RUN_BASELINE:-true}
RUN_CANDIDATE=${RUN_CANDIDATE:-true}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${PROJECT_ROOT}/scripts/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "runtime-ab"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_BASE_DIR=${RESULTS_BASE_DIR:-${PROJECT_ROOT}/experiments/k6/results/runtime-ab-${TIMESTAMP}}

ab_log() { log "$@"; }
ab_warn() { warn "$@"; }
ab_err() { err "$@"; }

normalize_runtime() {
    local raw="$1"
    local lowered
    lowered=$(printf '%s' "${raw}" | tr '[:upper:]' '[:lower:]')
    case "${lowered}" in
        java|rust)
            echo "${lowered}"
            ;;
        *)
            ab_err "Unsupported runtime '${raw}'. Allowed: java,rust"
            exit 2
            ;;
    esac
}

cleanup_vm_if_needed() {
    if [[ "${KEEP_VM_AFTER}" == "true" ]]; then
        ab_log "KEEP_VM_AFTER=true, preserving VM '${VM_NAME}'."
        return
    fi
    ab_log "Cleaning up VM '${VM_NAME}'..."
    KEEP_VM=false VM_NAME="${VM_NAME}" e2e_cleanup_vm
}

run_deploy_case() {
    local case_name="$1"
    local runtime="$2"
    local case_dir="$3"
    local tag="${TAG_PREFIX}-${case_name}-${TIMESTAMP}"
    local deploy_log="${case_dir}/deploy.log"
    mkdir -p "${case_dir}"

    ab_log "Deploying case '${case_name}' (runtime=${runtime}, tag=${tag})..."
    (
        E2E_K3S_HELM_NONINTERACTIVE=true \
        VM_NAME="${VM_NAME}" \
        CPUS="${CPUS}" \
        MEMORY="${MEMORY}" \
        DISK="${DISK}" \
        NAMESPACE="${NAMESPACE}" \
        KEEP_VM=true \
        TAG="${tag}" \
        CONTROL_PLANE_RUNTIME="${runtime}" \
        CONTROL_PLANE_NATIVE_BUILD="${CONTROL_PLANE_NATIVE_BUILD}" \
        CONTROL_PLANE_BUILD_ON_HOST=true \
        CONTROL_PLANE_ONLY=false \
        CONTROL_PLANE_MODULES="${CONTROL_PLANE_MODULES}" \
        HOST_REBUILD_IMAGES="${HOST_REBUILD_IMAGES}" \
        LOADTEST_WORKLOADS="${LOADTEST_WORKLOADS}" \
        LOADTEST_RUNTIMES="${LOADTEST_RUNTIMES}" \
        CONTROL_PLANE_RUST_DIR="${CONTROL_PLANE_RUST_DIR:-}" \
        bash "${PROJECT_ROOT}/scripts/e2e-k3s-helm.sh"
    ) > "${deploy_log}" 2>&1
}

run_loadtest_case() {
    local case_name="$1"
    local runtime="$2"
    local case_dir="$3"
    local loadtest_dir="${case_dir}/loadtest"
    local loadtest_log="${case_dir}/loadtest.log"
    local window_file="${case_dir}/loadtest-window.json"
    local loadtest_start_epoch=""
    local loadtest_end_epoch=""
    mkdir -p "${loadtest_dir}"

    ab_log "Running loadtest for case '${case_name}'..."
    loadtest_start_epoch="$(date +%s)"
    (
        VM_NAME="${VM_NAME}" \
        CONTROL_PLANE_RUNTIME="${runtime}" \
        SKIP_GRAFANA="${SKIP_GRAFANA}" \
        VERIFY_OUTPUT_PARITY="${VERIFY_OUTPUT_PARITY}" \
        LOADTEST_WORKLOADS="${LOADTEST_WORKLOADS}" \
        LOADTEST_RUNTIMES="${LOADTEST_RUNTIMES}" \
        INVOCATION_MODE="${INVOCATION_MODE}" \
        K6_STAGE_SEQUENCE="${K6_STAGE_SEQUENCE}" \
        K6_PAYLOAD_MODE="${K6_PAYLOAD_MODE}" \
        K6_PAYLOAD_POOL_SIZE="${K6_PAYLOAD_POOL_SIZE}" \
        RESULTS_DIR_OVERRIDE="${loadtest_dir}" \
        bash "${PROJECT_ROOT}/experiments/e2e-loadtest.sh"
    ) > "${loadtest_log}" 2>&1 &
    local loadtest_pid=$!

    local rc=0
    wait "${loadtest_pid}" || rc=$?
    loadtest_end_epoch="$(date +%s)"
    printf '{"start_epoch": %s, "end_epoch": %s}\n' "${loadtest_start_epoch}" "${loadtest_end_epoch}" > "${window_file}"
    if [[ ${rc} -ne 0 ]]; then
        ab_err "Loadtest case '${case_name}' failed (exit=${rc}). See ${loadtest_log}."
        exit "${rc}"
    fi
}

summarize_case() {
    local case_name="$1"
    local runtime="$2"
    local case_dir="$3"
    local out_json="${case_dir}/summary.json"
    local prom_url="${PROM_URL:-}"
    if [[ -z "${prom_url}" ]]; then
        prom_url="$(VM_NAME="${VM_NAME}" e2e_resolve_nanofaas_url 30090 || true)"
    fi
    python3 - "${case_name}" "${runtime}" "${case_dir}" "${out_json}" "${prom_url}" "${NAMESPACE}" <<'PYEOF'
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

case_name = sys.argv[1]
runtime = sys.argv[2]
case_dir = Path(sys.argv[3])
out_json = Path(sys.argv[4])
prom_url = sys.argv[5].strip()
namespace = sys.argv[6].strip()
loadtest_dir = case_dir / "loadtest"
window_file = case_dir / "loadtest-window.json"
legacy_cp_samples_file = case_dir / "control-plane-top-samples.txt"
legacy_fn_samples_file = case_dir / "function-top-samples.txt"

json_files = sorted(p for p in loadtest_dir.glob("*.json") if p.name != "summary.json")
total_reqs = 0
total_fails = 0
weighted_avg_sum = 0.0
weighted_p95_sum = 0.0
weighted_rate_sum = 0.0
loadtest_by_function = {}

for jf in json_files:
    data = json.loads(jf.read_text(encoding="utf-8"))
    m = data.get("metrics", {})
    reqs = int(m.get("http_reqs", {}).get("count", 0))
    failed = m.get("http_req_failed", {})
    if "passes" in failed:
        # k6 Rate summaries encode "passes" as samples where the metric is true.
        # For http_req_failed, "true" means request failed.
        fails = int(failed.get("passes", 0))
    elif "fails" in failed:
        # Fallback for legacy/non-standard summaries where false samples are not separated.
        fails = int(failed.get("fails", 0))
    else:
        fails = int(round(float(failed.get("value", 0.0)) * reqs))
    dur = m.get("http_req_duration", {})
    avg = float(dur.get("avg", 0.0))
    p95 = float(dur.get("p(95)", 0.0))
    rate = float(m.get("iterations", {}).get("rate", 0.0))

    total_reqs += reqs
    total_fails += fails
    weighted_avg_sum += avg * reqs
    weighted_p95_sum += p95 * reqs
    weighted_rate_sum += rate
    loadtest_by_function[jf.stem] = {
        "requests": reqs,
        "fails": fails,
        "fail_pct": (fails / reqs * 100.0) if reqs else 0.0,
        "avg_ms": avg,
        "p95_ms": p95,
        "iterations_rate": rate,
    }

def parse_cpu_m(cpu: str) -> float:
    cpu = cpu.strip()
    if cpu.endswith("m"):
        return float(cpu[:-1])
    return float(cpu) * 1000.0

def parse_mem_mi(mem: str) -> float:
    mem = mem.strip()
    unit_scale = {
        "Ki": 1.0 / 1024.0,
        "Mi": 1.0,
        "Gi": 1024.0,
        "Ti": 1024.0 * 1024.0,
    }
    m = re.match(r"^([0-9]+(?:\\.[0-9]+)?)(Ki|Mi|Gi|Ti)$", mem)
    if not m:
        return 0.0
    value = float(m.group(1))
    return value * unit_scale[m.group(2)]

def prom_query_range(expr: str, start_epoch: int, end_epoch: int, step_seconds: int = 15):
    if not prom_url or start_epoch <= 0 or end_epoch <= 0:
        return []
    if end_epoch < start_epoch:
        start_epoch, end_epoch = end_epoch, start_epoch
    if end_epoch == start_epoch:
        end_epoch += 1
    params = urllib.parse.urlencode(
        {
            "query": expr,
            "start": str(start_epoch),
            "end": str(end_epoch),
            "step": f"{step_seconds}s",
        }
    )
    url = f"{prom_url}/api/v1/query_range?{params}"
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            body = json.loads(resp.read())
        if body.get("status") != "success":
            return []
        return body.get("data", {}).get("result", [])
    except Exception:
        return []

def summarize_grouped_resources(cpu_rows, mem_rows, pod_to_group):
    grouped = {}
    pods_seen = {}

    def merge_rows(rows, key):
        for row in rows:
            pod = row.get("metric", {}).get("pod", "")
            group = pod_to_group(pod)
            if not group:
                continue
            for sample in row.get("values", []):
                if len(sample) < 2:
                    continue
                try:
                    ts = int(float(sample[0]))
                    val = float(sample[1])
                except Exception:
                    continue
                point = grouped.setdefault(group, {}).setdefault(
                    ts, {"cpu_m_total": 0.0, "mem_mi_total": 0.0, "pods": set()}
                )
                if key == "cpu":
                    point["cpu_m_total"] += val
                else:
                    point["mem_mi_total"] += val
                point["pods"].add(pod)
                pods_seen.setdefault(group, set()).add(pod)

    merge_rows(cpu_rows, "cpu")
    merge_rows(mem_rows, "mem")

    out = {}
    for group, by_ts in grouped.items():
        points = list(by_ts.values())
        cpu_totals = [p["cpu_m_total"] for p in points]
        mem_totals = [p["mem_mi_total"] for p in points]
        pod_counts = [len(p["pods"]) for p in points]
        out[group] = {
            "sample_count": len(points),
            "pods_seen": len(pods_seen.get(group, set())),
            "pods_avg": (sum(pod_counts) / len(pod_counts)) if pod_counts else 0.0,
            "pods_peak": max(pod_counts) if pod_counts else 0.0,
            "cpu_m_avg": (sum(cpu_totals) / len(cpu_totals)) if cpu_totals else 0.0,
            "cpu_m_peak": max(cpu_totals) if cpu_totals else 0.0,
            "mem_mi_avg": (sum(mem_totals) / len(mem_totals)) if mem_totals else 0.0,
            "mem_mi_peak": max(mem_totals) if mem_totals else 0.0,
        }
    return out

def load_window():
    if not window_file.is_file():
        return 0, 0
    try:
        payload = json.loads(window_file.read_text(encoding="utf-8"))
        start = int(payload.get("start_epoch", 0))
        end = int(payload.get("end_epoch", 0))
        return start, end
    except Exception:
        return 0, 0

def legacy_parse_samples():
    cpu_samples = []
    mem_samples = []
    if legacy_cp_samples_file.is_file():
        for raw in legacy_cp_samples_file.read_text(encoding="utf-8", errors="ignore").splitlines():
            parts = raw.split()
            if len(parts) < 4:
                continue
            try:
                cpu_samples.append(parse_cpu_m(parts[2]))
                mem_samples.append(parse_mem_mi(parts[3]))
            except Exception:
                continue

    fn_by_ts = {}
    fn_pods_seen = {}
    if legacy_fn_samples_file.is_file():
        for raw in legacy_fn_samples_file.read_text(encoding="utf-8", errors="ignore").splitlines():
            parts = raw.split()
            if len(parts) < 4:
                continue
            ts, pod, cpu, mem = parts[0], parts[1], parts[2], parts[3]
            pod_match = re.match(r"^fn-(.+?)-[a-f0-9]+-[a-z0-9]+$", pod)
            if not pod_match:
                continue
            fn_name = pod_match.group(1)
            try:
                cpu_m = parse_cpu_m(cpu)
                mem_mi = parse_mem_mi(mem)
            except Exception:
                continue
            point = fn_by_ts.setdefault(fn_name, {}).setdefault(
                ts, {"cpu_m_total": 0.0, "mem_mi_total": 0.0, "pod_count": 0}
            )
            point["cpu_m_total"] += cpu_m
            point["mem_mi_total"] += mem_mi
            point["pod_count"] += 1
            fn_pods_seen.setdefault(fn_name, set()).add(pod)

    function_resources = {}
    for fn_name, points_by_ts in fn_by_ts.items():
        points = list(points_by_ts.values())
        cpu_totals = [p["cpu_m_total"] for p in points]
        mem_totals = [p["mem_mi_total"] for p in points]
        pod_counts = [p["pod_count"] for p in points]
        function_resources[fn_name] = {
            "sample_count": len(points),
            "pods_seen": len(fn_pods_seen.get(fn_name, set())),
            "pods_avg": (sum(pod_counts) / len(pod_counts)) if pod_counts else 0.0,
            "pods_peak": max(pod_counts) if pod_counts else 0.0,
            "cpu_m_avg": (sum(cpu_totals) / len(cpu_totals)) if cpu_totals else 0.0,
            "cpu_m_peak": max(cpu_totals) if cpu_totals else 0.0,
            "mem_mi_avg": (sum(mem_totals) / len(mem_totals)) if mem_totals else 0.0,
            "mem_mi_peak": max(mem_totals) if mem_totals else 0.0,
        }

    control_plane = {
        "sample_count": len(cpu_samples),
        "cpu_m_avg": (sum(cpu_samples) / len(cpu_samples)) if cpu_samples else 0.0,
        "cpu_m_peak": max(cpu_samples) if cpu_samples else 0.0,
        "mem_mi_avg": (sum(mem_samples) / len(mem_samples)) if mem_samples else 0.0,
        "mem_mi_peak": max(mem_samples) if mem_samples else 0.0,
    }
    return control_plane, function_resources

start_epoch, end_epoch = load_window()
resource_source = "prometheus-cadvisor"

fn_cpu_expr = (
    f'sum by (pod) (rate(container_cpu_usage_seconds_total{{namespace="{namespace}",'
    f'pod=~"fn-.*",container!="",container!="POD"}}[1m])) * 1000'
)
fn_mem_expr_working_set = (
    f'sum by (pod) (container_memory_working_set_bytes{{namespace="{namespace}",'
    f'pod=~"fn-.*",container!="",container!="POD"}}) / 1048576'
)
fn_mem_expr_usage = (
    f'sum by (pod) (container_memory_usage_bytes{{namespace="{namespace}",'
    f'pod=~"fn-.*",container!="",container!="POD"}}) / 1048576'
)
cp_cpu_expr = (
    f'sum by (pod) (rate(container_cpu_usage_seconds_total{{namespace="{namespace}",'
    f'pod=~"nanofaas-control-plane-.*",container!="",container!="POD"}}[1m])) * 1000'
)
cp_mem_expr_working_set = (
    f'sum by (pod) (container_memory_working_set_bytes{{namespace="{namespace}",'
    f'pod=~"nanofaas-control-plane-.*",container!="",container!="POD"}}) / 1048576'
)
cp_mem_expr_usage = (
    f'sum by (pod) (container_memory_usage_bytes{{namespace="{namespace}",'
    f'pod=~"nanofaas-control-plane-.*",container!="",container!="POD"}}) / 1048576'
)

fn_cpu_rows = prom_query_range(fn_cpu_expr, start_epoch, end_epoch)
fn_mem_rows = prom_query_range(fn_mem_expr_working_set, start_epoch, end_epoch)
if not fn_mem_rows:
    fn_mem_rows = prom_query_range(fn_mem_expr_usage, start_epoch, end_epoch)

cp_cpu_rows = prom_query_range(cp_cpu_expr, start_epoch, end_epoch)
cp_mem_rows = prom_query_range(cp_mem_expr_working_set, start_epoch, end_epoch)
if not cp_mem_rows:
    cp_mem_rows = prom_query_range(cp_mem_expr_usage, start_epoch, end_epoch)

fn_regex = re.compile(r"^fn-(.+?)-[a-f0-9]+-[a-z0-9]+$")
def function_from_pod(pod: str):
    m = fn_regex.match(pod)
    return m.group(1) if m else None

def control_plane_group_from_pod(pod: str):
    if pod.startswith("nanofaas-control-plane-"):
        return "_control_plane"
    return None

function_resources = summarize_grouped_resources(fn_cpu_rows, fn_mem_rows, function_from_pod)
control_plane_resources = summarize_grouped_resources(cp_cpu_rows, cp_mem_rows, control_plane_group_from_pod).get(
    "_control_plane", {}
)

if not function_resources and not control_plane_resources:
    resource_source = "kubectl-top-fallback"
    control_plane_resources, function_resources = legacy_parse_samples()

summary = {
    "case": case_name,
    "runtime": runtime,
    "loadtest": {
        "files": [p.name for p in json_files],
        "total_reqs": total_reqs,
        "total_fails": total_fails,
        "fail_pct": (total_fails / total_reqs * 100.0) if total_reqs else 0.0,
        "avg_ms_weighted": (weighted_avg_sum / total_reqs) if total_reqs else 0.0,
        "p95_ms_weighted": (weighted_p95_sum / total_reqs) if total_reqs else 0.0,
        "avg_iterations_rate": (weighted_rate_sum / len(json_files)) if json_files else 0.0,
        "by_function": loadtest_by_function,
    },
    "resources": {
        "source": resource_source,
        "window_start_epoch": start_epoch,
        "window_end_epoch": end_epoch,
        "sample_count": int(control_plane_resources.get("sample_count", 0)),
        "cpu_m_avg": float(control_plane_resources.get("cpu_m_avg", 0.0)),
        "cpu_m_peak": float(control_plane_resources.get("cpu_m_peak", 0.0)),
        "mem_mi_avg": float(control_plane_resources.get("mem_mi_avg", 0.0)),
        "mem_mi_peak": float(control_plane_resources.get("mem_mi_peak", 0.0)),
    },
    "function_resources": function_resources,
}
out_json.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PYEOF
}

write_final_report() {
    local out_md="${RESULTS_BASE_DIR}/comparison.md"
    local out_json="${RESULTS_BASE_DIR}/comparison.json"
    python3 - "${RESULTS_BASE_DIR}" "${out_md}" "${out_json}" <<'PYEOF'
import json
import sys
from pathlib import Path

base_dir = Path(sys.argv[1])
out_md = Path(sys.argv[2])
out_json = Path(sys.argv[3])

def load_case(name: str):
    p = base_dir / name / "summary.json"
    if not p.is_file():
        return None
    return json.loads(p.read_text(encoding="utf-8"))

baseline = load_case("baseline")
candidate = load_case("candidate")

def delta(new, old):
    if new is None or old is None:
        return None
    return new - old

def fmt(v, digits=2):
    if v is None:
        return "n/a"
    return f"{v:.{digits}f}"

comparison = {"baseline": baseline, "candidate": candidate, "deltas": {}}

def fn_load(case):
    if not case:
        return {}
    return case.get("loadtest", {}).get("by_function", {})

def fn_resources(case):
    if not case:
        return {}
    return case.get("function_resources", {})

WORKLOAD_ORDER = {"word-stats": 0, "json-transform": 1}
RUNTIME_ORDER = {"java": 0, "java-lite": 1, "python": 2, "exec": 3}
RUNTIME_SUFFIXES = ("java-lite", "java", "python", "exec")

def function_sort_key(name: str):
    workload = None
    runtime = None
    for rt in RUNTIME_SUFFIXES:
        suffix = f"-{rt}"
        if name.endswith(suffix):
            workload = name[: -len(suffix)]
            runtime = rt
            break
    if workload is None or runtime is None:
        if "-" not in name:
            return (99, 99, name)
        workload, runtime = name.rsplit("-", 1)
    return (
        WORKLOAD_ORDER.get(workload, 99),
        RUNTIME_ORDER.get(runtime, 99),
        name,
    )

all_functions = sorted(
    set(fn_load(baseline)) | set(fn_load(candidate)) | set(fn_resources(baseline)) | set(fn_resources(candidate)),
    key=function_sort_key,
)

if baseline and candidate:
    b = baseline["loadtest"]
    c = candidate["loadtest"]
    br = baseline["resources"]
    cr = candidate["resources"]
    comparison["deltas"] = {
        "avg_ms_weighted": delta(c.get("avg_ms_weighted"), b.get("avg_ms_weighted")),
        "p95_ms_weighted": delta(c.get("p95_ms_weighted"), b.get("p95_ms_weighted")),
        "fail_pct": delta(c.get("fail_pct"), b.get("fail_pct")),
        "avg_iterations_rate": delta(c.get("avg_iterations_rate"), b.get("avg_iterations_rate")),
        "cpu_m_avg": delta(cr.get("cpu_m_avg"), br.get("cpu_m_avg")),
        "cpu_m_peak": delta(cr.get("cpu_m_peak"), br.get("cpu_m_peak")),
        "mem_mi_avg": delta(cr.get("mem_mi_avg"), br.get("mem_mi_avg")),
        "mem_mi_peak": delta(cr.get("mem_mi_peak"), br.get("mem_mi_peak")),
    }
    if all_functions:
        by_function_delta = {}
        for fn in all_functions:
            b_load = fn_load(baseline).get(fn, {})
            c_load = fn_load(candidate).get(fn, {})
            b_res = fn_resources(baseline).get(fn, {})
            c_res = fn_resources(candidate).get(fn, {})
            by_function_delta[fn] = {
                "avg_ms": delta(c_load.get("avg_ms"), b_load.get("avg_ms")),
                "p95_ms": delta(c_load.get("p95_ms"), b_load.get("p95_ms")),
                "fail_pct": delta(c_load.get("fail_pct"), b_load.get("fail_pct")),
                "iterations_rate": delta(c_load.get("iterations_rate"), b_load.get("iterations_rate")),
                "cpu_m_avg": delta(c_res.get("cpu_m_avg"), b_res.get("cpu_m_avg")),
                "cpu_m_peak": delta(c_res.get("cpu_m_peak"), b_res.get("cpu_m_peak")),
                "mem_mi_avg": delta(c_res.get("mem_mi_avg"), b_res.get("mem_mi_avg")),
                "mem_mi_peak": delta(c_res.get("mem_mi_peak"), b_res.get("mem_mi_peak")),
                "pods_avg": delta(c_res.get("pods_avg"), b_res.get("pods_avg")),
                "pods_peak": delta(c_res.get("pods_peak"), b_res.get("pods_peak")),
            }
        comparison["deltas"]["by_function"] = by_function_delta

out_json.write_text(json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8")

lines = []
lines.append("# Runtime A/B Comparison")
lines.append("")
lines.append(f"Artifacts: `{base_dir}`")
lines.append("")
if baseline:
    lines.append(f"- Baseline runtime: `{baseline.get('runtime', 'n/a')}`")
if candidate:
    lines.append(f"- Candidate runtime: `{candidate.get('runtime', 'n/a')}`")
lines.append("")
lines.append("| Metric | Baseline | Candidate | Delta (Candidate-Baseline) |")
lines.append("|---|---:|---:|---:|")

def row(label, b, c, digits=2):
    lines.append(f"| {label} | {fmt(b, digits)} | {fmt(c, digits)} | {fmt(delta(c, b), digits)} |")

if baseline or candidate:
    b = baseline["loadtest"] if baseline else {}
    c = candidate["loadtest"] if candidate else {}
    br = baseline["resources"] if baseline else {}
    cr = candidate["resources"] if candidate else {}
    row("Latency avg (ms)", b.get("avg_ms_weighted"), c.get("avg_ms_weighted"))
    row("Latency p95 (ms)", b.get("p95_ms_weighted"), c.get("p95_ms_weighted"))
    row("Fail rate (%)", b.get("fail_pct"), c.get("fail_pct"), 4)
    row("Throughput iter/s", b.get("avg_iterations_rate"), c.get("avg_iterations_rate"))
    row("CPU avg (m)", br.get("cpu_m_avg"), cr.get("cpu_m_avg"))
    row("CPU peak (m)", br.get("cpu_m_peak"), cr.get("cpu_m_peak"))
    row("Memory avg (Mi)", br.get("mem_mi_avg"), cr.get("mem_mi_avg"))
    row("Memory peak (Mi)", br.get("mem_mi_peak"), cr.get("mem_mi_peak"))
else:
    lines.append("| n/a | n/a | n/a | n/a |")

if all_functions:
    lines.append("")
    lines.append("## Per-function Loadtest Metrics")
    lines.append("")
    lines.append("### Latency")
    lines.append("| Function | Base avg (ms) | Cand avg (ms) | Δ avg (ms) | Base p95 (ms) | Cand p95 (ms) | Δ p95 (ms) |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")
    base_fn_load = fn_load(baseline)
    cand_fn_load = fn_load(candidate)
    for fn in all_functions:
        b = base_fn_load.get(fn, {})
        c = cand_fn_load.get(fn, {})
        lines.append(
            f"| {fn} | {fmt(b.get('avg_ms'))} | {fmt(c.get('avg_ms'))} | {fmt(delta(c.get('avg_ms'), b.get('avg_ms')))} | "
            f"{fmt(b.get('p95_ms'))} | {fmt(c.get('p95_ms'))} | {fmt(delta(c.get('p95_ms'), b.get('p95_ms')))} |"
        )

    lines.append("")
    lines.append("### Reliability & Throughput")
    lines.append("| Function | Base fail (%) | Cand fail (%) | Δ fail (%) | Base iter/s | Cand iter/s | Δ iter/s |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")
    for fn in all_functions:
        b = base_fn_load.get(fn, {})
        c = cand_fn_load.get(fn, {})
        lines.append(
            f"| {fn} | {fmt(b.get('fail_pct'), 4)} | {fmt(c.get('fail_pct'), 4)} | {fmt(delta(c.get('fail_pct'), b.get('fail_pct')), 4)} | "
            f"{fmt(b.get('iterations_rate'))} | {fmt(c.get('iterations_rate'))} | {fmt(delta(c.get('iterations_rate'), b.get('iterations_rate')))} |"
        )

    lines.append("")
    lines.append("## Per-function CPU/RAM")
    lines.append("")
    lines.append("### CPU")
    lines.append("| Function | Base CPU avg (m) | Cand CPU avg (m) | Δ CPU avg (m) | Base CPU peak (m) | Cand CPU peak (m) | Δ CPU peak (m) |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")
    base_fn_res = fn_resources(baseline)
    cand_fn_res = fn_resources(candidate)
    for fn in all_functions:
        b = base_fn_res.get(fn, {})
        c = cand_fn_res.get(fn, {})
        lines.append(
            f"| {fn} | {fmt(b.get('cpu_m_avg'))} | {fmt(c.get('cpu_m_avg'))} | {fmt(delta(c.get('cpu_m_avg'), b.get('cpu_m_avg')))} | "
            f"{fmt(b.get('cpu_m_peak'))} | {fmt(c.get('cpu_m_peak'))} | {fmt(delta(c.get('cpu_m_peak'), b.get('cpu_m_peak')))} |"
        )

    lines.append("")
    lines.append("### Memory & Replicas")
    lines.append("| Function | Base RAM avg (Mi) | Cand RAM avg (Mi) | Δ RAM avg (Mi) | Base RAM peak (Mi) | Cand RAM peak (Mi) | Δ RAM peak (Mi) | Base pods avg | Cand pods avg | Δ pods avg |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for fn in all_functions:
        b = base_fn_res.get(fn, {})
        c = cand_fn_res.get(fn, {})
        lines.append(
            f"| {fn} | {fmt(b.get('mem_mi_avg'))} | {fmt(c.get('mem_mi_avg'))} | {fmt(delta(c.get('mem_mi_avg'), b.get('mem_mi_avg')))} | "
            f"{fmt(b.get('mem_mi_peak'))} | {fmt(c.get('mem_mi_peak'))} | {fmt(delta(c.get('mem_mi_peak'), b.get('mem_mi_peak')))} | "
            f"{fmt(b.get('pods_avg'))} | {fmt(c.get('pods_avg'))} | {fmt(delta(c.get('pods_avg'), b.get('pods_avg')))} |"
        )

lines.append("")
lines.append("## Notes")
lines.append("- Loadtest metrics are aggregated from k6 JSON summaries produced by `experiments/e2e-loadtest.sh`.")
lines.append("- Per-function loadtest metrics are keyed by the k6 summary filename stem (for example `word-stats-java`).")
lines.append("- Resource metrics come from Prometheus/cAdvisor query_range over the loadtest window (`container_cpu_usage_seconds_total`, `container_memory_working_set_bytes`).")
lines.append("- If Prometheus/cAdvisor data is unavailable, a legacy `kubectl top` fallback is used.")
lines.append("- Positive delta means candidate is larger/slower for that metric.")
if not (baseline and candidate):
    lines.append("- Delta values are `n/a` when only one side (baseline or candidate) is available.")

out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")
PYEOF
}

run_case() {
    local case_name="$1"
    local runtime="$2"
    local case_dir="${RESULTS_BASE_DIR}/${case_name}"
    run_deploy_case "${case_name}" "${runtime}" "${case_dir}"
    run_loadtest_case "${case_name}" "${runtime}" "${case_dir}"
    summarize_case "${case_name}" "${runtime}" "${case_dir}"
}

print_usage() {
    cat <<EOF
Usage: ./experiments/e2e-runtime-ab.sh

Runs two runtime scenarios and writes:
  - ${RESULTS_BASE_DIR}/baseline/*
  - ${RESULTS_BASE_DIR}/candidate/*
  - ${RESULTS_BASE_DIR}/comparison.md
  - ${RESULTS_BASE_DIR}/comparison.json
EOF
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        print_usage
        exit 0
    fi

    local baseline_runtime
    local candidate_runtime
    baseline_runtime="$(normalize_runtime "${BASELINE_RUNTIME}")"
    candidate_runtime="$(normalize_runtime "${CANDIDATE_RUNTIME}")"

    mkdir -p "${RESULTS_BASE_DIR}"
    ab_log "Results directory: ${RESULTS_BASE_DIR}"
    ab_log "Baseline runtime=${baseline_runtime} Candidate runtime=${candidate_runtime}"
    ab_log "Loadtest selection: workloads=${LOADTEST_WORKLOADS} runtimes=${LOADTEST_RUNTIMES} mode=${INVOCATION_MODE}"
    ab_log "K6 stages: ${K6_STAGE_SEQUENCE:-<default>}"
    ab_log "Control-plane build: native=${CONTROL_PLANE_NATIVE_BUILD} modules=${CONTROL_PLANE_MODULES}"

    if [[ "${RUN_BASELINE}" == "true" ]]; then
        run_case "baseline" "${baseline_runtime}"
    fi
    if [[ "${RUN_CANDIDATE}" == "true" ]]; then
        run_case "candidate" "${candidate_runtime}"
    fi

    write_final_report
    cleanup_vm_if_needed

    ab_log "Runtime A/B comparison ready:"
    ab_log "  ${RESULTS_BASE_DIR}/comparison.md"
    ab_log "  ${RESULTS_BASE_DIR}/comparison.json"
}

main "$@"
