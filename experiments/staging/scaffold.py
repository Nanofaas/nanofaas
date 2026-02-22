from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import shutil

from .io import load_version_metadata, save_version_metadata
from .model import VersionMetadata


def create_version(root: Path, slug: str, source: str) -> Path:
    versions_dir = root / "versions"
    versions_dir.mkdir(parents=True, exist_ok=True)

    version_dir = versions_dir / slug
    if version_dir.exists():
        raise ValueError(f"Version already exists: {slug}")

    source_snapshot = _resolve_source_snapshot(versions_dir, source)
    snapshot_dir = version_dir / "snapshot"
    version_dir.mkdir(parents=True, exist_ok=False)

    if source_snapshot is None:
        snapshot_dir.mkdir(parents=True, exist_ok=False)
    else:
        shutil.copytree(source_snapshot, snapshot_dir)

    hypothesis_path = version_dir / "hypothesis.md"
    hypothesis_path.write_text(_hypothesis_template(source), encoding="utf-8")

    metadata = VersionMetadata(
        slug=slug,
        kind="generic-service",
        status="staging",
        parent=source,
        created_at=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    )
    save_version_metadata(version_dir / "version.yaml", metadata)

    images_dir = version_dir / "images"
    images_dir.mkdir(parents=True, exist_ok=True)
    return version_dir


def _resolve_source_snapshot(versions_dir: Path, source: str) -> Path | None:
    if source == "none":
        return None
    if source == "baseline":
        baseline_slug = _find_baseline_slug(versions_dir)
        return versions_dir / baseline_slug / "snapshot"
    if source.startswith("version:"):
        source_slug = source.split(":", 1)[1]
        source_snapshot = versions_dir / source_slug / "snapshot"
        if not source_snapshot.is_dir():
            raise ValueError(f"Source version not found: {source_slug}")
        return source_snapshot
    raise ValueError(f"Unsupported source: {source}")


def _find_baseline_slug(versions_dir: Path) -> str:
    baseline_slugs: list[str] = []
    for version_file in versions_dir.glob("*/version.yaml"):
        metadata = load_version_metadata(version_file)
        if metadata.status == "baseline":
            baseline_slugs.append(metadata.slug)
    if not baseline_slugs:
        raise ValueError("No baseline version found")
    if len(baseline_slugs) > 1:
        joined = ", ".join(sorted(baseline_slugs))
        raise ValueError(f"Multiple baseline versions found: {joined}")
    return baseline_slugs[0]


def _hypothesis_template(parent: str) -> str:
    return (
        "# Hypothesis\n\n"
        "## Context\n\n"
        "Describe why this version exists and what scenario it targets.\n\n"
        "## Differences from parent\n\n"
        f"- Parent source: `{parent}`\n"
        "- List concrete implementation differences.\n\n"
        "## Hypotheses\n\n"
        "- Hypothesis 1:\n\n"
        "## Risks\n\n"
        "- Risk 1:\n\n"
        "## Expected impact\n\n"
        "- Metric impact expectations:\n"
    )

