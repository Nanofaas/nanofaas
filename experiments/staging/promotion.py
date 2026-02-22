from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import tempfile

import yaml


def promote_candidate_to_baseline(root: Path, candidate_slug: str, campaign_id: str) -> None:
    versions_dir = root / "versions"
    candidate_file = versions_dir / candidate_slug / "version.yaml"
    if not candidate_file.is_file():
        raise ValueError(f"Candidate version not found: {candidate_slug}")

    candidate = _load(candidate_file)
    if candidate.get("status") != "candidate":
        raise ValueError(f"Version '{candidate_slug}' must be in 'candidate' status")

    baseline_files = _find_baseline_files(versions_dir)
    if len(baseline_files) != 1:
        raise ValueError("Expected exactly one baseline version")

    baseline_file = baseline_files[0]
    baseline = _load(baseline_file)
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    baseline["status"] = "archived-baseline"
    baseline["archived_at"] = now
    _atomic_write_yaml(baseline_file, baseline)

    candidate["status"] = "baseline"
    candidate["promoted_by_campaign"] = campaign_id
    candidate["promoted_at"] = now
    _atomic_write_yaml(candidate_file, candidate)


def _find_baseline_files(versions_dir: Path) -> list[Path]:
    baseline_files: list[Path] = []
    for path in versions_dir.glob("*/version.yaml"):
        payload = _load(path)
        if payload.get("status") == "baseline":
            baseline_files.append(path)
    return baseline_files


def _load(path: Path) -> dict:
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(payload, dict):
        raise ValueError(f"Invalid metadata file: {path}")
    return payload


def _atomic_write_yaml(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        delete=False,
        dir=str(path.parent),
        prefix=f".{path.name}.",
        suffix=".tmp",
    ) as handle:
        yaml.safe_dump(payload, handle, sort_keys=False)
        temp_name = handle.name
    Path(temp_name).replace(path)

