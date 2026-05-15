from __future__ import annotations

from contextlib import contextmanager

import pytest

from workflow_tasks.workflow.context import bind_workflow_context, bind_workflow_sink
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent
from workflow_tasks.workflow.reporting import (
    fail, phase, skip, status, step, success, warning, workflow_log, workflow_step,
)


class _FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []
        self.status_labels: list[str] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        self.status_labels.append(label)
        yield


def test_step_emits_task_running_to_sink() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        step("Deploy VM")
    assert len(sink.events) == 1
    assert sink.events[0].kind == "task.running"
    assert sink.events[0].title == "Deploy VM"


def test_success_emits_task_completed_to_sink() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        success("Deploy VM", detail="took 3s")
    assert sink.events[0].kind == "task.completed"
    assert sink.events[0].detail == "took 3s"


def test_fail_emits_task_failed_to_sink() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        fail("Deploy VM", detail="timeout")
    assert sink.events[0].kind == "task.failed"


def test_phase_emits_phase_started() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        phase("Provisioning")
    assert sink.events[0].kind == "phase.started"
    assert sink.events[0].title == "Provisioning"


def test_warning_emits_task_warning() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        warning("Low disk space")
    assert sink.events[0].kind == "task.warning"


def test_skip_emits_task_skipped() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        skip("Optional step")
    assert sink.events[0].kind == "task.skipped"


def test_workflow_log_emits_log_line() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        workflow_log("hello")
    assert sink.events[0].kind == "log.line"
    assert sink.events[0].line == "hello"


def test_status_delegates_to_sink_status() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        with status("loading"):
            pass
    assert sink.status_labels == ["loading"]


def test_status_is_noop_without_sink() -> None:
    with status("loading"):
        pass  # no error, no crash


def test_helpers_are_noop_without_sink() -> None:
    step("no sink")
    success("no sink")
    fail("no sink")
    phase("no sink")


def test_workflow_step_emits_running_then_completed() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        with workflow_step(task_id="vm.up", title="Start VM"):
            pass
    assert [e.kind for e in sink.events] == ["task.running", "task.completed"]
    assert all(e.task_id == "vm.up" for e in sink.events)


def test_workflow_step_emits_failed_on_exception() -> None:
    sink = _FakeSink()
    with bind_workflow_sink(sink):
        with pytest.raises(RuntimeError, match="boom"):
            with workflow_step(task_id="vm.up", title="Start VM"):
                raise RuntimeError("boom")
    assert [e.kind for e in sink.events] == ["task.running", "task.failed"]


def test_workflow_step_propagates_parent_task_id_from_context() -> None:
    sink = _FakeSink()
    ctx = WorkflowContext(flow_id="e2e.k3s", task_id="tests.run_checks")
    with bind_workflow_sink(sink), bind_workflow_context(ctx):
        with workflow_step(task_id="verify.health", title="Verifying health"):
            pass
    assert all(e.parent_task_id == "tests.run_checks" for e in sink.events)
