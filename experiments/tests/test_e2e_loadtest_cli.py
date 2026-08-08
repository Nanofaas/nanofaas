from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
LEGACY_SCRIPT = REPO_ROOT / "experiments" / "e2e-loadtest.sh"


def test_legacy_loadtest_experiment_script_is_deleted():
    assert not LEGACY_SCRIPT.exists()
