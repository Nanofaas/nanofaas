from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "release-manager" / "release.py"


def test_release_manager_updates_and_packages_javascript_sdk() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert "function-sdk-javascript/package.json" in script
    assert "npm install --package-lock-only" in script
    assert "npm pack --dry-run" in script
    assert "npm publish --access public" in script
    assert "examples/javascript/{example}/Dockerfile" in script
    assert "javascript-{example}:{tag}-arm64" in script
