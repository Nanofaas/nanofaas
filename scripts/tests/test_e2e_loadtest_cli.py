import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "e2e-loadtest.sh"


def test_loadtest_help_mentions_mode_and_selection_env_vars():
    proc = subprocess.run(
        ["bash", str(SCRIPT), "--help"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0
    assert "INVOCATION_MODE" in proc.stdout
    assert "LOADTEST_WORKLOADS" in proc.stdout
    assert "LOADTEST_RUNTIMES" in proc.stdout
    assert "K6_STAGE_SEQUENCE" in proc.stdout
    assert "RESULTS_DIR_OVERRIDE" in proc.stdout
