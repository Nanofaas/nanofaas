from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
from typing import Callable


@dataclass(frozen=True)
class CacheDecision:
    rebuild: bool
    reason: str
    entry: dict | None = None


def load_image_manifest(path: Path) -> dict:
    if not path.exists():
        return {"modes": {}}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("Image manifest must be a JSON object")
    modes = payload.get("modes")
    if not isinstance(modes, dict):
        raise ValueError("Image manifest must contain 'modes' object")
    return payload


def save_image_manifest(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def evaluate_image_cache(
    manifest_path: Path,
    mode: str,
    build_fingerprint: str,
    snapshot_fingerprint: str,
    image_id_lookup: Callable[[str], str | None],
    force_rebuild_images: bool = False,
    force_rebuild_modes: set[str] | None = None,
) -> CacheDecision:
    if force_rebuild_images:
        return CacheDecision(rebuild=True, reason="forced-all")

    force_rebuild_modes = force_rebuild_modes or set()
    if mode in force_rebuild_modes:
        return CacheDecision(rebuild=True, reason="forced-mode")

    manifest = load_image_manifest(manifest_path)
    entry = manifest.get("modes", {}).get(mode)
    if not isinstance(entry, dict):
        return CacheDecision(rebuild=True, reason="mode-missing")

    if entry.get("build_fingerprint") != build_fingerprint:
        return CacheDecision(rebuild=True, reason="build-fingerprint-mismatch")
    if entry.get("snapshot_fingerprint") != snapshot_fingerprint:
        return CacheDecision(rebuild=True, reason="snapshot-fingerprint-mismatch")

    image_ref = str(entry.get("image_ref", "")).strip()
    image_id = str(entry.get("image_id", "")).strip()
    if not image_ref or not image_id:
        return CacheDecision(rebuild=True, reason="manifest-entry-incomplete")

    current_image_id = image_id_lookup(image_ref)
    if current_image_id != image_id:
        return CacheDecision(rebuild=True, reason="image-id-mismatch")

    return CacheDecision(rebuild=False, reason="cache-hit", entry=entry)


def fingerprint_directory(root: Path) -> str:
    hasher = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel_path = path.relative_to(root).as_posix()
        hasher.update(rel_path.encode("utf-8"))
        hasher.update(b"\x00")
        hasher.update(path.read_bytes())
        hasher.update(b"\x00")
    return hasher.hexdigest()


def fingerprint_build_inputs(parts: list[str]) -> str:
    hasher = hashlib.sha256()
    for part in parts:
        hasher.update(part.encode("utf-8"))
        hasher.update(b"\x00")
    return hasher.hexdigest()

