from __future__ import annotations

from workflow_tasks.vm.models import VmConfig, VmInfo, VmRequest


class VmLifecycleAdapter:
    """Implements VmLifecycle Protocol for any VM orchestrator."""

    def __init__(self, orchestrator: object, *, lifecycle: str) -> None:
        self._vm = orchestrator
        self._lifecycle = lifecycle

    def ensure_running(self, config: VmConfig) -> VmInfo:
        request = VmRequest(
            lifecycle=self._lifecycle,  # type: ignore[arg-type]
            name=config.name,
            cpus=config.cpus,
            memory=config.memory,
            disk=config.disk,
        )
        self._vm.ensure_running(request)  # type: ignore[attr-defined]
        host = self._vm.connection_host(request)  # type: ignore[attr-defined]
        return VmInfo(name=config.name, host=host, user="ubuntu", home="/home/ubuntu")

    def destroy(self, info: VmInfo) -> None:
        request = VmRequest(lifecycle=self._lifecycle, name=info.name)  # type: ignore[arg-type]
        self._vm.teardown(request)  # type: ignore[attr-defined]


def MultipassVmAdapter(orchestrator: object) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="multipass")


def AzureVmAdapter(orchestrator: object) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="azure")


def ProxmoxVmAdapter(orchestrator: object) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="proxmox")
