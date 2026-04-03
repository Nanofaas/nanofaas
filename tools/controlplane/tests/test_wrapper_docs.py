from pathlib import Path


def test_control_plane_build_wrapper_uses_tools_controlplane_project() -> None:
    script = Path("scripts/control-plane-build.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane" in script
