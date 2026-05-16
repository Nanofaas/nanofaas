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
class InstallK3s:
    task_id: str
    title: str
    vm: VmOrchestrator
    request: VmRequest

    def run(self) -> None:
        _check(self.vm.install_k3s(self.request))


@dataclass
class EnsureRegistry:
    task_id: str
    title: str
    vm: VmOrchestrator
    request: VmRequest
    registry: str = "localhost:5000"
    container_name: str = "nanofaas-e2e-registry"

    def run(self) -> None:
        _check(self.vm.ensure_registry_container(
            self.request,
            registry=self.registry,
            container_name=self.container_name,
        ))


@dataclass
class ConfigureK3sRegistry:
    task_id: str
    title: str
    vm: VmOrchestrator
    request: VmRequest
    registry: str = "localhost:5000"

    def run(self) -> None:
        _check(self.vm.configure_k3s_registry(self.request, registry=self.registry))


@dataclass
class HelmInstall:
    """Run a remote helm command via exec_argv on the VM."""

    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    argv: tuple[str, ...]

    def run(self) -> None:
        _check(self.vm.exec_argv(self.request, self.argv))


@dataclass
class HelmUninstall:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    argv: tuple[str, ...]

    def run(self) -> None:
        _check(self.vm.exec_argv(self.request, self.argv))


@dataclass
class NamespaceInstall:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    argv: tuple[str, ...]

    def run(self) -> None:
        _check(self.vm.exec_argv(self.request, self.argv))
