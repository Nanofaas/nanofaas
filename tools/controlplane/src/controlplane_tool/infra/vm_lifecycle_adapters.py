from __future__ import annotations

from typing import TYPE_CHECKING

from workflow_tasks.vm.models import VmConfig, VmInfo

from controlplane_tool.infra.vm.vm_models import VmRequest

if TYPE_CHECKING:
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
    from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator


class VmLifecycleAdapter:
    """Implements VmLifecycle Protocol for any orchestrator, parameterised by lifecycle string."""

    def __init__(self, orchestrator: "VmOrchestrator | AzureVmOrchestrator", *, lifecycle: str) -> None:
        self._vm = orchestrator
        self._lifecycle = lifecycle

    def ensure_running(self, config: VmConfig) -> VmInfo:
        request = VmRequest(
            lifecycle=self._lifecycle,
            name=config.name,
            cpus=config.cpus,
            memory=config.memory,
            disk=config.disk,
        )
        self._vm.ensure_running(request)
        host = self._vm.connection_host(request)
        return VmInfo(name=config.name, host=host, user="ubuntu", home="/home/ubuntu")

    def destroy(self, info: VmInfo) -> None:
        request = VmRequest(lifecycle=self._lifecycle, name=info.name)
        self._vm.teardown(request)


def MultipassVmAdapter(orchestrator: "VmOrchestrator") -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="multipass")


def AzureVmAdapter(orchestrator: "AzureVmOrchestrator") -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="azure")
