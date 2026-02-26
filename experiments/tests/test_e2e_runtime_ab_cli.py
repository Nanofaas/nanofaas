import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "experiments" / "e2e-runtime-ab.sh"


def test_runtime_ab_help_reports_runtime_knobs():
    proc = subprocess.run(
        ["bash", str(SCRIPT), "--help"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0
    assert "baseline" in proc.stdout.lower()
    assert "candidate" in proc.stdout.lower()


def test_runtime_ab_script_propagates_runtime_to_deploy_and_loadtest():
    content = SCRIPT.read_text(encoding="utf-8")
    assert "BASELINE_RUNTIME" in content
    assert "CANDIDATE_RUNTIME" in content
    assert "CONTROL_PLANE_RUNTIME=\"${runtime}\"" in content
    assert "bash \"${PROJECT_ROOT}/scripts/e2e-k3s-helm.sh\"" in content
    assert "bash \"${PROJECT_ROOT}/experiments/e2e-loadtest.sh\"" in content
    assert "comparison.md" in content
    assert "comparison.json" in content


def test_runtime_ab_summary_uses_shared_k6_fail_count_parser():
    content = SCRIPT.read_text(encoding="utf-8")
    assert "from k6_summary import resolve_http_req_failed_count" in content
    assert "fails = resolve_http_req_failed_count(failed, reqs)" in content


def test_runtime_ab_report_renders_single_side_metrics_when_one_case_missing():
    content = SCRIPT.read_text(encoding="utf-8")
    assert "if baseline or candidate:" in content
    assert "Delta values are `n/a` when only one side (baseline or candidate) is available." in content


def test_runtime_ab_summary_collects_per_function_data():
    content = SCRIPT.read_text(encoding="utf-8")
    assert 'window_file = case_dir / "loadtest-window.json"' in content
    assert "prom_query_range(" in content
    assert "container_cpu_usage_seconds_total" in content
    assert "container_memory_working_set_bytes" in content
    assert '"by_function": loadtest_by_function' in content
    assert '"function_resources": function_resources' in content


def test_runtime_ab_report_contains_per_function_tables():
    content = SCRIPT.read_text(encoding="utf-8")
    assert "## Per-function Loadtest Metrics" in content
    assert "## Per-function CPU/RAM" in content
    assert "all_functions = sorted(" in content
    assert "Prometheus/cAdvisor query_range" in content
