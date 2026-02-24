#!/usr/bin/env bash
set -euo pipefail

#
# Memory A/B runner for control-plane epoch timestamp compaction.
# Runs two scenarios (baseline vs epoch-enabled), captures JVM samples while k6
# runs, and writes a comparison report.
#
# Usage:
#   ./experiments/e2e-memory-ab.sh
#
# Key env vars:
#   VM_NAME                          (default: nanofaas-e2e)
#   CPUS/MEMORY/DISK/NAMESPACE       VM + k8s settings
#   LOADTEST_WORKLOADS               (default: word-stats)
#   LOADTEST_RUNTIMES                (default: java)
#   INVOCATION_MODE                  (default: sync)
#   K6_STAGE_SEQUENCE                (default stress profile)
#   K6_PAYLOAD_MODE                  (default: pool-sequential)
#   K6_PAYLOAD_POOL_SIZE             (default: 5000)
#   CONTROL_PLANE_NATIVE_BUILD       (default: false)
#   CONTROL_PLANE_MODULES            (default: none)
#   HOST_REBUILD_IMAGES              (default: false)
#   KEEP_VM_AFTER                    Keep VM after both runs (default: false)
#   SAMPLE_INTERVAL_SECONDS          JVM sample interval (default: 5)
#   RESULTS_BASE_DIR                 Output root (default: experiments/k6/results/memory-ab-<ts>)
#

VM_NAME=${VM_NAME:-nanofaas-e2e}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas}
CONTROL_PLANE_NATIVE_BUILD=${CONTROL_PLANE_NATIVE_BUILD:-false}
CONTROL_PLANE_MODULES=${CONTROL_PLANE_MODULES:-none}
HOST_REBUILD_IMAGES=${HOST_REBUILD_IMAGES:-false}
LOADTEST_WORKLOADS=${LOADTEST_WORKLOADS:-word-stats}
LOADTEST_RUNTIMES=${LOADTEST_RUNTIMES:-java}
INVOCATION_MODE=${INVOCATION_MODE:-sync}
CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}
K6_STAGE_SEQUENCE=${K6_STAGE_SEQUENCE:-20s:86,60s:171,60s:300,60s:300,20s:0}
K6_PAYLOAD_MODE=${K6_PAYLOAD_MODE:-pool-sequential}
K6_PAYLOAD_POOL_SIZE=${K6_PAYLOAD_POOL_SIZE:-5000}
SKIP_GRAFANA=${SKIP_GRAFANA:-true}
VERIFY_OUTPUT_PARITY=${VERIFY_OUTPUT_PARITY:-false}
SAMPLE_INTERVAL_SECONDS=${SAMPLE_INTERVAL_SECONDS:-5}
TAG_PREFIX=${TAG_PREFIX:-mem-ab}
KEEP_VM_AFTER=${KEEP_VM_AFTER:-false}
RUN_BASELINE=${RUN_BASELINE:-true}
RUN_EPOCH_ON=${RUN_EPOCH_ON:-true}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${PROJECT_ROOT}/scripts/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "mem-ab"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_BASE_DIR=${RESULTS_BASE_DIR:-${PROJECT_ROOT}/experiments/k6/results/memory-ab-${TIMESTAMP}}

ab_log() { e2e_log "$@"; }
ab_warn() { warn "$@"; }
ab_err() { err "$@"; }

cleanup_vm_if_needed() {
    if [[ "${KEEP_VM_AFTER}" == "true" ]]; then
        ab_log "KEEP_VM_AFTER=true, preserving VM '${VM_NAME}'."
        return
    fi
    ab_log "Cleaning up VM '${VM_NAME}'..."
    KEEP_VM=false e2e_cleanup_vm
}

resolve_vm_ip_or_fail() {
    local ip
    ip="$(VM_NAME="${VM_NAME}" e2e_get_vm_ip || true)"
    if [[ -z "${ip}" ]]; then
        ab_err "Cannot resolve VM IP for '${VM_NAME}'."
        exit 1
    fi
    printf "%s" "${ip}"
}

