from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "native-build.sh"


def test_native_build_uses_control_plane_wrapper() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert "./scripts/controlplane.sh native --profile all" in script
    assert "./gradlew :control-plane:nativeCompile" not in script
    assert "./gradlew :function-runtime:nativeCompile" in script
