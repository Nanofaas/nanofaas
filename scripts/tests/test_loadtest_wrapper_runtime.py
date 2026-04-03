import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "e2e-loadtest.sh"


def run_script(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", str(SCRIPT), *args],
        text=True,
        capture_output=True,
        check=check,
        env=os.environ.copy(),
    )


def test_e2e_loadtest_wrapper_dry_run_routes_to_legacy_loadtest_backend() -> None:
    proc = run_script("--profile", "demo-java", "--dry-run")
    output = f"{proc.stdout}\n{proc.stderr}"

    assert "experiments/e2e-loadtest.sh" in output
    assert "controlplane.sh loadtest run" not in output


def test_e2e_loadtest_wrapper_rejects_registry_only_summary_flag() -> None:
    proc = run_script("--summary-only", check=False)

    assert proc.returncode == 2
    assert "e2e-loadtest-registry" in proc.stderr
