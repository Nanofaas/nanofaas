import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_staging_manager_help_lists_commands():
    proc = subprocess.run(
        ["python3", "experiments/staging_manager.py", "--help"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0
    assert "create-version" in proc.stdout
    assert "build-images" in proc.stdout
    assert "run-campaign" in proc.stdout
    assert "promote" in proc.stdout
