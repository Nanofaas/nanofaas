from __future__ import annotations

from dataclasses import dataclass

import pytest

from workflow_tasks.core.resource_task import ResourceTask
from workflow_tasks.core.workflow import Workflow


@dataclass
class _FailTask:
    task_id: str = "main.fail"
    title: str = "Fail"

    def run(self) -> None:
        raise RuntimeError("main failed")


def _resource(name: str, calls: list[str], *, infrastructure: bool = False) -> ResourceTask:
    return ResourceTask(
        task_id=f"acquire.{name}",
        title=f"Acquire {name}",
        acquire=lambda: calls.append(f"acquire.{name}"),
        release=lambda: calls.append(f"release.{name}"),
        infrastructure=infrastructure,
    )


def test_releases_acquired_resources_in_reverse_order() -> None:
    calls: list[str] = []

    Workflow(tasks=[_resource("first", calls), _resource("second", calls)]).run()

    assert calls == ["acquire.first", "acquire.second", "release.second", "release.first"]


def test_releases_only_resources_acquired_before_failure() -> None:
    calls: list[str] = []

    with pytest.raises(RuntimeError, match="main failed"):
        Workflow(tasks=[_resource("first", calls), _FailTask(), _resource("never", calls)]).run()

    assert calls == ["acquire.first", "release.first"]


def test_preserves_primary_error_when_release_fails() -> None:
    def fail_release() -> None:
        raise RuntimeError("release failed")

    resource = ResourceTask(
        task_id="acquire.resource",
        title="Acquire resource",
        acquire=lambda: None,
        release=fail_release,
    )

    with pytest.raises(RuntimeError) as exc_info:
        Workflow(tasks=[resource, _FailTask()]).run()

    assert "main failed" in str(exc_info.value)
    assert "release failed" in str(exc_info.value)


def test_keep_infrastructure_still_releases_safety_resources() -> None:
    calls: list[str] = []

    Workflow(
        tasks=[
            _resource("vm", calls, infrastructure=True),
            _resource("port-forward", calls),
        ],
        keep_infrastructure=True,
    ).run()

    assert calls == ["acquire.vm", "acquire.port-forward", "release.port-forward"]
