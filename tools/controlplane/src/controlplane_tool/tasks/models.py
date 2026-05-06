from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Literal

ExecutionTarget = Literal["host", "vm"]
TaskStatus = Literal["pending", "running", "passed", "failed", "skipped"]


@dataclass(frozen=True, slots=True)
class CommandTaskSpec:
    task_id: str
    summary: str
    argv: tuple[str, ...]
    target: ExecutionTarget = "host"
    env: Mapping[str, str] = field(default_factory=lambda: MappingProxyType({}))
    cwd: Path | None = None
    remote_dir: str | None = None
    expected_exit_codes: frozenset[int] = field(default_factory=lambda: frozenset({0}))
    timeout_seconds: int | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "env", MappingProxyType(dict(self.env)))


@dataclass(frozen=True, slots=True)
class TaskResult:
    task_id: str
    status: TaskStatus
    return_code: int | None = None
    expected_exit_codes: frozenset[int] = field(default_factory=lambda: frozenset({0}))
    stdout: str = ""
    stderr: str = ""

    @property
    def ok(self) -> bool:
        return self.status == "passed" and (
            self.return_code is None or self.return_code in self.expected_exit_codes
        )
