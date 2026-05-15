from __future__ import annotations

from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext


def test_build_task_event_minimal() -> None:
    event = build_task_event(kind="task.completed", title="x")
    assert event.kind == "task.completed"
    assert event.title == "x"
    assert event.flow_id == "interactive.console"


def test_build_task_event_inherits_from_context() -> None:
    ctx = WorkflowContext(flow_id="my-flow", task_id="t1", parent_task_id="root")
    event = build_task_event(kind="task.running", title="run", context=ctx)
    assert event.flow_id == "my-flow"
    assert event.task_id == "t1"
    assert event.parent_task_id == "root"


def test_build_task_event_explicit_overrides_context() -> None:
    ctx = WorkflowContext(flow_id="ctx-flow", task_id="t1")
    event = build_task_event(kind="task.completed", task_id="t2", context=ctx)
    assert event.task_id == "t2"
    assert event.flow_id == "ctx-flow"


def test_build_task_event_falls_back_title_to_task_id() -> None:
    event = build_task_event(kind="task.completed", task_id="my-task")
    assert event.title == "my-task"


def test_build_phase_event() -> None:
    event = build_phase_event("Provisioning")
    assert event.kind == "phase.started"
    assert event.title == "Provisioning"


def test_build_log_event_default_stream_stdout() -> None:
    event = build_log_event(line="hello")
    assert event.kind == "log.line"
    assert event.line == "hello"
    assert event.stream == "stdout"


def test_build_log_event_stderr() -> None:
    event = build_log_event(line="boom", stream="stderr")
    assert event.stream == "stderr"


def test_build_task_event_supports_parent_task_identity() -> None:
    event = build_task_event(
        kind="task.running", flow_id="e2e.k3s",
        task_id="verify.health", parent_task_id="tests.run_checks",
        title="Verifying health",
    )
    assert event.task_id == "verify.health"
    assert event.parent_task_id == "tests.run_checks"


def test_build_log_event_preserves_parent_from_context() -> None:
    event = build_log_event(
        line="ok",
        context=WorkflowContext(
            flow_id="e2e.k3s", task_id="images.build", parent_task_id="tests.run_checks",
        ),
    )
    assert event.task_id == "images.build"
    assert event.parent_task_id == "tests.run_checks"
