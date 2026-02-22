from __future__ import annotations

from pathlib import Path

import yaml

from .model import VersionMetadata


def load_version_metadata(path: Path) -> VersionMetadata:
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(payload, dict):
        raise ValueError("version metadata must be a mapping")
    return VersionMetadata.from_dict(payload)


def save_version_metadata(path: Path, metadata: VersionMetadata) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    serialized = yaml.safe_dump(metadata.to_dict(), sort_keys=False)
    path.write_text(serialized, encoding="utf-8")

