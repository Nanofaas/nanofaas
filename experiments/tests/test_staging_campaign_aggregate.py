from pathlib import Path
import json

from experiments.staging.report import aggregate_campaign_reports


def _write_cell(campaign_dir: Path, run_idx: int, version: str, mode: str, metrics: dict) -> None:
    cell_dir = campaign_dir / "runs" / f"run-{run_idx:03d}" / f"{version}__{mode}"
    cell_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "run_index": run_idx,
        "version_slug": version,
        "platform_mode": mode,
        "metrics": metrics,
    }
    (cell_dir / "cell-summary.json").write_text(
        json.dumps(payload, indent=2, sort_keys=True),
        encoding="utf-8",
    )


def test_aggregate_campaign_reports_computes_median_first_deltas(tmp_path: Path):
    campaign_dir = tmp_path / "campaigns" / "cmp-001"
    campaign_dir.mkdir(parents=True, exist_ok=True)
    (campaign_dir / "campaign.json").write_text(
        json.dumps({"baseline_slug": "base", "candidate_slug": "cand"}, indent=2),
        encoding="utf-8",
    )

    _write_cell(campaign_dir, 1, "base", "jvm", {"p95": 120, "throughput": 90})
    _write_cell(campaign_dir, 1, "cand", "jvm", {"p95": 100, "throughput": 100})
    _write_cell(campaign_dir, 2, "base", "jvm", {"p95": 140, "throughput": 92})
    _write_cell(campaign_dir, 2, "cand", "jvm", {"p95": 110, "throughput": 102})

    aggregate = aggregate_campaign_reports(campaign_dir)

    p95_row = next(row for row in aggregate["rows"] if row["platform_mode"] == "jvm" and row["metric"] == "p95")
    assert p95_row["baseline"]["median"] == 130.0
    assert p95_row["candidate"]["median"] == 105.0
    assert p95_row["delta"]["median"] == -25.0


def test_aggregate_campaign_reports_writes_json_and_markdown_outputs(tmp_path: Path):
    campaign_dir = tmp_path / "campaigns" / "cmp-002"
    campaign_dir.mkdir(parents=True, exist_ok=True)
    (campaign_dir / "campaign.json").write_text(
        json.dumps({"baseline_slug": "base", "candidate_slug": "cand"}, indent=2),
        encoding="utf-8",
    )

    _write_cell(campaign_dir, 1, "base", "native", {"p99": 200})
    _write_cell(campaign_dir, 1, "cand", "native", {"p99": 180})

    aggregate_campaign_reports(campaign_dir)

    json_out = campaign_dir / "aggregate-comparison.json"
    md_out = campaign_dir / "aggregate-comparison.md"
    assert json_out.exists()
    assert md_out.exists()
    markdown = md_out.read_text(encoding="utf-8")
    assert "| mode | metric | baseline median | candidate median | delta median |" in markdown
    assert "| native | p99 | 200.0 | 180.0 | -20.0 |" in markdown
