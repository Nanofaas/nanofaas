from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class Task(Protocol):
    """Protocol that all composable workflow tasks must satisfy.

    Implementations are typically dataclasses with explicit constructor
    parameters. The task_id must be stable across runs (used for TUI
    phase tracking and workflow_step context).
    """

    task_id: str
    title: str

    def run(self) -> Any: ...
