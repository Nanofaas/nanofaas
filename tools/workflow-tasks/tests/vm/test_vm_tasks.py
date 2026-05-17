from __future__ import annotations

from dataclasses import dataclass
from unittest.mock import MagicMock

import pytest

from workflow_tasks.vm.models import VmConfig, VmInfo
from workflow_tasks.vm.tasks import DestroyVm, EnsureVmRunning


@dataclass
class _FakeLifecycle:
    vm_info: VmInfo
    destroyed: list[VmInfo]

    def ensure_running(self, config: VmConfig) -> VmInfo:
        return self.vm_info

    def destroy(self, info: VmInfo) -> None:
        self.destroyed.append(info)


def _make_lifecycle(name: str = "test-vm") -> _FakeLifecycle:
    return _FakeLifecycle(
        vm_info=VmInfo(name=name, host="10.0.0.1", user="ubuntu", home="/home/ubuntu"),
        destroyed=[],
    )


def test_ensure_vm_running_returns_vm_info() -> None:
    lifecycle = _make_lifecycle("my-vm")
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM running",
        lifecycle=lifecycle,
        config=VmConfig(name="my-vm"),
    )
    info = task.run()
    assert info.name == "my-vm"
    assert info.host == "10.0.0.1"


def test_ensure_vm_running_satisfies_task_protocol() -> None:
    from workflow_tasks.core.task import Task

    lifecycle = _make_lifecycle()
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM running",
        lifecycle=lifecycle,
        config=VmConfig(name="my-vm"),
    )
    assert isinstance(task, Task)


def test_destroy_vm_calls_lifecycle_destroy() -> None:
    lifecycle = _make_lifecycle()
    info = VmInfo(name="my-vm", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")
    task = DestroyVm(task_id="vm.destroy", title="Destroy VM", lifecycle=lifecycle, info=info)
    task.run()
    assert info in lifecycle.destroyed


def test_destroy_vm_satisfies_task_protocol() -> None:
    from workflow_tasks.core.task import Task

    lifecycle = _make_lifecycle()
    info = VmInfo(name="my-vm", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")
    task = DestroyVm(task_id="vm.destroy", title="Destroy VM", lifecycle=lifecycle, info=info)
    assert isinstance(task, Task)
