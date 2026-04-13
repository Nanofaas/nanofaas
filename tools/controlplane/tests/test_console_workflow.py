from contextlib import contextmanager
from threading import Thread

import pytest

from controlplane_tool.console import (
    _render_event,
    bind_workflow_context,
    bind_workflow_sink,
    fail,
    phase,
    skip,
    status,
    step,
    success,
    workflow_step,
    warning,
    workflow_log,
)
from controlplane_tool.workflow_events import build_log_event, build_task_event, normalize_task_state
from controlplane_tool.workflow_models import WorkflowContext, WorkflowEvent


class _FakeSink:
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


def test_bind_workflow_sink_routes_console_helpers() -> None:
    sink = _FakeSink()

    with bind_workflow_sink(sink), bind_workflow_context(WorkflowContext(flow_id="workflow.console")):
        phase("Build")
        step("Compile", "profile=k8s")
        warning("Using cached dependencies")
        skip("Skip optional image build")
        with status("Waiting for readiness"):
            pass
        success("Workflow completed", "exit code 0")
        fail("Workflow failed", "exit code 1")

    assert [event.kind for event in sink.events] == [
        "phase.started",
        "task.running",
        "task.warning",
        "task.skipped",
        "task.completed",
        "task.failed",
    ]
    assert sink.events[1].title == "Compile"
    assert sink.events[1].detail == "profile=k8s"
    assert sink.status_events == [
        ("start", "Waiting for readiness"),
        ("end", "Waiting for readiness"),
    ]


def test_workflow_log_from_background_thread_uses_bound_sink() -> None:
    sink = _FakeSink()
    context = WorkflowContext(
        flow_id="e2e.k8s_vm",
        task_id="images.build_core",
        task_run_id="task-run-123",
    )

    with bind_workflow_sink(sink), bind_workflow_context(context):
        thread = Thread(target=lambda: workflow_log("stream line", stream="stdout"))
        thread.start()
        thread.join()

    assert len(sink.events) == 1
    assert sink.events[0].kind == "log.line"
    assert sink.events[0].task_id == "images.build_core"
    assert sink.events[0].task_run_id == "task-run-123"
    assert sink.events[0].line == "stream line"


def test_render_event_shows_cancelled_tasks(capsys) -> None:
    _render_event(
        normalize_task_state(
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            state_name="Cancelled",
            title="Build core images",
        )
    )

    captured = capsys.readouterr()
    assert "Build core images" in captured.out


def test_render_event_shows_updated_tasks_and_log_lines(capsys) -> None:
    _render_event(
        build_task_event(
            kind="task.updated",
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            title="Build core images",
            detail="50%",
        )
    )
    _render_event(
        build_log_event(
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            line="docker push ok",
            stream="stderr",
        )
    )

    captured = capsys.readouterr()
    assert "Build core images" in captured.out
    assert "50%" in captured.out
    assert "stderr" in captured.out
    assert "docker push ok" in captured.out


def test_nested_console_steps_preserve_explicit_parent_identity() -> None:
    sink = _FakeSink()
    context = WorkflowContext(flow_id="workflow.console", task_id="tests.run_k3s_curl_checks")

    with bind_workflow_sink(sink), bind_workflow_context(context):
        step("Verify")
        step("Verify")

    assert [event.task_id for event in sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]
    assert [event.parent_task_id for event in sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]


def test_workflow_step_emits_balanced_child_events() -> None:
    sink = _FakeSink()
    context = WorkflowContext(
        flow_id="workflow.console",
        task_id="tests.run_k3s_curl_checks",
    )

    with bind_workflow_sink(sink), bind_workflow_context(context):
        with workflow_step(
            task_id="verify.control_plane_health",
            title="Verifying control-plane health",
        ):
            workflow_log("checking readiness")

    assert [event.kind for event in sink.events] == [
        "task.running",
        "log.line",
        "task.completed",
    ]
    assert [event.task_id for event in sink.events] == [
        "verify.control_plane_health",
        "verify.control_plane_health",
        "verify.control_plane_health",
    ]
    assert [event.parent_task_id for event in sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]


def test_workflow_step_emits_failed_child_events() -> None:
    sink = _FakeSink()
    context = WorkflowContext(
        flow_id="workflow.console",
        task_id="tests.run_k3s_curl_checks",
    )

    with bind_workflow_sink(sink), bind_workflow_context(context):
        with pytest.raises(RuntimeError, match="boom"):
            with workflow_step(
                task_id="verify.control_plane_health",
                title="Verifying control-plane health",
            ):
                raise RuntimeError("boom")

    assert [event.kind for event in sink.events] == [
        "task.running",
        "task.failed",
    ]
    assert [event.task_id for event in sink.events] == [
        "verify.control_plane_health",
        "verify.control_plane_health",
    ]
    assert [event.parent_task_id for event in sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]
