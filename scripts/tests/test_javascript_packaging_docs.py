from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_javascript_sdk_readme_mentions_install_and_pack() -> None:
    readme = (REPO_ROOT / "function-sdk-javascript" / "README.md").read_text(encoding="utf-8")
    assert "npm install nanofaas-function-sdk" in readme
    assert "npm pack --dry-run" in readme


def test_root_docs_point_to_packaging_release_flow() -> None:
    root = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    testing = (REPO_ROOT / "docs" / "testing.md").read_text(encoding="utf-8")
    assert "function-sdk-javascript" in root
    assert "npm pack --dry-run" in testing
    assert "scripts/release-manager/release.py" in root or "scripts/release-manager/release.py" in testing
