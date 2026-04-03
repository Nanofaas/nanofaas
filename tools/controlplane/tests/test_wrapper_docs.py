from pathlib import Path


def test_control_plane_build_wrapper_uses_tools_controlplane_project() -> None:
    script = Path("scripts/control-plane-build.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked" in script
    assert "controlplane-tool" in script


def test_pipeline_wrapper_uses_locked_uv_run() -> None:
    script = Path("scripts/controlplane-tool.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked" in script
