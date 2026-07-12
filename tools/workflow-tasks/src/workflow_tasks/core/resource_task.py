from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field


@dataclass(slots=True)
class ResourceTask:
    task_id: str
    title: str
    acquire: Callable[[], None] = field(repr=False)
    release: Callable[[], None] = field(repr=False)
    infrastructure: bool = False

    @property
    def cleanup_task_id(self) -> str:
        return f"{self.task_id}.cleanup"

    @property
    def cleanup_title(self) -> str:
        return f"Release {self.title.removeprefix('Acquire ')}"

    def run(self) -> None:
        self.acquire()

    def cleanup(self) -> None:
        self.release()
