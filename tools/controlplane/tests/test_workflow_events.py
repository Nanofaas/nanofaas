from __future__ import annotations

from controlplane_tool.workflow_events import build_log_event, build_task_event, normalize_task_state


def test_prefect_task_state_is_mapped_to_workflow_event() -> None:
    event = normalize_task_state(
        flow_id="e2e.k8s_vm",
        task_id="vm.ensure_running",
        state_name="Completed",
    )

    assert event.kind == "task.completed"
    assert event.task_id == "vm.ensure_running"


def test_logged_process_output_is_tagged_with_task_run_context() -> None:
    event = build_log_event(
        flow_id="e2e.k8s_vm",
        task_id="images.build_core",
        task_run_id="task-run-123",
        line="docker push ok",
    )

    assert event.kind == "log.line"
    assert event.task_id == "images.build_core"
    assert event.task_run_id == "task-run-123"
    assert event.line == "docker push ok"


def test_build_task_event_supports_parent_task_identity() -> None:
    event = build_task_event(
        kind="task.running",
        flow_id="e2e.k3s_junit_curl",
        task_id="verify.control_plane_health",
        parent_task_id="tests.run_k3s_curl_checks",
        title="Verifying control-plane health",
    )

    assert event.task_id == "verify.control_plane_health"
    assert event.parent_task_id == "tests.run_k3s_curl_checks"
