from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "build-push-images.sh"


def test_build_push_images_supports_javascript_demo_target_group() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert "javascript-demos" in script
    assert "examples/javascript/${example}/Dockerfile" in script
    assert '${BASE}/javascript-${example}:${TAG}${TAG_SUFFIX}' in script
