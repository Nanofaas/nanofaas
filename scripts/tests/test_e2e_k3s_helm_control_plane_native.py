from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "e2e-k3s-helm.sh"


def test_k3s_helm_script_supports_native_control_plane_build_knobs():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "CONTROL_PLANE_NATIVE_BUILD" in script
    assert "CONTROL_PLANE_MODULES" in script
    assert ":control-plane:bootBuildImage" in script
