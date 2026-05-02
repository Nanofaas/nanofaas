import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
LEGACY_SCRIPT = REPO_ROOT / "experiments" / "e2e-loadtest.sh"
WRAPPER_SCRIPT = REPO_ROOT / "scripts" / "e2e-loadtest.sh"


def test_legacy_loadtest_experiment_script_is_deleted():
    assert not LEGACY_SCRIPT.exists()


def test_loadtest_wrapper_dry_run_routes_to_controlplane():
    proc = subprocess.run(
        ["bash", str(WRAPPER_SCRIPT), "--profile", "demo-java", "--dry-run"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    output = proc.stdout + proc.stderr
    assert proc.returncode == 0
    assert "controlplane.sh loadtest run" in output
    assert "experiments/e2e-loadtest.sh" not in output


def test_loadtest_wrapper_rejects_removed_registry_summary_flags():
    proc = subprocess.run(
        ["bash", str(WRAPPER_SCRIPT), "--summary-only"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 2
    assert "summary-only" in proc.stderr


def test_loadtest_wrapper_rejects_unknown_arguments():
    proc = subprocess.run(
        ["bash", str(WRAPPER_SCRIPT), "--unknown-option"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 2
    assert "Unknown argument" in proc.stderr
