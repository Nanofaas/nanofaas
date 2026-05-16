from __future__ import annotations

import pytest
from unittest.mock import MagicMock
from controlplane_tool.scenario.tasks.vm import (
    EnsureVmRunning,
    ProvisionBase,
    SyncProject,
    TeardownVm,
)
from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.infra.vm.vm_models import VmRequest


def _ok() -> ShellExecutionResult:
    return ShellExecutionResult(command=["echo", "ok"], return_code=0, stdout="ok")


def _fail() -> ShellExecutionResult:
    return ShellExecutionResult(command=["echo", "fail"], return_code=1, stdout="", stderr="fail")


def _vm_request() -> VmRequest:
    return VmRequest(lifecycle="multipass", name="test-vm")


def test_ensure_vm_running_task_id_and_title() -> None:
    vm = MagicMock()
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM is running",
        vm=vm,
        request=_vm_request(),
    )
    assert task.task_id == "vm.ensure_running"
    assert task.title == "Ensure VM is running"


def test_ensure_vm_running_calls_vm_ensure_running() -> None:
    vm = MagicMock()
    vm.ensure_running.return_value = _ok()
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM is running",
        vm=vm,
        request=_vm_request(),
    )
    task.run()
    vm.ensure_running.assert_called_once_with(_vm_request())


def test_ensure_vm_running_raises_on_failure() -> None:
    vm = MagicMock()
    vm.ensure_running.return_value = _fail()
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM is running",
        vm=vm,
        request=_vm_request(),
    )
    with pytest.raises(RuntimeError, match="fail"):
        task.run()


def test_provision_base_calls_install_dependencies() -> None:
    vm = MagicMock()
    vm.install_dependencies.return_value = _ok()
    task = ProvisionBase(
        task_id="vm.provision_base",
        title="Provision base dependencies",
        vm=vm,
        request=_vm_request(),
        install_helm=True,
    )
    task.run()
    vm.install_dependencies.assert_called_once_with(_vm_request(), install_helm=True)


def test_sync_project_calls_sync_project() -> None:
    vm = MagicMock()
    vm.sync_project.return_value = _ok()
    task = SyncProject(
        task_id="repo.sync_to_vm",
        title="Sync project to VM",
        vm=vm,
        request=_vm_request(),
    )
    task.run()
    vm.sync_project.assert_called_once_with(_vm_request())


def test_teardown_vm_calls_teardown() -> None:
    vm = MagicMock()
    vm.teardown.return_value = _ok()
    task = TeardownVm(
        task_id="vm.down",
        title="Teardown VM",
        vm=vm,
        request=_vm_request(),
    )
    task.run()
    vm.teardown.assert_called_once_with(_vm_request())


def test_teardown_vm_is_satisfied_by_task_protocol() -> None:
    from workflow_tasks.core.task import Task
    vm = MagicMock()
    vm.teardown.return_value = _ok()
    task = TeardownVm(task_id="vm.down", title="Teardown VM", vm=vm, request=_vm_request())
    assert isinstance(task, Task)