sample_prometheus_text_to_jsonl() {
    local ts_epoch="$1"
    local text_file="$2"
    local out_file="$3"
    python3 - "${ts_epoch}" "${text_file}" "${out_file}" <<'PYEOF'
import json
import re
import sys
from pathlib import Path

ts_epoch = int(sys.argv[1])
text = Path(sys.argv[2]).read_text(encoding="utf-8", errors="ignore")
out_file = Path(sys.argv[3])

line_re = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+([-+0-9.eE]+)$')
label_re = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"])*)"')

heap_used = 0.0
heap_max = 0.0
gc_pause_count = 0.0
gc_pause_sum = 0.0
threads_live = None

for raw in text.splitlines():
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    m = line_re.match(line)
    if not m:
        continue
    metric = m.group(1)
    labels_blob = m.group(3) or ""
    labels = {k: v for k, v in label_re.findall(labels_blob)}
    try:
        value = float(m.group(4))
    except ValueError:
        continue

    if metric == "jvm_memory_used_bytes" and labels.get("area") == "heap":
        heap_used += value
    elif metric == "jvm_memory_max_bytes" and labels.get("area") == "heap":
        heap_max += value
    elif metric == "jvm_gc_pause_seconds_count":
        gc_pause_count += value
    elif metric == "jvm_gc_pause_seconds_sum":
        gc_pause_sum += value
    elif metric == "jvm_threads_live_threads":
        threads_live = value

payload = {
    "ts": ts_epoch,
    "heap_used_bytes": heap_used,
    "heap_max_bytes": heap_max,
    "gc_pause_count_total": gc_pause_count,
    "gc_pause_seconds_total": gc_pause_sum,
    "threads_live": threads_live,
}
with out_file.open("a", encoding="utf-8") as f:
    f.write(json.dumps(payload) + "\n")
PYEOF
}

