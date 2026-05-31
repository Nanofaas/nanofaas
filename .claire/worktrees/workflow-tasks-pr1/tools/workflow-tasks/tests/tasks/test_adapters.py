from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType

from workflow_tasks.tasks.adapters import operation_to_task_spec


@dataclass(frozen=True)
class _FakeOperation:
    operation_id: str
    summary: str
    argv: tuple[str, ...]
    env: Mapping[str, str]
    execution_target: str


def test_operation_converts_to_task_spec() -> None:
    op = _FakeOperation(
        operation_id="images.build", summary="Build image",
        argv=("docker", "build", "."), env=MappingProxyType({"A": "B"}), execution_target="vm",
    )
    task = operation_to_task_spec(op)
    assert task.task_id == "images.build"
    assert task.summary == "Build image"
    assert task.argv == ("docker", "build", ".")
    assert task.env == {"A": "B"}
    assert task.target == "vm"


def test_host_operation_maps_to_host_target() -> None:
    op = _FakeOperation(
        operation_id="build.jar", summary="Build jar",
        argv=("./gradlew", "jar"), env=MappingProxyType({}), execution_target="host",
    )
    task = operation_to_task_spec(op)
    assert task.target == "host"
    assert task.remote_dir is None


def test_vm_operation_with_remote_dir() -> None:
    op = _FakeOperation(
        operation_id="vm.build", summary="VM build",
        argv=("make",), env=MappingProxyType({}), execution_target="vm",
    )
    task = operation_to_task_spec(op, remote_dir="/home/ubuntu/project")
    assert task.remote_dir == "/home/ubuntu/project"
