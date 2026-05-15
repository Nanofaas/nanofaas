from __future__ import annotations

from workflow_tasks.workflow.models import TaskDefinition, TaskRun, WorkflowRun, WorkflowState


def test_workflow_run_defaults() -> None:
    run = WorkflowRun(flow_id="e2e.k3s", flow_run_id="run-1")
    assert run.status == "pending"
    assert run.orchestrator_backend == "none"
    assert run.started_at is None


def test_task_definition_defaults() -> None:
    td = TaskDefinition(task_id="vm.ensure_running")
    assert td.title == ""
    assert td.detail == ""


def test_task_run_defaults() -> None:
    tr = TaskRun(flow_id="e2e.k3s", task_id="vm.ensure_running", task_run_id="tr-1")
    assert tr.status == "pending"
    assert tr.title == ""


def test_workflow_state_values() -> None:
    from typing import get_args
    assert set(get_args(WorkflowState)) == {"pending", "running", "success", "failed", "cancelled"}
