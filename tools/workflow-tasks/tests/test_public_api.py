from __future__ import annotations

import workflow_tasks


def test_public_api_exports_task_types() -> None:
    assert hasattr(workflow_tasks, "CommandTaskSpec")
    assert hasattr(workflow_tasks, "TaskResult")
    assert hasattr(workflow_tasks, "TaskStatus")
    assert hasattr(workflow_tasks, "ExecutionTarget")


def test_public_api_exports_executors() -> None:
    assert hasattr(workflow_tasks, "HostCommandTaskExecutor")
    assert hasattr(workflow_tasks, "VmCommandTaskExecutor")


def test_public_api_exports_workflow_types() -> None:
    assert hasattr(workflow_tasks, "WorkflowEvent")
    assert hasattr(workflow_tasks, "WorkflowContext")
    assert hasattr(workflow_tasks, "WorkflowSink")


def test_public_api_exports_reporting_helpers() -> None:
    assert hasattr(workflow_tasks, "phase")
    assert hasattr(workflow_tasks, "step")
    assert hasattr(workflow_tasks, "success")
    assert hasattr(workflow_tasks, "fail")
    assert hasattr(workflow_tasks, "workflow_step")


def test_version_is_set() -> None:
    assert workflow_tasks.__version__ == "0.1.0"


def test_public_api_exports_task_and_workflow() -> None:
    assert hasattr(workflow_tasks, "Task")
    assert hasattr(workflow_tasks, "Workflow")
