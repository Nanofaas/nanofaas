from __future__ import annotations

from contextlib import contextmanager
from datetime import UTC, datetime

import pytest

from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink


def test_workflow_context_defaults() -> None:
    ctx = WorkflowContext()
    assert ctx.flow_id == "interactive.console"
    assert ctx.flow_run_id is None
    assert ctx.task_id is None
    assert ctx.parent_task_id is None
    assert ctx.task_run_id is None


def test_workflow_context_is_frozen() -> None:
    ctx = WorkflowContext()
    with pytest.raises(AttributeError):
        ctx.flow_id = "x"  # type: ignore[misc]


def test_workflow_event_minimal_construction() -> None:
    event = WorkflowEvent(kind="task.completed", flow_id="f")
    assert event.kind == "task.completed"
    assert event.flow_id == "f"
    assert event.title == ""
    assert event.detail == ""
    assert event.stream == "stdout"
    assert event.line == ""
    assert isinstance(event.at, datetime)
    assert event.at.tzinfo is UTC


def test_workflow_event_is_frozen() -> None:
    event = WorkflowEvent(kind="x", flow_id="f")
    with pytest.raises(AttributeError):
        event.kind = "y"  # type: ignore[misc]


def test_fake_sink_satisfies_workflow_sink_protocol() -> None:
    class _FakeSink:
        def __init__(self) -> None:
            self.events: list[WorkflowEvent] = []

        def emit(self, event: WorkflowEvent) -> None:
            self.events.append(event)

        @contextmanager
        def status(self, label: str):
            yield

    sink: WorkflowSink = _FakeSink()
    sink.emit(WorkflowEvent(kind="task.completed", flow_id="f"))
    with sink.status("loading"):
        pass
    assert sink.events[0].kind == "task.completed"  # type: ignore[attr-defined]
