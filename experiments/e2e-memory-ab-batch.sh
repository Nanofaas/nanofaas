#!/usr/bin/env bash
set -euo pipefail

# Batch runner for memory A/B experiments.
# Runs e2e-memory-ab multiple times and computes aggregate statistics
# (median + mean) across runs. Supports local execution and remote SSH
# execution (run remote script + copy artifacts back locally).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${PROJECT_ROOT}/scripts/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "mem-ab-batch"

AB_MODE="${AB_MODE:-local}"                    # local | ssh
AB_RUNS="${AB_RUNS:-3}"                        # positive integer
AB_RUN_LABEL="${AB_RUN_LABEL:-$(date +%Y%m%d-%H%M%S)}"
BATCH_RESULTS_DIR="${BATCH_RESULTS_DIR:-${PROJECT_ROOT}/experiments/k6/results/memory-ab-batch-${AB_RUN_LABEL}}"

# Local single-run script.
AB_SINGLE_RUN_SCRIPT="${AB_SINGLE_RUN_SCRIPT:-${PROJECT_ROOT}/experiments/e2e-memory-ab.sh}"

# SSH mode settings.
AB_SSH_BIN="${AB_SSH_BIN:-ssh}"
AB_SCP_BIN="${AB_SCP_BIN:-scp}"
AB_SSH_TARGET="${AB_SSH_TARGET:-}"
AB_REMOTE_PROJECT_ROOT="${AB_REMOTE_PROJECT_ROOT:-}"
AB_REMOTE_SINGLE_RUN_SCRIPT="${AB_REMOTE_SINGLE_RUN_SCRIPT:-experiments/e2e-memory-ab.sh}"
AB_REMOTE_RESULTS_PARENT="${AB_REMOTE_RESULTS_PARENT:-${AB_REMOTE_PROJECT_ROOT}/experiments/k6/results/memory-ab-batch-${AB_RUN_LABEL}}"

# Optional env propagation to remote single-run script.
REMOTE_FORWARD_ENV_VARS=(
    E2E_VM_LIFECYCLE
    VM_IP
    E2E_VM_HOST
    E2E_VM_USER
    E2E_VM_HOME
    E2E_KUBECONFIG_PATH
    E2E_REMOTE_PROJECT_DIR
    VM_NAME
    CPUS
    MEMORY
    DISK
    NAMESPACE
    CONTROL_PLANE_NATIVE_BUILD
    CONTROL_PLANE_MODULES
    HOST_REBUILD_IMAGES
    LOADTEST_WORKLOADS
    LOADTEST_RUNTIMES
    INVOCATION_MODE
    K6_STAGE_SEQUENCE
    K6_PAYLOAD_MODE
    K6_PAYLOAD_POOL_SIZE
    SKIP_GRAFANA
    VERIFY_OUTPUT_PARITY
    SAMPLE_INTERVAL_SECONDS
    TAG_PREFIX
    KEEP_VM_AFTER
    RUN_BASELINE
    RUN_EPOCH_ON
)

