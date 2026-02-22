from pathlib import Path

import pytest
import yaml

from experiments.staging.benchmark import BenchmarkConfig, load_benchmark_config


def _write(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")


def test_load_benchmark_defaults_function_profile_to_all(tmp_path: Path):
    benchmark_path = tmp_path / "benchmark" / "benchmark.yaml"
    _write(
        benchmark_path,
        {
            "platform_modes": ["jvm", "native"],
            "k6": {"stages": "20s:10,20s:0"},
        },
    )

    loaded = load_benchmark_config(benchmark_path)

    assert isinstance(loaded, BenchmarkConfig)
    assert loaded.function_profile == "all"
    assert loaded.platform_modes == ("jvm", "native")


def test_load_benchmark_rejects_platform_modes_missing_required_values(tmp_path: Path):
    benchmark_path = tmp_path / "benchmark" / "benchmark.yaml"
    _write(
        benchmark_path,
        {
            "function_profile": "all",
            "platform_modes": ["jvm"],
        },
    )

    with pytest.raises(ValueError, match="platform_modes must include jvm and native"):
        load_benchmark_config(benchmark_path)


def test_load_benchmark_rejects_malformed_mapping(tmp_path: Path):
    benchmark_path = tmp_path / "benchmark" / "benchmark.yaml"
    benchmark_path.parent.mkdir(parents=True, exist_ok=True)
    benchmark_path.write_text("- invalid\n- list\n", encoding="utf-8")

    with pytest.raises(ValueError, match="benchmark.yaml must be a mapping"):
        load_benchmark_config(benchmark_path)
