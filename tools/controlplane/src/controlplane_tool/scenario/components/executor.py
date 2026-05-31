from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field


@dataclass(frozen=True)
class ScenarioPlanStep:
    summary: str
    command: list[str]
    env: dict[str, str] = field(default_factory=dict)
    step_id: str = ""
    action: Callable[[], None] | None = None
    always_run: bool = False
