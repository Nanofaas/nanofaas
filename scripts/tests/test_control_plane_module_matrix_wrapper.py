from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "test-control-plane-module-combinations.sh"


def test_module_combination_script_delegates_to_matrix_command() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert "scripts/control-plane-build.sh matrix" in script
    assert ":control-plane:printSelectedControlPlaneModules" not in script
