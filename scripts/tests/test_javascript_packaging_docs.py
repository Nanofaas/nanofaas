from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_root_docs_point_to_current_packaging_flow() -> None:
    root = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    testing = (REPO_ROOT / "docs" / "testing.md").read_text(encoding="utf-8")
    quickstart = (REPO_ROOT / "docs" / "quickstart.md").read_text(encoding="utf-8")
    control_plane = (REPO_ROOT / "docs" / "control-plane.md").read_text(encoding="utf-8")
    removed_release_script = "scripts/" + "release" + "-manager/" + "release" + ".py"
    assert "sdks/javascript/" in root
    assert "npm pack --dry-run" in testing
    assert removed_release_script not in root
    assert removed_release_script not in testing
    assert "./scripts/controlplane.sh images --tag TAG --arch all --flavor all --push" in root
    assert "./scripts/controlplane.sh images --tag v1.2.3 --arch all --flavor all --push" in quickstart
    assert "publish-images — build & publish image matrix" in quickstart
    assert "v1.2.3-amd64-jvm" in control_plane
    assert "v1.2.3-arm64-native" in control_plane
    assert "v1.2.3-arm64" in control_plane
    assert "--arch-suffix" not in quickstart
    assert "--arch-suffix" not in control_plane
