from __future__ import annotations

import json
from pathlib import Path
from statistics import mean, median


METRIC_ORDER = ("p95", "p99", "fail_rate", "throughput", "heap_peak", "gc_pause")


def aggregate_campaign_reports(campaign_dir: Path) -> dict:
    metadata = json.loads((campaign_dir / "campaign.json").read_text(encoding="utf-8"))
    baseline_slug = metadata["baseline_slug"]
    candidate_slug = metadata["candidate_slug"]

    cell_summaries = _load_cell_summaries(campaign_dir)
    modes = sorted({summary["platform_mode"] for summary in cell_summaries})

    rows: list[dict] = []
    for mode in modes:
        for metric in METRIC_ORDER:
            baseline_values = _extract_metric_values(cell_summaries, baseline_slug, mode, metric)
            candidate_values = _extract_metric_values(cell_summaries, candidate_slug, mode, metric)
            if not baseline_values or not candidate_values:
                continue

            baseline_stats = _stats(baseline_values)
            candidate_stats = _stats(candidate_values)
            delta_stats = {
                "median": round(candidate_stats["median"] - baseline_stats["median"], 4),
                "mean": round(candidate_stats["mean"] - baseline_stats["mean"], 4),
                "min": round(candidate_stats["min"] - baseline_stats["min"], 4),
                "max": round(candidate_stats["max"] - baseline_stats["max"], 4),
            }

            rows.append(
                {
                    "platform_mode": mode,
                    "metric": metric,
                    "baseline": baseline_stats,
                    "candidate": candidate_stats,
                    "delta": delta_stats,
                }
            )

    aggregate = {
        "baseline_slug": baseline_slug,
        "candidate_slug": candidate_slug,
        "rows": rows,
    }

    (campaign_dir / "aggregate-comparison.json").write_text(
        json.dumps(aggregate, indent=2, sort_keys=True),
        encoding="utf-8",
    )
    (campaign_dir / "aggregate-comparison.md").write_text(
        _to_markdown(rows),
        encoding="utf-8",
    )
    return aggregate


def _load_cell_summaries(campaign_dir: Path) -> list[dict]:
    summaries: list[dict] = []
    for path in sorted(campaign_dir.glob("runs/run-*/**/cell-summary.json")):
        summaries.append(json.loads(path.read_text(encoding="utf-8")))
    return summaries


def _extract_metric_values(
    cell_summaries: list[dict],
    version_slug: str,
    platform_mode: str,
    metric: str,
) -> list[float]:
    values: list[float] = []
    for summary in cell_summaries:
        if summary.get("version_slug") != version_slug:
            continue
        if summary.get("platform_mode") != platform_mode:
            continue
        metrics = summary.get("metrics") or {}
        if metric in metrics:
            values.append(float(metrics[metric]))
    return values


def _stats(values: list[float]) -> dict:
    return {
        "median": round(float(median(values)), 4),
        "mean": round(float(mean(values)), 4),
        "min": round(float(min(values)), 4),
        "max": round(float(max(values)), 4),
    }


def _to_markdown(rows: list[dict]) -> str:
    lines = [
        "# Aggregate Comparison",
        "",
        "| mode | metric | baseline median | candidate median | delta median |",
        "| --- | --- | ---: | ---: | ---: |",
    ]
    for row in rows:
        lines.append(
            f"| {row['platform_mode']} | {row['metric']} | "
            f"{row['baseline']['median']} | {row['candidate']['median']} | {row['delta']['median']} |"
        )
    lines.append("")
    return "\n".join(lines)

