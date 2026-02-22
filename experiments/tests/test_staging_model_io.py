from pathlib import Path

import pytest
import yaml

from experiments.staging.io import load_version_metadata, save_version_metadata
from experiments.staging.model import VersionMetadata


def _write_yaml(path: Path, payload: dict) -> None:
    path.write_text(yaml.safe_dump(payload, sort_keys=True), encoding="utf-8")


def test_load_and_save_version_metadata_roundtrip(tmp_path: Path):
    metadata = VersionMetadata(
        slug="opt-memory-v1",
        kind="generic-service",
        status="staging",
        parent="baseline",
        created_at="2026-02-22T18:00:00Z",
        source_commit="abc123",
        notes="memory tuning",
    )

    target = tmp_path / "version.yaml"
    save_version_metadata(target, metadata)
    loaded = load_version_metadata(target)

    assert loaded == metadata


def test_load_version_metadata_rejects_missing_required_field(tmp_path: Path):
    target = tmp_path / "version.yaml"
    _write_yaml(
        target,
        {
            "slug": "missing-status",
            "kind": "generic-service",
            "parent": "baseline",
            "created_at": "2026-02-22T18:00:00Z",
        },
    )

    with pytest.raises(ValueError, match="Missing required field: status"):
        load_version_metadata(target)


def test_load_version_metadata_rejects_unknown_status(tmp_path: Path):
    target = tmp_path / "version.yaml"
    _write_yaml(
        target,
        {
            "slug": "bad-status",
            "kind": "generic-service",
            "status": "unknown",
            "parent": "baseline",
            "created_at": "2026-02-22T18:00:00Z",
        },
    )

    with pytest.raises(ValueError, match="Unsupported status: unknown"):
        load_version_metadata(target)
