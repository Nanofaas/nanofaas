import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"
SCRIPT = SCRIPTS_DIR / "e2e-loadtest.sh"


def run_script(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", str(SCRIPT), *args],
        text=True,
        capture_output=True,
        check=check,
        env=os.environ.copy(),
    )


# M12: loadtest wrapper now routes to controlplane.sh loadtest run (not experiments script)
def test_e2e_loadtest_wrapper_dry_run_routes_to_python_runner() -> None:
    proc = run_script("--profile", "demo-java", "--dry-run")
    output = f"{proc.stdout}\n{proc.stderr}"

    assert "experiments/e2e-loadtest.sh" not in output
    assert "controlplane.sh loadtest run" in output


def test_e2e_loadtest_wrapper_rejects_registry_only_summary_flag() -> None:
    proc = run_script("--summary-only", check=False)

    assert proc.returncode == 2
    assert "summary-only" in proc.stderr or proc.returncode == 2


# M12: experiment loadtest scripts deleted; Python adapters own the workflow.
def test_e2e_loadtest_experiment_script_is_deleted() -> None:
    assert not (REPO_ROOT / "experiments" / "e2e-loadtest.sh").exists(), (
        "experiments/e2e-loadtest.sh still exists — delete it after Python path is green (M12)"
    )


def test_e2e_loadtest_registry_experiment_script_is_deleted() -> None:
    assert not (REPO_ROOT / "experiments" / "e2e-loadtest-registry.sh").exists(), (
        "experiments/e2e-loadtest-registry.sh still exists — delete it after Python path is green (M12)"
    )
