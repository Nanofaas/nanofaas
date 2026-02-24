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

start_control_plane_sampler() {
    local samples_file="$1"
    local loadtest_pid="$2"
    (
        while kill -0 "${loadtest_pid}" >/dev/null 2>&1; do
            local sample=""
            sample=$(VM_NAME="${VM_NAME}" e2e_vm_exec \
                "sudo kubectl top pods -n ${NAMESPACE} -l app=nanofaas-control-plane --no-headers 2>/dev/null | head -n 1" || true)
            if [[ -n "${sample}" ]]; then
                echo "$(date +%s) ${sample}" >> "${samples_file}"
            fi
            sleep "${SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    echo $!
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
    local samples_file="${case_dir}/control-plane-top-samples.txt"
    : > "${samples_file}"
    mkdir -p "${loadtest_dir}"

    ab_log "Running loadtest for case '${case_name}'..."
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

    local sampler_pid
    sampler_pid="$(start_control_plane_sampler "${samples_file}" "${loadtest_pid}")"

    local rc=0
    wait "${loadtest_pid}" || rc=$?
    if kill -0 "${sampler_pid}" >/dev/null 2>&1; then
        wait "${sampler_pid}" || true
    fi
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
    python3 - "${case_name}" "${runtime}" "${case_dir}" "${out_json}" <<'PYEOF'
import json
import re
import sys
from pathlib import Path

case_name = sys.argv[1]
runtime = sys.argv[2]
case_dir = Path(sys.argv[3])
out_json = Path(sys.argv[4])
loadtest_dir = case_dir / "loadtest"
samples_file = case_dir / "control-plane-top-samples.txt"

json_files = sorted(p for p in loadtest_dir.glob("*.json") if p.name != "summary.json")
total_reqs = 0
total_fails = 0
weighted_avg_sum = 0.0
weighted_p95_sum = 0.0
weighted_rate_sum = 0.0

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

cpu_samples = []
mem_samples = []
if samples_file.is_file():
    for raw in samples_file.read_text(encoding="utf-8", errors="ignore").splitlines():
        parts = raw.split()
        if len(parts) < 4:
            continue
        cpu = parts[2]
        mem = parts[3]
        try:
            cpu_samples.append(parse_cpu_m(cpu))
            mem_samples.append(parse_mem_mi(mem))
        except Exception:
            continue

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
    },
    "resources": {
        "sample_count": len(cpu_samples),
        "cpu_m_avg": (sum(cpu_samples) / len(cpu_samples)) if cpu_samples else 0.0,
        "cpu_m_peak": max(cpu_samples) if cpu_samples else 0.0,
        "mem_mi_avg": (sum(mem_samples) / len(mem_samples)) if mem_samples else 0.0,
        "mem_mi_peak": max(mem_samples) if mem_samples else 0.0,
    },
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

lines.append("")
lines.append("## Notes")
lines.append("- Loadtest metrics are aggregated from k6 JSON summaries produced by `experiments/e2e-loadtest.sh`.")
lines.append("- Resource metrics come from `kubectl top` sampling of control-plane pods during loadtest.")
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
