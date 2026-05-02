from threading import Thread

import pytest

from tui_toolkit import (
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
from tui_toolkit.workflow import _render_event
from controlplane_tool.workflow_events import build_log_event, build_task_event, normalize_task_state
from controlplane_tool.workflow_models import WorkflowContext


def test_bind_workflow_sink_routes_console_helpers(fake_sink) -> None:
    with bind_workflow_sink(fake_sink), bind_workflow_context(WorkflowContext(flow_id="workflow.console")):
        phase("Build")
        step("Compile", "profile=k8s")
        warning("Using cached dependencies")
        skip("Skip optional image build")
        with status("Waiting for readiness"):
            pass
        success("Workflow completed", "exit code 0")
        fail("Workflow failed", "exit code 1")

    assert [event.kind for event in fake_sink.events] == [
        "phase.started",
        "task.running",
        "task.warning",
        "task.skipped",
        "task.completed",
        "task.failed",
    ]
    assert fake_sink.events[1].title == "Compile"
    assert fake_sink.events[1].detail == "profile=k8s"
    assert fake_sink.status_events == [
        ("start", "Waiting for readiness"),
        ("end", "Waiting for readiness"),
    ]


def test_phase_preserves_context_task_identity(fake_sink) -> None:
    context = WorkflowContext(
        flow_id="workflow.console",
        task_id="tests.run_k3s_curl_checks",
        parent_task_id="workflow.root",
        task_run_id="task-run-123",
    )

    with bind_workflow_sink(fake_sink), bind_workflow_context(context):
        phase("Build")

    assert len(fake_sink.events) == 1
    assert fake_sink.events[0].kind == "phase.started"
    assert fake_sink.events[0].task_id == "tests.run_k3s_curl_checks"
    assert fake_sink.events[0].parent_task_id == "workflow.root"
    assert fake_sink.events[0].task_run_id == "task-run-123"


def test_workflow_log_from_background_thread_uses_bound_sink(fake_sink) -> None:
    context = WorkflowContext(
        flow_id="e2e.k8s_vm",
        task_id="images.build_core",
        task_run_id="task-run-123",
    )

    with bind_workflow_sink(fake_sink), bind_workflow_context(context):
        thread = Thread(target=lambda: workflow_log("stream line", stream="stdout"))
        thread.start()
        thread.join()

    assert len(fake_sink.events) == 1
    assert fake_sink.events[0].kind == "log.line"
    assert fake_sink.events[0].task_id == "images.build_core"
    assert fake_sink.events[0].task_run_id == "task-run-123"
    assert fake_sink.events[0].line == "stream line"


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


def test_nested_console_steps_stay_parentless_without_explicit_parent_identity(fake_sink) -> None:
    context = WorkflowContext(flow_id="workflow.console", task_id="tests.run_k3s_curl_checks")

    with bind_workflow_sink(fake_sink), bind_workflow_context(context):
        step("Verify")
        step("Verify")

    assert [event.task_id for event in fake_sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]
    assert [event.parent_task_id for event in fake_sink.events] == [
        None,
        None,
    ]


def test_workflow_step_emits_balanced_child_events(fake_sink) -> None:
    context = WorkflowContext(
        flow_id="workflow.console",
        task_id="tests.run_k3s_curl_checks",
    )

    with bind_workflow_sink(fake_sink), bind_workflow_context(context):
        with workflow_step(
            task_id="verify.control_plane_health",
            title="Verifying control-plane health",
        ):
            workflow_log("checking readiness")

    assert [event.kind for event in fake_sink.events] == [
        "task.running",
        "log.line",
        "task.completed",
    ]
    assert [event.task_id for event in fake_sink.events] == [
        "verify.control_plane_health",
        "verify.control_plane_health",
        "verify.control_plane_health",
    ]
    assert [event.parent_task_id for event in fake_sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]


def test_workflow_step_emits_failed_child_events(fake_sink) -> None:
    context = WorkflowContext(
        flow_id="workflow.console",
        task_id="tests.run_k3s_curl_checks",
    )

    with bind_workflow_sink(fake_sink), bind_workflow_context(context):
        with pytest.raises(RuntimeError, match="boom"):
            with workflow_step(
                task_id="verify.control_plane_health",
                title="Verifying control-plane health",
            ):
                raise RuntimeError("boom")

    assert [event.kind for event in fake_sink.events] == [
        "task.running",
        "task.failed",
    ]
    assert [event.task_id for event in fake_sink.events] == [
        "verify.control_plane_health",
        "verify.control_plane_health",
    ]
    assert [event.parent_task_id for event in fake_sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]
