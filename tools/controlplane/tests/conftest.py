from __future__ import annotations

from contextlib import contextmanager

import pytest

from controlplane_tool.workflow_models import WorkflowEvent


class FakeSink:
    """Shared test double for WorkflowSink — records emitted events and status calls."""

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
