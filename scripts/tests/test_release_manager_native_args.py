from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "release-manager" / "release.py"


def test_release_manager_uses_native_build_args_for_bootbuildimage_tasks():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "resolve_native_image_build_args" in script
    assert "NATIVE_IMAGE_XMX" in script
    assert "NATIVE_ACTIVE_PROCESSORS" in script
    assert "-J-Xmx" in script
    assert "-J-XX:ActiveProcessorCount=" in script
    assert "NATIVE_IMAGE_BUILD_ARGS=" in script
