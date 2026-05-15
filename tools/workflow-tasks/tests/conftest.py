from __future__ import annotations

from contextlib import contextmanager

import pytest

from workflow_tasks.workflow.events import WorkflowEvent


class FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []
        self.status_events: list[tuple[str, str]] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        self.status_events.append(("start", label))
        try:
            yield
        finally:
            self.status_events.append(("end", label))


@pytest.fixture
def fake_sink() -> FakeSink:
    return FakeSink()
