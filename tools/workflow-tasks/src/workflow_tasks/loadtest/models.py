from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path


@dataclass(frozen=True)
class K6Stage:
    duration: str
    target: int


@dataclass(frozen=True)
class K6Config:
    script_path: Path
    target_url: str
    summary_output_path: Path
    stages: tuple[K6Stage, ...] = ()
    env: dict[str, str] = field(default_factory=dict)
    vus: int | None = None
    duration: str | None = None
    payload_path: Path | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "stages", tuple(self.stages))
        object.__setattr__(self, "env", dict(self.env))


@dataclass(frozen=True)
class K6RunResult:
    summary_path: Path
    started_at: datetime
    ended_at: datetime
    passed: bool


@dataclass(frozen=True)
class TimeWindow:
    start: datetime
    end: datetime


@dataclass(frozen=True)
class PrometheusQuery:
    name: str
    expr: str
    required: bool = False
