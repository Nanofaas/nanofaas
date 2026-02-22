import json
import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "experiments" / "e2e-memory-ab-batch.sh"


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def _write_fake_single_run_script(path: Path) -> None:
    _write_executable(
        path,
        """#!/usr/bin/env bash
set -euo pipefail
idx="${AB_RUN_INDEX:?missing AB_RUN_INDEX}"
out="${RESULTS_BASE_DIR:?missing RESULTS_BASE_DIR}"
mkdir -p "${out}"
python3 - "${idx}" "${out}/comparison.json" <<'PYEOF'
import json
import sys

idx = int(sys.argv[1])
out = sys.argv[2]

delta_heap_avg = idx * 4 - 3       # 1,5,9,...
delta_heap_peak = -(idx * 10)      # -10,-20,-30,...
delta_gc = -0.1 * idx              # -0.1,-0.2,-0.3,...
delta_p95 = 10.0 * idx             # 10,20,30,...
delta_p99 = 20.0 * idx             # 20,40,60,...
delta_fail = float(idx)            # 1,2,3,...

baseline = {
    "jvm": {
        "heap_used_avg_mb": 100.0 + idx,
        "heap_used_peak_mb": 200.0 + idx,
        "gc_pause_seconds_delta": 10.0 + idx,
    },
    "loadtest": {
        "p95_ms_weighted": 300.0 + idx,
        "p99_ms_weighted": 400.0 + idx,
        "fail_pct": 1.0 + idx,
    },
}
epoch = {
    "jvm": {
        "heap_used_avg_mb": baseline["jvm"]["heap_used_avg_mb"] + delta_heap_avg,
        "heap_used_peak_mb": baseline["jvm"]["heap_used_peak_mb"] + delta_heap_peak,
        "gc_pause_seconds_delta": baseline["jvm"]["gc_pause_seconds_delta"] + delta_gc,
    },
    "loadtest": {
        "p95_ms_weighted": baseline["loadtest"]["p95_ms_weighted"] + delta_p95,
        "p99_ms_weighted": baseline["loadtest"]["p99_ms_weighted"] + delta_p99,
        "fail_pct": baseline["loadtest"]["fail_pct"] + delta_fail,
    },
}

payload = {
    "baseline": baseline,
    "epoch_on": epoch,
    "deltas": {
        "heap_used_avg_mb": delta_heap_avg,
        "heap_used_peak_mb": delta_heap_peak,
        "gc_pause_seconds_delta": delta_gc,
        "p95_ms_weighted": delta_p95,
        "p99_ms_weighted": delta_p99,
        "fail_pct": delta_fail,
    },
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(payload, f)
PYEOF
echo "# run ${idx}" > "${out}/comparison.md"
""",
    )


def test_memory_ab_batch_help_mentions_runs_and_ssh():
    proc = subprocess.run(
        ["bash", str(SCRIPT), "--help"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0
    assert "AB_RUNS" in proc.stdout
    assert "AB_MODE=local|ssh" in proc.stdout
    assert "AB_SSH_TARGET" in proc.stdout
    assert "aggregate-comparison.md" in proc.stdout


def test_memory_ab_batch_local_mode_aggregates_median_and_mean(tmp_path: Path):
    fake_single = tmp_path / "fake-single-run.sh"
    _write_fake_single_run_script(fake_single)

    out_dir = tmp_path / "batch-local"
    env = os.environ.copy()
    env["AB_MODE"] = "local"
    env["AB_RUNS"] = "3"
    env["AB_SINGLE_RUN_SCRIPT"] = str(fake_single)
    env["BATCH_RESULTS_DIR"] = str(out_dir)

    proc = subprocess.run(
        ["bash", str(SCRIPT)],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
        env=env,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr

    agg_json = out_dir / "aggregate-comparison.json"
    agg_md = out_dir / "aggregate-comparison.md"
    assert agg_json.exists()
    assert agg_md.exists()

    data = json.loads(agg_json.read_text(encoding="utf-8"))
    assert data["run_count"] == 3
    assert data["metrics"]["heap_used_avg_mb"]["delta"]["median"] == 5.0
    assert data["metrics"]["heap_used_avg_mb"]["delta"]["mean"] == 5.0
    assert data["metrics"]["p95_ms_weighted"]["delta"]["median"] == 20.0
    assert data["metrics"]["p99_ms_weighted"]["delta"]["median"] == 40.0
    assert data["metrics"]["fail_pct"]["delta"]["median"] == 2.0

    md = agg_md.read_text(encoding="utf-8")
    assert "Median Comparison" in md
    assert "run-001" in md
    assert "run-003" in md


def test_memory_ab_batch_ssh_mode_via_fake_ssh_and_scp(tmp_path: Path):
    remote_root = tmp_path / "remote-root"
    remote_root.mkdir(parents=True, exist_ok=True)
    remote_script = remote_root / "fake-remote-single-run.sh"
    _write_fake_single_run_script(remote_script)

    fake_bin = tmp_path / "bin"
    fake_bin.mkdir(parents=True, exist_ok=True)
    _write_executable(
        fake_bin / "ssh",
        """#!/usr/bin/env bash
set -euo pipefail
target="$1"
shift
cmd="$*"
bash -lc "${cmd}"
""",
    )
    _write_executable(
        fake_bin / "scp",
        """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-r" ]]; then
  shift
fi
src="${1:?missing src}"
dst="${2:?missing dst}"
src_path="${src#*:}"
cp -R "${src_path}" "${dst}"
""",
    )

    out_dir = tmp_path / "batch-ssh-local"
    remote_parent = remote_root / "remote-results"

    env = os.environ.copy()
    env["PATH"] = f"{fake_bin}:{env.get('PATH', '')}"
    env["AB_MODE"] = "ssh"
    env["AB_RUNS"] = "2"
    env["AB_SSH_TARGET"] = "dummy@host"
    env["AB_REMOTE_PROJECT_ROOT"] = str(remote_root)
    env["AB_REMOTE_SINGLE_RUN_SCRIPT"] = str(remote_script)
    env["AB_REMOTE_RESULTS_PARENT"] = str(remote_parent)
    env["AB_SSH_BIN"] = str(fake_bin / "ssh")
    env["AB_SCP_BIN"] = str(fake_bin / "scp")
    env["BATCH_RESULTS_DIR"] = str(out_dir)

    proc = subprocess.run(
        ["bash", str(SCRIPT)],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
        env=env,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr

    agg_json = out_dir / "aggregate-comparison.json"
    assert agg_json.exists()
    data = json.loads(agg_json.read_text(encoding="utf-8"))
    assert data["run_count"] == 2
    assert (out_dir / "runs" / "run-001" / "comparison.json").exists()
    assert (out_dir / "runs" / "run-002" / "comparison.json").exists()
