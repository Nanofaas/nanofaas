import json
import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "e2e-loadtest-registry.sh"


def _write_k6_summary(
    path: Path,
    reqs: int,
    avg: float,
    med: float,
    p90: float,
    p95: float,
    max_v: float,
    rps: float,
    payload_avg: float = 0.0,
    payload_q1: float = 0.0,
    payload_q2: float = 0.0,
    payload_q3: float = 0.0,
):
    payload = {
        "metrics": {
            "http_reqs": {"count": reqs},
            "http_req_failed": {"passes": 0},
            "http_req_duration": {"avg": avg, "med": med, "p(90)": p90, "p(95)": p95, "max": max_v},
            "iterations": {"count": reqs, "rate": rps},
            "payload_size_bytes": {
                "avg": payload_avg,
                "p(25)": payload_q1,
                "med": payload_q2,
                "p(75)": payload_q3,
            },
        }
    }
    path.write_text(json.dumps(payload), encoding="utf-8")


def test_registry_help_mentions_summary_only():
    proc = subprocess.run(
        ["bash", str(SCRIPT), "--help"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0
    assert "--summary-only" in proc.stdout
    assert "--no-refresh-summary-metrics" in proc.stdout
    assert "--interactive" in proc.stdout
    assert "TAG_SUFFIX" in proc.stdout
    assert "K6_PAYLOAD_MODE" in proc.stdout
    assert "K6_PAYLOAD_POOL_SIZE" in proc.stdout


def test_registry_summary_only_uses_local_results_without_vm(tmp_path: Path):
    results_dir = tmp_path / "results"
    results_dir.mkdir(parents=True, exist_ok=True)

    _write_k6_summary(
        results_dir / "word-stats-java.json",
        1000,
        3.2,
        3.0,
        4.2,
        5.1,
        11.0,
        120.0,
        payload_avg=580.0,
        payload_q1=510.0,
        payload_q2=560.0,
        payload_q3=640.0,
    )
    _write_k6_summary(
        results_dir / "json-transform-java.json",
        900,
        4.1,
        3.6,
        5.5,
        6.2,
        12.0,
        115.0,
        payload_avg=1120.0,
        payload_q1=980.0,
        payload_q2=1100.0,
        payload_q3=1240.0,
    )
    prom_payload = {
        "word-stats-java": {"latency_p50": 0.001, "latency_p95": 0.002, "latency_count": 10, "latency_sum": 0.01},
        "json-transform-java": {"latency_p50": 0.002, "latency_p95": 0.003, "latency_count": 10, "latency_sum": 0.02},
        "word-stats-python": {"latency_p50": 0.001, "latency_p95": 0.002, "latency_count": 10, "latency_sum": 0.01},
    }
    (results_dir / "prometheus-dump.json").write_text(json.dumps(prom_payload), encoding="utf-8")
    (results_dir / "k8s-resources.json").write_text("{}", encoding="utf-8")

    env = os.environ.copy()
    env["RESULTS_DIR_OVERRIDE"] = str(results_dir)
    env["REFRESH_SUMMARY_METRICS"] = "false"
    env["KEEP_VM"] = "true"

    proc = subprocess.run(
        ["bash", str(SCRIPT), "--summary-only"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
        env=env,
    )

    assert proc.returncode == 0
    assert "SECTION 1: CLIENT-SIDE LATENCY (k6)" in proc.stdout
    assert "SECTION 8: CONTROL-PLANE RESOURCE PROFILE" in proc.stdout
    assert "SECTION 9: PAYLOAD PROFILE (k6 INPUT MIX)" in proc.stdout
    assert "Payload bytes" in proc.stdout
    assert "Q1(B)" in proc.stdout
    assert "Q3(B)" in proc.stdout
    assert "word-stats-python" not in proc.stdout


def test_registry_build_run_results_dir_is_unique_and_mode_scoped(tmp_path: Path):
    results_root = tmp_path / "results-root"
    cmd = (
        f"source {SCRIPT}; "
        f"RESULTS_DIR_OVERRIDE='{results_root}'; "
        "INVOCATION_MODE='sync'; LOADTEST_RUN_ID_OVERRIDE='run-a'; build_run_results_dir; "
        "INVOCATION_MODE='async'; LOADTEST_RUN_ID_OVERRIDE='run-b'; build_run_results_dir"
    )
    proc = subprocess.run(
        ["bash", "-lc", cmd],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr
    lines = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    assert len(lines) == 2
    assert lines[0].endswith("/run-run-a-sync")
    assert lines[1].endswith("/run-run-b-async")
    assert lines[0] != lines[1]
