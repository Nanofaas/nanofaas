import subprocess
import os
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
    assert "K6_PAYLOAD_MODE" in proc.stdout
    assert "K6_PAYLOAD_POOL_SIZE" in proc.stdout


def test_loadtest_selection_works_on_bash3_without_unbound_array_error():
    env = os.environ.copy()
    env["LOADTEST_WORKLOADS"] = "word-stats"
    env["LOADTEST_RUNTIMES"] = "java"
    env["K6_STAGE_SEQUENCE"] = "bad-stage"

    proc = subprocess.run(
        ["bash", str(SCRIPT)],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
        env=env,
    )
    combined = proc.stdout + proc.stderr
    assert proc.returncode == 2
    assert "Invalid stage 'bad-stage'" in combined
    assert "unbound variable" not in combined
