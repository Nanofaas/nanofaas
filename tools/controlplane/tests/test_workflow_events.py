from __future__ import annotations

from controlplane_tool.workflow.workflow_events import build_log_event, build_task_event, normalize_task_state
from controlplane_tool.workflow.workflow_models import WorkflowContext


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


def test_logged_process_output_preserves_parent_task_identity() -> None:
    event = build_log_event(
        flow_id="e2e.k8s_vm",
        task_id="images.build_core",
        parent_task_id="tests.run_k3s_curl_checks",
        line="docker push ok",
    )

    assert event.parent_task_id == "tests.run_k3s_curl_checks"


def test_logged_process_output_preserves_parent_task_identity_from_context() -> None:
    event = build_log_event(
        line="docker push ok",
        context=WorkflowContext(
            flow_id="e2e.k8s_vm",
            task_id="images.build_core",
            parent_task_id="tests.run_k3s_curl_checks",
        ),
    )

    assert event.task_id == "images.build_core"
    assert event.parent_task_id == "tests.run_k3s_curl_checks"


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


def test_build_task_event_leaves_root_level_parentless_without_parent_identity() -> None:
    event = build_task_event(
        kind="task.running",
        flow_id="e2e.k8s_vm",
        task_id="images.build_core",
        title="Building core images",
    )

    assert event.task_id == "images.build_core"
    assert event.parent_task_id is None


def test_normalize_task_state_preserves_parent_task_identity_from_arguments() -> None:
    event = normalize_task_state(
        flow_id="e2e.k8s_vm",
        task_id="vm.ensure_running",
        parent_task_id="tests.run_k3s_curl_checks",
        state_name="Completed",
    )

    assert event.kind == "task.completed"
    assert event.parent_task_id == "tests.run_k3s_curl_checks"


def test_normalize_task_state_preserves_parent_task_identity_from_context() -> None:
    event = normalize_task_state(
        flow_id="e2e.k8s_vm",
        task_id="vm.ensure_running",
        state_name="Completed",
        context=WorkflowContext(
            flow_id="e2e.k8s_vm",
            task_id="vm.ensure_running",
            parent_task_id="tests.run_k3s_curl_checks",
        ),
    )

    assert event.kind == "task.completed"
    assert event.parent_task_id == "tests.run_k3s_curl_checks"
