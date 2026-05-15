from __future__ import annotations

from contextlib import contextmanager

from workflow_tasks.workflow.context import (
    bind_workflow_context,
    bind_workflow_sink,
    get_workflow_context,
    has_workflow_sink,
)
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent


class _FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        yield


def test_get_workflow_context_default_is_none() -> None:
    assert get_workflow_context() is None


def test_bind_workflow_context_makes_it_visible() -> None:
    ctx = WorkflowContext(flow_id="bound")
    with bind_workflow_context(ctx):
        assert get_workflow_context() is ctx
    assert get_workflow_context() is None


def test_has_workflow_sink_default_false() -> None:
    assert has_workflow_sink() is False


def test_bind_workflow_sink_makes_it_visible() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        assert has_workflow_sink() is True
    assert has_workflow_sink() is False


def test_context_restores_after_nested_bind() -> None:
    outer = WorkflowContext(flow_id="outer")
    inner = WorkflowContext(flow_id="inner")
    with bind_workflow_context(outer):
        with bind_workflow_context(inner):
            assert get_workflow_context().flow_id == "inner"
        assert get_workflow_context().flow_id == "outer"
    assert get_workflow_context() is None
