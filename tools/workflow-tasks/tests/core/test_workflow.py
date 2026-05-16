from __future__ import annotations

import pytest
from dataclasses import dataclass
from workflow_tasks.core.workflow import Workflow


@dataclass
class _OkTask:
    task_id: str
    title: str
    calls: list[str]

    def run(self) -> None:
        self.calls.append(self.task_id)


@dataclass
class _FailTask:
    task_id: str
    title: str
    calls: list[str]

    def run(self) -> None:
        self.calls.append(self.task_id)
        raise RuntimeError(f"{self.task_id} failed")


def test_workflow_runs_tasks_in_order() -> None:
    calls: list[str] = []
    workflow = Workflow(tasks=[
        _OkTask(task_id="a", title="A", calls=calls),
        _OkTask(task_id="b", title="B", calls=calls),
        _OkTask(task_id="c", title="C", calls=calls),
    ])
    workflow.run()
    assert calls == ["a", "b", "c"]


def test_workflow_stops_on_first_failure() -> None:
    calls: list[str] = []
    workflow = Workflow(tasks=[
        _OkTask(task_id="a", title="A", calls=calls),
        _FailTask(task_id="b", title="B", calls=calls),
        _OkTask(task_id="c", title="C", calls=calls),
    ])
    with pytest.raises(RuntimeError, match="b failed"):
        workflow.run()
    assert calls == ["a", "b"]
    assert "c" not in calls


def test_workflow_cleanup_tasks_always_run() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[
            _OkTask(task_id="a", title="A", calls=calls),
            _FailTask(task_id="b", title="B", calls=calls),
        ],
        cleanup_tasks=[
            _OkTask(task_id="cleanup", title="Cleanup", calls=calls),
        ],
    )
    with pytest.raises(RuntimeError, match="b failed"):
        workflow.run()
    assert "cleanup" in calls


def test_workflow_cleanup_runs_after_success_too() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[_OkTask(task_id="a", title="A", calls=calls)],
        cleanup_tasks=[_OkTask(task_id="cleanup", title="Cleanup", calls=calls)],
    )
    workflow.run()
    assert calls == ["a", "cleanup"]


def test_workflow_task_ids_includes_all_tasks() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[
            _OkTask(task_id="a", title="A", calls=calls),
            _OkTask(task_id="b", title="B", calls=calls),
        ],
        cleanup_tasks=[
            _OkTask(task_id="cleanup", title="Cleanup", calls=calls),
        ],
    )
    assert workflow.task_ids == ["a", "b", "cleanup"]


def test_workflow_cleanup_error_raised_after_main_error() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[_FailTask(task_id="main", title="Main", calls=calls)],
        cleanup_tasks=[_FailTask(task_id="cleanup", title="Cleanup", calls=calls)],
    )
    with pytest.raises(RuntimeError) as exc_info:
        workflow.run()
    assert "main failed" in str(exc_info.value)
    assert "cleanup failed" in str(exc_info.value)


def test_workflow_with_no_tasks_runs_cleanly() -> None:
    workflow = Workflow(tasks=[])
    workflow.run()  # should not raise


def test_workflow_cleanup_only_failure_raised() -> None:
    calls: list[str] = []
    workflow = Workflow(
        tasks=[_OkTask(task_id="a", title="A", calls=calls)],
        cleanup_tasks=[_FailTask(task_id="cleanup", title="Cleanup", calls=calls)],
    )
    with pytest.raises(RuntimeError, match="Cleanup failed"):
        workflow.run()
    assert calls == ["a", "cleanup"]
