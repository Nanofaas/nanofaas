from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
LEGACY_SCRIPT = REPO_ROOT / "experiments" / "e2e-loadtest-registry.sh"
INTERACTIVE_ENTRYPOINT = REPO_ROOT / "experiments" / "e2e-loadtest-registry-interactive.py"


def test_legacy_loadtest_registry_script_is_deleted():
    assert not LEGACY_SCRIPT.exists()


def test_registry_interactive_entrypoint_remains_python_based():
    content = INTERACTIVE_ENTRYPOINT.read_text(encoding="utf-8")
    assert "questionary" in content
    assert "subprocess" in content