print_usage() {
    cat <<EOF
Usage: ./experiments/e2e-memory-ab-batch.sh

Env vars:
  AB_MODE=local|ssh                        Runner backend (default: local)
  AB_RUNS=<N>                              Number of A/B runs (default: 3)
  AB_RUN_LABEL=<label>                     Suffix label for results dir
  BATCH_RESULTS_DIR=<dir>                  Local aggregate output directory
  AB_SINGLE_RUN_SCRIPT=<path>              Local single-run script

SSH mode:
  AB_SSH_TARGET=<user@host>                Required in ssh mode
  AB_REMOTE_PROJECT_ROOT=<path>            Required in ssh mode
  AB_REMOTE_SINGLE_RUN_SCRIPT=<path>       Remote script path (default: experiments/e2e-memory-ab.sh)
  AB_REMOTE_RESULTS_PARENT=<path>          Remote parent dir for run results
  AB_SSH_BIN=<bin>                         SSH binary override (default: ssh)
  AB_SCP_BIN=<bin>                         SCP binary override (default: scp)

Outputs:
  <BATCH_RESULTS_DIR>/runs/run-XXX/*
  <BATCH_RESULTS_DIR>/aggregate-comparison.json
  <BATCH_RESULTS_DIR>/aggregate-comparison.md
EOF
}

validate_config() {
    if [[ "${AB_MODE}" != "local" && "${AB_MODE}" != "ssh" ]]; then
        e2e_err "AB_MODE must be 'local' or 'ssh' (got '${AB_MODE}')."
        exit 2
    fi

    if ! [[ "${AB_RUNS}" =~ ^[1-9][0-9]*$ ]]; then
        e2e_err "AB_RUNS must be a positive integer (got '${AB_RUNS}')."
        exit 2
    fi

    if [[ "${AB_MODE}" == "local" ]]; then
        if [[ ! -x "${AB_SINGLE_RUN_SCRIPT}" && ! -f "${AB_SINGLE_RUN_SCRIPT}" ]]; then
            e2e_err "AB_SINGLE_RUN_SCRIPT not found: ${AB_SINGLE_RUN_SCRIPT}"
            exit 2
        fi
        return
    fi

    if [[ -z "${AB_SSH_TARGET}" || -z "${AB_REMOTE_PROJECT_ROOT}" ]]; then
        e2e_err "In ssh mode, AB_SSH_TARGET and AB_REMOTE_PROJECT_ROOT are required."
        exit 2
    fi
}

build_remote_env_prefix() {
    local run_index="$1"
    local remote_run_dir="$2"
    local prefix=""
    local kv=""
    local var=""
    local value=""

    kv="AB_RUN_INDEX=${run_index}"
    prefix+="$(printf '%q ' "${kv}")"
    kv="RESULTS_BASE_DIR=${remote_run_dir}"
    prefix+="$(printf '%q ' "${kv}")"

    for var in "${REMOTE_FORWARD_ENV_VARS[@]}"; do
        if [[ -n "${!var:-}" ]]; then
            value="${!var}"
            kv="${var}=${value}"
            prefix+="$(printf '%q ' "${kv}")"
        fi
    done

    printf '%s' "${prefix}"
}

run_single_local() {
    local run_index="$1"
    local run_id="$2"
    local local_run_dir="$3"
    local run_log="$4"

    mkdir -p "${local_run_dir}"
    (
        AB_RUN_INDEX="${run_index}" \
        RESULTS_BASE_DIR="${local_run_dir}" \
        bash "${AB_SINGLE_RUN_SCRIPT}"
    ) > "${run_log}" 2>&1
}

run_single_ssh() {
    local run_index="$1"
    local run_id="$2"
    local local_run_dir="$3"
    local run_log="$4"
    local remote_run_dir="${AB_REMOTE_RESULTS_PARENT}/runs/${run_id}"
    local remote_env_prefix=""
    local remote_cmd=""

    remote_env_prefix="$(build_remote_env_prefix "${run_index}" "${remote_run_dir}")"
    remote_cmd="cd $(printf '%q' "${AB_REMOTE_PROJECT_ROOT}") && ${remote_env_prefix}bash $(printf '%q' "${AB_REMOTE_SINGLE_RUN_SCRIPT}")"

    "${AB_SSH_BIN}" "${AB_SSH_TARGET}" "${remote_cmd}" > "${run_log}" 2>&1

    rm -rf "${local_run_dir}"
    mkdir -p "$(dirname "${local_run_dir}")"
    "${AB_SCP_BIN}" -r "${AB_SSH_TARGET}:${remote_run_dir}" "$(dirname "${local_run_dir}")" >> "${run_log}" 2>&1
}

verify_run_artifacts() {
    local run_id="$1"
    local local_run_dir="$2"
    local cmp_json="${local_run_dir}/comparison.json"
    local cmp_md="${local_run_dir}/comparison.md"

    if [[ ! -f "${cmp_json}" ]]; then
        e2e_err "Run ${run_id} missing comparison JSON: ${cmp_json}"
        exit 1
    fi
    if [[ ! -f "${cmp_md}" ]]; then
        e2e_err "Run ${run_id} missing comparison markdown: ${cmp_md}"
        exit 1
    fi
}

write_aggregate_reports() {
    local out_json="${BATCH_RESULTS_DIR}/aggregate-comparison.json"
    local out_md="${BATCH_RESULTS_DIR}/aggregate-comparison.md"
    python3 - "${out_json}" "${out_md}" "$@" <<'PYEOF'
import json
import statistics
import sys
from pathlib import Path

out_json = Path(sys.argv[1])
out_md = Path(sys.argv[2])
run_dirs = [Path(p) for p in sys.argv[3:]]

METRICS = [
    ("heap_used_avg_mb", "Heap used avg (MB)"),
    ("heap_used_peak_mb", "Heap used peak (MB)"),
    ("gc_pause_seconds_delta", "GC pause delta (s)"),
    ("p95_ms_weighted", "Latency p95 (ms)"),
    ("p99_ms_weighted", "Latency p99 (ms)"),
    ("fail_pct", "Fail rate (%)"),
]


def _extract_metric(comp: dict, key: str):
    if key in ("heap_used_avg_mb", "heap_used_peak_mb", "gc_pause_seconds_delta"):
        return (
            comp.get("baseline", {}).get("jvm", {}).get(key),
            comp.get("epoch_on", {}).get("jvm", {}).get(key),
            comp.get("deltas", {}).get(key),
        )
    return (
        comp.get("baseline", {}).get("loadtest", {}).get(key),
        comp.get("epoch_on", {}).get("loadtest", {}).get(key),
        comp.get("deltas", {}).get(key),
    )


def _reduce(vals, fn):
    clean = [float(v) for v in vals if v is not None]
    if not clean:
        return None
    return fn(clean)


def _fmt(v, digits=4):
    if v is None:
        return "n/a"
    return f"{v:.{digits}f}"


runs = []
for run_dir in run_dirs:
    comp_file = run_dir / "comparison.json"
    if not comp_file.is_file():
        continue
    comp = json.loads(comp_file.read_text(encoding="utf-8"))
    entry = {
        "run_id": run_dir.name,
        "path": str(run_dir),
        "metrics": {},
    }
    for key, _label in METRICS:
        b, e, d = _extract_metric(comp, key)
        entry["metrics"][key] = {"baseline": b, "epoch_on": e, "delta": d}
    runs.append(entry)

if not runs:
    raise SystemExit("No run comparison files found for aggregation.")

aggregate = {
    "run_count": len(runs),
    "metrics": {},
    "runs": runs,
}

for key, _label in METRICS:
    b_vals = [r["metrics"][key]["baseline"] for r in runs]
    e_vals = [r["metrics"][key]["epoch_on"] for r in runs]
    d_vals = [r["metrics"][key]["delta"] for r in runs]
    aggregate["metrics"][key] = {
        "baseline": {
            "mean": _reduce(b_vals, statistics.fmean),
            "median": _reduce(b_vals, statistics.median),
        },
        "epoch_on": {
            "mean": _reduce(e_vals, statistics.fmean),
            "median": _reduce(e_vals, statistics.median),
        },
        "delta": {
            "mean": _reduce(d_vals, statistics.fmean),
            "median": _reduce(d_vals, statistics.median),
            "min": _reduce(d_vals, min),
            "max": _reduce(d_vals, max),
        },
    }

out_json.write_text(json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8")

lines = []
lines.append("# Memory A/B Batch Aggregate")
lines.append("")
lines.append(f"Runs: **{aggregate['run_count']}**")
lines.append("")
lines.append("## Median Comparison")
lines.append("")
lines.append("| Metric | Baseline median | Epoch ON median | Delta median (ON-Base) |")
lines.append("|---|---:|---:|---:|")
for key, label in METRICS:
    m = aggregate["metrics"][key]
    lines.append(
        f"| {label} | {_fmt(m['baseline']['median'])} | {_fmt(m['epoch_on']['median'])} | {_fmt(m['delta']['median'])} |"
    )

lines.append("")
lines.append("## Delta Variability")
lines.append("")
lines.append("| Metric | Delta mean | Delta median | Delta min | Delta max |")
lines.append("|---|---:|---:|---:|---:|")
for key, label in METRICS:
    d = aggregate["metrics"][key]["delta"]
    lines.append(
        f"| {label} | {_fmt(d['mean'])} | {_fmt(d['median'])} | {_fmt(d['min'])} | {_fmt(d['max'])} |"
    )

lines.append("")
lines.append("## Runs")
lines.append("")
for run in runs:
    lines.append(f"- `{run['run_id']}`: `{run['path']}`")

out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")
PYEOF
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        print_usage
        exit 0
    fi

    validate_config
    mkdir -p "${BATCH_RESULTS_DIR}/runs"

    e2e_log "Mode=${AB_MODE} runs=${AB_RUNS}"
    e2e_log "Batch results directory: ${BATCH_RESULTS_DIR}"

    local run_dirs=()
    local i=0
    while [[ ${i} -lt ${AB_RUNS} ]]; do
        i=$((i + 1))
        local run_id
        run_id="$(printf 'run-%03d' "${i}")"
        local local_run_dir="${BATCH_RESULTS_DIR}/runs/${run_id}"
        local run_log="${BATCH_RESULTS_DIR}/runs/${run_id}.runner.log"

        e2e_log "Starting ${run_id}..."
        if [[ "${AB_MODE}" == "local" ]]; then
            run_single_local "${i}" "${run_id}" "${local_run_dir}" "${run_log}"
        else
            run_single_ssh "${i}" "${run_id}" "${local_run_dir}" "${run_log}"
        fi
        verify_run_artifacts "${run_id}" "${local_run_dir}"
        run_dirs+=("${local_run_dir}")
        e2e_log "Completed ${run_id}"
    done

    write_aggregate_reports "${run_dirs[@]}"
    e2e_log "Aggregate reports ready:"
    e2e_log "  ${BATCH_RESULTS_DIR}/aggregate-comparison.md"
    e2e_log "  ${BATCH_RESULTS_DIR}/aggregate-comparison.json"
}

main "$@"
