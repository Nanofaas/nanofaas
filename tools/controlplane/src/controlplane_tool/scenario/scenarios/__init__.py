from __future__ import annotations

from typing import Protocol, runtime_checkable


@runtime_checkable
class ScenarioPlan(Protocol):
    """Protocol for scenario execution plans.

    Concrete implementations carry the steps (or tasks) for a scenario
    and know how to execute themselves. The task_ids property allows the
    TUI to display phases before execution (dry-run planning).
    """

    @property
    def task_ids(self) -> list[str]: ...

    def run(self) -> None: ...
