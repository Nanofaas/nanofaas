from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_root_docs_point_to_current_packaging_flow() -> None:
    root = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    testing = (REPO_ROOT / "docs" / "testing.md").read_text(encoding="utf-8")
    removed_release_script = "scripts/" + "release" + "-manager/" + "release" + ".py"
    assert "sdks/javascript/" in root
    assert "npm pack --dry-run" in testing
    assert removed_release_script not in root
    assert removed_release_script not in testing
    assert "./scripts/controlplane.sh images --tag TAG --arch all --flavor all --push" in root
