from __future__ import annotations

from collections.abc import Callable, Generator
from contextlib import contextmanager
import time

import typer
from workflow_tasks.workflow.events import WorkflowEvent


class ConsoleProgressSink:
    def __init__(
        self,
        *,
        write: Callable[[str], object] = typer.echo,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._write = write
        self._clock = clock
        self._started: dict[str, float] = {}

    def emit(self, event: WorkflowEvent) -> None:
        task_id = event.task_id
        if task_id is None:
            return
        if event.kind == "task.running":
            self._started[task_id] = self._clock()
            self._write(f"[{task_id}] running  {event.title}")
            return
        if event.kind not in {"task.completed", "task.failed"}:
            return
        now = self._clock()
        elapsed = now - self._started.pop(task_id, now)
        status = "passed" if event.kind == "task.completed" else "failed"
        detail = f"  {event.detail}" if event.detail else ""
        self._write(f"[{task_id}] {status:<8} {elapsed:.1f}s{detail}")

    @contextmanager
    def status(self, label: str) -> Generator[None, None, None]:
        yield
