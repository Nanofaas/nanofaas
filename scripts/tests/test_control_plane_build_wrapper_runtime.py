from pathlib import Path


SCRIPT = Path("scripts/control-plane-build.sh")


def test_control_plane_build_wrapper_fails_fast_when_uv_is_missing() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert "command -v uv" in script
    assert "uv not found" in script.lower()
