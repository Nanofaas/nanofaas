from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


REQUIRED_PLATFORM_MODES = ("jvm", "native")
ALLOWED_FUNCTION_PROFILES = ("all", "subset")


@dataclass(frozen=True)
class BenchmarkConfig:
    function_profile: str
    functions: tuple[str, ...]
    platform_modes: tuple[str, ...]
    raw: dict[str, Any]


def load_benchmark_config(path: Path) -> BenchmarkConfig:
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(payload, dict):
        raise ValueError("benchmark.yaml must be a mapping")

    function_profile = str(payload.get("function_profile", "all"))
    if function_profile not in ALLOWED_FUNCTION_PROFILES:
        raise ValueError(f"Unsupported function_profile: {function_profile}")

    functions = _normalize_functions(payload.get("functions", []), function_profile)
    platform_modes = _normalize_platform_modes(payload.get("platform_modes"))

    return BenchmarkConfig(
        function_profile=function_profile,
        functions=functions,
        platform_modes=platform_modes,
        raw=payload,
    )


def _normalize_functions(value: Any, function_profile: str) -> tuple[str, ...]:
    if function_profile == "all":
        return ()
    if not isinstance(value, list) or not value:
        raise ValueError("functions must be a non-empty list when function_profile=subset")
    return tuple(str(item) for item in value)


def _normalize_platform_modes(value: Any) -> tuple[str, ...]:
    if not isinstance(value, list) or not value:
        raise ValueError("platform_modes must be a non-empty list")
    modes = tuple(str(mode) for mode in value)
    if "jvm" not in modes or "native" not in modes:
        raise ValueError("platform_modes must include jvm and native")
    return modes