start_jvm_sampler() {
    local vm_ip="$1"
    local out_file="$2"
    local watched_pid="$3"
    (
        while kill -0 "${watched_pid}" >/dev/null 2>&1; do
            local ts_epoch
            ts_epoch="$(date +%s)"
            local scrape_file
            scrape_file="$(mktemp /tmp/nanofaas-jvm-prom.XXXXXX.txt)"
            if curl -fsS --max-time 5 "http://${vm_ip}:30081/actuator/prometheus" > "${scrape_file}" 2>/dev/null; then
                sample_prometheus_text_to_jsonl "${ts_epoch}" "${scrape_file}" "${out_file}" || true
            fi
            rm -f "${scrape_file}"
            sleep "${SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    echo $!
}

run_deploy_case() {
    local case_name="$1"
    local epoch_enabled="$2"
    local case_dir="$3"
    local tag="${TAG_PREFIX}-${case_name}-${TIMESTAMP}"
    local deploy_log="${case_dir}/deploy.log"
    mkdir -p "${case_dir}"

    ab_log "Deploying case '${case_name}' (epoch=${epoch_enabled}, tag=${tag})..."
    (
        E2E_K3S_HELM_NONINTERACTIVE=true \
        VM_NAME="${VM_NAME}" \
        CPUS="${CPUS}" \
        MEMORY="${MEMORY}" \
        DISK="${DISK}" \
        NAMESPACE="${NAMESPACE}" \
        KEEP_VM=true \
        TAG="${tag}" \
        CONTROL_PLANE_RUNTIME="${CONTROL_PLANE_RUNTIME}" \
        CONTROL_PLANE_NATIVE_BUILD="${CONTROL_PLANE_NATIVE_BUILD}" \
        CONTROL_PLANE_BUILD_ON_HOST=true \
        CONTROL_PLANE_ONLY=false \
        CONTROL_PLANE_EPOCH_MILLIS_ENABLED="${epoch_enabled}" \
        CONTROL_PLANE_MODULES="${CONTROL_PLANE_MODULES}" \
        HOST_REBUILD_IMAGES="${HOST_REBUILD_IMAGES}" \
        LOADTEST_WORKLOADS="${LOADTEST_WORKLOADS}" \
        LOADTEST_RUNTIMES="${LOADTEST_RUNTIMES}" \
        bash "${PROJECT_ROOT}/scripts/e2e-k3s-helm.sh"
    ) > "${deploy_log}" 2>&1
}

run_loadtest_case() {
    local case_name="$1"
    local case_dir="$2"
    local loadtest_dir="${case_dir}/loadtest"
    local loadtest_log="${case_dir}/loadtest.log"
    local jvm_samples="${case_dir}/jvm-samples.jsonl"
    : > "${jvm_samples}"
    mkdir -p "${loadtest_dir}"

    ab_log "Running loadtest for case '${case_name}'..."
    (
        VM_NAME="${VM_NAME}" \
        SKIP_GRAFANA="${SKIP_GRAFANA}" \
        CONTROL_PLANE_RUNTIME="${CONTROL_PLANE_RUNTIME}" \
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

    local vm_ip
    vm_ip="$(resolve_vm_ip_or_fail)"
    local sampler_pid
    sampler_pid="$(start_jvm_sampler "${vm_ip}" "${jvm_samples}" "${loadtest_pid}")"

    local rc=0
    wait "${loadtest_pid}" || rc=$?
    if kill -0 "${sampler_pid}" >/dev/null 2>&1; then
        wait "${sampler_pid}" || true
    fi
    if [[ ${rc} -ne 0 ]]; then
        ab_err "Loadtest case '${case_name}' failed (exit=${rc}). See ${loadtest_log}."
        exit "${rc}"
    fi

    if [[ ! -s "${jvm_samples}" ]]; then
        ab_warn "No JVM samples collected for case '${case_name}'."
    fi
}

summarize_case() {
    local case_name="$1"
    local case_dir="$2"
    local out_json="${case_dir}/summary.json"
    python3 - "${case_name}" "${case_dir}" "${out_json}" <<'PYEOF'
import json
import sys
from pathlib import Path

case_name = sys.argv[1]
case_dir = Path(sys.argv[2])
out_json = Path(sys.argv[3])
loadtest_dir = case_dir / "loadtest"
jvm_samples_file = case_dir / "jvm-samples.jsonl"
prom_snaps_file = loadtest_dir / "prom-snapshots.jsonl"

json_files = sorted(p for p in loadtest_dir.glob("*.json") if p.name != "summary.json")

total_reqs = 0
total_fails = 0
weighted_fail_sum = 0.0
weighted_avg_sum = 0.0
weighted_p95_sum = 0.0
weighted_p99_sum = 0.0
count_with_p99 = 0

for jf in json_files:
    data = json.loads(jf.read_text(encoding="utf-8"))
    m = data.get("metrics", {})
    reqs = int(m.get("http_reqs", {}).get("count", 0))
    failed = m.get("http_req_failed", {})
    if "value" in failed:
        fail_ratio = float(failed.get("value", 0.0))
    else:
        if "fails" in failed:
            fails = int(failed.get("fails", 0))
        elif "passes" in failed:
            fails = max(0, reqs - int(failed.get("passes", 0)))
        else:
            fails = 0
        fail_ratio = (fails / reqs) if reqs else 0.0
    fails = int(round(fail_ratio * reqs))
    dur = m.get("http_req_duration", {})
    avg = float(dur.get("avg", 0.0))
    p95 = float(dur.get("p(95)", 0.0))
    p99 = float(dur.get("p(99)", 0.0)) if "p(99)" in dur else None

    total_reqs += reqs
    total_fails += fails
    weighted_fail_sum += fail_ratio * reqs
    weighted_avg_sum += avg * reqs
    weighted_p95_sum += p95 * reqs
    if p99 is not None:
        weighted_p99_sum += p99 * reqs
        count_with_p99 += reqs

loadtest = {
    "files": [p.name for p in json_files],
    "total_reqs": total_reqs,
    "total_fails": total_fails,
    "fail_pct": (weighted_fail_sum / total_reqs * 100.0) if total_reqs else 0.0,
    "avg_ms_weighted": (weighted_avg_sum / total_reqs) if total_reqs else 0.0,
    "p95_ms_weighted": (weighted_p95_sum / total_reqs) if total_reqs else 0.0,
    "p99_ms_weighted": (weighted_p99_sum / count_with_p99) if count_with_p99 else None,
}

# Fallback p99 from Prometheus snapshots if k6 summary lacks p99.
prom_p99_vals = []
if prom_snaps_file.is_file():
    for line in prom_snaps_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
            val = row.get("metrics", {}).get("latency_p99")
            if val is not None:
                prom_p99_vals.append(float(val) * 1000.0)
        except Exception:
            pass
if loadtest["p99_ms_weighted"] is None and prom_p99_vals:
    loadtest["p99_ms_weighted"] = sum(prom_p99_vals) / len(prom_p99_vals)

samples = []
if jvm_samples_file.is_file():
    for line in jvm_samples_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            samples.append(json.loads(line))
        except Exception:
            pass

if samples:
    heap_used = [float(s.get("heap_used_bytes", 0.0)) for s in samples]
    heap_max = [float(s.get("heap_max_bytes", 0.0)) for s in samples]
    gc_count = [float(s.get("gc_pause_count_total", 0.0)) for s in samples]
    gc_sum = [float(s.get("gc_pause_seconds_total", 0.0)) for s in samples]
    ratios = []
    for used, mx in zip(heap_used, heap_max):
        if mx > 0:
            ratios.append(used / mx)
    jvm = {
        "samples": len(samples),
        "heap_used_avg_mb": (sum(heap_used) / len(heap_used)) / (1024 * 1024),
        "heap_used_peak_mb": (max(heap_used) / (1024 * 1024)),
        "heap_utilization_peak_pct": (max(ratios) * 100.0) if ratios else None,
        "gc_pause_count_delta": gc_count[-1] - gc_count[0],
        "gc_pause_seconds_delta": gc_sum[-1] - gc_sum[0],
    }
else:
    jvm = {
        "samples": 0,
        "heap_used_avg_mb": None,
        "heap_used_peak_mb": None,
        "heap_utilization_peak_pct": None,
        "gc_pause_count_delta": None,
        "gc_pause_seconds_delta": None,
    }

payload = {
    "case": case_name,
    "loadtest": loadtest,
    "jvm": jvm,
}
out_json.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
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
epoch_on = load_case("epoch-on")

def delta(new, old):
    if new is None or old is None:
        return None
    return new - old

def fmt(v, digits=2):
    if v is None:
        return "n/a"
    return f"{v:.{digits}f}"

comparison = {"baseline": baseline, "epoch_on": epoch_on, "deltas": {}}
if baseline and epoch_on:
    b = baseline["loadtest"]
    e = epoch_on["loadtest"]
    bj = baseline["jvm"]
    ej = epoch_on["jvm"]
    comparison["deltas"] = {
        "heap_used_avg_mb": delta(ej.get("heap_used_avg_mb"), bj.get("heap_used_avg_mb")),
        "heap_used_peak_mb": delta(ej.get("heap_used_peak_mb"), bj.get("heap_used_peak_mb")),
        "gc_pause_seconds_delta": delta(ej.get("gc_pause_seconds_delta"), bj.get("gc_pause_seconds_delta")),
        "p95_ms_weighted": delta(e.get("p95_ms_weighted"), b.get("p95_ms_weighted")),
        "p99_ms_weighted": delta(e.get("p99_ms_weighted"), b.get("p99_ms_weighted")),
        "fail_pct": delta(e.get("fail_pct"), b.get("fail_pct")),
    }

out_json.write_text(json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8")

lines = []
lines.append("# Memory A/B Comparison")
lines.append("")
lines.append(f"Artifacts: `{base_dir}`")
lines.append("")
lines.append("| Metric | Baseline | Epoch ON | Delta (ON-Base) |")
lines.append("|---|---:|---:|---:|")

def row(label, b, e, digits=2):
    lines.append(f"| {label} | {fmt(b, digits)} | {fmt(e, digits)} | {fmt(delta(e, b), digits)} |")

if baseline and epoch_on:
    b = baseline["loadtest"]
    e = epoch_on["loadtest"]
    bj = baseline["jvm"]
    ej = epoch_on["jvm"]
    row("Heap used avg (MB)", bj.get("heap_used_avg_mb"), ej.get("heap_used_avg_mb"))
    row("Heap used peak (MB)", bj.get("heap_used_peak_mb"), ej.get("heap_used_peak_mb"))
    row("GC pause delta (s)", bj.get("gc_pause_seconds_delta"), ej.get("gc_pause_seconds_delta"), 4)
    row("Latency p95 (ms)", b.get("p95_ms_weighted"), e.get("p95_ms_weighted"))
    row("Latency p99 (ms)", b.get("p99_ms_weighted"), e.get("p99_ms_weighted"))
    row("Fail rate (%)", b.get("fail_pct"), e.get("fail_pct"), 4)

    guardrail_ok = (
        (comparison["deltas"]["p95_ms_weighted"] is None or comparison["deltas"]["p95_ms_weighted"] <= 0.0)
        and (comparison["deltas"]["p99_ms_weighted"] is None or comparison["deltas"]["p99_ms_weighted"] <= 0.0)
        and (comparison["deltas"]["fail_pct"] is None or comparison["deltas"]["fail_pct"] <= 0.0)
    )
    lines.append("")
    lines.append(f"Guardrail status: **{'PASS' if guardrail_ok else 'FAIL'}**")
else:
    lines.append("| n/a | n/a | n/a | n/a |")
    lines.append("")
    lines.append("Guardrail status: **n/a** (missing case summaries)")

lines.append("")
lines.append("## Notes")
lines.append("- Latency and fail-rate are aggregated from k6 summary JSON files in each case directory.")
lines.append("- P99 uses k6 when available; otherwise it falls back to Prometheus snapshots captured during loadtest.")
lines.append("- JVM metrics are sampled from `/actuator/prometheus` during the loadtest window.")

out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")
PYEOF
}

run_case() {
    local case_name="$1"
    local epoch_enabled="$2"
    local case_dir="${RESULTS_BASE_DIR}/${case_name}"
    run_deploy_case "${case_name}" "${epoch_enabled}" "${case_dir}"
    run_loadtest_case "${case_name}" "${case_dir}"
    summarize_case "${case_name}" "${case_dir}"
}

print_usage() {
    cat <<EOF
Usage: ./experiments/e2e-memory-ab.sh

Runs baseline and epoch-enabled scenarios, captures JVM samples, and writes:
  - ${RESULTS_BASE_DIR}/baseline/*
  - ${RESULTS_BASE_DIR}/epoch-on/*
  - ${RESULTS_BASE_DIR}/comparison.md
  - ${RESULTS_BASE_DIR}/comparison.json
EOF
}

guard_runtime_support() {
    local runtime_kind
    runtime_kind="$(e2e_runtime_kind)"
    if [[ "${runtime_kind}" == "rust" ]]; then
        ab_warn "SKIP: e2e-memory-ab targets Java control-plane JVM memory profiling and is not supported for CONTROL_PLANE_RUNTIME=rust."
        exit 0
    fi
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        print_usage
        exit 0
    fi

    guard_runtime_support

    mkdir -p "${RESULTS_BASE_DIR}"
    ab_log "Results directory: ${RESULTS_BASE_DIR}"
    ab_log "Loadtest selection: workloads=${LOADTEST_WORKLOADS} runtimes=${LOADTEST_RUNTIMES} mode=${INVOCATION_MODE}"
    ab_log "Control-plane runtime: ${CONTROL_PLANE_RUNTIME} (kind=$(e2e_runtime_kind))"
    ab_log "K6 stages: ${K6_STAGE_SEQUENCE}"
    ab_log "Control-plane: native=${CONTROL_PLANE_NATIVE_BUILD} modules=${CONTROL_PLANE_MODULES}"

    if [[ "${RUN_BASELINE}" == "true" ]]; then
        run_case "baseline" "false"
    fi
    if [[ "${RUN_EPOCH_ON}" == "true" ]]; then
        run_case "epoch-on" "true"
    fi

    write_final_report
    cleanup_vm_if_needed

    ab_log "A/B comparison ready:"
    ab_log "  ${RESULTS_BASE_DIR}/comparison.md"
    ab_log "  ${RESULTS_BASE_DIR}/comparison.json"
}

main "$@"
