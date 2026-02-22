from pathlib import Path

import pytest
import yaml

from experiments.staging.scaffold import create_version


def _write_version(root: Path, slug: str, status: str) -> Path:
    version_dir = root / "versions" / slug
    snapshot = version_dir / "snapshot"
    snapshot.mkdir(parents=True, exist_ok=True)
    (snapshot / "marker.txt").write_text(f"{slug}-snapshot", encoding="utf-8")
    (version_dir / "version.yaml").write_text(
        yaml.safe_dump(
            {
                "slug": slug,
                "kind": "generic-service",
                "status": status,
                "parent": "none",
                "created_at": "2026-02-22T18:00:00Z",
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    return version_dir


def test_create_version_from_baseline_copies_snapshot_and_writes_metadata(tmp_path: Path):
    root = tmp_path / "control-plane-staging"
    _write_version(root, "baseline-main", "baseline")

    created = create_version(root=root, slug="candidate-a", source="baseline")

    assert (created / "snapshot" / "marker.txt").read_text(encoding="utf-8") == "baseline-main-snapshot"
    metadata = yaml.safe_load((created / "version.yaml").read_text(encoding="utf-8"))
    assert metadata["slug"] == "candidate-a"
    assert metadata["kind"] == "generic-service"
    assert metadata["status"] == "staging"
    assert metadata["parent"] == "baseline"
    assert (created / "hypothesis.md").exists()


def test_create_version_from_explicit_version_slug(tmp_path: Path):
    root = tmp_path / "control-plane-staging"
    _write_version(root, "opt-v1", "candidate")

    created = create_version(root=root, slug="opt-v2", source="version:opt-v1")

    assert (created / "snapshot" / "marker.txt").read_text(encoding="utf-8") == "opt-v1-snapshot"
    metadata = yaml.safe_load((created / "version.yaml").read_text(encoding="utf-8"))
    assert metadata["parent"] == "version:opt-v1"


def test_create_version_from_none_creates_empty_snapshot_scaffold(tmp_path: Path):
    root = tmp_path / "control-plane-staging"

    created = create_version(root=root, slug="rust-proto", source="none")

    assert (created / "snapshot").is_dir()
    assert list((created / "snapshot").iterdir()) == []
    hypothesis = (created / "hypothesis.md").read_text(encoding="utf-8")
    assert "## Context" in hypothesis
    assert "## Differences from parent" in hypothesis
    assert "## Hypotheses" in hypothesis
    assert "## Risks" in hypothesis
    assert "## Expected impact" in hypothesis


def test_create_version_rejects_unknown_source_mode(tmp_path: Path):
    root = tmp_path / "control-plane-staging"
    with pytest.raises(ValueError, match="Unsupported source"):
        create_version(root=root, slug="bad", source="foo")
