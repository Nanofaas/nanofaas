from __future__ import annotations

from dataclasses import dataclass

from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest

_VmRunner = VmOrchestrator | AzureVmOrchestrator


def _check(result: ShellExecutionResult) -> None:
    if result.return_code != 0:
        raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class EnsureVmRunning:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest

    def run(self) -> None:
        _check(self.vm.ensure_running(self.request))


@dataclass
class ProvisionBase:
    task_id: str
    title: str
    vm: VmOrchestrator
    request: VmRequest
    install_helm: bool = False

    def run(self) -> None:
        _check(self.vm.install_dependencies(self.request, install_helm=self.install_helm))


@dataclass
class SyncProject:
    task_id: str
    title: str
    vm: VmOrchestrator
    request: VmRequest

    def run(self) -> None:
        _check(self.vm.sync_project(self.request))


@dataclass
class TeardownVm:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest

    def run(self) -> None:
        _check(self.vm.teardown(self.request))
