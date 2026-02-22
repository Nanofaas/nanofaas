from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import shutil
from typing import Callable


@dataclass(frozen=True)
class CampaignCell:
    run_index: int
    version_slug: str
    platform_mode: str
    run_dir: Path
    cell_dir: Path


@dataclass(frozen=True)
class CampaignResult:
    campaign_id: str
    campaign_dir: Path
    benchmark_hash: str
    cells_executed: int
    cell_summaries: tuple[dict, ...]


def run_campaign(
    root: Path,
    campaign_id: str,
    benchmark_path: Path,
    baseline_slug: str,
    candidate_slug: str,
    runs: int,
    platform_modes: tuple[str, ...],
    executor: Callable[[CampaignCell], dict],
) -> CampaignResult:
    if runs < 1:
        raise ValueError("runs must be >= 1")

    campaign_dir = root / "campaigns" / campaign_id
    campaign_dir.mkdir(parents=True, exist_ok=True)

    copied_benchmark = campaign_dir / "benchmark" / "benchmark.yaml"
    copied_benchmark.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(benchmark_path, copied_benchmark)
    benchmark_hash = _sha256_file(copied_benchmark)

    cell_summaries: list[dict] = []
    for run_index in range(1, runs + 1):
        run_dir = campaign_dir / "runs" / f"run-{run_index:03d}"
        run_dir.mkdir(parents=True, exist_ok=True)
        for platform_mode in platform_modes:
            for version_slug in (baseline_slug, candidate_slug):
                cell_dir = run_dir / f"{version_slug}__{platform_mode}"
                cell_dir.mkdir(parents=True, exist_ok=True)
                cell = CampaignCell(
                    run_index=run_index,
                    version_slug=version_slug,
                    platform_mode=platform_mode,
                    run_dir=run_dir,
                    cell_dir=cell_dir,
                )
                metrics = executor(cell)
                summary = {
                    "run_index": run_index,
                    "version_slug": version_slug,
                    "platform_mode": platform_mode,
                    "metrics": metrics,
                }
                (cell_dir / "cell-summary.json").write_text(
                    json.dumps(summary, indent=2, sort_keys=True),
                    encoding="utf-8",
                )
                cell_summaries.append(summary)

    campaign_metadata = {
        "campaign_id": campaign_id,
        "benchmark_path": str(copied_benchmark),
        "benchmark_hash": benchmark_hash,
        "runs": runs,
        "platform_modes": list(platform_modes),
        "baseline_slug": baseline_slug,
        "candidate_slug": candidate_slug,
        "cells_executed": len(cell_summaries),
    }
    (campaign_dir / "campaign.json").write_text(
        json.dumps(campaign_metadata, indent=2, sort_keys=True),
        encoding="utf-8",
    )

    return CampaignResult(
        campaign_id=campaign_id,
        campaign_dir=campaign_dir,
        benchmark_hash=benchmark_hash,
        cells_executed=len(cell_summaries),
        cell_summaries=tuple(cell_summaries),
    )


def _sha256_file(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            hasher.update(chunk)
    return hasher.hexdigest()

