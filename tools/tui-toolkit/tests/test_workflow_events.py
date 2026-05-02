"""Tests for tui_toolkit.workflow — event builders and sink/context binding."""
from __future__ import annotations

from contextlib import contextmanager

import pytest
from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink
from tui_toolkit.workflow import (
    bind_workflow_context,
    bind_workflow_sink,
    build_log_event,
    build_phase_event,
    build_task_event,
    get_workflow_context,
    has_workflow_sink,
)


class FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        yield


def test_build_task_event_minimal():
    event = build_task_event(kind="task.completed", title="x")
    assert event.kind == "task.completed"
    assert event.title == "x"
    assert event.flow_id == "interactive.console"  # default WorkflowContext


def test_build_task_event_inherits_from_context():
    ctx = WorkflowContext(flow_id="my-flow", task_id="t1", parent_task_id="root")
    event = build_task_event(kind="task.running", title="run", context=ctx)
    assert event.flow_id == "my-flow"
    assert event.task_id == "t1"
    assert event.parent_task_id == "root"


def test_build_task_event_explicit_overrides_context():
    ctx = WorkflowContext(flow_id="ctx-flow", task_id="t1")
    event = build_task_event(kind="task.completed", task_id="t2", context=ctx)
    assert event.task_id == "t2"
    assert event.flow_id == "ctx-flow"


def test_build_task_event_falls_back_title_to_task_id():
    event = build_task_event(kind="task.completed", task_id="my-task")
    assert event.title == "my-task"


def test_build_phase_event():
    event = build_phase_event("Provisioning")
    assert event.kind == "phase.started"
    assert event.title == "Provisioning"


def test_build_log_event_default_stream_stdout():
    event = build_log_event(line="hello")
    assert event.kind == "log.line"
    assert event.line == "hello"
    assert event.stream == "stdout"


def test_build_log_event_stderr():
    event = build_log_event(line="boom", stream="stderr")
    assert event.stream == "stderr"


def test_get_workflow_context_default_is_none():
    assert get_workflow_context() is None


def test_bind_workflow_context_makes_it_visible():
    ctx = WorkflowContext(flow_id="bound")
    with bind_workflow_context(ctx):
        assert get_workflow_context() is ctx
    assert get_workflow_context() is None


def test_has_workflow_sink_default_false():
    assert has_workflow_sink() is False


def test_bind_workflow_sink_makes_it_visible():
    sink = FakeSink()
    with bind_workflow_sink(sink):
        assert has_workflow_sink() is True
    assert has_workflow_sink() is False
