from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "native-build.sh"


def test_native_build_uses_control_plane_wrapper() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert ":control-plane:nativeCompile" in script
    assert ":services:java:warm-echo:nativeCompile" in script
    assert "services/java/warm-echo/build/native/nativeCompile/warm-echo" in script
    assert ":function-runtime:nativeCompile" not in script
