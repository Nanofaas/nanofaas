from pathlib import Path

import yaml

from experiments.staging.campaign import run_campaign


def _write_benchmark(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        yaml.safe_dump(
            {
                "function_profile": "all",
                "platform_modes": ["jvm", "native"],
                "k6": {"stages": "20s:10,20s:0"},
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )


def test_run_campaign_expands_expected_matrix_and_creates_deterministic_layout(tmp_path: Path):
    root = tmp_path / "control-plane-staging"
    benchmark_path = root / "benchmark" / "benchmark.yaml"
    _write_benchmark(benchmark_path)
    observed_cells: list[tuple[int, str, str]] = []

    def fake_executor(cell):
        observed_cells.append((cell.run_index, cell.version_slug, cell.platform_mode))
        return {"p95": 100.0 + cell.run_index}

    campaign = run_campaign(
        root=root,
        campaign_id="cmp-001",
        benchmark_path=benchmark_path,
        baseline_slug="baseline-main",
        candidate_slug="opt-v2",
        runs=2,
        platform_modes=("jvm", "native"),
        executor=fake_executor,
    )

    assert observed_cells == [
        (1, "baseline-main", "jvm"),
        (1, "opt-v2", "jvm"),
        (1, "baseline-main", "native"),
        (1, "opt-v2", "native"),
        (2, "baseline-main", "jvm"),
        (2, "opt-v2", "jvm"),
        (2, "baseline-main", "native"),
        (2, "opt-v2", "native"),
    ]

    run_cell = root / "campaigns" / "cmp-001" / "runs" / "run-001" / "baseline-main__jvm"
    assert run_cell.is_dir()
    assert (run_cell / "cell-summary.json").exists()
    assert campaign.cells_executed == 8


def test_run_campaign_copies_benchmark_and_pins_hash(tmp_path: Path):
    root = tmp_path / "control-plane-staging"
    benchmark_path = root / "benchmark" / "benchmark.yaml"
    _write_benchmark(benchmark_path)

    campaign = run_campaign(
        root=root,
        campaign_id="cmp-002",
        benchmark_path=benchmark_path,
        baseline_slug="baseline-main",
        candidate_slug="opt-v2",
        runs=1,
        platform_modes=("jvm", "native"),
        executor=lambda cell: {"p99": 42.0},
    )

    copied = root / "campaigns" / "cmp-002" / "benchmark" / "benchmark.yaml"
    assert copied.exists()
    assert campaign.benchmark_hash
    metadata = (root / "campaigns" / "cmp-002" / "campaign.json").read_text(encoding="utf-8")
    assert campaign.benchmark_hash in metadata
