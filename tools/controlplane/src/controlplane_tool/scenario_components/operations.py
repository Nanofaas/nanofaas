from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class ScenarioOperation:
    operation_id: str
    summary: str


@dataclass(frozen=True, slots=True)
class RemoteCommandOperation(ScenarioOperation):
    argv: tuple[str, ...]
    env: Mapping[str, str] = field(default_factory=dict)
