from __future__ import annotations

from typing import TYPE_CHECKING

from workflow_tasks.vm.models import VmConfig, VmInfo

if TYPE_CHECKING:
    from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator


class MultipassVmAdapter:
    """Implements VmLifecycle Protocol using VmOrchestrator (multipass lifecycle)."""

    def __init__(self, orchestrator: "VmOrchestrator") -> None:
        self._vm = orchestrator

    def ensure_running(self, config: VmConfig) -> VmInfo:
        from controlplane_tool.infra.vm.vm_models import VmRequest

        request = VmRequest(
            lifecycle="multipass",
            name=config.name,
            cpus=config.cpus,
            memory=config.memory,
            disk=config.disk,
        )
        self._vm.ensure_running(request)
        host = self._vm.connection_host(request)
        return VmInfo(
            name=config.name,
            host=host,
            user="ubuntu",
            home="/home/ubuntu",
        )

    def destroy(self, info: VmInfo) -> None:
        from controlplane_tool.infra.vm.vm_models import VmRequest

        request = VmRequest(lifecycle="multipass", name=info.name)
        self._vm.teardown(request)
