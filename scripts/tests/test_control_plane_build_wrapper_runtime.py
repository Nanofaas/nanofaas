from pathlib import Path


def test_controlplane_wrapper_fails_fast_when_uv_is_missing() -> None:
    script = Path("scripts/controlplane.sh").read_text(encoding="utf-8")
    assert "command -v uv" in script
    assert "uv not found" in script.lower()


