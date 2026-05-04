from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class StepResult:
    name: str
    status: str
    detail: str
    duration_ms: int


@dataclass(frozen=True)
class RunResult:
    profile_name: str
    run_dir: Path
    final_status: str
    steps: list[StepResult]
