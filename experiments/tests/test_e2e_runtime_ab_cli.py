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


def test_runtime_ab_summary_uses_passes_for_http_req_failed_rate_metric():
    content = SCRIPT.read_text(encoding="utf-8")
    assert 'if "passes" in failed' in content
    assert 'fails = int(failed.get("passes", 0))' in content
